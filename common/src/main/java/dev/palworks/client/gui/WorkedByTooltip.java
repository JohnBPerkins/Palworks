package dev.palworks.client.gui;

import dev.architectury.event.events.client.ClientTooltipEvent;
import dev.palworks.job.JobChecks;
import dev.palworks.suitability.Suitability;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * While Shift is held, an item whose block Pokémon work on says which work skills do it in its
 * tooltip: "Worked by: Kindling" on a furnace. Which skills is the job checks' own answer (see
 * JobChecks.workedWith), so a block a datapack adds to a tag the jobs go by (furnaces, logs) has
 * it too. Only skills with work today, as the PC and summary show (see WorkSkills). Nothing at all
 * without Shift, not even a hint to hold it: it would be on every log and seed.
 */
public final class WorkedByTooltip {
    private WorkedByTooltip() {}

    public static void init() {
        ClientTooltipEvent.ITEM.register((stack, lines, context, flag) -> {
            if (!Screen.hasShiftDown()) {
                return;
            }
            MutableComponent skills = null;
            for (Suitability skill : JobChecks.workedWith(stack.getItem())) { // in the skills' order
                if (WorkSkills.icon(skill) == null) {
                    continue; // nothing to do it on yet
                }
                Component name = Component.translatable(WorkSkills.nameKey(skill));
                skills = skills == null ? name.copy() : skills.append(", ").append(name);
            }
            if (skills != null) {
                lines.add(
                        Component.translatable("palworks.ui.worked_by", skills).withStyle(ChatFormatting.GRAY));
            }
        });
    }
}
