package dev.palworks.client.hud;

import dev.palworks.job.JobType;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every job (and the generic and "off" icons) has a HUD icon texture. */
class WorkStatusHudTest {
    private static final Path ASSETS = Path.of("src/main/resources/assets");

    private static void assertTextureExists(String namespace, String path) {
        Path file = ASSETS.resolve(namespace).resolve(path);
        assertTrue(Files.isRegularFile(file), "missing texture " + file);
    }

    @Test
    void everyJobHasAnIcon() {
        for (JobType job : JobType.values()) {
            var icon = WorkStatusHud.iconFor(job);
            assertTextureExists(icon.getNamespace(), icon.getPath());
        }
        var generic = WorkStatusHud.iconFor(null);
        assertTextureExists(generic.getNamespace(), generic.getPath());
        assertTextureExists("palworks", "textures/gui/hud/work_off.png");
    }
}
