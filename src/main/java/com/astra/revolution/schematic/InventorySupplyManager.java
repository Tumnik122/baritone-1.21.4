package com.astra.revolution.schematic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.OptionalInt;

/**
 * Pillar 5: Intelligent Inventory & Hotbar Auto-Replenishment.
 * Automatically equips required blocks from hotbar (slots 0-8), performs silent
 * packet slot swaps from main inventory (slots 9-35), locates nearby supply containers,
 * and triggers localized HUD notifications when materials are exhausted.
 */
public final class InventorySupplyManager {

    private static final int DEDICATED_BUILD_HOTBAR_SLOT = 7; // Hotbar slot 8 (0-indexed 7)
    private static final long WARNING_THROTTLE_MS = 3_500L;

    private long lastMissingMaterialWarningMs = 0L;
    private BlockPos nearestSupplyContainerPos = null;

    /**
     * Ensures the required block for placement is present and selected on the player's hotbar.
     *
     * @return true if the item is now equipped in the main hand and ready to place;
     *         false if out of stock (triggers HUD warning and pauses placement).
     */
    public boolean ensureBlockEquipped(
            LocalPlayer player,
            MultiPlayerGameMode gameMode,
            Block requiredBlock,
            int totalStillMissingInSchematic
    ) {
        Item requiredItem = (requiredBlock instanceof LiquidBlock || requiredBlock == Blocks.WATER)
                ? Items.WATER_BUCKET
                : requiredBlock.asItem();
        Inventory inventory = player.getInventory();

        // 1. Search Hotbar (Slots 0 - 8)
        int hotbarSlot = findMatchingSlot(inventory, requiredItem, 0, 9);
        if (hotbarSlot != -1) {
            selectHotbarSlot(player, hotbarSlot);
            return true;
        }

        // 2. Search Main Inventory (Slots 9 - 35) and perform silent ClickType.SWAP
        int mainInvSlot = findMatchingSlot(inventory, requiredItem, 9, 36);
        if (mainInvSlot != -1 && gameMode != null) {
            int targetHotbarSlot = pickReusableHotbarSlot(inventory);

            // In Player's InventoryMenu (containerId == 0), slots 9-35 map directly to window slot IDs 9-35
            gameMode.handleInventoryMouseClick(
                    player.inventoryMenu.containerId,
                    mainInvSlot,
                    targetHotbarSlot,
                    ClickType.SWAP,
                    player
            );

            selectHotbarSlot(player, targetHotbarSlot);
            return true;
        }

        // 3. Sprawdź możliwość Auto-Refill z nieskończonego źródła wody w pobliżu
        if (requiredItem == Items.WATER_BUCKET) {
            int bucketSlot = findMatchingSlot(inventory, Items.BUCKET, 0, 36);
            if (bucketSlot != -1) {
                BlockPos sourcePos = findRefillableWaterSource(player.level(), player.blockPosition(), 4);
                if (sourcePos != null) {
                    if (bucketSlot >= 9 && gameMode != null) {
                        int targetHotbar = pickReusableHotbarSlot(inventory);
                        gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, bucketSlot, targetHotbar, ClickType.SWAP, player);
                        selectHotbarSlot(player, targetHotbar);
                    } else {
                        selectHotbarSlot(player, bucketSlot);
                    }
                    if (player.connection != null) {
                        player.connection.send(new ServerboundMovePlayerPacket.Rot(
                                player.getYRot(),
                                player.getXRot(),
                                player.onGround(),
                                player.horizontalCollision
                        ));
                    }
                    if (gameMode != null) {
                        gameMode.useItem(player, InteractionHand.MAIN_HAND);
                    }
                    player.swing(InteractionHand.MAIN_HAND);
                    return true;
                }
            }
        }

        // 4. Out of stock in both hotbar and main inventory: scan for nearby chests/shulkers & alert HUD
        this.nearestSupplyContainerPos = scanNearestSupplyContainer(player.level(), player.blockPosition(), 6);
        notifyMissingMaterial(requiredBlock, Math.max(1, totalStillMissingInSchematic));
        return false;
    }

    public OptionalInt getTotalCountInInventory(LocalPlayer player, Block block) {
        Item target = (block instanceof LiquidBlock || block == Blocks.WATER)
                ? Items.WATER_BUCKET
                : block.asItem();
        int total = 0;
        Inventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(target)) {
                total += stack.getCount();
            }
        }
        return OptionalInt.of(total);
    }

    public BlockPos getNearestSupplyContainerPos() {
        return nearestSupplyContainerPos;
    }

    private int findMatchingSlot(Inventory inv, Item targetItem, int startInclusive, int endExclusive) {
        for (int i = startInclusive; i < endExclusive; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(targetItem)) {
                return i;
            }
        }
        return -1;
    }

    private int pickReusableHotbarSlot(Inventory inv) {
        // Prefer an empty hotbar slot first
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).isEmpty()) {
                return i;
            }
        }
        // Prefer a slot already holding a generic BlockItem rather than a weapon/pickaxe
        for (int i = 8; i >= 0; i--) {
            if (inv.getItem(i).getItem() instanceof BlockItem) {
                return i;
            }
        }
        return DEDICATED_BUILD_HOTBAR_SLOT;
    }

    private void selectHotbarSlot(LocalPlayer player, int slot) {
        Inventory inv = player.getInventory();
        if (inv.selected != slot) {
            inv.selected = slot;
            if (Minecraft.getInstance().getConnection() != null) {
                Minecraft.getInstance().getConnection().send(new ServerboundSetCarriedItemPacket(slot));
            }
        }
    }

    private BlockPos scanNearestSupplyContainer(Level level, BlockPos center, int radius) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-radius, -2, -radius),
                center.offset(radius, 3, radius))) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof ChestBlock || state.getBlock() instanceof ShulkerBoxBlock) {
                double d = center.distSqr(pos);
                if (d < bestDist) {
                    bestDist = d;
                    best = pos.immutable();
                }
            }
        }
        return best;
    }

    private BlockPos findRefillableWaterSource(Level level, BlockPos center, int radius) {
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-radius, -2, -radius),
                center.offset(radius, 2, radius))) {
            if (level.getFluidState(pos).isSource() && level.getFluidState(pos).is(FluidTags.WATER)) {
                int adjacentSources = 0;
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos neighbor = pos.relative(dir);
                    if (level.getFluidState(neighbor).isSource() && level.getFluidState(neighbor).is(FluidTags.WATER)) {
                        adjacentSources++;
                    }
                }
                if (adjacentSources >= 2) {
                    return pos.immutable();
                }
            }
        }
        return null;
    }

    private void notifyMissingMaterial(Block missingBlock, int missingCount) {
        long now = System.currentTimeMillis();
        if (now - lastMissingMaterialWarningMs < WARNING_THROTTLE_MS) {
            return;
        }
        lastMissingMaterialWarningMs = now;

        Minecraft mc = Minecraft.getInstance();
        String itemName = missingBlock.getName().getString();
        String warningText = String.format("§d[Litematica] §cBrakujące materiały: §f%s §7(x%d)", itemName, missingCount);

        if (mc.gui != null) {
            mc.gui.setOverlayMessage(Component.literal(warningText), false);
        }
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(warningText), false);
        }
    }
}
