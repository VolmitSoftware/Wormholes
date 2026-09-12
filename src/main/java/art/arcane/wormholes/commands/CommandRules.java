package art.arcane.wormholes.commands;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.RulesMessages;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.rules.KeyItems;
import art.arcane.wormholes.rules.RuleDocument;
import art.arcane.wormholes.rules.RuleTemplates;
import art.arcane.wormholes.rules.RuleValidationException;
import art.arcane.wormholes.rules.RulesPortalExtension;
import art.arcane.wormholes.service.WormholesAudience;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Operator access to the traversal rules engine: move documents between portals and TOML templates, list what
 * is saved, and mint portal keys. Portals are named; the optional forms fall back to the portal the sender is
 * standing in.
 */
@Director(name = "rules", descriptionKey = "rules.command.help.rules",
        description = "Traversal rules: templates, keys and per-portal profiles")
public class CommandRules {
    private static final String PERMISSION = "wormholes.admin.rules";

    @Director(name = "export", sync = true, descriptionKey = "rules.command.help.export",
            description = "Write a portal's rules to a template file")
    public void export(@Param(name = "sender", contextual = true) CommandSender sender,
                       @Param(name = "name", descriptionKey = "rules.command.help.export.name",
                               description = "Template name to write") String name,
                       @Param(name = "portal", descriptionKey = "rules.command.help.export.portal",
                               description = "Portal name, or the portal you are standing in",
                               defaultValue = "") String portal) {
        if (!allowed(sender)) {
            return;
        }
        LocalPortal target = resolvePortal(sender, portal);
        if (target == null) {
            return;
        }
        RulesPortalExtension extension = target.extension(RulesPortalExtension.class);
        if (extension == null) {
            send(sender, RulesMessages.COMMAND_FAILED, WormholesLocalization.args(
                    MessageArgument.untrusted("reason", "the rules subsystem is not running")));
            return;
        }
        try {
            templates().save(name, extension.document());
        } catch (IOException | IllegalArgumentException failure) {
            send(sender, RulesMessages.COMMAND_FAILED, WormholesLocalization.args(
                    MessageArgument.untrusted("reason", String.valueOf(failure.getMessage()))));
            return;
        }
        send(sender, RulesMessages.COMMAND_EXPORTED, WormholesLocalization.args(
                MessageArgument.untrusted("portal", target.getName()),
                MessageArgument.untrusted("name", name)));
    }

    @Director(name = "import", sync = true, descriptionKey = "rules.command.help.import",
            description = "Apply a template to the portal you are standing in")
    public void importTemplate(@Param(name = "sender", contextual = true) CommandSender sender,
                               @Param(name = "name", descriptionKey = "rules.command.help.import.name",
                                       description = "Template name to read") String name,
                               @Param(name = "portal", descriptionKey = "rules.command.help.import.portal",
                                       description = "Portal name, or the portal you are standing in",
                                       defaultValue = "") String portal) {
        if (!allowed(sender)) {
            return;
        }
        applyTemplate(sender, name, resolvePortal(sender, portal));
    }

    @Director(name = "apply", sync = true, descriptionKey = "rules.command.help.apply",
            description = "Apply a template to a named portal from anywhere")
    public void apply(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "name", descriptionKey = "rules.command.help.apply.name",
                              description = "Template name to read") String name,
                      @Param(name = "portal", descriptionKey = "rules.command.help.apply.portal",
                              description = "Portal name to apply the template to") String portal) {
        if (!allowed(sender)) {
            return;
        }
        applyTemplate(sender, name, byName(sender, portal));
    }

    @Director(name = "list", sync = true, descriptionKey = "rules.command.help.list",
            description = "List saved rule templates")
    public void list(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!allowed(sender)) {
            return;
        }
        List<String> names = templates().list();
        if (names.isEmpty()) {
            send(sender, RulesMessages.COMMAND_NO_TEMPLATES, MessageArgs.empty());
            return;
        }
        send(sender, RulesMessages.COMMAND_TEMPLATES, WormholesLocalization.args(
                MessageArgument.untrusted("value", String.join(", ", names))));
    }

    @Director(name = "key", sync = true, descriptionKey = "rules.command.help.key",
            description = "Give yourself a key for a portal")
    public void key(@Param(name = "sender", contextual = true) CommandSender sender,
                    @Param(name = "portal", descriptionKey = "rules.command.help.key.portal",
                            description = "Portal name to mint the key for") String portal,
                    @Param(name = "uses", descriptionKey = "rules.command.help.key.uses",
                            description = "How many crossings the key allows, 0 for unlimited",
                            defaultValue = "0") int uses) {
        if (!allowed(sender)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            send(sender, WormholesMessages.COMMAND_ONLY_PLAYERS, MessageArgs.empty());
            return;
        }
        LocalPortal target = byName(sender, portal);
        if (target == null) {
            return;
        }
        player.getInventory().addItem(KeyItems.mint(target.getId(), uses));
        send(sender, RulesMessages.COMMAND_KEY_GIVEN, WormholesLocalization.args(
                MessageArgument.untrusted("portal", target.getName())));
    }

    private void applyTemplate(CommandSender sender, String name, LocalPortal target) {
        if (target == null) {
            return;
        }
        RulesPortalExtension extension = target.extension(RulesPortalExtension.class);
        if (extension == null) {
            send(sender, RulesMessages.COMMAND_FAILED, WormholesLocalization.args(
                    MessageArgument.untrusted("reason", "the rules subsystem is not running")));
            return;
        }
        RuleDocument document;
        try {
            document = templates().load(name);
        } catch (RuleValidationException invalid) {
            send(sender, RulesMessages.COMMAND_TEMPLATE_INVALID, WormholesLocalization.args(
                    MessageArgument.untrusted("name", name),
                    MessageArgument.untrusted("reason", String.join("; ", invalid.problems()))));
            return;
        } catch (IllegalArgumentException badName) {
            send(sender, RulesMessages.COMMAND_FAILED, WormholesLocalization.args(
                    MessageArgument.untrusted("reason", String.valueOf(badName.getMessage()))));
            return;
        }
        if (document == null) {
            send(sender, RulesMessages.COMMAND_TEMPLATE_MISSING, WormholesLocalization.args(
                    MessageArgument.untrusted("name", name)));
            return;
        }
        extension.setDocument(document);
        Wormholes.i("rules template " + name + " applied to portal " + target.getId());
        send(sender, RulesMessages.COMMAND_IMPORTED, WormholesLocalization.args(
                MessageArgument.untrusted("name", name),
                MessageArgument.untrusted("portal", target.getName())));
    }

    /** The named portal, or the one the sender is standing in when no name was given. */
    private static LocalPortal resolvePortal(CommandSender sender, String name) {
        if (name != null && !name.isBlank()) {
            return byName(sender, name);
        }
        if (!(sender instanceof Player player) || Wormholes.portalManager == null) {
            send(sender, RulesMessages.COMMAND_NOT_IN_PORTAL, MessageArgs.empty());
            return null;
        }
        for (ILocalPortal candidate : Wormholes.portalManager.getLocalPortals()) {
            if (candidate instanceof LocalPortal portal && portal.getStructure().contains(player.getLocation())) {
                return portal;
            }
        }
        send(sender, RulesMessages.COMMAND_NOT_IN_PORTAL, MessageArgs.empty());
        return null;
    }

    private static LocalPortal byName(CommandSender sender, String name) {
        if (Wormholes.portalManager == null) {
            send(sender, RulesMessages.COMMAND_PORTAL_MISSING, WormholesLocalization.args(
                    MessageArgument.untrusted("name", name)));
            return null;
        }
        List<LocalPortal> matches = new ArrayList<>();
        for (ILocalPortal candidate : Wormholes.portalManager.getLocalPortals()) {
            if (candidate instanceof LocalPortal portal && portal.getName() != null
                    && portal.getName().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                matches.add(portal);
            }
        }
        if (matches.isEmpty()) {
            send(sender, RulesMessages.COMMAND_PORTAL_MISSING, WormholesLocalization.args(
                    MessageArgument.untrusted("name", name)));
            return null;
        }
        if (matches.size() > 1) {
            send(sender, RulesMessages.COMMAND_PORTAL_AMBIGUOUS, WormholesLocalization.args(
                    MessageArgument.untrusted("count", Integer.valueOf(matches.size())),
                    MessageArgument.untrusted("name", name)));
            return null;
        }
        return matches.getFirst();
    }

    private static RuleTemplates templates() {
        return new RuleTemplates(Wormholes.instance.getDataFolder().toPath());
    }

    private static boolean allowed(CommandSender sender) {
        if (sender.hasPermission(PERMISSION)) {
            return true;
        }
        send(sender, WormholesMessages.COMMAND_NO_PERMISSION, MessageArgs.empty());
        return false;
    }

    private static void send(CommandSender sender, TextKey key, MessageArgs arguments) {
        WormholesAudience.sendMessage(sender, Wormholes.text().component(sender, key, arguments));
    }
}
