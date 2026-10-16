package art.arcane.wormholes.menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocolException;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;

public final class FakeMenuHost implements MenuHost {
    public final Map<UUID, FakeMenuViewer> viewers = new HashMap<>();
    public final RecordingRenderer chest = new RecordingRenderer();
    public final List<byte[]> frames = new ArrayList<>();
    public final List<Runnable> deferred = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    public boolean nativeEnabled = true;
    public boolean transportOpen = true;
    public boolean deferTasks;
    public boolean rejectTasks;
    public int scheduled;
    public CapturedItem heldItem;

    public FakeMenuViewer viewer(UUID id, String name) {
        FakeMenuViewer viewer = new FakeMenuViewer(id, name);
        viewers.put(id, viewer);
        return viewer;
    }

    @Override
    public Optional<MenuViewer> viewer(UUID viewerId) {
        return Optional.ofNullable(viewers.get(viewerId));
    }

    @Override
    public boolean runForViewer(UUID viewerId, Runnable task) {
        scheduled++;
        if (rejectTasks) {
            return false;
        }
        if (deferTasks) {
            deferred.add(task);
        } else {
            task.run();
        }
        return true;
    }

    @Override
    public MenuRenderer chestRenderer() {
        return chest;
    }

    @Override
    public MenuTransport transport() {
        return (viewerId, frame) -> {
            if (!transportOpen) {
                return false;
            }
            frames.add(frame);
            return true;
        };
    }

    @Override
    public MenuCaptures captures() {
        return new MenuCaptures() {
            @Override
            public void prompt(MenuViewer viewer, List<String> promptLines, Consumer<String> submitted, Runnable cancelled) {
                cancelled.run();
            }

            @Override
            public void dropItem(MenuViewer viewer, List<String> promptLines, Consumer<CapturedItem> captured, Runnable cancelled) {
                cancelled.run();
            }

            @Override
            public Optional<CapturedItem> heldItem(MenuViewer viewer) {
                return Optional.ofNullable(heldItem);
            }
        };
    }

    @Override
    public boolean nativeScreensEnabled() {
        return nativeEnabled;
    }

    @Override
    public void warn(String message) {
        warnings.add(message);
    }

    public void runDeferred() {
        List<Runnable> tasks = new ArrayList<>(deferred);
        deferred.clear();
        for (Runnable task : tasks) {
            task.run();
        }
    }

    public List<MenuServerMessage> serverMessages() throws MenuProtocolException {
        List<MenuServerMessage> messages = new ArrayList<>(frames.size());
        for (byte[] frame : frames) {
            messages.add(MenuCodec.decodeServer(frame));
        }
        return messages;
    }

    public MenuServerMessage lastMessage() throws MenuProtocolException {
        return MenuCodec.decodeServer(frames.get(frames.size() - 1));
    }

    public long screenRevision() throws MenuProtocolException {
        for (int index = frames.size() - 1; index >= 0; index--) {
            MenuServerMessage message = MenuCodec.decodeServer(frames.get(index));
            if (message instanceof MenuServerMessage.Open open) {
                return open.screen().revision();
            }
            if (message instanceof MenuServerMessage.Update update) {
                return update.screen().revision();
            }
        }
        return -1L;
    }
}
