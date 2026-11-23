# Boulders

[Mining](Jobs#mine-boulders) workers don't dig into your world. They break up boulders, which
[Unearthing](Jobs#raise-boulders) workers (Rock types, and Groudon) raise out of open ground. A
boulder is ordinary blocks, stone and sometimes ore, so it drops what those blocks drop, and you can
mine it yourself too.

Each Unearthing worker keeps one boulder standing. Once its boulder is mined away, it raises
another. Raising one is hungry work: a point of fullness for every 20 blocks, and at least one.

## Kinds

What a boulder is made of goes by the worker's type besides Rock. A plain Rock type raises stone,
and a few species have a kind of their own. Each kind lays its stone out in a pattern (below), so it
looks like real rock rather than a random mix.

| Kind | Raised by | Stone | Pattern | Ores |
|---|---|---|---|---|
| Stone | Rock types with no other type | stone, andesite, diorite, granite, tuff | patches | coal, copper, iron, lapis, redstone, gold, diamond |
| Badlands | Ground (Geodude, Onix, Rhyhorn, Larvitar) | terracotta, orange, yellow and brown terracotta, red sandstone | strata | gold, iron, copper, redstone |
| Volcanic | Fire (Magcargo, Carkol, Hisuian Arcanine), Rolycoly, Groudon | basalt, blackstone, netherrack | graded, basalt at the bottom | coal, nether quartz, nether gold, ancient debris |
| Seabed | Water (Omanyte, Kabuto, Corsola, Binacle, Drednaw) | prismarine, dark prismarine, clay, stone | patches | copper, lapis, sea lantern |
| Peak | Flying (Aerodactyl, Archen, Minior) | stone and andesite, capped with calcite | capped | coal, iron, emerald |
| Fossil bed | Bug (Shuckle, Anorith, Dwebble, Kleavor) | sandstone, bone block, smooth sandstone | strata | coal, gold, diamond |
| Iron vein | Steel (Aron, Shieldon, Probopass), Nosepass | deepslate, tuff, stone | graded, deepslate at the bottom | coal, iron, gold, raw iron block |
| Copper vein | Electric (Alolan Geodude) | granite, stone | patches | copper, redstone, raw copper block |
| Glacier | Ice (Amaura, Hisuian Avalugg) | calcite outside, diorite inside | shell | iron, lapis, diamond |
| Geode | Psychic (Lunatone, Solrock) | smooth basalt outside, calcite inside | shell | amethyst block |
| Mossy | Grass (Lileep) | mossy cobblestone and stone, capped with moss | capped | copper, iron |
| Deep | Dragon (Tyrunt) | deepslate, tuff | graded, deepslate at the bottom | deepslate coal, redstone, gold and diamond |
| Dripstone | Poison (Glimmet) | tuff, stone, dripstone block | graded, tuff at the bottom | copper, redstone, lapis |
| Blackstone | Dark (Tyranitar) | deepslate, blackstone | graded, deepslate at the bottom | gilded blackstone, nether gold, deepslate diamond |
| Gem | Fairy (Carbink) | calcite outside, diorite inside | shell | amethyst block, lapis, diamond, emerald |
| Salt | the Nacli line | calcite, diorite, quartz block | strata | nether quartz |

The patterns:

- **Patches**: each stone in smooth patches, in proportion to its weight.
- **Strata**: level bands, each stone in turn, slightly tilted, like the bands in a badlands hill.
- **Graded**: from the first stone at the bottom to the last at the top, blending where they meet.
- **Shell**: layers from the outside in, like a geode, by how far each block is from the middle, so
  even a small boulder has both.

Whatever the pattern, about one block in five is any of the kind's stone at random, and the edges
between bands and layers waver, so no boulder looks ruled.
- **Capped**: one block over every top surface (moss, or calcite like snow), the rest in patches
  below.

Datapacks can change these and add more (see [Datapacks and Tags](Datapacks-and-Tags#boulders)).

## Size

A higher [Unearthing level](Suitabilities#work-levels) raises a bigger boulder:

| Level | Blocks | Widest | Height |
|---|---|---|---|
| 1 | 5 to 8 | 3 blocks | 2 to 3 |
| 2 | 10 to 15 | 4 blocks | 2 to 3 |
| 3 | 17 to 24 | 4 blocks | 2 to 4 |
| 4 | 28 to 38 | 5 blocks | 3 to 4 |
| 5 | 42 to 56 | 6 blocks | 3 to 4 |

## Ore

Most boulders are only stone. About **one in ten** has ore, at every level. In a boulder with ore,
each ore block is anywhere in it at random, outside or in, and each is one of the kind's ores the
level has unlocked:

| Level | Ore blocks in a boulder with ore |
|---|---|
| 1 | 1 to 2 |
| 2 | 2 to 3 |
| 3 | 2 to 6 |
| 4 | 4 to 8 |
| 5 | 6 to 12 |

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

Nether gold ore and gilded blackstone come at level 2, as they only drop gold nuggets. A boulder
whose kind has no ore unlocked at the worker's level is stone.

## Shapes

Every boulder's shape is different. Each is built from rounded lumps, the way vanilla's mossy
forest rocks are, in one of five styles rolled at random:

- **Dome**: one round lump
- **Peak**: a smaller, taller lump on a broad base, off the middle
- **Slab**: one wide lump, flat on top
- **Lopsided**: three lumps in a row, each lower than the last, so it leans
- **Twin**: two lumps of different sizes with a low saddle between

Their sizes, positions and turn are random, and their surface has a smooth roughness, so it's lumpy
rather than jagged. Miners take a boulder from the top, so nothing is left floating, and nothing is
out of reach.
