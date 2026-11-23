# Workers

A **worker** is one of your Pokémon that is doing jobs for you.

## Who works

A Pokémon works when all of these are true:

- **You own it.** Wild Pokémon never work.
- **It's out in the world**: sent out from your party, or roaming a Cobblemon **pasture**. Pokémon
  in your PC or in their ball aren't in the world.
- **It has at least one [work skill](Suitabilities)**. One with none is never a
  worker, and Palworks leaves it alone.
- **Its work is switched on.** It starts off: you switch it on (below), unless the server's
  `work_on_by_default` [setting](Configuration) has it start on.

What a worker can do depends on its [work skills](Suitabilities).

## Switching work on and off

Shift-right-click your Pokémon and use the **bottom slot of the interact wheel**: a green check
means its work is on, a red cross that it's off. With its work off, it's a normal Cobblemon Pokémon
again: no jobs, and Palworks' [hunger](Hunger), [sleep](Sleep) and [mood](Mood) leave it alone.
Admins can also use `/palworks worker enable` and `disable` (see [Commands](Commands)).

## How workers find jobs

- Workers look for jobs within **32 blocks** of themselves, both horizontally and vertically (the
  `work_range_horizontal` and `work_range_vertical` [settings](Configuration)).
- A **party** Pokémon only takes jobs within **24 blocks** of you (`owner_range`). Cobblemon brings
  a party Pokémon back to its owner past 32 blocks, so it can't be more.
- A **pastured** Pokémon only takes jobs where its pasture lets it roam (64 blocks from the pasture
  block, unless your Cobblemon settings say otherwise). Cobblemon brings one that steps outside
  back to its pasture.
- Each job is reserved by one worker at a time, so two Pokémon won't fight over the same furnace or
  crop.
- A worker picks the best job from how close it is and how important it is. See
  [Jobs](Jobs#job-priority) for the order.
- If a worker can't reach a job, it skips that spot for up to 30 seconds, or less if it moves well
  away or blocks change nearby.
- After a harvest or a chop, the worker that did it is offered the pickup first, if it can
  transport.

## Flying

Workers that can fly in Cobblemon (Pidgey, Charizard, Hoppip and the like) fly to a job more than
8 blocks away, one 2 or more blocks above them, such as items on top of a tree, or one they can't
walk to. They take off straight up, fly across above everything on the way, and land at the job.
If the job goes while one is in the air (another worker got there first), it flies down where it is.
They walk to jobs close by, and anywhere with a roof over them or over the job. Flying-type Pokémon
that can't fly in Cobblemon, such as Doduo, always walk.

## Inventory

What a worker carries depends on its work skills:

- **Transporting** workers have an inventory for the items they pick up and deliver: **one slot for
  each [work level](Suitabilities#work-levels)**, 1 to 5.
- **Planting** and **Nether** workers have a **1-slot seed pouch** that only holds seeds (or nether
  wart). A Transporting worker brings them a stack from a chest, and they keep it until it's
  planted.
- Other workers carry nothing.

**To open it:** use Cobblemon's interaction wheel on your Pokémon and choose **Inventory**. The
button is only there for workers that carry something. You can move items in and out like a chest.
Like a horse's inventory, the screen closes if the two of you end up too far apart.

**What it holds shows in its hand**, as a held item would, but only while it has no Cobblemon held
item. Its real held item (Leftovers, a Choice item...) is never touched.

## Chests

Workers use regular **chests** and **trapped chests**, both to take seeds from and to drop off items in.
Barrels, shulker boxes and storage from other mods aren't supported yet. Transporters take
[trough food](Hunger) to troughs before chests.

A chest opens while a worker takes something out or puts something in, as it does for you: the lid
lifts, it makes its sound, and a trapped chest gives off its redstone signal.

## What the icons mean

- **PC and summary:** each Pokémon's work skills and [levels](Suitabilities#work-levels) show as
  icons in the corner of its portrait, in the PC as you hover over it and on its summary (a
  skill whose level changes nothing yet shows no number). Hover
  them for the full list by name, with a line on what each skill does. Its [mood](Mood) shows in
  the bottom-left corner as a face (hover it for the number), and as a bar on the summary's Stats
  page, under Other.
- **Party HUD:** in the top-left corner of each sent-out party member's card, next to the "sent
  out" icon: its job's icon while it works, faded while it walks there, greyed out while it's
  paused (evolving, say), or a red cross if its work is off. In a battle Cobblemon swaps these
  cards for its battle display, so no icon shows until it's over.
- **Item tooltips:** hold **Shift** over an item whose block Pokémon work on to see which work
  skills do it: "Worked by: Kindling" on a furnace, "Worked by: Watering, Planting, Gathering" on
  wheat seeds. It covers furnaces, crops and their seeds, logs and saplings, crystals, nether wart
  and soul sand, and the chests and troughs transporters fill.
- **Over its head:** what's holding its work back: a half hunger shank when it's out of food, a "z"
  when it's Tired, a sad face when it's unhappy, an anger mark when it's on strike, a brick wall
  when it's idle because it can't get to any of the work there is, a chest when it's carrying items
  and has no chest with room it can get to, and a moon while it's asleep, if its species has no
  sleep animation to show it. Each player can turn these off (`show_status_icons`, see
  [Configuration](Configuration#display-settings)).
