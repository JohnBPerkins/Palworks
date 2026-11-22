package dev.palworks.client.gui;

import com.cobblemon.mod.common.CobblemonSounds;
import com.cobblemon.mod.common.api.pokedex.entry.PokedexEntry;
import com.cobblemon.mod.common.api.pokedex.filter.EntryFilter;
import com.cobblemon.mod.common.client.gui.pokedex.PokedexGUI;
import com.cobblemon.mod.common.client.gui.pokedex.PokedexGUIConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.architectury.event.events.client.ClientGuiEvent;
import dev.palworks.client.ClientWorkerData;
import dev.palworks.net.packets.RequestDexWorkSkillsPacket;
import dev.palworks.suitability.Suitability;
import java.lang.ref.WeakReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Lets the player filter Cobblemon's Pokédex by work skill: a small button at the right of its
 * search bar, beside Cobblemon's "search by" button, that goes through the work skills as it's
 * clicked (none, Kindling, Watering... and back to none). With one picked, the list keeps only the
 * species that can have it, in any form; hovering the button names it. It works with Cobblemon's
 * own search and category filter, not in place of them.
 *
 * <p>It lists every species with the skill, whether the player has seen it or not: one they
 * haven't is listed as the Pokédex shows any it doesn't know, so they can see how many there are to
 * find. (Cobblemon's own searches leave those out; this one is for finding what to catch.)
 *
 * <p>Which species have which skills is the server's (its datapacks and admins' changes): asked for
 * as the Pokédex opens (see WorkLevelSync.sendDex) and kept in {@link ClientWorkerData}. Until it
 * comes a picked skill lists nothing, and the list is filtered again as it does.
 *
 * <p>The button is added to the screen once it's set up (Architectury's screen event, on both
 * loaders); the filter itself is added to the screen's own by PokedexGUIMixin. The layout is copied
 * from PokedexGUI, the same in Cobblemon 1.8.x.
 */
public final class DexWorkSkillFilter {
    // PokedexGUI's search bar: the search box is at 26 to 146 across, its "search by" button
    // (8 square) at 154.5, 29.5. Ours fits between them
    private static final int BUTTON_X = 146;
    private static final float BUTTON_Y = 29.5F;
    private static final int ICON_SIZE = 8;
    // Cobblemon draws its Pokédex tooltips this far towards the viewer, over the Pokémon in the list
    private static final float TOOLTIP_Z = 1000;

    // The Pokédex the picked skill is for: another one, opened later, starts with none, as it
    // does with Cobblemon's own filters
    private static WeakReference<PokedexGUI> shownOn = new WeakReference<>(null);

    @Nullable
    private static Suitability picked;

    private DexWorkSkillFilter() {}

    public static void init() {
        ClientGuiEvent.INIT_POST.register((screen, access) -> {
            if (screen instanceof PokedexGUI dex) {
                if (shownOn.get() != dex) {
                    shownOn = new WeakReference<>(dex);
                    picked = null;
                }
                new RequestDexWorkSkillsPacket().sendToServer();
                access.addRenderableWidget(new FilterButton(dex));
            }
        });
        ClientGuiEvent.RENDER_POST.register((screen, graphics, mouseX, mouseY, delta) -> {
            if (screen instanceof PokedexGUI && overButton(screen, mouseX, mouseY)) {
                PoseStack pose = graphics.pose();
                pose.pushPose();
                pose.translate(0, 0, TOOLTIP_Z);
                graphics.renderTooltip(Minecraft.getInstance().font, label(), mouseX, mouseY);
                pose.popPose();
            }
        });
    }

    /**
     * The filter to add to this Pokédex's own, or null while no skill is picked on it. Asked each
     * time it filters its list, the first of them before {@link #init}'s event has seen it: a
     * skill picked on an earlier Pokédex isn't this one's.
     */
    @Nullable
    public static EntryFilter filterFor(PokedexGUI dex) {
        Suitability skill = picked;
        return skill == null || shownOn.get() != dex ? null : new SkillFilter(skill);
    }

    /** The server has sent the species' skills: an open Pokédex with a skill picked lists again by them. */
    public static void skillsArrived(Minecraft client) {
        if (picked != null && client.screen instanceof PokedexGUI dex && shownOn.get() == dex) {
            dex.updateFilters(false);
        }
    }

    private static Component label() {
        Suitability skill = picked;
        return skill == null
                ? Component.translatable("palworks.ui.dex_filter.any")
                : Component.translatable(
                        "palworks.ui.dex_filter.skill", Component.translatable(WorkSkills.nameKey(skill)));
    }

    private static int left(Screen screen) {
        return (screen.width - PokedexGUIConstants.BASE_WIDTH) / 2 + BUTTON_X;
    }

    private static float top(Screen screen) {
        int screenTop = (screen.height - PokedexGUIConstants.BASE_HEIGHT) / 2; // in whole pixels, as PokedexGUI's
        return screenTop + BUTTON_Y;
    }

    private static boolean overButton(Screen screen, double mouseX, double mouseY) {
        int x = left(screen);
        float y = top(screen);
        return mouseX >= x && mouseX < x + ICON_SIZE && mouseY >= y && mouseY < y + ICON_SIZE;
    }

    /** Keeps the species that can have a work skill, seen by the player or not. */
    private static final class SkillFilter extends EntryFilter {
        private final Suitability skill;

        SkillFilter(Suitability skill) {
            this.skill = skill;
        }

        @Override
        public boolean test(PokedexEntry entry) {
            return ClientWorkerData.speciesHasSkill(entry.getSpeciesId(), skill);
        }
    }

    /** The picked skill's icon, or a faded cog while none is: a click picks the next. */
    private static final class FilterButton extends Button {
        private final PokedexGUI dex;

        FilterButton(PokedexGUI dex) {
            super(left(dex), (int) top(dex), ICON_SIZE, ICON_SIZE + 1, label(), button -> {}, DEFAULT_NARRATION);
            this.dex = dex;
        }

        @Override
        public void onPress() {
            picked = WorkSkills.nextFilter(picked);
            setMessage(label());
            dex.updateFilters(false);
        }

        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            return overButton(dex, mouseX, mouseY);
        }

        @Override
        protected boolean clicked(double mouseX, double mouseY) {
            return active && visible && overButton(dex, mouseX, mouseY);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            Suitability skill = picked;
            WorkIcons.Icon icon = skill == null ? WorkIcons.ANY_SKILL : WorkSkills.icon(skill);
            if (icon == null) {
                return;
            }
            // Faded while it filters nothing, unless the mouse is on it
            graphics.setColor(1, 1, 1, skill == null && !overButton(dex, mouseX, mouseY) ? 0.5F : 1);
            WorkIconDrawer.draw(graphics, icon, left(dex), top(dex), ICON_SIZE);
            graphics.setColor(1, 1, 1, 1);
        }

        @Override
        public void playDownSound(SoundManager sounds) {
            sounds.play(SimpleSoundInstance.forUI(CobblemonSounds.POKEDEX_CLICK_SHORT, 1.0F));
        }
    }
}
