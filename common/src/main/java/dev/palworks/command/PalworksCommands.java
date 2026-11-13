package dev.palworks.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.architectury.platform.Platform;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/**
 * The /palworks commands, all requiring OP level 2. {@link #build()} is registered once for both
 * loaders, through Architectury's command event ({@code PalworksCommon.init}). The ones for
 * developing and testing Palworks (the debug overlay, job dumps, the test area...) are only there in
 * development runs (runClient, runServer, the in-game tests): a released jar has just the admin
 * tools.
 */
public final class PalworksCommands {
    private PalworksCommands() {}

    /** The command tree: with the developer tools in a development run. */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return build(Platform.isDevelopmentEnvironment());
    }

    /** The command tree, with or without the developer tools ({@code devTools}). */
    public static LiteralArgumentBuilder<CommandSourceStack> build(boolean devTools) {
        // Switch the nearest owned Pokémon's work on or off (the interact wheel does it too). A
        // child is built as it's added, so the worker node is finished before it is.
        LiteralArgumentBuilder<CommandSourceStack> worker = Commands.literal("worker")
                .then(Commands.literal("enable").executes(ctx -> run(ctx, DebugCommand::enableNearestWorker)))
                .then(Commands.literal("disable").executes(ctx -> run(ctx, DebugCommand::disableNearestWorker)));
        if (devTools) {
            worker.then(Commands.literal("clear").executes(ctx -> run(ctx, DebugCommand::clearAllReservations)))
                    .then(Commands.literal("inventory").executes(ctx -> run(ctx, DebugCommand::showWorkerInventory)))
                    .then(Commands.literal("openinv").executes(ctx -> run(ctx, DebugCommand::openWorkerInventoryGUI)))
                    .then(Commands.literal("clearinv").executes(ctx -> run(ctx, DebugCommand::clearWorkerInventory)));
        }
        LiteralArgumentBuilder<CommandSourceStack> palworks = Commands.literal("palworks")
                .requires(source -> source.hasPermission(2))
                .then(worker)

                // Change species' work suitabilities over the datapacks', saved with the world.
                // Also from the server console
                .then(SuitabilityCommand.build())

                // See and change the settings in config/palworks.json. Also from the server console
                .then(ConfigCommand.build());
        if (!devTools) {
            return palworks;
        }

        // Developer tools: development runs only
        return palworks
                // Toggle worker debugging for this player: activity in chat, and the in-world overlay
                // (job icons above workers' heads, their paths and job blocks)
                .then(Commands.literal("debug").executes(ctx -> run(ctx, DebugCommand::toggleDebug)))

                // Inspect the nearest worker's state
                .then(Commands.literal("inspect")
                        .then(Commands.literal("worker")
                                .executes(ctx -> run(ctx, DebugCommand::showNearestWorkerInfo))))

                // Build a test area (smelting, every crop and tree) from where the player stands
                .then(Commands.literal("testarea").executes(ctx -> run(ctx, DebugCommand::buildTestArea)))

                // Make the nearest owned Pokémon drowsy now, as Cobblemon does (in its resting hours)
                .then(Commands.literal("sleep").executes(ctx -> run(ctx, DebugCommand::makeNearestDrowsy)))

                // Dump current job state (available/reserved/workers + assignment analysis)
                .then(Commands.literal("jobs").executes(ctx -> run(ctx, DebugCommand::showJobsInfo)));
    }

    // Not a player: Brigadier tells the sender. Anything else is a bug, which Minecraft logs and
    // reports in chat ("An unexpected error occurred"); it used to be swallowed without a trace.
    private static int run(CommandContext<CommandSourceStack> ctx, Consumer<ServerPlayer> action)
            throws CommandSyntaxException {
        action.accept(ctx.getSource().getPlayerOrException());
        return 1;
    }
}
