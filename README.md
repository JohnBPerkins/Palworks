# Palworks

Palworks is a multi-loader Minecraft mod (**1.21.1**, **Fabric + NeoForge**) that turns your
owned [Cobblemon](https://cobblemon.com) Pokémon into automated workers — Palworld-style "Pals."

Send one of your Pokémon out into the world and it scans for nearby work and does it on its own:
smelting, farming, lumbering, mining, and hauling. What a Pokémon is good at depends on its
**suitability** (derived from its type/species), so different Pals are better at different jobs.

> **Requires [Cobblemon](https://cobblemon.com).** Palworks reads your owned Pokémon and assigns
> them jobs; it does nothing on its own without Cobblemon installed.

## Features

- **Autonomous workers** — a sent-out Pokémon scans the area around it (~32-block radius) for
  available work and performs it without micromanagement.
- **Jobs** — smelting (furnaces), planting & harvesting crops, lumbering (chopping trees),
  mining (ores), and hauling finished goods to nearby chests.
- **Suitability** — type- and species-based mapping decides which jobs a given Pokémon can do
  and how well.
- **Per-Pokémon inventory** — each worker carries a small inventory for the items it gathers.
- **Data-driven work detection** — what counts as "ore" or other work is controlled by datapack
  tags, so it can be tuned without code changes.
- **Multi-loader** — a shared core runs on both Fabric and NeoForge from the same codebase
  (built with [Architectury](https://architectury.dev)).

## Requirements

- Minecraft **1.21.1**
- [Cobblemon](https://cobblemon.com) **1.7.1**
- **Fabric Loader + Fabric API**, _or_ **NeoForge**
- Java **21** (required to play 1.21.1; bundled with most modern launchers)

## Installation

> Palworks is not yet published to a mod host. For now, build it from source (below).

1. Install your loader (Fabric + Fabric API, or NeoForge) and Cobblemon for Minecraft 1.21.1.
2. Drop the matching Palworks jar (`palworks-fabric-*.jar` or `palworks-neoforge-*.jar`) into
   your `mods/` folder.
3. Launch the game.

## Building from source

Prerequisites: **JDK 21** and Git. The Gradle wrapper handles the rest.

```bash
git clone https://github.com/JohnBPerkins/Palworks.git
cd Palworks

./gradlew build          # builds both loaders
./gradlew :common:test   # runs the pure-logic unit tests
```

Build outputs land in `fabric/build/libs/` and `neoforge/build/libs/`. If you hit Minecraft
mapping errors, try `./gradlew clean` and rebuild.

## Project layout

| Module      | Contents                                                                 |
|-------------|--------------------------------------------------------------------------|
| `common/`   | Loader-agnostic core: worker AI, job system, suitability, world helpers. |
| `fabric/`   | Fabric entry point, mixins, and registration.                            |
| `neoforge/` | NeoForge entry point and registration.                                   |

## Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) for how to build,
test, and submit changes, and our [Code of Conduct](CODE_OF_CONDUCT.md).

## License

Palworks is released under the [MIT License](LICENSE).

Palworks is an unofficial, fan-made project. It is not affiliated with or endorsed by Mojang,
Cobblemon, or Pocketpair (Palworld). "Minecraft" is a trademark of Mojang Synergies AB.
