package com.astra.revolution.visuals;

import baritone.Baritone;
import baritone.api.event.events.RenderEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ASTRA REVOLUTION — Elite Visuals Engine V1 (Minecraft 1.21.4 OpenGL 3.3 Core).
 *
 * Implements 5+ top-tier visual modules:
 * 1. Hand Shaders (Obsidian-Lilac Chams + Cyber-Cyan Wireframe Chroma in 1st person)
 * 2. Custom Player F5 Disguise (Tung Tung Sahur, Ballerina Capuccina, Cyber Mech, Hologram Avatar, Capybara King)
 * 3. 3D Conical China Hat & Levitating Halo (Smooth 48-segment gradient fan with head tilt)
 * 4. 3D Motion Trail / Breadcrumbs Ribbon (Fading alpha dual-tone ribbons trailing movement)
 * 5. Dynamic Jump & Landing Ripple Shockwave Circles (Cubic eased ground rings)
 * 6. Target ESP Track Ring (Smooth Sine bouncing ring on active target)
 */
public final class AstraVisualsEngine implements AbstractGameEventListener {

    private static AstraVisualsEngine instance;
    private final Minecraft mc;
    private final Baritone baritone;

    // Visual Palettes: Obsidian & Lilac Glass, Cyber Cyan, Gold, Rose
    public static final int COLOR_LILAC = 0xFFC77DFF;
    public static final int COLOR_CYAN  = 0xFF00F0FF;
    public static final int COLOR_GOLD  = 0xFFFFD700;
    public static final int COLOR_ROSE  = 0xFFFF69B4;

    public enum DisguiseModel {
        OFF("Wyłączone"),
        TUNG_TUNG_SAHUR("Tung Tung Sahur"),
        BALLERINA_CAPUCCINA("Ballerina Capuccina"),
        CYBER_MECH("Cyber Mech"),
        HOLOGRAM_AVATAR("Hologram Avatar"),
        CAPYBARA_KING("Capybara King");

        private final String displayName;

        DisguiseModel(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public DisguiseModel next() {
            DisguiseModel[] values = values();
            return values[(this.ordinal() + 1) % values.length];
        }
    }

    // Active visual toggles
    private static boolean handShadersEnabled = true;
    private static DisguiseModel activeDisguise = DisguiseModel.TUNG_TUNG_SAHUR;
    private static boolean chinaHatEnabled = true;
    private static boolean motionTrailEnabled = true;
    private static int motionTrailMode = 0;
    private static boolean jumpCirclesEnabled = true;
    private static boolean targetEspEnabled = true;
    private static int targetEspMode = 0;
    private static boolean customFogEnabled = true;
    private static int customFogMode = 0;
    private static boolean customSkyEnabled = true;
    private static int customSkyMode = 0;
    private static boolean ambientParticlesEnabled = true;

    // Motion trail record
    public record TrailNode(Vec3 pos, float height, long timestamp) {}

    // Jump circles & dynamic shockwave sparks (Newtonian Drag & Vortex Physics)
    public static class SparkParticle {
        public final float angle;
        public final float speed;
        public float y;
        public float vy;
        public final float initialVy;
        public final float size;
        public final int color;
        public final float spinOmega;
        public final float drag;
        public final float bounceDamping;
        public final float lifetimeSec;

        public SparkParticle(float angle, float speed, float vy, float size, int color,
                             float spinOmega, float drag, float bounceDamping, float lifetimeSec) {
            this.angle = angle;
            this.speed = speed;
            this.y = 0.02F;
            this.vy = vy;
            this.initialVy = vy;
            this.size = size;
            this.color = color;
            this.spinOmega = spinOmega;
            this.drag = drag;
            this.bounceDamping = bounceDamping;
            this.lifetimeSec = lifetimeSec;
        }

        public SparkParticle(float angle, float speed, float vy, float size, int color) {
            this(angle, speed, vy, size, color, 2.0F, 1.4F, 0.48F, 1.2F);
        }
    }

    public static class JumpCircleInstance {
        public final Vec3 pos;
        public final long spawnTime;
        public final float maxRadius;
        public final float durationMs;
        public final boolean isLanding;
        public final int mode;
        public final int primaryColor;
        public final int secondaryColor;
        public final List<SparkParticle> sparks;

        public JumpCircleInstance(Vec3 pos, long spawnTime, float maxRadius, float durationMs, boolean isLanding, int mode, int primaryColor, int secondaryColor, List<SparkParticle> sparks) {
            this.pos = pos;
            this.spawnTime = spawnTime;
            this.maxRadius = maxRadius;
            this.durationMs = durationMs;
            this.isLanding = isLanding;
            this.mode = mode;
            this.primaryColor = primaryColor;
            this.secondaryColor = secondaryColor;
            this.sparks = sparks;
        }
    }

    private static final Deque<TrailNode> TRAIL_NODES = new ArrayDeque<>(128);
    private static final Deque<JumpCircleInstance> JUMP_CIRCLES = new ArrayDeque<>(32);
    private static final Map<UUID, OtherPlayerState> OTHER_PLAYERS_GROUND = new HashMap<>();

    private static class OtherPlayerState {
        boolean wasOnGround = true;
        float lastFallDistance = 0.0f;
    }

    private static int jumpCirclesMode = 0;
    private static boolean jumpCirclesOthers = true;
    private static boolean jumpCirclesSparks = true;
    private static float jumpCirclesRadius = 2.5F;

    private boolean wasOnGround = true;
    private float lastFallDistance = 0.0f;
    private LivingEntity currentTrackedTarget = null;

    public AstraVisualsEngine(Baritone baritone) {
        instance = this;
        this.mc = Minecraft.getInstance();
        this.baritone = baritone;
        baritone.getGameEventHandler().registerEventListener(this);
    }

    public static AstraVisualsEngine getInstance() {
        return instance;
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() != TickEvent.Type.IN) {
            return;
        }

        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }

        if (Baritone.settings() != null) {
            handShadersEnabled = Baritone.settings().handShaders.value;
            chinaHatEnabled = Baritone.settings().chinaHat.value;
            motionTrailEnabled = Baritone.settings().motionTrail.value;
            jumpCirclesEnabled = Baritone.settings().jumpCircles.value;
            if (Baritone.settings().motionTrailMode != null) {
                motionTrailMode = Baritone.settings().motionTrailMode.value;
            }
            if (Baritone.settings().targetEsp != null) {
                targetEspEnabled = Baritone.settings().targetEsp.value;
            }
            if (Baritone.settings().targetEspMode != null) {
                targetEspMode = Baritone.settings().targetEspMode.value;
            }
            if (Baritone.settings().jumpCirclesMode != null) {
                jumpCirclesMode = Baritone.settings().jumpCirclesMode.value;
            }
            if (Baritone.settings().jumpCirclesRadius != null) {
                jumpCirclesRadius = Baritone.settings().jumpCirclesRadius.value.floatValue();
            }
            if (Baritone.settings().jumpCirclesSparks != null) {
                jumpCirclesSparks = Baritone.settings().jumpCirclesSparks.value;
            }
            if (Baritone.settings().jumpCirclesOthers != null) {
                jumpCirclesOthers = Baritone.settings().jumpCirclesOthers.value;
            }
            customFogEnabled = Baritone.settings().customFog.value;
            customSkyEnabled = Baritone.settings().customSky.value;
            ambientParticlesEnabled = Baritone.settings().ambientParticles.value;
            try {
                String disguiseName = Baritone.settings().customPlayerDisguise.value;
                if (disguiseName != null) {
                    activeDisguise = DisguiseModel.valueOf(disguiseName.toUpperCase().trim());
                }
            } catch (Exception ignored) {
            }
        }

        // Target ESP Auto-Acquisition
        if (targetEspEnabled) {
            if (currentTrackedTarget != null && (!currentTrackedTarget.isAlive() || currentTrackedTarget.distanceToSqr(player) > 36.0D * 36.0D)) {
                currentTrackedTarget = null;
            }
            if (currentTrackedTarget == null && mc.level != null) {
                if (mc.crosshairPickEntity instanceof LivingEntity le && le.isAlive() && le != player) {
                    currentTrackedTarget = le;
                } else if (baritone != null && baritone.getFollowProcess() != null && baritone.getFollowProcess().isActive()) {
                    for (var entity : mc.level.entitiesForRendering()) {
                        if (entity instanceof LivingEntity le && le.isAlive() && le != player) {
                            currentTrackedTarget = le;
                            break;
                        }
                    }
                }
            }
        }

        long now = System.currentTimeMillis();
        Vec3 pos = player.position();

        // 1. Motion Trail sampling
        if (motionTrailEnabled) {
            if (TRAIL_NODES.isEmpty() || TRAIL_NODES.peekLast().pos().distanceToSqr(pos) > 0.006D) {
                TRAIL_NODES.addLast(new TrailNode(pos, player.getBbHeight(), now));
            }
        }
        TRAIL_NODES.removeIf(n -> (now - n.timestamp()) > 900L);

        // 2. Jump and landing circle detection (Ground snapping + multi-entity support)
        boolean onGround = player.onGround();
        if (jumpCirclesEnabled) {
            int activeMode = Baritone.settings() != null ? Baritone.settings().jumpCirclesMode.value : jumpCirclesMode;

            // Local Player Jump detected
            if (wasOnGround && !onGround && player.getDeltaMovement().y > 0.08D) {
                spawnJumpCircle(pos, false, 0.0F, activeMode);
                AstraGodTierVisuals.triggerShockwave(pos.x, pos.y, pos.z, COLOR_CYAN, false);
            }
            // Local Player Hard landing detected
            else if (!wasOnGround && onGround && lastFallDistance > 0.45F) {
                spawnJumpCircle(pos, true, lastFallDistance, activeMode);
                AstraGodTierVisuals.triggerShockwave(pos.x, pos.y, pos.z, COLOR_LILAC, false);
            }

            // Other nearby players & bots jump/landing ripples
            boolean allowOthers = Baritone.settings() != null ? Baritone.settings().jumpCirclesOthers.value : jumpCirclesOthers;
            if (allowOthers && mc.level != null) {
                for (var other : mc.level.players()) {
                    if (other == player || other.distanceToSqr(player) > 36.0D * 36.0D) continue;
                    UUID id = other.getUUID();
                    OtherPlayerState st = OTHER_PLAYERS_GROUND.computeIfAbsent(id, k -> new OtherPlayerState());
                    boolean otherOnGround = other.onGround();
                    if (st.wasOnGround && !otherOnGround && other.getDeltaMovement().y > 0.08D) {
                        spawnJumpCircle(other.position(), false, 0.0F, activeMode);
                    } else if (!st.wasOnGround && otherOnGround && st.lastFallDistance > 0.45F) {
                        spawnJumpCircle(other.position(), true, st.lastFallDistance, activeMode);
                    }
                    st.wasOnGround = otherOnGround;
                    st.lastFallDistance = other.fallDistance;
                }
            }

            // Update spark physics (rising upward drift & gravity)
            for (JumpCircleInstance c : JUMP_CIRCLES) {
                if (c.sparks != null) {
                    for (SparkParticle sp : c.sparks) {
                        sp.y += sp.vy * 0.05F;
                        sp.vy = Math.max(-0.02F, sp.vy - 0.035F);
                    }
                }
            }
        }
        this.wasOnGround = onGround;
        this.lastFallDistance = player.fallDistance;
        JUMP_CIRCLES.removeIf(c -> (now - c.spawnTime) > (long) c.durationMs);
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.options.hideGui) {
            return;
        }

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        PoseStack poseStack = event.getModelViewStack();
        float partialTick = event.getPartialTicks();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        try (AstraGlStateGuard guard = new AstraGlStateGuard(false, false, true)) {
            // Pass 1: Motion Trail ribbons (Zero-Jitter camera relative)
            if (motionTrailEnabled) {
                renderMotionTrail(poseStack, camPos);
            }

            // Pass 2: Jump & landing circles
            if (jumpCirclesEnabled) {
                renderJumpCircles(poseStack);
            }

            // Pass 3: 3D Cyber Target ESP Reticle
            if (targetEspEnabled && currentTrackedTarget != null && currentTrackedTarget.isAlive()) {
                renderTargetEspRing(poseStack, currentTrackedTarget, partialTick, camPos);
            }

            // Pass 4: 3rd person cosmetics (Custom Player Disguise)
            // (China Hat jest renderowany wyłącznie przez ChinaHatRenderer z pełną synchronizacją GUI i pochyleniem głowy)
            if (!mc.options.getCameraType().isFirstPerson()) {
                if (activeDisguise != DisguiseModel.OFF) {
                    renderCustomDisguise(poseStack, player, partialTick);
                }
            }

            // Pass 5: 3D World Ambient Particles (Geometric Stardust or Mascot Companions)
            if (ambientParticlesEnabled) {
                AstraAmbientParticles.renderParticles(poseStack, player, partialTick);
            }

            // Pass 6: God-Tier 3D Combat Impact Waves & Dispersion Rings (Zero-Alloc)
            AstraGodTierVisuals.renderActiveShockwaves(poseStack, camPos);

            // Pass 7: Volumetric Cyber-Beacon & Double-Helix DNA Beacon for Path Goal (All goal types)
            if (baritone != null && baritone.getPathingBehavior() != null && baritone.getPathingBehavior().getGoal() != null) {
                var goal = baritone.getPathingBehavior().getGoal();
                BlockPos targetGoalPos = null;
                if (goal instanceof baritone.api.pathing.goals.GoalBlock gb) {
                    targetGoalPos = gb.getGoalPos();
                } else if (goal instanceof baritone.api.pathing.goals.GoalNear gn) {
                    targetGoalPos = gn.getGoalPos();
                } else if (goal instanceof baritone.api.pathing.goals.GoalGetToBlock gtb) {
                    targetGoalPos = gtb.getGoalPos();
                } else if (goal instanceof baritone.api.pathing.goals.GoalXZ gxz) {
                    targetGoalPos = new BlockPos(gxz.getX(), player.getBlockY(), gxz.getZ());
                } else if (goal instanceof baritone.api.pathing.goals.GoalComposite gc) {
                    for (var g : gc.goals()) {
                        if (g instanceof baritone.api.pathing.goals.GoalBlock gb2) {
                            targetGoalPos = gb2.getGoalPos();
                            break;
                        } else if (g instanceof baritone.api.pathing.goals.GoalNear gn2) {
                            targetGoalPos = gn2.getGoalPos();
                            break;
                        }
                    }
                }
                if (targetGoalPos != null) {
                    float timeSec = (System.currentTimeMillis() % 100000L) / 1000.0F;
                    AstraGodTierVisuals.renderVolumetricCyberBeacon(poseStack, camPos,
                            targetGoalPos.getX() + 0.5, targetGoalPos.getY(), targetGoalPos.getZ() + 0.5,
                            COLOR_CYAN, COLOR_LILAC, timeSec);
                }
            }

            // Pass 8: God-Tier 3D Catmull-Rom Spline Path Ribbon with Animated Chevrons (> > >)
            if (baritone != null && baritone.getPathingBehavior() != null && baritone.getPathingBehavior().hasPath()) {
                var current = baritone.getPathingBehavior().getCurrent();
                if (current != null && current.getPath() != null) {
                    var positions = current.getPath().positions();
                    if (positions != null && positions.size() >= 2) {
                        AstraGodTierVisuals.renderPathCatmullRomRibbonWithChevrons(poseStack, camPos, positions, current.getPosition());
                    }
                }
            }
        }
        poseStack.popPose();
    }

    // =========================================================================
    // MODUŁ 2: CUSTOM PLAYER F5 DISGUISE (PREMIUM HIGH-FIDELITY 3D MODELS)
    // =========================================================================

    private static void renderCustomDisguise(PoseStack poseStack, LocalPlayer player, float partialTick) {
        double ix = Mth.lerp(partialTick, player.xo, player.getX());
        double iy = Mth.lerp(partialTick, player.yo, player.getY());
        double iz = Mth.lerp(partialTick, player.zo, player.getZ());
        float bodyYaw = Mth.lerp(partialTick, player.yBodyRotO, player.yBodyRot);
        float walkSwing = Mth.sin(player.walkAnimation.position(partialTick) * 0.6662F) * player.walkAnimation.speed(partialTick);
        float time = (System.currentTimeMillis() % 100000L) / 1000.0F;

        poseStack.pushPose();
        poseStack.translate(ix, iy, iz);
        poseStack.mulPose(Axis.YP.rotationDegrees(-bodyYaw));

        // PASS 1: Solid Opaque (Zapis do bufora głębokości, włączony culling ścian tylnych)
        setup3DBlendState(false);
        renderDisguisePass(poseStack, player, walkSwing, time, false);

        // PASS 2: Translucent / Effects (Odczyt bufora głębokości, blendowanie bez nadpisywania Z)
        setup3DBlendState(true);
        renderDisguisePass(poseStack, player, walkSwing, time, true);

        poseStack.popPose();
    }

    private static void renderDisguisePass(PoseStack poseStack, LocalPlayer player, float walkSwing, float time, boolean translucent) {
        switch (activeDisguise) {
            case CAPYBARA_KING -> {
                if (!translucent) {
                    // Pasywne oddychanie i pulchne ciało (zamknięty grzbiet, boki i spód)
                    float breath = Mth.sin(time * 3.0F) * 0.015F;
                    // Główny korpus (torso)
                    drawShadedBox(poseStack, -0.36F - breath, 0.12F, -0.52F, 0.36F + breath, 0.80F + breath, 0.38F, 0xFF8C5828);
                    // Zaokrąglony zadek z tyłu (całkowicie zamyka tył przed prześwitami!)
                    drawShadedBox(poseStack, -0.33F, 0.16F, -0.58F, 0.33F, 0.74F, -0.50F, 0xFF7A4B20);
                    // Jasny, kremowy brzuszek
                    drawShadedBox(poseStack, -0.28F, 0.10F, -0.42F, 0.28F, 0.32F, 0.34F, 0xFFC6935C);

                    // 4 łapki z naprzemienną animacją czworonoga (quadruped diagonal gait)
                    float walkFL = walkSwing * 0.22F;
                    float walkFR = -walkSwing * 0.22F;
                    float walkBL = -walkSwing * 0.22F;
                    float walkBR = walkSwing * 0.22F;

                    // Przednia lewa
                    drawShadedBox(poseStack, -0.32F, 0.0F, 0.18F + walkFL, -0.18F, 0.16F, 0.32F + walkFL, 0xFF5C3317);
                    drawShadedBox(poseStack, -0.31F, 0.0F, 0.31F + walkFL, -0.19F, 0.06F, 0.35F + walkFL, 0xFF2B1405); // pazurki
                    // Przednia prawa
                    drawShadedBox(poseStack,  0.18F, 0.0F, 0.18F + walkFR,  0.32F, 0.16F, 0.32F + walkFR, 0xFF5C3317);
                    drawShadedBox(poseStack,  0.19F, 0.0F, 0.31F + walkFR,  0.31F, 0.06F, 0.35F + walkFR, 0xFF2B1405);
                    // Tylna lewa
                    drawShadedBox(poseStack, -0.32F, 0.0F, -0.48F + walkBL, -0.18F, 0.18F, -0.34F + walkBL, 0xFF5C3317);
                    drawShadedBox(poseStack, -0.31F, 0.0F, -0.35F + walkBL, -0.19F, 0.06F, -0.31F + walkBL, 0xFF2B1405);
                    // Tylna prawa
                    drawShadedBox(poseStack,  0.18F, 0.0F, -0.48F + walkBR,  0.32F, 0.18F, -0.34F + walkBR, 0xFF5C3317);
                    drawShadedBox(poseStack,  0.19F, 0.0F, -0.35F + walkBR,  0.31F, 0.06F, -0.31F + walkBR, 0xFF2B1405);

                    // Głowa i pysk kapibary
                    drawShadedBox(poseStack, -0.25F, 0.52F, 0.26F, 0.25F, 1.04F, 0.72F, 0xFF7D481C);
                    // Charakterystyczny ścięty pyszczek (blunt muzzle)
                    drawShadedBox(poseStack, -0.18F, 0.52F, 0.70F, 0.18F, 0.86F, 0.90F, 0xFF5A2F0F);
                    // Ciemny nos i nozdrza
                    drawShadedBox(poseStack, -0.08F, 0.74F, 0.89F, 0.08F, 0.84F, 0.92F, 0xFF1E1005);
                    drawShadedBox(poseStack, -0.05F, 0.76F, 0.915F, -0.02F, 0.80F, 0.925F, 0xFF000000);
                    drawShadedBox(poseStack,  0.02F, 0.76F, 0.915F,  0.05F, 0.80F, 0.925F, 0xFF000000);

                    // Oczka Zen (spokojna zrelaksowana kapibara)
                    boolean capyBlink = (time % 4.5F) < 0.15F;
                    if (!capyBlink) {
                        drawShadedBox(poseStack, -0.255F, 0.82F, 0.50F, -0.245F, 0.88F, 0.62F, 0xFF2B1405);
                        drawShadedBox(poseStack,  0.245F, 0.82F, 0.50F,  0.255F, 0.88F, 0.62F, 0xFF2B1405);
                    } else {
                        drawShadedBox(poseStack, -0.255F, 0.84F, 0.52F, -0.245F, 0.86F, 0.60F, 0xFF2B1405);
                        drawShadedBox(poseStack,  0.245F, 0.84F, 0.52F,  0.255F, 0.86F, 0.60F, 0xFF2B1405);
                    }
                    drawShadedBox(poseStack, -0.255F, 0.72F, 0.52F, -0.245F, 0.78F, 0.64F, 0xEEFFA0B0); // rumieniec
                    drawShadedBox(poseStack,  0.245F, 0.72F, 0.52F,  0.255F, 0.78F, 0.64F, 0xEEFFA0B0);

                    // Ruchome uszka z różowym wnętrzem
                    float earTwitch = Mth.sin(time * 2.8F) * 0.04F;
                    drawShadedBox(poseStack, -0.28F, 0.96F + earTwitch, 0.38F, -0.20F, 1.10F + earTwitch, 0.50F, 0xFF5C3317);
                    drawShadedBox(poseStack, -0.26F, 0.98F + earTwitch, 0.40F, -0.22F, 1.08F + earTwitch, 0.48F, 0xFFFFB6C1);
                    drawShadedBox(poseStack,  0.20F, 0.96F - earTwitch, 0.38F,  0.28F, 1.10F - earTwitch, 0.50F, 0xFF5C3317);
                    drawShadedBox(poseStack,  0.22F, 0.98F - earTwitch, 0.40F,  0.26F, 1.08F - earTwitch, 0.48F, 0xFFFFB6C1);

                    // Ikoniczna mandarynka Yuzu z zielonym listkiem
                    drawShadedBox(poseStack, -0.11F, 1.04F, 0.50F, 0.11F, 1.22F, 0.70F, 0xFFFF9500);
                    drawShadedBox(poseStack, -0.02F, 1.22F, 0.59F, 0.02F, 1.27F, 0.61F, 0xFF3D200A); // szypułka
                    drawShadedBox(poseStack,  0.02F, 1.23F, 0.56F, 0.10F, 1.26F, 0.64F, 0xFF2ECC71); // listek

                    // Królewska 5-ramienna złota korona z klejnotami
                    drawShadedCylinder(poseStack, 0.0F, 1.04F, 0.36F, 0.17F, 0.22F, 0.16F, 24, COLOR_GOLD, 0xFFFFE066);
                    drawShadedDisk(poseStack, 0.0F, 1.20F, 0.36F, 0.22F, 24, COLOR_GOLD, true);
                    // Igliczki korony
                    drawShadedBox(poseStack, -0.16F, 1.20F, 0.24F, -0.11F, 1.28F, 0.29F, COLOR_GOLD);
                    drawShadedBox(poseStack,  0.11F, 1.20F, 0.24F,  0.16F, 1.28F, 0.29F, COLOR_GOLD);
                    drawShadedBox(poseStack, -0.16F, 1.20F, 0.43F, -0.11F, 1.28F, 0.48F, COLOR_GOLD);
                    drawShadedBox(poseStack,  0.11F, 1.20F, 0.43F,  0.16F, 1.28F, 0.48F, COLOR_GOLD);
                    drawShadedBox(poseStack, -0.03F, 1.20F, 0.53F,  0.03F, 1.30F, 0.58F, COLOR_GOLD);
                    // Klejnoty koronne
                    drawShadedBox(poseStack, -0.15F, 1.22F, 0.25F, -0.12F, 1.26F, 0.28F, 0xFFFF1E4D); // Rubin
                    drawShadedBox(poseStack,  0.12F, 1.22F, 0.25F,  0.15F, 1.26F, 0.28F, COLOR_CYAN); // Szafir
                    drawShadedBox(poseStack, -0.02F, 1.24F, 0.54F,  0.02F, 1.28F, 0.57F, 0xFF2ECC71); // Szmaragd

                    // Królewska aksamitna peleryna z gronostajowym kołnierzem
                    drawShadedBox(poseStack, -0.37F, 0.24F, -0.59F, 0.37F, 0.78F, -0.53F, 0xFF9E0B2B); // tył peleryny
                    drawShadedBox(poseStack, -0.37F, 0.74F, -0.58F, 0.37F, 0.82F,  0.24F, 0xFF9E0B2B); // ramiona
                    // Puszyste białe futerko gronostajowe z czarnymi cętkami
                    drawShadedBox(poseStack, -0.38F, 0.75F, 0.16F, 0.38F, 0.84F, 0.32F, 0xFFF5F5F5);
                    drawShadedBox(poseStack, -0.22F, 0.78F, 0.315F, -0.18F, 0.81F, 0.325F, 0xFF1A1A1A);
                    drawShadedBox(poseStack,  0.18F, 0.78F, 0.315F,  0.22F, 0.81F, 0.325F, 0xFF1A1A1A);
                    // Złota klamra broszy
                    drawShadedBox(poseStack, -0.06F, 0.76F, 0.30F, 0.06F, 0.83F, 0.34F, COLOR_GOLD);
                } else {
                    // Cząsteczki królewskiej aury wokół korony
                    for (int k = 0; k < 4; k++) {
                        float angle = (time * 1.5F + k * (float) Math.PI / 2.0F);
                        float kx = Mth.cos(angle) * 0.28F;
                        float kz = 0.36F + Mth.sin(angle) * 0.28F;
                        float ky = 1.24F + Mth.sin(time * 3.0F + k) * 0.05F;
                        drawShadedBox(poseStack, kx - 0.015F, ky, kz - 0.015F, kx + 0.015F, ky + 0.03F, kz + 0.015F, 0xDDFFD700);
                    }
                }
            }

            case TUNG_TUNG_SAHUR -> {
                if (!translucent) {
                    // 1. Autentyczny, w 100% lity korpus indonezyjskiego bębna Kentongan z drewna tekowego
                    // Solidny cylinder: promień 0.35, wysokość 1.18 (od Y=0.36 do Y=1.54)
                    drawShadedCylinder(poseStack, 0.0F, 0.36F, 0.0F, 0.35F, 0.35F, 1.18F, 32, 0xFF8A4513, 0xFFB86A2E);
                    // Całkowicie domknięty dół i góra bębna (brak jakichkolwiek dziur czy prześwitów pod spodem!)
                    drawShadedDisk(poseStack, 0.0F, 1.54F, 0.0F, 0.35F, 32, 0xFFC97C3A, true);
                    drawShadedDisk(poseStack, 0.0F, 0.36F, 0.0F, 0.35F, 32, 0xFF6B330C, false);

                    // 2. Wyrzeźbiony podłużny otwór rezonansowy (slit) wkomponowany w strukturę drewna
                    // Drewniana fazowana ramka wokół otworu:
                    drawShadedBox(poseStack, -0.065F, 0.60F, 0.28F, 0.065F, 1.26F, 0.36F, 0xFF542507);
                    // Głęboki cień rezonansowy wnętrza kłody:
                    drawShadedBox(poseStack, -0.035F, 0.63F, 0.26F, 0.035F, 1.23F, 0.355F, 0xFF120702);

                    // 3. Złocone mosiężne obręcze wzmacniające bęben ze złotymi nitami
                    drawRing(poseStack.last().pose(), 0.44F, 0.365F, 0.03F, 32, COLOR_GOLD, 255);
                    drawRing(poseStack.last().pose(), 1.48F, 0.365F, 0.03F, 32, COLOR_GOLD, 255);
                    drawShadedBox(poseStack, -0.03F, 0.42F, 0.35F, 0.03F, 0.46F, 0.38F, 0xFFFFE066);
                    drawShadedBox(poseStack, -0.03F, 1.46F, 0.35F, 0.03F, 1.50F, 0.38F, 0xFFFFE066);

                    // 4. Tradycyjne nakrycie głowy: czapka Songkok / Peci z haftowanym złotym paskiem i zwieńczeniem
                    drawShadedCylinder(poseStack, 0.0F, 1.54F, 0.0F, 0.31F, 0.28F, 0.26F, 28, 0xFF141414, 0xFF1C1C1C);
                    drawShadedDisk(poseStack, 0.0F, 1.80F, 0.0F, 0.28F, 28, 0xFF121212, true);
                    drawRing(poseStack.last().pose(), 1.55F, 0.315F, 0.02F, 28, COLOR_GOLD, 255);
                    drawShadedBox(poseStack, -0.02F, 1.80F, -0.02F, 0.02F, 1.83F, 0.02F, COLOR_GOLD); // złoty guziczek

                    // 5. Duże, ekspresyjne kreskówkowe oczy idealnie osadzone na łuku korpusu
                    boolean blinking = (time % 4.0F) < 0.16F;
                    if (!blinking) {
                        // Białka oczu
                        drawShadedBox(poseStack, -0.17F, 1.29F, 0.28F, -0.04F, 1.45F, 0.36F, 0xFFFFFFFF);
                        drawShadedBox(poseStack,  0.04F, 1.29F, 0.28F,  0.17F, 1.45F, 0.36F, 0xFFFFFFFF);
                        float eyeLook = Mth.sin(time * 3.0F) * 0.015F;
                        // Źrenice
                        drawShadedBox(poseStack, -0.13F + eyeLook, 1.32F, 0.35F, -0.07F + eyeLook, 1.42F, 0.38F, 0xFF0A1018);
                        drawShadedBox(poseStack,  0.07F + eyeLook, 1.32F, 0.35F,  0.13F + eyeLook, 1.42F, 0.38F, 0xFF0A1018);
                        // Błysk w oku (Cyber Cyan reflektory)
                        drawShadedBox(poseStack, -0.11F + eyeLook, 1.37F, 0.375F, -0.08F + eyeLook, 1.40F, 0.39F, COLOR_CYAN);
                        drawShadedBox(poseStack,  0.09F + eyeLook, 1.37F, 0.375F,  0.12F + eyeLook, 1.40F, 0.39F, COLOR_CYAN);
                        // Iskra białego światła
                        drawShadedBox(poseStack, -0.12F + eyeLook, 1.38F, 0.385F, -0.10F + eyeLook, 1.40F, 0.395F, 0xFFFFFFFF);
                        drawShadedBox(poseStack,  0.08F + eyeLook, 1.38F, 0.385F,  0.10F + eyeLook, 1.40F, 0.395F, 0xFFFFFFFF);
                    } else {
                        // Mrugnięcie powiekami
                        drawShadedBox(poseStack, -0.17F, 1.35F, 0.28F, -0.04F, 1.39F, 0.36F, 0xFF3D1B07);
                        drawShadedBox(poseStack,  0.04F, 1.35F, 0.28F,  0.17F, 1.39F, 0.36F, 0xFF3D1B07);
                    }

                    // 6. Wesoły wyrzeźbiony uśmiech z ciemnym podcięciem
                    drawShadedBox(poseStack, -0.07F, 1.17F, 0.30F, 0.07F, 1.21F, 0.35F, 0xFF421603);
                    drawShadedBox(poseStack, -0.04F, 1.175F, 0.32F, 0.04F, 1.195F, 0.355F, 0xFFFFE0D0); // ząbek

                    // 7. Drewniany uchwyt z tyłu bębna
                    drawShadedBox(poseStack, -0.05F, 0.85F, -0.42F, 0.05F, 1.15F, -0.32F, 0xFF542507);

                    // 8. Bambusowe nogi wpuszczone w głąb bębna (Y=0.07 do Y=0.42 - ZERO PRZEŚWITÓW!)
                    float legSwingFactor = player.onGround() ? 1.0F : 0.15F;
                    float leftLegZ = walkSwing * 0.28F * legSwingFactor;
                    float rightLegZ = -walkSwing * 0.28F * legSwingFactor;
                    // Lewa noga
                    drawShadedBox(poseStack, -0.20F, 0.07F, leftLegZ - 0.06F, -0.08F, 0.42F, leftLegZ + 0.06F, 0xFFA65E26);
                    drawShadedBox(poseStack, -0.21F, 0.21F, leftLegZ - 0.07F, -0.07F, 0.24F, leftLegZ + 0.07F, 0xFF7A3E15); // kolanko
                    // Prawa noga
                    drawShadedBox(poseStack,  0.08F, 0.07F, rightLegZ - 0.06F,  0.20F, 0.42F, rightLegZ + 0.06F, 0xFFA65E26);
                    drawShadedBox(poseStack,  0.07F, 0.21F, rightLegZ - 0.07F,  0.21F, 0.24F, rightLegZ + 0.07F, 0xFF7A3E15); // kolanko

                    // Tradycyjne sandały (Bakiak) z drewnianą podeszwą i czarnym paskiem
                    drawShadedBox(poseStack, -0.23F, 0.0F, leftLegZ - 0.10F, -0.06F, 0.07F, leftLegZ + 0.12F, 0xFF4A2508);
                    drawShadedBox(poseStack, -0.18F, 0.07F, leftLegZ - 0.02F, -0.10F, 0.09F, leftLegZ + 0.04F, 0xFF1A1A1A); // pasek
                    drawShadedBox(poseStack,  0.06F, 0.0F, rightLegZ - 0.10F,  0.23F, 0.07F, rightLegZ + 0.12F, 0xFF4A2508);
                    drawShadedBox(poseStack,  0.10F, 0.07F, rightLegZ - 0.02F,  0.18F, 0.09F, rightLegZ + 0.04F, 0xFF1A1A1A); // pasek

                    // 9. Lewa ręka oparta na boku bębna
                    drawShadedBox(poseStack, -0.38F, 0.90F, -0.04F, -0.33F, 1.15F, 0.06F, 0xFF944D1E);
                    drawShadedBox(poseStack, -0.37F, 0.86F,  0.02F, -0.32F, 0.92F, 0.12F, 0xFFB86A2E);

                    // 10. Prawa ręka z wyrzeźbioną pałką (Pemukul Kentongan) uderzająca rytmicznie w szczelinę
                    float drumPhase = Mth.sin(time * 9.0F);
                    poseStack.pushPose();
                    poseStack.translate(0.34F, 1.05F, 0.08F);
                    // Ramię
                    drawShadedBox(poseStack, -0.03F, -0.15F, -0.03F, 0.05F, 0.08F, 0.05F, 0xFF944D1E);
                    // Rotacja przedramienia i pałki
                    poseStack.translate(0.02F, -0.15F, 0.02F);
                    poseStack.mulPose(Axis.XP.rotationDegrees(25.0F + drumPhase * 30.0F));
                    poseStack.mulPose(Axis.YP.rotationDegrees(-15.0F));
                    // Rękojeść pałki (smukły drewniany trzonek)
                    drawShadedBox(poseStack, -0.02F, -0.35F, -0.02F, 0.02F, 0.05F, 0.02F, 0xFFD4A373);
                    // Zaokrąglona drewniana głowica bijaka uderzająca w bęben
                    drawShadedBox(poseStack, -0.045F, -0.42F, -0.045F, 0.045F, -0.35F, 0.045F, 0xFF5C2C0A);
                    drawRing(poseStack.last().pose(), -0.38F, 0.05F, 0.015F, 16, COLOR_GOLD, 255);
                    poseStack.popPose();
                } else {
                    // Pierścienie fali dźwiękowej (Sahur Resonance Rings)
                    float drumPhase = Mth.sin(time * 9.0F);
                    if (drumPhase > 0.65F) {
                        float sProgress = (drumPhase - 0.65F) / 0.35F;
                        drawRing(poseStack.last().pose(), 0.95F, 0.35F + sProgress * 0.60F, 0.04F, 36, COLOR_CYAN, (int) ((1.0F - sProgress) * 240));
                        drawRing(poseStack.last().pose(), 0.95F, 0.32F + sProgress * 0.38F, 0.025F, 36, COLOR_GOLD, (int) ((1.0F - sProgress) * 200));
                    }
                }
            }

            case BALLERINA_CAPUCCINA -> {
                poseStack.pushPose();
                // Płynny, zmysłowy obrót baletnicy (Pirouette z harmonicznym easingiem)
                float spinAngle = (time * 160.0F) + Mth.sin(time * 3.5F) * 16.0F;
                poseStack.mulPose(Axis.YP.rotationDegrees(spinAngle));

                // Baletowy oddech i podskok (Relevé & Breathe)
                float breatheY = Mth.sin(time * 4.2F) * 0.022F;
                poseStack.translate(0.0F, breatheY, 0.0F);

                if (!translucent) {
                    // 1. Baletowe nogi w jedwabistych rajstopach z wiązaniami
                    drawBalletLegs(poseStack, walkSwing, time);

                    // 2. Wcięty gorset baletnicy (Bodice) z perłowym połyskiem i złotym sznurowaniem
                    drawShadedCylinder(poseStack, 0.0F, 0.85F, 0.0F, 0.19F, 0.15F, 0.22F, 24, 0xFFFFB6D9, 0xFFE0AAFF);
                    drawShadedCylinder(poseStack, 0.0F, 1.07F, 0.0F, 0.15F, 0.22F, 0.27F, 24, 0xFFE0AAFF, 0xFFFAF0F8);
                    drawShadedBox(poseStack, -0.02F, 0.90F, 0.16F, 0.02F, 1.25F, 0.18F, COLOR_GOLD);
                    drawRing(poseStack.last().pose(), 1.05F, 0.16F, 0.015F, 24, COLOR_GOLD, 240);

                    // 3. Porcelanowy spodek (Saucer) pod filiżanką ze złotym brzegiem
                    drawShadedDisk(poseStack, 0.0F, 1.34F, 0.0F, 0.44F, 32, 0xFFFFFFFF, true);
                    drawRing(poseStack.last().pose(), 1.345F, 0.44F, 0.03F, 32, COLOR_GOLD, 255);

                    // 4. Głowa: Porcelanowa filiżanka Cappuccino ze złotym uchwytem i rantem
                    drawShadedCylinder(poseStack, 0.0F, 1.35F, 0.0F, 0.26F, 0.40F, 0.42F, 36, 0xFFFAF5FF, 0xFFFFFFFF);
                    drawRing(poseStack.last().pose(), 1.77F, 0.405F, 0.025F, 36, COLOR_GOLD, 255);
                    drawCupHandle(poseStack, 0.38F, 1.45F, 1.68F, 0.18F, 0.022F, COLOR_GOLD);

                    // 5. Kawa i kremowa pianka z Latte Art Heart (serduszko baristy)
                    drawShadedDisk(poseStack, 0.0F, 1.76F, 0.0F, 0.39F, 32, 0xFF542E15, true);
                    drawShadedDisk(poseStack, 0.0F, 1.765F, 0.0F, 0.36F, 28, 0xFFC98E5C, true);
                    drawShadedDisk(poseStack, -0.05F, 1.77F, 0.04F, 0.06F, 16, 0xFFFFF5E4, true);
                    drawShadedDisk(poseStack,  0.05F, 1.77F, 0.04F, 0.06F, 16, 0xFFFFF5E4, true);
                    drawShadedBox(poseStack, -0.06F, 1.768F, -0.06F, 0.06F, 1.772F, 0.04F, 0xFFFFF5E4);

                    // 6. Puszysta góra bitej śmietany (Whipped Cream Swirl)
                    drawShadedCylinder(poseStack, 0.0F, 1.77F, 0.0F, 0.22F, 0.16F, 0.07F, 24, 0xFFFFFDF8, 0xFFFFFFFF);
                    drawShadedDisk(poseStack, 0.0F, 1.84F, 0.0F, 0.16F, 20, 0xFFFFFFFF, true);
                    drawShadedCylinder(poseStack, 0.01F, 1.84F, 0.01F, 0.15F, 0.09F, 0.06F, 20, 0xFFFFFDF8, 0xFFFFFFFF);
                    drawShadedDisk(poseStack, 0.01F, 1.90F, 0.01F, 0.09F, 16, 0xFFFFFFFF, true);
                    drawShadedCylinder(poseStack, 0.0F, 1.90F, -0.01F, 0.08F, 0.03F, 0.05F, 16, 0xFFFFFDF8, 0xFFFFFFFF);
                    // Drobinki kakao/cynamonu
                    drawShadedBox(poseStack, -0.08F, 1.85F, 0.06F, -0.06F, 1.86F, 0.08F, 0xFF6E350C);
                    drawShadedBox(poseStack,  0.07F, 1.86F, -0.05F, 0.09F, 1.87F, -0.03F, 0xFF6E350C);
                    drawShadedBox(poseStack, -0.03F, 1.91F, -0.05F, -0.01F, 1.92F, -0.03F, 0xFF6E350C);

                    // Błyszcząca soczysta wisienka Amarena z szypułką
                    drawShadedBox(poseStack, -0.045F, 1.95F, -0.045F, 0.045F, 2.04F, 0.045F, 0xFFFF1E4D);
                    drawShadedBox(poseStack, -0.025F, 2.01F, 0.025F, -0.005F, 2.03F, 0.046F, 0xFFFFFFFF);
                    drawShadedBox(poseStack, 0.0F, 2.04F, -0.01F, 0.02F, 2.11F, 0.01F, 0xFF2ECC71);
                    drawShadedBox(poseStack, 0.02F, 2.09F, -0.01F, 0.06F, 2.13F, 0.01F, 0xFF2ECC71);

                    // 7. Słodka buźka anime (Kawaii Expression) na ściance filiżanki
                    boolean cupBlink = (time % 3.6F) < 0.16F;
                    if (!cupBlink) {
                        drawShadedDisk(poseStack, -0.14F, 1.58F, 0.38F, 0.045F, 16, 0xFF351C0C, true);
                        drawShadedDisk(poseStack,  0.14F, 1.58F, 0.38F, 0.045F, 16, 0xFF351C0C, true);
                        drawShadedDisk(poseStack, -0.125F, 1.60F, 0.382F, 0.018F, 12, 0xFFFFFFFF, true);
                        drawShadedDisk(poseStack,  0.155F, 1.60F, 0.382F, 0.018F, 12, 0xFFFFFFFF, true);
                    } else {
                        drawShadedBox(poseStack, -0.18F, 1.58F, 0.38F, -0.10F, 1.60F, 0.382F, 0xFF351C0C);
                        drawShadedBox(poseStack,  0.10F, 1.58F, 0.38F,  0.18F, 1.60F, 0.382F, 0xFF351C0C);
                    }
                    drawShadedDisk(poseStack, -0.22F, 1.51F, 0.36F, 0.04F, 12, 0xEEFF85A2, true);
                    drawShadedDisk(poseStack,  0.22F, 1.51F, 0.36F, 0.04F, 12, 0xEEFF85A2, true);
                    drawShadedBox(poseStack, -0.04F, 1.48F, 0.385F, 0.04F, 1.50F, 0.388F, 0xFFC2410C);

                    // 8. Klasyczne baletowe ramiona w 5. pozycji (En Haut)
                    drawBalletArm(poseStack, true, time);
                    drawBalletArm(poseStack, false, time);
                } else {
                    // 9. Kaskadowa, 3-warstwowa spódniczka baletowa Tutu z falowaniem tiulu
                    float tutuWave = Mth.sin(time * 6.0F) * 0.028F;
                    // Warstwa 1: Rozkloszowane pudroworóżowe falbany tiulowe
                    drawShadedCylinder(poseStack, 0.0F, 0.74F + tutuWave, 0.0F, 0.54F, 0.20F, 0.14F, 32, 0xD0FFB6D9, 0xE5FFAFCC);
                    drawShadedDisk(poseStack, 0.0F, 0.88F + tutuWave, 0.0F, 0.22F, 24, 0xD0FFB6D9, true);
                    // Warstwa 2: Rozłożysty liliowy tiul (Lilac Rose)
                    drawShadedCylinder(poseStack, 0.0F, 0.62F + tutuWave * 0.8F, 0.0F, 0.68F, 0.38F, 0.16F, 32, 0xCCE0AAFF, 0xD5D899FF);
                    // Warstwa 3: Kryształowa lawendowa wstęga
                    drawShadedCylinder(poseStack, 0.0F, 0.50F + tutuWave * 0.6F, 0.0F, 0.78F, 0.52F, 0.16F, 32, 0xC0C77DFF, 0xD09955FF);

                    // 10. Aromat kawy i kłęby pary (Steam Hearts)
                    for (int s = 0; s < 5; s++) {
                        float st = (time * 0.9F + s * 0.45F) % 2.0F;
                        float sy = 2.02F + st * 0.40F;
                        float spiralAngle = time * 3.0F + s * 1.6F;
                        float sx = Mth.sin(spiralAngle) * (0.05F + st * 0.08F);
                        float sz = Mth.cos(spiralAngle) * (0.05F + st * 0.08F);
                        int sa = (int) ((1.0F - st / 2.0F) * 190);
                        drawShadedDisk(poseStack, sx, sy, sz, 0.025F + st * 0.02F, 10, (sa << 24) | 0xFFF5E8, true);
                    }

                    // 11. Iskry brokatu (Fairy Dust Sparkles) wokół piruetu
                    for (int p = 0; p < 6; p++) {
                        float pAngle = (time * 2.2F + p * (float) Math.PI / 3.0F);
                        float pr = 0.85F + Mth.sin(time * 4.0F + p) * 0.10F;
                        float py = 0.55F + Mth.cos(time * 3.0F + p * 1.5F) * 0.20F;
                        int pAlpha = (int) ((Mth.sin(time * 5.0F + p * 2.0F) * 0.5F + 0.5F) * 220);
                        drawShadedBox(poseStack, Mth.cos(pAngle) * pr - 0.015F, py, Mth.sin(pAngle) * pr - 0.015F,
                                                 Mth.cos(pAngle) * pr + 0.015F, py + 0.03F, Mth.sin(pAngle) * pr + 0.015F,
                                                 (pAlpha << 24) | ((p % 2 == 0) ? 0xFFC77D : 0x00F0FF));
                    }
                }
                poseStack.popPose();
            }

            case CYBER_MECH -> {
                if (!translucent) {
                    // Masywny pancerz mecha z tytanu i szkła obsydianowego
                    drawShadedBox(poseStack, -0.42F, 0.65F, -0.26F, 0.42F, 1.48F, 0.26F, 0xFF181D26);
                    drawShadedBox(poseStack, -0.36F, 0.70F, 0.22F, 0.36F, 1.42F, 0.29F, 0xFF242C3D); // Płyta reaktora z tyłu
                    drawShadedBox(poseStack, -0.32F, 0.80F, -0.29F, 0.32F, 1.35F, -0.24F, 0xFF2A3448); // Płyta piersiowa

                    // Bojowy hełm szturmowy
                    drawShadedBox(poseStack, -0.24F, 1.50F, -0.22F, 0.24F, 1.92F, 0.22F, 0xFF1E2636);
                    drawShadedBox(poseStack, -0.20F, 1.86F, -0.24F, 0.20F, 1.94F, 0.20F, 0xFF35425C); // Daszek hełmu

                    // Naramienniki bojowe z dyszami chłodzącymi
                    drawShadedBox(poseStack, -0.56F, 1.25F, -0.18F, -0.40F, 1.54F, 0.18F, 0xFF2B364A);
                    drawShadedBox(poseStack,  0.40F, 1.25F, -0.18F,  0.56F, 1.54F, 0.18F, 0xFF2B364A);
                    drawShadedBox(poseStack, -0.58F, 1.32F, -0.06F, -0.42F, 1.48F, 0.06F, 0xFF151B24);
                    drawShadedBox(poseStack,  0.42F, 1.32F, -0.06F,  0.58F, 1.48F, 0.06F, 0xFF151B24);

                    // Opancerzone ramiona robotyczne
                    float armSwing = walkSwing * 0.30F;
                    drawShadedBox(poseStack, -0.52F, 0.60F, -0.10F + armSwing, -0.42F, 1.25F, 0.10F + armSwing, 0xFF202735);
                    drawShadedBox(poseStack,  0.42F, 0.60F, -0.10F - armSwing,  0.52F, 1.25F, 0.10F - armSwing, 0xFF202735);
                    // Rękawice bojowe
                    drawShadedBox(poseStack, -0.54F, 0.46F, -0.12F + armSwing, -0.40F, 0.62F, 0.12F + armSwing, 0xFF384660);
                    drawShadedBox(poseStack,  0.40F, 0.46F, -0.12F - armSwing,  0.54F, 0.62F, 0.12F - armSwing, 0xFF384660);

                    // Opancerzone bioniczne nogi
                    float legSwing = walkSwing * 0.32F;
                    drawShadedBox(poseStack, -0.34F, 0.08F, -0.14F + legSwing, -0.14F, 0.66F, 0.14F + legSwing, 0xFF222B3B);
                    drawShadedBox(poseStack,  0.14F, 0.08F, -0.14F - legSwing,  0.34F, 0.66F, 0.14F - legSwing, 0xFF222B3B);
                    // Wzmocnione stopy gąsienicowe
                    drawShadedBox(poseStack, -0.36F, 0.0F, -0.22F + legSwing, -0.12F, 0.10F, 0.18F + legSwing, 0xFF141924);
                    drawShadedBox(poseStack,  0.12F, 0.0F, -0.22F - legSwing,  0.36F, 0.10F, 0.18F - legSwing, 0xFF141924);

                    // Podwójne turbiny odrzutowe na plecach
                    drawShadedCylinder(poseStack, -0.22F, 0.90F, 0.28F, 0.09F, 0.11F, 0.42F, 16, 0xFF1A212E, 0xFF2E3B52);
                    drawShadedCylinder(poseStack,  0.22F, 0.90F, 0.28F, 0.09F, 0.11F, 0.42F, 16, 0xFF1A212E, 0xFF2E3B52);
                } else {
                    // Świecący holograficzny wizjer HUD
                    drawShadedBox(poseStack, -0.21F, 1.66F, 0.19F, 0.21F, 1.76F, 0.24F, 0xEE00F0FF);

                    // Pulsujący reaktor fuzyjny w klatce piersiowej
                    float pulse = (Mth.sin(time * 6.0F) * 0.5F + 0.5F);
                    int coreAlpha = (int) (180 + pulse * 75);
                    drawShadedBox(poseStack, -0.10F, 0.98F, -0.30F, 0.10F, 1.18F, -0.28F, (coreAlpha << 24) | 0x00F0FF);

                    // Płomienie plazmowe z silników odrzutowych na plecach
                    float thrusterFlicker = 0.8F + Mth.sin(time * 30.0F) * 0.2F;
                    drawShadedCylinder(poseStack, -0.22F, 0.60F - (0.30F * thrusterFlicker), 0.28F, 0.02F, 0.09F, 0.32F * thrusterFlicker, 16, 0xCCFF5500, 0xEE00F0FF);
                    drawShadedCylinder(poseStack,  0.22F, 0.60F - (0.30F * thrusterFlicker), 0.28F, 0.02F, 0.09F, 0.32F * thrusterFlicker, 16, 0xCCFF5500, 0xEE00F0FF);

                    // Orbitalne holograficzne pierścienie namierzania
                    poseStack.pushPose();
                    poseStack.mulPose(Axis.YP.rotationDegrees(time * 110.0F));
                    drawRing(poseStack.last().pose(), 1.08F, 0.76F, 0.05F, 36, COLOR_CYAN, 210);
                    drawRing(poseStack.last().pose(), 1.08F, 0.70F, 0.02F, 36, COLOR_LILAC, 180);
                    poseStack.popPose();
                }
            }

            case HOLOGRAM_AVATAR -> {
                if (!translucent) {
                    // Hologram to projekcja cyfrowa bez stałej bryły w buforze głębokości
                } else {
                    // Chromatyczna aberracja glitcha holograficznego
                    float scanline = (time % 2.0F) / 2.0F;
                    float glitchX = (Mth.sin(time * 18.0F) > 0.92F) ? 0.035F : 0.0F;

                    // 1. Rdzeń cyjanowy hologramu
                    poseStack.pushPose();
                    poseStack.translate(-0.015F + glitchX, 0.0F, 0.0F);
                    drawShadedBox(poseStack, -0.30F, 0.0F, -0.20F, 0.30F, 0.88F, 0.20F, 0x6600F0FF);
                    drawShadedBox(poseStack, -0.20F, 0.90F, -0.18F, 0.20F, 1.30F, 0.18F, 0x7700F0FF);
                    drawShadedBox(poseStack, -0.42F, 0.35F, -0.12F, -0.32F, 0.88F, 0.12F, 0x5500F0FF);
                    drawShadedBox(poseStack,  0.32F, 0.35F, -0.12F,  0.42F, 0.88F, 0.12F, 0x5500F0FF);
                    poseStack.popPose();

                    // 2. Widmo różowo-lawendowe (Magenta Chromatic Shift)
                    poseStack.pushPose();
                    poseStack.translate(0.015F - glitchX, 0.0F, 0.0F);
                    drawShadedBox(poseStack, -0.30F, 0.0F, -0.20F, 0.30F, 0.88F, 0.20F, 0x55C77DFF);
                    drawShadedBox(poseStack, -0.20F, 0.90F, -0.18F, 0.20F, 1.30F, 0.18F, 0x66C77DFF);
                    poseStack.popPose();

                    // 3. Poziome pierścienie telemetryczne skanera
                    for (int i = 0; i < 7; i++) {
                        float yOff = (i * 0.26F) + ((time * 0.5F) % 0.26F);
                        drawRing(poseStack.last().pose(), yOff, 0.44F, 0.035F, 32, (i % 2 == 0) ? COLOR_CYAN : COLOR_LILAC, 175);
                    }

                    // Promień lasera skanującego
                    float scanY = scanline * 1.80F;
                    drawRing(poseStack.last().pose(), scanY, 0.48F, 0.05F, 40, COLOR_CYAN, 245);
                    drawShadedBox(poseStack, -0.35F, scanY - 0.015F, -0.25F, 0.35F, scanY + 0.015F, 0.25F, 0xAA00FFFF);

                    // 4. Obracające się pierścienie telemetryczne z bitami danych
                    poseStack.pushPose();
                    poseStack.mulPose(Axis.YP.rotationDegrees(time * 75.0F));
                    drawRing(poseStack.last().pose(), 0.95F, 0.65F, 0.03F, 32, COLOR_CYAN, 190);
                    for (int c = 0; c < 4; c++) {
                        float angle = c * (float) Math.PI / 2.0F;
                        float cx = Mth.cos(angle) * 0.65F;
                        float cz = Mth.sin(angle) * 0.65F;
                        drawShadedBox(poseStack, cx - 0.025F, 0.93F, cz - 0.025F, cx + 0.025F, 0.98F, cz + 0.025F, 0xEE00F0FF);
                    }
                    poseStack.popPose();
                }
            }

            default -> {}
        }
    }

    // Geometry Helpers for 3D Disguises
    private static void drawDisk(PoseStack ps, float x, float y, float z, float radius, int segs, int color) {
        drawShadedDisk(ps, x, y, z, radius, segs, color, true);
    }

    private static void drawCupHandle(PoseStack ps, float xBase, float yBottom, float yTop, float xOut, float thickness, int color) {
        int segs = 6;
        for (int i = 0; i < segs; i++) {
            float t0 = (float) i / segs;
            float t1 = (float) (i + 1) / segs;
            float y0 = Mth.lerp(t0, yBottom, yTop);
            float y1 = Mth.lerp(t1, yBottom, yTop);
            float arc0 = Mth.sin(t0 * (float) Math.PI) * xOut;
            float arc1 = Mth.sin(t1 * (float) Math.PI) * xOut;
            drawShadedBox(ps, xBase + arc0 - thickness, y0 - thickness, -thickness,
                              xBase + arc1 + thickness, y1 + thickness, thickness, color);
        }
    }

    private static void drawBalletArm(PoseStack ps, boolean left, float time) {
        float sign = left ? -1.0F : 1.0F;
        float armFlutter = Mth.sin(time * 3.0F + (left ? 0.0F : 0.8F)) * 0.015F;
        drawShadedBox(ps, sign * 0.18F, 1.18F, -0.04F, sign * 0.28F, 1.34F + armFlutter, 0.04F, 0xFFF7EFF5);
        drawShadedBox(ps, sign * 0.26F, 1.32F + armFlutter, -0.035F, sign * 0.35F, 1.50F + armFlutter * 1.5F, 0.035F, 0xFFF7EFF5);
        drawShadedBox(ps, sign * 0.22F, 1.48F + armFlutter * 1.5F, -0.03F, sign * 0.33F, 1.68F + armFlutter * 1.8F, 0.03F, 0xFFF7EFF5);
        drawShadedBox(ps, sign * 0.10F, 1.66F + armFlutter * 1.8F, -0.025F, sign * 0.23F, 1.80F + armFlutter * 2.0F, 0.025F, 0xFFF7EFF5);
        drawShadedBox(ps, sign * 0.04F, 1.78F + armFlutter * 2.0F, 0.0F, sign * 0.12F, 1.85F + armFlutter * 2.2F, 0.04F, 0xFFFFE8EF);
        drawShadedBox(ps, sign * 0.19F, 1.65F, -0.04F, sign * 0.26F, 1.69F, 0.04F, 0xFFFF99CC);
    }

    private static void drawBalletLegs(PoseStack ps, float walkSwing, float time) {
        float lSwing = walkSwing * 0.25F;
        float rSwing = -walkSwing * 0.25F;

        drawShadedCylinder(ps, -0.11F, 0.08F, lSwing, 0.052F, 0.075F, 0.64F, 16, 0xFFFFF5F8, 0xFFF2E6EF);
        drawShadedCylinder(ps,  0.11F, 0.08F, rSwing, 0.052F, 0.075F, 0.64F, 16, 0xFFFFF5F8, 0xFFF2E6EF);

        for (int r = 0; r < 3; r++) {
            float ry = 0.16F + r * 0.10F;
            drawRing(ps.last().pose(), ry, 0.065F, 0.015F, 16, 0xFFFF99CC, 220);
        }

        drawShadedBox(ps, -0.15F, 0.0F, lSwing - 0.06F, -0.07F, 0.09F, lSwing + 0.06F, 0xFFFF99CC);
        drawShadedBox(ps, -0.14F, 0.0F, lSwing + 0.05F, -0.08F, 0.05F, lSwing + 0.09F, 0xFFFFB3D9);
        drawShadedBox(ps,  0.07F, 0.0F, rSwing - 0.06F,  0.15F, 0.09F, rSwing + 0.06F, 0xFFFF99CC);
        drawShadedBox(ps,  0.08F, 0.0F, rSwing + 0.05F,  0.14F, 0.05F, rSwing + 0.09F, 0xFFFFB3D9);
    }

    // =========================================================================
    // =========================================================================
    // MODUŁ 4: 3D MOTION TRAIL / CENTRIPETAL CATMULL-ROM RIBBON
    // =========================================================================

    private static void renderMotionTrail(PoseStack poseStack, Vec3 camPos) {
        if (TRAIL_NODES.size() < 2) {
            return;
        }

        TrailNode[] rawNodes = TRAIL_NODES.toArray(new TrailNode[0]);
        if (rawNodes.length < 2) {
            return;
        }

        long now = System.currentTimeMillis();
        float time = (now % 100000L) / 1000.0F;
        int mode = getMotionTrailMode();

        poseStack.pushPose();
        // Zero-Jitter: kamera jest w punkcie (0,0,0), a współrzędne węzłów przeliczamy w double przed rzutowaniem na float
        poseStack.last().pose().identity();
        Matrix4f mat = poseStack.last().pose();

        // Pass 1: Translucent Centripetal Catmull-Rom Ribbon Mesh (TRIANGLE_STRIP)
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        int totalSegments = rawNodes.length - 1;
        int subSteps = 4; // 4 pod-kroki interpolacji na segment węzła dla 144-240Hz płynności

        for (int i = 0; i < totalSegments; i++) {
            TrailNode n0 = rawNodes[Math.max(0, i - 1)];
            TrailNode n1 = rawNodes[i];
            TrailNode n2 = rawNodes[i + 1];
            TrailNode n3 = rawNodes[Math.min(rawNodes.length - 1, i + 2)];

            Vec3 p0 = n0.pos(), p1 = n1.pos(), p2 = n2.pos(), p3 = n3.pos();

            // Centripetal knot parameterization (alpha = 0.5 zapobiega samoprzecięciom i pętlom)
            double dt0 = Math.max(0.001D, Math.sqrt(p0.distanceTo(p1)));
            double dt1 = Math.max(0.001D, Math.sqrt(p1.distanceTo(p2)));
            double dt2 = Math.max(0.001D, Math.sqrt(p2.distanceTo(p3)));

            double t0 = 0.0D;
            double t1 = t0 + dt0;
            double t2 = t1 + dt1;
            double t3 = t2 + dt2;

            for (int s = 0; s < subSteps; s++) {
                double u = (double) s / (double) subSteps;
                double t = t1 + u * (t2 - t1);

                // Algorytm piramidy Barry'ego i Goldmana dla Centripetal Catmull-Rom
                Vec3 a1 = p0.scale((t1 - t) / (t1 - t0)).add(p1.scale((t - t0) / (t1 - t0)));
                Vec3 a2 = p1.scale((t2 - t) / (t2 - t1)).add(p2.scale((t - t1) / (t2 - t1)));
                Vec3 a3 = p2.scale((t3 - t) / (t3 - t2)).add(p3.scale((t - t2) / (t3 - t2)));

                Vec3 b1 = a1.scale((t2 - t) / (t2 - t0)).add(a2.scale((t - t0) / (t2 - t0)));
                Vec3 b2 = a2.scale((t3 - t) / (t3 - t1)).add(a3.scale((t - t1) / (t3 - t1)));

                Vec3 pt = b1.scale((t2 - t) / (t2 - t1)).add(b2.scale((t - t1) / (t2 - t1)));

                float h = Mth.lerp((float) u, n1.height(), n2.height());
                float ts = Mth.lerp((float) u, (float) n1.timestamp(), (float) n2.timestamp());
                float ageRatio = 1.0F - Math.min(1.0F, (now - (long) ts) / 900.0F);

                float quadAlpha = (float) Math.pow(ageRatio, 1.35D);
                // Profil zwężania wstęgi: w(tau) = w0 * sin^0.35(pi * tau) * tau
                float taper = (float) Math.pow(Math.sin(ageRatio * Math.PI), 0.35D) * ageRatio;
                float ribbonHeight = h * Math.max(0.14F, taper * 0.95F);

                int alphaBottom = (int) (quadAlpha * 205.0F);
                int alphaTop = (int) (quadAlpha * 115.0F);

                float rx = (float) (pt.x - camPos.x);
                float ry = (float) (pt.y - camPos.y);
                float rz = (float) (pt.z - camPos.z);

                float globalProgress = ((float) i + (float) u) / (float) totalSegments;
                int rBot, gBot, bBot, rTop, gTop, bTop;
                switch (mode) {
                    case 1 -> { // Chroma Rainbow
                        float hue = (time * 0.35F + globalProgress) % 1.0F;
                        int rgb = Mth.hsvToRgb(hue, 0.85F, 1.0F);
                        rBot = (rgb >> 16) & 0xFF; gBot = (rgb >> 8) & 0xFF; bBot = rgb & 0xFF;
                        int rgbTop = Mth.hsvToRgb((hue + 0.15F) % 1.0F, 0.70F, 1.0F);
                        rTop = (rgbTop >> 16) & 0xFF; gTop = (rgbTop >> 8) & 0xFF; bTop = rgbTop & 0xFF;
                    }
                    case 2 -> { // Sunset Gold
                        rBot = 255; gBot = 60; bBot = 140; // Coral Hot Pink
                        rTop = 255; gTop = 210; bTop = 30; // 24k Sun Gold
                    }
                    case 3 -> { // Glacial Ice Blue
                        rBot = 0; gBot = 160; bBot = 255;
                        rTop = 180; gTop = 245; bTop = 255;
                    }
                    case 4 -> { // Matrix Emerald
                        rBot = 0; gBot = 255; bBot = 120;
                        rTop = 0; gTop = 240; bTop = 255;
                    }
                    default -> { // Cyber Lilac & Cyan (Default)
                        rBot = 0; gBot = 240; bBot = 255;
                        rTop = 199; gTop = 125; bTop = 255;
                    }
                }

                buf.addVertex(mat, rx, ry + 0.04F, rz).setColor(rBot, gBot, bBot, alphaBottom);
                buf.addVertex(mat, rx, ry + ribbonHeight, rz).setColor(rTop, gTop, bTop, alphaTop);
            }
        }

        TrailNode lastNode = rawNodes[rawNodes.length - 1];
        float lastAge = 1.0F - Math.min(1.0F, (now - lastNode.timestamp()) / 900.0F);
        float lrx = (float) (lastNode.pos().x - camPos.x);
        float lry = (float) (lastNode.pos().y - camPos.y);
        float lrz = (float) (lastNode.pos().z - camPos.z);
        buf.addVertex(mat, lrx, lry + 0.04F, lrz).setColor(0, 240, 255, (int)(lastAge * 190.0F));
        buf.addVertex(mat, lrx, lry + lastNode.height() * 0.88F, lrz).setColor(199, 125, 255, (int)(lastAge * 100.0F));

        MeshData mesh = buf.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
            mesh.close();
        }

        // Pass 2: Glowing Top Laser Line (Laser Crest Filament)
        BufferBuilder lineBuf = Tesselator.getInstance().begin(VertexFormat.Mode.LINE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < totalSegments; i++) {
            TrailNode n0 = rawNodes[Math.max(0, i - 1)];
            TrailNode n1 = rawNodes[i];
            TrailNode n2 = rawNodes[i + 1];
            TrailNode n3 = rawNodes[Math.min(rawNodes.length - 1, i + 2)];

            Vec3 p0 = n0.pos(), p1 = n1.pos(), p2 = n2.pos(), p3 = n3.pos();
            double dt0 = Math.max(0.001D, Math.sqrt(p0.distanceTo(p1)));
            double dt1 = Math.max(0.001D, Math.sqrt(p1.distanceTo(p2)));
            double dt2 = Math.max(0.001D, Math.sqrt(p2.distanceTo(p3)));
            double t0 = 0.0D;
            double t1 = t0 + dt0;
            double t2 = t1 + dt1;
            double t3 = t2 + dt2;

            for (int s = 0; s < subSteps; s++) {
                double u = (double) s / (double) subSteps;
                double t = t1 + u * (t2 - t1);
                Vec3 a1 = p0.scale((t1 - t) / (t1 - t0)).add(p1.scale((t - t0) / (t1 - t0)));
                Vec3 a2 = p1.scale((t2 - t) / (t2 - t1)).add(p2.scale((t - t1) / (t2 - t1)));
                Vec3 a3 = p2.scale((t3 - t) / (t3 - t2)).add(p3.scale((t - t2) / (t3 - t2)));
                Vec3 b1 = a1.scale((t2 - t) / (t2 - t0)).add(a2.scale((t - t0) / (t2 - t0)));
                Vec3 b2 = a2.scale((t3 - t) / (t3 - t1)).add(a3.scale((t - t1) / (t3 - t1)));
                Vec3 pt = b1.scale((t2 - t) / (t2 - t1)).add(b2.scale((t - t1) / (t2 - t1)));

                float h = Mth.lerp((float) u, n1.height(), n2.height());
                float ts = Mth.lerp((float) u, (float) n1.timestamp(), (float) n2.timestamp());
                float ageRatio = 1.0F - Math.min(1.0F, (now - (long) ts) / 900.0F);
                float taper = (float) Math.pow(Math.sin(ageRatio * Math.PI), 0.35D) * ageRatio;
                float ribbonHeight = h * Math.max(0.14F, taper * 0.95F);

                float rx = (float) (pt.x - camPos.x);
                float ry = (float) (pt.y - camPos.y);
                float rz = (float) (pt.z - camPos.z);
                int lineAlpha = (int) (ageRatio * 240.0F);
                lineBuf.addVertex(mat, rx, ry + ribbonHeight, rz).setColor(255, 255, 255, lineAlpha);
            }
        }
        lineBuf.addVertex(mat, lrx, lry + lastNode.height() * 0.88F, lrz).setColor(255, 255, 255, (int)(lastAge * 240.0F));

        MeshData lineMesh = lineBuf.build();
        if (lineMesh != null) {
            RenderSystem.lineWidth(2.5F);
            BufferUploader.drawWithShader(lineMesh);
            lineMesh.close();
        }

        poseStack.popPose();
    }

    // =========================================================================
    // MODUŁ 5: JUMP & LANDING CIRCLES + TARGET ESP
    // =========================================================================

    public static Vec3 snapToGround(Minecraft mc, Vec3 pos) {
        if (mc == null || mc.level == null) {
            return pos.add(0, 0.018D, 0);
        }
        try {
            BlockPos feetPos = BlockPos.containing(pos.x, pos.y, pos.z);
            VoxelShape feetShape = mc.level.getBlockState(feetPos).getCollisionShape(mc.level, feetPos);
            if (!feetShape.isEmpty()) {
                double topY = feetPos.getY() + feetShape.max(Direction.Axis.Y);
                if (topY <= pos.y + 0.35D) {
                    return new Vec3(pos.x, topY + 0.018D, pos.z);
                }
            }

            BlockPos belowPos = feetPos.below();
            VoxelShape belowShape = mc.level.getBlockState(belowPos).getCollisionShape(mc.level, belowPos);
            if (!belowShape.isEmpty()) {
                double topY = belowPos.getY() + belowShape.max(Direction.Axis.Y);
                return new Vec3(pos.x, topY + 0.018D, pos.z);
            }

            Vec3 start = new Vec3(pos.x, pos.y + 0.1D, pos.z);
            Vec3 end = new Vec3(pos.x, pos.y - 3.2D, pos.z);
            BlockHitResult hit = mc.level.clip(new ClipContext(
                    start, end,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    mc.player
            ));
            if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
                return new Vec3(pos.x, hit.getLocation().y + 0.018D, pos.z);
            }
        } catch (Throwable ignored) {
        }
        return pos.add(0, 0.018D, 0);
    }

    public static void spawnJumpCircle(Vec3 worldPos, boolean isLanding, float fallDistance, int mode) {
        long now = System.currentTimeMillis();
        Vec3 groundPos = snapToGround(Minecraft.getInstance(), worldPos);

        float baseRadius = Baritone.settings() != null ? Baritone.settings().jumpCirclesRadius.value.floatValue() : jumpCirclesRadius;
        float maxRadius = isLanding ? Math.min(4.8F, baseRadius * (1.0F + Math.min(1.4F, fallDistance * 0.28F))) : baseRadius;
        float duration = isLanding ? Math.min(1600.0F, 1250.0F + fallDistance * 55.0F) : 1050.0F;

        int primColor, secColor;
        switch (mode) {
            case 1 -> { // Chroma Rainbow
                primColor = 0xFFFF007F;
                secColor = 0xFF00F0FF;
            }
            case 2 -> { // Celestial Starfire
                primColor = 0xFF3A86FF;
                secColor = 0xFF9D4EDD;
            }
            case 3 -> { // Synthwave Sunset
                primColor = 0xFFFF007F;
                secColor = 0xFFFF9900;
            }
            case 4 -> { // Magma Blast
                primColor = 0xFFFF4500;
                secColor = 0xFFFF1744;
            }
            case 5 -> { // Fluid Water
                primColor = 0xFF00E5FF;
                secColor = 0xFF0077B6;
            }
            case 6 -> { // Golden Radiance
                primColor = 0xFFFFD700;
                secColor = 0xFFFFAA00;
            }
            default -> { // Cyber Lilac & Cyan (Mode 0)
                primColor = COLOR_LILAC;
                secColor = COLOR_CYAN;
            }
        }

        List<SparkParticle> sparks = new ArrayList<>();
        boolean enableSparks = Baritone.settings() != null ? Baritone.settings().jumpCirclesSparks.value : jumpCirclesSparks;
        if (enableSparks) {
            int sparkCount = isLanding ? Math.min(32, 18 + (int) (fallDistance * 3.0F)) : 16;
            for (int i = 0; i < sparkCount; i++) {
                float angle = (float) (i * Math.PI * 2.0D / sparkCount) + (float) ((Math.random() - 0.5D) * 0.40D);
                float speed = 0.85F + (float) (Math.random() * 0.45D);
                float vy = isLanding ? (0.75F + (float) (Math.random() * 0.75D) + Math.min(0.55F, fallDistance * 0.14F))
                                     : (0.48F + (float) (Math.random() * 0.48D));
                float size = isLanding ? (0.042F + (float) (Math.random() * 0.020F)) : (0.032F + (float) (Math.random() * 0.015F));
                float spinOmega = (float) ((Math.random() > 0.5D ? 1.0D : -1.0D) * (1.8D + Math.random() * 2.4D));
                float drag = 1.25F + (float) (Math.random() * 0.65D);
                float bounceDamping = 0.48F + (float) (Math.random() * 0.12D);
                float lifetimeSec = (duration / 1000.0F) * (0.80F + (float) (Math.random() * 0.20F));

                int sparkCol = (i % 2 == 0) ? primColor : secColor;
                if (mode == 1) {
                    sparkCol = Mth.hsvToRgb((float) i / sparkCount, 0.9F, 1.0F) | 0xFF000000;
                }
                sparks.add(new SparkParticle(angle, speed, vy, size, sparkCol, spinOmega, drag, bounceDamping, lifetimeSec));
            }
        }

        JUMP_CIRCLES.addLast(new JumpCircleInstance(groundPos, now, maxRadius, duration, isLanding, mode, primColor, secColor, sparks));
    }

    private static void renderJumpCircles(PoseStack poseStack) {
        if (JUMP_CIRCLES.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        float time = (now % 100000L) / 1000.0F;

        for (JumpCircleInstance circle : JUMP_CIRCLES) {
            float elapsed = (float) (now - circle.spawnTime);
            if (elapsed >= circle.durationMs) {
                continue;
            }
            float progress = Math.min(1.0F, elapsed / circle.durationMs);

            // Quartic ease-out expansion curve (Punchy fast explosion, smooth dissipation)
            float easeOut = 1.0F - (float) Math.pow(1.0F - progress, 4.0D);
            float currentRadius = circle.maxRadius * easeOut;

            // Dynamic thickness tapering (shockwave dissipation)
            float baseThickness = (circle.isLanding ? 0.30F : 0.22F) * (1.0F - progress * 0.70F);
            float alphaCurve = (float) Math.pow(1.0F - progress, 1.75D);
            int baseAlpha = (int) (alphaCurve * 255.0F);
            if (baseAlpha <= 2) {
                continue;
            }

            poseStack.pushPose();
            poseStack.translate(circle.pos.x, circle.pos.y, circle.pos.z);
            Matrix4f mat = poseStack.last().pose();

            boolean isChroma = circle.mode == 1;

            // Additive bloom blend for laser shockwave intensity
            RenderSystem.blendFuncSeparate(
                    GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE,
                    GlStateManager.SourceFactor.ONE,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
            );

            // 1. Sub-Surface Ground Acoustic Resonance (Radial ground bloom disk)
            int diskAlpha = (int) (baseAlpha * 0.24F);
            if (diskAlpha > 1) {
                drawRadialDisk(mat, 0.0F, 0.002F, 0.0F, currentRadius * 0.94F, 40, circle.primaryColor, diskAlpha, 0);
            }

            // 2. Precursor Ionization Rim (Ultra-fast thin barrier ahead of shockwave)
            float preProgress = Math.min(1.0F, progress * 1.6F);
            int preAlpha = (int) ((1.0F - preProgress) * (float) Math.exp(-progress * 3.5F) * 220.0F);
            if (preAlpha > 2) {
                float preRadius = currentRadius * 1.14F;
                int preColor = isChroma ? (Mth.hsvToRgb((time * 0.9F) % 1.0F, 0.4F, 1.0F) | 0xFF000000) : 0xFFFFFFFF;
                drawRing(mat, 0.003F, preRadius, 0.016F, 48, preColor, preAlpha);
            }

            // 3. Primary Double-Feathered Shockwave (Glowing body)
            drawFeatheredShockwave(mat, 0.005F, currentRadius, baseThickness, 56, circle.primaryColor, circle.secondaryColor, baseAlpha, isChroma, time * 0.75F);

            // 4. Diamond Laser Core Filament (White-hot energy crest)
            int coreColor = isChroma ? (Mth.hsvToRgb((time * 0.75F) % 1.0F, 0.5F, 1.0F) | 0xFF000000) : 0xFFFFFFFF;
            drawRing(mat, 0.007F, currentRadius, 0.024F, 56, coreColor, (int) (baseAlpha * 0.92F));

            // 5. Secondary Delayed Echo Ripple (starts after 65ms)
            if (elapsed > 65.0F) {
                float echoProgress = (elapsed - 65.0F) / (circle.durationMs - 65.0F);
                float echoEase = 1.0F - (float) Math.pow(1.0F - echoProgress, 3.2D);
                float echoRadius = (circle.maxRadius * 0.76F) * echoEase;
                int echoAlpha = (int) (baseAlpha * 0.62F);
                drawFeatheredShockwave(mat, 0.006F, echoRadius, baseThickness * 0.60F, 48, circle.secondaryColor, circle.primaryColor, echoAlpha, isChroma, (time * 0.75F) + 0.35F);
            }

            // 6. Tertiary Cavitation Micro-Ripple for heavy landings
            if (circle.isLanding && elapsed > 130.0F) {
                float cavProgress = (elapsed - 130.0F) / (circle.durationMs - 130.0F);
                float cavEase = 1.0F - (float) Math.pow(1.0F - cavProgress, 2.5D);
                float cavRadius = (circle.maxRadius * 0.52F) * cavEase;
                int cavAlpha = (int) (baseAlpha * 0.40F);
                drawRing(mat, 0.004F, cavRadius, 0.020F, 40, circle.primaryColor, cavAlpha);
            }

            // 7. Aerodynamic Motion Streak Sparks with Ground Restitution Physics
            if (circle.sparks != null && !circle.sparks.isEmpty()) {
                renderSparks(mat, circle, currentRadius, progress, elapsed / 1000.0F);
            }

            // Restore default blending
            RenderSystem.defaultBlendFunc();

            poseStack.popPose();
        }
    }

    private static void drawRadialDisk(Matrix4f mat, float x, float y, float z, float radius, int segs, int rgb, int centerAlpha, int rimAlpha) {
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        buf.addVertex(mat, x, y, z).setColor(r, g, b, centerAlpha);
        for (int i = 0; i <= segs; i++) {
            float angle = (float) (i * Math.PI * 2.0D / segs);
            float cos = Mth.cos(angle);
            float sin = Mth.sin(angle);
            buf.addVertex(mat, x + cos * radius, y, z + sin * radius).setColor(r, g, b, rimAlpha);
        }
        MeshData mesh = buf.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
            mesh.close();
        }
    }

    private static void drawFeatheredShockwave(Matrix4f mat, float y, float radius, float thickness, int segs, int primaryArgb, int secondaryArgb, int alpha, boolean isChroma, float chromaOffset) {
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        float halfT = thickness * 0.5F;
        float rInner = Math.max(0.01F, radius - halfT);
        float rCenter = radius;
        float rOuter = radius + halfT;

        int pr = (primaryArgb >> 16) & 0xFF, pg = (primaryArgb >> 8) & 0xFF, pb = primaryArgb & 0xFF;
        int sr = (secondaryArgb >> 16) & 0xFF, sg = (secondaryArgb >> 8) & 0xFF, sb = secondaryArgb & 0xFF;

        for (int i = 0; i < segs; i++) {
            float a0 = (float) (i * Math.PI * 2.0D / segs);
            float a1 = (float) ((i + 1) * Math.PI * 2.0D / segs);

            float c0 = Mth.cos(a0), s0 = Mth.sin(a0);
            float c1 = Mth.cos(a1), s1 = Mth.sin(a1);

            int cr, cg, cb;
            if (isChroma) {
                float hue0 = (chromaOffset + (float) i / segs) % 1.0F;
                int rgb = Mth.hsvToRgb(hue0, 0.9F, 1.0F);
                cr = (rgb >> 16) & 0xFF; cg = (rgb >> 8) & 0xFF; cb = rgb & 0xFF;
            } else {
                cr = (i % 2 == 0) ? pr : sr;
                cg = (i % 2 == 0) ? pg : sg;
                cb = (i % 2 == 0) ? pb : sb;
            }

            // Quad 1: Wewnętrzny gradient (od 0 do alpha w centrum)
            buf.addVertex(mat, c0 * rInner, y, s0 * rInner).setColor(cr, cg, cb, 0);
            buf.addVertex(mat, c1 * rInner, y, s1 * rInner).setColor(cr, cg, cb, 0);
            buf.addVertex(mat, c1 * rCenter, y, s1 * rCenter).setColor(cr, cg, cb, alpha);
            buf.addVertex(mat, c0 * rCenter, y, s0 * rCenter).setColor(cr, cg, cb, alpha);

            // Quad 2: Zewnętrzny gradient (od alpha w centrum do 0 na zewnątrz)
            buf.addVertex(mat, c0 * rCenter, y, s0 * rCenter).setColor(cr, cg, cb, alpha);
            buf.addVertex(mat, c1 * rCenter, y, s1 * rCenter).setColor(cr, cg, cb, alpha);
            buf.addVertex(mat, c1 * rOuter, y, s1 * rOuter).setColor(cr, cg, cb, 0);
            buf.addVertex(mat, c0 * rOuter, y, s0 * rOuter).setColor(cr, cg, cb, 0);
        }

        MeshData mesh = buf.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
            mesh.close();
        }
    }

    private static float computeBouncingHeight(SparkParticle sp, float t) {
        float g = 8.6F;
        float ground = 0.02F;
        float v0 = sp.initialVy;
        float t1 = (2.0F * v0) / g;
        if (t <= t1) {
            return ground + Math.max(0.0F, v0 * t - 0.5F * g * t * t);
        }
        float v1 = v0 * sp.bounceDamping;
        float t2 = t1 + (2.0F * v1) / g;
        if (t <= t2) {
            float dt1 = t - t1;
            return ground + Math.max(0.0F, v1 * dt1 - 0.5F * g * dt1 * dt1);
        }
        float v2 = v1 * sp.bounceDamping;
        float dt2 = t - t2;
        return ground + Math.max(0.0F, v2 * dt2 - 0.5F * g * dt2 * dt2);
    }

    private static void renderSparks(Matrix4f mat, JumpCircleInstance circle, float currentRadius, float progress, float elapsedSec) {
        float sparkAlpha = Math.max(0.0F, 1.0F - progress * 1.10F) * (circle.isLanding ? 255.0F : 225.0F);
        int sa = (int) sparkAlpha;
        if (sa <= 3) return;

        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        float t = elapsedSec;
        float dt = 0.022F;
        float tPrev = Math.max(0.0F, t - dt);

        for (SparkParticle sp : circle.sparks) {
            if (t > sp.lifetimeSec) {
                continue;
            }

            // 1. Current position at time t with vortex angular rotation & aerodynamic drag
            float angle = sp.angle + (sp.spinOmega * t) / (1.0F + 0.45F * t);
            float dragMod = (float) Math.exp(-sp.drag * t * 0.55F);
            float dist = (sp.speed * currentRadius) * dragMod;
            float sx = Mth.cos(angle) * dist;
            float sz = Mth.sin(angle) * dist;
            float curY = computeBouncingHeight(sp, t);

            // 2. Previous position at t - dt for motion streak vector
            float anglePrev = sp.angle + (sp.spinOmega * tPrev) / (1.0F + 0.45F * tPrev);
            float dragModPrev = (float) Math.exp(-sp.drag * tPrev * 0.55F);
            float progressFactor = (t > 0.001F) ? (float) Math.pow(tPrev / t, 0.85D) : 0.0F;
            float distPrev = (sp.speed * currentRadius * progressFactor) * dragModPrev;
            float sxPrev = Mth.cos(anglePrev) * distPrev;
            float szPrev = Mth.sin(anglePrev) * distPrev;
            float curYPrev = computeBouncingHeight(sp, tPrev);

            // 3. Velocity tangent vector
            float vx = sx - sxPrev;
            float vy = curY - curYPrev;
            float vz = sz - szPrev;
            float vMag = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);

            int sc = sp.color;
            int sr = (sc >> 16) & 0xFF, sg = (sc >> 8) & 0xFF, sb = sc & 0xFF;
            float pSize = sp.size * (1.0F - progress * 0.45F);

            if (vMag >= 0.001F) {
                // Directional Motion Streak Ribbon
                float invMag = 1.0F / vMag;
                float dirX = vx * invMag;
                float dirY = vy * invMag;
                float dirZ = vz * invMag;

                float streakLen = Math.min(0.24F, vMag * 2.2F + pSize * 0.8F);
                float tailX = sx - dirX * streakLen;
                float tailY = curY - dirY * streakLen;
                float tailZ = sz - dirZ * streakLen;

                float halfW = pSize * 0.55F;
                float sideX = -dirZ * halfW;
                float sideZ = dirX * halfW;

                // Motion ribbon: Tail (faded, saturated) -> Head (intense, white-hot)
                buf.addVertex(mat, tailX + sideX, tailY, tailZ + sideZ).setColor(sr, sg, sb, 0);
                buf.addVertex(mat, tailX - sideX, tailY, tailZ - sideZ).setColor(sr, sg, sb, 0);
                buf.addVertex(mat, sx - sideX, curY, sz - sideZ).setColor(255, 255, 255, sa);
                buf.addVertex(mat, sx + sideX, curY, sz + sideZ).setColor(255, 255, 255, sa);
            }

            // Volumetric diamond core at particle head
            float ds = pSize * 0.75F;
            buf.addVertex(mat, sx - ds, curY, sz).setColor(sr, sg, sb, sa);
            buf.addVertex(mat, sx, curY + ds * 1.4F, sz).setColor(255, 255, 255, sa);
            buf.addVertex(mat, sx + ds, curY, sz).setColor(sr, sg, sb, sa);
            buf.addVertex(mat, sx, curY - ds * 0.5F, sz).setColor(sr, sg, sb, sa);
        }

        MeshData mesh = buf.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
            mesh.close();
        }
    }

    private static void renderTargetEspRing(PoseStack poseStack, LivingEntity target, float partialTick, Vec3 camPos) {
        if (target == null) return;

        double tx = Mth.lerp(partialTick, target.xo, target.getX());
        double ty = Mth.lerp(partialTick, target.yo, target.getY());
        double tz = Mth.lerp(partialTick, target.zo, target.getZ());
        float time = (System.currentTimeMillis() % 100000L) / 1000.0F;

        float width = target.getBbWidth();
        float height = target.getBbHeight();

        // 1. Obliczenie kompresji promienia i rozbłysku od uderzenia (Damage Impulse Compression)
        float hurtFactor = 0.0F;
        if (target.hurtTime > 0) {
            float h = target.hurtTime / 10.0F;
            hurtFactor = (float) (Math.sin(h * Math.PI) * Math.exp(-2.0D * (1.0D - h)));
        }
        float radiusScale = 1.0F - (hurtFactor * 0.25F);

        int mode = getTargetEspMode();
        int primaryColor, secondaryColor;
        switch (mode) {
            case 1 -> { // Dynamic Health HUD
                float hpRatio = Mth.clamp(target.getHealth() / target.getMaxHealth(), 0.0F, 1.0F);
                if (hpRatio > 0.5F) {
                    float sub = (hpRatio - 0.5F) * 2.0F;
                    int r = (int) Mth.lerp(sub, 245, 16);
                    int g = (int) Mth.lerp(sub, 158, 185);
                    int b = (int) Mth.lerp(sub, 11, 129);
                    primaryColor = (r << 16) | (g << 8) | b;
                } else {
                    float sub = hpRatio * 2.0F;
                    int r = (int) Mth.lerp(sub, 244, 245);
                    int g = (int) Mth.lerp(sub, 63, 158);
                    int b = (int) Mth.lerp(sub, 94, 11);
                    primaryColor = (r << 16) | (g << 8) | b;
                }
                secondaryColor = 0xFFFFFFFF;
            }
            case 2 -> { // Chroma Rainbow
                primaryColor = Mth.hsvToRgb((time * 0.4F) % 1.0F, 0.85F, 1.0F);
                secondaryColor = Mth.hsvToRgb((time * 0.4F + 0.3F) % 1.0F, 0.85F, 1.0F);
            }
            case 3 -> { // Golden Radiance
                primaryColor = 0xFFFFD700;
                secondaryColor = 0xFFFFAA00;
            }
            case 4 -> { // Matrix Emerald
                primaryColor = 0xFF00FF88;
                secondaryColor = 0xFF00F0FF;
            }
            default -> { // Cyber Lilac & Cyan (Default)
                primaryColor = COLOR_CYAN;
                secondaryColor = COLOR_LILAC;
            }
        }

        // Błysk szkarłatno-biały przy rejestracji trafienia (Hurt Flash)
        if (hurtFactor > 0.05F) {
            primaryColor = 0xFFFF3344;
            secondaryColor = 0xFFFFFFFF;
        }

        poseStack.pushPose();
        poseStack.translate(tx, ty, tz);

        // 1. Dysk rozbłysku podłoża (Soft Ground Bloom Disk)
        drawRadialDisk(poseStack.last().pose(), 0.0F, 0.015F, 0.0F, width * 1.25F * radiusScale, 36, primaryColor, 80, 0);

        // 2. 3-OSIOWY ŻYROSKOP RUNICZNY (3-Axis Runic Gyroscope):
        // Pierścień 1: Obrót w osi Y + harmoniczne kołysanie osi X (Outer Orbital Ring)
        poseStack.pushPose();
        poseStack.translate(0.0F, height * 0.45F, 0.0F);
        float tiltX1 = Mth.sin(time * 1.7F) * 18.0F;
        poseStack.mulPose(Axis.XP.rotationDegrees(tiltX1));
        poseStack.mulPose(Axis.YP.rotationDegrees(time * 65.0F));
        drawRing(poseStack.last().pose(), 0.0F, width * 1.15F * radiusScale, 0.038F, 40, primaryColor, 220);
        // 4 Tech Reticle Corner Brackets na obwodzie pierścienia 1
        for (int b = 0; b < 4; b++) {
            float ang = b * (float) Math.PI * 0.5F;
            float bx = Mth.cos(ang) * (width * 1.15F * radiusScale);
            float bz = Mth.sin(ang) * (width * 1.15F * radiusScale);
            drawShadedBox(poseStack, bx - 0.028F, -0.025F, bz - 0.028F, bx + 0.028F, 0.025F, bz + 0.028F, secondaryColor | 0xFF000000);
        }
        poseStack.popPose();

        // Pierścień 2: Przeciwbieżny obrót w osi Y + kołysanie osi Z (Inner Orbital Ring)
        poseStack.pushPose();
        poseStack.translate(0.0F, height * 0.45F, 0.0F);
        float tiltZ2 = Mth.cos(time * 1.3F) * 22.0F;
        poseStack.mulPose(Axis.ZP.rotationDegrees(tiltZ2));
        poseStack.mulPose(Axis.YP.rotationDegrees(-time * 85.0F));
        drawRing(poseStack.last().pose(), 0.0F, width * 0.92F * radiusScale, 0.028F, 36, secondaryColor, 195);
        poseStack.popPose();

        // Pierścień 3: Pochylony pierścień równikowy (Equatorial Scanner Ring)
        poseStack.pushPose();
        poseStack.translate(0.0F, height * 0.45F, 0.0F);
        poseStack.mulPose(Axis.XP.rotationDegrees(45.0F + Mth.sin(time * 2.2F) * 15.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(time * 40.0F));
        drawRing(poseStack.last().pose(), 0.0F, width * 0.76F * radiusScale, 0.020F, 32, primaryColor, 160);
        poseStack.popPose();

        // 3. Pionowy skanujący pierścień biodrowy (Sine Bouncing Waist Scanner)
        float yBounce = (Mth.sin(time * 3.6F) * 0.5F + 0.5F) * (height * 0.85F) + 0.10F;
        float pulsePulse = (Mth.sin(time * 7.0F) * 0.08F + 1.0F);
        drawRing(poseStack.last().pose(), yBounce, (width * 0.95F * radiusScale) * pulsePulse, 0.042F, 36, primaryColor, 240);

        // 4. Półprzezroczysty laserowy słup namierzania (Vertical Cyber Laser Cylinder)
        drawShadedCylinder(poseStack, 0.0F, 0.0F, 0.0F, width * 0.65F * radiusScale, width * 0.65F * radiusScale, height * 1.05F, 24, primaryColor & 0x24FFFFFF, secondaryColor & 0x06FFFFFF);

        poseStack.popPose();

        // 5. KINEMATIC PREDICTIVE LEAD RETICLE (Celownik Predykcyjny Wyprzedzenia 2nd Order)
        Vec3 vel = target.getDeltaMovement();
        if (vel.lengthSqr() > 0.0008D) {
            double leadTime = 0.22D; // 220ms predykcji ruchu (GrimAC / PvP hit projection)
            double predX = tx + vel.x * leadTime * 20.0D;
            double predY = ty + vel.y * leadTime * 20.0D + height * 0.55D;
            double predZ = tz + vel.z * leadTime * 20.0D;

            poseStack.pushPose();
            poseStack.translate(predX, predY, predZ);
            poseStack.mulPose(Axis.YP.rotationDegrees(time * 120.0F));

            // Dynamiczny obracający się diament wskaźnika trafienia
            float dSize = 0.10F;
            drawShadedBox(poseStack, -dSize, -dSize, -0.01F, dSize, dSize, 0.01F, primaryColor | 0xDD000000);
            drawRing(poseStack.last().pose(), 0.0F, dSize * 1.6F, 0.025F, 16, secondaryColor, 240);

            poseStack.popPose();
        }
    }

    // --- OpenGL 3.3 Core Helper Geometric Primitives ---

    private static void drawRing(Matrix4f mat, float y, float radius, float thickness, int segs, int argb, int alphaOverride) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i <= segs; i++) {
            float a = (float) (i * Math.PI * 2.0D / segs);
            float cos = Mth.cos(a), sin = Mth.sin(a);
            buf.addVertex(mat, cos * (radius - thickness), y, sin * (radius - thickness)).setColor(r, g, b, 0);
            buf.addVertex(mat, cos * radius, y, sin * radius).setColor(r, g, b, alphaOverride);
        }
        MeshData mesh = buf.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
            mesh.close();
        }
    }

    private static void drawShadedCylinder(PoseStack ps, float x, float y, float z, float rBot, float rTop, float h, int segs, int colorBot, int colorTop) {
        Matrix4f pose = ps.last().pose();
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        int aTop = (colorTop >> 24) & 0xFF; if (aTop == 0) aTop = 0xFF;
        int rT = (colorTop >> 16) & 0xFF, gT = (colorTop >> 8) & 0xFF, bT = colorTop & 0xFF;

        int aBot = (colorBot >> 24) & 0xFF; if (aBot == 0) aBot = 0xFF;
        int rB = (colorBot >> 16) & 0xFF, gB = (colorBot >> 8) & 0xFF, bB = colorBot & 0xFF;

        for (int i = 0; i < segs; i++) {
            float a0 = (float) (i * Math.PI * 2.0D / segs);
            float a1 = (float) ((i + 1) * Math.PI * 2.0D / segs);

            float c0 = Mth.cos(a0), s0 = Mth.sin(a0);
            float c1 = Mth.cos(a1), s1 = Mth.sin(a1);

            float midSin = (s0 + s1) * 0.5F;
            float midCos = (c0 + c1) * 0.5F;
            float diff = Mth.clamp(0.74F + 0.16F * midSin + 0.10F * midCos, 0.45F, 1.0F);

            int crT = (int) (rT * diff), cgT = (int) (gT * diff), cbT = (int) (bT * diff);
            int crB = (int) (rB * diff * 0.85F), cgB = (int) (gB * diff * 0.85F), cbB = (int) (bB * diff * 0.85F);

            // Zewnętrzna ściana (Outward face)
            buf.addVertex(pose, x + c0 * rTop, y + h, z + s0 * rTop).setColor(crT, cgT, cbT, aTop);
            buf.addVertex(pose, x + c1 * rTop, y + h, z + s1 * rTop).setColor(crT, cgT, cbT, aTop);
            buf.addVertex(pose, x + c1 * rBot, y,     z + s1 * rBot).setColor(crB, cgB, cbB, aBot);
            buf.addVertex(pose, x + c0 * rBot, y,     z + s0 * rBot).setColor(crB, cgB, cbB, aBot);

            // Wewnętrzna ściana (Inward face, eliminuje prześwity i braki pod każdym kątem)
            buf.addVertex(pose, x + c0 * rBot, y,     z + s0 * rBot).setColor(crB, cgB, cbB, aBot);
            buf.addVertex(pose, x + c1 * rBot, y,     z + s1 * rBot).setColor(crB, cgB, cbB, aBot);
            buf.addVertex(pose, x + c1 * rTop, y + h, z + s1 * rTop).setColor(crT, cgT, cbT, aTop);
            buf.addVertex(pose, x + c0 * rTop, y + h, z + s0 * rTop).setColor(crT, cgT, cbT, aTop);
        }

        MeshData mesh = buf.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
            mesh.close();
        }
    }

    private static void drawCylinder(PoseStack ps, float x, float y, float z, float rBot, float rTop, float h, int segs, int cBot, int cTop) {
        drawShadedCylinder(ps, x, y, z, rBot, rTop, h, segs, cBot, cTop);
    }

    private static void drawShadedDisk(PoseStack ps, float x, float y, float z, float radius, int segs, int color, boolean top) {
        Matrix4f pose = ps.last().pose();
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);

        int a = (color >> 24) & 0xFF; if (a == 0) a = 0xFF;
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        float shade = top ? 1.0F : 0.62F;
        int rC = (int) (r * shade), gC = (int) (g * shade), bC = (int) (b * shade);

        buf.addVertex(pose, x, y, z).setColor(rC, gC, bC, a);
        for (int i = 0; i <= segs; i++) {
            float angle = (float) (i * Math.PI * 2.0D / segs);
            float sign = top ? 1.0F : -1.0F;
            float cos = Mth.cos(angle);
            float sin = Mth.sin(angle) * sign;
            buf.addVertex(pose, x + cos * radius, y, z + sin * radius).setColor(rC, gC, bC, a);
        }

        MeshData mesh = buf.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
            mesh.close();
        }
    }

    private static void drawShadedBox(PoseStack ps, float x1, float y1, float z1, float x2, float y2, float z2, int color) {
        float minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        float minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        float minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

        Matrix4f pose = ps.last().pose();
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        int a = (color >> 24) & 0xFF;
        if (a == 0) a = 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;

        // Minecraft directional diffuse lighting factors:
        // Top (+Y): 1.0F, Bottom (-Y): 0.52F, North/South (Z): 0.82F, West/East (X): 0.65F
        int rTop = r, gTop = g, bTop = b;
        int rBot = (int) (r * 0.52F), gBot = (int) (g * 0.52F), bBot = (int) (b * 0.52F);
        int rNS  = (int) (r * 0.82F), gNS  = (int) (g * 0.82F), bNS  = (int) (b * 0.82F);
        int rEW  = (int) (r * 0.65F), gEW  = (int) (g * 0.65F), bEW  = (int) (b * 0.65F);

        // Bottom (-Y)
        buf.addVertex(pose, minX, minY, minZ).setColor(rBot, gBot, bBot, a);
        buf.addVertex(pose, maxX, minY, minZ).setColor(rBot, gBot, bBot, a);
        buf.addVertex(pose, maxX, minY, maxZ).setColor(rBot, gBot, bBot, a);
        buf.addVertex(pose, minX, minY, maxZ).setColor(rBot, gBot, bBot, a);

        // Top (+Y)
        buf.addVertex(pose, minX, maxY, maxZ).setColor(rTop, gTop, bTop, a);
        buf.addVertex(pose, maxX, maxY, maxZ).setColor(rTop, gTop, bTop, a);
        buf.addVertex(pose, maxX, maxY, minZ).setColor(rTop, gTop, bTop, a);
        buf.addVertex(pose, minX, maxY, minZ).setColor(rTop, gTop, bTop, a);

        // North (-Z)
        buf.addVertex(pose, minX, maxY, minZ).setColor(rNS, gNS, bNS, a);
        buf.addVertex(pose, maxX, maxY, minZ).setColor(rNS, gNS, bNS, a);
        buf.addVertex(pose, maxX, minY, minZ).setColor(rNS, gNS, bNS, a);
        buf.addVertex(pose, minX, minY, minZ).setColor(rNS, gNS, bNS, a);

        // South (+Z)
        buf.addVertex(pose, minX, minY, maxZ).setColor(rNS, gNS, bNS, a);
        buf.addVertex(pose, maxX, minY, maxZ).setColor(rNS, gNS, bNS, a);
        buf.addVertex(pose, maxX, maxY, maxZ).setColor(rNS, gNS, bNS, a);
        buf.addVertex(pose, minX, maxY, maxZ).setColor(rNS, gNS, bNS, a);

        // West (-X)
        buf.addVertex(pose, minX, minY, minZ).setColor(rEW, gEW, bEW, a);
        buf.addVertex(pose, minX, minY, maxZ).setColor(rEW, gEW, bEW, a);
        buf.addVertex(pose, minX, maxY, maxZ).setColor(rEW, gEW, bEW, a);
        buf.addVertex(pose, minX, maxY, minZ).setColor(rEW, gEW, bEW, a);

        // East (+X)
        buf.addVertex(pose, maxX, minY, maxZ).setColor(rEW, gEW, bEW, a);
        buf.addVertex(pose, maxX, minY, minZ).setColor(rEW, gEW, bEW, a);
        buf.addVertex(pose, maxX, maxY, minZ).setColor(rEW, gEW, bEW, a);
        buf.addVertex(pose, maxX, maxY, maxZ).setColor(rEW, gEW, bEW, a);

        MeshData mesh = buf.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
            mesh.close();
        }
    }

    private static void drawBox(PoseStack ps, float x1, float y1, float z1, float x2, float y2, float z2, int color) {
        drawShadedBox(ps, x1, y1, z1, x2, y2, z2, color);
    }

    private static void setup3DBlendState() {
        setup3DBlendState(true);
    }

    private static void setup3DBlendState(boolean translucentPass) {
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO
        );
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(515); // GL11.GL_LEQUAL
        if (translucentPass) {
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
        } else {
            RenderSystem.depthMask(true);
            RenderSystem.disableCull(); // Ochrona przed obcinaniem geometrii cylindrów i zaokrągleń pod każdym kątem!
        }
        RenderSystem.setShader(CoreShaders.POSITION_COLOR);
    }

    private static void restore3DBlendState() {
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
    }

    // Getters and Setters for Settings / GUI
    public static boolean isHandShadersEnabled() {
        return Baritone.settings() != null ? Baritone.settings().handShaders.value : handShadersEnabled;
    }
    public static void setHandShadersEnabled(boolean enabled) {
        handShadersEnabled = enabled;
        if (Baritone.settings() != null) {
            Baritone.settings().handShaders.value = enabled;
        }
    }

    public static DisguiseModel getActiveDisguise() { return activeDisguise; }
    public static void setActiveDisguise(DisguiseModel disguise) { activeDisguise = disguise; }

    public static boolean isChinaHatEnabled() {
        return Baritone.settings() != null ? Baritone.settings().chinaHat.value : chinaHatEnabled;
    }
    public static void setChinaHatEnabled(boolean enabled) {
        chinaHatEnabled = enabled;
        if (Baritone.settings() != null) {
            Baritone.settings().chinaHat.value = enabled;
        }
    }

    public static boolean isMotionTrailEnabled() {
        return Baritone.settings() != null && Baritone.settings().motionTrail != null ? Baritone.settings().motionTrail.value : motionTrailEnabled;
    }
    public static void setMotionTrailEnabled(boolean enabled) {
        motionTrailEnabled = enabled;
        if (Baritone.settings() != null && Baritone.settings().motionTrail != null) {
            Baritone.settings().motionTrail.value = enabled;
        }
    }

    public static int getMotionTrailMode() {
        return Baritone.settings() != null && Baritone.settings().motionTrailMode != null ? Baritone.settings().motionTrailMode.value : motionTrailMode;
    }
    public static void setMotionTrailMode(int mode) {
        motionTrailMode = mode;
        if (Baritone.settings() != null && Baritone.settings().motionTrailMode != null) {
            Baritone.settings().motionTrailMode.value = mode;
        }
    }

    public static boolean isJumpCirclesEnabled() {
        return Baritone.settings() != null ? Baritone.settings().jumpCircles.value : jumpCirclesEnabled;
    }
    public static void setJumpCirclesEnabled(boolean enabled) {
        jumpCirclesEnabled = enabled;
        if (Baritone.settings() != null) {
            Baritone.settings().jumpCircles.value = enabled;
        }
    }

    public static int getJumpCirclesMode() {
        return Baritone.settings() != null && Baritone.settings().jumpCirclesMode != null ? Baritone.settings().jumpCirclesMode.value : jumpCirclesMode;
    }
    public static void setJumpCirclesMode(int mode) {
        jumpCirclesMode = mode;
        if (Baritone.settings() != null && Baritone.settings().jumpCirclesMode != null) {
            Baritone.settings().jumpCirclesMode.value = mode;
        }
    }

    public static boolean isTargetEspEnabled() {
        return Baritone.settings() != null && Baritone.settings().targetEsp != null ? Baritone.settings().targetEsp.value : targetEspEnabled;
    }
    public static void setTargetEspEnabled(boolean enabled) {
        targetEspEnabled = enabled;
        if (Baritone.settings() != null && Baritone.settings().targetEsp != null) {
            Baritone.settings().targetEsp.value = enabled;
        }
    }

    public static int getTargetEspMode() {
        return Baritone.settings() != null && Baritone.settings().targetEspMode != null ? Baritone.settings().targetEspMode.value : targetEspMode;
    }
    public static void setTargetEspMode(int mode) {
        targetEspMode = mode;
        if (Baritone.settings() != null && Baritone.settings().targetEspMode != null) {
            Baritone.settings().targetEspMode.value = mode;
        }
    }

    public void setTrackedTarget(LivingEntity target) { this.currentTrackedTarget = target; }

    // =========================================================================
    // CUSTOM FOG & SKY & PARTICLES
    // =========================================================================
    // CUSTOM ATMOSPHERIC FOG & CELESTIAL SKY (ARGON 4.0 ARCHITECTURE)
    // =========================================================================

    public static float fogDensity = 1.0F;
    public static float vanillaBlendRatio = 0.28F;
    public static float fogLerpSpeed = 6.0F;
    private static float currentFogStart = -1.0F;
    private static float currentFogEnd = -1.0F;
    private static final org.joml.Vector3f currentFogColor = new org.joml.Vector3f(-1.0F);
    private static long lastFogNano = System.nanoTime();

    public static void resetFogState() {
        currentFogStart = -1.0F;
        currentFogEnd = -1.0F;
        currentFogColor.set(-1.0F);
    }

    public static boolean isCustomFogEnabled() {
        return Baritone.settings() != null ? Baritone.settings().customFog.value : customFogEnabled;
    }
    public static void setCustomFogEnabled(boolean enabled) {
        customFogEnabled = enabled;
        if (Baritone.settings() != null) {
            Baritone.settings().customFog.value = enabled;
        }
        if (!enabled) {
            resetFogState();
        }
    }
    public static int getCustomFogMode() { return customFogMode; }
    public static void setCustomFogMode(int mode) {
        customFogMode = mode;
        resetFogState();
    }

    /**
     * 7 Zsynchronizowanych motywów atmosferycznych dla mgły, kopuły nieba, horyzontu i gwiazd:
     * 0: Cyber Lilac & Cyan (Sygnatura Astra)
     * 1: Synthwave Sunset (Ciepły zachód słońca i magenta)
     * 2: Deep Galaxy (Kosmiczne indygo, fioletowa mgławica i gwiezdny pył)
     * 3: Aurora Borealis (Zorza polarna, lodowy szmaragd i turkus)
     * 4: Blood Eclipse (Karmazynowy mrok, ognisty rubin i złoto)
     * 5: Ethereal Void (Pastelowa lawenda i perłowy zmierzch)
     * 6: Chroma Rainbow (Ciągły przepływ barw z pełną synchronizacją fazową)
     */
    public static org.joml.Vector3f evaluateFogPalette(int mode, float time, Camera camera) {
        float wave = (Mth.sin(time * 0.45F) + 1.0F) * 0.5F;
        return switch (mode) {
            case 0 -> new org.joml.Vector3f(0.06F, 0.88F, 0.98F)
                    .lerp(new org.joml.Vector3f(0.78F, 0.42F, 0.98F), wave); // Cyber Lilac & Cyan
            case 1 -> new org.joml.Vector3f(0.99F, 0.45F, 0.12F)
                    .lerp(new org.joml.Vector3f(0.96F, 0.12F, 0.48F), wave); // Synthwave Sunset
            case 2 -> new org.joml.Vector3f(0.38F, 0.08F, 0.72F)
                    .lerp(new org.joml.Vector3f(0.20F, 0.12F, 0.95F), wave); // Deep Galaxy
            case 3 -> new org.joml.Vector3f(0.08F, 0.95F, 0.65F)
                    .lerp(new org.joml.Vector3f(0.25F, 0.82F, 0.98F), wave); // Aurora Borealis
            case 4 -> new org.joml.Vector3f(0.88F, 0.08F, 0.14F)
                    .lerp(new org.joml.Vector3f(0.98F, 0.45F, 0.05F), wave); // Blood Eclipse
            case 5 -> new org.joml.Vector3f(0.78F, 0.72F, 0.92F)
                    .lerp(new org.joml.Vector3f(0.94F, 0.82F, 0.88F), wave); // Ethereal Void
            case 6 -> { // Chroma Rainbow Flow
                int rgb = Mth.hsvToRgb((time * 0.08F) % 1.0F, 0.78F, 0.95F);
                yield new org.joml.Vector3f(
                        ((rgb >> 16) & 0xFF) / 255.0F,
                        ((rgb >> 8) & 0xFF) / 255.0F,
                        (rgb & 0xFF) / 255.0F
                );
            }
            default -> new org.joml.Vector3f(0.06F, 0.88F, 0.98F);
        };
    }

    public static org.joml.Vector4f getCustomFogColor(org.joml.Vector4f vanilla) {
        return getCustomFogColor(vanilla, null);
    }

    public static org.joml.Vector4f getCustomFogColor(org.joml.Vector4f vanilla, Camera camera) {
        if (!isCustomFogEnabled()) return vanilla;
        if (camera != null) {
            net.minecraft.world.level.material.FogType submersion = camera.getFluidInCamera();
            if (submersion == net.minecraft.world.level.material.FogType.LAVA ||
                submersion == net.minecraft.world.level.material.FogType.WATER ||
                submersion == net.minecraft.world.level.material.FogType.POWDER_SNOW) {
                return vanilla;
            }
            if (camera.getEntity() instanceof LivingEntity living &&
                (living.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS) ||
                 living.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS))) {
                return vanilla;
            }
        }
        if (currentFogColor.x >= 0.0F) {
            return new org.joml.Vector4f(currentFogColor.x, currentFogColor.y, currentFogColor.z, 1.0F);
        }
        float timeSeconds = (System.currentTimeMillis() % 3600000L) / 1000.0F;
        org.joml.Vector3f c = evaluateFogPalette(customFogMode, timeSeconds, camera);
        return new org.joml.Vector4f(c.x, c.y, c.z, 1.0F);
    }

    public static net.minecraft.client.renderer.FogParameters modifyFogParameters(net.minecraft.client.renderer.FogParameters original, float renderDistance) {
        Minecraft mc = Minecraft.getInstance();
        Camera cam = mc.gameRenderer != null ? mc.gameRenderer.getMainCamera() : null;
        return modifyFogParameters(original, cam, renderDistance, net.minecraft.client.renderer.FogRenderer.FogMode.FOG_TERRAIN);
    }

    public static net.minecraft.client.renderer.FogParameters modifyFogParameters(
            net.minecraft.client.renderer.FogParameters original,
            Camera camera,
            float renderDistanceBlocks,
            net.minecraft.client.renderer.FogRenderer.FogMode fogMode
    ) {
        if (!isCustomFogEnabled() || original == net.minecraft.client.renderer.FogParameters.NO_FOG) {
            resetFogState();
            return original;
        }

        Minecraft mc = Minecraft.getInstance();
        if (camera == null) return original;

        // 1. Bezpieczna detekcja płynów - nie psujemy waniliowej mgły pod wodą, lawą i w śniegu
        net.minecraft.world.level.material.FogType submersion = camera.getFluidInCamera();
        if (submersion == net.minecraft.world.level.material.FogType.LAVA ||
            submersion == net.minecraft.world.level.material.FogType.WATER ||
            submersion == net.minecraft.world.level.material.FogType.POWDER_SNOW) {
            resetFogState();
            return original;
        }

        // 2. Bezpieczna detekcja efektów Blindness i Darkness
        if (camera.getEntity() instanceof LivingEntity living) {
            if (living.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS) ||
                living.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS)) {
                resetFogState();
                return original;
            }
        }

        // 3. Wygładzanie klatkowe wyłącznie dla FOG_TERRAIN (zapobiega podwójnemu lerpowaniu na klatkę)
        long now = System.nanoTime();
        if (fogMode == net.minecraft.client.renderer.FogRenderer.FogMode.FOG_TERRAIN) {
            float dt = Mth.clamp((now - lastFogNano) / 1_000_000_000.0F, 0.0001F, 0.1F);
            lastFogNano = now;
            float alpha = 1.0F - (float) Math.exp(-fogLerpSpeed * dt);

            float maxBlocks = renderDistanceBlocks <= 64.0F ? renderDistanceBlocks * 16.0F : renderDistanceBlocks;

            // 4. Adaptacja do pory dnia i pogody
            float rainLevel = mc.level != null ? mc.level.getRainLevel(1.0F) : 0.0F;
            float thunderLevel = mc.level != null ? mc.level.getThunderLevel(1.0F) : 0.0F;
            float dayTimeAngle = mc.level != null ? mc.level.getTimeOfDay(1.0F) : 0.0F;
            float nightFactor = Mth.clamp((Mth.cos(dayTimeAngle * ((float) Math.PI * 2F)) * -2.0F) + 0.5F, 0.0F, 1.0F);

            float weatherDensityBoost = 1.0F + (rainLevel * 0.35F) + (thunderLevel * 0.45F);
            float effectiveDensity = Math.max(0.05F, fogDensity * weatherDensityBoost);

            // Mnożnik odległości Clean Distant Fog (krystalicznie czysty widok przed graczem)
            double distMult = Baritone.settings() != null ? Baritone.settings().customFogDistanceMultiplier.value : 1.35D;
            float startRatio = Mth.clamp((0.26F / effectiveDensity) * (float) distMult, 0.05F, 0.85F);
            float endRatio = Mth.clamp((0.92F / (float) Math.sqrt(effectiveDensity)) * (float) distMult, startRatio + 0.15F, 1.45F);

            float targetStart = Math.max(12.0F, maxBlocks * startRatio);
            float targetEnd = Math.max(32.0F, maxBlocks * endRatio);

            // 5. Dynamiczny blend z kolorem biomu i nieba
            float timeSeconds = (System.currentTimeMillis() % 3600000L) / 1000.0F;
            org.joml.Vector3f customRgb = evaluateFogPalette(customFogMode, timeSeconds, camera);
            org.joml.Vector3f vanillaRgb = new org.joml.Vector3f(original.red(), original.green(), original.blue());

            float nightDim = Mth.lerp(nightFactor, 1.0F, 0.45F);
            customRgb.mul(nightDim);

            float dynamicBlend = Mth.clamp(vanillaBlendRatio + (rainLevel * 0.20F) + (thunderLevel * 0.25F), 0.0F, 0.85F);
            org.joml.Vector3f targetColor = customRgb.lerp(vanillaRgb, dynamicBlend);

            if (currentFogStart < 0.0F) {
                currentFogStart = targetStart;
                currentFogEnd = targetEnd;
                currentFogColor.set(targetColor);
            } else {
                currentFogStart = Mth.lerp(alpha, currentFogStart, targetStart);
                currentFogEnd = Mth.lerp(alpha, currentFogEnd, targetEnd);
                currentFogColor.lerp(targetColor, alpha);
            }
        }

        // FOG_SKY: Płynny horyzont nieba bez ostrych krawędzi i skoków
        if (fogMode == net.minecraft.client.renderer.FogRenderer.FogMode.FOG_SKY) {
            float skyEnd = currentFogEnd > 0.0F ? Math.max(32.0F, currentFogEnd) : original.end();
            return new net.minecraft.client.renderer.FogParameters(
                    0.0F,
                    skyEnd,
                    com.mojang.blaze3d.shaders.FogShape.SPHERE,
                    Mth.clamp(currentFogColor.x >= 0.0F ? currentFogColor.x : original.red(), 0.0F, 1.0F),
                    Mth.clamp(currentFogColor.y >= 0.0F ? currentFogColor.y : original.green(), 0.0F, 1.0F),
                    Mth.clamp(currentFogColor.z >= 0.0F ? currentFogColor.z : original.blue(), 0.0F, 1.0F),
                    original.alpha()
            );
        }

        // FOG_TERRAIN: FogShape.SPHERE eliminuje zniekształcenia pionowe przy podskokach i ruchach kamery!
        return new net.minecraft.client.renderer.FogParameters(
                currentFogStart > 0.0F ? currentFogStart : original.start(),
                currentFogEnd > 0.0F ? currentFogEnd : original.end(),
                com.mojang.blaze3d.shaders.FogShape.SPHERE,
                Mth.clamp(currentFogColor.x >= 0.0F ? currentFogColor.x : original.red(), 0.0F, 1.0F),
                Mth.clamp(currentFogColor.y >= 0.0F ? currentFogColor.y : original.green(), 0.0F, 1.0F),
                Mth.clamp(currentFogColor.z >= 0.0F ? currentFogColor.z : original.blue(), 0.0F, 1.0F),
                original.alpha()
        );
    }

    public static boolean isCustomSkyEnabled() {
        return Baritone.settings() != null ? Baritone.settings().customSky.value : customSkyEnabled;
    }
    public static void setCustomSkyEnabled(boolean enabled) {
        customSkyEnabled = enabled;
        if (Baritone.settings() != null) {
            Baritone.settings().customSky.value = enabled;
        }
    }
    public static int getCustomSkyMode() { return customSkyMode; }
    public static void setCustomSkyMode(int mode) { customSkyMode = mode; }

    public static boolean isCelestialStarsEnabled() {
        return Baritone.settings() != null ? Baritone.settings().customCelestialStars.value : true;
    }

    /**
     * Zwraca kolor górnej kopuły nieba (zenit) w pełnej harmonii z wybranym motywem:
     */
    public static float[] getCustomSkyRgb() {
        float time = (System.currentTimeMillis() % 100000L) / 1000.0F;
        float wave = (Mth.sin(time * 0.45F) + 1.0F) * 0.5F;
        float r, g, b;

        switch (customSkyMode) {
            case 0 -> { // Cyber Lilac & Cyan (Zenith: neon indigo/violet)
                r = Mth.lerp(wave, 0.16F, 0.28F);
                g = Mth.lerp(wave, 0.02F, 0.06F);
                b = Mth.lerp(wave, 0.38F, 0.55F);
            }
            case 1 -> { // Synthwave Sunset (Zenith: retro twilight purple)
                r = Mth.lerp(wave, 0.26F, 0.45F);
                g = Mth.lerp(wave, 0.04F, 0.08F);
                b = Mth.lerp(wave, 0.42F, 0.58F);
            }
            case 2 -> { // Deep Galaxy (Zenith: abyssal space black & starlight indigo)
                r = Mth.lerp(wave, 0.03F, 0.08F);
                g = Mth.lerp(wave, 0.01F, 0.03F);
                b = Mth.lerp(wave, 0.14F, 0.26F);
            }
            case 3 -> { // Aurora Borealis (Zenith: polar dark teal)
                r = Mth.lerp(wave, 0.01F, 0.04F);
                g = Mth.lerp(wave, 0.08F, 0.18F);
                b = Mth.lerp(wave, 0.16F, 0.30F);
            }
            case 4 -> { // Blood Eclipse (Zenith: crimson shadow)
                r = Mth.lerp(wave, 0.22F, 0.40F);
                g = Mth.lerp(wave, 0.01F, 0.03F);
                b = Mth.lerp(wave, 0.04F, 0.08F);
            }
            case 5 -> { // Ethereal Void (Zenith: dreamy pastel amethyst)
                r = Mth.lerp(wave, 0.25F, 0.38F);
                g = Mth.lerp(wave, 0.22F, 0.32F);
                b = Mth.lerp(wave, 0.42F, 0.54F);
            }
            case 6 -> { // Chroma Rainbow
                int rgb = Mth.hsvToRgb((time * 0.08F) % 1.0F, 0.65F, 0.85F);
                r = ((rgb >> 16) & 0xFF) / 255.0F;
                g = ((rgb >> 8) & 0xFF) / 255.0F;
                b = (rgb & 0xFF) / 255.0F;
            }
            default -> {
                r = 0.16F; g = 0.02F; b = 0.38F;
            }
        }
        return new float[]{r, g, b};
    }

    /**
     * Zwraca kolor dolnego dysku (renderDarkDisc / otchłań pod horyzontem):
     * Zastępuje domyślną czarną plamę (0,0,0) miękkim, głębokim zmierzchem, eliminując czarną szczelinę na horyzoncie!
     */
    public static float[] getCustomDarkDiscRgb() {
        float[] sky = getCustomSkyRgb();
        // Dolna półkula otrzymuje 28% jasności górnej kopuły, stapiając się idealnie z mgłą
        return new float[]{sky[0] * 0.28F, sky[1] * 0.28F, sky[2] * 0.28F};
    }

    /**
     * Zwraca kolor i jasność dla gwiazd (renderStars):
     * Zapewnia subtelny blask i gwiezdne migotanie w barwach dopasowanych do motywu kosmicznego.
     */
    public static float[] getCustomStarTint(float baseStarBrightness) {
        float time = (System.currentTimeMillis() % 100000L) / 1000.0F;
        float twinkle = (float) (Math.sin(time * 3.5F) * 0.12F + 0.88F);
        float alpha = Math.max(baseStarBrightness, 0.40F) * twinkle;

        float r = 1.0F, g = 1.0F, b = 1.0F;
        switch (customSkyMode) {
            case 0 -> { r = 0.60F; g = 0.95F; b = 1.00F; } // Cyber cyan diamond
            case 1 -> { r = 1.00F; g = 0.80F; b = 0.50F; } // Solar amber dust
            case 2 -> { r = 0.88F; g = 0.75F; b = 1.00F; } // Nebula violet stardust
            case 3 -> { r = 0.65F; g = 1.00F; b = 0.85F; } // Emerald polar glint
            case 4 -> { r = 1.00F; g = 0.45F; b = 0.45F; } // Crimson spark
            case 5 -> { r = 0.95F; g = 0.92F; b = 1.00F; } // Pastel starlight
            case 6 -> { // Chroma twinkle
                int rgb = Mth.hsvToRgb((time * 0.15F) % 1.0F, 0.40F, 1.0F);
                r = ((rgb >> 16) & 0xFF) / 255.0F;
                g = ((rgb >> 8) & 0xFF) / 255.0F;
                b = (rgb & 0xFF) / 255.0F;
            }
        }
        return new float[]{r * alpha, g * alpha, b * alpha, alpha};
    }

    public static int getCustomSkyColor(int original) {
        if (!isCustomSkyEnabled()) return original;
        float[] rgb = getCustomSkyRgb();
        int r = (int) (rgb[0] * 255.0F);
        int g = (int) (rgb[1] * 255.0F);
        int b = (int) (rgb[2] * 255.0F);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public static boolean isAmbientParticlesEnabled() {
        return Baritone.settings() != null ? Baritone.settings().ambientParticles.value : ambientParticlesEnabled;
    }
    public static void setAmbientParticlesEnabled(boolean enabled) {
        ambientParticlesEnabled = enabled;
        if (Baritone.settings() != null) {
            Baritone.settings().ambientParticles.value = enabled;
        }
    }
}
