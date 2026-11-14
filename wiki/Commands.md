# Commands

All Palworks commands start with `/palworks` and need **operator permissions** (level 2, the same
as `/gamemode`). You don't need them to play.

The `suitability` and `config` commands also work from the server console; the `worker` ones need
a player. "Nearest" means the first of your Pokémon found within 10 blocks of you.

## Workers

| Command | What it does |
|---|---|
| `/palworks worker enable` | Switches the nearest Pokémon's work on, as the interact wheel's toggle does. |
| `/palworks worker disable` | Switches its work off. |

A Pokémon with no work skills doesn't work even with its work on. See [Workers](Workers).

## Work skills

Change a species' work skills without touching the datapacks. Changes apply straight away (players'
PC and summary screens show them a moment later), go over every datapack's levels, and are kept
after `/reload` and restarts. They're saved with the world, in
`<world>/palworks/suitability_overrides.json`.

| Command | What it does |
|---|---|
| `/palworks suitability get <species>` | Its levels (its standard form's), and which were changed in game. |
| `/palworks suitability set <species> <job> <level>` | Sets its level for that job outright, whatever its stats give. `0` takes the job away. E.g. `/palworks suitability set bulbasaur watering 3`. |
| `/palworks suitability reset <species> [<job>]` | Back to the datapacks' levels, for one job or all. |
| `/palworks suitability reset all` | Undoes every change. |
| `/palworks suitability list` | Every species changed, and how. |

The jobs are `kindling`, `watering`, `planting`, `generating_electricity`, `handiwork`, `gathering`,
`lumbering`, `mining`, `medicine_production`, `cooling`, `transporting`, `nether` and `farming`.

## Settings

Change the [server settings](Configuration) in game, straight away. Each setting is named by its
place in `config/palworks.json`, and the change is written to the file.

| Command | What it does |
|---|---|
| `/palworks config get [<setting>]` | One setting, or all of them, with their defaults. |
| `/palworks config set <setting> <value>` | E.g. `/palworks config set hunger.enabled false`. Values must be in the same ranges as in the file. |
| `/palworks config reset <setting>` | Back to its default. |
| `/palworks config reset all` | Every setting back to its default. |

## Development only

These are only there in a development run of the mod (`./gradlew :fabric:runClient` and the like),
for debugging:

| Command | What it does |
|---|---|
| `/palworks debug` | Turns on worker activity messages in chat and an in-world overlay of jobs, paths and job blocks. |
| `/palworks inspect worker` | The state of the first worker within 10 blocks: its skills, job and so on. |
| `/palworks jobs` | The jobs found, which are reserved, the workers, and why jobs went to whom. |
| `/palworks worker inventory` | Prints the nearest worker's inventory and job. |
| `/palworks worker openinv` | Opens its inventory screen. |
| `/palworks worker clearinv` | Deletes everything in its inventory. **The items are destroyed.** |
| `/palworks worker clear` | Clears every worker's job reservation, which helps when workers get stuck. |
| `/palworks sleep` | Makes the nearest Pokémon drowsy now. |
| `/palworks testarea` | Builds the test area. |
