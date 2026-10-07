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
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.bypass.RotationEngine;
import baritone.hud.AiActionLogger;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
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
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Moduł AutoEat – automatyczne, w 100% bezpieczne dla GrimAC i Custom GrimAC spożywanie jedzenia.
 *
 * Główne zasady działania GrimAC-safe:
 * 1. ZATRZYMANIE RUCHU (#stop):
 *    Gdy bot jest głodny, całkowicie zatrzymuje ruch (clearAllKeys, brak biegania, brak skakania, brak ataków/kopania).
 *    GrimAC ItemUse / NoSlow nie ma żadnej możliwości wykrycia nielegalnego ruchu, bo prędkość gracza wynosi 0.
 * 2. CZEKANIE NA KONTAKT Z ZIEMIĄ (onGround):
 *    Bot nie zaczyna jeść w powietrzu (w trakcie skoku lub spadania), co zapobiega flagom NoSlow w powietrzu oraz
 *    spadnięciu w przepaść/lawę.
 * 3. OPÓŹNIENIE ZMIANY SLOTU (2 ticki):
 *    Wysłanie kliknięcia PPM w tym samym tiku co zmiana slotu powoduje flagi FastEat/InvalidSlot w custom GrimAC.
 *    Czekamy 2 tiki bufora przed rozpoczęciem jedzenia.
 * 4. OCHRONA PRZED INTERAKCJĄ Z BLOKAMI (Crosshair Safety):
 *    Jeśli celownik wskazuje na skrzynkę, piec, crafting, drzwi lub inny blok interaktywny, bot płynnie
 *    (z kwantyzacją GCD i krzywą Beziera przez RotationEngine) unosi celownik w powietrze (-45° pitch),
 *    aby PPM nie otworzyło GUI zamiast zjeść.
 * 5. NATURALNY CZAS JEDZENIA:
 *    Bot trzyma PPM przez pełne 32 ticki (lub 16 dla kelpu), dopóki stan isUsingItem() nie zakończy się
 *    i poziom głodu/życia nie wzrośnie.
 * 6. PŁYNNE WZNOWIENIE (isTemporary = true, priority = 6.0):
 *    Proces jest tymczasowy, co oznacza że PathingControlManager NIE anuluje zadań w tle (np. #mine diamond, #bypass, #goto).
 *    Po zjedzeniu bot przywraca pierwotny slot i natychmiast wznawia poprzednie zadanie dokładnie w tym samym miejscu!
 */
public class AutoEatProcess extends BaritoneProcessHelper implements IBaritoneProcess {

    public static final int OFFHAND_SLOT_INDEX = 40;
    private static final int MAX_SWAP_WAIT_TICKS = 40;

    public enum State {
        IDLE,
        WAIT_GROUND,       // Czekanie na wylądowanie na ziemi
        STOPPING,          // Zerowanie klawiszy i zatrzymanie gracza
        SWAPPING_INVENTORY,// Przenoszenie jedzenia z plecaka na hotbar
        SELECTING_SLOT,    // Wybór slotu z jedzeniem + 2 ticki bufora GrimAC
        EATING,            // Trzymanie PPM i oczekiwanie na zjedzenie
        POST_EAT,          // 2 ticki bufora po zjedzeniu przed zmianą slotu
        RESTORING          // Przywrócenie pierwotnego slotu i oddanie kontroli
    }

    private State state = State.IDLE;
    private int originalSlot = -1;
    private int foodSlot = -1;
    private int stateTicks = 0;
    private int eatingTicks = 0;
    private int startFoodLevel = 0;
    private float startHealth = 0f;
    private int cooldownTicksRemaining = 0;

    // Obsługa plecaka (sloty 9-35)
    private boolean movedFromInventory = false;
    private int inventorySourceSlot = -1;
    private int swapWaitTicks = 0;

    // Crosshair safety (ochrona przed otwieraniem skrzyń)
    private boolean pitchAdjusted = false;
    private Rotation preEatRotation = null;

    // Wymuszenie natychmiastowego zjedzenia (np. z komendy #autoeat now)
    private boolean forceEatNow = false;
    private boolean eatPacketSent = false;

    public AutoEatProcess(Baritone baritone) {
        super(baritone);
    }

    // -------------------------------------------------------------------------
    // isActive
    // -------------------------------------------------------------------------

    @Override
    public boolean isActive() {
        if (ctx.player() == null || ctx.world() == null) {
            return false;
        }

        if (!Baritone.settings().autoEat.value && !forceEatNow) {
            if (state != State.IDLE) {
                stopEating();
            }
            return false;
        }

        // Jeśli już jesteśmy w trakcie sekwencji jedzenia, proces musi zachować kontrolę
        if (state != State.IDLE) {
            return true;
        }

        if (cooldownTicksRemaining > 0) {
            cooldownTicksRemaining--;
            return false;
        }

        int foodLevel = ctx.player().getFoodData().getFoodLevel();
        float health = ctx.player().getHealth();
        int threshold = Baritone.settings().autoEatThreshold.value;
        double healthThresh = Baritone.settings().autoEatHealthThreshold.value;

        boolean criticalHp = Baritone.settings().autoEatGoldenApple.value
                && health <= Baritone.settings().autoEatGoldenAppleThreshold.value;

        boolean needsFood = forceEatNow
                || foodLevel <= threshold
                || (health <= healthThresh && foodLevel < 20)
                || criticalHp;

        if (!needsFood) {
            return false;
        }

        // Combat check: wstrzymaj niekrytyczne jedzenie jeśli w pobliżu są wrogie moby
        if (Baritone.settings().autoEatPauseInCombat.value && !criticalHp && !forceEatNow) {
            if (hasNearbyHostiles()) {
                return false;
            }
        }

        // Sprawdź czy mamy jakiekolwiek jedzenie w hotbarze, offhandzie lub plecaku
        return findBestFoodSlot(criticalHp) != -1 || canMoveFromInventory(criticalHp);
    }

    // -------------------------------------------------------------------------
    // onTick - Maszyna stanów GrimAC-Safe
    // -------------------------------------------------------------------------

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (ctx.player() == null || (!Baritone.settings().autoEat.value && !forceEatNow)) {
            stopEating();
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        boolean criticalHp = Baritone.settings().autoEatGoldenApple.value
                && ctx.player().getHealth() <= Baritone.settings().autoEatGoldenAppleThreshold.value;

        switch (state) {
            case IDLE:
                // Rozpoczęcie procedury jedzenia
                state = State.WAIT_GROUND;
                stateTicks = 0;
                originalSlot = ctx.player().getInventory().selected;
                startFoodLevel = ctx.player().getFoodData().getFoodLevel();
                startHealth = ctx.player().getHealth();
                pitchAdjusted = false;
                preEatRotation = null;
                eatPacketSent = false;
                AiActionLogger.log("EAT", "§eAutoEat uruchomiony: zatrzymywanie bota...");
                // fallthrough to WAIT_GROUND

            case WAIT_GROUND:
                stateTicks++;
                boolean grounded = ctx.player().onGround() || ctx.player().isInWater() || ctx.player().getAbilities().flying;
                boolean safeToPause = baritone.getPathingBehavior().isSafeToCancel();
                // Czekamy na kontakt z ziemią i bezpieczny moment pauzy ścieżki (maks 20 ticków)
                if ((grounded && safeToPause) || stateTicks >= 20) {
                    state = State.STOPPING;
                    stateTicks = 0;
                } else {
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                // fallthrough to STOPPING

            case STOPPING:
                stateTicks++;
                // 1. Pełne zatrzymanie ruchu gracza
                baritone.getInputOverrideHandler().clearAllKeys();
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);
                baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
                if (ctx.player().isSprinting()) {
                    ctx.player().setSprinting(false);
                }
                if (ctx.player().isUsingItem()) {
                    ctx.player().stopUsingItem();
                }
                try {
                    Minecraft.getInstance().options.keyUse.setDown(false);
                } catch (Throwable ignored) {}

                // Czekamy aż pęd poziomy wygaśnie (zero prędkości przed jedzeniem zapobiega NoSlow / ItemUse)
                Vec3 vel = ctx.player().getDeltaMovement();
                double horizSpeedSq = vel.x * vel.x + vel.z * vel.z;
                if (horizSpeedSq > 0.005D && stateTicks < 5) {
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                // Znajdź najlepsze jedzenie
                foodSlot = findBestFoodSlot(criticalHp);

                // Jeśli nie ma w hotbarze/offhandzie, spróbuj przenieść z plecaka
                if (foodSlot == -1 && canMoveFromInventory(criticalHp)) {
                    int invSlot = findBestInventoryFoodSlot(criticalHp);
                    if (invSlot != -1) {
                        boolean queued = baritone.getInventoryBehavior().attemptToPutOnHotbar(invSlot, s -> false);
                        if (queued) {
                            movedFromInventory = true;
                            inventorySourceSlot = invSlot;
                            state = State.SWAPPING_INVENTORY;
                            swapWaitTicks = 0;
                            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                        }
                    }
                }

                if (foodSlot == -1) {
                    AiActionLogger.log("EAT", "§cBrak zdatnego jedzenia w ekwipunku!");
                    stopEating();
                    return new PathingCommand(null, PathingCommandType.DEFER);
                }

                state = State.SELECTING_SLOT;
                stateTicks = 0;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);

            case SWAPPING_INVENTORY:
                swapWaitTicks++;
                foodSlot = findBestFoodSlot(criticalHp);
                if (foodSlot != -1) {
                    state = State.SELECTING_SLOT;
                    stateTicks = 0;
                } else if (swapWaitTicks > MAX_SWAP_WAIT_TICKS) {
                    AiActionLogger.log("EAT", "§cPrzekroczono limit czasu przenoszenia jedzenia z plecaka.");
                    stopEating();
                    return new PathingCommand(null, PathingCommandType.DEFER);
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);

            case SELECTING_SLOT:
                // Utrzymuj zatrzymanie ruchu
                baritone.getInputOverrideHandler().clearAllKeys();
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);

                // Wybór slotu z jedzeniem (jeśli to nie offhand)
                if (foodSlot != OFFHAND_SLOT_INDEX) {
                    if (ctx.player().getInventory().selected != foodSlot) {
                        ctx.player().getInventory().selected = foodSlot;
                        ctx.playerController().syncHeldItem();
                    }
                }

                // Ochrona celownika przed interakcją z blokami lub mobami
                HitResult hit = ctx.objectMouseOver();
                boolean dangerousHit = false;
                if (hit != null) {
                    if (hit.getType() == HitResult.Type.ENTITY) {
                        dangerousHit = true;
                    } else if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
                        BlockState bs = ctx.world().getBlockState(bhr.getBlockPos());
                        if (isInteractiveBlock(bs.getBlock(), bhr.getBlockPos())) {
                            dangerousHit = true;
                        }
                    }
                }
                if (dangerousHit) {
                    if (!pitchAdjusted) {
                        preEatRotation = new Rotation(ctx.player().getYRot(), ctx.player().getXRot());
                        pitchAdjusted = true;
                    }
                    Rotation safeRot = new Rotation(ctx.player().getYRot(), -50.0f);
                    baritone.getLookBehavior().updateTarget(safeRot, false);
                }

                // Bufor 3 ticków dla GrimAC przed rozpoczęciem klikania PPM
                stateTicks++;
                if (stateTicks >= 3) {
                    state = State.EATING;
                    eatingTicks = 0;
                    eatPacketSent = false;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);

            case EATING:
                // Utrzymuj zatrzymanie ruchu i brak blok place
                baritone.getInputOverrideHandler().clearAllKeys();
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);

                if (pitchAdjusted) {
                    baritone.getLookBehavior().updateTarget(new Rotation(ctx.player().getYRot(), -50.0f), false);
                }

                InteractionHand hand = (foodSlot == OFFHAND_SLOT_INDEX) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;

                if (foodSlot != OFFHAND_SLOT_INDEX && ctx.player().getInventory().selected != foodSlot) {
                    ctx.player().getInventory().selected = foodSlot;
                    ctx.playerController().syncHeldItem();
                }

                // Trzymaj klawisz PPM wciśnięty w grze
                try {
                    Minecraft.getInstance().options.keyUse.setDown(true);
                } catch (Throwable ignored) {}

                // Wyślij proces jedzenia (useItem) TYLKO RAZ na początku
                if (!eatPacketSent) {
                    ctx.playerController().syncHeldItem();
                    ctx.playerController().processRightClick(ctx.player(), ctx.world(), hand);
                    eatPacketSent = true;
                    startFoodLevel = ctx.player().getFoodData().getFoodLevel();
                    startHealth = ctx.player().getHealth();
                    AiActionLogger.log("EAT", "§e[AutoEat] Start jedzenia: slot=" + foodSlot);
                }

                ItemStack currentStack = (foodSlot == OFFHAND_SLOT_INDEX)
                        ? ctx.player().getOffhandItem()
                        : ctx.player().getInventory().getItem(foodSlot);

                int expectedUseDuration = currentStack.getUseDuration(ctx.player());
                if (expectedUseDuration <= 0) expectedUseDuration = 32;

                eatingTicks++;

                boolean isUsingItem = ctx.player().isUsingItem();

                // Jeśli po 4 tickach gracz wciąż nie jest w stanie isUsingItem, anuluj zamiast spamować pakietami
                if (!isUsingItem && eatingTicks >= 4 && eatingTicks < expectedUseDuration) {
                    AiActionLogger.log("EAT", "§c[AutoEat] Brak stanu jedzenia na serwerze. Przerywanie.");
                    try {
                        Minecraft.getInstance().options.keyUse.setDown(false);
                    } catch (Throwable ignored) {}
                    stopEating();
                    return new PathingCommand(null, PathingCommandType.DEFER);
                }

                int currentFood = ctx.player().getFoodData().getFoodLevel();
                float currentHp = ctx.player().getHealth();

                boolean durationSatisfied = eatingTicks >= expectedUseDuration;
                boolean foodGrown = currentFood > startFoodLevel;
                boolean hpGrown = currentHp > startHealth;
                boolean stackGone = currentStack.isEmpty() || currentStack.get(DataComponents.FOOD) == null;

                // Bezpieczne zakończenie konsumpcji: TYLKO gdy minął pełny czas trwania przedmiotu!
                boolean consumed = durationSatisfied && (!isUsingItem || foodGrown || hpGrown || stackGone);
                boolean timeout = eatingTicks >= expectedUseDuration + 25;

                if (consumed || timeout) {
                    try {
                        Minecraft.getInstance().options.keyUse.setDown(false);
                    } catch (Throwable ignored) {}
                    state = State.POST_EAT;
                    stateTicks = 0;
                    AiActionLogger.log("EAT", "§aZjedzono pomyślnie (" + eatingTicks + " t)! Bufor...");
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);

            case POST_EAT:
                // Utrzymuj zwolniony PPM i zatrzymanie gracza przez 3 ticki bufora
                try {
                    Minecraft.getInstance().options.keyUse.setDown(false);
                } catch (Throwable ignored) {}
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
                baritone.getInputOverrideHandler().clearAllKeys();
                stateTicks++;
                if (stateTicks >= 3) {
                    state = State.RESTORING;
                    stateTicks = 0;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);

            case RESTORING:
                // Przywróć pierwotny slot i kąt widzenia
                if (originalSlot >= 0 && originalSlot < 9) {
                    if (ctx.player().getInventory().selected != originalSlot) {
                        ctx.player().getInventory().selected = originalSlot;
                        ctx.playerController().syncHeldItem();
                    }
                }
                if (pitchAdjusted && preEatRotation != null) {
                    baritone.getLookBehavior().updateTarget(preEatRotation, false);
                }
                stateTicks++;
                if (stateTicks >= 2) {
                    if (baritone.getLookBehavior() != null) {
                        baritone.getLookBehavior().updateTarget(null, false);
                    }
                    cooldownTicksRemaining = Math.max(15, Baritone.settings().autoEatCooldownTicks.value);
                    forceEatNow = false;
                    stopEating();
                    return new PathingCommand(null, PathingCommandType.DEFER);
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);

            default:
                stopEating();
                return new PathingCommand(null, PathingCommandType.DEFER);
        }
    }

    // -------------------------------------------------------------------------
    // Zatrzymanie / sprzątanie
    // -------------------------------------------------------------------------

    private void stopEating() {
        state = State.IDLE;
        foodSlot = -1;
        eatingTicks = 0;
        stateTicks = 0;
        eatPacketSent = false;
        movedFromInventory = false;
        inventorySourceSlot = -1;
        swapWaitTicks = 0;
        pitchAdjusted = false;
        preEatRotation = null;
        forceEatNow = false;

        try {
            Minecraft.getInstance().options.keyUse.setDown(false);
        } catch (Throwable ignored) {}
        if (baritone != null) {
            if (baritone.getInputOverrideHandler() != null) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
                baritone.getInputOverrideHandler().clearAllKeys();
            }
            if (baritone.getLookBehavior() != null) {
                baritone.getLookBehavior().updateTarget(null, false);
            }
        }
        if (ctx.player() != null && originalSlot >= 0 && originalSlot < 9) {
            ctx.player().getInventory().selected = originalSlot;
            ctx.playerController().syncHeldItem();
        }
        originalSlot = -1;
    }

    @Override
    public void onLostControl() {
        try {
            Minecraft.getInstance().options.keyUse.setDown(false);
        } catch (Throwable ignored) {}
        stopEating();
    }

    @Override
    public String displayName0() {
        if (isEating()) {
            return "auto eat ♥";
        }
        if (isWaitingForSwap()) {
            return "auto eat ⇄";
        }
        return "auto eat";
    }

    @Override
    public double priority() {
        return 6.0D; // Wyższy priorytet niż BypassProcess (3.0), InventoryPauser (4.0) i MineProcess (1.0)
    }

    @Override
    public boolean isTemporary() {
        return true; // KLUCZOWE: nie resetuje procesów w tle (#mine, #bypass, #goto)!
    }

    public boolean isEating() {
        return state != State.IDLE;
    }

    public boolean isSuppressingMovement() {
        return state != State.IDLE && state != State.WAIT_GROUND;
    }

    public boolean isWaitingForSwap() {
        return state == State.SWAPPING_INVENTORY;
    }

    public State getState() {
        return state;
    }

    public void forceEatNow() {
        this.forceEatNow = true;
        this.cooldownTicksRemaining = 0;
    }

    // -------------------------------------------------------------------------
    // Wyszukiwanie jedzenia i scoring
    // -------------------------------------------------------------------------

    private int findBestFoodSlot(boolean preferGoldenApple) {
        int bestSlot = -1;
        float bestScore = -1f;

        // Najpierw sprawdź offhand — jeśli tam jest jedzenie, preferujemy je (brak potrzeby zmiany slotu)
        ItemStack offhand = ctx.player().getOffhandItem();
        float offhandScore = scoreFood(offhand, preferGoldenApple);
        if (offhandScore > bestScore && offhandScore > 0) {
            bestScore = offhandScore;
            bestSlot = OFFHAND_SLOT_INDEX;
        }

        // Następnie sprawdź hotbar (0-8)
        for (int i = 0; i < 9; i++) {
            ItemStack stack = ctx.player().getInventory().getItem(i);
            float score = scoreFood(stack, preferGoldenApple);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
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

    private float scoreFood(ItemStack stack, boolean preferGoldenApple) {
        if (stack == null || stack.isEmpty()) return -1f;

        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food == null) return -1f;

        if (!ctx.player().canEat(food.canAlwaysEat())) return -1f;

        Item item = stack.getItem();

        // Blokada szkodliwego jedzenia
        if (item == Items.ROTTEN_FLESH || item == Items.PUFFERFISH
                || item == Items.SPIDER_EYE || item == Items.POISONOUS_POTATO
                || item == Items.CHORUS_FRUIT || item == Items.SUSPICIOUS_STEW) {
            return -1f;
        }

        List<Item> blacklist = Baritone.settings().autoEatBlacklist.value;
        if (blacklist != null && blacklist.contains(item)) {
            return -1f;
        }

        // Detekcja szkodliwych efektów z komponentu CONSUMABLE (1.21.2+)
        Consumable consumable = stack.get(DataComponents.CONSUMABLE);
        if (consumable != null) {
            for (ConsumeEffect effect : consumable.onConsumeEffects()) {
                if (effect instanceof ApplyStatusEffectsConsumeEffect applyEffects) {
                    for (MobEffectInstance mobEffectInstance : applyEffects.effects()) {
                        if (!mobEffectInstance.getEffect().value().isBeneficial()) {
                            return -1f; // Efekt szkodliwy (np. trucizna, głód)
                        }
                    }
                }
            }
        }

        // Obsługa złotych jabłek (ochrona przed marnowaniem)
        if (preferGoldenApple) {
            if (item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE) {
                boolean hasRegen = ctx.player().hasEffect(MobEffects.REGENERATION);
                boolean isCriticallyLow = ctx.player().getHealth() <= 6.0f; // 3 serca
                if (hasRegen && !isCriticallyLow) {
                    return -1f; // Nie marnuj kolejnego jabłka jeśli Regeneracja już działa
                }
                return item == Items.ENCHANTED_GOLDEN_APPLE ? 5000f : 4000f;
            }
        } else {
            if (item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE) {
                return -1000f; // Nie jedz koxów na zwykły głód
            }
        }

        int currentFood = ctx.player().getFoodData().getFoodLevel();
        int stopAt = Baritone.settings().autoEatStopThreshold.value;
        if (currentFood >= stopAt && !preferGoldenApple && !forceEatNow) {
            return -1f;
        }

        int nutrition = food.nutrition();
        float saturation = food.saturation();
        int needed = 20 - currentFood;
        int waste = Math.max(0, nutrition - needed);

        float wastePenalty = waste * 10.0f;
        if (needed <= 3 && nutrition > 3) {
            wastePenalty += 20.0f; // Preferuj mniejsze przekąski
        }

        float score = (saturation * 2.0f) + nutrition - wastePenalty;
        if (waste == 0 && currentFood + nutrition >= 20) {
            score += 2.0f; // Bonus za idealne zapełnienie
        }

        return score;
    }

    private boolean isInteractiveBlock(Block block, BlockPos pos) {
        if (block instanceof BaseEntityBlock
                || block instanceof AnvilBlock
                || block instanceof DoorBlock
                || block instanceof TrapDoorBlock
                || block instanceof ButtonBlock
                || block instanceof BedBlock
                || block instanceof FenceGateBlock
                || block instanceof CraftingTableBlock
                || block instanceof LeverBlock
                || block instanceof LecternBlock
                || block instanceof EnchantingTableBlock) {
            return true;
        }
        return ctx.world() != null && pos != null && ctx.world().getBlockEntity(pos) != null;
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
}
