package art.arcane.wormholes.menu;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.entity.Player;

import art.arcane.volmlib.util.localization.LanguageAudience;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.PortalTypeAccess;

public record BukkitMenuViewer(Player player) implements MenuViewer, MenuStrings {
    public BukkitMenuViewer {
        Objects.requireNonNull(player, "player");
    }

    public static Player player(MenuViewer viewer) {
        return ((BukkitMenuViewer) viewer).player();
    }

    @Override
    public UUID id() {
        return player.getUniqueId();
    }

    @Override
    public String name() {
        return player.getName();
    }

    @Override
    public boolean hasPermission(String node) {
        return player.hasPermission(node);
    }

    @Override
    public boolean administrator() {
        return player.isOp() || player.hasPermission(PortalTypeAccess.ADMIN);
    }

    @Override
    public MenuStrings strings() {
        return this;
    }

    @Override
    public String text(TextKey key, MessageArgs arguments) {
        return LanguageAudience.call(player.getUniqueId(), () -> Wormholes.text().legacy(key, arguments));
    }

    @Override
    public MenuLines lines(LinesKey key, MessageArgs arguments, int chestHintLines) {
        List<String> resolved = LanguageAudience.call(player.getUniqueId(), () -> Wormholes.text().legacyLines(key, arguments));
        return MenuLines.split(resolved, key, chestHintLines);
    }
}
