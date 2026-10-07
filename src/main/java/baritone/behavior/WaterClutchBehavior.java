package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.PlayerUpdateEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.utils.Helper;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.WaterFluid;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * WaterClutchBehavior — Perfekcyjny i niezawodny MLG Water Clutch.
 *
 * Automatycznie zabezpiecza bota przed obrażeniami od upadku z dowolnej wysokości:
 * 1. Wykrywa niebezpieczny upadek w locie (prędkość w dół, odległość do ziemi >= 3 kratek).
 * 2. Pre-Aim: błyskawicznie i płynnie kieruje wzrok pionowo w dół (Pitch = 90.0F) jeszcze wysoko w powietrzu,
 *    dzięki czemu w momencie wejścia w zasięg celownik jest już idealnie zablokowany na ziemi.
 * 3. Hotbar & Inventory Swap: automatycznie dobiera wiadro z wodą z paska, a jeśli jest w głównym EQ,
 *    błyskawicznie przekłada je do aktywnego slotu.
 * 4. Clutch Placement: stawia wodę na ułamek sekundy przed uderzeniem (w odległości 3.2-3.8 kratek),
 *    całkowicie neutralizując prędkość i fall damage (0 obrażeń).
 * 5. Water Retraction: natychmiast po wylądowaniu zbiera wodę z powrotem do wiadra, nie zostawiając rozlanej wody.
 */
public class WaterClutchBehavior extends Behavior implements Helper {

    private BlockPos clutchedWaterPos = null;
    private int clutchedTicks = 0;
    private boolean clutching = false;
    private double fallStartHeight = 0.0D;

    public WaterClutchBehavior(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (ctx.player() == null || ctx.world() == null) {
            return;
        }

        Player player = ctx.player();

        // 1. Sprawdź czy podnosimy postawioną wcześniej wodę po udanym clutchu
        if (clutchedWaterPos != null) {
            clutchedTicks++;
            if (event.getState() == EventState.POST) {
                handleWaterPickup(player);
            }
            if (clutchedTicks > 30) {
                clutchedWaterPos = null;
                clutching = false;
            }
        }

        if (!Baritone.settings().autoWaterClutch.value && !Baritone.settings().allowWaterBucketFall.value) {
            return;
        }

        // Ignoruj w trybie kreatywnym, widza lub w Netherze
        if (player.isCreative() || player.isSpectator() || player.isFallFlying() || player.isPassenger()) {
            clutching = false;
            return;
        }

        // Nigdy nie clutchuj podczas pracy bota na farmie (#farm)
        if (baritone.getFarmProcess() != null && baritone.getFarmProcess().isActive()) {
            clutching = false;
            return;
        }

        if (ctx.world().dimension() == Level.NETHER) {
            return; // Woda wyparowuje w Netherze
        }

        // Jeśli gracz stoi stabilnie na ziemi lub pływa w wodzie, resetuj stan
        if (player.onGround() || player.isInWater()) {
            clutching = false;
            return;
        }

        // 2. Warunki wykrycia niebezpiecznego upadku
        // W Minecraft obrażenia od upadku powstają WYŁĄCZNIE gdy fallDistance > 3.0F.
        // Zeskok z 1, 2 lub 3 bloków jest całkowicie bezbolesny (0 obrażeń).
        float fallDist = player.fallDistance;
        double motionY = player.getDeltaMovement().y;

        // Jeśli spadamy z małej wysokości lub prędkość w dół jest niewielka — to nie jest upadek
        if (fallDist < 2.5F && motionY > -0.65D) {
            clutching = false;
            return;
        }

        // 3. Sprawdź podłoże pod graczem
        BlockPos groundPos = findGroundBelow(player);
        if (groundPos == null) {
            return;
        }

        BlockState groundState = ctx.world().getBlockState(groundPos);
        // Jeśli podłoże to woda, pajęczyna, slime lub siano — upadek jest już zamortyzowany
        if (isNaturallySafeLanding(groundState)) {
            clutching = false;
            return;
        }

        double distanceToGround = player.getY() - (groundPos.getY() + 1.0D);

        // Jeśli dystans do ziemi jest mały (< 4 kratek), a gracz nie ma dużej wysokości upadku — ignorujemy
        if (fallDist < 3.2F && distanceToGround < 4.0D) {
            clutching = false;
            return;
        }

        // 4. Znajdź wiadro z wodą w ekwipunku
        int bucketSlot = findWaterBucketSlot(player);
        if (bucketSlot == -1) {
            // Brak wiadra z wodą
            clutching = false;
            return;
        }

        clutching = true;
        if (fallStartHeight <= 0.0D) {
            fallStartHeight = player.getY();
        }

        // 5. PRE-AIM (BARDZO WAŻNE DLA GRIMAC):
        // W EventState.PRE (przed sendPosition()) kierujemy wzrok pionowo w dół (Pitch = 89.5F).
        // Dzięki temu pakiet rotacji leci w sendPosition() ZANIM wykonamy placement w POST.
        Rotation downRotation = new Rotation(player.getYRot(), 89.5F);
        baritone.getLookBehavior().updateTarget(downRotation, true);
        player.setXRot(89.5F);

        // Upewnij się, że wiadro z wodą jest na pasku podręcznym (hotbarze)
        int hotbarBucketSlot = ensureBucketInHotbar(player, bucketSlot);
        if (hotbarBucketSlot != -1) {
            player.getInventory().selected = hotbarBucketSlot;
            ctx.playerController().syncHeldItem();
        }

        // 6. EGZEKUCJA WATER CLUTCHA (Postawienie wody tuż przed ziemią)
        // Optymalny zasięg stawiania wiadra to 0.1 - 3.8 kratek nad ziemią
        if (distanceToGround <= 3.8D && distanceToGround >= 0.1D) {
            if (event.getState() == EventState.POST) {
                executeWaterPlacement(player, groundPos, distanceToGround);
            }
        }
    }

    private void executeWaterPlacement(Player player, BlockPos groundPos, double distanceToGround) {
        BlockPos waterTargetPos = groundPos.above();
        BlockState targetState = ctx.world().getBlockState(waterTargetPos);

        // Jeśli woda już tam jest, zapamiętaj pozycję do podniesienia
        if (targetState.getFluidState().getType() instanceof WaterFluid) {
            this.clutchedWaterPos = waterTargetPos;
            return;
        }

        // Sprawdź, czy celownik faktycznie jest skierowany na blok podłoża
        net.minecraft.world.phys.HitResult mouseOver = ctx.objectMouseOver();
        if (mouseOver instanceof BlockHitResult bhr && bhr.getBlockPos().equals(groundPos)) {
            net.minecraft.world.InteractionResult result = ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, bhr);
            if (result.consumesAction()) {
                player.swing(InteractionHand.MAIN_HAND);
                this.clutchedWaterPos = waterTargetPos;
                this.clutchedTicks = 0;

                double totalFall = Math.max(player.fallDistance, fallStartHeight - groundPos.getY());
                logDirect(String.format(Locale.ROOT,
                        "§a[WaterClutch] Wykonano MLG Water Clutch z wysokości %.1f kratek (0 obrażeń)!",
                        totalFall));
                fallStartHeight = 0.0D;
            }
        }
    }

    private void handleWaterPickup(Player player) {
        if (clutchedWaterPos == null) return;

        BlockState currentTarget = ctx.world().getBlockState(clutchedWaterPos);
        boolean hasWater = currentTarget.getFluidState().getType() instanceof WaterFluid;

        // Jeśli woda już zniknęła lub została zebrana do wiadra:
        if (!hasWater || player.getMainHandItem().is(Items.WATER_BUCKET)) {
            clutchedWaterPos = null;
            clutching = false;
            return;
        }

        // Limit prób podniesienia wody — po 20 tickach rezygnujemy, by nie wisieć w nieskończoność
        if (clutchedTicks > 20) {
            clutchedWaterPos = null;
            clutching = false;
            return;
        }

        // Podnosimy wodę, gdy gracz już wpadł do wody lub stanął bezpiecznie
        if (player.isInWater() || player.onGround() || player.getY() <= clutchedWaterPos.getY() + 1.2D) {
            player.fallDistance = 0.0F; // Bezpieczne wyzerowanie obrażeń

            if (!Baritone.settings().autoWaterClutchPickup.value) {
                clutchedWaterPos = null;
                clutching = false;
                return;
            }

            // Znajdź puste wiadro w ekwipunku i przenieś do hotbara jeśli potrzeba
            int emptyBucketSlot = findEmptyBucketSlot(player);
            if (emptyBucketSlot != -1) {
                int hotbarSlot = ensureBucketInHotbar(player, emptyBucketSlot);
                if (hotbarSlot != -1) {
                    player.getInventory().selected = hotbarSlot;
                    ctx.playerController().syncHeldItem();

                    // Skieruj celownik prosto w blok wody w LookBehavior (oraz na kliencie)
                    Rotation lookRot = RotationUtils.calcRotationFromVec3d(
                            ctx.playerHead(),
                            Vec3.atCenterOf(clutchedWaterPos),
                            ctx.playerRotations()
                    );
                    baritone.getLookBehavior().updateTarget(lookRot, true);

                    // Podnoś TYLKO wtedy, gdy celownik gracza fizycznie nakierował się na wodę
                    net.minecraft.world.phys.HitResult mouseOver = ctx.objectMouseOver();
                    if (mouseOver instanceof BlockHitResult bhr && bhr.getBlockPos().equals(clutchedWaterPos)) {
                        net.minecraft.world.InteractionResult res = ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, bhr);
                        if (res.consumesAction() || player.getMainHandItem().is(Items.WATER_BUCKET)) {
                            player.swing(InteractionHand.MAIN_HAND);
                            clutchedWaterPos = null;
                            clutching = false;
                        }
                    }
                }
            }
        }
    }

    private BlockPos findGroundBelow(Player player) {
        int px = player.getBlockX();
        int py = player.getBlockY();
        int pz = player.getBlockZ();

        for (int y = py; y >= ctx.world().getMinY() && (py - y) <= 45; y--) {
            BlockPos pos = new BlockPos(px, y, pz);
            BlockState state = ctx.world().getBlockState(pos);

            if (isNaturallySafeLanding(state)) {
                return pos;
            }

            if (!state.isAir() && state.blocksMotion()) {
                return pos;
            }
        }
        return null;
    }

    private boolean isNaturallySafeLanding(BlockState state) {
        return state.getFluidState().getType() instanceof WaterFluid
                || state.is(Blocks.COBWEB)
                || state.is(Blocks.SLIME_BLOCK)
                || state.is(Blocks.HAY_BLOCK)
                || state.is(Blocks.LADDER)
                || state.is(Blocks.VINE)
                || state.is(Blocks.POWDER_SNOW);
    }

    public static int findWaterBucketSlot(Player player) {
        // 1. Sprawdź najpierw hotbar (sloty 0-8)
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.WATER_BUCKET)) {
                return i;
            }
        }
        // 2. Sprawdź główny ekwipunek (sloty 9-35)
        for (int i = 9; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.WATER_BUCKET)) {
                return i;
            }
        }
        return -1;
    }

    private int findEmptyBucketSlot(Player player) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.BUCKET)) {
                return i;
            }
        }
        for (int i = 9; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.BUCKET)) {
                return i;
            }
        }
        return -1;
    }

    private int ensureBucketInHotbar(Player player, int slot) {
        if (Inventory.isHotbarSlot(slot)) {
            return slot;
        }

        // Wiadro jest w głównym EQ (slot 9-35) -> natychmiast zamień z aktualnym slotem hotbara!
        try {
            int currentHotbar = player.getInventory().selected;
            ctx.playerController().windowClick(player.inventoryMenu.containerId, slot, currentHotbar, ClickType.SWAP, player);
            return currentHotbar;
        } catch (Throwable t) {
            return -1;
        }
    }

    public boolean isClutching() {
        return clutching;
    }
}
