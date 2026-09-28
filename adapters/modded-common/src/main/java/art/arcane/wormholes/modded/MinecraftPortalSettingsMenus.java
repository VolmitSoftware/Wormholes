package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.FidelityMessages;
import art.arcane.wormholes.localization.TransitMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.BlackoutColor;
import art.arcane.wormholes.portal.NetworkViewQuality;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.wormholes.render.acoustics.AcousticsProfile;
import art.arcane.wormholes.render.atmosphere.AtmosphereMode;
import art.arcane.wormholes.render.lod.LodProfile;
import art.arcane.wormholes.transit.MomentumPolicy;
import art.arcane.wormholes.transit.OrientationPolicy;
import art.arcane.wormholes.transit.TransitionProfile;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public final class MinecraftPortalSettingsMenus implements AutoCloseable {
    private static final long PROMPT_NANOS = 60_000_000_000L;
    private final WormholesModRuntime runtime;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Prompt> prompts = new HashMap<>();

    public MinecraftPortalSettingsMenus(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime);
    }

    public void open(ServerPlayer viewer, UUID portalId, Page page) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        if (portal != null && runtime.portals().canManage(viewer, portal)) {
            Session session = new Session(viewer, portal);
            session.page = page;
            prompts.remove(viewer.getUUID());
            sessions.put(viewer.getUUID(), session);
            session.open();
        }
    }

    public void tick() {
        long now = System.nanoTime();
        prompts.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        Iterator<Session> iterator = sessions.values().iterator();
        while (iterator.hasNext()) {
            Session session = iterator.next();
            if (!session.valid()) {
                iterator.remove();
                prompts.remove(session.viewer.getUUID());
                if (session.viewer.containerMenu == session.menu) {
                    session.viewer.closeContainer();
                }
            } else if (session.viewer.containerMenu != session.menu && !prompts.containsKey(session.viewer.getUUID())) {
                iterator.remove();
            }
        }
    }

    public boolean chat(ServerPlayer viewer, String input) {
        Prompt prompt = prompts.remove(viewer.getUUID());
        if (prompt == null) {
            return false;
        }
        Session session = prompt.session();
        if (!session.valid() || viewer.containerMenu != viewer.inventoryMenu || prompt.expiresAt() <= System.nanoTime()) {
            return true;
        }
        String answer = input.trim();
        String cancel = session.text(WormholesMessages.PORTAL_INPUT_CANCEL);
        if (!answer.equalsIgnoreCase(cancel) && !answer.equalsIgnoreCase("cancel")) {
            try {
                session.apply(prompt.input(), answer);
            } catch (IllegalArgumentException error) {
                viewer.sendSystemMessage(MinecraftMenuText.text(viewer, WormholesMessages.PORTAL_RTP_SETTING_REJECTED,
                    Map.of("reason", Objects.toString(error.getMessage(), "Invalid setting"))));
            }
        }
        if (session.valid()) {
            session.open();
        }
        return true;
    }

    public void disconnected(ServerPlayer viewer) {
        sessions.remove(viewer.getUUID());
        prompts.remove(viewer.getUUID());
    }

    @Override
    public void close() {
        for (Session session : sessions.values()) {
            if (session.viewer.containerMenu == session.menu) {
                session.viewer.closeContainer();
            }
        }
        sessions.clear();
        prompts.clear();
    }

    public enum Page { NETWORK, COSMETICS, BLACKOUT, AMBIENT, SKIN, FIDELITY, TRANSIT }
    private enum Input { FALLBACK, MOMENTUM, THRESHOLD, ARRIVAL, MASK }
    private record Prompt(Session session, Input input, long expiresAt) { }

    private final class Session {
        private final ServerPlayer viewer;
        private final MinecraftPortal portal;
        private Page page;
        private MinecraftInventoryMenu menu;

        private Session(ServerPlayer viewer, MinecraftPortal portal) {
            this.viewer = viewer;
            this.portal = portal;
        }

        private boolean valid() {
            return runtime.running() && !viewer.hasDisconnected() && sessions.get(viewer.getUUID()) == this
                && runtime.portals().get(portal.getId()) == portal && runtime.portals().canManage(viewer, portal);
        }

        private void open() {
            MinecraftInventoryMenu.open(viewer, Component.literal(portal.getName()), new MinecraftInventoryMenu.Actions(this::valid, this::render, this::click));
        }

        private void render(MinecraftInventoryMenu menu) {
            this.menu = menu;
            switch (page) {
                case NETWORK -> network();
                case COSMETICS -> cosmetics();
                case BLACKOUT -> blackout();
                case AMBIENT -> ambient();
                case SKIN -> skin();
                case FIDELITY -> fidelity();
                case TRANSIT -> transit();
            }
            put(49, Items.ARROW, WormholesMessages.PORTAL_MENU_BACK_SETTINGS, Map.of());
        }

        private void network() {
            NetworkViewQuality quality = portal.getNetworkViewQuality();
            put(4, Items.COMPARATOR, WormholesMessages.PORTAL_MENU_STREAM_QUALITY,
                Map.of("quality", text(quality.labelKey()), "depth", portal.getNetworkViewDepth(), "entities", portal.getNetworkViewEntityIntervalTicks(),
                    "refresh", portal.getNetworkViewHeartbeatTicks(), "grace", portal.getNetworkViewUnsubscribeGraceSeconds()));
            number(10, Items.SPYGLASS, WormholesMessages.PORTAL_NETWORK_LABEL_CAPTURE_RADIUS,
                WormholesMessages.PORTAL_NETWORK_DESCRIPTION_CAPTURE_RADIUS, portal.getNetworkViewDepth(), 4, 16);
            number(12, Items.CLOCK, WormholesMessages.PORTAL_NETWORK_LABEL_FULL_REFRESH,
                WormholesMessages.PORTAL_NETWORK_DESCRIPTION_FULL_REFRESH, portal.getNetworkViewHeartbeatTicks(), 10, 60);
            number(14, Items.ENDER_EYE, WormholesMessages.PORTAL_NETWORK_LABEL_ENTITY_UPDATE,
                WormholesMessages.PORTAL_NETWORK_DESCRIPTION_ENTITY_UPDATE, portal.getNetworkViewEntityIntervalTicks(), 2, 20);
            number(16, Items.REDSTONE, WormholesMessages.PORTAL_NETWORK_LABEL_VIEW_GRACE,
                WormholesMessages.PORTAL_NETWORK_DESCRIPTION_VIEW_GRACE, portal.getNetworkViewUnsubscribeGraceSeconds(), 5, 30);
            put(31, Items.GLASS, WormholesMessages.PORTAL_MENU_FALLBACK_BLOCK, Map.of("block", portal.getNetworkViewFallbackBlock()));
        }

        private void cosmetics() {
            put(11, Items.CONCRETE.black(), WormholesMessages.PORTAL_MENU_BLACKOUT,
                Map.of("state", state(portal.isBlackoutBackground()), "color", portal.getBlackoutColor().displayName()));
            put(13, Items.GLOWSTONE_DUST, WormholesMessages.PORTAL_MENU_AMBIENT_PARTICLES,
                Map.of("style", ambientStyle(), "color", color()));
            put(15, Items.GLASS, WormholesMessages.PORTAL_MENU_SURFACE_SKIN, Map.of("skin", skinLabel()));
        }

        private void blackout() {
            put(4, Items.CONCRETE.black(), WormholesMessages.PORTAL_MENU_BLACKOUT_COLOR_PLACARD,
                Map.of("color", portal.getBlackoutColor().displayName()));
            BlackoutColor[] colors = BlackoutColor.values();
            for (int index = 0; index < colors.length; index++) {
                BlackoutColor color = colors[index];
                Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(color.blockState()));
                put(10 + index, item, WormholesMessages.PORTAL_MENU_BLACKOUT_COLOR_OPTION, Map.of("color", color.displayName()));
                selected(10 + index, color == portal.getBlackoutColor());
            }
        }

        private void ambient() {
            put(4, Items.GLOWSTONE_DUST, WormholesMessages.PORTAL_MENU_AMBIENT_COLOR_PLACARD, Map.of("color", color()));
            channel(10, Items.DYE.red(), WormholesMessages.PORTAL_LABEL_AMBIENT_RED, (portal.getAmbientColor() >> 16) & 255);
            channel(13, Items.DYE.green(), WormholesMessages.PORTAL_LABEL_AMBIENT_GREEN, (portal.getAmbientColor() >> 8) & 255);
            channel(16, Items.DYE.blue(), WormholesMessages.PORTAL_LABEL_AMBIENT_BLUE, portal.getAmbientColor() & 255);
            DyeColor[] colors = DyeColor.values();
            for (int index = 0; index < colors.length; index++) {
                DyeColor dye = colors[index];
                Item item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(dye.getName() + "_dye"));
                put(27 + index, item, WormholesMessages.PORTAL_MENU_AMBIENT_COLOR_OPTION, Map.of("color", dye.getName()));
                selected(27 + index, dye.getTextureDiffuseColor() == portal.getAmbientColor());
            }
        }

        private void skin() {
            put(4, Items.GLASS, WormholesMessages.PORTAL_MENU_SURFACE_SKIN_PLACARD, Map.of("skin", skinLabel()));
            put(12, Items.GLASS, WormholesMessages.PORTAL_MENU_SURFACE_SKIN_GLASS, Map.of());
            put(14, Items.BARRIER, WormholesMessages.PORTAL_MENU_SURFACE_SKIN_CLEAR, Map.of());
            selected(12, portal.getSurfaceSkin().equals("minecraft:glass"));
            selected(14, portal.getSurfaceSkin().isEmpty());
        }

        private void fidelity() {
            put(4, Items.COMPARATOR, FidelityMessages.MENU_PLACARD, Map.of());
            fidelityEntry(10, Items.GLASS, FidelityMessages.MENU_ATMOSPHERE, "fidelity.atmosphere", "mode");
            fidelityEntry(12, Items.NOTE_BLOCK, FidelityMessages.MENU_ACOUSTICS, "fidelity.acoustics", "mode");
            fidelityEntry(14, Items.SPYGLASS, FidelityMessages.MENU_LOD, "fidelity.lod", "mode");
            fidelityEntry(16, Items.OAK_SIGN, FidelityMessages.MENU_BLOCK_ENTITIES, "fidelity.block_entities", "state");
        }

        private void fidelityEntry(int slot, Item item, TextKey label, String key, String placeholder) {
            Object value = portal.setting(key);
            List<Component> hint = MinecraftMenuText.lines(runtime.localization().snapshot(viewer), FidelityMessages.MENU_HINT, Map.of());
            ItemStack stack = new ItemStack(item);
            stack.set(DataComponents.CUSTOM_NAME, MinecraftMenuText.text(viewer, label,
                Map.of(placeholder, value == null ? text(FidelityMessages.MENU_DEFAULT) : value)));
            stack.set(DataComponents.LORE, new ItemLore(hint));
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, value != null);
            menu.set(slot, stack);
        }

        private void transit() {
            put(4, Items.ENDER_PEARL, TransitMessages.MENU_ENTRY, Map.of());
            MomentumPolicy momentum = momentum();
            put(10, Items.SLIME_BALL, TransitMessages.MENU_MOMENTUM,
                Map.of("mode", momentum.mode().label(), "value", momentum.factor()));
            put(12, Items.COMPASS, TransitMessages.MENU_ORIENTATION, Map.of("mode", orientation().label()));
            put(14, Items.PHANTOM_MEMBRANE, TransitMessages.MENU_MEMBRANE, Map.of("state", state(flag("transit.membrane"))));
            put(16, Items.SLIME_BLOCK, TransitMessages.MENU_BOUNCE, Map.of("state", state(flag("transit.bounce"))));
            TransitionProfile profile = profile();
            String arrival = profile.arrivalSound().isEmpty() ? text(FidelityMessages.MENU_DEFAULT) : profile.arrivalSound();
            put(31, Items.NOTE_BLOCK, TransitMessages.MENU_PROFILE,
                Map.of("mode", profile.thresholdEffect().isEmpty() ? text(FidelityMessages.MENU_DEFAULT) : profile.thresholdEffect(),
                    "state", arrival + (profile.overridesMask() ? " / " + profile.maskOverrideTicks() + "t" : "")));
        }

        private void click(MinecraftInventoryMenu.Click click) {
            if (!valid() || click.menu() != menu || viewer.containerMenu != menu || click.middle()) {
                return;
            }
            if (click.slot() == 49) {
                if (page == Page.BLACKOUT || page == Page.AMBIENT || page == Page.SKIN) {
                    navigate(Page.COSMETICS);
                } else {
                    runtime.menus().open(viewer, portal.getId());
                }
                return;
            }
            switch (page) {
                case NETWORK -> networkClick(click);
                case COSMETICS -> cosmeticsClick(click);
                case BLACKOUT -> {
                    int index = click.slot() - 10;
                    if (index >= 0 && index < BlackoutColor.values().length && !click.right()) {
                        change(target -> target.setBlackoutColor(BlackoutColor.values()[index]));
                    }
                }
                case AMBIENT -> ambientClick(click);
                case SKIN -> {
                    if (click.slot() == 12) { change(target -> target.setSurfaceSkin("minecraft:glass")); }
                    if (click.slot() == 14) { change(target -> target.setSurfaceSkin("")); }
                }
                case FIDELITY -> fidelityClick(click);
                case TRANSIT -> transitClick(click);
            }
        }

        private void networkClick(MinecraftInventoryMenu.Click click) {
            int sign = click.right() ? -1 : 1;
            switch (click.slot()) {
                case 4 -> { if (!click.right()) { change(target -> target.setNetworkViewQuality(target.getNetworkViewQuality().next())); } }
                case 10 -> change(target -> target.setNetworkViewDepth(target.getNetworkViewDepth() + sign * (click.shift() ? 16 : 4)));
                case 12 -> change(target -> target.setNetworkViewHeartbeatTicks(target.getNetworkViewHeartbeatTicks() + sign * (click.shift() ? 60 : 10)));
                case 14 -> change(target -> target.setNetworkViewEntityIntervalTicks(target.getNetworkViewEntityIntervalTicks() + sign * (click.shift() ? 20 : 2)));
                case 16 -> change(target -> target.setNetworkViewUnsubscribeGraceSeconds(target.getNetworkViewUnsubscribeGraceSeconds() + sign * (click.shift() ? 30 : 5)));
                case 31 -> {
                    if (click.right()) { change(target -> target.setNetworkViewFallbackBlock("minecraft:air")); }
                    else { prompt(Input.FALLBACK); }
                }
                default -> { }
            }
        }

        private void cosmeticsClick(MinecraftInventoryMenu.Click click) {
            switch (click.slot()) {
                case 11 -> {
                    if (click.right()) { navigate(Page.BLACKOUT); }
                    else { change(target -> target.setBlackoutBackground(!target.isBlackoutBackground())); }
                }
                case 13 -> {
                    if (click.right()) { navigate(Page.AMBIENT); }
                    else { change(target -> target.setAmbientStyle(target.getAmbientStyle().next())); }
                }
                case 15 -> {
                    if (click.right()) { navigate(Page.SKIN); }
                    else { change(target -> target.setSurfaceSkin("")); }
                }
                default -> { }
            }
        }

        private void ambientClick(MinecraftInventoryMenu.Click click) {
            int shift = switch (click.slot()) { case 10 -> 16; case 13 -> 8; case 16 -> 0; default -> -1; };
            if (shift >= 0) {
                int value = (portal.getAmbientColor() >> shift) & 255;
                int changed = Math.clamp(value + (click.right() ? -1 : 1) * (click.shift() ? 16 : 1), 0, 255);
                change(target -> target.setAmbientColor((target.getAmbientColor() & ~(255 << shift)) | (changed << shift)));
                return;
            }
            int index = click.slot() - 27;
            if (index >= 0 && index < DyeColor.values().length) {
                change(target -> target.setAmbientColor(DyeColor.values()[index].getTextureDiffuseColor()));
            }
        }

        private void fidelityClick(MinecraftInventoryMenu.Click click) {
            if (click.right()) {
                return;
            }
            switch (click.slot()) {
                case 10 -> change(target -> target.setAtmosphereMode(click.shift() ? null
                    : AtmosphereMode.parse(string("fidelity.atmosphere"), FidelitySettings.atmosphereModeDefault).next()));
                case 12 -> change(target -> target.setAcousticsProfile(click.shift() ? null
                    : AcousticsProfile.parse(string("fidelity.acoustics"), FidelitySettings.acousticsProfileDefault).next()));
                case 14 -> change(target -> target.setLodProfile(click.shift() ? null : LodProfile.parse(string("fidelity.lod"), LodProfile.BALANCED).next()));
                case 16 -> change(target -> target.setBlockEntities(click.shift() ? null
                    : !(portal.setting("fidelity.block_entities") instanceof Boolean enabled ? enabled : FidelitySettings.blockEntities)));
                default -> { }
            }
        }

        private void transitClick(MinecraftInventoryMenu.Click click) {
            switch (click.slot()) {
                case 10 -> {
                    if (click.right()) { prompt(Input.MOMENTUM); }
                    else { change(target -> target.setMomentum(momentum().withMode(momentum().mode().next()))); }
                }
                case 12 -> change(target -> target.setOrientation(orientation().next()));
                case 14 -> change(target -> target.setMembrane(!flag("transit.membrane")));
                case 16 -> change(target -> target.setBounce(!flag("transit.bounce")));
                case 31 -> prompt(click.right() ? Input.ARRIVAL : click.shift() ? Input.MASK : Input.THRESHOLD);
                default -> { }
            }
        }

        private void prompt(Input input) {
            viewer.closeContainer();
            prompts.put(viewer.getUUID(), new Prompt(this, input, System.nanoTime() + PROMPT_NANOS));
            TextKey key = input == Input.FALLBACK ? WormholesMessages.PORTAL_PROMPT_BLOCK_STATE : TransitMessages.MENU_PROMPT;
            viewer.sendSystemMessage(MinecraftMenuText.text(viewer, key, Map.of("cancel", text(WormholesMessages.PORTAL_INPUT_CANCEL))));
        }

        private void apply(Input input, String answer) {
            switch (input) {
                case FALLBACK -> runtime.portals().update(viewer, portal.getId(), target -> target.setNetworkViewFallbackBlock(answer));
                case MOMENTUM -> {
                    double factor = Double.parseDouble(answer);
                    if (!Double.isFinite(factor) || factor < 0 || factor > 10) { throw new IllegalArgumentException("Factor must be between 0 and 10"); }
                    runtime.portals().update(viewer, portal.getId(), target -> target.setMomentum(momentum().withFactor(factor)));
                }
                case MASK -> {
                    int ticks = answer.isEmpty() ? -1 : Integer.parseInt(answer);
                    if (ticks < -1 || ticks > 200) { throw new IllegalArgumentException("Mask ticks must be between -1 and 200"); }
                    runtime.portals().update(viewer, portal.getId(), target -> target.setTransitionProfile(profile().withMaskOverrideTicks(ticks)));
                }
                case THRESHOLD -> runtime.portals().update(viewer, portal.getId(), target -> target.setTransitionProfile(profile().withThresholdEffect(effect(answer))));
                case ARRIVAL -> runtime.portals().update(viewer, portal.getId(), target -> target.setTransitionProfile(profile().withArrivalSound(effect(answer))));
            }
        }

        private String effect(String text) {
            String normalized = text.toLowerCase(Locale.ROOT);
            return normalized.isEmpty() || normalized.equals("-") || normalized.equals("none") || normalized.equals("default") ? "" : normalized;
        }

        private void navigate(Page target) {
            page = target;
            menu.refresh();
        }

        private void change(Consumer<MinecraftPortal> mutation) {
            if (runtime.portals().update(viewer, portal.getId(), mutation)) {
                menu.refresh();
            }
        }

        private MomentumPolicy momentum() {
            MomentumPolicy value = MomentumPolicy.decode(string("transit.momentum"));
            return value == null ? MomentumPolicy.of(MomentumPolicy.Mode.parse(runtime.configuration().settings().getTransit().momentumDefault,
                MomentumPolicy.Mode.PRESERVE)) : value;
        }

        private OrientationPolicy orientation() {
            return OrientationPolicy.parse(string("transit.orientation"), OrientationPolicy.parse(
                runtime.configuration().settings().getTransit().orientationDefault, OrientationPolicy.FRAME));
        }

        private TransitionProfile profile() { return TransitionProfile.decode(string("transit.profile")); }
        private boolean flag(String key) { return Boolean.TRUE.equals(portal.setting(key)); }
        private String string(String key) { return portal.setting(key) instanceof String value ? value : ""; }
        private String text(TextKey key) { return MinecraftMenuText.text(viewer, key, Map.of()).getString(); }
        private String state(boolean enabled) { return text(enabled ? WormholesMessages.LABEL_ON : WormholesMessages.LABEL_OFF); }
        private String color() { return String.format(Locale.ROOT, "#%06X", portal.getAmbientColor()); }
        private String skinLabel() { return portal.getSurfaceSkin().isEmpty() ? text(WormholesMessages.LABEL_NONE) : portal.getSurfaceSkin(); }

        private String ambientStyle() {
            return text(switch (portal.getAmbientStyle()) {
                case SPARKS -> WormholesMessages.PORTAL_LABEL_AMBIENT_STYLE_SPARKS;
                case OUTLINE -> WormholesMessages.PORTAL_LABEL_AMBIENT_STYLE_OUTLINE;
                case CORNERS -> WormholesMessages.PORTAL_LABEL_AMBIENT_STYLE_CORNERS;
                case OFF -> WormholesMessages.PORTAL_LABEL_AMBIENT_STYLE_OFF;
            });
        }

        private void number(int slot, Item material, TextKey label, TextKey description, int value, int step, int largeStep) {
            put(slot, material, WormholesMessages.PORTAL_MENU_NETWORK_NUMBER,
                Map.of("label", text(label), "description", text(description), "value", value, "step", step, "large_step", largeStep));
        }

        private void channel(int slot, Item material, TextKey label, int value) {
            put(slot, material, WormholesMessages.PORTAL_MENU_AMBIENT_CHANNEL,
                Map.of("label", text(label), "value", value, "step", 1, "large_step", 16));
        }

        private void put(int slot, Item material, LinesKey key, Map<String, ?> arguments) {
            menu.set(slot, MinecraftMenuText.item(viewer, material, key, arguments));
        }

        private void selected(int slot, boolean value) {
            menu.getContainer().getItem(slot).set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, value);
        }
    }
}
