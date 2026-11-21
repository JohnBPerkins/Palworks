package dev.palworks.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Palworks' textures: one file per image, so an icon used in several places is shared, not copied. */
class TexturesTest {
    private static final Path TEXTURES = Path.of("src/main/resources/assets/palworks/textures");

    @Test
    void noTwoTexturesAreTheSameFile() throws IOException {
        Map<ByteBuffer, Path> seen = new HashMap<>();
        List<String> copies = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEXTURES)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                Path first = seen.putIfAbsent(ByteBuffer.wrap(Files.readAllBytes(file)), file);
                if (first != null) {
                    copies.add(TEXTURES.relativize(file) + " is a copy of " + TEXTURES.relativize(first));
                }
            }
        }
        assertTrue(copies.isEmpty(), String.join("\n", copies));
    }
}
