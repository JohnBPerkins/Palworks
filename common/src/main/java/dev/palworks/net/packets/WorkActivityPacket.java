package dev.palworks.net.packets;

import com.cobblemon.mod.common.api.net.NetworkPacket;
import dev.palworks.job.JobType;
import dev.palworks.net.PalworksResource;
import dev.palworks.worker.WorkActivity;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * Sent from server to a worker's owner when what it's doing changes (and now and then anyway), so
 * the party HUD can show it. Keyed by the Pokemon's own UUID, the one the client's party uses.
 */
public class WorkActivityPacket implements NetworkPacket<WorkActivityPacket> {
    public static final ResourceLocation ID = PalworksResource.of("work_activity");

    private final UUID pokemonUUID;
    private final WorkActivity activity;

    public WorkActivityPacket(UUID pokemonUUID, WorkActivity activity) {
        this.pokemonUUID = pokemonUUID;
        this.activity = activity;
    }

    public UUID getPokemonUUID() {
        return pokemonUUID;
    }

    public WorkActivity getActivity() {
        return activity;
    }

    @NotNull
    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    public void encode(@NotNull RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(pokemonUUID);
        buffer.writeVarInt(activity.phase().ordinal());
        // Job by name, not ordinal, so a new job type can't shift the others; empty for none.
        buffer.writeUtf(activity.job() == null ? "" : activity.job().name());
    }

    public static WorkActivityPacket decode(RegistryFriendlyByteBuf buffer) {
        UUID uuid = buffer.readUUID();
        WorkActivity.Phase phase = WorkActivity.Phase.fromOrdinal(buffer.readVarInt());
        String jobName = buffer.readUtf();
        JobType job = null;
        if (!jobName.isEmpty()) {
            try {
                job = JobType.valueOf(jobName);
            } catch (IllegalArgumentException ignored) {
                // A job this client doesn't know: show the phase without a job icon.
            }
        }
        return new WorkActivityPacket(uuid, new WorkActivity(phase, job));
    }
}
