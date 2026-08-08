# API - Traversal Cost & Events

`art.arcane.wormholes.api.traversal` lets another plugin price, charge for, or veto a portal traversal. It depends only on Bukkit types, `java.*`, and its own types (no VolmLib, Adventure, or shaded types on the compile surface). Descriptor and `apiJar` setup: `20 - API - Getting Started.md`.

| Goal | Use |
|------|-----|
| Take value and reverse it if the trip fails | `TraversalCostProvider` (`ServicesManager`) |
| Watch or free-veto | `WormholesPortalTraverseEvent` / `WormholesPortalTraversedEvent` |

Events never move money. Only a registered `TraversalCostProvider` holds value.

## Dependency

Same as `20 - API - Getting Started.md`: soft-depend Wormholes; Paper needs `join-classpath: true`; compile against `Wormholes-*-api.jar` with `compileOnly`.

## Lifecycle

```
quote(context)          side-effect free → PASS | PAYABLE | INSUFFICIENT | DENIED
   |
   |  only if PAYABLE and no prior deny
   v
reserve(context, quote) take value now → receipt
   |
   +--> commit(receipt)                 trip succeeded; value kept (FINAL)
   +--> refund(receipt, reason)         trip did not happen; reverse
```

Guarantees:

- `quote` at most once per provider per traversal; never after another provider denied.
- `reserve` only for `PAYABLE` quotes after every provider quoted without denying.
- **All-or-nothing:** any reserve failure refunds already-reserved providers in reverse order; nobody pays.
- Exactly one of `commit` or `refund` per receipt; first wins.
- **`commit` is final.** Refund after commit is a no-op and never reaches you.
- Unresolved receipts older than 30s refund with `EXPIRED` (sweep at head of next traversal evaluation, at most once per second; idle servers wait until the next attempt or shutdown).
- Shutdown refunds unresolved receipts with `SERVER_SHUTDOWN`.
- One in-flight traversal per player; a second attempt is refused before any provider (`DENIED_IN_PROGRESS`).

## Threading

`quote`, `reserve`, and `commit` run on the portal's region thread. Inventory/XP/location of the traveler are legal there.

`refund` is the same thread except:

- `EXPIRED` — region of whichever portal triggered the next evaluation
- `SERVER_SHUTDOWN` — unload thread

On those two paths the traveler may be on another region or offline: reverse against your own ledger only unless you hop to the player's entity scheduler (and handle refusal).

Do not block any of the four methods. Slow providers get a throttled warning; the decision is not changed.

## Worked example

Receipt is opaque: `TraversalReceipt` has no abstract instance methods. Wormholes stores and returns the same instance and never calls into it.

```java
import art.arcane.wormholes.api.traversal.TraversalContext;
import art.arcane.wormholes.api.traversal.TraversalCostProvider;
import art.arcane.wormholes.api.traversal.TraversalQuote;
import art.arcane.wormholes.api.traversal.TraversalReceipt;
import art.arcane.wormholes.api.traversal.TraversalRefundReason;
import art.arcane.wormholes.api.traversal.TraversalReservation;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

import java.util.Objects;
import java.util.UUID;

interface ManaPool {
    long balance(UUID playerId);
    boolean withdraw(UUID playerId, long amount);
    void deposit(UUID playerId, long amount);
    void recordSpend(UUID playerId, long amount);
}

record ManaReceipt(UUID playerId, long amount) implements TraversalReceipt {
}

public final class ManaTravelCost implements TraversalCostProvider {
    private final ManaPool pool;

    public ManaTravelCost(ManaPool pool) {
        this.pool = Objects.requireNonNull(pool, "pool");
    }

    public static void register(Plugin plugin, ManaPool pool) {
        plugin.getServer().getServicesManager().register(
            TraversalCostProvider.class,
            new ManaTravelCost(pool),
            plugin,
            ServicePriority.Normal
        );
    }

    @Override
    public String providerId() {
        return "example-mana";
    }

    @Override
    public TraversalQuote quote(TraversalContext context) {
        long price = priceOf(context);
        if (price <= 0L) {
            return TraversalQuote.pass();
        }
        if (pool.balance(context.travelerId()) < price) {
            return TraversalQuote.insufficient(price + " Mana").withPrice(price, "Mana");
        }
        return TraversalQuote.payable(price + " Mana").withPrice(price, "Mana");
    }

    @Override
    public TraversalReservation reserve(TraversalContext context, TraversalQuote quote) {
        long price = quote.amount().orElse(0L);
        if (!pool.withdraw(context.travelerId(), price)) {
            return TraversalReservation.failed("Your mana ran out");
        }
        return TraversalReservation.reserved(new ManaReceipt(context.travelerId(), price));
    }

    @Override
    public void commit(TraversalReceipt receipt) {
        if (receipt instanceof ManaReceipt mana) {
            pool.recordSpend(mana.playerId(), mana.amount());
        }
    }

    @Override
    public void refund(TraversalReceipt receipt, TraversalRefundReason reason) {
        if (receipt instanceof ManaReceipt mana) {
            pool.deposit(mana.playerId(), mana.amount());
        }
    }

    private long priceOf(TraversalContext context) {
        return switch (context.kind()) {
            case RANDOM_TELEPORT -> 10L;
            case CROSS_SERVER -> 5L;
            case LOCAL, DIMENSIONAL_DOOR -> 3L;
            default -> 3L;
        };
    }
}
```

Register in `onEnable`:

```java
ManaTravelCost.register(this, manaPool);
```

Providers run highest `ServicePriority` first, then plugin name, then `providerId()`.

`reserve` receives the same `TraversalQuote` instance your `quote` returned (`amount()` / `unit()` available if set via `withPrice`).

### Pure veto

`TraversalCostProvider` is a functional interface; only `quote` is required:

```java
getServer().getServicesManager().register(TraversalCostProvider.class,
    context -> claims.isOwner(context.travelerId(), context.portalId())
        ? TraversalQuote.pass()
        : TraversalQuote.denied("This gate belongs to someone else"),
    this, ServicePriority.Normal);
```

If you charge, implement `providerId()`. Default is the class name (unstable for lambdas); Wormholes logs a warning and still uses the generated name within a run.

## TraversalContext

```java
public record TraversalContext(
    UUID traversalId, TraversalKind kind, Player traveler, UUID portalId,
    String portalName, Location origin, Optional<TraversalDestination> destination)
```

| Field | Meaning |
|-------|---------|
| `traversalId` | Unique per attempt |
| `kind` | `LOCAL`, `CROSS_SERVER`, `RANDOM_TELEPORT`, `DIMENSIONAL_DOOR` |
| `traveler` | Live `Player` |
| `portalId` / `portalName` | Entered portal (door for dimensional door); name sanitised, empty if unnamed |
| `origin` | Entry location; fresh clone every read |
| `destination` | Present when known; empty for RTP at quote time |

`TraversalDestination.sameServer()` is false for cross-server; `serverName()` names the peer; `location()` is empty when remote and returns a defensive clone when present. `TraversalContext.origin()` also returns a defensive clone. Static factories on context/destination exist for Wormholes and unit tests. Only players are gated; minecarts, mobs, and items never reach providers.

`TraversalDecision` is not passed to providers or events. `TraversalReceipt.SimpleReceipt` is only for receipts you create via `TraversalReceipt.of(label)`.

## Events

```java
@EventHandler(ignoreCancelled = true)
public void onTraverse(WormholesPortalTraverseEvent event) {
    if (regions.isProtected(event.getContext().origin())) {
        event.setCancelReason("This portal is inside a protected region");
        event.setCancelled(true);
    }
}

@EventHandler
public void onTraversed(WormholesPortalTraversedEvent event) {
    // post-commit; charged provider ids available
}
```

- `WormholesPortalTraverseEvent`: before any quote; cancel is free; portal region thread; no blocking.
- `WormholesPortalTraversedEvent`: after commit; traveler entity scheduler; dropped with a warning if the scheduler refuses; not a ledger of record.
- Both extend `org.bukkit.event.Event` with their own `HandlerList`. Not async. Not dispatched with zero listeners. Neither fires when `traversal-api-enabled` is false.

## Hostile-provider policy

| Misbehaviour | Response |
|--------------|----------|
| `quote` throws or null | Fault logged (stack if throw); treated as refusal to charge |
| `reserve` throws or null | Reverse-order refund of prior reserves; nobody pays |
| Receipt `toString`/`equals`/`hashCode` throws | Irrelevant — never called |
| `reserve` returns `failed(reason)` | Not a fault; rollback then deny with your reason |
| `commit` throws | Logged; trip not undone |
| `refund` throws | Logged; rollback continues |
| Repeated faults | Quarantine until re-register |
| Slow call | Throttled warning; outcome unchanged |
| Blank/`providerId` throws | Registration ignored |
| Duplicate `providerId` | Higher priority kept |
| Same instance twice | Collapsed to higher priority |
| Nested traversal from inside pipeline | `DENIED_REENTRANT` |
| Your plugin disabled mid-flight | No further quotes; still `refund` held receipts |

`amount()` / `unit()` on quotes are display-only. Third-party text (descriptions, reasons, cancel reasons, receipt labels) is truncated to 128 chars, control characters flattened, whitespace stripped at construction.

`withPrice` rejects negative amounts. `TraversalReservation.reserved` requires a non-null receipt; nullable optionals are normalized to empty by the context and destination records, while the required traversal ID, kind, traveler, portal ID, and origin reject null.

### Configuration (`config/wormholes.toml` `[main]`)

| Key | Default | Meaning |
|-----|---------|---------|
| `traversal-api-enabled` | `true` | Master switch; false → no providers, no events |
| `traversal-api-provider-failure-policy` | `allow` | `allow` = fault → free pass; `deny` = fault closes portal |
| `traversal-api-provider-fault-limit` | `5` | Quarantine on Nth fault; `0` disables; clamped 0–1000 |
| `traversal-api-slow-provider-millis` | `5` | Warn threshold; `0` disables; clamped 0–60000 |

Default is fail-open on faults only. Deliberate `DENIED` / `INSUFFICIENT` always deny. Quarantine is in-memory per registration; unregister/re-register clears it; nothing persists across restart.

## Enums (always use `default` in switch expressions)

`TraversalKind`: `LOCAL`, `CROSS_SERVER`, `RANDOM_TELEPORT`, `DIMENSIONAL_DOOR`.

`TraversalQuoteStatus`: `PASS`, `PAYABLE`, `INSUFFICIENT`, `DENIED` (no `FREE`).

`TraversalReservationStatus`: `RESERVED`, `FAILED` (use factories; `RESERVED` without receipt throws).

`TraversalRefundReason` (all reach `refund`):

| Constant | Meaning |
|----------|---------|
| `TRAVERSAL_ABORTED` | Abandoned / fallback |
| `DESTINATION_REJECTED` | Far side refused |
| `DESTINATION_UNAVAILABLE` | Nowhere to arrive |
| `TELEPORT_FAILED` | Move failed |
| `TIMED_OUT` | Did not complete in time |
| `TRAVELER_RETREATED` | Stepped back before commit |
| `TRAVELER_LEFT` | Disconnected |
| `RATE_LIMITED` | Throttled |
| `CHARGE_ROLLBACK` | Another provider failed reserve |
| `EXPIRED` | 30s reclaim |
| `SERVER_SHUTDOWN` | Unload |

`TraversalOutcome` (`allowed()` true for the four allow cases):

| Constant | allowed | Meaning |
|----------|---------|---------|
| `DISABLED` | true | API off; no provider ran |
| `ALLOWED_FREE` | true | Nobody charged |
| `ALLOWED_CHARGED` | true | At least one reserved |
| `ALLOWED_PROVIDER_FAILED` | true | Fault under `allow` policy |
| `DENIED_BY_LISTENER` | false | Event cancelled |
| `DENIED_BY_PROVIDER` | false | `DENIED` quote |
| `DENIED_INSUFFICIENT` | false | `INSUFFICIENT` or failed reserve |
| `DENIED_PROVIDER_FAILED` | false | Fault under `deny` policy |
| `DENIED_IN_PROGRESS` | false | Traveler already in flight |
| `DENIED_REENTRANT` | false | Nested pipeline attempt |

`WormholesPortalTraversedEvent.getOutcome()` is only `ALLOWED_FREE`, `ALLOWED_CHARGED`, or `ALLOWED_PROVIDER_FAILED`. **No denial event.** Observe denials from your `quote` and from `WormholesPortalTraverseEvent`.
