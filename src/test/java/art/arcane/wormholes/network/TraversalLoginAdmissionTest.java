package art.arcane.wormholes.network;

import art.arcane.wormholes.access.AccessTestPortals;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerLoginEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TraversalLoginAdmissionTest {
    @Test
    void sourceOnlyPrivilegeOverridesFullAndWhitelistForTheAdmittedCrossing() throws Exception {
        for (PlayerLoginEvent.Result result : Set.of(PlayerLoginEvent.Result.KICK_FULL, PlayerLoginEvent.Result.KICK_WHITELIST)) {
            TraversalService service = new TraversalService(null);
            Player player = AccessTestPortals.player("Traveler", false, Set.of());
            admit(service, player, true);
            PlayerLoginEvent event = deniedLogin(player, result);

            service.on(event);

            assertEquals(PlayerLoginEvent.Result.ALLOWED, event.getResult());
            assertFalse(player.isOp());
            assertFalse(player.hasPermission("*"));
        }
    }

    @Test
    void currentDestinationWildcardAlsoAllowsAnAdmittedCrossing() throws Exception {
        TraversalService service = new TraversalService(null);
        Player player = AccessTestPortals.player("Traveler", false, Set.of("*"));
        admit(service, player, false);
        PlayerLoginEvent event = deniedLogin(player, PlayerLoginEvent.Result.KICK_FULL);

        service.on(event);

        assertEquals(PlayerLoginEvent.Result.ALLOWED, event.getResult());
    }

    @Test
    void unreservedLoginsAndNonPrivilegedReservationsKeepTheirAdmissionResult() throws Exception {
        TraversalService service = new TraversalService(null);
        Player player = AccessTestPortals.player("Traveler", false, Set.of());
        PlayerLoginEvent event = deniedLogin(player, PlayerLoginEvent.Result.KICK_FULL);
        service.on(event);
        assertEquals(PlayerLoginEvent.Result.KICK_FULL, event.getResult());

        admit(service, player, false);
        service.on(event);
        assertEquals(PlayerLoginEvent.Result.KICK_FULL, event.getResult());
    }

    @Test
    void privilegedHandoffsDoNotOverrideBansOrOtherLoginFailures() throws Exception {
        for (PlayerLoginEvent.Result result : Set.of(PlayerLoginEvent.Result.KICK_BANNED, PlayerLoginEvent.Result.KICK_OTHER)) {
            TraversalService service = new TraversalService(null);
            Player player = AccessTestPortals.player("Traveler", true, Set.of("*"));
            admit(service, player, true);
            PlayerLoginEvent event = deniedLogin(player, result);

            service.on(event);

            assertEquals(result, event.getResult());
        }
    }

    private static void admit(TraversalService service, Player player, boolean sourceBypass) throws ReflectiveOperationException {
        Field field = TraversalService.class.getDeclaredField("inboundAdmissions");
        field.setAccessible(true);
        PlayerHandoffAdmission admission = (PlayerHandoffAdmission) field.get(service);
        PlayerHandoffAdmission.Request request = new PlayerHandoffAdmission.Request(
            UUID.randomUUID(), player.getUniqueId(), player.getName(), "source", null, false, sourceBypass, null);
        admission.decide(new PlayerHandoffAdmission.Attempt(request, null, System.currentTimeMillis(), 60_000L, 1_000L));
    }

    private static PlayerLoginEvent deniedLogin(Player player, PlayerLoginEvent.Result result) {
        PlayerLoginEvent event = new PlayerLoginEvent(player, "destination.example", InetAddress.getLoopbackAddress());
        event.disallow(result, "destination admission refused");
        return event;
    }
}
