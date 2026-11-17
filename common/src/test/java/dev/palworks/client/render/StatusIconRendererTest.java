package dev.palworks.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.palworks.Palworks;
import dev.palworks.worker.WorkerStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** Which status icons a Pokémon shows, from its aspects. */
class StatusIconRendererTest {

    @Test
    void aHungryWorkerShowsHungry() {
        assertEquals(
                List.of(WorkerStatus.HUNGRY),
                StatusIconRenderer.statusesOf(Set.of("palworks-hungry", "palworks-working", "shiny")));
    }

    @Test
    void aTiredHungryWorkerShowsBothInOrder() {
        assertEquals(
                List.of(WorkerStatus.HUNGRY, WorkerStatus.TIRED),
                StatusIconRenderer.statusesOf(Set.of("palworks-tired", "palworks-hungry")));
    }

    @Test
    void anUnhappyOrStrikingWorkerShowsItAfterItsNeeds() {
        assertEquals(
                List.of(WorkerStatus.HUNGRY, WorkerStatus.UNHAPPY),
                StatusIconRenderer.statusesOf(Set.of("palworks-unhappy", "palworks-hungry")));
        assertEquals(
                List.of(WorkerStatus.TIRED, WorkerStatus.STRIKING),
                StatusIconRenderer.statusesOf(Set.of("palworks-striking", "palworks-tired")));
    }

    @Test
    void oneAsleepWithNoSleepAnimationShowsAsleep() {
        assertEquals(List.of(WorkerStatus.ASLEEP), StatusIconRenderer.statusesOf(Set.of("palworks-asleep")));
    }

    @Test
    void aWorkerThatCantReachItsWorkOrDeliverShowsWhichAfterItsMood() {
        assertEquals(List.of(WorkerStatus.CANT_REACH), StatusIconRenderer.statusesOf(Set.of("palworks-cant_reach")));
        assertEquals(
                List.of(WorkerStatus.UNHAPPY, WorkerStatus.CANT_REACH, WorkerStatus.CANT_DELIVER),
                StatusIconRenderer.statusesOf(
                        Set.of("palworks-cant_deliver", "palworks-cant_reach", "palworks-unhappy")));
    }

    @Test
    void onlyWhatSlowsAWorkerPulses() {
        for (WorkerStatus status : WorkerStatus.values()) {
            assertEquals(status == WorkerStatus.HUNGRY || status == WorkerStatus.TIRED, status.slows(), status.name());
        }
    }

    @Test
    void everyPalworksIconHasASprite() {
        for (WorkerStatus status : WorkerStatus.values()) {
            ResourceLocation sprite = status.sprite();
            if (Palworks.MOD_ID.equals(sprite.getNamespace())) {
                Path file = Path.of("src/main/resources/assets", sprite.getNamespace(), "textures/gui/sprites")
                        .resolve(sprite.getPath() + ".png");
                assertTrue(Files.isRegularFile(file), "missing sprite " + file);
            }
        }
    }

    @Test
    void otherAspectsShowNothing() {
        assertEquals(List.of(), StatusIconRenderer.statusesOf(Set.of("palworks-working", "shiny")));
    }
}
