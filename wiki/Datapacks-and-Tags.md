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
`gathering`, `lumbering`, `mining`, `medicine_production`, `cooling`, `transporting`, `nether` and
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

## `#palworks:ores`

The blocks [Mining](Jobs#mine-ores) workers will go to once they mine ores (for now they only
[mine crystals](Jobs#mine-crystals), which need no tag). By default it holds
every vanilla ore: coal, iron, copper, gold, diamond, emerald, lapis and redstone (stone and
deepslate versions), plus nether gold ore and nether quartz ore.

To add ores from another mod, create this file in your datapack:

`data/palworks/tags/block/ores.json`

```json
{
  "replace": false,
  "values": [
    "examplemod:tin_ore",
    "examplemod:deepslate_tin_ore"
  ]
}
```

Keep `"replace": false` so your entries are added to the defaults instead of replacing them. The
furnaces tag works the same way.

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
