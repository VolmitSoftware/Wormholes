package art.arcane.wormholes.modded.client;

import net.minecraft.client.gui.components.debug.DebugEntryCategory;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class ClientViewDebugEntry implements DebugScreenEntry {
    public static final Identifier STATUS_ID = Identifier.fromNamespaceAndPath("wormholes", "status");
    public static final Identifier DETAIL_ID = Identifier.fromNamespaceAndPath("wormholes", "status_detail");
    public static final Identifier GROUP = STATUS_ID;

    private final Supplier<List<String>> lines;

    public ClientViewDebugEntry(Supplier<List<String>> lines) {
        this.lines = Objects.requireNonNull(lines, "lines");
    }

    @Override
    public void display(DebugScreenDisplayer displayer, Level level, LevelChunk clientChunk, LevelChunk serverChunk) {
        displayer.addToGroup(GROUP, lines.get());
    }

    @Override
    public DebugEntryCategory category() {
        return DebugEntryCategory.SCREEN_TEXT;
    }
}
