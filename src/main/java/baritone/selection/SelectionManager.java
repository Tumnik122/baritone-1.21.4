package baritone.selection;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.selection.ISelection;
import baritone.api.selection.ISelectionManager;
import baritone.api.utils.BetterBlockPos;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.Direction;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

public class SelectionManager implements ISelectionManager, AbstractGameEventListener {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final LinkedList<ISelection> selections = new LinkedList<>();
    private ISelection[] selectionsArr = new ISelection[0];
    private final Baritone baritone;
    private BetterBlockPos pos1 = null;
    private boolean loadedForCurrentWorld = false;

    public SelectionManager(Baritone baritone) {
        this.baritone = baritone;
        new SelectionRenderer(baritone, this);
        baritone.getGameEventHandler().registerEventListener(this);
    }

    @Override
    public synchronized BetterBlockPos getPos1() {
        return pos1;
    }

    @Override
    public synchronized void setPos1(BetterBlockPos pos) {
        this.pos1 = pos;
        save();
    }

    private void resetSelectionsArr() {
        selectionsArr = selections.toArray(new ISelection[0]);
    }

    @Override
    public synchronized ISelection addSelection(ISelection selection) {
        selections.add(selection);
        resetSelectionsArr();
        save();
        return selection;
    }

    @Override
    public ISelection addSelection(BetterBlockPos pos1, BetterBlockPos pos2) {
        return addSelection(new Selection(pos1, pos2));
    }

    @Override
    public synchronized ISelection removeSelection(ISelection selection) {
        selections.remove(selection);
        resetSelectionsArr();
        save();
        return selection;
    }

    @Override
    public synchronized ISelection[] removeAllSelections() {
        ISelection[] removed = getSelections();
        selections.clear();
        this.pos1 = null;
        resetSelectionsArr();
        save();
        return removed;
    }

    @Override
    public ISelection[] getSelections() {
        return selectionsArr;
    }

    @Override
    public synchronized ISelection getOnlySelection() {
        if (selections.size() == 1) {
            return selections.peekFirst();
        }

        return null;
    }

    @Override
    public ISelection getLastSelection() {
        return selections.peekLast();
    }

    @Override
    public synchronized ISelection expand(ISelection selection, Direction direction, int blocks) {
        for (ListIterator<ISelection> it = selections.listIterator(); it.hasNext(); ) {
            ISelection current = it.next();

            if (current == selection) {
                it.remove();
                it.add(current.expand(direction, blocks));
                resetSelectionsArr();
                save();
                return it.previous();
            }
        }

        return null;
    }

    @Override
    public synchronized ISelection contract(ISelection selection, Direction direction, int blocks) {
        for (ListIterator<ISelection> it = selections.listIterator(); it.hasNext(); ) {
            ISelection current = it.next();

            if (current == selection) {
                it.remove();
                it.add(current.contract(direction, blocks));
                resetSelectionsArr();
                save();
                return it.previous();
            }
        }

        return null;
    }

    @Override
    public synchronized ISelection shift(ISelection selection, Direction direction, int blocks) {
        for (ListIterator<ISelection> it = selections.listIterator(); it.hasNext(); ) {
            ISelection current = it.next();

            if (current == selection) {
                it.remove();
                it.add(current.shift(direction, blocks));
                resetSelectionsArr();
                save();
                return it.previous();
            }
        }

        return null;
    }

    // =========================================================================
    // Persistence (zapis i odczyt zaznaczeń między restartami i serwerami)
    // =========================================================================

    private Path getWorldStoragePath() {
        try {
            if (baritone.getWorldProvider() != null && baritone.getWorldProvider().getCurrentWorld() != null) {
                Path worldDir = baritone.getWorldProvider().getCurrentWorld().directory;
                if (worldDir != null) {
                    return worldDir.resolve("selections.json");
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Path getGlobalStoragePath() {
        return baritone.getDirectory().resolve("saved_selections.json");
    }

    public synchronized void save() {
        try {
            if (selections.isEmpty() && pos1 == null) {
                Path worldPath = getWorldStoragePath();
                if (worldPath != null) {
                    Files.deleteIfExists(worldPath);
                }
                Files.deleteIfExists(getGlobalStoragePath());
                return;
            }

            SavedSelectionsData data = new SavedSelectionsData();
            if (pos1 != null) {
                data.pos1 = new BlockPosDto(pos1.x, pos1.y, pos1.z);
            }
            for (ISelection sel : selections) {
                data.selections.add(new SelectionDto(sel.pos1(), sel.pos2()));
            }

            Path targetPath = getWorldStoragePath();
            if (targetPath == null) {
                targetPath = getGlobalStoragePath();
            }

            Files.createDirectories(targetPath.getParent());
            try (Writer writer = Files.newBufferedWriter(targetPath)) {
                GSON.toJson(data, writer);
            }

            if (targetPath != getGlobalStoragePath()) {
                Files.createDirectories(getGlobalStoragePath().getParent());
                try (Writer writer = Files.newBufferedWriter(getGlobalStoragePath())) {
                    GSON.toJson(data, writer);
                }
            }
        } catch (Exception e) {
            System.err.println("[Baritone] Błąd podczas zapisywania zaznaczeń: " + e.getMessage());
        }
    }

    public synchronized void load() {
        try {
            Path targetPath = getWorldStoragePath();
            if (targetPath == null || !Files.exists(targetPath)) {
                targetPath = getGlobalStoragePath();
            }
            if (targetPath == null || !Files.exists(targetPath)) {
                return;
            }

            SavedSelectionsData data;
            try (Reader reader = Files.newBufferedReader(targetPath)) {
                data = GSON.fromJson(reader, SavedSelectionsData.class);
            }
            if (data == null) {
                return;
            }

            selections.clear();
            if (data.pos1 != null) {
                this.pos1 = new BetterBlockPos(data.pos1.x, data.pos1.y, data.pos1.z);
            } else {
                this.pos1 = null;
            }

            if (data.selections != null) {
                for (SelectionDto dto : data.selections) {
                    BetterBlockPos p1 = new BetterBlockPos(dto.x1, dto.y1, dto.z1);
                    BetterBlockPos p2 = new BetterBlockPos(dto.x2, dto.y2, dto.z2);
                    selections.add(new Selection(p1, p2));
                }
            }
            resetSelectionsArr();
            if (!selections.isEmpty() || pos1 != null) {
                System.out.println("[Baritone] Wczytano zapisane zaznaczenie (" + selections.size() + " obszarów)");
            }
        } catch (Exception e) {
            System.err.println("[Baritone] Błąd podczas wczytywania zaznaczeń: " + e.getMessage());
        }
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        if (event.getWorld() != null && event.getState() == EventState.POST) {
            loadedForCurrentWorld = false;
        } else if (event.getWorld() == null) {
            loadedForCurrentWorld = false;
        }
    }

    @Override
    public void onTick(TickEvent event) {
        if (!loadedForCurrentWorld && event.getType() == TickEvent.Type.IN) {
            if (baritone.getPlayerContext() != null && baritone.getPlayerContext().world() != null) {
                if (baritone.getWorldProvider() != null && baritone.getWorldProvider().getCurrentWorld() != null) {
                    loadedForCurrentWorld = true;
                    load();
                }
            }
        }
    }

    public static class BlockPosDto {
        public int x, y, z;
        public BlockPosDto() {}
        public BlockPosDto(int x, int y, int z) {
            this.x = x; this.y = y; this.z = z;
        }
    }

    public static class SelectionDto {
        public int x1, y1, z1;
        public int x2, y2, z2;
        public SelectionDto() {}
        public SelectionDto(BetterBlockPos p1, BetterBlockPos p2) {
            this.x1 = p1.x; this.y1 = p1.y; this.z1 = p1.z;
            this.x2 = p2.x; this.y2 = p2.y; this.z2 = p2.z;
        }
    }

    public static class SavedSelectionsData {
        public BlockPosDto pos1;
        public List<SelectionDto> selections = new ArrayList<>();
    }
}
