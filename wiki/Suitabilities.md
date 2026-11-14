# Suitabilities

A **suitability** (or work skill) is a kind of work a Pokémon can do, at a **level** from 1 to 5. A
Pokémon gets suitabilities from its **types**, and some species get extra ones. A Pokémon can only
take a [job](Jobs) if it has that job's suitability.

To look up a particular Pokémon, see [Work skills by Pokémon](Work-Skills-by-Pokemon), or its PC
and summary screens in game.

## From types

<!-- generated:type-table -->

Dual-type Pokémon get the suitabilities of both types: Gyarados (Water/Flying) waters crops and
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
| Planting | Take seeds from chests and plant farmland | Working |
| Gathering | Harvest ripe crops and berries | Working |
| Lumbering | Chop trees and replant saplings | Working |
| Transporting | Pick up drops, deliver them to chests and troughs, restock troughs | Working |
| Nether Work | Plant and harvest nether wart; chop crimson and warped trees (with Lumbering) | Working |
| Mining | Mine ores | Not yet |
| Handiwork | Crafting | Not yet |
| Medicine Production | Brewing | Not yet |
| Generating Electricity | Powering devices | Not yet |
| Cooling | Cooling containers | Not yet |
| Farming | Producing ranch items | Not yet |

The PC and summary only show the skills that have work so far.

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
  types that give the job, the better level counts.
- **From a species entry:** the level its stats give, whatever its earlier forms were. Kingambit
  chops at 4. A datapack can give a species a level or more above that (see
  [Datapacks and Tags](Datapacks-and-Tags#work-skills)).
- An admin can set a species' level outright in game with `/palworks suitability set` (see
  [Commands](Commands)).

A Pokémon's level only changes as it evolves or changes form, not as it levels up.

Levels so far change how often a Watering worker fertilizes crops: 10% a level by default (the
`fertilize_chance_per_level` [setting](Configuration)), so a level 1 Squirtle fertilizes 10% of
the crops it waters and a level 3 Blastoise 30%.
