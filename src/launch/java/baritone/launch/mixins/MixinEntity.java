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

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.RotationMoveEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public class MixinEntity {

    @Shadow
    private float yRot;

    @Shadow
    private float xRot;

    @Inject(
            method = "moveRelative",
            at = @At("HEAD")
    )
    private void baritone$moveRelativeHead(CallbackInfo ci) {
        if (!LocalPlayer.class.isInstance(this)) {
            return;
        }

        IBaritone baritone = BaritoneAPI.getProvider()
                .getBaritoneForPlayer((LocalPlayer) (Object) this);
        if (baritone == null) {
            return;
        }

        RotationMoveEvent event = new RotationMoveEvent(
                RotationMoveEvent.Type.MOTION_UPDATE,
                this.yRot,
                this.xRot
        );

        baritone.getGameEventHandler().onPlayerRotationMove(event);

        // ═════════════════════════════════════════════════════════
        // FIX 1.21.4: Zapisz rotację TRWALE — NIE przywracaj.
        //
        // this.yRot jest odczytywane przez getInputVector() wewnątrz
        // moveRelative(). Jednocześnie ten sam this.yRot trafia do
        // ServerboundMovePlayerPacket.Rot na końcu ticku.
        //
        // Zapis bez przywracania gwarantuje:
        //   yaw(fizyka) == yaw(pakiet) → zero desync.
        // ═════════════════════════════════════════════════════════
        this.yRot = event.getYaw();
        this.xRot = event.getPitch();
    }
}
