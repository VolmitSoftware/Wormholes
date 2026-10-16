package art.arcane.wormholes.modded.menu;

import art.arcane.wormholes.menu.MenuStrings;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.modded.MinecraftLocalization;
import art.arcane.wormholes.modded.WormholesModRuntime;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;
import java.util.UUID;

public final class MinecraftMenuViewer implements MenuViewer {
    private final WormholesModRuntime runtime;
    private final ServerPlayer player;

    public MinecraftMenuViewer(WormholesModRuntime runtime, ServerPlayer player) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.player = Objects.requireNonNull(player, "player");
    }

    public static ServerPlayer player(MenuViewer viewer) {
        if (viewer instanceof MinecraftMenuViewer minecraft) {
            return minecraft.player;
        }
        throw new IllegalArgumentException("Menu viewer " + viewer.name() + " is not a modded player");
    }

    public ServerPlayer player() {
        return player;
    }

    @Override
    public UUID id() {
        return player.getUUID();
    }

    @Override
    public String name() {
        return player.getGameProfile().name();
    }

    @Override
    public boolean hasPermission(String node) {
        return runtime.access().permission(player, node);
    }

    @Override
    public boolean administrator() {
        return runtime.access().administrator(player);
    }

    @Override
    public MenuStrings strings() {
        return new MinecraftMenuStrings(MinecraftLocalization.forPlayer(player).snapshot(player));
    }
}
