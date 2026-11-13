package dev.palworks.command;

import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.serialization.DataResult;
import dev.palworks.config.ConfigKeys;
import dev.palworks.config.PalworksConfig;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

/**
 * {@code /palworks config}: admins see and change the server settings in {@code config/palworks.json}
 * ({@link PalworksConfig}) in game. A change is used straight away, and written to the file.
 * <ul>
 *   <li>{@code get [<setting>]}: one setting's value, or all of them, and their defaults;</li>
 *   <li>{@code set <setting> <value>}: change one, within the range the file allows;</li>
 *   <li>{@code reset <setting>|all}: put one, or all of them, back to the default.</li>
 * </ul>
 * Settings are named by their place in the file: {@code work_range_horizontal}, {@code hunger.enabled}.
 */
final class ConfigCommand {
    private static final String SETTING = "setting";
    private static final String VALUE = "value";

    private ConfigCommand() {}

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("config")
                .then(Commands.literal("get")
                        .executes(ctx -> getAll(ctx.getSource()))
                        .then(setting().executes(ctx -> get(ctx.getSource(), setting(ctx)))))
                .then(Commands.literal("set")
                        .then(setting()
                                .then(Commands.argument(VALUE, StringArgumentType.word())
                                        .suggests(ConfigCommand::suggestValues)
                                        .executes(ctx -> set(
                                                ctx.getSource(),
                                                setting(ctx),
                                                StringArgumentType.getString(ctx, VALUE))))))
                .then(Commands.literal("reset")
                        .then(Commands.literal("all").executes(ctx -> resetAll(ctx.getSource())))
                        .then(setting().executes(ctx -> reset(ctx.getSource(), setting(ctx)))));
    }

    private static int getAll(CommandSourceStack source) {
        List<String> names = ConfigKeys.names();
        source.sendSuccess(() -> Component.literal("Palworks settings (config/palworks.json):"), false);
        for (String name : names) {
            source.sendSuccess(() -> Component.literal("  " + describe(name)), false);
        }
        return names.size();
    }

    private static int get(CommandSourceStack source, String name) {
        if (ConfigKeys.get(PalworksConfig.current(), name) == null) {
            return noSuchSetting(source, name);
        }
        source.sendSuccess(() -> Component.literal(describe(name)), false);
        return 1;
    }

    private static int set(CommandSourceStack source, String name, String value) {
        return use(source, name, ConfigKeys.set(PalworksConfig.current(), name, value));
    }

    private static int reset(CommandSourceStack source, String name) {
        return use(source, name, ConfigKeys.reset(PalworksConfig.current(), name));
    }

    private static int resetAll(CommandSourceStack source) {
        try {
            PalworksConfig.save(PalworksConfig.DEFAULTS);
        } catch (IOException e) {
            return couldNotSave(source, e);
        }
        source.sendSuccess(() -> Component.literal("Put every Palworks setting back to its default"), true);
        return 1;
    }

    private static int use(CommandSourceStack source, String name, DataResult<PalworksConfig> changed) {
        if (changed.error().isPresent()) {
            source.sendFailure(Component.literal(
                    "Can't change " + name + ": " + changed.error().get().message()));
            return 0;
        }
        try {
            PalworksConfig.save(changed.getOrThrow());
        } catch (IOException e) {
            return couldNotSave(source, e);
        }
        source.sendSuccess(() -> Component.literal("Set " + describe(name)), true);
        return 1;
    }

    /** {@code name = value}, and its default if it isn't that. */
    private static String describe(String name) {
        JsonPrimitive value = ConfigKeys.get(PalworksConfig.current(), name);
        JsonPrimitive fallback = ConfigKeys.get(PalworksConfig.DEFAULTS, name);
        return name + " = " + value + (Objects.equals(value, fallback) ? "" : " (default " + fallback + ")");
    }

    private static int noSuchSetting(CommandSourceStack source, String name) {
        source.sendFailure(Component.literal("There's no setting \"" + name + "\""));
        return 0;
    }

    private static int couldNotSave(CommandSourceStack source, IOException e) {
        source.sendFailure(
                Component.literal("Couldn't write config/palworks.json (" + e.getMessage() + "), so nothing changed"));
        return 0;
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> setting() {
        return Commands.argument(SETTING, StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ConfigKeys.names(), builder));
    }

    private static String setting(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, SETTING);
    }

    /** true and false for a switch; for a number, its value now and its default. */
    private static CompletableFuture<Suggestions> suggestValues(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String name = setting(ctx);
        JsonPrimitive value = ConfigKeys.get(PalworksConfig.current(), name);
        if (value == null) {
            return builder.buildFuture();
        }
        if (value.isBoolean()) {
            return SharedSuggestionProvider.suggest(List.of("true", "false"), builder);
        }
        JsonPrimitive fallback = ConfigKeys.get(PalworksConfig.DEFAULTS, name);
        return SharedSuggestionProvider.suggest(
                Stream.of(value, fallback).map(JsonPrimitive::toString).distinct(), builder);
    }
}
