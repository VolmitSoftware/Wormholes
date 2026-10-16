package art.arcane.wormholes.menu.rules;

import java.util.Objects;

import art.arcane.wormholes.config.toml.RulesConfig;
import art.arcane.wormholes.rules.Rule;
import art.arcane.wormholes.rules.RuleDocument;
import art.arcane.wormholes.rules.RuleOutcome;
import art.arcane.wormholes.rules.RulesMenuModel;
import art.arcane.wormholes.rules.TraversalProfile;

public sealed interface RulesEdit permits RulesEdit.Cooldown, RulesEdit.Warmup, RulesEdit.Group, RulesEdit.Charges, RulesEdit.Pushback,
    RulesEdit.Sound, RulesEdit.DefaultOutcome, RulesEdit.AddRule, RulesEdit.RemoveRule, RulesEdit.AddLine, RulesEdit.RemoveLine {
    RuleDocument apply(RuleDocument document, RulesConfig limits);

    record Cooldown(long millis) implements RulesEdit {
        @Override
        public RuleDocument apply(RuleDocument document, RulesConfig limits) {
            TraversalProfile current = document.profile();
            return document.withProfile(new TraversalProfile(millis, current.cooldownGroup(), current.warmupMillis(), current.pushbackScale(),
                current.soundVolume(), current.chargeCapacity(), current.chargeRegenPerInterval()));
        }
    }

    record Warmup(long millis) implements RulesEdit {
        @Override
        public RuleDocument apply(RuleDocument document, RulesConfig limits) {
            TraversalProfile current = document.profile();
            return document.withProfile(new TraversalProfile(current.cooldownMillis(), current.cooldownGroup(), millis, current.pushbackScale(),
                current.soundVolume(), current.chargeCapacity(), current.chargeRegenPerInterval()));
        }
    }

    record Group(String group) implements RulesEdit {
        public Group {
            Objects.requireNonNull(group, "group");
        }

        @Override
        public RuleDocument apply(RuleDocument document, RulesConfig limits) {
            TraversalProfile current = document.profile();
            return document.withProfile(new TraversalProfile(current.cooldownMillis(), group, current.warmupMillis(), current.pushbackScale(),
                current.soundVolume(), current.chargeCapacity(), current.chargeRegenPerInterval()));
        }
    }

    record Charges(int capacity) implements RulesEdit {
        @Override
        public RuleDocument apply(RuleDocument document, RulesConfig limits) {
            TraversalProfile current = document.profile();
            return document.withProfile(new TraversalProfile(current.cooldownMillis(), current.cooldownGroup(), current.warmupMillis(),
                current.pushbackScale(), current.soundVolume(), capacity, Math.min(current.chargeRegenPerInterval(), Math.max(0, capacity))));
        }
    }

    record Pushback(double scale) implements RulesEdit {
        @Override
        public RuleDocument apply(RuleDocument document, RulesConfig limits) {
            TraversalProfile current = document.profile();
            return document.withProfile(new TraversalProfile(current.cooldownMillis(), current.cooldownGroup(), current.warmupMillis(), scale,
                current.soundVolume(), current.chargeCapacity(), current.chargeRegenPerInterval()));
        }
    }

    record Sound(double volume) implements RulesEdit {
        @Override
        public RuleDocument apply(RuleDocument document, RulesConfig limits) {
            TraversalProfile current = document.profile();
            return document.withProfile(new TraversalProfile(current.cooldownMillis(), current.cooldownGroup(), current.warmupMillis(),
                current.pushbackScale(), volume, current.chargeCapacity(), current.chargeRegenPerInterval()));
        }
    }

    record DefaultOutcome(RuleOutcome outcome) implements RulesEdit {
        public DefaultOutcome {
            Objects.requireNonNull(outcome, "outcome");
        }

        @Override
        public RuleDocument apply(RuleDocument document, RulesConfig limits) {
            return document.withDefaultOutcome(outcome);
        }
    }

    record AddRule(String ruleId) implements RulesEdit {
        public AddRule {
            Objects.requireNonNull(ruleId, "ruleId");
        }

        @Override
        public RuleDocument apply(RuleDocument document, RulesConfig limits) {
            return RulesMenuModel.addRule(document, ruleId);
        }
    }

    record RemoveRule(String ruleId) implements RulesEdit {
        public RemoveRule {
            Objects.requireNonNull(ruleId, "ruleId");
        }

        @Override
        public RuleDocument apply(RuleDocument document, RulesConfig limits) {
            return RulesMenuModel.removeRule(document, ruleId);
        }
    }

    record AddLine(String ruleId, String line) implements RulesEdit {
        public AddLine {
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(line, "line");
        }

        @Override
        public RuleDocument apply(RuleDocument document, RulesConfig limits) {
            int index = RulesMenuModel.indexOf(document, ruleId);
            if (index < 0) {
                return document;
            }
            Rule rule = document.rules().get(index);
            return RulesMenuModel.replaceRule(document, RulesMenuModel.addLine(rule, line, limits));
        }
    }

    record RemoveLine(String ruleId, int line) implements RulesEdit {
        public RemoveLine {
            Objects.requireNonNull(ruleId, "ruleId");
        }

        @Override
        public RuleDocument apply(RuleDocument document, RulesConfig limits) {
            int index = RulesMenuModel.indexOf(document, ruleId);
            if (index < 0) {
                return document;
            }
            return RulesMenuModel.replaceRule(document, RulesMenuModel.removeLine(document.rules().get(index), line));
        }
    }
}
