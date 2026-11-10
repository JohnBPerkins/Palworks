package dev.palworks.fabric.data;

import dev.palworks.suitability.SuitabilityReloadListener;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resources.ResourceLocation;

/** Fabric's resource loader needs an id on each reload listener. */
public class FabricSuitabilityReloadListener extends SuitabilityReloadListener
        implements IdentifiableResourceReloadListener {

    @Override
    public ResourceLocation getFabricId() {
        return ID;
    }
}
