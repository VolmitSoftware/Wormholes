package art.arcane.wormholes.modded.menu;

import art.arcane.wormholes.menu.MenuCaptures;
import art.arcane.wormholes.menu.MenuHost;
import art.arcane.wormholes.menu.MenuRenderer;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.modded.WormholesModRuntime;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class MinecraftMenuHost implements MenuHost {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");

    private final WormholesModRuntime runtime;
    private final MenuRenderer chestRenderer;
    private final MenuCaptures captures;

    public MinecraftMenuHost(WormholesModRuntime runtime, Options options) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.chestRenderer = options.chestRenderer();
        this.captures = options.captures();
    }

    @Override
    public Optional<MenuViewer> viewer(UUID viewerId) {
        return player(viewerId).<MenuViewer>map(player -> new MinecraftMenuViewer(runtime, player));
    }

    @Override
    public boolean runForViewer(UUID viewerId, Runnable task) {
        return runtime.schedule(task, 1L);
    }

    @Override
    public MenuRenderer chestRenderer() {
        return chestRenderer;
    }

    @Override
    public MinecraftMenuTransport transport() {
        return runtime.menuTransport();
    }

    @Override
    public MenuCaptures captures() {
        return captures;
    }

    @Override
    public boolean nativeScreensEnabled() {
        return runtime.running() && runtime.configuration().settings().getMenus().nativeScreens;
    }

    @Override
    public void warn(String message) {
        LOGGER.warn(message);
    }

    private Optional<ServerPlayer> player(UUID viewerId) {
        if (!runtime.running()) {
            return Optional.empty();
        }
        return Optional.ofNullable(runtime.server().getPlayerList().getPlayer(viewerId));
    }

    public record Options(MenuRenderer chestRenderer, MenuCaptures captures) {
        public Options {
            Objects.requireNonNull(chestRenderer, "chestRenderer");
            Objects.requireNonNull(captures, "captures");
        }
    }
}
