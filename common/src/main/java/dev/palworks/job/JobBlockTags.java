package dev.palworks.job;

import dev.palworks.net.PalworksResource;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * Data-driven block tags used by job detection.
 *
 * <p>Backed by datapack tag files under {@code data/palworks/tags/block/}. Modpacks and addons
 * can extend what counts as work simply by adding blocks to these tags — no code change needed.
 * (Logs are intentionally not duplicated here; tree detection uses
 * {@link dev.palworks.tree.TreeDetector#isLog} which already keys off {@code #minecraft:logs}.)
 */
public final class JobBlockTags {
    private JobBlockTags() {}

    /**
     * Furnaces a KINDLING worker lights for {@link JobType#FUEL_FURNACE}. Each must have a furnace
     * block entity ({@code AbstractFurnaceBlockEntity}); any other block in the tag is ignored.
     */
    public static final TagKey<Block> FURNACES = TagKey.create(Registries.BLOCK, PalworksResource.of("furnaces"));

    /** Blocks a MINING worker will break for {@link JobType#MINE_RESOURCE}. */
    public static final TagKey<Block> ORES = TagKey.create(Registries.BLOCK, PalworksResource.of("ores"));
}
