package baritone.utils.inventory;

import baritone.Baritone;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * Kontroler bezpiecznych, transakcyjnych transferów ekwipunku zgodny z GrimAC, Polar i Vulcan.
 *
 * Realizuje sekwencję micro-stopu:
 * 1. STOPPING_MOVEMENT: Odcięcie wejść ruchu i oczekiwanie na zerowy wektor (GrimAC InvMove safe).
 * 2. CLICKING: Wysłanie windowClick (ClickType.QUICK_MOVE lub ClickType.SWAP).
 * 3. CLOSING_GUI: Wysłanie ServerboundContainerClosePacket(0) po 1 ticku (Polar / Vulcan safe).
 * 4. COOLDOWN: 1 tick bufora po zamknięciu GUI przed kolejną akcją w świecie.
 */
public class SafeInventorySwapController {

    public enum State {
        IDLE,
        STOPPING_MOVEMENT,
        CLICKING,
        CLOSING_GUI,
        COOLDOWN
    }

    private final Baritone baritone;
    private final IPlayerContext ctx;

    private State state = State.IDLE;
    private int stateTicks = 0;

    private int pendingSourceSlot = -1;
    private int pendingTargetHotbarSlot = -1;
    private boolean useQuickMove = false;
    private Predicate<ItemStack> expectedItemPredicate = null;

    private int globalCooldownTicks = 0;

    public SafeInventorySwapController(Baritone baritone) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
    }

    public boolean isBusy() {
        return state != State.IDLE;
    }

    public State getState() {
        return state;
    }

    /**
     * Zleca bezpieczny transfer z plecaka (9-35) do hotbara (0-8).
     * @return true, jeśli zlecenie zostało przyjęte lub jest już w toku.
     */
    public boolean requestSafeTransfer(int sourceInventorySlot, int targetHotbarSlot,
                                       boolean preferQuickMove, Predicate<ItemStack> validator) {
        if (globalCooldownTicks > 0) {
            return false;
        }
        if (state != State.IDLE) {
            return true;
        }
        if (sourceInventorySlot < 9 || sourceInventorySlot > 35) {
            return false;
        }
        if (targetHotbarSlot < 0 || targetHotbarSlot > 8) {
            return false;
        }

        this.pendingSourceSlot = sourceInventorySlot;
        this.pendingTargetHotbarSlot = targetHotbarSlot;
        this.useQuickMove = preferQuickMove;
        this.expectedItemPredicate = validator;
        this.state = State.STOPPING_MOVEMENT;
        this.stateTicks = 0;

        forceStopMovement();
        return true;
    }

    /**
     * Obsługa maszyny stanów co tick (wywoływane w InventoryBehavior.onTick).
     */
    public void onTick() {
        if (globalCooldownTicks > 0) {
            globalCooldownTicks--;
        }

        if (state == State.IDLE) {
            return;
        }

        LocalPlayer player = ctx.player();
        Minecraft mc = Minecraft.getInstance();
        if (player == null || mc.gameMode == null) {
            reset();
            return;
        }

        forceStopMovement();
        stateTicks++;

        switch (state) {
            case STOPPING_MOVEMENT -> {
                // GrimAC wymaga, aby pakiet ruchu poprzedzający kliknięcie nie miał aktywnych wejść
                if (stateTicks >= 2 && isInputCompletelyZero(player)) {
                    state = State.CLICKING;
                    stateTicks = 0;
                } else if (stateTicks > 12) {
                    reset();
                }
            }

            case CLICKING -> {
                ItemStack sourceStack = player.getInventory().getItem(pendingSourceSlot);
                if (sourceStack.isEmpty() || (expectedItemPredicate != null && !expectedItemPredicate.test(sourceStack))) {
                    reset();
                    return;
                }

                if (useQuickMove) {
                    // Shift-Click (dopełnia istniejący stack w hotbarze)
                    mc.gameMode.handleInventoryMouseClick(
                            player.inventoryMenu.containerId,
                            pendingSourceSlot,
                            0,
                            ClickType.QUICK_MOVE,
                            player
                    );
                } else {
                    // SWAP klawiszem 1-9 (zamienia slot z plecaka ze wskazanym slotem hotbara)
                    mc.gameMode.handleInventoryMouseClick(
                            player.inventoryMenu.containerId,
                            pendingSourceSlot,
                            pendingTargetHotbarSlot,
                            ClickType.SWAP,
                            player
                    );
                }

                state = State.CLOSING_GUI;
                stateTicks = 0;
            }

            case CLOSING_GUI -> {
                if (stateTicks >= 1) {
                    if (player.connection != null) {
                        player.connection.send(new ServerboundContainerClosePacket(player.inventoryMenu.containerId));
                    }
                    state = State.COOLDOWN;
                    stateTicks = 0;
                }
            }

            case COOLDOWN -> {
                if (stateTicks >= 1) {
                    ItemStack resultStack = player.getInventory().getItem(pendingTargetHotbarSlot);
                    if (expectedItemPredicate != null && expectedItemPredicate.test(resultStack) && !resultStack.isEmpty()) {
                        player.getInventory().selected = pendingTargetHotbarSlot;
                        ctx.playerController().syncHeldItem();
                    }
                    globalCooldownTicks = 4;
                    reset();
                }
            }

            default -> reset();
        }
    }

    private void forceStopMovement() {
        if (baritone.getInputOverrideHandler() != null) {
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        }

        LocalPlayer p = ctx.player();
        if (p != null) {
            p.setSprinting(false);
            if (p.input != null) {
                p.input.forwardImpulse = 0.0F;
                p.input.leftImpulse = 0.0F;
            }
        }
    }

    private boolean isInputCompletelyZero(LocalPlayer p) {
        if (p.isSprinting()) {
            return false;
        }
        if (p.input == null) {
            return true;
        }
        return Math.abs(p.input.forwardImpulse) < 1.0E-4F
                && Math.abs(p.input.leftImpulse) < 1.0E-4F
                && !p.input.keyPresses.jump()
                && !p.input.keyPresses.shift();
    }

    public void reset() {
        this.state = State.IDLE;
        this.stateTicks = 0;
        this.pendingSourceSlot = -1;
        this.pendingTargetHotbarSlot = -1;
        this.useQuickMove = false;
        this.expectedItemPredicate = null;
    }
}
