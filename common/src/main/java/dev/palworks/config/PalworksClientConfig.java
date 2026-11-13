package dev.palworks.config;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.palworks.Palworks;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Each player's own display settings, from {@code config/palworks-client.json} (written with the
 * defaults the first time the game starts). Every field is optional: left out, it keeps its default.
 * Read when the game starts.
 *
 * @param showStatusIcons whether workers show what's holding their work back (hungry...) as an icon
 *     over their heads
 */
public record PalworksClientConfig(boolean showStatusIcons) {
    public static final PalworksClientConfig DEFAULTS = new PalworksClientConfig(true);

    public static final Codec<PalworksClientConfig> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(Codec.BOOL
                            .optionalFieldOf("show_status_icons")
                            .xmap(value -> value.orElse(DEFAULTS.showStatusIcons()), Optional::of)
                            .forGetter(PalworksClientConfig::showStatusIcons))
                    .apply(instance, PalworksClientConfig::new));

    private static final String FILE_NAME = "palworks-client.json";

    private static volatile PalworksClientConfig current = DEFAULTS;

    /** The settings in use: the defaults until {@link #load} has run. */
    public static PalworksClientConfig current() {
        return current;
    }

    /**
     * Read {@code palworks-client.json} from the config folder, writing it with the defaults if it
     * isn't there, so there's a file to edit. Problems are logged, and the defaults used.
     */
    public static void load(Path configDir) {
        Path file = configDir.resolve(FILE_NAME);
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(configDir);
                Files.writeString(file, toJson(DEFAULTS), StandardCharsets.UTF_8);
                current = DEFAULTS;
                return;
            }
            current = parse(
                    Files.readString(file, StandardCharsets.UTF_8),
                    message -> Palworks.LOGGER.warn("Ignoring config/{}: {}. Using the defaults.", FILE_NAME, message));
        } catch (IOException e) {
            Palworks.LOGGER.warn("Couldn't read or write config/{}; using the defaults", FILE_NAME, e);
            current = DEFAULTS;
        }
    }

    /** The settings in {@code json}, or the defaults (and a warning) if it isn't valid. */
    public static PalworksClientConfig parse(String json, Consumer<String> warn) {
        JsonElement element;
        try {
            element = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            warn.accept("it isn't valid JSON (" + e.getMessage() + ")");
            return DEFAULTS;
        }
        DataResult<PalworksClientConfig> result = CODEC.parse(JsonOps.INSTANCE, element);
        if (result.error().isPresent()) {
            warn.accept(result.error().get().message());
            return DEFAULTS;
        }
        return result.getOrThrow();
    }

    /** The settings as the JSON file holds them. */
    public static String toJson(PalworksClientConfig config) {
        JsonElement json = CODEC.encodeStart(JsonOps.INSTANCE, config).getOrThrow();
        return new GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n";
    }
}
