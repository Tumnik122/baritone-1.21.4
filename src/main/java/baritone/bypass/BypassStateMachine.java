package baritone.bypass;

import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.IPlayerContext;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Owns phase transitions and delegates one tick to exactly one phase controller.
 *
 * <p>All entry points run on the client thread. In particular, event callbacks must
 * not call {@link #transition(BypassProcess.State)} from a network worker thread.</p>
 *
 * <p>Integration architecture: the Baritone process owns lifecycle and shared session
 * data; this dispatcher owns the phase and controller lifecycle. Safety checks precede
 * dispatch, and the active controller alone supplies the pathing command. The current
 * process binds its existing phase handlers through the common controller interface;
 * each handler can be moved into an independent controller without changing the
 * transition contract or the public process API.</p>
 *
 * <p>Audit points in the legacy implementation: drop collection previously returned
 * before player/mob threat checks; block interaction trusts a cached client hit result
 * and does not wait after changing tool slots; the fixed break timeout can interrupt
 * slow deepslate mining even while actual progress is being made. The dispatcher
 * addresses phase ownership and stale path commands; interaction timing and mining
 * progress require separate controller work before claiming server compatibility.</p>
 */
public final class BypassStateMachine {

    private static final PathingCommand PAUSE = new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    private final IPlayerContext ctx;
    private final BypassConfig config;
    private final Map<BypassProcess.State, IBypassSubController> controllers;
    private BypassProcess.State state = BypassProcess.State.IDLE;
    private long transitionVersion;

    public BypassStateMachine(IPlayerContext ctx, BypassConfig config,
                              Map<BypassProcess.State, IBypassSubController> controllers) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.config = Objects.requireNonNull(config, "config");
        Objects.requireNonNull(controllers, "controllers");
        EnumMap<BypassProcess.State, IBypassSubController> copy = new EnumMap<>(BypassProcess.State.class);
        copy.putAll(controllers);
        for (BypassProcess.State phase : BypassProcess.State.values()) {
            if (phase != BypassProcess.State.IDLE && phase != BypassProcess.State.DISCONNECTED) {
                Objects.requireNonNull(copy.get(phase), "Missing controller for " + phase);
            }
        }
        this.controllers = Map.copyOf(copy);
    }

    public BypassProcess.State state() {
        return state;
    }

    public void transition(BypassProcess.State next) {
        Objects.requireNonNull(next, "next");
        if (state == next) {
            return;
        }
        IBypassSubController previous = controllers.get(state);
        if (previous != null) {
            previous.onExit(ctx);
        }
        state = next;
        transitionVersion++;
        IBypassSubController incoming = controllers.get(next);
        if (incoming != null) {
            incoming.onEnter(ctx, config);
        }
    }

    public PathingCommand onTick() {
        IBypassSubController controller = controllers.get(state);
        if (controller == null || controller.requiresImmediateHalt()) {
            return PAUSE;
        }
        long dispatchedVersion = transitionVersion;
        PathingCommand command = controller.onTick(ctx, config);
        // An exit triggered inside onTick must never let the old phase start a new path.
        if (transitionVersion != dispatchedVersion) {
            return PAUSE;
        }
        if (controller.requiresImmediateHalt()) {
            return PAUSE;
        }
        return command != null ? command : PAUSE;
    }

    public boolean isFinished() {
        IBypassSubController controller = controllers.get(state);
        return controller != null && controller.isFinished();
    }
}
