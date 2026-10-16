package art.arcane.wormholes.menu;

import java.util.Objects;

import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocolException;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;

public final class NativeMenuRenderer implements MenuRenderer {
    private final MenuTransport transport;

    public NativeMenuRenderer(MenuTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public void show(MenuViewer viewer, MenuBuild build, boolean opening) throws MenuProtocolException {
        MenuServerMessage message = opening ? new MenuServerMessage.Open(build.screen()) : new MenuServerMessage.Update(build.screen());
        byte[] frame = MenuCodec.encodeServer(message);
        if (!transport.send(viewer.id(), frame)) {
            throw new MenuProtocolException("the menu channel is not registered for " + viewer.name());
        }
    }

    @Override
    public void close(MenuViewer viewer) {
        sendQuietly(viewer, new MenuServerMessage.Close());
    }

    @Override
    public void notice(MenuViewer viewer, MenuNotice notice) {
        sendQuietly(viewer, new MenuServerMessage.Feedback(notice.feedback()));
    }

    private void sendQuietly(MenuViewer viewer, MenuServerMessage message) {
        try {
            transport.send(viewer.id(), MenuCodec.encodeServer(message));
        } catch (MenuProtocolException exception) {
            throw new IllegalStateException("menu frame for " + viewer.name() + " could not be encoded", exception);
        }
    }
}
