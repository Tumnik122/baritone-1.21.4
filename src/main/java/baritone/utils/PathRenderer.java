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

package baritone.utils;

import baritone.api.BaritoneAPI;
import baritone.api.event.events.RenderEvent;
import baritone.api.pathing.goals.*;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.behavior.PathingBehavior;
import baritone.pathing.path.PathExecutor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * @author Brady
 * @since 8/9/2018
 */
public final class PathRenderer implements IRenderer {

    private static final ResourceLocation TEXTURE_BEACON_BEAM = ResourceLocation.parse("textures/entity/beacon_beam.png");


    private PathRenderer() {}

    public static double posX() {
        return renderManager.renderPosX();
    }

    public static double posY() {
        return renderManager.renderPosY();
    }

    public static double posZ() {
        return renderManager.renderPosZ();
    }

    public static void render(RenderEvent event, PathingBehavior behavior) {
        final IPlayerContext ctx = behavior.ctx;
        if (ctx.world() == null) {
            return;
        }
        if (ctx.minecraft().screen instanceof GuiClick) {
            ((GuiClick) ctx.minecraft().screen).onRender(event.getModelViewStack(), event.getProjectionMatrix());
        }

        final float partialTicks = event.getPartialTicks();
        final Goal goal = behavior.getGoal();

        final DimensionType thisPlayerDimension = ctx.world().dimensionType();
        final DimensionType currentRenderViewDimension = BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext().world().dimensionType();

        if (thisPlayerDimension != currentRenderViewDimension) {
            // this is a path for a bot in a different dimension, don't render it
            return;
        }

        if (goal != null && settings.renderGoal.value) {
            drawGoal(event.getModelViewStack(), ctx, goal, partialTicks, settings.colorGoalBox.value);
            // Orbiting particles around goal
            if (settings.renderGoalAnimated.value) {
                drawGoalOrbitParticles(event.getModelViewStack(), ctx, goal);
            }
        }

        if (!settings.renderPath.value) {
            return;
        }

        PathExecutor current = behavior.getCurrent(); // this should prevent most race conditions?
        PathExecutor next = behavior.getNext(); // like, now it's not possible for current!=null to be true, then suddenly false because of another thread
        if (current != null && settings.renderSelectionBoxes.value) {
            // toBreak: pulsing alpha for prominence
            drawManySelectionBoxesPulsed(event.getModelViewStack(), ctx.player(), current.toBreak(), settings.colorBlocksToBreak.value);
            drawManySelectionBoxes(event.getModelViewStack(), ctx.player(), current.toPlace(), settings.colorBlocksToPlace.value);
            drawManySelectionBoxes(event.getModelViewStack(), ctx.player(), current.toWalkInto(), settings.colorBlocksToWalkInto.value);
        }

        //drawManySelectionBoxes(player, Collections.singletonList(behavior.pathStart()), partialTicks, Color.WHITE);

        // Render the current path with gradient color
        if (current != null && current.getPath() != null) {
            int renderBegin = Math.max(current.getPosition() - 3, 0);
            drawPathGradient(event.getModelViewStack(), current.getPath().positions(), renderBegin, settings.fadePath.value, 10, 20);
        }

        if (next != null && next.getPath() != null) {
            drawPath(event.getModelViewStack(), next.getPath().positions(), 0, settings.colorNextPath.value, settings.fadePath.value, 10, 20);
        }

        // If there is a path calculation currently running, render the path calculation process
        behavior.getInProgress().ifPresent(currentlyRunning -> {
            currentlyRunning.bestPathSoFar().ifPresent(p -> {
                drawPath(event.getModelViewStack(), p.positions(), 0, settings.colorBestPathSoFar.value, settings.fadePath.value, 10, 20);
            });

            currentlyRunning.pathToMostRecentNodeConsidered().ifPresent(mr -> {
                drawPath(event.getModelViewStack(), mr.positions(), 0, settings.colorMostRecentConsidered.value, settings.fadePath.value, 10, 20);
                drawManySelectionBoxes(event.getModelViewStack(), ctx.player(), Collections.singletonList(mr.getDest()), settings.colorMostRecentConsidered.value);
            });
        });
    }

    public static void drawPath(PoseStack stack, List<BetterBlockPos> positions, int startIndex, Color color, boolean fadeOut, int fadeStart0, int fadeEnd0) {
        drawPath(stack, positions, startIndex, color, fadeOut, fadeStart0, fadeEnd0, 0.5D);
    }

    public static void drawPath(PoseStack stack, List<BetterBlockPos> positions, int startIndex, Color color, boolean fadeOut, int fadeStart0, int fadeEnd0, double offset) {
        if (settings.renderShaderEffects.value) {
            // A wide, low-alpha pass gives the custom fragment shader a visible halo without a costly post-process pass.
            drawPathPass(stack, positions, startIndex, color, fadeOut, fadeStart0, fadeEnd0, offset, 2.35F, 0.24F);
        }
        drawPathPass(stack, positions, startIndex, color, fadeOut, fadeStart0, fadeEnd0, offset, 1.0F, 1.0F);
    }

    private static void drawPathPass(PoseStack stack, List<BetterBlockPos> positions, int startIndex, Color color, boolean fadeOut,
                                     int fadeStart0, int fadeEnd0, double offset, float lineWidthMultiplier, float alphaMultiplier) {
        BufferBuilder bufferBuilder = IRenderer.startLines(
                color,
                0.4F * alphaMultiplier,
                settings.pathRenderLineWidthPixels.value * lineWidthMultiplier,
                settings.renderPathIgnoreDepth.value
        );

        int fadeStart = fadeStart0 + startIndex;
        int fadeEnd = fadeEnd0 + startIndex;

        for (int i = startIndex, next; i < positions.size() - 1; i = next) {
            BetterBlockPos start = positions.get(i);
            BetterBlockPos end = positions.get(next = i + 1);

            int dirX = end.x - start.x;
            int dirY = end.y - start.y;
            int dirZ = end.z - start.z;

            while (next + 1 < positions.size() && (!fadeOut || next + 1 < fadeStart) &&
                    (dirX == positions.get(next + 1).x - end.x &&
                            dirY == positions.get(next + 1).y - end.y &&
                            dirZ == positions.get(next + 1).z - end.z)) {
                end = positions.get(++next);
            }

            float alpha = 0.4F;
            if (fadeOut) {
                if (i <= fadeStart) {
                    alpha = 0.4F;
                } else {
                    if (i > fadeEnd) {
                        break;
                    }
                    alpha = 0.4F * (1.0F - (float) (i - fadeStart) / (float) (fadeEnd - fadeStart));
                }
            }

            if (settings.renderRainbowPath.value) {
                float hue = (float) ((System.currentTimeMillis() / 2500.0 + (double) i / 18.0) % 1.0);
                Color rainbowColor = Color.getHSBColor(hue, 0.85F, 1.0F);
                IRenderer.glColor(rainbowColor, (fadeOut ? alpha : 0.65F) * alphaMultiplier);
            } else if (fadeOut) {
                IRenderer.glColor(color, alpha * alphaMultiplier);
            }

            emitPathLine(bufferBuilder, stack, start.x, start.y, start.z, end.x, end.y, end.z, offset);
        }

        IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
    }

    private static void emitPathLine(BufferBuilder bufferBuilder, PoseStack stack, double x1, double y1, double z1, double x2, double y2, double z2, double offset) {
        final double extraOffset = offset + 0.03D;

        double vpX = posX();
        double vpY = posY();
        double vpZ = posZ();
        boolean renderPathAsFrickinThingy = !settings.renderPathAsLine.value;

        IRenderer.emitLine(bufferBuilder, stack,
                x1 + offset - vpX, y1 + offset - vpY, z1 + offset - vpZ,
                x2 + offset - vpX, y2 + offset - vpY, z2 + offset - vpZ
        );
        if (renderPathAsFrickinThingy) {
            IRenderer.emitLine(bufferBuilder, stack,
                    x2 + offset - vpX, y2 + offset - vpY, z2 + offset - vpZ,
                    x2 + offset - vpX, y2 + extraOffset - vpY, z2 + offset - vpZ
            );
            IRenderer.emitLine(bufferBuilder, stack,
                    x2 + offset - vpX, y2 + extraOffset - vpY, z2 + offset - vpZ,
                    x1 + offset - vpX, y1 + extraOffset - vpY, z1 + offset - vpZ
            );
            IRenderer.emitLine(bufferBuilder, stack,
                    x1 + offset - vpX, y1 + extraOffset - vpY, z1 + offset - vpZ,
                    x1 + offset - vpX, y1 + offset - vpY, z1 + offset - vpZ
            );
        }
    }

    public static void drawManySelectionBoxes(PoseStack stack, Entity player, Collection<BlockPos> positions, Color color) {
        if (positions.isEmpty()) {
            return;
        }
        BlockStateInterface bsi = new BlockStateInterface(BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext());

        // Render translucent shader-filled box if enabled
        if (settings.renderFilledBoxes.value) {
            float fillAlpha = settings.filledBoxAlpha.value;
            if (settings.renderBlockGlow.value && !settings.renderShaderEffects.value) {
                float pulse = (float) (0.8 + 0.2 * Math.sin(System.currentTimeMillis() / 180.0));
                fillAlpha = Math.min(1.0F, fillAlpha * pulse);
            }
            BufferBuilder filledBuilder = IRenderer.startFilled(color, fillAlpha, settings.renderSelectionBoxesIgnoreDepth.value);
            positions.forEach(pos -> {
                BlockState state = bsi.get0(pos);
                VoxelShape shape = state.getShape(player.level(), pos);
                AABB toDraw = shape.isEmpty() ? Shapes.block().bounds() : shape.bounds();
                toDraw = toDraw.move(pos);
                IRenderer.emitFilledAABB(filledBuilder, stack, toDraw);
            });
            IRenderer.endFilled(filledBuilder, settings.renderSelectionBoxesIgnoreDepth.value);
        }

        BufferBuilder bufferBuilder = IRenderer.startLines(color, settings.pathRenderLineWidthPixels.value, settings.renderSelectionBoxesIgnoreDepth.value);

        positions.forEach(pos -> {
            BlockState state = bsi.get0(pos);
            VoxelShape shape = state.getShape(player.level(), pos);
            AABB toDraw = shape.isEmpty() ? Shapes.block().bounds() : shape.bounds();
            toDraw = toDraw.move(pos);
            IRenderer.emitAABB(bufferBuilder, stack, toDraw, .002D);
        });

        IRenderer.endLines(bufferBuilder, settings.renderSelectionBoxesIgnoreDepth.value);
    }

    public static void drawGoal(PoseStack stack, IPlayerContext ctx, Goal goal, float partialTicks, Color color) {
        drawGoal(null, stack, ctx, goal, partialTicks, color, true);
    }

    private static void drawGoal(@Nullable BufferBuilder bufferBuilder, PoseStack stack, IPlayerContext ctx, Goal goal, float partialTicks, Color color, boolean setupRender) {
        if (!setupRender && bufferBuilder == null) {
            throw new RuntimeException("BufferBuilder must not be null if setupRender is false");
        }
        double renderPosX = posX();
        double renderPosY = posY();
        double renderPosZ = posZ();
        double minX, maxX;
        double minZ, maxZ;
        double minY, maxY;
        double y, y1, y2;
        if (!settings.renderGoalAnimated.value) {
            // y = 1 causes rendering issues when the player is at the same y as the top of a block for some reason
            y = 0.999F;
        } else {
            y = Mth.cos((float) (((float) ((System.nanoTime() / 100000L) % 20000L)) / 20000F * Math.PI * 2));
        }
        if (goal instanceof IGoalRenderPos) {
            BlockPos goalPos = ((IGoalRenderPos) goal).getGoalPos();
            minX = goalPos.getX() + 0.002 - renderPosX;
            maxX = goalPos.getX() + 1 - 0.002 - renderPosX;
            minZ = goalPos.getZ() + 0.002 - renderPosZ;
            maxZ = goalPos.getZ() + 1 - 0.002 - renderPosZ;
            if (goal instanceof GoalGetToBlock || goal instanceof GoalTwoBlocks) {
                y /= 2;
            }
            y1 = 1 + y + goalPos.getY() - renderPosY;
            y2 = 1 - y + goalPos.getY() - renderPosY;
            minY = goalPos.getY() - renderPosY;
            maxY = minY + 2;
            if (goal instanceof GoalGetToBlock || goal instanceof GoalTwoBlocks) {
                y1 -= 0.5;
                y2 -= 0.5;
                maxY--;
            }
            drawDankLitGoalBox(bufferBuilder, stack, color, minX, maxX, minZ, maxZ, minY, maxY, y1, y2, setupRender);
        } else if (goal instanceof GoalXZ) {
            GoalXZ goalPos = (GoalXZ) goal;
            minY = ctx.world().getMinY();
            maxY = ctx.world().getMaxY();

            if (settings.renderGoalXZBeacon.value) {
                //TODO: check
                textureManager.getTexture(TEXTURE_BEACON_BEAM).bind();
                if (settings.renderGoalIgnoreDepth.value) {
                    RenderSystem.disableDepthTest();
                }

                stack.pushPose(); // push
                stack.translate(goalPos.getX() - renderPosX, -renderPosY, goalPos.getZ() - renderPosZ); // translate

                //TODO: check
                BeaconRenderer.renderBeaconBeam(
                        stack,
                        ctx.minecraft().renderBuffers().bufferSource(),
                        TEXTURE_BEACON_BEAM,
                        settings.renderGoalAnimated.value ? partialTicks : 0,
                        1.0F,
                        settings.renderGoalAnimated.value ? ctx.world().getGameTime() : 0,
                        (int) minY,
                        (int) maxY,
                        color.getRGB(),

                        // Arguments filled by the private method lol
                        0.2F,
                        0.25F
                );

                stack.popPose(); // pop

                if (settings.renderGoalIgnoreDepth.value) {
                    RenderSystem.enableDepthTest();
                }
                return;
            }

            minX = goalPos.getX() + 0.002 - renderPosX;
            maxX = goalPos.getX() + 1 - 0.002 - renderPosX;
            minZ = goalPos.getZ() + 0.002 - renderPosZ;
            maxZ = goalPos.getZ() + 1 - 0.002 - renderPosZ;

            y1 = 0;
            y2 = 0;
            minY -= renderPosY;
            maxY -= renderPosY;
            drawDankLitGoalBox(bufferBuilder, stack, color, minX, maxX, minZ, maxZ, minY, maxY, y1, y2, setupRender);
        } else if (goal instanceof GoalComposite) {
            // Simple way to determine if goals can be batched, without having some sort of GoalRenderer
            boolean batch = Arrays.stream(((GoalComposite) goal).goals()).allMatch(IGoalRenderPos.class::isInstance);
            BufferBuilder buf = bufferBuilder;
            if (batch) {
                buf = IRenderer.startLines(color, settings.goalRenderLineWidthPixels.value, settings.renderGoalIgnoreDepth.value);
            }
            for (Goal g : ((GoalComposite) goal).goals()) {
                drawGoal(buf, stack, ctx, g, partialTicks, color, !batch);
            }
            if (batch) {
                IRenderer.endLines(buf, settings.renderGoalIgnoreDepth.value);
            }
        } else if (goal instanceof GoalInverted) {
            drawGoal(stack, ctx, ((GoalInverted) goal).origin, partialTicks, settings.colorInvertedGoalBox.value);
        } else if (goal instanceof GoalYLevel) {
            GoalYLevel goalpos = (GoalYLevel) goal;
            minX = ctx.player().position().x - settings.yLevelBoxSize.value - renderPosX;
            minZ = ctx.player().position().z - settings.yLevelBoxSize.value - renderPosZ;
            maxX = ctx.player().position().x + settings.yLevelBoxSize.value - renderPosX;
            maxZ = ctx.player().position().z + settings.yLevelBoxSize.value - renderPosZ;
            minY = ((GoalYLevel) goal).level - renderPosY;
            maxY = minY + 2;
            y1 = 1 + y + goalpos.level - renderPosY;
            y2 = 1 - y + goalpos.level - renderPosY;
            drawDankLitGoalBox(bufferBuilder, stack, color, minX, maxX, minZ, maxZ, minY, maxY, y1, y2, setupRender);
        }
    }

    private static void drawDankLitGoalBox(BufferBuilder bufferBuilder, PoseStack stack, Color colorIn, double minX, double maxX, double minZ, double maxZ, double minY, double maxY, double y1, double y2, boolean setupRender) {
        if (setupRender) {
            bufferBuilder = IRenderer.startLines(colorIn, settings.goalRenderLineWidthPixels.value, settings.renderGoalIgnoreDepth.value);
        }

        renderHorizontalQuad(bufferBuilder, stack, minX, maxX, minZ, maxZ, y1);
        renderHorizontalQuad(bufferBuilder, stack, minX, maxX, minZ, maxZ, y2);

        for (double y = minY; y < maxY; y += 16) {
            double max = Math.min(maxY, y + 16);
            IRenderer.emitLine(bufferBuilder, stack, minX, y, minZ, minX, max, minZ, 0.0, 1.0, 0.0);
            IRenderer.emitLine(bufferBuilder, stack, maxX, y, minZ, maxX, max, minZ, 0.0, 1.0, 0.0);
            IRenderer.emitLine(bufferBuilder, stack, maxX, y, maxZ, maxX, max, maxZ, 0.0, 1.0, 0.0);
            IRenderer.emitLine(bufferBuilder, stack, minX, y, maxZ, minX, max, maxZ, 0.0, 1.0, 0.0);
        }

        if (setupRender) {
            IRenderer.endLines(bufferBuilder, settings.renderGoalIgnoreDepth.value);
        }
    }

    private static void renderHorizontalQuad(BufferBuilder bufferBuilder, PoseStack stack, double minX, double maxX, double minZ, double maxZ, double y) {
        if (y != 0) {
            IRenderer.emitLine(bufferBuilder, stack, minX, y, minZ, maxX, y, minZ, 1.0, 0.0, 0.0);
            IRenderer.emitLine(bufferBuilder, stack, maxX, y, minZ, maxX, y, maxZ, 0.0, 0.0, 1.0);
            IRenderer.emitLine(bufferBuilder, stack, maxX, y, maxZ, minX, y, maxZ, -1.0, 0.0, 0.0);
            IRenderer.emitLine(bufferBuilder, stack, minX, y, maxZ, minX, y, minZ, 0.0, 0.0, -1.0);
        }
    }

    // =========================================================================
    //  ENHANCED VISUAL METHODS
    // =========================================================================

    /**
     * Draws the current path with a cyan→magenta gradient that shifts over time.
     * Provides a premium "neon" appearance distinguishing the active route.
     */
    public static void drawPathGradient(PoseStack stack, List<BetterBlockPos> positions, int startIndex, boolean fadeOut, int fadeStart0, int fadeEnd0) {
        if (positions == null || positions.size() < 2) return;
        long t = System.currentTimeMillis();

        // Wide halo pass first
        if (settings.renderShaderEffects.value) {
            drawPathGradientPass(stack, positions, startIndex, fadeOut, fadeStart0, fadeEnd0, 0.5D, 2.4F, 0.22F, t);
        }
        // Sharp pass on top
        drawPathGradientPass(stack, positions, startIndex, fadeOut, fadeStart0, fadeEnd0, 0.5D, 1.0F, 1.0F, t);
    }

    private static void drawPathGradientPass(PoseStack stack, List<BetterBlockPos> positions, int startIndex,
                                              boolean fadeOut, int fadeStart0, int fadeEnd0,
                                              double offset, float widthMult, float alphaMult, long timeMs) {
        int total = positions.size();
        if (total < 2 || startIndex >= total - 1) return;

        int fadeStart = fadeStart0 + startIndex;
        int fadeEnd = fadeEnd0 + startIndex;

        BufferBuilder buf = IRenderer.startLines(Color.WHITE, 0.55F * alphaMult,
                settings.pathRenderLineWidthPixels.value * widthMult, settings.renderPathIgnoreDepth.value);

        // We render all segments in a single batched buffer pass
        for (int i = startIndex; i < total - 1; i++) {
            BetterBlockPos start = positions.get(i);
            BetterBlockPos end = positions.get(i + 1);

            float alpha = 0.55F;
            if (fadeOut) {
                if (i > fadeEnd) break;
                if (i > fadeStart) {
                    alpha = 0.55F * (1.0F - (float) (i - fadeStart) / (float) (fadeEnd - fadeStart));
                }
            }

            // Dynamic light pulse traveling along the path (wave effect)
            if (total > 2) {
                double wavePos = ((timeMs % 1600L) / 1600.0) * (total - 1);
                double distToWave = Math.abs(i - wavePos);
                float waveBoost = (float) Math.max(0.0, 1.0 - distToWave / 3.0) * 0.45F;
                alpha = Math.min(1.0F, alpha + waveBoost);
            }

            alpha *= alphaMult;

            Color segColor;
            if (settings.renderRainbowPath.value) {
                float hue = (float) ((timeMs / 2500.0 + (double) i / 18.0) % 1.0);
                float satPulse = (float) (0.75 + 0.25 * Math.sin(timeMs / 300.0));
                segColor = Color.getHSBColor(hue, satPulse, 1.0F);
            } else {
                // Gradient: cyan (hue=0.5) at start → magenta (hue=0.83) at end, animated shift
                float t01 = (total > 1) ? (float) i / (total - 1) : 0;
                float baseHue = 0.50f + t01 * 0.33f; // cyan to magenta
                float animShift = (float) ((timeMs / 8000.0) % 1.0); // slow full rotation
                float hue = (baseHue + animShift) % 1.0f;
                segColor = Color.getHSBColor(hue, 0.88F, 1.0F);
            }

            IRenderer.glColor(segColor, alpha);
            emitPathLine(buf, stack, start.x, start.y, start.z, end.x, end.y, end.z, offset);
        }

        IRenderer.endLines(buf, settings.renderPathIgnoreDepth.value);
    }

    /**
     * Like {@link #drawManySelectionBoxes} but with a pulsing sin-wave alpha,
     * making blocks-to-break visually pop and indicate active targeting.
     */
    public static void drawManySelectionBoxesPulsed(PoseStack stack, Entity player, Collection<BlockPos> positions, Color color) {
        if (positions.isEmpty()) {
            return;
        }
        // Pulsed alpha: oscillates between 0.35 and 0.9
        double pulse = 0.5 + 0.4 * Math.sin(System.currentTimeMillis() / 160.0);
        float pulsedAlpha = (float) (0.35 + pulse * 0.55);

        BlockStateInterface bsi = new BlockStateInterface(BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext());

        if (settings.renderFilledBoxes.value) {
            BufferBuilder filledBuilder = IRenderer.startFilled(color, pulsedAlpha * settings.filledBoxAlpha.value, settings.renderSelectionBoxesIgnoreDepth.value);
            positions.forEach(pos -> {
                BlockState state = bsi.get0(pos);
                VoxelShape shape = state.getShape(player.level(), pos);
                AABB toDraw = shape.isEmpty() ? Shapes.block().bounds() : shape.bounds();
                toDraw = toDraw.move(pos);
                IRenderer.emitFilledAABB(filledBuilder, stack, toDraw);
            });
            IRenderer.endFilled(filledBuilder, settings.renderSelectionBoxesIgnoreDepth.value);
        }

        BufferBuilder bufferBuilder = IRenderer.startLines(color, pulsedAlpha * 0.8F,
                settings.pathRenderLineWidthPixels.value, settings.renderSelectionBoxesIgnoreDepth.value);
        positions.forEach(pos -> {
            BlockState state = bsi.get0(pos);
            VoxelShape shape = state.getShape(player.level(), pos);
            AABB toDraw = shape.isEmpty() ? Shapes.block().bounds() : shape.bounds();
            toDraw = toDraw.move(pos);
            IRenderer.emitAABB(bufferBuilder, stack, toDraw, .002D);
        });
        IRenderer.endLines(bufferBuilder, settings.renderSelectionBoxesIgnoreDepth.value);
    }

    /**
     * Draws 4 small orbital marker boxes around the goal position,
     * rotating around the Y axis like satellites.
     */
    private static void drawGoalOrbitParticles(PoseStack stack, IPlayerContext ctx, Goal goal) {
        if (!(goal instanceof IGoalRenderPos)) return;
        BlockPos goalPos = ((IGoalRenderPos) goal).getGoalPos();

        double cx = goalPos.getX() + 0.5 - posX();
        double cy = goalPos.getY() + 1.0 - posY();
        double cz = goalPos.getZ() + 0.5 - posZ();

        long t = System.currentTimeMillis();
        double angleBase = (t / 1200.0) * Math.PI * 2.0; // full orbit every 1.2 seconds
        double radius = 0.75;
        double size = 0.08;

        int numOrbs = 4;
        BufferBuilder buf = IRenderer.startLines(Color.WHITE, 0.85F,
                settings.pathRenderLineWidthPixels.value * 1.5F, settings.renderGoalIgnoreDepth.value);

        for (int i = 0; i < numOrbs; i++) {
            double angle = angleBase + (Math.PI * 2.0 / numOrbs) * i;
            double ox = cx + Math.cos(angle) * radius;
            double oz = cz + Math.sin(angle) * radius;
            // Bob up and down slightly
            double oy = cy + Math.sin(angle * 2 + t / 700.0) * 0.2;

            AABB orb = new AABB(ox - size, oy - size, oz - size, ox + size, oy + size, oz + size);

            // Hue based on orbit position
            float hue = (float) (((angle / (Math.PI * 2.0)) + (t / 3000.0)) % 1.0);
            Color orbColor = Color.getHSBColor(hue, 0.9F, 1.0F);

            IRenderer.glColor(orbColor, 0.85F);
            IRenderer.emitAABB(buf, stack, orb, 0.0);
        }

        IRenderer.endLines(buf, settings.renderGoalIgnoreDepth.value);
    }
}

