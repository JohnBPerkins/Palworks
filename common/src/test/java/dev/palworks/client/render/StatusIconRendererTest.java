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
    void otherAspectsShowNothing() {
        assertEquals(List.of(), StatusIconRenderer.statusesOf(Set.of("palworks-working", "shiny")));
    }
}
