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
import baritone.api.event.events.PlayerUpdateEvent;
import baritone.api.event.events.SprintStateEvent;
import baritone.api.event.events.type.EventState;
import baritone.behavior.LookBehavior;
import baritone.api.utils.input.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Abilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * @author Brady
 * @since 8/1/2018
 */
@Mixin(LocalPlayer.class)
public class MixinClientPlayerEntity {

    @Unique
    private static final MethodHandle MAY_FLY = baritone$resolveMayFly();

    @Unique
    private static MethodHandle baritone$resolveMayFly() {
        try {
            var lookup = MethodHandles.publicLookup();
            return lookup.findVirtual(LocalPlayer.class, "mayFly", MethodType.methodType(boolean.class));
        } catch (NoSuchMethodException e) {
            return null;
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Unique
    private IBaritone baritone$getBaritone() {
        // noinspection ConstantConditions
        return BaritoneAPI.getProvider().getBaritoneForPlayer((LocalPlayer) (Object) this);
    }

    @Inject(
            method = "tick",
            at = @At("HEAD")
    )
    private void onPreUpdate(CallbackInfo ci) {
        IBaritone baritone = this.baritone$getBaritone();
        if (baritone != null) {
            baritone.getGameEventHandler().onPlayerUpdate(new PlayerUpdateEvent(EventState.PRE));
        }
    }

    @Inject(
            method = "tick",
            at = @At("RETURN")
    )
    private void onPostUpdate(CallbackInfo ci) {
        IBaritone baritone = this.baritone$getBaritone();
        if (baritone != null) {
            baritone.getGameEventHandler().onPlayerUpdate(new PlayerUpdateEvent(EventState.POST));
        }
    }

    @Inject(
            method = "aiStep",
            at = @At("HEAD")
    )
    private void synchronizeSprintState(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        IBaritone baritone = this.baritone$getBaritone();
        if (baritone == null) {
            return;
        }

        SprintStateEvent event = new SprintStateEvent(self.isSprinting());
        baritone.getGameEventHandler().onPlayerSprintState(event);

        if (event.isModified()) {
            boolean targetSprint = event.isSprinting();
            if (targetSprint) {
                // Strict vanilla physical simulation validation (GrimAC / PolarAC compatibility):
                // 1. Cannot sprint while sneaking/crouching
                // 2. Cannot sprint without enough food (hunger > 6 or mayfly)
                // 3. Cannot sprint if horizontally colliding (wall bump)
                // 4. Cannot sprint if in water (unless underwater swimming)
                // 5. Cannot sprint without forward movement input
                boolean canSprint = !self.isCrouching()
                        && (self.getAbilities().mayfly || self.getFoodData().getFoodLevel() > 6.0F)
                        && !(self.isInWater() && !self.isUnderWater())
                        && !(self.horizontalCollision && !self.minorHorizontalCollision)
                        && (self.input == null || self.input.hasForwardImpulse() || baritone.getInputOverrideHandler().isInputForcedDown(Input.MOVE_FORWARD));
                if (!canSprint) {
                    targetSprint = false;
                    baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
                }
            }

            if (targetSprint != self.isSprinting()) {
                self.setSprinting(targetSprint);
            }
        }
    }

    @Redirect(
            method = "aiStep",
            at = @At(
                    value = "FIELD",
                    target = "net/minecraft/world/entity/player/Abilities.mayfly:Z"
            )
    )
    @Group(name = "mayFly", min = 1, max = 1)
    private boolean isAllowFlying(Abilities capabilities) {
        IBaritone baritone = this.baritone$getBaritone();
        if (baritone == null) {
            return capabilities.mayfly;
        }
        return !baritone.getPathingBehavior().isPathing() && capabilities.mayfly;
    }

    @Redirect(
        method = "aiStep",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;mayFly()Z"
        )
    )
    @Group(name = "mayFly", min = 1, max = 1)
    private boolean onMayFlyNeoforge(LocalPlayer instance) throws Throwable {
        IBaritone baritone = this.baritone$getBaritone();
        if (baritone == null) {
            return (boolean) MAY_FLY.invokeExact(instance);
        }
        return !baritone.getPathingBehavior().isPathing() && (boolean) MAY_FLY.invokeExact(instance);
    }

    @Inject(
            method = "rideTick",
            at = @At(
                    value = "HEAD"
            )
    )
    private void updateRidden(CallbackInfo cb) {
        IBaritone baritone = this.baritone$getBaritone();
        if (baritone != null) {
            ((LookBehavior) baritone.getLookBehavior()).pig();
        }
    }

    @Redirect(
            method = "aiStep",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;tryToStartFallFlying()Z"
            )
    )
    private boolean tryToStartFallFlying(final LocalPlayer instance) {
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer(instance);
        if (baritone != null && baritone.getPathingBehavior().isPathing()) {
            return false;
        }
        return instance.tryToStartFallFlying();
    }
}
