package dev.palworks.suitability;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import dev.palworks.Palworks;
import dev.palworks.net.PalworksResource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.HashMap;
import java.util.Map;

/**
 * Loads {@code data/<namespace>/palworks/suitability/<job>.json} into {@link SuitabilityData} when
 * the server starts and on {@code /reload}. Each loader registers it (Fabric's resource loader,
 * NeoForge's AddReloadListenerEvent).
 */
public class SuitabilityReloadListener extends SimpleJsonResourceReloadListener {
    public static final ResourceLocation ID = PalworksResource.of("suitability");
    public static final String DIRECTORY = "palworks/suitability";

    public SuitabilityReloadListener() {
        super(new Gson(), DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<String, JsonElement> byId = new HashMap<>();
        files.forEach((id, json) -> byId.put(id.toString(), json));
        SuitabilityData data = SuitabilityData.fromFiles(byId, message -> Palworks.LOGGER.warn("[suitability] {}", message));
        SuitabilityData.setCurrent(data);
        Palworks.LOGGER.info("Loaded work suitabilities: {} jobs, {} types, {} species",
                data.jobCount(), data.typeCount(), data.speciesCount());
    }
}
