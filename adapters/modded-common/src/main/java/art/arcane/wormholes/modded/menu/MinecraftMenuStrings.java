package art.arcane.wormholes.modded.menu;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.WormholesMessageRenderer;
import art.arcane.wormholes.menu.MenuLines;
import art.arcane.wormholes.menu.MenuStrings;

import java.util.Objects;

record MinecraftMenuStrings(LocalizationSnapshot snapshot) implements MenuStrings {
    MinecraftMenuStrings {
        Objects.requireNonNull(snapshot, "snapshot");
    }

    @Override
    public String text(TextKey key, MessageArgs arguments) {
        return WormholesMessageRenderer.legacy(snapshot.resolve(key, arguments));
    }

    @Override
    public MenuLines lines(LinesKey key, MessageArgs arguments, int chestHintLines) {
        return MenuLines.split(WormholesMessageRenderer.legacyLines(snapshot.resolve(key, arguments)), key, chestHintLines);
    }
}
