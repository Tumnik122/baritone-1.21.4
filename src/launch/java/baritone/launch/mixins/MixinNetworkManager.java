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
import baritone.api.event.events.PacketEvent;
import baritone.api.event.events.type.EventState;
import baritone.utils.GrimBreakValidator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * @author Brady
 * @since 8/6/2018
 */
@Mixin(Connection.class)
public class MixinNetworkManager {

    @Shadow
    private Channel channel;

    @Shadow
    @Final
    private PacketFlow receiving;

    @Inject(
            method = "sendPacket",
            at = @At("HEAD"),
            cancellable = true
    )
    private void preDispatchPacket(Packet<?> packet, PacketSendListener packetSendListener, boolean flush, CallbackInfo ci) {
        if (this.receiving != PacketFlow.CLIENTBOUND) {
            return;
        }

        // 1. Zsynchronizowany koniec ticku klienta (1.21.2+):
        if (packet instanceof ServerboundClientTickEndPacket) {
            GrimBreakValidator.onClientTickEnd();
        }
        // 2. Eliminacja TickTimer (type=flying, packets=1 / type=end, packets=2) oraz Timer:
        // GrimAC zlicza pakiety ruchu pomiędzy kolejnymi ServerboundClientTickEndPacket.
        // Odrzucamy duplikaty pakietów ruchu w obrębie tego samego ticku!
        else if (packet instanceof ServerboundMovePlayerPacket) {
            if (!GrimBreakValidator.allowFlyingPacket()) {
                ci.cancel();
                return;
            }
        }
        // 3. Ostateczna zapora na poziomie sieci przed AirLiquidBreak (START_DIGGING / FINISHED_DIGGING na AIR):
        else if (packet instanceof ServerboundPlayerActionPacket actionPacket) {
            ServerboundPlayerActionPacket.Action action = actionPacket.getAction();
            if (action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                    || action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) {
                Minecraft mc = Minecraft.getInstance();
                ClientLevel level = mc.level;
                if (level != null) {
                    BlockPos targetPos = actionPacket.getPos();
                    if (level.getBlockState(targetPos).isAir() || GrimBreakValidator.isUnderQuarantine(targetPos)) {
                        ci.cancel();
                        return;
                    }
                }
            }
        }

        for (IBaritone ibaritone : BaritoneAPI.getProvider().getAllBaritones()) {
            if (ibaritone.getPlayerContext().player() != null && ibaritone.getPlayerContext().player().connection.getConnection() == (Connection) (Object) this) {
                ibaritone.getGameEventHandler().onSendPacket(new PacketEvent((Connection) (Object) this, EventState.PRE, packet));
            }
        }
    }

    @Inject(
            method = "sendPacket",
            at = @At("RETURN")
    )
    private void postDispatchPacket(Packet<?> packet, PacketSendListener packetSendListener, boolean flush, CallbackInfo ci) {
        if (this.receiving != PacketFlow.CLIENTBOUND) {
            return;
        }

        for (IBaritone ibaritone : BaritoneAPI.getProvider().getAllBaritones()) {
            if (ibaritone.getPlayerContext().player() != null && ibaritone.getPlayerContext().player().connection.getConnection() == (Connection) (Object) this) {
                ibaritone.getGameEventHandler().onSendPacket(new PacketEvent((Connection) (Object) this, EventState.POST, packet));
            }
        }
    }

    @Inject(
            method = "channelRead0",
            at = @At(
                    value = "INVOKE",
                    target = "net/minecraft/network/Connection.genericsFtw(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V"
            )
    )
    private void preProcessPacket(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        if (this.receiving != PacketFlow.CLIENTBOUND) {
            return;
        }

        // Odblokowanie natychmiastowej odpowiedzi na teleport z serwera (potwierdzenie pozycji)
        if (packet instanceof ClientboundPlayerPositionPacket) {
            GrimBreakValidator.onServerTeleportReceived();
        }

        for (IBaritone ibaritone : BaritoneAPI.getProvider().getAllBaritones()) {
            if (ibaritone.getPlayerContext().player() != null && ibaritone.getPlayerContext().player().connection.getConnection() == (Connection) (Object) this) {
                ibaritone.getGameEventHandler().onReceivePacket(new PacketEvent((Connection) (Object) this, EventState.PRE, packet));
            }
        }
    }

    @Inject(
            method = "channelRead0",
            at = @At("RETURN")
    )
    private void postProcessPacket(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        if (!this.channel.isOpen() || this.receiving != PacketFlow.CLIENTBOUND) {
            return;
        }
        for (IBaritone ibaritone : BaritoneAPI.getProvider().getAllBaritones()) {
            if (ibaritone.getPlayerContext().player() != null && ibaritone.getPlayerContext().player().connection.getConnection() == (Connection) (Object) this) {
                ibaritone.getGameEventHandler().onReceivePacket(new PacketEvent((Connection) (Object) this, EventState.POST, packet));
            }
        }
    }
}
