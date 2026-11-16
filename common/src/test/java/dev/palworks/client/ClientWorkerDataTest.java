package dev.palworks.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.palworks.inventory.CarryMode;
import dev.palworks.net.packets.WorkerDebugPacket;
import dev.palworks.net.packets.WorkerMoodPacket;
import dev.palworks.suitability.Suitability;
import dev.palworks.worker.WorkActivity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What the server said about its workers is kept until the player leaves it, then all forgotten. */
class ClientWorkerDataTest {
    @Test
    void leavingForgetsAllOfIt() {
        UUID pokemon = UUID.randomUUID();
        UUID entity = UUID.randomUUID();
        ClientWorkerData.putActivity(pokemon, WorkActivity.OFF);
        ClientWorkerData.putStatus(entity, new ClientWorkerData.Status(true, CarryMode.FULL));
        ClientWorkerData.setDebugEntries(
                List.of(new WorkerDebugPacket.Entry(1, WorkActivity.OFF, null, List.of(), null, List.of())));
        ClientWorkerData.putWorkLevels(Map.of(pokemon, Map.of(Suitability.KINDLING, 2)));
        ClientWorkerData.putMoods(Map.of(pokemon, new WorkerMoodPacket.Shown(40, true, false)), 30);
        assertNotNull(ClientWorkerData.activity(pokemon), "kept until then");

        ClientWorkerData.clear();

        assertNull(ClientWorkerData.activity(pokemon), "what its workers were doing");
        assertNull(ClientWorkerData.takeStatus(entity), "a work status sent for a wheel never opened");
        assertTrue(ClientWorkerData.debugEntries().isEmpty(), "the debug overlay's workers");
        assertNull(ClientWorkerData.workLevels(pokemon), "work levels");
        assertNull(ClientWorkerData.mood(pokemon), "moods");
    }
}
