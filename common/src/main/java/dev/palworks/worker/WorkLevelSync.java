package dev.palworks.worker;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.config.PalworksConfig;
import dev.palworks.mood.Mood;
import dev.palworks.mood.MoodMath;
import dev.palworks.net.packets.WorkLevelsPacket;
import dev.palworks.net.packets.WorkerMoodPacket;
import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityDetector;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Keeps each player told their Pokémon's work levels ({@link WorkLevelsPacket}), for the PC and
 * summary screens:
 * <ul>
 *   <li>their party's, checked every {@link #PARTY_CHECK_TICKS} and sent when they change: when
 *       they join, and after an evolution, a form change, a party change or a {@code /reload};</li>
 *   <li>their PC's, all of them, when the client asks as the PC opens (at most once every
 *       {@link #PC_REQUEST_COOLDOWN_TICKS}, however often it asks).</li>
 * </ul>
 * A Pokémon's levels are cached by its form ({@code DatapackSuitabilityProvider}), so checking a
 * party costs a few map lookups.
 *
 * <p>Their moods ({@link WorkerMoodPacket}, see dev.palworks.mood.Mood) go the same way, at the same
 * times: the party's when they change (as whole points), the PC's as it opens. Not while mood is off.
 */
public final class WorkLevelSync {
    static final int PARTY_CHECK_TICKS = 20;
    static final int PC_REQUEST_COOLDOWN_TICKS = 20;

    // What each player was last sent about each of their party's Pokémon: player -> Pokémon -> levels
    private static final Map<UUID, Map<UUID, Map<Suitability, Integer>>> SENT = new ConcurrentHashMap<>();
    // What each player was last sent about each of their party's Pokémon's moods
    private static final Map<UUID, Map<UUID, WorkerMoodPacket.Shown>> SENT_MOODS = new ConcurrentHashMap<>();
    // Server tick each player last asked for their PC's levels
    private static final Map<UUID, Integer> LAST_PC_REQUEST = new ConcurrentHashMap<>();

    private WorkLevelSync() {}

    /** A Pokémon's level for each job it can do. Empty if it can't do any. */
    public static Map<Suitability, Integer> levelsOf(Pokemon pokemon) {
        Map<Suitability, Integer> levels = new EnumMap<>(Suitability.class);
        for (Suitability job : SuitabilityDetector.getSuitabilities(pokemon)) {
            levels.put(job, SuitabilityDetector.getSuitabilityLevel(pokemon, job));
        }
        return levels;
    }

    /** How a Pokémon's mood is shown, from what's saved of it (it may be in its ball, or the PC). */
    public static WorkerMoodPacket.Shown moodOf(Pokemon pokemon) {
        WorkerState state = WorkerStateManager.get(pokemon);
        double mood = state == null ? WorkerState.MAX_MOOD : state.getMood();
        PalworksConfig.MoodConfig config = PalworksConfig.current().mood();
        return new WorkerMoodPacket.Shown(
                (int) Math.round(mood),
                MoodMath.unhappy(mood, config.unhappyBelow()),
                state != null && state.isStriking());
    }

    /** Every server tick: every {@link #PARTY_CHECK_TICKS}, tell each player of any change to their party's levels. */
    public static void onServerTick(MinecraftServer server) {
        if (server.getTickCount() % PARTY_CHECK_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            syncParty(player);
        }
    }

    /** Send the player the levels of their party's Pokémon that have changed since they were last sent. */
    public static void syncParty(ServerPlayer player) {
        Map<UUID, Map<Suitability, Integer>> sent = SENT.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
        Map<UUID, Map<Suitability, Integer>> changed = new HashMap<>();
        for (Pokemon pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) {
            Map<Suitability, Integer> levels = levelsOf(pokemon);
            if (!levels.equals(sent.get(pokemon.getUuid()))) {
                changed.put(pokemon.getUuid(), levels);
                sent.put(pokemon.getUuid(), levels);
            }
        }
        if (!changed.isEmpty()) {
            new WorkLevelsPacket(changed).sendToPlayer(player);
        }
        syncPartyMoods(player);
    }

    /** Send the player the moods of their party's Pokémon that have changed since they were last sent. */
    private static void syncPartyMoods(ServerPlayer player) {
        if (!Mood.enabled()) {
            return;
        }
        Map<UUID, WorkerMoodPacket.Shown> sent = SENT_MOODS.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
        Map<UUID, WorkerMoodPacket.Shown> changed = new HashMap<>();
        for (Pokemon pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) {
            WorkerMoodPacket.Shown mood = moodOf(pokemon);
            if (!mood.equals(sent.get(pokemon.getUuid()))) {
                changed.put(pokemon.getUuid(), mood);
                sent.put(pokemon.getUuid(), mood);
            }
        }
        if (!changed.isEmpty()) {
            new WorkerMoodPacket(changed, PalworksConfig.current().mood().strikeUntil()).sendToPlayer(player);
        }
    }

    /** Send the player the levels of every Pokémon in their PC, unless they asked very recently. */
    public static void sendPc(ServerPlayer player, int tick) {
        Integer last = LAST_PC_REQUEST.get(player.getUUID());
        if (last != null && tick - last < PC_REQUEST_COOLDOWN_TICKS) {
            return;
        }
        LAST_PC_REQUEST.put(player.getUUID(), tick);
        Map<UUID, Map<Suitability, Integer>> levels = new HashMap<>();
        for (Pokemon pokemon : Cobblemon.INSTANCE.getStorage().getPC(player)) {
            levels.put(pokemon.getUuid(), levelsOf(pokemon));
        }
        if (!levels.isEmpty()) {
            new WorkLevelsPacket(levels).sendToPlayer(player);
        }
        if (Mood.enabled()) {
            Map<UUID, WorkerMoodPacket.Shown> moods = new HashMap<>();
            for (Pokemon pokemon : Cobblemon.INSTANCE.getStorage().getPC(player)) {
                moods.put(pokemon.getUuid(), moodOf(pokemon));
            }
            if (!moods.isEmpty()) {
                new WorkerMoodPacket(moods, PalworksConfig.current().mood().strikeUntil()).sendToPlayer(player);
            }
        }
    }

    /** What this player was last sent about this party Pokémon's levels, if anything (tests). */
    public static Optional<Map<Suitability, Integer>> lastSent(UUID player, UUID pokemon) {
        return Optional.ofNullable(SENT.getOrDefault(player, Map.of()).get(pokemon));
    }

    /** What this player was last sent about this party Pokémon's mood, if anything (tests). */
    public static Optional<WorkerMoodPacket.Shown> lastSentMood(UUID player, UUID pokemon) {
        return Optional.ofNullable(SENT_MOODS.getOrDefault(player, Map.of()).get(pokemon));
    }

    /** Forget a player who left, so their party is sent afresh when they're back. */
    public static void forget(UUID player) {
        SENT.remove(player);
        SENT_MOODS.remove(player);
        LAST_PC_REQUEST.remove(player);
    }

    /** Drop everything (server stop). */
    public static void clear() {
        SENT.clear();
        SENT_MOODS.clear();
        LAST_PC_REQUEST.clear();
    }
}
