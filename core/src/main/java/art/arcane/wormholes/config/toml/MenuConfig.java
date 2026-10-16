package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Player menus. Changes hot-reload."
})
public class MenuConfig {
    @ConfigDescription("Show native menu screens to players running the Wormholes client mod. Players without the mod always get chest menus.")
    public boolean nativeScreens = true;
}
