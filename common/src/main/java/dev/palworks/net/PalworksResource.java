package dev.palworks.net;

import dev.palworks.Palworks;
import net.minecraft.resources.ResourceLocation;

public class PalworksResource {
    public static ResourceLocation of(String path) {
        return ResourceLocation.fromNamespaceAndPath(Palworks.MOD_ID, path);
    }
}
