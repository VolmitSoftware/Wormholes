package art.arcane.wormholes.modded.seamless;

import net.minecraft.server.level.ServerPlayer;

import java.util.AbstractList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

public final class RoutedOnlyPlayers extends AbstractList<ServerPlayer> {
    public static final List<ServerPlayer> INSTANCE = new RoutedOnlyPlayers();

    private RoutedOnlyPlayers() {
    }

    @Override
    public ServerPlayer get(int index) {
        throw new IndexOutOfBoundsException(index);
    }

    @Override
    public int size() {
        return 1;
    }

    @Override
    public Iterator<ServerPlayer> iterator() {
        return Collections.emptyIterator();
    }

    @Override
    public void forEach(Consumer<? super ServerPlayer> action) {
    }
}
