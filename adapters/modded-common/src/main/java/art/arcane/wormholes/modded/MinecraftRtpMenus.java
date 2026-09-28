package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.rtp.RtpPortalEditor;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel;
import art.arcane.wormholes.portal.rtp.RtpService;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.wormholes.portal.rtp.RtpWorld;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class MinecraftRtpMenus implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private final WormholesModRuntime runtime;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private int ticks;

    public MinecraftRtpMenus(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime);
    }

    public void open(ServerPlayer viewer, UUID portalId) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        if (portal == null || portal.getType() != PortalType.RTP || !runtime.portals().canManage(viewer, portal)) {
            return;
        }
        Session session = new Session(viewer, portal);
        sessions.put(viewer.getUUID(), session);
        MinecraftInventoryMenu.open(viewer, runtime.localization().text(viewer, WormholesMessages.PORTAL_RTP_EDITOR_TITLE,
            Map.of("portal", portal.getName())), new MinecraftInventoryMenu.Actions(session::valid, session::render, session::click));
    }

    public void tick() {
        Iterator<Session> iterator = sessions.values().iterator();
        boolean refresh = ++ticks % 20 == 0;
        while (iterator.hasNext()) {
            Session session = iterator.next();
            if (session.viewer.containerMenu != session.menu) {
                iterator.remove();
            } else if (!session.valid()) {
                iterator.remove();
                session.viewer.closeContainer();
            } else if (refresh) {
                session.menu.refresh();
            }
        }
    }

    public void disconnected(ServerPlayer viewer) {
        sessions.remove(viewer.getUUID());
    }

    @Override
    public void close() {
        for (Session session : sessions.values()) {
            if (session.viewer.containerMenu == session.menu) {
                session.viewer.closeContainer();
            }
        }
        sessions.clear();
    }

    private ServerLevel level(String key) {
        for (ServerLevel level : runtime.server().getAllLevels()) {
            if (level.dimension().identifier().toString().equals(key)) {
                return level;
            }
        }
        return null;
    }

    private static RtpWorld world(ServerLevel level) {
        if (level == null) {
            return null;
        }
        String key = level.dimension().identifier().toString();
        return new RtpWorld(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)), key,
            level.getMinY(), level.getMaxY(), level.getSeaLevel());
    }

    private static Map<String, Object> arguments(MessageArgs arguments) {
        Map<String, Object> values = new HashMap<>();
        arguments.arguments().forEach((name, argument) -> values.put(name, argument.value()));
        return values;
    }

    private final class Session implements RtpPortalEditor.Host, RtpPortalEditor.View {
        private final ServerPlayer viewer;
        private final MinecraftPortal portal;
        private final RtpPortalEditor editor;
        private final Map<Integer, RtpPortalEditor.Entry> entries = new HashMap<>();
        private MinecraftInventoryMenu menu;
        private RtpSettings renderedSettings;
        private long revision;

        private Session(ServerPlayer viewer, MinecraftPortal portal) {
            this.viewer = viewer;
            this.portal = portal;
            editor = new RtpPortalEditor(this);
        }

        private boolean valid() {
            return runtime.running() && !viewer.hasDisconnected() && sessions.get(viewer.getUUID()) == this
                && runtime.portals().get(portal.getId()) == portal && portal.getType() == PortalType.RTP
                && runtime.portals().canManage(viewer, portal);
        }

        private void render(MinecraftInventoryMenu menu) {
            this.menu = menu;
            editor.populate(this, viewer.getUUID());
        }

        private void click(MinecraftInventoryMenu.Click click) {
            if (!valid() || click.menu() != menu || viewer.containerMenu != menu || click.right() || click.shift() || click.middle()) {
                return;
            }
            RtpPortalEditor.Entry entry = entries.get(click.slot());
            if (entry != null) {
                entry.activate();
            }
        }

        @Override
        public String text(TextKey key, MessageArgs arguments) {
            return runtime.localization().text(viewer, key, arguments(arguments)).getString();
        }

        @Override
        public RtpPortalEditorModel.EditorSnapshot snapshot(UUID viewerId) {
            if (!valid() || !viewer.getUUID().equals(viewerId)) {
                throw new IllegalStateException("RTP editor session is stale");
            }
            RtpSettings settings = runtime.rtp().settings(portal);
            if (!settings.equals(renderedSettings)) {
                renderedSettings = settings;
                revision++;
            }
            List<RtpPortalEditorModel.WorldOption> worlds = new ArrayList<>();
            for (ServerLevel level : runtime.server().getAllLevels()) {
                if (!level.dimension().identifier().toString().equals("wormholes:pockets")) {
                    worlds.add(RtpPortalEditorModel.WorldOption.from(world(level)));
                }
            }
            boolean available = level(settings.getTargetWorldKey()) != null;
            RtpService.Snapshot state = runtime.rtp().snapshot(portal.getId()).orElse(null);
            RtpPortalEditorModel.StatusSnapshot status = state == null
                ? new RtpPortalEditorModel.StatusSnapshot(available ? RtpPortalEditorModel.StatusState.IDLE
                    : RtpPortalEditorModel.StatusState.TARGET_WORLD_UNAVAILABLE, available, true, false, false, 0, 0, 0, 0, 0, 0)
                : RtpPortalEditorModel.StatusSnapshot.from(state.runtime(), new RtpPortalEditorModel.StatusContext(
                    available, state.integrationAvailable(), System.currentTimeMillis(), state.nextSearchAllowedAtMillis()));
            GeometryVector center = portal.getGeometry().getApertureCenter();
            return new RtpPortalEditorModel.EditorSnapshot(revision, portal.getName(), RtpPortalEditorModel.SettingsSnapshot.from(settings),
                status, worlds, center.x(), center.z());
        }

        @Override
        public List<RtpPortalEditorModel.BiomeOption> biomeOptions(UUID viewerId) {
            if (!valid() || !viewer.getUUID().equals(viewerId)) {
                return List.of();
            }
            ServerLevel target = level(runtime.rtp().settings(portal).getTargetWorldKey());
            if (target == null) {
                return List.of();
            }
            List<RtpPortalEditorModel.BiomeOption> options = new ArrayList<>();
            for (Identifier id : target.registryAccess().lookupOrThrow(Registries.BIOME).keySet()) {
                options.add(new RtpPortalEditorModel.BiomeOption(id.toString(), id.toString(), false));
            }
            options.sort(Comparator.comparing(RtpPortalEditorModel.BiomeOption::key));
            return List.copyOf(options);
        }

        private boolean current(UUID viewerId, long expectedRevision) {
            if (!valid() || !viewer.getUUID().equals(viewerId)) {
                return false;
            }
            if (revision != expectedRevision || !runtime.rtp().settings(portal).equals(renderedSettings)) {
                notice(WormholesMessages.PORTAL_RTP_EDITOR_REFRESHED);
                menu.refresh();
                return false;
            }
            return true;
        }

        @Override
        public void mutate(UUID viewerId, long expectedRevision, RtpPortalEditorModel.Mutation mutation) {
            if (!current(viewerId, expectedRevision)) {
                return;
            }
            try {
                RtpSettings changed = RtpPortalEditorModel.applyMutation(renderedSettings, mutation,
                    world(level(portal.getWorldKey())), key -> world(level(key)));
                runtime.portals().update(viewer, portal.getId(), target -> target.setRtpSettings(changed));
                notice(WormholesMessages.PORTAL_RTP_APPLIED);
                menu.refresh();
            } catch (IllegalArgumentException failure) {
                viewer.sendSystemMessage(runtime.localization().text(viewer, WormholesMessages.PORTAL_RTP_SETTING_REJECTED,
                    Map.of("reason", Objects.toString(failure.getMessage(), "Invalid setting"))));
                menu.refresh();
            }
        }

        @Override
        public void reset(UUID viewerId, long expectedRevision) {
            if (!current(viewerId, expectedRevision)) {
                return;
            }
            RtpSettings defaults = RtpSettings.defaults(world(level(portal.getWorldKey())));
            runtime.portals().update(viewer, portal.getId(), target -> target.setRtpSettings(defaults));
            notice(WormholesMessages.PORTAL_RTP_RESET_DEFAULTS);
            menu.refresh();
        }

        @Override
        public void manual(UUID viewerId, long expectedRevision, RtpPortalEditorModel.ManualAction action) {
            if (!current(viewerId, expectedRevision)) {
                return;
            }
            if (action == RtpPortalEditorModel.ManualAction.REROLL) {
                runtime.rtp().reroll(portal.getId()).whenComplete((accepted, error) -> runtime.server().execute(() -> {
                    complete(error, error != null ? WormholesMessages.PORTAL_RTP_REROLL_FAILED
                        : Boolean.TRUE.equals(accepted) ? WormholesMessages.PORTAL_RTP_REROLL_PREPARING : WormholesMessages.PORTAL_RTP_REROLL_UNAVAILABLE);
                }));
            } else {
                runtime.rtp().rebuild(portal.getId()).whenComplete((destinations, error) -> runtime.server().execute(() ->
                    complete(error, error == null ? WormholesMessages.PORTAL_RTP_POOL_REBUILDING : WormholesMessages.PORTAL_RTP_POOL_FAILED)));
            }
        }

        private void complete(Throwable error, TextKey key) {
            if (error != null) {
                LOGGER.error("Could not update random destinations for portal {}", portal.getId(), error);
            }
            if (valid() && viewer.containerMenu == menu) {
                notice(key);
                menu.refresh();
            }
        }

        private void notice(TextKey key) {
            viewer.sendSystemMessage(runtime.localization().text(viewer, key, Map.of()));
        }

        @Override
        public void back(UUID viewerId) {
            if (viewer.getUUID().equals(viewerId)) {
                runtime.menus().open(viewer, portal.getId());
            }
        }

        @Override
        public void configure(String title) {
        }

        @Override
        public void clearElements() {
            entries.clear();
            for (int slot = 0; slot < 54; slot++) {
                ItemStack background = new ItemStack(Items.STAINED_GLASS_PANE.black());
                background.set(DataComponents.CUSTOM_NAME, Component.empty());
                menu.set(slot, background);
            }
        }

        @Override
        public void setElement(int position, int row, RtpPortalEditor.Entry entry) {
            int slot = row * 9 + position + 4;
            entries.put(slot, entry);
            Item material = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(entry.icon().name().toLowerCase(Locale.ROOT)));
            ItemStack item = MinecraftMenuText.item(viewer, material, entry.key(), arguments(entry.arguments()));
            item.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, entry.selected());
            if (!entry.lore().isEmpty()) {
                List<Component> lore = new ArrayList<>(item.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines());
                for (String line : entry.lore()) {
                    lore.add(Component.literal(line));
                }
                item.set(DataComponents.LORE, new ItemLore(lore));
            }
            menu.set(slot, item);
        }

        @Override
        public void updateInventory() {
            menu.broadcastFullState();
        }

        @Override
        public void close() {
            viewer.closeContainer();
            sessions.remove(viewer.getUUID(), this);
        }
    }
}
