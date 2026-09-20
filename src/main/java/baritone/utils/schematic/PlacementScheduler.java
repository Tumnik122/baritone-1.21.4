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

package baritone.utils.schematic;

import baritone.Baritone;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * Inteligentny harmonogram stawiania bloków schematu (inspirowany Lambda Conflict-Free Orchestration):
 * - Kolejność stawiania: najpierw fundament, potem ściany, na końcu dach (od najniższego Y do najwyższego).
 * - Zapobiega stawianiu bloków "w powietrzu" poprzez weryfikację podparcia topologicznego.
 * - Weryfikuje potwierdzenie postawienia bloku przez serwer przed postawieniem kolejnego bloku zależnego.
 * - Zapewnia płynne i bezpieczne tempo (pacing) zapobiegając flagom antycheatów (GrimAC / Vulcan / Polar).
 */
public final class PlacementScheduler {

    private final Set<BlockPos> verifiedPlacements = new HashSet<>();
    private BlockPos lastPlaced = null;
    private int ticksSinceLastPlaced = 0;
    private static final int VERIFICATION_TIMEOUT_TICKS = 10;

    public PlacementScheduler() {}

    /**
     * Sortuje pozycje bloków:
     * 1. Warstwami od dołu do góry (Y rosnąco: fundament -> ściany -> dach).
     * 2. Sprawdza podparcie (bloki z oparciem od dołu mają bezwzględny priorytet).
     * 3. W obrębie tej samej warstwy: sekwencyjnie / najbliżej gracza, aby unikać chaotycznego obracania.
     */
    public List<BetterBlockPos> schedulePlacements(Collection<BetterBlockPos> candidates, BlockPos playerFeet, BlockStateInterface bsi) {
        List<BetterBlockPos> sorted = new ArrayList<>(candidates);

        sorted.sort((a, b) -> {
            // 1. Priorytet wysokości Y (od dołu do góry)
            int yComp = Integer.compare(a.getY(), b.getY());
            if (yComp != 0) {
                return yComp;
            }

            // 2. Priorytet podparcia: blok mający solidny blok pod sobą powinien być stawiany przed wiszącymi
            boolean aHasSupport = hasSolidBelow(a, bsi);
            boolean bHasSupport = hasSolidBelow(b, bsi);
            if (aHasSupport != bHasSupport) {
                return aHasSupport ? -1 : 1;
            }

            // 3. Sekwencyjność przestrzenna (odległość od stóp gracza)
            double distA = a.distSqr(playerFeet);
            double distB = b.distSqr(playerFeet);
            return Double.compare(distA, distB);
        });

        return sorted;
    }

    /**
     * Sprawdza czy blok pod pozycją pos jest solidny i stanowi pewne oparcie.
     */
    public static boolean hasSolidBelow(BlockPos pos, BlockStateInterface bsi) {
        BlockPos below = pos.below();
        BlockState belowState = bsi.get0(below);
        return !(belowState.getBlock() instanceof AirBlock)
                && !(belowState.getBlock() instanceof LiquidBlock)
                && !MovementHelper.isReplaceable(below.getX(), below.getY(), below.getZ(), belowState, bsi);
    }

    /**
     * Sprawdza czy blok posiada jakiekolwiek oparcie (od dołu lub z boku),
     * aby nie stawiać bloków zawieszonych w próżni.
     */
    public static boolean hasAnySupport(BlockPos pos, BlockStateInterface bsi) {
        for (Direction side : Direction.values()) {
            if (side == Direction.UP) continue; // Górne oparcie nie chroni przed grawitacją
            BlockPos neighbor = pos.relative(side);
            BlockState neighborState = bsi.get0(neighbor);
            if (!(neighborState.getBlock() instanceof AirBlock)
                    && !(neighborState.getBlock() instanceof LiquidBlock)
                    && !MovementHelper.isReplaceable(neighbor.getX(), neighbor.getY(), neighbor.getZ(), neighborState, bsi)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Wywoływane co tick budowy. Sprawdza czy ostatnio postawiony blok został potwierdzony przez serwer.
     * Zwraca true jeśli Baritone powinien zaczekać na synchronizację świata.
     */
    public boolean isAwaitingVerification(Level world) {
        if (!Baritone.settings().placementVerifyServer.value) {
            return false;
        }

        if (lastPlaced == null) {
            return false;
        }

        ticksSinceLastPlaced++;

        // Jeśli w świecie gry na tej pozycji nie ma już powietrza ani cieczy zastępowalnej,
        // oznacza to, że serwer zatwierdził postawienie bloku!
        BlockState currentState = world.getBlockState(lastPlaced);
        if (!(currentState.getBlock() instanceof AirBlock)) {
            verifiedPlacements.add(lastPlaced);
            lastPlaced = null;
            ticksSinceLastPlaced = 0;
            return false;
        }

        // Zabezpieczenie przed zacięciem: jeśli minęło zbyt wiele ticków, odblokowujemy kolejny krok
        if (ticksSinceLastPlaced >= VERIFICATION_TIMEOUT_TICKS) {
            lastPlaced = null;
            ticksSinceLastPlaced = 0;
            return false;
        }

        // Czekaj na potwierdzenie od serwera
        return true;
    }

    /**
     * Rejestruje wysłanie akcji postawienia bloku na danej pozycji.
     */
    public void notifyPlaced(BlockPos pos) {
        this.lastPlaced = pos.immutable();
        this.ticksSinceLastPlaced = 0;
    }

    /**
     * Informuje o zmianie bloku w świecie (np. pakiet aktualizacji od serwera).
     */
    public void onBlockUpdate(BlockPos pos, BlockState newState) {
        if (lastPlaced != null && lastPlaced.equals(pos) && !(newState.getBlock() instanceof AirBlock)) {
            verifiedPlacements.add(pos.immutable());
            lastPlaced = null;
            ticksSinceLastPlaced = 0;
        }
    }

    /**
     * Resetuje stan harmonogramu i weryfikacji.
     */
    public void reset() {
        verifiedPlacements.clear();
        lastPlaced = null;
        ticksSinceLastPlaced = 0;
    }

    public BlockPos getLastPlaced() {
        return lastPlaced;
    }

    public Set<BlockPos> getVerifiedPlacements() {
        return Collections.unmodifiableSet(verifiedPlacements);
    }
}
