"""Download Cobblemon and the mods it requires from Modrinth, for the CI server boot test.

These are the same jars players install, so the boot test runs Palworks against real releases.
Every download is checked against the SHA-512 that Modrinth publishes for the file.

Usage:
  python3 fetch_server_mods.py --loader fabric --minecraft 1.21.1 --cobblemon 1.8.4 \
      --pin fabric-api=0.116.7+1.21.1 --dest server/mods

--pin SLUG=VERSION picks an exact version of a dependency (matched against the Modrinth version
number); other required dependencies use their newest version for the loader and game version.
"""

import argparse
import hashlib
import json
import os
import sys
import urllib.parse
import urllib.request

API = "https://api.modrinth.com/v2"
# Modrinth asks API clients to identify themselves.
USER_AGENT = "JohnBPerkins/Palworks-CI (github.com/JohnBPerkins/Palworks)"


def get_json(path: str, **params: str) -> object:
    query = "?" + urllib.parse.urlencode(params) if params else ""
    request = urllib.request.Request(API + path + query, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


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
    digest = hashlib.sha512()
    with urllib.request.urlopen(request, timeout=300) as response, open(target, "wb") as out:
        while chunk := response.read(1 << 20):
            digest.update(chunk)
            out.write(chunk)
    if digest.hexdigest() != file["hashes"]["sha512"]:
        os.remove(target)
        sys.exit(f"SHA-512 mismatch for {file['filename']}; refusing to use it.")
    print(f"  {file['filename']}  ({file['size'] // 1024} KiB, sha512 ok)")
    return target


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--loader", required=True, choices=["fabric", "neoforge"])
    parser.add_argument("--minecraft", required=True)
    parser.add_argument("--cobblemon", required=True, help="e.g. 1.8.4")
    parser.add_argument("--pin", action="append", default=[], metavar="SLUG=VERSION")
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

    for dependency in cobblemon["dependencies"]:
        if dependency["dependency_type"] != "required" or not dependency.get("project_id"):
            continue
        slug = get_json(f"/project/{dependency['project_id']}")["slug"]
        if dependency.get("version_id"):
            version = get_json(f"/version/{dependency['version_id']}")
        else:
            candidates = versions_for(slug, args.loader, args.minecraft)
            if slug in pins:
                candidates = [v for v in candidates if v["version_number"] == pins[slug]]
            if not candidates:
                wanted = f" {pins[slug]}" if slug in pins else ""
                sys.exit(f"No {slug}{wanted} for {args.loader} {args.minecraft} on Modrinth.")
            version = candidates[0]  # Modrinth lists newest first
        print(f"Required by Cobblemon: {slug} {version['version_number']}")
        download(version, args.dest)


if __name__ == "__main__":
    main()
