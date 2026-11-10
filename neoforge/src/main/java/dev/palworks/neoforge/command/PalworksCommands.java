package dev.palworks.neoforge.command;

import com.mojang.brigadier.context.CommandContext;
import dev.palworks.command.DebugCommand;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.function.Consumer;

/**
 * Registers the /palworks commands for NeoForge. All require OP level 2.
 */
public class PalworksCommands {

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("palworks")
            .requires(source -> source.hasPermission(2))

            // Toggle worker debugging for this player: activity in chat, and the in-world overlay
            // (job icons above workers' heads, their paths and job blocks)
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

            // Build a test area (smelting, every crop and tree) from where the player stands
            .then(Commands.literal("testarea")
                .executes(ctx -> run(ctx, DebugCommand::buildTestArea)))

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
