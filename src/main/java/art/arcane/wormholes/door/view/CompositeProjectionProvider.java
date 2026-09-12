package art.arcane.wormholes.door.view;

import art.arcane.wormholes.ProjectionManager;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.rtp.RtpRimRenderer;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;

/**
 * Runs several per-observer projection providers behind the single slot the projection manager has.
 * The first provider that claims a portal owns it; RTP portals keep going to the RTP runtime and
 * door apertures to the door provider.
 */
public final class CompositeProjectionProvider implements ProjectionManager.RtpProjectionProvider {
    private final List<ProjectionManager.RtpProjectionProvider> providers;

    public CompositeProjectionProvider(List<ProjectionManager.RtpProjectionProvider> providers) {
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
        for (ProjectionManager.RtpProjectionProvider provider : this.providers) {
            Objects.requireNonNull(provider, "provider");
        }
    }

    @Override
    public boolean supports(ILocalPortal portal) {
        return owner(portal) != null;
    }

    @Override
    public ProjectionManager.RtpProjectionResult touch(ILocalPortal portal, Player observer) {
        ProjectionManager.RtpProjectionProvider owner = owner(portal);
        if (owner == null) {
            throw new IllegalArgumentException("no projection provider claims " + portal);
        }
        return owner.touch(portal, observer);
    }

    @Override
    public World resolveTargetWorld(String worldKey) {
        for (ProjectionManager.RtpProjectionProvider provider : providers) {
            World world = provider.resolveTargetWorld(worldKey);
            if (world != null) {
                return world;
            }
        }
        return null;
    }

    @Override
    public void dispatchRim(ILocalPortal portal, Player observer, RtpRimRenderer.Sample sample) {
        ProjectionManager.RtpProjectionProvider owner = owner(portal);
        if (owner != null) {
            owner.dispatchRim(portal, observer, sample);
        }
    }

    private ProjectionManager.RtpProjectionProvider owner(ILocalPortal portal) {
        for (ProjectionManager.RtpProjectionProvider provider : providers) {
            if (provider.supports(portal)) {
                return provider;
            }
        }
        return null;
    }
}
