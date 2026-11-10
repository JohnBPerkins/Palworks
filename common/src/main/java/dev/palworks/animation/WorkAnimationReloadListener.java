package dev.palworks.animation;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import dev.palworks.Palworks;
import dev.palworks.net.PalworksResource;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Loads {@code data/<namespace>/palworks/work_animations/<name>.json} into
 * {@link WorkAnimationData} when the server starts and on {@code /reload}. Each loader registers it
 * (Fabric's resource loader, NeoForge's AddReloadListenerEvent).
 */
public class WorkAnimationReloadListener extends SimpleJsonResourceReloadListener {
    public static final ResourceLocation ID = PalworksResource.of("work_animations");
    public static final String DIRECTORY = "palworks/work_animations";

    public WorkAnimationReloadListener() {
        super(new Gson(), DIRECTORY);
    }

    @Override
    protected void apply(
            Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<String, JsonElement> byId = new HashMap<>();
        files.forEach((id, json) -> byId.put(id.toString(), json));
        WorkAnimationData data = WorkAnimationData.fromFiles(
                byId, WorkAnimations.ALL, message -> Palworks.LOGGER.warn("[work animations] {}", message));
        WorkAnimationData.setCurrent(data);
        Palworks.LOGGER.info("Loaded work animations: {} species with their own", data.speciesCount());
    }
}
