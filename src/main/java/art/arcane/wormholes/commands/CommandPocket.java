package art.arcane.wormholes.commands;

import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.director.DirectorParameterHandler;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.door.DimensionalDoorManager;
import art.arcane.wormholes.door.PocketLayout;
import art.arcane.wormholes.door.PocketMaterials;
import art.arcane.wormholes.door.PocketResizeOutcome;
import art.arcane.wormholes.door.PocketRole;
import art.arcane.wormholes.door.PocketSnapshots;
import art.arcane.wormholes.door.PocketRules;
import art.arcane.wormholes.door.PocketShell;
import art.arcane.wormholes.door.PocketSpace;
import art.arcane.wormholes.localization.PocketsMessages;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.service.WormholesAudience;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.structure.Structure;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pocket-dimension inspection and reshaping.
 *
 * <p>Size and materials are stored per pocket, so the config only shapes new
 * ones; these commands are how an existing pocket changes.</p>
 */
@Director(name = "pocket", descriptionKey = "command.help.pocket", description = "Inspect and reshape pocket dimensions")
public class CommandPocket {
    private static final String PERMISSION = "wormholes.admin.pocket";
    private static final String KEEP = "keep";

    private Template template = new Template();
    private Roster roster = new Roster();
    private Instance instance = new Instance();

    @Director(name = "info", sync = true, descriptionKey = "command.help.pocket.info",
            description = "Show the size, materials, and bounds of the pocket you are standing in")
    public void info(@Param(name = "sender", contextual = true) CommandSender sender) {
        DimensionalDoorManager manager = requireManager(sender);
        if (manager == null) {
            return;
        }
        PocketSpace space = requireStandingPocket(sender, manager);
        if (space == null) {
            return;
        }
        PocketLayout layout = manager.layoutOf(space);
        sendLines(sender, WormholesMessages.COMMAND_POCKET_INFO, WormholesLocalization.args(
                MessageArgument.untrusted("space", space.spaceId()),
                MessageArgument.untrusted("size", Integer.valueOf(layout.size())),
                MessageArgument.untrusted("material", space.shell().shellMaterial()),
                MessageArgument.untrusted("door", space.shell().returnDoorMaterial()),
                MessageArgument.untrusted("minimum", corner(layout.minX(), layout.minY(), layout.minZ())),
                MessageArgument.untrusted("maximum", corner(layout.maxX(), layout.maxY(), layout.maxZ()))
        ));
    }

    @Director(name = "resize", sync = true, descriptionKey = "command.help.pocket.resize",
            description = "Rebuild the pocket you are standing in at a new size or material")
    public void resize(@Param(name = "sender", contextual = true) CommandSender sender,
                       @Param(name = "size", descriptionKey = "command.help.pocket.resize.size",
                               description = "New room edge in blocks, or 0 to keep the current size",
                               defaultValue = "0", customHandler = SizeHandler.class) int size,
                       @Param(name = "material", descriptionKey = "command.help.pocket.resize.material",
                               description = "New wall, floor, and ceiling block, or keep",
                               defaultValue = KEEP, customHandler = ShellMaterialHandler.class) String material,
                       @Param(name = "door", descriptionKey = "command.help.pocket.resize.door",
                               description = "New exit door block, or keep",
                               defaultValue = KEEP, customHandler = DoorMaterialHandler.class) String door,
                       @Param(name = "confirm", descriptionKey = "command.help.pocket.resize.confirm",
                               description = "Required when the change would destroy or move anything",
                               defaultValue = "false") boolean confirm) {
        DimensionalDoorManager manager = requireManager(sender);
        if (manager == null) {
            return;
        }
        PocketSpace space = requireStandingPocket(sender, manager);
        if (space == null) {
            return;
        }
        PocketShell target = target(sender, space.shell(), size, material, door);
        if (target == null) {
            return;
        }
        manager.resizePocket(space, target, confirm, outcome -> report(sender, outcome));
    }

    @Director(name = "resizeall", sync = true, descriptionKey = "command.help.pocket.resizeall",
            description = "Rebuild every existing pocket at a new size or material")
    public void resizeAll(@Param(name = "sender", contextual = true) CommandSender sender,
                          @Param(name = "size", descriptionKey = "command.help.pocket.resize.size",
                                  description = "New room edge in blocks, or 0 to keep the current size",
                                  defaultValue = "0", customHandler = SizeHandler.class) int size,
                          @Param(name = "material", descriptionKey = "command.help.pocket.resize.material",
                                  description = "New wall, floor, and ceiling block, or keep",
                                  defaultValue = KEEP, customHandler = ShellMaterialHandler.class) String material,
                          @Param(name = "door", descriptionKey = "command.help.pocket.resize.door",
                                  description = "New exit door block, or keep",
                                  defaultValue = KEEP, customHandler = DoorMaterialHandler.class) String door,
                          @Param(name = "confirm", descriptionKey = "command.help.pocket.resize.confirm",
                                  description = "Required when the change would destroy or move anything",
                                  defaultValue = "false") boolean confirm) {
        DimensionalDoorManager manager = requireManager(sender);
        if (manager == null) {
            return;
        }
        // Validated against the configured shape so a bad size or material is
        // rejected once here rather than per pocket.
        if (target(sender, Settings.POCKET_SHELL, size, material, door) == null) {
            return;
        }

        List<PocketSpace> spaces = manager.pockets();
        send(sender, WormholesMessages.COMMAND_POCKET_BULK_STARTED,
                WormholesLocalization.args(MessageArgument.untrusted("count", Integer.valueOf(spaces.size()))));
        AtomicInteger pending = new AtomicInteger(spaces.size());
        AtomicInteger resized = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        if (spaces.isEmpty()) {
            sendBulkSummary(sender, resized, skipped, failed);
            return;
        }
        for (PocketSpace space : spaces) {
            PocketShell shell = space.shell()
                .withSize(size <= 0 ? space.shell().size() : size);
            if (!KEEP.equalsIgnoreCase(material)) {
                shell = shell.withShellMaterial(material);
            }
            if (!KEEP.equalsIgnoreCase(door)) {
                shell = shell.withReturnDoorMaterial(door);
            }
            manager.resizePocket(space, shell, confirm, outcome -> {
                switch (outcome.status()) {
                    case RESIZED -> resized.incrementAndGet();
                    case UNCHANGED, NEEDS_CONFIRMATION, NON_EMPTY_CONTAINERS -> skipped.incrementAndGet();
                    default -> failed.incrementAndGet();
                }
                if (pending.decrementAndGet() == 0) {
                    sendBulkSummary(sender, resized, skipped, failed);
                }
            });
        }
    }

    @Director(name = "template", sync = true, description = "List and apply pocket templates")
    public static class Template {
        @Director(name = "list", sync = true, description = "List the structure files usable as pocket templates")
        public void list(@Param(name = "sender", contextual = true) CommandSender sender) {
            DimensionalDoorManager manager = requireManager(sender);
            if (manager == null) {
                return;
            }
            List<String> names = manager.templates().names();
            send(sender, PocketsMessages.TEMPLATE_LIST, WormholesLocalization.args(
                    MessageArgument.untrusted("count", Integer.valueOf(names.size())),
                    MessageArgument.untrusted("value", names.isEmpty() ? "-" : String.join(", ", names))));
        }

        @Director(name = "apply", sync = true, description = "Stamp a template over the pocket you are standing in")
        public void apply(@Param(name = "sender", contextual = true) CommandSender sender,
                          @Param(name = "name", description = "Template file name without .nbt") String name,
                          @Param(name = "confirm", description = "Required because this replaces the room interior",
                                  defaultValue = "false") boolean confirm) {
            DimensionalDoorManager manager = requireManager(sender);
            if (manager == null) {
                return;
            }
            PocketSpace space = requireStandingPocket(sender, manager);
            if (space == null) {
                return;
            }
            Optional<Structure> structure = loadTemplate(sender, manager, name);
            if (structure == null || structure.isEmpty()) {
                return;
            }
            if (!confirm) {
                sendLines(sender, PocketsMessages.CONFIRM_OVERWRITE, WormholesLocalization.args(
                        MessageArgument.untrusted("space", space.spaceId())));
                return;
            }
            manager.applyTemplate(space, structure.get(), name.trim(), true, applied -> {
                if (Boolean.TRUE.equals(applied)) {
                    send(sender, PocketsMessages.TEMPLATE_APPLIED, WormholesLocalization.args(
                            MessageArgument.untrusted("name", name.trim()),
                            MessageArgument.untrusted("space", space.spaceId())));
                } else {
                    send(sender, WormholesMessages.COMMAND_POCKET_FAILED);
                }
            });
        }
    }

    @Director(name = "rules", sync = true, description = "Set one rule on the pocket you are standing in")
    public void rules(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "key", description = "mobs | pvp | keep-inventory | fixed-time | build")
                      String key,
                      @Param(name = "value", description = "true/false, a tick of day or -1, or everyone/builders/owner")
                      String value) {
        DimensionalDoorManager manager = requireManager(sender);
        if (manager == null) {
            return;
        }
        PocketSpace space = requireStandingPocket(sender, manager);
        if (space == null) {
            return;
        }
        PocketRules updated = withRule(space.rules(), key, value);
        if (updated == null) {
            send(sender, PocketsMessages.RULES_INVALID,
                    WormholesLocalization.args(MessageArgument.untrusted("key", key)));
            return;
        }
        try {
            manager.applyPocketRules(space.spaceId(), updated);
        } catch (IOException failure) {
            send(sender, WormholesMessages.COMMAND_POCKET_FAILED);
            return;
        }
        send(sender, PocketsMessages.RULES_SET, WormholesLocalization.args(
                MessageArgument.untrusted("key", key.trim().toLowerCase(Locale.ROOT)),
                MessageArgument.untrusted("value", value.trim())));
    }

    /** @return the updated rules, or null when the key or the value is not one we know */
    private static PocketRules withRule(PocketRules rules, String key, String value) {
        String normalizedKey = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        String normalizedValue = value == null ? "" : value.trim();
        try {
            return switch (normalizedKey) {
                case "mobs" -> rules.withMobs(flag(normalizedValue));
                case "pvp" -> rules.withPvp(flag(normalizedValue));
                case "keep-inventory" -> rules.withKeepInventory(flag(normalizedValue));
                case "fixed-time" -> rules.withFixedTime(Long.parseLong(normalizedValue));
                case "build" -> rules.withBuild(PocketRules.BuildPolicy.parse(normalizedValue));
                default -> null;
            };
        } catch (IllegalArgumentException rejected) {
            return null;
        }
    }

    private static boolean flag(String value) {
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException("expected true or false, got " + value);
    }

    @Director(name = "snapshot", sync = true, description = "Save a copy of this pocket's interior")
    public void snapshot(@Param(name = "sender", contextual = true) CommandSender sender,
                         @Param(name = "name", description = "Snapshot name",
                                 defaultValue = PocketSnapshots.LATEST) String name) {
        DimensionalDoorManager manager = requireManager(sender);
        if (manager == null) {
            return;
        }
        PocketSpace space = requireStandingPocket(sender, manager);
        if (space == null) {
            return;
        }
        try {
            manager.captureSnapshot(space, name.trim());
        } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
            send(sender, WormholesMessages.COMMAND_POCKET_FAILED);
            return;
        }
        send(sender, PocketsMessages.SNAPSHOT_SAVED, WormholesLocalization.args(
                MessageArgument.untrusted("name", name.trim()),
                MessageArgument.untrusted("space", space.spaceId())));
    }

    @Director(name = "restore", sync = true, description = "Put a saved copy of this pocket's interior back")
    public void restore(@Param(name = "sender", contextual = true) CommandSender sender,
                        @Param(name = "name", description = "Snapshot name",
                                defaultValue = PocketSnapshots.LATEST) String name,
                        @Param(name = "confirm", description = "Required because this replaces the room interior",
                                defaultValue = "false") boolean confirm) {
        DimensionalDoorManager manager = requireManager(sender);
        if (manager == null) {
            return;
        }
        PocketSpace space = requireStandingPocket(sender, manager);
        if (space == null) {
            return;
        }
        Optional<Structure> snapshot;
        try {
            snapshot = manager.snapshots().load(space.spaceId(), name.trim());
        } catch (IOException | IllegalArgumentException failure) {
            snapshot = Optional.empty();
        }
        if (snapshot.isEmpty()) {
            send(sender, PocketsMessages.SNAPSHOT_MISSING,
                    WormholesLocalization.args(MessageArgument.untrusted("name", name)));
            return;
        }
        if (!confirm) {
            sendLines(sender, PocketsMessages.CONFIRM_OVERWRITE, WormholesLocalization.args(
                    MessageArgument.untrusted("space", space.spaceId())));
            return;
        }
        manager.applyTemplate(space, snapshot.get(), space.templateName(), true, applied -> {
            if (Boolean.TRUE.equals(applied)) {
                send(sender, PocketsMessages.SNAPSHOT_RESTORED, WormholesLocalization.args(
                        MessageArgument.untrusted("name", name.trim()),
                        MessageArgument.untrusted("space", space.spaceId())));
            } else {
                send(sender, WormholesMessages.COMMAND_POCKET_FAILED);
            }
        });
    }

    @Director(name = "instance", sync = true, description = "Manage the instance this pocket is a copy of")
    public static class Instance {
        @Director(name = "reset", sync = true, description = "Wipe this instance back to its template")
        public void reset(@Param(name = "sender", contextual = true) CommandSender sender) {
            DimensionalDoorManager manager = requireManager(sender);
            if (manager == null) {
                return;
            }
            PocketSpace space = requireStandingPocket(sender, manager);
            if (space == null) {
                return;
            }
            if (space.instance() == null) {
                send(sender, PocketsMessages.INSTANCE_NONE);
                return;
            }
            manager.resetInstance(space, applied -> {
                if (Boolean.TRUE.equals(applied)) {
                    send(sender, PocketsMessages.INSTANCE_RESET,
                            WormholesLocalization.args(MessageArgument.untrusted("space", space.spaceId())));
                } else {
                    send(sender, WormholesMessages.COMMAND_POCKET_FAILED);
                }
            });
        }
    }

    @Director(name = "roster", sync = true, description = "Manage who may build in this pocket")
    public static class Roster {
        @Director(name = "add", sync = true, description = "Put a player on this pocket's roster")
        public void add(@Param(name = "sender", contextual = true) CommandSender sender,
                        @Param(name = "player", description = "Player name") String player,
                        @Param(name = "role", description = "visitor | builder | owner", defaultValue = "visitor")
                        String role) {
            assign(sender, player, role, PocketsMessages.ROSTER_ADDED);
        }

        @Director(name = "role", sync = true, description = "Change a listed player's role")
        public void role(@Param(name = "sender", contextual = true) CommandSender sender,
                         @Param(name = "player", description = "Player name") String player,
                         @Param(name = "role", description = "visitor | builder | owner") String role) {
            assign(sender, player, role, PocketsMessages.ROSTER_ROLE);
        }

        @Director(name = "remove", sync = true, description = "Take a player off this pocket's roster")
        public void remove(@Param(name = "sender", contextual = true) CommandSender sender,
                           @Param(name = "player", description = "Player name") String player) {
            DimensionalDoorManager manager = requireManager(sender);
            if (manager == null) {
                return;
            }
            PocketSpace space = requireStandingPocket(sender, manager);
            if (space == null) {
                return;
            }
            UUID playerId = resolvePlayer(player);
            boolean removed;
            try {
                removed = playerId != null && manager.removePocketRole(space.spaceId(), playerId);
            } catch (IOException failure) {
                send(sender, WormholesMessages.COMMAND_POCKET_FAILED);
                return;
            }
            send(sender, removed ? PocketsMessages.ROSTER_REMOVED : PocketsMessages.ROSTER_MISSING,
                    WormholesLocalization.args(MessageArgument.untrusted("name", player)));
        }

        private static void assign(CommandSender sender, String player, String role, TextKey success) {
            DimensionalDoorManager manager = requireManager(sender);
            if (manager == null) {
                return;
            }
            PocketSpace space = requireStandingPocket(sender, manager);
            if (space == null) {
                return;
            }
            UUID playerId = resolvePlayer(player);
            if (playerId == null) {
                send(sender, PocketsMessages.ROSTER_MISSING,
                        WormholesLocalization.args(MessageArgument.untrusted("name", player)));
                return;
            }
            PocketRole requested = parseRole(role);
            if (requested == null) {
                send(sender, PocketsMessages.RULES_INVALID,
                        WormholesLocalization.args(MessageArgument.untrusted("key", role)));
                return;
            }
            try {
                manager.assignPocketRole(space.spaceId(), playerId, requested);
            } catch (IOException failure) {
                send(sender, WormholesMessages.COMMAND_POCKET_FAILED);
                return;
            }
            send(sender, success, WormholesLocalization.args(
                    MessageArgument.untrusted("name", player),
                    MessageArgument.untrusted("value", requested.name().toLowerCase(Locale.ROOT))));
        }

        private static PocketRole parseRole(String role) {
            String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
            for (PocketRole candidate : PocketRole.values()) {
                if (candidate.name().equals(normalized)) {
                    return candidate;
                }
            }
            return null;
        }

        /** Offline players resolve too, so a roster survives the player being away. */
        private static UUID resolvePlayer(String name) {
            if (name == null || name.isBlank()) {
                return null;
            }
            Player online = Bukkit.getPlayerExact(name.trim());
            if (online != null) {
                return online.getUniqueId();
            }
            OfflinePlayer known = WormholesPlatform.offlinePlayerIfCached(name.trim());
            return known == null ? null : known.getUniqueId();
        }
    }

    /** @return empty when the file is missing, null when the name itself was rejected */
    private static Optional<Structure> loadTemplate(
        CommandSender sender,
        DimensionalDoorManager manager,
        String name
    ) {
        try {
            Optional<Structure> structure = manager.templates().load(name);
            if (structure.isEmpty()) {
                send(sender, PocketsMessages.TEMPLATE_MISSING,
                        WormholesLocalization.args(MessageArgument.untrusted("name", name)));
            }
            return structure;
        } catch (IOException | IllegalArgumentException rejected) {
            send(sender, PocketsMessages.TEMPLATE_MISSING,
                    WormholesLocalization.args(MessageArgument.untrusted("name", name)));
            return null;
        }
    }

    /** Applies the requested changes to {@code current}, reporting the first rejection. */
    private static PocketShell target(
        CommandSender sender,
        PocketShell current,
        int size,
        String material,
        String door
    ) {
        int requestedSize = size <= 0 ? current.size() : size;
        if (!PocketShell.isSupportedSize(requestedSize)) {
            send(sender, WormholesMessages.COMMAND_POCKET_INVALID_SIZE, WormholesLocalization.args(
                    MessageArgument.untrusted("minimum", Integer.valueOf(PocketShell.MIN_SIZE)),
                    MessageArgument.untrusted("maximum", Integer.valueOf(PocketShell.MAX_SIZE))
            ));
            return null;
        }
        PocketShell target = current.withSize(requestedSize);
        if (!KEEP.equalsIgnoreCase(material)) {
            if (PocketMaterials.shellMaterial(material).isEmpty()) {
                send(sender, WormholesMessages.COMMAND_POCKET_INVALID_SHELL_MATERIAL,
                        WormholesLocalization.args(MessageArgument.untrusted("material", material)));
                return null;
            }
            target = target.withShellMaterial(material);
        }
        if (!KEEP.equalsIgnoreCase(door)) {
            if (PocketMaterials.returnDoorMaterial(door).isEmpty()) {
                send(sender, WormholesMessages.COMMAND_POCKET_INVALID_DOOR_MATERIAL,
                        WormholesLocalization.args(MessageArgument.untrusted("material", door)));
                return null;
            }
            target = target.withReturnDoorMaterial(door);
        }
        return target;
    }

    private static void report(CommandSender sender, PocketResizeOutcome outcome) {
        PocketShell target = outcome.target();
        switch (outcome.status()) {
            case RESIZED -> send(sender, WormholesMessages.COMMAND_POCKET_RESIZED, WormholesLocalization.args(
                    MessageArgument.untrusted("size", Integer.valueOf(target.size())),
                    MessageArgument.untrusted("previous", Integer.valueOf(outcome.space().shell().size())),
                    MessageArgument.untrusted("material", target.shellMaterial()),
                    MessageArgument.untrusted("door", target.returnDoorMaterial())
            ));
            case NEEDS_CONFIRMATION -> sendLines(sender, WormholesMessages.COMMAND_POCKET_CONFIRM_REQUIRED,
                    WormholesLocalization.args(
                            MessageArgument.untrusted("size", Integer.valueOf(target.size())),
                            MessageArgument.untrusted("blocks", Long.valueOf(outcome.impact().blocks())),
                            MessageArgument.untrusted("entities", Long.valueOf(outcome.impact().entities()))
                    ));
            case NON_EMPTY_CONTAINERS -> send(sender, WormholesMessages.COMMAND_POCKET_CONTAINERS_NOT_EMPTY,
                    WormholesLocalization.args(
                            MessageArgument.untrusted("containers", Long.valueOf(outcome.impact().containers()))));
            case UNCHANGED -> send(sender, WormholesMessages.COMMAND_POCKET_UNCHANGED);
            case WORLD_UNAVAILABLE -> send(sender, WormholesMessages.COMMAND_POCKET_WORLD_UNAVAILABLE);
            case DOES_NOT_FIT -> send(sender, WormholesMessages.COMMAND_POCKET_DOES_NOT_FIT,
                    WormholesLocalization.args(
                            MessageArgument.untrusted("size", Integer.valueOf(target.size()))));
            case FAILED -> send(sender, WormholesMessages.COMMAND_POCKET_FAILED);
        }
    }

    private static void sendBulkSummary(
        CommandSender sender,
        AtomicInteger resized,
        AtomicInteger skipped,
        AtomicInteger failed
    ) {
        send(sender, WormholesMessages.COMMAND_POCKET_BULK_FINISHED, WormholesLocalization.args(
                MessageArgument.untrusted("resized", Integer.valueOf(resized.get())),
                MessageArgument.untrusted("skipped", Integer.valueOf(skipped.get())),
                MessageArgument.untrusted("failed", Integer.valueOf(failed.get()))
        ));
    }

    private static DimensionalDoorManager requireManager(CommandSender sender) {
        if (!sender.hasPermission(PERMISSION)) {
            send(sender, WormholesMessages.COMMAND_NO_PERMISSION);
            return null;
        }
        DimensionalDoorManager manager = Wormholes.instance.getDimensionalDoorManager();
        if (!Settings.DIMENSIONAL_DOORS_ENABLED || manager == null) {
            send(sender, WormholesMessages.COMMAND_DOORS_UNAVAILABLE);
            return null;
        }
        return manager;
    }

    private static PocketSpace requireStandingPocket(CommandSender sender, DimensionalDoorManager manager) {
        if (!(sender instanceof Player player)) {
            send(sender, WormholesMessages.COMMAND_ONLY_PLAYERS);
            return null;
        }
        Optional<PocketSpace> space = manager.pocketAt(player.getLocation());
        if (space.isEmpty()) {
            send(sender, WormholesMessages.COMMAND_POCKET_NOT_INSIDE);
            return null;
        }
        return space.get();
    }

    private static String corner(int x, int y, int z) {
        return x + ", " + y + ", " + z;
    }

    private static void send(CommandSender sender, TextKey key) {
        send(sender, key, MessageArgs.empty());
    }

    private static void send(CommandSender sender, TextKey key, MessageArgs arguments) {
        WormholesAudience.sendMessage(sender, Wormholes.text().component(sender, key, arguments));
    }

    private static void sendLines(CommandSender sender, LinesKey key, MessageArgs arguments) {
        for (Component line : Wormholes.text().components(sender, key, arguments)) {
            WormholesAudience.sendMessage(sender, line);
        }
    }

    /** Offers a ladder of room sizes; any size in range still parses. */
    public static final class SizeHandler implements DirectorParameterHandler<Integer> {
        private static final List<Integer> SUGGESTED = List.of(0, 16, 32, 64, 96, 128);
        private static final int STEP = 8;

        @Override
        public KList<Integer> getPossibilities() {
            return new KList<>(SUGGESTED);
        }

        @Override
        public KList<Integer> getPossibilities(String input) {
            if (input == null || input.isBlank()) {
                return getPossibilities();
            }
            KList<Integer> matches = new KList<>();
            String needle = input.trim();
            for (int size = PocketShell.MIN_SIZE; size <= PocketShell.MAX_SIZE; size += STEP) {
                if (Integer.toString(size).startsWith(needle)) {
                    matches.add(Integer.valueOf(size));
                }
            }
            return matches;
        }

        @Override
        public String toString(Integer value) {
            return value == null ? "" : value.toString();
        }

        @Override
        public Integer parse(String input, boolean force) throws DirectorParsingException {
            try {
                int parsed = Integer.parseInt(input == null ? "" : input.trim());
                if (parsed < 0) {
                    throw new NumberFormatException(input);
                }
                return Integer.valueOf(parsed);
            } catch (NumberFormatException notANumber) {
                throw new DirectorParsingException(Wormholes.text().plain(
                        WormholesMessages.COMMAND_POCKET_INVALID_SIZE,
                        WormholesLocalization.args(
                                MessageArgument.untrusted("minimum", Integer.valueOf(PocketShell.MIN_SIZE)),
                                MessageArgument.untrusted("maximum", Integer.valueOf(PocketShell.MAX_SIZE)))));
            }
        }

        @Override
        public boolean supports(Class<?> type) {
            return type == Integer.class || type == int.class;
        }
    }

    /** Completes wall blocks, and {@code keep} for leaving the material alone. */
    public static final class ShellMaterialHandler extends MaterialHandler {
        @Override
        List<String> suggested() {
            return PocketMaterials.commonShellMaterialNames();
        }

        @Override
        List<String> matching(String input) {
            return PocketMaterials.shellMaterialNamesMatching(input);
        }
    }

    /** Completes hand-operable doors, and {@code keep} for leaving the door alone. */
    public static final class DoorMaterialHandler extends MaterialHandler {
        @Override
        List<String> suggested() {
            return PocketMaterials.commonReturnDoorMaterialNames();
        }

        @Override
        List<String> matching(String input) {
            return PocketMaterials.returnDoorMaterialNamesMatching(input);
        }
    }

    /**
     * Material completion is deliberately narrow with no prefix typed and wide
     * once there is one, so a bare tab does not list every block in the game.
     * Rejection stays in the command, where the message can say why.
     */
    abstract static class MaterialHandler implements DirectorParameterHandler<String> {
        abstract List<String> suggested();

        abstract List<String> matching(String input);

        @Override
        public KList<String> getPossibilities() {
            KList<String> possibilities = new KList<>();
            possibilities.add(KEEP);
            possibilities.addAll(suggested());
            return possibilities;
        }

        @Override
        public KList<String> getPossibilities(String input) {
            if (input == null || input.isBlank()) {
                return getPossibilities();
            }
            KList<String> matches = new KList<>();
            if (KEEP.startsWith(input.trim().toLowerCase(Locale.ROOT))) {
                matches.add(KEEP);
            }
            matches.addAll(matching(input));
            return matches;
        }

        @Override
        public String toString(String value) {
            return value == null ? "" : value;
        }

        @Override
        public String parse(String input, boolean force) throws DirectorParsingException {
            return input == null ? "" : input.trim();
        }

        @Override
        public boolean supports(Class<?> type) {
            return type == String.class;
        }
    }
}
