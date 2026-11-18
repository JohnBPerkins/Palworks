package dev.palworks.client;

import dev.palworks.inventory.CarryMode;
import dev.palworks.net.packets.WorkerDebugPacket;
import dev.palworks.net.packets.WorkerMoodPacket;
import dev.palworks.suitability.Suitability;
import dev.palworks.worker.WorkActivity;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * The client's copies of what the server sent about workers, filled by the packet handlers
 * (net.handlers) and read by what draws them:
 * <ul>
 *   <li>what the player's workers are doing (WorkActivityPacket), for the party HUD
 *       ({@code WorkStatusHud}), by Pokémon UUID;</li>
 *   <li>a Pokémon's work status (WorkerStatusPacket), sent just before its interact wheel opens
 *       and read once when the wheel is built ({@code PokemonInteractHandler}), by entity UUID.
 *       Only used to draw the wheel's options: the server never trusts it;</li>
 *   <li>the workers near the player (WorkerDebugPacket), for the debug overlay
 *       ({@code WorkerDebugRenderer}). Empty unless the player has {@code /palworks debug} on;</li>
 *   <li>the work levels of the player's Pokémon, party and PC (WorkLevelsPacket), for the PC and
 *       summary screens ({@code WorkSkillsOverlay}), by Pokémon UUID;</li>
 *   <li>their moods (WorkerMoodPacket), for the same screens, by Pokémon UUID;</li>
 *   <li>which work skills each species can have (DexWorkSkillsPacket), for the Pokédex's work
 *       skill filter ({@code DexWorkSkillFilter}), by species id.</li>
 * </ul>
 * All of it is the world or server's the player is in, and is forgotten as they leave it
 * ({@link #clear}, from {@code PalworksClient}), and only then: the server keeps each of them up to
 * date as it changes, and a change of dimension changes none of it. (The party HUD used to forget
 * what the workers were doing whenever the client's level changed, a new dimension included, and
 * was blank until the server next said.)
 *
 * <p>Has no client-only imports, so it is safe to load on a dedicated server (where Cobblemon still
 * constructs the client packet handlers).
 */
public final class ClientWorkerData {
    /** What the server said about one Pokémon's work. */
    public record Status(boolean enabled, CarryMode carryMode) {}

    private static final Map<UUID, WorkActivity> ACTIVITY = new ConcurrentHashMap<>();
    private static final Map<UUID, Status> STATUS = new ConcurrentHashMap<>();
    private static volatile List<WorkerDebugPacket.Entry> debugEntries = List.of();
    private static final Map<UUID, Map<Suitability, Integer>> WORK_LEVELS = new ConcurrentHashMap<>();
    private static final Map<UUID, WorkerMoodPacket.Shown> MOODS = new ConcurrentHashMap<>();
    private static volatile int strikeUntil;
    private static volatile Map<ResourceLocation, Set<Suitability>> speciesSkills = Map.of();

    private ClientWorkerData() {}

    /** Forget all of it: the player has left the world or server it was about. */
    public static void clear() {
        ACTIVITY.clear();
        STATUS.clear();
        debugEntries = List.of();
        WORK_LEVELS.clear();
        MOODS.clear();
        speciesSkills = Map.of();
    }

    // --- Work activity (party HUD) ---

    public static void putActivity(UUID pokemonUUID, WorkActivity activity) {
        ACTIVITY.put(pokemonUUID, activity);
    }

    @Nullable
    public static WorkActivity activity(UUID pokemonUUID) {
        return ACTIVITY.get(pokemonUUID);
    }

    // --- Work status (interact wheel) ---

    public static void putStatus(UUID entityUUID, Status status) {
        STATUS.put(entityUUID, status);
    }

    /**
     * The status sent for this entity, removing it so a later wheel never reuses a stale value.
     * Null when the server sent none (the Pokémon can't do any work).
     */
    @Nullable
    public static Status takeStatus(UUID entityUUID) {
        return STATUS.remove(entityUUID);
    }

    // --- Nearby workers (debug overlay) ---

    public static void setDebugEntries(List<WorkerDebugPacket.Entry> entries) {
        debugEntries = List.copyOf(entries);
    }

    public static List<WorkerDebugPacket.Entry> debugEntries() {
        return debugEntries;
    }

    // --- Work levels (PC and summary screens) ---

    public static void putWorkLevels(Map<UUID, Map<Suitability, Integer>> levels) {
        WORK_LEVELS.putAll(levels);
    }

    /** A Pokémon's level for each job it can do (empty if none), or null if the server hasn't said. */
    @Nullable
    public static Map<Suitability, Integer> workLevels(UUID pokemonUUID) {
        return WORK_LEVELS.get(pokemonUUID);
    }

    // --- Mood (PC and summary) ---

    public static void putMoods(Map<UUID, WorkerMoodPacket.Shown> moods, int strikeUntil) {
        MOODS.putAll(moods);
        ClientWorkerData.strikeUntil = strikeUntil;
    }

    /** Forget every mood (mood was turned off on the server). */
    public static void clearMoods() {
        MOODS.clear();
    }

    /** A Pokémon's mood, or null if the server hasn't said (or mood is off). */
    @Nullable
    public static WorkerMoodPacket.Shown mood(UUID pokemonUUID) {
        return MOODS.get(pokemonUUID);
    }

    /** The mood a worker on strike works again at (the server's config). */
    public static int strikeUntil() {
        return strikeUntil;
    }

    // --- Species' work skills (Pokédex filter) ---

    public static void setSpeciesSkills(Map<ResourceLocation, Set<Suitability>> skills) {
        speciesSkills = Map.copyOf(skills);
    }

    /** Whether a species can have this work skill, in any form. False until the server has said. */
    public static boolean speciesHasSkill(ResourceLocation species, Suitability skill) {
        return speciesSkills.getOrDefault(species, Set.of()).contains(skill);
    }
}
