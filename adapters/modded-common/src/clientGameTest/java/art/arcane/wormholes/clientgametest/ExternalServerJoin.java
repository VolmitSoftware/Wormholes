package art.arcane.wormholes.clientgametest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ExternalServerJoin {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final String FIXTURE_PERMISSION = "wormholesfixture.use";
    private static final List<String> PORTAL_FIELDS = List.of("name", "mode", "owner", "facing", "orientation", "projection", "access",
        "cost", "cost-amount");

    private ExternalServerJoin() {
    }

    static void run(TestClient client, String label) {
        String address = ExternalServerSession.configuredAddress();
        if (address.isEmpty()) {
            LOGGER.info("[{}] skipped without an external server", label);
            return;
        }
        try (ExternalServerSession session = ExternalServerSession.connect(client, address)) {
            List<Portal> portals = portals(session.command("whtest portal list", "portal-list"));
            String permitted = session.command("whtest perm " + FIXTURE_PERMISSION, "perm " + FIXTURE_PERMISSION);
            if (!permitted.equals("true")) {
                throw new AssertionError(label + ": the joined player reports " + FIXTURE_PERMISSION + "=" + permitted);
            }
            for (Portal portal : portals) {
                LOGGER.info("[{}] portal {} {}", label, portal.id(), fields(session, portal, label));
            }
            LOGGER.info("[{}] joined {} and read {} portals", label, address, portals.size());
        }
    }

    private static Map<String, String> fields(ExternalServerSession session, Portal portal, String label) {
        Map<String, String> values = new LinkedHashMap<>(PORTAL_FIELDS.size());
        for (String field : PORTAL_FIELDS) {
            String value = URLDecoder.decode(session.command("whtest portal get " + portal.id() + " " + field,
                "portal-get " + portal.id() + " " + field), StandardCharsets.UTF_8);
            String expected = switch (field) {
                case "name" -> portal.name();
                case "mode" -> portal.mode();
                case "owner" -> portal.owner();
                default -> value;
            };
            if (!value.equals(expected) || value.isEmpty() && !field.equals("name")) {
                throw new AssertionError(label + ": portal " + portal.id() + " " + field + " read '" + value + "' but the list reported '" + expected + "'");
            }
            values.put(field, value);
        }
        return values;
    }

    private static List<Portal> portals(String payload) {
        String[] tokens = payload.split(" ");
        int count = Integer.parseInt(tokens[0]);
        if (tokens.length != count + 1) {
            throw new AssertionError("the portal list announced " + count + " portals but carried " + (tokens.length - 1) + ": " + payload);
        }
        List<Portal> portals = new ArrayList<>(count);
        for (int index = 1; index < tokens.length; index++) {
            String[] fields = tokens[index].split(",", 4);
            if (fields.length != 4) {
                throw new AssertionError("malformed portal list entry: " + tokens[index]);
            }
            portals.add(new Portal(UUID.fromString(fields[0]), fields[1], fields[2], URLDecoder.decode(fields[3], StandardCharsets.UTF_8)));
        }
        return portals;
    }

    private record Portal(UUID id, String mode, String owner, String name) {
    }
}
