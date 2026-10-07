package baritone.process;

import baritone.Baritone;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Helper;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.hud.AiActionLogger;
import baritone.bypass.RotationEngine;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.builder.SmoothLookHelper;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.Locale;

/**
 * MobDefenseProcess — Automatyczna obrona bota i bicie potworów w pobliżu.
 *
 * Spełnia wymagania:
 * 1. ZATRZYMUJE TO CO ROBIŁ BOT: Jako proces tymczasowy (isTemporary = true) z priorytetem 6.2,
 *    automatycznie pauzuje aktualnie wykonywany proces (#mine, #goto, #follow itp.),
 *    zeruje klawisze ruchu i niszczenia bloków.
 * 2. ZABIJA MOBA: Wykrywa wrogie potwory w promieniu obrony (domyślnie 4.2 bloku),
 *    płynnie celuje w moba (GrimAC-safe), podchodzi w zasięg ataku i uderza z pełnym cooldownem (100% damage).
 * 3. WYBIERA BROŃ LUB KILOF: Przełącza na najlepszą broń z paska:
 *    Miecz > Topór > Mace > Kilof > Łopata > Dłoń.
 *    Jeśli gracz kopie i ma tylko kilof, bije moba kilofem!
 * 4. PŁYNNE WZNOWIENIE: Po zabiciu moba (i braku innych wrogów w pobliżu)
 *    przywraca pierwotnie trzymany slot i oddaje kontrolę poprzedniemu zadaniu,
 *    które natychmiast kontynuuje pracę dokładnie w tym samym miejscu.
 */
public class MobDefenseProcess extends BaritoneProcessHelper implements IBaritoneProcess {

    public enum State {
        IDLE,
        ENGAGING,      // Celowanie, zbliżanie się lub oczekiwanie na pełny cooldown
        POST_COMBAT,   // Po zlikwidowaniu celu: krótki bufor bezpieczeństwa
        RESTORING      // Przywrócenie pierwotnego slotu na pasku
    }

    private State state = State.IDLE;
    private LivingEntity currentTarget = null;
    private int originalSlot = -1;
    private int postCombatTicks = 0;
    private long lastLogTime = 0L;

    public MobDefenseProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        if (ctx.player() == null || ctx.world() == null) {
            return false;
        }

        if (!Baritone.settings().mobDefense.value) {
            if (state != State.IDLE) {
                stopCombat();
            }
            return false;
        }

        LocalPlayer player = ctx.player();
        if (player.isCreative() || player.isSpectator() || player.isDeadOrDying()) {
            if (state != State.IDLE) {
                stopCombat();
            }
            return false;
        }

        // Nie atakuj gdy otwarty jest zewnętrzny kontener (skrzynka, piec)
        if (player.containerMenu != player.inventoryMenu) {
            return false;
        }

        // Jeśli proces jest w trakcie walki lub przywracania slotu, musi zachować kontrolę
        if (state != State.IDLE) {
            return true;
        }

        double range = Baritone.settings().mobDefenseRange.value;
        LivingEntity target = findBestTarget(player, range);
        return target != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (ctx.player() == null || ctx.world() == null || !Baritone.settings().mobDefense.value) {
            stopCombat();
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        LocalPlayer player = ctx.player();

        // 1. ZATRZYMAJ KOPANIE I POPRZEDNI RUCH BOTA
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
        baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
        if (player.isSprinting()) {
            player.setSprinting(false);
        }

        double range = Baritone.settings().mobDefenseRange.value;

        // Auto-logout na Creepera (jeśli włączone w settingsach)
        if (Baritone.settings().mobDefenseLogoutOnCreeper.value) {
            Creeper creeper = findNearbyCreeper(player, 6.5D);
            if (creeper != null) {
                triggerCreeperLogout(creeper);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        // 2. Walidacja lub wybór celu
        if (currentTarget == null || !isValidTarget(player, currentTarget, range + 1.5D)) {
            currentTarget = findBestTarget(player, range);
        }

        // Jeśli brak wrogów w zasięgu:
        if (currentTarget == null) {
            if (state == State.ENGAGING) {
                state = State.POST_COMBAT;
                postCombatTicks = 0;
            }

            if (state == State.POST_COMBAT) {
                postCombatTicks++;
                if (postCombatTicks >= 5) { // 5 ticków bufora (0.25s) po walce
                    state = State.RESTORING;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            if (state == State.RESTORING) {
                restoreOriginalSlot(player);
                stopCombat();
                return new PathingCommand(null, PathingCommandType.DEFER);
            }

            stopCombat();
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        // Mamy aktywny cel!
        if (state == State.IDLE || state == State.POST_COMBAT || state == State.RESTORING) {
            state = State.ENGAGING;
            if (originalSlot == -1) {
                originalSlot = player.getInventory().selected;
            }
            String mobName = currentTarget.getType().getDescription().getString();
            AiActionLogger.log("DEFEND", String.format("⚔️ Obrona: wykryto wrogiego moba %s! Zatrzymywanie zadania...", mobName));
        }

        // 3. Wybór broni: Miecz > Topór > Mace > Kilof > Łopata > Dłoń
        if (Baritone.settings().mobDefenseSwitchWeapon.value) {
            int bestSlot = findBestWeaponSlot(player);
            if (bestSlot != -1 && player.getInventory().selected != bestSlot) {
                player.getInventory().selected = bestSlot;
                ctx.playerController().syncHeldItem();
                // Po zmianie slotu Minecraft resetuje attack cooldown do 0; dajemy tick na załadowanie
                aimAtTarget(player, currentTarget);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        // 4. Celowanie w moba
        aimAtTarget(player, currentTarget);

        // 5. Pozycjonowanie i podejście (zbliżenie się do moba jeśli jest poza zasięgiem ręki)
        double eyeToBoxDist = getEyeDistanceToBox(player, currentTarget);
        boolean lookingAtMob = isAimingAtTarget(player, currentTarget);

        if (eyeToBoxDist > 2.6D && eyeToBoxDist <= range + 1.2D) {
            // Podejdź do moba aby wejść w zasięg ataku (2.0 - 2.5 bloku)
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        } else if (eyeToBoxDist < 1.2D) {
            // Mob wchodzi w gracza - zrób krok w tył dla optymalnego dystansu
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
        }

        // 6. Atak na potwora (pełny cooldown broni dla 100% damage i pełna zgodność z GrimAC Reach + Raytrace)
        float attackCooldown = player.getAttackStrengthScale(0.5f);
        if (eyeToBoxDist <= 3.0D && lookingAtMob && attackCooldown >= 0.92f) {
            // Wykonaj atak przez playerController
            ctx.playerController().attack(player, currentTarget);

            long now = System.currentTimeMillis();
            if (now - lastLogTime > 1200L) {
                lastLogTime = now;
                String mobName = currentTarget.getType().getDescription().getString();
                ItemStack held = player.getMainHandItem();
                String weaponName = held.isEmpty() ? "pięść" : held.getHoverName().getString();
                AiActionLogger.log("DEFEND", String.format("💥 Uderzono %s (%s) | HP: %.1f", mobName, weaponName, currentTarget.getHealth()));
            }
        }

        // 7. Sprawdź czy cel nie zginął w tym ticku
        if (!currentTarget.isAlive() || currentTarget.isDeadOrDying() || currentTarget.getHealth() <= 0f) {
            String mobName = currentTarget.getType().getDescription().getString();
            AiActionLogger.log("DEFEND", String.format("💀 Pokonano potwora: %s!", mobName));
            currentTarget = null;
            // Sprawdź czy w pobliżu jest kolejny wróg
            LivingEntity nextTarget = findBestTarget(player, range);
            if (nextTarget != null) {
                currentTarget = nextTarget;
                state = State.ENGAGING;
            } else {
                state = State.POST_COMBAT;
                postCombatTicks = 0;
            }
        }

        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private void aimAtTarget(LocalPlayer player, LivingEntity target) {
        Vec3 eyePos = player.getEyePosition();
        // Celuj w klatkę piersiową / głowę moba
        Vec3 targetPoint = new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.75D, target.getZ());
        Rotation aim = RotationUtils.calcRotationFromVec3d(eyePos, targetPoint, ctx.playerRotations());

        // Płynna rotacja przez RotationEngine (GrimAC-safe)
        if (Baritone.settings().smoothRotation.value || Baritone.settings().antiCheatCompat.value) {
            RotationEngine.apply(player, aim, Baritone.settings(), false);
        } else {
            SmoothLookHelper.apply(player, aim, Baritone.settings(), false);
        }
        baritone.getLookBehavior().updateTarget(aim, true);
    }

    private double getEyeDistanceToBox(LocalPlayer player, LivingEntity target) {
        Vec3 eyePos = player.getEyePosition();
        AABB box = target.getBoundingBox();
        double closestX = Mth.clamp(eyePos.x, box.minX, box.maxX);
        double closestY = Mth.clamp(eyePos.y, box.minY, box.maxY);
        double closestZ = Mth.clamp(eyePos.z, box.minZ, box.maxZ);
        return eyePos.distanceTo(new Vec3(closestX, closestY, closestZ));
    }

    private boolean isAimingAtTarget(LocalPlayer player, LivingEntity target) {
        Vec3 eyePos = player.getEyePosition();
        Vec3 lookVec = player.getViewVector(1.0f);
        Vec3 reachVec = eyePos.add(lookVec.scale(4.2D));
        if (target.getBoundingBox().inflate(0.2D).clip(eyePos, reachVec).isPresent()) {
            return true;
        }
        Vec3 targetCenter = target.getBoundingBox().getCenter();
        Rotation aim = RotationUtils.calcRotationFromVec3d(eyePos, targetCenter, ctx.playerRotations());
        float yawDiff = Math.abs(Mth.wrapDegrees(player.getYRot() - aim.getYaw()));
        float pitchDiff = Math.abs(player.getXRot() - aim.getPitch());
        return yawDiff <= 18.0f && pitchDiff <= 18.0f;
    }

    public int findBestWeaponSlot(LocalPlayer player) {
        int bestSlot = -1;
        float bestScore = -1f;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            float score = evaluateWeaponScore(stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }

        // Jeśli na hotbarze nie ma żadnej broni ani narzędzia (wynik <= 5),
        // sprawdź czy w plecaku (sloty 9-35) jest miecz, topór lub kilof i przenieś go na hotbar
        if (bestScore <= 5f) {
            int invWeapon = findBestInventoryWeaponSlot(player);
            if (invWeapon != -1) {
                baritone.getInventoryBehavior().attemptToPutOnHotbar(invWeapon, s -> false);
            }
        }

        return bestSlot;
    }

    private int findBestInventoryWeaponSlot(LocalPlayer player) {
        int bestSlot = -1;
        float bestScore = -1f;

        for (int i = 9; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            float score = evaluateWeaponScore(stack);
            if (score > 10f && score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private float evaluateWeaponScore(ItemStack stack) {
        if (stack.isEmpty()) return 1.0f;
        Item item = stack.getItem();

        // 1. Miecze (najwyższy priorytet: szybki cooldown 1.6s, sweeping damage)
        if (item instanceof SwordItem) {
            if (item == Items.NETHERITE_SWORD) return 100f;
            if (item == Items.DIAMOND_SWORD) return 90f;
            if (item == Items.IRON_SWORD) return 80f;
            if (item == Items.STONE_SWORD) return 70f;
            if (item == Items.GOLDEN_SWORD) return 60f;
            if (item == Items.WOODEN_SWORD) return 60f;
            return 65f;
        }

        // 2. Topory (wysoki burst damage)
        if (item instanceof AxeItem) {
            if (item == Items.NETHERITE_AXE) return 88f;
            if (item == Items.DIAMOND_AXE) return 82f;
            if (item == Items.IRON_AXE) return 76f;
            if (item == Items.STONE_AXE) return 70f;
            if (item == Items.GOLDEN_AXE) return 58f;
            if (item == Items.WOODEN_AXE) return 58f;
            return 65f;
        }

        // 3. Mace (1.21+) / Trident
        if (item == Items.MACE) return 85f;
        if (item == Items.TRIDENT) return 75f;

        // 4. Kilofy (zgodnie z życzeniem użytkownika: bot bije moba kilofem jeśli nie ma miecza)
        if (item instanceof PickaxeItem) {
            if (item == Items.NETHERITE_PICKAXE) return 55f;
            if (item == Items.DIAMOND_PICKAXE) return 50f;
            if (item == Items.IRON_PICKAXE) return 45f;
            if (item == Items.STONE_PICKAXE) return 40f;
            if (item == Items.GOLDEN_PICKAXE) return 35f;
            if (item == Items.WOODEN_PICKAXE) return 35f;
            return 38f;
        }

        // 5. Łopaty
        if (item instanceof ShovelItem) {
            if (item == Items.NETHERITE_SHOVEL) return 25f;
            if (item == Items.DIAMOND_SHOVEL) return 22f;
            if (item == Items.IRON_SHOVEL) return 18f;
            return 15f;
        }

        // Inne przedmioty
        return 2.0f;
    }

    public LivingEntity findBestTarget(LocalPlayer player, double range) {
        return ctx.entitiesStream()
                .filter(e -> e instanceof LivingEntity)
                .map(e -> (LivingEntity) e)
                .filter(e -> isValidTarget(player, e, range))
                .min(Comparator.comparingDouble(e -> getTargetPriority(player, e)))
                .orElse(null);
    }

    public boolean isValidTarget(LocalPlayer player, LivingEntity entity, double maxRange) {
        if (entity == null || !entity.isAlive() || entity.isDeadOrDying() || entity.getHealth() <= 0f) {
            return false;
        }
        if (entity.isSpectator() || entity == player) {
            return false;
        }
        if (entity.distanceToSqr(player) > maxRange * maxRange) {
            return false;
        }
        if (!isHostile(player, entity)) {
            return false;
        }
        return hasLineOfSight(player, entity);
    }

    public boolean isHostile(LocalPlayer player, LivingEntity entity) {
        // Ignoruj oswojone zwierzęta gracza
        if (entity instanceof TamableAnimal tamable && tamable.isTame()) {
            return false;
        }

        // Ignoruj osadników (Villagers)
        if (entity instanceof Villager) {
            return false;
        }

        // Ignoruj innych graczy (obrona bota dotyczy mobów)
        if (entity instanceof Player) {
            return false;
        }

        // Każdy byt, który bezpośrednio atakuje bota (lub uderzył bota)
        if (entity instanceof Mob mob && mob.getTarget() == player) {
            return true;
        }
        if (player.getLastHurtByMob() == entity || entity.getLastHurtByMob() == player) {
            return true;
        }

        // Creeper: zawsze atakuj i zabijaj w trybie obrony!
        if (entity instanceof Creeper) {
            return Baritone.settings().mobDefenseAttackCreepers.value;
        }

        // Wrogie potwory (Zombie, Skeleton, Spider, Witch, Drowned, Slime, Phantom, MagmaCube itp.)
        if (entity instanceof Enemy || entity instanceof Monster) {
            return true;
        }

        // Enderman: atakuj jeśli rozwścieczony, celuje w nas lub jest blisko
        if (entity instanceof EnderMan enderman) {
            return enderman.isCreepy() || enderman.getTarget() == player;
        }

        // Zombified Piglin: atakuj jeśli sprowokowany
        if (entity instanceof ZombifiedPiglin zombified) {
            return zombified.getLastHurtByMob() != null || zombified.getTarget() == player;
        }

        // Neutralne moby: atakuj jeśli rozwścieczone
        if (entity instanceof NeutralMob neutral) {
            return neutral.isAngry() || ((entity instanceof Mob m) && m.getTarget() == player);
        }

        // Piglin / Hoglin w Netherze
        if (entity instanceof Mob m && m.getTarget() == player) {
            return true;
        }

        return false;
    }

    public boolean hasLineOfSight(LocalPlayer player, LivingEntity target) {
        if (player.hasLineOfSight(target)) {
            return true;
        }
        // W bezpośrednim zwarciu (<= 2.5 bloku) nie blokuj ataku przez mikro-krawędzie terenu
        if (target.distanceToSqr(player) <= 6.25D) {
            return true;
        }
        Vec3 eyePos = player.getEyePosition();
        // Sprawdź klatkę piersiową
        Vec3 chest = new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.7D, target.getZ());
        if (isRayClear(eyePos, chest)) return true;
        // Sprawdź środek bounding boxa
        Vec3 center = target.getBoundingBox().getCenter();
        if (isRayClear(eyePos, center)) return true;
        // Sprawdź dół (nogi)
        Vec3 feet = new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.25D, target.getZ());
        return isRayClear(eyePos, feet);
    }

    private boolean isRayClear(Vec3 start, Vec3 end) {
        ClipContext clip = new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.player());
        BlockHitResult result = ctx.world().clip(clip);
        return result == null || result.getType() == HitResult.Type.MISS;
    }

    private double getTargetPriority(LocalPlayer player, LivingEntity entity) {
        double distSq = entity.distanceToSqr(player);
        // Najwyższy priorytet: wybuchający Creeper!
        if (entity instanceof Creeper creeper && (creeper.isIgnited() || creeper.getSwellDir() > 0)) {
            return distSq - 100.0D;
        }
        // Bardzo wysoki priorytet: mob aktywnie atakujący bota
        if (entity instanceof Mob mob && mob.getTarget() == player) {
            return distSq - 50.0D;
        }
        // Creeper w pobliżu
        if (entity instanceof Creeper) {
            return distSq - 25.0D;
        }
        return distSq;
    }

    private Creeper findNearbyCreeper(LocalPlayer player, double range) {
        double maxDistSq = range * range;
        return (Creeper) ctx.entitiesStream()
                .filter(e -> e instanceof Creeper)
                .map(e -> (Creeper) e)
                .filter(LivingEntity::isAlive)
                .filter(e -> e.distanceToSqr(player) <= maxDistSq)
                .findFirst()
                .orElse(null);
    }

    private void triggerCreeperLogout(Creeper creeper) {
        double dist = creeper.distanceTo(ctx.player());
        logDirect(String.format(Locale.ROOT, "§c[MobDefense] WYKRYTO CREEPERA w odległości %.1f kratek! Auto-Logout...", dist));

        baritone.getPathingControlManager().cancelEverything();
        baritone.getInputOverrideHandler().clearAllKeys();

        Component reason = Component.literal(String.format(Locale.ROOT,
                "§c[Baritone Safety] Wykryto Creepera (%.1f kratek)! Auto-Logout.",
                dist));

        if (ctx.player().connection != null && ctx.player().connection.getConnection() != null) {
            ctx.player().connection.getConnection().disconnect(reason);
        }
    }

    private void restoreOriginalSlot(LocalPlayer player) {
        if (originalSlot >= 0 && originalSlot < 9) {
            if (player.getInventory().selected != originalSlot) {
                player.getInventory().selected = originalSlot;
                ctx.playerController().syncHeldItem();
            }
        }
        originalSlot = -1;
    }

    public void stopCombat() {
        state = State.IDLE;
        currentTarget = null;
        postCombatTicks = 0;
        if (ctx.player() != null && originalSlot != -1) {
            restoreOriginalSlot(ctx.player());
        }
        if (baritone != null && baritone.getInputOverrideHandler() != null) {
            baritone.getInputOverrideHandler().clearAllKeys();
        }
    }

    @Override
    public boolean isTemporary() {
        return true; // KLUCZOWE: nie resetuje procesów w tle (#mine, #goto, #follow itp.)!
    }

    @Override
    public double priority() {
        return 6.2D; // Wyższy priorytet niż AutoEat (6.0), Mine (1.0), Builder (2.0) i inne
    }

    @Override
    public String displayName0() {
        return "defending";
    }

    @Override
    public void onLostControl() {
        stopCombat();
    }

    public LivingEntity getCurrentTarget() {
        return currentTarget;
    }

    public State getState() {
        return state;
    }
}
