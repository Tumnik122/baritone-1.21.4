package baritone.render;

import baritone.Baritone;
import baritone.api.event.events.RenderEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.bypass.BypassProcess;
import baritone.utils.FastBreakHelper;
import baritone.utils.IRenderer;
import baritone.utils.WorldFxShaders;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * VENTURA CLIENT STYLE: SHADER BLOCK OVERLAY & RADIAL GROUND WAVE VISUALS (ULTRA HQ EDITION)
 *
 * ULEPSZENIA W WERSJI ULTRA HQ:
 * 1. Krystalicznie czyste i gładkie krawędzie (Zero pikseli / stipplingu / kropek):
 *    - Prawidłowe jednostkowe wektory styczne normalnych w shaderze linii (CoreShaders.RENDERTYPE_LINES).
 *    - Aktywacja GL_LINE_SMOOTH oraz GL_POLYGON_OFFSET_LINE (-3.0F).
 *    - Uniesienie dolnej krawędzi o 0.002D nad siatkę terenu (całkowity brak kolizji z depth buforem).
 *    - Krawędzie są w 100% ciągłe, jednolite, wektorowe i antyaliasowane.
 *
 * 2. Fala WYŁĄCZNIE przy wyskoku gracza (Takeoff Only):
 *    - Podpięcie bezpośrednio pod MixinLivingEntity.jumpFromGround() dla LocalPlayer.
 *    - Zabezpieczenie prędkości pionowej (motionY >= 0.35) i cooldown 220ms.
 *    - NIGDY nie wyzwala się podczas spadania, zeskakiwania czy lądowania na ziemi.
 *
 * 3. Super wydajne batchowanie:
 *    - Wszystkie klocki fali uderzeniowej rysowane są w pojedynczym wywołaniu (1 draw call dla wypełnień, 1 dla linii).
 *    - Twardy reset parametrów GL gwarantujący brak czarnych ekranów przy F5.
 */
public class BlockOverlayRenderer implements IRenderer, AbstractGameEventListener {

    private static volatile BlockOverlayRenderer instance;

    private final Baritone baritone;

    // Stan płynnej interpolacji ramki (Smooth Box Lerp)
    private AABB currentBox = null;
    private float currentAlpha = 0.0F;
    private long lastTimeNanos = System.nanoTime();

    // Detekcja wyskoku gracza
    private boolean wasOnGround = true;
    private long lastJumpTimeNanos = 0;
    private final List<GroundShockwave> activeShockwaves = new CopyOnWriteArrayList<>();

    // Czas referencyjny dla fali ciągłej
    private static final long START_TIME_NANOS = System.nanoTime();

    public BlockOverlayRenderer(Baritone baritone) {
        this.baritone = baritone;
        instance = this;
        baritone.getGameEventHandler().registerEventListener(this);
    }

    /**
     * Bezpośrednie wywołanie z MixinLivingEntity.jumpFromGround() w momencie faktycznego wyskoku.
     */
    public static void onPlayerJump(double x, double y, double z) {
        BlockOverlayRenderer r = instance;
        if (r != null && r.settings.radialWave.value) {
            r.triggerJumpWave(x, y, z, System.nanoTime());
        }
    }

    public synchronized void triggerJumpWave(double x, double y, double z, long now) {
        if (settings.radialWaveTrigger.value != 0) {
            return;
        }
        if (now - lastJumpTimeNanos < 220_000_000L) { // 220ms cooldown przeciwko duplikatom
            return;
        }
        lastJumpTimeNanos = now;
        spawnShockwave(x, y, z, now);
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        long now = System.nanoTime();
        float delta = Math.min((now - lastTimeNanos) / 1.0E9F, 0.05F);
        lastTimeNanos = now;

        PoseStack stack = event.getModelViewStack();
        double vx = renderManager.renderPosX();
        double vy = renderManager.renderPosY();
        double vz = renderManager.renderPosZ();
        boolean ignoreDepth = settings.blockOverlayIgnoreDepth.value;

        // 1. Aktualizacja stanu wyskoku (oraz czyszczenie starych fal)
        updateJumpShockwaves(mc, now);

        // 2. Renderowanie rozchodzącej się fali po ziemi
        if (settings.radialWave.value) {
            renderGroundWaveVisuals(event, stack, mc, vx, vy, vz, ignoreDepth, now);
        }

        // 3. Renderowanie krystalicznego Block Overlay HQ
        if (settings.clientBlockOverlay.value) {
            renderTargetBlockOverlay(event, stack, mc, vx, vy, vz, delta, ignoreDepth);
        }

        // 4. Renderowanie fali na celu wydobywczym bota (Mining Wave)
        if (settings.renderMiningWaveOverlay.value) {
            renderMiningTargetOverlay(event, stack, mc, vx, vy, vz, ignoreDepth);
        }

        // 5. Renderowanie kapelusza China Hat (3D Visual Cosmetic)
        if (settings.chinaHat.value) {
            try {
                ChinaHatRenderer.render(event, stack, mc, delta);
            } catch (Throwable t) {
                // Safeguard against cosmetic rendering failures
            }
        }

        // Twardy reset stanu OpenGL zabezpieczający przed czarnym ekranem przy F5
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.setShader(CoreShaders.POSITION_COLOR);
    }

    /**
     * Aktualizacja fal i awaryjna detekcja wyskoku (wyłącznie z prędkością wznoszenia >= 0.35).
     * NIGDY nie wyzwala się podczas spadania (gdzie motionY < 0) ani lądowania (motionY <= 0).
     */
    private void updateJumpShockwaves(Minecraft mc, long now) {
        boolean onGround = mc.player.onGround();
        double motionY = mc.player.getDeltaMovement().y;

        int triggerMode = settings.radialWaveTrigger.value; // 0 = Tylko przy wyskoku, 1 = Ciągła

        if (triggerMode == 0) {
            // Awaryjny fallback: gracz oderwał się od ziemi z mocnym impulsem w górę (skok vanilla = 0.42)
            if (wasOnGround && !onGround && motionY >= 0.35) {
                triggerJumpWave(mc.player.getX(), mc.player.getY(), mc.player.getZ(), now);
            }
        }

        wasOnGround = onGround;

        // Usuwanie zakończonych fal
        float maxRadius = Math.max(3.0F, settings.radialWaveRadius.value);
        float speed = Math.max(1.5F, settings.radialWaveSpeed.value);
        float maxLifeSeconds = (maxRadius / speed) + 0.25F;

        Iterator<GroundShockwave> it = activeShockwaves.iterator();
        while (it.hasNext()) {
            GroundShockwave sw = it.next();
            float age = (now - sw.startTimeNanos) / 1.0E9F;
            if (age > maxLifeSeconds) {
                activeShockwaves.remove(sw);
            }
        }
    }

    private void spawnShockwave(double x, double y, double z, long now) {
        if (activeShockwaves.size() >= 4) {
            activeShockwaves.remove(0);
        }
        activeShockwaves.add(new GroundShockwave(
                x, y, z, now,
                settings.radialWaveRadius.value,
                settings.radialWaveSpeed.value,
                settings.blockOverlayColor.value
        ));
    }

    /**
     * Renderuje krystalicznie przezroczysty Block Overlay o ultra-wysokiej jakości krawędzi (HQ lines).
     */
    private void renderTargetBlockOverlay(RenderEvent event, PoseStack stack, Minecraft mc,
                                         double vx, double vy, double vz, float delta, boolean ignoreDepth) {
        AABB targetBox = null;
        float targetAlpha = 0.0F;

        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            BlockPos targetPos = bhr.getBlockPos();
            BlockState state = mc.level.getBlockState(targetPos);
            if (!state.isAir()) {
                VoxelShape shape = state.getShape(mc.level, targetPos);
                targetBox = (shape.isEmpty() ? Shapes.block().bounds() : shape.bounds()).move(targetPos);
                targetAlpha = 1.0F;
            }
        }

        float speed = settings.blockOverlaySpeed.value;
        float t = settings.blockOverlaySmooth.value ? (1.0F - (float) Math.exp(-speed * delta)) : 1.0F;

        if (targetBox != null) {
            if (currentBox == null || currentBox.getCenter().distanceTo(targetBox.getCenter()) > 10.0D) {
                currentBox = targetBox;
            } else {
                currentBox = new AABB(
                        currentBox.minX + (targetBox.minX - currentBox.minX) * t,
                        currentBox.minY + (targetBox.minY - currentBox.minY) * t,
                        currentBox.minZ + (targetBox.minZ - currentBox.minZ) * t,
                        currentBox.maxX + (targetBox.maxX - currentBox.maxX) * t,
                        currentBox.maxY + (targetBox.maxY - currentBox.maxY) * t,
                        currentBox.maxZ + (targetBox.maxZ - currentBox.maxZ) * t
                );
            }
        }

        currentAlpha += (targetAlpha - currentAlpha) * t;

        if (currentAlpha > 0.01F && currentBox != null) {
            Color glassBaseColor = settings.blockOverlayColor.value;
            Color outlineBaseColor = settings.blockOverlaySecondaryColor.value;

            // Breathing pulse effect (subtle, 0.06 amplitude, ~2.8s organic cycle)
            float pulse = (float) Math.sin((System.currentTimeMillis() % 10000L) / 1000.0 * 2.8) * 0.06F;
            float pulseAlpha = Math.min(1.0F, Math.max(0.0F, currentAlpha * (1.0F + pulse)));

            // 1. Krystalicznie przezroczyste szklane wypełnienie (Mode 5 Ventura Glass Shader lub Ultra-Smooth fallback)
            float glassAlpha = pulseAlpha * settings.blockOverlayAlpha.value;
            if (glassAlpha > 0.005F) {
                if (WorldFxShaders.isUsable()) {
                    WorldFxShaders.begin(stack, event.getProjectionMatrix(), WorldFxShaders.MODE_BLOCK_OVERLAY, ignoreDepth, false);
                    WorldFxShaders.waveBox(currentBox.inflate(0.0020D).move(-vx, -vy, -vz), glassBaseColor, glassAlpha);
                    WorldFxShaders.end();
                } else {
                    BufferBuilder filled = IRenderer.startFilled(glassBaseColor, glassAlpha, ignoreDepth);
                    IRenderer.emitFilledAABB(filled, stack, currentBox.inflate(0.0020D));
                    IRenderer.endFilled(filled, ignoreDepth);
                }
            }

            // 2. High-Quality Dual-Pass Anti-Aliased Lines:
            float lineWidth = Math.max(1.8F, settings.blockOverlayLineWidth.value);

            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);

            // Pass A: Soft Neon Glow Rim (Aksamitna poświata zewnętrzna o podwójnej szerokości)
            float glowAlpha = pulseAlpha * 0.35F;
            if (glowAlpha > 0.01F) {
                BufferBuilder glowLines = IRenderer.startLines(outlineBaseColor, glowAlpha, lineWidth * 1.6F, ignoreDepth);
                emitHQBoxLines(glowLines, stack, currentBox, vx, vy, vz);
                IRenderer.endLines(glowLines, ignoreDepth);
            }

            // Pass B: Sharp Inner Boundary (Krystalicznie ostra linia krawędzi)
            float lineAlpha = pulseAlpha * 0.95F;
            BufferBuilder lines = IRenderer.startLines(outlineBaseColor, lineAlpha, lineWidth, ignoreDepth);
            emitHQBoxLines(lines, stack, currentBox, vx, vy, vz);
            IRenderer.endLines(lines, ignoreDepth);

            // Pass C: Precision Tech Corner Brackets (Akcenty narożników CAD / Cyber reticle)
            BufferBuilder cornerLines = IRenderer.startLines(outlineBaseColor, Math.min(1.0F, pulseAlpha * 1.15F), lineWidth * 1.75F, ignoreDepth);
            emitHQCornerBrackets(cornerLines, stack, currentBox, vx, vy, vz);
            IRenderer.endLines(cornerLines, ignoreDepth);

            GL11.glDisable(GL11.GL_LINE_SMOOTH);
        }
    }

    /**
     * Renderuje rozchodzącą się falę po ziemi wyzwalaną wyłącznie skokiem gracza.
     * Wykorzystuje ultra-szybkie batchowanie (2 draw calls dla całej sceny).
     */
    private void renderGroundWaveVisuals(RenderEvent event, PoseStack stack, Minecraft mc,
                                         double vx, double vy, double vz, boolean ignoreDepth, long now) {
        int triggerMode = settings.radialWaveTrigger.value;
        boolean isContinuous = (triggerMode == 1);

        if (!isContinuous && activeShockwaves.isEmpty()) {
            return;
        }

        float maxRadius = Math.max(3.0F, settings.radialWaveRadius.value);
        int rInt = (int) Math.ceil(maxRadius);
        BlockPos playerPos = mc.player.blockPosition();

        List<WaveBlockEntry> waveBlocks = new ArrayList<>();
        Color lilacCol = settings.blockOverlayColor.value;
        Color cyanCol = settings.blockOverlaySecondaryColor.value;
        int colorMode = settings.radialWaveMode.value;

        float continuousTime = (now - START_TIME_NANOS) / 1.0E9F;
        float continuousR1 = (continuousTime * settings.radialWaveSpeed.value) % maxRadius;
        float continuousR2 = (continuousR1 + maxRadius * 0.5F) % maxRadius;

        for (int dx = -rInt; dx <= rInt; dx++) {
            for (int dz = -rInt; dz <= rInt; dz++) {
                double dist = Math.hypot(dx, dz);
                if (dist > maxRadius) {
                    continue;
                }

                float weight = 0.0F;

                if (isContinuous) {
                    float sigma = 0.80F;
                    float w1 = (float) (Math.max(0.0, 1.0 - (continuousR1 / maxRadius)) * Math.exp(-Math.pow(dist - continuousR1, 2) / (2.0 * sigma * sigma)));
                    float w2 = settings.radialWaveDouble.value
                            ? (float) (Math.max(0.0, 1.0 - (continuousR2 / maxRadius)) * Math.exp(-Math.pow(dist - continuousR2, 2) / (2.0 * sigma * sigma)))
                            : 0.0F;
                    weight = Math.max(w1, w2);
                } else {
                    for (GroundShockwave sw : activeShockwaves) {
                        float age = (now - sw.startTimeNanos) / 1.0E9F;
                        float waveFront = age * sw.speed;
                        double distToOrigin = Math.hypot((playerPos.getX() + dx + 0.5) - sw.x, (playerPos.getZ() + dz + 0.5) - sw.z);

                        if (distToOrigin <= sw.maxRadius) {
                            float sigma = 0.82F;
                            float radialFade = (float) Math.pow(Math.max(0.0, 1.0 - (waveFront / sw.maxRadius)), 1.1);
                            float w = (float) (radialFade * Math.exp(-Math.pow(distToOrigin - waveFront, 2) / (2.0 * sigma * sigma)));
                            if (w > weight) {
                                weight = w;
                            }
                        }
                    }
                }

                if (weight < 0.035F) {
                    continue;
                }

                int bx = playerPos.getX() + dx;
                int bz = playerPos.getZ() + dz;
                int pyInt = playerPos.getY();

                for (int dy = 2; dy >= -3; dy--) {
                    BlockPos pos = new BlockPos(bx, pyInt + dy, bz);
                    BlockState st = mc.level.getBlockState(pos);
                    if (!st.isAir() && st.isSolid()) {
                        BlockState above = mc.level.getBlockState(pos.above());
                        if (above.isAir() || !above.isSolid()) {
                            VoxelShape shape = st.getShape(mc.level, pos);
                            AABB box = (shape.isEmpty() ? Shapes.block().bounds() : shape.bounds()).move(pos);

                            Color col;
                            if (colorMode == 1) {
                                col = cyanCol;
                            } else if (colorMode == 2) {
                                float gradFactor = (float) Math.min(1.0, dist / maxRadius);
                                col = IRenderer.blendColors(lilacCol, cyanCol, gradFactor);
                            } else {
                                col = lilacCol;
                            }

                            waveBlocks.add(new WaveBlockEntry(box, col, weight));
                            break;
                        }
                    }
                }
            }
        }

        if (waveBlocks.isEmpty()) {
            return;
        }

        float baseGlassAlpha = settings.blockOverlayAlpha.value;

        // 1. Zbatchowane szklane wypełnienie bloków fali
        BufferBuilder filled = IRenderer.startFilled(lilacCol, baseGlassAlpha, ignoreDepth);
        for (WaveBlockEntry entry : waveBlocks) {
            float alpha = entry.weight * baseGlassAlpha * 1.15F;
            if (alpha > 0.008F) {
                IRenderer.emitFilledAABB(filled, stack, entry.box.inflate(0.0020D), entry.color, alpha);
            }
        }
        IRenderer.endFilled(filled, ignoreDepth);

        // 2. Zbatchowane linie bloków fali (Hardware line smooth + polygon offset)
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);

        float waveLineWidth = Math.max(1.6F, settings.blockOverlayLineWidth.value);
        BufferBuilder lines = IRenderer.startLines(lilacCol, 0.90F, waveLineWidth, ignoreDepth);
        for (WaveBlockEntry entry : waveBlocks) {
            float lineAlpha = entry.weight * 0.90F;
            if (lineAlpha > 0.015F) {
                emitHQBoxLines(lines, stack, entry.box, vx, vy, vz, entry.color, lineAlpha);
            }
        }
        IRenderer.endLines(lines, ignoreDepth);

        GL11.glDisable(GL11.GL_LINE_SMOOTH);
    }

    /**
     * Rysuje linie sześcianu w najwyższej jakości:
     * - box.inflate(0.0025D) eliminuje Z-fighting z meshem terenu,
     * - linie mają idealnie przeliczone wektory normalnych w CoreShaders.RENDERTYPE_LINES.
     */
    private void emitHQBoxLines(BufferBuilder bufferBuilder, PoseStack stack, AABB aabb, double vx, double vy, double vz) {
        AABB box = aabb.inflate(0.0025D);
        double minX = box.minX - vx;
        double minY = box.minY - vy;
        double minZ = box.minZ - vz;
        double maxX = box.maxX - vx;
        double maxY = box.maxY - vy;
        double maxZ = box.maxZ - vz;
        IRenderer.emitAABB(bufferBuilder, stack, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private void emitHQBoxLines(BufferBuilder bufferBuilder, PoseStack stack, AABB aabb, double vx, double vy, double vz, Color color, float alpha) {
        AABB box = aabb.inflate(0.0025D);
        double minX = box.minX - vx;
        double minY = box.minY - vy;
        double minZ = box.minZ - vz;
        double maxX = box.maxX - vx;
        double maxY = box.maxY - vy;
        double maxZ = box.maxZ - vz;
        IRenderer.emitAABB(bufferBuilder, stack, minX, minY, minZ, maxX, maxY, maxZ, color, alpha);
    }

    private void emitHQCornerBrackets(BufferBuilder bufferBuilder, PoseStack stack, AABB aabb, double vx, double vy, double vz) {
        emitHQCornerBrackets(bufferBuilder, stack, aabb, vx, vy, vz, 0.0F);
    }

    private void emitHQCornerBrackets(BufferBuilder bufferBuilder, PoseStack stack, AABB aabb, double vx, double vy, double vz, float progress) {
        AABB box = aabb.inflate(0.0030D);
        double minX = box.minX - vx;
        double minY = box.minY - vy;
        double minZ = box.minZ - vz;
        double maxX = box.maxX - vx;
        double maxY = box.maxY - vy;
        double maxZ = box.maxZ - vz;

        // Dynamiczna ekspansja klamer w miarę postępu niszczenia (0.0 -> 1.0)
        float s = 1.0F - (float) Math.pow(1.0F - Math.min(1.0F, Math.max(0.0F, progress)), 3.0D);
        double bracketFactor = 0.22D + 0.28D * s;

        double blX = Math.min(0.48, (maxX - minX) * bracketFactor);
        double blY = Math.min(0.48, (maxY - minY) * bracketFactor);
        double blZ = Math.min(0.48, (maxZ - minZ) * bracketFactor);

        double[] xs = {minX, maxX};
        double[] ys = {minY, maxY};
        double[] zs = {minZ, maxZ};

        for (double x : xs) {
            double dx = (x == minX) ? blX : -blX;
            for (double y : ys) {
                double dy = (y == minY) ? blY : -blY;
                for (double z : zs) {
                    double dz = (z == minZ) ? blZ : -blZ;
                    IRenderer.emitLine(bufferBuilder, stack, x, y, z, x + dx, y, z, 1.0F, 0.0F, 0.0F);
                    IRenderer.emitLine(bufferBuilder, stack, x, y, z, x, y + dy, z, 0.0F, 1.0F, 0.0F);
                    IRenderer.emitLine(bufferBuilder, stack, x, y, z, x, y, z + dz, 0.0F, 0.0F, 1.0F);
                }
            }
        }
    }

    private void emitCyberMiningLattice(BufferBuilder buf, PoseStack stack, AABB aabb, double vx, double vy, double vz, float progress) {
        if (progress <= 0.05F) return;
        AABB box = aabb.inflate(0.0035D);
        double minX = box.minX - vx, maxX = box.maxX - vx;
        double minY = box.minY - vy, maxY = box.maxY - vy;
        double minZ = box.minZ - vz, maxZ = box.maxZ - vz;
        double cx = (minX + maxX) * 0.5D;
        double cy = (minY + maxY) * 0.5D;
        double cz = (minZ + maxZ) * 0.5D;

        float rProg = (float) Math.min(0.46D, progress * 0.46D);

        // Górna ścianka (Diamond Energy Lattice)
        IRenderer.emitLine(buf, stack, cx - rProg, maxY, cz, cx, maxY, cz + rProg);
        IRenderer.emitLine(buf, stack, cx, maxY, cz + rProg, cx + rProg, maxY, cz);
        IRenderer.emitLine(buf, stack, cx + rProg, maxY, cz, cx, maxY, cz - rProg);
        IRenderer.emitLine(buf, stack, cx, maxY, cz - rProg, cx - rProg, maxY, cz);

        // Ścianki boczne (Cross Fracture)
        IRenderer.emitLine(buf, stack, cx - rProg, cy, minZ, cx, cy + rProg, minZ);
        IRenderer.emitLine(buf, stack, cx, cy + rProg, minZ, cx + rProg, cy, minZ);
        IRenderer.emitLine(buf, stack, cx + rProg, cy, minZ, cx, cy - rProg, minZ);
        IRenderer.emitLine(buf, stack, cx, cy - rProg, minZ, cx - rProg, cy, minZ);

        IRenderer.emitLine(buf, stack, cx - rProg, cy, maxZ, cx, cy + rProg, maxZ);
        IRenderer.emitLine(buf, stack, cx, cy + rProg, maxZ, cx + rProg, cy, maxZ);
        IRenderer.emitLine(buf, stack, cx + rProg, cy, maxZ, cx, cy - rProg, maxZ);
        IRenderer.emitLine(buf, stack, cx, cy - rProg, maxZ, cx - rProg, cy, maxZ);
    }

    /**
     * Renderuje pulsujący cel wydobywczy Baritone z dynamiczną siatką pęknięć.
     */
    private void renderMiningTargetOverlay(RenderEvent event, PoseStack stack, Minecraft mc,
                                          double vx, double vy, double vz, boolean ignoreDepth) {
        BypassProcess bypass = baritone.getBypassProcess();
        BlockPos miningPos = null;
        if (bypass != null && bypass.isActive()) {
            miningPos = bypass.getCurrentBreakingBlock();
            if (miningPos == null) {
                miningPos = bypass.getCurrentTargetBlock();
            }
        }

        HitResult hit = mc.hitResult;
        if (miningPos != null && !miningPos.equals(hit instanceof BlockHitResult b ? b.getBlockPos() : null)) {
            BlockState mState = mc.level.getBlockState(miningPos);
            if (!mState.isAir()) {
                VoxelShape mShape = mState.getShape(mc.level, miningPos);
                AABB mBox = (mShape.isEmpty() ? Shapes.block().bounds() : mShape.bounds()).move(miningPos);

                // Estymacja postępu niszczenia (Mining Progress 0.0 - 1.0)
                float progress = 0.0F;
                try {
                    progress = (float) FastBreakHelper.getGrimDestroyProgress(baritone.getPlayerContext(), miningPos, mState);
                } catch (Throwable ignored) {
                }
                progress = Math.min(1.0F, Math.max(0.0F, progress));

                // Dynamic breathing pulse z przyspieszeniem pod koniec kopania
                float pulseFreq = 4.5F + progress * 8.0F;
                float pulse = (float) Math.sin((System.currentTimeMillis() % 10000L) / 1000.0 * pulseFreq) * 0.15F + 0.85F;

                // Przejście barwne z neonowego cyjanu w gorący róż/fiolet i biel tuż przed rozbiciem bloku
                Color baseCyan = new Color(6, 182, 212, 220);
                Color hotMagenta = new Color(255, 60, 180, 240);
                Color miningColor = IRenderer.blendColors(baseCyan, hotMagenta, progress);

                // Pass 1: Translucent Energy Box
                BufferBuilder filled = IRenderer.startFilled(miningColor, (0.20F + progress * 0.25F) * pulse, ignoreDepth);
                IRenderer.emitFilledAABB(filled, stack, mBox.inflate(0.0020D));
                IRenderer.endFilled(filled, ignoreDepth);

                GL11.glEnable(GL11.GL_LINE_SMOOTH);
                GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);

                // Pass 2: Sharp Box Outline
                float width = Math.max(1.8F, settings.blockOverlayLineWidth.value);
                BufferBuilder mLines = IRenderer.startLines(miningColor, 0.90F * pulse, width, ignoreDepth);
                emitHQBoxLines(mLines, stack, mBox, vx, vy, vz);
                IRenderer.endLines(mLines, ignoreDepth);

                // Pass 3: Precision CAD Corner Brackets + Cyber Mining Lattice (progresywne zamykanie klatki)
                Color goldColor = new Color(255, 215, 0);
                Color bracketColor = IRenderer.blendColors(goldColor, Color.WHITE, progress);
                BufferBuilder cornerLines = IRenderer.startLines(bracketColor, 0.95F * pulse, width * 1.6F, ignoreDepth);
                emitHQCornerBrackets(cornerLines, stack, mBox, vx, vy, vz, progress);
                emitCyberMiningLattice(cornerLines, stack, mBox, vx, vy, vz, progress);
                IRenderer.endLines(cornerLines, ignoreDepth);

                GL11.glDisable(GL11.GL_LINE_SMOOTH);
            }
        }
    }

    private static class GroundShockwave {
        public final double x, y, z;
        public final long startTimeNanos;
        public final float maxRadius;
        public final float speed;
        public final Color color;

        public GroundShockwave(double x, double y, double z, long startTimeNanos, float maxRadius, float speed, Color color) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.startTimeNanos = startTimeNanos;
            this.maxRadius = maxRadius;
            this.speed = speed;
            this.color = color;
        }
    }

    private record WaveBlockEntry(AABB box, Color color, float weight) {}
}
