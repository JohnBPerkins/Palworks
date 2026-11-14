# Getting Started

## Requirements

- Minecraft **1.21.1**
- [Cobblemon](https://cobblemon.com) **1.7.1**
- **Fabric Loader + Fabric API**, or **NeoForge**
- [Architectury API](https://modrinth.com/mod/architectury-api) **13.0.8** or later (the shared
  library Palworks uses to run on both loaders)
- Java **21** (required to play 1.21.1; bundled with most modern launchers)

## Installing

Palworks isn't on a mod host yet, so for now you build the jar yourself. You need JDK 21 and Git.

```bash
git clone https://github.com/JohnBPerkins/Palworks.git
cd Palworks
./gradlew build
```

Then:

1. Install your loader (Fabric + Fabric API, or NeoForge), Architectury API and Cobblemon for
   Minecraft 1.21.1.
2. Take the jar for your loader, `fabric/build/libs/palworks-fabric-*.jar` or
   `neoforge/build/libs/palworks-neoforge-*.jar`, and put it in your `mods/` folder.
3. Launch the game.

On a server, Palworks goes on the server and every player's game, like Cobblemon.

## Your first worker: an automatic furnace

The quickest way to see Palworks working is a furnace run by a Fire-type Pokémon.

1. Place a **furnace** and put something smeltable in the top slot, for example raw iron. You
   don't need fuel.
2. Send out a **Fire-type** Pokémon you own, such as Charmander, near the furnace: within 32 blocks
   of it, and within 24 blocks of you (a party Pokémon only works near its owner).
3. The Pokémon walks to the furnace, uses Ember on it, and lights it. It keeps relighting the
   furnace until everything in the input slot is smelted.

Blast furnaces and smokers work the same way.

## A small automatic farm

For a farm that plants, waters, harvests and replants itself:

1. Till some **farmland** and put a **chest** nearby with **seeds** in it.
2. Send out a **Grass-type** Pokémon. Grass types plant and harvest.
3. Send out a **Water-type** Pokémon to water the crops, which keeps the farmland wet and sometimes
   fertilizes them.
4. Send out a **Flying-type** Pokémon to pick up the harvest, and put a **chest** with free space
   nearby for it to go into.

The Grass type takes seeds from the chest and plants the empty farmland. When crops are fully
grown, it harvests them and replants them in place. The Flying type picks up what was harvested
and puts it in a chest.

Without step 4 the farm still plants and harvests, but nobody picks up the harvest, so it stays
on the ground.

For a farm that runs while you're away, put the workers in a Cobblemon **pasture**, with a
[trough](Hunger) of berries to eat from and [beds](Sleep) to sleep in.

See [Jobs](Jobs) for every job and [Suitabilities](Suitabilities) to find out what your Pokémon can
do.
