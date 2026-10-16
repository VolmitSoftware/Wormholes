package art.arcane.wormholes.menu.nexus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.config.toml.NexusConfig;
import art.arcane.wormholes.localization.MenuMessages;
import art.arcane.wormholes.localization.NexusMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.ButtonElement;
import art.arcane.wormholes.menu.ChestClick;
import art.arcane.wormholes.menu.ChestConfirmLayout;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.ChestReopen;
import art.arcane.wormholes.menu.ChestTarget;
import art.arcane.wormholes.menu.CycleElement;
import art.arcane.wormholes.menu.LabelElement;
import art.arcane.wormholes.menu.MenuAction;
import art.arcane.wormholes.menu.MenuBindings;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuConfirmRequest;
import art.arcane.wormholes.menu.MenuContext;
import art.arcane.wormholes.menu.MenuController;
import art.arcane.wormholes.menu.MenuDecision;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuElementCommon;
import art.arcane.wormholes.menu.MenuFeedbackStyle;
import art.arcane.wormholes.menu.MenuGuard;
import art.arcane.wormholes.menu.MenuHandler;
import art.arcane.wormholes.menu.MenuIcon;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuLimits;
import art.arcane.wormholes.menu.MenuLines;
import art.arcane.wormholes.menu.MenuNotice;
import art.arcane.wormholes.menu.MenuNotices;
import art.arcane.wormholes.menu.MenuResult;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuSideAction;
import art.arcane.wormholes.menu.MenuSlot;
import art.arcane.wormholes.menu.MenuStrings;
import art.arcane.wormholes.menu.MenuStyle;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.NoticeChannel;
import art.arcane.wormholes.menu.TextElement;
import art.arcane.wormholes.menu.ToggleElement;
import art.arcane.wormholes.menu.portal.PortalMenuText;
import art.arcane.wormholes.nexus.AddressAllocator;
import art.arcane.wormholes.nexus.DestinationMode;
import art.arcane.wormholes.nexus.DestinationPolicy;
import art.arcane.wormholes.nexus.FrameIo;
import art.arcane.wormholes.nexus.NetworkMember;
import art.arcane.wormholes.nexus.PortalNetwork;
import art.arcane.wormholes.nexus.Topology;
import art.arcane.wormholes.nexus.Visibility;

public final class NetworkMenuController implements MenuController {
    public static final String ADMIN_NEXUS_PERMISSION = "wormholes.admin.nexus";
    public static final Map<LinesKey, Integer> CHEST_HINTS = Map.ofEntries(
        Map.entry(NexusMessages.MENU_PLACARD, 0),
        Map.entry(NexusMessages.MENU_JOIN, 1),
        Map.entry(NexusMessages.MENU_CREATE, 1),
        Map.entry(NexusMessages.MENU_ADDRESS, 1),
        Map.entry(NexusMessages.MENU_VISIBILITY, 1),
        Map.entry(NexusMessages.MENU_TOPOLOGY, 1),
        Map.entry(NexusMessages.MENU_DIAL, 1),
        Map.entry(NexusMessages.MENU_RECIPROCAL, 1),
        Map.entry(NexusMessages.MENU_POLICY, 1),
        Map.entry(NexusMessages.MENU_REDSTONE, 1),
        Map.entry(NexusMessages.MENU_LEAVE, 1));

    private static final Set<MenuInteraction> PRESS = Set.of(MenuInteraction.PRIMARY);
    private static final Set<MenuInteraction> TEXT = Set.of(MenuInteraction.SET_TEXT);
    private static final MenuIcon CLEAR_ICON = MenuIcon.sprite("small/delete");
    private static final MenuIcon REROLL_ICON = MenuIcon.sprite("small/reset");

    private final NetworkMenuHost host;
    private final Random random;

    public NetworkMenuController(NetworkMenuHost host, Random random) {
        this.host = Objects.requireNonNull(host, "host");
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public String id() {
        return NexusRoutes.NETWORK_CONTROLLER;
    }

    @Override
    public MenuDecision routeGuard(MenuContext context) {
        UUID portalId = NexusRoutes.portalId(context.route());
        if (host.portal(portalId).isEmpty() || !host.canManage(context.viewer(), portalId)) {
            return MenuDecision.denyAndClose(MenuNotices.hud(context.viewer(), WormholesMessages.PORTAL_EDIT_DENIED));
        }
        return MenuDecision.allow();
    }

    @Override
    public MenuBuild build(MenuContext context) {
        NexusPortalView portal = require(context);
        Optional<PortalNetwork> network = host.network(portal.networkId());
        return network.isPresent() ? joined(context, portal, network.get()) : unjoined(context, portal);
    }

    @Override
    public boolean touches(MenuRoute route, MenuScope scope) {
        UUID portalId = NexusRoutes.portalId(route);
        return switch (scope) {
            case MenuScope.Portal portal -> portal.portalId().equals(portalId);
            case MenuScope.Network _ -> true;
            default -> false;
        };
    }

    private MenuBuild unjoined(MenuContext context, NexusPortalView portal) {
        MenuStrings strings = context.strings();
        MenuGuard manage = manage(portal.id());
        List<MenuElement> elements = new ArrayList<>(3);
        MenuBindings.Builder bindings = MenuBindings.builder();

        elements.add(placard(strings, portal, null));
        elements.add(networkPrompt(strings, "nexus-join", NexusMessages.MENU_JOIN, "minecraft:ender_eye", -1));
        bindings.guard("nexus-join", manage).on("nexus-join", MenuInteraction.SET_TEXT, this::joinNamed);
        elements.add(networkPrompt(strings, "nexus-create", NexusMessages.MENU_CREATE, "minecraft:nether_star", 1));
        bindings.guard("nexus-create", manage).on("nexus-create", MenuInteraction.SET_TEXT, this::create);

        MenuScreen screen = MenuScreen.builder(NexusRoutes.NETWORK)
            .title(strings.text(MenuMessages.TITLE_NETWORK))
            .context(strings.text(MenuMessages.CONTEXT_PORTAL, MessageArgs.builder()
                .untrusted("portal", portal.name())
                .untrusted("type", portal.mirror()
                    ? PortalMenuText.plain(strings, WormholesMessages.PORTAL_LABEL_MIRROR)
                    : PortalMenuText.type(strings, portal.type()))
                .build()))
            .elements(elements)
            .build();
        return new MenuBuild(screen, chest(portal), bindings.build());
    }

    private MenuBuild joined(MenuContext context, NexusPortalView portal, PortalNetwork network) {
        MenuStrings strings = context.strings();
        UUID portalId = portal.id();
        MenuGuard manage = manage(portalId);
        DestinationPolicy policy = portal.policy();
        FrameIo frameIo = portal.frameIo();
        List<MenuElement> elements = new ArrayList<>(10);
        MenuBindings.Builder bindings = MenuBindings.builder();

        elements.add(placard(strings, portal, network));

        elements.add(new TextElement(common("nexus-address", lines(strings, NexusMessages.MENU_ADDRESS, args("address", portal.address())),
            "minecraft:name_tag", "rename", new MenuSlot(-3, 1)
                .withClick(ChestClick.LEFT, ChestTarget.self(MenuInteraction.SET_TEXT))
                .withClick(ChestClick.RIGHT, ChestTarget.self(MenuInteraction.SECONDARY)))
            .interactions(Set.of(MenuInteraction.SET_TEXT, MenuInteraction.SECONDARY))
            .sideActions(List.of(new MenuSideAction(MenuInteraction.SECONDARY, strings.text(MenuMessages.ACTION_REROLL), REROLL_ICON)))
            .build(), clip(portal.address()), MenuLimits.MAX_TEXT_INPUT_LENGTH, strings.text(MenuMessages.PLACEHOLDER_ADDRESS),
            prompt(strings, NexusMessages.PROMPT_ADDRESS), ChestReopen.NEVER, null));
        bindings.guard("nexus-address", manage)
            .on("nexus-address", MenuInteraction.SET_TEXT, (ctx, action) -> applyAddress(ctx, text(action)))
            .on("nexus-address", MenuInteraction.SECONDARY, this::rerollAddress);

        MessageArgs visibility = args("value", network.visibility().name());
        elements.add(new CycleElement(common("nexus-visibility", lines(strings, NexusMessages.MENU_VISIBILITY, visibility),
            "minecraft:item_frame", "network", new MenuSlot(-1, 1))
            .nativeLabel(strings.settingName(NexusMessages.MENU_VISIBILITY, visibility, "value")).interactions(PRESS).build(),
            names(Visibility.values()), network.visibility().ordinal()));
        bindings.guard("nexus-visibility", manage)
            .on("nexus-visibility", MenuInteraction.PRIMARY, this::cycleVisibility);

        MessageArgs topology = args("value", network.topology().name());
        elements.add(new CycleElement(common("nexus-topology", lines(strings, NexusMessages.MENU_TOPOLOGY, topology),
            "minecraft:iron_bars", "network", new MenuSlot(1, 1))
            .nativeLabel(strings.settingName(NexusMessages.MENU_TOPOLOGY, topology, "value")).interactions(PRESS).build(),
            names(Topology.values()), network.topology().ordinal()));
        bindings.guard("nexus-topology", manage)
            .on("nexus-topology", MenuInteraction.PRIMARY, this::cycleTopology);

        elements.add(new ButtonElement(common("nexus-dial", lines(strings, NexusMessages.MENU_DIAL, MessageArgs.empty()), "minecraft:lever",
            "wormhole", new MenuSlot(3, 1)).interactions(PRESS).build()));
        bindings.guard("nexus-dial", this::dialAdmitted)
            .on("nexus-dial", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.push(NexusRoutes.dial(portalId)));

        MessageArgs reciprocal = args("state", Boolean.toString(portal.reciprocal()));
        elements.add(new ToggleElement(common("nexus-reciprocal", lines(strings, NexusMessages.MENU_RECIPROCAL, reciprocal),
            "minecraft:ender_chest", "link", new MenuSlot(-3, 2))
            .nativeLabel(strings.settingName(NexusMessages.MENU_RECIPROCAL, reciprocal, "state")).interactions(PRESS).build(),
            portal.reciprocal()));
        bindings.guard("nexus-reciprocal", manage)
            .on("nexus-reciprocal", MenuInteraction.PRIMARY, (ctx, action) -> edit(ctx, new NetworkEdit.Reciprocal(!require(ctx).reciprocal())));

        MessageArgs policyArguments = MessageArgs.builder()
            .untrusted("mode", policy.mode().name())
            .untrusted("count", policy.entries().size())
            .build();
        elements.add(new CycleElement(common("nexus-policy", lines(strings, NexusMessages.MENU_POLICY, policyArguments),
            "minecraft:target", "network", new MenuSlot(-1, 2)
                .withClick(ChestClick.LEFT, ChestTarget.self(MenuInteraction.PRIMARY))
                .withClick(ChestClick.RIGHT, ChestTarget.self(MenuInteraction.SECONDARY)))
            .nativeLabel(strings.settingName(NexusMessages.MENU_POLICY, policyArguments, "mode"))
            .interactions(Set.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY))
            .sideActions(List.of(new MenuSideAction(MenuInteraction.SECONDARY, strings.text(MenuMessages.ACTION_CLEAR), CLEAR_ICON)))
            .build(), names(DestinationMode.values()), policy.mode().ordinal()));
        bindings.guard("nexus-policy", manage)
            .on("nexus-policy", MenuInteraction.PRIMARY, (ctx, action) -> {
                DestinationPolicy currentPolicy = require(ctx).policy();
                return edit(ctx, new NetworkEdit.Policy(currentPolicy.withMode(currentPolicy.mode().next())));
            })
            .on("nexus-policy", MenuInteraction.SECONDARY, (ctx, action) -> edit(ctx, new NetworkEdit.Policy(DestinationPolicy.empty())));

        MessageArgs redstone = args("value", frameIo.action().name() + "/" + frameIo.comparator().name());
        elements.add(new CycleElement(common("nexus-redstone", lines(strings, NexusMessages.MENU_REDSTONE, redstone), "minecraft:redstone_torch",
            "settings", new MenuSlot(1, 2)
                .withClick(ChestClick.LEFT, ChestTarget.self(MenuInteraction.PRIMARY))
                .withClick(ChestClick.RIGHT, ChestTarget.delegate("nexus-redstone-comparator", MenuInteraction.PRIMARY)))
            .nativeLabel(strings.settingName(NexusMessages.MENU_REDSTONE, redstone, "value"))
            .interactions(PRESS).build(), names(FrameIo.RedstoneAction.values()), frameIo.action().ordinal()));
        bindings.guard("nexus-redstone", manage)
            .on("nexus-redstone", MenuInteraction.PRIMARY, this::cycleRedstoneAction);

        elements.add(new CycleElement(common("nexus-redstone-comparator", new MenuLines(strings.text(MenuMessages.NEXUS_COMPARATOR), List.of(),
            List.of()), "minecraft:comparator", "settings", null).interactions(PRESS).build(),
            names(FrameIo.ComparatorOutput.values()), frameIo.comparator().ordinal()));
        bindings.guard("nexus-redstone-comparator", manage)
            .on("nexus-redstone-comparator", MenuInteraction.PRIMARY, this::cycleComparator);

        elements.add(new ButtonElement(common("nexus-leave", lines(strings, NexusMessages.MENU_LEAVE, args("name", network.name())),
            "minecraft:barrier", "delete", new MenuSlot(3, 2).withClick(ChestClick.LEFT, ChestTarget.preconfirmed(MenuInteraction.PRIMARY)))
            .style(MenuStyle.DESTRUCTIVE).interactions(PRESS).build()));
        bindings.guard("nexus-leave", manage)
            .on("nexus-leave", MenuInteraction.PRIMARY, this::leave)
            .confirm("nexus-leave", (ctx, onConfirm) -> leaveConfirmation(ctx, portal, network, onConfirm));

        MenuScreen screen = MenuScreen.builder(NexusRoutes.NETWORK)
            .title(strings.text(MenuMessages.TITLE_NETWORK))
            .context(strings.text(MenuMessages.CONTEXT_NETWORK, args("name", network.name())))
            .elements(elements)
            .build();
        return new MenuBuild(screen, chest(portal), bindings.build());
    }

    private MenuResult joinNamed(MenuContext context, MenuAction action) {
        String name = text(action);
        Optional<PortalNetwork> found = host.networkByName(name);
        if (found.isEmpty()) {
            return MenuResult.stay(chat(context.viewer(), NexusMessages.NETWORK_UNKNOWN, args("name", name), MenuFeedbackStyle.ERROR));
        }
        return MenuResult.stay(joinNetwork(context, found.get()).notice());
    }

    private MenuResult create(MenuContext context, MenuAction action) {
        String name = text(action);
        MenuViewer viewer = context.viewer();
        if (host.networkByName(name).isPresent()) {
            return MenuResult.stay(chat(viewer, NexusMessages.NETWORK_EXISTS, args("name", name), MenuFeedbackStyle.ERROR));
        }
        int limit = host.config().maxNetworksPerPlayer;
        if (!viewer.hasPermission(ADMIN_NEXUS_PERMISSION) && host.networksOwnedBy(viewer.id()) >= limit) {
            return MenuResult.stay(chat(viewer, NexusMessages.NETWORK_LIMIT, args("count", limit), MenuFeedbackStyle.ERROR));
        }
        Joining joining = joinNetwork(context, PortalNetwork.create(UUID.randomUUID(), name, viewer.id()));
        MessageArgs created = args("name", name);
        if (joining.key() == null) {
            host.tell(viewer, NexusMessages.CREATED, created);
            return MenuResult.stay(joining.notice());
        }
        host.tell(viewer, joining.key(), joining.arguments());
        return MenuResult.stay(chat(viewer, NexusMessages.CREATED, created, MenuFeedbackStyle.SUCCESS));
    }

    private Joining joinNetwork(MenuContext context, PortalNetwork network) {
        MenuViewer viewer = context.viewer();
        NexusPortalView portal = require(context);
        NexusConfig config = host.config();
        int limit = config.maxMembersPerNetwork;
        if (network.members().size() >= limit) {
            return Joining.told(viewer, NexusMessages.NETWORK_FULL, MessageArgs.builder()
                .untrusted("name", network.name())
                .untrusted("count", limit)
                .build(), MenuFeedbackStyle.ERROR);
        }
        String address = AddressAllocator.next(network, config.addressAlphabet, config.addressLength, random);
        PortalNetwork joined = network.withMember(portal.id(), new NetworkMember(portal.id(), address, portal.name(), System.currentTimeMillis(), null));
        if (!host.saveNetwork(joined)) {
            return new Joining(null, MessageArgs.empty(), saveFailed(viewer));
        }
        host.edit(viewer, portal.id(), new NetworkEdit.Join(joined.id(), address, portal.name()));
        MessageArgs arguments = MessageArgs.builder()
            .untrusted("portal", portal.name())
            .untrusted("name", joined.name())
            .untrusted("address", address)
            .build();
        return Joining.told(viewer, NexusMessages.JOINED, arguments, MenuFeedbackStyle.SUCCESS);
    }

    private MenuResult leave(MenuContext context, MenuAction action) {
        NexusPortalView portal = require(context);
        Optional<PortalNetwork> network = host.network(portal.networkId());
        if (network.isEmpty()) {
            return MenuResult.stay();
        }
        if (!host.saveNetwork(network.get().withoutMember(portal.id()))) {
            return MenuResult.stay(saveFailed(context.viewer()));
        }
        host.edit(context.viewer(), portal.id(), new NetworkEdit.Leave());
        return MenuResult.stay(chat(context.viewer(), NexusMessages.LEFT, MessageArgs.builder()
            .untrusted("portal", portal.name())
            .untrusted("name", network.get().name())
            .build(), MenuFeedbackStyle.SUCCESS));
    }

    private MenuResult rerollAddress(MenuContext context, MenuAction action) {
        NexusPortalView portal = require(context);
        Optional<PortalNetwork> network = host.network(portal.networkId());
        if (network.isEmpty()) {
            return MenuResult.stay();
        }
        NexusConfig config = host.config();
        return applyAddress(context, AddressAllocator.next(network.get().withoutMember(portal.id()), config.addressAlphabet,
            config.addressLength, random));
    }

    private MenuResult applyAddress(MenuContext context, String requested) {
        MenuViewer viewer = context.viewer();
        NexusPortalView portal = require(context);
        Optional<PortalNetwork> found = host.network(portal.networkId());
        if (found.isEmpty()) {
            return MenuResult.stay();
        }
        PortalNetwork network = found.get();
        String alphabet = host.config().addressAlphabet;
        if (!AddressAllocator.isValid(requested, alphabet)) {
            return MenuResult.stay(chat(viewer, NexusMessages.ADDRESS_INVALID, args("value", alphabet), MenuFeedbackStyle.ERROR));
        }
        String address = NetworkMember.normalizeAddress(requested);
        UUID holder = network.portalIdAt(address);
        if (holder != null && !holder.equals(portal.id())) {
            return MenuResult.stay(chat(viewer, NexusMessages.ADDRESS_TAKEN, MessageArgs.builder()
                .untrusted("address", address)
                .untrusted("name", network.name())
                .build(), MenuFeedbackStyle.ERROR));
        }
        NetworkMember member = network.member(portal.id());
        if (member == null) {
            return MenuResult.stay();
        }
        if (!host.saveNetwork(network.withMember(portal.id(), member.withAddress(address)))) {
            return MenuResult.stay(saveFailed(viewer));
        }
        host.edit(viewer, portal.id(), new NetworkEdit.Address(address));
        return MenuResult.stay(chat(viewer, NexusMessages.ADDRESS_SET, MessageArgs.builder()
            .untrusted("portal", portal.name())
            .untrusted("address", address)
            .build(), MenuFeedbackStyle.SUCCESS));
    }

    private MenuResult cycleRedstoneAction(MenuContext context, MenuAction action) {
        FrameIo frameIo = require(context).frameIo();
        FrameIo changed = frameIo.withAction(frameIo.action().next());
        return redstone(context, changed, changed.action().name());
    }

    private MenuResult cycleComparator(MenuContext context, MenuAction action) {
        FrameIo frameIo = require(context).frameIo();
        FrameIo changed = frameIo.withComparator(frameIo.comparator().next());
        return redstone(context, changed, changed.comparator().name());
    }

    private MenuResult redstone(MenuContext context, FrameIo changed, String value) {
        NexusPortalView portal = require(context);
        host.edit(context.viewer(), portal.id(), new NetworkEdit.Redstone(changed));
        return MenuResult.stay(chat(context.viewer(), NexusMessages.REDSTONE_SET, MessageArgs.builder()
            .untrusted("portal", portal.name())
            .untrusted("value", value)
            .build(), MenuFeedbackStyle.SUCCESS));
    }

    private MenuResult cycleVisibility(MenuContext context, MenuAction action) {
        PortalNetwork network = current(context);
        return saveNetwork(context, network.withVisibility(network.visibility().next()));
    }

    private MenuResult cycleTopology(MenuContext context, MenuAction action) {
        PortalNetwork network = current(context);
        return saveNetwork(context, network.withTopology(network.topology().next()));
    }

    private MenuResult saveNetwork(MenuContext context, PortalNetwork network) {
        return host.saveNetwork(network) ? MenuResult.stay() : MenuResult.stay(saveFailed(context.viewer()));
    }

    private MenuResult edit(MenuContext context, NetworkEdit edit) {
        host.edit(context.viewer(), NexusRoutes.portalId(context.route()), edit);
        return MenuResult.stay();
    }

    private MenuDecision dialAdmitted(MenuContext context, MenuAction action) {
        if (host.dialAdmission(context.viewer(), NexusRoutes.portalId(context.route()))) {
            return MenuDecision.allow();
        }
        return MenuDecision.denyAndClose(chat(context.viewer(), WormholesMessages.PORTAL_ACCESS_DENIED, MessageArgs.empty(), MenuFeedbackStyle.ERROR));
    }

    private MenuGuard manage(UUID portalId) {
        return (context, action) -> host.canManage(context.viewer(), portalId)
            ? MenuDecision.allow()
            : MenuDecision.denyAndClose(MenuNotices.hud(context.viewer(), WormholesMessages.PORTAL_EDIT_DENIED));
    }

    private MenuConfirmRequest leaveConfirmation(MenuContext context, NexusPortalView portal, PortalNetwork network, MenuHandler onConfirm) {
        MenuStrings strings = context.strings();
        MenuLines confirm = strings.lines(MenuMessages.CONFIRM_NEXUS_LEAVE, args("name", network.name()), 0);
        return new MenuConfirmRequest(confirm.label(), confirm.description(), strings.text(MenuMessages.CONFIRM_NEXUS_LEAVE_ACCEPT),
            strings.text(MenuMessages.CHROME_CANCEL), onConfirm, ChestConfirmLayout.standard(portal.routerTitle()));
    }

    private PortalNetwork current(MenuContext context) {
        NexusPortalView portal = require(context);
        return host.network(portal.networkId()).orElseThrow(() -> new IllegalStateException("portal " + portal.id() + " left its network"));
    }

    private NexusPortalView require(MenuContext context) {
        UUID portalId = NexusRoutes.portalId(context.route());
        return host.portal(portalId).orElseThrow(() -> new IllegalStateException("portal " + portalId + " is gone"));
    }

    private static LabelElement placard(MenuStrings strings, NexusPortalView portal, PortalNetwork network) {
        MessageArgs arguments = MessageArgs.builder()
            .untrusted("name", network == null ? "" : network.name())
            .untrusted("address", portal.address())
            .untrusted("count", network == null ? 0 : network.members().size())
            .build();
        return new LabelElement(common("nexus-placard", lines(strings, NexusMessages.MENU_PLACARD, arguments), "minecraft:compass", "network",
            new MenuSlot(0, 0)).build());
    }

    private static TextElement networkPrompt(MenuStrings strings, String id, LinesKey key, String item, int position) {
        return new TextElement(common(id, lines(strings, key, MessageArgs.empty()), item, "network", new MenuSlot(position, 1))
            .interactions(TEXT).build(), "", MenuLimits.MAX_TEXT_INPUT_LENGTH, strings.text(MenuMessages.PLACEHOLDER_NETWORK),
            prompt(strings, NexusMessages.PROMPT_NETWORK_NAME), ChestReopen.NEVER, null);
    }

    private static ChestLayout chest(NexusPortalView portal) {
        return new ChestLayout(portal.routerTitle(), 4, ChestPane.CYAN, null);
    }

    private static List<String> prompt(MenuStrings strings, TextKey key) {
        return List.of(strings.text(key, args("cancel", PortalMenuText.plain(strings, WormholesMessages.PORTAL_INPUT_CANCEL))));
    }

    private static MenuNotice chat(MenuViewer viewer, TextKey key, MessageArgs arguments, MenuFeedbackStyle style) {
        return MenuNotices.of(viewer, key, arguments, style, NoticeChannel.CHAT);
    }

    private static MenuNotice saveFailed(MenuViewer viewer) {
        return MenuNotices.nativeOnly(viewer, MenuMessages.FEEDBACK_SAVE_FAILED, MessageArgs.empty(), MenuFeedbackStyle.ERROR);
    }

    private static MenuElementCommon.Builder common(String id, MenuLines lines, String item, String sprite, MenuSlot slot) {
        return MenuElementCommon.builder(id)
            .lines(lines)
            .icon(MenuIcon.item(item))
            .nativeIcon(MenuIcon.sprite(sprite))
            .chestSlot(slot);
    }

    private static MenuLines lines(MenuStrings strings, LinesKey key, MessageArgs arguments) {
        return strings.lines(key, arguments, CHEST_HINTS.getOrDefault(key, 0));
    }

    private static MessageArgs args(String name, Object value) {
        return MessageArgs.builder().untrusted(name, value).build();
    }

    private static List<String> names(Enum<?>[] values) {
        List<String> names = new ArrayList<>(values.length);
        for (Enum<?> value : values) {
            names.add(value.name());
        }
        return names;
    }

    private static String text(MenuAction action) {
        return ((MenuValue.Text) action.value()).text().trim();
    }

    private static String clip(String value) {
        if (value.codePointCount(0, value.length()) <= MenuLimits.MAX_TEXT_INPUT_LENGTH) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, MenuLimits.MAX_TEXT_INPUT_LENGTH));
    }

    private record Joining(TextKey key, MessageArgs arguments, MenuNotice notice) {
        private static Joining told(MenuViewer viewer, TextKey key, MessageArgs arguments, MenuFeedbackStyle style) {
            return new Joining(key, arguments, chat(viewer, key, arguments, style));
        }
    }
}
