# Suitabilities

A **work skill** is a kind of work a Pokémon can do, at a **level** from 1 to 5. (The datapack
files and the `/palworks suitability` command call it a suitability: it's the same thing.) A
Pokémon gets work skills from its **types**, and some species get extra ones. A Pokémon can only
take a [job](Jobs), a single piece of work such as lighting one furnace, if it has that job's work
skill.

To look up a particular Pokémon, see [Work skills by Pokémon](Work-Skills-by-Pokemon), or its PC
and summary screens in game.

To find the Pokémon that have a particular work skill, use the **Pokédex**: the small cog at the
right of its search bar, beside the "search by" button, filters the list by work skill. Each click
picks the next skill (hover it to see which), and after the last it goes back to showing every
Pokémon. It lists every species that can have the skill in any of its forms, including the ones
you haven't seen yet (shown as the Pokédex shows any it doesn't know), and works together with the
search and the filter at the bottom of the list: pick "Owned" there to see only the ones you have.

## From types

<!-- generated:type-table -->

Dual-type Pokémon get the work skills of both types: Gyarados (Water/Flying) waters crops and
transports.

## Every work skill

Each skill's page lists every Pokémon that can do it, at its level, and why: its type, or an entry
for its species.

<!-- generated:skill-table -->

## What each work skill does today

| Work skill | Jobs | Status |
|---|---|---|
| Kindling | Light furnaces, blast furnaces and smokers | Working |
| Watering | Water growing crops, sometimes fertilizing them | Working |
| Planting | Plant farmland, with seeds a Transporting worker brings | Working |
| Gathering | Harvest ripe crops and berries | Working |
| Lumbering | Chop trees and replant saplings | Working |
| Transporting | Pick up drops, deliver them to chests and troughs, restock troughs | Working |
| Nether Work | Plant and harvest nether wart; chop crimson and warped trees (with Lumbering) | Working |
| Mining | Mine boulders, amethyst, tumblestone and type gems | Working |
| Stoneshaping | Raise boulders for miners | Working |
| Handiwork | Crafting | Not yet |
| Medicine Production | Brewing | Not yet |
| Generating Electricity | Powering devices | Not yet |
| Cooling | Cooling containers | Not yet |
| Farming | Producing ranch items | Not yet |

The PC and summary only show the skills that have work so far. Hover them there to see what each
one does.

## Work levels

A Pokémon's level for a job (1 to 5) comes from how strong it is: its **base stat total**.

| Base stat total | Level | For example |
|---|---|---|
| under 350 | 1 | Squirtle (314), Magikarp (200) |
| 350 to 449 | 2 | Wartortle (405) |
| 450 to 549 | 3 | Blastoise (530), Vaporeon (525), Lapras (535) |
| 550 to 649 | 4 | Dragonite (600), most legendaries |
| 650 or more | 5 | Kyogre (670), Mewtwo (680) |

- **From a type:** one level less for each earlier form in the Pokémon's evolution line that
  didn't have that type. Hisuian Typhlosion does Kindling at 3, but Nether work at only 1, as
  Cyndaquil and Quilava were Fire but not Ghost. Vaporeon waters at 2, as Eevee was Normal. With two
  types that give the skill, the better level counts.
- **From a species entry:** the level its stats give, whatever its earlier forms were. Kingambit
  chops at 4. A datapack can give a species a level or more above that (see
  [Datapacks and Tags](Datapacks-and-Tags#work-skills)).
- An admin can set a species' level outright in game with `/palworks suitability set` (see
  [Commands](Commands)).

A Pokémon's level only changes as it evolves or changes form, not as it levels up.

What a level changes, so far:

| Skill | Level 1 | Level 2 | Level 3 | Level 4 | Level 5 |
|---|---|---|---|---|---|
| **Kindling**: items one Ember smelts | 1 | 2 | 4 | 8 | 16 |
| **Lumbering**: the biggest tree it chops | 12 logs | 32 logs | 64 logs | 128 logs | 256 logs |
| **Transporting**: inventory slots | 1 | 2 | 3 | 4 | 5 |
| **Mining**: mines as with | a wooden pickaxe | a diamond pickaxe | a diamond pickaxe with Fortune I | Fortune II | Fortune III |
| **Stoneshaping**: blocks in a boulder | 5-8 | 10-15 | 17-24 | 28-38 | 42-56 |
| **Stoneshaping**: of them ore | 10% | 15% | 20% | 25% | 30% |
| **Watering**: crops it fertilizes | 10% | 20% | 30% | 40% | 50% |

- **Kindling:** a level 1 kindler stays at its furnace and relights it for each item. From level 2
  it lights a furnace and moves on to the next, so one kindler keeps several going. Blast furnaces
  and smokers cook twice as fast, so they get through twice as many items on one Ember, as with
  real fuel.
- **Lumbering:** oaks, birches, spruces and acacias are 12 logs or fewer, so any lumberjack chops
  them. Cherries and big oaks need level 2, dark oaks and mangroves level 3, and giant spruces and
  jungle trees level 4. A tree too big for every lumberjack you have is left standing.
- **Mining:** every pickaxe gets a crystal's full loot (4 amethyst shards, where a hand gets 2), so
  levels 1 and 2 mine crystals the same. Fortune gives more amethyst and tumblestone, and type gem
  clusters a gem more a third, two thirds or all of the time. In a boulder, a level 1 miner's wooden
  pickaxe gets nothing from most ores, so it leaves them for a better miner.
- **Stoneshaping:** a higher level raises bigger boulders with more ore in them, and unlocks rarer
  ores: gold, lapis and redstone at level 3, diamond and emerald at 4, ancient debris at 5 (see
  [Boulders](Boulders#ores-by-level)).
- **Watering:** 10% a level is the default; the `fertilize_chance_per_level`
  [setting](Configuration) changes it.

The Pokémon's work skills tooltip, on the PC and summary screens, says what its levels give. Hold
Shift over it to see what every level gives, with the Pokémon's own level in white.
Planting, Gathering and Nether work don't change with level yet, so those screens show their
icons with no number.
