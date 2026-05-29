package dev.palworks.fabric.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import dev.palworks.command.DebugCommand;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/**
 * Registers the /palworks commands for Fabric. All require OP level 2.
 */
public class PalworksCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("palworks")
            .requires(source -> source.hasPermission(2))

            // Toggle worker-activity chat for this player
            .then(Commands.literal("debug")
                .executes(ctx -> run(ctx, DebugCommand::toggleDebug)))

            // Inspect the nearest worker's state
            .then(Commands.literal("inspect")
                .then(Commands.literal("worker")
                    .executes(ctx -> run(ctx, DebugCommand::showNearestWorkerInfo))))

            // Worker management
            .then(Commands.literal("worker")
                .then(Commands.literal("enable").executes(ctx -> run(ctx, DebugCommand::enableNearestWorker)))
                .then(Commands.literal("disable").executes(ctx -> run(ctx, DebugCommand::disableNearestWorker)))
                .then(Commands.literal("clear").executes(ctx -> run(ctx, DebugCommand::clearAllReservations)))
                .then(Commands.literal("inventory").executes(ctx -> run(ctx, DebugCommand::showWorkerInventory)))
                .then(Commands.literal("openinv").executes(ctx -> run(ctx, DebugCommand::openWorkerInventoryGUI)))
                .then(Commands.literal("clearinv").executes(ctx -> run(ctx, DebugCommand::clearWorkerInventory))))

            // Dump current job state (available/reserved/workers + assignment analysis)
            .then(Commands.literal("jobs")
                .executes(ctx -> run(ctx, DebugCommand::showJobsInfo)))
        );
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Consumer<ServerPlayer> action) {
        try {
            action.accept(ctx.getSource().getPlayerOrException());
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }
}
