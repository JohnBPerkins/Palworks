# Contributing to Palworks

Thanks for your interest in improving Palworks! This guide covers how to get set up, the
conventions the project follows, and how to get your changes merged.

By participating, you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md).

## Setup

Clone and build as described in the [README](../README.md#building-from-source) (JDK 21 + Git; the
Gradle wrapper provides the right Gradle version). The commands you'll use day to day:

```bash
./gradlew build          # builds both loaders -> fabric/build/libs/, neoforge/build/libs/
./gradlew :common:test   # runs the pure-logic unit tests
./gradlew clean          # if you hit Minecraft mapping errors
```

## Project layout

Palworks is a multi-loader mod built with [Architectury](https://architectury.dev):

| Module      | Contents                                                                   |
|-------------|----------------------------------------------------------------------------|
| `common/`   | Loader-agnostic core: worker AI, job system, suitability, world helpers.   |
| `fabric/`   | Fabric entry point, mixins, and registration.                              |
| `neoforge/` | NeoForge entry point and registration.                                     |

Most logic lives in `common/`. Put new logic there and keep `fabric/` and `neoforge/` limited to
the loader-specific seams (tick hooks, registration, menus).

## Testing and Cobblemon

Cobblemon is a **compile-only** dependency: the code compiles against its API, but Cobblemon
**cannot load in the dev environment**. This means:

- Pure logic (enums, mappings, suitability, IDs) is unit-tested under `common/src/test/java` and
  must pass `./gradlew :common:test`. New logic that doesn't need a running game belongs here.
- Behavior that touches Cobblemon or live world state must be tested **in an actual Minecraft
  instance** (Fabric+Cobblemon or NeoForge+Cobblemon), verified in-game.

## Conventions

- **Match the surrounding code.** Follow existing naming, structure, and style rather than
  introducing new patterns.
- **Keep changes surgical.** Touch only what the change requires; don't reformat or refactor
  unrelated code in the same PR.
- **Namespaced keys.** Persistent data and identifiers are namespaced `palworks:*`.
- **Debug logging** is gated behind a single flag and is off by default. Launch with
  `-Dpalworks.debug=true` to enable verbose logging while developing.
- **Data-driven where possible.** Things like which blocks count as work are controlled by
  datapack tags — prefer editing tags over hard-coding in Java.

## Pull-request process

1. **Open an issue first** for anything non-trivial so the approach can be discussed before you
   invest a lot of time.
2. Branch from `main` and keep your branch focused on a single change.
3. Make sure `./gradlew build` and `./gradlew :common:test` pass before pushing.
4. Open a PR against `main` with a title in the format below, and fill out the template,
   including how you tested (unit tests and/or what you verified in-game) and any related issue.

PRs need a maintainer's approval and a passing CI build, and are squash-merged into `main`.
Thanks for contributing!

### PR titles

**PR titles must follow [Conventional Commits](https://www.conventionalcommits.org/):**
`type: short description`, in lowercase and the imperative mood, with no trailing period.

```
feat: let Water-type workers water crops
fix: stop workers pathing to furnaces that were broken
docs: explain nether wart farming on the wiki
```

This isn't just style. Each PR is squash-merged as a single commit **titled with the PR title**,
and the [release notes](#releasing) are built from those titles. **Only player-facing types
appear in the release notes**; the rest are only in the full changelog:

| Type | Use for | Release notes |
|---|---|---|
| `feat` | Something new players or modpacks can use | **Features** |
| `fix` | A bug players could hit in the mod | **Fixes** |
| `perf` | Faster or lighter, with no behaviour change | **Performance** |
| `refactor` | Code restructuring with no behaviour change | not shown |
| `test` | Adding or fixing tests only | not shown |
| `docs` | README, wiki or contributor docs | not shown |
| `build` | Gradle, dependencies, mod metadata | not shown |
| `ci` | GitHub Actions workflows | not shown |
| `chore` | Anything else that doesn't change the mod for players | not shown |

**Pick the type by what players notice, not by what the diff touches:**

- `fix` and `feat` are only for changes to the mod itself. Fixing a broken workflow is `ci:`,
  fixing a typo in the docs is `docs:`, and fixing the Gradle build is `build:`.
- A change that fixes an in-game bug is `fix`, even if most of the diff is a refactor.
- **Breaking changes** get a `!` after the type, and then always appear under "Breaking changes"
  whatever the type. Use it whenever players have to act: raising the minimum Cobblemon or
  Minecraft version, changing save data, renaming a datapack tag. For example,
  `build!: require Cobblemon 1.8 or newer`.

An optional scope is fine, such as `fix(furnace): ...` (lowercase). A required **PR title** check
fails until the title follows the format; editing the title re-runs it. Commit messages inside a
PR aren't checked, but conventional ones are still appreciated.

## Releasing

_Maintainers only._ Releases are made entirely by the **Release** workflow; there's no version
to edit by hand. The git tag is the version: `gradle.properties` only holds `0.0.0-dev` for
development builds.

1. Go to **Actions → Release → Run workflow** (on `main`).
2. Pick the **bump** (`patch`, `minor` or `major`) and the **channel** (`alpha`, `beta` or
   `release`). The first release is always `0.1.0`.
3. Leave **Dry run** ticked the first time to preview the version and release notes in the run
   summary, then run it again with it unticked to release.

The workflow builds and tests both loaders, creates the `vX.Y.Z` tag and a GitHub Release with
both jars, then uploads to Modrinth and CurseForge. The release notes list the player-facing PRs
merged since the previous release, grouped by type (see [PR titles](#pr-titles)), and link to the
full changelog for everything else. The
"Full changelog" link in a dry run's preview points at a tag that doesn't exist yet, so it shows
nothing until the release is made.

### Publishing setup

Modrinth and CurseForge are skipped until they're configured. In **Settings → Environments →
release**:

| Site | Variable (project ID) | Secret (API token) |
|---|---|---|
| Modrinth | `MODRINTH_PROJECT_ID` | `MODRINTH_TOKEN`: a personal access token with only the *Create versions* scope |
| CurseForge | `CURSEFORGE_PROJECT_ID` | `CURSEFORGE_TOKEN`: an API token from CurseForge's account settings |

Setting a project ID without its token fails the release instead of silently skipping that site.
