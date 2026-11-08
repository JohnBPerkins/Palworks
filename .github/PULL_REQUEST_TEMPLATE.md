<!-- Thanks for contributing to Palworks! Please fill out the sections below.
     PR title format: `type: short description`, e.g. `fix: stop workers pathing to broken furnaces`.
     The title becomes the release-note line. Types and details: .github/CONTRIBUTING.md#pr-titles -->


## Summary

<!-- What does this PR change, and why? -->

## Related issue

<!-- e.g. Closes #123. Open an issue first for non-trivial changes. -->

## How was this tested?

<!-- Tick all that apply and add detail. -->

- [ ] `./gradlew build` passes
- [ ] `./gradlew :common:test` passes
- [ ] `./gradlew :fabric:runGameTest` and `./gradlew :neoforge:runGameTest` pass (in-game tests)
- [ ] Verified in-game (note loader + which behavior you checked; `./gradlew :fabric:runClient` or `:neoforge:runClient` starts a game with Cobblemon)

## Checklist

- [ ] Changes are focused and don't reformat/refactor unrelated code
- [ ] New logic has tests: unit tests under `common/src/test/java`, or in-game tests under `common/src/gametest/java` for anything that needs a world
- [ ] Follows the existing code style and `palworks:*` naming conventions
