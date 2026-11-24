package dev.palworks.boulder;

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
 * Loads {@code data/<namespace>/palworks/boulder_ores/<name>.json} into {@link BoulderOreLevels}
 * when the server starts and on {@code /reload}. Registered with {@link BoulderKindReloadListener}.
 */
public class BoulderOreLevelsReloadListener extends SimpleJsonResourceReloadListener {
    public static final ResourceLocation ID = Palworks.id("boulder_ores");
    public static final String DIRECTORY = "palworks/boulder_ores";

    public BoulderOreLevelsReloadListener() {
        super(new Gson(), DIRECTORY);
    }

    @Override
    protected void apply(
            Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<String, JsonElement> byId = new HashMap<>();
        files.forEach((id, json) -> byId.put(id.toString(), json));
        BoulderOreLevels levels = BoulderOreLevels.fromFiles(
                byId,
                BoulderKindReloadListener::isBlock,
                message -> Palworks.LOGGER.warn("[boulder_ores] {}", message));
        BoulderOreLevels.setCurrent(levels);
        Palworks.LOGGER.info("Loaded boulder ore levels: {} blocks", levels.size());
    }
}
