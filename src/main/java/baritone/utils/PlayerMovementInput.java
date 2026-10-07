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

package baritone.utils;

import baritone.Baritone;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;

public class PlayerMovementInput extends ClientInput {

    private final InputOverrideHandler handler;

    public PlayerMovementInput(InputOverrideHandler handler) {
        this.handler = handler;
    }

    @Override
    public void tick() {
        // (1) ChatC: wyciszenie TYLKO w ticku faktycznej wysyłki czatu
        boolean chatSuppressed = ScreenInputGate.isSuppressed();
        if (chatSuppressed) {
            ScreenInputGate.tickDown();
        }

        // (2) Screen otwarty lub czyszczenie ekwipunku AutoDrop: zawsze zero input (GrimAC MultiActions safe)
        boolean screenOpen = Minecraft.getInstance().screen != null;
        boolean isContainerScreen = screenOpen && Minecraft.getInstance().screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
        boolean strictAntiCheat = Baritone.settings().antiCheatCompat.value || Baritone.settings().antiCheatCompatibility.value;
        boolean autoDropCleaning = handler != null && handler.baritone != null
                && handler.baritone.getAutoDropProcess() != null
                && handler.baritone.getAutoDropProcess().isCleaning();
        boolean homeTeleporting = handler != null && handler.baritone != null
                && handler.baritone.getHomeProcess() != null
                && handler.baritone.getHomeProcess().isActive();
        boolean autoEatSuppress = handler != null && handler.baritone != null
                && handler.baritone.getAutoEatProcess() != null
                && handler.baritone.getAutoEatProcess().isSuppressingMovement();

        if (chatSuppressed || autoDropCleaning || homeTeleporting || autoEatSuppress || (screenOpen && (!Baritone.settings().inputWhileScreenOpen.value || (strictAntiCheat && isContainerScreen)))) {
            this.keyPresses = new net.minecraft.world.entity.player.Input(
                    false, false, false, false, false, false, false);
            this.forwardImpulse = 0.0F;
            this.leftImpulse = 0.0F;
            return;
        }

        // (3) JEDYNY zapis inputu w ticku — kolejność vanilla:
        //     input.tick() → aiStep/fizyka → sendInput → pakiety
        boolean forward  = handler.isInputForcedDown(Input.MOVE_FORWARD);
        boolean backward = handler.isInputForcedDown(Input.MOVE_BACK);
        boolean left     = handler.isInputForcedDown(Input.MOVE_LEFT);
        boolean right    = handler.isInputForcedDown(Input.MOVE_RIGHT);
        boolean jump     = handler.isInputForcedDown(Input.JUMP);
        boolean sneak    = handler.isInputForcedDown(Input.SNEAK);
        boolean sprint   = handler.isInputForcedDown(Input.SPRINT);

        boolean isBreaking = handler != null && handler.isInputForcedDown(Input.CLICK_LEFT);
        if (isBreaking) {
            sprint = false;
        }

        net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;

        // Niemal natychmiastowy sprint przy chodzeniu (zgodny z fizyką Vanilla i GrimAC):
        // Jeśli bot idzie w przód i dozwolony jest sprint, włącz sprint natychmiast w 1. ticku (jak wciśnięty klawisz sprintu w MC)
        if (forward && !backward && !sneak && !isBreaking && Baritone.settings().allowSprint.value) {
            if (player != null && player.getFoodData().getFoodLevel() > 6
                    && !player.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)
                    && (!player.horizontalCollision || player.minorHorizontalCollision)) {
                boolean inWater = player.isInWater() && !player.isUnderWater();
                if (!inWater || Baritone.settings().sprintInWater.value) {
                    sprint = true;
                }
            }
        }

        // GrimAC / Vanilla physics: Nigdy nie sprintuj na powierzchni wody bez sprintInWater
        if (player != null && player.isInWater() && !player.isUnderWater() && !Baritone.settings().sprintInWater.value) {
            sprint = false;
        }

        this.keyPresses = new net.minecraft.world.entity.player.Input(
                forward, backward, left, right, jump, sneak, sprint);

        this.forwardImpulse = (forward == backward) ? 0.0F : (forward ? 1.0F : -1.0F);
        this.leftImpulse    = (left == right)       ? 0.0F : (left ? 1.0F : -1.0F);
    }
}