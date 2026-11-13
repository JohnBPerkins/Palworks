package dev.palworks.block;

import dev.architectury.registry.CreativeTabRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.palworks.Palworks;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/**
 * Palworks's blocks, their items and block entities, registered on both loaders through
 * Architectury's {@link DeferredRegister}.
 */
public final class ModBlocks {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Palworks.MOD_ID, Registries.BLOCK);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Palworks.MOD_ID, Registries.ITEM);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Palworks.MOD_ID, Registries.BLOCK_ENTITY_TYPE);

    /**
     * The woods a trough is made of: every vanilla wood with planks and slabs in 1.21.1. Each trough
     * is {@code <wood>_trough}, made from {@code <wood>_slab}s, and behaves as its planks do (how
     * hard it is, its sound, whether it burns).
     */
    public static final List<String> WOODS = List.of(
            "oak",
            "spruce",
            "birch",
            "jungle",
            "acacia",
            "dark_oak",
            "mangrove",
            "cherry",
            "bamboo",
            "crimson",
            "warped");

    private static final Map<String, RegistrySupplier<Block>> TROUGHS = new LinkedHashMap<>();
    private static final Map<String, RegistrySupplier<Item>> TROUGH_ITEMS = new LinkedHashMap<>();

    static {
        for (String wood : WOODS) {
            String id = wood + "_trough";
            RegistrySupplier<Block> trough =
                    BLOCKS.register(id, () -> new TroughBlock(BlockBehaviour.Properties.ofFullCopy(planks(wood))));
            TROUGHS.put(wood, trough);
            TROUGH_ITEMS.put(wood, ITEMS.register(id, () -> new BlockItem(trough.get(), new Item.Properties())));
        }
    }

    /**
     * The colours a wool bed comes in: every dye's. Each is {@code <colour>_wool_bed}, made from
     * {@code <colour>_wool}, with large and huge ones as straw beds have.
     */
    public static final List<DyeColor> WOOL_COLORS = List.of(DyeColor.values());

    // A straw bed's cushion, in its id
    private static final String STRAW = "straw";

    // Every worker bed (see WorkerBedBlock), by id: straw, then each wool colour; each in its sizes
    private static final Map<String, RegistrySupplier<Block>> BEDS = new LinkedHashMap<>();
    private static final Map<String, RegistrySupplier<Item>> BED_ITEMS = new LinkedHashMap<>();

    static {
        for (int size = 1; size <= WorkerBedBlock.MAX_SIZE; size++) {
            bed(
                    STRAW,
                    size,
                    WorkerBedBlock.Tier.STRAW,
                    () -> BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_YELLOW)
                            .strength(0.5F)
                            .sound(SoundType.GRASS));
        }
        for (DyeColor color : WOOL_COLORS) {
            for (int size = 1; size <= WorkerBedBlock.MAX_SIZE; size++) {
                bed(
                        color.getSerializedName() + "_wool",
                        size,
                        WorkerBedBlock.Tier.WOOL,
                        () -> BlockBehaviour.Properties.of()
                                .mapColor(color.getMapColor())
                                .strength(0.8F)
                                .sound(SoundType.WOOL));
            }
        }
    }

    /** Straw beds, where pastured workers sleep: 1, 2 by 2 and 3 by 3. */
    public static final RegistrySupplier<Block> STRAW_BED = BEDS.get(bedId(STRAW, 1));

    public static final RegistrySupplier<Block> LARGE_STRAW_BED = BEDS.get(bedId(STRAW, 2));
    public static final RegistrySupplier<Block> HUGE_STRAW_BED = BEDS.get(bedId(STRAW, 3));

    public static final RegistrySupplier<Item> STRAW_BED_ITEM = BED_ITEMS.get(bedId(STRAW, 1));
    public static final RegistrySupplier<Item> LARGE_STRAW_BED_ITEM = BED_ITEMS.get(bedId(STRAW, 2));
    public static final RegistrySupplier<Item> HUGE_STRAW_BED_ITEM = BED_ITEMS.get(bedId(STRAW, 3));

    private static void bed(
            String cushion, int size, WorkerBedBlock.Tier tier, Supplier<BlockBehaviour.Properties> properties) {
        String id = bedId(cushion, size);
        RegistrySupplier<Block> bed = BLOCKS.register(
                id,
                () -> new WorkerBedBlock(
                        tier, size, properties.get().ignitedByLava().noOcclusion()));
        BEDS.put(id, bed);
        BED_ITEMS.put(id, ITEMS.register(id, () -> new BlockItem(bed.get(), new Item.Properties())));
    }

    /** A bed's id: {@code straw_bed}, {@code large_red_wool_bed} and so on. */
    private static String bedId(String cushion, int size) {
        String prefix = switch (size) {
            case 1 -> "";
            case 2 -> "large_";
            default -> "huge_";
        };
        return prefix + cushion + "_bed";
    }

    /** The wool bed of this colour and size (1 to {@link WorkerBedBlock#MAX_SIZE}). */
    public static RegistrySupplier<Block> woolBed(DyeColor color, int size) {
        return BEDS.get(bedId(color.getSerializedName() + "_wool", size));
    }

    /** The wool bed of this colour and size, as an item. */
    public static RegistrySupplier<Item> woolBedItem(DyeColor color, int size) {
        return BED_ITEMS.get(bedId(color.getSerializedName() + "_wool", size));
    }

    /** Every worker bed's id, straw then wool. */
    public static List<String> bedIds() {
        return List.copyOf(BEDS.keySet());
    }

    // Built with no data fixer type, as mods' block entities are
    public static final RegistrySupplier<BlockEntityType<TroughBlockEntity>> TROUGH_ENTITY = BLOCK_ENTITIES.register(
            "trough",
            () -> BlockEntityType.Builder.of(
                            TroughBlockEntity::new,
                            TROUGHS.values().stream().map(Supplier::get).toArray(Block[]::new))
                    .build(null));

    private ModBlocks() {}

    private static Block planks(String wood) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(wood + "_planks"));
    }

    /** The trough of this wood (one of {@link #WOODS}). */
    public static RegistrySupplier<Block> trough(String wood) {
        return TROUGHS.get(wood);
    }

    /** The trough of this wood, as an item. */
    public static RegistrySupplier<Item> troughItem(String wood) {
        return TROUGH_ITEMS.get(wood);
    }

    /** Every trough, by wood, in {@link #WOODS}' order. */
    public static Map<String, RegistrySupplier<Block>> troughs() {
        return Collections.unmodifiableMap(TROUGHS);
    }

    /** Register the blocks, items and block entities. Called once, from {@link dev.palworks.PalworksCommon#init}. */
    public static void register() {
        BLOCKS.register();
        ITEMS.register();
        BLOCK_ENTITIES.register();
        // Next to the composter and the other functional blocks
        for (RegistrySupplier<Item> item : TROUGH_ITEMS.values()) {
            Supplier<ItemStack> stack = () -> new ItemStack(item.get());
            CreativeTabRegistry.appendStack(CreativeModeTabs.FUNCTIONAL_BLOCKS, stack);
        }
        for (RegistrySupplier<Item> item : BED_ITEMS.values()) {
            Supplier<ItemStack> bed = () -> new ItemStack(item.get());
            CreativeTabRegistry.appendStack(CreativeModeTabs.FUNCTIONAL_BLOCKS, bed);
        }
    }
}
