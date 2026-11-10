package dev.palworks;

import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Palworks {
    public static final String MOD_ID = "palworks";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * Master debug toggle for verbose logging and in-chat broadcasts. Off by default;
     * enable at launch with {@code -Dpalworks.debug=true}. Classes reference this rather than
     * hardcoding their own {@code DEBUG = true} flag.
     */
    public static final boolean DEBUG = Boolean.getBoolean("palworks.debug");

    private Palworks() {}

    /** An id in Palworks's namespace: {@code palworks:<path>}. */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
