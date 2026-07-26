# Wormholes placeholders

Wormholes ships a PlaceholderAPI expansion under the identifier `wormholes`. It answers two different kinds
of question. **Server keys** describe the whole plugin: how many portals exist, how many projections are
running, whether the cross-server link is healthy. **Player keys** describe one portal — the one that
particular player is standing in front of — its name, whether it is open, where it goes, how far away it is,
and if it is a random-teleport portal, what that portal is currently doing.

Every value is a plain string. There is no compile dependency, no service to acquire and no Wormholes type to
name: if you can call `PlaceholderAPI.setPlaceholders`, or you are writing a scoreboard config in somebody
else's plugin, you can read all of this.

Two things are worth reading before you use the player keys:
[the selection rule](#which-portal-do-the-player-keys-describe), and
[what "fresh" means here](#where-the-answers-come-from). The player keys are answered from a snapshot the
plugin publishes on its own schedule, not computed when you ask.

---

## Depending on Wormholes

Nothing. That is the point of this surface.

Your plugin needs PlaceholderAPI, which you almost certainly already declare:

```yaml
softdepend: [PlaceholderAPI]
```

You do not need to soft-depend on Wormholes, you do not need `join-classpath`, and you must not put the
Wormholes API jar on your classpath for this. When Wormholes is absent the keys simply do not resolve, and
PlaceholderAPI leaves the text alone.

---

## The lifecycle, in order

1. Wormholes enables. If PlaceholderAPI is **already enabled at that moment**, Wormholes builds the expansion
   and registers it, and logs `PlaceholderAPI expansion registered as %wormholes_*%`.
2. Every key immediately answers something. Server keys answer `---` until the first publish; player keys
   answer `---` for everybody.
3. The plugin's portal attendance pass runs, and publishes a server snapshot plus one portal snapshot per
   player near a portal. From here on the values are live.
4. Wormholes disables, hot-reloads or unloads. The expansion is unregistered, every player snapshot is
   dropped, and the server snapshot is cleared. Keys stop resolving and PlaceholderAPI leaves your text
   unreplaced.

The expansion sets `persist()`, so `/papi reload` does not disturb it and Wormholes does not re-register.

**Limitation, stated plainly:** registration is attempted exactly once, during Wormholes' own enable. If
PlaceholderAPI is not enabled at that instant, the expansion never appears and there is no retry. Both plugin
descriptors declare PlaceholderAPI as a soft dependency loaded first, so the normal startup path is fine — but
if PlaceholderAPI is installed onto a running server, or enabled by a plugin manager after Wormholes, you must
reload Wormholes to get the placeholders back.

---

## Where the answers come from

Neither kind of key is computed when you resolve it. Both read a snapshot that Wormholes publishes from its
**portal attendance pass** — the same pass that decides which portals are close enough to a player to be worth
ticking at all.

The attendance pass walks every local portal against every tracked player position and marks the portals that
have somebody near them. On every fourth pass it also builds the placeholder snapshots and publishes them.
The portal update driver re-arms itself roughly once a tick, attendance runs every fifth driver pass, and
placeholders are published every fourth attendance pass, so **in practice values are republished about once a
second**, and they are exactly as late as the server is behind.

This matters in three ways.

**Resolving a placeholder is cheap and never blocks.** It is a hash lookup and a field read of an immutable
record. You can resolve a hundred of them in a scoreboard refresh without touching the portal registry, the
network layer or a single chunk.

**A value can be up to about a second stale, and more on a loaded server.** A player who sprints past a portal
may briefly show the previous portal's name. A portal that just closed may still read `open` for one refresh.
If you are driving a per-tick HUD, resolve at your own cadence and accept that the underlying data changes at
about 1 Hz; resolving faster than that gets you the same string back.

**A player who has never been seen has no snapshot.** Player positions are recorded on join, on move and on
world change, and forgotten on quit. If Wormholes is reloaded under a standing player who then does not move,
that player has no recorded position and every player key answers `---` until they take a step.

There is no way to force a publish, and no placeholder that reports the snapshot's age.

---

## Threading

`PlaceholderAPI.setPlaceholders` may be called from **any thread** for the `wormholes` keys, and this one is
a genuine exception to the usual Folia rule rather than a shrug.

The justification: every resolver reads either a `volatile` reference to an immutable record, or a
`ConcurrentHashMap` keyed by player UUID holding immutable records. No resolver touches a `Player`, an entity,
a `World`, a block or a chunk, so there is no region or entity ownership to violate. The values themselves
were produced earlier by the attendance pass, which is their only producer.

Two caveats that are about PlaceholderAPI, not about Wormholes:

- Other expansions in the same string are not necessarily thread-safe. `setPlaceholders` on a string
  containing `%some_other_plugin_thing%` is only as safe as that expansion. If you are off the main thread,
  resolve Wormholes keys in their own call.
- If you then push the result into a scoreboard, a boss bar or a title, that write has its own threading
  rules and on Folia belongs on the target player's entity scheduler.

The publish side runs on Wormholes' own asynchronous task. You never see that thread.

---

## Which portal do the player keys describe

For each player, Wormholes picks exactly one portal, or none. The rule, in the order it is applied:

1. **Same world.** Portals in another world are never considered.
2. **In range.** The distance from the player's location to the portal's centre must be within
   `64 blocks + half the portal's bounding-box diagonal`. A small two-by-three frame is therefore selectable
   from a little over 65 blocks; a large gateway from further, in proportion to its size. Beyond that, the
   portal is not a candidate.
3. **Looking at it wins.** Among the candidates, any portal the player is looking at beats any portal they
   are not — regardless of distance. "Looking at" means the cosine of the angle between the player's look
   direction and the direction to the portal centre is at least `0.94`, which is a cone of roughly 20 degrees.
   A portal you are facing at 60 blocks beats one behind you at 3 blocks.
4. **Then nearest.** Within the same facing class, the closer portal wins.
5. **Then stable.** Exact ties are broken by position in the portal registry, so the answer does not flicker
   between two equidistant portals from one refresh to the next.

If no portal passes steps 1 and 2, the player has **no** snapshot: `%wormholes_portal.available%` is `false`
and every other `portal.*` and `rtp.*` key is `---`.

Note that the direction is measured from the player's **location**, not their eyes, and to the portal's
**centre**, not its nearest face. For a tall portal seen from close range the facing test can fail while the
portal fills the screen; the distance rule then picks it anyway, which is the intended outcome.

The 64-block base range and the 0.94 facing threshold are compiled in. Neither is configurable.

### The grace period after logout

When a player quits, their snapshot is not deleted immediately — it is marked to expire 60 seconds later. For
that minute, resolving player keys against their `OfflinePlayer` still returns the portal they were last near.
This exists so that a "last seen" display does not blank the instant somebody logs off. After 60 seconds the
entry is dropped on the next read and the keys answer `---`.

---

## Worked example: a portal HUD line

```java
package com.example.hud;

import java.util.List;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class PortalHud {
    private static final String UNAVAILABLE = "---";

    public String line(Player player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return "";
        }

        List<String> resolved = PlaceholderAPI.setPlaceholders(player, List.of(
            "%wormholes_portal.available%",
            "%wormholes_portal.name%",
            "%wormholes_portal.state%",
            "%wormholes_portal.destination%",
            "%wormholes_portal.distance%",
            "%wormholes_portal.cross-server%"));

        if (!"true".equals(resolved.get(0))) {
            return "No portal in range";
        }

        String destination = UNAVAILABLE.equals(resolved.get(3)) ? "unlinked" : resolved.get(3);
        String scope = "true".equals(resolved.get(5)) ? " [remote]" : "";

        return resolved.get(1) + " (" + resolved.get(2) + ") -> " + destination + scope
            + " " + resolved.get(4) + "m";
    }
}
```

Three things in there are the point of the example.

`%wormholes_portal.available%` is checked **first**, and it is the only key that distinguishes "no portal
near this player" from "a portal whose name happens to be unset". Every other player key answers `---` for
both cases.

`---` is compared against explicitly, because `portal.destination` legitimately answers `---` for a portal
that has no tunnel yet. Unavailable is a value here, not an error.

The batch overload resolves all six keys in one call. That is not a performance requirement — the resolvers
are trivially cheap — but it keeps the snapshot reads adjacent in time, so the line cannot show one portal's
name next to another portal's distance.

### Reacting to random-teleport state

`%wormholes_rtp.state%` returns one of a small fixed vocabulary. Switch on it with a `default` arm, because
`---` is one of the answers and more states may be added:

```java
package com.example.hud;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

public final class RtpLabel {
    public String of(Player player) {
        String state = PlaceholderAPI.setPlaceholders(player, "%wormholes_rtp.state%");

        return switch (state) {
            case "ready" -> "Destination locked";
            case "warming" -> "Searching for a destination";
            case "rerolling" -> "Picking a new destination";
            case "cooldown" -> "Cooling down";
            case "idle" -> "Idle";
            default -> "Not a random-teleport portal";
        };
    }
}
```

---

## The minimum

If all you want is one value on a scoreboard, there is nothing to write at all. Put the key in whatever
config drives your display:

```
Portal: %wormholes_portal.name% (%wormholes_portal.state%)
```

And from Java, one line:

```java
String state = PlaceholderAPI.setPlaceholders(player, "%wormholes_portal.state%");
```

Handle `---`. That is the whole contract.

---

## The keys

Every key is prefixed `%wormholes_`. Keys are matched case-insensitively; the canonical spelling is lower
case with `-` inside a segment and `.` between segments.

### Server keys

These ignore the player entirely and answer the same thing for everyone, including for a server-context parse
with no player at all.

| Key                            | Format  | Meaning                                                                                   |
|--------------------------------|---------|-------------------------------------------------------------------------------------------|
| `%wormholes_available%`        | boolean | `true` once the first server snapshot has been published. `false` before that. Never `---` |
| `%wormholes_portals%`          | integer | Portals registered on **this** server. Remote portals are not counted                      |
| `%wormholes_projections.active%` | integer | Portals currently rendering a projection                                                 |
| `%wormholes_projections.observers%` | integer | Distinct players with a projection frame in flight. One player watching two portals counts once, and the number dips between frames |
| `%wormholes_peers.connected%`  | integer | Peer servers with a completed handshake and a live link                                    |
| `%wormholes_peers.link%`       | enum    | Cross-server link health. See [the vocabularies](#value-vocabularies)                       |
| `%wormholes_transfers.in-flight%` | integer | Cross-server traversals currently in progress                                            |
| `%wormholes_failures%`         | integer | Internal failures counted since startup, across every subsystem. A cumulative total, not a rate |
| `%wormholes_failures.per-minute%` | decimal | Failures per minute over the last completed one-second window, extrapolated               |

`failures` counts anything the plugin classified as a fault — a refused scheduler task, a dropped event, a
provider that threw, a sideband drop. It is a health signal, not an error log. A steady non-zero
`failures.per-minute` is worth investigating; a `failures` total that stopped growing is not.

### Player keys

These describe the selected portal. With the single exception of `portal.available`, which answers `false`,
all of them answer `---` when the player has no portal in range, when the player is unknown, and when the
placeholder is parsed with no player at all.

| Key                             | Format  | Meaning                                                                                |
|---------------------------------|---------|-----------------------------------------------------------------------------------------|
| `%wormholes_portal.available%`  | boolean | `true` when this player has a portal snapshot. **Never `---`** — this is the key you branch on |
| `%wormholes_portal.name%`       | text    | The selected portal's name. `---` if it has none                                         |
| `%wormholes_portal.state%`      | enum    | `open`, `closed` or `syncing`. See [the vocabularies](#value-vocabularies)                |
| `%wormholes_portal.destination%` | text   | The linked portal's name; for a cross-server link with no known remote name, the peer server's name; `---` if the portal has no tunnel |
| `%wormholes_portal.distance%`   | decimal | Blocks from the player to the portal centre, two decimal places                          |
| `%wormholes_portal.cross-server%` | boolean | `true` when the portal's tunnel goes to another server                                 |
| `%wormholes_rtp.state%`         | enum    | Random-teleport state, or `---` when the selected portal is not an RTP portal             |
| `%wormholes_rtp.cooldown%`      | decimal | Seconds until this RTP portal may search again, two decimal places. `0.00` when it may search now. `---` when the selected portal is not an RTP portal |

`rtp.state` and `rtp.cooldown` describe the **selected portal**, not the player. They are `---` for a player
standing in front of an ordinary portal, and they change the moment the selection changes.

---

## Value vocabularies

Three keys answer from a closed set of words. Treat any word you do not recognise the same way you treat
`---`, and always write a `default` arm.

`%wormholes_portal.state%`:

| Value     | Meaning                                                                       |
|-----------|--------------------------------------------------------------------------------|
| `open`    | The portal is open and passable                                                |
| `closed`  | The portal is closed                                                           |
| `syncing` | The portal is mid-transition, opening or closing. Takes priority over open/closed |

`%wormholes_rtp.state%`:

| Value       | Meaning                                                                     |
|-------------|------------------------------------------------------------------------------|
| `ready`     | A destination is chosen and the portal will accept a traveler                 |
| `warming`   | A destination search is running                                               |
| `rerolling` | The portal is discarding its destination and choosing another                 |
| `cooldown`  | No destination, and the portal may not search again yet                       |
| `idle`      | No destination and no cooldown; nothing has asked it for one                  |
| `---`       | The selected portal is not an RTP portal, or its RTP runtime is not registered |

`%wormholes_peers.link%`:

| Value      | Meaning                                                                            |
|------------|-------------------------------------------------------------------------------------|
| `offline`  | Cross-server networking is not configured, or not running                            |
| `solo`     | Networking is running but no peers are configured. This is not a fault               |
| `down`     | Peers are configured and **none** are connected                                      |
| `degraded` | Some configured peers are connected, not all                                         |
| `linked`   | Every configured peer is connected                                                   |

`solo` and `down` are the pair worth distinguishing in an alert: `solo` is a single-server installation
behaving correctly, `down` is a cluster that has lost every link.

---

## Value formats

There are exactly four shapes, and knowing them is enough to parse anything here without guessing.

| Format  | Produced as                                                                                        |
|---------|-----------------------------------------------------------------------------------------------------|
| boolean | The literal string `true` or `false`. Never localised, never `---` unless the table says so           |
| integer | Base-ten digits, no grouping separators, no sign. Negative inputs are clamped to zero before printing |
| decimal | Always exactly two decimal places, always a `.` separator regardless of server locale. `1` prints as `1.00`. A value that is not a finite number prints as `---` |
| text    | The value with `%` characters removed and legacy `§`-colour pairs stripped. Empty after stripping becomes `---` |

`---` is the unavailable marker across every key. It is a value, not an error, and it is deliberately not
empty so that a scoreboard line does not silently collapse.

The text sanitising is not cosmetic. A portal name containing `%` would otherwise be re-parsed as a
placeholder by whatever consumed the output, and a name containing `§` colour codes would recolour the rest of
your line. Both are stripped before the value leaves Wormholes, so a portal named `§cDanger%` resolves as
`Danger`. **You cannot get the raw name back**, and you cannot colour a portal name through this surface.

Decimals never carry a thousands separator and never switch to `,` on a European locale, so parsing with
`Double.parseDouble` is safe. Very large magnitudes fall back to an integral value with `.00` appended rather
than switching to scientific notation.

---

## Failure policy

The expansion is written to answer something in every case rather than to propagate a problem into somebody
else's scoreboard.

| Situation                                       | What you get                                                                 |
|-------------------------------------------------|-------------------------------------------------------------------------------|
| Wormholes is not installed, or is disabled      | The key does not resolve. PlaceholderAPI leaves your text as written           |
| PlaceholderAPI was not enabled when Wormholes enabled | No expansion at all. Same as above                                       |
| A key that does not exist                       | Does not resolve. Your text is left alone — **not** `---`                      |
| An empty or blank parameter                     | Does not resolve                                                              |
| A player key parsed with no player               | `---`, and `portal.available` is `false`                                      |
| A player Wormholes has never seen                | `---`, and `portal.available` is `false`                                      |
| A player who quit less than 60 seconds ago      | Their last known portal, then `---`                                           |
| A resolver throws                                | `---`, plus one warning naming the exact key. Repeats of the same key are silent, and at most 64 distinct keys are ever logged |
| Wormholes disables mid-parse                    | A parse already under way may still return the last published values. The expansion is then unregistered and the snapshots cleared; from that point the keys do not resolve at all |

The distinction between **does not resolve** and **`---`** is the one to internalise. Unresolved means
PlaceholderAPI hands your original `%wormholes_whatever%` text straight through, which is what you will see on
screen if you typo a key. `---` means the expansion is present and answered "no value". If your display shows
a literal `%wormholes_…%`, the key is wrong or the plugin is not there; if it shows `---`, the key is right and
there is nothing to report.

Nothing here throws into your code, and nothing here blocks. The worst case is a stale or unavailable string.

---

## Configuration

There are none. The expansion has no configuration keys of its own: no toggle, no range setting, no publish
interval, no way to change the unavailable marker. It registers whenever PlaceholderAPI is present at Wormholes'
enable, and it publishes on the attendance pass' own schedule.

The two constants a server operator might expect to tune — the 64-block base selection range and the 0.94
facing threshold — are compiled in and shared with the portal attendance logic that drives portal ticking.
They are not exposed, because changing them would change how portals are ticked, not just what a scoreboard
says.

The file at `plugins/Wormholes/config/wormholes.toml` governs the plugin's own behaviour, and some of that is
visible through these values — turning off cross-server networking will move `peers.link` to `offline`, and
portals that are never built will keep `portals` at `0` — but no key in that file addresses the placeholders.

---

## Discovering the keys on a running server

The expansion publishes its full key list to PlaceholderAPI, so the authoritative answer is always on the
server itself:

```
/papi info wormholes
```

lists every key this build supports, and

```
/papi parse me %wormholes_portal.state%
```

resolves one against yourself, standing where you are, which is the fastest way to confirm both the selection
rule and the current value at the same time.
