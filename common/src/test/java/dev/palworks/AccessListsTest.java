package dev.palworks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The two lists of vanilla fields Palworks is let into say the same. The access widener is what
 * the code compiles against, and all a dev run or a GameTest needs, on both loaders; a NeoForge
 * jar goes by its access transformer, written by hand. A field in the first and not the second
 * builds and passes every test, then fails with an IllegalAccessError in a real NeoForge game.
 */
class AccessListsTest {
    private static final Path WIDENER = Path.of("src/main/resources/palworks.accesswidener");
    private static final Path TRANSFORMER = Path.of("../neoforge/src/main/resources/META-INF/accesstransformer.cfg");

    @Test
    void neoForgesTransformerOpensWhatTheWidenerDoes() throws IOException {
        // "accessible field net/minecraft/Owner name Ltype;" -> "net.minecraft.Owner name"
        Set<String> widened = new TreeSet<>();
        for (String line : Files.readAllLines(WIDENER)) {
            String[] parts = line.trim().split("\\s+", -1);
            if (parts.length == 5 && "accessible".equals(parts[0]) && "field".equals(parts[1])) {
                widened.add(parts[2].replace('/', '.') + " " + parts[3]);
            }
        }
        // "public net.minecraft.Owner name"
        Set<String> transformed = new TreeSet<>();
        for (String line : Files.readAllLines(TRANSFORMER)) {
            String[] parts = line.trim().split("\\s+", -1);
            if (parts.length == 3 && "public".equals(parts[0])) {
                transformed.add(parts[1] + " " + parts[2]);
            }
        }
        assertFalse(widened.isEmpty(), "no fields read from the access widener");
        assertEquals(widened, transformed, "fields in palworks.accesswidener and in NeoForge's accesstransformer.cfg");
    }
}
