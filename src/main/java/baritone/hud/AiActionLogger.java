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

package baritone.hud;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.process.IBaritoneProcess;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.path.PathExecutor;
import baritone.process.MineProcess;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Szczegółowy rejestrator i diagnostyk stanu Baritone zoptymalizowany pod kątem
 * wyjaśniania zachowania bota modelom AI (ChatGPT, Claude, Gemini).
 */
public final class AiActionLogger {

    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("HH:mm:ss.SSS");
    private static String lastDetailedState = "";
    private static long lastLogTime = 0;

    private AiActionLogger() {}

    /**
     * Loguje kluczowe zdarzenie Baritone z prefiksem [BaritoneAI].
     */
    public static void log(String category, String message) {
        String timestamp = DATE_FMT.format(new Date());
        String logLine = String.format("[%s] [BaritoneAI] [%s] %s", timestamp, category, message);
        System.out.println(logLine);
        HudLog.push(category + " ▸ " + message);
    }

    /**
     * Zwraca pełny, ustrukturyzowany zrzut stanu Baritone w formacie Markdown,
     * idealny do skopiowania i wklejenia do prompta AI.
     */
    public static String generateAiDiagnosticReport() {
        Minecraft mc = Minecraft.getInstance();
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone == null || mc.player == null) {
            return "### [Baritone AI State]\n**Status**: Baritone nie jest zainicjalizowany lub gracz nie jest w świecie.";
        }

        Player player = mc.player;
        Vec3 pos = player.position();
        BlockPos feet = player.blockPosition();
        PathingBehavior pb = (PathingBehavior) baritone.getPathingBehavior();
        PathExecutor pe = pb.getCurrent();
        IPath currentPath = pe != null ? pe.getPath() : null;

        StringBuilder sb = new StringBuilder();
        sb.append("### 🤖 Baritone AI State Report\n");
        sb.append("**Timestamp**: ").append(DATE_FMT.format(new Date())).append("\n\n");

        // 1. Gracz i Pozycja
        sb.append("#### 📍 1. Player State\n");
        sb.append(String.format("- **Pozycja gracza**: X=%.2f, Y=%.2f, Z=%.2f (BlockPos: %d, %d, %d)\n",
                pos.x, pos.y, pos.z, feet.getX(), feet.getY(), feet.getZ()));
        sb.append(String.format("- **Rotacja kamery**: Yaw=%.1f°, Pitch=%.1f°\n",
                player.getYRot(), player.getXRot()));
        sb.append(String.format("- **Zdrowie / Głód**: %.1f/20 HP, %d/20 Food\n",
                player.getHealth(), player.getFoodData().getFoodLevel()));
        sb.append(String.format("- **Wymiar**: %s\n",
                player.level().dimension().location()));
        sb.append("\n");

        // 2. Aktywny Proces Baritone
        sb.append("#### ⚙️ 2. Active Process\n");
        String activeProcessName = "IDLE (Brak aktywnego zadania)";
        if (baritone.getPathingControlManager() != null) {
            var inControl = baritone.getPathingControlManager().mostRecentInControl();
            if (inControl.isPresent() && inControl.get().isActive()) {
                activeProcessName = inControl.get().displayName();
            }
        }
        sb.append("- **Proces nadrzędny**: `").append(activeProcessName).append("`\n");
        sb.append("- **Czy wykonuje ruch (isPathing)**: `").append(pb.isPathing()).append("`\n");

        // Szczegóły dla MineProcess
        if (baritone.getMineProcess() != null && baritone.getMineProcess().isActive()) {
            sb.append("- **MineProcess Details**:\n");
            MineProcess mp = (MineProcess) baritone.getMineProcess();
            sb.append("  - Zadanie: Kopanie bloków\n");
        }
        sb.append("\n");

        // 3. Ścieżka i Ruch (Pathing & Movement)
        sb.append("#### 🛤️ 3. Pathing & Movement\n");
        if (currentPath != null && pe != null) {
            int posIdx = pe.getPosition();
            int len = currentPath.length();
            sb.append(String.format("- **Postęp ścieżki**: Krok %d z %d (%.1f%%)\n",
                    posIdx, len, (float) posIdx / Math.max(1, len) * 100.0F));
            sb.append("- **Punkt docelowy ścieżki**: ").append(currentPath.getDest()).append("\n");

            if (posIdx < len && currentPath.movements() != null && posIdx < currentPath.movements().size()) {
                IMovement mov = currentPath.movements().get(posIdx);
                sb.append("- **Aktualny typ ruchu**: `").append(mov.getClass().getSimpleName()).append("`\n");
                sb.append("  - Źródło ruchu: ").append(mov.getSrc()).append("\n");
                sb.append("  - Cel ruchu: ").append(mov.getDest()).append("\n");
                sb.append("  - Koszt ruchu (heuristic cost): ").append(mov.getCost()).append("\n");
            }
        } else {
            sb.append("- **Stan ścieżki**: Brak aktywnej ścieżki (stoi w miejscu lub oblicza nową)\n");
        }

        // 4. Algorytm A* (Wyszukiwanie drogi)
        sb.append("\n#### 🧠 4. A* Pathfinder\n");
        var inProgress = pb.getInProgress();
        if (inProgress.isPresent()) {
            AbstractNodeCostSearch finder = inProgress.get();
            sb.append("- **Stan A***: ⚡ W trakcie obliczania ścieżki\n");
            sb.append("- **Zbadane węzły (nodes explored)**: ").append(finder.nodesExplored).append("\n");
        } else {
            sb.append("- **Stan A***: Bezczynny / Ścieżka znaleziona\n");
        }

        // 5. Interakcja z blokami (Crosshair / Raytrace)
        sb.append("\n#### ⛏️ 5. Block Interaction\n");
        HitResult trace = baritone.getPlayerContext().objectMouseOver();
        if (trace != null && trace.getType() == HitResult.Type.BLOCK) {
            BlockPos targetBlock = ((BlockHitResult) trace).getBlockPos();
            BlockState state = player.level().getBlockState(targetBlock);
            sb.append(String.format("- **Celownik na bloku**: `%s` na pozycji [%d, %d, %d]\n",
                    state.getBlock().getName().getString(),
                    targetBlock.getX(), targetBlock.getY(), targetBlock.getZ()));
            sb.append(String.format("- **Dystans do celu**: %.2f bloków\n",
                    Math.sqrt(targetBlock.distSqr(feet))));
        } else {
            sb.append("- **Celownik na bloku**: Brak bloku w zasięgu celownika\n");
        }

        // 6. Wymuszone wejścia (Forced Inputs)
        sb.append("\n#### 🎮 6. Key Inputs Overrides\n");
        List<String> forced = new ArrayList<>();
        for (Input inp : Input.values()) {
            if (baritone.getInputOverrideHandler().isInputForcedDown(inp)) {
                forced.add(inp.name());
            }
        }
        sb.append("- **Wciskane klawisze przez Baritone**: ").append(forced.isEmpty() ? "`Brak (IDLE)`" : "`" + String.join(", ", forced) + "`").append("\n");
        sb.append("- **Czy ekran/menu jest otwarte**: `").append(mc.screen != null ? mc.screen.getClass().getSimpleName() : "Brak (w grze)").append("`\n");

        return sb.toString();
    }

    /**
     * Zapisuje bieżący raport diagnostyczny do pliku .minecraft/baritone/ai_state.md
     */
    public static File saveReportToFile() {
        try {
            Minecraft mc = Minecraft.getInstance();
            File baritoneDir = new File(mc.gameDirectory, "baritone");
            if (!baritoneDir.exists()) {
                baritoneDir.mkdirs();
            }
            File reportFile = new File(baritoneDir, "ai_state.md");
            String report = generateAiDiagnosticReport();
            Files.writeString(reportFile.toPath(), report, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return reportFile;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
