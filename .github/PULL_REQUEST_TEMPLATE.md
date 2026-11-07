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
- [ ] Verified in-game (note loader + which behavior you checked; `./gradlew :fabric:runClient` or `:neoforge:runClient` starts a game with Cobblemon)

## Checklist

- [ ] Changes are focused and don't reformat/refactor unrelated code
- [ ] New pure logic has unit tests under `common/src/test/java` where practical
- [ ] Follows the existing code style and `palworks:*` naming conventions
