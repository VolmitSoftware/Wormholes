package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Message keys owned by the rules lane. Every id starts with "rules.". Add keys here and translate
 * them inside the "lane:rules" block of every languages/*.toml file.
 */
public final class RulesMessages {
    private static final MessageGroup GROUP = new MessageGroup("rules.");

    public static final TextKey ROUTE_CARD = GROUP.text("rules.route_card", "{portal} > {destination} | {amount} | {state}");
    public static final TextKey DENIED_DEFAULT = GROUP.text("rules.denied.default", "This portal refused you.");
    public static final TextKey DENIED_ENTITY_CLASS = GROUP.text("rules.denied.entity_class", "Only {mode} may use this portal.");
    public static final TextKey DENIED_KEY = GROUP.text("rules.denied.key", "You need the key for {portal}.");
    public static final TextKey DENIED_TIME = GROUP.text("rules.denied.time", "{portal} is closed right now.");
    public static final TextKey DENIED_COOLDOWN = GROUP.text("rules.denied.cooldown", "Wait {seconds}s before using {portal} again.");
    public static final TextKey DENIED_CHARGES = GROUP.text("rules.denied.charges", "{portal} has no charges left.");
    public static final TextKey DENIED_COST = GROUP.text("rules.denied.cost", "You cannot pay {amount} to use {portal}.");
    public static final TextKey WARMUP_COUNTDOWN = GROUP.text("rules.warmup.countdown", "Hold still: {seconds}s");
    public static final TextKey WARMUP_CANCELLED = GROUP.text("rules.warmup.cancelled", "Warmup cancelled.");
    public static final TextKey ROUTE_UNLISTED = GROUP.text("rules.route.unlisted", "Unknown");
    public static final TextKey ROUTE_FREE = GROUP.text("rules.route.free", "Free");
    public static final TextKey ROUTE_READY = GROUP.text("rules.route.ready", "Ready");
    public static final TextKey ROUTE_COOLDOWN = GROUP.text("rules.route.cooldown", "{seconds}s");

    public static final TextKey COMMAND_RULES = GROUP.text("rules.command.help.rules", "Traversal rules: templates, keys and per-portal profiles");
    public static final TextKey COMMAND_EXPORT = GROUP.text("rules.command.help.export", "Write a portal's rules to a template file");
    public static final TextKey COMMAND_EXPORT_NAME = GROUP.text("rules.command.help.export.name", "Template name to write");
    public static final TextKey COMMAND_EXPORT_PORTAL = GROUP.text("rules.command.help.export.portal", "Portal name, or the portal you are standing in");
    public static final TextKey COMMAND_IMPORT = GROUP.text("rules.command.help.import", "Apply a template to the portal you are standing in");
    public static final TextKey COMMAND_IMPORT_NAME = GROUP.text("rules.command.help.import.name", "Template name to read");
    public static final TextKey COMMAND_IMPORT_PORTAL = GROUP.text("rules.command.help.import.portal", "Portal name, or the portal you are standing in");
    public static final TextKey COMMAND_APPLY = GROUP.text("rules.command.help.apply", "Apply a template to a named portal from anywhere");
    public static final TextKey COMMAND_APPLY_NAME = GROUP.text("rules.command.help.apply.name", "Template name to read");
    public static final TextKey COMMAND_APPLY_PORTAL = GROUP.text("rules.command.help.apply.portal", "Portal name to apply the template to");
    public static final TextKey COMMAND_LIST = GROUP.text("rules.command.help.list", "List saved rule templates");
    public static final TextKey COMMAND_KEY = GROUP.text("rules.command.help.key", "Give yourself a key for a portal");
    public static final TextKey COMMAND_KEY_PORTAL = GROUP.text("rules.command.help.key.portal", "Portal name to mint the key for");
    public static final TextKey COMMAND_KEY_USES = GROUP.text("rules.command.help.key.uses", "How many crossings the key allows, 0 for unlimited");

    public static final TextKey COMMAND_EXPORTED = GROUP.text("rules.command.exported", "Exported {portal} rules to template {name}.");
    public static final TextKey COMMAND_IMPORTED = GROUP.text("rules.command.imported", "Applied template {name} to {portal}.");
    public static final TextKey COMMAND_TEMPLATES = GROUP.text("rules.command.templates", "Rule templates: {value}");
    public static final TextKey COMMAND_NO_TEMPLATES = GROUP.text("rules.command.no_templates", "No rule templates are saved.");
    public static final TextKey COMMAND_TEMPLATE_MISSING = GROUP.text("rules.command.template_missing", "There is no rule template named {name}.");
    public static final TextKey COMMAND_TEMPLATE_INVALID = GROUP.text("rules.command.template_invalid", "Template {name} is invalid: {reason}");
    public static final TextKey COMMAND_PORTAL_MISSING = GROUP.text("rules.command.portal_missing", "There is no portal named {name}.");
    public static final TextKey COMMAND_PORTAL_AMBIGUOUS = GROUP.text("rules.command.portal_ambiguous", "{count} portals are named {name}; rename one.");
    public static final TextKey COMMAND_NOT_IN_PORTAL = GROUP.text("rules.command.not_in_portal", "Stand in a portal or name one with the portal option.");
    public static final TextKey COMMAND_KEY_GIVEN = GROUP.text("rules.command.key_given", "You received a key for {portal}.");
    public static final TextKey COMMAND_FAILED = GROUP.text("rules.command.failed", "Could not finish: {reason}");

    public static final LinesKey MENU_ENTRY = GROUP.lines("rules.menu.entry",
        "&6Rules", "&7Who may pass, what it costs, what happens next.");
    public static final LinesKey MENU_COOLDOWN = GROUP.lines("rules.menu.cooldown",
        "&6Cooldown: {seconds}s", "&7Left and right click to change, shift for 10s steps.");
    public static final LinesKey MENU_WARMUP = GROUP.lines("rules.menu.warmup",
        "&6Warmup: {seconds}s", "&7Left and right click to change, shift for 10s steps.");
    public static final LinesKey MENU_GROUP = GROUP.lines("rules.menu.group",
        "&6Cooldown group: {value}", "&7Click to type a group name.");
    public static final LinesKey MENU_PUSHBACK = GROUP.lines("rules.menu.pushback",
        "&6Pushback: {value}", "&7Left and right click to change the value.");
    public static final LinesKey MENU_SOUND = GROUP.lines("rules.menu.sound",
        "&6Refusal volume: {value}", "&7Left and right click to change the value.");
    public static final LinesKey MENU_CHARGES = GROUP.lines("rules.menu.charges",
        "&6Charges: {count}", "&7Left and right click to change the pool size.");
    public static final LinesKey MENU_DEFAULT_OUTCOME = GROUP.lines("rules.menu.default_outcome",
        "&6Default: {mode}", "&7Click to switch between allow and deny.");
    public static final LinesKey MENU_RULES = GROUP.lines("rules.menu.rules",
        "&6Rules ({count})", "&7Click to open the rule list.");
    public static final LinesKey MENU_RULE = GROUP.lines("rules.menu.rule",
        "&6{name}", "&7{value} &8- shift click removes the rule.");
    public static final LinesKey MENU_ADD_RULE = GROUP.lines("rules.menu.add_rule",
        "&aAdd a rule", "&7Click to type a rule id.");
    public static final LinesKey MENU_LINE = GROUP.lines("rules.menu.line",
        "&f{value}", "&7Shift click removes this line.");
    public static final LinesKey MENU_ADD_LINE = GROUP.lines("rules.menu.add_line",
        "&aAdd a line", "&7Click and type kind=PERMISSION;node=group.vip.");
    public static final LinesKey MENU_TEMPLATES = GROUP.lines("rules.menu.templates",
        "&6Templates", "&7Click to apply a saved template.");
    public static final LinesKey MENU_TEMPLATE = GROUP.lines("rules.menu.template",
        "&f{name}", "&7Click to apply this template.");
    public static final LinesKey MENU_BACK = GROUP.lines("rules.menu.back",
        "&7Back", "&8To the previous page.");
    public static final LinesKey MENU_PAGE = GROUP.lines("rules.menu.page",
        "&7Page {page} of {pages}", "&8Click to turn the page.");

    public static final TextKey PROMPT_GROUP = GROUP.text("rules.prompt.group",
        "&bType the cooldown group name (or \"{cancel}\"):");
    public static final TextKey PROMPT_RULE_ID = GROUP.text("rules.prompt.rule_id",
        "&bType an id for the new rule (or \"{cancel}\"):");
    public static final TextKey PROMPT_LINE = GROUP.text("rules.prompt.line",
        "&bType a rule line, for example kind=PERMISSION;node=group.vip (or \"{cancel}\"):");

    public static final TextKey NOTICE_RULE_ADDED = GROUP.text("rules.notice.rule_added", "Added rule {name}.");
    public static final TextKey NOTICE_RULE_REMOVED = GROUP.text("rules.notice.rule_removed", "Removed rule {name}.");
    public static final TextKey NOTICE_INVALID = GROUP.text("rules.notice.invalid", "That could not be applied: {reason}");
    public static final TextKey NOTICE_APPLIED = GROUP.text("rules.notice.applied", "Applied template {name}.");

    private static final Map<String, TextKey> DENIALS = denials();

    private RulesMessages() {
    }

    public static List<MessageKey> keys() {
        return GROUP.keys();
    }

    /**
     * The denial key a rule outcome names. Unknown ids fall back to the generic refusal rather than failing
     * the traversal, so a document written against a newer build still refuses politely.
     */
    public static TextKey denial(String id) {
        return DENIALS.getOrDefault(id, DENIED_DEFAULT);
    }

    private static Map<String, TextKey> denials() {
        Map<String, TextKey> byId = new LinkedHashMap<>();
        for (MessageKey key : GROUP.keys()) {
            if (key instanceof TextKey text && text.id().startsWith("rules.denied.")) {
                byId.put(text.id(), text);
            }
        }
        return Map.copyOf(byId);
    }
}
