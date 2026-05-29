package dev.palworks.client.gui;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokemon.interaction.PokemonInteractionGUICreationEvent;
import com.cobblemon.mod.common.client.gui.interact.wheel.InteractWheelOption;
import com.cobblemon.mod.common.client.gui.interact.wheel.Orientation;
import dev.palworks.Palworks;
import dev.palworks.net.PalworksResource;
import dev.palworks.net.packets.OpenInventoryPacket;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;

import java.util.function.Consumer;

/**
 * Handles adding custom options to the Cobblemon Pokemon interact wheel.
 * Adds an "Inventory" option that opens the worker inventory GUI.
 */
public class PokemonInteractHandler {
    private static final ResourceLocation INVENTORY_ICON = PalworksResource.of("textures/gui/interact/interact_wheel_icon_inventory.png");

    public static void init() {
        Palworks.LOGGER.info("Registering Pokemon interact wheel handler");

        Consumer<PokemonInteractionGUICreationEvent> handler = event -> {
            // Create the inventory option
            // Using Kotlin function interfaces for compatibility
            Function0<Vector3f> colourFunction = () -> null;
            Function0<Unit> onPressFunction = () -> {
                // Send packet to server to open inventory
                new OpenInventoryPacket(event.getPokemonID()).sendToServer();
                closeGUI();
                return Unit.INSTANCE;
            };

            InteractWheelOption inventoryOption = new InteractWheelOption(
                    INVENTORY_ICON,
                    null, // No secondary icon
                    true, // Always enabled
                    "palworks.ui.interact.inventory", // Tooltip translation key
                    colourFunction,
                    onPressFunction
            );

            // Add to the wheel at EAST position (right side)
            event.addOption(Orientation.EAST, inventoryOption);
        };

        CobblemonEvents.POKEMON_INTERACTION_GUI_CREATION.subscribe(Priority.NORMAL, handler);

        Palworks.LOGGER.info("Pokemon interact wheel handler registered");
    }

    private static void closeGUI() {
        Minecraft.getInstance().setScreen(null);
    }
}
