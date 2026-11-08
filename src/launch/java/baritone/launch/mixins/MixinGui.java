/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.launch.mixins;

import baritone.hud.BaritoneHudOverlay;
import com.astra.revolution.gui.AstraTabListRenderer;
import com.astra.revolution.visuals.AstraVisualRemovals;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into Minecraft's Ingame GUI to render Baritone's stats and Astra God-Tier visual suite.
 * Dodaje:
 * 1. Astra Glassmorphism Tab-List (inspirowany MoonDLC MixinPlayerListHud).
 * 2. Astra Dynamic Crosshair & Hitmarker (inspirowany MoonDLC InGameHudMixin).
 * 3. Portal & Confusion Nausea Removals (inspirowany MoonDLC InGameHudMixin).
 */
@Mixin(Gui.class)
public class MixinGui {

    @Inject(
            method = "render",
            at = @At("RETURN")
    )
    private void onRender(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        BaritoneHudOverlay.render(guiGraphics, deltaTracker);
        // Astra Glassmorphism Tab-List — renderuje się na górze wszystkiego
        float pt = deltaTracker != null ? deltaTracker.getGameTimeDeltaPartialTick(false) : 0.0f;
        AstraTabListRenderer.INSTANCE.render(guiGraphics, pt);
    }

    /**
     * Intercept renderTabList — jeśli Astra tab-list ma aktywną animację, cancellujemy vanilla listę
     * aby uniknąć nakładania się dwóch tab-listów.
     * (Inspirowane MoonDLC MixinPlayerListHud — slide-in cancel przy ease > 0)
     */
    @Inject(
            method = "renderTabList",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void onRenderTabList(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (AstraTabListRenderer.INSTANCE.isActive()) {
            ci.cancel();
        }
    }


    /**
     * Usuwa drgania i falowanie ekranu portalu Netheru (Clean Visuals).
     */
    @Inject(
            method = "renderPortalOverlay",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void onRenderPortalOverlay(GuiGraphics guiGraphics, float f, CallbackInfo ci) {
        if (AstraVisualRemovals.isNoPortalNauseaEnabled()) {
            ci.cancel();
        }
    }

    /**
     * Usuwa efekt nudności (Nausea) dla maksymalnego skupienia w PvP.
     */
    @Inject(
            method = "renderConfusionOverlay",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void onRenderConfusionOverlay(GuiGraphics guiGraphics, float f, CallbackInfo ci) {
        if (AstraVisualRemovals.isNoPortalNauseaEnabled()) {
            ci.cancel();
        }
    }
}
