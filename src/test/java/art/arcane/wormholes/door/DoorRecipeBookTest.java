package art.arcane.wormholes.door;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoorRecipeBookTest {
    private static final NamespacedKey PAIR = key("dimensional_door_pair_kit");
    private static final NamespacedKey PUBLIC = key("public_dimensional_door");

    @Test
    void aPermittedPlayerDiscoversEveryRegisteredRecipe() {
        DoorRecipeBook.Plan plan = DoorRecipeBook.plan(List.of(PAIR, PUBLIC), true);

        assertEquals(List.of(PAIR, PUBLIC), plan.discover());
        assertTrue(plan.undiscover().isEmpty());
    }

    @Test
    void aPlayerWithoutTheCraftNodeHasThemTakenBackOutOfTheBook() {
        DoorRecipeBook.Plan plan = DoorRecipeBook.plan(List.of(PAIR, PUBLIC), false);

        assertEquals(List.of(PAIR, PUBLIC), plan.undiscover());
        assertTrue(plan.discover().isEmpty());
    }

    @Test
    void aDisabledRecipeIsNeverPlannedBecauseItIsNotRegistered() {
        DoorRecipeBook.Plan plan = DoorRecipeBook.plan(List.of(PAIR), true);

        assertEquals(List.of(PAIR), plan.discover());
        assertTrue(DoorRecipeBook.plan(List.of(), true).isEmpty());
        assertTrue(DoorRecipeBook.plan(null, true).isEmpty());
    }

    @Test
    void nullKeysNeverReachTheBook() {
        DoorRecipeBook.Plan plan = DoorRecipeBook.plan(Arrays.asList(PAIR, null), true);

        assertEquals(List.of(PAIR), plan.discover());
    }

    @Test
    void plansAreImmutableSnapshots() {
        List<NamespacedKey> registered = new ArrayList<>(List.of(PAIR));
        DoorRecipeBook.Plan plan = DoorRecipeBook.plan(registered, true);
        registered.add(PUBLIC);

        assertEquals(List.of(PAIR), plan.discover());
    }

    private static NamespacedKey key(String name) {
        return new NamespacedKey("wormholes", name);
    }
}
