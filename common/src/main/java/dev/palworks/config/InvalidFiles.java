package dev.palworks.config;

import dev.palworks.Palworks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Files of settings that someone wrote and Palworks couldn't read all of. */
public final class InvalidFiles {
    private InvalidFiles() {}

    /** What's added to a file's name for the copy kept of it. */
    public static final String SUFFIX = ".invalid";

    /**
     * Keep a copy of {@code file} beside it, as {@code <name>.invalid}, and say so in the log.
     * Palworks carries on with what it could read of the file (for {@code palworks.json}, the
     * defaults), and the next change made in game writes that over it, so without the copy one
     * wrong value cost whoever wrote the file everything else in it.
     */
    public static void keep(Path file) {
        Path copy = file.resolveSibling(file.getFileName() + SUFFIX);
        try {
            Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
            Palworks.LOGGER.warn(
                    "Kept {} as it was in {}: a change made in game writes over the file",
                    file.getFileName(),
                    copy.getFileName());
        } catch (IOException e) {
            Palworks.LOGGER.warn("Couldn't keep a copy of {}", file, e);
        }
    }
}
