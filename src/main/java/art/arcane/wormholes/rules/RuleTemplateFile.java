package art.arcane.wormholes.rules;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

import java.util.ArrayList;
import java.util.List;

/**
 * The TOML shape of a rule template. The vendored TOML codec handles scalars, string lists and nested section
 * lists, so each condition, cost and effect is one {@code kind=...;field=value} line rather than a table of its
 * own. Reading a template rebuilds the JSON document and runs it through {@link RuleDocumentCodec}, so a
 * hand-edited file is validated exactly like a stored one.
 */
@ConfigDoc({
    "A portal rules template. Apply it with /wormholes rules apply <name> portal=<portal>.",
    "Conditions, costs and effects are semicolon-separated field lines starting with kind=..."
})
public class RuleTemplateFile {
    @ConfigDescription("ALLOW or DENY when no rule matches.")
    public String defaultOutcome = "ALLOW";

    @ConfigDescription("Message id shown when the default outcome denies.")
    public String defaultReason = "";

    @ConfigDescription("Per-portal cooldown in milliseconds.")
    public long cooldownMillis = 0L;

    @ConfigDescription("Cooldown group shared with other portals, empty for none.")
    public String cooldownGroup = "";

    @ConfigDescription("Warmup in milliseconds the traveler must stand still for.")
    public long warmupMillis = 0L;

    @ConfigDescription("Pushback strength applied when a crossing is refused.")
    public double pushbackScale = 1.0D;

    @ConfigDescription("Volume of the refusal sound.")
    public double soundVolume = 1.0D;

    @ConfigDescription("Charge pool size, 0 for an unlimited portal.")
    public int chargeCapacity = 0;

    @ConfigDescription("Charges restored per regeneration interval.")
    public int chargeRegenPerInterval = 0;

    public List<RuleEntry> rules = new ArrayList<>();

    /** One ordered rule. Every condition must match for the rule to decide the crossing. */
    public static class RuleEntry {
        @ConfigDescription("Rule id, unique inside this template.")
        public String id = "";

        @ConfigDescription("ALLOW or DENY when this rule matches.")
        public String outcome = "ALLOW";

        @ConfigDescription("Message id shown when this rule denies.")
        public String reason = "";

        public List<String> conditions = new ArrayList<>();
        public List<String> costs = new ArrayList<>();
        public List<String> effects = new ArrayList<>();
    }
}
