package dev.palworks.fabric;

import dev.palworks.PalworksCommon;
import net.fabricmc.api.ModInitializer;

/** Fabric's entry point: everything is set up in common (see {@link PalworksCommon}). */
public class PalworksFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        PalworksCommon.init();
    }
}
