# Advancements

Palworks has its own advancement tab. Your workers earn the advancements for you: each one goes to
the Pokémon's owner, as long as you're online when it happens. Missed one while you were away?
Nearly all of them come round again.

**Frames:** a plain square is a task, a rounded one a goal, a spiky one a challenge.

## Getting started

| Advancement | How to get it | Frame |
|---|---|---|
| Palworks | Put a Pokémon to work | root |
| Day One | Have a worker finish a job | task |
| Fired Up | Have a worker light a furnace, blast furnace or smoker | task |
| Brewmaster | Have a worker light a brewing stand | task |
| Rain Dance | Have a worker water a crop | task |
| Sow and Tell | Have a worker plant a seed | task |
| Reap What You Sow | Have a worker harvest a ripe crop | task |
| Tall Order | Have a worker cut sugar cane, cactus, bamboo or kelp | task |
| Wart and All | Have a worker harvest nether wart | task |
| Timber! | Have a worker fell a tree | task |
| Special Delivery | Have a worker deliver what it carries | task |
| Room Service | Have a worker restock a trough | task |
| Rock Collector | Have a worker mine a crystal | task |
| Ground Breaking | Have a worker raise a boulder | task |

## Mining

| Advancement | How to get it | Frame |
|---|---|---|
| Rough Diamonds | Have a worker mine diamond ore from a [boulder](Boulders) | goal |
| Gem Collection | Have your workers mine a gem cluster of every type | challenge |

Diamond ore comes in boulders raised at Unearthing level 4 or more.

## Skill and size

| Advancement | How to get it | Frame |
|---|---|---|
| Old Growth | Have a worker fell a tree of 128 logs or more | goal |
| Jack of All Trades | Have your workers do a job of every work skill that has jobs | challenge |
| Small Business | Have 5 Pokémon working at once | task |
| Workforce | Have 15 Pokémon working at once | goal |

See [work levels](Suitabilities#work-levels) for what a worker's level is.

## Looking after workers

| Advancement | How to get it | Frame |
|---|---|---|
| Chow Time | Have a worker eat from a [trough](Hunger) | task |
| Sweet Dreams | Have a worker sleep in a [bed](Sleep) | task |
| Power Nap | Have a worker nap in a bed in the day | task |
| Rude Awakening | Wake a sleeping worker by hitting it (hidden until you do) | task |
| Walkout | Work a worker so hard it goes [on strike](Mood) | task |
| Back on the Clock | Have a worker on strike cheer up and go back to work | task |

Rude Awakening goes to whoever hit the worker, not its owner.

## For datapacks

The advancements are ordinary datapack files, in `data/palworks/advancement/workers/`, so a
datapack can change them or add more. Palworks adds three triggers for them:

- `palworks:worker_job`: one of your workers did some work. Optional conditions: `jobs` (job type
  ids such as `chop_tree`), `skill` (a work skill id such as `nether`), `min_level` (the worker's
  level in that skill, or with no `skill` in every skill the job took), `blocks` (the block it
  worked: an id, a list or a `#tag`), `min_amount` (a felled tree's logs) and `labels` (Cobblemon
  species labels such as `legendary`, any of them).
- `palworks:worker_event`: `event` is one of `ate`, `slept_in_bed`, `napped_in_bed`,
  `went_on_strike`, `back_to_work` and `woke_a_worker`.
- `palworks:workers`: at least `min` of your Pokémon are working at once.
