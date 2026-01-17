package dev.palworks.neoforge.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.palworks.command.DebugCommand;
import dev.palworks.debug.DebugManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Registers Palworks commands for NeoForge.
 */
public class PalworksCommands {

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("palworks")
            .requires(source -> source.hasPermission(2)) // Require OP level 2

            // Debug commands
            .then(Commands.literal("debug")
                .then(Commands.literal("worker")
                    .executes(ctx -> toggleDebug(ctx, DebugManager.DebugFlag.WORKER_INFO)))
                .then(Commands.literal("station")
                    .executes(ctx -> toggleDebug(ctx, DebugManager.DebugFlag.STATION_INFO)))
                // Base debug commands removed
                .then(Commands.literal("paths")
                    .executes(ctx -> toggleDebug(ctx, DebugManager.DebugFlag.WORKER_PATHS)))
                .then(Commands.literal("targets")
                    .executes(ctx -> toggleDebug(ctx, DebugManager.DebugFlag.JOB_TARGETS)))
                .then(Commands.literal("all")
                    .executes(ctx -> toggleDebug(ctx, DebugManager.DebugFlag.ALL))))

            // Inspect commands
            .then(Commands.literal("inspect")
                .then(Commands.literal("worker")
                    .executes(this::inspectWorker))
                // Base inspect removed
            )

            // Worker management commands
            .then(Commands.literal("worker")
                .then(Commands.literal("enable")
                    .executes(this::enableWorker))
                .then(Commands.literal("disable")
                    .executes(this::disableWorker))
                .then(Commands.literal("clear")
                    .executes(this::clearReservations))
                .then(Commands.literal("inventory")
                    .executes(this::showInventory))
                .then(Commands.literal("clearinv")
                    .executes(this::clearInventory))
                .then(Commands.literal("test")
                    .executes(this::testTickWorker)))

            // Base management commands removed

            // List command
            .then(Commands.literal("list")
                .executes(this::listAll))
        );
    }

    private int enableWorker(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            DebugCommand.enableNearestWorker(player);
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private int disableWorker(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            DebugCommand.disableNearestWorker(player);
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private int toggleDebug(CommandContext<CommandSourceStack> ctx, DebugManager.DebugFlag flag) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            DebugCommand.toggleDebug(player, flag);
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private int inspectWorker(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            DebugCommand.showNearestWorkerInfo(player);
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    // Base system removed - inspectBase(), createBase(), and setBaseRadius() methods removed

    private int clearReservations(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            DebugCommand.clearAllReservations(player);
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private int showInventory(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            DebugCommand.showWorkerInventory(player);
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private int clearInventory(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            DebugCommand.clearWorkerInventory(player);
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private int listAll(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            DebugCommand.listAllInfo(player);
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private int testTickWorker(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            DebugCommand.testTickWorker(player);
            return 1;
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }
}
