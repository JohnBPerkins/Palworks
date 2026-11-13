package dev.palworks.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * The settings in {@code palworks.json} by name, for {@code /palworks config}: each one's path in the
 * file, with dots between objects' keys ({@code work_range_horizontal}, {@code hunger.enabled}).
 * Worked out from {@link PalworksConfig#CODEC}, so every setting the file has can be changed, in the
 * same ranges, and a new setting needs nothing here.
 */
public final class ConfigKeys {
    private ConfigKeys() {}

    /** Every setting's name, in the file's order. */
    public static List<String> names() {
        List<String> names = new ArrayList<>();
        addNames("", json(PalworksConfig.DEFAULTS), names);
        return names;
    }

    /** This setting's value in {@code config}, as the file writes it; null if there's no such setting. */
    @Nullable
    public static JsonPrimitive get(PalworksConfig config, String name) {
        JsonElement element = json(config);
        for (String part : name.split("\\.", -1)) {
            if (!(element instanceof JsonObject object) || !object.has(part)) {
                return null;
            }
            element = object.get(part);
        }
        return element instanceof JsonPrimitive value ? value : null;
    }

    /**
     * {@code config} with this setting set to {@code value} ({@code true} or {@code false}, or a
     * number); an error if there's no such setting, or the value isn't one it can have.
     */
    public static DataResult<PalworksConfig> set(PalworksConfig config, String name, String value) {
        JsonPrimitive was = get(config, name);
        if (was == null) {
            return DataResult.error(() -> "there's no setting \"" + name + "\"");
        }
        JsonPrimitive parsed;
        if (was.isBoolean()) {
            if (!"true".equals(value) && !"false".equals(value)) {
                return DataResult.error(() -> "it's true or false");
            }
            parsed = new JsonPrimitive(Boolean.parseBoolean(value));
        } else if (was.getAsNumber() instanceof Integer) {
            try {
                parsed = new JsonPrimitive(Integer.parseInt(value));
            } catch (NumberFormatException e) {
                return DataResult.error(() -> "it's a whole number");
            }
        } else {
            try {
                double number = Double.parseDouble(value);
                if (!Double.isFinite(number)) {
                    return DataResult.error(() -> "it's a number");
                }
                parsed = new JsonPrimitive(number);
            } catch (NumberFormatException e) {
                return DataResult.error(() -> "it's a number");
            }
        }
        return with(config, name, parsed);
    }

    /** {@code config} with this setting back to its default; an error if there's no such setting. */
    public static DataResult<PalworksConfig> reset(PalworksConfig config, String name) {
        JsonPrimitive fallback = get(PalworksConfig.DEFAULTS, name);
        if (fallback == null) {
            return DataResult.error(() -> "there's no setting \"" + name + "\"");
        }
        return with(config, name, fallback);
    }

    private static DataResult<PalworksConfig> with(PalworksConfig config, String name, JsonPrimitive value) {
        JsonObject root = json(config);
        String[] parts = name.split("\\.", -1);
        JsonObject parent = root;
        for (int i = 0; i < parts.length - 1; i++) {
            parent = parent.getAsJsonObject(parts[i]);
        }
        parent.add(parts[parts.length - 1], value);
        return PalworksConfig.CODEC.parse(JsonOps.INSTANCE, root);
    }

    private static JsonObject json(PalworksConfig config) {
        return PalworksConfig.CODEC
                .encodeStart(JsonOps.INSTANCE, config)
                .getOrThrow()
                .getAsJsonObject();
    }

    private static void addNames(String prefix, JsonObject object, List<String> names) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry.getValue() instanceof JsonObject inner) {
                addNames(prefix + entry.getKey() + ".", inner, names);
            } else {
                names.add(prefix + entry.getKey());
            }
        }
    }
}
