package dev.palworks.advancement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.palworks.advancement.WorkerJobTrigger.TriggerInstance;
import dev.palworks.job.JobType;
import dev.palworks.suitability.Suitability;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderSet;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WorkerJobTriggerTest {
    private static final String LEGENDARY = "legendary";

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static JobDone chop(int logs, int lumbering) {
        return new JobDone(
                JobType.CHOP_TREE,
                Map.of(Suitability.LUMBERING, lumbering),
                Blocks.OAK_LOG.defaultBlockState(),
                logs,
                Set.of());
    }

    private static TriggerInstance when(
            Optional<List<JobType>> jobs,
            Optional<Suitability> skill,
            Optional<Integer> minLevel,
            Optional<HolderSet<Block>> blocks,
            Optional<Integer> minAmount,
            Optional<List<String>> labels) {
        return new TriggerInstance(Optional.empty(), jobs, skill, minLevel, blocks, minAmount, labels);
    }

    private static TriggerInstance any() {
        return when(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    @Test
    void withNoConditionsAnyWorkCounts() {
        assertTrue(any().matches(chop(5, 1)));
    }

    @Test
    void jobsAndSkillsMustBeAmongTheJobs() {
        TriggerInstance chopping = when(
                Optional.of(List.of(JobType.CHOP_TREE)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        TriggerInstance nether = when(
                Optional.empty(),
                Optional.of(Suitability.NETHER),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        assertTrue(chopping.matches(chop(5, 1)));
        assertFalse(nether.matches(chop(5, 1)), "an oak is Lumbering alone");
        JobDone netherTree = new JobDone(
                JobType.CHOP_TREE,
                Map.of(Suitability.LUMBERING, 2, Suitability.NETHER, 1),
                Blocks.CRIMSON_STEM.defaultBlockState(),
                5,
                Set.of());
        assertTrue(nether.matches(netherTree), "a nether tree takes Nether too");
    }

    @Test
    void aMinimumLevelWithNoSkillNeedsItInEverySkillTheJobTook() {
        TriggerInstance levelFive = when(
                Optional.empty(),
                Optional.empty(),
                Optional.of(5),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        TriggerInstance netherFive = when(
                Optional.empty(),
                Optional.of(Suitability.NETHER),
                Optional.of(5),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        JobDone mixed = new JobDone(
                JobType.CHOP_TREE,
                Map.of(Suitability.LUMBERING, 2, Suitability.NETHER, 5),
                Blocks.CRIMSON_STEM.defaultBlockState(),
                5,
                Set.of());
        assertTrue(levelFive.matches(chop(5, 5)));
        assertFalse(levelFive.matches(chop(5, 4)));
        assertFalse(levelFive.matches(mixed), "Lumbering is only 2");
        assertTrue(netherFive.matches(mixed), "its Nether is 5");
    }

    @Test
    void blocksAmountAndLabelsAreChecked() {
        TriggerInstance debris = when(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(HolderSet.direct(Blocks.ANCIENT_DEBRIS.builtInRegistryHolder())),
                Optional.empty(),
                Optional.empty());
        JobDone mined = new JobDone(
                JobType.MINE_BOULDER,
                Map.of(Suitability.MINING, 2),
                Blocks.ANCIENT_DEBRIS.defaultBlockState(),
                1,
                Set.of());
        assertTrue(debris.matches(mined));
        assertFalse(debris.matches(chop(5, 1)));

        TriggerInstance bigTree = when(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(128),
                Optional.empty());
        assertTrue(bigTree.matches(chop(128, 4)));
        assertFalse(bigTree.matches(chop(127, 4)));

        TriggerInstance legendary = when(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(List.of(LEGENDARY, "mythical")));
        JobDone groudon = new JobDone(
                JobType.SUMMON_BOULDER,
                Map.of(Suitability.UNEARTHING, 5),
                Blocks.AIR.defaultBlockState(),
                1,
                Set.of(LEGENDARY, "gen3"));
        assertTrue(legendary.matches(groudon));
        assertFalse(legendary.matches(chop(5, 1)));
    }

    @Test
    void conditionsAreReadByJobAndSkillId() {
        String json = "{\"jobs\": [\"fuel_furnace\"], \"skill\": \"kindling\", \"min_level\": 5}";
        TriggerInstance read = TriggerInstance.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString(json))
                .getOrThrow();
        JobDone lit = new JobDone(
                JobType.FUEL_FURNACE, Map.of(Suitability.KINDLING, 5), Blocks.FURNACE.defaultBlockState(), 1, Set.of());
        assertTrue(read.matches(lit));
        assertTrue(
                TriggerInstance.CODEC
                        .parse(JsonOps.INSTANCE, JsonParser.parseString("{\"jobs\": [\"juggling\"]}"))
                        .isError(),
                "an unknown job type is an error");
    }
}
