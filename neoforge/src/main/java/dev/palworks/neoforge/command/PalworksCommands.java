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
                .then(Commands.literal("base")
                    .executes(ctx -> toggleDebug(ctx, DebugManager.DebugFlag.BASE_INFO)))
                .then(Commands.literal("radius")
                    .executes(ctx -> toggleDebug(ctx, DebugManager.DebugFlag.BASE_RADIUS)))
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
                .then(Commands.literal("base")
                    .executes(this::inspectBase)))

            // Worker management commands
            .then(Commands.literal("worker")
                .then(Commands.literal("enable")
                    .executes(this::enableWorker))
                .then(Commands.literal("disable")
                    .executes(this::disableWorker))
                .then(Commands.literal("test")
                    .executes(this::testTickWorker)))

            // Base management commands
            .then(Commands.literal("base")
                .then(Commands.literal("create")
                    .executes(this::createBase))
                .then(Commands.literal("radius")
                    .then(Commands.argument("radius", IntegerArgumentType.integer(8, 128))
                        .executes(this::setBaseRadius))))

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

    private int inspectBase(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            DebugCommand.showNearestBaseInfo(player);
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private int createBase(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            DebugCommand.createBase(player);
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private int setBaseRadius(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            int radius = IntegerArgumentType.getInteger(ctx, "radius");
            DebugCommand.setBaseRadius(player, radius);
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
