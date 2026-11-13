package dev.palworks.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/**
 * The /palworks commands, all requiring OP level 2. Each loader registers {@link #build()} with
 * its own command event.
 */
public final class PalworksCommands {
    private PalworksCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("palworks")
                .requires(source -> source.hasPermission(2))

                // Toggle worker debugging for this player: activity in chat, and the in-world overlay
                // (job icons above workers' heads, their paths and job blocks)
                .then(Commands.literal("debug").executes(ctx -> run(ctx, DebugCommand::toggleDebug)))

                // Inspect the nearest worker's state
                .then(Commands.literal("inspect")
                        .then(Commands.literal("worker")
                                .executes(ctx -> run(ctx, DebugCommand::showNearestWorkerInfo))))

                // Worker management
                .then(Commands.literal("worker")
                        .then(Commands.literal("enable").executes(ctx -> run(ctx, DebugCommand::enableNearestWorker)))
                        .then(Commands.literal("disable").executes(ctx -> run(ctx, DebugCommand::disableNearestWorker)))
                        .then(Commands.literal("clear").executes(ctx -> run(ctx, DebugCommand::clearAllReservations)))
                        .then(Commands.literal("inventory")
                                .executes(ctx -> run(ctx, DebugCommand::showWorkerInventory)))
                        .then(Commands.literal("openinv")
                                .executes(ctx -> run(ctx, DebugCommand::openWorkerInventoryGUI)))
                        .then(Commands.literal("clearinv")
                                .executes(ctx -> run(ctx, DebugCommand::clearWorkerInventory))))

                // Build a test area (smelting, every crop and tree) from where the player stands
                .then(Commands.literal("testarea").executes(ctx -> run(ctx, DebugCommand::buildTestArea)))

                // Dump current job state (available/reserved/workers + assignment analysis)
                .then(Commands.literal("jobs").executes(ctx -> run(ctx, DebugCommand::showJobsInfo)))

                // Change species' work suitabilities over the datapacks', saved with the world.
                // Also from the server console
                .then(SuitabilityCommand.build());
    }

    // Not a player: Brigadier tells the sender. Anything else is a bug, which Minecraft logs and
    // reports in chat ("An unexpected error occurred"); it used to be swallowed without a trace.
    private static int run(CommandContext<CommandSourceStack> ctx, Consumer<ServerPlayer> action)
            throws CommandSyntaxException {
        action.accept(ctx.getSource().getPlayerOrException());
        return 1;
    }
}
