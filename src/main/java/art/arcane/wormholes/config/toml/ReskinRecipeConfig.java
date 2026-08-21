package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;

/**
 * A reskin recipe toggle.
 *
 * <p>Reskinning has no configurable grid: the result is derived from the exact
 * two items placed in, so the inputs are not free to redefine.</p>
 */
public class ReskinRecipeConfig {
    @ConfigDescription("Whether this reskin recipe exists. False removes it from the server.")
    public boolean enabled = true;
}
