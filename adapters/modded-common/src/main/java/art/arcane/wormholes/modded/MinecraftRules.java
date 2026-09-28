package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import net.minecraft.network.chat.Component;
import java.util.StringJoiner;
import java.util.Locale;
import art.arcane.wormholes.hook.TraversalPhase;
import art.arcane.wormholes.localization.RulesMessages;
import art.arcane.wormholes.rules.ChargePool;
import art.arcane.wormholes.rules.CompiledRules;
import art.arcane.wormholes.rules.PortalCooldowns;
import art.arcane.wormholes.rules.RuleAdmission;
import art.arcane.wormholes.rules.Condition;
import art.arcane.wormholes.rules.TravelerClass;
import art.arcane.wormholes.rules.RuleCostReservation;
import art.arcane.wormholes.rules.RuleCostText;
import art.arcane.wormholes.rules.RuleDocument;
import art.arcane.wormholes.rules.RuleValidationException;
import art.arcane.wormholes.rules.RuleOutcome;
import art.arcane.wormholes.rules.Rule;
import art.arcane.wormholes.rules.Effect;
import art.arcane.wormholes.rules.RuleDocumentCodec;
import art.arcane.wormholes.rules.RuleTraversalLedger;
import art.arcane.wormholes.rules.TraversalProfile;
import art.arcane.wormholes.rules.WarmupTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class MinecraftRules implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final Map<MinecraftServer, MinecraftRules> SERVICES = new HashMap<>();
    private final WormholesModRuntime runtime;
    private final RuleTraversalLedger ledger = new RuleTraversalLedger();
    private final WarmupTracker warmups = new WarmupTracker(new Pinner());
    private final RuleAdmission admission = new RuleAdmission(warmups, ledger);
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, Warming> warming = new HashMap<>();
    private final Map<UUID, Reserved> reserved = new HashMap<>();
    private MinecraftRuleEvaluation.Placeholders placeholders = (player, expression) -> "";

    public MinecraftRules(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime);
    }

    public static MinecraftRules forServer(MinecraftServer server) {
        return SERVICES.get(server);
    }

    public void start() {
        runtime.requireServerThread();
        SERVICES.put(runtime.server(), this);
    }

    public RuleDocument authorDocument(ServerPlayer author, RuleDocument document) {
        List<Rule> rules = new ArrayList<>(document.rules().size());
        for (Rule rule : document.rules()) {
            List<Effect> effects = new ArrayList<>(rule.effects().size());
            for (Effect effect : rule.effects()) {
                if (effect instanceof Effect.Command command) {
                    if (command.asConsole() && (!runtime.access().administrator(author)
                        || !runtime.configuration().settings().getRules().commandEffectsConsoleAllowed)) {
                        throw new IllegalArgumentException("Console command effects require an administrator and enabled console effects");
                    }
                    effects.add(new Effect.Command(command.line(), command.asConsole(), author.getUUID()));
                } else {
                    effects.add(effect);
                }
            }
            rules.add(new Rule(rule.id(), rule.conditions(), rule.outcome(), rule.costs(), effects));
        }
        return document.withRules(rules);
    }

    public void placeholders(MinecraftRuleEvaluation.Placeholders provider) {
        runtime.requireServerThread();
        placeholders = Objects.requireNonNull(provider);
    }

    public RuleDocument document(MinecraftPortal portal) {
        return state(portal).document;
    }

    public int charges(MinecraftPortal portal) {
        State state = state(portal);
        state.charges.canConsume(0, System.currentTimeMillis());
        return state.charges.count();
    }

    public void setDocument(MinecraftPortal portal, RuleDocument replacement) {
        runtime.requireServerThread();
        RuleDocument validated = RuleDocumentCodec.fromJson(RuleDocumentCodec.toJson(replacement),
            runtime.configuration().settings().getRules()).withRevision(document(portal).revision() + 1L);
        State previous = state(portal);
        portal.setRuleDocument(validated);
        previous.charges.reshape(validated.profile(), System.currentTimeMillis());
        persist(portal, previous.charges);
        states.put(portal.getId(), new State(portal.setting("rules.document"), validated,
            CompiledRules.compile(validated), previous.charges));
    }

    public boolean depart(Entity traveler, MinecraftPortal portal, Runnable retry) {
        runtime.requireServerThread();
        if (reserved.containsKey(traveler.getUUID())) {
            return false;
        }
        RuleAdmission.Decision decision = evaluate(traveler, portal, TraversalPhase.DEPART, false);
        if (decision == RuleAdmission.DeferredWarmup.INSTANCE && traveler instanceof ServerPlayer player) {
            Warming previous = warming.get(player.getUUID());
            if (previous == null || !previous.portalId().equals(portal.getId())) {
                warming.put(player.getUUID(), new Warming(player, portal.getId(), player.getHealth(),
                    System.currentTimeMillis() + document(portal).profile().warmupMillis(), Objects.requireNonNull(retry)));
            }
            return false;
        }
        if (decision != RuleAdmission.Allow.INSTANCE) {
            refuse(traveler, portal, decision);
            return false;
        }
        return true;
    }

    public boolean screeningAllowed(Entity traveler, MinecraftPortal portal) {
        return evaluate(traveler, portal, TraversalPhase.DEPART, true) == RuleAdmission.Allow.INSTANCE;
    }

    public boolean arrivalAllowed(Entity traveler, MinecraftPortal portal, boolean screening) {
        return evaluate(traveler, portal, TraversalPhase.ARRIVE, screening) == RuleAdmission.Allow.INSTANCE;
    }

    public void retain(Entity traveler) {
        runtime.requireServerThread();
        long now = System.currentTimeMillis();
        RuleTraversalLedger.Pending pending = ledger.peek(traveler.getUUID());
        if (pending != null) {
            pending.retain(now);
        }
        Reserved reservation = reserved.get(traveler.getUUID());
        if (reservation != null) {
            reserved.put(traveler.getUUID(), new Reserved(reservation.portalId(), reservation.group(), now));
        }
    }

    public boolean reserve(Entity traveler, MinecraftPortal portal) {
        runtime.requireServerThread();
        if (reserved.containsKey(traveler.getUUID())) {
            return false;
        }
        State state = state(portal);
        RuleTraversalLedger.Pending pending = ledger.peek(traveler.getUUID());
        if (pending != null && !pending.portalId().equals(portal.getId())) {
            return false;
        }
        if (pending == null && !state.document.isInert()) {
            MinecraftRuleEvaluation context = context(traveler, portal);
            if (!context.hasPermission(RuleAdmission.BYPASS_PERMISSION)) {
                CompiledRules.Match match = state.compiled.evaluate(context);
                if (!match.outcome().allowed()) {
                    refuse(traveler, portal, new RuleAdmission.DeniedOutcome(match));
                    return false;
                }
                if (!match.costs().isEmpty() || !match.effects().isEmpty()) {
                    if (!(traveler instanceof ServerPlayer)) {
                        return match.costs().isEmpty();
                    }
                    pending = new RuleTraversalLedger.Pending(portal.getId(), match.costs(), match.effects(),
                        state.document.profile().cooldownGroup(), System.currentTimeMillis());
                    ledger.stage(traveler.getUUID(), pending);
                }
            }
        }
        if (pending != null) {
            if (!(traveler instanceof ServerPlayer player)) {
                return false;
            }
            RuleCostReservation payment = pending.reserve(runtime.costs().ruleSubject(player), state.charges,
                System.currentTimeMillis());
            if (!payment.successful()) {
                ledger.discard(traveler.getUUID());
                refuse(traveler, portal, new RuleAdmission.DeniedCost(payment.failedCost()));
                return false;
            }
            pending.dispatch(System.currentTimeMillis());
            if (!state.charges.unlimited()) {
                persist(portal, state.charges);
            }
        }
        TraversalProfile profile = state.document.profile();
        PortalCooldowns.stamp(traveler.getUUID(), portal.getId(), profile.cooldownGroup(), profile.cooldownMillis(),
            System.currentTimeMillis());
        reserved.put(traveler.getUUID(), new Reserved(portal.getId(), profile.cooldownGroup(), System.currentTimeMillis()));
        return true;
    }

    public void arrived(Entity traveler, MinecraftPortal destination) {
        runtime.requireServerThread();
        RuleTraversalLedger.Pending pending = ledger.take(traveler.getUUID());
        reserved.remove(traveler.getUUID());
        warmups.clear(traveler.getUUID());
        warming.remove(traveler.getUUID());
        if (pending != null && pending.dispatched()) {
            pending.commit(System.currentTimeMillis());
            MinecraftPortal source = runtime.portals().get(pending.portalId());
            State state = states.get(pending.portalId());
            if (source != null && state != null && !state.charges.unlimited()) {
                persist(source, state.charges);
            }
            MinecraftRuleEffects.run(runtime, traveler, destination, pending.effects());
        }
    }

    public void dispatched(Entity traveler, MinecraftPortal source) {
        arrived(traveler, source);
    }

    public void failed(Entity traveler) {
        fail(traveler.getUUID());
    }

    public void damaged(ServerPlayer player) {
        if (runtime.configuration().settings().getRules().warmupCancelOnDamage) {
            warmups.cancelOnDamage(player.getUUID(), System.currentTimeMillis());
            warming.remove(player.getUUID());
        }
    }

    public void disconnected(ServerPlayer player) {
        fail(player.getUUID());
        warmups.clear(player.getUUID());
        warming.remove(player.getUUID());
        PortalCooldowns.clear(player.getUUID());
    }

    public void tick() {
        runtime.requireServerThread();
        long now = System.currentTimeMillis();
        for (Warming entry : List.copyOf(warming.values())) {
            ServerPlayer player = entry.player();
            UUID id = player.getUUID();
            MinecraftPortal portal = runtime.portals().get(entry.portalId());
            if (!player.isAlive() || portal == null || !portal.isOpen()) {
                warming.remove(id);
                warmups.clear(id);
                continue;
            }
            boolean moved = warmups.cancelOnMove(id, anchor(player),
                runtime.configuration().settings().getRules().warmupCancelMoveBlocks, now);
            boolean hurt = runtime.configuration().settings().getRules().warmupCancelOnDamage
                && player.getHealth() < entry.health() && warmups.cancelOnDamage(id, now);
            if (moved || hurt) {
                warming.remove(id);
                continue;
            }
            warmups.tick(id, now);
            if (now >= entry.readyAt()) {
                warming.remove(id);
                entry.retry().run();
            } else {
                player.setDeltaMovement(Vec3.ZERO);
            }
        }
        for (Map.Entry<UUID, Reserved> entry : List.copyOf(reserved.entrySet())) {
            if (now - entry.getValue().stamp() > RuleTraversalLedger.TTL_MILLIS) {
                fail(entry.getKey());
            }
        }
        ledger.prune(now);
        states.keySet().removeIf(id -> runtime.portals().get(id) == null);
    }

    @Override
    public void close() {
        runtime.requireServerThread();
        for (UUID id : List.copyOf(reserved.keySet())) {
            fail(id);
        }
        ledger.clear();
        warmups.clear();
        warming.clear();
        states.clear();
        SERVICES.values().removeIf(service -> service == this);
        placeholders = (player, expression) -> "";
    }

    private RuleAdmission.Decision evaluate(Entity traveler, MinecraftPortal portal, TraversalPhase phase, boolean screening) {
        State state = state(portal);
        MinecraftRuleCostSubject subject = traveler instanceof ServerPlayer player ? runtime.costs().ruleSubject(player) : null;
        MinecraftRuleEvaluation context = context(traveler, portal);
        return admission.evaluate(new RuleAdmission.Attempt(portal.getId(), state.document, state.compiled, state.charges,
            context, phase, screening, subject, traveler instanceof ServerPlayer player ? anchor(player) : null));
    }

    private MinecraftRuleEvaluation context(Entity traveler, MinecraftPortal portal) {
        MinecraftRuleCostSubject subject = traveler instanceof ServerPlayer player ? runtime.costs().ruleSubject(player) : null;
        return new MinecraftRuleEvaluation(new MinecraftRuleEvaluation.Options(runtime, portal,
            (ServerLevel) traveler.level(), traveler, System.currentTimeMillis(), runtime.nexus().dialedAddress(portal),
            subject, placeholders));
    }

    private State state(MinecraftPortal portal) {
        Object encoded = portal.setting("rules.document");
        State current = states.get(portal.getId());
        if (current != null && current.encoded == encoded) {
            return current;
        }
        RuleDocument document;
        try {
            document = portal.ruleDocument(runtime.configuration().settings().getRules());
        } catch (IllegalArgumentException | RuleValidationException malformed) {
            LOGGER.error("Could not load traversal rules for portal {}", portal.getId(), malformed);
            document = RuleDocument.EMPTY.withDefaultOutcome(RuleOutcome.deny(RulesMessages.DENIED_DEFAULT.id()));
        }
        ChargePool charges = current == null ? new ChargePool(() ->
            runtime.configuration().settings().getRules().chargesRegenIntervalSeconds * 1000L) : current.charges;
        if (current == null) {
            try {
                Map<String, Object> stored = portal.ruleCharges();
                charges.load(stored.isEmpty() ? null : stored);
            } catch (IllegalArgumentException | RuleValidationException malformed) {
                LOGGER.error("Could not load traversal rule charges for portal {}", portal.getId(), malformed);
                charges.load(Map.of());
                document = RuleDocument.EMPTY.withDefaultOutcome(RuleOutcome.deny(RulesMessages.DENIED_DEFAULT.id()));
            }
        }
        charges.reshape(document.profile(), System.currentTimeMillis());
        State next = new State(encoded, document, CompiledRules.compile(document), charges);
        states.put(portal.getId(), next);
        return next;
    }

    private void fail(UUID id) {
        runtime.requireServerThread();
        RuleTraversalLedger.Pending pending = ledger.peek(id);
        ledger.discard(id);
        Reserved reservation = reserved.remove(id);
        if (reservation != null) {
            PortalCooldowns.stamp(id, reservation.portalId(), reservation.group(), 0L, System.currentTimeMillis());
        }
        UUID portalId = reservation != null ? reservation.portalId() : pending == null ? null : pending.portalId();
        MinecraftPortal portal = portalId == null ? null : runtime.portals().get(portalId);
        State state = portalId == null ? null : states.get(portalId);
        if (portal != null && state != null) {
            persist(portal, state.charges);
        }
    }

    private void persist(MinecraftPortal portal, ChargePool charges) {
        portal.setRuleCharges(charges.unlimited() ? Map.of() : charges.save());
        runtime.portals().save(portal);
    }

    private void refuse(Entity traveler, MinecraftPortal portal, RuleAdmission.Decision decision) {
        String reason = decision instanceof RuleAdmission.DeniedOutcome denied ? denied.match().outcome().reason()
            : decision.getClass().getSimpleName();
        runtime.api().emit(new MinecraftWormholesApi.Event(MinecraftWormholesApi.Kind.HANDOFF_DENIED,
            traveler.getUUID(), portal.getId(), null, reason, null, null));
        MinecraftTraversalCues.reject(runtime, portal, traveler);
        if (!(traveler instanceof ServerPlayer player)) {
            return;
        }
        player.sendSystemMessage(refusalMessage(runtime.localization().snapshot(player), portal, decision), true);
    }

    static Component refusalMessage(LocalizationSnapshot snapshot, MinecraftPortal portal, RuleAdmission.Decision decision) {
        TextKey key = switch (decision) {
            case RuleAdmission.DeniedOutcome denied -> RulesMessages.denial(denied.match().outcome().reason());
            case RuleAdmission.DeniedCharges ignored -> RulesMessages.DENIED_CHARGES;
            case RuleAdmission.DeniedCost ignored -> RulesMessages.DENIED_COST;
            case RuleAdmission.DeniedCooldown ignored -> RulesMessages.DENIED_COOLDOWN;
            case RuleAdmission.CancelledWarmup ignored -> RulesMessages.WARMUP_CANCELLED;
            default -> RulesMessages.DENIED_DEFAULT;
        };
        String amount = decision instanceof RuleAdmission.DeniedCost denied ? RuleCostText.describe(denied.cost()) : "";
        long seconds = decision instanceof RuleAdmission.DeniedCooldown denied ? (denied.remainingMillis() + 999L) / 1000L : 0L;
        Map<String, Object> arguments = new HashMap<>();
        for (String placeholder : key.placeholders()) {
            arguments.put(placeholder, switch (placeholder) {
                case "portal" -> portal.getName();
                case "amount" -> amount;
                case "seconds" -> seconds;
                case "mode" -> decision instanceof RuleAdmission.DeniedOutcome denied ? travelerFilterLabel(denied.match()) : "";
                default -> "";
            });
        }
        return MinecraftMenuText.text(snapshot, key, arguments);
    }

    private static String travelerFilterLabel(CompiledRules.Match match) {
        if (match.rule() == null) {
            return "";
        }
        for (Condition condition : match.rule().conditions()) {
            if (condition instanceof Condition.EntityClass filter) {
                StringJoiner result = new StringJoiner(", ");
                for (TravelerClass kind : filter.classes()) {
                    result.add(kind.name().toLowerCase(Locale.ROOT).replace('_', ' '));
                }
                return result.toString();
            }
        }
        return "";
    }

    private static WarmupTracker.Anchor anchor(ServerPlayer player) {
        return new WarmupTracker.Anchor(player.level().dimension().identifier().toString(), player.getX(), player.getZ());
    }

    private record State(Object encoded, RuleDocument document, CompiledRules compiled, ChargePool charges) { }
    private record Warming(ServerPlayer player, UUID portalId, float health, long readyAt, Runnable retry) { }
    private record Reserved(UUID portalId, String group, long stamp) { }

    private final class Pinner implements WarmupTracker.Pinner {
        @Override
        public void schedule(UUID playerId) { }

        @Override
        public void pin(UUID playerId, long secondsLeft) {
            Warming entry = warming.get(playerId);
            if (entry != null) {
                entry.player().sendSystemMessage(MinecraftMenuText.text(entry.player(), RulesMessages.WARMUP_COUNTDOWN,
                    Map.of("seconds", secondsLeft)), true);
            }
        }

        @Override
        public void cancelled(UUID playerId) {
            Warming entry = warming.get(playerId);
            if (entry != null) {
                entry.player().sendSystemMessage(MinecraftMenuText.text(entry.player(), RulesMessages.WARMUP_CANCELLED, Map.of()), true);
            }
        }
    }
}
