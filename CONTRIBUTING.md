# Contributing to Palworks

Thanks for your interest in improving Palworks! This guide covers how to get set up, the
conventions the project follows, and how to get your changes merged.

By participating, you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md).

## Getting started

Prerequisites: **JDK 21** and Git. The Gradle wrapper provides the correct Gradle version.

```bash
git clone https://github.com/JohnBPerkins/Palworks.git
cd Palworks
./gradlew build
```

### Project layout

Palworks is a multi-loader mod built with [Architectury](https://architectury.dev):

- `common/` — loader-agnostic core (worker AI, job system, suitability, world-interaction
  helpers). Most logic lives here.
- `fabric/` — Fabric entry point, mixins, registration.
- `neoforge/` — NeoForge entry point, registration.

Prefer adding new logic to `common/` and exposing only the loader-specific seams (tick hooks,
registration, menus) in `fabric/` and `neoforge/`.

## Building and testing

```bash
./gradlew build          # builds both loaders -> fabric/build/libs/, neoforge/build/libs/
./gradlew :common:test   # runs the pure-logic unit tests
./gradlew clean          # if you hit Minecraft mapping errors
```

### About Cobblemon in the dev environment

Cobblemon is a **compile-only** dependency: the code compiles against its API, but Cobblemon
**cannot load in the dev environment**. This means:

- Pure logic (enums, mappings, suitability, IDs) is unit-tested under `common/src/test/java` and
  must pass `./gradlew :common:test`. New logic that doesn't need a running game belongs here.
- Behavior that touches Cobblemon or live world state must be tested **in an actual Minecraft
  instance** (Fabric+Cobblemon or NeoForge+Cobblemon), verified in-game.

When you open a PR, please say how you tested: which unit tests, and/or what you verified in-game.

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

## Commit and pull-request process

1. **Open an issue first** for anything non-trivial so the approach can be discussed before you
   invest a lot of time.
2. Branch from `main` and keep your branch focused on a single change.
3. Use clear, conventional commit messages where practical (e.g. `feat:`, `fix:`, `docs:`,
   `refactor:`), matching the existing history.
4. Make sure `./gradlew build` and `./gradlew :common:test` pass before pushing.
5. Open a PR against `main`, fill out the template, and link any related issue.

A maintainer will review as soon as they can. Thanks for contributing!
