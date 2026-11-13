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
- **Work skills in the PC and summary** — each Pokémon's jobs and [work levels](#work-levels) show
  as icons in the corner of its portrait, in the PC as you hover over it and on its summary. The
  summary's Info page names them, in the box beside the experience bar. Hover either for the full
  list.
- **Per-Pokémon inventory** — each worker carries a small inventory for the items it gathers.
- **Sleep and beds** — pastured workers sleep in their species' hours (see [Sleep](#sleep)). Give
  them **straw** or **wool beds**, large ones for large Pokémon, or they wake Tired and work slower the next day.
- **Mood** — overwork, hunger and no rest wear a worker's mood down (see [Mood](#mood)). One whose
  mood runs out goes on strike until it cheers up.
- **Hunger and troughs** — work makes workers hungry (see [Hunger](#hunger)). Fill a **trough** with
  Cobblemon berries or Aprijuice and they walk over and eat from it. A worker
  that's run out of food works at three quarters of its speed until it eats.
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
  "watering_cooldown_seconds": 60,
  "hunger": {
    "enabled": true,
    "work_per_fullness": 20,
    "idle_drain_per_day": 0.25,
    "trough_fill_fraction": 0.5,
    "empty_work_speed": 0.75
  },
  "sleep": {
    "enabled": true,
    "tired_work_speed": 0.75
  },
  "mood": {
    "enabled": true,
    "work_per_point": 2,
    "hungry_drain_per_day": 50,
    "tired_drain_per_day": 25,
    "recovery_per_day": 100,
    "unhappy_below": 50,
    "strike_until": 30
  }
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
- **`hunger`**: how workers get hungry and eat (see [Hunger](#hunger)).
  - **`enabled`**: `false` turns hunger off: Palworks leaves fullness entirely to Cobblemon.
  - **`work_per_fullness`** (1 to 500): how many units of work use up one point of fullness. A
    unit is a crop harvested, a seed planted, a crop watered, a furnace lit, a trip to pick up or
    deliver, or every 8 logs of a tree (at most 5 a tree).
  - **`idle_drain_per_day`** (0 to 1): the share of its max fullness a worker loses over a Minecraft
    day while its work is on, working or not.
  - **`trough_fill_fraction`** (0 to 1): how full troughs fill a worker, as a share of its max
    fullness. The rest is left for hand-feeding. 0 and troughs never feed.
  - **`empty_work_speed`** (0.1 to 1): how fast a worker with no fullness left works and walks.
    Slowdowns add up (out of food and Tired is both taken off), but never below half speed.
- **`sleep`**: how pastured workers sleep (see [Sleep](#sleep)).
  - **`enabled`**: `false` turns it off: pastured workers' sleep is then Cobblemon's alone.
  - **`tired_work_speed`** (0.1 to 1): how fast a Tired worker works and walks.
- **`mood`**: how workers' mood goes down and back up (see [Mood](#mood)).
  - **`enabled`**: `false` turns mood off: no strikes.
  - **`work_per_point`** (1 to 500): how many units of work (as for hunger) take a point off.
  - **`hungry_drain_per_day`**, **`tired_drain_per_day`** (0 to 1000): the points a worker loses
    over a Minecraft day while Hungry, or while Tired.
  - **`recovery_per_day`** (0 to 1000): the points it gains back over a day while it's neither,
    and not at work.
  - **`unhappy_below`** (0 to 100): the mood below which it shows it's unhappy, a warning that
    it's on its way to a strike.
  - **`strike_until`** (1 to 100): the mood a worker on strike has to be back to before it works.

Any setting left out keeps its default. If a value is out of range or the file isn't valid JSON,
the server logs a warning and uses the defaults. Changes to the file apply the next time the server
starts.

Admins (OP level 2, or the server console) can also change them in game, straight away. Each
setting is named by its place in the file, and the change is written to the file:

- `/palworks config get [<setting>]`: one setting, or all of them, with their defaults.
- `/palworks config set <setting> <value>`: e.g. `/palworks config set hunger.enabled false`. Values
  must be in the same ranges as in the file.
- `/palworks config reset <setting>` or `/palworks config reset all`: back to the defaults.

The display settings in `config/palworks-client.json` are each player's own, so they're only
changed in that file.

## Hunger

A worker's hunger is its Cobblemon **fullness**, the bar on its summary screen: Palworks adds no
stat of its own.

- **While its work is on**, Cobblemon's own drain of its fullness is paused, and Palworks drains
  it instead: a point for every 20 units of work it does (a crop harvested, a seed planted, a trip
  made...), and a quarter of its bar over each Minecraft day. With its work off, or back in its
  ball, it's a normal Cobblemon Pokémon again, and Palworks doesn't touch its fullness.
- **When it's below half full** it walks to the nearest **trough** with feed and eats, a point at
  a time, until it's half full. The other half is left for you: a full Pokémon refuses Mochi and
  berries fed by hand. Eating from a trough only fills it up; the food's own effects (EVs,
  friendship, healing) don't happen.
- **Which troughs:** any within 32 blocks of it, or, for a pastured worker, of its pasture block.
  So a pastured worker that has wandered off for work still goes back to the troughs
  by its pasture.
- **With no fullness left** it works and walks at three quarters of its speed, with a half hunger
  shank over its head, until it eats. It never stops working.
- **A slowed worker** (out of food, Tired, or both: a quarter off for each, so half speed at the
  slowest) is slow at everything: each job takes longer,
  its animations play slower to match (its legs as it walks, the move it works with, landing just as
  the work happens), and it moves slower whether Palworks or Cobblemon is moving it. Except on its
  way to eat or sleep, which is never held up.

A **trough** comes in every wood (oak, spruce, birch, jungle, acacia, dark oak, mangrove, cherry,
bamboo, crimson and warped), crafted from 5 of that wood's slabs (two on the top row's ends, three
along the bottom). It holds **feed**, as a composter holds compost: food put in it becomes feed,
worth the fullness it gives (a point a berry), and workers eat it a point at a time.
Each trough holds 64 points; an empty one shows its bare wooden bottom, and a layer of feed rises
as it fills. Troughs placed side by side join into one long trough, whatever their woods, and share
their feed evenly: food put in anywhere spreads along all of them, and workers eat at any of them.
Feed can't be taken back out, and breaking a trough loses its share.

Filling them:

- **Right-click a trough holding food** to put one in; **shift-right-click** to put the whole stack
  in, as far as there's room along it. Right-click with anything else to see how much feed it has.
- **Hoppers** feed troughs as they do composters.
- **Transporters take food to troughs first.** Harvested berries they carry go to the nearest
  trough with room before a chest, so a berry farm keeps its workers fed.
- **Transporters restock troughs from chests.** When a trough in reach has less than 16 feed left,
  a transporter fetches a stack of food from a chest that has some.

Troughs take the items in the `palworks:trough_food` item tag: by default Cobblemon's berries and
Aprijuices. Datapacks can add to the tag, and set how much feed an item is worth in
`data/<namespace>/palworks/trough_food/<name>.json` (anything not listed is worth 1):

```json
{
  "values": [
    { "item": "cobblemon:sitrus_berry", "fullness": 2 },
    { "tag": "cobblemon:berries", "fullness": 1 }
  ]
}
```

## Sleep

Pastured workers keep a sleep schedule, as a Palworld base's pals do. Party workers never do: they
stay awake with you, as Cobblemon's party Pokémon do.

- **When:** each sleeps in its species' Cobblemon sleep hours: most at night, nocturnal ones by day
  (they work the night shift), some at their own hours. Species Cobblemon lets sleep at any time
  sleep at night. A species Cobblemon gives no sleep animation still sleeps, lying still.
- **Where:** at its bedtime it finishes what it's doing and goes to a free **bed** it fits
  in, one worker to a bed: the smallest it fits, so big beds are left for big Pokémon, and of those
  the nearest. Like troughs, beds by its pasture block count however far it has wandered. With none
  to be had, it sleeps where it is.
- **Tired:** a worker that slept less than half the night in a bed wakes **Tired**: it works and
  walks at three quarters of its speed, with a "z" over its head, until it next sleeps. One whose
  species has no sleep animation (it only lies still) shows a moon over its head while it sleeps.
- **Waking:** hitting or hurting a sleeping worker, breaking its bed, or recalling it wakes it. It
  stays up for 15 seconds, then goes back to bed if it's still its bedtime. What it slept before
  still counts towards whether it wakes Tired.
- **Naps:** Cobblemon's own sleep is left alone: an idle pastured worker still naps when it gets
  drowsy, and isn't given work until it wakes. It just doesn't doze off in the middle of a job.
- **Pokémon that aren't working** (no work skills, or their work switched off) use beds too: a
  pastured one goes to a free bed by its pasture when it gets sleepy, and sleeps there as a worker
  does. Sleepy is when Cobblemon makes it drowsy, until Cobblemon would wake it; for a species
  Cobblemon never lets sleep, its bedtime, as for a worker. Workers come first: it only takes a bed
  no worker has, and keeps it till it wakes. With none free, it sleeps where Cobblemon has it (or,
  if Cobblemon never lets it sleep, stays up). It never wakes Tired.

Beds come in three sizes, for Pokémon up to as wide as the bed:

| Bed | Size | Fits | Crafted from |
|---|---|---|---|
| Straw Bed | 1 by 1 | up to 1 block wide (Bulbasaur, Machamp) | 3 wheat over 3 wooden slabs |
| Large Straw Bed | 2 by 2 | up to 2 wide (Charizard, Gyarados) | 4 straw beds |
| Huge Straw Bed | 3 by 3 | up to 3 wide (Snorlax, Wailord), and wider if the space round it is clear | 9 straw beds |

**Wool beds** are the nicer kind, in all 16 colours: a Red Wool Bed is 3 red wool over 3 wooden
slabs, a Large Red Wool Bed 4 red wool beds and a Huge one 9. Like a vanilla bed, a wool bed of any
size is dyed another colour by crafting it with that dye. For now they sleep just as straw beds do.

A large or huge bed is placed whole, like a vanilla bed: out from where you place it, forward and to
your right. Breaking any part of it breaks it all. A bed is a quarter of a block high, like a slab: a
worker steps up onto it and lies down in the very middle. With its work off, a pastured Pokémon's
sleep is Cobblemon's (napping when it gets drowsy), but in a free bed if there is one.

## Mood

Each worker has a mood, from 0 to 100, separate from its friendship. It starts at 100.

- **What lowers it:** the work it does (a point for every 2 units of work, as hunger counts them),
  and being Hungry or Tired.
- **What brings it back:** while it's neither Hungry nor Tired, it cheers up whenever it isn't at
  work: idle, eating or asleep. A fed worker with a bed stays about level through a busy day and night.
- **Unhappy (below 50):** a sad face over its head, a warning that it's on its way to a strike. It
  works no slower for it.
- **On strike (at 0):** it finishes what it's doing, then does no work until its mood is back to
  30, with an anger mark over its head. It still eats and sleeps, which is how it gets there.
- **Where to see it:** its mood shows in the bottom-left corner of its portrait on the PC and its
  summary: a face and the number. Hover it to see what it means.

Party workers have a mood too, but never sleep, so they're never Tired. With its work off, or in
its ball, a worker's mood holds where it is.

### Display settings

`config/palworks-client.json` (written with the defaults the first time the game starts) holds each
player's own display settings:

```json
{
  "show_status_icons": true
}
```

- **`show_status_icons`**: whether Pokémon show over their heads what's holding their work back (a
  half hunger shank when they're out of food, a "z" when they're Tired, a sad face when they're
  unhappy and an anger mark when they're on strike), and a moon while they're asleep, if their
  species has no sleep animation to show it.

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

### Changing work skills in game

Admins (OP level 2, or the server console) can change a species' work skills without touching the
datapacks. Changes apply straight away (players' PC and summary screens show them a moment later),
go over every datapack's levels, and are kept after `/reload` and restarts. They're saved with the
world, in `<world>/palworks/suitability_overrides.json`:

- `/palworks suitability set <species> <job> <level>`: e.g. `/palworks suitability set bulbasaur
  watering 3`. Like a species entry in the files, it sets the level outright; `0` takes the job away.
- `/palworks suitability get <species>`: its levels (its standard form's), and which were changed in
  game.
- `/palworks suitability reset <species> [<job>]`: back to the datapacks' levels, for one job or all.
- `/palworks suitability reset all`: undo every change.
- `/palworks suitability list`: every species changed, and how.

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
[`fertilize_chance_per_level`](#server-settings)). A Pokémon's levels show in the PC and on its
summary, for work Palworks does so far (Cooling, say, isn't shown until there's something to cool),
and `/palworks suitability get <species>` lists a species' levels.

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
