package baritone.bypass.nav;

import baritone.api.IBaritone;
import baritone.api.utils.input.Input;
import baritone.bypass.grim.GrimSyncController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Continuous Mining Path Executor z Head Clearance Guard (+1Y / -1Y),
 * 3-węzłowym Pre-Break Pipeline oraz Server Break Confirmation (anty-ghost-block dla GrimAC).
 *
 * 100% Zero-GC w pętli tickowej (240+ FPS), zero wywołań clearAllKeys().
 */
public final class ContinuousBoreExecutor {

    private static final double SPRINT_BPT = 0.2806D;
    private static final double COLLISION_BUFFER = 0.42D;
    private static final double SAFE_REACH_SQ = 3.95D * 3.95D;
    private static final double PLAYER_EYE_HEIGHT = 1.62D;
    private static final int LOOKAHEAD_NODES = 3;
    private static final int CONFIRM_RING_SIZE = 8; // potęga 2
    private static final int MAX_CONFIRM_WAIT_TICKS = 4;

    private final IBaritone baritone;
    private final GrimSyncController grimSync;

    // Aktywna trasa w postaci płaskiej tablicy (z A* lub DirectBoreCorridorPlanner)
    private long[] pathNodes = new long[512];
    private int pathLength = 0;
    private int currentNodeIndex = 0;

    // Prealokowana kolejka bloków do rozbicia w bieżącym ticku (Zero-GC)
    private final long[] breakQueue = new long[16];
    private int breakQueueSize = 0;

    // Pierścień potwierdzeń zniszczenia bloków z serwera (anty-ghost-block)
    private final long[] pendingConfirmPos = new long[CONFIRM_RING_SIZE];
    private final int[] pendingConfirmExpireTick = new int[CONFIRM_RING_SIZE];
    private int tickCounter = 0;

    // Stan ciągłego kopania (brak resetu między blokami)
    private final BlockPos.MutableBlockPos activeBreakPos = new BlockPos.MutableBlockPos();
    private BlockPos activeBreakImmutable = BlockPos.ZERO;
    private boolean isBreaking = false;
    private float breakProgress = 0.0F;
    private Direction lastBreakFace = Direction.NORTH;

    // Reużywalne obiekty robocze Zero-GC
    private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos ddaPos = new BlockPos.MutableBlockPos();

    // Pola wynikowe Zero-GC raytracera
    private Direction rayHitFace = Direction.UP;
    private double rayHitX, rayHitY, rayHitZ;

    public ContinuousBoreExecutor(IBaritone baritone, GrimSyncController grimSync) {
        this.baritone = baritone;
        this.grimSync = grimSync;
        for (int i = 0; i < CONFIRM_RING_SIZE; i++) {
            pendingConfirmPos[i] = Long.MIN_VALUE;
        }
    }

    /**
     * Ładuje nową ścieżkę (np. z DirectBoreCorridorPlanner lub przekonwertowanego IPath).
     * Zachowuje bieżący postęp kopania, jeśli nowy cel pokrywa się z aktualnie kopanym blokiem!
     */
    public void loadPath(long[] nodes, int length) {
        if (length > pathNodes.length) {
            pathNodes = new long[length];
        }
        System.arraycopy(nodes, 0, pathNodes, 0, length);
        this.pathLength = length;
        this.currentNodeIndex = 0;
    }

    /**
     * Wywoływane z Mixina w ClientPacketListener przy odbiorze ClientboundBlockUpdatePacket /
     * ClientboundSectionBlocksUpdatePacket, gdy blok zmieni się na powietrze.
     */
    public void onServerBlockConfirmedAir(long packedPos) {
        for (int i = 0; i < CONFIRM_RING_SIZE; i++) {
            if (pendingConfirmPos[i] == packedPos) {
                pendingConfirmPos[i] = Long.MIN_VALUE;
            }
        }
    }

    /**
     * Główny tick egzekutora. Zwraca true, gdy ścieżka została w pełni pokonana.
     */
    public boolean tick(LocalPlayer player, Level level) {
        tickCounter++;
        if (pathLength == 0 || currentNodeIndex >= pathLength - 1) {
            return true;
        }

        MultiPlayerGameMode gameMode = Minecraft.getInstance().gameMode;
        if (gameMode == null) return false;

        // 1. Płynne przesuwanie indeksu węzła (z tolerancją promienia 0.45 bloku, aby nie hamować na środku)
        advancePathCursor(player);
        if (currentNodeIndex >= pathLength - 1) {
            return true;
        }

        long curNode = pathNodes[currentNodeIndex];
        long nextNode = pathNodes[currentNodeIndex + 1];

        int cx = BlockPos.getX(curNode), cy = BlockPos.getY(curNode), cz = BlockPos.getZ(curNode);
        int nx = BlockPos.getX(nextNode), ny = BlockPos.getY(nextNode), nz = BlockPos.getZ(nextNode);
        int stepDy = ny - cy;

        // 2. Zbuduj priorytetową kolejkę kopania na 3 węzły do przodu (z Head Clearance Guard!)
        buildLookaheadBreakQueue(level);

        // 3. Wybierz pierwszy legalny blok z kolejki (w GrimAC Reach + LOS) i kop go w locie
        long chosenBreakPacked = Long.MIN_VALUE;
        for (int i = 0; i < breakQueueSize; i++) {
            long cand = breakQueue[i];
            if (rayTraceBlockZeroGc(player, level, cand)) {
                chosenBreakPacked = cand;
                break;
            }
        }

        int remainingBreakTicks = 0;
        if (chosenBreakPacked != Long.MIN_VALUE) {
            remainingBreakTicks = tickBreakingBlock(player, level, gameMode, chosenBreakPacked);
        } else {
            if (isBreaking) {
                grimSync.sendAbortDestroy(player, activeBreakImmutable, lastBreakFace);
                isBreaking = false;
                breakProgress = 0.0F;
            }
            // Brak bloków w kolejce: celuj wzdłuż osi kolejnego węzła
            aimAtZeroGc(player, nx + 0.5D, ny + 1.25D, nz + 0.5D);
        }

        // 4. Kontrola Head Clearance, potwierdzeń serwera oraz detekcja zakrętu 90°
        boolean immediatePathClear = isNextStepPhysicallyClear(level, cx, cy, cz, nx, ny, nz, stepDy);
        boolean headClearForAscend = (stepDy <= 0) || isBlockConfirmedClear(level, cx, cy + 2, cz);
        boolean nextTurnIsSharp = isUpcomingSharpTurn();

        double distToNextHoriz = Math.hypot((nx + 0.5D) - player.getX(), (nz + 0.5D) - player.getZ());
        double distToWall = Math.max(0.0D, distToNextHoriz - 0.5D);

        // Obliczenie dozwolonej prędkości bez ryzyka zderzenia / cofki GrimAC
        boolean allowForward;
        boolean allowSprint;
        boolean allowJump = false;

        if (!immediatePathClear) {
            // Ściana przed nami jeszcze stoi lub czeka na potwierdzenie serwera:
            // Możemy podbiegać dopóki mamy bufor hamowania > czas kopania * prędkość
            double requiredClearance = (remainingBreakTicks * SPRINT_BPT) + COLLISION_BUFFER;
            allowForward = distToWall > COLLISION_BUFFER;
            allowSprint = allowForward && (distToWall > requiredClearance) && !nextTurnIsSharp && stepDy == 0;
        } else {
            // Korytarz przed nami jest czysty i potwierdzony
            allowForward = true;
            // Wyłączamy sprint 1 tick przed zakrętem 90° lub podczas schodka +1Y, żeby nie uderzyć w ścianę
            allowSprint = !nextTurnIsSharp && stepDy <= 0;

            if (stepDy > 0) {
                // Skok na schodek +1Y wyłącznie gdy:
                // 1) sufit nad głową (cx, cy+2, cz) jest fizycznie i serwerowo pusty (Head Clearance Guard),
                // 2) gracz stoi na ziemi i jest w odległości 0.55..1.15 bloku od progu schodka.
                allowJump = headClearForAscend
                        && player.onGround()
                        && distToNextHoriz <= 1.18D
                        && distToNextHoriz >= 0.42D;
            }
        }

        // 5. Korygowanie odchylenia bocznego (micro-strafe) bez szarpania kamery
        double targetCenterX = nx + 0.5D;
        double targetCenterZ = nz + 0.5D;
        float moveYawRad = (float) Math.toRadians(player.getYRot());
        double forwardX = -Math.sin(moveYawRad);
        double forwardZ = Math.cos(moveYawRad);
        double toTargetX = targetCenterX - player.getX();
        double toTargetZ = targetCenterZ - player.getZ();

        // Rzut na oś prawo/lewo względem aktualnego yaw kamery (ważne, bo kamera patrzy na kopany blok!)
        double rightX = -forwardZ;
        double rightZ = forwardX;
        double forwardDot = toTargetX * forwardX + toTargetZ * forwardZ;
        double rightDot = toTargetX * rightX + toTargetZ * rightZ;

        boolean pressForward = allowForward && forwardDot > 0.12D;
        boolean pressBack = allowForward && forwardDot < -0.25D;
        boolean pressRight = allowForward && rightDot > 0.16D;
        boolean pressLeft = allowForward && rightDot < -0.16D;

        // GrimAC Rule: sprint legalny WYŁĄCZNIE gdy pressForward == true i brak pressBack
        boolean finalSprint = allowSprint && pressForward && !pressBack;

        // 6. Aplikacja wejść BEZ clearAllKeys() + atomowa synchronizacja z GrimSyncController
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, pressForward);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, pressBack);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, pressLeft);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, pressRight);
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, allowJump);
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, finalSprint);

        grimSync.syncSprintState(player, finalSprint, pressForward);
        return false;
    }

    /**
     * Buduje deterministyczną kolejkę bloków do rozbicia w oknie 3 węzłów.
     * Gwarantuje odkopanie sufitu (Head Clearance) przed skokiem +1Y oraz progu przed zejściem -1Y.
     */
    private void buildLookaheadBreakQueue(Level level) {
        breakQueueSize = 0;
        int limit = Math.min(currentNodeIndex + LOOKAHEAD_NODES, pathLength - 1);

        for (int idx = currentNodeIndex; idx < limit; idx++) {
            long a = pathNodes[idx];
            long b = pathNodes[idx + 1];
            int ax = BlockPos.getX(a), ay = BlockPos.getY(a), az = BlockPos.getZ(a);
            int bx = BlockPos.getX(b), by = BlockPos.getY(b), bz = BlockPos.getZ(b);
            int dy = by - ay;

            if (dy > 0) {
                // SCHODEK W GÓRĘ (+1Y):
                // 1) Najpierw sufit nad obecnym węzłem (zapobiega uderzeniu głową przy skoku!)
                enqueueIfSolid(level, ax, ay + 2, az);
                // 2) Głowa na wyższym stopniu
                enqueueIfSolid(level, bx, by + 1, bz);
                // 3) Stopy na wyższym stopniu
                enqueueIfSolid(level, bx, by, bz);
            } else if (dy < 0) {
                // ZEJŚCIE W DÓŁ (-1Y):
                // 1) Górny próg na wysokości głowy obecnego piętra (zapobiega klinowaniu czoła!)
                enqueueIfSolid(level, bx, ay + 1, bz);
                // 2) Środek (głowa na niższym piętrze)
                enqueueIfSolid(level, bx, ay, bz);
                // 3) Stopy na niższym piętrze
                enqueueIfSolid(level, bx, by, bz);
            } else {
                // PŁASKI KORYTARZ 1x2:
                // 1) Głowa (naturalny poziom oczu)
                enqueueIfSolid(level, bx, by + 1, bz);
                // 2) Stopy
                enqueueIfSolid(level, bx, by, bz);
            }
        }
    }

    private void enqueueIfSolid(Level level, int x, int y, int z) {
        if (breakQueueSize >= breakQueue.length) return;
        scratchPos.set(x, y, z);
        BlockState state = level.getBlockState(scratchPos);
        if (state.isAir() || state.getCollisionShape(level, scratchPos).isEmpty()) {
            return;
        }
        long packed = BlockPos.asLong(x, y, z);
        // Deduplikacja w kolejce (mała tablica <= 16 — szybsze niż HashSet)
        for (int i = 0; i < breakQueueSize; i++) {
            if (breakQueue[i] == packed) return;
        }
        breakQueue[breakQueueSize++] = packed;
    }

    private int tickBreakingBlock(
            LocalPlayer player,
            Level level,
            MultiPlayerGameMode gameMode,
            long packedTarget
    ) {
        int tx = BlockPos.getX(packedTarget);
        int ty = BlockPos.getY(packedTarget);
        int tz = BlockPos.getZ(packedTarget);

        if (!isBreaking
                || activeBreakPos.getX() != tx
                || activeBreakPos.getY() != ty
                || activeBreakPos.getZ() != tz) {
            if (isBreaking) {
                grimSync.sendAbortDestroy(player, activeBreakImmutable, lastBreakFace);
            }
            activeBreakPos.set(tx, ty, tz);
            activeBreakImmutable = activeBreakPos.immutable(); // Jedyna alokacja raz na zmianę bloku
            breakProgress = 0.0F;
            isBreaking = false;
        }

        // Kwantyzacja GrimAC GCD w dokładnie wyznaczony punkt ściany z Zero-GC raytracera
        aimAtZeroGc(player, rayHitX, rayHitY, rayHitZ);

        BlockState state = level.getBlockState(activeBreakPos);
        float dmgPerTick = state.getDestroyProgress(player, level, activeBreakPos);
        boolean isInstamine = dmgPerTick >= 1.0F;

        if (!isBreaking) {
            if (grimSync.canStartNewBreak()) {
                gameMode.startDestroyBlock(activeBreakImmutable, rayHitFace);
                player.swing(InteractionHand.MAIN_HAND);
                lastBreakFace = rayHitFace;
                breakProgress = dmgPerTick;

                if (isInstamine) {
                    grimSync.onBlockBroken(true);
                    registerPendingServerConfirm(packedTarget);
                    isBreaking = false;
                    breakProgress = 0.0F;
                    return 0;
                } else {
                    isBreaking = true;
                }
            }
        } else {
            gameMode.continueDestroyBlock(activeBreakImmutable, rayHitFace);
            player.swing(InteractionHand.MAIN_HAND);
            lastBreakFace = rayHitFace;
            breakProgress += dmgPerTick;

            if (breakProgress >= 1.0F || level.getBlockState(activeBreakPos).isAir()) {
                grimSync.onBlockBroken(false);
                registerPendingServerConfirm(packedTarget);
                isBreaking = false;
                breakProgress = 0.0F;
                return 0;
            }
        }

        return (dmgPerTick > 0.0F) ? Math.max(0, Mth.ceil((1.0F - breakProgress) / dmgPerTick)) : 20;
    }

    /**
     * Bezalokacyjny 3D DDA RayTracer dla GrimAC (zastępuje Level.clip w pętli tickowej).
     * Zapisuje trafioną ścianę i punkt uderzenia bezpośrednio w polach prymitywnych klasy.
     */
    private boolean rayTraceBlockZeroGc(LocalPlayer player, Level level, long targetPacked) {
        double ex = player.getX();
        double ey = player.getY() + PLAYER_EYE_HEIGHT;
        double ez = player.getZ();

        int bx = BlockPos.getX(targetPacked);
        int by = BlockPos.getY(targetPacked);
        int bz = BlockPos.getZ(targetPacked);

        double tx = Mth.clamp(ex, bx + 0.08D, bx + 0.92D);
        double ty = Mth.clamp(ey, by + 0.08D, by + 0.92D);
        double tz = Mth.clamp(ez, bz + 0.08D, bz + 0.92D);

        double dx = tx - ex;
        double dy = ty - ey;
        double dz = tz - ez;
        if (dx * dx + dy * dy + dz * dz > SAFE_REACH_SQ) {
            return false;
        }

        int vx = Mth.floor(ex);
        int vy = Mth.floor(ey);
        int vz = Mth.floor(ez);

        int stepX = dx > 0.0D ? 1 : (dx < 0.0D ? -1 : 0);
        int stepY = dy > 0.0D ? 1 : (dy < 0.0D ? -1 : 0);
        int stepZ = dz > 0.0D ? 1 : (dz < 0.0D ? -1 : 0);

        double tDeltaX = (stepX != 0) ? Math.abs(1.0D / dirXOr1(dx)) : Double.MAX_VALUE;
        double tDeltaY = (stepY != 0) ? Math.abs(1.0D / dirXOr1(dy)) : Double.MAX_VALUE;
        double tDeltaZ = (stepZ != 0) ? Math.abs(1.0D / dirXOr1(dz)) : Double.MAX_VALUE;

        double tMaxX = (stepX != 0) ? (((stepX > 0) ? (vx + 1.0D - ex) : (ex - vx)) * tDeltaX) : Double.MAX_VALUE;
        double tMaxY = (stepY != 0) ? (((stepY > 0) ? (vy + 1.0D - ey) : (ey - vy)) * tDeltaY) : Double.MAX_VALUE;
        double tMaxZ = (stepZ != 0) ? (((stepZ > 0) ? (vz + 1.0D - ez) : (ez - vz)) * tDeltaZ) : Double.MAX_VALUE;

        Direction entryFace = Direction.UP;
        double t = 0.0D;

        for (int iter = 0; iter < 16; iter++) {
            if (vx == bx && vy == by && vz == bz) {
                rayHitFace = entryFace;
                rayHitX = ex + dx * t;
                rayHitY = ey + dy * t;
                rayHitZ = ez + dz * t;
                return true;
            }

            if (iter > 0) {
                ddaPos.set(vx, vy, vz);
                BlockState st = level.getBlockState(ddaPos);
                if (!st.isAir() && !st.getCollisionShape(level, ddaPos).isEmpty()) {
                    return false; // Promień zablokowany przez bliższy lity blok
                }
            }

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    if (tMaxX > 1.0D) break;
                    vx += stepX;
                    t = tMaxX;
                    tMaxX += tDeltaX;
                    entryFace = (stepX > 0) ? Direction.WEST : Direction.EAST;
                } else {
                    if (tMaxZ > 1.0D) break;
                    vz += stepZ;
                    t = tMaxZ;
                    tMaxZ += tDeltaZ;
                    entryFace = (stepZ > 0) ? Direction.NORTH : Direction.SOUTH;
                }
            } else {
                if (tMaxY < tMaxZ) {
                    if (tMaxY > 1.0D) break;
                    vy += stepY;
                    t = tMaxY;
                    tMaxY += tDeltaY;
                    entryFace = (stepY > 0) ? Direction.DOWN : Direction.UP;
                } else {
                    if (tMaxZ > 1.0D) break;
                    vz += stepZ;
                    t = tMaxZ;
                    tMaxZ += tDeltaZ;
                    entryFace = (stepZ > 0) ? Direction.NORTH : Direction.SOUTH;
                }
            }
        }
        return false;
    }

    private static double dirXOr1(double d) {
        return Math.abs(d) < 1.0E-6D ? 1.0E-6D : d;
    }

    private boolean isNextStepPhysicallyClear(
            Level level, int cx, int cy, int cz, int nx, int ny, int nz, int stepDy
    ) {
        if (!isBlockConfirmedClear(level, nx, ny, nz)) return false;
        if (!isBlockConfirmedClear(level, nx, ny + 1, nz)) return false;
        if (stepDy > 0 && !isBlockConfirmedClear(level, cx, cy + 2, cz)) return false;
        if (stepDy < 0 && !isBlockConfirmedClear(level, nx, cy + 1, nz)) return false;
        return true;
    }

    private boolean isBlockConfirmedClear(Level level, int x, int y, int z) {
        scratchPos.set(x, y, z);
        BlockState st = level.getBlockState(scratchPos);
        if (!st.isAir() && !st.getCollisionShape(level, scratchPos).isEmpty()) {
            return false;
        }
        long packed = BlockPos.asLong(x, y, z);
        for (int i = 0; i < CONFIRM_RING_SIZE; i++) {
            if (pendingConfirmPos[i] == packed && tickCounter < pendingConfirmExpireTick[i]) {
                return false; // Oczekiwanie na potwierdzenie z serwera (max 4 ticki na wysokim pingu)
            }
        }
        return true;
    }

    private void registerPendingServerConfirm(long packedPos) {
        int slot = tickCounter & (CONFIRM_RING_SIZE - 1);
        pendingConfirmPos[slot] = packedPos;
        pendingConfirmExpireTick[slot] = tickCounter + MAX_CONFIRM_WAIT_TICKS;
    }

    private void advancePathCursor(LocalPlayer player) {
        while (currentNodeIndex < pathLength - 1) {
            long next = pathNodes[currentNodeIndex + 1];
            double dx = (BlockPos.getX(next) + 0.5D) - player.getX();
            double dy = Math.abs(BlockPos.getY(next) - player.getY());
            double dz = (BlockPos.getZ(next) + 0.5D) - player.getZ();
            if (dx * dx + dz * dz <= 0.25D && dy <= 0.45D) {
                currentNodeIndex++;
            } else {
                break;
            }
        }
    }

    private boolean isUpcomingSharpTurn() {
        if (currentNodeIndex + 2 >= pathLength) return false;
        long a = pathNodes[currentNodeIndex];
        long b = pathNodes[currentNodeIndex + 1];
        long c = pathNodes[currentNodeIndex + 2];

        int dx1 = BlockPos.getX(b) - BlockPos.getX(a);
        int dz1 = BlockPos.getZ(b) - BlockPos.getZ(a);
        int dx2 = BlockPos.getX(c) - BlockPos.getX(b);
        int dz2 = BlockPos.getZ(c) - BlockPos.getZ(b);
        return (dx1 * dx2 + dz1 * dz2) <= 0; // Kąt 90° lub 180°
    }

    private void aimAtZeroGc(LocalPlayer player, double tx, double ty, double tz) {
        double dx = tx - player.getX();
        double dy = ty - (player.getY() + PLAYER_EYE_HEIGHT);
        double dz = tz - player.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);

        float rawYaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float rawPitch = (float) (-(Mth.atan2(dy, horiz) * (180.0D / Math.PI)));
        float[] q = GrimSyncController.applyGrimGcdRaw(player.getYRot(), player.getXRot(), rawYaw, rawPitch);
        player.setYRot(q[0]);
        player.setXRot(q[1]);
    }

    public boolean isCurrentlyBreaking() {
        return isBreaking;
    }

    public BlockPos getActiveBreakPos() {
        return activeBreakImmutable;
    }
}

