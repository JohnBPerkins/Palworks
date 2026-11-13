package dev.palworks.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.palworks.worker.WorkerStatus;
import java.util.List;
import java.util.Set;
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
    void otherAspectsShowNothing() {
        assertEquals(List.of(), StatusIconRenderer.statusesOf(Set.of("palworks-working", "shiny")));
    }
}
