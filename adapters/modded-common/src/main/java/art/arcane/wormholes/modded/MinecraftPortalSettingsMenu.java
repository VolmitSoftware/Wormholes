package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.access.PortalPermissionKey;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.ApertureShapePresets;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.NetworkViewQuality;
import art.arcane.wormholes.portal.PortalPermissionMode;
import art.arcane.wormholes.portal.PortalTravelMode;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.Objects;
import java.util.function.ObjIntConsumer;
import java.util.function.ToIntFunction;

final class MinecraftPortalSettingsMenu {
    private static final String DEFAULT_NETWORK_VIEW_FALLBACK_BLOCK = "minecraft:air";
    private static final NumberControl CAPTURE_RADIUS = new NumberControl("network-view-depth",
        WormholesMessages.PORTAL_NETWORK_LABEL_CAPTURE_RADIUS, WormholesMessages.PORTAL_NETWORK_DESCRIPTION_CAPTURE_RADIUS,
        Items.SPYGLASS, MinecraftPortal::getNetworkViewDepth, MinecraftPortal::setNetworkViewDepth, 4, 16);
    private static final NumberControl FULL_REFRESH = new NumberControl("network-view-heartbeat",
        WormholesMessages.PORTAL_NETWORK_LABEL_FULL_REFRESH, WormholesMessages.PORTAL_NETWORK_DESCRIPTION_FULL_REFRESH,
        Items.CLOCK, MinecraftPortal::getNetworkViewHeartbeatTicks, MinecraftPortal::setNetworkViewHeartbeatTicks, 10, 60);
    private static final NumberControl ENTITY_UPDATE = new NumberControl("network-view-entities",
        WormholesMessages.PORTAL_NETWORK_LABEL_ENTITY_UPDATE, WormholesMessages.PORTAL_NETWORK_DESCRIPTION_ENTITY_UPDATE,
        Items.ENDER_EYE, MinecraftPortal::getNetworkViewEntityIntervalTicks, MinecraftPortal::setNetworkViewEntityIntervalTicks, 2, 20);
    private static final NumberControl VIEW_GRACE = new NumberControl("network-view-grace",
        WormholesMessages.PORTAL_NETWORK_LABEL_VIEW_GRACE, WormholesMessages.PORTAL_NETWORK_DESCRIPTION_VIEW_GRACE,
        Items.REDSTONE, MinecraftPortal::getNetworkViewUnsubscribeGraceSeconds, MinecraftPortal::setNetworkViewUnsubscribeGraceSeconds, 5, 30);

    private final WormholesModRuntime runtime;
    private final MinecraftPortalMenus menus;

    MinecraftPortalSettingsMenu(WormholesModRuntime runtime, MinecraftPortalMenus menus) {
        this.runtime = Objects.requireNonNull(runtime);
        this.menus = Objects.requireNonNull(menus);
    }

    void open(ServerPlayer viewer, MinecraftPortal portal) {
        create(viewer, portal).open();
    }

    private MinecraftWindow create(ServerPlayer p, MinecraftPortal portal) {
        MinecraftWindow window = menus.window(p, portal);
        boolean custom = portal.getNetworkViewQuality() == NetworkViewQuality.CUSTOM;
        window.setViewportHeight(custom ? 6 : 5);
        window.setDecorator(Items.STAINED_GLASS_PANE.gray());

        window.setElement(0, 0, MinecraftPortalText.localizedElement(p, "settings-placard",
            WormholesMessages.PORTAL_MENU_SETTINGS_PLACARD_GATEWAY, MessageArgs.empty(), Items.LEVER));

        window.setElement(-3, 1, permissionElement(window, p, portal));
        window.setElement(-1, 1, travelDirectionElement(window, p, portal));
        window.setElement(1, 1, networkViewQualityElement(window, p, portal));
        window.setElement(3, 1, settingsSyncElement(window, p, portal));

        MinecraftPortalCosmeticsMenu cosmetics = menus.cosmetics();
        if (custom) {
            window.setElement(-3, 2, networkViewNumberElement(window, p, portal, CAPTURE_RADIUS));
            window.setElement(-1, 2, networkViewNumberElement(window, p, portal, FULL_REFRESH));
            window.setElement(1, 2, networkViewNumberElement(window, p, portal, ENTITY_UPDATE));
            window.setElement(3, 2, networkViewNumberElement(window, p, portal, VIEW_GRACE));
            window.setElement(-4, 3, cosmetics.blackoutElement(window, p, portal));
            window.setElement(-2, 3, cosmetics.ambientParticlesElement(window, p, portal));
            window.setElement(-1, 3, apertureShapeElement(window, p, portal));
            window.setElement(0, 3, networkViewFallbackElement(p, window, portal));
            window.setElement(2, 3, cosmetics.surfaceSkinElement(window, p, portal));
            window.setElement(4, 3, activationRangeElement(window, p, portal));
            window.setElement(-2, 4, renderModeElement(window, p, portal));
            window.setElement(0, 4, publicLookLabelElement(window, p, portal));
            window.setElement(2, 4, costOpenerElement(window, p, portal));
            if (menus.extensions().hasEntries()) {
                window.setElement(4, 4, menus.extensions().openerElement(window, p, portal));
            }
        } else {
            window.setElement(-4, 2, cosmetics.blackoutElement(window, p, portal));
            window.setElement(-2, 2, activationRangeElement(window, p, portal));
            window.setElement(0, 2, renderModeElement(window, p, portal));
            window.setElement(2, 2, cosmetics.ambientParticlesElement(window, p, portal));
            window.setElement(3, 2, apertureShapeElement(window, p, portal));
            window.setElement(4, 2, cosmetics.surfaceSkinElement(window, p, portal));
            window.setElement(-1, 3, publicLookLabelElement(window, p, portal));
            window.setElement(1, 3, costOpenerElement(window, p, portal));
            if (menus.extensions().hasEntries()) {
                window.setElement(3, 3, menus.extensions().openerElement(window, p, portal));
            }
        }

        window.setElement(0, custom ? 5 : 4, menus.backToPortalMenuElement(window, p, portal));
        return window;
    }

    private void openAdvanced(ServerPlayer p, MinecraftPortal portal) {
        MinecraftWindow window = menus.window(p, portal);
        window.setViewportHeight(5);
        window.setDecorator(Items.STAINED_GLASS_PANE.black());
        MinecraftPortalCosmeticsMenu cosmetics = menus.cosmetics();
        window.setElement(0, 0, MinecraftPortalText.localizedElement(p, "advanced-settings-placard",
            WormholesMessages.PORTAL_MENU_ADVANCED_SETTINGS, MessageArgs.empty(), Items.COMPARATOR));
        window.setElement(-3, 1, networkViewNumberElement(window, p, portal, CAPTURE_RADIUS));
        window.setElement(-1, 1, networkViewNumberElement(window, p, portal, FULL_REFRESH));
        window.setElement(1, 1, networkViewNumberElement(window, p, portal, ENTITY_UPDATE));
        window.setElement(3, 1, networkViewNumberElement(window, p, portal, VIEW_GRACE));
        window.setElement(-2, 2, cosmetics.ambientParticlesElement(window, p, portal));
        window.setElement(0, 2, networkViewFallbackElement(p, window, portal));
        window.setElement(2, 2, cosmetics.surfaceSkinElement(window, p, portal));
        window.setElement(-2, 3, cosmetics.blackoutElement(window, p, portal));
        window.setElement(0, 3, renderModeElement(window, p, portal));
        window.setElement(2, 3, activationRangeElement(window, p, portal));
        window.setElement(0, 4, backToSettingsMenuElement(window, p, portal));
        window.open();
    }

    MinecraftElement backToSettingsMenuElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftElement element = MinecraftPortalText.localizedElement(viewer, "back-to-settings", WormholesMessages.PORTAL_MENU_BACK_SETTINGS,
            MessageArgs.empty(), Items.ARROW);
        element.onLeftClick(clicked -> menus.runEntity(viewer, () -> {
            window.close();
            open(viewer, portal);
        }));
        return element;
    }

    private MinecraftElement travelDirectionElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftElement element = new MinecraftElement("travel-direction");
        element.onLeftClick(clicked -> {
            if (portal.isManaged()) {
                MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_TRAVEL_MANAGED);
                return;
            }
            if (portal.isMirrorMode()) {
                MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_TRAVEL_MIRROR_LOCKED);
                return;
            }
            PortalTravelMode mode = travelMode(portal).next();
            menus.update(viewer, portal, target -> {
                target.setOutgoingTraversalsEnabled(mode.allowsOutgoing());
                target.setIncomingTraversalsEnabled(mode.allowsIncoming());
            });
            applyTravelDirectionElement(viewer, element, portal);
            window.updateInventory();
            MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_TRAVEL_CHANGED,
                MinecraftPortalText.arguments("mode", MinecraftPortalText.travelModeLabel(viewer, mode)));
        });
        applyTravelDirectionElement(viewer, element, portal);
        return element;
    }

    private void applyTravelDirectionElement(ServerPlayer viewer, MinecraftElement element, MinecraftPortal portal) {
        DimensionalPortalKind kind = portal.getDimensionalKind();
        if (kind.isManagedPortal()) {
            boolean receiver = kind.isReceiverOnly();
            String direction = MinecraftPortalText.localized(viewer, kind.isNetherPortal()
                ? WormholesMessages.PORTAL_LABEL_BOTH_WAYS
                : receiver ? WormholesMessages.PORTAL_LABEL_ARRIVAL_ONLY : WormholesMessages.PORTAL_LABEL_DEPARTURE_ONLY);
            String detail = MinecraftPortalText.localized(viewer, kind.isNetherPortal()
                ? WormholesMessages.PORTAL_LABEL_DIMENSIONAL_BOTH_ACTIVE : WormholesMessages.PORTAL_LABEL_DIMENSIONAL_RETURN_DISABLED);
            MinecraftLegacyText.apply(viewer, element, WormholesMessages.PORTAL_MENU_TRAVEL_MANAGED,
                MinecraftPortalText.arguments("direction", direction, "detail", detail));
            element.setEnchanted(true);
            element.setMaterial(kind.isNetherPortal() ? Items.OBSIDIAN : receiver ? Items.ENDER_EYE : Items.ENDER_PEARL);
            return;
        }
        if (portal.isMirrorMode()) {
            MinecraftLegacyText.apply(viewer, element, WormholesMessages.PORTAL_MENU_TRAVEL_MIRROR, MessageArgs.empty());
            element.setEnchanted(false);
            element.setMaterial(Items.BARRIER);
            return;
        }
        PortalTravelMode mode = travelMode(portal);
        MinecraftLegacyText.apply(viewer, element, WormholesMessages.PORTAL_MENU_TRAVEL, MinecraftPortalText.arguments(
            "mode", MinecraftPortalText.travelModeLabel(viewer, mode),
            "outgoing", MinecraftPortalText.onOffLabel(viewer, mode.allowsOutgoing()),
            "incoming", MinecraftPortalText.onOffLabel(viewer, mode.allowsIncoming())));
        element.setEnchanted(mode != PortalTravelMode.LOCKED);
        element.setMaterial(switch (mode) {
            case BOTH -> Items.RECOVERY_COMPASS;
            case OUTBOUND -> Items.ENDER_PEARL;
            case INBOUND -> Items.ENDER_EYE;
            case LOCKED -> Items.BARRIER;
        });
    }

    private static PortalTravelMode travelMode(MinecraftPortal portal) {
        return PortalTravelMode.from(portal.isOutgoingTraversalsEnabled(), portal.isIncomingTraversalsEnabled());
    }

    private MinecraftElement networkViewQualityElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftElement element = new MinecraftElement("network-view-quality");
        element.onLeftClick(clicked -> {
            NetworkViewQuality previous = portal.getNetworkViewQuality();
            NetworkViewQuality quality = previous.next();
            menus.update(viewer, portal, target -> target.setNetworkViewQuality(quality));
            MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_STREAM_QUALITY_CHANGED,
                MinecraftPortalText.arguments("quality", MinecraftPortalText.networkViewQualityLabel(viewer, quality)));
            if ((previous == NetworkViewQuality.CUSTOM) != (quality == NetworkViewQuality.CUSTOM)) {
                menus.runEntity(viewer, () -> {
                    window.close();
                    open(viewer, portal);
                });
                return;
            }
            applyNetworkViewQualityElement(viewer, element, portal);
            window.updateInventory();
        });
        element.onShiftLeftClick(clicked -> menus.runEntity(viewer, () -> {
            window.close();
            openAdvanced(viewer, portal);
        }));
        applyNetworkViewQualityElement(viewer, element, portal);
        return element;
    }

    private void applyNetworkViewQualityElement(ServerPlayer viewer, MinecraftElement element, MinecraftPortal portal) {
        NetworkViewQuality quality = portal.getNetworkViewQuality();
        MinecraftLegacyText.apply(viewer, element, WormholesMessages.PORTAL_MENU_STREAM_QUALITY, MinecraftPortalText.arguments(
            "quality", MinecraftPortalText.networkViewQualityLabel(viewer, quality),
            "depth", portal.getNetworkViewDepth(),
            "entities", portal.getNetworkViewEntityIntervalTicks(),
            "refresh", portal.getNetworkViewHeartbeatTicks(),
            "grace", portal.getNetworkViewUnsubscribeGraceSeconds()));
        element.setEnchanted(quality == NetworkViewQuality.CINEMATIC);
        element.setMaterial(switch (quality) {
            case STANDARD -> Items.CONDUIT;
            case PERFORMANCE -> Items.FEATHER;
            case BALANCED -> Items.SPYGLASS;
            case CINEMATIC -> Items.BEACON;
            case CUSTOM -> Items.COMPARATOR;
        });
    }

    private MinecraftElement networkViewNumberElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal, NumberControl control) {
        MinecraftElement element = new MinecraftElement(control.id());
        element.onLeftClick(clicked -> adjustNetworkViewNumber(element, window, viewer, portal, control, control.step()));
        element.onRightClick(clicked -> adjustNetworkViewNumber(element, window, viewer, portal, control, -control.step()));
        element.onShiftLeftClick(clicked -> adjustNetworkViewNumber(element, window, viewer, portal, control, control.largeStep()));
        element.onShiftRightClick(clicked -> adjustNetworkViewNumber(element, window, viewer, portal, control, -control.largeStep()));
        applyNetworkViewNumberElement(viewer, element, portal, control, control.step(), control.largeStep());
        return element;
    }

    private void adjustNetworkViewNumber(MinecraftElement element, MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal,
                                         NumberControl control, int delta) {
        int previous = control.getter().applyAsInt(portal);
        menus.update(viewer, portal, target -> control.setter().accept(target, previous + delta));
        int current = control.getter().applyAsInt(portal);
        applyNetworkViewNumberElement(viewer, element, portal, control, Math.abs(delta), Math.abs(delta));
        window.updateInventory();
        if (current != previous) {
            MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED,
                MinecraftPortalText.arguments("label", MinecraftPortalText.localized(viewer, control.label()), "value", current));
        }
    }

    private void applyNetworkViewNumberElement(ServerPlayer viewer, MinecraftElement element, MinecraftPortal portal, NumberControl control,
                                               int step, int largeStep) {
        MinecraftLegacyText.apply(viewer, element, WormholesMessages.PORTAL_MENU_NETWORK_NUMBER, MinecraftPortalText.arguments(
            "label", MinecraftPortalText.localized(viewer, control.label()),
            "description", MinecraftPortalText.localized(viewer, control.description()),
            "value", control.getter().applyAsInt(portal),
            "step", Math.abs(step),
            "large_step", Math.abs(largeStep)));
        element.setMaterial(control.material());
    }

    private MinecraftElement networkViewFallbackElement(ServerPlayer p, MinecraftWindow window, MinecraftPortal portal) {
        MinecraftElement element = new MinecraftElement("network-view-fallback");
        element.onLeftClick(clicked -> {
            window.close();
            p.closeContainer();
            p.sendSystemMessage(MinecraftMenuText.text(p, WormholesMessages.PORTAL_PROMPT_BLOCK_STATE,
                MinecraftPortalText.arguments("cancel", MinecraftPortalText.localized(p, WormholesMessages.PORTAL_INPUT_CANCEL))));
            runtime.chatInput().await(p, input -> {
                if (input == null || MinecraftPortalText.isCancelInput(p, input)) {
                    open(p, portal);
                    return;
                }
                String previous = portal.getNetworkViewFallbackBlock();
                menus.update(p, portal, target -> target.setNetworkViewFallbackBlock(input));
                if (!previous.equals(portal.getNetworkViewFallbackBlock())) {
                    MinecraftPortalText.notifySetting(p, portal, WormholesMessages.PORTAL_FALLBACK_SET,
                        MinecraftPortalText.arguments("block", portal.getNetworkViewFallbackBlock()));
                }
                open(p, portal);
            });
        });
        element.onRightClick(clicked -> {
            String previous = portal.getNetworkViewFallbackBlock();
            menus.update(p, portal, target -> target.setNetworkViewFallbackBlock(DEFAULT_NETWORK_VIEW_FALLBACK_BLOCK));
            applyNetworkViewFallbackElement(p, element, portal);
            window.updateInventory();
            if (!previous.equals(portal.getNetworkViewFallbackBlock())) {
                MinecraftPortalText.notifySetting(p, portal, WormholesMessages.PORTAL_FALLBACK_RESET,
                    MinecraftPortalText.arguments("block", portal.getNetworkViewFallbackBlock()));
            }
        });
        applyNetworkViewFallbackElement(p, element, portal);
        return element;
    }

    private void applyNetworkViewFallbackElement(ServerPlayer viewer, MinecraftElement element, MinecraftPortal portal) {
        MinecraftLegacyText.apply(viewer, element, WormholesMessages.PORTAL_MENU_FALLBACK_BLOCK,
            MinecraftPortalText.arguments("block", portal.getNetworkViewFallbackBlock()));
        element.setMaterial(Items.GLASS);
    }

    private MinecraftElement activationRangeElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftElement element = new MinecraftElement("activation-range");
        element.onLeftClick(clicked -> adjustActivationRange(element, window, viewer, portal, 8));
        element.onRightClick(clicked -> adjustActivationRange(element, window, viewer, portal, -8));
        element.onShiftLeftClick(clicked -> adjustActivationRange(element, window, viewer, portal, 32));
        element.onShiftRightClick(clicked -> adjustActivationRange(element, window, viewer, portal, -32));
        applyActivationRangeElement(viewer, element, portal);
        return element;
    }

    private void adjustActivationRange(MinecraftElement element, MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal, int delta) {
        int previous = portal.getActivationRange();
        int base = previous > 0 ? previous : globalProjectionRange();
        int target = base + delta;
        int applied = target < 8 ? 0 : target;
        menus.update(viewer, portal, changed -> changed.setActivationRange(applied));
        int current = portal.getActivationRange();
        applyActivationRangeElement(viewer, element, portal);
        window.updateInventory();
        if (current != previous) {
            MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED, MinecraftPortalText.arguments(
                "label", MinecraftPortalText.localized(viewer, WormholesMessages.PORTAL_NETWORK_LABEL_ACTIVATION_RANGE),
                "value", activationRangeDisplay(viewer, portal)));
        }
    }

    private void applyActivationRangeElement(ServerPlayer viewer, MinecraftElement element, MinecraftPortal portal) {
        MinecraftLegacyText.apply(viewer, element, WormholesMessages.PORTAL_MENU_ACTIVATION_RANGE,
            MinecraftPortalText.arguments("value", activationRangeDisplay(viewer, portal), "step", 8, "large_step", 32));
        element.setMaterial(Items.LODESTONE);
        element.setEnchanted(portal.getActivationRange() > 0);
    }

    private String activationRangeDisplay(ServerPlayer viewer, MinecraftPortal portal) {
        if (portal.getActivationRange() > 0) {
            return Integer.toString(portal.getActivationRange());
        }
        return MinecraftPortalText.plain(viewer, WormholesMessages.PORTAL_LABEL_ACTIVATION_GLOBAL,
            MinecraftPortalText.arguments("range", globalProjectionRange()));
    }

    private int globalProjectionRange() {
        return (int) Math.round(runtime.configuration().settings().getProjection().range);
    }

    private MinecraftElement renderModeElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftElement element = new MinecraftElement("render-mode");
        element.onLeftClick(clicked -> {
            ProjectionRenderMode next = portal.getRenderMode().next();
            menus.update(viewer, portal, target -> target.setRenderMode(next));
            applyRenderModeElement(viewer, element, portal);
            window.updateInventory();
            MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED, MinecraftPortalText.arguments(
                "label", MinecraftPortalText.localized(viewer, WormholesMessages.PORTAL_NETWORK_LABEL_RENDER_MODE),
                "value", portal.getRenderMode().displayName()));
        });
        applyRenderModeElement(viewer, element, portal);
        return element;
    }

    private void applyRenderModeElement(ServerPlayer viewer, MinecraftElement element, MinecraftPortal portal) {
        ProjectionRenderMode mode = portal.getRenderMode();
        MinecraftLegacyText.apply(viewer, element, WormholesMessages.PORTAL_MENU_RENDER_MODE,
            MinecraftPortalText.arguments("mode", mode.displayName()));
        element.setMaterial(switch (mode) {
            case PANOPTIC -> Items.SPYGLASS;
            case VENTICULAR -> Items.TINTED_GLASS;
        });
        element.setEnchanted(mode != ProjectionRenderMode.PANOPTIC);
    }

    private MinecraftElement costOpenerElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftPortalCostMenu costs = menus.costs();
        Map<?, ?> price = MinecraftPortalCostMenu.price(portal);
        Item material = Items.HOPPER;
        if (MinecraftPortalCostMenu.vanilla(price)) {
            material = costs.template(price).getItem();
        } else if (MinecraftPortalCostMenu.vault(price)) {
            material = Items.EMERALD;
        }
        MinecraftElement element = MinecraftPortalText.localizedElement(viewer, "travel-cost", WormholesMessages.PORTAL_MENU_COST_OPENER,
            MinecraftPortalText.arguments("mode", costs.modeLabel(viewer, price), "cost", costs.costSummary(viewer, price)), material);
        if (MinecraftPortalCostMenu.vanilla(price)) {
            element.setBaseItemStack(costs.template(price));
        }
        if (!price.isEmpty()) {
            element.setEnchanted(true);
        }
        element.onLeftClick(clicked -> {
            window.close();
            costs.open(viewer, portal);
        });
        return element;
    }

    private MinecraftElement apertureShapeElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftElement element = new MinecraftElement("aperture-shape");
        ShapeCursor cursor = new ShapeCursor();
        element.onLeftClick(clicked -> changeApertureShape(window, viewer, portal, element, cursor,
            ApertureShapePresets.next(cursor.refused == null ? portal.getApertureShape() : cursor.refused)));
        element.onRightClick(clicked -> changeApertureShape(window, viewer, portal, element, cursor,
            ApertureShapePresets.rotated(portal.getApertureShape())));
        element.onShiftRightClick(clicked -> changeApertureShape(window, viewer, portal, element, cursor, ShapeDescriptor.FULL));
        applyApertureShapeElement(viewer, element, portal, cursor);
        return element;
    }

    private void changeApertureShape(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal, MinecraftElement element,
                                     ShapeCursor cursor, ShapeDescriptor requested) {
        if (portal.acceptsApertureShape(requested)) {
            cursor.refused = null;
            if (menus.update(viewer, portal, target -> target.setApertureShape(requested))) {
                MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED, MinecraftPortalText.arguments(
                    "label", MinecraftPortalText.localized(viewer, WormholesMessages.PORTAL_LABEL_APERTURE_SHAPE),
                    "value", requested.format()));
            }
        } else {
            cursor.refused = requested;
            MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_APERTURE_SHAPE_TOO_SMALL,
                MinecraftPortalText.arguments("shape", requested.format()));
        }
        applyApertureShapeElement(viewer, element, portal, cursor);
        window.updateInventory();
    }

    private void applyApertureShapeElement(ServerPlayer viewer, MinecraftElement element, MinecraftPortal portal, ShapeCursor cursor) {
        ShapeDescriptor shape = portal.getApertureShape();
        MinecraftLegacyText.apply(viewer, element, WormholesMessages.PORTAL_MENU_APERTURE_SHAPE, MinecraftPortalText.arguments(
            "shape", shape.format(),
            "cells", Integer.valueOf(portal.getGeometry().getBlockPositions().size())));
        if (cursor.refused != null) {
            element.addLore(MinecraftLegacyText.text(viewer, WormholesMessages.PORTAL_APERTURE_SHAPE_TOO_SMALL,
                MinecraftPortalText.arguments("shape", cursor.refused.format())));
        }
        element.setMaterial(shape.isFull() ? Items.GLASS_PANE : Items.STAINED_GLASS_PANE.cyan());
        element.setEnchanted(!shape.isFull());
    }

    private MinecraftElement publicLookLabelElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftElement element = new MinecraftElement("public-look-label");
        element.onLeftClick(clicked -> {
            boolean enabled = !portal.isPublicLookLabel();
            menus.update(viewer, portal, target -> target.setPublicLookLabel(enabled));
            applyPublicLookLabelElement(viewer, element, portal);
            window.updateInventory();
            MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED, MinecraftPortalText.arguments(
                "label", MinecraftPortalText.localized(viewer, WormholesMessages.PORTAL_LABEL_PUBLIC_LOOK_LABEL),
                "value", MinecraftPortalText.onOffLabel(viewer, portal.isPublicLookLabel())));
        });
        applyPublicLookLabelElement(viewer, element, portal);
        return element;
    }

    private void applyPublicLookLabelElement(ServerPlayer viewer, MinecraftElement element, MinecraftPortal portal) {
        boolean enabled = portal.isPublicLookLabel();
        MinecraftLegacyText.apply(viewer, element, WormholesMessages.PORTAL_MENU_PUBLIC_LOOK_LABEL,
            MinecraftPortalText.arguments("state", MinecraftPortalText.onOffLabel(viewer, enabled)));
        element.setMaterial(Items.NAME_TAG);
        element.setEnchanted(enabled);
    }

    private MinecraftElement settingsSyncElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftElement element = new MinecraftElement("settings-sync");
        element.onLeftClick(clicked -> {
            boolean previous = portal.isSettingsSyncEnabled();
            menus.update(viewer, portal, target -> target.setSettingsSyncEnabled(!previous));
            applySettingsSyncElement(viewer, element, portal);
            window.updateInventory();
            MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_SETTINGS_SYNC_CHANGED,
                MinecraftPortalText.arguments("state", MinecraftPortalText.onOffLabel(viewer, portal.isSettingsSyncEnabled())));
        });
        applySettingsSyncElement(viewer, element, portal);
        return element;
    }

    private void applySettingsSyncElement(ServerPlayer viewer, MinecraftElement element, MinecraftPortal portal) {
        boolean enabled = portal.isSettingsSyncEnabled();
        MinecraftLegacyText.apply(viewer, element, WormholesMessages.PORTAL_MENU_SETTINGS_SYNC,
            MinecraftPortalText.arguments("state", MinecraftPortalText.onOffLabel(viewer, enabled)));
        element.setEnchanted(enabled);
        element.setMaterial(enabled ? Items.SOUL_LANTERN : Items.LANTERN);
    }

    private MinecraftElement permissionElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftElement element = new MinecraftElement("permission-mode");
        element.onLeftClick(clicked -> {
            PortalPermissionMode previous = portal.getPermissionMode();
            menus.update(viewer, portal, target -> target.setPermissionMode(previous.next()));
            applyPermissionElement(viewer, element, portal);
            window.updateInventory();
            if (previous != portal.getPermissionMode()) {
                MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_ACCESS_CHANGED,
                    MinecraftPortalText.arguments("mode", MinecraftPortalText.permissionModeLabel(viewer, portal.getPermissionMode())));
            }
        });
        applyPermissionElement(viewer, element, portal);
        return element;
    }

    private void applyPermissionElement(ServerPlayer viewer, MinecraftElement element, MinecraftPortal portal) {
        PortalPermissionMode mode = portal.getPermissionMode();
        MinecraftLegacyText.apply(viewer, element, WormholesMessages.PORTAL_MENU_PERMISSION, MinecraftPortalText.arguments(
            "mode", MinecraftPortalText.permissionModeLabel(viewer, mode),
            "description", MinecraftPortalText.permissionModeDescription(viewer, mode),
            "node", "wormholes.portal." + PortalPermissionKey.sanitize(portal.getName())));
        element.setEnchanted(mode == PortalPermissionMode.WHITELIST);
        element.setMaterial(mode == PortalPermissionMode.WHITELIST ? Items.GOLDEN_HELMET : Items.IRON_HELMET);
    }

    private record NumberControl(String id, TextKey label, TextKey description, Item material, ToIntFunction<MinecraftPortal> getter,
                                 ObjIntConsumer<MinecraftPortal> setter, int step, int largeStep) {
    }

    private static final class ShapeCursor {
        private ShapeDescriptor refused;
    }
}
