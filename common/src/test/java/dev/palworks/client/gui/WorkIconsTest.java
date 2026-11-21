package dev.palworks.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.palworks.job.JobType;
import dev.palworks.suitability.Suitability;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** Every job has a picture that exists, and every picture is used. */
class WorkIconsTest {
    private static final Path ASSETS = Path.of("src/main/resources/assets");
    private static final Path ICONS = ASSETS.resolve("palworks/textures/gui/icon");

    private static Path fileOf(ResourceLocation icon) {
        return ASSETS.resolve(icon.getNamespace()).resolve(icon.getPath());
    }

    @Test
    void everyJobHasAPictureThatExists() {
        for (JobType job : JobType.values()) {
            Path file = fileOf(WorkIcons.forJob(job));
            assertTrue(Files.isRegularFile(file), job + ": missing " + file);
        }
        assertTrue(Files.isRegularFile(fileOf(WorkIcons.NO_JOB)));
        assertTrue(Files.isRegularFile(fileOf(WorkIcons.OFF)));
    }

    @Test
    void everyPictureIsUsed() throws IOException {
        Set<Path> used = new TreeSet<>();
        for (JobType job : JobType.values()) {
            used.add(fileOf(WorkIcons.forJob(job)));
        }
        for (Suitability skill : Suitability.values()) {
            ResourceLocation icon = WorkIcons.forSkill(skill);
            if (icon != null) {
                used.add(fileOf(icon));
            }
        }
        used.add(fileOf(WorkIcons.NO_JOB));
        used.add(fileOf(WorkIcons.OFF));
        try (Stream<Path> files = Files.list(ICONS)) {
            assertEquals(new TreeSet<>(files.toList()), used);
        }
    }
}
