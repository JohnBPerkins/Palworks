# Jobs

A **job** is a piece of work a [worker](Workers) can do. Workers find jobs on their own nearby
(32 blocks by default). All you have to do is set up the blocks.

| Job | Who does it | Status |
|---|---|---|
| [Light furnaces](#light-furnaces) | Kindling | Working |
| [Water crops](#water-crops) | Watering | Working |
| [Harvest crops](#harvest-crops) | Gathering | Working |
| [Cut tall plants](#cut-tall-plants) | Gathering | Working |
| [Plant crops](#plant-crops) | Planting | Working |
| [Nether wart](#nether-wart) | Nether Work | Working |
| [Chop trees](#chop-trees) | Lumbering | Working |
| [Pick up drops](#pick-up-drops) | Transporting | Working |
| [Deliver items](#deliver-items) | Transporting | Working |
| [Restock troughs](#restock-troughs) | Transporting | Working |
| [Mine crystals](#mine-crystals) | Mining | Working |
| [Raise boulders](#raise-boulders) | Unearthing | Working |
| [Mine boulders](#mine-boulders) | Mining | Working |

Crafting (Handiwork), brewing (Medicine Production), and work for Generating Electricity, Cooling
and Farming are planned; nothing uses them yet.

## Light furnaces

**Who:** Kindling (Fire types)
**Blocks:** furnace, blast furnace, smoker (the `#palworks:furnaces` tag)

Put smeltable items in the input slot. **You don't need fuel.** A Kindling worker walks over, uses
Ember on the furnace and lights it. How long one Ember burns depends on its
[work level](Suitabilities#work-levels): at level 1 it smelts one item, so the worker stays and
relights the furnace until the input slot is empty. Higher levels burn longer, so the worker lights
a furnace, goes on to light others, and comes back once it goes out.

Workers only light a furnace whose input can be smelted in it, so a blast furnace full of raw food
is left alone. So is one with no room in its output slot for what it would smelt, until you take
some out.

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

## Cut tall plants

**Who:** Gathering, for now. These will probably move to the particular Pokémon suited to each
plant later.

A Gathering worker cuts **sugar cane**, **cactus**, **bamboo**, **kelp**, **weeping vines** and
**twisting vines** as soon as they've grown a second block. It cuts everything but the block the
plant grows from, which stays and grows back, as you'd farm them yourself. Each block drops what
it would by hand (a vine only drops itself now and then), and a Transporting worker picks the drops
up.

The worker stands up to two blocks away and works from the ground beside the plant, so it can cut
kelp from the shore and weeping vines under a low ceiling. Weeping vines hanging from a high
ceiling are out of its reach.

## Plant crops

**Who:** Planting (Grass types)

A Planting worker plants on **farmland** with an empty space above it, from the stack of seeds in
its seed pouch. It can't take seeds out of a chest itself. When it runs out and a nearby **chest**
or **trapped chest** has seeds it can plant, it asks for some, and carries on with its other work
meanwhile:

1. A Transporting worker takes a stack of the seeds from the chest.
2. It brings them to wherever the planter is and throws them to its feet.
3. The planter picks them up a moment later, as a player would, and plants them.

A transporter that can't get the seeds to the planter puts them back in a chest, and leaves that
planter's seeds to other transporters for 30 seconds before it tries again.

You can also throw seeds (with Q) next to a planter that has room for them, and it picks them up.
A worker that both plants and transports, such as a Grass/Flying type, takes its own seeds from
chests. Seeds it has nowhere to plant go back in a chest, so they don't take up its slots.

Any item that plants a crop or stem counts as seeds, so modded and Cobblemon seeds work too.
Pitcher pods can't be planted.

## Nether wart

**Who:** Nether Work (Ghost types)

Works like [planting](#plant-crops) and [harvesting](#harvest-crops), but on **soul sand** with
**nether wart**, brought from a chest by a Transporting worker. Regular Planting and Gathering workers leave nether wart alone.

A worker with both Nether Work and Lumbering also chops **crimson and warped trees** (see
[Chop trees](#chop-trees)).

## Chop trees

**Who:** Lumbering (Fighting types, plus species that cut: Scizor, Kingambit, Aegislash...; see
[who can](Lumbering-Workers))

A Lumbering worker chops down a **whole tree** at once, then replants it itself: a sapling at the
base, four for a 2×2 trunk, and a propagule under a mangrove. Trees grown into each other come
down together, and each gets its sapling back. Afterwards a Transporting worker picks up the logs.

How big a tree it chops depends on its [work level](Suitabilities#work-levels): any lumberjack
chops an oak or a birch, but a giant spruce needs level 4. A tree too big for every lumberjack
nearby is left standing.

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

A Transporting worker picks up items that workers or players dropped, wherever they land, and
carries them. That covers:

- a harvest's drops, and everything a chopped tree drops, including the saplings, sticks and
  apples its leaves drop as they decay over the next few minutes;
- items a player throws (with Q, or out of their inventory);
- what drops from blocks a player mines.

Everything else on the ground is left alone, so transporters don't empty mob farms, water- or
piston-harvested farms, or dispensers. A player's death drops stay where they fell.

It goes round the items nearest first, picking each up from beside it as a player would (and
anything it passes on the way), taking everything within 3 blocks of where they landed (the
`pickup_radius` [setting](Configuration)) in one go. One that can fly [flies](Workers#flying) to
items it can't walk to, like ones on top of a tree. It fills up before a trip: anything while it has a free slot,
then only items matching what it carries. It has one slot for each
[work level](Suitabilities#work-levels) in Transporting. Items no worker can get to are tried again later, less
often each time, until they despawn.

It stands right in front of a chest to put items in or take them out.

## Deliver items

**Who:** Transporting

A transporter carrying items takes them to the nearest chest with room. [Trough food](Hunger) goes
to a trough with room first. If no chest has room, or it can't get to one, it keeps the items,
shows a chest over its head, and carries on with its other jobs.

## Restock troughs

**Who:** Transporting

When a [trough](Hunger) in reach has less than 16 feed left, a transporter that can get to it
fetches a stack of food from a chest that has some and takes it to the trough.

## Mine crystals

**Who:** Mining (Ground, Rock and Fighting types)

Mining workers mine crystals once they're fully grown, getting exactly what you'd get breaking them
with a pickaxe: a wooden one at level 1, a diamond one at level 2, and a diamond one with Fortune I
to III at levels 3 to 5 (see [work levels](Suitabilities#work-levels)). They leave what the
crystals grow from alone, so the crystals grow back. And they only
mine a crystal where another will grow: one you've put somewhere else, as decoration or a light,
is left alone.

- **Amethyst clusters** on budding amethyst (4 shards, more with Fortune). Buds aren't mined, and the budding
  amethyst grows new ones.
- **Tumblestone clusters** of every colour (4 tumblestone, more with Fortune). The worker puts a new tumblestone back
  where the cluster was, using one of the four. It grows again if there's lava or a magma block
  next to it. A cluster under water isn't put back, because tumblestone can't be placed in water.
- **Type gem clusters** at their last stage, on a gem block or a crystal core (2-4 gems, one more
  with Fortune III). Gem
  blocks and the deepslate crystal core are never mined. Gem blocks joined to a core grow new
  clusters. Left alone, a fully grown cluster turns into a gem block, so a formation a worker
  keeps mined stays the same size.

A Transporting worker then picks up the drops.

## Raise boulders

**Who:** Unearthing (Rock types, and Groudon)
**Blocks:** none: it needs open, flat ground

An Unearthing worker stamps the ground and a boulder rises out of it, for Mining workers to break
up. Each worker keeps one boulder of its own standing, and raises the next once that one is mined
away. It raises one only when it has no other work to do. It's hungry work: a boulder costs a
point of fullness for every 20 blocks, and at least one.

What a boulder is made of depends on the worker's type besides Rock. A Geodude (Rock and Ground)
raises banded terracotta, an Alolan Geodude (Rock and Electric) granite with copper, and an Aron
(Steel) deepslate and stone with iron. About one boulder in ten has ore, here and there in
it. How big it is, and how much ore the ones with ore have, depend on its
[work level](Suitabilities#work-levels). See [Boulders](Boulders) for every kind.

A boulder only goes on open, flat ground in the worker's work range, with room round it:

- Its footprint needs nothing on it at all, not even grass or a flower. **Nothing is ever broken or
  replaced to make room.**
- Two blocks all round it need nothing solid on them: a flower there is fine, and is left alone.
- All of it, footprint and border, must be full blocks at one height: no steps, holes, slabs or
  water.

A pasture covered in grass has nowhere to put one, so clear a patch of ground for it (or lay a
floor of stone or dirt). A pastured worker only uses ground inside its pasture's range, and one in
your party ground near you.

## Mine boulders

**Who:** Mining (Ground, Rock and Fighting types)
**Blocks:** the blocks of boulders that [Unearthing](#raise-boulders) workers raised

Miners break boulders down a block at a time, from the top. Natural stone and ore in the world are
never mined: only boulders, and [crystals](#mine-crystals). Each block drops what it would for a
player with the pickaxe the miner's [work level](Suitabilities#work-levels) stands for: stone gives
cobblestone, and ore gives more with Fortune from level 3.

A level 1 miner mines with a wooden pickaxe, which gets nothing from iron, copper, lapis, gold,
redstone, diamond or emerald ore. It leaves those for a miner of level 2 or more, so with only
level 1 miners some of a boulder stays standing.

A Transporting worker then picks up the drops.

## Job priority

When several jobs are available, a worker weighs how close each one is against how important it
is. From most to least important:

1. Deliver items, bring seeds to a planter, pick up seeds brought to it
2. Pick up drops, restock troughs
3. Harvest crops
4. Light furnaces, mine crystals, mine boulders
5. Fetch seeds from a chest
6. Plant crops
7. Chop trees
8. Water crops

An Unearthing worker raises a boulder only when none of these is left for it.

Eating at a [trough](Hunger) when hungry and going to [bed](Sleep) at bedtime come before any work.
While there's a ripe crop to harvest, Planting workers don't plant.
