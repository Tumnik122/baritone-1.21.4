package com.astra.revolution.schematic;

import baritone.Baritone;
import baritone.api.event.events.RenderEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Pillar 4: Unified 3D Ghost Hologram Renderer supporting both BakedModel blocks and BlockEntity models
 * (chests, shulker boxes, signs with text, banners with patterns, skulls/heads, beds, bells, pots).
 */
public final class SchematicHologramRenderer implements AbstractGameEventListener, AutoCloseable {

    public enum SliceMode {
        ALL_LAYERS("All Layers"),
        SINGLE_LAYER("Single Layer"),
        UP_TO_LAYER("Up To Layer");

        private final String displayName;

        SliceMode(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public SliceMode next() {
            SliceMode[] vals = values();
            return vals[(this.ordinal() + 1) % vals.length];
        }
    }

    private static final float GHOST_MODEL_ALPHA = 0.65F;
    private static final Direction[] CULL_DIRECTIONS = Direction.values();

    // Isolated native memory arenas for BakedModel quads, translucent boxes, and wireframes
    private final ByteBufferBuilder texturedModelAllocator = new ByteBufferBuilder(2 * 1024 * 1024);
    private final ByteBufferBuilder translucentBoxAllocator = new ByteBufferBuilder(512 * 1024);
    private final ByteBufferBuilder wireframeLineAllocator = new ByteBufferBuilder(512 * 1024);

    // Virtual BlockEntity instance cache & custom translucent MultiBufferSource
    private final SchematicBlockEntityCache blockEntityCache = new SchematicBlockEntityCache();
    private final GhostBlockEntityBufferSource blockEntityBufferSource = new GhostBlockEntityBufferSource();

    private SliceMode sliceMode = SliceMode.ALL_LAYERS;
    private int currentLayerY = 0;
    private boolean renderCorrectBlocks = false;
    private boolean hologramVisible = true;
    private static SchematicHologramRenderer INSTANCE;

    public static SchematicHologramRenderer getInstance() {
        return INSTANCE;
    }

    public SchematicHologramRenderer(Baritone baritone) {
        INSTANCE = this;
        baritone.getGameEventHandler().registerEventListener(this);
    }

    @Override
    public void onTick(TickEvent event) {
        // Ticked centrally from MixinMinecraft tick lifecycle
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        AstraSchematicManager mgr = AstraSchematicManager.getInstance();
        SelectionManager sel = mgr.getSelectionManager();

        boolean hasSchematic = mgr.isHologramEnabled() && mgr.hasLoadedSchematic();
        boolean hasSelection = sel != null && sel.hasAnySelection();

        if (!hasSchematic && !hasSelection) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }

        Camera camera = mc.gameRenderer.getMainCamera();
        PoseStack poseStack = event.getModelViewStack();

        // Synchronize slice parameters from AstraSchematicManager
        this.sliceMode = switch (mgr.getSliceMode()) {
            case ALL_LAYERS -> SliceMode.ALL_LAYERS;
            case SINGLE_LAYER -> SliceMode.SINGLE_LAYER;
            case UP_TO_LAYER -> SliceMode.UP_TO_LAYER;
        };
        this.currentLayerY = mgr.getCurrentLayerY();
        this.renderCorrectBlocks = mgr.isRenderCorrectBlocks();
        this.hologramVisible = mgr.isHologramEnabled();

        renderWorldOverlay(
                poseStack,
                camera,
                mgr.getActiveSchematic(),
                mgr.getPlacementOrigin(),
                sel
        );
    }

    public SliceMode getSliceMode() {
        return sliceMode;
    }

    public int getCurrentLayerY() {
        return currentLayerY;
    }

    public void setSliceMode(SliceMode sliceMode, int layerY) {
        this.sliceMode = sliceMode;
        this.currentLayerY = Math.max(0, layerY);
    }

    public boolean isRenderCorrectBlocks() {
        return renderCorrectBlocks;
    }

    public void setRenderCorrectBlocks(boolean renderCorrectBlocks) {
        this.renderCorrectBlocks = renderCorrectBlocks;
    }

    public boolean isHologramVisible() {
        return hologramVisible;
    }

    public void setHologramVisible(boolean hologramVisible) {
        this.hologramVisible = hologramVisible;
    }

    public void renderWorldOverlay(
            PoseStack poseStack,
            Camera camera,
            AstraSchematic schematic,
            BlockPos origin,
            SelectionManager selectionManager
    ) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            return;
        }

        blockEntityCache.validateCacheState(level, schematic, origin);

        Vec3 camPos = camera.getPosition();
        float partialTick = mc.getDeltaTracker() != null
                ? mc.getDeltaTracker().getGameTimeDeltaPartialTick(false)
                : 0.0F;

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        BufferBuilder modelBuffer = new BufferBuilder(
                texturedModelAllocator,
                VertexFormat.Mode.QUADS,
                DefaultVertexFormat.BLOCK
        );
        BufferBuilder boxBuffer = new BufferBuilder(
                translucentBoxAllocator,
                VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_COLOR
        );
        BufferBuilder lineBuffer = new BufferBuilder(
                wireframeLineAllocator,
                VertexFormat.Mode.LINES,
                DefaultVertexFormat.POSITION_COLOR_NORMAL
        );

        boolean hasModelGeometry = false;
        boolean hasBlockEntityGeometry = false;
        boolean hasBoxGeometry = false;
        boolean hasLineGeometry = false;

        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
        BlockEntityRenderDispatcher blockEntityDispatcher = mc.getBlockEntityRenderDispatcher();
        BlockColors blockColors = mc.getBlockColors();
        RandomSource random = RandomSource.create();

        if (hologramVisible && schematic != null && origin != null) {
            int minY = (sliceMode == SliceMode.SINGLE_LAYER) ? currentLayerY : 0;
            int maxY = switch (sliceMode) {
                case ALL_LAYERS -> schematic.getHeight() - 1;
                case SINGLE_LAYER, UP_TO_LAYER -> Math.min(schematic.getHeight() - 1, currentLayerY);
            };

            for (int y = minY; y <= maxY; y++) {
                for (int z = 0; z < schematic.getLength(); z++) {
                    for (int x = 0; x < schematic.getWidth(); x++) {
                        BlockState targetState = schematic.getTransformedState(x, y, z);
                        BlockPos worldPos = origin.offset(x, y, z);

                        if (camPos.distanceToSqr(
                                worldPos.getX() + 0.5D,
                                worldPos.getY() + 0.5D,
                                worldPos.getZ() + 0.5D
                        ) > 4096.0D) {
                            continue;
                        }

                        BlockState worldState = level.getBlockState(worldPos);
                        PlacementStatus status = evaluatePlacementStatus(level, worldPos, worldState, targetState);

                        if (status == PlacementStatus.EMPTY_IGNORE) {
                            continue;
                        }

                        AABB blockBox = new AABB(worldPos).deflate(0.004D);

                        switch (status) {
                            case CORRECT -> {
                                if (renderCorrectBlocks) {
                                    emitWireframeBox(poseStack.last(), lineBuffer, blockBox, 0.18F, 0.95F, 0.42F, 0.35F);
                                    hasLineGeometry = true;
                                }
                            }
                            case MISSING -> {
                                // 1. Emit standard BakedModel quads (if RenderShape.MODEL)
                                if (emitGhostBlockModel(
                                        poseStack, modelBuffer, blockRenderer, blockColors,
                                        level, schematic, x, y, z, worldPos, targetState, random,
                                        0.88F, 0.96F, 1.00F
                                )) {
                                    hasModelGeometry = true;
                                }

                                // 2. Emit BlockEntity 3D Model (chests, shulkers, signs, banners, heads, bells, beds)
                                if (emitGhostBlockEntity(
                                        poseStack, blockEntityDispatcher, level, schematic,
                                        x, y, z, worldPos, targetState, partialTick,
                                        0.88F, 0.96F, 1.00F, GHOST_MODEL_ALPHA
                                )) {
                                    hasBlockEntityGeometry = true;
                                }

                                emitWireframeBox(poseStack.last(), lineBuffer, blockBox, 0.15F, 0.85F, 1.00F, 0.45F);
                                hasLineGeometry = true;
                            }
                            case WRONG_BLOCK -> {
                                emitFilledBox(poseStack.last().pose(), boxBuffer, blockBox, 1.00F, 0.18F, 0.25F, 0.38F);
                                emitWireframeBox(poseStack.last(), lineBuffer, blockBox, 1.00F, 0.22F, 0.30F, 0.95F);
                                hasBoxGeometry = true;
                                hasLineGeometry = true;
                            }
                            case STATE_MISMATCH -> {
                                // Render expected state's BakedModel & BlockEntity with warm amber-orange tint
                                if (emitGhostBlockModel(
                                        poseStack, modelBuffer, blockRenderer, blockColors,
                                        level, schematic, x, y, z, worldPos, targetState, random,
                                        1.00F, 0.72F, 0.30F
                                )) {
                                    hasModelGeometry = true;
                                }

                                if (emitGhostBlockEntity(
                                        poseStack, blockEntityDispatcher, level, schematic,
                                        x, y, z, worldPos, targetState, partialTick,
                                        1.00F, 0.68F, 0.25F, 0.58F
                                )) {
                                    hasBlockEntityGeometry = true;
                                }

                                emitWireframeBox(poseStack.last(), lineBuffer, blockBox, 1.00F, 0.58F, 0.08F, 0.98F);
                                emitMismatchOrientationArrow(poseStack.last(), lineBuffer, worldPos, targetState);
                                hasLineGeometry = true;
                            }
                            default -> {}
                        }
                    }
                }
            }
        }

        // Render Area Selection Boxes
        if (selectionManager != null && selectionManager.hasAnySelection()) {
            if (selectionManager.getCorner1() != null) {
                AABB c1 = new AABB(selectionManager.getCorner1()).inflate(0.006D);
                emitFilledBox(poseStack.last().pose(), boxBuffer, c1, 0.62F, 0.31F, 0.87F, 0.32F);
                emitWireframeBox(poseStack.last(), lineBuffer, c1, 0.78F, 0.49F, 1.00F, 1.00F);
                hasBoxGeometry = true;
                hasLineGeometry = true;
            }
            if (selectionManager.getCorner2() != null) {
                AABB c2 = new AABB(selectionManager.getCorner2()).inflate(0.006D);
                emitFilledBox(poseStack.last().pose(), boxBuffer, c2, 0.00F, 0.85F, 0.95F, 0.32F);
                emitWireframeBox(poseStack.last(), lineBuffer, c2, 0.25F, 0.92F, 1.00F, 1.00F);
                hasBoxGeometry = true;
                hasLineGeometry = true;
            }
            if (selectionManager.hasCompleteSelection()) {
                AABB sel = selectionManager.getNormalizedBounds().inflate(0.008D);
                emitWireframeBox(poseStack.last(), lineBuffer, sel, 0.82F, 0.58F, 1.00F, 0.95F);
                hasLineGeometry = true;
            }
        }

        // Flush GPU Draw Passes
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
        );
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);

        // Pass 1: BakedModel Block Quads
        if (hasModelGeometry) {
            try (MeshData mesh = modelBuffer.build()) {
                if (mesh != null) {
                    RenderSystem.setShader(CoreShaders.RENDERTYPE_TRANSLUCENT);
                    RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
                    BufferUploader.drawWithShader(mesh);
                }
            }
        }

        // Pass 2: BlockEntity Models (Chests, Shulkers, Signs, Banners, Skulls, Beds, Bells)
        if (hasBlockEntityGeometry) {
            blockEntityBufferSource.flushAllTranslucent();
        }

        // Pass 3: Translucent Status Boxes
        RenderSystem.enableBlend();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        if (hasBoxGeometry) {
            try (MeshData mesh = boxBuffer.build()) {
                if (mesh != null) {
                    net.minecraft.client.renderer.ShaderProgram shader = baritone.utils.BaritoneShaderPrograms.SCHEMATIC_HOLO;
                    RenderSystem.setShader(shader != null ? shader : CoreShaders.POSITION_COLOR);
                    BufferUploader.drawWithShader(mesh);
                }
            }
        }

        // Pass 4: Wireframes & 3D Orientation Arrows
        if (hasLineGeometry) {
            try (MeshData mesh = lineBuffer.build()) {
                if (mesh != null) {
                    RenderSystem.setShader(CoreShaders.RENDERTYPE_LINES);
                    RenderSystem.lineWidth(2.0F);
                    BufferUploader.drawWithShader(mesh);
                }
            }
        }

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    /**
     * Renders a virtual BlockEntity via its registered BlockEntityRenderer into our
     * alpha-modulating GhostBlockEntityBufferSource.
     */
    @SuppressWarnings("unchecked")
    private <T extends BlockEntity> boolean emitGhostBlockEntity(
            PoseStack poseStack,
            BlockEntityRenderDispatcher dispatcher,
            Level level,
            AstraSchematic schematic,
            int sx, int sy, int sz,
            BlockPos worldPos,
            BlockState targetState,
            float partialTick,
            float tintR, float tintG, float tintB, float alpha
    ) {
        BlockEntity blockEntity = blockEntityCache.getOrCreateRenderableBlockEntity(
                level, schematic, sx, sy, sz, worldPos, targetState
        );
        if (blockEntity == null) {
            return false;
        }

        BlockEntityRenderer<T> renderer = (BlockEntityRenderer<T>) dispatcher.getRenderer(blockEntity);
        if (renderer == null) {
            return false;
        }

        blockEntityBufferSource.setActiveTint(tintR, tintG, tintB, alpha);
        poseStack.pushPose();
        poseStack.translate(worldPos.getX(), worldPos.getY(), worldPos.getZ());
        try {
            renderer.render(
                    (T) blockEntity,
                    partialTick,
                    poseStack,
                    blockEntityBufferSource,
                    LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY
            );
        } catch (Throwable ignored) {
            poseStack.popPose();
            return false;
        }
        poseStack.popPose();
        return true;
    }

    private boolean emitGhostBlockModel(
            PoseStack poseStack,
            VertexConsumer buffer,
            BlockRenderDispatcher dispatcher,
            BlockColors blockColors,
            Level level,
            AstraSchematic schematic,
            int sx, int sy, int sz,
            BlockPos worldPos,
            BlockState targetState,
            RandomSource random,
            float baseR, float baseG, float baseB
    ) {
        if (targetState.getRenderShape() != RenderShape.MODEL) {
            return false;
        }

        BakedModel model = dispatcher.getBlockModel(targetState);
        poseStack.pushPose();
        poseStack.translate(worldPos.getX(), worldPos.getY(), worldPos.getZ());
        PoseStack.Pose pose = poseStack.last();

        boolean emittedAny = false;

        for (Direction dir : CULL_DIRECTIONS) {
            if (shouldCullInteriorFace(schematic, sx, sy, sz, targetState, dir)) {
                continue;
            }
            random.setSeed(42L);
            List<BakedQuad> quads = model.getQuads(targetState, dir, random);
            if (!quads.isEmpty()) {
                emitQuadList(pose, buffer, quads, blockColors, level, worldPos, targetState, baseR, baseG, baseB);
                emittedAny = true;
            }
        }

        random.setSeed(42L);
        List<BakedQuad> unculledQuads = model.getQuads(targetState, null, random);
        if (!unculledQuads.isEmpty()) {
            emitQuadList(pose, buffer, unculledQuads, blockColors, level, worldPos, targetState, baseR, baseG, baseB);
            emittedAny = true;
        }

        poseStack.popPose();
        return emittedAny;
    }

    private boolean shouldCullInteriorFace(
            AstraSchematic schematic,
            int x, int y, int z,
            BlockState currentState,
            Direction face
    ) {
        if (!currentState.canOcclude()) {
            return false;
        }
        int nx = x + face.getStepX();
        int ny = y + face.getStepY();
        int nz = z + face.getStepZ();
        if (!schematic.isInBounds(nx, ny, nz)) {
            return false;
        }
        BlockState neighbor = schematic.getTransformedState(nx, ny, nz);
        return !neighbor.isAir() && neighbor.canOcclude();
    }

    private void emitQuadList(
            PoseStack.Pose pose,
            VertexConsumer buffer,
            List<BakedQuad> quads,
            BlockColors blockColors,
            Level level,
            BlockPos worldPos,
            BlockState state,
            float baseR, float baseG, float baseB
    ) {
        for (BakedQuad quad : quads) {
            float r = baseR;
            float g = baseG;
            float b = baseB;

            if (quad.isTinted()) {
                int tint = blockColors.getColor(state, level, worldPos, quad.getTintIndex());
                r *= ((tint >> 16) & 0xFF) / 255.0F;
                g *= ((tint >> 8) & 0xFF) / 255.0F;
                b *= (tint & 0xFF) / 255.0F;
            }

            buffer.putBulkData(
                    pose,
                    quad,
                    r, g, b,
                    GHOST_MODEL_ALPHA,
                    LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY
            );
        }
    }

    /**
     * Emits a 3D orientation arrow for both 6-way cardinal Direction blocks and 16-segment
     * radial blocks (Standing Signs, Standing Banners, Floor Skulls, Hanging Signs).
     */
    private void emitMismatchOrientationArrow(
            PoseStack.Pose pose,
            VertexConsumer lineConsumer,
            BlockPos pos,
            BlockState targetState
    ) {
        if (targetState.hasProperty(BlockStateProperties.ROTATION_16)) {
            int rotSegment = targetState.getValue(BlockStateProperties.ROTATION_16);
            float degrees = RotationSegment.convertToDegrees(rotSegment);
            float rad = degrees * Mth.DEG_TO_RAD;
            float dirX = -Mth.sin(rad);
            float dirZ = Mth.cos(rad);
            emitVectorArrow3D(pose, lineConsumer, pos, dirX, 0.0F, dirZ, 1.00F, 0.65F, 0.10F, 1.00F);
            return;
        }

        Direction dir = extractFacingDirection(targetState);
        if (dir != null) {
            emitVectorArrow3D(
                    pose,
                    lineConsumer,
                    pos,
                    dir.getStepX(),
                    dir.getStepY(),
                    dir.getStepZ(),
                    1.00F, 0.65F, 0.10F, 1.00F
            );
        }
    }

    private Direction extractFacingDirection(BlockState state) {
        if (state.getBlock() instanceof DiodeBlock && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
        }
        if (state.hasProperty(BlockStateProperties.FACING)) {
            return state.getValue(BlockStateProperties.FACING);
        }
        if (state.hasProperty(HorizontalDirectionalBlock.FACING)) {
            return state.getValue(HorizontalDirectionalBlock.FACING);
        }
        if (state.hasProperty(BlockStateProperties.FACING_HOPPER)) {
            return state.getValue(BlockStateProperties.FACING_HOPPER);
        }
        return null;
    }

    private static void emitVectorArrow3D(
            PoseStack.Pose pose,
            VertexConsumer lineConsumer,
            BlockPos pos,
            float dirX, float dirY, float dirZ,
            float r, float g, float b, float a
    ) {
        float cx = pos.getX() + 0.5F;
        float cy = pos.getY() + 0.65F;
        float cz = pos.getZ() + 0.5F;

        float dx = dirX * 0.38F;
        float dy = dirY * 0.38F;
        float dz = dirZ * 0.38F;

        float startX = cx - dx * 0.7F;
        float startY = cy - dy * 0.7F;
        float startZ = cz - dz * 0.7F;

        float tipX = cx + dx;
        float tipY = cy + dy;
        float tipZ = cz + dz;

        emitLine(pose, lineConsumer, startX, startY, startZ, tipX, tipY, tipZ, r, g, b, a);

        // Perpendicular horizontal wing vector
        float perpX = -dz * 0.45F;
        float perpZ = dx * 0.45F;
        float perpY = (Math.abs(dirY) > 0.5F) ? 0.16F : 0.0F;
        if (Math.abs(dirY) > 0.5F) {
            perpX = 0.16F;
        }

        emitLine(pose, lineConsumer, tipX, tipY, tipZ,
                tipX - dx * 0.45F + perpX, tipY - dy * 0.45F + perpY, tipZ - dz * 0.45F + perpZ, r, g, b, a);
        emitLine(pose, lineConsumer, tipX, tipY, tipZ,
                tipX - dx * 0.45F - perpX, tipY - dy * 0.45F - perpY, tipZ - dz * 0.45F - perpZ, r, g, b, a);
    }

    private PlacementStatus evaluatePlacementStatus(Level level, BlockPos pos, BlockState worldState, BlockState targetState) {
        if (targetState == null || targetState.isAir()) {
            if (worldState == null || worldState.isAir()) {
                return PlacementStatus.EMPTY_IGNORE;
            }
            if (worldState.getBlock() instanceof LiquidBlock || !worldState.getFluidState().isEmpty()) {
                return PlacementStatus.EMPTY_IGNORE;
            }
            return PlacementStatus.WRONG_BLOCK;
        }

        if (targetState.is(Blocks.WATER) && worldState != null && worldState.getFluidState().is(FluidTags.WATER)) {
            return PlacementStatus.CORRECT;
        }
        if (targetState.is(Blocks.LAVA) && worldState != null && worldState.getFluidState().is(FluidTags.LAVA)) {
            return PlacementStatus.CORRECT;
        }

        if (AstraSchematic.matchesStateSemantics(level, pos, worldState, targetState)) {
            return PlacementStatus.CORRECT;
        }
        if (worldState == null || worldState.isAir() || worldState.canBeReplaced()) {
            return PlacementStatus.MISSING;
        }
        if (worldState.is(targetState.getBlock())) {
            return PlacementStatus.STATE_MISMATCH;
        }
        return PlacementStatus.WRONG_BLOCK;
    }

    private static void emitFilledBox(Matrix4f matrix, VertexConsumer consumer, AABB box, float r, float g, float b, float a) {
        float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
        float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;

        consumer.addVertex(matrix, x0, y0, z0).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1, y0, z0).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1, y0, z1).setColor(r, g, b, a);
        consumer.addVertex(matrix, x0, y0, z1).setColor(r, g, b, a);

        consumer.addVertex(matrix, x0, y1, z0).setColor(r, g, b, a);
        consumer.addVertex(matrix, x0, y1, z1).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1, y1, z0).setColor(r, g, b, a);

        consumer.addVertex(matrix, x0, y0, z0).setColor(r, g, b, a);
        consumer.addVertex(matrix, x0, y1, z0).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1, y1, z0).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1, y0, z0).setColor(r, g, b, a);

        consumer.addVertex(matrix, x0, y0, z1).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1, y0, z1).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        consumer.addVertex(matrix, x0, y1, z1).setColor(r, g, b, a);

        consumer.addVertex(matrix, x0, y0, z0).setColor(r, g, b, a);
        consumer.addVertex(matrix, x0, y0, z1).setColor(r, g, b, a);
        consumer.addVertex(matrix, x0, y1, z1).setColor(r, g, b, a);
        consumer.addVertex(matrix, x0, y1, z0).setColor(r, g, b, a);

        consumer.addVertex(matrix, x1, y0, z0).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1, y1, z0).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1, y0, z1).setColor(r, g, b, a);
    }

    private static void emitWireframeBox(PoseStack.Pose pose, VertexConsumer consumer, AABB box, float r, float g, float b, float a) {
        float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
        float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;

        emitLine(pose, consumer, x0, y0, z0, x1, y0, z0, r, g, b, a);
        emitLine(pose, consumer, x1, y0, z0, x1, y0, z1, r, g, b, a);
        emitLine(pose, consumer, x1, y0, z1, x0, y0, z1, r, g, b, a);
        emitLine(pose, consumer, x0, y0, z1, x0, y0, z0, r, g, b, a);

        emitLine(pose, consumer, x0, y1, z0, x1, y1, z0, r, g, b, a);
        emitLine(pose, consumer, x1, y1, z0, x1, y1, z1, r, g, b, a);
        emitLine(pose, consumer, x1, y1, z1, x0, y1, z1, r, g, b, a);
        emitLine(pose, consumer, x0, y1, z1, x0, y1, z0, r, g, b, a);

        emitLine(pose, consumer, x0, y0, z0, x0, y1, z0, r, g, b, a);
        emitLine(pose, consumer, x1, y0, z0, x1, y1, z0, r, g, b, a);
        emitLine(pose, consumer, x1, y0, z1, x1, y1, z1, r, g, b, a);
        emitLine(pose, consumer, x0, y0, z1, x0, y1, z1, r, g, b, a);
    }

    private static void emitLine(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float r, float g, float b, float a
    ) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float dz = z1 - z0;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 1.0E-5F) {
            dx /= len;
            dy /= len;
            dz /= len;
        }
        consumer.addVertex(pose, x0, y0, z0).setColor(r, g, b, a).setNormal(pose, dx, dy, dz);
        consumer.addVertex(pose, x1, y1, z1).setColor(r, g, b, a).setNormal(pose, dx, dy, dz);
    }

    @Override
    public void close() {
        texturedModelAllocator.close();
        translucentBoxAllocator.close();
        wireframeLineAllocator.close();
        blockEntityBufferSource.close();
        blockEntityCache.clear();
    }

    private enum PlacementStatus {
        EMPTY_IGNORE,
        CORRECT,
        MISSING,
        WRONG_BLOCK,
        STATE_MISMATCH
    }
}
