# Jobs

A **job** is a piece of work a [worker](Workers) can do. Workers find jobs on their own nearby
(32 blocks by default). All you have to do is set up the blocks.

| Job | Who does it | Status |
|---|---|---|
| [Light furnaces](#light-furnaces) | Kindling | Working |
| [Water crops](#water-crops) | Watering | Working |
| [Harvest crops](#harvest-crops) | Gathering | Working |
| [Plant crops](#plant-crops) | Planting | Working |
| [Nether wart](#nether-wart) | Nether Work | Working |
| [Chop trees](#chop-trees) | Lumbering | Working |
| [Pick up drops](#pick-up-drops) | Transporting | Working |
| [Deliver items](#deliver-items) | Transporting | Working |
| [Restock troughs](#restock-troughs) | Transporting | Working |
| [Mine ores](#mine-ores) | Mining | Not yet |

Crafting (Handiwork), brewing (Medicine Production), and work for Generating Electricity, Cooling
and Farming are planned; nothing uses them yet.

## Light furnaces

**Who:** Kindling (Fire types)
**Blocks:** furnace, blast furnace, smoker (the `#palworks:furnaces` tag)

Put smeltable items in the input slot. **You don't need fuel.** A Kindling worker walks over, uses
Ember on the furnace and lights it, and keeps relighting it until the input slot is empty.

Workers only light a furnace whose input can be smelted in it, so a blast furnace full of raw food
is left alone.

## Water crops

**Who:** Watering (Water types)

A Watering worker waters growing crops, which keeps their farmland wet. Sometimes it fertilizes the
crop as it does, just as bone meal would: the stronger the Pokémon, the more often (see
[work levels](Suitabilities#work-levels)). A crop can be watered again a minute after it was last
watered (the `watering_cooldown_seconds` [setting](Configuration)). Watering comes after other work,
so a worker that can do something else does that first.

## Harvest crops

**Who:** Gathering (Grass types and some berry-picking species)

Workers harvest these when they're ripe, getting exactly what you'd get harvesting by hand:

- Wheat, carrots, potatoes and beetroot, and modded crops
- Pumpkins and melons (the fruit, not the stem)
- Torchflowers and pitcher plants (broken, not replanted)
- Cocoa beans, sweet berries and glow berries
- Cobblemon berry trees, apricorns, Galarica nut bushes, hearty grains, mints, vivichokes and
  revival herbs

A harvested crop is **replanted in place** by the worker that harvested it, using one seed from
what it dropped. Berry bushes regrow on their own. A Transporting worker then picks up the drops.

Nether wart is Nether work: see [below](#nether-wart).

## Plant crops

**Who:** Planting (Grass types)

A Planting worker plants on **farmland** with an empty space above it. It takes seeds from a nearby
**chest** or **trapped chest**, up to a stack in its seed pouch, and keeps them until it has planted
them all.

Any item that plants a crop or stem counts as seeds, so modded and Cobblemon seeds work too.
Pitcher pods can't be planted.

## Nether wart

**Who:** Nether Work (Ghost types)

Works like [planting](#plant-crops) and [harvesting](#harvest-crops), but on **soul sand** with
**nether wart** from a chest. Regular Planting and Gathering workers leave nether wart alone.

A worker with both Nether Work and Lumbering also chops **crimson and warped trees** (see
[Chop trees](#chop-trees)).

## Chop trees

**Who:** Lumbering (Fighting types, plus species that cut: Scizor, Kingambit, Aegislash...; see
[who can](Lumbering-Workers))

A Lumbering worker chops down a **whole tree** at once, then replants it itself: a sapling at the
base, four for a 2×2 trunk, and a propagule under a mangrove. Afterwards a Transporting worker picks
up the logs.

To protect your builds, a group of logs only counts as a tree if:

- it has at least **4 natural leaf blocks** within 2 blocks of its logs (leaves you placed don't
  count),
- it stands on the ground, and
- nothing but logs, leaves, air, vines, ground, plants or moss carpet touches its logs. A log
  touching planks, glass or any other building block rules the whole tree out.

Cobblemon apricorn trees are always left alone. Any block in the `#minecraft:logs` tag counts as a
log, and mangrove roots too, so trees from other mods work. See
[Datapacks and Tags](Datapacks-and-Tags).

## Pick up drops

**Who:** Transporting (Flying types)

After a harvest or a tree is chopped, a Transporting worker picks up the items lying within 3 blocks
of the spot (the `pickup_radius` [setting](Configuration)) and carries them. It fills up before a
trip: anything while it has a free slot, then only items matching what it carries.

## Deliver items

**Who:** Transporting

A transporter carrying items takes them to the nearest chest with room. [Trough food](Hunger) goes
to a trough with room first. If no chest has room, it keeps the items and carries on with its other
jobs.

## Restock troughs

**Who:** Transporting

When a [trough](Hunger) in reach has less than 16 feed left, a transporter fetches a stack of food
from a chest that has some and takes it to the trough.

## Mine ores

**Who:** Mining (Rock and Ground types)
**Status:** not yet

Mining is switched off until workers can break ore: they don't look for ores at all yet. The blocks
that will count as ore come from the `#palworks:ores` tag. See
[Datapacks and Tags](Datapacks-and-Tags).

## Job priority

When several jobs are available, a worker weighs how close each one is against how important it
is. From most to least important:

1. Deliver items
2. Pick up drops, restock troughs
3. Harvest crops
4. Light furnaces
5. Take seeds from a chest
6. Plant crops
7. Chop trees
8. Water crops

Eating at a [trough](Hunger) when hungry and going to [bed](Sleep) at bedtime come before any work.
While there's a ripe crop to harvest, Planting workers don't plant.
