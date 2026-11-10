# Palworks

Palworks is a multi-loader Minecraft mod (**1.21.1**, **Fabric + NeoForge**) that turns your
owned [Cobblemon](https://cobblemon.com) Pokémon into automated workers — Palworld-style "Pals."

Send one of your Pokémon out into the world and it scans for nearby work and does it on its own:
smelting, farming, lumbering, and hauling. What a Pokémon is good at depends on its
**suitability** (derived from its type/species), so different Pals are better at different jobs.

> **Requires [Cobblemon](https://cobblemon.com).** Palworks reads your owned Pokémon and assigns
> them jobs; it does nothing on its own without Cobblemon installed.

## Features

- **Autonomous workers** — a sent-out Pokémon scans the area around it (~32-block radius) for
  available work and performs it without micromanagement.
- **Jobs** — smelting (furnaces), planting & harvesting crops, lumbering (chopping trees), and
  hauling finished goods to nearby chests. Mining is in progress.
- **Suitability** — type- and species-based mapping decides which jobs a given Pokémon can do
  and how well.
- **Per-Pokémon inventory** — each worker carries a small inventory for the items it gathers.
- **Work toggle** — shift-right-click your Pokémon and use the bottom slot of the interact wheel
  to switch its work on (green check) or off (red cross).
- **Work icons on the party HUD** — in the top-left corner of each sent-out party member's card: its
  job's icon while it works, faded while it walks there, greyed out while it's in a battle, or a red
  cross if its work is off.
- **Data-driven** — which Pokémon can do which work, and what counts as "ore", come from datapack
  files, so modpacks can tune them without code changes (see
  [Customizing work skills](#customizing-work-skills)).
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

## Customizing work skills

Which work a Pokémon can do (its *suitabilities*) comes from JSON files in a datapack, under
`data/<namespace>/palworks/suitability/`. Each file maps suitability ids to a level from 1 to 5:

- **By type:** `types/<type>.json`, e.g. `data/palworks/palworks/suitability/types/fire.json`:
  ```json
  { "kindling": 1 }
  ```
  Every Pokémon of that type gets these. The file name is the type's id (`fire`, `fighting`, …). Files
  for the same type in different namespaces add together, so an addon can give a type more work without
  replacing the defaults.
- **By species:** `species/<species>.json`, where the file's namespace and name are the species,
  e.g. `data/cobblemon/palworks/suitability/species/scizor.json`:
  ```json
  { "lumbering": 3 }
  ```
  These add to what its types give, or raise the level. Level `0` takes a skill away, even if a
  type gives it.

A Pokémon's level is the highest its types give (+1 if both of its types give it), raised to its
species' level if that's higher. To change a default, put a file at the same path in your own datapack;
to remove one, replace it with `{}`. `/reload` applies changes. The ids are `kindling`, `watering`,
`planting`, `generating_electricity`, `handiwork`, `gathering`, `lumbering`, `mining`,
`medicine_production`, `cooling`, `transporting`, `nether` and `farming`. The defaults are in
[`common/src/main/resources/data`](common/src/main/resources/data).

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
