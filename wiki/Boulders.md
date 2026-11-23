# Boulders

[Mining](Jobs#mine-boulders) workers don't dig into your world. They break up boulders, which
[Unearthing](Jobs#raise-boulders) workers (Rock types, and Groudon) raise out of open ground. A
boulder is ordinary blocks, stone and ore, so it drops what those blocks drop, and you can mine it
yourself too.

Each Unearthing worker keeps one boulder standing. Once its boulder is mined away, it raises
another.

## Kinds

What a boulder is made of goes by the worker's type besides Rock. A plain Rock type raises stone,
and a few species have a kind of their own. In the table, the number after each block is its weight
among the stone, or among the ores.

| Kind | Raised by | Stone | Ores |
|---|---|---|---|
| Stone | Rock types with no other type | stone 5, andesite 2, diorite 2, granite 2, tuff 1 | coal 4, copper 3, iron 3, lapis 1, redstone 1, gold 1, diamond 1 |
| Badlands | Ground (Geodude, Onix, Rhyhorn, Larvitar) | terracotta 4, red sandstone 2, granite 2 | gold 4, iron 3, copper 2, redstone 1 |
| Volcanic | Fire (Magcargo, Carkol, Hisuian Arcanine), Rolycoly, Groudon | blackstone 3, basalt 3, netherrack 2 | coal 4, nether quartz 3, nether gold 3, ancient debris 1 |
| Seabed | Water (Omanyte, Kabuto, Corsola, Binacle, Drednaw) | prismarine 3, dark prismarine 1, clay 2, stone 2 | copper 3, lapis 3, sea lantern 1 |
| Peak | Flying (Aerodactyl, Archen, Minior) | stone 4, calcite 2, andesite 2 | coal 2, iron 3, emerald 2 |
| Fossil bed | Bug (Shuckle, Anorith, Dwebble, Kleavor) | sandstone 4, bone block 2 | coal 4, gold 1, diamond 1 |
| Iron vein | Steel (Aron, Shieldon, Probopass), Nosepass | stone 3, tuff 2, deepslate 2 | coal 1, iron 5, gold 1, raw iron block 1 |
| Copper vein | Electric (Alolan Geodude) | granite 3, stone 3 | copper 5, redstone 2, raw copper block 1 |
| Glacier | Ice (Amaura, Hisuian Avalugg) | calcite 3, diorite 3 | iron 2, lapis 3, diamond 1 |
| Geode | Psychic (Lunatone, Solrock) | calcite 3, smooth basalt 2 | amethyst block 1 |
| Mossy | Grass (Lileep) | mossy cobblestone 3, moss block 2, stone 2 | copper 3, iron 2 |
| Deep | Dragon (Tyrunt) | deepslate 4, tuff 2 | deepslate coal 2, deepslate redstone 2, deepslate gold 3, deepslate diamond 1 |
| Dripstone | Poison (Glimmet) | dripstone block 3, tuff 2, stone 2 | copper 2, redstone 3, lapis 1 |
| Blackstone | Dark (Tyranitar) | blackstone 4, deepslate 2 | gilded blackstone 3, nether gold 2, deepslate diamond 1 |
| Gem | Fairy (Carbink) | calcite 4, diorite 2 | amethyst block 2, lapis 2, diamond 2, emerald 2 |
| Salt | the Nacli line | calcite 3, diorite 3, quartz block 1 | nether quartz 1 |

Datapacks can change these and add more (see [Datapacks and Tags](Datapacks-and-Tags#boulders)).

## Size and ore

A higher [Unearthing level](Suitabilities#work-levels) raises a bigger boulder, with more of it
ore:

| Level | Blocks | Widest | Height | Ore |
|---|---|---|---|---|
| 1 | 5 to 8 | 3 blocks | 2 to 3 | 10% |
| 2 | 10 to 15 | 4 blocks | 2 to 3 | 15% |
| 3 | 17 to 24 | 4 blocks | 2 to 4 | 20% |
| 4 | 28 to 38 | 5 blocks | 3 to 4 | 25% |
| 5 | 42 to 56 | 6 blocks | 3 to 4 | 30% |

Each block is ore or stone by the level's chance first. Then the block is picked by weight from
the kind's stone, or from the ores the level has unlocked (below). If none is unlocked yet, the
block is stone.

## Ores by level

The level an ore needs is the same in every kind. A kind rich in gold has more gold, not gold at a
lower level.

| Level | Ores it unlocks |
|---|---|
| 1 | coal, copper, nether quartz, amethyst block |
| 2 | iron, nether gold, gilded blackstone, sea lantern |
| 3 | gold, lapis, redstone |
| 4 | diamond, emerald, raw iron block, raw copper block |
| 5 | ancient debris |

Nether gold ore and gilded blackstone come at level 2, as they only drop gold nuggets.

## Shapes

Every boulder's shape is different. Each is one of five styles, rolled at random, with rough edges:

- **Dome**: round and squat
- **Peak**: narrow and tall, its top off the middle
- **Slab**: wide and low, flat on top
- **Lopsided**: leaning to one side
- **Twin**: two lumps of different sizes, joined at the bottom

Miners take a boulder from the top, so nothing is left floating, and nothing is out of reach.
