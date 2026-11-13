"""Download Cobblemon and the mods it requires, plus Palworks's own required mods (--require), from
Modrinth, for the CI server boot test.

These are the same jars players install, so the boot test runs Palworks against real releases.
Every download is checked against the SHA-512 that Modrinth publishes for the file. Network
hiccups (a dropped connection, a timeout, Modrinth busy or briefly down) are retried a few times,
waiting longer each time, so one doesn't fail the boot test; so is a download whose SHA-512 doesn't
match, in case it arrived cut short.

Usage:
  python3 fetch_server_mods.py --loader fabric --minecraft 1.21.1 --cobblemon 1.8.4 \
      --pin fabric-api=0.116.7+1.21.1 --require architectury-api --dest server/mods

--pin SLUG=VERSION picks an exact version of a dependency (matched against the Modrinth version
number); other required dependencies use their newest version for the loader and game version.
--require SLUG also downloads a mod Palworks itself requires (newest, unless pinned), if Cobblemon
didn't already bring it.
"""

import argparse
import hashlib
import http.client
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.modrinth.com/v2"
# Modrinth asks API clients to identify themselves.
USER_AGENT = "JohnBPerkins/Palworks-CI (github.com/JohnBPerkins/Palworks)"

# How many times to try a request, and how long to wait before the first retry (doubling after)
ATTEMPTS = 5
FIRST_WAIT_SECONDS = 2


class ChecksumMismatch(Exception):
    """A download's SHA-512 isn't Modrinth's: most likely cut short, so worth another try."""


def is_transient(error: Exception) -> bool:
    """Whether a failed request is worth trying again: the network or Modrinth, not the request."""
    if isinstance(error, urllib.error.HTTPError):
        return error.code == 408 or error.code == 429 or error.code >= 500
    return isinstance(
        error,
        (
            urllib.error.URLError,  # includes SSL errors and refused connections while connecting
            ssl.SSLError,
            http.client.HTTPException,  # IncompleteRead, RemoteDisconnected...
            ConnectionError,
            TimeoutError,
            ChecksumMismatch,
        ),
    )


def with_retries(what: str, attempt, attempts: int = ATTEMPTS, first_wait: float = FIRST_WAIT_SECONDS):
    """Run attempt(), trying again after a transient failure, waiting longer each time."""
    wait = first_wait
    for number in range(1, attempts + 1):
        try:
            return attempt()
        except Exception as error:  # noqa: BLE001 - sorted by is_transient
            if number == attempts or not is_transient(error):
                raise
            print(f"  {what} failed ({type(error).__name__}: {error}); trying again in {wait:g}s ({number}/{attempts - 1})", flush=True)
            time.sleep(wait)
            wait *= 2
    raise AssertionError("unreachable")


def get_json(path: str, **params: str) -> object:
    query = "?" + urllib.parse.urlencode(params) if params else ""
    request = urllib.request.Request(API + path + query, headers={"User-Agent": USER_AGENT})

    def attempt() -> object:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)

    return with_retries(f"GET {path}", attempt)


def versions_for(project: str, loader: str, minecraft: str) -> list:
    return get_json(
        f"/project/{project}/version",
        loaders=json.dumps([loader]),
        game_versions=json.dumps([minecraft]),
    )


def primary_file(version: dict) -> dict:
    files = version["files"]
    return next((f for f in files if f["primary"]), files[0])


def download(version: dict, dest: str) -> str:
    file = primary_file(version)
    target = os.path.join(dest, file["filename"])
    request = urllib.request.Request(file["url"], headers={"User-Agent": USER_AGENT})

    def attempt() -> None:
        digest = hashlib.sha512()
        with urllib.request.urlopen(request, timeout=300) as response, open(target, "wb") as out:
            while chunk := response.read(1 << 20):
                digest.update(chunk)
                out.write(chunk)
        if digest.hexdigest() != file["hashes"]["sha512"]:
            os.remove(target)
            raise ChecksumMismatch(f"SHA-512 mismatch for {file['filename']}")

    try:
        with_retries(f"Downloading {file['filename']}", attempt)
    except ChecksumMismatch:
        sys.exit(f"SHA-512 mismatch for {file['filename']}, every time; refusing to use it.")
    print(f"  {file['filename']}  ({file['size'] // 1024} KiB, sha512 ok)")
    return target


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--loader", required=True, choices=["fabric", "neoforge"])
    parser.add_argument("--minecraft", required=True)
    parser.add_argument("--cobblemon", required=True, help="e.g. 1.8.4")
    parser.add_argument("--pin", action="append", default=[], metavar="SLUG=VERSION")
    parser.add_argument("--require", action="append", default=[], metavar="SLUG")
    parser.add_argument("--dest", required=True)
    args = parser.parse_args()
    pins = dict(p.split("=", 1) for p in args.pin)
    os.makedirs(args.dest, exist_ok=True)

    # Cobblemon's Modrinth version numbers are inconsistent ("1.8.4F", "1.8.2", "1.7.1"), so match
    # on the release file name instead, e.g. Cobblemon-fabric-1.8.4+1.21.1.jar.
    prefix = f"Cobblemon-{args.loader}-{args.cobblemon}+"
    cobblemon = next(
        (v for v in versions_for("cobblemon", args.loader, args.minecraft)
         if primary_file(v)["filename"].startswith(prefix)),
        None,
    )
    if cobblemon is None:
        sys.exit(f"No Cobblemon {args.cobblemon} for {args.loader} {args.minecraft} on Modrinth.")

    print(f"Cobblemon {args.cobblemon} ({args.loader}, Minecraft {args.minecraft}):")
    download(cobblemon, args.dest)

    fetched = set()
    for dependency in cobblemon["dependencies"]:
        if dependency["dependency_type"] != "required" or not dependency.get("project_id"):
            continue
        slug = get_json(f"/project/{dependency['project_id']}")["slug"]
        if dependency.get("version_id"):
            version = get_json(f"/version/{dependency['version_id']}")
        else:
            version = newest(slug, args.loader, args.minecraft, pins)
        print(f"Required by Cobblemon: {slug} {version['version_number']}")
        download(version, args.dest)
        fetched.add(slug)

    for slug in args.require:
        if slug in fetched:
            continue
        version = newest(slug, args.loader, args.minecraft, pins)
        print(f"Required by Palworks: {slug} {version['version_number']}")
        download(version, args.dest)


def newest(slug: str, loader: str, minecraft: str, pins: dict) -> dict:
    """The newest version of a mod for this loader and game version, or its pinned one."""
    candidates = versions_for(slug, loader, minecraft)
    if slug in pins:
        candidates = [v for v in candidates if v["version_number"] == pins[slug]]
    if not candidates:
        wanted = f" {pins[slug]}" if slug in pins else ""
        sys.exit(f"No {slug}{wanted} for {loader} {minecraft} on Modrinth.")
    return candidates[0]  # Modrinth lists newest first

if __name__ == "__main__":
    main()
