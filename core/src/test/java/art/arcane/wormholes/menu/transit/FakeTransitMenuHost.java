package art.arcane.wormholes.menu.transit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import art.arcane.wormholes.menu.FakePortalAccess;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.transit.MomentumPolicy;
import art.arcane.wormholes.transit.OrientationPolicy;
import art.arcane.wormholes.transit.ScaleRuleSettings;
import art.arcane.wormholes.transit.TransitionProfile;

final class FakeTransitMenuHost implements TransitMenuHost {
    final List<TransitEdit> edits = new ArrayList<>();
    boolean gravity;
    boolean present = true;
    TransitMenuView view = new TransitMenuView(null, MomentumPolicy.of(MomentumPolicy.Mode.PRESERVE), null, OrientationPolicy.FRAME, false,
        false, TransitionProfile.NONE, ScaleRuleSettings.DEFAULT, false);

    @Override
    public Optional<TransitMenuView> transit(UUID portalId) {
        return present && portalId.equals(FakePortalAccess.PORTAL) ? Optional.of(view) : Optional.empty();
    }

    @Override
    public boolean supportsGravityRotation() {
        return gravity;
    }

    @Override
    public void apply(MenuViewer viewer, UUID portalId, TransitEdit edit) {
        edits.add(edit);
        view = switch (edit) {
            case TransitEdit.Gravity change -> new TransitMenuView(view.momentum(), view.effectiveMomentum(), view.orientation(),
                view.effectiveOrientation(), view.membrane(), view.bounce(), view.profile(), view.scaleRule(), change.enabled());
            case TransitEdit.Momentum change -> new TransitMenuView(change.policy(), change.policy(), view.orientation(), view.effectiveOrientation(),
                view.membrane(), view.bounce(), view.profile(), view.scaleRule(), view.gravityRotation());
            case TransitEdit.Orientation change -> new TransitMenuView(view.momentum(), view.effectiveMomentum(), change.policy(), change.policy(),
                view.membrane(), view.bounce(), view.profile(), view.scaleRule(), view.gravityRotation());
            case TransitEdit.Membrane change -> new TransitMenuView(view.momentum(), view.effectiveMomentum(), view.orientation(),
                view.effectiveOrientation(), change.enabled(), view.bounce(), view.profile(), view.scaleRule(), view.gravityRotation());
            case TransitEdit.Bounce change -> new TransitMenuView(view.momentum(), view.effectiveMomentum(), view.orientation(),
                view.effectiveOrientation(), view.membrane(), change.enabled(), view.profile(), view.scaleRule(), view.gravityRotation());
            case TransitEdit.Profile change -> new TransitMenuView(view.momentum(), view.effectiveMomentum(), view.orientation(),
                view.effectiveOrientation(), view.membrane(), view.bounce(), change.profile(), view.scaleRule(), view.gravityRotation());
            case TransitEdit.Scale change -> new TransitMenuView(view.momentum(), view.effectiveMomentum(), view.orientation(),
                view.effectiveOrientation(), view.membrane(), view.bounce(), view.profile(), change.rule(), view.gravityRotation());
        };
    }
}
