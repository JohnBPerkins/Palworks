# Palworks

Palworks is a multi-loader Minecraft mod (**1.21.1**, **Fabric + NeoForge**) that turns your
owned [Cobblemon](https://cobblemon.com) Pokémon into automated workers — Palworld-style "Pals."

Send one of your Pokémon out into the world and it scans for nearby work and does it on its own:
smelting, farming, lumbering, and hauling. What a Pokémon is good at depends on its
**suitability** (derived from its type/species), so different Pals are better at different jobs.

> **Requires [Cobblemon](https://cobblemon.com).** Palworks reads your owned Pokémon and assigns
> them jobs; it does nothing on its own without Cobblemon installed.

## Features

- **Autonomous workers**: a sent-out or pastured Pokémon finds work nearby and does it on its own.
  See [Workers](https://github.com/JohnBPerkins/Palworks/wiki/Workers).
- **Jobs**: lighting furnaces, planting, watering and harvesting crops, nether wart, chopping trees,
  and hauling to chests and troughs. Mining is in progress. See [Jobs](https://github.com/JohnBPerkins/Palworks/wiki/Jobs).
- **Suitabilities**: a Pokémon's types and species decide which work it can do, and its base stats
  how well. See [Suitabilities](https://github.com/JohnBPerkins/Palworks/wiki/Suitabilities), or look any Pokémon up in
  [Work skills by Pokémon](https://github.com/JohnBPerkins/Palworks/wiki/Work-Skills-by-Pokemon).
- **Hunger, sleep and mood**: workers eat from [troughs](https://github.com/JohnBPerkins/Palworks/wiki/Hunger), sleep in
  [beds](https://github.com/JohnBPerkins/Palworks/wiki/Sleep), and an overworked one gets [unhappy](https://github.com/JohnBPerkins/Palworks/wiki/Mood) and goes on strike.
- **Data-driven**: who can do what, and what counts as work, come from datapack files and tags.
  See [Datapacks and Tags](https://github.com/JohnBPerkins/Palworks/wiki/Datapacks-and-Tags).
- **Multi-loader**: a shared core runs on both Fabric and NeoForge from the same codebase
  (built with [Architectury](https://architectury.dev)).

**Everything else is on the [wiki](https://github.com/JohnBPerkins/Palworks/wiki)**: setting up your first workers, every job, settings,
commands, and customizing Palworks with datapacks and resource packs.

## Requirements

- Minecraft **1.21.1**
- [Cobblemon](https://cobblemon.com) **1.7.1**
- **Fabric Loader + Fabric API**, _or_ **NeoForge**
- [Architectury API](https://modrinth.com/mod/architectury-api) **13.0.8** or later (the shared
  library Palworks uses to run on both loaders)
- Java **21** (required to play 1.21.1; bundled with most modern launchers)

## Installation

> Palworks is not yet published to a mod host. For now, build it from source (below).

1. Install your loader (Fabric + Fabric API, or NeoForge), Architectury API and Cobblemon for
   Minecraft 1.21.1.
2. Drop the matching Palworks jar (`palworks-fabric-*.jar` or `palworks-neoforge-*.jar`) into
   your `mods/` folder.
3. Launch the game.

## Building from source

With **JDK 21** and Git installed:

```bash
git clone https://github.com/JohnBPerkins/Palworks.git
cd Palworks
./gradlew build
```

The jars land in `fabric/build/libs/` and `neoforge/build/libs/`.

## Contributing

Contributions are welcome! See [CONTRIBUTING.md](.github/CONTRIBUTING.md) for the project layout,
testing, and how to submit changes.

## License

Palworks is released under the [MIT License](LICENSE).

Palworks is an unofficial, fan-made project. It is not affiliated with or endorsed by Mojang,
Cobblemon, or Pocketpair (Palworld). "Minecraft" is a trademark of Mojang Synergies AB.
