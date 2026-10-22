package com.astra.revolution.schematic;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custom MultiBufferSource for rendering BlockEntity holograms.
 * 1. Allocates isolated ByteBufferBuilders per RenderType to avoid Tesselator corruption.
 * 2. Wraps every VertexConsumer to modulate vertex RGB and alpha (e.g., 65% opacity or orange mismatch tint).
 * 3. Overrides NO_TRANSPARENCY on cutout/solid entity sheets (chests, shulkers, signs, banners, heads)
 *    with GL_BLEND so all BlockEntity models render as translucent ghost holograms.
 */
public final class GhostBlockEntityBufferSource implements MultiBufferSource, AutoCloseable {

    private static final class BufferBucket implements AutoCloseable {
        final ByteBufferBuilder allocator = new ByteBufferBuilder(64 * 1024);
        BufferBuilder builder;
        boolean hasVertices = false;

        BufferBuilder getOrStart(RenderType renderType) {
            if (builder == null) {
                builder = new BufferBuilder(allocator, renderType.mode(), renderType.format());
            }
            hasVertices = true;
            return builder;
        }

        @Override
        public void close() {
            allocator.close();
        }
    }

    private final Map<RenderType, BufferBucket> buckets = new LinkedHashMap<>();
    private final GhostTintVertexConsumer tintWrapper = new GhostTintVertexConsumer();

    private float redMul = 0.88F;
    private float greenMul = 0.96F;
    private float blueMul = 1.00F;
    private float alphaMul = 0.65F;

    /**
     * Sets the active color multiplier and opacity before invoking BlockEntityRenderDispatcher.
     */
    public void setActiveTint(float r, float g, float b, float a) {
        this.redMul = r;
        this.greenMul = g;
        this.blueMul = b;
        this.alphaMul = a;
    }

    @Override
    public VertexConsumer getBuffer(RenderType renderType) {
        BufferBucket bucket = buckets.computeIfAbsent(renderType, rt -> new BufferBucket());
        BufferBuilder rawBuilder = bucket.getOrStart(renderType);
        return tintWrapper.bind(rawBuilder, redMul, greenMul, blueMul, alphaMul);
    }

    /**
     * Flushes all accumulated BlockEntity RenderType streams with forced translucent alpha blending.
     */
    public void flushAllTranslucent() {
        for (Map.Entry<RenderType, BufferBucket> entry : buckets.entrySet()) {
            RenderType renderType = entry.getKey();
            BufferBucket bucket = entry.getValue();

            if (!bucket.hasVertices || bucket.builder == null) {
                continue;
            }

            try (MeshData mesh = bucket.builder.build()) {
                bucket.builder = null;
                bucket.hasVertices = false;

                if (mesh != null) {
                    // Bind the RenderType's texture atlas/sheet, lightmap, and shader program
                    renderType.setupRenderState();

                    // Force translucent alpha blending even if the sheet uses entityCutout or entitySolid
                    RenderSystem.enableBlend();
                    RenderSystem.blendFuncSeparate(
                            GlStateManager.SourceFactor.SRC_ALPHA,
                            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                            GlStateManager.SourceFactor.ONE,
                            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
                    );
                    RenderSystem.enableDepthTest();
                    RenderSystem.depthMask(false);

                    BufferUploader.drawWithShader(mesh);

                    RenderSystem.depthMask(true);
                    renderType.clearRenderState();
                }
            }
        }
    }

    @Override
    public void close() {
        for (BufferBucket bucket : buckets.values()) {
            bucket.close();
        }
        buckets.clear();
    }

    /**
     * Intercepts both standard VertexConsumer calls and ModelPart's 11-argument addVertex fast path.
     */
    private static final class GhostTintVertexConsumer implements VertexConsumer {
        private VertexConsumer delegate;
        private float rMul, gMul, bMul, aMul;

        GhostTintVertexConsumer bind(VertexConsumer delegate, float r, float g, float b, float a) {
            this.delegate = delegate;
            this.rMul = r;
            this.gMul = g;
            this.bMul = b;
            this.aMul = a;
            return this;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            int mr = Math.clamp((int) (red * rMul), 0, 255);
            int mg = Math.clamp((int) (green * gMul), 0, 255);
            int mb = Math.clamp((int) (blue * bMul), 0, 255);
            int ma = Math.clamp((int) (alpha * aMul), 0, 255);
            delegate.setColor(mr, mg, mb, ma);
            return this;
        }

        @Override
        public VertexConsumer setColor(int colorARGB) {
            delegate.setColor(modulatePackedArgb(colorARGB));
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float normalX, float normalY, float normalZ) {
            delegate.setNormal(normalX, normalY, normalZ);
            return this;
        }

        @Override
        public void addVertex(
                float x, float y, float z,
                int color,
                float u, float v,
                int overlayCoords,
                int lightCoords,
                float normalX, float normalY, float normalZ
        ) {
            delegate.addVertex(
                    x, y, z,
                    modulatePackedArgb(color),
                    u, v,
                    overlayCoords,
                    lightCoords,
                    normalX, normalY, normalZ
            );
        }

        private int modulatePackedArgb(int color) {
            int a = (color >>> 24) & 0xFF;
            int r = (color >>> 16) & 0xFF;
            int g = (color >>> 8) & 0xFF;
            int b = color & 0xFF;

            int ma = Math.clamp((int) (a * aMul), 0, 255);
            int mr = Math.clamp((int) (r * rMul), 0, 255);
            int mg = Math.clamp((int) (g * gMul), 0, 255);
            int mb = Math.clamp((int) (b * bMul), 0, 255);

            return (ma << 24) | (mr << 16) | (mg << 8) | mb;
        }
    }
}
