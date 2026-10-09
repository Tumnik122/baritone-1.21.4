package baritone.process;

import baritone.Baritone;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.bypass.BypassConfig;
import baritone.bypass.InventoryCleaner;
import baritone.bypass.InventorySorter;
import baritone.bypass.RotationEngine;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * Moduł automatycznego czyszczenia ekwipunku ze śmieciowych bloków (AutoDrop / Trash Cleaner),
 * blokowania wolnych slotów surowcem (Slot Locking / Inventory Filter)
 * oraz dynamicznej rezerwacji slotów na cenniejsze surowce (Sacrifice / Priority Slot Allocation).
 *
 * GrimAC MultiActionsC-Safe:
 * Anticheat GrimAC flaguje MultiActionsC, jeśli klient wysyła pakiety interakcji z ekwipunkiem
 * (windowClick / ServerboundContainerClickPacket) podczas gdy postać ma jakikolwiek ruch
 * lub aktywne wejście z klawiatury (input=true).
 *
 * Dlatego podczas czyszczenia i blokowania bot:
 *  1. STOPPING / WARMUP: Wycisza absolutnie wszystkie klawisze (WASD, skok, sprint, atak),
 *     zeruje pęd poziomy (setDeltaMovement) i odczekuje 3 ticki bufora, aby serwer
 *     i GrimAC otrzymały i zatwierdziły pakiet input=false / brak ruchu.
 *  2. DROPPING: Postać stoi całkowicie nieruchomo, celuje w bezpieczną wolną przestrzeń
 *     (np. otwarty tunel za sobą, a NIE w ścianę przed sobą!) i wyrzuca śmieci ze slotów.
 *  3. SACRIFICE: Wyrzuca 1 slot niższego surowca (np. żelazo/redstone) i kładzie 1 sztukę cenniejszego
 *     surowca (np. diamenty 64/64), aby zwolnić miejsce na leżące na ziemi diamenty.
 *  4. LOCK_START / DISTRIBUTE / RETURN: Rozdziela po 1 sztuce surowca (np. surowego żelaza)
 *     do każdego pustego slotu w ekwipunku, zabezpieczając go przed podnoszeniem bruku i łupka.
 *  5. COOLDOWN: Po zakończeniu przywraca pierwotny wzrok i odczekuje bufor w bezruchu przed wznowieniem ruchu.
 *     Cykl czyszczenia i sortowania może powtórzyć się maksymalnie raz na 2 minuty (cleanSortCooldownMs).
 */
public class AutoDropProcess extends BaritoneProcessHelper implements IBaritoneProcess {

    public enum State {
        IDLE,
        STOPPING,
        WARMUP,
        AIMING,
        DROPPING,
        SORTING,
        SACRIFICE_DROP,
        SACRIFICE_PICKUP,
        SACRIFICE_PLACE_ONE,
        SACRIFICE_RESTORE,
        LOCK_START,
        LOCK_DISTRIBUTE,
        LOCK_RETURN,
        COOLDOWN
    }

    public static class SacrificeTarget {
        public final int candInvSlot;
        public final int candMenuSlot;
        public final int sourceInvSlot;
        public final int sourceMenuSlot;
        public final String highItemName;

        public SacrificeTarget(int candInvSlot, int candMenuSlot, int sourceInvSlot, int sourceMenuSlot, String highItemName) {
            this.candInvSlot = candInvSlot;
            this.candMenuSlot = candMenuSlot;
            this.sourceInvSlot = sourceInvSlot;
            this.sourceMenuSlot = sourceMenuSlot;
            this.highItemName = highItemName;
        }
    }

    private State state = State.IDLE;
    private int stateTicks = 0;
    private boolean forceCleanNow = false;
    private boolean forceLockNow = false;
    private boolean forceSortNow = false;
    private final BypassConfig bypassConfig = new BypassConfig();
    private int droppedCount = 0;
    private int lockedSlotsCount = 0;

    private int sourceMenuSlot = -1;
    private Queue<Integer> emptySlotsQueue = new ArrayDeque<>();
    private String lockItemDisplayName = "raw_iron";
    private long lastLockTime = 0L;
    private long lastSortTime = 0L;
    private long lastSacrificeTime = 0L;
    private long lastCleanSortTime = 0L;
    private float originalYaw = 0f;
    private float originalPitch = 0f;
    private boolean hasAdjustedAim = false;
    private Rotation activeDropAim = null;
    private SacrificeTarget pendingSacrifice = null;

    public AutoDropProcess(Baritone baritone) {
        super(baritone);
    }

    /**
     * Wyszukuje leżący na ziemi cenniejszy surowiec (np. diamenty), którego gracz nie może podnieść
     * ze względu na brak miejsca w ekwipunku (np. diamenty 64/64), oraz znajduje slot z niższym surowcem
     * (np. żelazo, redstone, węgiel), który można poświęcić, by umieścić w nim 1 sztukę cenniejszego surowca.
     */
    public static SacrificeTarget findSacrificeTarget(LocalPlayer player, Level world) {
        if (player == null || world == null) return null;

        AABB pickupBox = player.getBoundingBox().inflate(2.2D);
        List<ItemEntity> nearby = world.getEntitiesOfClass(ItemEntity.class, pickupBox);
        for (ItemEntity entity : nearby) {
            if (!entity.isAlive()) continue;
            ItemStack dropStack = entity.getItem();
            if (dropStack.isEmpty()) continue;

            int dropPriority = InventoryCleaner.getResourcePriority(dropStack);
            if (dropPriority < 10) continue; // tylko wartościowe rudy/surowce (żelazo, złoto, diamenty itp.)

            if (HomeProcess.canInventoryAcceptItem(player, dropStack)) {
                continue; // Gracz może podnieść ten surowiec bezpośrednio – brak potrzeby poświęcania
            }

            // Gracz NIE MOŻE podnieść surowca – szukamy w ekwipunku slotu z surowcem o niższym priorytecie
            int bestCandSlot = -1;
            int lowestPriority = dropPriority; // ściśle niższy priorytet

            var inv = player.getInventory();
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty()) continue;
                int prio = InventoryCleaner.getResourcePriority(stack);
                if (prio < lowestPriority) {
                    lowestPriority = prio;
                    bestCandSlot = i;
                }
            }

            if (bestCandSlot != -1) {
                // Znaleziono slot o niższej wartości! Sprawdzamy czy w ekwipunku jest stos cenniejszego surowca (> 1 szt.)
                int sourceSlot = -1;
                for (int i = 0; i < 36; i++) {
                    if (i == bestCandSlot) continue;
                    ItemStack s = inv.getItem(i);
                    if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, dropStack) && s.getCount() > 1) {
                        sourceSlot = i;
                        break;
                    }
                }

                int candMenuSlot = InventoryCleaner.invToMenuSlot(bestCandSlot);
                int sourceMenuSlot = sourceSlot != -1 ? InventoryCleaner.invToMenuSlot(sourceSlot) : -1;
                String name = BuiltInRegistries.ITEM.getKey(dropStack.getItem()).getPath();
                return new SacrificeTarget(bestCandSlot, candMenuSlot, sourceSlot, sourceMenuSlot, name);
            }
        }
        return null;
    }

    public boolean isBotWorking() {
        return baritone.getMineProcess().isActive()
                || baritone.getFarmProcess().isActive()
                || baritone.getBypassProcess().isActive()
                || baritone.getExploreProcess().isActive()
                || baritone.getFollowProcess().isActive()
                || baritone.getGetToBlockProcess().isActive();
    }

    /**
     * Wyszukuje najbezpieczniejszy i najbardziej otwarty kierunek (kąt yaw i pitch) do wyrzucenia śmieci.
     * Zapobiega wyrzucaniu przedmiotów wprost w ścianę przed sobą (np. podczas kopania w ścianie/tunelu),
     * dzięki czemu wyrzucone przedmioty nie odbijają się od ściany i nie wpadają z powrotem do ekwipunku gracza.
     */
    public static Rotation findBestDropRotation(LocalPlayer player, Level world) {
        if (player == null || world == null) {
            return new Rotation(0f, 0f);
        }
        Vec3 eye = player.getEyePosition(1.0F);
        float currentYaw = player.getYRot();
        double maxDist = 5.0;

        // 1. Sprawdzamy przestrzeń wprost przed graczem (aktualny yaw, pitch 0 stopni - wzdłuż poziomu oczu)
        Rotation forwardRot = new Rotation(currentYaw, 0.0f);
        Vec3 forwardDir = RotationUtils.calcLookDirectionFromRotation(forwardRot);
        BlockHitResult forwardHit = world.clip(new ClipContext(eye, eye.add(forwardDir.scale(maxDist)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        double forwardDist = forwardHit.getType() == HitResult.Type.MISS ? maxDist : eye.distanceTo(forwardHit.getLocation());

        // Jeśli przed graczem jest co najmniej 3.0 bloki czystego powietrza bez ściany, można bezpiecznie wyrzucać w przód
        if (forwardDist >= 3.0) {
            return forwardRot;
        }

        // 2. Przed graczem jest ściana (odległość < 3.0)! Szukamy otwartego kierunku (np. otwarty tunel za graczem)
        float[] candidateOffsets = new float[]{ 180.0f, 135.0f, -135.0f, 90.0f, -90.0f, 45.0f, -45.0f };
        float bestYaw = currentYaw + 180.0f;
        float bestPitch = 0.0f;
        double bestDist = -1.0;

        for (float offset : candidateOffsets) {
            float testYaw = currentYaw + offset;
            Rotation testRot = new Rotation(testYaw, 0.0f);
            Vec3 dir = RotationUtils.calcLookDirectionFromRotation(testRot);
            BlockHitResult hit = world.clip(new ClipContext(eye, eye.add(dir.scale(maxDist)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            double dist = hit.getType() == HitResult.Type.MISS ? maxDist : eye.distanceTo(hit.getLocation());

            if (dist > bestDist) {
                bestDist = dist;
                bestYaw = testYaw;
                bestPitch = 0.0f;
            }
        }

        if (bestDist < 1.5) {
            bestYaw = currentYaw + 180.0f;
            bestPitch = 0.0f;
        }

        return new Rotation(bestYaw, bestPitch);
    }

    private void ensureSafeDropAim() {
        if (ctx.player() == null || ctx.world() == null) return;
        if (!hasAdjustedAim) {
            originalYaw = ctx.player().getYRot();
            originalPitch = ctx.player().getXRot();
            activeDropAim = findBestDropRotation(ctx.player(), ctx.world());
            hasAdjustedAim = true;
        }
        if (activeDropAim != null) {
            ctx.player().setYRot(activeDropAim.getYaw());
            ctx.player().setXRot(activeDropAim.getPitch());
            ctx.player().yRotO = activeDropAim.getYaw();
            ctx.player().xRotO = activeDropAim.getPitch();
            try {
                baritone.getLookBehavior().updateTarget(activeDropAim, true);
            } catch (Throwable ignored) {}
        }
    }

    private void restoreOriginalAim() {
        if (hasAdjustedAim && ctx.player() != null) {
            ctx.player().setYRot(originalYaw);
            ctx.player().setXRot(originalPitch);
            ctx.player().yRotO = originalYaw;
            ctx.player().xRotO = originalPitch;
            try {
                baritone.getLookBehavior().updateTarget(new Rotation(originalYaw, originalPitch), true);
            } catch (Throwable ignored) {}
            hasAdjustedAim = false;
            activeDropAim = null;
        }
    }

    @Override
    public boolean isActive() {
        if (ctx.player() == null || ctx.world() == null) {
            return false;
        }

        // Nie wyrzucaj itemów gdy otwarty jest zewnętrzny kontener (skrzynka, piec)
        if (ctx.player().containerMenu != ctx.player().inventoryMenu) {
            return false;
        }

        // Wymuszone czyszczenie, blokowanie lub segregowanie (np. komendy #clean, #lock, #sort)
        if (forceCleanNow || forceLockNow || forceSortNow || pendingSacrifice != null) {
            return true;
        }

        // ZASADA: AutoDrop, AutoSort i AutoLock mają działać TYLKO wtedy, gdy bot ma aktywne zadanie
        // (np. #mine, #farm, #bypass, #build, pathfinding itp.) - a NIE wtedy, gdy gracz gra samemu!
        if (!isBotWorking()) {
            if (state != State.IDLE) {
                onLostControl();
            }
            return false;
        }

        if (!Baritone.settings().autoDropTrash.value && !Baritone.settings().autoLockResource.value && !Baritone.settings().autoSortInventory.value) {
            if (state != State.IDLE) {
                onLostControl();
            }
            return false;
        }

        // Podczas budowania lub czyszczenia terenu (#build, #cleararea, #sel cleararea)
        // NIGDY nie aktywujemy czyszczenia ekwipunku ani wyrzucania przedmiotów!
        if (baritone.getBuilderProcess().isActive()) {
            if (state != State.IDLE) {
                onLostControl();
            }
            return false;
        }

        // Jeśli proces jest już w trakcie czyszczenia/blokowania/segregowania, kontynuuje aż zakończy całą sekwencję
        if (state != State.IDLE) {
            return true;
        }

        Set<String> targets = InventoryCleaner.collectResourceTargets(ctx.player(), baritone.getBypassProcess());
        int freeSlots = InventoryCleaner.getFreeSlots(ctx.player());
        int threshold = Baritone.settings().autoDropThreshold.value;

        long now = System.currentTimeMillis();
        long cleanSortCooldown = Baritone.settings().cleanSortCooldownMs.value;
        boolean onCleanSortCooldown = (now - lastCleanSortTime) < cleanSortCooldown;

        // 1. Warunek automatycznego wyrzucania śmieci (oraz następującego po nim segregowania)
        // Zgodnie z wymaganiem: może przejść w wywalanie i sortowanie MAKSYMALNIE raz na 2 minuty!
        if (!onCleanSortCooldown && Baritone.settings().autoDropTrash.value && freeSlots <= threshold) {
            if (InventoryCleaner.hasTrash(ctx.player(), bypassConfig, targets)) {
                return true;
            }
        }

        // 2. Warunek poświęcenia niższego surowca dla cenniejszego surowca leżącego pod nogami (np. diamenty 64/64)
        if (now - lastSacrificeTime > 3000L && findSacrificeTarget(ctx.player(), ctx.world()) != null) {
            return true;
        }

        // 3. Warunek segregowania ekwipunku (tylko gdy włączone autoSortInventory i minął cooldown 2 minut)
        if (!onCleanSortCooldown && Baritone.settings().autoSortInventory.value && freeSlots <= threshold) {
            if (InventorySorter.hasAnythingToSort(ctx.player())) {
                return true;
            }
        }

        // UWAGA: Podczas farmienia (#farm) lub budowania/czyszczenia (#build, #cleararea) NIGDY nie blokujemy slotów — ekwipunek jest zajmowany plonami/blokami,
        // a blokowanie nasionami powoduje wyrzucanie surowców oraz MultiActionsC na GrimAC.
        if (Baritone.settings().autoLockResource.value && freeSlots > 0
                && !baritone.getFarmProcess().isActive()
                && !baritone.getBuilderProcess().isActive()) {
            if (now - lastLockTime > 5000L) {
                String lockItem = Baritone.settings().autoLockItemName.value;
                int sourceSlot = InventoryCleaner.findBestLockSourceSlot(ctx.player(), lockItem, targets);
                if (sourceSlot != -1) {
                    int count = ctx.player().getInventory().getItem(sourceSlot).getCount();
                    if (count >= 3) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (ctx.player() == null || ctx.world() == null) {
            onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        Set<String> targets = InventoryCleaner.collectResourceTargets(ctx.player(), baritone.getBypassProcess());

        switch (state) {
            case IDLE -> {
                droppedCount = 0;
                lockedSlotsCount = 0;
                stateTicks = 0;
                sourceMenuSlot = -1;
                emptySlotsQueue.clear();

                if (pendingSacrifice == null) {
                    pendingSacrifice = findSacrificeTarget(ctx.player(), ctx.world());
                }

                boolean hasTrash = InventoryCleaner.hasTrash(ctx.player(), bypassConfig, targets);
                boolean canSort = (forceSortNow || Baritone.settings().autoSortInventory.value) && InventorySorter.hasAnythingToSort(ctx.player());
                if (pendingSacrifice != null) {
                    logDirect(String.format("§a[AutoDrop] Zatrzymuję bota i zwalniam 1 slot dla cenniejszego surowca (§b%s§a)...", pendingSacrifice.highItemName));
                } else if (forceSortNow && canSort) {
                    logDirect("§b[AutoSort] Zatrzymuję bota i segreguję ekwipunek (GrimAC safe)...");
                } else if (forceCleanNow) {
                    logDirect("§e[AutoDrop] Zatrzymuję bota, czyszczę śmieci (w bezpieczną stronę) i segreguję ekwipunek (GrimAC safe)...");
                } else if (hasTrash) {
                    logDirect("§e[AutoDrop] Zatrzymuję bota, czyszczę śmieci (w bezpieczną stronę) i segreguję ekwipunek (cooldown 2 min)...");
                } else if (canSort) {
                    logDirect("§b[AutoSort] Zatrzymuję bota i segreguję ekwipunek (cooldown 2 min)...");
                } else if (forceLockNow || Baritone.settings().autoLockResource.value) {
                    logDirect("§e[AutoDrop] Zatrzymuję bota i blokuję puste sloty surowcem (GrimAC safe)...");
                }
                state = State.STOPPING;
                return enforceDeadStop();
            }
            case STOPPING -> {
                enforceDeadStop();
                state = State.WARMUP;
                stateTicks = 0;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case WARMUP -> {
                enforceDeadStop();
                stateTicks++;
                var motion = ctx.player().getDeltaMovement();
                boolean movingHorizontally = Math.abs(motion.x) > 0.001 || Math.abs(motion.z) > 0.001;
                // Czekamy co najmniej 6 ticków (300ms) aby:
                // 1. Pęd gracza wyhamował naturalnie i serwer odebrał pakiet z prędkością ~0
                // 2. Klawisze ruchu Minecrafta zostały zwolnione (input=false w pakiecie serwera)
                // GrimAC MultiActionsC: flagi występują gdy windowClick przybywa z input=true
                if ((!movingHorizontally && stateTicks >= 6) || stateTicks >= 20) {
                    if (pendingSacrifice != null) {
                        ensureSafeDropAim();
                        state = State.AIMING;
                        stateTicks = 0;
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                    boolean hasTrash = InventoryCleaner.hasTrash(ctx.player(), bypassConfig, targets);
                    boolean canSort = (forceSortNow || Baritone.settings().autoSortInventory.value) && InventorySorter.hasAnythingToSort(ctx.player());
                    if (forceSortNow && canSort) {
                        state = State.SORTING;
                        InventorySorter.start();
                    } else if (forceCleanNow || hasTrash) {
                        if (hasTrash) {
                            ensureSafeDropAim();
                            state = State.AIMING;
                        } else if (canSort) {
                            state = State.SORTING;
                            InventorySorter.start();
                        } else if ((Baritone.settings().autoLockResource.value || forceLockNow)
                                && InventoryCleaner.canLockSlots(ctx.player(), Baritone.settings().autoLockItemName.value, targets)) {
                            state = State.LOCK_START;
                        } else {
                            state = State.COOLDOWN;
                        }
                    } else if (forceLockNow) {
                        state = State.LOCK_START;
                    } else if (canSort) {
                        state = State.SORTING;
                        InventorySorter.start();
                    } else if (Baritone.settings().autoLockResource.value
                            && InventoryCleaner.canLockSlots(ctx.player(), Baritone.settings().autoLockItemName.value, targets)) {
                        state = State.LOCK_START;
                    } else {
                        state = State.COOLDOWN;
                    }
                    stateTicks = 0;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case AIMING -> {
                enforceDeadStop();
                ensureSafeDropAim();
                stateTicks++;
                // Czekamy 3 ticki w bezruchu, aby klient zsynchronizował nową rotację (skierowaną w otwarty tunel)
                // z serwerem i GrimAC przed wysłaniem pakietów THROW w oknie ekwipunku
                if (stateTicks >= 3) {
                    if (pendingSacrifice != null) {
                        state = State.SACRIFICE_DROP;
                    } else {
                        state = State.DROPPING;
                    }
                    stateTicks = 0;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case DROPPING -> {
                enforceDeadStop();

                // Sprawdzamy czy są jeszcze jakiekolwiek śmieci do wyrzucenia
                if (!InventoryCleaner.hasTrash(ctx.player(), bypassConfig, targets)) {
                    // Wszystkie śmieci usunięte! Przywracamy pierwotny wzrok gracza
                    restoreOriginalAim();

                    // Sprawdzamy czy użytkownik chce segregować ekwipunek
                    if (Baritone.settings().autoSortInventory.value && InventorySorter.hasAnythingToSort(ctx.player())) {
                        state = State.SORTING;
                        InventorySorter.start();
                        stateTicks = 0;
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    } else if ((Baritone.settings().autoLockResource.value || forceLockNow)
                            && InventoryCleaner.canLockSlots(ctx.player(), Baritone.settings().autoLockItemName.value, targets)) {
                        state = State.LOCK_START;
                        stateTicks = 0;
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    } else {
                        state = State.COOLDOWN;
                        stateTicks = 0;
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                }

                // Przed wyrzuceniem upewnij się, że patrzymy w bezpieczną, wolną przestrzeń (np. tunel za graczem, a NIE w ścianę przed sobą!)
                ensureSafeDropAim();

                // Wyrzucamy kolejny stack (z opóźnieniem GrimAC-safe)
                boolean dropped = InventoryCleaner.dropNextTrash(ctx, bypassConfig, targets);
                if (dropped) {
                    droppedCount++;
                }

                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case SORTING -> {
                enforceDeadStop();

                // Wykonujemy bezpieczny krok segregowania ekwipunku
                boolean stillSorting = InventorySorter.tick(ctx);
                if (!stillSorting) {
                    // Segregowanie zakończone sukcesem!
                    lastCleanSortTime = System.currentTimeMillis();
                    lastSortTime = lastCleanSortTime;
                    forceSortNow = false;
                    if ((Baritone.settings().autoLockResource.value || forceLockNow)
                            && InventoryCleaner.canLockSlots(ctx.player(), Baritone.settings().autoLockItemName.value, targets)) {
                        state = State.LOCK_START;
                        stateTicks = 0;
                    } else {
                        state = State.COOLDOWN;
                        stateTicks = 0;
                    }
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case SACRIFICE_DROP -> {
                enforceDeadStop();
                ensureSafeDropAim();
                stateTicks++;
                int delayTicks = Math.max(1, Baritone.settings().autoSortDelayTicks.value);
                if (stateTicks >= delayTicks) {
                    stateTicks = 0;
                    if (pendingSacrifice != null) {
                        // Wyrzucamy cały stos niższego surowca (button 1 = throw all), co natychmiast uwalnia slot
                        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, pendingSacrifice.candMenuSlot, 1, ClickType.THROW, ctx.player());
                        restoreOriginalAim();
                        if (pendingSacrifice.sourceMenuSlot != -1) {
                            state = State.SACRIFICE_PICKUP;
                        } else {
                            // Gracz nie miał jeszcze w ekwipunku tego surowca – slot jest teraz wolny i podniesie leżący drop!
                            logDirect(String.format("§a[AutoDrop] Zwolniono 1 slot z niższego surowca na cenniejszy leżący surowiec (§b%s§a)!", pendingSacrifice.highItemName));
                            lastSacrificeTime = System.currentTimeMillis();
                            pendingSacrifice = null;
                            state = State.COOLDOWN;
                        }
                    } else {
                        restoreOriginalAim();
                        state = State.COOLDOWN;
                    }
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case SACRIFICE_PICKUP -> {
                enforceDeadStop();
                stateTicks++;
                int delayTicks = Math.max(1, Baritone.settings().autoSortDelayTicks.value);
                if (stateTicks >= delayTicks) {
                    stateTicks = 0;
                    if (pendingSacrifice != null && pendingSacrifice.sourceMenuSlot != -1) {
                        // Podnosimy na kursor pełny stack cenniejszego surowca (np. 64 diamenty)
                        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, pendingSacrifice.sourceMenuSlot, 0, ClickType.PICKUP, ctx.player());
                        state = State.SACRIFICE_PLACE_ONE;
                    } else {
                        state = State.COOLDOWN;
                    }
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case SACRIFICE_PLACE_ONE -> {
                enforceDeadStop();
                stateTicks++;
                int delayTicks = Math.max(1, Baritone.settings().autoSortDelayTicks.value);
                if (stateTicks >= delayTicks) {
                    stateTicks = 0;
                    if (pendingSacrifice != null) {
                        // Prawy klik (button 1) w pusty slot umieszcza DOKŁADNIE 1 sztukę surowca z kursora!
                        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, pendingSacrifice.candMenuSlot, 1, ClickType.PICKUP, ctx.player());
                        state = State.SACRIFICE_RESTORE;
                    } else {
                        state = State.COOLDOWN;
                    }
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case SACRIFICE_RESTORE -> {
                enforceDeadStop();
                stateTicks++;
                int delayTicks = Math.max(1, Baritone.settings().autoSortDelayTicks.value);
                if (stateTicks >= delayTicks) {
                    stateTicks = 0;
                    if (pendingSacrifice != null && pendingSacrifice.sourceMenuSlot != -1) {
                        // Zwracamy resztę (63 sztuki) do slotu źródłowego
                        if (!ctx.player().containerMenu.getCarried().isEmpty()) {
                            ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, pendingSacrifice.sourceMenuSlot, 0, ClickType.PICKUP, ctx.player());
                        }
                        ctx.playerController().syncHeldItem();
                        logDirect(String.format("§a[AutoDrop] Zwolniono 1 slot z niższego surowca i zarezerwowano 1 sztukę cenniejszego surowca (§b%s§a)!", pendingSacrifice.highItemName));
                        lastSacrificeTime = System.currentTimeMillis();
                        pendingSacrifice = null;
                    }
                    state = State.COOLDOWN;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case LOCK_START -> {
                enforceDeadStop();
                stateTicks++;
                // Bufor 2 ticków (100ms) przed pierwszym klikiem ekwipunku —
                // serwer musi odebrać pakiet z input=false zanim dostanie ServerboundContainerClickPacket
                if (stateTicks < 2) {
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                String lockItem = Baritone.settings().autoLockItemName.value;
                int sourceInvSlot = InventoryCleaner.findBestLockSourceSlot(ctx.player(), lockItem, targets);
                List<Integer> emptySlots = InventoryCleaner.collectEmptyMenuSlots(ctx.player());

                if (sourceInvSlot == -1 || emptySlots.isEmpty()) {
                    state = State.COOLDOWN;
                    stateTicks = 0;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                sourceMenuSlot = InventoryCleaner.invToMenuSlot(sourceInvSlot);
                emptySlotsQueue = new ArrayDeque<>(emptySlots);
                lockedSlotsCount = 0;
                ItemStack sourceStack = ctx.player().getInventory().getItem(sourceInvSlot);
                lockItemDisplayName = BuiltInRegistries.ITEM.getKey(sourceStack.getItem()).getPath();

                // Podnosimy cały stack surowca na kursor (ClickType.PICKUP, button 0)
                ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, sourceMenuSlot, 0, ClickType.PICKUP, ctx.player());

                state = State.LOCK_DISTRIBUTE;
                stateTicks = 0;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case LOCK_DISTRIBUTE -> {
                enforceDeadStop();

                ItemStack carried = ctx.player().containerMenu.getCarried();
                if (carried.isEmpty()) {
                    state = State.LOCK_RETURN;
                    stateTicks = 0;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                if (!emptySlotsQueue.isEmpty() && carried.getCount() > 1) {
                    stateTicks++;
                    int delayTicks = Math.max(1, Baritone.settings().autoLockDelayTicks.value);
                    if (stateTicks >= delayTicks) {
                        stateTicks = 0;
                        int targetMenuSlot = emptySlotsQueue.poll();
                        // Prawy klik (button 1) kładzie dokładnie 1 sztukę do pustego slotu
                        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, targetMenuSlot, 1, ClickType.PICKUP, ctx.player());
                        lockedSlotsCount++;
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                } else {
                    state = State.LOCK_RETURN;
                    stateTicks = 0;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
            case LOCK_RETURN -> {
                enforceDeadStop();

                // Zwracamy resztę surowca z kursora do slotu źródłowego
                ItemStack carried = ctx.player().containerMenu.getCarried();
                if (!carried.isEmpty() && sourceMenuSlot != -1) {
                    ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, sourceMenuSlot, 0, ClickType.PICKUP, ctx.player());
                }

                ctx.playerController().syncHeldItem();
                lastLockTime = System.currentTimeMillis();

                if (lockedSlotsCount > 0) {
                    logDirect(String.format("§a[AutoDrop] Zablokowano %d pustych slotów surowcem (§e%s§a) - tylko ten surowiec będzie zbierany!",
                            lockedSlotsCount, lockItemDisplayName));
                }

                state = State.COOLDOWN;
                stateTicks = 0;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case COOLDOWN -> {
                enforceDeadStop();
                restoreOriginalAim();
                stateTicks++;
                if (stateTicks >= 4) {
                    if (droppedCount > 0) {
                        logDirect(String.format("§a[AutoDrop] Wyrzucono %d stacków śmieci (pozostawiono surowce, kilofy i jedzenie).", droppedCount));
                    }
                    lastCleanSortTime = System.currentTimeMillis();
                    lastSortTime = lastCleanSortTime;
                    state = State.IDLE;
                    forceCleanNow = false;
                    forceLockNow = false;
                    forceSortNow = false;
                    stateTicks = 0;
                    sourceMenuSlot = -1;
                    emptySlotsQueue.clear();
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            default -> {
                state = State.IDLE;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
    }

    /**
     * Wymusza absolutny bezruch (Dead Stop):
     * Zeruje wszystkie klawisze Minecrafta, Baritone, prędkość poziomą oraz sprint.
     * Zapewnia input=false we wszystkich wysyłanych pakietach.
     */
    private PathingCommand enforceDeadStop() {
        // 1. Baritone input override
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);

        // 2. Przerwij ewentualne kopanie bloku i zresetuj rotacje
        baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
        RotationEngine.reset();

        // 3. Wstrzymaj pathing
        baritone.getPathingBehavior().requestPause();

        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    @Override
    public void onLostControl() {
        restoreOriginalAim();
        if (ctx.player() != null && ctx.player().containerMenu != null) {
            try {
                ItemStack carried = ctx.player().containerMenu.getCarried();
                if (!carried.isEmpty() && sourceMenuSlot != -1) {
                    ctx.playerController().windowClick(ctx.player().containerMenu.containerId, sourceMenuSlot, 0, ClickType.PICKUP, ctx.player());
                }
            } catch (Throwable ignored) {}
        }
        InventorySorter.stop();
        state = State.IDLE;
        forceCleanNow = false;
        forceLockNow = false;
        forceSortNow = false;
        droppedCount = 0;
        lockedSlotsCount = 0;
        stateTicks = 0;
        sourceMenuSlot = -1;
        emptySlotsQueue.clear();
        pendingSacrifice = null;
    }

    @Override
    public double priority() {
        return 5.8D;
    }

    @Override
    public boolean isTemporary() {
        return true; // Nie anuluje zadań w tle (np. #mine diamond czy #bypass)
    }

    @Override
    public String displayName0() {
        if (state == State.AIMING) {
            return "AutoDrop (bezpieczny kąt)";
        }
        if (state == State.SORTING) {
            return "AutoSort (segregowanie)";
        }
        if (state == State.SACRIFICE_DROP || state == State.SACRIFICE_PICKUP || state == State.SACRIFICE_PLACE_ONE || state == State.SACRIFICE_RESTORE) {
            return "AutoDrop (rezerwacja slotu dla " + (pendingSacrifice != null ? pendingSacrifice.highItemName : "surowca") + ")";
        }
        if (state == State.LOCK_START || state == State.LOCK_DISTRIBUTE || state == State.LOCK_RETURN) {
            return "AutoDrop (blokowanie " + lockItemDisplayName + ")";
        }
        return isCleaning() ? "AutoDrop (czyszczenie)" : "AutoDrop";
    }

    /**
     * Wymusza poświęcenie 1 slotu niższego surowca i rezerwację 1 sztuki cenniejszego surowca.
     */
    public void sacrificeForTarget(SacrificeTarget target) {
        this.pendingSacrifice = target;
        this.state = State.IDLE;
    }

    /**
     * Wymusza wyczyszczenie ekwipunku ze śmieci (np. z komendy #clean).
     */
    public void cleanNow() {
        cleanNow(true);
    }

    public void cleanNow(boolean force) {
        if (!force && isOnCleanSortCooldown()) {
            return;
        }
        this.forceCleanNow = true;
        this.state = State.IDLE;
    }

    /**
     * Wymusza posegregowanie ekwipunku (np. z komendy #sort).
     */
    public void sortNow() {
        sortNow(true);
    }

    public void sortNow(boolean force) {
        if (!force && isOnCleanSortCooldown()) {
            return;
        }
        this.forceSortNow = true;
        this.state = State.IDLE;
    }

    public boolean isOnCleanSortCooldown() {
        long cooldownMs = Baritone.settings().cleanSortCooldownMs.value;
        return (System.currentTimeMillis() - lastCleanSortTime) < cooldownMs;
    }

    public long getRemainingCooldownMs() {
        long cooldownMs = Baritone.settings().cleanSortCooldownMs.value;
        long elapsed = System.currentTimeMillis() - lastCleanSortTime;
        return Math.max(0L, cooldownMs - elapsed);
    }

    public long getLastCleanSortTime() {
        return lastCleanSortTime;
    }

    /**
     * Wymusza natychmiastowe zablokowanie pustych slotów surowcem (np. z komendy #lock lub #clean lock).
     */
    public void lockNow() {
        this.forceLockNow = true;
        this.state = State.IDLE;
    }

    public boolean isCleaning() {
        return state != State.IDLE;
    }
}

