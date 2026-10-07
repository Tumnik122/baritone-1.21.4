package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.utils.IPlayerContext;
import net.minecraft.client.player.LocalPlayer;

/**
 * Główny handler GrimAC compatibility.
 * Podpina się do tick eventów i koordynuje wszystkie moduły Grim.
 */
public final class GrimTickHandler extends Behavior {

    private final GrimRotationEngine rotationEngine;
    private final GrimMovementEngine movementEngine;
    private final GrimBreakEngine breakEngine;
    private final GrimPlacementEngine placementEngine;
    private final GrimCompatSettings settings;

    public GrimTickHandler(IPlayerContext ctx) {
        super(null, ctx);
        this.rotationEngine = GrimRotationEngine.getInstance(ctx);
        this.movementEngine = new GrimMovementEngine(ctx);
        this.breakEngine = new GrimBreakEngine(ctx);
        this.placementEngine = new GrimPlacementEngine(ctx);
        this.settings = GrimCompatSettings.getInstance();
    }

    public GrimTickHandler(Baritone baritone) {
        super(baritone);
        this.rotationEngine = GrimRotationEngine.getInstance(baritone.getPlayerContext());
        this.movementEngine = new GrimMovementEngine(baritone.getPlayerContext());
        this.breakEngine = new GrimBreakEngine(baritone.getPlayerContext());
        this.placementEngine = new GrimPlacementEngine(baritone.getPlayerContext());
        this.settings = GrimCompatSettings.getInstance();
    }

    /**
     * Wywoływane co tick PRZED wysłaniem pakietów ruchu.
     * Kolejność operacji jest krytyczna dla GrimAC.
     */
    public void onTickPre(TickEvent event) {
        if (event == null || event.getType() != TickEvent.Type.IN) return;
        LocalPlayer player = ctx != null ? ctx.player() : null;
        if (player == null) return;

        // 1. Update rotacji tylko gdy cel jest aktywnie śledzony
        if (rotationEngine.isRotating()) {
            rotationEngine.tick();
        } else {
            rotationEngine.syncFromPlayer(player);
        }

        // 2. Waliduj movement (przed wysłaniem pakietu)
        movementEngine.validateAndClampMovement(
                player.isSprinting(),
                player.isShiftKeyDown(),
                player.xxa,
                player.zza
        );
    }

    /**
     * Wywoływane co tick PO wysłaniu pakietów ruchu.
     */
    public void onTickPost(TickEvent event) {
        if (event == null || event.getType() != TickEvent.Type.IN) return;

        // 3. Tick kopania
        if (breakEngine.isBreaking()) {
            breakEngine.tickBreak();
        }
    }

    @Override
    public void onTick(TickEvent event) {
        if (Baritone.settings() != null && Baritone.settings().grimCompat.value) {
            onTickPre(event);
        }
    }

    @Override
    public void onPostTick(TickEvent event) {
        if (Baritone.settings() != null && Baritone.settings().grimCompat.value) {
            onTickPost(event);
        }
    }

    public GrimRotationEngine getRotationEngine() { return rotationEngine; }
    public GrimMovementEngine getMovementEngine() { return movementEngine; }
    public GrimBreakEngine getBreakEngine() { return breakEngine; }
    public GrimPlacementEngine getPlacementEngine() { return placementEngine; }
    public GrimCompatSettings getSettings() { return settings; }
}
