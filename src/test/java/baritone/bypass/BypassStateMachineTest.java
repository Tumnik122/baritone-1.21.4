package baritone.bypass;

import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.IPlayerContext;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.Assert.*;

public class BypassStateMachineTest {

    private final IPlayerContext context = (IPlayerContext) Proxy.newProxyInstance(
            IPlayerContext.class.getClassLoader(), new Class<?>[]{IPlayerContext.class},
            (proxy, method, args) -> null);
    private final BypassConfig config = new BypassConfig();

    @Test
    public void transitionCallsExitBeforeEntryExactlyOnce() {
        Map<BypassProcess.State, CountingController> phases = phases();
        BypassStateMachine machine = machine(phases);

        machine.transition(BypassProcess.State.TUNNELING);
        machine.transition(BypassProcess.State.TUNNELING);
        machine.transition(BypassProcess.State.MINING_ORE);
        machine.transition(BypassProcess.State.IDLE);

        assertEquals(1, phases.get(BypassProcess.State.TUNNELING).entries);
        assertEquals(1, phases.get(BypassProcess.State.TUNNELING).exits);
        assertEquals(1, phases.get(BypassProcess.State.MINING_ORE).entries);
        assertEquals(1, phases.get(BypassProcess.State.MINING_ORE).exits);
        assertEquals(BypassProcess.State.IDLE, machine.state());
    }

    @Test
    public void inTickTransitionCannotReturnPreviousPhasePath() {
        Map<BypassProcess.State, CountingController> phases = phases();
        BypassStateMachine machine = machine(phases);
        PathingCommand oldPath = new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        CountingController tunnel = phases.get(BypassProcess.State.TUNNELING);
        tunnel.command = oldPath;
        tunnel.onTick = () -> machine.transition(BypassProcess.State.MINING_ORE);

        machine.transition(BypassProcess.State.TUNNELING);
        assertEquals(PathingCommandType.REQUEST_PAUSE, machine.onTick().commandType);
        assertEquals(BypassProcess.State.MINING_ORE, machine.state());
        assertEquals(1, tunnel.exits);
    }

    @Test
    public void haltedAndIdlePhasesNeverStartPathing() {
        Map<BypassProcess.State, CountingController> phases = phases();
        BypassStateMachine machine = machine(phases);
        assertEquals(PathingCommandType.REQUEST_PAUSE, machine.onTick().commandType);
        machine.transition(BypassProcess.State.TUNNELING);
        CountingController tunnel = phases.get(BypassProcess.State.TUNNELING);
        tunnel.halt = true;
        assertEquals(PathingCommandType.REQUEST_PAUSE, machine.onTick().commandType);
        assertEquals(0, tunnel.ticks);
    }

    @Test
    public void leaveAndReenterWithinOneTickStillDiscardsTheOldCommand() {
        Map<BypassProcess.State, CountingController> phases = phases();
        BypassStateMachine machine = machine(phases);
        CountingController tunnel = phases.get(BypassProcess.State.TUNNELING);
        tunnel.command = new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        tunnel.onTick = () -> {
            machine.transition(BypassProcess.State.MINING_ORE);
            machine.transition(BypassProcess.State.TUNNELING);
        };
        machine.transition(BypassProcess.State.TUNNELING);
        assertEquals(PathingCommandType.REQUEST_PAUSE, machine.onTick().commandType);
        assertEquals(2, tunnel.entries);
        assertEquals(1, tunnel.exits);
    }

    @Test
    public void threatInterruptsCollectionAndDropsItsPreviousNavigationCommand() {
        Map<BypassProcess.State, CountingController> phases = phases();
        BypassStateMachine machine = machine(phases);
        CountingController collect = phases.get(BypassProcess.State.COLLECTING_DROP);
        collect.command = new PathingCommand(null, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        collect.onTick = () -> machine.transition(BypassProcess.State.RETREATING_MOB);
        machine.transition(BypassProcess.State.COLLECTING_DROP);
        assertEquals(PathingCommandType.REQUEST_PAUSE, machine.onTick().commandType);
        assertEquals(1, collect.exits);
        assertEquals(BypassProcess.State.RETREATING_MOB, machine.state());
    }

    private BypassStateMachine machine(Map<BypassProcess.State, CountingController> phases) {
        return new BypassStateMachine(context, config, Map.copyOf(phases));
    }

    private Map<BypassProcess.State, CountingController> phases() {
        Map<BypassProcess.State, CountingController> phases = new EnumMap<>(BypassProcess.State.class);
        for (BypassProcess.State phase : BypassProcess.State.values()) {
            if (phase != BypassProcess.State.IDLE && phase != BypassProcess.State.DISCONNECTED) {
                phases.put(phase, new CountingController());
            }
        }
        return phases;
    }

    private static final class CountingController implements IBypassSubController {
        int entries;
        int exits;
        int ticks;
        boolean halt;
        Runnable onTick = () -> {};
        PathingCommand command;

        @Override
        public void onEnter(IPlayerContext ctx, BypassConfig config) {
            entries++;
        }

        @Override
        public PathingCommand onTick(IPlayerContext ctx, BypassConfig config) {
            ticks++;
            onTick.run();
            return command;
        }

        @Override
        public void onExit(IPlayerContext ctx) {
            exits++;
        }

        @Override
        public boolean isFinished() {
            return false;
        }

        @Override
        public boolean requiresImmediateHalt() {
            return halt;
        }
    }
}
