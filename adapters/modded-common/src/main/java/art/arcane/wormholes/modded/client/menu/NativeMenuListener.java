package art.arcane.wormholes.modded.client.menu;

import art.arcane.wormholes.menu.MenuFeedback;
import art.arcane.wormholes.menu.MenuScreen;
import com.mojang.blaze3d.systems.RenderSystem;

import java.util.Objects;
import java.util.Optional;

public final class NativeMenuListener implements ClientMenuListener {
    private final ClientMenuSession session;

    public NativeMenuListener(ClientMenuSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    public static Optional<NativeMenuScreen> current() {
        return ClientScreens.current() instanceof NativeMenuScreen screen ? Optional.of(screen) : Optional.empty();
    }

    @Override
    public void opened(MenuScreen screen) {
        RenderSystem.assertOnRenderThread();
        current().ifPresent(NativeMenuScreen::markReplaced);
        ClientScreens.open(new NativeMenuScreen(session, screen));
    }

    @Override
    public void updated(MenuScreen screen) {
        RenderSystem.assertOnRenderThread();
        current().ifPresent(open -> open.apply(screen));
    }

    @Override
    public void closed() {
        RenderSystem.assertOnRenderThread();
        current().ifPresent(NativeMenuScreen::closeFromServer);
    }

    @Override
    public void feedback(MenuFeedback feedback) {
        RenderSystem.assertOnRenderThread();
        current().ifPresent(open -> open.feedback(feedback));
    }
}
