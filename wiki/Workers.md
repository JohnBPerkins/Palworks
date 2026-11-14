# Workers

A **worker** is one of your Pokémon that is doing jobs for you.

## Who works

A Pokémon works when all of these are true:

- **You own it.** Wild Pokémon never work.
- **It's out in the world**: sent out from your party, or roaming a Cobblemon **pasture**. Pokémon
  in your PC or in their ball aren't in the world.
- **It has at least one work skill** (a [suitability](Suitabilities)). One with none is never a
  worker, and Palworks leaves it alone.
- **Its work is switched on.** It is unless you switch it off.

What a worker can do depends on its [suitabilities](Suitabilities).

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
- Each job is reserved by one worker at a time, so two Pokémon won't fight over the same furnace or
  crop.
- A worker picks the best job from how close it is and how important it is. See
  [Jobs](Jobs#job-priority) for the order.
- If a worker can't reach a job, it skips that spot for up to 30 seconds, or less if it moves well
  away or blocks change nearby.
- After a harvest or a chop, the worker that did it is offered the pickup first, if it can
  transport.

## Inventory

What a worker carries depends on its work skills:

- **Transporting** workers have a **3-slot inventory** for the items they pick up and deliver.
- **Planting** and **Nether** workers have a **1-slot seed pouch** that only holds seeds (or nether
  wart). They take up to a stack from a chest and keep it until it's planted.
- Other workers carry nothing.

**To open it:** use Cobblemon's interaction wheel on your Pokémon and choose **Inventory**. The
button is only there for workers that carry something. You can move items in and out like a chest.

**What it holds shows in its hand**, as a held item would, but only while it has no Cobblemon held
item. Its real held item (Leftovers, a Choice item...) is never touched.

## Chests

Workers use regular **chests** and **trapped chests**, both to take seeds and to drop off items.
Barrels, shulker boxes and storage from other mods aren't supported yet. Transporters take
[trough food](Hunger) to troughs before chests.

## What the icons mean

- **PC and summary:** each Pokémon's work skills and [levels](Suitabilities#work-levels) show as
  icons in the corner of its portrait, in the PC as you hover over it and on its summary. The
  summary's Info page names them, in the box beside the experience bar. Hover either for the full
  list. Its [mood](Mood) shows in the bottom-left corner: a face and the number.
- **Party HUD:** in the top-left corner of each sent-out party member's card, next to the "sent
  out" icon: its job's icon while it works, faded while it walks there, greyed out while it's in a
  battle, or a red cross if its work is off.
- **Over its head:** what's holding its work back: a half hunger shank when it's out of food, a "z"
  when it's Tired, a sad face when it's unhappy, an anger mark when it's on strike, and a moon while
  it's asleep, if its species has no sleep animation to show it. Each player can turn these off
  (`show_status_icons`, see [Configuration](Configuration#display-settings)).
