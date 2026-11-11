# Palworks

Palworks is a multi-loader Minecraft mod (**1.21.1**, **Fabric + NeoForge**) that turns your
owned [Cobblemon](https://cobblemon.com) Pokémon into automated workers — Palworld-style "Pals."

Send one of your Pokémon out into the world and it scans for nearby work and does it on its own:
smelting, farming, lumbering, and hauling. What a Pokémon is good at depends on its
**suitability** (derived from its type/species), so different Pals are better at different jobs.

> **Requires [Cobblemon](https://cobblemon.com).** Palworks reads your owned Pokémon and assigns
> them jobs; it does nothing on its own without Cobblemon installed.

## Features

- **Autonomous workers** — a sent-out Pokémon scans the area around it (32 blocks by default, see [Server settings](#server-settings)) for
  available work and performs it without micromanagement.
- **Jobs** — smelting (furnaces), planting, watering & harvesting crops, lumbering (chopping
  trees), and hauling finished goods to nearby chests. Haulers fill up before a trip: they pick up
  anything while they have a free slot, then only items matching what they carry. Mining is in
  progress.
- **Watering** — Water types water growing crops, keeping their farmland wet, and sometimes
  fertilize them as they do, just as bone meal would. The stronger the Pokémon, the more often
  (see [Work levels](#work-levels)).
- **Suitability** — type- and species-based mapping decides which jobs a given Pokémon can do
  and how well.
- **Per-Pokémon inventory** — each worker carries a small inventory for the items it gathers.
- **Work toggle** — shift-right-click your Pokémon and use the bottom slot of the interact wheel
  to switch its work on (green check) or off (red cross).
- **Work icons on the party HUD** — in the top-left corner of each sent-out party member's card, next
  to the "sent out" icon: its job's icon while it works, faded while it walks there, greyed out while
  it's in a battle, or a red cross if its work is off.
- **Data-driven** — which Pokémon can do which work, and what counts as a furnace or an "ore", come from datapack
  files, so modpacks can tune them without code changes (see
  [Customizing work skills](#customizing-work-skills)).
- **Multi-loader** — a shared core runs on both Fabric and NeoForge from the same codebase
  (built with [Architectury](https://architectury.dev)).

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

## Server settings

`config/palworks.json` (written with the defaults the first time the server starts) holds the
settings that aren't per-Pokémon:

```json
{
  "work_range_horizontal": 32,
  "work_range_vertical": 32,
  "owner_range": 24,
  "pickup_radius": 3.0,
  "fertilize_chance_per_level": 0.1,
  "watering_cooldown_seconds": 60
}
```

- **`work_range_horizontal`** (8 to 64): how many blocks out each way a worker looks for and takes
  jobs. Bigger ranges scan more of the world each tick.
- **`work_range_vertical`** (4 to 64): how many blocks up and down it does.
- **`owner_range`** (4 to 32): how far from its owner a Pokémon from a party takes jobs. Cobblemon
  teleports a party Pokémon back to its owner past 32 blocks, so it can't be more.
- **`pickup_radius`** (1 to 8): how far round a harvest, chop or pickup spot dropped items are
  collected from.
- **`fertilize_chance_per_level`** (0 to 0.2): the chance that watering a crop also fertilizes it,
  for each of the waterer's [Watering levels](#work-levels): at 0.1, a level 1 Squirtle fertilizes
  10% of the crops it waters, a level 3 Blastoise 30%.
- **`watering_cooldown_seconds`** (5 to 600): how long after a crop is watered until it can be
  watered again. Crops watered before a restart can be watered again straight away.

Any setting left out keeps its default. If a value is out of range or the file isn't valid JSON,
the server logs a warning and uses the defaults. Changes apply the next time the server starts.

## Customizing work skills

Which work a Pokémon can do (its *work suitabilities*) comes from datapack files, one per job:
`data/<namespace>/palworks/suitability/<job>.json`. For example, the default
`data/palworks/palworks/suitability/lumbering.json`:

```json
{
  "types":   { "fighting": 1, "steel": 1 },
  "species": { "cobblemon:scizor": 3, "cobblemon:pinsir": 2 }
}
```

- **`types`**: every Pokémon of that type can do the job. Types are named as in Cobblemon (`fire`,
  `fighting`, …). The number (1 to 5) is added to the Pokémon's [work level](#work-levels): `1` is
  the usual, `2` gives one more.
- **`species`**: extra species that can do it, at exactly that level (1 to 5), whatever their types
  and stats would give. A species without a namespace is Cobblemon's (`scizor` =
  `cobblemon:scizor`). Level `0` takes the job away from that species, even if one of its types
  gives it.

Files for the same job from different namespaces **merge**, like vanilla tags: an addon can add
`data/myaddon/palworks/suitability/kindling.json` with just its own entries. Where two files give the
same type or species a level, the higher one wins, and a species' `0` wins over any level. Add
`"replace": true` to a file to use only it for that job, ignoring the other namespaces' files. To
change a default, put a file at the same path as ours in your datapack. `/reload` applies changes.

The jobs are `kindling`, `watering`, `planting`, `generating_electricity`, `handiwork`, `gathering`,
`lumbering`, `mining`, `medicine_production`, `cooling`, `transporting`, `nether` and `farming`. The
defaults are in
[`common/src/main/resources/data/palworks/palworks/suitability`](common/src/main/resources/data/palworks/palworks/suitability).

Which blocks are work come from block tags: `#palworks:furnaces` (lit by Kindling workers: the
furnace, blast furnace and smoker; a modded block needs a furnace block entity) and `#palworks:ores`
(for Mining, once it's done). Add to them like any tag, with a
`data/palworks/tags/block/<tag>.json` in your datapack.

### Work levels

A Pokémon's level for a job (1 to 5) comes from how strong it is: its **base stat total**.

| Base stat total | Level | For example |
|---|---|---|
| under 350 | 1 | Squirtle (314), Magikarp (200) |
| 350 to 449 | 2 | Wartortle (405) |
| 450 to 549 | 3 | Blastoise (530), Vaporeon (525), Lapras (535) |
| 550 to 649 | 4 | Dragonite (600), most legendaries |
| 650 or more | 5 | Kyogre (670), Mewtwo (680) |

A type that gives the job gives one level less for each earlier form in the Pokémon's evolution
line that didn't have that type. Hisuian Typhlosion does Kindling at 3, but Nether work at only 1,
as Cyndaquil and Quilava were Fire but not Ghost. Vaporeon waters at 2, as Eevee was Normal. With
two types that give the job, the better level counts. A Pokémon's level only changes as it evolves
or changes form, not as it levels up. A species entry in the files sets its level outright, to
make an exception (Slaking's stats are a legendary's, but it loafs around).

Levels so far change how often a Watering worker fertilizes crops (see
[`fertilize_chance_per_level`](#server-settings)). `/palworks inspect worker` shows a worker's
levels.

## Customizing work animations

While a Pokémon works, it plays a placeholder Cobblemon move as its work animation: Ember to light
a furnace, Razor Leaf to harvest, Leech Seed to plant, Water Gun to water and Scratch to chop. Each
animation has a **stance**, which sets where the worker stands. A contact move like Scratch is played right up
against the block; ranged moves are played from a little way back. Bigger Pokémon stand further
back, measured from the edge of their hitbox.

**Poser animations.** While a Pokémon is working, it has the aspect `palworks-working`, plus one for
the job: `palworks-harvest-crops`, `palworks-chop-tree`, `palworks-fuel-furnace`, and so on. A
resource pack can animate a species from its poser JSON with a pose condition such as
`"condition": "q.has_aspect('palworks-chop-tree')"`.

**Per-species changes (the worker roster).** A datapack can give a species its own version of any
work animation. Use files at `data/<namespace>/palworks/work_animations/<name>.json`:

```json
{
  "species": ["cobblemon:scizor", "scyther"],
  "animations": {
    "chop": { "move": "furycutter", "impact_ticks": 18, "duration_ticks": 45, "stance": "against" },
    "harvest": { "stance": { "gap": 2.5, "max_ring": 5 } }
  }
}
```

- **`species`**: one species, or a list of them. A species without a namespace is Cobblemon's.
- **`animations`**: the changes for each animation, by id. The ids are `ignite_furnace`, `harvest`,
  `plant`, `water` and `chop`. Anything you leave out keeps its default.
  - **`move`**: the action effect to play. Give a Cobblemon move name (`furycutter`) or a full id
    (`myaddon:chop`).
  - **`impact_ticks`**: how many ticks in the effect lands on the block. The work (the harvest, the
    chop) happens at that moment.
  - **`duration_ticks`**: how many ticks until the body animation is over. The worker waits that long
    before it walks off or starts another animation.
  - **`aim_y`**: how high in the block to aim, from 0 (bottom) to 1 (top).
  - **`stance`**: where to stand. Use `"near"` for a ranged move or `"against"` for a contact move.
    You can also give an object of numbers in blocks; any you leave out keep the default stance's
    value:
    - `gap`: the most clear space between the body and the block.
    - `min_ring` and `max_ring`: how many tiles out from the block it may stand, at least and at most.
    - `slack`: how much further than that still counts as there.

Where two files change the same thing for the same species, the file whose id sorts last wins.
`/reload` applies changes.

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
