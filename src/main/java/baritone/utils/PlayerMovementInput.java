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

        // (2) Screen otwarty: false (default, Grim-safe) → zero input;
        //     Gdy aktywny anty-cheat, zawsze wyzeruj input przy otwartym kontenerze (InventoryMove / OpenScreen)
        boolean screenOpen = Minecraft.getInstance().screen != null;
        boolean isContainerScreen = screenOpen && Minecraft.getInstance().screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
        boolean strictAntiCheat = Baritone.settings().antiCheatCompat.value || Baritone.settings().antiCheatCompatibility.value;
        if (chatSuppressed || (screenOpen && (!Baritone.settings().inputWhileScreenOpen.value || (strictAntiCheat && isContainerScreen)))) {
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

        this.keyPresses = new net.minecraft.world.entity.player.Input(
                forward, backward, left, right, jump, sneak, sprint);

        this.forwardImpulse = (forward == backward) ? 0.0F : (forward ? 1.0F : -1.0F);
        this.leftImpulse    = (left == right)       ? 0.0F : (left ? 1.0F : -1.0F);
    }
}