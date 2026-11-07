# Contributing to Palworks

Thanks for your interest in improving Palworks! This guide covers how to get set up, the
conventions the project follows, and how to get your changes merged.

By participating, you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md).

## Setup

Clone and build as described in the [README](README.md#building-from-source) (JDK 21 + Git; the
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
3. Use clear, conventional commit messages where practical (e.g. `feat:`, `fix:`, `docs:`,
   `refactor:`), matching the existing history.
4. Make sure `./gradlew build` and `./gradlew :common:test` pass before pushing.
5. Open a PR against `main` and fill out the template, including how you tested (unit tests
   and/or what you verified in-game) and any related issue.

PRs need a maintainer's approval and a passing CI build, and are squash-merged into `main`.
Thanks for contributing!
