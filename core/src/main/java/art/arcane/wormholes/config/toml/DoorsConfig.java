package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Dimensional Door presentation. Changes hot-reload."
})
public class DoorsConfig {
    @ConfigDescription("Project the destination through Dimensional Door and trapdoor apertures. Off by default; doors keep their animated surface without a through-view until this is enabled.")
    public boolean projectionEnabled = false;

    @ConfigDescription("Most door apertures projected at once across the server. Frame portals are never starved by doors.")
    public int projectionMaxActive = 64;

    @ConfigDescription("How close a player has to be for a door aperture to start projecting, in blocks.")
    public int projectionRange = 24;

    @ConfigDescription("How far the view reaches past a door aperture, in blocks.")
    public int projectionDepthBlocks = 24;

    @ConfigDescription("Attendance rotation length for door apertures, in passes. Higher spreads the presence checks over more ticks.")
    public int projectionAttendanceSlots = 20;

    @ConfigDescription("Hide the opaque backing pane behind a door while its aperture is projecting.")
    public boolean projectionHideBacking = true;
}
