package dev.palworks.config;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.palworks.Palworks;
import dev.palworks.job.WorkRange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/**
 * Server settings players and modpacks can tune, from {@code config/palworks.json}. Every field
 * is optional (left out, it keeps its default), and each must be in its range, or the file is
 * ignored with a warning and the defaults are used. Read when the server starts; admins can also
 * change each setting in game ({@code /palworks config}, see {@link ConfigKeys}), which writes the file.
 *
 * @param workRangeHorizontal how far (blocks, each way) round itself a worker looks for and takes
 *     jobs
 * @param workRangeVertical how far up and down it does
 * @param ownerRange how far (straight line) from its owner a party Pokémon takes jobs. At most
 *     Cobblemon's teleport-back distance ({@link WorkRange#OWNER_LEASH}), or it would be yanked back mid-walk
 * @param pickupRadius how far round a pickup job's spot its items are looked for and picked up
 * @param fertilizeChancePerLevel the chance, for each of a waterer's Watering levels, that watering
 *     a crop also fertilizes it, as bone meal would: 0.1 is 10% at level 1, 30% at level 3. At most
 *     0.2, so level 5 is at most certain
 * @param wateringCooldownSeconds how long after a crop is watered until it can be watered again
 * @param hunger how workers get hungry and eat (the {@code hunger} object in the file)
 * @param sleep how pastured workers sleep (the {@code sleep} object in the file)
 * @param mood how workers' mood goes down and back up (the {@code mood} object in the file)
 */
public record PalworksConfig(
        int workRangeHorizontal,
        int workRangeVertical,
        int ownerRange,
        double pickupRadius,
        double fertilizeChancePerLevel,
        int wateringCooldownSeconds,
        HungerConfig hunger,
        SleepConfig sleep,
        MoodConfig mood) {
    public static final PalworksConfig DEFAULTS = new PalworksConfig(
            32, 32, 24, 3.0, 0.1, 60, HungerConfig.DEFAULTS, SleepConfig.DEFAULTS, MoodConfig.DEFAULTS);

    /** These settings, with the default hunger, sleep and mood settings. */
    public PalworksConfig(
            int workRangeHorizontal,
            int workRangeVertical,
            int ownerRange,
            double pickupRadius,
            double fertilizeChancePerLevel,
            int wateringCooldownSeconds) {
        this(
                workRangeHorizontal,
                workRangeVertical,
                ownerRange,
                pickupRadius,
                fertilizeChancePerLevel,
                wateringCooldownSeconds,
                HungerConfig.DEFAULTS,
                SleepConfig.DEFAULTS,
                MoodConfig.DEFAULTS);
    }

    /** These settings with other hunger settings. */
    public PalworksConfig withHunger(HungerConfig hunger) {
        return new PalworksConfig(
                workRangeHorizontal,
                workRangeVertical,
                ownerRange,
                pickupRadius,
                fertilizeChancePerLevel,
                wateringCooldownSeconds,
                hunger,
                sleep,
                mood);
    }

    /** These settings with other sleep settings. */
    public PalworksConfig withSleep(SleepConfig sleep) {
        return new PalworksConfig(
                workRangeHorizontal,
                workRangeVertical,
                ownerRange,
                pickupRadius,
                fertilizeChancePerLevel,
                wateringCooldownSeconds,
                hunger,
                sleep,
                mood);
    }

    /** These settings with other mood settings. */
    public PalworksConfig withMood(MoodConfig mood) {
        return new PalworksConfig(
                workRangeHorizontal,
                workRangeVertical,
                ownerRange,
                pickupRadius,
                fertilizeChancePerLevel,
                wateringCooldownSeconds,
                hunger,
                sleep,
                mood);
    }

    /**
     * How a worker's mood (0 to 100, separate from friendship) goes down and back up. Work wears it
     * down, and being Hungry or Tired too; while it's neither, and not at work, it comes back up. Below
     * {@code unhappyBelow} it's unhappy (a warning, nothing more), and at 0 it goes on strike: no work (it still eats and
     * sleeps) until it's back to {@code strikeUntil}. With its work off, or in its ball, it holds.
     *
     * @param enabled whether workers have a mood at all. Off, nothing here applies
     * @param workPerPoint how many units of work (as for hunger) take one point off
     * @param hungryDrainPerDay the points it loses over a Minecraft day while Hungry (no fullness left)
     * @param tiredDrainPerDay the points it loses over a day while Tired (it slept without a bed)
     * @param recoveryPerDay the points it gains back over a day while it's neither, and not at work
     * @param unhappyBelow the mood below which it's unhappy: on its way to a strike
     * @param strikeUntil the mood a worker on strike has to be back to before it works again
     */
    public record MoodConfig(
            boolean enabled,
            int workPerPoint,
            double hungryDrainPerDay,
            double tiredDrainPerDay,
            double recoveryPerDay,
            int unhappyBelow,
            int strikeUntil) {
        public static final MoodConfig DEFAULTS = new MoodConfig(true, 2, 50, 25, 100, 50, 30);

        public static final Codec<MoodConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        field(Codec.BOOL, "enabled", DEFAULTS.enabled()).forGetter(MoodConfig::enabled),
                        field(Codec.intRange(1, 500), "work_per_point", DEFAULTS.workPerPoint())
                                .forGetter(MoodConfig::workPerPoint),
                        field(Codec.doubleRange(0.0, 1000.0), "hungry_drain_per_day", DEFAULTS.hungryDrainPerDay())
                                .forGetter(MoodConfig::hungryDrainPerDay),
                        field(Codec.doubleRange(0.0, 1000.0), "tired_drain_per_day", DEFAULTS.tiredDrainPerDay())
                                .forGetter(MoodConfig::tiredDrainPerDay),
                        field(Codec.doubleRange(0.0, 1000.0), "recovery_per_day", DEFAULTS.recoveryPerDay())
                                .forGetter(MoodConfig::recoveryPerDay),
                        field(Codec.intRange(0, 100), "unhappy_below", DEFAULTS.unhappyBelow())
                                .forGetter(MoodConfig::unhappyBelow),
                        field(Codec.intRange(1, 100), "strike_until", DEFAULTS.strikeUntil())
                                .forGetter(MoodConfig::strikeUntil))
                .apply(instance, MoodConfig::new));
    }

    /**
     * How pastured workers sleep (party workers never do: they stay awake with their trainer). Each
     * sleeps in its species' Cobblemon sleep hours, at night if those are "any"; in a bed, or it wakes
     * Tired.
     *
     * @param enabled whether pastured workers keep a sleep schedule. Off, their sleep is Cobblemon's
     *     alone, as for any pastured Pokémon
     * @param tiredWorkSpeed how fast a Tired worker (one that slept without a bed) works and walks, as
     *     a share of its normal speed
     */
    public record SleepConfig(boolean enabled, double tiredWorkSpeed) {
        public static final SleepConfig DEFAULTS = new SleepConfig(true, 0.75);

        public static final Codec<SleepConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        field(Codec.BOOL, "enabled", DEFAULTS.enabled()).forGetter(SleepConfig::enabled),
                        field(Codec.doubleRange(0.1, 1.0), "tired_work_speed", DEFAULTS.tiredWorkSpeed())
                                .forGetter(SleepConfig::tiredWorkSpeed))
                .apply(instance, SleepConfig::new));
    }

    /**
     * How workers get hungry and eat. Hunger is the Pokémon's own Cobblemon fullness. While a sent-out
     * Pokémon's work is switched on, Cobblemon's own drain of it is paused and these rates apply
     * instead; with its work off it's left entirely to Cobblemon.
     *
     * @param enabled whether workers get hungry at all. Off, Palworks leaves fullness to Cobblemon
     * @param workPerFullness how many units of work (a crop harvested, a seed planted, a trip made,
     *     every 8 logs of a tree) use up one point of fullness
     * @param idleDrainPerDay the share of its max fullness a worker loses over a Minecraft day while
     *     its work is on, working or not (0 for none)
     * @param troughFillFraction how full troughs fill a worker: it eats while below this share of its
     *     max fullness (rounded up). The rest is left for hand-feeding (Mochi, berries), which a full
     *     Pokémon refuses. 0 and troughs never feed
     * @param emptyWorkSpeed how fast a worker with no fullness left works and walks, as a share of
     *     its normal speed
     */
    public record HungerConfig(
            boolean enabled,
            int workPerFullness,
            double idleDrainPerDay,
            double troughFillFraction,
            double emptyWorkSpeed) {
        public static final HungerConfig DEFAULTS = new HungerConfig(true, 20, 0.25, 0.5, 0.75);

        public static final Codec<HungerConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        field(Codec.BOOL, "enabled", DEFAULTS.enabled()).forGetter(HungerConfig::enabled),
                        field(Codec.intRange(1, 500), "work_per_fullness", DEFAULTS.workPerFullness())
                                .forGetter(HungerConfig::workPerFullness),
                        field(Codec.doubleRange(0.0, 1.0), "idle_drain_per_day", DEFAULTS.idleDrainPerDay())
                                .forGetter(HungerConfig::idleDrainPerDay),
                        field(Codec.doubleRange(0.0, 1.0), "trough_fill_fraction", DEFAULTS.troughFillFraction())
                                .forGetter(HungerConfig::troughFillFraction),
                        field(Codec.doubleRange(0.1, 1.0), "empty_work_speed", DEFAULTS.emptyWorkSpeed())
                                .forGetter(HungerConfig::emptyWorkSpeed))
                .apply(instance, HungerConfig::new));
    }

    public static final Codec<PalworksConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    field(Codec.intRange(8, 64), "work_range_horizontal", DEFAULTS.workRangeHorizontal())
                            .forGetter(PalworksConfig::workRangeHorizontal),
                    field(Codec.intRange(4, 64), "work_range_vertical", DEFAULTS.workRangeVertical())
                            .forGetter(PalworksConfig::workRangeVertical),
                    field(Codec.intRange(4, WorkRange.OWNER_LEASH), "owner_range", DEFAULTS.ownerRange())
                            .forGetter(PalworksConfig::ownerRange),
                    field(Codec.doubleRange(1.0, 8.0), "pickup_radius", DEFAULTS.pickupRadius())
                            .forGetter(PalworksConfig::pickupRadius),
                    field(Codec.doubleRange(0.0, 0.2), "fertilize_chance_per_level", DEFAULTS.fertilizeChancePerLevel())
                            .forGetter(PalworksConfig::fertilizeChancePerLevel),
                    field(Codec.intRange(5, 600), "watering_cooldown_seconds", DEFAULTS.wateringCooldownSeconds())
                            .forGetter(PalworksConfig::wateringCooldownSeconds),
                    field(HungerConfig.CODEC, "hunger", DEFAULTS.hunger()).forGetter(PalworksConfig::hunger),
                    field(SleepConfig.CODEC, "sleep", DEFAULTS.sleep()).forGetter(PalworksConfig::sleep),
                    field(MoodConfig.CODEC, "mood", DEFAULTS.mood()).forGetter(PalworksConfig::mood))
            .apply(instance, PalworksConfig::new));

    private static final String FILE_NAME = "palworks.json";

    /**
     * An optional field: left out, it's {@code fallback}. Unlike {@code optionalFieldOf(name,
     * fallback)}, it's always written, so the file written with the defaults shows every setting.
     */
    private static <T> MapCodec<T> field(Codec<T> codec, String name, T fallback) {
        return codec.optionalFieldOf(name).xmap(value -> value.orElse(fallback), Optional::of);
    }

    private static volatile PalworksConfig current = DEFAULTS;
    // The file read by load, which save writes: none until then (in tests, and in-game tests)
    @Nullable
    private static volatile Path loadedFrom;

    /** The settings in use: the defaults until {@link #load} has run. */
    public static PalworksConfig current() {
        return current;
    }

    /** Use these settings (tests). */
    public static void setCurrent(PalworksConfig config) {
        current = config;
    }

    /**
     * Read {@code palworks.json} from the loader's config folder, writing it with the defaults if
     * it isn't there, so there's a file to edit. Problems are logged, and the defaults used.
     */
    public static void load(Path configDir) {
        Path file = configDir.resolve(FILE_NAME);
        loadedFrom = file;
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

    /**
     * Use these settings from now on ({@code /palworks config}), and write them to {@code palworks.json}
     * (once {@link #load} has read it). If they can't be written, nothing changes.
     */
    public static void save(PalworksConfig config) throws IOException {
        Path file = loadedFrom;
        if (file != null) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, toJson(config), StandardCharsets.UTF_8);
        }
        current = config;
    }

    /** The settings in {@code json}, or the defaults (and a warning) if it isn't valid. */
    public static PalworksConfig parse(String json, Consumer<String> warn) {
        JsonElement element;
        try {
            element = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            warn.accept("it isn't valid JSON (" + e.getMessage() + ")");
            return DEFAULTS;
        }
        DataResult<PalworksConfig> result = CODEC.parse(JsonOps.INSTANCE, element);
        if (result.error().isPresent()) {
            warn.accept(result.error().get().message());
            return DEFAULTS;
        }
        return result.getOrThrow();
    }

    /** The settings as the JSON file holds them. */
    public static String toJson(PalworksConfig config) {
        JsonElement json = CODEC.encodeStart(JsonOps.INSTANCE, config).getOrThrow();
        return new GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n";
    }
}
