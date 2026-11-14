# Hunger and Troughs

A worker's hunger is its Cobblemon **fullness**, the bar on its summary screen: Palworks adds no
stat of its own.

- **While its work is on**, Cobblemon's own drain of its fullness is paused, and Palworks drains
  it instead: a point for every 20 units of work it does (a crop harvested, a seed planted, a trip
  made...), and a quarter of its bar over each Minecraft day. With its work off, or back in its
  ball, it's a normal Cobblemon Pokémon again, and Palworks doesn't touch its fullness.
- **When it's below half full** it walks to the nearest **trough** with feed and eats, a point at
  a time, until it's half full. The other half is left for you: a full Pokémon refuses Mochi and
  berries fed by hand. Eating from a trough only fills it up; the food's own effects (EVs,
  friendship, healing) don't happen.
- **Which troughs:** any within 32 blocks of it, or, for a pastured worker, of its pasture block.
  So a pastured worker that has wandered off for work still goes back to the troughs
  by its pasture.
- **With no fullness left** it works and walks at a quarter speed, with a half hunger shank over
  its head, until it eats. It never stops working.
- **A new Pokémon starts half full**, not empty as in Cobblemon, so it can go straight to work:
  the first time you get it (caught, a starter, hatched, traded, given by a command). Moving it
  between party and PC, or evolving it, doesn't top it up again.

A **trough** comes in every wood (oak, spruce, birch, jungle, acacia, dark oak, mangrove, cherry,
bamboo, crimson and warped), crafted from 5 of that wood's slabs (two on the top row's ends, three
along the bottom). It holds **feed**, as a composter holds compost: food put in it becomes feed,
worth the fullness it gives (a point a berry), and workers eat it a point at a time.
Each trough holds 64 points; an empty one shows its bare wooden bottom, and a layer of feed rises
as it fills. Troughs placed side by side join into one long trough, whatever their woods, and share
their feed evenly: food put in anywhere spreads along all of them, and workers eat at any of them.
Feed can't be taken back out, and breaking a trough loses its share.

Filling them:

- **Right-click a trough holding food** to put one in; **shift-right-click** to put the whole stack
  in, as far as there's room along it. Right-click with anything else to see how much feed it has.
- **Hoppers** feed troughs as they do composters.
- **Transporters take food to troughs first.** Harvested berries they carry go to the nearest
  trough with room before a chest, so a berry farm keeps its workers fed.
- **Transporters restock troughs from chests.** When a trough in reach has less than 16 feed left,
  a transporter fetches a stack of food from a chest that has some.

Troughs take the items in the `palworks:trough_food` item tag: by default Cobblemon's berries and
Aprijuices. Datapacks can add to the tag, and set how much feed an item is worth in
`data/<namespace>/palworks/trough_food/<name>.json` (anything not listed is worth 1):

```json
{
  "values": [
    { "item": "cobblemon:sitrus_berry", "fullness": 2 },
    { "tag": "cobblemon:berries", "fullness": 1 }
  ]
}
```
