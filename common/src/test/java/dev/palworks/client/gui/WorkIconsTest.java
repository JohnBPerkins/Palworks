package dev.palworks.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.palworks.job.JobType;
import dev.palworks.suitability.Suitability;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Every picture a job or skill shows exists, and every picture is used. That every item exists: WorkStatusGameTests. */
class WorkIconsTest {
    private static final Path ASSETS = Path.of("src/main/resources/assets");
    private static final Path PICTURES = ASSETS.resolve("palworks/textures/gui/icon");

    /** Every icon in use: each job's, each shown skill's, off and the Pokédex filter's "any". */
    static List<WorkIcons.Icon> allIcons() {
        List<WorkIcons.Icon> icons = new ArrayList<>(List.of(WorkIcons.OFF, WorkIcons.ANY_SKILL));
        for (JobType job : JobType.values()) {
            icons.add(WorkIcons.forJob(job));
        }
        for (Suitability skill : Suitability.values()) {
            WorkIcons.Icon icon = WorkIcons.forSkill(skill);
            if (icon != null) {
                icons.add(icon);
            }
        }
        return icons;
    }

    private static Set<Path> pictureFiles() {
        Set<Path> files = new TreeSet<>();
        for (WorkIcons.Icon icon : allIcons()) {
            if (icon instanceof WorkIcons.Picture picture) {
                files.add(ASSETS.resolve(picture.texture().getNamespace())
                        .resolve(picture.texture().getPath()));
            }
        }
        return files;
    }

    @Test
    void everyPictureExists() {
        for (Path file : pictureFiles()) {
            assertTrue(Files.isRegularFile(file), "missing " + file);
        }
    }

    @Test
    void everyPictureIsUsed() throws IOException {
        try (Stream<Path> files = Files.list(PICTURES)) {
            assertEquals(new TreeSet<>(files.toList()), pictureFiles());
        }
    }
}
