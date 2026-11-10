package dev.palworks.fabric.data;

import dev.palworks.animation.WorkAnimationReloadListener;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resources.ResourceLocation;

/** Fabric's resource loader needs an id on each reload listener. */
public class FabricWorkAnimationReloadListener extends WorkAnimationReloadListener
        implements IdentifiableResourceReloadListener {

    @Override
    public ResourceLocation getFabricId() {
        return ID;
    }
}
