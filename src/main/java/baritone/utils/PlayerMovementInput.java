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
        if (screenOpen && Minecraft.getInstance().screen instanceof net.minecraft.client.gui.screens.PauseScreen) {
            if (com.astra.revolution.perf.AstraPerformanceConfig.getBotNum() > 1 || Baritone.isPrimaryActive()) {
                Minecraft.getInstance().setScreen(null);
                screenOpen = false;
            }
        }
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
        boolean safeInventorySwapping = handler != null && handler.baritone != null
                && handler.baritone.getInventoryBehavior() != null
                && handler.baritone.getInventoryBehavior().swapController != null
                && handler.baritone.getInventoryBehavior().swapController.isBusy();

        boolean isSecondaryBot = com.astra.revolution.perf.AstraPerformanceConfig.getBotNum() > 1;
        boolean shouldBlockScreen = screenOpen && (!Baritone.settings().inputWhileScreenOpen.value || (strictAntiCheat && isContainerScreen));
        if (isSecondaryBot && !isContainerScreen) {
            // Drugi bot i kolejne boty pracujące w tle nie mogą być blokowane przez ekrany informacyjne lub czat
            shouldBlockScreen = false;
        }

        if (chatSuppressed || autoDropCleaning || homeTeleporting || autoEatSuppress || safeInventorySwapping || shouldBlockScreen) {
            this.keyPresses = new net.minecraft.world.entity.player.Input(
                    false, false, false, false, false, false, false);
            this.forwardImpulse = 0.0F;
            this.leftImpulse = 0.0F;
            return;
        }

        net.minecraft.client.Options opts = Minecraft.getInstance().options;
        boolean keyUp    = opts != null && opts.keyUp.isDown();
        boolean keyDown  = opts != null && opts.keyDown.isDown();
        boolean keyLeft  = opts != null && opts.keyLeft.isDown();
        boolean rightKey = opts != null && opts.keyRight.isDown();
        boolean jumpKey  = opts != null && opts.keyJump.isDown();
        boolean sneakKey = opts != null && opts.keyShift.isDown();
        boolean sprintKey = opts != null && opts.keySprint.isDown();

        // (3) JEDYNY zapis inputu w ticku — kolejność vanilla:
        //     input.tick() → aiStep/fizyka → sendInput → pakiety
        //     Łączymy stan wymuszony przez Baritone z fizycznymi klawiszami gracza
        boolean forward   = (handler != null && handler.isInputForcedDown(Input.MOVE_FORWARD)) || keyUp;
        boolean backward  = (handler != null && handler.isInputForcedDown(Input.MOVE_BACK)) || keyDown;
        boolean left      = (handler != null && handler.isInputForcedDown(Input.MOVE_LEFT)) || keyLeft;
        boolean right     = (handler != null && handler.isInputForcedDown(Input.MOVE_RIGHT)) || rightKey;
        boolean jump      = (handler != null && handler.isInputForcedDown(Input.JUMP)) || jumpKey;
        boolean sneak     = (handler != null && handler.isInputForcedDown(Input.SNEAK)) || sneakKey;
        boolean rawSprint = (handler != null && handler.isInputForcedDown(Input.SPRINT)) || sprintKey;

        boolean isBreaking = handler != null && handler.isInputForcedDown(Input.CLICK_LEFT);

        net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;

        // GrimAC 1.21.4 Simulation Fix: Sprint jest fizycznie legalny TYLKO przy ruchu w przód,
        // bez wciskania w tył, bez skradania, bez kopania i przy głodzie > 6
        boolean canSprintVanilla = forward && !backward && !sneak && !isBreaking;
        boolean sprint = false;

        boolean botMoving = handler != null && (handler.isInputForcedDown(Input.MOVE_FORWARD) || handler.isInputForcedDown(Input.SPRINT));
        boolean sprintDesired = rawSprint || (player != null && player.isSprinting()) || (botMoving && Baritone.settings().allowSprint.value);

        if (canSprintVanilla && sprintDesired) {
            if (player != null && player.getFoodData().getFoodLevel() > 6
                    && !player.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)
                    && (!player.horizontalCollision || player.minorHorizontalCollision)) {
                boolean inWater = player.isInWater() && !player.isUnderWater();
                if (!inWater || Baritone.settings().sprintInWater.value) {
                    sprint = true;
                }
            }
        }

        this.keyPresses = new net.minecraft.world.entity.player.Input(
                forward, backward, left, right, jump, sneak, sprint);

        this.forwardImpulse = (forward == backward) ? 0.0F : (forward ? 1.0F : -1.0F);
        this.leftImpulse    = (left == right)       ? 0.0F : (left ? 1.0F : -1.0F);

        if (sneak) {
            this.forwardImpulse *= 0.3F;
            this.leftImpulse    *= 0.3F;
        }
    }
}