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

package baritone.process;

import baritone.Baritone;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.item.consume_effects.ConsumeEffect;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

/**
 * AutoEatProcess – advanced automated eating for Baritone (1.21.4+).
 *
 * Features:
 * - 1.21.4 DataComponents API (FOOD + CONSUMABLE)
 * - Dynamic Use Duration via ItemStack#getUseDuration
 * - Crosshair safety (avoids interacting with chests/doors by looking up)
 * - Parkour safety (defers pause commands while in the air)
 * - Dynamic harmful effect detection via ApplyStatusEffectsConsumeEffect
 * - Smart Golden Apple usage (prevents spamming while Regeneration is active)
 * - Off-hand support (consumes off-hand food directly)
 * - Anti-overfill scoring (prefers small snacks when only 1-3 hunger points are needed)
 * - Safe inventory-to-hotbar swapping using Baritone's InventoryBehavior
 */
public class AutoEatProcess extends BaritoneProcessHelper {

    private static final int OFFHAND_SLOT_INDEX = 40; // Internal tracking index for offhand
    private static final int MAX_SWAP_WAIT_TICKS = 40;

    private boolean eating = false;
    private int originalSlot = -1;
    private int foodSlot = -1;
    private int eatingTicks = 0;
    private int startFoodLevel = 0;
    private int cooldownTicksRemaining = 0;

    // Inventory swap tracking
    private boolean movedFromInventory = false;
    private int inventorySourceSlot = -1;
    private boolean waitingForSwap = false;
    private int swapWaitTicks = 0;

    public AutoEatProcess(Baritone baritone) {
        super(baritone);
    }

    // -------------------------------------------------------------------------
    // isActive
    // -------------------------------------------------------------------------

    @Override
    public boolean isActive() {
        if (ctx.player() == null || ctx.world() == null || !Baritone.settings().autoEat.value) {
            return false;
        }

        if (eating) {
            return true;
        }

        if (cooldownTicksRemaining > 0) {
            cooldownTicksRemaining--;
            return false;
        }

        int foodLevel = ctx.player().getFoodData().getFoodLevel();
        float health  = ctx.player().getHealth();
        int threshold       = Baritone.settings().autoEatThreshold.value;
        double healthThresh = Baritone.settings().autoEatHealthThreshold.value;

        boolean criticalHp = Baritone.settings().autoEatGoldenApple.value
                && health <= Baritone.settings().autoEatGoldenAppleThreshold.value;

        boolean needsFood = foodLevel <= threshold
                || (health <= healthThresh && foodLevel < 20)
                || criticalHp;

        if (!needsFood) {
            return false;
        }

        // Combat check: skip non-critical eating when near hostile mobs
        if (Baritone.settings().autoEatPauseInCombat.value && !criticalHp) {
            if (hasNearbyHostiles()) {
                return false;
            }
        }

        // If waiting for inventory swap to process on the server
        if (waitingForSwap) {
            return true;
        }

        return findBestFoodSlot(criticalHp) != -1 || canMoveFromInventory(criticalHp);
    }

    // -------------------------------------------------------------------------
    // Food finding helpers
    // -------------------------------------------------------------------------

    private int findBestFoodSlot(boolean preferGoldenApple) {
        int bestSlot = -1;
        float bestScore = -1f;

        // Check hotbar
        for (int i = 0; i < 9; i++) {
            ItemStack stack = ctx.player().getInventory().getItem(i);
            float score = scoreFood(stack, preferGoldenApple);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }

        // Check offhand
        ItemStack offhand = ctx.player().getOffhandItem();
        float offhandScore = scoreFood(offhand, preferGoldenApple);
        if (offhandScore > bestScore && offhandScore > 0) {
            bestScore = offhandScore;
            bestSlot = OFFHAND_SLOT_INDEX;
        }

        return bestSlot;
    }

    private boolean canMoveFromInventory(boolean preferGoldenApple) {
        if (!Baritone.settings().autoEatSearchInventory.value) return false;
        if (!Baritone.settings().allowInventory.value) return false;
        return findBestInventoryFoodSlot(preferGoldenApple) != -1;
    }

    private int findBestInventoryFoodSlot(boolean preferGoldenApple) {
        int bestSlot = -1;
        float bestScore = -1f;

        for (int i = 9; i < 36; i++) {
            ItemStack stack = ctx.player().getInventory().getItem(i);
            float score = scoreFood(stack, preferGoldenApple);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    /**
     * Smart scoring algorithm for 1.21.4.
     * Penalizes food that overfills the hunger bar to prevent waste.
     * Detects harmful effects dynamically via the CONSUMABLE DataComponent.
     */
    private float scoreFood(ItemStack stack, boolean preferGoldenApple) {
        if (stack.isEmpty()) return -1f;

        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food == null) return -1f;

        if (!ctx.player().canEat(food.canAlwaysEat())) return -1f;

        Item item = stack.getItem();

        // Hard-coded harmful / dangerous foods
        if (item == Items.ROTTEN_FLESH || item == Items.PUFFERFISH
                || item == Items.SPIDER_EYE || item == Items.POISONOUS_POTATO
                || item == Items.CHORUS_FRUIT || item == Items.SUSPICIOUS_STEW) {
            return -1f;
        }

        List<Item> blacklist = Baritone.settings().autoEatBlacklist.value;
        if (blacklist != null && blacklist.contains(item)) {
            return -1f;
        }

        // Dynamic harmful effect detection (1.21.2+ API)
        Consumable consumable = stack.get(DataComponents.CONSUMABLE);
        if (consumable != null) {
            for (ConsumeEffect effect : consumable.onConsumeEffects()) {
                if (effect instanceof ApplyStatusEffectsConsumeEffect applyEffects) {
                    for (MobEffectInstance mobEffectInstance : applyEffects.effects()) {
                        if (!mobEffectInstance.getEffect().value().isBeneficial()) {
                            return -1f; // Harmful effect found (e.g. Poison, Hunger), avoid!
                        }
                    }
                }
            }
        }

        // Golden Apple logic with anti-spam protection
        if (preferGoldenApple) {
            if (item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE) {
                boolean hasRegen = ctx.player().hasEffect(MobEffects.REGENERATION);
                boolean isCriticallyLow = ctx.player().getHealth() <= 6.0f; // 3 hearts
                if (hasRegen && !isCriticallyLow) {
                    return -1f; // Don't waste another GA while Regeneration is active and HP isn't critical
                }
                return item == Items.ENCHANTED_GOLDEN_APPLE ? 5000f : 4000f;
            }
        } else {
            if (item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE) {
                return -1000f; // Never eat golden apples for normal hunger
            }
        }

        int currentFood = ctx.player().getFoodData().getFoodLevel();
        int stopAt = Baritone.settings().autoEatStopThreshold.value;
        if (currentFood >= stopAt) {
            return -1f;
        }

        int nutrition = food.nutrition();
        float saturation = food.saturation();
        int needed = 20 - currentFood;
        int waste = Math.max(0, nutrition - needed);
        
        float wastePenalty = waste * 10.0f;
        // Prefer smaller snacks if only 1-3 points are needed to avoid wasting high-tier food
        if (needed <= 3 && nutrition > 3) {
            wastePenalty += 20.0f; 
        }

        float score = (saturation * 2.0f) + nutrition - wastePenalty;
        if (waste == 0 && currentFood + nutrition >= 20) {
            score += 2.0f; // Bonus for perfect fill
        }

        return score;
    }

    private boolean hasNearbyHostiles() {
        if (ctx.player() == null || ctx.world() == null) return false;
        double radius = Baritone.settings().autoEatCombatRadius.value;
        AABB box = ctx.player().getBoundingBox().inflate(radius);
        return !ctx.world().getEntitiesOfClass(
                LivingEntity.class,
                box,
                e -> e instanceof Enemy && e.isAlive()
        ).isEmpty();
    }

    // -------------------------------------------------------------------------
    // onTick
    // -------------------------------------------------------------------------

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (ctx.player() == null || !Baritone.settings().autoEat.value) {
            stopEating();
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        if (!eating) {
            boolean criticalHp = Baritone.settings().autoEatGoldenApple.value
                    && ctx.player().getHealth() <= Baritone.settings().autoEatGoldenAppleThreshold.value;

            // Handle waiting for inventory swap latency
            if (waitingForSwap) {
                foodSlot = findBestFoodSlot(criticalHp);
                if (foodSlot != -1) {
                    waitingForSwap = false;
                    swapWaitTicks = 0;
                    // fallthrough to start eating
                } else {
                    swapWaitTicks++;
                    if (swapWaitTicks > MAX_SWAP_WAIT_TICKS) {
                        waitingForSwap = false;
                        swapWaitTicks = 0;
                        return new PathingCommand(null, PathingCommandType.DEFER);
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            } else {
                foodSlot = findBestFoodSlot(criticalHp);

                // If not found in hotbar/offhand, try to move from inventory
                if (foodSlot == -1 && canMoveFromInventory(criticalHp)) {
                    int invSlot = findBestInventoryFoodSlot(criticalHp);
                    if (invSlot != -1) {
                        boolean queued = baritone.getInventoryBehavior().attemptToPutOnHotbar(invSlot, s -> false);
                        if (queued) {
                            movedFromInventory = true;
                            inventorySourceSlot = invSlot;
                            waitingForSwap = true;
                            swapWaitTicks = 0;
                            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                        }
                    }
                }

                if (foodSlot == -1) {
                    return new PathingCommand(null, PathingCommandType.DEFER);
                }
            }

            // Begin eating sequence
            eating = true;
            originalSlot = ctx.player().getInventory().selected;
            eatingTicks = 0;
            startFoodLevel = ctx.player().getFoodData().getFoodLevel();

            if (foodSlot != OFFHAND_SLOT_INDEX) {
                ctx.player().getInventory().selected = foodSlot;
            }
            
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        eatingTicks++;

        // Crosshair safety: check if looking at interactive block (chest, door, anvil, etc.)
        HitResult hit = ctx.objectMouseOver();
        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) hit;
            BlockState state = ctx.world().getBlockState(blockHit.getBlockPos());
            Block block = state.getBlock();
            
            boolean isInteractive = block instanceof BaseEntityBlock 
                    || block instanceof AnvilBlock 
                    || block instanceof DoorBlock 
                    || block instanceof TrapDoorBlock 
                    || block instanceof ButtonBlock 
                    || block instanceof BedBlock 
                    || block instanceof FenceGateBlock 
                    || block instanceof CraftingTableBlock 
                    || ctx.world().getBlockEntity(blockHit.getBlockPos()) != null;
            
            if (isInteractive) {
                // Force pitch up to look at the sky/air so we don't open the GUI
                baritone.getLookBehavior().updateTarget(new Rotation(ctx.player().getYRot(), -60f), true);
            }
        }

        ItemStack currentStack = (foodSlot == OFFHAND_SLOT_INDEX) 
                ? ctx.player().getOffhandItem() 
                : ctx.player().getInventory().getItem(foodSlot);

        if (foodSlot != OFFHAND_SLOT_INDEX && ctx.player().getInventory().selected != foodSlot) {
            ctx.player().getInventory().selected = foodSlot;
        }
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);

        // Dynamic eating duration (supports fast foods like Kelp natively)
        int expectedUseDuration = currentStack.getUseDuration(ctx.player());
        if (expectedUseDuration <= 0) expectedUseDuration = 32;

        // Check if done eating dynamically
        boolean isUsingItem      = ctx.player().isUsingItem();
        int currentFoodLevel     = ctx.player().getFoodData().getFoodLevel();
        boolean foodChanged      = currentFoodLevel > startFoodLevel;
        boolean foodGone         = currentStack.isEmpty() || currentStack.get(DataComponents.FOOD) == null;
        boolean maxTicksExceeded = eatingTicks > expectedUseDuration + 15;

        // Safety warmup: verify isUsingItem only after 5 ticks of starting
        boolean stoppedEarly = (eatingTicks > 5 && !isUsingItem);

        if (foodChanged || foodGone || stoppedEarly || maxTicksExceeded) {
            stopEating();
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        // Movement Safety: Don't pause pathing if in the air (prevents dropping mid-jump)
        boolean safeToPause = (ctx.player().onGround() || ctx.player().isInWater() || ctx.player().getAbilities().flying) && isSafeToCancel;
        if (!safeToPause) {
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    // -------------------------------------------------------------------------
    // Stop / cleanup
    // -------------------------------------------------------------------------

    private void stopEating() {
        if (eating || waitingForSwap) {
            eating = false;
            waitingForSwap = false;
            swapWaitTicks = 0;
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);

            if (ctx.player() != null) {
                if (originalSlot >= 0 && originalSlot < 9) {
                    ctx.player().getInventory().selected = originalSlot;
                }
            }

            originalSlot = -1;
            foodSlot = -1;
            eatingTicks = 0;
            movedFromInventory = false;
            inventorySourceSlot = -1;

            cooldownTicksRemaining = Math.max(0, Baritone.settings().autoEatCooldownTicks.value);
        }
    }

    @Override
    public void onLostControl() {
        stopEating();
    }

    @Override
    public String displayName0() {
        if (eating) {
            return "auto eat \u2665";
        }
        if (waitingForSwap) {
            return "auto eat \u21C4"; // Swapping indicator
        }
        return "auto eat";
    }

    @Override
    public double priority() {
        return 6.0; 
    }

    @Override
    public boolean isTemporary() {
        return true;
    }

    public boolean isEating() {
        return eating;
    }

    public boolean isWaitingForSwap() {
        return waitingForSwap;
    }
}
