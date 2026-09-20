package baritone.selection;

import baritone.Baritone;
import baritone.api.event.events.RenderEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.selection.ISelection;
import baritone.utils.IRenderer;
import baritone.utils.WorldFxShaders;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

public class SelectionRenderer implements IRenderer, AbstractGameEventListener {

    public static final double SELECTION_BOX_EXPANSION = .005D;

    private final SelectionManager manager;

    SelectionRenderer(Baritone baritone, SelectionManager manager) {
        this.manager = manager;
        baritone.getGameEventHandler().registerEventListener(this);
    }

    public static void renderSelections(PoseStack stack, Matrix4f projection, ISelection[] selections) {
        float opacity = settings.selectionOpacity.value;
        boolean ignoreDepth = settings.renderSelectionIgnoreDepth.value;
        float lineWidth = settings.selectionLineWidth.value;

        if (!settings.renderSelection.value || selections.length == 0) {
            return;
        }

        if (settings.renderFilledBoxes.value) {
            BufferBuilder filled = IRenderer.startFilled(settings.colorSelection.value, opacity * 0.35F, ignoreDepth);
            for (ISelection selection : selections) {
                IRenderer.emitFilledAABB(filled, stack, selection.aabb().inflate(SELECTION_BOX_EXPANSION));
            }
            IRenderer.endFilled(filled, ignoreDepth);
        }

        BufferBuilder bufferBuilder = IRenderer.startLines(settings.colorSelection.value, opacity, lineWidth, ignoreDepth);

        for (ISelection selection : selections) {
            IRenderer.emitAABB(bufferBuilder, stack, selection.aabb(), SELECTION_BOX_EXPANSION);
        }

        if (settings.renderSelectionCorners.value) {
            IRenderer.glColor(settings.colorSelectionPos1.value, opacity);

            for (ISelection selection : selections) {
                IRenderer.emitAABB(bufferBuilder, stack, new AABB(selection.pos1()));
            }

            IRenderer.glColor(settings.colorSelectionPos2.value, opacity);

            for (ISelection selection : selections) {
                IRenderer.emitAABB(bufferBuilder, stack, new AABB(selection.pos2()));
            }
        }

        IRenderer.endLines(bufferBuilder, ignoreDepth);

        if (WorldFxShaders.isUsable()) {
            double vx = IRenderer.renderManager.renderPosX();
            double vy = IRenderer.renderManager.renderPosY();
            double vz = IRenderer.renderManager.renderPosZ();
            WorldFxShaders.begin(stack, projection, WorldFxShaders.MODE_HOLO, ignoreDepth, false);
            for (ISelection selection : selections) {
                WorldFxShaders.holoBox(selection.aabb().inflate(SELECTION_BOX_EXPANSION).move(-vx, -vy, -vz),
                        settings.colorSelection.value, 0.4F);
            }
            WorldFxShaders.end();

            WorldFxShaders.begin(stack, projection, WorldFxShaders.MODE_ORB, ignoreDepth, true);
            for (ISelection selection : selections) {
                AABB a = new AABB(selection.pos1()).move(-vx, -vy, -vz);
                AABB b = new AABB(selection.pos2()).move(-vx, -vy, -vz);
                WorldFxShaders.billboard(
                        (float) ((a.minX + a.maxX) * 0.5), (float) ((a.minY + a.maxY) * 0.5), (float) ((a.minZ + a.maxZ) * 0.5),
                        0.16F, settings.colorSelectionPos1.value, 0.85F, 0.2F);
                WorldFxShaders.billboard(
                        (float) ((b.minX + b.maxX) * 0.5), (float) ((b.minY + b.maxY) * 0.5), (float) ((b.minZ + b.maxZ) * 0.5),
                        0.16F, settings.colorSelectionPos2.value, 0.85F, 0.8F);
            }
            WorldFxShaders.end();
        }
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        renderSelections(event.getModelViewStack(), event.getProjectionMatrix(), manager.getSelections());
    }
}
