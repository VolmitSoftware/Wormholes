package art.arcane.wormholes.modded.menu.host;

import art.arcane.wormholes.config.toml.TransitConfig;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.transit.TransitEdit;
import art.arcane.wormholes.menu.transit.TransitMenuHost;
import art.arcane.wormholes.menu.transit.TransitMenuView;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import art.arcane.wormholes.transit.MomentumPolicy;
import art.arcane.wormholes.transit.OrientationPolicy;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public final class MinecraftTransitMenuHost implements TransitMenuHost {
    private final WormholesModRuntime runtime;

    public MinecraftTransitMenuHost(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public Optional<TransitMenuView> transit(UUID portalId) {
        return Optional.ofNullable(runtime.portals().get(portalId)).map(this::view);
    }

    @Override
    public boolean supportsGravityRotation() {
        return true;
    }

    @Override
    public void apply(MenuViewer viewer, UUID portalId, TransitEdit edit) {
        Consumer<MinecraftPortal> change = switch (edit) {
            case TransitEdit.Gravity gravity -> portal -> portal.setGravityRotationEnabled(gravity.enabled());
            case TransitEdit.Momentum momentum -> portal -> portal.setMomentum(momentum.policy());
            case TransitEdit.Orientation orientation -> portal -> portal.setOrientation(orientation.policy());
            case TransitEdit.Membrane membrane -> portal -> portal.setMembrane(membrane.enabled());
            case TransitEdit.Bounce bounce -> portal -> portal.setBounce(bounce.enabled());
            case TransitEdit.Profile profile -> portal -> portal.setTransitionProfile(profile.profile());
            case TransitEdit.Scale scale -> portal -> portal.setScaleRule(scale.rule());
        };
        if (runtime.portals().update(MinecraftMenuViewer.player(viewer), portalId, change)) {
            runtime.menus().invalidate(new MenuScope.Portal(portalId));
        }
    }

    private TransitMenuView view(MinecraftPortal portal) {
        TransitConfig config = runtime.configuration().settings().getTransit();
        MomentumPolicy momentum = portal.getMomentum();
        OrientationPolicy orientation = portal.getOrientation();
        return new TransitMenuView(momentum,
            momentum != null ? momentum : MomentumPolicy.of(MomentumPolicy.Mode.parse(config.momentumDefault, MomentumPolicy.Mode.PRESERVE)),
            orientation,
            orientation != null ? orientation : OrientationPolicy.parse(config.orientationDefault, OrientationPolicy.FRAME),
            portal.isMembrane(), portal.isBounce(), portal.getTransitionProfile(), portal.getScaleRule(), portal.isGravityRotationEnabled());
    }
}
