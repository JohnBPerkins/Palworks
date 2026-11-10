package dev.palworks;

import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Palworks {
    public static final String MOD_ID = "palworks";
    /**
     * Palworks's logger. Job-level events log at DEBUG (in {@code logs/debug.log} in dev runs, and
     * on NeoForge); per-tick detail at TRACE, off unless the log config turns it on.
     */
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private Palworks() {}

    /** An id in Palworks's namespace: {@code palworks:<path>}. */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
