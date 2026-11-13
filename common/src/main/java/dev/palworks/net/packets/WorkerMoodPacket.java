package dev.palworks.net.packets;

import com.cobblemon.mod.common.api.net.NetworkPacket;
import dev.palworks.Palworks;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * Sent from server to a player: the mood of some of their Pokémon (party or PC), by Pokémon UUID,
 * for the PC and summary screens (see dev.palworks.mood.Mood). Only for display: the server never
 * reads it back. While mood is off in the server's config, only {@link #off()} is sent, once (mood can
 * be turned off in game: see /palworks config), so the client stops showing the moods it has.
 */
public class WorkerMoodPacket implements NetworkPacket<WorkerMoodPacket> {
    public static final ResourceLocation ID = Palworks.id("worker_mood");

    /**
     * One Pokémon's mood, as shown.
     *
     * @param mood     its mood, rounded, 0 to 100
     * @param unhappy  whether it's unhappy, and works slower
     * @param striking whether it's on strike, and does no work
     */
    public record Shown(int mood, boolean unhappy, boolean striking) {}

    // False: mood is off, so show none (and moods is empty)
    private final boolean enabled;
    private final Map<UUID, Shown> moods;
    // The mood a worker on strike works again at, for the tooltip (the server's config)
    private final int strikeUntil;

    public WorkerMoodPacket(Map<UUID, Shown> moods, int strikeUntil) {
        this(true, moods, strikeUntil);
    }

    private WorkerMoodPacket(boolean enabled, Map<UUID, Shown> moods, int strikeUntil) {
        this.enabled = enabled;
        this.moods = moods;
        this.strikeUntil = strikeUntil;
    }

    /** Mood is off: the client forgets the moods it has, and shows none. */
    public static WorkerMoodPacket off() {
        return new WorkerMoodPacket(false, Map.of(), 0);
    }

    /** Whether mood is on (false: show none). */
    public boolean isEnabled() {
        return enabled;
    }

    public Map<UUID, Shown> getMoods() {
        return moods;
    }

    public int getStrikeUntil() {
        return strikeUntil;
    }

    @NotNull
    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    public void encode(@NotNull RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(enabled);
        buffer.writeVarInt(strikeUntil);
        buffer.writeVarInt(moods.size());
        moods.forEach((pokemon, shown) -> {
            buffer.writeUUID(pokemon);
            buffer.writeVarInt(shown.mood());
            buffer.writeBoolean(shown.unhappy());
            buffer.writeBoolean(shown.striking());
        });
    }

    public static WorkerMoodPacket decode(RegistryFriendlyByteBuf buffer) {
        boolean enabled = buffer.readBoolean();
        int strikeUntil = buffer.readVarInt();
        int count = buffer.readVarInt();
        Map<UUID, Shown> moods = new HashMap<>();
        for (int i = 0; i < count; i++) {
            moods.put(buffer.readUUID(), new Shown(buffer.readVarInt(), buffer.readBoolean(), buffer.readBoolean()));
        }
        return new WorkerMoodPacket(enabled, moods, strikeUntil);
    }
}
