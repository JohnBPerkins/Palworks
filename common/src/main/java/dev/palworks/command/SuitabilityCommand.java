package dev.palworks.command;

import com.cobblemon.mod.common.command.argument.SpeciesArgumentType;
import com.cobblemon.mod.common.pokemon.Species;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import dev.palworks.suitability.DatapackSuitabilityProvider;
import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityData;
import dev.palworks.suitability.SuitabilityOverrides;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /palworks suitability}: admins change which work a species can do, and how well, over the
 * datapacks' ({@link SuitabilityOverrides}, saved with the world; the datapacks are left alone).
 * <ul>
 *   <li>{@code get <species>}: its levels (its standard form's), and which were changed in game;</li>
 *   <li>{@code set <species> <job> <level>}: give it a level for a job, 0 to take the job away;</li>
 *   <li>{@code reset <species> [<job>]}: back to the datapacks' level, for one job or all of them;</li>
 *   <li>{@code reset all}: undo every species' changes;</li>
 *   <li>{@code list}: every species changed, and how.</li>
 * </ul>
 * Players see the new levels in the PC and summary screens a moment later ({@code WorkLevelSync}).
 */
final class SuitabilityCommand {
    private static final String SPECIES = "species";
    private static final String JOB = "job";
    private static final String LEVEL = "level";
    private static final DynamicCommandExceptionType UNKNOWN_JOB = new DynamicCommandExceptionType(
            id -> Component.literal("Unknown job \"" + id + "\": expected one of " + String.join(", ", jobIds())));

    private SuitabilityCommand() {}

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("suitability")
                .then(Commands.literal("get").then(species().executes(ctx -> get(ctx.getSource(), species(ctx)))))
                .then(Commands.literal("set")
                        .then(species()
                                .then(job().then(Commands.argument(
                                                LEVEL, IntegerArgumentType.integer(0, SuitabilityData.MAX_LEVEL))
                                        .executes(ctx -> set(
                                                ctx.getSource(),
                                                species(ctx),
                                                job(ctx),
                                                IntegerArgumentType.getInteger(ctx, LEVEL)))))))
                .then(Commands.literal("reset")
                        .then(Commands.literal("all").executes(ctx -> resetAll(ctx.getSource())))
                        .then(species()
                                .executes(ctx -> reset(ctx.getSource(), species(ctx), null))
                                .then(job().executes(ctx -> reset(ctx.getSource(), species(ctx), job(ctx))))))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())));
    }

    private static int get(CommandSourceStack source, Species species) {
        Map<Suitability, Integer> levels = DatapackSuitabilityProvider.levels(species.getStandardForm());
        Map<Suitability, Integer> changed = SuitabilityOverrides.all().getOrDefault(id(species), Map.of());
        List<Component> lines = new ArrayList<>();
        for (Suitability job : Suitability.values()) {
            Integer level = levels.get(job);
            String note = changed.containsKey(job) ? " (changed in game)" : "";
            if (level != null) {
                lines.add(Component.literal("  ").append(name(job)).append(" " + level + note));
            } else if (changed.containsKey(job)) {
                lines.add(Component.literal("  ").append(name(job)).append(": taken away" + note));
            }
        }
        source.sendSuccess(() -> Component.literal(species.getName() + "'s work skills (standard form):"), false);
        if (lines.isEmpty()) {
            source.sendSuccess(() -> Component.literal("  none: it can't work"), false);
        }
        lines.forEach(line -> source.sendSuccess(() -> line, false));
        return levels.size();
    }

    private static int set(CommandSourceStack source, Species species, Suitability job, int level) {
        try {
            SuitabilityOverrides.set(id(species), job, level);
        } catch (IOException e) {
            return couldNotSave(source, e);
        }
        Component message = level == 0
                ? Component.literal(species.getName() + " can no longer do ").append(name(job))
                : Component.literal(species.getName() + "'s ").append(name(job)).append(" is now level " + level);
        source.sendSuccess(() -> message, true);
        return 1;
    }

    private static int reset(CommandSourceStack source, Species species, @Nullable Suitability job) {
        boolean had;
        try {
            had = SuitabilityOverrides.reset(id(species), job);
        } catch (IOException e) {
            return couldNotSave(source, e);
        }
        if (!had) {
            source.sendFailure(
                    job == null
                            ? Component.literal(species.getName() + " has no changes made in game")
                            : Component.literal(species.getName() + "'s ")
                                    .append(name(job))
                                    .append(" wasn't changed in game"));
            return 0;
        }
        Component message = job == null
                ? Component.literal(species.getName() + "'s work skills are back to the datapacks'")
                : Component.literal(species.getName() + "'s ").append(name(job)).append(" is back to the datapacks'");
        source.sendSuccess(() -> message, true);
        return 1;
    }

    private static int resetAll(CommandSourceStack source) {
        int species;
        try {
            species = SuitabilityOverrides.resetAll();
        } catch (IOException e) {
            return couldNotSave(source, e);
        }
        if (species == 0) {
            source.sendFailure(Component.literal("No work skills were changed in game"));
            return 0;
        }
        source.sendSuccess(
                () -> Component.literal(
                        "Undid the in-game changes to " + species + " species: they're back to the datapacks'"),
                true);
        return species;
    }

    private static int list(CommandSourceStack source) {
        Map<String, Map<Suitability, Integer>> all = new TreeMap<>(SuitabilityOverrides.all());
        if (all.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No work skills were changed in game"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Work skills changed in game (" + all.size() + " species):"), false);
        all.forEach((id, jobs) -> {
            MutableComponent line = Component.literal("  " + id + ":");
            for (Suitability job : Suitability.values()) {
                Integer level = jobs.get(job);
                if (level != null) {
                    line.append(" ").append(name(job)).append(" " + level);
                }
            }
            source.sendSuccess(() -> line, false);
        });
        return all.size();
    }

    private static int couldNotSave(CommandSourceStack source, IOException e) {
        source.sendFailure(Component.literal(
                "Couldn't save " + SuitabilityOverrides.FILE_NAME + " (" + e.getMessage() + "), so nothing changed"));
        return 0;
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Species> species() {
        return Commands.argument(SPECIES, SpeciesArgumentType.Companion.species());
    }

    private static Species species(CommandContext<CommandSourceStack> ctx) {
        return SpeciesArgumentType.Companion.getPokemon(ctx, SPECIES);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> job() {
        return Commands.argument(JOB, StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(jobIds(), builder));
    }

    private static Suitability job(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, JOB);
        Suitability job = Suitability.fromId(id.toLowerCase(Locale.ROOT));
        if (job == null) {
            throw UNKNOWN_JOB.create(id);
        }
        return job;
    }

    private static List<String> jobIds() {
        return Arrays.stream(Suitability.values()).map(Suitability::getId).toList();
    }

    private static String id(Species species) {
        return species.getResourceIdentifier().toString();
    }

    /** The job's name in the player's language; its id on a server console without Palworks' language files. */
    private static Component name(Suitability job) {
        return Component.translatableWithFallback("palworks.work_skill." + job.getId(), job.getId());
    }
}
