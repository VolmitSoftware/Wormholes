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

To compile, put the API-only artifact on your compile classpath rather than the whole plugin jar — see
[README.md](README.md) for how to obtain it and for the other integration surfaces.

---

## The lifecycle

```
quote(context)          side-effect free. Say PASS, PAYABLE, INSUFFICIENT or DENIED.
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
  backstop, not a timer: the sweep runs at the head of the next traversal evaluation, and at most once per
  second. On a server where nobody else uses a portal, the refund waits until somebody does, or until
  shutdown.
- On plugin shutdown, every unresolved receipt is refunded with `SERVER_SHUTDOWN`.
- A player has at most **one** traversal in flight at a time. A second attempt while the first is unresolved
  is refused outright and never reaches a provider, so you cannot be asked to charge the same player twice
  for overlapping trips.

## Threading

`quote`, `reserve` and `commit` run inline on the region thread that owns the portal being used. Reading and
mutating the traveling player's inventory, experience and location is legal there.

`refund` runs on that same thread for every reason **except two**:

- `EXPIRED`. The reclaim of an unresolved receipt happens at the head of the next traversal evaluation, so
  the call arrives on the region thread of whatever portal somebody used next — not the one you were quoted
  for.
- `SERVER_SHUTDOWN`. Wormholes refunds from its own unload path, on whichever thread is disabling the plugin.

On both of those paths the traveler may be owned by a different region thread, or may not be on the server at
all. Reverse the charge against your own state and nothing else: no inventory writes, no teleports, no entity,
block or chunk access. If a refund genuinely has to touch the player, hop to that player's entity scheduler
and handle the hop being refused.

**Do not block**, in any of the four. No I/O, no `CompletableFuture.join`, no `callSyncMethod`, no locks held
across the call. Wormholes ticks every portal in that region from this thread; a provider that blocks stalls
them all. Slow providers are logged with a warning naming your plugin, but the warning never changes the
decision — a provider that hangs cannot be interrupted, so the contract is the only protection.

If you need remote data, cache it (prime it on `PlayerJoinEvent`).

---

## Worked example: charging from your own resource pool

A plugin with its own "Mana" pool. It charges 3 Mana for a local hop, 10 for a random teleport, and refuses
travel entirely when the player has none.

### The receipt

The receipt is yours and it is **opaque**. `TraversalReceipt` declares no instance methods: Wormholes stores
the object verbatim, never calls anything on it — not even `toString()` — and hands the exact same instance
back to `commit` or `refund`. Put whatever you need to reverse the charge in it.

```java
package com.example.mana;

import art.arcane.wormholes.api.traversal.TraversalReceipt;
import java.util.UUID;

public record ManaReceipt(UUID playerId, int amount) implements TraversalReceipt {
}
```

Wormholes pairs every receipt with the provider that returned it, so a receipt never has to identify itself
and can never be attributed to anyone else. `TraversalReceipt.of("label")` is there for providers that need
no state — the label is for your own logs, and Wormholes never reads it. The factory does sanitise it on the
way in, so `label()` is not necessarily the string you passed.

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
            default -> 3;
        };
    }
}
```

The `default` arm in `priceOf` is not padding. `TraversalKind` may gain a constant, and an exhaustive `switch`
expression compiled against today's jar throws `IncompatibleClassChangeError` the moment it does — see
[Switching over the enums](#switching-over-the-enums).

`reserve` is handed the exact `TraversalQuote` instance your `quote` returned, so a provider that would rather
not recompute can read `amount()` and `unit()` straight back off it. `quote.payable()` and
`reservation.reserved()` are shorthands for the status comparison.

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
public record TraversalContext(UUID traversalId, TraversalKind kind, Player traveler, UUID portalId,
                               String portalName, Location origin, Optional<TraversalDestination> destination)
```

| Component     | What it is                                                                        |
|---------------|------------------------------------------------------------------------------------|
| `traversalId` | Unique per attempt. The key for this whole transaction                              |
| `kind`        | `LOCAL`, `CROSS_SERVER`, `RANDOM_TELEPORT` or `DIMENSIONAL_DOOR`                     |
| `traveler`    | The live `Player`                                                                    |
| `portalId`    | The portal being entered; the door, for a dimensional door                           |
| `portalName`  | That portal's name, sanitised. Empty for an unnamed portal                           |
| `origin`      | Where the traveler is entering from. A fresh clone on every read                     |
| `destination` | Present when Wormholes knows it                                                      |

`destination` is empty for a random teleport, because no destination exists at quote time.
`TraversalDestination.sameServer()` is false for a cross-server hop, where `serverName()` names the peer and
`location()` is empty.

`origin()` and `TraversalDestination.location()` both return a fresh clone on every call, so mutating what
you get back changes nothing. `travelerId()` is the shorthand for `traveler().getUniqueId()`.
`TraversalDestination.portalName()` is sanitised by the same rule that governs your own text;
`serverName()` is only trimmed of surrounding whitespace.

The static factories — `TraversalContext.local`, `crossServer`, `randomTeleport` and `dimensionalDoor`, and
`TraversalDestination.portal` and `remotePortal` — exist so Wormholes can build these records and so you can
build one in your own unit tests. Nothing else calls them.

Only players are gated. Minecarts, mobs and dropped items never reach a provider.

### Two public types you will never be handed

`TraversalDecision` is the verdict Wormholes produces for itself: the traversal id, the outcome, a reason and
the provider that caused it. It is not passed to a provider and it is not carried on an event. Read it as
documentation of what the outcomes mean, not as something to consume.

`TraversalReceipt.SimpleReceipt` is the record behind `TraversalReceipt.of(label)`. Match on it only if you
created it; a `SimpleReceipt` you did not create belongs to another provider and will never reach you.

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

`WormholesPortalTraverseEvent` fires **before** any provider is quoted, so a cancel costs nothing. It is a
synchronous event delivered inline on the region thread that owns the portal, and the same no-blocking rule
that governs providers governs your handler.

`WormholesPortalTraversedEvent` fires after the traversal is committed, on the traveler's entity scheduler.
If that scheduler refuses the task — the traveler is gone, the server is stopping — the event is dropped and
a warning is logged. Do not use it as a ledger of record; the charged providers already committed.

Each event extends `org.bukkit.event.Event` directly and owns its own `HandlerList`; there is no shared
Wormholes base class, and neither event is constructed as asynchronous. Neither event is dispatched when no
listener is registered for it, and neither fires at all when `traversal-api-enabled` is false.

---

## Hostile-provider policy

Wormholes assumes a provider will throw, return null, hand back somebody else's receipt, or fail to refund.

| Misbehaviour                          | What Wormholes does                                                    |
|---------------------------------------|------------------------------------------------------------------------|
| `quote` throws or returns null        | Counted as a fault and logged — with the stack trace when it threw — and treated as a refusal to charge |
| `reserve` throws or returns null      | Everything already reserved is refunded in reverse order; nobody pays  |
| A receipt that throws from `toString`/`equals`/`hashCode` | Nothing. Wormholes never calls a receipt |
| `reserve` returns `failed(reason)`    | Not a fault — a deliberate late refusal. Rollback, then deny with your reason |
| `commit` throws                       | Logged and counted. The traversal already happened; it is not undone   |
| `refund` throws                       | Logged and counted, and the rollback loop **continues** to the next receipt |
| Repeated faults                       | Provider is quarantined with one log line naming your plugin, and skipped until it re-registers |
| Slow call                             | Throttled warning naming your plugin, at most one a minute per provider. Never changes the outcome |
| `providerId()` throws or is blank     | The registration is ignored with a warning                             |
| Two providers claim one `providerId`  | The higher-priority one is kept, the other is ignored with a warning   |
| The same provider instance registered twice | Collapsed silently to the higher-priority registration. Your `quote` is called once, not twice |
| A provider starts another traversal from inside `quote`, `reserve`, or an `EXPIRED` or `CHARGE_ROLLBACK` `refund` | The nested attempt is refused with `DENIED_REENTRANT` before it reaches any provider, and logged. The guard is per thread and spans the whole evaluation, including the expiry sweep that runs at its head |
| Your plugin is disabled mid-traversal | Wormholes stops quoting you immediately, but still calls `refund` for receipts you already hold, and logs that it is refunding through a disabled plugin |

No value ever moves through this API. `TraversalQuote` carries an **optional** `amount()` / `unit()` that
exists so Wormholes can show a price in its own voice; it is display-only, refused at construction if it is
negative, and never reconstructs, inspects or arithmetics what you actually charge. You own the movement of
value end to end, and a quote without `withPrice(…)` is perfectly valid.

All third-party text — quote descriptions, refusal reasons, event cancel reasons, and the label handed to
`TraversalReceipt.of` — is truncated to 128 characters, has its control characters flattened to spaces, and is
stripped of surrounding whitespace. The sanitising happens where the value is constructed, so reading the
field back gives you the sanitised form, not the original.

### Configuration

`plugins/Wormholes/config/wormholes.toml`, `[main]`:

| Key                                    | Default | Meaning                                                            |
|----------------------------------------|---------|--------------------------------------------------------------------|
| `traversal-api-enabled`                | `true`  | Master switch. When false, no provider is called and neither event fires |
| `traversal-api-provider-failure-policy` | `allow` | `allow`: a faulting provider is treated as a refusal to charge and the traversal proceeds free. `deny`: a faulting provider closes the portal |
| `traversal-api-provider-fault-limit`   | `5`     | Quarantine trips on the Nth fault, so the default tolerates four. Clamped to `0`–`1000`; `0` disables quarantine |
| `traversal-api-slow-provider-millis`   | `5`     | Warn when one provider call takes at least this long. Clamped to `0`–`60000`; `0` disables |

The default is **fail-open** on purpose. A third-party bug making every portal on the server free is
recoverable and loudly logged; a third-party bug making every portal on the server unusable is not. Admins
who need hard gating set `traversal-api-provider-failure-policy = "deny"`.

A deliberate `DENIED` or `INSUFFICIENT` quote always denies, whatever this setting says. The policy governs
**faults only**.

A quarantine is tied to the registration, not to the server run. The fault count and the quarantine flag are
both dropped as soon as that `providerId` stops appearing in the `ServicesManager` registrations, so
unregistering and registering again — a plugin reload is enough — clears the record. Nothing is persisted
across a restart, and there is no command to lift a quarantine.

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
    case ALLOWED_FREE -> "free";
    default -> "";
};
```

`TraversalOutcome.allowed()` answers the only question most consumers actually have, without a switch.

### The constants as they stand

`TraversalKind` — what sort of trip this is. It is the only enum you are expected to switch on for pricing.

| Constant           | Meaning                                                                 |
|--------------------|-------------------------------------------------------------------------|
| `LOCAL`            | Portal to portal on this server                                          |
| `CROSS_SERVER`     | Portal to a portal on a peer server                                      |
| `RANDOM_TELEPORT`  | An RTP portal. `destination` is empty at quote time                      |
| `DIMENSIONAL_DOOR` | A dimensional door. `portalId` and `portalName` describe the door        |

`TraversalQuoteStatus` — what your `quote` said. `PASS` is the free answer; there is no `FREE`.

| Constant       | Meaning                                                                       |
|----------------|-------------------------------------------------------------------------------|
| `PASS`         | Nothing to charge. `reserve` is not called                                     |
| `PAYABLE`      | You will charge if everyone else agrees. `reserve` follows                     |
| `INSUFFICIENT` | The traveler cannot afford it. Denies with `DENIED_INSUFFICIENT`               |
| `DENIED`       | Refused outright. Denies with `DENIED_BY_PROVIDER`                             |

`TraversalReservationStatus` — `RESERVED` and `FAILED`. Use `TraversalReservation.reserved(receipt)` and
`TraversalReservation.failed(reason)`; constructing a `RESERVED` reservation without a receipt throws
`IllegalArgumentException`.

`TraversalRefundReason` — why a receipt is being reversed. All eleven arrive at `refund`.

| Constant                  | Meaning                                                             |
|---------------------------|---------------------------------------------------------------------|
| `TRAVERSAL_ABORTED`       | The trip was abandoned; also the fallback when no reason was given   |
| `DESTINATION_REJECTED`    | The far side refused the traveler                                    |
| `DESTINATION_UNAVAILABLE` | There was nowhere to arrive                                          |
| `TELEPORT_FAILED`         | The move itself failed                                               |
| `TIMED_OUT`               | The traversal did not complete in time                               |
| `TRAVELER_RETREATED`      | The traveler stepped back out before committing                      |
| `TRAVELER_LEFT`           | The traveler disconnected                                            |
| `RATE_LIMITED`            | The traversal was throttled                                          |
| `CHARGE_ROLLBACK`         | Another provider failed to reserve; nobody pays                      |
| `EXPIRED`                 | The receipt outlived its 30-second ticket and was reclaimed          |
| `SERVER_SHUTDOWN`         | Wormholes is unloading                                               |

`TraversalOutcome` — the final verdict, carried on `WormholesPortalTraversedEvent`. Four allow, six deny.

| Constant                   | `allowed()` | Meaning                                                       |
|----------------------------|-------------|----------------------------------------------------------------|
| `DISABLED`                 | `true`      | `traversal-api-enabled` is false; no provider ran               |
| `ALLOWED_FREE`             | `true`      | Nobody charged                                                  |
| `ALLOWED_CHARGED`          | `true`      | At least one provider reserved                                  |
| `ALLOWED_PROVIDER_FAILED`  | `true`      | Somebody faulted and the policy is `allow`                      |
| `DENIED_BY_LISTENER`       | `false`     | `WormholesPortalTraverseEvent` was cancelled                    |
| `DENIED_BY_PROVIDER`       | `false`     | A provider returned `DENIED`                                    |
| `DENIED_INSUFFICIENT`      | `false`     | A provider returned `INSUFFICIENT`, or failed to reserve        |
| `DENIED_PROVIDER_FAILED`   | `false`     | Somebody faulted and the policy is `deny`                       |
| `DENIED_IN_PROGRESS`       | `false`     | That traveler already has a traversal in flight                 |
| `DENIED_REENTRANT`         | `false`     | A traversal was started from inside the traversal pipeline      |

`WormholesPortalTraversedEvent` only ever fires for a traversal that reached commit, so its `getOutcome()` is
always `ALLOWED_FREE`, `ALLOWED_CHARGED` or `ALLOWED_PROVIDER_FAILED`. **There is no event for a denial.**
The deny-side constants exist for logs and for future use; if you need to observe refusals, observe them from
your own `quote` and from `WormholesPortalTraverseEvent`, which is the only place a cancel is visible.
