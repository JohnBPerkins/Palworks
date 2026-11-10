package dev.palworks.client.gui;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokemon.interaction.PokemonInteractionGUICreationEvent;
import com.cobblemon.mod.common.client.gui.interact.wheel.InteractWheelOption;
import com.cobblemon.mod.common.client.gui.interact.wheel.Orientation;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.Palworks;
import dev.palworks.inventory.CarryMode;
import dev.palworks.suitability.SuitabilityDetector;
import dev.palworks.net.PalworksResource;
import dev.palworks.net.packets.OpenInventoryPacket;
import dev.palworks.net.packets.ToggleWorkerPacket;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Handles adding custom options to the Cobblemon Pokemon interact wheel:
 * <ul>
 *   <li>a work toggle (bottom), a green check while the Pokemon is working and a red cross while
 *       it isn't; pressing it switches work on or off</li>
 *   <li>an "Inventory" option (right) that opens the worker inventory GUI</li>
 * </ul>
 */
public class PokemonInteractHandler {
    private static final ResourceLocation INVENTORY_ICON = PalworksResource.of("textures/gui/interact/interact_wheel_icon_inventory.png");
    // White icons, tinted green or red by the option's colour
    private static final ResourceLocation WORKING_ICON = PalworksResource.of("textures/gui/interact/interact_wheel_icon_working.png");
    private static final ResourceLocation NOT_WORKING_ICON = PalworksResource.of("textures/gui/interact/interact_wheel_icon_not_working.png");
    private static final Vector3f GREEN = new Vector3f(0.33F, 0.85F, 0.33F);
    private static final Vector3f RED = new Vector3f(0.95F, 0.3F, 0.3F);

    public static void init() {
        Palworks.LOGGER.info("Registering Pokemon interact wheel handler");

        Consumer<PokemonInteractionGUICreationEvent> handler = event -> {
            addWorkToggle(event);

            // Only Pokemon that can carry something get the button: Transporting (full inventory)
            // and planters (seed pouch). Computed client-side from the Pokemon's suitabilities.
            if (carryModeOf(event.getPokemonID()) == CarryMode.NONE) {
                return;
            }

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

    /**
     * Adds the work on/off toggle at the bottom of the wheel. The server sends the Pokemon's status
     * just before the wheel opens (see PokemonEntityInteractMixin); with no status (it can't do any
     * work) there's no toggle.
     */
    private static void addWorkToggle(PokemonInteractionGUICreationEvent event) {
        Boolean enabled = WorkerStatusCache.take(event.getPokemonID());
        if (enabled == null) {
            return;
        }

        Function0<Vector3f> colourFunction = () -> enabled ? GREEN : RED;
        Function0<Unit> onPressFunction = () -> {
            new ToggleWorkerPacket(event.getPokemonID()).sendToServer();
            closeGUI();
            return Unit.INSTANCE;
        };

        event.addOption(Orientation.SOUTH, new InteractWheelOption(
                enabled ? WORKING_ICON : NOT_WORKING_ICON,
                null, // No secondary icon
                true,
                enabled ? "palworks.ui.interact.stop_work" : "palworks.ui.interact.start_work",
                colourFunction,
                onPressFunction
        ));
    }

    /** The carry mode of the Pokemon entity with this UUID on the client, or NONE if not found. */
    private static CarryMode carryModeOf(UUID entityUUID) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return CarryMode.NONE;
        }
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof PokemonEntity pokemonEntity && entity.getUUID().equals(entityUUID)) {
                Pokemon pokemon = pokemonEntity.getPokemon();
                return pokemon == null ? CarryMode.NONE
                    : CarryMode.forSuitabilities(SuitabilityDetector.getSuitabilities(pokemon));
            }
        }
        return CarryMode.NONE;
    }

    private static void closeGUI() {
        Minecraft.getInstance().setScreen(null);
    }
}
