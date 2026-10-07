package baritone.behavior;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Cienka warstwa nad vanilla MultiPlayerGameMode.
 * Nie wysyła "surowych" pakietów akcji ze stałym sequence 0, bo to MultiPlayerGameMode:
 *  1) nadaje poprawny, rosnący sequence (BlockStatePredictionHandler) i obsługuje pakiety ACK,
 *  2) pilnuje stanu kopania (isDestroying, destroyProgress, destroyDelay),
 *  3) synchronizuje wybrany slot hotbara przed użyciem (ensureHasSentCarriedItem).
 *
 * Wszystkie metody wywoływane z wątku klienta.
 */
public final class GrimPacketOrder {

    private GrimPacketOrder() {}

    private static Minecraft client(LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        if (player == null || player.connection == null || mc.gameMode == null) return null;
        if (!mc.isSameThread()) {
            throw new IllegalStateException("GrimPacketOrder must be called on the client thread");
        }
        return mc;
    }

    /** Zastępuje sendBreakStart. Vanilla wysyła START_DESTROY_BLOCK z właściwym sequence. */
    public static boolean startBreak(LocalPlayer player, BlockPos pos, Direction face) {
        Minecraft mc = client(player);
        if (mc == null || pos == null || face == null) return false;
        boolean started = mc.gameMode.startDestroyBlock(pos, face);
        if (started) player.swing(InteractionHand.MAIN_HAND);
        return started;
    }

    /**
     * Wołaj co tick w trakcie kopania. Zastępuje sendBreakStop: STOP_DESTROY_BLOCK wysyła vanilla
     * dokładnie wtedy, gdy destroyProgress >= 1.0 (z uwzględnieniem destroyDelay).
     */
    public static boolean continueBreak(LocalPlayer player, BlockPos pos, Direction face) {
        Minecraft mc = client(player);
        if (mc == null || pos == null || face == null) return false;
        boolean cont = mc.gameMode.continueDestroyBlock(pos, face);
        if (cont) player.swing(InteractionHand.MAIN_HAND);
        return cont;
    }

    /** Zastępuje sendBreakAbort: vanilla wysyła ABORT_DESTROY_BLOCK z Direction.DOWN i seq 0. */
    public static void abortBreak(LocalPlayer player) {
        Minecraft mc = client(player);
        if (mc == null) return;
        mc.gameMode.stopDestroyBlock();
    }

    /** Zastępuje sendBlockPlace. Swing tylko jeśli wynik tego wymaga (jak Minecraft#startUseItem). */
    public static InteractionResult place(LocalPlayer player, BlockHitResult hit, InteractionHand hand) {
        Minecraft mc = client(player);
        if (mc == null || hit == null || hand == null) return InteractionResult.PASS;
        InteractionResult result = mc.gameMode.useItemOn(player, hand, hit);
        if (result instanceof InteractionResult.Success success
                && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
            player.swing(hand);
        }
        return result;
    }

    /** LocalPlayer#swing robi animację po stronie klienta i sam wysyła ServerboundSwingPacket. */
    public static void swing(LocalPlayer player, InteractionHand hand) {
        Minecraft mc = client(player);
        if (mc == null || hand == null) return;
        player.swing(hand);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Metody wstecznej kompatybilności dla istniejących modułów Baritone
    // ─────────────────────────────────────────────────────────────────────────

    public static void sendBreakStart(LocalPlayer player, BlockPos pos, Direction face) {
        startBreak(player, pos, face);
    }

    public static void sendBreakStop(LocalPlayer player, BlockPos pos, Direction face) {
        continueBreak(player, pos, face);
    }

    public static void sendBreakAbort(LocalPlayer player, BlockPos pos, Direction face) {
        abortBreak(player);
    }

    public static void sendBlockPlace(LocalPlayer player, BlockHitResult hitResult, InteractionHand hand) {
        place(player, hitResult, hand);
    }

    public static void sendSwing(LocalPlayer player) {
        swing(player, InteractionHand.MAIN_HAND);
    }
}
