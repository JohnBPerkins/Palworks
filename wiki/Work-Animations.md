# Work Animations

While a Pokémon works, it plays a placeholder Cobblemon move as its work animation: Ember to light
a furnace, Razor Leaf to harvest, Leech Seed to plant, Water Gun to water and Scratch to chop. Each
animation has a **stance**, which sets where the worker stands. A contact move like Scratch is played right up
against the block; ranged moves are played from a little way back. Bigger Pokémon stand further
back, measured from the edge of their hitbox.

**Poser animations.** While a Pokémon is working, it has the aspect `palworks-working`, plus one for
the job: `palworks-harvest-crops`, `palworks-chop-tree`, `palworks-fuel-furnace`, and so on. A
resource pack can animate a species from its poser JSON with a pose condition such as
`"condition": "q.has_aspect('palworks-chop-tree')"`.

**Per-species changes (the worker roster).** A datapack can give a species its own version of any
work animation. Use files at `data/<namespace>/palworks/work_animations/<name>.json`:

```json
{
  "species": ["cobblemon:scizor", "scyther"],
  "animations": {
    "chop": { "move": "furycutter", "impact_ticks": 18, "duration_ticks": 45, "stance": "against" },
    "harvest": { "stance": { "gap": 2.5, "max_ring": 5 } }
  }
}
```

- **`species`**: one species, or a list of them. A species without a namespace is Cobblemon's.
- **`animations`**: the changes for each animation, by id. The ids are `ignite_furnace`, `harvest`,
  `plant`, `water` and `chop`. Anything you leave out keeps its default.
  - **`move`**: the action effect to play. Give a Cobblemon move name (`furycutter`) or a full id
    (`myaddon:chop`).
  - **`impact_ticks`**: how many ticks in the effect lands on the block. The work (the harvest, the
    chop) happens at that moment.
  - **`duration_ticks`**: how many ticks until the body animation is over. The worker waits that long
    before it walks off or starts another animation.
  - **`aim_y`**: how high in the block to aim, from 0 (bottom) to 1 (top).
  - **`stance`**: where to stand. Use `"near"` for a ranged move or `"against"` for a contact move.
    You can also give an object of numbers in blocks; any you leave out keep the default stance's
    value:
    - `gap`: the most clear space between the body and the block.
    - `min_ring` and `max_ring`: how many tiles out from the block it may stand, at least and at most.
    - `slack`: how much further than that still counts as there.

Where two files change the same thing for the same species, the file whose id sorts last wins.
`/reload` applies changes.
