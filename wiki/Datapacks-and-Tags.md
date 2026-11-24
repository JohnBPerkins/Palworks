# Datapacks and Tags

Who can do which work, and what counts as work, come from datapack files and tags, so modpacks and
servers can change them without code. `/reload` applies changes.

## Work skills

Which work a Pokémon can do (its [work skills](Suitabilities), which the files call suitabilities)
comes from datapack files, one per work skill:
`data/<namespace>/palworks/suitability/<skill>.json`. For example, part of the default
`data/palworks/palworks/suitability/lumbering.json`:

```json
{
  "types":   { "fighting": 1 },
  "species": { "cobblemon:scizor": 1, "cobblemon:pinsir": 1 }
}
```

- **`types`**: every Pokémon of that type has the skill. Types are named as in Cobblemon (`fire`,
  `fighting`, …). The number (1 to 5) is added to the Pokémon's
  [work level](Suitabilities#work-levels): `1` is the usual, `2` gives one more.
- **`species`**: extra species that have it. The number (1 to 5) works as a type's does: `1` is
  the level its stats give, `2` one more. It's used instead of what the species' types give for that
  skill. A species without a namespace is Cobblemon's (`scizor` = `cobblemon:scizor`). `0` takes the
  skill away from that species, even if one of its types gives it.

Files for the same skill from different namespaces **merge**, like vanilla tags: an addon can add
`data/myaddon/palworks/suitability/kindling.json` with just its own entries. Where two files give
the same type or species a level, the higher one wins, and a species' `0` wins over any level. Add
`"replace": true` to a file to use only it for that skill, ignoring the other namespaces' files. To
change a default, put a file at the same path as ours in your datapack.

The work skills are `kindling`, `watering`, `planting`, `generating_electricity`, `handiwork`,
`gathering`, `lumbering`, `mining`, `unearthing`, `medicine_production`, `cooling`, `transporting`, `nether` and
`farming`. The defaults are in
[`common/src/main/resources/data/palworks/palworks/suitability`](https://github.com/JohnBPerkins/Palworks/tree/main/common/src/main/resources/data/palworks/palworks/suitability),
and the [work skill pages](Suitabilities#every-work-skill) list who they give each skill to.

To change a species on one server or world instead, use `/palworks suitability` (see
[Commands](Commands#work-skills)).

## Trough food

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

See [Hunger and Troughs](Hunger).

Blocks added to the tags below also say which work skill works them in their item's tooltip, while
Shift is held (see [Workers](Workers#what-the-icons-mean)).

## `#palworks:furnaces`

The blocks [Kindling](Jobs#light-furnaces) workers light: by default the furnace, blast furnace and
smoker. A modded block needs a furnace block entity to work (one without still says Kindling in its
tooltip).

To add furnaces from another mod, create this file in your datapack:

`data/palworks/tags/block/furnaces.json`

```json
{
  "replace": false,
  "values": [
    "examplemod:copper_furnace"
  ]
}
```

Keep `"replace": false` so your entries are added to the defaults instead of replacing them.

## Boulders

What the boulders [Unearthing](Jobs#raise-boulders) workers raise are made of comes from one
file per kind of boulder: `data/<namespace>/palworks/boulders/<kind>.json`. For example, the default
`data/palworks/palworks/boulders/volcanic.json`:

```json
{
  "types": ["fire"],
  "species": ["cobblemon:rolycoly", "cobblemon:groudon"],
  "pattern": "graded",
  "stone": { "minecraft:basalt": 3, "minecraft:blackstone": 3, "minecraft:netherrack": 2 },
  "ores": { "minecraft:coal_ore": 4, "minecraft:nether_quartz_ore": 3, "minecraft:nether_gold_ore": 3, "minecraft:ancient_debris": 1 }
}
```

- **`types`**: the kind is for Pokémon with this type besides Rock (a Rock and Ground Pokémon
  raises badlands boulders). A Pokémon with no other type uses the kind for its own type: the
  default `stone` kind is for `rock`.
- **`species`**: species that raise this kind whatever their types (Groudon raises volcanic
  boulders, the Nacli line salt).
- **`stone`**: its blocks and weights, in order. What the weights and order mean depends on the
  `pattern`:
  - `patches` (if no pattern is given): smooth patches, each block's share by weight.
  - `strata`: level bands, each block in turn, its weight the band's thickness in blocks.
  - `graded`: from the first block at the bottom to the last at the top, its weight its share of
    the height.
  - `shell`: layers from the outside in, by how far each block is from the middle, each block's
    weight its share of the way in.
  - `capped`: the first block over every top surface, the rest in patches below.
- **`ores`**: ores and their weights. About one boulder in eight has ore, each ore block anywhere in
  it, picked by weight among the ores the worker's level has unlocked (below).

Whatever the pattern, about one block in five is any of the stone at random, by weight.

Files for the same kind from different namespaces merge: types and species add up, a block's
weight is the highest given, and a weight of `0` takes a block out. The last file (by namespace)
that gives a pattern sets it. `"replace": true` uses only that file for the kind. A new file name
is a new kind. Every block should drop something for a pickaxe without Silk Touch: only packed and
blue ice are mined with it.

The Unearthing level each ore needs is one table for every kind, in
`data/<namespace>/palworks/boulder_ores/<name>.json` (the default is `ore_levels.json`):

```json
{ "minecraft:gold_ore": 3, "minecraft:diamond_ore": 4, "examplemod:tin_ore": 2 }
```

An ore that isn't listed needs level 1. The same block in several files takes the highest level.
See [Boulders](Boulders) for the default kinds and levels.

## Logs: `#minecraft:logs`

[Lumbering](Jobs#chop-trees) workers treat any block in the vanilla `#minecraft:logs` tag as a log,
and mangrove roots too, and any block in `#minecraft:leaves` as leaves (only natural leaves count
towards a tree). Most mods already add their wood to these tags, so modded trees usually work
without changes.

Cobblemon apricorn trees (`#cobblemon:apricorn_logs` and `#cobblemon:apricorn_leaves`) are always
excluded, so workers never chop them.

## Berries: `#cobblemon:berries`

[Gathering](Jobs#harvest-crops) workers harvest Cobblemon's berry bushes once they're ripe. Only
Cobblemon's own bushes ripen: another mod's block added to the tag is never harvested (though its
tooltip still says Gathering and Watering).

## Work animations

A datapack can give a species its own work animations. See [Work Animations](Work-Animations).
