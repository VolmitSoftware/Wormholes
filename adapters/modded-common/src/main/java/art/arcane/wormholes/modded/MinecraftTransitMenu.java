package art.arcane.wormholes.modded;

import art.arcane.optics.crossing.ScaleRule;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.config.toml.TransitConfig;
import art.arcane.wormholes.localization.TransitMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.transit.MomentumPolicy;
import art.arcane.wormholes.transit.OrientationPolicy;
import art.arcane.wormholes.transit.ScaleRuleChange;
import art.arcane.wormholes.transit.ScaleRuleSettings;
import art.arcane.wormholes.transit.TransitionProfile;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

public final class MinecraftTransitMenu {
    static final double MIN_FACTOR = 0.0D;
    static final double MAX_FACTOR = 10.0D;
    static final int MAX_MASK_OVERRIDE_TICKS = 200;

    private final WormholesModRuntime runtime;
    private final MinecraftPortal portal;

    public MinecraftTransitMenu(WormholesModRuntime runtime, MinecraftPortal portal) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.portal = Objects.requireNonNull(portal, "portal");
    }

    public void open(ServerPlayer viewer) {
        MinecraftWindow window = new MinecraftWindow(runtime, viewer);
        window.setTitle(MinecraftPortalText.router(runtime, portal, true));
        window.setViewportHeight(3);
        window.setDecorator(Items.STAINED_GLASS_PANE.cyan());
        window.setElement(-2, 1, momentumElement(window, viewer));
        window.setElement(-1, 1, orientationElement(window, viewer));
        window.setElement(0, 1, membraneElement(window, viewer));
        window.setElement(1, 1, bounceElement(window, viewer));
        window.setElement(2, 1, profileElement(window, viewer));
        window.setElement(3, 1, scaleElement(window, viewer));
        window.setElement(0, 2, backElement(window, viewer));
        window.setVisible(true);
    }

    static MomentumPolicy momentum(MinecraftPortal portal) {
        return MomentumPolicy.decode(string(portal, "transit.momentum"));
    }

    static OrientationPolicy orientation(MinecraftPortal portal) {
        return OrientationPolicy.parse(string(portal, "transit.orientation"), null);
    }

    static boolean membrane(MinecraftPortal portal) {
        return Boolean.TRUE.equals(portal.setting("transit.membrane"));
    }

    static boolean bounce(MinecraftPortal portal) {
        return Boolean.TRUE.equals(portal.setting("transit.bounce"));
    }

    static TransitionProfile profile(MinecraftPortal portal) {
        return TransitionProfile.decode(string(portal, "transit.profile"));
    }

    static Double parseFactor(String text) {
        try {
            double factor = Double.parseDouble(text.trim());
            if (!Double.isFinite(factor) || factor < MIN_FACTOR || factor > MAX_FACTOR) {
                return null;
            }
            return factor;
        } catch (NumberFormatException | NullPointerException malformed) {
            return null;
        }
    }

    static Integer parseMaskTicks(String text) {
        String answer = text == null ? "" : text.trim();
        if (answer.isEmpty()) {
            return -1;
        }
        try {
            int ticks = Integer.parseInt(answer);
            if (ticks < -1 || ticks > MAX_MASK_OVERRIDE_TICKS) {
                return null;
            }
            return ticks;
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    static String soundOrEmpty(String text) {
        String answer = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (answer.isEmpty() || answer.equals("-") || answer.equals("none") || answer.equals("default")) {
            return "";
        }
        return answer;
    }

    private MinecraftElement momentumElement(MinecraftWindow window, ServerPlayer viewer) {
        MinecraftElement element = new MinecraftElement("transit-momentum");
        element.setMaterial(Items.SLIME_BALL);
        applyMomentum(element, viewer);
        element.onLeftClick(event -> {
            MomentumPolicy current = effectiveMomentum();
            changed(viewer, target -> target.setMomentum(current.withMode(current.mode().next())));
            applyMomentum(element, viewer);
            window.updateInventory();
        });
        element.onRightClick(event -> prompt(window, viewer, text -> {
            Double factor = parseFactor(text);
            if (factor != null) {
                MomentumPolicy current = effectiveMomentum();
                changed(viewer, target -> target.setMomentum(current.withFactor(factor)));
            }
        }));
        return element;
    }

    private void applyMomentum(MinecraftElement element, ServerPlayer viewer) {
        MomentumPolicy policy = effectiveMomentum();
        element.setEnchanted(momentum(portal) != null);
        MinecraftLegacyText.apply(viewer, element, TransitMessages.MENU_MOMENTUM, MinecraftPortalText.arguments(
            "mode", policy.mode().label(),
            "value", formatFactor(policy.factor())));
    }

    private MinecraftElement orientationElement(MinecraftWindow window, ServerPlayer viewer) {
        MinecraftElement element = new MinecraftElement("transit-orientation");
        element.setMaterial(Items.COMPASS);
        applyOrientation(element, viewer);
        element.onLeftClick(event -> {
            OrientationPolicy next = effectiveOrientation().next();
            changed(viewer, target -> target.setOrientation(next));
            applyOrientation(element, viewer);
            window.updateInventory();
        });
        return element;
    }

    private void applyOrientation(MinecraftElement element, ServerPlayer viewer) {
        element.setEnchanted(orientation(portal) != null);
        MinecraftLegacyText.apply(viewer, element, TransitMessages.MENU_ORIENTATION, MinecraftPortalText.arguments(
            "mode", effectiveOrientation().label()));
    }

    private MinecraftElement membraneElement(MinecraftWindow window, ServerPlayer viewer) {
        MinecraftElement element = new MinecraftElement("transit-membrane");
        element.setMaterial(Items.PHANTOM_MEMBRANE);
        applyToggle(element, viewer, TransitMessages.MENU_MEMBRANE, membrane(portal));
        element.onLeftClick(event -> {
            boolean enabled = !membrane(portal);
            changed(viewer, target -> target.setMembrane(enabled));
            applyToggle(element, viewer, TransitMessages.MENU_MEMBRANE, membrane(portal));
            window.updateInventory();
        });
        return element;
    }

    private MinecraftElement bounceElement(MinecraftWindow window, ServerPlayer viewer) {
        MinecraftElement element = new MinecraftElement("transit-bounce");
        element.setMaterial(Items.SLIME_BLOCK);
        applyToggle(element, viewer, TransitMessages.MENU_BOUNCE, bounce(portal));
        element.onLeftClick(event -> {
            boolean enabled = !bounce(portal);
            changed(viewer, target -> target.setBounce(enabled));
            applyToggle(element, viewer, TransitMessages.MENU_BOUNCE, bounce(portal));
            window.updateInventory();
        });
        return element;
    }

    private void applyToggle(MinecraftElement element, ServerPlayer viewer, LinesKey key, boolean enabled) {
        element.setEnchanted(enabled);
        MinecraftLegacyText.apply(viewer, element, key, MinecraftPortalText.arguments("state",
            plain(viewer, enabled ? WormholesMessages.LABEL_ON : WormholesMessages.LABEL_OFF)));
    }

    private MinecraftElement profileElement(MinecraftWindow window, ServerPlayer viewer) {
        MinecraftElement element = new MinecraftElement("transit-profile");
        element.setMaterial(Items.NOTE_BLOCK);
        applyProfile(element, viewer);
        element.onLeftClick(event -> prompt(window, viewer, text -> {
            TransitionProfile next = profile(portal).withThresholdEffect(soundOrEmpty(text));
            changed(viewer, target -> target.setTransitionProfile(next));
        }));
        element.onRightClick(event -> prompt(window, viewer, text -> {
            TransitionProfile next = profile(portal).withArrivalSound(soundOrEmpty(text));
            changed(viewer, target -> target.setTransitionProfile(next));
        }));
        element.onShiftLeftClick(event -> prompt(window, viewer, text -> {
            Integer ticks = parseMaskTicks(text);
            if (ticks != null) {
                TransitionProfile next = profile(portal).withMaskOverrideTicks(ticks);
                changed(viewer, target -> target.setTransitionProfile(next));
            }
        }));
        return element;
    }

    private void applyProfile(MinecraftElement element, ServerPlayer viewer) {
        TransitionProfile profile = profile(portal);
        element.setEnchanted(!profile.isNone());
        MinecraftLegacyText.apply(viewer, element, TransitMessages.MENU_PROFILE, MinecraftPortalText.arguments(
            "mode", labelOrDefault(profile.thresholdEffect()),
            "state", labelOrDefault(profile.arrivalSound())
                + (profile.overridesMask() ? " / " + profile.maskOverrideTicks() + "t" : "")));
    }

    private MinecraftElement scaleElement(MinecraftWindow window, ServerPlayer viewer) {
        MinecraftElement element = new MinecraftElement("transit-scale");
        element.setMaterial(Items.SPYGLASS);
        applyScale(element, viewer);
        element.onLeftClick(event -> {
            ScaleRule current = portal.getScaleRule();
            ScaleRule next = ScaleRuleSettings.withMode(current, ScaleRuleSettings.next(current.mode()));
            changed(viewer, target -> target.setScaleRule(next));
            applyScale(element, viewer);
            window.updateInventory();
        });
        element.onRightClick(event -> prompt(window, viewer, text -> {
            Double bound = ScaleRuleSettings.bound(text);
            if (bound != null) {
                ScaleRule next = ScaleRuleSettings.withMin(portal.getScaleRule(), bound);
                changed(viewer, target -> target.setScaleRule(next));
            }
        }));
        element.onShiftRightClick(event -> prompt(window, viewer, text -> {
            Double bound = ScaleRuleSettings.bound(text);
            if (bound != null) {
                ScaleRule next = ScaleRuleSettings.withMax(portal.getScaleRule(), bound);
                changed(viewer, target -> target.setScaleRule(next));
            }
        }));
        return element;
    }

    private void applyScale(MinecraftElement element, ServerPlayer viewer) {
        ScaleRule rule = portal.getScaleRule();
        element.setEnchanted(!ScaleRuleSettings.isDefault(rule));
        MinecraftLegacyText.apply(viewer, element, TransitMessages.MENU_SCALE, MinecraftPortalText.arguments(
            "mode", ScaleRuleChange.mode(rule),
            "value", ScaleRuleChange.range(rule)));
    }

    private MinecraftElement backElement(MinecraftWindow window, ServerPlayer viewer) {
        MinecraftElement element = new MinecraftElement("transit-back");
        element.setMaterial(Items.ARROW);
        MinecraftLegacyText.apply(viewer, element, WormholesMessages.PORTAL_MENU_BACK_SETTINGS, MessageArgs.empty());
        element.onLeftClick(event -> {
            window.close();
            runtime.menus().open(viewer, portal.getId());
        });
        return element;
    }

    private void prompt(MinecraftWindow window, ServerPlayer viewer, Consumer<String> apply) {
        window.close();
        String cancelWord = plain(viewer, WormholesMessages.PORTAL_INPUT_CANCEL);
        viewer.sendSystemMessage(MinecraftMenuText.text(viewer, TransitMessages.MENU_PROMPT,
            MinecraftPortalText.arguments("cancel", cancelWord)));
        runtime.chatInput().await(viewer, text -> {
            String answer = text == null ? "" : text.trim();
            if (!answer.equalsIgnoreCase(cancelWord)) {
                apply.accept(answer);
            }
            open(viewer);
        });
    }

    private void changed(ServerPlayer viewer, Consumer<MinecraftPortal> change) {
        if (runtime.portals().update(viewer, portal.getId(), change)) {
            runtime.menus().refresh(portal.getId());
        }
    }

    private MomentumPolicy effectiveMomentum() {
        MomentumPolicy explicit = momentum(portal);
        return explicit != null ? explicit : MomentumPolicy.of(MomentumPolicy.Mode.parse(config().momentumDefault, MomentumPolicy.Mode.PRESERVE));
    }

    private OrientationPolicy effectiveOrientation() {
        OrientationPolicy explicit = orientation(portal);
        return explicit != null ? explicit : OrientationPolicy.parse(config().orientationDefault, OrientationPolicy.FRAME);
    }

    private TransitConfig config() {
        return runtime.configuration().settings().getTransit();
    }

    private static String labelOrDefault(String value) {
        return value == null || value.isBlank() ? "default" : value;
    }

    private static String formatFactor(double factor) {
        return factor == Math.rint(factor) ? Integer.toString((int) factor) : String.format(Locale.ROOT, "%.2f", factor);
    }

    private static String plain(ServerPlayer viewer, TextKey key) {
        return MinecraftMenuText.text(viewer, key, MessageArgs.empty()).getString();
    }

    private static String string(MinecraftPortal portal, String key) {
        return portal.setting(key) instanceof String value ? value : "";
    }
}
