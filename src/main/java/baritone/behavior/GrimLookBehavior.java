package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import net.minecraft.client.player.LocalPlayer;

/**
 * Zmodyfikowany LookBehavior używający GrimRotationEngine.
 * Zamiast instant-snap, używa płynnych, kwantyzowanych rotacji.
 */
public final class GrimLookBehavior extends Behavior {

    private final GrimRotationEngine rotationEngine;
    private final GrimCompatSettings grimSettings;

    // -- Target --
    private boolean hasTarget = false;

    public GrimLookBehavior(Baritone baritone) {
        super(baritone);
        this.rotationEngine = GrimRotationEngine.getInstance(baritone.getPlayerContext());
        this.grimSettings = GrimCompatSettings.getInstance();
    }

    /**
     * Ustawia docelową rotację (wywoływane przez inne moduły).
     */
    public void lookAt(float yaw, float pitch) {
        this.hasTarget = true;
        this.rotationEngine.setMouseSensitivity(grimSettings.mouseSensitivity);
        this.rotationEngine.targetRotation(yaw, pitch);
    }

    /**
     * Celuje w konkretną pozycję w świecie.
     */
    public void lookAt(double x, double y, double z) {
        LocalPlayer player = ctx.player();
        if (player == null) return;

        double dx = x - player.getX();
        double dy = y - player.getEyeY();
        double dz = z - player.getZ();

        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDist));

        lookAt(yaw, pitch);
    }

    @Override
    public void onTick(TickEvent event) {
        if (event == null || event.getType() != TickEvent.Type.IN) return;
        if (!hasTarget) return;

        // Update rotacji przez silnik Grim-compatible
        rotationEngine.tick();

        // Zastosuj rotację do gracza
        LocalPlayer player = ctx.player();
        if (player != null) {
            player.setYRot(rotationEngine.getCurrentYaw());
            player.setXRot(rotationEngine.getCurrentPitch());
            player.yRotO = rotationEngine.getCurrentYaw();
            player.xRotO = rotationEngine.getCurrentPitch();
        }

        // Jeśli osiągnęliśmy target, przestań
        if (rotationEngine.hasReachedTarget()) {
            hasTarget = false;
        }
    }

    public boolean isActive() {
        return hasTarget;
    }
}
