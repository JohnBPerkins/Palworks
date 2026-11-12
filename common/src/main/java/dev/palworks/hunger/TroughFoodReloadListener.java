package dev.palworks.hunger;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import dev.palworks.Palworks;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Loads {@code data/<namespace>/palworks/trough_food/<name>.json} into {@link TroughFood} when the
 * server starts and on {@code /reload}.
 */
public class TroughFoodReloadListener extends SimpleJsonResourceReloadListener {
    public static final ResourceLocation ID = Palworks.id("trough_food");
    public static final String DIRECTORY = "palworks/trough_food";

    public TroughFoodReloadListener() {
        super(new Gson(), DIRECTORY);
    }

    @Override
    protected void apply(
            Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<String, JsonElement> byId = new HashMap<>();
        files.forEach((id, json) -> byId.put(id.toString(), json));
        TroughFood values = TroughFood.fromFiles(byId, message -> Palworks.LOGGER.warn("[trough_food] {}", message));
        TroughFood.setCurrent(values);
        Palworks.LOGGER.info("Loaded trough food values: {} items, {} tags", values.itemCount(), values.tagCount());
    }
}
