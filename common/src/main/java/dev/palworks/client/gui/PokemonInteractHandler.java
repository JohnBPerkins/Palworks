package dev.palworks.client.gui;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokemon.interaction.PokemonInteractionGUICreationEvent;
import com.cobblemon.mod.common.client.gui.interact.wheel.InteractWheelOption;
import com.cobblemon.mod.common.client.gui.interact.wheel.Orientation;
import dev.palworks.Palworks;
import dev.palworks.inventory.CarryMode;
import dev.palworks.net.PalworksResource;
import dev.palworks.net.packets.OpenInventoryPacket;
import dev.palworks.net.packets.ToggleWorkerPacket;
import java.util.function.Consumer;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;

/**
 * Handles adding custom options to the Cobblemon Pokemon interact wheel:
 * <ul>
 *   <li>a work toggle (bottom), a green check while the Pokemon is working and a red cross while
 *       it isn't; pressing it switches work on or off</li>
 *   <li>an "Inventory" option (right) that opens the worker inventory GUI</li>
 * </ul>
 */
public class PokemonInteractHandler {
    private static final ResourceLocation INVENTORY_ICON =
            PalworksResource.of("textures/gui/interact/interact_wheel_icon_inventory.png");
    // White icons, tinted green or red by the option's colour
    private static final ResourceLocation WORKING_ICON =
            PalworksResource.of("textures/gui/interact/interact_wheel_icon_working.png");
    private static final ResourceLocation NOT_WORKING_ICON =
            PalworksResource.of("textures/gui/interact/interact_wheel_icon_not_working.png");
    private static final Vector3f GREEN = new Vector3f(0.33F, 0.85F, 0.33F);
    private static final Vector3f RED = new Vector3f(0.95F, 0.3F, 0.3F);

    public static void init() {
        Palworks.LOGGER.info("Registering Pokemon interact wheel handler");

        Consumer<PokemonInteractionGUICreationEvent> handler = event -> {
            // The server sends the Pokemon's status just before the wheel opens (see
            // PokemonEntityInteractMixin). None means it can't do any work: no work options.
            WorkerStatusCache.Status status = WorkerStatusCache.take(event.getPokemonID());
            if (status == null) {
                return;
            }
            addWorkToggle(event, status.enabled());
            // Only Pokemon that carry something get the inventory: Transporting (full inventory)
            // and planters (seed pouch).
            if (status.carryMode() != CarryMode.NONE) {
                addInventoryButton(event);
            }
        };

        CobblemonEvents.POKEMON_INTERACTION_GUI_CREATION.subscribe(Priority.NORMAL, handler);

        Palworks.LOGGER.info("Pokemon interact wheel handler registered");
    }

    /** Adds the work on/off toggle at the bottom of the wheel. */
    private static void addWorkToggle(PokemonInteractionGUICreationEvent event, boolean enabled) {
        Function0<Vector3f> colourFunction = () -> enabled ? GREEN : RED;
        Function0<Unit> onPressFunction = () -> {
            new ToggleWorkerPacket(event.getPokemonID()).sendToServer();
            closeGUI();
            return Unit.INSTANCE;
        };

        event.addOption(
                Orientation.SOUTH,
                new InteractWheelOption(
                        enabled ? WORKING_ICON : NOT_WORKING_ICON,
                        null, // No secondary icon
                        true,
                        enabled ? "palworks.ui.interact.stop_work" : "palworks.ui.interact.start_work",
                        colourFunction,
                        onPressFunction));
    }

    /** Adds the inventory button on the right of the wheel. */
    private static void addInventoryButton(PokemonInteractionGUICreationEvent event) {
        Function0<Vector3f> colourFunction = () -> null;
        Function0<Unit> onPressFunction = () -> {
            new OpenInventoryPacket(event.getPokemonID()).sendToServer();
            closeGUI();
            return Unit.INSTANCE;
        };

        event.addOption(
                Orientation.EAST,
                new InteractWheelOption(
                        INVENTORY_ICON,
                        null, // No secondary icon
                        true,
                        "palworks.ui.interact.inventory",
                        colourFunction,
                        onPressFunction));
    }

    private static void closeGUI() {
        Minecraft.getInstance().setScreen(null);
    }
}
