# Mood

Each worker has a mood, from 0 to 100, separate from its friendship. It starts at 100.

- **What lowers it:** the work it does (a point for every 2 units of work, as hunger counts them),
  and being Hungry or Tired.
- **What brings it back:** while it's neither Hungry nor Tired, it cheers up whenever it isn't at
  work: idle, eating or asleep. A fed worker with a bed stays about level through a busy day and night.
- **Unhappy (below 50):** it works and walks at three quarters of its speed, on top of any slowdown
  for hunger or sleep, with a sad face over its head.
- **On strike (at 0):** it finishes what it's doing, then does no work until its mood is back to
  30, with an anger mark over its head. It still eats and sleeps, which is how it gets there.
- **Where to see it:** its mood shows in the bottom-left corner of its portrait on the PC and its
  summary: a face and the number. Hover it to see what it means.

Party workers have a mood too, but never sleep, so they're never Tired. With its work off, or in
its ball, a worker's mood holds where it is.
