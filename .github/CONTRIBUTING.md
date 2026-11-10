# Contributing to Palworks

Thanks for your interest in improving Palworks! This guide covers how to get set up, the
conventions the project follows, and how to get your changes merged.

By participating, you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md).

## Setup

Clone and build as described in the [README](../README.md#building-from-source) (JDK 21 + Git; the
Gradle wrapper provides the right Gradle version). The commands you'll use day to day:

```bash
./gradlew build                  # builds both loaders -> fabric/build/libs/, neoforge/build/libs/,
                                 # and runs the unit tests and the Fabric in-game tests
./gradlew :common:test           # runs the unit tests (seconds)
./gradlew :fabric:runGameTest    # runs the in-game tests on a headless Fabric server (about 30 seconds)
./gradlew :neoforge:runGameTest  # the same in-game tests on a headless NeoForge server
./gradlew clean                # if you hit Minecraft mapping errors
./gradlew spotlessApply          # formats all Java code
./gradlew lint                   # checks formatting, and runs Error Prone and PMD
```

Then install the git hooks, which format and lint each commit with the same tools CI uses:

```bash
brew install pre-commit          # or: pipx install pre-commit
pre-commit install
```

### Formatting and linting

- **Formatting** is [palantir-java-format](https://github.com/palantir/palantir-java-format),
  applied by Spotless. Don't format by hand: the pre-commit hook (or `./gradlew spotlessApply`)
  does it. If the hook reformats something, the commit stops; `git add` the changes and commit
  again. For your IDE, the palantir-java-format plugin (IntelliJ) or Spotless's own integrations
  give the same output.
- **[Error Prone](https://errorprone.info)** runs inside every Java compile with all its default
  checks, and **any compiler warning fails the build**. Compare blocks and items with
  `state.is(Blocks.X)` / `stack.is(Items.X)` rather than `==`. To keep an intentional finding,
  put `@SuppressWarnings("CheckName")` on the smallest method that holds it, with a comment
  saying why (enum ordinals in network packets, for example).
- **[PMD](https://pmd.github.io)** rules are in `config/pmd/ruleset.xml`: four whole categories,
  with rules configured for Minecraft (game-owned resources, Mixin handlers, the `DEBUG` flag),
  and the few exclusions each explained. To keep an intentional finding, end its line with
  `// NOPMD - reason`.
- Prefer fixing a finding over suppressing it, and suppressing one over excluding a rule.

CI's `lint` job runs `./gradlew lint`, the same check as the hook.

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

Dev runs load Cobblemon, so you can test in-game straight from Gradle (JDK 21 required):

| Command | What it starts |
|---|---|
| `./gradlew :fabric:runClient` | Fabric game client with Cobblemon and Palworks |
| `./gradlew :neoforge:runClient` | NeoForge game client with Cobblemon and Palworks |
| `./gradlew :fabric:runServer` | Fabric dedicated server (same for `:neoforge:runServer`) |

These use the oldest supported Cobblemon (`cobblemon_version` in `gradle.properties`). Add
`-Pcobblemon_version=1.8.4` to try the newest. Each loader keeps its world and settings in its own
`run/` folder, which git ignores. The first server start stops until you accept the EULA in
`run/eula.txt`.

Dev runs also include the [spark](https://spark.lucko.me/docs) profiler, to measure what Palworks
costs per tick:

- `/spark profiler start`, play for a minute or two, then `/spark profiler stop`. It prints a link
  to an interactive view; search it for `dev.palworks` to see where Palworks spends time. The link
  uploads the profile to spark's public viewer; add `--save-to-file` to keep it local instead.
- `/spark profiler start --only-ticks-over 50` records only ticks slower than 50 ms, to catch
  lag spikes.
- `/spark tps` shows recent TPS and tick times.

Cobblemon and spark are only added to these dev runs: they never end up in the published jars or
their dependencies.

## Automated tests

There are two kinds, and CI runs both on every PR:

| Kind | Where | Run with | For |
|---|---|---|---|
| Unit tests | `common/src/test/java` | `./gradlew :common:test` | Logic that doesn't need a world: rules (steps, carry modes, sapling names), saved state, items |
| In-game tests (GameTests) | `common/src/gametest/java` | `./gradlew :fabric:runGameTest`, `./gradlew :neoforge:runGameTest` | Anything that needs a real world: blocks, block tags, loot, entities, and real Cobblemon Pokémon doing jobs |

**Unit tests** can use items, blocks and block states after `Bootstrap.bootStrap()` (see
`WorkerStateTest`), but not block tags or a world: tags only exist once a world loads its data.
Pull logic into a small class with no world dependency when you can, as `StepRunner`,
`CarryMode` and `SaplingNames` do, so it can be tested here.

**In-game tests** boot a headless server with Palworks, Cobblemon and the tests (a separate
`palworks_gametest` mod, never published), run every test, and fail the build if any fail. The
same test classes run on both loaders, and CI runs them on both: they live in
`common/src/gametest` (which `common` itself doesn't compile), and each loader's `gametest` source
set compiles them with its own registration on top (Fabric's `fabric-gametest` entrypoint;
NeoForge's `PalworksGameTestsNeoForge` in `neoforge/src/gametest`). Each test builds its own
scene, in an empty 8x8x8 area (`Templates.EMPTY`) or a 16x24x16 one for full-size trees
(`Templates.ARENA`), and checks the result:

- `TreeGameTests`, `CropGameTests`, `JobGameTests`: Palworks' world logic against real blocks
  (what counts as a tree, what a harvest drops, whether a job is still there...). `Scenes` has
  helpers for floors, trees and counting dropped items.
- `WorkerGameTests`: real Pokémon (`Workers.sendOut`) finding and doing jobs end to end.
- `RealTreeGameTests`: trees grown by Minecraft's own worldgen (branches, low leaves, bent trunks),
  chopped by real lumberjacks of different sizes.

Start every test that sends out Pokémon with `Workers.isolate(helper)`, and give it its own
`batch` (batches run one after another). Workers look for jobs 32 blocks around them, and earlier
tests' blocks, items and Pokémon stay in the world; `isolate` clears them, lays a floor around the
test, and keeps the chunks around it ticking (the test server only ticks entities in the test's own
chunks, so a worker that wandered past the edge used to freeze there).

The test server ticks as fast as it can, so a 600-tick timeout passes in a second or two. Avoid
anything timed in real (wall-clock) milliseconds in code you want to test this way.

Add a new GameTest class to `Templates.TEST_CLASSES` (NeoForge) and to
`entrypoints."fabric-gametest"` in `fabric/src/gametest/resources/fabric.mod.json` (Fabric). The
test areas are `.nbt` structures in `common/src/gametest/resources/data/palworks_gametest/structure/`,
written by `common/src/gametest/make_structures.py`; add a size there and run it again.
A GameTest that never fails isn't testing anything: when adding one, briefly break the behavior
it covers and check that it fails.

Behavior that's hard to automate (how something looks, a full play session) still needs an
in-game check: use the dev runs above, or a normal Minecraft instance with the built jar from
`fabric/build/libs` or `neoforge/build/libs`.

## Conventions

- **Match the surrounding code.** Follow existing naming, structure, and style rather than
  introducing new patterns.
- **Keep changes surgical.** Touch only what the change requires; don't refactor unrelated
  code in the same PR. (Formatting is automatic; see [Formatting and linting](#formatting-and-linting).)
- **Namespaced keys.** Persistent data and identifiers are namespaced `palworks:*`.
- **Debug logging** is gated behind a single flag and is off by default. Launch with
  `-Dpalworks.debug=true` to enable verbose logging while developing.
- **Seeing what workers do in game** (op commands):
  - `/palworks debug` toggles worker debugging for you. It posts job activity (assignments,
    completions) in chat, and shows every worker within 48 blocks in the world: its job icon and a
    label like `chop_tree · walking` above its head (through walls), the path it's walking, and a
    box around its job's block. Colours: yellow walking, green working, grey paused, red off, white
    idle. Under the label:
    - `job 12.3s · gives up in 17.7s`: how long it has had its job, and the countdown to the 30s
      timeout for a job it hasn't started (none once it's working, or while paused);
    - `step 2/3 chop · 34/60t`: the step it's on and how far through;
    - `partial path · fails 2/3 · still 3.5s · step-in 12/40` (while walking): whether its path
      reaches the job (`path reaches`, `partial path` or `no path`), recent walks that ended short
      (it gives up at 3), how long it has stood still, and a lumberjack's ticks stepping onto its
      tile;
    - `2 unreachable spots`: spots it remembers it couldn't reach (it won't take jobs there for
      now), each with a dark red box.

    Lines about something going wrong are red, and so is a path that doesn't reach its job.
  - `/palworks inspect worker` and `/palworks jobs` dump the nearest worker's state and the job pool.
  - `/palworks testarea` builds a flat test area from where you stand, east and south (about
    62 x 94 blocks; everything in it is cleared first). Its sections are:
    - smelting: a furnace, a blast furnace and a smoker with input but no fuel;
    - crops: one of each vanilla crop, Cobblemon crop, root (hanging from a little arch) and berry
      bush (with berries), with a sign naming it;
    - every vanilla and Cobblemon tree.

    Everything is fully grown. Anything it couldn't place is listed in chat.
- **Data-driven where possible.** Things like which blocks count as work are controlled by
  datapack tags — prefer editing tags over hard-coding in Java.

## Pull-request process

1. **Open an issue first** for anything non-trivial so the approach can be discussed before you
   invest a lot of time.
2. Branch from `main` and keep your branch focused on a single change.
3. Make sure `./gradlew build`, `./gradlew :common:test`, `./gradlew :fabric:runGameTest` and
   `./gradlew :neoforge:runGameTest` pass
   before pushing.
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
