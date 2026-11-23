package dev.palworks.gametest;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.ai.WorkerAI;
import dev.palworks.client.gui.WorkIcons;
import dev.palworks.debug.WorkerDebugSync;
import dev.palworks.inventory.CarryMode;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.job.JobType;
import dev.palworks.net.packets.WorkActivityPacket;
import dev.palworks.net.packets.WorkerDebugPacket;
import dev.palworks.net.packets.WorkerStatusPacket;
import dev.palworks.suitability.Suitability;
import dev.palworks.worker.WorkActivity;
import dev.palworks.worker.WorkActivitySync;
import dev.palworks.worker.WorkerStateManager;
import dev.palworks.worker.WorkerStatus;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * What workers report doing (the party HUD's work icons and the debug overlay), and the packets
 * that carry it.
 */
public class WorkStatusGameTests {

    private static RegistryFriendlyByteBuf buffer(GameTestHelper helper) {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
    }

    @GameTest(template = Templates.EMPTY)
    public void everyItemAWorkIconShowsIsRegistered(GameTestHelper helper) {
        List<WorkIcons.Icon> icons = new ArrayList<>();
        for (JobType job : JobType.values()) {
            icons.add(WorkIcons.forJob(job));
        }
        for (Suitability skill : Suitability.values()) {
            icons.add(WorkIcons.forSkill(skill));
        }
        for (WorkerStatus status : WorkerStatus.values()) {
            icons.add(WorkIcons.forStatus(status));
        }
        for (WorkIcons.Icon icon : icons) {
            if (icon instanceof WorkIcons.OfItem item && item.stack().isEmpty()) {
                helper.fail("no item " + item.item());
            }
        }
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void packetsDecodeToWhatWasSent(GameTestHelper helper) {
        UUID uuid = UUID.randomUUID();
        for (WorkActivity activity : List.of(
                WorkActivity.OFF,
                WorkActivity.IDLE,
                new WorkActivity(WorkActivity.Phase.WALKING, JobType.CHOP_TREE),
                new WorkActivity(WorkActivity.Phase.PAUSED, JobType.DELIVER_ITEMS))) {
            RegistryFriendlyByteBuf buf = buffer(helper);
            new WorkActivityPacket(uuid, activity).encode(buf);
            WorkActivityPacket decoded = WorkActivityPacket.decode(buf);
            helper.assertValueEqual(decoded.getPokemonUUID(), uuid, "work activity uuid");
            helper.assertValueEqual(decoded.getActivity(), activity, "work activity");
            helper.assertValueEqual(buf.readableBytes(), 0, "bytes left after work activity");
        }

        List<WorkerDebugPacket.Entry> entries = List.of(
                new WorkerDebugPacket.Entry(
                        42,
                        new WorkActivity(WorkActivity.Phase.WALKING, JobType.HARVEST_CROPS),
                        new BlockPos(10, 64, -3),
                        List.of(new BlockPos(1, 64, 1), new BlockPos(2, 65, 1)),
                        new WorkerDebugPacket.Details(
                                120, 480, "", 0, 0, 0, 0, WorkerDebugPacket.PathStatus.PARTIAL, 2, 7, 35),
                        List.of(new BlockPos(-5, 70, 9))),
                new WorkerDebugPacket.Entry(
                        7,
                        new WorkActivity(WorkActivity.Phase.WORKING, JobType.CHOP_TREE),
                        new BlockPos(0, 0, 0),
                        List.of(),
                        new WorkerDebugPacket.Details(
                                900, -1, "chop", 1, 3, 34, 60, WorkerDebugPacket.PathStatus.NONE, 0, 0, 0),
                        List.of()),
                new WorkerDebugPacket.Entry(
                        8, WorkActivity.IDLE, null, List.of(), WorkerDebugPacket.Details.NONE, List.of()));
        RegistryFriendlyByteBuf debugBuf = buffer(helper);
        new WorkerDebugPacket(entries).encode(debugBuf);
        helper.assertValueEqual(WorkerDebugPacket.decode(debugBuf).getEntries(), entries, "debug entries");
        helper.assertValueEqual(debugBuf.readableBytes(), 0, "bytes left after debug entries");

        RegistryFriendlyByteBuf statusBuf = buffer(helper);
        new WorkerStatusPacket(uuid, false, CarryMode.SEED_POUCH).encode(statusBuf);
        WorkerStatusPacket status = WorkerStatusPacket.decode(statusBuf);
        helper.assertValueEqual(status.getEntityUUID(), uuid, "status uuid");
        helper.assertFalse(status.isEnabled(), "status enabled");
        helper.assertValueEqual(status.getCarryMode(), CarryMode.SEED_POUCH, "status carry mode");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY, batch = "work_status_chop", timeoutTicks = 800)
    public void aLumberjackShowsWalkingThenWorkingWithItsPath(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        BlockPos trunk = new BlockPos(6, 1, 6);
        Scenes.tree(helper, trunk.getX(), trunk.getZ(), 4, Blocks.OAK_LOG, Scenes.naturalLeaves(Blocks.OAK_LEAVES));
        PokemonEntity machop = Workers.sendOut(helper, "machop level=20", new BlockPos(1, 1, 1));

        AABB area = new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(16);
        Set<WorkActivity.Phase> seen = EnumSet.noneOf(WorkActivity.Phase.class);
        boolean[] sawPathToTree = {false};
        boolean[] sawReachingPath = {false};
        boolean[] sawChopStep = {false};
        String[] badTimer = {null};
        helper.onEachTick(() -> {
            for (WorkerDebugPacket.Entry entry : WorkerDebugSync.entriesIn(helper.getLevel(), area)) {
                if (entry.entityId() != machop.getId() || entry.activity().job() != JobType.CHOP_TREE) {
                    continue;
                }
                seen.add(entry.activity().phase());
                WorkerDebugPacket.Details d = entry.details();
                if (entry.activity().phase() == WorkActivity.Phase.WALKING) {
                    // Counting down from the 30s timeout as the job gets older
                    if (d.jobAgeTicks() < 0
                            || d.expiresInTicks() + d.jobAgeTicks() != WorkerAI.RESERVATION_TIMEOUT_TICKS) {
                        badTimer[0] = "age " + d.jobAgeTicks() + ", gives up in " + d.expiresInTicks();
                    }
                    sawReachingPath[0] |= d.pathStatus() == WorkerDebugPacket.PathStatus.REACHES;
                }
                if (entry.activity().phase() == WorkActivity.Phase.WORKING && "chop".equals(d.stepName())) {
                    sawChopStep[0] = d.stepCount() > 0 && d.stepLength() > 0;
                }
                if (entry.activity().phase() == WorkActivity.Phase.WALKING
                        && !entry.path().isEmpty()
                        && helper.absolutePos(trunk).equals(entry.target())) {
                    sawPathToTree[0] = true;
                }
            }
        });

        // Also wait for the whole job (chop and replant), like the other lumberjack tests: ending
        // mid-chop would hand the half-done job over to whichever lumberjack test runs here next
        // (batches run one after another in the same spot).
        helper.succeedWhen(() -> {
            helper.assertTrue(sawPathToTree[0], "walking to the tree, with a path and the trunk as its target");
            helper.assertTrue(seen.contains(WorkActivity.Phase.WORKING), "then chopping it");
            helper.assertTrue(
                    badTimer[0] == null, "job timer while walking should add up to the timeout: " + badTimer[0]);
            helper.assertTrue(sawReachingPath[0], "its path to the tree reported as reaching it");
            helper.assertTrue(sawChopStep[0], "the chop step named while it chopped");
            for (int y = 1; y <= 4; y++) {
                helper.assertBlockNotPresent(Blocks.OAK_LOG, trunk.atY(y));
            }
            helper.assertBlockPresent(Blocks.OAK_SAPLING, trunk);
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "work_status_off", timeoutTicks = 200)
    public void aSwitchedOffWorkerShowsOff(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        PokemonEntity bulbasaur = Workers.sendOut(helper, "bulbasaur level=20", new BlockPos(3, 1, 3));
        WorkerStateManager.setEnabled(bulbasaur.getPokemon(), false);

        helper.succeedWhen(() -> helper.assertValueEqual(
                WorkActivitySync.activityOf(bulbasaur, WorkerStateManager.getOrCreate(bulbasaur.getPokemon())),
                WorkActivity.OFF,
                "activity"));
    }

    @GameTest(template = Templates.EMPTY, batch = "work_status_unreachable", timeoutTicks = 300)
    public void aBoxedInWorkerReportsTheJobsItCantReach(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        BlockPos crop = new BlockPos(5, 1, 5);
        helper.setBlock(crop.below(), Blocks.FARMLAND);
        helper.setBlock(crop, ((CropBlock) Blocks.WHEAT).getStateForAge(7));
        // A glass box round the worker, so the wheat can't be reached
        BlockPos inside = new BlockPos(1, 1, 1);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int y = 1; y <= 3; y++) {
                    if (!(dx == 0 && dz == 0 && y <= 2)) {
                        helper.setBlock(inside.getX() + dx, y, inside.getZ() + dz, Blocks.GLASS);
                    }
                }
            }
        }
        PokemonEntity bulbasaur = Workers.sendOut(helper, "bulbasaur level=20", inside);
        AABB area = new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(16);
        BlockPos cropAbs = helper.absolutePos(crop);

        helper.succeedWhen(() -> {
            WorkerDebugPacket.Entry entry = WorkerDebugSync.entriesIn(helper.getLevel(), area).stream()
                    .filter(e -> e.entityId() == bulbasaur.getId())
                    .findFirst()
                    .orElse(null);
            helper.assertTrue(entry != null, "the worker is in the overlay");
            helper.assertTrue(
                    entry.unreachable().contains(cropAbs),
                    "the wheat reported as unreachable, got " + entry.unreachable());
        });
    }

    // Long enough to take in a retry of the jobs a worker couldn't reach: it forgets them after 600
    // ticks (JobAssigner.JOB_FAILURE_BLACKLIST_TICKS) and tries again
    private static final int STEADY_TICKS = 700;
    // How soon an icon goes once what caused it has: a worker idle with nothing to do is looked at
    // every tick or so
    private static final int CLEARS_WITHIN = 40;
    private static final BlockPos BOXED = new BlockPos(1, 1, 1);
    private static final BlockPos BOXED_WHEAT = new BlockPos(5, 1, 5);

    /**
     * A Bulbasaur in a glass box at {@link #BOXED}, with a ripe wheat outside it at
     * {@link #BOXED_WHEAT} that it would harvest: work it can't get to.
     */
    private static PokemonEntity boxedInAwayFromRipeWheat(GameTestHelper helper) {
        Workers.isolate(helper);
        Scenes.floor(helper, Blocks.GRASS_BLOCK);
        helper.setBlock(BOXED_WHEAT.below(), Blocks.FARMLAND);
        helper.setBlock(BOXED_WHEAT, ((CropBlock) Blocks.WHEAT).getStateForAge(7));
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int y = 1; y <= 3; y++) {
                    if (!(dx == 0 && dz == 0 && y <= 2)) {
                        helper.setBlock(BOXED.getX() + dx, y, BOXED.getZ() + dz, Blocks.GLASS);
                    }
                }
            }
        }
        return Workers.sendOut(helper, "bulbasaur level=20", BOXED);
    }

    /** A Pidgey (Transporting) carrying wheat, with no chest anywhere. */
    private static PokemonEntity transporterWithNowhereToDeliver(GameTestHelper helper, BlockPos at) {
        PokemonEntity pidgey = Workers.sendOut(helper, "pidgey level=20", at);
        WorkerInventoryManager.getOrCreate(pidgey.getPokemon()).setItem(0, new ItemStack(Items.WHEAT, 10));
        return pidgey;
    }

    private static boolean shows(PokemonEntity worker, WorkerStatus status) {
        return worker.getAspects().contains(status.aspect());
    }

    @GameTest(template = Templates.EMPTY, batch = "work_status_cant_reach", timeoutTicks = 1200)
    public void aWorkerThatCantReachItsWorkShowsItSteadilyUntilItsLetOut(GameTestHelper helper) {
        PokemonEntity bulbasaur = boxedInAwayFromRipeWheat(helper);
        long[] shownAt = {-1};
        long[] openedAt = {-1};
        long[] clearedAt = {-1};
        String[] flickered = {null};
        helper.onEachTick(() -> {
            long now = helper.getTick();
            boolean shown = shows(bulbasaur, WorkerStatus.CANT_REACH);
            if (openedAt[0] < 0) {
                if (shown && shownAt[0] < 0) {
                    shownAt[0] = now;
                } else if (!shown && shownAt[0] >= 0 && flickered[0] == null) {
                    flickered[0] = "went off at tick " + now + ", " + (now - shownAt[0]) + " after it showed";
                }
                if (shownAt[0] >= 0 && now - shownAt[0] >= STEADY_TICKS) {
                    // A way out, towards the wheat
                    helper.setBlock(BOXED.east(), Blocks.AIR);
                    helper.setBlock(BOXED.east().above(), Blocks.AIR);
                    openedAt[0] = now;
                }
            } else if (!shown && clearedAt[0] < 0) {
                clearedAt[0] = now;
            }
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(shownAt[0] >= 0, "it shows it can't reach the wheat");
            helper.assertTrue(flickered[0] == null, "and keeps showing it while it can't: " + flickered[0]);
            helper.assertTrue(openedAt[0] >= 0, "shown for " + STEADY_TICKS + " ticks before it's let out");
            helper.assertTrue(clearedAt[0] >= 0, "it stops showing it once it's let out");
            helper.assertTrue(
                    clearedAt[0] - openedAt[0] <= CLEARS_WITHIN,
                    "within " + CLEARS_WITHIN + " ticks, not " + (clearedAt[0] - openedAt[0]));
            helper.assertFalse(shows(bulbasaur, WorkerStatus.CANT_DELIVER), "it has nothing to deliver");
            BlockState wheat = helper.getBlockState(BOXED_WHEAT);
            helper.assertFalse(
                    wheat.getBlock() instanceof CropBlock crop && crop.isMaxAge(wheat), "and it harvests the wheat");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "work_status_cant_deliver", timeoutTicks = 600)
    public void aTransporterWithNowhereToDeliverShowsItUntilThereIsAChest(GameTestHelper helper) {
        Workers.isolate(helper);
        BlockPos chest = new BlockPos(6, 1, 6);
        PokemonEntity pidgey = transporterWithNowhereToDeliver(helper, new BlockPos(2, 1, 2));
        long[] shownAt = {-1};
        long[] placedAt = {-1};
        long[] clearedAt = {-1};
        String[] flickered = {null};
        helper.onEachTick(() -> {
            long now = helper.getTick();
            boolean shown = shows(pidgey, WorkerStatus.CANT_DELIVER);
            if (placedAt[0] < 0) {
                if (shown && shownAt[0] < 0) {
                    shownAt[0] = now;
                } else if (!shown && shownAt[0] >= 0 && flickered[0] == null) {
                    flickered[0] = "went off at tick " + now;
                }
                if (shownAt[0] >= 0 && now - shownAt[0] >= 100) {
                    helper.setBlock(chest, Blocks.CHEST);
                    placedAt[0] = now;
                }
            } else if (!shown && clearedAt[0] < 0) {
                clearedAt[0] = now;
            }
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(shownAt[0] >= 0, "it shows it has nowhere to deliver");
            helper.assertTrue(flickered[0] == null, "and keeps showing it while it hasn't: " + flickered[0]);
            helper.assertTrue(placedAt[0] >= 0, "until a chest is placed");
            helper.assertTrue(clearedAt[0] >= 0, "then it stops showing it");
            helper.assertTrue(
                    clearedAt[0] - placedAt[0] <= CLEARS_WITHIN,
                    "within " + CLEARS_WITHIN + " ticks, not " + (clearedAt[0] - placedAt[0]));
            helper.assertFalse(shows(pidgey, WorkerStatus.CANT_REACH), "there's no work it can't reach");
            helper.assertTrue(
                    ((Container) helper.getBlockEntity(chest)).countItem(Items.WHEAT) == 10,
                    "and it delivers the wheat");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "work_status_held_off", timeoutTicks = 400)
    public void aWorkerWhoseWorkIsOffShowsNeitherIcon(GameTestHelper helper) {
        PokemonEntity bulbasaur = boxedInAwayFromRipeWheat(helper);
        PokemonEntity pidgey = transporterWithNowhereToDeliver(helper, new BlockPos(6, 1, 2));
        long[] offAt = {-1};
        String[] shownWhileOff = {null};
        helper.onEachTick(() -> {
            long now = helper.getTick();
            if (offAt[0] < 0) {
                if (shows(bulbasaur, WorkerStatus.CANT_REACH) && shows(pidgey, WorkerStatus.CANT_DELIVER)) {
                    WorkerStateManager.setEnabled(bulbasaur.getPokemon(), false);
                    WorkerStateManager.setEnabled(pidgey.getPokemon(), false);
                    offAt[0] = now;
                }
            } else if (now > offAt[0] + 2
                    && shownWhileOff[0] == null
                    && (shows(bulbasaur, WorkerStatus.CANT_REACH) || shows(pidgey, WorkerStatus.CANT_DELIVER))) {
                shownWhileOff[0] = "still shown " + (now - offAt[0]) + " ticks after its work went off";
            }
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(offAt[0] >= 0, "both icons shown while their work was on");
            helper.assertTrue(helper.getTick() - offAt[0] >= 100, "then watched with their work off");
            helper.assertTrue(shownWhileOff[0] == null, "neither shown with their work off: " + shownWhileOff[0]);
        });
    }
}
