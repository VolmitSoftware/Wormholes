package art.arcane.wormholes.door;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

/** The optional {@code <template>.toml} sidecar beside a pocket template structure file. */
@ConfigDoc({
    "Options for one pocket template. Optional; a template without this file is a shared room."
})
public class PocketTemplateOptions {
    @ConfigDescription("Give every traveler their own copy of this template instead of sharing one room.")
    public boolean instanced = false;
}
