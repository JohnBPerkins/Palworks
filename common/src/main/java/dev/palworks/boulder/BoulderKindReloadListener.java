package dev.palworks.boulder;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import dev.palworks.Palworks;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Loads {@code data/<namespace>/palworks/boulders/<kind>.json} into {@link BoulderKinds} when the
 * server starts and on {@code /reload}. Registered once for both loaders, through Architectury
 * ({@code PalworksCommon.init}).
 */
public class BoulderKindReloadListener extends SimpleJsonResourceReloadListener {
    public static final ResourceLocation ID = Palworks.id("boulders");
    public static final String DIRECTORY = "palworks/boulders";

    public BoulderKindReloadListener() {
        super(new Gson(), DIRECTORY);
    }

    @Override
    protected void apply(
            Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<String, JsonElement> byId = new HashMap<>();
        files.forEach((id, json) -> byId.put(id.toString(), json));
        BoulderKinds kinds = BoulderKinds.fromFiles(
                byId, BoulderKindReloadListener::isBlock, message -> Palworks.LOGGER.warn("[boulders] {}", message));
        BoulderKinds.setCurrent(kinds);
        Palworks.LOGGER.info("Loaded boulder kinds: {}", kinds.all().size());
    }

    /** Whether there's a block of this id (not air, which every unknown id would come back as). */
    static boolean isBlock(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location != null && BuiltInRegistries.BLOCK.containsKey(location);
    }
}
