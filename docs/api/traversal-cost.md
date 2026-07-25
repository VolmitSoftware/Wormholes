# Wormholes traversal cost & permission API

`art.arcane.wormholes.api.traversal` lets another plugin **price**, **charge for**, or **veto** a portal
traversal. It is built from Bukkit types, `java.*` types and its own types only — no VolmLib, no Adventure,
no shaded types — so it links against a plain Spigot or Paper compile classpath.

There are two entry points, and they are not interchangeable:

| You want to…                                   | Use                                          |
|------------------------------------------------|----------------------------------------------|
| take something from the player, and give it back if the trip fails | `TraversalCostProvider` (ServicesManager) |
| watch traversals, or veto them for free        | `WormholesPortalTraverseEvent` / `WormholesPortalTraversedEvent` |

**Events are never the transport for money.** A cancelled event costs nothing and refunds nothing. Only a
registered `TraversalCostProvider` ever holds value.

---

## Depending on Wormholes

Bukkit plugin (`plugin.yml`):

```yaml
softdepend: [Wormholes]
```

Paper plugin (`paper-plugin.yml`):

```yaml
dependencies:
  server:
    Wormholes:
      load: BEFORE
      required: false
      join-classpath: true
```

`join-classpath: true` is mandatory on Paper — plugin classloaders are isolated, and without it you get
`NoClassDefFoundError` on `art.arcane.wormholes.api.traversal.*` even though the classes ship unrelocated.

---

## The lifecycle

```
quote(context)          side-effect free. Say FREE, PAYABLE, INSUFFICIENT or DENIED.
   |
   |  (only if you said PAYABLE, and only if nobody denied)
   v
reserve(context, quote) take the value now. Return a receipt.
   |
   +--> commit(receipt)                    the traversal happened. Value is yours. FINAL.
   +--> refund(receipt, reason)            the traversal did not happen. Give it back.
```

Rules Wormholes guarantees:

- `quote` is called at most **once** per provider per traversal, and never after another provider denied.
- `reserve` is called **only** for a `PAYABLE` quote, and only after every provider has quoted without denying.
- **All-or-nothing.** If any provider fails to reserve, every provider that already reserved is refunded in
  strict reverse order, and nobody pays.
- Exactly **one** of `commit` or `refund` is called for each receipt. Whichever arrives first wins.
- **`commit` is final.** A refund attempted after commit is a no-op and never reaches you.
- A receipt you never see resolved is refunded with `EXPIRED` once it is 30 seconds old. The reclaim is a
  backstop, not a timer: it runs when Wormholes next evaluates a traversal or prunes on its slow timer, so on
  a completely idle server the refund waits until something else happens, or until shutdown.
- On plugin shutdown, every unresolved receipt is refunded with `SERVER_SHUTDOWN`.
- A player has at most **one** traversal in flight at a time. A second attempt while the first is unresolved
  is refused outright and never reaches a provider, so you cannot be asked to charge the same player twice
  for overlapping trips.

## Threading

Every call — `quote`, `reserve`, `commit`, `refund` — runs on the region thread that owns the portal.
Reading and mutating the traveling player's inventory, experience and location is legal there.

**Do not block.** No I/O, no `CompletableFuture.join`, no `callSyncMethod`, no locks held across the call.
Wormholes ticks every portal in that region from this thread; a provider that blocks stalls them all. Slow
providers are logged with a warning naming your plugin, but the warning never changes the decision — a
provider that hangs cannot be interrupted, so the contract is the only protection.

If you need remote data, cache it (prime it on `PlayerJoinEvent`).

---

## Worked example: charging from your own resource pool

A plugin with its own "Mana" pool. It charges 3 Mana for a local hop, 10 for a random teleport, and refuses
travel entirely when the player has none.

### The receipt

The receipt is yours and it is **opaque**. `TraversalReceipt` declares no methods: Wormholes stores the
object verbatim, never calls anything on it — not even `toString()` — and hands the exact same instance back
to `commit` or `refund`. Put whatever you need to reverse the charge in it.

```java
package com.example.mana;

import art.arcane.wormholes.api.traversal.TraversalReceipt;
import java.util.UUID;

public record ManaReceipt(UUID playerId, int amount) implements TraversalReceipt {
}
```

Wormholes pairs every receipt with the provider that returned it, so a receipt never has to identify itself
and can never be attributed to anyone else. `TraversalReceipt.of("label")` is there for providers that need
no state — the label is for your own logs, and Wormholes never reads it.

### The provider

```java
package com.example.mana;

import art.arcane.wormholes.api.traversal.TraversalContext;
import art.arcane.wormholes.api.traversal.TraversalCostProvider;
import art.arcane.wormholes.api.traversal.TraversalKind;
import art.arcane.wormholes.api.traversal.TraversalQuote;
import art.arcane.wormholes.api.traversal.TraversalReceipt;
import art.arcane.wormholes.api.traversal.TraversalRefundReason;
import art.arcane.wormholes.api.traversal.TraversalReservation;
import java.util.UUID;

public final class ManaTravelCost implements TraversalCostProvider {
    public static final String ID = "example-mana";

    private final ManaPool pool;

    public ManaTravelCost(ManaPool pool) {
        this.pool = pool;
    }

    @Override
    public String providerId() {
        return ID;
    }

    @Override
    public TraversalQuote quote(TraversalContext context) {
        if (!pool.isAttuned(context.travelerId())) {
            return TraversalQuote.denied("You are not attuned to the ley lines");
        }

        int price = priceOf(context);

        if (price <= 0) {
            return TraversalQuote.pass();
        }

        if (pool.balance(context.travelerId()) < price) {
            return TraversalQuote.insufficient(price + " Mana").withPrice(price, "Mana");
        }

        return TraversalQuote.payable(price + " Mana").withPrice(price, "Mana");
    }

    @Override
    public TraversalReservation reserve(TraversalContext context, TraversalQuote quote) {
        UUID playerId = context.travelerId();
        int price = priceOf(context);

        if (!pool.withdraw(playerId, price)) {
            return TraversalReservation.failed("Your mana ran out");
        }

        return TraversalReservation.reserved(new ManaReceipt(playerId, price));
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

    private int priceOf(TraversalContext context) {
        return switch (context.kind()) {
            case RANDOM_TELEPORT -> 10;
            case CROSS_SERVER -> 5;
            case LOCAL, DIMENSIONAL_DOOR -> 3;
        };
    }
}
```

### Registration

Register in `onEnable`. Bukkit unregisters you automatically when your plugin disables.

```java
@Override
public void onEnable() {
    getServer().getServicesManager().register(
        TraversalCostProvider.class, new ManaTravelCost(pool), this, ServicePriority.Normal);
}
```

Providers run in `ServicePriority` order, highest first, tie-broken by plugin name then `providerId()` — so
the order is deterministic and survives a restart.

---

## The minimum: a pure veto

If you only want to allow or refuse, `quote` is the **only** method you have to write. Everything else has a
default. `TraversalCostProvider` is a functional interface:

```java
getServer().getServicesManager().register(TraversalCostProvider.class,
    context -> claims.isOwner(context.travelerId(), context.portalId())
        ? TraversalQuote.pass()
        : TraversalQuote.denied("This gate belongs to someone else"),
    this, ServicePriority.Normal);
```

If you charge, implement `providerId()` too. The default is your class name, which for a lambda is a
generated name like `com.example.Claims$$Lambda/0x00007f2a…` that changes on every restart. Wormholes logs
one warning naming your plugin when it sees that, and keeps using the generated name — it is unique within a
run, so quarantine and dedupe still work, but it is not a name an admin can recognise.

---

## What the context tells you

```java
public record TraversalContext(
    UUID traversalId,                        // unique per attempt; the key for this whole transaction
    TraversalKind kind,                      // LOCAL | CROSS_SERVER | RANDOM_TELEPORT | DIMENSIONAL_DOOR
    Player traveler,
    UUID portalId,
    String portalName,
    Location origin,                         // defensive copy on every read
    Optional<TraversalDestination> destination)
```

`destination` is present when Wormholes knows it. It is empty for a random teleport (no destination exists
at quote time). `TraversalDestination.sameServer()` is false for a cross-server hop, where `serverName()`
names the peer and `location()` is empty.

Only players are gated. Minecarts, mobs and dropped items never reach a provider.

---

## Observing and vetoing with events

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
    log.info(event.getContext().traveler().getName() + " paid "
        + event.getChargedProviderIds() + " to use " + event.getContext().portalName());
}
```

`WormholesPortalTraverseEvent` fires **before** any provider is quoted, so a cancel costs nothing.
`WormholesPortalTraversedEvent` fires after the traversal is committed, on the traveler's entity scheduler.
Each event has its own `HandlerList`; there is no shared base class.

---

## Hostile-provider policy

Wormholes assumes a provider will throw, return null, hand back somebody else's receipt, or fail to refund.

| Misbehaviour                          | What Wormholes does                                                    |
|---------------------------------------|------------------------------------------------------------------------|
| `quote` throws or returns null        | Counted as a fault, logged with the stack trace, treated as a refusal to charge |
| `reserve` throws or returns null      | Everything already reserved is refunded in reverse order; nobody pays  |
| A receipt that throws from `toString`/`equals`/`hashCode` | Nothing. Wormholes never calls a receipt |
| `reserve` returns `failed(reason)`    | Not a fault — a deliberate late refusal. Rollback, then deny with your reason |
| `commit` throws                       | Logged and counted. The traversal already happened; it is not undone   |
| `refund` throws                       | Logged and counted, and the rollback loop **continues** to the next receipt |
| Repeated faults                       | Provider is quarantined with one log line naming your plugin, and skipped until it re-registers |
| Slow call                             | Throttled warning naming your plugin. Never changes the outcome        |
| `providerId()` throws or is blank     | The registration is ignored with a warning                             |
| Two providers claim one `providerId`  | The higher-priority one is kept, the other is ignored with a warning   |

No value ever moves through this API. `TraversalQuote` carries an **optional** `amount()` / `unit()` that
exists so Wormholes can show a price in its own voice; it is display-only, refused at construction if it is
negative, and never reconstructs, inspects or arithmetics what you actually charge. You own the movement of
value end to end, and a quote without `withPrice(…)` is perfectly valid.

All third-party text — quote descriptions, refusal reasons, event cancel reasons — is trimmed of control
characters and truncated to 128 characters before Wormholes shows it to anyone.

### Configuration

`plugins/Wormholes/config/wormholes.toml`, `[main]`:

| Key                                    | Default | Meaning                                                            |
|----------------------------------------|---------|--------------------------------------------------------------------|
| `traversal-api-enabled`                | `true`  | Master switch. When false, no provider is called and neither event fires |
| `traversal-api-provider-failure-policy` | `allow` | `allow`: a faulting provider is treated as a refusal to charge and the traversal proceeds free. `deny`: a faulting provider closes the portal |
| `traversal-api-provider-fault-limit`   | `5`     | Faults before a provider is quarantined. `0` disables quarantine    |
| `traversal-api-slow-provider-millis`   | `5`     | Warn when one provider call takes at least this long. `0` disables  |

The default is **fail-open** on purpose. A third-party bug making every portal on the server free is
recoverable and loudly logged; a third-party bug making every portal on the server unusable is not. Admins
who need hard gating set `traversal-api-provider-failure-policy = "deny"`.

A deliberate `DENIED` or `INSUFFICIENT` quote always denies, whatever this setting says. The policy governs
**faults only**.

---

## Switching over the enums

`TraversalOutcome`, `TraversalRefundReason`, `TraversalKind`, `TraversalQuoteStatus` and
`TraversalReservationStatus` may gain constants in a future release. A `switch` **expression** over them is
exhaustive, so it stops compiling — and throws `IncompatibleClassChangeError` on an already-compiled jar —
the moment one is added.

**Always write a `default` arm** in third-party code:

```java
String message = switch (event.getOutcome()) {
    case ALLOWED_CHARGED -> "paid";
    case DENIED_INSUFFICIENT -> "too poor";
    default -> "";
};
```

`TraversalOutcome.allowed()` answers the only question most consumers actually have, without a switch.
