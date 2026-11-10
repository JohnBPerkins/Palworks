package dev.palworks.gametest;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.debug.WorkerDebugSync;
import dev.palworks.inventory.CarryMode;
import dev.palworks.job.JobType;
import dev.palworks.net.packets.WorkActivityPacket;
import dev.palworks.net.packets.WorkerDebugPacket;
import dev.palworks.net.packets.WorkerStatusPacket;
import dev.palworks.worker.WorkActivity;
import dev.palworks.worker.WorkActivitySync;
import dev.palworks.worker.WorkerStateManager;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What workers report doing (the party HUD's work icons and the debug overlay), and the packets
 * that carry it.
 */
public class WorkStatusGameTests {

    private static RegistryFriendlyByteBuf buffer(GameTestHelper helper) {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
    }

    @GameTest(template = Templates.EMPTY)
    public void packetsDecodeToWhatWasSent(GameTestHelper helper) {
        UUID uuid = UUID.randomUUID();
        for (WorkActivity activity : List.of(WorkActivity.OFF, WorkActivity.IDLE,
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
                new WorkerDebugPacket.Entry(42, new WorkActivity(WorkActivity.Phase.WALKING, JobType.HARVEST_CROPS),
                        new BlockPos(10, 64, -3), List.of(new BlockPos(1, 64, 1), new BlockPos(2, 65, 1))),
                new WorkerDebugPacket.Entry(7, WorkActivity.IDLE, null, List.of()));
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
        helper.onEachTick(() -> {
            for (WorkerDebugPacket.Entry entry : WorkerDebugSync.entriesIn(helper.getLevel(), area)) {
                if (entry.entityId() != machop.getId() || entry.activity().job() != JobType.CHOP_TREE) {
                    continue;
                }
                seen.add(entry.activity().phase());
                if (entry.activity().phase() == WorkActivity.Phase.WALKING && !entry.path().isEmpty()
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
                WorkActivity.OFF, "activity"));
    }
}
