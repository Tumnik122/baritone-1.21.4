package baritone.bypass;

import baritone.api.process.PathingCommand;
import baritone.api.utils.IPlayerContext;

/**
 * A single phase of the bypass mining process. Called on the Minecraft client thread.
 *
 * <p>Controllers may request a phase change through their owning state machine. A null
 * command means that the controller has no pathing request for this tick; the dispatcher
 * will pause pathing until the next tick.</p>
 */
public interface IBypassSubController {

    void onEnter(IPlayerContext ctx, BypassConfig config);

    PathingCommand onTick(IPlayerContext ctx, BypassConfig config);

    void onExit(IPlayerContext ctx);

    boolean isFinished();

    boolean requiresImmediateHalt();
}
