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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity extends Entity {

    private MixinLivingEntity(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    @Inject(
            method = "jumpFromGround",
            at = @At("HEAD")
    )
    private void baritone$onJumpFromGround(CallbackInfo ci) {
        this.getBaritone().ifPresent(baritone -> {
            RotationMoveEvent event = new RotationMoveEvent(
                    RotationMoveEvent.Type.JUMP,
                    this.getYRot(),
                    this.getXRot()
            );
            baritone.getGameEventHandler().onPlayerRotationMove(event);

            // FIX 1.21.4: Zapis trwały — brak przywracania.
            // yaw użyty do boostu sprint-jump (sin/cos w jumpFromGround)
            // jest tym samym yaw, który trafi do pakietu rotacji.
            this.setYRot(event.getYaw());
            this.setXRot(event.getPitch());
        });
    }

    @Inject(
            method = "updateFallFlyingMovement",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;getLookAngle()Lnet/minecraft/world/phys/Vec3;"
            )
    )
    private void baritone$onPreElytraMove(Vec3 direction,
                                          CallbackInfoReturnable<Vec3> cir) {
        this.getBaritone().ifPresent(baritone -> {
            RotationMoveEvent event = new RotationMoveEvent(
                    RotationMoveEvent.Type.MOTION_UPDATE,
                    this.getYRot(),
                    this.getXRot()
            );
            baritone.getGameEventHandler().onPlayerRotationMove(event);

            // Ustaw rotację PRZED wywołaniem getLookAngle().
            // Kąt elytry (getLookAngle) i kąt pakietu są identyczne.
            this.setYRot(event.getYaw());
            this.setXRot(event.getPitch());
        });
    }

    @Unique
    private Optional<IBaritone> getBaritone() {
        if (LocalPlayer.class.isInstance(this)) {
            return Optional.ofNullable(
                    BaritoneAPI.getProvider()
                            .getBaritoneForPlayer((LocalPlayer) (Object) this)
            );
        }
        return Optional.empty();
    }
}
