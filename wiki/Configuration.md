# Configuration

## Server settings

`config/palworks.json` (written with the defaults the first time the server starts) holds the
settings that aren't per-Pokémon:

```json
{
  "work_range_horizontal": 32,
  "work_range_vertical": 32,
  "owner_range": 24,
  "pickup_radius": 3.0,
  "fertilize_chance_per_level": 0.1,
  "watering_cooldown_seconds": 60,
  "hunger": {
    "enabled": true,
    "work_per_fullness": 20,
    "idle_drain_per_day": 0.25,
    "trough_fill_fraction": 0.5,
    "empty_work_speed": 0.75
  },
  "sleep": {
    "enabled": true,
    "tired_work_speed": 0.75
  },
  "mood": {
    "enabled": true,
    "work_per_point": 2,
    "hungry_drain_per_day": 50,
    "tired_drain_per_day": 25,
    "recovery_per_day": 100,
    "unhappy_below": 50,
    "strike_until": 30
  }
}
```

- **`work_range_horizontal`** (8 to 64): how many blocks out each way a worker looks for and takes
  jobs. Bigger ranges scan more of the world each tick.
- **`work_range_vertical`** (4 to 64): how many blocks up and down it does.
- **`owner_range`** (4 to 32): how far from its owner a Pokémon from a party takes jobs. Cobblemon
  teleports a party Pokémon back to its owner past 32 blocks, so it can't be more.
- **`pickup_radius`** (1 to 8): how far round a harvest, chop or pickup spot dropped items are
  collected from.
- **`fertilize_chance_per_level`** (0 to 0.2): the chance that watering a crop also fertilizes it,
  for each of the waterer's [Watering levels](Suitabilities#work-levels): at 0.1, a level 1 Squirtle fertilizes
  10% of the crops it waters, a level 3 Blastoise 30%.
- **`watering_cooldown_seconds`** (5 to 600): how long after a crop is watered until it can be
  watered again. Crops watered before a restart can be watered again straight away.
- **`hunger`**: how workers get hungry and eat (see [Hunger](Hunger)).
  - **`enabled`**: `false` turns hunger off: Palworks leaves fullness entirely to Cobblemon.
  - **`work_per_fullness`** (1 to 500): how many units of work use up one point of fullness. A
    unit is a crop harvested, a seed planted, a crop watered, a furnace lit, a trip to pick up or
    deliver, or every 8 logs of a tree (at most 5 a tree).
  - **`idle_drain_per_day`** (0 to 1): the share of its max fullness a worker loses over a Minecraft
    day while its work is on, working or not.
  - **`trough_fill_fraction`** (0 to 1): how full troughs fill a worker, as a share of its max
    fullness. The rest is left for hand-feeding. 0 and troughs never feed.
  - **`empty_work_speed`** (0.1 to 1): how fast a worker with no fullness left works and walks.
    Slowdowns add up (out of food and Tired is both taken off), but never below half speed.
- **`sleep`**: how pastured workers sleep (see [Sleep](Sleep)).
  - **`enabled`**: `false` turns it off: pastured workers' sleep is then Cobblemon's alone.
  - **`tired_work_speed`** (0.1 to 1): how fast a Tired worker works and walks.
- **`mood`**: how workers' mood goes down and back up (see [Mood](Mood)).
  - **`enabled`**: `false` turns mood off: no strikes.
  - **`work_per_point`** (1 to 500): how many units of work (as for hunger) take a point off.
  - **`hungry_drain_per_day`**, **`tired_drain_per_day`** (0 to 1000): the points a worker loses
    over a Minecraft day while Hungry, or while Tired.
  - **`recovery_per_day`** (0 to 1000): the points it gains back over a day while it's neither,
    and not at work.
  - **`unhappy_below`** (0 to 100): the mood below which it shows it's unhappy, a warning that
    it's on its way to a strike.
  - **`strike_until`** (1 to 100): the mood a worker on strike has to be back to before it works.

Any setting left out keeps its default. If a value is out of range or the file isn't valid JSON,
the server logs a warning and uses the defaults. Changes to the file apply the next time the server
starts.

Admins (OP level 2, or the server console) can also change them in game, straight away. Each
setting is named by its place in the file, and the change is written to the file:

- `/palworks config get [<setting>]`: one setting, or all of them, with their defaults.
- `/palworks config set <setting> <value>`: e.g. `/palworks config set hunger.enabled false`. Values
  must be in the same ranges as in the file.
- `/palworks config reset <setting>` or `/palworks config reset all`: back to the defaults.

The display settings in `config/palworks-client.json` are each player's own, so they're only
changed in that file.

## Display settings

`config/palworks-client.json` (written with the defaults the first time the game starts) holds each
player's own display settings:

```json
{
  "show_status_icons": true
}
```

- **`show_status_icons`**: whether Pokémon show over their heads what's holding their work back (a
  half hunger shank when they're out of food, a "z" when they're Tired, a sad face when they're
  unhappy and an anger mark when they're on strike), and a moon while they're asleep, if their
  species has no sleep animation to show it.
