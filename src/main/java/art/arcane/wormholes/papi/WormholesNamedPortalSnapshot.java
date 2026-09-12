package art.arcane.wormholes.papi;

import art.arcane.volmlib.util.bukkit.papi.PlaceholderValues;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.rtp.BukkitRtpRuntime;
import art.arcane.wormholes.portal.rtp.RtpService;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record WormholesNamedPortalSnapshot(
    Map<String, WormholesPortalDestinations> byId,
    Map<String, WormholesPortalDestinations> byName) {

    private static final String DESTINATION_SEPARATOR = ".destination.";

    public WormholesNamedPortalSnapshot {
        byId = Map.copyOf(byId);
        byName = Map.copyOf(byName);
    }

    public static WormholesNamedPortalSnapshot capture(List<ILocalPortal> portals, BukkitRtpRuntime rtp) {
        Map<String, WormholesPortalDestinations> byId = new HashMap<>(portals.size());
        Map<String, WormholesPortalDestinations> byName = new HashMap<>(portals.size());
        Set<String> ambiguousNames = new HashSet<>();
        for (ILocalPortal portal : portals) {
            RtpService.Snapshot rtpSnapshot = portal.getType() == PortalType.RTP && rtp != null
                ? rtp.snapshotOrNull(portal.getId()) : null;
            WormholesPortalDestinations destinations = WormholesPortalDestinations.capture(portal, rtpSnapshot);
            byId.put(portal.getId().toString(), destinations);
            String selector = selector(portal.getName());
            if (selector.isEmpty() || ambiguousNames.contains(selector)) {
                continue;
            }
            if (byName.putIfAbsent(selector, destinations) != null) {
                byName.remove(selector);
                ambiguousNames.add(selector);
            }
        }
        return new WormholesNamedPortalSnapshot(byId, byName);
    }

    public static String resolve(WormholesNamedPortalSnapshot snapshot, UUID playerId, String tail, long nowMillis) {
        int separator = tail.lastIndexOf(DESTINATION_SEPARATOR);
        if (separator <= 0) {
            return null;
        }
        String field = tail.substring(separator + DESTINATION_SEPARATOR.length());
        if (!field.equals("x") && !field.equals("y") && !field.equals("z") && !field.equals("time-remaining")) {
            return null;
        }
        if (snapshot == null) {
            return PlaceholderValues.UNAVAILABLE;
        }
        String selector = tail.substring(0, separator);
        WormholesPortalDestinations destinations = snapshot.byId().get(selector);
        if (destinations == null) {
            destinations = snapshot.byName().get(selector);
        }
        return destinations == null ? PlaceholderValues.UNAVAILABLE : destinations.resolve(playerId, field, nowMillis);
    }

    public static String selector(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String plain = name.indexOf('&') >= 0 || name.indexOf('\u00a7') >= 0 || name.indexOf('<') >= 0 || name.indexOf('[') >= 0
            ? ComponentText.legacyOnly(name).plain() : name;
        String normalized = plain.toLowerCase(Locale.ROOT);
        StringBuilder selector = new StringBuilder(normalized.length());
        boolean separator = false;
        for (int offset = 0; offset < normalized.length();) {
            int character = normalized.codePointAt(offset);
            offset += Character.charCount(character);
            if (!Character.isLetterOrDigit(character)) {
                separator = !selector.isEmpty();
                continue;
            }
            if (separator) {
                selector.append('-');
                separator = false;
            }
            selector.appendCodePoint(character);
        }
        return selector.toString();
    }
}
