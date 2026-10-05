package art.arcane.wormholes.modded.client;

import net.minecraft.client.gui.components.debug.DebugEntryCategory;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Objects;
import java.util.function.Supplier;

public final class ClientViewDebugEntry implements DebugScreenEntry {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("wormholes", "client_view");
    public static final Identifier STATUS_ID = Identifier.fromNamespaceAndPath("wormholes", "connection_status");

    private final Supplier<String> line;

    public ClientViewDebugEntry(Supplier<String> line) {
        this.line = Objects.requireNonNull(line, "line");
    }

    @Override
    public void display(DebugScreenDisplayer displayer, Level level, LevelChunk clientChunk, LevelChunk serverChunk) {
        displayer.addLine(line.get());
    }

    @Override
    public DebugEntryCategory category() {
        return DebugEntryCategory.SCREEN_TEXT;
    }
}
