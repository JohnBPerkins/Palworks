package dev.palworks.neoforge;

import dev.palworks.Palworks;
import dev.palworks.PalworksCommon;
import net.neoforged.fml.common.Mod;

/** NeoForge's entry point: everything is set up in common (see {@link PalworksCommon}). */
@Mod(Palworks.MOD_ID)
public class PalworksNeoForge {
    public PalworksNeoForge() {
        PalworksCommon.init();
    }
}
