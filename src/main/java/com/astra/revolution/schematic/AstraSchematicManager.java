package com.astra.revolution.schematic;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.bypass.RotationEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * UNIFIED ASTRA SCHEMATIC & LITEMATICA AUTONOMOUS BUILD ENGINE (MC 1.21.4)
 *
 * Wersja: REDSTONE MECHANISMS + STAIRS/SLABS/RAILS + ZERO-FALL + 100% STRICT
 * LoS
 * 1. DirectionalStateSolver: 100% Legit ustawianie schodów (StairBlock: FACING
 * + HALF top/bottom),
 * półbloków (SlabBlock: BOTTOM/TOP/DOUBLE), torów (BaseRailBlock: NORTH_SOUTH /
 * EAST_WEST),
 * przekaźników (RepeaterBlock), komparatorów (ComparatorBlock), tłoków
 * (PistonBaseBlock),
 * obserwatorów (ObserverBlock), dozowników/podajników (DispenserBlock), lejów
 * (HopperBlock),
 * dźwigni/guzików (FaceAttachedHorizontalDirectionalBlock) oraz pni
 * (RotatedPillarBlock).
 * 2. Post-Placement Property Tuner (POST_INTERACT): Automatycznie klika prawym
 * przyciskiem
 * (bez Shifta) w postawione przekaźniki (ustawiając DELAY 1..4), komparatory
 * (COMPARE/SUBTRACT),
 * dźwignie (POWERED) oraz klapy/furtki (OPEN).
 * 3. Misoriented Block Auto-Fixer: Jeśli na miejscu schematu stoi już
 * przekaźnik, schodek lub tłok
 * obrócony w złą stronę, bot automatycznie go niszczy i stawia poprawnie od
 * właściwej strony.
 * 4. Poprawiona fizyka kolizji dla bloków bez hitboxa (Redstone Dust, Tory,
 * Dźwignie, Pochodnie)
 * oraz kolejkowanie Support-First (najpierw pełne bloki podłoża, potem
 * mechanizmy i tory).
 */
public final class AstraSchematicManager {

    private static final AstraSchematicManager INSTANCE = new AstraSchematicManager();

    public static AstraSchematicManager getInstance() {
        return INSTANCE;
    }

    public enum Phase {
        IDLE,
        PREPARE_SNEAK,
        ROTATE_AND_SELECT,
        AIM_LOCK,
        VERIFY_AND_PLACE,
        SEND_PLACEMENT,
        POST_INTERACT,
        BREAKING_WRONG_BLOCK,
        RELEASE_SNEAK,
        PILLARING_UP,
        BRIDGING,
        CLEANUP_SCAFFOLDING,
        COOLDOWN
    }

    public enum SliceMode {
        ALL_LAYERS("All Layers"),
        SINGLE_LAYER("Single Layer"),
        UP_TO_LAYER("Up To Layer");

        final String displayName;

        SliceMode(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private static final int MAX_TASK_STUCK_TICKS = 80;
    private static final int MAX_AIM_LOCK_TICKS = 6;

    private final Minecraft mc;

    private final AstraRotationController rotationController;
    private final AuxiliaryBlockManager auxiliaryBlockManager;
    private final BlockStatePlacementEngine placementEngine;
    private final SchematicBuildNavigator navigator;
    private final BedwarsBridgeEngine bridgeEngine;
    private final PillarUpEngine pillarEngine;
    private final ScaffoldingSolver scaffoldingSolver;
    private final SchematicBuildQueue buildQueue;

    // Schematic & GUI state
    private AstraSchematic activeSchematic = null;
    private BlockPos placementOrigin = null;
    private final SelectionManager selectionManager = new SelectionManager();

    private boolean hologramEnabled = true;
    private boolean renderCorrectBlocks = false;
    private SliceMode sliceMode = SliceMode.ALL_LAYERS;
    private int currentLayerY = 0;
    private String statusBannerMessage = "Gotowy — Wybierz lub załaduj schemat";
    private int placementCooldownTicks = 0;
    private boolean auraPrinterMode = false;
    private boolean autoCleanupScaffolding = true;

    // FSM & Synchronized Aim-Then-Place State
    private Phase currentPhase = Phase.IDLE;
    private SchematicBuildQueue.BuildTask activeTask = null;
    private SchematicBuildQueue.BuildTask aimLockedTask = null;
    private BlockStatePlacementEngine.PlacementPlan currentPlan = null;
    private int aimLockTicks = 0;

    // Dodatkowe pola stanu dla Failsafe BREAKING_WRONG_BLOCK (w klasie AstraSchematicManager):
    private static final int MAX_BREAKING_WRONG_TICKS = 100;
    private BlockPos breakingWrongPos = null;
    private boolean breakingWrongStarted = false;
    private int breakingWrongTicks = 0;
    private int breakingWrongTotalAttemptTicks = 0;
    private int stepBackFromWrongBlockTicks = 0;
    private int underFeetWrongBlockStuckTicks = 0;

    // Scaffolding Auto-Cleanup Tracking (LIFO)
    private final LinkedHashSet<BlockPos> placedScaffoldPositions = new LinkedHashSet<>();
    final Set<BlockPos> allSchematicPositions = new HashSet<>();
    private BlockPos activeCleanupPos = null;
    private boolean cleanupBreakingStarted = false;
    private int cleanupStuckTicks = 0;

    private boolean active = false;
    private boolean sneakAppliedByFsm = false;
    private int ledgeSneakHoldTicks = 0;
    private int phaseTicks = 0;
    private int cooldownTicks = 0;
    private int taskStuckTicks = 0;
    private int lastPlayerTick = -1;

    // Watchdog 7-sekundowy (Precyzyjny monitor bezczynności i logi diagnostyczne)
    private long lastProductiveActionTime = 0L;
    private long lastWatchdogReportTime = 0L;

    public void recordProductiveAction() {
        this.lastProductiveActionTime = System.currentTimeMillis();
    }

    public void dumpStallDiagnostics(LocalPlayer player, Level level) {
        if (player == null || level == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("§c[AstraWatchdog - 7s Stall Detected]\n");
        sb.append("§e▸ Faza FSM: §f").append(currentPhase)
          .append(" (ticks=").append(phaseTicks).append(", cooldown=").append(cooldownTicks).append(")\n");

        int activeSchemPhase = buildQueue.getActiveSchematicPhase(player, level);
        String phaseName = switch (activeSchemPhase) {
            case 0 -> "Faza 0 (Konstrukcje i mechanizmy)";
            case 1 -> "Faza 1 (Woda/Płyny w rynnach)";
            case 2 -> "Faza 2 (Sadzenie upraw i trzciny)";
            default -> "Nieznana (" + activeSchemPhase + ")";
        };
        sb.append("§e▸ Faza schematu: §f").append(phaseName)
          .append(" | Pozostało bloków: ").append(buildQueue.getPendingVoxels().size())
          .append(" (odłożone: ").append(buildQueue.getDeferredVoxels().size()).append(")\n");

        if (activeTask != null) {
            BlockPos targetPos = activeTask.targetPos();
            BlockState desired = activeTask.desiredState();
            boolean isScaffold = activeTask.isScaffold();
            double dist = player.getEyePosition(1.0F).distanceTo(Vec3.atCenterOf(targetPos));
            BlockState worldState = level.getBlockState(targetPos);
            boolean hasItem = player.getAbilities().instabuild || placementEngine.hasRequiredItemInInventory(player, desired);
            boolean hasSupport = placementEngine.hasValidSupportForState(targetPos, desired);
            boolean inPosition = navigator.isInPositionForTask(targetPos, desired, isScaffold);

            sb.append("§e▸ Aktywne zadanie: §b").append(isScaffold ? "[RUSZTOWANIE] " : "")
              .append(desired.getBlock().getName().getString())
              .append(" na [").append(targetPos.toShortString()).append("]\n");
            sb.append("§e  - Dystans: §f").append(String.format(Locale.ROOT, "%.2f", dist)).append("m")
              .append(" | W świecie: §f").append(worldState.getBlock().getName().getString()).append("\n");
            sb.append("§e  - Przedmiot w EQ: ").append(hasItem ? "§aTAK" : "§cBRAK!")
              .append(" §e| Oparcie: ").append(hasSupport ? "§aTAK" : "§cBRAK!")
              .append(" §e| Pozycja: ").append(inPosition ? "§aW ZASIĘGU" : "§6DOJŚCIE").append("\n");

            // Sprawdź przeszkody na linii wzroku (Raycast LoS)
            Vec3 eye = player.getEyePosition(1.0F);
            BlockHitResult hit = level.clip(new ClipContext(eye, Vec3.atCenterOf(targetPos), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(targetPos)) {
                BlockState obsState = level.getBlockState(hit.getBlockPos());
                sb.append("§e  - Przeszkoda LoS: §c").append(obsState.getBlock().getName().getString())
                  .append(" na [").append(hit.getBlockPos().toShortString()).append("]\n");
            }
        } else {
            sb.append("§e▸ Aktywne zadanie: §cBrak aktywnego zadania!\n");
            if (buildQueue.isEmpty()) {
                sb.append("§e  - Kolejka jest pusta (brak bloków do postawienia)\n");
            } else {
                long missingItemsCount = buildQueue.getPendingVoxels().entrySet().stream()
                        .filter(e -> !player.getAbilities().instabuild && !placementEngine.hasRequiredItemInInventory(player, e.getValue()))
                        .count();
                sb.append("§e  - Bloki z brakiem itemów w EQ: §c").append(missingItemsCount).append("/").append(buildQueue.getPendingVoxels().size()).append("\n");
                buildQueue.getPendingVoxels().values().stream()
                        .filter(s -> !player.getAbilities().instabuild && !placementEngine.hasRequiredItemInInventory(player, s))
                        .map(s -> s.getBlock().getName().getString())
                        .distinct()
                        .limit(3)
                        .forEach(name -> sb.append("§c    • Brak w ekwipunku: ").append(name).append("\n"));
            }
        }

        IBaritone b = getBaritone();
        boolean isPathing = b != null && b.getPathingBehavior().isPathing();
        sb.append("§e▸ Baritone pathing: ").append(isPathing ? "§aRuch aktywny" : "§cZatrzymany/brak ścieżki")
          .append(" | Gracz: §f").append(player.blockPosition().toShortString());

        String report = sb.toString();
        System.out.println("[AstraWatchdog] Stall report:\n" + report.replaceAll("§[0-9a-fk-or]", ""));
        player.displayClientMessage(Component.literal(report), false);

        // Automatyczny Unstuck / Failsafe
        if (activeTask != null) {
            buildQueue.recordFailedAttempt(activeTask.targetPos());
            buildQueue.deferVoxel(activeTask.targetPos(), 100);
            activeTask = null;
        }
        clearAimLockState(player);
        if (b != null && b.getPathingBehavior().isPathing()) {
            b.getPathingBehavior().cancelEverything();
            b.getInputOverrideHandler().clearAllKeys();
        }
        navigator.reset();
        recordProductiveAction();
    }

    private AstraSchematicManager() {
        this.mc = Minecraft.getInstance();
        IBaritone baritone = getBaritone();

        this.rotationController = new AstraRotationController(baritone);
        this.auxiliaryBlockManager = new AuxiliaryBlockManager(this);
        this.placementEngine = new BlockStatePlacementEngine(rotationController, auxiliaryBlockManager);
        this.navigator = new SchematicBuildNavigator(baritone, placementEngine, rotationController);
        this.bridgeEngine = new BedwarsBridgeEngine(
                baritone, rotationController, navigator, placementEngine, auxiliaryBlockManager, this);
        this.pillarEngine = new PillarUpEngine(
                baritone, rotationController, placementEngine, auxiliaryBlockManager, this);
        this.scaffoldingSolver = new ScaffoldingSolver(placementEngine, auxiliaryBlockManager);
        this.buildQueue = new SchematicBuildQueue(placementEngine, navigator, scaffoldingSolver);
        this.navigator.setBuildQueue(this.buildQueue);

        ensureSchematicsDirectory();
    }

    public IBaritone getBaritone() {
        try {
            return BaritoneAPI.getProvider().getPrimaryBaritone();
        } catch (Throwable ignored) {
            return null;
        }
    }

    // =========================================================================
    // FILE & SCHEMATIC MANAGEMENT
    // =========================================================================

    public void ensureSchematicsDirectory() {
        try {
            Path dir = Path.of("schematics");
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
        } catch (Exception ignored) {
        }
    }

    public List<Path> listSchematicFiles() {
        ensureSchematicsDirectory();
        List<Path> files = new ArrayList<>();
        try {
            Path dir = Path.of("schematics");
            if (Files.exists(dir)) {
                try (var stream = Files.list(dir)) {
                    stream.filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".litematic") || name.endsWith(".schem");
                    }).forEach(files::add);
                }
            }
        } catch (Exception ignored) {
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        return files;
    }

    public List<Path> listAvailableSchematics() {
        return listSchematicFiles();
    }

    public Path getSchematicsDir() {
        return Path.of("schematics");
    }

    public void loadSchematic(Path file, BlockPos origin) throws IOException {
        this.activeSchematic = LitematicaParser.load(file);
        this.placementOrigin = origin.immutable();
        this.currentLayerY = 0;
        this.currentPhase = Phase.IDLE;
        this.placedScaffoldPositions.clear();
        populateVoxelsFromSchematic();
        setStatusBannerMessage("Załadowano schemat: " + file.getFileName().toString());
    }

    public void loadSchematicFile(Path file, BlockPos origin) {
        try {
            loadSchematic(file, origin);
        } catch (Exception e) {
            setStatusBannerMessage("Błąd ładowania: " + e.getMessage());
        }
    }

    public void unloadSchematic() {
        stopBuilding();
        this.activeSchematic = null;
        this.placementOrigin = null;
        this.placedScaffoldPositions.clear();
        this.allSchematicPositions.clear();
        this.buildQueue.loadSchematicVoxels(Collections.emptyMap());
        setStatusBannerMessage("Schemat został wyładowany");
    }

    public boolean hasLoadedSchematic() {
        return activeSchematic != null && placementOrigin != null;
    }

    public AstraSchematic getActiveSchematic() {
        return activeSchematic;
    }

    public BlockPos getPlacementOrigin() {
        return placementOrigin;
    }

    public void setPlacementOrigin(BlockPos origin) {
        this.placementOrigin = origin != null ? origin.immutable() : null;
        if (activeSchematic != null && placementOrigin != null) {
            populateVoxelsFromSchematic();
        }
    }

    public BlockPos getSchematicOrigin() {
        return getPlacementOrigin();
    }

    public void setSchematicOrigin(BlockPos origin) {
        setPlacementOrigin(origin);
    }

    public SchematicHologramRenderer getHologramRenderer() {
        return SchematicHologramRenderer.getInstance();
    }

    public void nudgeOrigin(int dx, int dy, int dz) {
        if (this.placementOrigin != null) {
            setPlacementOrigin(this.placementOrigin.offset(dx, dy, dz));
        }
    }

    public void cycleRotation() {
        if (this.activeSchematic != null) {
            this.activeSchematic.cycleRotation();
            if (this.placementOrigin != null) {
                populateVoxelsFromSchematic();
            }
        }
    }

    public void cycleMirror() {
        if (this.activeSchematic != null) {
            this.activeSchematic.cycleMirror();
            if (this.placementOrigin != null) {
                populateVoxelsFromSchematic();
            }
        }
    }

    public static BlockPos getTargetedOrPlayerBlockPos() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return BlockPos.ZERO;
        }
        if (mc.hitResult instanceof BlockHitResult bhr
                && mc.hitResult.getType() == HitResult.Type.BLOCK) {
            return bhr.getBlockPos();
        }
        return mc.player.blockPosition();
    }

    public void populateVoxelsFromSchematic() {
        if (activeSchematic == null || placementOrigin == null) {
            return;
        }
        Map<BlockPos, BlockState> voxels = new LinkedHashMap<>();
        allSchematicPositions.clear();

        int width = activeSchematic.getWidth();
        int height = activeSchematic.getHeight();
        int length = activeSchematic.getLength();

        int startY = 0;
        int endY = height;
        if (sliceMode == SliceMode.SINGLE_LAYER) {
            startY = Math.clamp(currentLayerY, 0, Math.max(0, height - 1));
            endY = startY + 1;
        } else if (sliceMode == SliceMode.UP_TO_LAYER) {
            endY = Math.clamp(currentLayerY + 1, 1, height);
        }

        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    BlockState state = activeSchematic.getTransformedState(x, y, z);
                    if (state != null && !state.isAir() && !isTechnicalSubBlock(state)) {
                        BlockPos worldPos = placementOrigin.offset(x, y, z).immutable();
                        allSchematicPositions.add(worldPos);
                        if (y >= startY && y < endY) {
                            voxels.put(worldPos, state);
                        }
                    }
                }
            }
        }
        buildQueue.loadSchematicVoxels(voxels);
    }

    /**
     * Pomija wirtualne/automatyczne pod-bloki (głowica wysuniętego tłoka, górna
     * połowa drzwi/kwiatów,
     * wezgłowie łóżka), które gra tworzy sama po postawieniu bloku bazowego.
     */
    public static boolean isTechnicalSubBlock(BlockState state) {
        if (state == null || state.isAir()) {
            return true;
        }
        Block block = state.getBlock();
        if (block instanceof PistonHeadBlock || block instanceof MovingPistonBlock) {
            return true;
        }
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return true;
        }
        if (state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) {
            return true;
        }
        return false;
    }

    // =========================================================================
    // GUI DISPLAY, SLICING & CLEANUP CONTROLS
    // =========================================================================

    public boolean isHologramEnabled() {
        return hologramEnabled;
    }

    public void setHologramEnabled(boolean enabled) {
        this.hologramEnabled = enabled;
    }

    public boolean isRenderCorrectBlocks() {
        return renderCorrectBlocks;
    }

    public void setRenderCorrectBlocks(boolean render) {
        this.renderCorrectBlocks = render;
    }

    public SliceMode getSliceMode() {
        return sliceMode;
    }

    public void setSliceMode(SliceMode mode) {
        this.sliceMode = mode != null ? mode : SliceMode.ALL_LAYERS;
        populateVoxelsFromSchematic();
    }

    public int getCurrentLayerY() {
        return currentLayerY;
    }

    public void setCurrentLayerY(int layerY) {
        if (activeSchematic != null) {
            this.currentLayerY = Math.clamp(layerY, 0, Math.max(0, activeSchematic.getHeight() - 1));
        } else {
            this.currentLayerY = Math.max(0, layerY);
        }
        populateVoxelsFromSchematic();
    }

    public String getStatusBannerMessage() {
        return statusBannerMessage;
    }

    public void setStatusBannerMessage(String msg) {
        this.statusBannerMessage = msg;
        System.out.println("[AstraSchematic] " + msg);
    }

    public int getPlacementCooldownTicks() {
        return placementCooldownTicks;
    }

    public void setPlacementCooldownTicks(int ticks) {
        this.placementCooldownTicks = Math.max(0, ticks);
    }

    public boolean isAuraPrinterMode() {
        return auraPrinterMode;
    }

    public void setAuraPrinterMode(boolean aura) {
        this.auraPrinterMode = aura;
    }

    public boolean isAutoCleanupScaffolding() {
        return autoCleanupScaffolding;
    }

    public void setAutoCleanupScaffolding(boolean autoCleanupScaffolding) {
        this.autoCleanupScaffolding = autoCleanupScaffolding;
    }

    public Set<BlockPos> getPlacedScaffoldPositions() {
        return Collections.unmodifiableSet(placedScaffoldPositions);
    }

    public void recordScaffoldPlacement(BlockPos pos) {
        if (pos == null) {
            return;
        }
        BlockPos immutable = pos.immutable();
        if (!allSchematicPositions.contains(immutable) && !buildQueue.getPendingVoxels().containsKey(immutable)) {
            placedScaffoldPositions.add(immutable);
        }
    }

    public SelectionManager getSelectionManager() {
        return selectionManager;
    }

    public SchematicBuildQueue getBuildQueue() {
        return buildQueue;
    }

    public void rebuildQueue() {
        populateVoxelsFromSchematic();
    }

    public AstraSchematicManager getBuilderProcess() {
        return this;
    }

    public Map<Block, MaterialAudit> auditMaterials() {
        if (activeSchematic == null || mc.player == null) {
            return Collections.emptyMap();
        }
        Inventory inv = mc.player.getInventory();
        Map<Item, Integer> invCounts = new HashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                invCounts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }

        Map<Block, MaterialAudit> audit = new LinkedHashMap<>();
        for (Map.Entry<Block, Integer> req : activeSchematic.getMaterialRequirements().entrySet()) {
            Block block = req.getKey();
            if (block instanceof PistonHeadBlock || block instanceof MovingPistonBlock) {
                continue;
            }
            int needed = req.getValue();
            Item mappedItem = BlockStatePlacementEngine.resolveRequiredItemForState(block.defaultBlockState());
            int inInv = invCounts.getOrDefault(mappedItem, 0);
            audit.merge(block, new MaterialAudit(block, needed, inInv),
                    (a, b) -> new MaterialAudit(block, a.neededTotal + b.neededTotal, inInv));
        }
        return audit;
    }

    public record MaterialAudit(Block block, int neededTotal, int inInventory) {
        public int missing() {
            return Math.max(0, neededTotal - inInventory);
        }
    }

    // =========================================================================
    // BUILDING CONTROL
    // =========================================================================

    public boolean isBuilding() {
        return active;
    }

    public boolean isBuildingActive() {
        return active;
    }

    public void startBuilding() {
        if (activeSchematic != null && placementOrigin != null) {
            populateVoxelsFromSchematic();
            resetFsmState();
            this.active = true;
            recordProductiveAction();
            this.lastWatchdogReportTime = 0L;
            this.navigator.ensureBaritoneSettingsConfigured();
            setStatusBannerMessage("⚡ Auto-Builder Aktywny (Mechanizmy + Schody/Tory + Zero-Fall)");
        } else {
            setStatusBannerMessage("Wybierz i ustaw punkt początkowy schematu przed budową!");
        }
    }

    public void stopBuilding() {
        this.active = false;
        resetFsmState();
        this.bridgeEngine.abortBridge();
        this.pillarEngine.abortPillar();
        this.scaffoldingSolver.clearCachedChain();
        this.navigator.reset();
        this.rotationController.reset();
        IBaritone b = getBaritone();
        if (b != null) {
            b.getPathingBehavior().cancelEverything();
            b.getCustomGoalProcess().onLostControl();
            b.getInputOverrideHandler().clearAllKeys();
        }
        if (mc.player != null) {
            mc.player.setShiftKeyDown(false);
            if (mc.options != null) {
                mc.options.keyShift.setDown(false);
            }
        }
        if (mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
        setStatusBannerMessage("Budowa schematu zatrzymana");
    }

    public void toggleBuilding() {
        if (isBuilding()) {
            stopBuilding();
        } else {
            startBuilding();
        }
    }

    public void startBuild(Map<BlockPos, BlockState> schematicVoxels) {
        this.allSchematicPositions.clear();
        if (schematicVoxels != null) {
            this.allSchematicPositions.addAll(schematicVoxels.keySet());
        }
        this.buildQueue.loadSchematicVoxels(schematicVoxels);
        resetFsmState();
        this.active = true;
        recordProductiveAction();
        this.lastWatchdogReportTime = 0L;
        this.navigator.ensureBaritoneSettingsConfigured();
    }

    public void stopBuild() {
        stopBuilding();
    }

    private void resetFsmState() {
        this.currentPhase = Phase.IDLE;
        this.activeTask = null;
        this.aimLockedTask = null;
        this.currentPlan = null;
        this.aimLockTicks = 0;
        this.breakingWrongPos = null;
        this.breakingWrongStarted = false;
        this.breakingWrongTicks = 0;
        this.breakingWrongTotalAttemptTicks = 0;
        this.stepBackFromWrongBlockTicks = 0;
        this.underFeetWrongBlockStuckTicks = 0;
        this.activeCleanupPos = null;
        this.cleanupBreakingStarted = false;
        this.cleanupStuckTicks = 0;
        this.phaseTicks = 0;
        this.cooldownTicks = 0;
        this.taskStuckTicks = 0;
        this.ledgeSneakHoldTicks = 0;
        this.lastWatchdogReportTime = 0L;
        this.scaffoldingSolver.clearCachedChain();
    }

    // =========================================================================
    // GLOBAL LEDGE & ANTI-FALL SAFETY GUARD
    // =========================================================================

    // =========================================================================
    // POPRAWKA B: GLOBAL LEDGE & ANTI-FALL SAFETY GUARD (TOLERANCJA RYNIEN WODNYCH)
    // =========================================================================

    public boolean isPlayerNearDangerousLedge(LocalPlayer player, Level level) {
        if (player == null || level == null) {
            return false;
        }

        if (pillarEngine.isActive()) {
            return false;
        }

        IBaritone b = getBaritone();
        boolean isBaritonePathing = b != null && b.getPathingBehavior() != null && b.getPathingBehavior().isPathing();

        BlockPos feetPos = player.blockPosition();
        BlockPos belowPos = feetPos.below();
        BlockState belowState = level.getBlockState(belowPos);

        // Jeśli gracz jest w wodzie lub w stopach ma wodę, kucanie jest ZABRONIONE (woda unosi gracza i niweluje upadki)
        if (player.isInWater() || !level.getFluidState(feetPos).isEmpty()) {
            return false;
        }
        if (!belowState.getFluidState().isEmpty()) {
            // Tylko lawa jest niebezpieczna, woda nie jest przepaścią!
            return belowState.getFluidState().is(FluidTags.LAVA);
        }

        // Jeśli gracz wisi nad powietrzem, sprawdź czy to rzeczywista przepaść (> 2 kratki)
        if (belowState.isAir() || belowState.canBeReplaced()) {
            return player.onGround() && isDangerousDrop(level, belowPos);
        }

        double relX = player.getX() - feetPos.getX();
        double relZ = player.getZ() - feetPos.getZ();

        double enterThreshold = 0.16D;
        double exitThreshold = 0.28D;
        double thresh = sneakAppliedByFsm ? exitThreshold : enterThreshold;

        boolean nearWest = relX < thresh;
        boolean nearEast = relX > (1.0D - thresh);
        boolean nearNorth = relZ < thresh;
        boolean nearSouth = relZ > (1.0D - thresh);

        boolean dangerousEdgeNear = (nearWest && isDangerousDrop(level, belowPos.west()))
                || (nearEast && isDangerousDrop(level, belowPos.east()))
                || (nearNorth && isDangerousDrop(level, belowPos.north()))
                || (nearSouth && isDangerousDrop(level, belowPos.south()))
                || (nearWest && nearNorth && isDangerousDrop(level, belowPos.west().north()))
                || (nearEast && nearNorth && isDangerousDrop(level, belowPos.east().north()))
                || (nearWest && nearSouth && isDangerousDrop(level, belowPos.west().south()))
                || (nearEast && nearSouth && isDangerousDrop(level, belowPos.east().south()));

        if (dangerousEdgeNear) {
            return true;
        }

        // Gdy Baritone bezpiecznie nawiguje po solidnym podłożu z dala od krawędzi przepaści, nie blokuj ruchu kucaniem
        if (isBaritonePathing && !belowState.getCollisionShape(level, belowPos).isEmpty()) {
            return false;
        }

        return false;
    }

    /**
     * Woda nigdy nie jest niebezpiecznym spadkiem w Minecraft (amortyzuje 100% upadku).
     * Niebezpieczna jest lawa oraz spadek o >= 2 bloki czystego powietrza (wpadnięcie do rowu/przepaści).
     */
    private static boolean isDangerousDrop(Level level, BlockPos neighborGroundPos) {
        BlockState s0 = level.getBlockState(neighborGroundPos);

        // 1. Jeśli sąsiedni blok na poziomie podłoża jest solidny -> brak spadku
        if (!s0.isAir() && !s0.canBeReplaced() && s0.getFluidState().isEmpty()) {
            return false;
        }

        // 2. Jeśli na poziomie podłoża (Y-1) jest płyn:
        if (!s0.getFluidState().isEmpty()) {
            // Woda całkowicie niweluje obrażenia z upadku - nie traktuj jej jako przepaści!
            return s0.getFluidState().is(FluidTags.LAVA);
        }

        // 3. Jeśli s0 (Y-1) to powietrze, sprawdzamy s1 (Y-2, czyli 1 blok niżej):
        BlockState s1 = level.getBlockState(neighborGroundPos.below());
        if (!s1.isAir() && !s1.canBeReplaced() && s1.getFluidState().isEmpty()) {
            return false; // Bezpieczny stopień 1 blok w dół
        }
        if (!s1.getFluidState().isEmpty()) {
            return s1.getFluidState().is(FluidTags.LAVA);
        }

        // 4. Jeśli s1 to też powietrze, spadek wynosi >= 2 bloki w dół (wpadnięcie do rowu lub przepaści) -> niebezpieczny!
        return true;
    }

    private void enforceGlobalLedgeSafety(LocalPlayer player, Level level) {
        // W wodzie nigdy nie wymuszaj kucania – gracz musi swobodnie płynąć i wyskakiwać na brzeg!
        if (player.isInWater() || !level.getFluidState(player.blockPosition()).isEmpty()) {
            this.ledgeSneakHoldTicks = 0;
            if (sneakAppliedByFsm) {
                setPlayerSneak(player, false);
                sneakAppliedByFsm = false;
            }
            return;
        }

        if (isPlayerNearDangerousLedge(player, level)) {
            setPlayerSneak(player, true);
            this.sneakAppliedByFsm = true;
            this.ledgeSneakHoldTicks = 6;
            player.setSprinting(false);
        } else if (ledgeSneakHoldTicks > 0) {
            ledgeSneakHoldTicks--;
        } else if (sneakAppliedByFsm
                && currentPhase != Phase.AIM_LOCK
                && currentPhase != Phase.VERIFY_AND_PLACE
                && !bridgeEngine.isActive()
                && !pillarEngine.isActive()) {
            setPlayerSneak(player, false);
            sneakAppliedByFsm = false;
        }
    }

    // =========================================================================
    // TICK LOOP: MECHANISMS + DIRECTIONAL BLOCKS + ZERO-FALL ENGINE
    // =========================================================================

    public void onClientTick() {
        if (!active) {
            return;
        }

        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) {
            stopBuild();
            return;
        }

        if (player.tickCount == lastPlayerTick) {
            return;
        }
        lastPlayerTick = player.tickCount;

        navigator.tickTimers();
        placementEngine.tickCooldowns();
        pillarEngine.tickCooldowns();
        buildQueue.tickDeferredVoxels();
        buildQueue.pruneSatisfiedVoxels(level);

        // 0. 7-Sekundowy Watchdog bezczynności i automatyczna diagnostyka
        long nowMs = System.currentTimeMillis();
        if (lastProductiveActionTime == 0L) {
            lastProductiveActionTime = nowMs;
        }
        if (nowMs - lastProductiveActionTime >= 7000L) {
            if (nowMs - lastWatchdogReportTime >= 7000L) {
                lastWatchdogReportTime = nowMs;
                dumpStallDiagnostics(player, level);
            }
        }

        // 1. Zabezpieczenie przed spadnięciem z krawędzi
        enforceGlobalLedgeSafety(player, level);

        // 2. Aktualizacja wygładzonego obrotu kamery
        rotationController.tick();

        // 3. Sprawdź zakończenie budowy schematu (oraz Auto-Cleanup rusztowań)
        if (buildQueue.isEmpty()) {
            if (autoCleanupScaffolding && !placedScaffoldPositions.isEmpty()) {
                currentPhase = Phase.CLEANUP_SCAFFOLDING;
                executeScaffoldCleanupPass(player, level);
                return;
            }
            stopBuild();
            setStatusBannerMessage("✔ Budowa schematu została ukończona w 100%!");
            return;
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
        }

        // 4. PILLAR-UP STATE MACHINE
        if (pillarEngine.isActive()) {
            currentPhase = Phase.PILLARING_UP;
            clearAimLockState(player);
            pillarEngine.tickPillar(player, level);
            return;
        }

        // 5. SMART BRIDGE STATE MACHINE
        if (bridgeEngine.isActive()) {
            currentPhase = Phase.BRIDGING;
            clearAimLockState(player);
            if (activeTask != null && bridgeEngine.shouldBridge(activeTask.targetPos(), activeTask.desiredState())) {
                setStatusBannerMessage("⚡ Smart-Bridge do [" + activeTask.targetPos().toShortString() + "]");
                bridgeEngine.tickBridge(activeTask.targetPos(), activeTask.desiredState());
                if (bridgeEngine.isAborted()) {
                    if (activeTask != null) {
                        buildQueue.recordFailedAttempt(activeTask.targetPos());
                        buildQueue.deferVoxel(activeTask.targetPos(), 180);
                        activeTask = null;
                    }
                    bridgeEngine.clearAborted();
                }
                enforceGlobalLedgeSafety(player, level);
                return;
            } else {
                boolean wasAborted = bridgeEngine.isAborted();
                bridgeEngine.completeBridge();
                if (wasAborted && activeTask != null) {
                    buildQueue.recordFailedAttempt(activeTask.targetPos());
                    buildQueue.deferVoxel(activeTask.targetPos(), 180);
                    activeTask = null;
                }
                bridgeEngine.clearAborted();
                enforceGlobalLedgeSafety(player, level);
            }
        }

        // 6. POST-PLACEMENT INTERACTION & WRONG-ORIENTATION BREAKING PASS
        // (Ustawia ticki przekaźnika, tryb komparatora, przełącza dźwignię lub niszczy
        // źle obrócony blok)
        if (cooldownTicks == 0 && processPostInteractOrBreakWrongPass(player, level)) {
            enforceGlobalLedgeSafety(player, level);
            return;
        }

        // 7. SZYBKI PIPELINE STAWIANIA BLOKÓW (Z WERYFIKACJĄ KIERUNKU SCHODÓW, TORÓW I
        // MECHANIZMÓW)
        if (cooldownTicks == 0) {
            int maxPlaces = auraPrinterMode ? 2 : 1;
            for (int i = 0; i < maxPlaces; i++) {
                boolean waitingForMultiTickRotation = processFastSynchronizedPlacement(player, level);
                if (waitingForMultiTickRotation) {
                    break;
                }
                if (!auraPrinterMode) {
                    break;
                }
            }
        }

        // 8. MĄDRA NAWIGACJA (Ustawia gracza od właściwej strony dla przekaźników,
        // schodów, tłoków i torów!)
        executeNavigationAndBridgingPass(player, level);

        enforceGlobalLedgeSafety(player, level);
    }

    /**
     * Obsługuje:
     * A) Strojenie właściwości po postawieniu (`POST_INTERACT`): np. klikanie
     * prawym przyciskiem
     * w przekaźnik (Repeater), aby ustawić wymagane opóźnienie `DELAY` (1..4), w
     * komparator (`MODE`),
     * lub w dźwignię/klapę (`POWERED` / `OPEN`).
     * został wcześniej postawiony w złą stronę (`FACING` / `HALF` / `AXIS`),
     * bot go niszczy, aby postawić go ponownie we właściwym kierunku!
     */
    // =========================================================================
    // POPRAWKA D: POST-INTERACT & BREAKING_WRONG_BLOCK Z FAILSAFE 40 TICKÓW
    // =========================================================================

    private boolean processPostInteractOrBreakWrongPass(LocalPlayer player, Level level) {
        Vec3 eyePos = player.getEyePosition(1.0F);
        double maxReachSq = BlockStatePlacementEngine.SURVIVAL_REACH_DISTANCE
                * BlockStatePlacementEngine.SURVIVAL_REACH_DISTANCE;

        // Obsługa aktywnego wycofywania się (Step-Back Failsafe) po nieudanym niszczeniu bloku
        if (stepBackFromWrongBlockTicks > 0) {
            stepBackFromWrongBlockTicks--;
            if (breakingWrongPos != null) {
                navigator.stepBackFromBlock(player, level, breakingWrongPos);
            }
            if (stepBackFromWrongBlockTicks == 0) {
                navigator.clearCollisionMovementIfActive();
                if (breakingWrongPos != null) {
                    buildQueue.deferVoxel(breakingWrongPos, 45);
                    breakingWrongPos = null;
                }
                breakingWrongStarted = false;
                breakingWrongTicks = 0;
                breakingWrongTotalAttemptTicks = 0;
                underFeetWrongBlockStuckTicks = 0;
            }
            return true;
        }

        // A. Sprawdź, czy w zasięgu ręki jest blok wymagający kliknięcia prawym przyciskiem (np. Repeater DELAY)
        for (Map.Entry<BlockPos, BlockState> entry : buildQueue.getPendingVoxels().entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState desired = entry.getValue();
            if (eyePos.distanceToSqr(Vec3.atCenterOf(pos)) > maxReachSq) {
                continue;
            }
            BlockState worldState = level.getBlockState(pos);
            int clicksNeeded = DirectionalStateSolver.getRequiredPostPlacementRightClicks(worldState, desired);
            if (clicksNeeded > 0) {
                Vec3 targetPoint = Vec3.atCenterOf(pos).subtract(0.0D, 0.25D, 0.0D);
                Rotation rawRot = BlockStatePlacementEngine.computeRotationToVec(eyePos, targetPoint);
                Rotation quantized = rotationController.quantizeToPlayerGrid(player, rawRot.getYaw(),
                        rawRot.getPitch());
                rotationController.requestRotation(
                        quantized,
                        AstraRotationController.AimPriority.PLACEMENT_RAYCAST,
                        3);
                rotationController.stepImmediatelyWithinTick();

                Vec3 lookVec = AstraRotationController.calculateViewVector(player.getXRot(), player.getYRot());
                Vec3 rayEnd = eyePos.add(lookVec.scale(BlockStatePlacementEngine.SURVIVAL_REACH_DISTANCE));
                BlockHitResult hit = level.clip(new ClipContext(
                        eyePos,
                        rayEnd,
                        ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE,
                        player));

                if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
                    currentPhase = Phase.POST_INTERACT;
                    boolean wasSneaking = player.isShiftKeyDown();
                    if (wasSneaking) {
                        Vec3 vel = player.getDeltaMovement();
                        player.setDeltaMovement(0.0D, vel.y, 0.0D);
                        setPlayerSneak(player, false);
                    }
                    GrimPacketOrder.interactBlock(player, hit, InteractionHand.MAIN_HAND);
                    recordProductiveAction();
                    if (wasSneaking) {
                        setPlayerSneak(player, true);
                        this.ledgeSneakHoldTicks = 6;
                    }
                    this.cooldownTicks = 2;
                    setStatusBannerMessage("⚙ Strojenie mechanizmu: "
                            + desired.getBlock().getName().getString() + " [" + pos.toShortString() + "]");
                    return true;
                }
                return true;
            }
        }

        // B. Sprawdź, czy w zasięgu ręki jest blok postawiony w złym stanie / kierunku (z wykluczeniem cieczy!)
        BlockPos wrongBlockPos = null;
        BlockState wrongBlockWorld = null;
        BlockPos playerFeet = player.blockPosition();
        BlockPos directlyUnderFeet = playerFeet.below();

        // Priorytet 0: Jeśli gracz stoi BEZPOŚREDNIO NA lub WEWNĄTRZ błędnego bloku (np. ziemia zamiast torów!),
        // próbuje zejść na sąsiednie bezpieczne pole. Jeśli nie ma bezpiecznego odwrotu (np. wąska krawędź/podest),
        // nie zapętlamy bota w paraliżu, lecz odkładamy ten blok i pozwalamy mu budować ze stojącej pozycji!
        BlockState underFeetState = level.getBlockState(directlyUnderFeet);
        BlockState desiredUnderFeet = buildQueue.getPendingVoxels().get(directlyUnderFeet);
        if (desiredUnderFeet != null && !buildQueue.isDeferred(directlyUnderFeet)
                && !(underFeetState.getBlock() instanceof LiquidBlock)
                && DirectionalStateSolver.requiresBreakAndReplace(underFeetState, desiredUnderFeet)) {
            if (underFeetWrongBlockStuckTicks >= 6) {
                buildQueue.deferVoxel(directlyUnderFeet, 120);
                underFeetWrongBlockStuckTicks = 0;
                this.stepBackFromWrongBlockTicks = 0;
                navigator.clearCollisionMovementIfActive();
                this.breakingWrongPos = null;
            } else {
                underFeetWrongBlockStuckTicks++;
                this.stepBackFromWrongBlockTicks = 6;
                this.breakingWrongPos = directlyUnderFeet;
                navigator.stepBackFromBlock(player, level, directlyUnderFeet);
                setStatusBannerMessage("↩ Schodzenie z błędnego bloku pod stopami [" + directlyUnderFeet.toShortString() + "]");
                return true;
            }
        }

        BlockState atFeetState = level.getBlockState(playerFeet);
        BlockState desiredAtFeet = buildQueue.getPendingVoxels().get(playerFeet);
        if (desiredAtFeet != null && !buildQueue.isDeferred(playerFeet)
                && !(atFeetState.getBlock() instanceof LiquidBlock)
                && DirectionalStateSolver.requiresBreakAndReplace(atFeetState, desiredAtFeet)) {
            if (underFeetWrongBlockStuckTicks >= 6) {
                buildQueue.deferVoxel(playerFeet, 120);
                underFeetWrongBlockStuckTicks = 0;
                this.stepBackFromWrongBlockTicks = 0;
                navigator.clearCollisionMovementIfActive();
                this.breakingWrongPos = null;
            } else {
                underFeetWrongBlockStuckTicks++;
                this.stepBackFromWrongBlockTicks = 6;
                this.breakingWrongPos = playerFeet;
                navigator.stepBackFromBlock(player, level, playerFeet);
                setStatusBannerMessage("↩ Schodzenie z błędnego bloku w stopach [" + playerFeet.toShortString() + "]");
                return true;
            }
        }

        // Priorytet 1: Kontynuuj wcześniej zablokowany cel breakingWrongPos, jeśli nadal wymaga zniszczenia
        if (this.breakingWrongPos != null
                && !this.breakingWrongPos.equals(directlyUnderFeet)
                && !buildQueue.isDeferred(this.breakingWrongPos)
                && eyePos.distanceToSqr(Vec3.atCenterOf(this.breakingWrongPos)) <= maxReachSq) {
            BlockState desired = buildQueue.getPendingVoxels().get(this.breakingWrongPos);
            BlockState worldState = level.getBlockState(this.breakingWrongPos);
            if (desired != null
                    && !(worldState.getBlock() instanceof LiquidBlock)
                    && worldState.getFluidState().isEmpty()
                    && DirectionalStateSolver.requiresBreakAndReplace(worldState, desired)) {
                wrongBlockPos = this.breakingWrongPos;
                wrongBlockWorld = worldState;
            }
        }

        // Priorytet 2: Sprawdź activeTask
        if (wrongBlockPos == null && activeTask != null && !activeTask.isScaffold()) {
            BlockPos pos = activeTask.targetPos();
            if (!pos.equals(directlyUnderFeet) && !buildQueue.isDeferred(pos)) {
                BlockState desired = activeTask.desiredState();
                BlockState worldState = level.getBlockState(pos);
                if (!(worldState.getBlock() instanceof LiquidBlock)
                        && worldState.getFluidState().isEmpty()
                        && DirectionalStateSolver.requiresBreakAndReplace(worldState, desired)
                        && eyePos.distanceToSqr(Vec3.atCenterOf(pos)) <= maxReachSq) {
                    wrongBlockPos = pos;
                    wrongBlockWorld = worldState;
                }
            }
        }

        // Priorytet 2.5: Jeśli activeTask to blok strukturalny lub mechanizm (np. tłok, obserwator),
        // sprawdź czy w bezpośredniej linii wzroku gracza do celu nie stoi wyrośnięta roślina/trzcina!
        if (wrongBlockPos == null && activeTask != null && !activeTask.isScaffold()) {
            BlockPos targetPos = activeTask.targetPos();
            BlockPos[] targetCheckPositions = new BlockPos[] { targetPos, targetPos.below() };
            for (BlockPos checkPos : targetCheckPositions) {
                if (wrongBlockPos != null) break;
                Vec3 checkCenter = Vec3.atCenterOf(checkPos);
                if (eyePos.distanceToSqr(checkCenter) <= maxReachSq) {
                    BlockHitResult hit = level.clip(new ClipContext(
                            eyePos,
                            checkCenter,
                            ClipContext.Block.OUTLINE,
                            ClipContext.Fluid.NONE,
                            player));
                    if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(checkPos)) {
                        BlockPos hitPos = hit.getBlockPos();
                        if (!hitPos.equals(directlyUnderFeet) && !hitPos.equals(playerFeet)
                                && eyePos.distanceToSqr(Vec3.atCenterOf(hitPos)) <= maxReachSq) {
                            BlockState hitState = level.getBlockState(hitPos);
                            if (DirectionalStateSolver.isCropOrVegetation(hitState)) {
                                int taskOrder = DirectionalStateSolver.getLayerDependencyOrder(activeTask.desiredState());
                                if (taskOrder < 20) {
                                    // Roślina zasłania widok do mechanizmu/konstrukcji - niszczymy ją, aby odsłonić cel!
                                    // Dopiszemy ją z powrotem do pendingVoxels, by została zasadzona po mechanizmie.
                                    buildQueue.getPendingVoxels().put(hitPos, hitState);
                                    wrongBlockPos = hitPos;
                                    wrongBlockWorld = hitState;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }

        // Priorytet 3: Sprawdź pozostałe bloki z kolejki w zasięgu
        if (wrongBlockPos == null) {
            for (Map.Entry<BlockPos, BlockState> entry : buildQueue.getPendingVoxels().entrySet()) {
                BlockPos pos = entry.getKey();
                if (pos.equals(directlyUnderFeet) || buildQueue.isDeferred(pos)) {
                    continue;
                }
                if (eyePos.distanceToSqr(Vec3.atCenterOf(pos)) <= maxReachSq) {
                    BlockState worldState = level.getBlockState(pos);
                    if (!(worldState.getBlock() instanceof LiquidBlock)
                            && worldState.getFluidState().isEmpty()
                            && DirectionalStateSolver.requiresBreakAndReplace(worldState, entry.getValue())) {
                        wrongBlockPos = pos;
                        wrongBlockWorld = worldState;
                        break;
                    }
                }
            }
        }

        // Priorytet 4: Jeśli w zasięgu ręki znajduje się woksel ze schematu z błędnym blokiem (np. ziemia zamiast toru),
        // który został wcześniej omyłkowo usunięty lub pominięty, przywróć go do kolejki do natychmiastowego zniszczenia!
        if (wrongBlockPos == null && activeSchematic != null && placementOrigin != null) {
            for (BlockPos schemPos : allSchematicPositions) {
                if (schemPos.equals(directlyUnderFeet) || schemPos.equals(playerFeet) || buildQueue.isDeferred(schemPos)) {
                    continue;
                }
                if (eyePos.distanceToSqr(Vec3.atCenterOf(schemPos)) <= maxReachSq) {
                    BlockState currentWorld = level.getBlockState(schemPos);
                    if (currentWorld.isAir() || currentWorld.canBeReplaced() || currentWorld.getBlock() instanceof LiquidBlock) {
                        continue;
                    }
                    BlockPos local = schemPos.subtract(placementOrigin);
                    BlockState schemState = activeSchematic.getTransformedState(local.getX(), local.getY(), local.getZ());
                    if (schemState != null && !schemState.isAir() && DirectionalStateSolver.requiresBreakAndReplace(currentWorld, schemState)) {
                        buildQueue.getPendingVoxels().put(schemPos, schemState);
                        wrongBlockPos = schemPos;
                        wrongBlockWorld = currentWorld;
                        break;
                    }
                }
            }
        }

        if (wrongBlockPos != null) {
            if (!wrongBlockPos.equals(this.breakingWrongPos)) {
                this.breakingWrongPos = wrongBlockPos.immutable();
                this.breakingWrongStarted = false;
                this.breakingWrongTicks = 0;
                this.breakingWrongTotalAttemptTicks = 0;
            }

            this.breakingWrongTotalAttemptTicks++;

            // FAILSAFE 40 TICKÓW: Jeśli blok nie pęka lub gracz na nim stoi, cofnij się i odłóż woksel
            if (this.breakingWrongTotalAttemptTicks > MAX_BREAKING_WRONG_TICKS
                    || wrongBlockPos.equals(playerFeet)) {
                if (mc.gameMode != null) {
                    mc.gameMode.stopDestroyBlock();
                }
                this.breakingWrongStarted = false;
                this.stepBackFromWrongBlockTicks = 10;
                setStatusBannerMessage("↩ Cofanie od zablokowanego woksela [" + wrongBlockPos.toShortString() + "]");
                navigator.stepBackFromBlock(player, level, wrongBlockPos);
                return true;
            }

            Rotation bestBreakRot = findVisibleBreakRotation(player, level, eyePos, wrongBlockPos);
            if (bestBreakRot == null) {
                if (this.breakingWrongTotalAttemptTicks > 8) {
                    this.stepBackFromWrongBlockTicks = 8;
                    navigator.stepBackFromBlock(player, level, wrongBlockPos);
                    return true;
                }
                buildQueue.deferVoxel(wrongBlockPos, 25);
                this.breakingWrongPos = null;
                this.breakingWrongTotalAttemptTicks = 0;
                return false;
            }

            IBaritone b = getBaritone();
            if (b != null && b.getPathingBehavior().isPathing()) {
                b.getPathingBehavior().cancelEverything();
            }

            scaffoldingSolver.equipBestToolForScaffold(player, wrongBlockWorld);

            rotationController.requestRotation(
                    bestBreakRot,
                    AstraRotationController.AimPriority.PLACEMENT_RAYCAST,
                    4);
            rotationController.stepImmediatelyWithinTick();

            Vec3 lookVec = AstraRotationController.calculateViewVector(player.getXRot(), player.getYRot());
            Vec3 rayEnd = eyePos.add(lookVec.scale(BlockStatePlacementEngine.SURVIVAL_REACH_DISTANCE));
            BlockHitResult hit = level.clip(new ClipContext(
                    eyePos,
                    rayEnd,
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    player));

            // KLUCZOWA NAPRAWA DRŻENIA KAMERY (JITTER FIX):
            // Zawsze utrzymujemy currentPhase = Phase.BREAKING_WRONG_BLOCK i zwracamy true przez pierwsze 12 ticków
            // obracania kamery do bestBreakRot, aby Baritone i PlacementEngine NIE nadpisywały rotacji w tym samym ticku!
            currentPhase = Phase.BREAKING_WRONG_BLOCK;

            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(wrongBlockPos)) {
                if (!breakingWrongStarted) {
                    breakingWrongStarted = true;
                    breakingWrongTicks = 0;
                    GrimPacketOrder.startBreak(player, wrongBlockPos, hit.getDirection());
                    recordProductiveAction();
                } else if (mc.gameMode != null) {
                    breakingWrongTicks++;
                    if (mc.gameMode.continueDestroyBlock(wrongBlockPos, hit.getDirection())) {
                        player.swing(InteractionHand.MAIN_HAND);
                        recordProductiveAction();
                    }
                }
                setStatusBannerMessage("⛏ Poprawianie bloku: "
                        + wrongBlockWorld.getBlock().getName().getString() + " [" + wrongBlockPos.toShortString() + "]");
                return true;
            }

            // Kamera jest w trakcie płynnego obrotu GCD do wrongBlockPos (ticki 1..12) — czekaj bez zrywania fazy!
            if (this.breakingWrongTotalAttemptTicks <= 12) {
                setStatusBannerMessage("🎯 Celowanie w błędny blok: [" + wrongBlockPos.toShortString() + "]");
                return true;
            } else {
                if (mc.gameMode != null) {
                    mc.gameMode.stopDestroyBlock();
                }
                buildQueue.deferVoxel(wrongBlockPos, 35);
                this.breakingWrongStarted = false;
                this.breakingWrongPos = null;
                this.breakingWrongTicks = 0;
                this.breakingWrongTotalAttemptTicks = 0;
                return false;
            }
        }

        if (breakingWrongStarted) {
            if (mc.gameMode != null) {
                mc.gameMode.stopDestroyBlock();
            }
            breakingWrongStarted = false;
            breakingWrongPos = null;
            breakingWrongTicks = 0;
            breakingWrongTotalAttemptTicks = 0;
        }

        return false;
    }

    /**
     * Szuka odsłoniętego punktu na dowolnej ściance `targetPos`, w który trafia raycast `OUTLINE`.
     * Rozwiązuje problem, w którym `Vec3.atCenterOf(wrongBlockPos)` jest zasłonięty przez sąsiedni blok.
     */
    private Rotation findVisibleBreakRotation(
            LocalPlayer player,
            Level level,
            Vec3 eyePos,
            BlockPos targetPos) {
        BlockState targetState = level.getBlockState(targetPos);
        if (targetState.isAir() || targetState.canBeReplaced() || targetState.getBlock() instanceof LiquidBlock || !targetState.getFluidState().isEmpty()) {
            return null;
        }
        List<Vec3> candidatePoints = new ArrayList<>(13);
        candidatePoints.add(Vec3.atCenterOf(targetPos));
        for (Direction face : Direction.values()) {
            candidatePoints.add(BlockStatePlacementEngine.computeFaceSamplePoint(targetPos, face, 0.50D, 0.50D));
            candidatePoints.add(BlockStatePlacementEngine.computeFaceSamplePoint(targetPos, face, 0.50D, 0.75D));
        }

        for (Vec3 pt : candidatePoints) {
            if (eyePos.distanceToSqr(pt) > BlockStatePlacementEngine.SURVIVAL_REACH_DISTANCE
                    * BlockStatePlacementEngine.SURVIVAL_REACH_DISTANCE) {
                continue;
            }
            Rotation raw = BlockStatePlacementEngine.computeRotationToVec(eyePos, pt);
            Rotation quantized = rotationController.quantizeToPlayerGrid(player, raw.getYaw(), raw.getPitch());
            Vec3 lookVec = AstraRotationController.calculateViewVector(quantized.getPitch(), quantized.getYaw());
            Vec3 rayEnd = eyePos.add(lookVec.scale(BlockStatePlacementEngine.SURVIVAL_REACH_DISTANCE));
            BlockHitResult hit = level.clip(new ClipContext(
                    eyePos,
                    rayEnd,
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(targetPos)) {
                return quantized;
            }
        }
        return null;
    }

    private boolean processFastSynchronizedPlacement(LocalPlayer player, Level level) {
        if (aimLockedTask != null) {
            BlockPos targetPos = aimLockedTask.targetPos();
            BlockState desiredState = aimLockedTask.desiredState();

            if (isVoxelAlreadySatisfied(level.getBlockState(targetPos), desiredState)) {
                if (!aimLockedTask.isScaffold()) {
                    buildQueue.markCompleted(targetPos);
                } else {
                    scaffoldingSolver.onScaffoldStepPlaced(targetPos);
                }
                clearAimLockState(player);
                return false;
            }

            Optional<BlockStatePlacementEngine.PlacementPlan> refreshedOpt = placementEngine
                    .computePlacementPlan(targetPos, desiredState, aimLockedTask.isScaffold());

            if (refreshedOpt.isEmpty()) {
                clearAimLockState(player);
                return false;
            }

            currentPlan = refreshedOpt.get();
            placementEngine.selectHotbarSlotForPlan(currentPlan);

            if (currentPlan.requiresSneak() && !player.isShiftKeyDown()) {
                setPlayerSneak(player, true);
                this.sneakAppliedByFsm = true;
            }

            // Zatrzymaj ruch poziomy podczas precyzyjnego celowania w bloki kierunkowe (Tłoki, Przekaźniki)
            if (!aimLockedTask.isScaffold() && DirectionalStateSolver.isOrientationSensitive(desiredState)) {
                IBaritone b = getBaritone();
                if (b != null) {
                    if (b.getPathingBehavior().isPathing()) {
                        b.getPathingBehavior().cancelEverything();
                    }
                    b.getInputOverrideHandler().clearAllKeys();
                    if (player.isShiftKeyDown()) {
                        b.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                    }
                }
            }

            rotationController.requestRotation(
                    currentPlan.requiredRotation(),
                    AstraRotationController.AimPriority.PLACEMENT_RAYCAST,
                    3);
            rotationController.stepImmediatelyWithinTick();

            // Dla bloków wrażliwych na orientację (Repeater, Comparator, Piston, Observer, Stairs)
            // wymagamy co najmniej 1 pełnego ticku (aimLockTicks >= 1) ORAZ potwierdzenia, że rotacja
            // wysłana na serwer w poprzednim pakiecie MovePlayerPacket również daje 100% poprawny kierunek!
            boolean requiresServerRotSync = !aimLockedTask.isScaffold()
                    && DirectionalStateSolver.isOrientationSensitive(desiredState);

            if ((!requiresServerRotSync || aimLockTicks >= 1)
                    && placementEngine.canExecuteLockedPlacement(currentPlan, !aimLockedTask.isScaffold())) {
                this.currentPhase = Phase.VERIFY_AND_PLACE;
                if (placementEngine.executePlacement(currentPlan, !aimLockedTask.isScaffold())) {
                    onVoxelSuccessfullyPlaced(aimLockedTask);
                    clearAimLockState(player);
                    int postClicks = DirectionalStateSolver.getInitialPostPlacementClicks(desiredState);
                    this.cooldownTicks = postClicks > 0 ? 1 : (auraPrinterMode ? 0 : this.placementCooldownTicks);
                    this.currentPhase = Phase.COOLDOWN;
                    return false;
                }
            }

            aimLockTicks++;
            if (aimLockTicks > MAX_AIM_LOCK_TICKS) {
                buildQueue.recordFailedAttempt(targetPos);
                buildQueue.deferVoxel(targetPos, 30);
                clearAimLockState(player);
                return false;
            }

            this.currentPhase = Phase.AIM_LOCK;
            return true;
        }

        Optional<SchematicBuildQueue.BuildTask> candidateOpt = Optional.empty();
        if (activeTask != null && navigator.isInPositionForTask(
                activeTask.targetPos(), activeTask.desiredState(), activeTask.isScaffold())) {
            candidateOpt = Optional.of(activeTask);
        }
        if (candidateOpt.isEmpty()) {
            candidateOpt = buildQueue.findImmediatePlaceableTask();
        }

        if (candidateOpt.isEmpty()) {
            return false;
        }

        SchematicBuildQueue.BuildTask task = candidateOpt.get();
        Optional<BlockStatePlacementEngine.PlacementPlan> planOpt = placementEngine
                .computePlacementPlan(task.targetPos(), task.desiredState(), task.isScaffold());

        if (planOpt.isEmpty()) {
            return false;
        }

        BlockStatePlacementEngine.PlacementPlan plan = planOpt.get();
        this.aimLockedTask = task;
        this.currentPlan = plan;
        this.aimLockTicks = 0;

        placementEngine.selectHotbarSlotForPlan(plan);
        if (plan.requiresSneak() && !player.isShiftKeyDown()) {
            setPlayerSneak(player, true);
            this.sneakAppliedByFsm = true;
        }

        boolean requiresServerRotSync = !task.isScaffold()
                && DirectionalStateSolver.isOrientationSensitive(task.desiredState());

        if (requiresServerRotSync) {
            IBaritone b = getBaritone();
            if (b != null) {
                if (b.getPathingBehavior().isPathing()) {
                    b.getPathingBehavior().cancelEverything();
                }
                b.getInputOverrideHandler().clearAllKeys();
                if (player.isShiftKeyDown()) {
                    b.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                }
            }
        }

        rotationController.requestRotation(
                plan.requiredRotation(),
                AstraRotationController.AimPriority.PLACEMENT_RAYCAST,
                3);
        rotationController.stepImmediatelyWithinTick();

        // Natychmiastowe postawienie w ticku 0 dozwolone TYLKO wtedy, gdy serwer ma już zsynchronizowaną
        // poprawną rotację (lub blok jest zwykłym blokiem pełnym niewrażliwym na kierunek)
        if (!requiresServerRotSync
                && placementEngine.canExecuteLockedPlacement(plan, !task.isScaffold())) {
            this.currentPhase = Phase.VERIFY_AND_PLACE;
            if (placementEngine.executePlacement(plan, !task.isScaffold())) {
                onVoxelSuccessfullyPlaced(task);
                clearAimLockState(player);
                int postClicks = DirectionalStateSolver.getInitialPostPlacementClicks(task.desiredState());
                this.cooldownTicks = postClicks > 0 ? 1 : (auraPrinterMode ? 0 : this.placementCooldownTicks);
                this.currentPhase = Phase.COOLDOWN;
                return false;
            }
        }

        this.currentPhase = Phase.AIM_LOCK;
        return true;
    }

    private void onVoxelSuccessfullyPlaced(SchematicBuildQueue.BuildTask completedTask) {
        recordProductiveAction();
        this.taskStuckTicks = 0;
        BlockPos pos = completedTask.targetPos();
        BlockState desired = completedTask.desiredState();

        if (!completedTask.isScaffold()) {
            // Jeśli blok wymaga dodatkowego kliknięcia po postawieniu (np. Repeater z DELAY
            // > 1,
            // podwójny półblok SlabType.DOUBLE, Komparator SUBTRACT), zostaw go w kolejce
            // dla POST_INTERACT!
            if (DirectionalStateSolver.getInitialPostPlacementClicks(desired) == 0
                    && !DirectionalStateSolver.isDoubleSlab(desired)) {
                buildQueue.markCompleted(pos);
            } else {
                buildQueue.recordLastPlaced(pos);
            }
        } else {
            buildQueue.recordLastPlaced(pos);
            scaffoldingSolver.onScaffoldStepPlaced(pos);
            recordScaffoldPlacement(pos);
        }
        if (activeTask != null && activeTask.targetPos().equals(pos)
                && DirectionalStateSolver.getInitialPostPlacementClicks(desired) == 0
                && !DirectionalStateSolver.isDoubleSlab(desired)) {
            activeTask = null;
        }

        int ring = buildQueue.getRingDepth(pos);
        String modeTag = completedTask.isScaffold()
                ? "RUSZTOWANIE"
                : (ring == 0 ? "OBRYS/MECHANIZM" : ("ŚRODEK R" + ring));
        setStatusBannerMessage("✔ [" + modeTag + "] Postawiono "
                + desired.getBlock().getName().getString()
                + " [" + pos.toShortString() + "] (Pozostało: "
                + buildQueue.getPendingVoxels().size() + ")");
    }

    private void clearAimLockState(LocalPlayer player) {
        this.aimLockedTask = null;
        this.currentPlan = null;
        this.aimLockTicks = 0;
        if (this.sneakAppliedByFsm
                && !bridgeEngine.isActive()
                && !pillarEngine.isActive()
                && this.ledgeSneakHoldTicks <= 0
                && mc.level != null
                && !isPlayerNearDangerousLedge(player, mc.level)) {
            setPlayerSneak(player, false);
            this.sneakAppliedByFsm = false;
        }
    }

    private void setPlayerSneak(LocalPlayer player, boolean sneak) {
        IBaritone b = getBaritone();
        if (b != null) {
            b.getInputOverrideHandler().setInputForceState(Input.SNEAK, sneak);
        }
        if (mc.options != null) {
            mc.options.keyShift.setDown(sneak);
        }
        if (player != null) {
            player.setShiftKeyDown(sneak);
        }
    }

    private void executeNavigationAndBridgingPass(LocalPlayer player, Level level) {
            if (activeTask != null) {
                BlockState existing = level.getBlockState(activeTask.targetPos());
                if (isVoxelAlreadySatisfied(existing, activeTask.desiredState())) {
                    if (!activeTask.isScaffold()) {
                        buildQueue.markCompleted(activeTask.targetPos());
                    } else {
                        scaffoldingSolver.onScaffoldStepPlaced(activeTask.targetPos());
                    }
                    activeTask = null;
                    taskStuckTicks = 0;
                } else if (!activeTask.isScaffold() && buildQueue.isDeferred(activeTask.targetPos())) {
                    activeTask = null;
                    taskStuckTicks = 0;
                }
            }

            if (activeTask == null) {
                Optional<SchematicBuildQueue.BuildTask> immediate = buildQueue.findImmediatePlaceableTask();
                if (immediate.isPresent()) {
                    activeTask = immediate.get();
                    taskStuckTicks = 0;
                    IBaritone b = getBaritone();
                    if (b != null && b.getPathingBehavior().isPathing()) {
                        b.getPathingBehavior().cancelEverything();
                        b.getInputOverrideHandler().clearAllKeys();
                    }
                } else {
                    Optional<SchematicBuildQueue.BuildTask> next = buildQueue.pollNextTask();
                    if (next.isEmpty()) {
                        return;
                    }
                    activeTask = next.get();
                    taskStuckTicks = 0;
                }
            }

            if (navigator.isCollisionMovementActive()
                    || placementEngine.isIntersectingPlayer(player, level, activeTask.targetPos(), activeTask.desiredState())) {
                boolean steppedAside = navigator.resolvePlayerCollisionDeadlock(
                        activeTask.targetPos(),
                        activeTask.desiredState());
                if (!steppedAside && pillarEngine.canStartVerticalEscapePillar(
                        player, level, activeTask.targetPos(), activeTask.desiredState())) {
                    pillarEngine.startPillar(player.blockPosition(), activeTask.desiredState(), activeTask.isScaffold());
                } else if (!steppedAside && !navigator.isCollisionMovementActive()) {
                    buildQueue.deferVoxel(activeTask.targetPos(), 100);
                    activeTask = null;
                    taskStuckTicks = 0;
                }
                return;
            } else {
                navigator.clearCollisionMovementIfActive();
            }

            // 1. Jeśli sam docelowy blok nie ma jeszcze oparcia, sprawdź czy blok pod spodem jest częścią schematu
            if (!placementEngine.hasValidSupportForState(activeTask.targetPos(), activeTask.desiredState())) {
                BlockPos targetPos = activeTask.targetPos();
                BlockPos below = targetPos.below();
                // Jeśli blok pod spodem czeka w kolejce na wybudowanie, NIE buduj rusztowania z ziemi!
                if (buildQueue.getPendingVoxels().containsKey(below)) {
                    buildQueue.deferVoxel(targetPos, 20);
                    activeTask = null;
                    taskStuckTicks = 0;
                    return;
                }
                Optional<SchematicBuildQueue.BuildTask> scaffoldStep = scaffoldingSolver.findNextScaffoldTask(
                        DirectionalStateSolver.requiresBottomSupportOnly(activeTask.desiredState())
                                ? below
                                : targetPos);
                if (scaffoldStep.isPresent()) {
                    this.activeTask = scaffoldStep.get();
                    this.taskStuckTicks = 0;
                }
            }

            // 2. Sprawdź, czy gracz jest już w idealnej pozycji osiowej
            if (navigator.isInPositionForTask(activeTask.targetPos(), activeTask.desiredState(), activeTask.isScaffold())) {
                navigator.clearAxialMicroCenteringIfActive();
                IBaritone b = getBaritone();
                if (b != null && b.getPathingBehavior().isPathing()) {
                    b.getPathingBehavior().cancelEverything();
                    b.getInputOverrideHandler().clearAllKeys();
                }
                taskStuckTicks = 0;
                return;
            }

            // 3. Jeśli gracz stoi już na docelowym bloku osiowym przed tłokiem/przekaźnikiem, ale jego
            // współrzędne sub-blokowe (Vec3) wymagają dociągnięcia do środka osi 90°:
            if (!activeTask.isScaffold()
                    && navigator.tryMicroCenterInAxialCorridor(player, level, activeTask.targetPos(), activeTask.desiredState())) {
                setStatusBannerMessage("🎯 Centrowanie osiowe 90° dla: "
                        + activeTask.desiredState().getBlock().getName().getString()
                        + " [" + activeTask.targetPos().toShortString() + "]");
                return;
            } else {
                navigator.clearAxialMicroCenteringIfActive();
            }

            IBaritone b = getBaritone();
            boolean inWater = player.isInWater() || !level.getFluidState(player.blockPosition()).isEmpty();
            double speedSq = player.getDeltaMovement().horizontalDistanceSqr();
            boolean activelyWalking = b != null
                    && b.getPathingBehavior().isPathing()
                    && (speedSq > (inWater ? 0.0003D : 0.0015D) || (inWater && Math.abs(player.getDeltaMovement().y) > 0.005D));

            if (activelyWalking) {
                recordProductiveAction();
                if (taskStuckTicks > 0) {
                    taskStuckTicks = Math.max(0, taskStuckTicks - 2);
                }
            } else {
                taskStuckTicks++;
            }

            if (taskStuckTicks > MAX_TASK_STUCK_TICKS) {
                buildQueue.recordFailedAttempt(activeTask.targetPos());
                buildQueue.deferVoxel(activeTask.targetPos(), 140);
                scaffoldingSolver.clearCachedChain();
                navigator.reset();
                IBaritone b2 = getBaritone();
                if (b2 != null) {
                    if (b2.getPathingBehavior().isPathing()) {
                        b2.getPathingBehavior().cancelEverything();
                    }
                    b2.getInputOverrideHandler().clearAllKeys();
                }
                activeTask = null;
                taskStuckTicks = 0;
                return;
            }

            int ringDepth = buildQueue.getRingDepth(activeTask.targetPos());
            String ringLabel = activeTask.isScaffold()
                    ? "RUSZTOWANIE"
                    : (ringDepth == 0 ? "OBRYS/MECHANIZM" : ("ŚRODEK R" + ringDepth));
            setStatusBannerMessage("Idę do [" + ringLabel + "]: "
                    + activeTask.desiredState().getBlock().getName().getString()
                    + " [" + activeTask.targetPos().toShortString() + "]");

            SchematicBuildNavigator.NavigationStatus navStatus = navigator.navigateToTarget(
                    activeTask.targetPos(),
                    activeTask.desiredState(),
                    activeTask.isScaffold());

            if (navStatus == SchematicBuildNavigator.NavigationStatus.REQUIRES_BRIDGE_OR_SCAFFOLD) {
                if (pillarEngine.shouldPillarForTarget(player, level, activeTask.targetPos())) {
                    setStatusBannerMessage("⬆ Podbudowywanie w górę do [" + activeTask.targetPos().toShortString() + "]");
                    pillarEngine.startPillarForElevation(player, level);
                    return;
                }

                // Jeśli blok docelowy (np. Tłok lub Przekaźnik) ma już oparcie, ale w korytarzu osiowym 90°
                // przed nim brakuje podłogi (powietrze poza krawędzią platformy), wyznacz rusztowanie pod stanowisko stania!
                BlockPos scaffoldGoalPos = activeTask.targetPos();
                if (!activeTask.isScaffold()
                        && placementEngine.hasValidSupportForState(activeTask.targetPos(), activeTask.desiredState())) {
                    BlockPos axialStandGround = navigator.findMissingAxialVantageGround(
                            level, activeTask.targetPos(), activeTask.desiredState());
                    if (axialStandGround != null) {
                        scaffoldGoalPos = axialStandGround;
                    }
                }

                Optional<SchematicBuildQueue.BuildTask> scaffoldStep = scaffoldingSolver
                        .findNextScaffoldTask(scaffoldGoalPos);
                boolean steppedScaffold = false;
                if (scaffoldStep.isPresent() && !scaffoldStep.get().targetPos().equals(activeTask.targetPos())) {
                    SchematicBuildQueue.BuildTask step = scaffoldStep.get();
                    this.activeTask = step;
                    this.taskStuckTicks = 0;
                    steppedScaffold = true;

                    if (navigator.isInPositionForTask(step.targetPos(), step.desiredState(), true)) {
                        return;
                    }
                    if (pillarEngine.shouldPillarForTarget(player, level, step.targetPos())) {
                        pillarEngine.startPillarForElevation(player, level);
                        return;
                    }
                }

                BlockPos bridgeGoal = activeTask.targetPos();
                if (bridgeEngine.shouldBridge(bridgeGoal, activeTask.desiredState())) {
                    setStatusBannerMessage("⚡ Smart-Bridge do stanowiska [" + bridgeGoal.toShortString() + "]");
                    bridgeEngine.tickBridge(bridgeGoal, activeTask.desiredState());
                } else if (!steppedScaffold) {
                    buildQueue.deferVoxel(activeTask.targetPos(), 180);
                    activeTask = null;
                    taskStuckTicks = 0;
                }
            }
        }

    // =========================================================================
    // SCAFFOLDING AUTO-CLEANUP ENGINE (REVERSE LIFO ORDER)
    // =========================================================================

    private void executeScaffoldCleanupPass(LocalPlayer player, Level level) {
        placedScaffoldPositions.removeIf(pos -> {
            if (allSchematicPositions.contains(pos)) {
                return true;
            }
            BlockState state = level.getBlockState(pos);
            return state.isAir() || state.canBeReplaced() || !state.getFluidState().isEmpty();
        });

        if (placedScaffoldPositions.isEmpty()) {
            if (mc.gameMode != null) {
                mc.gameMode.stopDestroyBlock();
            }
            stopBuild();
            setStatusBannerMessage("✔ Budowa i czyszczenie rusztowań ukończone w 100%!");
            return;
        }

        if (activeCleanupPos == null || !placedScaffoldPositions.contains(activeCleanupPos)) {
            List<BlockPos> reversed = new ArrayList<>(placedScaffoldPositions);
            reversed.sort(Comparator.<BlockPos>comparingInt(p -> p.getY()).reversed()
                    .thenComparingDouble(p -> p.distSqr(player.blockPosition())));
            activeCleanupPos = reversed.get(0);
            cleanupBreakingStarted = false;
            cleanupStuckTicks = 0;
        }

        BlockState targetState = level.getBlockState(activeCleanupPos);
        if (targetState.isAir() || targetState.canBeReplaced()) {
            placedScaffoldPositions.remove(activeCleanupPos);
            recordProductiveAction();
            activeCleanupPos = null;
            cleanupBreakingStarted = false;
            return;
        }

        Vec3 eyePos = player.getEyePosition(1.0F);
        Vec3 blockCenter = Vec3.atCenterOf(activeCleanupPos);
        double distSq = eyePos.distanceToSqr(blockCenter);

        if (distSq <= BlockStatePlacementEngine.SURVIVAL_REACH_DISTANCE
                * BlockStatePlacementEngine.SURVIVAL_REACH_DISTANCE) {
            IBaritone b = getBaritone();
            if (b != null && b.getPathingBehavior().isPathing()) {
                b.getPathingBehavior().cancelEverything();
            }

            scaffoldingSolver.equipBestToolForScaffold(player, targetState);

            Rotation lookRot = BlockStatePlacementEngine.computeRotationToVec(eyePos, blockCenter);
            Rotation quantized = rotationController.quantizeToPlayerGrid(player, lookRot.getYaw(), lookRot.getPitch());
            rotationController.requestRotation(
                    quantized,
                    AstraRotationController.AimPriority.PLACEMENT_RAYCAST,
                    4);
            rotationController.stepImmediatelyWithinTick();

            Vec3 lookVec = AstraRotationController.calculateViewVector(player.getXRot(), player.getYRot());
            Vec3 rayEnd = eyePos.add(lookVec.scale(BlockStatePlacementEngine.SURVIVAL_REACH_DISTANCE));
            BlockHitResult hit = level.clip(new ClipContext(
                    eyePos,
                    rayEnd,
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    player));

            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(activeCleanupPos)) {
                Direction hitFace = hit.getDirection();
                if (!cleanupBreakingStarted) {
                    GrimPacketOrder.startBreak(player, activeCleanupPos, hitFace);
                    cleanupBreakingStarted = true;
                    recordProductiveAction();
                } else if (mc.gameMode != null) {
                    if (mc.gameMode.continueDestroyBlock(activeCleanupPos, hitFace)) {
                        player.swing(InteractionHand.MAIN_HAND);
                        recordProductiveAction();
                    }
                }
                setStatusBannerMessage("⛏ Czyszczenie rusztowań: [" + activeCleanupPos.toShortString()
                        + "] (Pozostało: " + placedScaffoldPositions.size() + ")");
                return;
            }
        }

        cleanupBreakingStarted = false;
        cleanupStuckTicks++;
        if (cleanupStuckTicks > MAX_TASK_STUCK_TICKS * 2) {
            placedScaffoldPositions.remove(activeCleanupPos);
            activeCleanupPos = null;
            cleanupStuckTicks = 0;
            return;
        }

        IBaritone b = getBaritone();
        if (b != null && !b.getPathingBehavior().isPathing()) {
            b.getCustomGoalProcess().setGoalAndPath(new GoalNear(activeCleanupPos, 2));
        }
    }


    /**
     * Dokładna weryfikacja czy blok w świecie (`worldState`) zgadza się ze
     * schematem (`desiredState`),
     * uwzględniając kierunek (`FACING`), połowę schodów/półbloków
     * (`HALF`/`SLAB_TYPE`), oś pnia (`AXIS`),
     * oraz opóźnienie przekaźnika (`DELAY`) i tryb komparatora (`MODE_COMPARATOR`)!
     */
    // =========================================================================
    // POPRAWKA C.1: AKCEPTACJA ISTNIEJĄCEJ WODY (ŹRÓDŁA I PRZEPŁYWU) ORAZ ZIEMI/TRAWY
    // =========================================================================

    public static boolean isVoxelAlreadySatisfied(BlockState worldState, BlockState desiredState) {
        if (worldState == null) {
            return false;
        }
        if (desiredState == null) {
            return !worldState.isAir() && !worldState.canBeReplaced() && worldState.getFluidState().isEmpty();
        }

        // 1. Jeśli schemat oczekuje wody (Blocks.WATER), a w świecie gry na tej pozycji znajduje się już
        // źródło lub woda płynąca (FluidTags.WATER), uznaj woksel za natychmiast spełniony!
        if (desiredState.getBlock() == Blocks.WATER || desiredState.getFluidState().is(FluidTags.WATER)) {
            if (worldState.getBlock() == Blocks.WATER || worldState.getFluidState().is(FluidTags.WATER)) {
                // Jeśli to sam płyn w schemacie, każda obecność wody w rynnie spełnia zadanie:
                if (desiredState.getBlock() == Blocks.WATER) {
                    return true;
                }
            }
        }

        // 2. Analogiczna obsługa dla lawy (Blocks.LAVA)
        if (desiredState.getBlock() == Blocks.LAVA) {
            return worldState.getBlock() == Blocks.LAVA || worldState.getFluidState().is(FluidTags.LAVA);
        }

        if (worldState.isAir()) {
            return false;
        }

        if (!isSameBlockFamily(worldState.getBlock(), desiredState.getBlock())) {
            return false;
        }
        return DirectionalStateSolver.areStatePropertiesSatisfied(worldState, desiredState);
    }

    /**
     * Traktuje Ziemię (DIRT) i Blok Trawy (GRASS_BLOCK / COARSE_DIRT / PODZOL) jako tę samą rodzinę podłoża,
     * aby bot nigdy nie niszczył ziemi pod trzciną cukrową lub pod własnymi stopami, gdy w schemacie była trawa!
     */
    public static boolean isSameBlockFamily(Block a, Block b) {
        if (a == b) {
            return true;
        }
        if (isDirtOrGrassFamily(a) && isDirtOrGrassFamily(b)) {
            return true;
        }
        if ((a == Blocks.TORCH || a == Blocks.WALL_TORCH) && (b == Blocks.TORCH || b == Blocks.WALL_TORCH)) {
            return true;
        }
        if ((a == Blocks.REDSTONE_TORCH || a == Blocks.REDSTONE_WALL_TORCH)
                && (b == Blocks.REDSTONE_TORCH || b == Blocks.REDSTONE_WALL_TORCH)) {
            return true;
        }
        if ((a == Blocks.SOUL_TORCH || a == Blocks.SOUL_WALL_TORCH)
                && (b == Blocks.SOUL_TORCH || b == Blocks.SOUL_WALL_TORCH)) {
            return true;
        }
        return false;
    }

    private static boolean isDirtOrGrassFamily(Block b) {
        return b == Blocks.DIRT
                || b == Blocks.GRASS_BLOCK
                || b == Blocks.COARSE_DIRT
                || b == Blocks.PODZOL
                || b == Blocks.MYCELIUM
                || b == Blocks.ROOTED_DIRT;
    }

    public Phase getCurrentPhase() {
        return currentPhase;
    }

    public boolean isActive() {
        return active;
    }

    public AstraRotationController getRotationController() {
        return rotationController;
    }

    public AuxiliaryBlockManager getAuxiliaryBlockManager() {
        return auxiliaryBlockManager;
    }

    public BlockStatePlacementEngine getPlacementEngine() {
        return placementEngine;
    }

    public SchematicBuildNavigator getNavigator() {
        return navigator;
    }

    public BedwarsBridgeEngine getBridgeEngine() {
        return bridgeEngine;
    }

    public PillarUpEngine getPillarEngine() {
        return pillarEngine;
    }

    public ScaffoldingSolver getScaffoldingSolver() {
        return scaffoldingSolver;
    }

    // =========================================================================
    // 0. DIRECTIONAL STATE SOLVER (STRICT 90° AXIAL PISTONS + 3-STAGE ORDERING)
    // =========================================================================

    public static final class DirectionalStateSolver {

        // Maksymalne odchylenie od osi kardynalnej (0°, 90°, 180°, -90°) dla poziomych tłoków i obserwatorów.
        // Wartość 20.0° całkowicie eliminuje stawianie pod skosem 50°-60°!
        public static final float MAX_PISTON_AXIAL_YAW_ERROR = 20.0F;
        // Maksymalny kąt pochylenia w dół/górę dla poziomych tłoków i obserwatorów (do 39.0° w osi kardynalnej)
        public static final float HORIZONTAL_DOMINANCE_MAX_PITCH = 39.0F;
        // Minimalny kąt pochylenia dla pionowych tłoków i obserwatorów (UP / DOWN)
        public static final float VERTICAL_DOMINANCE_MIN_PITCH = 52.0F;

        private DirectionalStateSolver() {
        }

        public static boolean isOrientationSensitive(BlockState desiredState) {
            if (desiredState == null) {
                return false;
            }
            Block block = desiredState.getBlock();
            return block instanceof DiodeBlock
                    || block instanceof PistonBaseBlock
                    || block instanceof ObserverBlock
                    || block instanceof DispenserBlock
                    || block instanceof StairBlock
                    || block instanceof SlabBlock
                    || block instanceof BaseRailBlock
                    || block instanceof HopperBlock
                    || block instanceof RotatedPillarBlock
                    || block instanceof FaceAttachedHorizontalDirectionalBlock
                    || block instanceof TrapDoorBlock
                    || block instanceof FenceGateBlock
                    || desiredState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                    || desiredState.hasProperty(BlockStateProperties.FACING);
        }

        /**
         * Zwraca kierunek, w którym gracz MUSI patrzeć, aby postawić blok 3D (Tłok, Dozownik, Obserwator)
         * lub blok poziomy (Przekaźnik, Komparator) zgodnie ze schematem.
         */
        public static Direction getRequiredLookDirection(BlockState desiredState) {
            if (desiredState == null) {
                return null;
            }
            Block block = desiredState.getBlock();
            if ((block instanceof PistonBaseBlock || block instanceof DispenserBlock)
                    && desiredState.hasProperty(BlockStateProperties.FACING)) {
                return desiredState.getValue(BlockStateProperties.FACING).getOpposite();
            }
            if (block instanceof ObserverBlock && desiredState.hasProperty(BlockStateProperties.FACING)) {
                return desiredState.getValue(BlockStateProperties.FACING);
            }
            if (block instanceof DiodeBlock && desiredState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                return desiredState.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
            }
            if (block instanceof StairBlock && desiredState.hasProperty(StairBlock.FACING)) {
                return desiredState.getValue(StairBlock.FACING);
            }
            if (block instanceof BaseRailBlock) {
                RailShape shape = getRailShape(desiredState);
                if (shape != null) {
                    if (shape == RailShape.EAST_WEST || shape == RailShape.ASCENDING_EAST || shape == RailShape.ASCENDING_WEST) {
                        return Direction.EAST;
                    }
                    if (shape == RailShape.NORTH_SOUTH || shape == RailShape.ASCENDING_NORTH || shape == RailShape.ASCENDING_SOUTH) {
                        return Direction.NORTH;
                    }
                }
            }
            return null;
        }

        /**
         * Zwraca kierunek od `targetPos`, po którym musi stanąć gracz, aby patrzeć na blok
         * pod kątem prostym (90° osiowo na wprost).
         */
        public static Direction getRequiredStandingSide(BlockState desiredState) {
            Direction reqLook = getRequiredLookDirection(desiredState);
            return reqLook != null ? reqLook.getOpposite() : null;
        }

        /**
         * Oblicza bezwzględny błąd kątowy Yaw (w stopniach 0..180) względem idealnej osi kardynalnej.
         */
        public static float computeAxialYawErrorDegrees(float yawDeg, Direction requiredHorizontalLook) {
            if (requiredHorizontalLook == null || !requiredHorizontalLook.getAxis().isHorizontal()) {
                return 0.0F;
            }
            float idealYaw = requiredHorizontalLook.toYRot();
            return Math.abs(Mth.wrapDegrees(yawDeg - idealYaw));
        }

        /**
         * Oblicza karę kątową dla sortowania planów stawiania bloków kierunkowych (im bliżej 0,
         * tym bardziej prostopadły i płaski jest strzał raycastu).
         */
        public static double computeAxialOrientationPenalty(Rotation rot, BlockState desiredState) {
            if (rot == null || desiredState == null) {
                return 0.0D;
            }
            Direction reqLook = getRequiredLookDirection(desiredState);
            if (reqLook == null) {
                return 0.0D;
            }
            if (reqLook.getAxis().isHorizontal()) {
                double yawErr = computeAxialYawErrorDegrees(rot.getYaw(), reqLook);
                double pitchAbs = Math.abs(rot.getPitch());
                return (yawErr * yawErr * 3.0D) + (pitchAbs * pitchAbs * 2.0D);
            } else {
                double idealPitch = (reqLook == Direction.DOWN) ? 90.0D : -90.0D;
                double pitchErr = Math.abs(rot.getPitch() - idealPitch);
                return pitchErr * pitchErr * 3.0D;
            }
        }

        public static boolean areStatePropertiesSatisfied(BlockState worldState, BlockState desiredState) {
            Block block = desiredState.getBlock();

            if (block instanceof DiodeBlock) {
                if (worldState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                        && desiredState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                    if (worldState.getValue(BlockStateProperties.HORIZONTAL_FACING) != desiredState
                            .getValue(BlockStateProperties.HORIZONTAL_FACING)) {
                        return false;
                    }
                }
                if (block instanceof RepeaterBlock
                        && worldState.hasProperty(BlockStateProperties.DELAY)
                        && desiredState.hasProperty(BlockStateProperties.DELAY)) {
                    if (!Objects.equals(
                            worldState.getValue(BlockStateProperties.DELAY),
                            desiredState.getValue(BlockStateProperties.DELAY))) {
                        return false;
                    }
                }
                if (block instanceof ComparatorBlock
                        && worldState.hasProperty(BlockStateProperties.MODE_COMPARATOR)
                        && desiredState.hasProperty(BlockStateProperties.MODE_COMPARATOR)) {
                    if (worldState.getValue(BlockStateProperties.MODE_COMPARATOR) != desiredState
                            .getValue(BlockStateProperties.MODE_COMPARATOR)) {
                        return false;
                    }
                }
                return true;
            }

            if (block instanceof StairBlock) {
                if (worldState.getValue(StairBlock.FACING) != desiredState.getValue(StairBlock.FACING)) {
                    return false;
                }
                return worldState.getValue(StairBlock.HALF) == desiredState.getValue(StairBlock.HALF);
            }

            if (block instanceof SlabBlock) {
                return worldState.getValue(SlabBlock.TYPE) == desiredState.getValue(SlabBlock.TYPE);
            }

            if (block instanceof PistonBaseBlock || block instanceof ObserverBlock || block instanceof DispenserBlock) {
                if (worldState.hasProperty(BlockStateProperties.FACING)
                        && desiredState.hasProperty(BlockStateProperties.FACING)) {
                    return worldState.getValue(BlockStateProperties.FACING) == desiredState
                            .getValue(BlockStateProperties.FACING);
                }
            }

            if (block instanceof BaseRailBlock) {
                if (worldState.getBlock() != desiredState.getBlock()) {
                    return false;
                }
                RailShape worldShape = getRailShape(worldState);
                RailShape desiredShape = getRailShape(desiredState);
                if (worldShape != null && desiredShape != null) {
                    return worldShape == desiredShape;
                }
                return true;
            }

            if (block instanceof HopperBlock) {
                return worldState.getValue(HopperBlock.FACING) == desiredState.getValue(HopperBlock.FACING);
            }

            if (block instanceof RotatedPillarBlock) {
                return worldState.getValue(RotatedPillarBlock.AXIS) == desiredState.getValue(RotatedPillarBlock.AXIS);
            }

            if (block instanceof FaceAttachedHorizontalDirectionalBlock) {
                if (worldState.getValue(BlockStateProperties.ATTACH_FACE) != desiredState
                        .getValue(BlockStateProperties.ATTACH_FACE)) {
                    return false;
                }
                if (worldState.getValue(BlockStateProperties.HORIZONTAL_FACING) != desiredState
                        .getValue(BlockStateProperties.HORIZONTAL_FACING)) {
                    return false;
                }
                if (block instanceof LeverBlock
                        && worldState.hasProperty(BlockStateProperties.POWERED)
                        && desiredState.hasProperty(BlockStateProperties.POWERED)) {
                    return Objects.equals(
                            worldState.getValue(BlockStateProperties.POWERED),
                            desiredState.getValue(BlockStateProperties.POWERED));
                }
                return true;
            }

            if (block instanceof TrapDoorBlock) {
                if (worldState.getValue(TrapDoorBlock.HALF) != desiredState.getValue(TrapDoorBlock.HALF)) {
                    return false;
                }
                if (worldState.getValue(TrapDoorBlock.FACING) != desiredState.getValue(TrapDoorBlock.FACING)) {
                    return false;
                }
                return Objects.equals(
                        worldState.getValue(TrapDoorBlock.OPEN),
                        desiredState.getValue(TrapDoorBlock.OPEN));
            }

            if (worldState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                    && desiredState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                return worldState.getValue(BlockStateProperties.HORIZONTAL_FACING) == desiredState
                        .getValue(BlockStateProperties.HORIZONTAL_FACING);
            }

            return true;
        }

        public static int getRequiredPostPlacementRightClicks(BlockState worldState, BlockState desiredState) {
            if (worldState == null || desiredState == null || worldState.isAir()) {
                return 0;
            }
            if (worldState.getBlock() != desiredState.getBlock()) {
                return 0;
            }

            Block block = desiredState.getBlock();

            if (block instanceof RepeaterBlock) {
                if (worldState.getValue(RepeaterBlock.FACING) != desiredState.getValue(RepeaterBlock.FACING)) {
                    return 0;
                }
                int currentDelay = worldState.getValue(RepeaterBlock.DELAY);
                int targetDelay = desiredState.getValue(RepeaterBlock.DELAY);
                if (currentDelay == targetDelay) {
                    return 0;
                }
                return (targetDelay - currentDelay + 4) % 4;
            }

            if (block instanceof ComparatorBlock) {
                if (worldState.getValue(ComparatorBlock.FACING) != desiredState.getValue(ComparatorBlock.FACING)) {
                    return 0;
                }
                ComparatorMode currentMode = worldState.getValue(ComparatorBlock.MODE);
                ComparatorMode targetMode = desiredState.getValue(ComparatorBlock.MODE);
                return currentMode != targetMode ? 1 : 0;
            }

            if (block instanceof LeverBlock) {
                if (worldState.getValue(LeverBlock.FACE) != desiredState.getValue(LeverBlock.FACE)
                        || worldState.getValue(LeverBlock.FACING) != desiredState.getValue(LeverBlock.FACING)) {
                    return 0;
                }
                boolean currentPowered = worldState.getValue(LeverBlock.POWERED);
                boolean targetPowered = desiredState.getValue(LeverBlock.POWERED);
                return currentPowered != targetPowered ? 1 : 0;
            }

            if (block instanceof TrapDoorBlock) {
                if (worldState.getValue(TrapDoorBlock.HALF) != desiredState.getValue(TrapDoorBlock.HALF)
                        || worldState.getValue(TrapDoorBlock.FACING) != desiredState.getValue(TrapDoorBlock.FACING)) {
                    return 0;
                }
                boolean currentOpen = worldState.getValue(TrapDoorBlock.OPEN);
                boolean targetOpen = desiredState.getValue(TrapDoorBlock.OPEN);
                return currentOpen != targetOpen ? 1 : 0;
            }

            return 0;
        }

        public static int getInitialPostPlacementClicks(BlockState desiredState) {
            if (desiredState == null) {
                return 0;
            }
            Block block = desiredState.getBlock();
            if (block instanceof RepeaterBlock) {
                return Math.max(0, desiredState.getValue(RepeaterBlock.DELAY) - 1);
            }
            if (block instanceof ComparatorBlock) {
                return desiredState.getValue(ComparatorBlock.MODE) == ComparatorMode.SUBTRACT ? 1 : 0;
            }
            if (block instanceof LeverBlock) {
                return desiredState.getValue(LeverBlock.POWERED) ? 1 : 0;
            }
            if (block instanceof TrapDoorBlock) {
                return desiredState.getValue(TrapDoorBlock.OPEN) ? 1 : 0;
            }
            return 0;
        }

        public static boolean isDoubleSlab(BlockState desiredState) {
            return desiredState != null
                    && desiredState.getBlock() instanceof SlabBlock
                    && desiredState.getValue(SlabBlock.TYPE) == SlabType.DOUBLE;
        }

        public static RailShape getRailShape(BlockState state) {
            if (state == null) {
                return null;
            }
            if (state.hasProperty(BlockStateProperties.RAIL_SHAPE)) {
                return state.getValue(BlockStateProperties.RAIL_SHAPE);
            }
            if (state.hasProperty(BlockStateProperties.RAIL_SHAPE_STRAIGHT)) {
                return state.getValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT);
            }
            return null;
        }

        public static boolean requiresBreakAndReplace(BlockState worldState, BlockState desiredState) {
            if (worldState == null || desiredState == null || worldState.isAir() || worldState.canBeReplaced()) {
                return false;
            }
            if (worldState.getBlock() instanceof LiquidBlock || !worldState.getFluidState().isEmpty()) {
                return false;
            }
            if (worldState.getBlock() == desiredState.getBlock() && isDoubleSlab(desiredState)) {
                return false;
            }
            if (worldState.getBlock() instanceof BaseRailBlock && desiredState.getBlock() instanceof BaseRailBlock) {
                if (worldState.getBlock() != desiredState.getBlock()) {
                    return true;
                }
                RailShape worldShape = getRailShape(worldState);
                RailShape desiredShape = getRailShape(desiredState);
                if (worldShape != null && desiredShape != null && worldShape != desiredShape) {
                    return true;
                }
                return false;
            }
            if (isVoxelAlreadySatisfied(worldState, desiredState)) {
                return false;
            }
            return getRequiredPostPlacementRightClicks(worldState, desiredState) == 0;
        }

        public static boolean requiresBottomSupportOnly(BlockState desiredState) {
            if (desiredState == null) {
                return false;
            }
            Block block = desiredState.getBlock();
            if (block instanceof DiodeBlock
                    || block instanceof RedStoneWireBlock
                    || block instanceof BaseRailBlock
                    || block instanceof BasePressurePlateBlock
                    || block instanceof DoorBlock
                    || block instanceof BannerBlock
                    || block instanceof StandingSignBlock
                    || block instanceof BushBlock
                    || block instanceof SugarCaneBlock
                    || block instanceof BambooSaplingBlock
                    || block instanceof BambooStalkBlock) {
                return true;
            }
            if (block instanceof TorchBlock && !(block instanceof WallTorchBlock)
                    && !(block instanceof RedstoneWallTorchBlock)) {
                return true;
            }
            if (block instanceof FaceAttachedHorizontalDirectionalBlock
                    && desiredState.hasProperty(BlockStateProperties.ATTACH_FACE)) {
                return desiredState.getValue(BlockStateProperties.ATTACH_FACE) == AttachFace.FLOOR;
            }
            return false;
        }

        public static boolean isSupportFaceCompatibleWithState(
                BlockPos targetPos,
                BlockPos supportPos,
                Direction clickedFace,
                BlockState desiredState) {
            if (desiredState == null) {
                return true;
            }
            Block block = desiredState.getBlock();

            if (requiresBottomSupportOnly(desiredState)) {
                return supportPos.equals(targetPos.below()) && clickedFace == Direction.UP;
            }

            if (block instanceof FaceAttachedHorizontalDirectionalBlock
                    && desiredState.hasProperty(BlockStateProperties.ATTACH_FACE)) {
                AttachFace attachFace = desiredState.getValue(BlockStateProperties.ATTACH_FACE);
                return switch (attachFace) {
                    case FLOOR -> supportPos.equals(targetPos.below()) && clickedFace == Direction.UP;
                    case CEILING -> supportPos.equals(targetPos.above()) && clickedFace == Direction.DOWN;
                    case WALL -> clickedFace == desiredState.getValue(BlockStateProperties.HORIZONTAL_FACING);
                };
            }

            if (block instanceof WallTorchBlock || block instanceof RedstoneWallTorchBlock) {
                if (desiredState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                    return clickedFace == desiredState.getValue(BlockStateProperties.HORIZONTAL_FACING);
                }
            }

            if (block instanceof HopperBlock && desiredState.hasProperty(HopperBlock.FACING)) {
                Direction hopperFacing = desiredState.getValue(HopperBlock.FACING);
                if (hopperFacing == Direction.DOWN) {
                    return clickedFace == Direction.UP || clickedFace == Direction.DOWN;
                }
                return clickedFace == hopperFacing.getOpposite();
            }

            if (block instanceof RotatedPillarBlock && desiredState.hasProperty(RotatedPillarBlock.AXIS)) {
                return clickedFace.getAxis() == desiredState.getValue(RotatedPillarBlock.AXIS);
            }

            if (desiredState.hasProperty(BlockStateProperties.HALF)) {
                Half half = desiredState.getValue(BlockStateProperties.HALF);
                if (half == Half.BOTTOM && clickedFace == Direction.DOWN) {
                    return false;
                }
                if (half == Half.TOP && clickedFace == Direction.UP) {
                    return false;
                }
            }

            if (block instanceof SlabBlock && desiredState.hasProperty(SlabBlock.TYPE)) {
                SlabType slabType = desiredState.getValue(SlabBlock.TYPE);
                if (slabType == SlabType.BOTTOM && clickedFace == Direction.DOWN) {
                    return false;
                }
                if (slabType == SlabType.TOP && clickedFace == Direction.UP) {
                    return false;
                }
            }

            return true;
        }

        public static boolean isHitHeightValidForState(
                Direction clickedFace,
                double hitVecY,
                BlockPos targetPos,
                BlockState desiredState) {
            if (desiredState == null || clickedFace.getAxis() == Direction.Axis.Y) {
                return true;
            }

            double localY = hitVecY - targetPos.getY();

            if (desiredState.hasProperty(BlockStateProperties.HALF)) {
                Half half = desiredState.getValue(BlockStateProperties.HALF);
                if (half == Half.BOTTOM) {
                    return localY >= 0.05D && localY <= 0.45D;
                } else {
                    return localY >= 0.55D && localY <= 0.95D;
                }
            }

            if (desiredState.getBlock() instanceof SlabBlock && desiredState.hasProperty(SlabBlock.TYPE)) {
                SlabType slabType = desiredState.getValue(SlabBlock.TYPE);
                if (slabType == SlabType.BOTTOM) {
                    return localY >= 0.05D && localY <= 0.45D;
                } else if (slabType == SlabType.TOP) {
                    return localY >= 0.55D && localY <= 0.95D;
                }
            }

            return true;
        }

        /**
         * Weryfikuje z rygorem osiowym 90°, czy kąt `quantizedRot` gwarantuje poprawny stan
         * bloku po stronie serwera Minecraft 1.21.4.
         */
        public static boolean doesPlayerLookProduceDesiredOrientation(
                Rotation quantizedRot,
                BlockState desiredState) {
            if (desiredState == null || quantizedRot == null) {
                return true;
            }

            Block block = desiredState.getBlock();
            Direction playerHorizDir = Direction.fromYRot(quantizedRot.getYaw());

            // 1. Schody (StairBlock)
            if (block instanceof StairBlock && desiredState.hasProperty(StairBlock.FACING)) {
                Direction desiredFacing = desiredState.getValue(StairBlock.FACING);
                return playerHorizDir == desiredFacing
                        && computeAxialYawErrorDegrees(quantizedRot.getYaw(), desiredFacing) <= 30.0F;
            }

            // 2. Przekaźniki (Repeater) i Komparatory (Comparator)
            if (block instanceof DiodeBlock && desiredState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                Direction desiredFacing = desiredState.getValue(BlockStateProperties.HORIZONTAL_FACING);
                Direction requiredLook = desiredFacing.getOpposite();
                return playerHorizDir == requiredLook
                        && computeAxialYawErrorDegrees(quantizedRot.getYaw(), requiredLook) <= 25.0F;
            }

            // 3. Furtki (FenceGateBlock)
            if (block instanceof FenceGateBlock && desiredState.hasProperty(FenceGateBlock.FACING)) {
                Direction desiredFacing = desiredState.getValue(FenceGateBlock.FACING);
                return playerHorizDir.getAxis() == desiredFacing.getAxis()
                        && isYawAwayFromBoundary(quantizedRot.getYaw(), 8.0F);
            }

            // 4. Dźwignie i Guziki
            if (block instanceof FaceAttachedHorizontalDirectionalBlock
                    && desiredState.hasProperty(BlockStateProperties.ATTACH_FACE)) {
                AttachFace attach = desiredState.getValue(BlockStateProperties.ATTACH_FACE);
                if (attach != AttachFace.WALL && desiredState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                    Direction desiredFacing = desiredState.getValue(BlockStateProperties.HORIZONTAL_FACING);
                    if (block instanceof LeverBlock) {
                        return playerHorizDir.getAxis() == desiredFacing.getAxis()
                                && isYawAwayFromBoundary(quantizedRot.getYaw(), 8.0F);
                    }
                    return playerHorizDir.getOpposite() == desiredFacing
                            && isYawAwayFromBoundary(quantizedRot.getYaw(), 8.0F);
                }
                return true;
            }

            // 5. Tory (BaseRailBlock):
            // Tory w Minecraft łączą się automatycznie z sąsiednimi torami niezależnie od kąta patrzenia gracza.
            // W ciasnych przestrzeniach (tunele, zakręty, maszynerie) wymuszanie konkretnej osi patrzenia
            // uniemożliwia postawienie toru i całkowicie blokowało bota.
            if (block instanceof BaseRailBlock) {
                return true;
            }

            // 6. Tłoki (PistonBaseBlock), Dozowniki i Podajniki (DispenserBlock):
            // FACING = context.getNearestLookingDirection().getOpposite()
            if ((block instanceof PistonBaseBlock || block instanceof DispenserBlock)
                    && desiredState.hasProperty(BlockStateProperties.FACING)) {
                Direction desiredFacing = desiredState.getValue(BlockStateProperties.FACING);
                Direction requiredLook = desiredFacing.getOpposite();
                return verifyStrict3DAxialOrientation(quantizedRot.getYaw(), quantizedRot.getPitch(), requiredLook);
            }

            // 7. Obserwatory (ObserverBlock):
            // FACING = context.getNearestLookingDirection()
            if (block instanceof ObserverBlock && desiredState.hasProperty(BlockStateProperties.FACING)) {
                Direction desiredFacing = desiredState.getValue(BlockStateProperties.FACING);
                return verifyStrict3DAxialOrientation(quantizedRot.getYaw(), quantizedRot.getPitch(), desiredFacing);
            }

            // 8. Piece, Skrzynie, Klapy
            if (!(block instanceof WallTorchBlock)
                    && !(block instanceof RedstoneWallTorchBlock)
                    && !(block instanceof HopperBlock)
                    && desiredState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                Direction desiredFacing = desiredState.getValue(BlockStateProperties.HORIZONTAL_FACING);
                if (block instanceof AnvilBlock) {
                    return playerHorizDir.getClockWise() == desiredFacing
                            && isYawAwayFromBoundary(quantizedRot.getYaw(), 8.0F);
                }
                return playerHorizDir.getOpposite() == desiredFacing
                        && isYawAwayFromBoundary(quantizedRot.getYaw(), 8.0F);
            }

            return true;
        }

        /**
         * Rygorystyczna weryfikacja osiowa 90° dla bloków 3D (Piston, Dispenser, Observer):
         * - Dla kierunków poziomych wymaga patrzenia na wprost w wąskim korytarzu osiowym
         *   (błąd Yaw <= 20.0° względem osi kardynalnej oraz Pitch w przedziale [-28.5°, +28.5°]).
         * - Dla kierunków pionowych (UP/DOWN) wymaga stromego kąta Pitch (>= 58° lub <= -58°).
         */
        public static boolean verifyStrict3DAxialOrientation(float yawDeg, float pitchDeg, Direction requiredLook) {
            Direction nearestLook = computeNearestLookingDirection(yawDeg, pitchDeg);
            if (nearestLook != requiredLook) {
                return false;
            }

            if (requiredLook.getAxis().isHorizontal()) {
                // 1. Sztywny limit Pitch dla poziomych tłoków: [-28.5°, +28.5°]
                if (pitchDeg < -HORIZONTAL_DOMINANCE_MAX_PITCH || pitchDeg > HORIZONTAL_DOMINANCE_MAX_PITCH) {
                    return false;
                }
                // 2. Sztywny limit osiowości Yaw: maksymalnie 20° odchylenia od kąta prostego 90°!
                // Całkowicie odrzuca strzały pod kątem 50°-60°.
                float axialYawError = computeAxialYawErrorDegrees(yawDeg, requiredLook);
                if (axialYawError > MAX_PISTON_AXIAL_YAW_ERROR) {
                    return false;
                }
            } else {
                if (requiredLook == Direction.DOWN && pitchDeg < VERTICAL_DOMINANCE_MIN_PITCH) {
                    return false;
                }
                if (requiredLook == Direction.UP && pitchDeg > -VERTICAL_DOMINANCE_MIN_PITCH) {
                    return false;
                }
            }

            return verify3DDominanceMargin(yawDeg, pitchDeg, requiredLook);
        }

        public static boolean verify3DDominanceMargin(float yawDeg, float pitchDeg, Direction requiredLook) {
            float pitchRad = pitchDeg * Mth.DEG_TO_RAD;
            float yawRad = -yawDeg * Mth.DEG_TO_RAD;

            double absX = Math.abs(Mth.sin(yawRad) * Mth.cos(pitchRad));
            double absY = Math.abs(Mth.sin(pitchRad));
            double absZ = Math.abs(Mth.cos(yawRad) * Mth.cos(pitchRad));
            double maxHoriz = Math.max(absX, absZ);
            double minHoriz = Math.min(absX, absZ);

            if (requiredLook.getAxis().isHorizontal()) {
                if (Math.abs(pitchDeg) > HORIZONTAL_DOMINANCE_MAX_PITCH) {
                    return false;
                }
                // Składowa pionowa musi być mniejsza od głównej składowej poziomej (tan 39° ≈ 0.81)
                if (absY >= maxHoriz * 0.82D) {
                    return false;
                }
                // Składowa boczna (prostopadła oś pozioma) nie może przekraczać 38% składowej głównej (tan 20° ≈ 0.364)
                return minHoriz <= maxHoriz * 0.38D;
            } else {
                if (Math.abs(pitchDeg) < VERTICAL_DOMINANCE_MIN_PITCH) {
                    return false;
                }
                return absY > maxHoriz * 1.20D;
            }
        }

        public static boolean isYawAwayFromBoundary(float yawDeg, float marginDeg) {
            float wrapped = Mth.wrapDegrees(yawDeg);
            float mod90 = Math.abs(((wrapped + 45.0F) % 90.0F + 90.0F) % 90.0F);
            return mod90 > marginDeg && mod90 < (90.0F - marginDeg);
        }

        public static boolean isHorizontal3DDirectional(BlockState state) {
            if (state == null) {
                return false;
            }
            Block b = state.getBlock();
            if (b instanceof PistonBaseBlock || b instanceof DispenserBlock || b instanceof ObserverBlock) {
                if (state.hasProperty(BlockStateProperties.FACING)) {
                    return state.getValue(BlockStateProperties.FACING).getAxis().isHorizontal();
                }
            }
            return false;
        }

        public static boolean is3DDirectional(BlockState state) {
            if (state == null) {
                return false;
            }
            Block b = state.getBlock();
            return (b instanceof PistonBaseBlock || b instanceof DispenserBlock || b instanceof ObserverBlock)
                    && state.hasProperty(BlockStateProperties.FACING);
        }

        public static Direction computeNearestLookingDirection(float yaw, float pitch) {
            float pitchRad = pitch * Mth.DEG_TO_RAD;
            float yawRad = -yaw * Mth.DEG_TO_RAD;

            float sinPitch = Mth.sin(pitchRad);
            float cosPitch = Mth.cos(pitchRad);
            float sinYaw = Mth.sin(yawRad);
            float cosYaw = Mth.cos(yawRad);

            float absX = Math.abs(sinYaw * cosPitch);
            float absY = Math.abs(sinPitch);
            float absZ = Math.abs(cosYaw * cosPitch);

            if (absY > absX && absY > absZ) {
                return sinPitch > 0.0F ? Direction.DOWN : Direction.UP;
            }
            if (absX > absZ) {
                return sinYaw > 0.0F ? Direction.EAST : Direction.WEST;
            } else {
                return cosYaw > 0.0F ? Direction.SOUTH : Direction.NORTH;
            }
        }

        /**
         * 3-ETAPOWY PRIORYTET BUDOWANIA W OBRĘBIE WARSTWY Y:
         * 0 = Pełne bloki podłoża, schody, półbloki (tworzą czystą platformę do chodzenia).
         * 1 = Bloki mechaniczne 3D (Tłoki, Obserwatory, Dozowniki, Leje) — budowane NA GOTOWEJ PODŁODZE,
         *     ale ZANIM wokół nich zostaną rozłożone kable redstone i przekaźniki!
         * 2 = Delikatne komponenty powierzchniowe (Redstone Dust, Przekaźniki, Komparatory, Pochodnie, Dźwignie, Tory).
         */
        public static boolean isCropOrVegetation(BlockState state) {
            if (state == null || state.isAir()) {
                return false;
            }
            Block block = state.getBlock();
            return block instanceof SugarCaneBlock
                    || block instanceof CropBlock
                    || block instanceof BushBlock
                    || block instanceof BambooSaplingBlock
                    || block == Blocks.BAMBOO
                    || block instanceof SweetBerryBushBlock
                    || block instanceof StemBlock
                    || block instanceof AttachedStemBlock
                    || block instanceof CocoaBlock
                    || block instanceof CactusBlock
                    || block instanceof NetherWartBlock
                    || block instanceof FungusBlock;
        }

        public static boolean isFluid(BlockState state) {
            if (state == null) {
                return false;
            }
            Block block = state.getBlock();
            return block == Blocks.WATER || block == Blocks.LAVA || block instanceof LiquidBlock;
        }

        /**
         * 5-ETAPOWY PRIORYTET BUDOWANIA W OBRĘBIE SCHEMATU:
         * 0 = Pełne bloki podłoża, ziemia, kamień, ścianki rynny wodnej.
         * 1 = Mechanizmy 3D (Tłoki, Obserwatory, Dozowniki, Leje).
         * 2 = Delikatne komponenty powierzchniowe (Redstone Dust, Repeatery, Komparatory, Pochodnie).
         * 15 = Płyny (Woda w rynnie, lawa) — wlewane PO wybudowaniu mechanizmów i ścian, ale PRZED sadzeniem upraw!
         * 20 = Rośliny i uprawy (Trzcina cukrowa, pszenica, marchew) — sadzone na SAMYM KOŃCU po nalaniu wody!
         */
        public static int getLayerDependencyOrder(BlockState state) {
            if (state == null) {
                return 0;
            }
            if (isCropOrVegetation(state)) {
                return 20; // Etap 3: Uprawy i rośliny - sadzone na samym końcu po nalaniu wody!
            }
            if (isFluid(state)) {
                return 15; // Etap 2: Woda i płyny - wlewane po wybudowaniu wszystkich ścian, rynien i mechanizmów!
            }
            Block block = state.getBlock();
            if (requiresBottomSupportOnly(state)
                    || block instanceof FaceAttachedHorizontalDirectionalBlock
                    || block instanceof WallTorchBlock
                    || block instanceof RedstoneWallTorchBlock
                    || block instanceof TripWireBlock
                    || block instanceof TripWireHookBlock) {
                return 2;
            }
            if (block instanceof PistonBaseBlock
                    || block instanceof ObserverBlock
                    || block instanceof DispenserBlock
                    || block instanceof HopperBlock) {
                return 1;
            }
            return 0;
        }

        /**
         * Globalna kolejność etapów budowy całego schematu:
         * 0 = Wszystkie bloki konstrukcyjne, ściany, fundamenty, mechanizmy (tłoki, obserwatory, dozowniki, lejki, redstone)
         *     dla wszystkich warstw Y (od dołu do góry).
         * 1 = Woda i płyny (Blocks.WATER, wiadro wody) wlewane do gotowych rynien i koryt PO wybudowaniu konstrukcji!
         * 2 = Rośliny i uprawy (Trzcina cukrowa, pszenica, marchew, nasiona) sadzone na SAMYM KOŃCU po nalaniu wody!
         */
        public static int getGlobalPhaseOrder(BlockState state) {
            if (state == null) {
                return 0;
            }
            if (isCropOrVegetation(state) || state.getBlock() instanceof SugarCaneBlock) {
                return 2;
            }
            if (isFluid(state) || state.getBlock() instanceof LiquidBlock) {
                return 1;
            }
            return 0;
        }
    }

    // =========================================================================
    // 1. ASTRA ROTATION CONTROLLER (FAST SAME-TICK GCD STEP + STRICT RAYCAST)
    // =========================================================================

    public static final class AstraRotationController {

        public enum AimPriority {
            PATHING_STEER(10, false),
            BRIDGE_ALIGNMENT(20, true),
            PLACEMENT_RAYCAST(30, true);

            private final int level;
            private final boolean blockInteract;

            AimPriority(int level, boolean blockInteract) {
                this.level = level;
                this.blockInteract = blockInteract;
            }

            public int getLevel() {
                return level;
            }

            public boolean isBlockInteract() {
                return blockInteract;
            }
        }

        public static final float MAX_PLACEMENT_ANGLE_DIVERGENCE_DEG = 2.2F;
        private static final float MAX_YAW_SPEED_PER_TICK = 86.0F;
        private static final float MAX_PITCH_SPEED_PER_TICK = 68.0F;
        private static final float MIN_STEP_THRESHOLD = 0.004F;

        private final IBaritone baritone;
        private final Minecraft mc;

        private Rotation desiredTarget;
        private AimPriority activePriority = AimPriority.PATHING_STEER;
        private int leaseTicksRemaining = 0;

        private float virtualYaw;
        private float virtualPitch;
        private boolean initialized = false;
        private int lastUpdatedPlayerTick = -1;
        private float serverSyncedYaw;
        private float serverSyncedPitch;

        public Rotation getServerSyncedRotation() {
            LocalPlayer player = mc.player;
            if (!initialized && player != null) {
                return new Rotation(player.getYRot(), player.getXRot());
            }
            return new Rotation(serverSyncedYaw, serverSyncedPitch);
        }

        public AstraRotationController(IBaritone baritone) {
            this.baritone = baritone;
            this.mc = Minecraft.getInstance();
        }

        private IBaritone resolveBaritone() {
            if (this.baritone != null) {
                return this.baritone;
            }
            try {
                return BaritoneAPI.getProvider().getPrimaryBaritone();
            } catch (Throwable ignored) {
                return null;
            }
        }

        public boolean requestRotation(Rotation target, AimPriority priority, int holdTicks) {
            if (target == null) {
                return false;
            }
            if (leaseTicksRemaining > 0 && priority.getLevel() < activePriority.getLevel()) {
                return false;
            }
            this.desiredTarget = new Rotation(
                    Mth.wrapDegrees(target.getYaw()),
                    Mth.clamp(target.getPitch(), -90.0F, 90.0F));
            this.activePriority = priority;
            this.leaseTicksRemaining = Math.max(1, holdTicks);
            return true;
        }

        public void releasePriority(AimPriority priority) {
            if (this.activePriority == priority) {
                this.leaseTicksRemaining = 0;
                this.desiredTarget = null;
                this.activePriority = AimPriority.PATHING_STEER;
            }
        }

        public void reset() {
            this.desiredTarget = null;
            this.leaseTicksRemaining = 0;
            this.activePriority = AimPriority.PATHING_STEER;
            this.initialized = false;
            this.lastUpdatedPlayerTick = -1;
        }

        public void tick() {
            LocalPlayer player = mc.player;
            if (player == null) {
                initialized = false;
                return;
            }

            if (player.tickCount == lastUpdatedPlayerTick) {
                return;
            }
            lastUpdatedPlayerTick = player.tickCount;

            // Rotacja z końca poprzedniego ticku została już wysłana przez LocalPlayer.sendPosition() do serwera
            this.serverSyncedYaw = player.getYRot();
            this.serverSyncedPitch = player.getXRot();

            applyRotationStepInternal(player);
        }

        public void stepImmediatelyWithinTick() {
            LocalPlayer player = mc.player;
            if (player == null || desiredTarget == null) {
                return;
            }
            applyRotationStepInternal(player);
        }

        private void applyRotationStepInternal(LocalPlayer player) {
            if (!initialized) {
                this.virtualYaw = player.getYRot();
                this.virtualPitch = player.getXRot();
                this.initialized = true;
            }

            if (leaseTicksRemaining > 0 && desiredTarget != null) {
                leaseTicksRemaining--;

                if (Math.abs(Mth.wrapDegrees(player.getYRot() - virtualYaw)) > 45.0F) {
                    virtualYaw = player.getYRot();
                    virtualPitch = player.getXRot();
                }

                Rotation nextStep = computeNextSmoothQuantizedStep(
                        virtualYaw,
                        virtualPitch,
                        desiredTarget.getYaw(),
                        desiredTarget.getPitch());

                this.virtualYaw = nextStep.getYaw();
                this.virtualPitch = nextStep.getPitch();

                IBaritone b = resolveBaritone();
                if (b != null && b.getLookBehavior() != null) {
                    b.getLookBehavior().updateTarget(
                            new Rotation(Mth.wrapDegrees(this.virtualYaw), this.virtualPitch),
                            activePriority.isBlockInteract());
                }

                player.yHeadRot = this.virtualYaw;
                player.yBodyRot = this.virtualYaw;
                player.setYRot(this.virtualYaw);
                player.setXRot(this.virtualPitch);

                if (leaseTicksRemaining == 0) {
                    desiredTarget = null;
                    activePriority = AimPriority.PATHING_STEER;
                }
            } else {
                this.virtualYaw = player.getYRot();
                this.virtualPitch = player.getXRot();
            }
        }

        public Rotation computeNextSmoothQuantizedStep(
                float currentYaw,
                float currentPitch,
                float targetYaw,
                float targetPitch) {
            return RotationEngine.snapToGcd(
                    new Rotation(currentYaw, currentPitch),
                    new Rotation(targetYaw, targetPitch)
            );
        }

        public Rotation quantizeToPlayerGrid(LocalPlayer player, float candidateYaw, float candidatePitch) {
            float baseYaw = player.getYRot();
            float basePitch = player.getXRot();
            return RotationEngine.snapToGcd(
                    new Rotation(baseYaw, basePitch),
                    new Rotation(candidateYaw, candidatePitch)
            );
        }

        public float computeMouseSensitivityGcd() {
            double sensitivity = mc.options != null ? mc.options.sensitivity().get() : 0.5D;
            double f = sensitivity * 0.6D + 0.2D;
            double gcd = f * f * f * 1.2D;
            return (float) Math.max(gcd, 1.0E-4D);
        }

        public static float quantizeAngleDelta(float delta, float gcd) {
            if (gcd <= 0.0F) {
                return delta;
            }
            return Math.round(delta / gcd) * gcd;
        }

        public boolean isAlignedWith(Rotation target, float maxDegreeError) {
            LocalPlayer player = mc.player;
            if (player == null || target == null) {
                return false;
            }
            return computeAngularDivergenceDegrees(target) <= maxDegreeError;
        }

        public float computeAngularDivergenceDegrees(Rotation target) {
            LocalPlayer player = mc.player;
            if (player == null || target == null) {
                return 180.0F;
            }
            Vec3 currentLook = calculateViewVector(player.getXRot(), player.getYRot());
            Vec3 targetLook = calculateViewVector(target.getPitch(), target.getYaw());
            double dot = Mth.clamp(currentLook.dot(targetLook), -1.0D, 1.0D);
            return (float) Math.toDegrees(Math.acos(dot));
        }

        public BlockHitResult verifyLiveLookRaytrace(
                BlockPos expectedSupportPos,
                Direction expectedFace,
                double reachDistance) {
            LocalPlayer player = mc.player;
            Level level = mc.level;
            if (player == null || level == null || expectedSupportPos == null || expectedFace == null) {
                return null;
            }

            Vec3 eyePos = player.getEyePosition(1.0F);
            Vec3 faceCenter = BlockStatePlacementEngine.computeExactFaceHitVec(expectedSupportPos, expectedFace);

            if (!BlockStatePlacementEngine.isFrontFacing(eyePos, faceCenter, expectedFace)) {
                return null;
            }

            Vec3 liveLookVec = calculateViewVector(player.getXRot(), player.getYRot());
            Vec3 rayEnd = eyePos.add(liveLookVec.scale(reachDistance));

            BlockHitResult outlineHit = level.clip(new ClipContext(
                    eyePos,
                    rayEnd,
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    player));

            if (outlineHit.getType() == HitResult.Type.BLOCK
                    && !outlineHit.getBlockPos().equals(expectedSupportPos)) {
                BlockState hitState = level.getBlockState(outlineHit.getBlockPos());
                if (DirectionalStateSolver.isCropOrVegetation(hitState)) {
                    Vec3 pastFlora = outlineHit.getLocation().add(liveLookVec.scale(0.12D));
                    if (pastFlora.distanceToSqr(eyePos) < reachDistance * reachDistance) {
                        outlineHit = level.clip(new ClipContext(
                                pastFlora,
                                rayEnd,
                                ClipContext.Block.OUTLINE,
                                ClipContext.Fluid.NONE,
                                player));
                    }
                }
            }

            if (outlineHit.getType() != HitResult.Type.BLOCK
                    || !outlineHit.getBlockPos().equals(expectedSupportPos)
                    || outlineHit.getDirection() != expectedFace
                    || eyePos.distanceToSqr(outlineHit.getLocation()) > reachDistance * reachDistance) {
                return null;
            }

            BlockHitResult colliderHit = level.clip(new ClipContext(
                    eyePos,
                    rayEnd,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    player));

            if (colliderHit.getType() == HitResult.Type.BLOCK
                    && colliderHit.getBlockPos().equals(expectedSupportPos)
                    && colliderHit.getDirection() == expectedFace
                    && eyePos.distanceToSqr(colliderHit.getLocation()) <= reachDistance * reachDistance) {
                return outlineHit;
            }

            return null;
        }

        public static Vec3 calculateViewVector(float xRot, float yRot) {
            float f = xRot * ((float) Math.PI / 180F);
            float g = -yRot * ((float) Math.PI / 180F);
            float h = Mth.cos(g);
            float i = Mth.sin(g);
            float j = Mth.cos(f);
            float k = Mth.sin(f);
            return new Vec3(i * j, -k, h * j);
        }

        public float getVirtualYaw() {
            return virtualYaw;
        }

        public float getVirtualPitch() {
            return virtualPitch;
        }

        public AimPriority getActivePriority() {
            return activePriority;
        }

        public int getLeaseTicksRemaining() {
            return leaseTicksRemaining;
        }
    }

    // =========================================================================
    // 2. GRIM PACKET ORDER
    // =========================================================================

    public static final class GrimPacketOrder {

        private GrimPacketOrder() {
        }

        public static boolean place(LocalPlayer player, BlockHitResult hitResult, InteractionHand hand) {
            Minecraft mc = Minecraft.getInstance();
            MultiPlayerGameMode gameMode = mc.gameMode;
            if (player == null || gameMode == null || mc.level == null || hitResult == null) {
                return false;
            }

            InteractionResult result = gameMode.useItemOn(player, hand, hitResult);
            if (result.consumesAction()) {
                player.swing(hand);
                return true;
            }

            InteractionResult itemResult = gameMode.useItem(player, hand);
            if (itemResult.consumesAction()) {
                player.swing(hand);
                return true;
            }
            return false;
        }

        public static boolean interactBlock(LocalPlayer player, BlockHitResult hitResult, InteractionHand hand) {
            Minecraft mc = Minecraft.getInstance();
            MultiPlayerGameMode gameMode = mc.gameMode;
            if (player == null || gameMode == null || mc.level == null || hitResult == null) {
                return false;
            }
            InteractionResult result = gameMode.useItemOn(player, hand, hitResult);
            if (result.consumesAction()) {
                player.swing(hand);
                return true;
            }
            return false;
        }

        public static boolean startBreak(LocalPlayer player, BlockPos pos, Direction face) {
            Minecraft mc = Minecraft.getInstance();
            MultiPlayerGameMode gameMode = mc.gameMode;
            if (player == null || gameMode == null || mc.level == null || pos == null || face == null) {
                return false;
            }
            player.swing(InteractionHand.MAIN_HAND);
            return gameMode.startDestroyBlock(pos, face);
        }
    }

    // =========================================================================
    // 3. AUXILIARY BLOCK MANAGER (DISPOSABLE SCAFFOLD / PILLAR / BRIDGE BLOCKS)
    // =========================================================================

    public static final class AuxiliaryBlockManager {

        public static final List<Block> DISPOSABLE_PRIORITY_BLOCKS = List.of(
                Blocks.DIRT,
                Blocks.COBBLESTONE,
                Blocks.COBBLED_DEEPSLATE,
                Blocks.NETHERRACK,
                Blocks.SCAFFOLDING,
                Blocks.OAK_PLANKS,
                Blocks.SPRUCE_PLANKS,
                Blocks.BIRCH_PLANKS,
                Blocks.STONE,
                Blocks.ANDESITE,
                Blocks.DIORITE,
                Blocks.GRANITE);

        private final AstraSchematicManager manager;

        public AuxiliaryBlockManager(AstraSchematicManager manager) {
            this.manager = manager;
        }

        public Map<Block, Integer> computeRemainingSchematicRequirements() {
            Map<Block, Integer> remaining = new HashMap<>();
            if (manager == null || manager.getBuildQueue() == null) {
                return remaining;
            }
            for (BlockState state : manager.getBuildQueue().getPendingVoxels().values()) {
                if (state != null && !state.isAir()) {
                    remaining.merge(state.getBlock(), 1, Integer::sum);
                }
            }
            return remaining;
        }

        public int countBlockInInventory(LocalPlayer player, Block block) {
            if (player == null || block == null) {
                return 0;
            }
            Item targetItem = block.asItem();
            if (targetItem == Items.AIR) {
                return 0;
            }
            Inventory inv = player.getInventory();
            int total = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty() && stack.is(targetItem)) {
                    total += stack.getCount();
                }
            }
            return total;
        }

        public boolean hasDisposableSurplus(LocalPlayer player, Block candidateBlock,
                Map<Block, Integer> remainingReqs) {
            if (player == null || candidateBlock == null || candidateBlock == Blocks.AIR) {
                return false;
            }
            if (!isSafeDisposableFallbackBlock(candidateBlock)) {
                return false;
            }
            int inInventory = countBlockInInventory(player, candidateBlock);
            if (inInventory <= 0) {
                return false;
            }
            int neededForSchematic = remainingReqs.getOrDefault(candidateBlock, 0);
            if (inInventory > neededForSchematic) {
                return true;
            }
            // Zezwalaj na użycie bloków budowlanych (np. ziemi) na rusztowanie, aby bot nie zablokował się przed schematem
            return inInventory >= 1;
        }

        public BlockState resolveBestAvailableAuxiliaryState(LocalPlayer player) {
            if (player == null) {
                return Blocks.DIRT.defaultBlockState();
            }
            if (player.getAbilities().instabuild) {
                return Blocks.DIRT.defaultBlockState();
            }

            Map<Block, Integer> remainingReqs = computeRemainingSchematicRequirements();

            for (Block priorityBlock : DISPOSABLE_PRIORITY_BLOCKS) {
                if (hasDisposableSurplus(player, priorityBlock, remainingReqs)) {
                    return priorityBlock.defaultBlockState();
                }
            }

            Inventory inv = player.getInventory();
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty() && stack.getItem() instanceof BlockItem bi) {
                    Block block = bi.getBlock();
                    if (isSafeDisposableFallbackBlock(block) && hasDisposableSurplus(player, block, remainingReqs)) {
                        return block.defaultBlockState();
                    }
                }
            }

            for (int i = 0; i < 36; i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty() && stack.getItem() instanceof BlockItem bi) {
                    if (isSafeDisposableFallbackBlock(bi.getBlock())) {
                        return bi.getBlock().defaultBlockState();
                    }
                }
            }

            return null;
        }

        public BlockStatePlacementEngine.SlotSelection findAuxiliaryBlockSlot(
                LocalPlayer player,
                BlockStatePlacementEngine placementEngine,
                boolean performSwapIfInMainInventory) {
            if (player == null || placementEngine == null) {
                return null;
            }

            Map<Block, Integer> remainingReqs = computeRemainingSchematicRequirements();

            for (Block priorityBlock : DISPOSABLE_PRIORITY_BLOCKS) {
                if (player.getAbilities().instabuild || hasDisposableSurplus(player, priorityBlock, remainingReqs)) {
                    BlockStatePlacementEngine.SlotSelection sel = placementEngine.findStrictSlotForState(
                            player,
                            priorityBlock.defaultBlockState(),
                            performSwapIfInMainInventory);
                    if (sel != null) {
                        return sel;
                    }
                }
            }

            Inventory inv = player.getInventory();
            for (int i = 0; i < 9; i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty() && stack.getItem() instanceof BlockItem bi) {
                    Block block = bi.getBlock();
                    if (isSafeDisposableFallbackBlock(block) && hasDisposableSurplus(player, block, remainingReqs)) {
                        return new BlockStatePlacementEngine.SlotSelection(i, InteractionHand.MAIN_HAND);
                    }
                }
            }

            for (int i = 9; i < 36; i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty() && stack.getItem() instanceof BlockItem bi) {
                    Block block = bi.getBlock();
                    if (isSafeDisposableFallbackBlock(block) && hasDisposableSurplus(player, block, remainingReqs)) {
                        if (!performSwapIfInMainInventory) {
                            return new BlockStatePlacementEngine.SlotSelection(0, InteractionHand.MAIN_HAND);
                        }
                        int targetHotbar = placementEngine.selectBestHotbarSwapTarget(inv);
                        if (placementEngine.swapFromInventoryToHotbar(player, i, targetHotbar)) {
                            return new BlockStatePlacementEngine.SlotSelection(targetHotbar, InteractionHand.MAIN_HAND);
                        }
                    }
                }
            }

            for (int i = 0; i < 9; i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty() && stack.getItem() instanceof BlockItem bi
                        && isSafeDisposableFallbackBlock(bi.getBlock())) {
                    return new BlockStatePlacementEngine.SlotSelection(i, InteractionHand.MAIN_HAND);
                }
            }

            return null;
        }

        private static boolean isSafeDisposableFallbackBlock(Block block) {
            if (block == null || block == Blocks.AIR || block == Blocks.TNT || block instanceof LiquidBlock) {
                return false;
            }
            if (block instanceof FallingBlock && block != Blocks.SCAFFOLDING) {
                return false;
            }
            if (block instanceof DiodeBlock
                    || block instanceof RedStoneWireBlock
                    || block instanceof BaseRailBlock
                    || block instanceof StairBlock
                    || block instanceof SlabBlock
                    || block instanceof PistonBaseBlock
                    || block instanceof ObserverBlock
                    || block instanceof TorchBlock
                    || block instanceof FaceAttachedHorizontalDirectionalBlock) {
                return false;
            }
            BlockState defaultState = block.defaultBlockState();
            return !defaultState.hasBlockEntity()
                    && !BlockStatePlacementEngine.doesBlockRequireSneakToPlaceAgainst(defaultState);
        }
    }

    // =========================================================================
    // 4. BLOCK STATE PLACEMENT ENGINE (DIRECTIONAL + STAIRS/RAILS + STRICT LoS)
    // =========================================================================

    public static class BlockStatePlacementEngine {

        public static final double SURVIVAL_REACH_DISTANCE = 4.2D;
        private static final int INVENTORY_SWAP_COOLDOWN_TICKS = 1;

        /**
         * Siatka 21 punktów próbkowania na ściance wspierającej:
         * - Zawiera punkty w dolnej połowie (`v = 0.22, 0.32`) dla `Half.BOTTOM`
         * (normalne schody / dolny półblok),
         * - Zawiera punkty w górnej połowie (`v = 0.68, 0.78, 0.85`) dla `Half.TOP`
         * (odwrócone schody / górny półblok),
         * - Zawiera środek (`0.50, 0.50`) dla pełnych bloków, torów i przekaźników.
         */
        private static final double[][] FACE_SAMPLE_UV_GRID = {
                { 0.50D, 0.50D },
                { 0.50D, 0.25D },
                { 0.50D, 0.35D },
                { 0.50D, 0.68D },
                { 0.50D, 0.78D },
                { 0.50D, 0.86D },
                { 0.35D, 0.50D },
                { 0.65D, 0.50D },
                { 0.35D, 0.28D },
                { 0.65D, 0.28D },
                { 0.35D, 0.75D },
                { 0.65D, 0.75D },
                { 0.32D, 0.32D },
                { 0.68D, 0.32D },
                { 0.32D, 0.68D },
                { 0.68D, 0.68D },
                { 0.22D, 0.50D },
                { 0.78D, 0.50D },
                { 0.50D, 0.18D },
                { 0.50D, 0.89D }
        };

        private final Minecraft mc;
        private final AstraRotationController rotationController;
        private final AuxiliaryBlockManager auxiliaryBlockManager;
        private int inventorySwapCooldown = 0;
        private long lastInvCacheGameTime = -1;
        private final java.util.Set<Item> cachedInventoryItems = new java.util.HashSet<>();

        public record SlotSelection(int slot, InteractionHand hand) {
        }

        public record PlacementPlan(
                BlockPos targetPos,
                BlockPos supportPos,
                Direction supportFace,
                Vec3 hitVec,
                Rotation requiredRotation,
                BlockState desiredState,
                int hotbarSlot,
                InteractionHand hand,
                boolean requiresSneak) {
            public int postPlacementRightClicks() {
                return DirectionalStateSolver.getInitialPostPlacementClicks(desiredState);
            }

            public boolean requireSneak() {
                return requiresSneak;
            }

            public BlockHitResult hitResult() {
                return new BlockHitResult(hitVec, supportFace, supportPos, false);
            }

            public boolean isBucketUse() {
                return false;
            }
        }

        public BlockStatePlacementEngine(
                AstraRotationController rotationController,
                AuxiliaryBlockManager auxiliaryBlockManager) {
            this.mc = Minecraft.getInstance();
            this.rotationController = rotationController;
            this.auxiliaryBlockManager = auxiliaryBlockManager;
        }

        public BlockStatePlacementEngine(AstraRotationController rotationController) {
            this(rotationController, null);
        }

        public void tickCooldowns() {
            if (inventorySwapCooldown > 0) {
                inventorySwapCooldown--;
            }
        }

        public AstraRotationController getRotationController() {
            return rotationController;
        }

        public Optional<PlacementPlan> computePlacementPlan(BlockPos targetPos, BlockState desiredState) {
            return computePlacementPlan(targetPos, desiredState, false);
        }

        /**
         * Oblicza w 100% legalny (GrimAC/Vulcan) plan postawienia bloku, który
         * gwarantuje:
         * 1. Czysty Raycast LoS (`OUTLINE` + `COLLIDER` + `Back-Face Culling`).
         * 2. Trafienie we właściwą ściankę (`isSupportFaceCompatibleWithState`) oraz
         * właściwą połowę
         * wysokości (`isHitHeightValidForState` dla schodów i półbloków).
         * 3. Zgodność kierunku patrzenia gracza z pożądanym kierunkiem bloku
         * (`doesPlayerLookProduceDesiredOrientation` dla przekaźników, komparatorów,
         * schodów, tłoków, obserwatorów i torów).
         */
        public Optional<PlacementPlan> computePlacementPlan(
                        BlockPos targetPos,
                        BlockState desiredState,
                        boolean isAuxiliaryScaffold) {
                    LocalPlayer player = mc.player;
                    Level level = mc.level;
                    if (player == null || level == null || targetPos == null || desiredState == null) {
                        return Optional.empty();
                    }

                    if (!canPlaceAtTargetVoxel(level, player, targetPos, desiredState)) {
                        return Optional.empty();
                    }

                    Vec3 eyePos = player.getEyePosition(1.0F);
                    Vec3 targetCenter = Vec3.atCenterOf(targetPos);
                    double maxCenterDist = SURVIVAL_REACH_DISTANCE + 0.60D;
                    if (eyePos.distanceToSqr(targetCenter) > maxCenterDist * maxCenterDist) {
                        return Optional.empty();
                    }

                    // Dla poziomych bloków 3D (Tłok, Dozownik, Obserwator) sprawdź, czy pozycja oczu gracza
                    // faktycznie leży w korytarzu osiowym 90° przed blokiem docelowym!
                    if (!isAuxiliaryScaffold && DirectionalStateSolver.isHorizontal3DDirectional(desiredState)) {
                        Direction standSide = DirectionalStateSolver.getRequiredStandingSide(desiredState);
                        if (standSide != null) {
                            double offsetX = eyePos.x - targetCenter.x;
                            double offsetZ = eyePos.z - targetCenter.z;
                            double along = offsetX * standSide.getStepX() + offsetZ * standSide.getStepZ();
                            double lateral = Math.abs(offsetX * standSide.getStepZ() - offsetZ * standSide.getStepX());

                            // Wymuś korytarz osiowy (zapobiega stawianiu z ostrych kątów bocznych)
                            // Dla Obserwatorów i mechanizmów dopuszczamy along >= 0.85D (stanie 1 blok za blokiem na redstonie)
                            if (along < 0.85D || lateral > 0.85D || (along > 0 && lateral > along * 0.55D)) {
                                return Optional.empty();
                            }
                            // Jeśli stopy gracza są o cały blok wyżej niż targetPos.Y, wymagaj dystansu >= 1.60 bloku (aby pitch < 45°)
                            if (player.getY() >= targetPos.getY() + 0.85D && along < 1.60D) {
                                return Optional.empty();
                            }
                        }
                    }

                    SlotSelection slotSelection = isAuxiliaryScaffold && auxiliaryBlockManager != null
                            ? auxiliaryBlockManager.findAuxiliaryBlockSlot(player, this, true)
                            : findStrictSlotForState(player, desiredState, true);

                    if (slotSelection == null && isAuxiliaryScaffold) {
                        slotSelection = findStrictSlotForState(player, desiredState, true);
                    }
                    if (slotSelection == null) {
                        return Optional.empty();
                    }

                    BlockState existingAtTarget = level.getBlockState(targetPos);
                    if (existingAtTarget.getBlock() == desiredState.getBlock()
                            && DirectionalStateSolver.isDoubleSlab(desiredState)
                            && existingAtTarget.getValue(SlabBlock.TYPE) != SlabType.DOUBLE) {
                        Direction slabClickFace = existingAtTarget.getValue(SlabBlock.TYPE) == SlabType.BOTTOM
                                ? Direction.UP
                                : Direction.DOWN;
                        Vec3 innerSlabPoint = new Vec3(
                                targetPos.getX() + 0.5D,
                                targetPos.getY() + 0.5D,
                                targetPos.getZ() + 0.5D);
                        Rotation rawRot = computeRotationToVec(eyePos, innerSlabPoint);
                        Rotation quantizedRot = rotationController != null
                                ? rotationController.quantizeToPlayerGrid(player, rawRot.getYaw(), rawRot.getPitch())
                                : rawRot;
                        BlockHitResult rayHit = verifyRaytrace(
                                level, player, eyePos, quantizedRot, targetPos, slabClickFace, SURVIVAL_REACH_DISTANCE);
                        if (rayHit != null) {
                            return Optional.of(new PlacementPlan(
                                    targetPos,
                                    targetPos,
                                    slabClickFace,
                                    rayHit.getLocation(),
                                    quantizedRot,
                                    desiredState,
                                    slotSelection.slot(),
                                    slotSelection.hand(),
                                    false));
                        }
                        return Optional.empty();
                    }

                    List<PlacementPlan> directVisiblePlans = new ArrayList<>();
                    boolean isDirectional = !isAuxiliaryScaffold && DirectionalStateSolver.isOrientationSensitive(desiredState);

                    Direction[] searchOrder = {
                            Direction.NORTH,
                            Direction.SOUTH,
                            Direction.WEST,
                            Direction.EAST,
                            Direction.DOWN,
                            Direction.UP
                    };

                    for (Direction offsetDir : searchOrder) {
                        BlockPos supportPos = targetPos.relative(offsetDir);
                        Direction clickedFace = offsetDir.getOpposite();

                        if (!DirectionalStateSolver.isSupportFaceCompatibleWithState(
                                targetPos, supportPos, clickedFace, desiredState)) {
                            continue;
                        }

                        BlockState supportState = level.getBlockState(supportPos);
                        if (!isValidSupportBlock(level, supportPos, supportState, clickedFace)) {
                            continue;
                        }

                        Vec3 faceCenter = computeExactFaceHitVec(supportPos, clickedFace);
                        if (!isFrontFacing(eyePos, faceCenter, clickedFace)) {
                            continue;
                        }

                        boolean requiresSneak = doesBlockRequireSneakToPlaceAgainst(supportState);

                        for (double[] uv : FACE_SAMPLE_UV_GRID) {
                            Vec3 candidateHitVec = computeFaceSamplePoint(supportPos, clickedFace, uv[0], uv[1]);
                            if (eyePos.distanceToSqr(candidateHitVec) > SURVIVAL_REACH_DISTANCE * SURVIVAL_REACH_DISTANCE) {
                                continue;
                            }

                            if (!DirectionalStateSolver.isHitHeightValidForState(
                                    clickedFace, candidateHitVec.y, targetPos, desiredState)) {
                                continue;
                            }

                            if (!isFrontFacing(eyePos, candidateHitVec, clickedFace)) {
                                continue;
                            }

                            Rotation rawRot = computeRotationToVec(eyePos, candidateHitVec);
                            Rotation quantizedRot = rotationController != null
                                    ? rotationController.quantizeToPlayerGrid(player, rawRot.getYaw(), rawRot.getPitch())
                                    : rawRot;

                            if (!isAuxiliaryScaffold
                                    && !DirectionalStateSolver.doesPlayerLookProduceDesiredOrientation(quantizedRot,
                                            desiredState)) {
                                continue;
                            }

                            BlockHitResult rayHit = verifyRaytrace(
                                    level,
                                    player,
                                    eyePos,
                                    quantizedRot,
                                    supportPos,
                                    clickedFace,
                                    SURVIVAL_REACH_DISTANCE,
                                    true);

                            if (rayHit != null
                                    && DirectionalStateSolver.isHitHeightValidForState(
                                            clickedFace, rayHit.getLocation().y, targetPos, desiredState)) {
                                directVisiblePlans.add(new PlacementPlan(
                                        targetPos,
                                        supportPos,
                                        clickedFace,
                                        rayHit.getLocation(),
                                        quantizedRot,
                                        desiredState,
                                        slotSelection.slot(),
                                        slotSelection.hand(),
                                        requiresSneak));
                                // Dla zwykłych bloków pełnych wystarczy pierwszy trafiony punkt UV na ściance;
                                // dla Tłoków/Przekaźników sprawdzamy wszystkie punkty UV, aby wybrać kąt najbliższy 90°!
                                if (!isDirectional) {
                                    break;
                                }
                            }
                        }
                    }

                    if (directVisiblePlans.isEmpty()) {
                        return Optional.empty();
                    }

                    float currentYaw = player.getYRot();
                    float currentPitch = player.getXRot();

                    if (isDirectional) {
                        // Sortuj plany według najmniejszego odchylenia od idealnej osi 90° (Yaw) oraz poziomu (Pitch = 0°)
                        directVisiblePlans.sort(Comparator
                                .comparingDouble((PlacementPlan plan) -> DirectionalStateSolver
                                        .computeAxialOrientationPenalty(plan.requiredRotation(), desiredState))
                                .thenComparingDouble(plan -> angularDistanceSq(
                                        currentYaw,
                                        currentPitch,
                                        plan.requiredRotation().getYaw(),
                                        plan.requiredRotation().getPitch())));
                    } else {
                        directVisiblePlans.sort(Comparator.comparingDouble(plan -> angularDistanceSq(
                                currentYaw,
                                currentPitch,
                                plan.requiredRotation().getYaw(),
                                plan.requiredRotation().getPitch())));
                    }

                    return Optional.of(directVisiblePlans.get(0));
                }


        public static boolean isFrontFacing(Vec3 eyePos, Vec3 facePoint, Direction clickedFace) {
            Vec3i normal = clickedFace.getUnitVec3i();
            double vx = eyePos.x - facePoint.x;
            double vy = eyePos.y - facePoint.y;
            double vz = eyePos.z - facePoint.z;
            double dot = vx * normal.getX() + vy * normal.getY() + vz * normal.getZ();
            return dot > 0.03D;
        }

        public SlotSelection findAuxiliaryBlockSlot(LocalPlayer player) {
            if (auxiliaryBlockManager != null) {
                return auxiliaryBlockManager.findAuxiliaryBlockSlot(player, this, true);
            }
            return null;
        }

        public static Vec3 computeExactFaceHitVec(BlockPos pos, Direction face) {
            double cx = pos.getX() + 0.5D + face.getStepX() * 0.5D;
            double cy = pos.getY() + 0.5D + face.getStepY() * 0.5D;
            double cz = pos.getZ() + 0.5D + face.getStepZ() * 0.5D;
            return new Vec3(cx, cy, cz);
        }

        public boolean canPlaceFromStandingPos(BlockPos candidateFeetPos, BlockPos targetPos, BlockState desiredState) {
            LocalPlayer player = mc.player;
            Level level = mc.level;
            if (player == null || level == null || candidateFeetPos == null || targetPos == null) {
                return false;
            }

            // Jeśli docelowy blok ma kolizję, gracz nie może w nim stać
            VoxelShape targetCollision = desiredState != null
                    ? desiredState.getCollisionShape(level, targetPos, CollisionContext.of(player))
                    : null;
            if (targetCollision != null && !targetCollision.isEmpty()) {
                AABB simulatedPlayerBox = new AABB(
                        candidateFeetPos.getX() + 0.2D, candidateFeetPos.getY(), candidateFeetPos.getZ() + 0.2D,
                        candidateFeetPos.getX() + 0.8D, candidateFeetPos.getY() + 1.8D, candidateFeetPos.getZ() + 0.8D);
                if (simulatedPlayerBox.intersects(targetCollision.bounds().move(targetPos))) {
                    return false;
                }
            }

            Vec3 simulatedEyePos = new Vec3(
                    candidateFeetPos.getX() + 0.5D,
                    candidateFeetPos.getY() + (double) player.getEyeHeight(player.getPose()),
                    candidateFeetPos.getZ() + 0.5D);

            return countVisibleSupportFacesFromEye(level, player, simulatedEyePos, targetPos, desiredState) > 0;
        }

        public int countVisibleSupportFacesFromEye(
                Level level,
                LocalPlayer player,
                Vec3 simulatedEyePos,
                BlockPos targetPos) {
            return countVisibleSupportFacesFromEye(level, player, simulatedEyePos, targetPos, null);
        }

        /**
         * Zlicza ile ścianek wspierających dla `targetPos` jest widocznych z
         * `simulatedEyePos`
         * ORAZ daje poprawny kąt orientacji (`doesPlayerLookProduceDesiredOrientation`)
         * dla `desiredState`!
         */
        public int countVisibleSupportFacesFromEye(
                        Level level,
                        LocalPlayer player,
                        Vec3 simulatedEyePos,
                        BlockPos targetPos,
                        BlockState desiredState) {
                    int visibleCount = 0;
                    double maxReachSq = (SURVIVAL_REACH_DISTANCE - 0.12D) * (SURVIVAL_REACH_DISTANCE - 0.12D);

                    for (Direction offsetDir : Direction.values()) {
                        BlockPos supportPos = targetPos.relative(offsetDir);
                        Direction clickedFace = offsetDir.getOpposite();

                        if (desiredState != null && !DirectionalStateSolver.isSupportFaceCompatibleWithState(
                                targetPos, supportPos, clickedFace, desiredState)) {
                            continue;
                        }

                        BlockState supportState = level.getBlockState(supportPos);
                        if (!isValidSupportBlock(level, supportPos, supportState, clickedFace)) {
                            continue;
                        }

                        for (double[] uv : FACE_SAMPLE_UV_GRID) {
                            Vec3 samplePoint = computeFaceSamplePoint(supportPos, clickedFace, uv[0], uv[1]);
                            if (simulatedEyePos.distanceToSqr(samplePoint) > maxReachSq) {
                                continue;
                            }
                            if (desiredState != null && !DirectionalStateSolver.isHitHeightValidForState(
                                    clickedFace, samplePoint.y, targetPos, desiredState)) {
                                continue;
                            }
                            if (!isFrontFacing(simulatedEyePos, samplePoint, clickedFace)) {
                                continue;
                            }

                            Rotation rawRot = computeRotationToVec(simulatedEyePos, samplePoint);
                            Rotation rot = rotationController != null
                                    ? rotationController.quantizeToPlayerGrid(player, rawRot.getYaw(), rawRot.getPitch())
                                    : rawRot;

                            if (desiredState != null
                                    && !DirectionalStateSolver.doesPlayerLookProduceDesiredOrientation(rot, desiredState)) {
                                continue;
                            }

                            BlockHitResult hit = verifyRaytrace(
                                    level, player, simulatedEyePos, rot, supportPos, clickedFace, SURVIVAL_REACH_DISTANCE, true);
                            if (hit != null
                                    && (desiredState == null || DirectionalStateSolver.isHitHeightValidForState(
                                            clickedFace, hit.getLocation().y, targetPos, desiredState))) {
                                visibleCount++;
                                break;
                            }
                        }
                    }

                    return visibleCount;
                }


        public boolean hasValidSupportForState(BlockPos targetPos, BlockState desiredState) {
            Level level = mc.level;
            if (level == null || targetPos == null) {
                return false;
            }
            for (Direction offsetDir : Direction.values()) {
                BlockPos supportPos = targetPos.relative(offsetDir);
                Direction clickedFace = offsetDir.getOpposite();
                if (desiredState != null && !DirectionalStateSolver.isSupportFaceCompatibleWithState(
                        targetPos, supportPos, clickedFace, desiredState)) {
                    continue;
                }
                BlockState state = level.getBlockState(supportPos);
                if (isValidSupportBlock(level, supportPos, state, clickedFace)) {
                    return true;
                }
            }
            return false;
        }

        public boolean hasAdjacentSupportBlock(BlockPos targetPos) {
            return hasValidSupportForState(targetPos, null);
        }

        public static boolean canClickSupport(Level level, BlockPos pos) {
            if (level == null || pos == null) {
                return false;
            }
            BlockState state = level.getBlockState(pos);
            return !state.isAir() && !state.canBeReplaced() && state.getFluidState().isEmpty();
        }

        public static Vec3 getFaceCenter(BlockPos pos, Direction face) {
            return computeExactFaceHitVec(pos, face);
        }

        public boolean canExecuteLockedPlacement(PlacementPlan plan) {
            return canExecuteLockedPlacement(plan, true);
        }

        public boolean canExecuteLockedPlacement(PlacementPlan plan, boolean checkOrientation) {
            LocalPlayer player = mc.player;
            Level level = mc.level;
            if (player == null || level == null || plan == null || rotationController == null) {
                return false;
            }
            if (plan.requiresSneak() && !player.isShiftKeyDown()) {
                return false;
            }
            if (plan.hand() == InteractionHand.MAIN_HAND && player.getInventory().selected != plan.hotbarSlot()) {
                return false;
            }

            Rotation currentPlayerRot = new Rotation(player.getYRot(), player.getXRot());
            if (checkOrientation) {
                if (!DirectionalStateSolver.doesPlayerLookProduceDesiredOrientation(currentPlayerRot,
                        plan.desiredState())) {
                    return false;
                }
                // Dla bloków kierunkowych (Repeater, Comparator, Piston, Observer, Stairs) upewnij się,
                // że rotacja już zsynchronizowana z serwerem (wysłana w MovePlayerPacket) również daje ten sam kierunek!
                if (DirectionalStateSolver.isOrientationSensitive(plan.desiredState())) {
                    Rotation syncedRot = rotationController.getServerSyncedRotation();
                    if (!DirectionalStateSolver.doesPlayerLookProduceDesiredOrientation(syncedRot,
                            plan.desiredState())) {
                        return false;
                    }
                    if (rotationController.computeAngularDivergenceDegrees(syncedRot) > 3.0F) {
                        return false;
                    }
                }
            }

            BlockHitResult liveHit = rotationController.verifyLiveLookRaytrace(
                    plan.supportPos(),
                    plan.supportFace(),
                    SURVIVAL_REACH_DISTANCE);
            if (liveHit == null) {
                return false;
            }

            return DirectionalStateSolver.isHitHeightValidForState(
                    plan.supportFace(),
                    liveHit.getLocation().y,
                    plan.targetPos(),
                    plan.desiredState());
        }

        public boolean executePlacement(PlacementPlan plan) {
            return executePlacement(plan, true);
        }

        public boolean executePlacement(PlacementPlan plan, boolean checkOrientation) {
            LocalPlayer player = mc.player;
            Level level = mc.level;
            if (player == null || level == null || plan == null || rotationController == null) {
                return false;
            }

            if (!canPlaceAtTargetVoxel(level, player, plan.targetPos(), plan.desiredState())) {
                return false;
            }

            if (!canExecuteLockedPlacement(plan, checkOrientation)) {
                return false;
            }

            BlockHitResult liveHit = rotationController.verifyLiveLookRaytrace(
                    plan.supportPos(),
                    plan.supportFace(),
                    SURVIVAL_REACH_DISTANCE);

            if (liveHit == null) {
                return false;
            }

            return GrimPacketOrder.place(player, liveHit, plan.hand());
        }

        public BlockHitResult verifyRaytrace(
                Level level,
                LocalPlayer player,
                Vec3 eyePos,
                Rotation rotation,
                BlockPos expectedSupportPos,
                Direction expectedFace,
                double reachDistance) {
            return verifyRaytrace(level, player, eyePos, rotation, expectedSupportPos, expectedFace, reachDistance, false);
        }

        public BlockHitResult verifyRaytrace(
                Level level,
                LocalPlayer player,
                Vec3 eyePos,
                Rotation rotation,
                BlockPos expectedSupportPos,
                Direction expectedFace,
                double reachDistance,
                boolean ignoreUnrequestedFlora) {
            Vec3 faceCenter = computeExactFaceHitVec(expectedSupportPos, expectedFace);
            if (!isFrontFacing(eyePos, faceCenter, expectedFace)) {
                return null;
            }

            Vec3 lookVec = AstraRotationController.calculateViewVector(rotation.getPitch(), rotation.getYaw());
            Vec3 rayEnd = eyePos.add(lookVec.scale(reachDistance));

            BlockHitResult outlineHit = level.clip(new ClipContext(
                    eyePos,
                    rayEnd,
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    player));

            if (outlineHit.getType() == HitResult.Type.BLOCK) {
                if (outlineHit.getBlockPos().equals(expectedSupportPos)
                        && outlineHit.getDirection() == expectedFace
                        && eyePos.distanceToSqr(outlineHit.getLocation()) <= reachDistance * reachDistance) {
                    BlockHitResult colliderHit = level.clip(new ClipContext(
                            eyePos,
                            rayEnd,
                            ClipContext.Block.COLLIDER,
                            ClipContext.Fluid.NONE,
                            player));

                    if (colliderHit.getType() == HitResult.Type.BLOCK
                            && colliderHit.getBlockPos().equals(expectedSupportPos)
                            && colliderHit.getDirection() == expectedFace
                            && eyePos.distanceToSqr(colliderHit.getLocation()) <= reachDistance * reachDistance) {
                        return outlineHit;
                    }
                } else if (ignoreUnrequestedFlora) {
                    // Podczas szukania pozycji stania (kandydatów nawigacji), wyrośnięta trzcina lub pospolita flora
                    // nie powinna dyskwalifikować idealnego stanowiska, ponieważ bot zniszczy ją przed postawieniem bloku!
                    BlockPos hitPos = outlineHit.getBlockPos();
                    BlockState hitState = level.getBlockState(hitPos);
                    if (DirectionalStateSolver.isCropOrVegetation(hitState)) {
                        Vec3 pastFlora = outlineHit.getLocation().add(lookVec.scale(0.12D));
                        if (pastFlora.distanceToSqr(eyePos) < reachDistance * reachDistance) {
                            BlockHitResult secondHit = level.clip(new ClipContext(
                                    pastFlora,
                                    rayEnd,
                                    ClipContext.Block.OUTLINE,
                                    ClipContext.Fluid.NONE,
                                    player));
                            if (secondHit.getType() == HitResult.Type.BLOCK
                                    && secondHit.getBlockPos().equals(expectedSupportPos)
                                    && secondHit.getDirection() == expectedFace
                                    && eyePos.distanceToSqr(secondHit.getLocation()) <= reachDistance * reachDistance) {
                                return secondHit;
                            }
                        }
                    }
                }
            }

            return null;
        }

        public boolean selectHotbarSlotForPlan(PlacementPlan plan) {
            LocalPlayer player = mc.player;
            if (player == null || plan == null) {
                return false;
            }
            if (plan.hand() == InteractionHand.OFF_HAND) {
                return true;
            }
            if (plan.hotbarSlot() >= 0 && plan.hotbarSlot() < 9) {
                player.getInventory().selected = plan.hotbarSlot();
                return true;
            }
            return false;
        }

        /**
         * Naprawiona walidacja docelowego woksela:
         * - Dla bloków bez kolizji (`shape.isEmpty()`, takich jak Redstone Dust, Tory,
         * Dźwignie, Guziki, Pochodnie)
         * NIE blokuje stawiania, gdy gracz stoi obok lub w tym polu!
         * - Obsługuje dokładanie drugiej połówki do `SlabType.DOUBLE`.
         */
        public boolean canPlaceAtTargetVoxel(
                Level level,
                LocalPlayer player,
                BlockPos targetPos,
                BlockState desiredState) {
            BlockState existing = level.getBlockState(targetPos);
            boolean isCompletingDoubleSlab = existing.getBlock() == desiredState.getBlock()
                    && DirectionalStateSolver.isDoubleSlab(desiredState)
                    && existing.getValue(SlabBlock.TYPE) != SlabType.DOUBLE;

            if (!existing.canBeReplaced() && !isCompletingDoubleSlab) {
                return false;
            }

            Block desiredBlock = desiredState.getBlock();

            // Jeśli stawiamy rośliny/uprawy (trzcina cukrowa, pszenica, marchew, kaktus itp.),
            // sprawdź czy może przeżyć (np. czy obok ziemi jest już nalana woda w rynnie!). Jeśli nie -> nie stawiaj na sucho!
            if (DirectionalStateSolver.isCropOrVegetation(desiredState)) {
                if (!desiredState.canSurvive(level, targetPos)) {
                    return false;
                }
            }

            if (DirectionalStateSolver.requiresBottomSupportOnly(desiredState)
                    || (desiredBlock instanceof FallingBlock && desiredBlock != Blocks.SCAFFOLDING)) {
                BlockState below = level.getBlockState(targetPos.below());
                if (below.isAir() || below.canBeReplaced()) {
                    return false;
                }
            }

            if (desiredBlock instanceof BaseRailBlock) {
                AABB fullVoxelBox = new AABB(
                        targetPos.getX(), targetPos.getY(), targetPos.getZ(),
                        targetPos.getX() + 1.0D, targetPos.getY() + 1.0D, targetPos.getZ() + 1.0D);
                if (player.getBoundingBox().intersects(fullVoxelBox)) {
                    return false;
                }
            }

            VoxelShape shape = desiredState.getCollisionShape(level, targetPos, CollisionContext.of(player));
            if (!shape.isEmpty()) {
                AABB blockBox = shape.bounds().move(targetPos);
                if (player.getBoundingBox().inflate(0.04D).intersects(blockBox)) {
                    return false;
                }
                List<Entity> entities = level.getEntities(null, blockBox);
                for (Entity entity : entities) {
                    if (entity.blocksBuilding && !entity.isSpectator()) {
                        return false;
                    }
                }
            }
            return true;
        }

        /**
         * Zabezpieczenie kolizyjne:
         * - Dla torów, redstone'a i roślin: gracz NIE MOŻE stać wewnątrz woksela docelowego!
         * - Dla bloków pełnych i z kolizją: gracz musi mieć bezpieczny margines clearance (+0.05m).
         */
        public boolean isIntersectingPlayer(
                LocalPlayer player,
                Level level,
                BlockPos targetPos,
                BlockState desiredState) {
            if (player == null || level == null || targetPos == null || desiredState == null) {
                return false;
            }

            AABB fullVoxelBox = new AABB(
                    targetPos.getX(), targetPos.getY(), targetPos.getZ(),
                    targetPos.getX() + 1.0D, targetPos.getY() + 1.0D, targetPos.getZ() + 1.0D);

            if (desiredState.getBlock() instanceof BaseRailBlock
                    || desiredState.getBlock() instanceof RedStoneWireBlock
                    || desiredState.getBlock() instanceof TorchBlock
                    || desiredState.getBlock() instanceof BushBlock
                    || desiredState.getBlock() instanceof SugarCaneBlock) {
                if (player.blockPosition().equals(targetPos) || player.getBoundingBox().intersects(fullVoxelBox)) {
                    return true;
                }
            }

            VoxelShape shape = desiredState.getCollisionShape(level, targetPos, CollisionContext.of(player));
            if (!shape.isEmpty()) {
                AABB blockBox = shape.bounds().move(targetPos);
                if (player.getBoundingBox().inflate(0.05D).intersects(blockBox)) {
                    return true;
                }
                for (net.minecraft.world.entity.player.Player other : level.players()) {
                    if (other != player && !other.isSpectator()) {
                        if (other.getBoundingBox().inflate(0.05D).intersects(blockBox)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        public boolean hasRequiredItemInInventory(LocalPlayer player, BlockState desiredState) {
            if (player == null || desiredState == null) {
                return false;
            }
            if (player.getAbilities().instabuild) {
                return true;
            }
            Level level = player.level();
            long now = level != null ? level.getGameTime() : -1;
            if (now != lastInvCacheGameTime) {
                lastInvCacheGameTime = now;
                cachedInventoryItems.clear();
                Inventory inv = player.getInventory();
                for (int i = 0; i < 36; i++) {
                    ItemStack st = inv.getItem(i);
                    if (!st.isEmpty()) {
                        cachedInventoryItems.add(st.getItem());
                    }
                }
            }
            Item reqItem = resolveRequiredItemForState(desiredState);
            if (reqItem == Items.AIR) {
                return true;
            }
            if (cachedInventoryItems.contains(reqItem)) {
                return true;
            }
            return findStrictSlotForState(player, desiredState, false) != null;
        }

        /**
         * POPRAWKA A: Tolerancja wody i bloków waterlogged w `isValidSupportBlock`.
         * Zezwala na opieranie bloków i rusztowań o solidne ścianki rynny z wodą
         * (`state.isFaceSturdy(level, pos, face, SupportType.FULL)`), niezależnie od obecności wody obok.
         */
        public static boolean isValidSupportBlock(Level level, BlockPos pos, BlockState state, Direction face) {
            if (state == null || state.isAir() || state.canBeReplaced()) {
                return false;
            }
            // Sam płyn (woda/lawa) nie jest sztywnym podparciem pod kliknięcie
            if (state.getBlock() instanceof LiquidBlock) {
                return false;
            }
            Block b = state.getBlock();
            if (DirectionalStateSolver.isCropOrVegetation(state)) {
                return false;
            }
            if (b instanceof RedStoneWireBlock
                    || b instanceof BaseRailBlock
                    || b instanceof TorchBlock
                    || b instanceof FaceAttachedHorizontalDirectionalBlock
                    || b instanceof BasePressurePlateBlock
                    || b instanceof SugarCaneBlock
                    || b instanceof BushBlock) {
                return false;
            }
            // Jeśli blok ma pełną, twardą ściankę w kierunku dostawianego bloku, jest w 100% poprawnym oparciem
            // (nawet jeśli jest waterlogged lub styka się z wodą w rynnie!)
            if (face != null && state.isFaceSturdy(level, pos, face, SupportType.FULL)) {
                return true;
            }
            VoxelShape shape = state.getCollisionShape(level, pos);
            return !shape.isEmpty();
        }

        public static boolean doesBlockRequireSneakToPlaceAgainst(BlockState state) {
            Block b = state.getBlock();
            if (b instanceof DiodeBlock
                    || b instanceof LeverBlock
                    || b instanceof ButtonBlock
                    || b instanceof DispenserBlock
                    || b instanceof HopperBlock
                    || b instanceof NoteBlock
                    || b instanceof DaylightDetectorBlock) {
                return true;
            }
            String key = b.getDescriptionId();
            return state.hasBlockEntity()
                    || key.contains("door")
                    || key.contains("trapdoor")
                    || key.contains("gate")
                    || key.contains("button")
                    || key.contains("lever")
                    || key.contains("anvil")
                    || key.contains("crafting_table")
                    || key.contains("repeater")
                    || key.contains("comparator")
                    || key.contains("bed")
                    || key.contains("scaffolding");
        }

        public static Vec3 computeFaceSamplePoint(BlockPos pos, Direction face, double u, double v) {
            double x = pos.getX();
            double y = pos.getY();
            double z = pos.getZ();

            return switch (face) {
                case DOWN -> new Vec3(x + u, y, z + v);
                case UP -> new Vec3(x + u, y + 1.0D, z + v);
                case NORTH -> new Vec3(x + u, y + v, z);
                case SOUTH -> new Vec3(x + u, y + v, z + 1.0D);
                case WEST -> new Vec3(x, y + v, z + u);
                case EAST -> new Vec3(x + 1.0D, y + v, z + u);
            };
        }

        public static Rotation computeRotationToVec(Vec3 eyePos, Vec3 targetVec) {
            double dx = targetVec.x - eyePos.x;
            double dy = targetVec.y - eyePos.y;
            double dz = targetVec.z - eyePos.z;
            double horizDist = Math.sqrt(dx * dx + dz * dz);

            float yaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
            float pitch = (float) (-(Mth.atan2(dy, horizDist) * (180.0D / Math.PI)));
            return new Rotation(Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90.0F, 90.0F));
        }

        private static double angularDistanceSq(float yaw1, float pitch1, float yaw2, float pitch2) {
            double dYaw = Mth.wrapDegrees(yaw2 - yaw1);
            double dPitch = pitch2 - pitch1;
            return dYaw * dYaw + dPitch * dPitch;
        }

        public static Item resolveRequiredItemForState(BlockState desiredState) {
            if (desiredState == null) {
                return Items.AIR;
            }
            Block block = desiredState.getBlock();
            if (block == Blocks.WATER) {
                return Items.WATER_BUCKET;
            }
            if (block == Blocks.LAVA) {
                return Items.LAVA_BUCKET;
            }
            if (block == Blocks.POWDER_SNOW) {
                return Items.POWDER_SNOW_BUCKET;
            }
            if (block == Blocks.REDSTONE_WIRE) {
                return Items.REDSTONE;
            }
            if (block == Blocks.TRIPWIRE) {
                return Items.STRING;
            }
            if (block == Blocks.WALL_TORCH) {
                return Items.TORCH;
            }
            if (block == Blocks.REDSTONE_WALL_TORCH) {
                return Items.REDSTONE_TORCH;
            }
            if (block == Blocks.SOUL_WALL_TORCH) {
                return Items.SOUL_TORCH;
            }
            return block.asItem();
        }

        public static boolean doesStackMatchState(ItemStack stack, BlockState desiredState) {
            if (stack == null || stack.isEmpty() || desiredState == null) {
                return false;
            }
            Block desiredBlock = desiredState.getBlock();
            Item requiredItem = resolveRequiredItemForState(desiredState);

            // Dla wody wymagane jest wyłącznie pełne wiadro wody (Items.WATER_BUCKET), nigdy puste Items.BUCKET!
            if (desiredBlock == Blocks.WATER) {
                return stack.is(Items.WATER_BUCKET);
            }
            if (desiredBlock == Blocks.LAVA) {
                return stack.is(Items.LAVA_BUCKET);
            }

            if (requiredItem != Items.AIR && stack.is(requiredItem)) {
                return true;
            }
            if (stack.getItem() instanceof BlockItem blockItem
                    && AstraSchematicManager.isSameBlockFamily(blockItem.getBlock(), desiredBlock)) {
                return true;
            }
            return false;
        }

        public SlotSelection findStrictSlotForState(
                LocalPlayer player,
                BlockState desiredState,
                boolean performSwapIfInMainInventory) {
            ItemStack offhand = player.getOffhandItem();
            if (doesStackMatchState(offhand, desiredState)) {
                return new SlotSelection(player.getInventory().selected, InteractionHand.OFF_HAND);
            }

            Inventory inv = player.getInventory();
            int current = inv.selected;
            if (current >= 0 && current < 9 && doesStackMatchState(inv.getItem(current), desiredState)) {
                return new SlotSelection(current, InteractionHand.MAIN_HAND);
            }

            for (int i = 0; i < 9; i++) {
                if (doesStackMatchState(inv.getItem(i), desiredState)) {
                    return new SlotSelection(i, InteractionHand.MAIN_HAND);
                }
            }

            int matchingMainSlot = -1;
            for (int i = 9; i < 36; i++) {
                if (doesStackMatchState(inv.getItem(i), desiredState)) {
                    matchingMainSlot = i;
                    break;
                }
            }

            if (matchingMainSlot == -1 && player.getAbilities().instabuild && performSwapIfInMainInventory) {
                Item reqItem = resolveRequiredItemForState(desiredState);
                if (reqItem != Items.AIR && mc.gameMode != null) {
                    int targetSlot = selectBestHotbarSwapTarget(inv);
                    ItemStack creativeStack = new ItemStack(reqItem, 64);
                    inv.setItem(targetSlot, creativeStack);
                    mc.gameMode.handleCreativeModeItemAdd(creativeStack, 36 + targetSlot);
                    inv.selected = targetSlot;
                    return new SlotSelection(targetSlot, InteractionHand.MAIN_HAND);
                }
            }

            if (matchingMainSlot != -1) {
                if (!performSwapIfInMainInventory) {
                    return new SlotSelection(0, InteractionHand.MAIN_HAND);
                }
                int targetHotbarSlot = selectBestHotbarSwapTarget(inv);
                if (swapFromInventoryToHotbar(player, matchingMainSlot, targetHotbarSlot)) {
                    return new SlotSelection(targetHotbarSlot, InteractionHand.MAIN_HAND);
                }
            }

            return null;
        }

        public int selectBestHotbarSwapTarget(Inventory inv) {
            for (int i = 0; i < 9; i++) {
                if (inv.getItem(i).isEmpty()) {
                    return i;
                }
            }
            for (int i = 8; i >= 0; i--) {
                if (!inv.getItem(i).isDamageableItem()) {
                    return i;
                }
            }
            return inv.selected >= 0 && inv.selected < 9 ? inv.selected : 0;
        }

        public boolean swapFromInventoryToHotbar(LocalPlayer player, int sourceInventorySlot, int targetHotbarSlot) {
            if (mc.gameMode == null || player.inventoryMenu == null) {
                return false;
            }
            if (inventorySwapCooldown > 0) {
                return false;
            }
            mc.gameMode.handleInventoryMouseClick(
                    player.inventoryMenu.containerId,
                    sourceInventorySlot,
                    targetHotbarSlot,
                    ClickType.SWAP,
                    player);
            player.getInventory().selected = targetHotbarSlot;
            this.inventorySwapCooldown = INVENTORY_SWAP_COOLDOWN_TICKS;
            return true;
        }
    }

    // =========================================================================
    // 5. PILLAR-UP ENGINE (SAFE CENTER-LOCKED VERTICAL JUMP-AND-PLACE)
    // =========================================================================

    public static final class PillarUpEngine {

        public enum PillarState {
            INACTIVE,
            CENTER_AND_PITCH_DOWN,
            TRIGGER_JUMP,
            WAIT_FOR_APEX_AND_PLACE,
            SETTLE_ON_PILLAR
        }

        private static final float TARGET_PILLAR_PITCH = 89.2F;
        private static final double MIN_APEX_ELEVATION = 1.12D;
        private static final int MAX_PILLAR_TICKS = 30;

        private final IBaritone baritone;
        private final AstraRotationController rotationController;
        private final BlockStatePlacementEngine placementEngine;
        private final AuxiliaryBlockManager auxiliaryBlockManager;
        private final AstraSchematicManager manager;
        private final Minecraft mc;

        private PillarState state = PillarState.INACTIVE;
        private BlockPos targetPillarBlockPos = null;
        private BlockState blockStateToPlace = null;
        private boolean isAuxiliaryPillar = true;
        private int pillarTicks = 0;
        private int pillarCooldownTicks = 0;

        public void tickCooldowns() {
            if (pillarCooldownTicks > 0) {
                pillarCooldownTicks--;
            }
        }

        public PillarUpEngine(
                IBaritone baritone,
                AstraRotationController rotationController,
                BlockStatePlacementEngine placementEngine,
                AuxiliaryBlockManager auxiliaryBlockManager,
                AstraSchematicManager manager) {
            this.baritone = baritone;
            this.rotationController = rotationController;
            this.placementEngine = placementEngine;
            this.auxiliaryBlockManager = auxiliaryBlockManager;
            this.manager = manager;
            this.mc = Minecraft.getInstance();
        }

        private IBaritone resolveBaritone() {
            if (this.baritone != null) {
                return this.baritone;
            }
            try {
                return BaritoneAPI.getProvider().getPrimaryBaritone();
            } catch (Throwable ignored) {
                return null;
            }
        }

        public boolean isActive() {
            return state != PillarState.INACTIVE;
        }

        public boolean shouldPillarForTarget(LocalPlayer player, Level level, BlockPos targetPos) {
            if (player == null || level == null || targetPos == null || pillarCooldownTicks > 0) {
                return false;
            }
            if (!player.onGround() && !player.isInWater()) {
                return false;
            }

            boolean isDirectlyAboveColumn = targetPos.getX() == player.getBlockX()
                    && targetPos.getZ() == player.getBlockZ()
                    && targetPos.getY() >= player.getBlockY();

            boolean isAbove = targetPos.getY() > player.getBlockY();
            if (!isDirectlyAboveColumn && !isAbove) {
                return false;
            }

            // Jeśli gracz jest już na pozycji i może bezpośrednio dosięgnąć bloku
            if (manager != null && manager.getNavigator() != null) {
                BlockState desiredState = (manager.activeTask != null && manager.activeTask.targetPos().equals(targetPos))
                        ? manager.activeTask.desiredState()
                        : ((manager.getBuildQueue() != null) ? manager.getBuildQueue().getPendingVoxels().get(targetPos) : null);
                if (desiredState != null && manager.getNavigator().isInPositionForTask(targetPos, desiredState)) {
                    return false;
                }
            }

            double horizDistSq = Math.pow(targetPos.getX() + 0.5D - player.getX(), 2)
                    + Math.pow(targetPos.getZ() + 0.5D - player.getZ(), 2);
            if (horizDistSq > 18.0D) {
                return false;
            }

            BlockPos playerFeet = player.blockPosition();
            if (!canPillarUpFrom(level, playerFeet)) {
                return false;
            }

            return auxiliaryBlockManager.findAuxiliaryBlockSlot(player, placementEngine, false) != null;
        }

        public boolean canStartVerticalEscapePillar(
                LocalPlayer player,
                Level level,
                BlockPos intersectingPos,
                BlockState desiredState) {
            if (player == null || level == null || intersectingPos == null || !player.onGround() || pillarCooldownTicks > 0) {
                return false;
            }
            BlockPos playerFeet = player.blockPosition();
            if (!playerFeet.equals(intersectingPos) && !playerFeet.above().equals(intersectingPos)) {
                return false;
            }
            BlockState feetCurrentState = level.getBlockState(playerFeet);
            // Nie można podbudować się ze stawianiem bloku w miejscu stóp, jeśli stopy są zajęte przez blok nie do zastąpienia
            if (!feetCurrentState.isAir() && !feetCurrentState.canBeReplaced()) {
                return false;
            }
            return canPillarUpFrom(level, playerFeet);
        }

        private boolean canPillarUpFrom(Level level, BlockPos playerFeet) {
            BlockPos below = playerFeet.below();
            BlockState belowState = level.getBlockState(below);
            if (!BlockStatePlacementEngine.isValidSupportBlock(level, below, belowState, Direction.UP)) {
                return false;
            }
            BlockState headAbove1 = level.getBlockState(playerFeet.above(1));
            BlockState headAbove2 = level.getBlockState(playerFeet.above(2));
            return headAbove1.getCollisionShape(level, playerFeet.above(1)).isEmpty()
                    && headAbove2.getCollisionShape(level, playerFeet.above(2)).isEmpty();
        }

        public void startPillarForElevation(LocalPlayer player, Level level) {
            if (player == null || level == null) {
                return;
            }
            BlockPos feetPos = player.blockPosition();
            if (manager != null && manager.getBuildQueue().getPendingVoxels().containsKey(feetPos)) {
                BlockState schemState = manager.getBuildQueue().getPendingVoxels().get(feetPos);
                if (DirectionalStateSolver.getLayerDependencyOrder(schemState) == 0
                        && placementEngine.findStrictSlotForState(player, schemState, false) != null) {
                    startPillar(feetPos, schemState, false);
                    return;
                }
            }
            BlockState auxState = auxiliaryBlockManager.resolveBestAvailableAuxiliaryState(player);
            if (auxState == null) {
                return;
            }
            startPillar(feetPos, auxState, true);
        }

        public void startPillar(BlockPos feetPosToFill, BlockState stateToPlace, boolean isAuxiliary) {
            if (feetPosToFill == null || stateToPlace == null) {
                return;
            }
            IBaritone b = resolveBaritone();
            if (b != null && b.getPathingBehavior().isPathing()) {
                b.getPathingBehavior().cancelEverything();
            }
            this.targetPillarBlockPos = feetPosToFill.immutable();
            this.blockStateToPlace = stateToPlace;
            this.isAuxiliaryPillar = isAuxiliary;
            this.state = PillarState.CENTER_AND_PITCH_DOWN;
            this.pillarTicks = 0;
        }

        public void tickPillar(LocalPlayer player, Level level) {
            if (player == null || level == null || targetPillarBlockPos == null) {
                abortPillar();
                return;
            }

            pillarTicks++;
            if (pillarTicks > MAX_PILLAR_TICKS) {
                abortPillar();
                return;
            }

            IBaritone b = resolveBaritone();
            if (b != null) {
                if (b.getPathingBehavior().isPathing()) {
                    b.getPathingBehavior().cancelEverything();
                }
                b.getInputOverrideHandler().clearAllKeys();
            }

            BlockStatePlacementEngine.SlotSelection slotSel = isAuxiliaryPillar
                    ? auxiliaryBlockManager.findAuxiliaryBlockSlot(player, placementEngine, true)
                    : placementEngine.findStrictSlotForState(player, blockStateToPlace, true);

            if (slotSel == null) {
                abortPillar();
                return;
            }
            if (slotSel.hand() == InteractionHand.MAIN_HAND && player.getInventory().selected != slotSel.slot()) {
                player.getInventory().selected = slotSel.slot();
            }

            BlockPos supportBelow = targetPillarBlockPos.below();
            Vec3 topFaceCenter = BlockStatePlacementEngine.computeExactFaceHitVec(supportBelow, Direction.UP);

            Rotation rawDown = BlockStatePlacementEngine.computeRotationToVec(player.getEyePosition(1.0F),
                    topFaceCenter);
            float clampedDownPitch = Mth.clamp(Math.max(rawDown.getPitch(), TARGET_PILLAR_PITCH), 88.5F, 89.7F);
            Rotation quantizedDown = rotationController.quantizeToPlayerGrid(player, player.getYRot(),
                    clampedDownPitch);
            rotationController.requestRotation(
                    quantizedDown,
                    AstraRotationController.AimPriority.PLACEMENT_RAYCAST,
                    4);
            rotationController.stepImmediatelyWithinTick();

            stabilizePlayerOnPillarCenter(player, targetPillarBlockPos);

            switch (state) {
                case CENTER_AND_PITCH_DOWN -> {
                    setSneak(player, true);
                    if (player.onGround() && player.getXRot() >= 84.5F) {
                        this.state = PillarState.TRIGGER_JUMP;
                    }
                }

                case TRIGGER_JUMP -> {
                    if (!player.onGround()) {
                        this.state = PillarState.WAIT_FOR_APEX_AND_PLACE;
                        return;
                    }
                    Vec3 vel = player.getDeltaMovement();
                    player.setDeltaMovement(0.0D, vel.y, 0.0D);
                    setSneak(player, true);
                    player.jumpFromGround();
                    if (b != null) {
                        b.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                    }
                    this.state = PillarState.WAIT_FOR_APEX_AND_PLACE;
                }

                case WAIT_FOR_APEX_AND_PLACE -> {
                    setSneak(player, true);
                    double risen = player.getY() - targetPillarBlockPos.getY();
                    if (risen >= MIN_APEX_ELEVATION
                            && !placementEngine.isIntersectingPlayer(player, level, targetPillarBlockPos,
                                    blockStateToPlace)) {

                        BlockHitResult liveHit = rotationController.verifyLiveLookRaytrace(
                                supportBelow,
                                Direction.UP,
                                BlockStatePlacementEngine.SURVIVAL_REACH_DISTANCE);

                        if (liveHit != null && GrimPacketOrder.place(player, liveHit, slotSel.hand())) {
                            if (isAuxiliaryPillar && manager != null) {
                                manager.recordScaffoldPlacement(targetPillarBlockPos);
                                manager.getScaffoldingSolver().onScaffoldStepPlaced(targetPillarBlockPos);
                            } else if (!isAuxiliaryPillar && manager != null) {
                                manager.getBuildQueue().markCompleted(targetPillarBlockPos);
                            }
                            this.state = PillarState.SETTLE_ON_PILLAR;
                            return;
                        }
                    }

                    if (player.onGround() && pillarTicks > 6) {
                        abortPillar();
                    }
                }

                case SETTLE_ON_PILLAR -> {
                    setSneak(player, true);
                    if (player.onGround()) {
                        completePillar();
                    }
                }

                default -> abortPillar();
            }
        }

        private void stabilizePlayerOnPillarCenter(LocalPlayer player, BlockPos columnPos) {
            setSneak(player, true);
            double centerX = columnPos.getX() + 0.5D;
            double centerZ = columnPos.getZ() + 0.5D;
            double dx = centerX - player.getX();
            double dz = centerZ - player.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);

            if (dist > 0.14D) {
                dx /= dist;
                dz /= dist;
                float yawRad = player.getYRot() * ((float) Math.PI / 180.0F);
                double lookForwardX = -Mth.sin(yawRad);
                double lookForwardZ = Mth.cos(yawRad);
                double lookLeftX = Mth.cos(yawRad);
                double lookLeftZ = Mth.sin(yawRad);

                double forwardDot = dx * lookForwardX + dz * lookForwardZ;
                double leftDot = dx * lookLeftX + dz * lookLeftZ;

                IBaritone b = resolveBaritone();
                if (b != null) {
                    var ih = b.getInputOverrideHandler();
                    ih.setInputForceState(Input.MOVE_FORWARD, forwardDot > 0.25D);
                    ih.setInputForceState(Input.MOVE_BACK, forwardDot < -0.25D);
                    ih.setInputForceState(Input.MOVE_LEFT, leftDot > 0.25D);
                    ih.setInputForceState(Input.MOVE_RIGHT, leftDot < -0.25D);
                }
            }
        }

        private void setSneak(LocalPlayer player, boolean sneak) {
            IBaritone b = resolveBaritone();
            if (b != null) {
                b.getInputOverrideHandler().setInputForceState(Input.SNEAK, sneak);
            }
            if (mc.options != null) {
                mc.options.keyShift.setDown(sneak);
            }
            player.setShiftKeyDown(sneak);
        }

        public void completePillar() {
            LocalPlayer player = mc.player;
            IBaritone b = resolveBaritone();
            if (b != null) {
                b.getInputOverrideHandler().setInputForceState(Input.JUMP, false);
            }
            if (player != null) {
                setSneak(player, true);
                if (manager != null) {
                    manager.sneakAppliedByFsm = true;
                }
            }
            rotationController.releasePriority(AstraRotationController.AimPriority.PLACEMENT_RAYCAST);
            this.state = PillarState.INACTIVE;
            this.targetPillarBlockPos = null;
            this.blockStateToPlace = null;
            this.pillarTicks = 0;
        }

        public void abortPillar() {
            this.pillarCooldownTicks = 25;
            if (this.targetPillarBlockPos != null && manager != null && manager.getBuildQueue() != null) {
                manager.getBuildQueue().deferVoxel(this.targetPillarBlockPos, 35);
                manager.getBuildQueue().recordFailedAttempt(this.targetPillarBlockPos);
            }
            completePillar();
        }
    }

    // =========================================================================
    // 6. SCHEMATIC BUILD NAVIGATOR (90° AXIAL CORRIDOR + SUB-BLOCK CENTERING)
    // =========================================================================

    public static class SchematicBuildNavigator {

        public enum NavigationStatus {
            ALREADY_IN_POSITION,
            UNSTICKING_FROM_VOXEL,
            PATHING_ENGAGED,
            APPROACHING_SHORELINE,
            REQUIRES_BRIDGE_OR_SCAFFOLD,
            BLOCKED_BY_LOCK
        }

        private static final int MIN_REPATH_COOLDOWN_TICKS = 10;
        private static final int MIN_NAVIGATION_LOCK_TICKS = 4;
        private static final int SHORELINE_SEARCH_RADIUS = 14;

        private final IBaritone baritone;
        private final BlockStatePlacementEngine placementEngine;
        private final AstraRotationController rotationController;
        private final Minecraft mc;
        private SchematicBuildQueue buildQueue;

        private BlockPos activeTaskPos;
        private Goal lastIssuedGoal;
        private int repathCooldownTicks = 0;
        private int navigationLockTicks = 0;
        private int pauseForPlacementTicks = 0;
        private boolean collisionMovementActive = false;
        private boolean axialMicroCenteringActive = false;
        private int inGoalUnableToPlaceTicks = 0;

        public SchematicBuildNavigator(
                IBaritone baritone,
                BlockStatePlacementEngine placementEngine,
                AstraRotationController rotationController) {
            this.baritone = baritone;
            this.placementEngine = placementEngine;
            this.rotationController = rotationController;
            this.mc = Minecraft.getInstance();
        }

        public SchematicBuildNavigator(IBaritone baritone, BlockStatePlacementEngine placementEngine) {
            this(baritone, placementEngine, null);
        }

        public void setBuildQueue(SchematicBuildQueue buildQueue) {
            this.buildQueue = buildQueue;
        }

        private IBaritone resolveBaritone() {
            if (this.baritone != null) {
                return this.baritone;
            }
            try {
                return BaritoneAPI.getProvider().getPrimaryBaritone();
            } catch (Throwable ignored) {
                return null;
            }
        }

        public void tickTimers() {
            if (repathCooldownTicks > 0) {
                repathCooldownTicks--;
            }
            if (navigationLockTicks > 0) {
                navigationLockTicks--;
            }
            if (pauseForPlacementTicks > 0) {
                pauseForPlacementTicks--;
            }
        }

        public void tickCooldowns() {
            tickTimers();
        }

        public boolean isInPositionForTask(BlockPos targetPos, BlockState desiredState) {
            return isInPositionForTask(targetPos, desiredState, false);
        }

        public boolean isInPositionForTask(BlockPos targetPos, BlockState desiredState, boolean isScaffold) {
            LocalPlayer player = mc.player;
            Level level = mc.level;
            if (player == null || level == null || targetPos == null || desiredState == null) {
                return false;
            }

            if (placementEngine.isIntersectingPlayer(player, level, targetPos, desiredState)) {
                return false;
            }

            // Rygorystyczne sprawdzenie korytarza osiowego 90° na żywych współrzędnych Vec3 gracza
            if (!isScaffold && DirectionalStateSolver.isHorizontal3DDirectional(desiredState)) {
                Direction standSide = DirectionalStateSolver.getRequiredStandingSide(desiredState);
                if (standSide != null) {
                    double offsetX = player.getX() - (targetPos.getX() + 0.5D);
                    double offsetZ = player.getZ() - (targetPos.getZ() + 0.5D);
                    double along = offsetX * standSide.getStepX() + offsetZ * standSide.getStepZ();
                    double lateral = Math.abs(offsetX * standSide.getStepZ() - offsetZ * standSide.getStepX());

                    // Korytarz osiowy (dopuszcza naturalny rozrzut pozycji stania)
                    if (lateral > 0.85D || (along > 0 && lateral > along * 0.55D)) {
                        return false;
                    }
                    // Jeśli gracz stoi o cały blok wyżej, wymagaj dystansu >= 1.60 bloku
                    if (player.getY() >= targetPos.getY() + 0.85D && along < 1.60D) {
                        return false;
                    }
                    if (along < 0.85D) {
                        return false;
                    }
                }
            }

            // Sprawdzenie osiowe dla Przekaźników i Komparatorów (DiodeBlock)
            if (!isScaffold && desiredState.getBlock() instanceof DiodeBlock
                    && desiredState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                Direction standSide = DirectionalStateSolver.getRequiredStandingSide(desiredState);
                if (standSide != null) {
                    double offsetX = player.getX() - (targetPos.getX() + 0.5D);
                    double offsetZ = player.getZ() - (targetPos.getZ() + 0.5D);
                    double along = offsetX * standSide.getStepX() + offsetZ * standSide.getStepZ();
                    double lateral = Math.abs(offsetX * standSide.getStepZ() - offsetZ * standSide.getStepX());
                    if (along < 0.85D || lateral > 0.85D || (along > 0 && lateral > along * 0.60D)) {
                        return false;
                    }
                }
            }

            return placementEngine.computePlacementPlan(targetPos, desiredState, isScaffold).isPresent();
        }

        /**
         * Gdy Baritone doprowadzi gracza do woksela w korytarzu osiowym (np. 2 kratki przed tłokiem),
         * ale gracz zatrzymał się na krawędzi bloku (np. x + 0.15 zamiast x + 0.50), ta metoda
         * precyzyjnie dociąga gracza do środka osi 90° oraz cofa go o +0.15 bloku, aby zmniejszyć Pitch!
         */
        public boolean tryMicroCenterInAxialCorridor(
                LocalPlayer player,
                Level level,
                BlockPos targetPos,
                BlockState desiredState) {
            if (player == null || level == null || targetPos == null || desiredState == null) {
                return false;
            }
            if (!DirectionalStateSolver.isHorizontal3DDirectional(desiredState)
                    && !(desiredState.getBlock() instanceof DiodeBlock)) {
                return false;
            }

            Direction standSide = DirectionalStateSolver.getRequiredStandingSide(desiredState);
            if (standSide == null || !standSide.getAxis().isHorizontal()) {
                return false;
            }

            BlockPos playerFeet = player.blockPosition();
            int relX = playerFeet.getX() - targetPos.getX();
            int relZ = playerFeet.getZ() - targetPos.getZ();
            int alongBlock = relX * standSide.getStepX() + relZ * standSide.getStepZ();
            int lateralBlock = Math.abs(relX * standSide.getStepZ() - relZ * standSide.getStepX());

            // Mikro-centrowanie uruchamiamy tylko wtedy, gdy gracz już stoi na poprawnym bloku osiowym (lateralBlock == 0)
            if (lateralBlock != 0 || alongBlock < 1 || alongBlock > 4) {
                return false;
            }
            if (!isStandablePosition(level, playerFeet, targetPos, desiredState)) {
                return false;
            }

            // Jeśli plan postawienia jest już w 100% możliwy z obecnej pozycji gracza,
            // nie blokujemy i nie opóźniamy postawienia niepotrzebnym mikroruchem!
            if (placementEngine.computePlacementPlan(targetPos, desiredState, false).isPresent()) {
                clearAxialMicroCenteringIfActive();
                return false;
            }

            // Jeśli gracz stoi na alongBlock == 1 na tej samej wysokości co poziomy tłok, musi cofnąć się na alongBlock == 2
            if (DirectionalStateSolver.isHorizontal3DDirectional(desiredState)
                    && !(desiredState.getBlock() instanceof ObserverBlock)
                    && playerFeet.getY() >= targetPos.getY()
                    && alongBlock < 2) {
                BlockPos stepBackFeet = targetPos.relative(standSide, 2);
                if (isStandablePosition(level, stepBackFeet, targetPos, desiredState)) {
                    this.axialMicroCenteringActive = true;
                    applyDirectPointMovement(player, stepBackFeet.getX() + 0.5D, stepBackFeet.getZ() + 0.5D);
                    return true;
                }
                return false;
            }

            // Punkt docelowy wewnątrz woksela: idealnie w osi bocznej (0.50) i lekko cofnięty (+0.18 w stronę standSide),
            // co daje maksymalnie płaski kąt Pitch przy celowaniu w tłok!
            double idealX = playerFeet.getX() + 0.5D + standSide.getStepX() * 0.18D;
            double idealZ = playerFeet.getZ() + 0.5D + standSide.getStepZ() * 0.18D;

            double distSq = Math.pow(idealX - player.getX(), 2) + Math.pow(idealZ - player.getZ(), 2);
            if (distSq <= 0.05D) {
                clearAxialMicroCenteringIfActive();
                return false;
            }

            this.axialMicroCenteringActive = true;
            applyDirectPointMovement(player, idealX, idealZ);
            return true;
        }

        public void clearAxialMicroCenteringIfActive() {
            if (axialMicroCenteringActive) {
                IBaritone b = resolveBaritone();
                if (b != null) {
                    b.getInputOverrideHandler().clearAllKeys();
                }
                axialMicroCenteringActive = false;
            }
        }

        /**
         * Jeśli Tłok lub Przekaźnik ma już oparcie pod sobą, ale w korytarzu osiowym 90° (2 lub 3 kratki
         * po stronie `standSide`) brakuje podłogi (jest tam powietrze poza platformą), zwraca pozycję
         * brakującego bloku podłogi `standFeet.below()`, aby `ScaffoldingSolver` dobudował tam stanowisko!
         */
        /**
         * POPRAWKA D: Wycofuje bota o 1 blok w tył od zablokowanego `wrongBlockPos`,
         * umożliwiając wyjście z martwej strefy raycastu.
         */
        public void stepBackFromBlock(LocalPlayer player, Level level, BlockPos wrongPos) {
            if (player == null || level == null || wrongPos == null) {
                return;
            }
            IBaritone b = resolveBaritone();
            if (b != null && b.getPathingBehavior().isPathing()) {
                b.getPathingBehavior().cancelEverything();
            }

            this.collisionMovementActive = true;
            BlockPos playerFeet = player.blockPosition();

            // Wybierz sąsiednie bezpieczne pole oddalające gracza od wrongPos
            BlockPos bestRetreat = null;
            double bestDistSq = playerFeet.distSqr(wrongPos);

            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos candidate = playerFeet.relative(dir);
                if (candidate.equals(wrongPos)) {
                    continue;
                }
                if (isStandablePosition(level, candidate, wrongPos, null)) {
                    double dSq = candidate.distSqr(wrongPos);
                    if (dSq > bestDistSq) {
                        bestDistSq = dSq;
                        bestRetreat = candidate;
                    }
                }
            }

            if (bestRetreat == null) {
                // Jeśli nie znaleziono pola ściśle oddalającego, sprawdź dowolne bezpieczne sąsiednie pole do zejścia
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos candidate = playerFeet.relative(dir);
                    if (!candidate.equals(wrongPos) && isStandablePosition(level, candidate, wrongPos, null)) {
                        bestRetreat = candidate;
                        break;
                    }
                }
            }

            if (bestRetreat != null) {
                applyDirectPointMovement(player, bestRetreat.getX() + 0.5D, bestRetreat.getZ() + 0.5D);
            } else {
                // Jeśli nie ma pełnego woksela za plecami, cofnij się w obrębie bieżącego bloku od wrongPos
                double awayX = player.getX() - (wrongPos.getX() + 0.5D);
                double awayZ = player.getZ() - (wrongPos.getZ() + 0.5D);
                double len = Math.hypot(awayX, awayZ);
                if (len > 1.0E-4D) {
                    applyDirectPointMovement(
                            player,
                            player.getX() + (awayX / len) * 0.45D,
                            player.getZ() + (awayZ / len) * 0.45D);
                }
            }
        }

        /**
         * POPRAWKA 5: Wyznacza brakujący blok rusztowania pod stanowisko strzeleckie dla Tłoków (Y+1)
         * i Obserwatorów (Y+2), sprawdzając zarówno poziom Y-1 (gdzie oczy gracza są na idealnej wysokości
         * środka tłoka!), jak i poziom Y.
         */
        public BlockPos findMissingAxialVantageGround(
                Level level,
                BlockPos targetPos,
                BlockState desiredState) {
            if (level == null || targetPos == null || desiredState == null) {
                return null;
            }
            Direction standSide = DirectionalStateSolver.getRequiredStandingSide(desiredState);
            if (standSide == null || !standSide.getAxis().isHorizontal()) {
                return null;
            }

            int[] preferredDistances = DirectionalStateSolver.isHorizontal3DDirectional(desiredState)
                    ? new int[] { 2, 3 }
                    : new int[] { 1, 2 };
            int[] preferredDy = DirectionalStateSolver.isHorizontal3DDirectional(desiredState)
                    ? new int[] { -1, 0 }
                    : new int[] { 0, -1 };

            AstraSchematicManager mgr = AstraSchematicManager.getInstance();
            Map<BlockPos, BlockState> pending = (mgr != null && mgr.getBuildQueue() != null)
                    ? mgr.getBuildQueue().getPendingVoxels()
                    : Collections.emptyMap();

            for (int dy : preferredDy) {
                for (int dist : preferredDistances) {
                    BlockPos standFeet = targetPos.offset(
                            standSide.getStepX() * dist,
                            dy,
                            standSide.getStepZ() * dist);
                    BlockPos standHead = standFeet.above();
                    BlockPos standGround = standFeet.below();

                    if (pending.containsKey(standFeet) || pending.containsKey(standHead)) {
                        continue;
                    }

                    BlockState feetState = level.getBlockState(standFeet);
                    BlockState headState = level.getBlockState(standHead);
                    if (!feetState.isAir() || !headState.isAir()) {
                        continue;
                    }

                    BlockState groundState = level.getBlockState(standGround);
                    if (groundState.isAir() || groundState.canBeReplaced() || !groundState.getFluidState().isEmpty()) {
                        return standGround;
                    }
                }
            }
            return null;
        }

        public void pauseNavigationForPlacement(int lockTicks) {
            this.pauseForPlacementTicks = Math.max(this.pauseForPlacementTicks, lockTicks);
        }

        public void pauseNavigationForPlacement() {
            pauseNavigationForPlacement(1);
        }

        private BlockPos collisionTargetPos = null;
        private BlockPos collisionEscapeFeet = null;
        private int collisionMovementTicks = 0;

        public boolean isCollisionMovementActive() {
            return collisionMovementActive;
        }

        public void clearCollisionMovementIfActive() {
            if (collisionMovementActive) {
                IBaritone b = resolveBaritone();
                if (b != null) {
                    b.getInputOverrideHandler().clearAllKeys();
                }
                collisionMovementActive = false;
                collisionTargetPos = null;
                collisionEscapeFeet = null;
                collisionMovementTicks = 0;
            }
        }

        public boolean resolvePlayerCollisionDeadlock(BlockPos targetPos, BlockState desiredState) {
            LocalPlayer player = mc.player;
            Level level = mc.level;
            if (player == null || level == null || targetPos == null) {
                return false;
            }

            IBaritone b = resolveBaritone();
            if (b != null && b.getPathingBehavior().isPathing()) {
                b.getPathingBehavior().cancelEverything();
            }

            // Jeśli ruch ucieczkowy już trwa dla tego samego bloku:
            if (this.collisionMovementActive && this.collisionEscapeFeet != null && targetPos.equals(this.collisionTargetPos)) {
                double targetDistSq = player.distanceToSqr(
                        this.collisionEscapeFeet.getX() + 0.5D,
                        player.getY(),
                        this.collisionEscapeFeet.getZ() + 0.5D);
                double horizClearance = Math.max(
                        Math.abs(player.getX() - (targetPos.getX() + 0.5D)),
                        Math.abs(player.getZ() - (targetPos.getZ() + 0.5D)));

                // Wymagamy pełnego wyjścia z bloku (clearance >= 0.65m) oraz ustabilizowania pozycji na nowym klocku
                if (!placementEngine.isIntersectingPlayer(player, level, targetPos, desiredState)
                        && horizClearance >= 0.65D && (targetDistSq <= 0.15D || this.collisionMovementTicks > 25)) {
                    clearCollisionMovementIfActive();
                    return true;
                }

                this.collisionMovementTicks++;
                if (this.collisionMovementTicks > 35) {
                    clearCollisionMovementIfActive();
                    return false;
                }
                applyDirectPointMovement(player, this.collisionEscapeFeet.getX() + 0.5D, this.collisionEscapeFeet.getZ() + 0.5D);
                return true;
            }

            if (!placementEngine.isIntersectingPlayer(player, level, targetPos, desiredState)) {
                clearCollisionMovementIfActive();
                return true;
            }

            BlockPos playerFeet = player.blockPosition();

            // 1. Osiowy odwrót dla tłoków/przekaźników
            Direction standSide = DirectionalStateSolver.getRequiredStandingSide(desiredState);
            if (standSide != null && standSide.getAxis().isHorizontal()) {
                BlockPos axialRetreat = playerFeet.relative(standSide);
                if (isStandablePosition(level, axialRetreat, targetPos, desiredState)) {
                    startCollisionMovement(targetPos, axialRetreat);
                    applyDirectPointMovement(player, axialRetreat.getX() + 0.5D, axialRetreat.getZ() + 0.5D);
                    return true;
                }
            }

            // 2. Krok w bok na pole, z którego można od razu stawiać blok
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos lateralFeet = playerFeet.relative(dir);
                if (isStandablePosition(level, lateralFeet, targetPos, desiredState)
                        && placementEngine.canPlaceFromStandingPos(lateralFeet, targetPos, desiredState)) {
                    startCollisionMovement(targetPos, lateralFeet);
                    applyDirectPointMovement(player, lateralFeet.getX() + 0.5D, lateralFeet.getZ() + 0.5D);
                    return true;
                }
            }

            // 3. Dowolne sąsiadujące bezpieczne pole
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos escapeFeet = playerFeet.relative(dir);
                if (isStandablePosition(level, escapeFeet, targetPos, desiredState)) {
                    startCollisionMovement(targetPos, escapeFeet);
                    applyDirectPointMovement(player, escapeFeet.getX() + 0.5D, escapeFeet.getZ() + 0.5D);
                    return true;
                }
            }

            return false;
        }

        private void startCollisionMovement(BlockPos targetPos, BlockPos escapeFeet) {
            this.collisionMovementActive = true;
            this.collisionTargetPos = targetPos;
            this.collisionEscapeFeet = escapeFeet;
            this.collisionMovementTicks = 0;
        }

        private void applyDirectStepMovement(LocalPlayer player, BlockPos destFeet) {
            applyDirectPointMovement(player, destFeet.getX() + 0.5D, destFeet.getZ() + 0.5D);
        }

        private void applyDirectPointMovement(LocalPlayer player, double targetX, double targetZ) {
            double dx = targetX - player.getX();
            double dz = targetZ - player.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1.0E-4D) {
                return;
            }
            dx /= len;
            dz /= len;

            float yawRad = player.getYRot() * ((float) Math.PI / 180.0F);
            double lookForwardX = -Mth.sin(yawRad);
            double lookForwardZ = Mth.cos(yawRad);
            double lookLeftX = Mth.cos(yawRad);
            double lookLeftZ = Mth.sin(yawRad);

            double forwardDot = dx * lookForwardX + dz * lookForwardZ;
            double leftDot = dx * lookLeftX + dz * lookLeftZ;

            IBaritone b = resolveBaritone();
            if (b != null) {
                var inputHandler = b.getInputOverrideHandler();
                boolean inWater = player.isInWater() || !player.level().getFluidState(player.blockPosition()).isEmpty();
                if (inWater) {
                    inputHandler.setInputForceState(Input.SNEAK, false);
                    inputHandler.setInputForceState(Input.JUMP, true);
                } else {
                    boolean shouldSneak = len < 0.38D;
                    inputHandler.setInputForceState(Input.SNEAK, shouldSneak);
                    inputHandler.setInputForceState(Input.JUMP, false);
                }
                inputHandler.setInputForceState(Input.MOVE_FORWARD, forwardDot > 0.18D);
                inputHandler.setInputForceState(Input.MOVE_BACK, forwardDot < -0.18D);
                inputHandler.setInputForceState(Input.MOVE_LEFT, leftDot > 0.18D);
                inputHandler.setInputForceState(Input.MOVE_RIGHT, leftDot < -0.18D);
            }
        }

        public NavigationStatus navigateToTarget(BlockPos targetPos, BlockState desiredState) {
            return navigateToTarget(targetPos, desiredState, false);
        }

        public NavigationStatus navigateToTarget(BlockPos targetPos, BlockState desiredState, boolean isScaffold) {
            LocalPlayer player = mc.player;
            Level level = mc.level;
            if (player == null || level == null) {
                return NavigationStatus.BLOCKED_BY_LOCK;
            }

            IBaritone b = resolveBaritone();
            if (b == null) {
                return NavigationStatus.BLOCKED_BY_LOCK;
            }

            BlockPos playerFeet = player.blockPosition();

            if (collisionMovementActive || placementEngine.isIntersectingPlayer(player, level, targetPos, desiredState)) {
                resolvePlayerCollisionDeadlock(targetPos, desiredState);
                return NavigationStatus.UNSTICKING_FROM_VOXEL;
            } else {
                clearCollisionMovementIfActive();
            }

            if (isInPositionForTask(targetPos, desiredState, isScaffold)) {
                return NavigationStatus.ALREADY_IN_POSITION;
            }

            if (activeTaskPos != null && activeTaskPos.equals(targetPos)) {
                if (b.getPathingBehavior().calcFailedLastTick()) {
                    this.repathCooldownTicks = 30;
                    buildQueue.recordFailedAttempt(targetPos);
                    buildQueue.deferVoxel(targetPos, 140);
                    this.activeTaskPos = null;
                    this.lastIssuedGoal = null;
                    return NavigationStatus.REQUIRES_BRIDGE_OR_SCAFFOLD;
                }

                // Wykrywanie pętli pustej ścieżki () lub stania w wyznaczonym celu bez możliwości postawienia
                boolean inIssuedGoal = lastIssuedGoal != null && lastIssuedGoal.isInGoal(playerFeet);
                boolean emptyPath = b.getPathingBehavior().getCurrent() != null
                        && b.getPathingBehavior().getCurrent().getPath().length() == 0;
                if (inIssuedGoal || emptyPath) {
                    this.inGoalUnableToPlaceTicks++;
                    if (this.inGoalUnableToPlaceTicks >= 4) {
                        if (b.getPathingBehavior().isPathing()) {
                            b.getPathingBehavior().cancelEverything();
                            b.getInputOverrideHandler().clearAllKeys();
                        }
                        this.inGoalUnableToPlaceTicks = 0;
                        this.repathCooldownTicks = 20;
                        buildQueue.recordFailedAttempt(targetPos);
                        buildQueue.deferVoxel(targetPos, 140);
                        this.activeTaskPos = null;
                        this.lastIssuedGoal = null;
                        return NavigationStatus.REQUIRES_BRIDGE_OR_SCAFFOLD;
                    }
                } else {
                    this.inGoalUnableToPlaceTicks = 0;
                }

                // Jeśli Baritone wciąż oblicza ścieżkę (thinking / getInProgress), idzie po niej (isPathing)
                // lub trwa początkowy repath cooldown:
                if (b.getPathingBehavior().isPathing()
                        || b.getPathingBehavior().getInProgress().isPresent()
                        || repathCooldownTicks > 0) {
                    return NavigationStatus.PATHING_ENGAGED;
                }
                // Dopiero gdy Baritone ZAKOŃCZYŁ obliczenia i ruch (nie jest w getInProgress() ani isPathing()),
                // a gracz nie znalazł się na pozycji do postawienia:
                buildQueue.recordFailedAttempt(targetPos);
                buildQueue.deferVoxel(targetPos, 120);
                this.activeTaskPos = null;
                this.lastIssuedGoal = null;
                return NavigationStatus.REQUIRES_BRIDGE_OR_SCAFFOLD;
            }


            List<BlockPos> reachableCandidates = findReachableStandingPositions(targetPos, desiredState, false);
            if (!reachableCandidates.isEmpty()) {
                // Jeśli gracz już stoi na jednej z poprawnych pozycji stania dla tego zadania,
                // natychmiast zatrzymaj Baritone i uznaj pozycję za osiągniętą (likwidacja pętli odchodzenia w tył!)
                if (reachableCandidates.contains(playerFeet)) {
                    if (b.getPathingBehavior().isPathing()) {
                        b.getPathingBehavior().cancelEverything();
                        b.getInputOverrideHandler().clearAllKeys();
                    }
                    return NavigationStatus.ALREADY_IN_POSITION;
                }

                // Dla bloków osiowych 3D (Tłoki) oraz Diod (Przekaźniki) podajemy do Baritone maksymalnie 2
                // najlepsze cele osiowe (zamiast 6), aby Baritone nie zatrzymał się na gorszym bocznym polu!
                boolean strictAxialBlock = !isScaffold && (DirectionalStateSolver.isHorizontal3DDirectional(desiredState)
                        || (desiredState != null && desiredState.getBlock() instanceof DiodeBlock));
                int maxGoals = strictAxialBlock
                        ? Math.min(reachableCandidates.size(), 2)
                        : Math.min(reachableCandidates.size(), 6);

                Goal[] goals = new Goal[maxGoals];
                for (int i = 0; i < maxGoals; i++) {
                    goals[i] = new GoalBlock(reachableCandidates.get(i));
                }
                Goal reachableGoal = goals.length == 1 ? goals[0] : new GoalComposite(goals);

                this.activeTaskPos = targetPos;
                this.lastIssuedGoal = reachableGoal;
                this.repathCooldownTicks = MIN_REPATH_COOLDOWN_TICKS;
                this.navigationLockTicks = MIN_NAVIGATION_LOCK_TICKS;
                ensureBaritoneSettingsConfigured();
                b.getCustomGoalProcess().setGoalAndPath(reachableGoal);
                return NavigationStatus.PATHING_ENGAGED;
            }

            List<BlockPos> shorelineCandidates = findNearestStandableToTarget(targetPos);
            if (!shorelineCandidates.isEmpty()) {
                BlockPos bestShorelineFeet = shorelineCandidates.get(0);

                if (playerFeet.distSqr(bestShorelineFeet) <= 2.25D) {
                    if (b.getPathingBehavior().isPathing()) {
                        b.getPathingBehavior().cancelEverything();
                    }
                    return NavigationStatus.REQUIRES_BRIDGE_OR_SCAFFOLD;
                }

                int count = Math.min(shorelineCandidates.size(), 6);
                Goal[] shoreGoals = new Goal[count];
                for (int i = 0; i < count; i++) {
                    shoreGoals[i] = new GoalBlock(shorelineCandidates.get(i));
                }
                Goal compositeShoreGoal = new GoalComposite(shoreGoals);

                this.activeTaskPos = targetPos;
                this.lastIssuedGoal = compositeShoreGoal;
                this.repathCooldownTicks = MIN_REPATH_COOLDOWN_TICKS;
                this.navigationLockTicks = MIN_NAVIGATION_LOCK_TICKS;
                b.getCustomGoalProcess().setGoalAndPath(compositeShoreGoal);
                return NavigationStatus.APPROACHING_SHORELINE;
            }

            return NavigationStatus.REQUIRES_BRIDGE_OR_SCAFFOLD;
        }

        public Goal computeGuaranteedReachableGoal(BlockPos targetPos, BlockState desiredState) {
            List<BlockPos> candidates = findReachableStandingPositions(targetPos, desiredState, false);
            if (candidates.isEmpty()) {
                return null;
            }
            int maxGoals = Math.min(candidates.size(), 8);
            Goal[] goals = new Goal[maxGoals];
            for (int i = 0; i < maxGoals; i++) {
                goals[i] = new GoalBlock(candidates.get(i));
            }
            return goals.length == 1 ? goals[0] : new GoalComposite(goals);
        }

        private List<BlockPos> findReachableStandingPositions(
                BlockPos targetPos,
                BlockState desiredState,
                boolean excludeCurrentFeet) {
            return findReachableStandingPositions(targetPos, desiredState, excludeCurrentFeet, false);
        }

        private List<BlockPos> findReachableStandingPositions(
                BlockPos targetPos,
                BlockState desiredState,
                boolean excludeCurrentFeet,
                boolean stopOnFirst) {
            LocalPlayer player = mc.player;
            Level level = mc.level;
            if (player == null || level == null || targetPos == null) {
                return List.of();
            }

            AstraSchematicManager mgr = AstraSchematicManager.getInstance();
            BlockPos playerFeet = player.blockPosition();
            List<ScoredStandingCandidate> validCandidates = new ArrayList<>();

            int horizRadius = 4;
            int vertRadius = 3;

            boolean isHoriz3D = DirectionalStateSolver.isHorizontal3DDirectional(desiredState);
            boolean isVert3D = DirectionalStateSolver.is3DDirectional(desiredState) && !isHoriz3D;
            boolean isDiode = desiredState != null
                    && desiredState.getBlock() instanceof DiodeBlock
                    && desiredState.hasProperty(BlockStateProperties.HORIZONTAL_FACING);
            Direction requiredStandSide = DirectionalStateSolver.getRequiredStandingSide(desiredState);

            if (stopOnFirst && !excludeCurrentFeet) {
                if (isStandablePosition(level, playerFeet, targetPos, desiredState)) {
                    double simOffsetX = (isHoriz3D && requiredStandSide != null)
                            ? 0.5D + requiredStandSide.getStepX() * 0.18D
                            : 0.5D;
                    double simOffsetZ = (isHoriz3D && requiredStandSide != null)
                            ? 0.5D + requiredStandSide.getStepZ() * 0.18D
                            : 0.5D;
                    Vec3 simEye = new Vec3(
                            playerFeet.getX() + simOffsetX,
                            playerFeet.getY() + (double) player.getEyeHeight(player.getPose()),
                            playerFeet.getZ() + simOffsetZ);
                    if (placementEngine.countVisibleSupportFacesFromEye(level, player, simEye, targetPos, desiredState) > 0) {
                        return List.of(playerFeet);
                    }
                }
            }

            for (int dx = -horizRadius; dx <= horizRadius; dx++) {
                for (int dy = -vertRadius; dy <= vertRadius; dy++) {
                    for (int dz = -horizRadius; dz <= horizRadius; dz++) {
                        BlockPos candidateFeet = targetPos.offset(dx, dy, dz);

                        if (candidateFeet.equals(targetPos) || candidateFeet.above().equals(targetPos)) {
                            continue;
                        }

                        if (excludeCurrentFeet && candidateFeet.equals(playerFeet)) {
                            continue;
                        }

                        if (!isStandablePosition(level, candidateFeet, targetPos, desiredState)) {
                            continue;
                        }

                        double horizDist = Math.hypot(dx, dz);

                        // 1. RYGORYSTYCZNY FILTR KORYTARZA OSIOWEGO 90° DLA POZIOMYCH TŁOKÓW / OBSERWATORÓW / DOZOWNIKÓW
                        if (isHoriz3D && requiredStandSide != null) {
                            int along = dx * requiredStandSide.getStepX() + dz * requiredStandSide.getStepZ();
                            int lateral = Math.abs(dx * requiredStandSide.getStepZ() - dz * requiredStandSide.getStepX());

                            // Odrzuć wszystkie pozycje po złej stronie tłoka
                            if (along <= 0) {
                                continue;
                            }
                            // Odrzuć wszystkie pozycje ukośne 50°-60°!
                            // Dopuszczamy WYŁĄCZNIE idealną oś 90° (lateral == 0), a lateral == 1 tylko przy along >= 3 (kąt 18.4°).
                            if (lateral > 1 || (lateral == 1 && along < 3)) {
                                continue;
                            }
                            // Jeśli gracz stoi na poziomie tłoka (dy == 0) lub wyżej (dy > 0):
                            // Dla tłoków wymagamy along >= 2 (aby nie postawić tłoka UP);
                            // Dla obserwatorów na tym samym poziomie (dy == 0) dopuszczamy along == 1 (1 blok z tyłu na redstonie)
                            boolean isObserver = desiredState.getBlock() instanceof ObserverBlock;
                            if (dy > 0 && along < 2) {
                                continue;
                            }
                            if (dy == 0 && along < (isObserver ? 1 : 2)) {
                                continue;
                            }
                            if (dy > 1) {
                                continue; // Zbyt wysoko nad poziomym tłokiem
                            }
                        }

                        // 2. FILTR DLA PIONOWYCH TŁOKÓW (UP / DOWN)
                        if (isVert3D && requiredStandSide != null) {
                            if (requiredStandSide == Direction.UP) {
                                // Tłok skierowany w górę (FACING = UP) -> gracz musi patrzeć ostro w dół (Pitch >= 58°)
                                if (horizDist > 1.5D || dy < 0) {
                                    continue;
                                }
                            } else if (requiredStandSide == Direction.DOWN) {
                                // Tłok skierowany w dół (FACING = DOWN) -> gracz musi patrzeć ostro w górę (Pitch <= -58°)
                                if (horizDist > 1.5D || dy > -2) {
                                    continue;
                                }
                            }
                        }

                        // 3. RYGORYSTYCZNY FILTR OSIOWY DLA PRZEKAŹNIKÓW I KOMPARATORÓW (DiodeBlock)
                        if (isDiode && requiredStandSide != null) {
                            int along = dx * requiredStandSide.getStepX() + dz * requiredStandSide.getStepZ();
                            int lateral = Math.abs(dx * requiredStandSide.getStepZ() - dz * requiredStandSide.getStepX());
                            if (along < 1 || lateral > 1 || (lateral == 1 && along < 2)) {
                                continue;
                            }
                        }

                        // Dla poziomych tłoków symulujemy pozycję oka lekko cofniętą w korytarzu osiowym (+0.18),
                        // dokładnie tam, gdzie ustawia gracza metoda tryMicroCenterInAxialCorridor!
                        double simOffsetX = (isHoriz3D && requiredStandSide != null)
                                ? 0.5D + requiredStandSide.getStepX() * 0.18D
                                : 0.5D;
                        double simOffsetZ = (isHoriz3D && requiredStandSide != null)
                                ? 0.5D + requiredStandSide.getStepZ() * 0.18D
                                : 0.5D;

                        Vec3 simulatedEyePos = new Vec3(
                                candidateFeet.getX() + simOffsetX,
                                candidateFeet.getY() + (double) player.getEyeHeight(player.getPose()),
                                candidateFeet.getZ() + simOffsetZ);

                        int visibleFaces = placementEngine.countVisibleSupportFacesFromEye(
                                level, player, simulatedEyePos, targetPos, desiredState);
                        if (visibleFaces <= 0) {
                            continue;
                        }

                        if (stopOnFirst) {
                            return List.of(candidateFeet);
                        }

                        double distToPlayerSq = candidateFeet.distSqr(playerFeet);
                        double distToTargetSq = candidateFeet.distSqr(targetPos);

                        double sweetSpotPenalty = Math.abs(distToTargetSq - 4.0D) * 0.65D;
                        double losBonus = Math.min(visibleFaces, 3) * 2.5D;
                        double score = distToPlayerSq + sweetSpotPenalty - losBonus;

                        // Preferencja podłoża naturalnego i gotowej konstrukcji schematu (zamiast uciekania na zewnątrz!)
                        BlockPos groundPos = candidateFeet.below();
                        boolean isSchemGround = mgr != null && mgr.allSchematicPositions.contains(groundPos);
                        boolean isRailTask = desiredState != null && desiredState.getBlock() instanceof BaseRailBlock;

                        if (isRailTask) {
                            // Dla torów podłoże schematu (ziemia/kamień pod torami) jest właściwym traktem do stania!
                            if (isSchemGround) {
                                score -= 35.0D;
                            }
                            if (candidateFeet.equals(playerFeet)) {
                                score -= 30.0D;
                            }
                            if (level.getBlockState(candidateFeet).getBlock() instanceof BaseRailBlock) {
                                score -= 25.0D; // Umożliwia płynne przemieszczanie się wzdłuż już wybudowanej linii torów!
                            }
                        } else {
                            // Jeśli stoimy już na tej pozycji, daj bonus, aby uniknąć niepotrzebnego biegania w kółko
                            if (candidateFeet.equals(playerFeet)) {
                                score -= 30.0D;
                            }
                            // Jeśli podłoże w schemacie jest już postawione i solidne (np. gotowa podłoga/dach budynku),
                            // jest to w 100% bezpieczny i naturalny trakt do poruszania się pod koniec budowy!
                            BlockState groundWorldState = level.getBlockState(groundPos);
                            boolean isGroundAlreadyBuilt = mgr != null && !mgr.getBuildQueue().getPendingVoxels().containsKey(groundPos)
                                    && isSolidFullSchematicGround(level, groundPos, groundWorldState);

                            if (isGroundAlreadyBuilt) {
                                score -= 30.0D; // Zdecydowany bonus za korzystanie z gotowej podłogi schematu!
                            } else if (isSchemGround && mgr != null && mgr.getBuildQueue().getPendingVoxels().containsKey(groundPos)) {
                                score += 30.0D; // Umiarkowana kara tylko wtedy, gdy blok podłogi jeszcze nie powstał i czeka na budowę
                            }
                        }

                        if (dy > 0) {
                            score += 60.0D; // Kara za stanie powyżej docelowego bloku (zapobiega patrzeniu stromo w dół)
                        }

                        // Potężny bonus za idealną pozycję 90° osiowo na wprost tłoka/obserwatora (lateral == 0) z poziomu ziemi/podłoża
                        if (isHoriz3D && requiredStandSide != null) {
                            int along = dx * requiredStandSide.getStepX() + dz * requiredStandSide.getStepZ();
                            int lateral = Math.abs(dx * requiredStandSide.getStepZ() - dz * requiredStandSide.getStepX());
                            if (lateral == 0) {
                                score -= 95.0D; // Bezwzględny priorytet dla kąta prostego 90°!
                            } else {
                                score += 65.0D; // Kara dla pozycji z odchyleniem lateral == 1
                            }
                            // Dopuszczamy optymalny dystans osiowy (2..4 kratki) ze wszystkich bezpiecznych poziomów podłoża (dy <= 0)
                            if (dy <= 0 && along >= 2 && along <= 4) {
                                score -= 45.0D; // Płaski Pitch bez konieczności podbudowywania się!
                            }
                        }

                        if (isDiode && requiredStandSide != null) {
                            int lateral = Math.abs(dx * requiredStandSide.getStepZ() - dz * requiredStandSide.getStepX());
                            if (lateral == 0) {
                                score -= 60.0D;
                            }
                        }

                        validCandidates.add(new ScoredStandingCandidate(candidateFeet, score));
                        if (stopOnFirst) {
                            return List.of(candidateFeet);
                        }
                    }
                }
            }

            validCandidates.sort(Comparator.comparingDouble(ScoredStandingCandidate::score));
            List<BlockPos> result = new ArrayList<>(validCandidates.size());
            for (ScoredStandingCandidate c : validCandidates) {
                result.add(c.feetPos());
            }
            return result;
        }

        public List<BlockPos> findNearestStandableToTarget(BlockPos targetPos) {
            LocalPlayer player = mc.player;
            Level level = mc.level;
            if (player == null || level == null || targetPos == null) {
                return List.of();
            }

            AstraSchematicManager mgr = AstraSchematicManager.getInstance();
            BlockPos playerFeet = player.blockPosition();
            List<ScoredStandingCandidate> shorelineCandidates = new ArrayList<>();

            int minDy = Math.max(-12, (playerFeet.getY() - targetPos.getY()) - 4);
            int maxDy = 4;

            for (int dx = -SHORELINE_SEARCH_RADIUS; dx <= SHORELINE_SEARCH_RADIUS; dx++) {
                for (int dz = -SHORELINE_SEARCH_RADIUS; dz <= SHORELINE_SEARCH_RADIUS; dz++) {
                    for (int dy = maxDy; dy >= minDy; dy--) {
                        BlockPos candidateFeet = targetPos.offset(dx, dy, dz);
                        if (!isStandablePosition(level, candidateFeet, targetPos, null)) {
                            continue;
                        }

                        double horizDistSq = dx * dx + dz * dz;
                        double vertDist = Math.abs(dy);
                        double distToPlayerSq = candidateFeet.distSqr(playerFeet);

                        double score = horizDistSq * 2.2D + vertDist * 6.5D + distToPlayerSq * 0.15D;
                        BlockPos groundPos = candidateFeet.below();
                        BlockState groundWorldState = level.getBlockState(groundPos);
                        boolean isGroundAlreadyBuilt = mgr != null && !mgr.getBuildQueue().getPendingVoxels().containsKey(groundPos)
                                && isSolidFullSchematicGround(level, groundPos, groundWorldState);
                        if (isGroundAlreadyBuilt) {
                            score -= 30.0D; // Gotowa podłoga schematu jest doskonałą kotwicą
                        } else {
                            boolean isSchem = mgr != null && (mgr.allSchematicPositions.contains(groundPos) || mgr.allSchematicPositions.contains(candidateFeet));
                            if (isSchem) {
                                score += 20.0D;
                            } else {
                                score -= 15.0D;
                            }
                        }

                        shorelineCandidates.add(new ScoredStandingCandidate(candidateFeet, score));
                        break;
                    }
                }
            }

            shorelineCandidates.sort(Comparator.comparingDouble(ScoredStandingCandidate::score));

            List<BlockPos> result = new ArrayList<>(Math.min(shorelineCandidates.size(), 8));
            for (int i = 0; i < Math.min(shorelineCandidates.size(), 8); i++) {
                result.add(shorelineCandidates.get(i).feetPos());
            }
            return result;
        }

        public boolean hasReachableStandingPosition(BlockPos targetPos, BlockState desiredState) {
            LocalPlayer player = mc.player;
            if (player == null) {
                return false;
            }
            if (isInPositionForTask(targetPos, desiredState)) {
                return true;
            }
            return !findReachableStandingPositions(targetPos, desiredState, false, true).isEmpty();
        }

        /**
         * Rozpoznaje delikatne rośliny (w tym Trzcinę Cukrową!) oraz mechanizmy redstone,
         * po których botowi nie wolno deptać ani traktować ich jako solidnego podłoża.
         */
        public static boolean isFragileOrMechanismBlock(BlockState state) {
            if (state == null || state.isAir()) {
                return false;
            }
            Block block = state.getBlock();
            // Pospolita roślinność (trawa, trawa morska, paprocie) nie jest kruchym mechanizmem i można w niej bezpiecznie stać
            if (block instanceof SeagrassBlock
                    || block instanceof TallSeagrassBlock
                    || block == Blocks.SHORT_GRASS
                    || block == Blocks.TALL_GRASS
                    || block == Blocks.FERN
                    || block == Blocks.LARGE_FERN) {
                return false;
            }
            return block instanceof SugarCaneBlock
                    || block instanceof BambooSaplingBlock
                    || block instanceof CropBlock
                    || block instanceof FarmBlock
                    || block instanceof ObserverBlock
                    || block instanceof PistonBaseBlock
                    || block instanceof PistonHeadBlock
                    || block instanceof MovingPistonBlock
                    || block instanceof DispenserBlock
                    || block instanceof DropperBlock
                    || block instanceof HopperBlock
                    || block instanceof DiodeBlock
                    || block instanceof RepeaterBlock
                    || block instanceof ComparatorBlock
                    || block instanceof FaceAttachedHorizontalDirectionalBlock
                    || block instanceof LeverBlock
                    || block instanceof ButtonBlock
                    || block instanceof TorchBlock
                    || block instanceof BasePressurePlateBlock
                    || block instanceof BushBlock
                    || block instanceof TripWireBlock
                    || block instanceof TripWireHookBlock
                    || block instanceof BannerBlock
                    || block instanceof SignBlock
                    || block instanceof DaylightDetectorBlock
                    || block instanceof SculkSensorBlock
                    || block instanceof AbstractCandleBlock
                    || block instanceof SkullBlock;
        }

        public static boolean isSolidFullSchematicGround(Level level, BlockPos groundPos, BlockState groundState) {
            if (groundState == null || groundState.isAir() || isFragileOrMechanismBlock(groundState)) {
                return false;
            }
            if (groundState.getBlock() instanceof LiquidBlock || !groundState.getFluidState().isEmpty()) {
                // Ciecz NIE jest stałym podłożem do stania NA niej (gracz zapada się w ciecz do jej wnętrza).
                return false;
            }
            if (groundState.getBlock() instanceof BaseRailBlock) {
                BlockPos underRail = groundPos.below();
                BlockState underState = level.getBlockState(underRail);
                return isSolidFullSchematicGround(level, underRail, underState);
            }
            if (groundState.getBlock() instanceof SlabBlock || groundState.getBlock() instanceof StairBlock) {
                return true;
            }
            VoxelShape shape = groundState.getCollisionShape(level, groundPos);
            return Block.isShapeFullBlock(shape) || groundState.isFaceSturdy(level, groundPos, Direction.UP);
        }

        public static boolean isStandablePosition(Level level, BlockPos feetPos, BlockPos targetBeingPlaced) {
            return isStandablePosition(level, feetPos, targetBeingPlaced, null);
        }

        public static boolean isStandablePosition(
                Level level,
                BlockPos feetPos,
                BlockPos targetBeingPlaced,
                BlockState desiredState) {
            if (level == null || feetPos == null) {
                return false;
            }

            if (targetBeingPlaced != null
                    && (feetPos.equals(targetBeingPlaced) || feetPos.above().equals(targetBeingPlaced))) {
                return false;
            }

            BlockPos groundPos = feetPos.below();
            BlockPos headPos = feetPos.above();

            AstraSchematicManager mgr = AstraSchematicManager.getInstance();
            if (mgr != null && mgr.getBuildQueue() != null) {
                Map<BlockPos, BlockState> pending = mgr.getBuildQueue().getPendingVoxels();
                if (pending.containsKey(feetPos) && !isPassableSchematicBlock(pending.get(feetPos))) {
                    return false;
                }
                if (pending.containsKey(headPos) && !isPassableSchematicBlock(pending.get(headPos))) {
                    return false;
                }
            }

            BlockState groundState = level.getBlockState(groundPos);
            BlockState feetState = level.getBlockState(feetPos);
            BlockState headState = level.getBlockState(headPos);

            if (isFragileOrMechanismBlock(groundState)
                    || isFragileOrMechanismBlock(feetState)
                    || isFragileOrMechanismBlock(headState)) {
                return false;
            }

            if (!isSolidFullSchematicGround(level, groundPos, groundState)) {
                return false;
            }

            if (feetState.getFluidState().is(FluidTags.LAVA) || headState.getFluidState().is(FluidTags.LAVA)) {
                return false;
            }

            // Głowa nie może być w lawie
            if (!headState.getFluidState().isEmpty() && !headState.getFluidState().is(FluidTags.WATER)) {
                return false;
            }

            boolean feetPassable = feetState.isAir()
                    || !feetState.getFluidState().isEmpty()
                    || feetState.getBlock() instanceof BaseRailBlock
                    || feetState.getBlock() instanceof RedStoneWireBlock
                    || feetState.getCollisionShape(level, feetPos).isEmpty();
            boolean headPassable = headState.isAir()
                    || !headState.getFluidState().isEmpty()
                    || headState.getCollisionShape(level, headPos).isEmpty();
            return feetPassable && headPassable;
        }

        private static boolean isPassableSchematicBlock(BlockState state) {
            if (state == null || state.isAir()) {
                return true;
            }
            Block b = state.getBlock();
            return b instanceof BaseRailBlock
                    || b instanceof RedStoneWireBlock
                    || b instanceof DiodeBlock
                    || b instanceof TorchBlock
                    || b instanceof LeverBlock
                    || b instanceof ButtonBlock
                    || b instanceof BasePressurePlateBlock
                    || b instanceof CarpetBlock;
        }

        public boolean isFacingBridgeableGapToward(BlockPos targetPos) {
            LocalPlayer player = mc.player;
            Level level = mc.level;
            if (player == null || level == null || targetPos == null) {
                return false;
            }

            BlockPos playerFeet = player.blockPosition();
            int dx = targetPos.getX() - playerFeet.getX();
            int dz = targetPos.getZ() - playerFeet.getZ();
            if (dx == 0 && dz == 0) {
                return false;
            }

            List<Direction> dirsToCheck = new ArrayList<>(2);
            if (Math.abs(dx) >= Math.abs(dz)) {
                if (dx != 0) {
                    dirsToCheck.add(dx > 0 ? Direction.EAST : Direction.WEST);
                }
                if (dz != 0) {
                    dirsToCheck.add(dz > 0 ? Direction.SOUTH : Direction.NORTH);
                }
            } else {
                if (dz != 0) {
                    dirsToCheck.add(dz > 0 ? Direction.SOUTH : Direction.NORTH);
                }
                if (dx != 0) {
                    dirsToCheck.add(dx > 0 ? Direction.EAST : Direction.WEST);
                }
            }

            for (Direction dir : dirsToCheck) {
                BlockPos stepAheadGround = playerFeet.relative(dir).below();
                if (isBridgeableGroundGap(level, stepAheadGround)) {
                    return true;
                }
            }

            if (dx != 0 && dz != 0) {
                Direction dirX = dx > 0 ? Direction.EAST : Direction.WEST;
                Direction dirZ = dz > 0 ? Direction.SOUTH : Direction.NORTH;
                BlockPos diagGround = playerFeet.relative(dirX).relative(dirZ).below();
                return isBridgeableGroundGap(level, diagGround);
            }

            return false;
        }

        public boolean isFacingCliffOrWaterToward(BlockPos targetPos) {
            return isFacingBridgeableGapToward(targetPos);
        }

        public static boolean isBridgeableGroundGap(Level level, BlockPos groundPos) {
            AstraSchematicManager mgr = AstraSchematicManager.getInstance();
            if (mgr != null && mgr.allSchematicPositions.contains(groundPos)) {
                // Pozycja w schemacie przeznaczona na tory, mechanizmy lub uprawy nigdy nie jest luką na most z ziemi!
                BlockState schem = mgr.getBuildQueue() != null ? mgr.getBuildQueue().getPendingVoxels().get(groundPos) : null;
                if (schem != null && (schem.getBlock() instanceof BaseRailBlock || DirectionalStateSolver.getLayerDependencyOrder(schem) > 0 || !schem.isSolid())) {
                    return false;
                }
            }
            BlockState state = level.getBlockState(groundPos);
            if (!state.getFluidState().isEmpty()) {
                return true;
            }
            return state.isAir() || state.canBeReplaced();
        }

        public static boolean isTrueCliffOrWater(Level level, BlockPos groundPos) {
            return isBridgeableGroundGap(level, groundPos);
        }

        public boolean isNavigationLocked() {
            return navigationLockTicks > 0;
        }

        public void reset() {
            this.activeTaskPos = null;
            this.lastIssuedGoal = null;
            this.repathCooldownTicks = 0;
            this.navigationLockTicks = 0;
            this.pauseForPlacementTicks = 0;
            this.collisionMovementActive = false;
            this.axialMicroCenteringActive = false;
            this.inGoalUnableToPlaceTicks = 0;
            IBaritone b = resolveBaritone();
            if (b != null) {
                b.getInputOverrideHandler().clearAllKeys();
            }
        }

        public void resetNavigationState() {
            reset();
        }

        public void ensureBaritoneSettingsConfigured() {
            try {
                var s = BaritoneAPI.getSettings();
                s.allowPlace.value = false; // Baritone nie stawia losowych bloków pod sobą podczas nawigacji
                s.allowBreak.value = false;
                s.allowSprint.value = false;
                s.allowParkour.value = false; // Zakaz skoków nad dziurami/przepaściami (koniec ze spadaniem i wpadaniem do rowów!)
                s.allowParkourPlace.value = false;
                s.assumeSafeWalk.value = false;
                s.allowWalkOnBottomSlab.value = true;
                s.maxFallHeightNoWater.value = 1; // Maksymalnie 1 blok w dół (zero zeskakiwania z murów i platform!)
                s.maxFallHeightBucket.value = 1; // Wiadro wody w ekwipunku NIE uprawnia do skakania z wysokości!
                s.waterAvoidPenalty.value = 0.0D;
                s.mineAvoidWater.value = false;
                s.walkOnWaterOnePenalty.value = 0.5D;
                s.jumpPenalty.value = 1.0D;
                s.primaryTimeoutMS.value = 2000L;
                s.failureTimeoutMS.value = 2500L;
            } catch (Throwable ignored) {
            }
        }

        private record ScoredStandingCandidate(BlockPos feetPos, double score) {
        }
    }

    // =========================================================================
    // 7. BEDWARS BRIDGE ENGINE (ZERO-FALL + CENTER-LINE LOCK + OVERHANG PHYSICS)
    // =========================================================================

    public static class BedwarsBridgeEngine {

        public enum BridgeState {
            INACTIVE,
            ALIGNING_CAMERA,
            BACKING_TO_LEDGE,
            PLACING_BRIDGE_BLOCK,
            VERIFYING_GROUND_SUPPORT
        }

        public enum BridgeMaterialMode {
            COBBLESTONE("Bruk / Kamień"),
            DIRT("Ziemia / Netherrack"),
            SCHEMATIC("Blok ze Schematu"),
            ANY_SOLID("Dowolny Pełny Blok");

            private final String displayName;

            BridgeMaterialMode(String displayName) {
                this.displayName = displayName;
            }

            public String getDisplayName() {
                return displayName;
            }
        }

        private static boolean globalEnabled = true;
        private static boolean globalEagleUnSneakEnabled = false;
        private static BridgeMaterialMode globalMaterialMode = BridgeMaterialMode.ANY_SOLID;
        private static int globalMaxBlocksPerSecond = 6;

        private static final int MIN_BRIDGE_LOCK_TICKS = 16;
        private static final int POST_PLACE_SUPPORT_HOLD_TICKS = 1;
        public static final double SAFE_SNEAK_LEDGE_DIST = 0.45D;
        private static final double OVERHANG_PLACE_LEDGE_DIST = 0.04D;
        private static final double MAX_SAFE_OVERHANG_DIST = -0.05D;
        private static final float INITIAL_BRIDGE_PITCH = 81.2F;

        private static final double[] BRIDGE_FACE_HEIGHT_SAMPLES = {
                0.90D, 0.85D, 0.80D, 0.74D, 0.65D
        };

        private final IBaritone baritone;
        private final AstraRotationController rotationController;
        private final SchematicBuildNavigator navigator;
        private final BlockStatePlacementEngine placementEngine;
        private final AuxiliaryBlockManager auxiliaryBlockManager;
        private final AstraSchematicManager manager;
        private final Minecraft mc;

        private BridgeState state = BridgeState.INACTIVE;
        private Direction bridgeDirection = null;
        private BlockPos currentSupportBlock = null;
        private BlockPos nextBridgeBlock = null;
        private int bridgeLockTicks = 0;
        private int supportVerifyTicks = 0;
        private int placeAttemptTicks = 0;
        private int totalBridgeSessionTicks = 0;
        private static final int MAX_GLOBAL_BRIDGE_TICKS = 80;
        private boolean aborted = false;

        public boolean isAborted() {
            return aborted;
        }

        public void clearAborted() {
            this.aborted = false;
        }

        public BedwarsBridgeEngine(
                IBaritone baritone,
                AstraRotationController rotationController,
                SchematicBuildNavigator navigator,
                BlockStatePlacementEngine placementEngine,
                AuxiliaryBlockManager auxiliaryBlockManager,
                AstraSchematicManager manager) {
            this.baritone = baritone;
            this.rotationController = rotationController;
            this.navigator = navigator;
            this.placementEngine = placementEngine;
            this.auxiliaryBlockManager = auxiliaryBlockManager;
            this.manager = manager;
            this.mc = Minecraft.getInstance();
        }

        public BedwarsBridgeEngine(
                IBaritone baritone,
                AstraRotationController rotationController,
                SchematicBuildNavigator navigator,
                BlockStatePlacementEngine placementEngine) {
            this(baritone, rotationController, navigator, placementEngine, null, null);
        }

        private IBaritone resolveBaritone() {
            if (this.baritone != null) {
                return this.baritone;
            }
            try {
                return BaritoneAPI.getProvider().getPrimaryBaritone();
            } catch (Throwable ignored) {
                return null;
            }
        }

        public static boolean isEnabled() {
            return globalEnabled;
        }

        public static void setEnabled(boolean state) {
            globalEnabled = state;
            if (!state && Minecraft.getInstance().player != null) {
                releaseBridgeSneak(Minecraft.getInstance().player);
            }
        }

        public static boolean isEagleUnSneakEnabled() {
            return globalEagleUnSneakEnabled;
        }

        public static void setEagleUnSneakEnabled(boolean state) {
            globalEagleUnSneakEnabled = state;
        }

        public static BridgeMaterialMode getMaterialMode() {
            return globalMaterialMode;
        }

        public static void setMaterialMode(BridgeMaterialMode mode) {
            globalMaterialMode = mode != null ? mode : BridgeMaterialMode.ANY_SOLID;
        }

        public static void cycleMaterialMode() {
            BridgeMaterialMode[] values = BridgeMaterialMode.values();
            globalMaterialMode = values[(globalMaterialMode.ordinal() + 1) % values.length];
        }

        public static int getMaxBlocksPerSecond() {
            return globalMaxBlocksPerSecond;
        }

        public static void setMaxBlocksPerSecond(int bps) {
            globalMaxBlocksPerSecond = Mth.clamp(bps, 1, 10);
        }

        public static boolean isCurrentlyBridging() {
            AstraSchematicManager mgr = AstraSchematicManager.getInstance();
            return mgr != null && mgr.getBridgeEngine() != null && mgr.getBridgeEngine().isActive();
        }

        public static void releaseBridgeSneak(LocalPlayer player) {
            AstraSchematicManager mgr = AstraSchematicManager.getInstance();
            if (mgr != null && mgr.getBridgeEngine() != null) {
                mgr.getBridgeEngine().abortBridge();
            }
        }

        public boolean shouldBridge(BlockPos targetTaskPos, BlockState desiredState) {
            if (!globalEnabled) {
                abortBridge();
                return false;
            }

            LocalPlayer player = mc.player;
            Level level = mc.level;
            if (player == null || level == null || targetTaskPos == null) {
                abortBridge();
                return false;
            }

            if (state != BridgeState.INACTIVE && bridgeLockTicks > 0) {
                if (totalBridgeSessionTicks > MAX_GLOBAL_BRIDGE_TICKS) {
                    abortBridge();
                    return false;
                }
                if (!player.onGround() && player.fallDistance > 1.2F) {
                    abortBridge();
                    return false;
                }
                if (navigator.isInPositionForTask(targetTaskPos, desiredState)
                        && isPlayerSupportedBySolidGround(player, level)) {
                    completeBridge();
                    return false;
                }
                return true;
            }

            if (!player.onGround()) {
                return false;
            }

            if (findBridgeBlockSlot(player, desiredState, false) == null) {
                return false;
            }

            BlockPos supportUnderFeet = resolveActualSupportingBlockUnderPlayer(player, level);
            if (supportUnderFeet == null) {
                return false;
            }

            Direction resolvedDir = resolveBridgeDirection(level, supportUnderFeet, supportUnderFeet.above(),
                    targetTaskPos);
            if (resolvedDir == null) {
                return false;
            }

            this.bridgeDirection = resolvedDir;
            this.currentSupportBlock = supportUnderFeet;
            this.nextBridgeBlock = supportUnderFeet.relative(resolvedDir);
            this.state = BridgeState.ALIGNING_CAMERA;
            this.bridgeLockTicks = MIN_BRIDGE_LOCK_TICKS;
            this.supportVerifyTicks = 0;
            this.placeAttemptTicks = 0;

            IBaritone b = resolveBaritone();
            if (b != null && b.getPathingBehavior().isPathing()) {
                b.getPathingBehavior().cancelEverything();
            }
            setSneakState(player, true);
            return true;
        }

        private Direction resolveBridgeDirection(
                Level level,
                BlockPos supportUnderFeet,
                BlockPos playerFeet,
                BlockPos targetTaskPos) {
            int dx = targetTaskPos.getX() - playerFeet.getX();
            int dz = targetTaskPos.getZ() - playerFeet.getZ();
            if (dx == 0 && dz == 0) {
                return null;
            }

            List<Direction> candidateDirs = new ArrayList<>(2);
            if (Math.abs(dx) >= Math.abs(dz)) {
                if (dx != 0) {
                    candidateDirs.add(dx > 0 ? Direction.EAST : Direction.WEST);
                }
                if (dz != 0) {
                    candidateDirs.add(dz > 0 ? Direction.SOUTH : Direction.NORTH);
                }
            } else {
                if (dz != 0) {
                    candidateDirs.add(dz > 0 ? Direction.SOUTH : Direction.NORTH);
                }
                if (dx != 0) {
                    candidateDirs.add(dx > 0 ? Direction.EAST : Direction.WEST);
                }
            }

            for (Direction dir : candidateDirs) {
                BlockPos nextGroundPos = supportUnderFeet.relative(dir);
                BlockPos nextFeetPos = playerFeet.relative(dir);
                if (SchematicBuildNavigator.isBridgeableGroundGap(level, nextGroundPos)
                        && level.getBlockState(nextFeetPos).getCollisionShape(level, nextFeetPos).isEmpty()) {
                    return dir;
                }
            }

            if (candidateDirs.size() == 2) {
                Direction primary = candidateDirs.get(0);
                Direction secondary = candidateDirs.get(1);
                BlockPos diagGroundPos = supportUnderFeet.relative(primary).relative(secondary);
                if (SchematicBuildNavigator.isBridgeableGroundGap(level, diagGroundPos)) {
                    BlockPos primaryFeet = playerFeet.relative(primary);
                    if (!SchematicBuildNavigator.isStandablePosition(level, primaryFeet, targetTaskPos)) {
                        return primary;
                    }
                }
            }

            return null;
        }

        public void tickBridge(BlockPos targetTaskPos, BlockState desiredState) {
            LocalPlayer player = mc.player;
            Level level = mc.level;
            if (player == null || level == null || bridgeDirection == null) {
                abortBridge();
                return;
            }

            totalBridgeSessionTicks++;
            if (totalBridgeSessionTicks > MAX_GLOBAL_BRIDGE_TICKS) {
                abortBridge();
                return;
            }

            if (bridgeLockTicks > 0) {
                bridgeLockTicks--;
            }

            IBaritone b = resolveBaritone();
            if (b != null) {
                if (b.getPathingBehavior().isPathing()) {
                    b.getPathingBehavior().cancelEverything();
                }
                b.getInputOverrideHandler().clearAllKeys();
            }

            setSneakState(player, true);
            player.setSprinting(false);

            BlockStatePlacementEngine.SlotSelection slot = findBridgeBlockSlot(player, desiredState, true);
            if (slot == null) {
                abortBridge();
                return;
            }

            boolean slotSynced = true;
            if (slot.hand() == InteractionHand.MAIN_HAND && player.getInventory().selected != slot.slot()) {
                player.getInventory().selected = slot.slot();
                slotSynced = false;
            }

            BlockPos actualSupportingPos = resolveActualSupportingBlockUnderPlayer(player, level);
            if (actualSupportingPos != null && !actualSupportingPos.equals(this.currentSupportBlock)) {
                this.currentSupportBlock = actualSupportingPos;
                Direction updatedDir = resolveBridgeDirection(level, actualSupportingPos, actualSupportingPos.above(),
                        targetTaskPos);
                if (updatedDir != null) {
                    this.bridgeDirection = updatedDir;
                }
                this.nextBridgeBlock = actualSupportingPos.relative(this.bridgeDirection);
            }

            Vec3 eyePos = player.getEyePosition(1.0F);
            Rotation bestQuantizedTarget = computeBestBridgeLookRotation(player, level, eyePos);
            rotationController.requestRotation(
                    bestQuantizedTarget,
                    AstraRotationController.AimPriority.BRIDGE_ALIGNMENT,
                    4);
            rotationController.stepImmediatelyWithinTick();

            double distToLedge = computeSignedDistanceToLedge(player.position(), currentSupportBlock, bridgeDirection);

            switch (state) {
                case ALIGNING_CAMERA -> {
                    applyCenterLockedBridgeMovement(player, bridgeDirection, 0.50D);
                    float yawError = Math.abs(Mth.wrapDegrees(player.getYRot() - bestQuantizedTarget.getYaw()));
                    if (yawError <= 16.0F) {
                        state = BridgeState.BACKING_TO_LEDGE;
                        bridgeLockTicks = Math.max(bridgeLockTicks, MIN_BRIDGE_LOCK_TICKS);
                    }
                }

                case BACKING_TO_LEDGE -> {
                    applyCenterLockedBridgeMovement(player, bridgeDirection, 0.74D);

                    BlockHitResult earlyHit = rotationController.verifyLiveLookRaytrace(
                            currentSupportBlock,
                            bridgeDirection,
                            BlockStatePlacementEngine.SURVIVAL_REACH_DISTANCE);

                    if (earlyHit != null || distToLedge <= OVERHANG_PLACE_LEDGE_DIST) {
                        state = BridgeState.PLACING_BRIDGE_BLOCK;
                        placeAttemptTicks = 0;
                        bridgeLockTicks = Math.max(bridgeLockTicks, 14);
                    }
                }

                case PLACING_BRIDGE_BLOCK -> {
                    placeAttemptTicks++;

                    if (distToLedge > MAX_SAFE_OVERHANG_DIST) {
                        applyCenterLockedBridgeMovement(player, bridgeDirection, 0.74D);
                    }

                    if (!slotSynced) {
                        return;
                    }

                    BlockHitResult liveHit = rotationController.verifyLiveLookRaytrace(
                            currentSupportBlock,
                            bridgeDirection,
                            BlockStatePlacementEngine.SURVIVAL_REACH_DISTANCE);

                    if (liveHit != null && GrimPacketOrder.place(player, liveHit, slot.hand())) {
                        if (manager != null && nextBridgeBlock != null) {
                            if (!manager.allSchematicPositions.contains(nextBridgeBlock)) {
                                manager.recordScaffoldPlacement(nextBridgeBlock);
                                manager.getScaffoldingSolver().onScaffoldStepPlaced(nextBridgeBlock);
                            } else {
                                BlockState placed = level.getBlockState(nextBridgeBlock);
                                BlockState desired = manager.getBuildQueue().getPendingVoxels().get(nextBridgeBlock);
                                if (desired != null && AstraSchematicManager.isVoxelAlreadySatisfied(placed, desired)) {
                                    manager.getBuildQueue().markCompleted(nextBridgeBlock);
                                }
                            }
                        }
                        this.state = BridgeState.VERIFYING_GROUND_SUPPORT;
                        this.supportVerifyTicks = POST_PLACE_SUPPORT_HOLD_TICKS;
                        this.bridgeLockTicks = Math.max(this.bridgeLockTicks, 14);
                        return;
                    }

                    if (placeAttemptTicks > 16) {
                        this.state = BridgeState.ALIGNING_CAMERA;
                        this.placeAttemptTicks = 0;
                    }
                }

                case VERIFYING_GROUND_SUPPORT -> {
                    applyCenterLockedBridgeMovement(player, bridgeDirection, 0.96D);

                    if (supportVerifyTicks > 0) {
                        supportVerifyTicks--;
                        return;
                    }

                    BlockState placedState = level.getBlockState(nextBridgeBlock);
                    boolean blockConfirmed = BlockStatePlacementEngine.isValidSupportBlock(
                            level,
                            nextBridgeBlock,
                            placedState,
                            Direction.UP);

                    if (blockConfirmed && isPlayerSupportedBySolidGround(player, level)) {
                        this.currentSupportBlock = nextBridgeBlock;
                        this.nextBridgeBlock = currentSupportBlock.relative(bridgeDirection);

                        if (navigator.isInPositionForTask(targetTaskPos, desiredState)) {
                            completeBridge();
                        } else {
                            this.state = BridgeState.BACKING_TO_LEDGE;
                            this.bridgeLockTicks = MIN_BRIDGE_LOCK_TICKS;
                        }
                    } else if (supportVerifyTicks <= -5) {
                        this.state = BridgeState.PLACING_BRIDGE_BLOCK;
                        this.placeAttemptTicks = 0;
                    } else {
                        supportVerifyTicks--;
                    }
                }

                default -> abortBridge();
            }
        }

        private BlockPos resolveActualSupportingBlockUnderPlayer(LocalPlayer player, Level level) {
            BlockPos directBelow = player.blockPosition().below();
            if (BlockStatePlacementEngine.isValidSupportBlock(level, directBelow, level.getBlockState(directBelow),
                    Direction.UP)) {
                return directBelow;
            }
            if (currentSupportBlock != null
                    && BlockStatePlacementEngine.isValidSupportBlock(
                            level, currentSupportBlock, level.getBlockState(currentSupportBlock), Direction.UP)) {
                return currentSupportBlock;
            }
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos neighborBelow = directBelow.relative(dir);
                if (BlockStatePlacementEngine.isValidSupportBlock(
                        level, neighborBelow, level.getBlockState(neighborBelow), Direction.UP)) {
                    AABB blockBox = new AABB(neighborBelow.above());
                    if (player.getBoundingBox().inflate(0.05D).intersects(blockBox)) {
                        return neighborBelow;
                    }
                }
            }
            return null;
        }

        private Rotation computeBestBridgeLookRotation(LocalPlayer player, Level level, Vec3 eyePos) {
            float rawBackwardsYaw = Mth.wrapDegrees(bridgeDirection.getOpposite().toYRot());

            for (double vHeight : BRIDGE_FACE_HEIGHT_SAMPLES) {
                Vec3 samplePoint = BlockStatePlacementEngine.computeFaceSamplePoint(
                        currentSupportBlock,
                        bridgeDirection,
                        0.50D,
                        vHeight);
                Rotation candidateRot = BlockStatePlacementEngine.computeRotationToVec(eyePos, samplePoint);
                Rotation quantized = rotationController.quantizeToPlayerGrid(
                        player,
                        candidateRot.getYaw(),
                        candidateRot.getPitch());

                BlockHitResult rayHit = placementEngine.verifyRaytrace(
                        level,
                        player,
                        eyePos,
                        quantized,
                        currentSupportBlock,
                        bridgeDirection,
                        BlockStatePlacementEngine.SURVIVAL_REACH_DISTANCE);

                if (rayHit != null) {
                    return quantized;
                }
            }

            Vec3 upperEdgePoint = BlockStatePlacementEngine.computeFaceSamplePoint(
                    currentSupportBlock,
                    bridgeDirection,
                    0.50D,
                    0.88D);
            Rotation idealRot = BlockStatePlacementEngine.computeRotationToVec(eyePos, upperEdgePoint);
            float targetYaw = Mth.abs(Mth.wrapDegrees(idealRot.getYaw() - rawBackwardsYaw)) < 25.0F
                    ? idealRot.getYaw()
                    : rawBackwardsYaw;
            float targetPitch = state == BridgeState.ALIGNING_CAMERA
                    ? INITIAL_BRIDGE_PITCH
                    : Mth.clamp(idealRot.getPitch(), 77.5F, 84.5F);

            return rotationController.quantizeToPlayerGrid(player, targetYaw, targetPitch);
        }

        private double computeSignedDistanceToLedge(Vec3 playerPos, BlockPos supportBlock, Direction bridgeDir) {
            return switch (bridgeDir) {
                case EAST -> (supportBlock.getX() + 1.0D) - playerPos.x;
                case WEST -> playerPos.x - supportBlock.getX();
                case SOUTH -> (supportBlock.getZ() + 1.0D) - playerPos.z;
                case NORTH -> playerPos.z - supportBlock.getZ();
                default -> 1.0D;
            };
        }

        private void applyCenterLockedBridgeMovement(LocalPlayer player, Direction worldDir, double forwardOffset) {
            setSneakState(player, true);

            double targetX = currentSupportBlock.getX() + 0.5D + worldDir.getStepX() * forwardOffset;
            double targetZ = currentSupportBlock.getZ() + 0.5D + worldDir.getStepZ() * forwardOffset;

            double errX = targetX - player.getX();
            double errZ = targetZ - player.getZ();
            if (worldDir.getAxis() == Direction.Axis.X) {
                errZ *= 2.5D;
            } else {
                errX *= 2.5D;
            }

            double len = Math.sqrt(errX * errX + errZ * errZ);
            if (len < 1.0E-4D) {
                return;
            }
            errX /= len;
            errZ /= len;

            float yawRad = player.getYRot() * ((float) Math.PI / 180.0F);
            double lookForwardX = -Mth.sin(yawRad);
            double lookForwardZ = Mth.cos(yawRad);
            double lookLeftX = Mth.cos(yawRad);
            double lookLeftZ = Mth.sin(yawRad);

            double forwardDot = errX * lookForwardX + errZ * lookForwardZ;
            double leftDot = errX * lookLeftX + errZ * lookLeftZ;

            double distToLedge = computeSignedDistanceToLedge(player.position(), currentSupportBlock, worldDir);

            IBaritone b = resolveBaritone();
            if (b != null) {
                var inputHandler = b.getInputOverrideHandler();
                inputHandler.setInputForceState(Input.SNEAK, true);
                if (forwardDot > 0.18D) {
                    inputHandler.setInputForceState(Input.MOVE_FORWARD, true);
                } else if (forwardDot < -0.18D) {
                    // Bezwzględna blokada zsunięcia z krawędzi (Strict Zero-Fall Clamp)
                    if (distToLedge > 0.02D) {
                        inputHandler.setInputForceState(Input.MOVE_BACK, true);
                    } else if (distToLedge < -0.04D) {
                        // Jeśli gracz wysunął się za daleko, pchnij go w stronę centrum bloku
                        inputHandler.setInputForceState(Input.MOVE_FORWARD, true);
                    }
                }

                if (leftDot > 0.16D) {
                    inputHandler.setInputForceState(Input.MOVE_LEFT, true);
                } else if (leftDot < -0.16D) {
                    inputHandler.setInputForceState(Input.MOVE_RIGHT, true);
                }
            }
        }

        public boolean isPlayerSupportedBySolidGround(LocalPlayer player, Level level) {
            if (!player.onGround()) {
                return false;
            }
            AABB feetCheckBox = player.getBoundingBox().deflate(0.05D, 0.0D, 0.05D).move(0.0D, -0.08D, 0.0D);
            return !level.noCollision(player, feetCheckBox);
        }

        private void setSneakState(LocalPlayer player, boolean sneak) {
            IBaritone b = resolveBaritone();
            if (b != null) {
                b.getInputOverrideHandler().setInputForceState(Input.SNEAK, sneak);
            }
            if (mc.options != null) {
                mc.options.keyShift.setDown(sneak);
            }
            player.setShiftKeyDown(sneak);
        }

        public void completeBridge() {
            LocalPlayer player = mc.player;
            IBaritone b = resolveBaritone();
            if (b != null) {
                b.getInputOverrideHandler().clearAllKeys();
            }
            if (player != null) {
                setSneakState(player, true);
                if (manager != null) {
                    manager.sneakAppliedByFsm = true;
                }
            }
            rotationController.releasePriority(AstraRotationController.AimPriority.BRIDGE_ALIGNMENT);
            this.state = BridgeState.INACTIVE;
            this.bridgeLockTicks = 0;
            this.supportVerifyTicks = 0;
            this.placeAttemptTicks = 0;
            this.totalBridgeSessionTicks = 0;
            this.bridgeDirection = null;
            this.aborted = false;
        }

        public void abortBridge() {
            completeBridge();
            this.aborted = true;
        }

        public boolean isActive() {
            return state != BridgeState.INACTIVE;
        }

        private BlockStatePlacementEngine.SlotSelection findBridgeBlockSlot(
                LocalPlayer player,
                BlockState desiredState,
                boolean performSwap) {
            if (auxiliaryBlockManager != null) {
                BlockStatePlacementEngine.SlotSelection auxSlot = auxiliaryBlockManager.findAuxiliaryBlockSlot(player,
                        placementEngine, performSwap);
                if (auxSlot != null) {
                    return auxSlot;
                }
            }
            if (desiredState != null && DirectionalStateSolver.getLayerDependencyOrder(desiredState) == 0) {
                return placementEngine.findStrictSlotForState(player, desiredState, performSwap);
            }
            return null;
        }
    }

// =========================================================================
    // 8. SCAFFOLDING SOLVER (HORIZONTAL PLATFORM BRIDGE FIRST, VERTICAL LAST)
    // =========================================================================

    public static class ScaffoldingSolver {

        private static final int HORIZONTAL_PLATFORM_SEARCH_RADIUS = 8;
        private static final int MAX_ANCHOR_SEARCH_RADIUS = 14;

        private final Minecraft mc;
        private final BlockStatePlacementEngine placementEngine;
        private final AuxiliaryBlockManager auxiliaryBlockManager;

        private BlockPos cachedFloatingTarget = null;
        private final List<BlockPos> cachedSupportChain = new ArrayList<>();

        public ScaffoldingSolver(
                BlockStatePlacementEngine placementEngine,
                AuxiliaryBlockManager auxiliaryBlockManager) {
            this.mc = Minecraft.getInstance();
            this.placementEngine = placementEngine;
            this.auxiliaryBlockManager = auxiliaryBlockManager;
        }

        public ScaffoldingSolver(BlockStatePlacementEngine placementEngine) {
            this(placementEngine, null);
        }

        public void clearCachedChain() {
            cachedFloatingTarget = null;
            cachedSupportChain.clear();
        }

        public void onScaffoldStepPlaced(BlockPos placedPos) {
            cachedSupportChain.remove(placedPos);
        }

        public Optional<SchematicBuildQueue.BuildTask> findNextScaffoldTask(BlockPos floatingTargetPos) {
            LocalPlayer player = mc.player;
            Level level = mc.level;
            if (player == null || level == null || floatingTargetPos == null) {
                return Optional.empty();
            }

            BlockState scaffoldState = resolveAvailableScaffoldState(player);
            if (scaffoldState == null) {
                return Optional.empty();
            }

            if (!floatingTargetPos.equals(cachedFloatingTarget) || cachedSupportChain.isEmpty()) {
                BlockPos anchor = findSmartSolidAnchor(level, floatingTargetPos, player.blockPosition());
                if (anchor == null) {
                    return Optional.empty();
                }
                cachedFloatingTarget = floatingTargetPos.immutable();
                cachedSupportChain.clear();
                cachedSupportChain.addAll(computeColumnFirstSupportChain(anchor, floatingTargetPos));
            }

            AstraSchematicManager mgr = AstraSchematicManager.getInstance();
            Map<BlockPos, BlockState> pending = (mgr != null && mgr.getBuildQueue() != null)
                    ? mgr.getBuildQueue().getPendingVoxels()
                    : Collections.emptyMap();

            for (BlockPos stepPos : cachedSupportChain) {
                // Jeśli stepPos to sam docelowy woksel ze schematu, zakończ łańcuch rusztowań tuż przed nim:
                if (stepPos.equals(floatingTargetPos)) {
                    break;
                }

                // Nigdy nie stawiaj bloku pomocniczego w pozycji należącej do schematu!
                if (mgr != null && (mgr.allSchematicPositions.contains(stepPos) || pending.containsKey(stepPos))) {
                    continue;
                }

                BlockState current = level.getBlockState(stepPos);
                // NIGDY nie stawiaj rusztowania w miejscu, w którym rośnie trzcina lub inna roślina!
                if (DirectionalStateSolver.isCropOrVegetation(current)) {
                    continue;
                }
                // NIGDY nie stawiaj rusztowania bezpośrednio na trzcinie ani na uprawach!
                BlockState stateBelow = level.getBlockState(stepPos.below());
                if (DirectionalStateSolver.isCropOrVegetation(stateBelow)) {
                    continue;
                }

                if (current.isAir() || current.canBeReplaced() || (!current.getFluidState().isEmpty() && !current.getFluidState().is(FluidTags.LAVA))) {
                    if (placementEngine.hasAdjacentSupportBlock(stepPos)) {
                        return Optional.of(new SchematicBuildQueue.BuildTask(stepPos, scaffoldState, true));
                    }
                }
            }

            return Optional.empty();
        }

        /**
         * Zoptymalizowane wyszukiwanie kotwicy: w pierwszej kolejności szuka solidnego podłoża
         * DOKŁADNIE PIONOWO pod celem (dx = 0, dz = 0), aby stawiać wyłącznie minimalistyczny pionowy
         * słupek z ziemi/dna bez rozciągania poziomych mostów po mapie i wodzie!
         */
        private BlockPos findSmartSolidAnchor(Level level, BlockPos targetPos, BlockPos playerFeet) {
            AstraSchematicManager mgr = AstraSchematicManager.getInstance();

            // ETAP 0 (NAJWAŻNIEJSZY): Sprawdź pionowo bezpośrednio pod celem (dx = 0, dz = 0, dy = -1 .. -16)!
            // Pozwala to postawić minimalny pionowy słup z ziemi/dna rzeki pod blokiem BEZ rozciągania poziomych mostów po mapie!
            for (int dy = -1; dy >= -16; dy--) {
                BlockPos straightBelow = targetPos.offset(0, dy, 0);
                BlockState state = level.getBlockState(straightBelow);
                if (DirectionalStateSolver.isCropOrVegetation(state)) {
                    continue;
                }
                if (BlockStatePlacementEngine.isValidSupportBlock(level, straightBelow, state, Direction.UP)
                        && !SchematicBuildNavigator.isFragileOrMechanismBlock(state)) {
                    return straightBelow;
                }
            }

            // ETAP 1: Przeszukaj promień bezpośredni r = 1..3 wokół celu w dół (dy = 0 .. -3)
            for (int r = 1; r <= 3; r++) {
                for (int dy = 0; dy >= -3; dy--) {
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                                continue;
                            }
                            BlockPos checkPos = targetPos.offset(dx, dy, dz);
                            BlockState state = level.getBlockState(checkPos);
                            if (DirectionalStateSolver.isCropOrVegetation(state)) {
                                continue;
                            }
                            if (BlockStatePlacementEngine.isValidSupportBlock(level, checkPos, state, Direction.UP)
                                     && !SchematicBuildNavigator.isFragileOrMechanismBlock(state)) {
                                return checkPos;
                            }
                        }
                    }
                }
            }

            // ETAP 2: Fallback pod stopy gracza
            BlockPos underPlayer = playerFeet.below();
            BlockState underState = level.getBlockState(underPlayer);
            if (!DirectionalStateSolver.isCropOrVegetation(underState)
                    && BlockStatePlacementEngine.isValidSupportBlock(level, underPlayer, underState, Direction.UP)
                    && !SchematicBuildNavigator.isFragileOrMechanismBlock(underState)) {
                return underPlayer;
            }
            return null;
        }

        /**
         * Buduje łańcuch rusztowania. Jeśli kotwica jest pionowo pod celem, tworzy wyłącznie pionowy słup
         * z podłoża bez żadnych poziomych bloków.
         */
        public List<BlockPos> computeColumnFirstSupportChain(BlockPos startAnchor, BlockPos endTarget) {
            List<BlockPos> chain = new ArrayList<>();
            int x = startAnchor.getX();
            int y = startAnchor.getY();
            int z = startAnchor.getZ();

            int endX = endTarget.getX();
            int endY = endTarget.getY();
            int endZ = endTarget.getZ();

            // Jeśli kotwica jest pionowo pod celem (ta sama kolumna X, Z) – tylko pionowy słup!
            if (x == endX && z == endZ) {
                for (int curY = y + 1; curY <= endY; curY++) {
                    chain.add(new BlockPos(x, curY, z));
                }
                return chain;
            }

            int sx = Integer.compare(endX, x);
            int sz = Integer.compare(endZ, z);

            while (x != endX || z != endZ) {
                if (Math.abs(endX - x) >= Math.abs(endZ - z) && x != endX) {
                    x += sx;
                } else if (z != endZ) {
                    z += sz;
                }
                chain.add(new BlockPos(x, y, z));
            }
            if (y < endY) {
                for (int curY = y + 1; curY <= endY; curY++) {
                    chain.add(new BlockPos(endX, curY, endZ));
                }
            }
            return chain;
        }

        public List<BlockPos> computeBresenhamSupportChain(BlockPos startAnchor, BlockPos endTarget) {
            return computeColumnFirstSupportChain(startAnchor, endTarget);
        }

        public static boolean hasAnyClickableNeighbor(Level level, BlockPos pos) {
            if (level == null || pos == null) {
                return false;
            }
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = pos.relative(dir);
                BlockState state = level.getBlockState(neighbor);
                if (!state.isAir() && !state.canBeReplaced() && state.getFluidState().isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        public void equipBestToolForScaffold(LocalPlayer player, BlockState state) {
            if (player == null || state == null) {
                return;
            }
            Inventory inv = player.getInventory();
            int bestSlot = -1;
            float bestSpeed = 1.0F;
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty()) {
                    float speed = stack.getDestroySpeed(state);
                    if (speed > bestSpeed) {
                        bestSpeed = speed;
                        bestSlot = i;
                    }
                }
            }
            if (bestSlot >= 0 && bestSlot < 9) {
                inv.selected = bestSlot;
            } else if (bestSlot >= 9) {
                int targetHotbar = placementEngine.selectBestHotbarSwapTarget(inv);
                placementEngine.swapFromInventoryToHotbar(player, bestSlot, targetHotbar);
            }
        }

        private BlockState resolveAvailableScaffoldState(LocalPlayer player) {
            if (auxiliaryBlockManager != null) {
                return auxiliaryBlockManager.resolveBestAvailableAuxiliaryState(player);
            }
            return Blocks.DIRT.defaultBlockState();
        }
    }

    // =========================================================================
    // 9. SCHEMATIC BUILD QUEUE (SMART LoS + ANTI-SELF-TRAP SCHEDULER)
    // =========================================================================

    public static class SchematicBuildQueue {

        public record BuildTask(BlockPos targetPos, BlockState desiredState, boolean isScaffold) {
            public BlockPos worldPos() {
                return targetPos;
            }

            public BlockState targetState() {
                return desiredState;
            }
        }

        private final Minecraft mc;
        private final BlockStatePlacementEngine placementEngine;
        private final SchematicBuildNavigator navigator;
        private final ScaffoldingSolver scaffoldingSolver;

        private final Map<BlockPos, BlockState> pendingVoxels = new LinkedHashMap<>();
        private final Map<BlockPos, Integer> deferredVoxels = new HashMap<>();
        private final Map<BlockPos, Integer> failCountMap = new HashMap<>();

        private final Map<BlockPos, Integer> ringDepthMap = new HashMap<>();
        private final Set<BlockPos> cornerVoxels = new HashSet<>();
        private final Map<BlockPos, Integer> tourOrderMap = new HashMap<>();

        private BlockPos lastPlacedPos = null;
        private int activeSliceY = -1;

        public SchematicBuildQueue(
                BlockStatePlacementEngine placementEngine,
                SchematicBuildNavigator navigator,
                ScaffoldingSolver scaffoldingSolver) {
            this.mc = Minecraft.getInstance();
            this.placementEngine = placementEngine;
            this.navigator = navigator;
            this.scaffoldingSolver = scaffoldingSolver;
        }

        public void loadSchematicVoxels(Map<BlockPos, BlockState> voxels) {
            pendingVoxels.clear();
            deferredVoxels.clear();
            failCountMap.clear();
            ringDepthMap.clear();
            cornerVoxels.clear();
            tourOrderMap.clear();
            lastPlacedPos = null;

            if (voxels != null) {
                List<Map.Entry<BlockPos, BlockState>> sorted = new ArrayList<>(voxels.entrySet());
                sorted.removeIf(e -> e.getValue() == null || e.getValue().isAir());
                sorted.sort(Comparator
                        .comparingInt((Map.Entry<BlockPos, BlockState> e) -> DirectionalStateSolver.getGlobalPhaseOrder(e.getValue()))
                        .thenComparingInt(e -> e.getKey().getY())
                        .thenComparingInt(e -> DirectionalStateSolver.getLayerDependencyOrder(e.getValue())));

                for (Map.Entry<BlockPos, BlockState> e : sorted) {
                    pendingVoxels.put(e.getKey().immutable(), e.getValue());
                }
                recomputeLayerPerimeterTopology(pendingVoxels.keySet());
            }
        }

        private void recomputeLayerPerimeterTopology(Set<BlockPos> allVoxels) {
            Map<Integer, Set<BlockPos>> layers = new HashMap<>();
            for (BlockPos pos : allVoxels) {
                layers.computeIfAbsent(pos.getY(), k -> new HashSet<>()).add(pos);
            }

            for (Map.Entry<Integer, Set<BlockPos>> entry : layers.entrySet()) {
                Set<BlockPos> layerSet = entry.getValue();
                if (layerSet.isEmpty()) {
                    continue;
                }

                Queue<BlockPos> bfsQueue = new ArrayDeque<>();
                Map<Integer, List<BlockPos>> ringsInLayer = new HashMap<>();

                for (BlockPos pos : layerSet) {
                    int cardinalNeighbors = 0;
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        if (layerSet.contains(pos.relative(dir))) {
                            cardinalNeighbors++;
                        }
                    }

                    if (cardinalNeighbors < 4) {
                        ringDepthMap.put(pos, 0);
                        bfsQueue.add(pos);
                        ringsInLayer.computeIfAbsent(0, k -> new ArrayList<>()).add(pos);
                        if (cardinalNeighbors <= 2) {
                            cornerVoxels.add(pos);
                        }
                    }
                }

                while (!bfsQueue.isEmpty()) {
                    BlockPos current = bfsQueue.poll();
                    int currentDepth = ringDepthMap.getOrDefault(current, 0);
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        BlockPos neighbor = current.relative(dir);
                        if (layerSet.contains(neighbor) && !ringDepthMap.containsKey(neighbor)) {
                            int nextDepth = currentDepth + 1;
                            ringDepthMap.put(neighbor, nextDepth);
                            bfsQueue.add(neighbor);
                            ringsInLayer.computeIfAbsent(nextDepth, k -> new ArrayList<>()).add(neighbor);
                        }
                    }
                }

                for (Map.Entry<Integer, List<BlockPos>> ringEntry : ringsInLayer.entrySet()) {
                    List<BlockPos> ringNodes = ringEntry.getValue();
                    if (ringNodes.isEmpty()) {
                        continue;
                    }

                    Set<BlockPos> unvisited = new HashSet<>(ringNodes);
                    BlockPos current = ringNodes.stream()
                            .min(Comparator.comparingInt((BlockPos p) -> cornerVoxels.contains(p) ? 0 : 1)
                                    .thenComparingInt(BlockPos::getX)
                                    .thenComparingInt(BlockPos::getZ))
                            .orElse(ringNodes.get(0));

                    int orderIdx = 0;
                    while (!unvisited.isEmpty()) {
                        unvisited.remove(current);
                        tourOrderMap.put(current, orderIdx++);
                        if (unvisited.isEmpty()) {
                            break;
                        }

                        final BlockPos currRef = current;
                        current = unvisited.stream()
                                .min(Comparator.comparingDouble(p -> p.distSqr(currRef)))
                                .orElseGet(() -> unvisited.iterator().next());
                    }
                }
            }
        }

        public int getRingDepth(BlockPos pos) {
            return ringDepthMap.getOrDefault(pos, 0);
        }

        public boolean isCornerVoxel(BlockPos pos) {
            return cornerVoxels.contains(pos);
        }

        public void markCompleted(BlockPos pos) {
            pendingVoxels.remove(pos);
            deferredVoxels.remove(pos);
            failCountMap.remove(pos);
            if (pos != null) {
                this.lastPlacedPos = pos.immutable();
            }
        }

        public void recordLastPlaced(BlockPos pos) {
            if (pos != null) {
                this.lastPlacedPos = pos.immutable();
            }
        }

        public void recordFailedAttempt(BlockPos pos) {
            if (pos != null) {
                failCountMap.merge(pos.immutable(), 1, Integer::sum);
            }
        }

        public void deferVoxel(BlockPos pos, int ticks) {
            if (pos != null) {
                deferredVoxels.put(pos.immutable(), ticks);
            }
        }

        public void tickDeferredVoxels() {
            if (deferredVoxels.isEmpty()) {
                return;
            }
            deferredVoxels.entrySet().removeIf(e -> {
                int remaining = e.getValue() - 1;
                if (remaining <= 0) {
                    return true;
                }
                e.setValue(remaining);
                return false;
            });
        }

        public boolean isEmpty() {
            return pendingVoxels.isEmpty();
        }

        public void setActiveSliceY(int sliceY) {
            this.activeSliceY = sliceY;
        }

        public int getActiveSliceY() {
            return activeSliceY;
        }

        public ScaffoldingSolver getScaffoldingSolver() {
            return scaffoldingSolver;
        }

        public Map<BlockPos, BlockState> getPendingVoxels() {
            return pendingVoxels;
        }

        public Map<BlockPos, Integer> getDeferredVoxels() {
            return deferredVoxels;
        }

        public int getActiveSchematicPhase(LocalPlayer player, Level level) {
            boolean hasStructure = pendingVoxels.values().stream()
                    .anyMatch(s -> DirectionalStateSolver.getGlobalPhaseOrder(s) == 0);
            if (hasStructure) {
                return 0;
            }
            boolean hasFluids = pendingVoxels.values().stream()
                    .anyMatch(s -> DirectionalStateSolver.getGlobalPhaseOrder(s) == 1);
            if (hasFluids) {
                return 1;
            }
            return 2;
        }

        public boolean isDeferred(BlockPos pos) {
            return pos != null && deferredVoxels.containsKey(pos);
        }

        /**
         * POPRAWKA C.2: Sprawdza, czy dany woksel ze schematu może być w tej chwili zrealizowany przez gracza:
         * 1. Jeśli to Blocks.WATER, a gracz zużył wiadro z wodą (ma tylko puste Items.BUCKET), zwraca false!
         * 2. Jeśli to SugarCaneBlock, a w rynnie obok nie ma jeszcze wody (canSurvive == false), zwraca false!
         * 3. Jeśli w trybie Survival gracz nie posiada wymaganego przedmiotu w ekwipunku, zwraca false!
         */
        public boolean canCurrentlyFulfillVoxel(LocalPlayer player, Level level, BlockPos pos, BlockState desiredState) {
            if (player == null || level == null || pos == null || desiredState == null) {
                return false;
            }
            if (deferredVoxels.containsKey(pos)) {
                return false;
            }
            if (DirectionalStateSolver.isCropOrVegetation(desiredState)) {
                if (!desiredState.canSurvive(level, pos)) {
                    return false;
                }
            }
            if (!player.getAbilities().instabuild
                    && !placementEngine.hasRequiredItemInInventory(player, desiredState)) {
                return false;
            }
            return true;
        }

        public void pruneSatisfiedVoxels(Level level) {
            if (level == null || pendingVoxels.isEmpty()) {
                return;
            }
            LocalPlayer player = mc.player;
            pendingVoxels.entrySet().removeIf(entry -> {
                BlockPos pos = entry.getKey();
                BlockState desired = entry.getValue();
                BlockState worldState = level.getBlockState(pos);

                // Automatycznie usuwa z kolejki bloki wody, gdy woda w rynnie już płynie lub stoi!
                if (AstraSchematicManager.isVoxelAlreadySatisfied(worldState, desired)) {
                    lastPlacedPos = pos;
                    failCountMap.remove(pos);
                    deferredVoxels.remove(pos);
                    return true;
                }

                // Jeśli schemat wymaga wody, w rynnie jest sucho, a gracz ma tylko puste wiadro (brak WATER_BUCKET),
                // odłóż ten woksel wody (defer), aby NIE blokował przejścia do warstwy Tłoków (Y+1) i Obserwatorów (Y+2)!
                if (player != null && !player.getAbilities().instabuild
                        && !placementEngine.hasRequiredItemInInventory(player, desired)) {
                    deferredVoxels.putIfAbsent(pos, 80);
                }

                return false;
            });
        }

        private Comparator<Map.Entry<BlockPos, BlockState>> createPerimeterFirstComparator(
                BlockPos playerFeet,
                int minLayerY) {
            return Comparator
                    // 0. BEZWZGLĘDNY PRIORYTET: Globalna faza budowy (0: Konstrukcja/Mechanizmy -> 1: Woda -> 2: Sadzenie upraw)!
                    .comparingInt((Map.Entry<BlockPos, BlockState> e) -> DirectionalStateSolver.getGlobalPhaseOrder(e.getValue()))
                    // 1. BEZWZGLĘDNY PRIORYTET WARSTWY: Budowa ściśle warstwami od najniższej do najwyższej Y (Bottom-to-Top)!
                    .thenComparingInt((Map.Entry<BlockPos, BlockState> e) -> e.getKey().getY())
                    // 2. Kolejność zależności w warstwie: podłoże/ściany (0) -> mechanizmy (1) -> redstone (2) -> płyny (15) -> uprawy (20)
                    .thenComparingInt(e -> DirectionalStateSolver.getLayerDependencyOrder(e.getValue()))
                    // 3. Nieodłożone bloki najpierw
                    .thenComparingInt(e -> deferredVoxels.containsKey(e.getKey()) ? 1 : 0)
                    // 4. Bloki z mniejszą liczbą nieudanych prób najpierw
                    .thenComparingInt(e -> failCountMap.getOrDefault(e.getKey(), 0))
                    // 5. Bloki posiadające już oparcie w świecie
                    .thenComparingInt(e -> placementEngine.hasValidSupportForState(e.getKey(), e.getValue()) ? 0 : 1)
                    // 6. Obrys (Ring 0) przed środkiem (Ring 1..N)
                    .thenComparingInt(e -> getRingDepth(e.getKey()))
                    // 7. Ciągłość ścieżki + kara za odcinanie sobie drogi
                    .thenComparingDouble(e -> computeChainAndCornerScore(e.getKey(), playerFeet));
        }

        private double computeChainAndCornerScore(BlockPos pos, BlockPos playerFeet) {
            double distSq = pos.distSqr(playerFeet);
            double distToPlayer = Math.sqrt(distSq);
            double score = distToPlayer * 1.1D;

            // Histereza klastra: potężny bonus dla bloków w bieżącym sektorze gracza (kończ rozpoczętą stronę schematu)
            if (distSq <= 25.0D) {
                score -= 25.0D;
            }

            // Progresywna kara za nieudane próby postawienia (nie wracaj natychmiast do problematycznego bloku)
            int failCount = failCountMap.getOrDefault(pos, 0);
            if (failCount > 0) {
                score += failCount * 45.0D;
            }

            if (pos.getY() == playerFeet.getY() + 1 && pos.distSqr(playerFeet.above()) <= 1.0D) {
                score += 12.0D;
            }

            if (lastPlacedPos != null && lastPlacedPos.getY() == pos.getY()) {
                double distToLastSq = pos.distSqr(lastPlacedPos);
                if (distToLastSq == 1.0D) {
                    score -= 60.0D;
                } else if (distToLastSq == 2.0D) {
                    score -= 35.0D;
                } else if (distToLastSq <= 4.0D) {
                    score -= 18.0D;
                }
            } else {
                if (isCornerVoxel(pos)) {
                    score -= 30.0D;
                }
            }

            score += tourOrderMap.getOrDefault(pos, 0) * 0.05D;
            return score;
        }

        public Optional<BuildTask> findImmediatePlaceableTask() {
            LocalPlayer player = mc.player;
            Level level = mc.level;
            if (player == null || level == null || pendingVoxels.isEmpty()) {
                return Optional.empty();
            }

            BlockPos playerFeet = player.blockPosition();

            // Ustal aktualną globalną fazę schematu:
            // Faza 0: Jeśli pozostał jakikolwiek blok konstrukcyjny / mechanizm w schemacie, NIE stawiaj wody ani roślin!
            int currentPhase = 2;
            boolean hasStructure = pendingVoxels.values().stream()
                    .anyMatch(s -> DirectionalStateSolver.getGlobalPhaseOrder(s) == 0);
            if (hasStructure) {
                currentPhase = 0;
            } else {
                boolean hasFluids = pendingVoxels.values().stream()
                        .anyMatch(s -> DirectionalStateSolver.getGlobalPhaseOrder(s) == 1);
                if (hasFluids) {
                    currentPhase = 1;
                }
            }

            final int activeSchemPhase = currentPhase;

            OptionalInt minLayerOpt = pendingVoxels.entrySet().stream()
                    .filter(e -> DirectionalStateSolver.getGlobalPhaseOrder(e.getValue()) == activeSchemPhase
                            && canCurrentlyFulfillVoxel(player, level, e.getKey(), e.getValue()))
                    .mapToInt(e -> e.getKey().getY())
                    .min();

            if (minLayerOpt.isEmpty()) {
                return Optional.empty();
            }
            int minLayerY = minLayerOpt.getAsInt();

            int minDepOrderInLayer = pendingVoxels.entrySet().stream()
                    .filter(e -> e.getKey().getY() == minLayerY
                            && DirectionalStateSolver.getGlobalPhaseOrder(e.getValue()) == activeSchemPhase
                            && canCurrentlyFulfillVoxel(player, level, e.getKey(), e.getValue()))
                    .mapToInt(e -> DirectionalStateSolver.getLayerDependencyOrder(e.getValue()))
                    .min()
                    .orElse(0);

            List<Map.Entry<BlockPos, BlockState>> candidates = new ArrayList<>();
            for (Map.Entry<BlockPos, BlockState> entry : pendingVoxels.entrySet()) {
                BlockPos pos = entry.getKey();
                BlockState state = entry.getValue();
                if (pos.getY() == minLayerY
                        && DirectionalStateSolver.getGlobalPhaseOrder(state) == activeSchemPhase
                        && canCurrentlyFulfillVoxel(player, level, pos, state)
                        && DirectionalStateSolver.getLayerDependencyOrder(state) == minDepOrderInLayer
                        && pos.distSqr(playerFeet) <= 20.25D) {
                    if (navigator.isInPositionForTask(pos, state, false)) {
                        candidates.add(entry);
                    }
                }
            }

            if (!candidates.isEmpty()) {
                candidates.sort(createPerimeterFirstComparator(playerFeet, minLayerY));
                Map.Entry<BlockPos, BlockState> best = candidates.get(0);
                return Optional.of(new BuildTask(best.getKey(), best.getValue(), false));
            }

            // Fallback w bieżącym miejscu stania gracza (ale TYLKO dla bloków z bieżącej fazy! Nigdy nie stawiaj upraw w fazie konstrukcji)
            List<Map.Entry<BlockPos, BlockState>> localImmediate = new ArrayList<>();
            for (Map.Entry<BlockPos, BlockState> entry : pendingVoxels.entrySet()) {
                BlockPos pos = entry.getKey();
                BlockState state = entry.getValue();
                if (DirectionalStateSolver.getGlobalPhaseOrder(state) != activeSchemPhase) {
                    continue;
                }
                if (canCurrentlyFulfillVoxel(player, level, pos, state)
                        && placementEngine.hasValidSupportForState(pos, state)
                        && pos.distSqr(playerFeet) <= 20.25D) {
                    if (navigator.isInPositionForTask(pos, state, false)) {
                        localImmediate.add(entry);
                    }
                }
            }

            if (!localImmediate.isEmpty()) {
                localImmediate.sort(Comparator
                        .comparingInt((Map.Entry<BlockPos, BlockState> e) -> DirectionalStateSolver.getLayerDependencyOrder(e.getValue()))
                        .thenComparingDouble(e -> e.getKey().distSqr(playerFeet)));
                Map.Entry<BlockPos, BlockState> bestLocal = localImmediate.get(0);
                return Optional.of(new BuildTask(bestLocal.getKey(), bestLocal.getValue(), false));
            }

            return Optional.empty();
        }

        public Optional<BuildTask> pollNextTask() {
            LocalPlayer player = mc.player;
            Level level = mc.level;
            if (player == null || level == null || pendingVoxels.isEmpty()) {
                return Optional.empty();
            }

            pruneSatisfiedVoxels(level);
            if (pendingVoxels.isEmpty()) {
                return Optional.empty();
            }

            BlockPos playerFeet = player.blockPosition();

            // POPRAWKA C.2: Filtruj wyłącznie zadania wykonalne (dla których gracz ma przedmiot w ekwipunku)
            List<Map.Entry<BlockPos, BlockState>> fulfillableEntries = new ArrayList<>();
            for (Map.Entry<BlockPos, BlockState> entry : pendingVoxels.entrySet()) {
                if (canCurrentlyFulfillVoxel(player, level, entry.getKey(), entry.getValue())) {
                    fulfillableEntries.add(entry);
                } else {
                    deferredVoxels.putIfAbsent(entry.getKey(), 60);
                }
            }

            if (fulfillableEntries.isEmpty()) {
                if (!pendingVoxels.isEmpty()) {
                    deferredVoxels.clear();
                    for (Map.Entry<BlockPos, BlockState> entry : pendingVoxels.entrySet()) {
                        if (canCurrentlyFulfillVoxel(player, level, entry.getKey(), entry.getValue())) {
                            fulfillableEntries.add(entry);
                        }
                    }
                }
                if (fulfillableEntries.isEmpty()) {
                    return Optional.empty();
                }
            }

            // Żelazna reguła: dopóki w całym schemacie pozostał jakikolwiek blok strukturalny lub mechanizm (Faza 0),
            // woda (Faza 1) i uprawy/trzcina (Faza 2) NIE mogą być stawiane!
            // Dopiero po skończeniu całej konstrukcji, wlewana jest woda do rynien (Faza 1).
            // A na samym końcu, po nalaniu wody, sadzona jest trzcina i uprawy (Faza 2)!
            int activeSchemPhase = getActiveSchematicPhase(player, level);
            fulfillableEntries.removeIf(e -> DirectionalStateSolver.getGlobalPhaseOrder(e.getValue()) != activeSchemPhase);

            int minLayerY = fulfillableEntries.stream()
                    .mapToInt(e -> e.getKey().getY())
                    .min()
                    .orElse(playerFeet.getY());

            // 3-etapowy filtr kolejności budowy W OBRĘBIE NAJNIŻSZEJ WARSTWY minLayerY:
            // Etap 1: Struktury i mechanizmy w warstwie (podłoże, koryta rynien, ściany)
            // Etap 2: Woda i płyny w warstwie (nalewane do rynny po wybudowaniu ścian i koryta)
            // Etap 3: Rośliny i uprawy w warstwie (sadzone po nalaniu wody, gdy canSurvive() jest spełnione!)
            boolean layerHasStructural = fulfillableEntries.stream()
                    .anyMatch(e -> e.getKey().getY() == minLayerY
                            && !DirectionalStateSolver.isFluid(e.getValue())
                            && !DirectionalStateSolver.isCropOrVegetation(e.getValue()));

            boolean layerHasFluids = !layerHasStructural && fulfillableEntries.stream()
                    .anyMatch(e -> e.getKey().getY() == minLayerY
                            && DirectionalStateSolver.isFluid(e.getValue()));

            java.util.function.Predicate<BlockState> stagePredicate;
            if (layerHasStructural) {
                stagePredicate = s -> !DirectionalStateSolver.isFluid(s) && !DirectionalStateSolver.isCropOrVegetation(s);
            } else if (layerHasFluids) {
                stagePredicate = DirectionalStateSolver::isFluid;
            } else {
                stagePredicate = s -> true;
            }

            int minDepOrderInLayer = fulfillableEntries.stream()
                    .filter(e -> e.getKey().getY() == minLayerY
                            && stagePredicate.test(e.getValue()))
                    .mapToInt(e -> DirectionalStateSolver.getLayerDependencyOrder(e.getValue()))
                    .min()
                    .orElse(0);

            fulfillableEntries.sort(createPerimeterFirstComparator(playerFeet, minLayerY));

            // Krok 1: Blok w aktywnym etapie i warstwie, gotowy do postawienia od razu
            for (Map.Entry<BlockPos, BlockState> entry : fulfillableEntries) {
                BlockPos pos = entry.getKey();
                BlockState state = entry.getValue();
                if (pos.getY() == minLayerY
                        && stagePredicate.test(state)
                        && DirectionalStateSolver.getLayerDependencyOrder(state) == minDepOrderInLayer
                        && !deferredVoxels.containsKey(pos)
                        && navigator.isInPositionForTask(pos, state, false)) {
                    return Optional.of(new BuildTask(pos, state, false));
                }
            }

            // Krok 2: Blok w aktywnym etapie i warstwie z osiągalną pozycją stojącą
            int checkedReachable = 0;
            int maxChecks = Math.min(fulfillableEntries.size(), 25);
            for (Map.Entry<BlockPos, BlockState> entry : fulfillableEntries) {
                BlockPos pos = entry.getKey();
                BlockState state = entry.getValue();
                if (pos.getY() == minLayerY
                        && stagePredicate.test(state)
                        && DirectionalStateSolver.getLayerDependencyOrder(state) == minDepOrderInLayer
                        && placementEngine.hasValidSupportForState(pos, state)
                        && !deferredVoxels.containsKey(pos)) {
                    if (navigator.hasReachableStandingPosition(pos, state)) {
                        return Optional.of(new BuildTask(pos, state, false));
                    }
                    if (++checkedReachable >= maxChecks) {
                        break; // Zapobiega mikroprzycięciom ticka przy dziesiątkach kandydatów
                    }
                }
            }

            // Krok 3: Blok w aktywnym etapie i warstwie posiadający oparcie (w razie potrzeby dobuduje stanowisko stania)
            for (Map.Entry<BlockPos, BlockState> entry : fulfillableEntries) {
                BlockPos pos = entry.getKey();
                BlockState state = entry.getValue();
                if (pos.getY() == minLayerY
                        && stagePredicate.test(state)
                        && DirectionalStateSolver.getLayerDependencyOrder(state) == minDepOrderInLayer
                        && placementEngine.hasValidSupportForState(pos, state)
                        && !deferredVoxels.containsKey(pos)) {
                    return Optional.of(new BuildTask(pos, state, false));
                }
            }

            // Krok 4: Dowolny wykonalny blok w aktywnym etapie i warstwie posiadający oparcie (nieodłożony!)
            for (Map.Entry<BlockPos, BlockState> entry : fulfillableEntries) {
                BlockPos pos = entry.getKey();
                BlockState state = entry.getValue();
                if (pos.getY() == minLayerY && stagePredicate.test(state) && placementEngine.hasValidSupportForState(pos, state)
                        && !deferredVoxels.containsKey(pos)) {
                    return Optional.of(new BuildTask(pos, state, false));
                }
            }

            // Krok 5: Rusztowanie TYLKO w bieżącej warstwie minLayerY dla bloków niemających oparcia w schemacie pod sobą!
            for (Map.Entry<BlockPos, BlockState> entry : fulfillableEntries) {
                BlockPos pos = entry.getKey();
                BlockState state = entry.getValue();
                if (pos.getY() != minLayerY || !stagePredicate.test(state) || deferredVoxels.containsKey(pos)) {
                    continue;
                }
                BlockPos below = pos.below();
                if (pendingVoxels.containsKey(below)) {
                    continue; // Blok pod spodem czeka w kolejce — czekaj na jego wybudowanie!
                }
                BlockPos supportTarget = DirectionalStateSolver.requiresBottomSupportOnly(entry.getValue())
                        ? below
                        : pos;
                Optional<BuildTask> scaffoldTask = scaffoldingSolver.findNextScaffoldTask(supportTarget);
                if (scaffoldTask.isPresent()) {
                    return scaffoldTask;
                }
            }

            // Krok 6: Ostateczny wybór w obrębie minLayerY (dla bloków nieodłożonych)
            for (Map.Entry<BlockPos, BlockState> entry : fulfillableEntries) {
                if (entry.getKey().getY() == minLayerY && !deferredVoxels.containsKey(entry.getKey())) {
                    return Optional.of(new BuildTask(entry.getKey(), entry.getValue(), false));
                }
            }

            // Krok 7: Jeśli wszystkie bloki w minLayerY są odłożone (deferred), wybierz pierwszy dostępny nieodłożony blok w wyższych warstwach!
            for (Map.Entry<BlockPos, BlockState> entry : fulfillableEntries) {
                if (!deferredVoxels.containsKey(entry.getKey())) {
                    return Optional.of(new BuildTask(entry.getKey(), entry.getValue(), false));
                }
            }

            Map.Entry<BlockPos, BlockState> fallback = fulfillableEntries.get(0);
            return Optional.of(new BuildTask(fallback.getKey(), fallback.getValue(), false));
        }

    }
}