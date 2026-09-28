package art.arcane.wormholes.modded;

import art.arcane.wormholes.door.DoorAccessRecord;
import art.arcane.wormholes.door.DoorAccessState;
import art.arcane.wormholes.door.DoorOpenState;
import art.arcane.wormholes.door.DoorProjectionState;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MinecraftDoorAccessMenuTest {
    private static final UUID ITEM = new UUID(0, 1);
    private static final UUID OWNER = new UUID(0, 2);
    private static final UUID LISTED = new UUID(0, 3);
    private static final UUID STRANGER = new UUID(0, 4);

    @BeforeClass
    public static void bootstrap() {
        MinecraftPortalToolsTest.bootstrap();
    }

    @Test
    public void additionsResolveLikeTheBukkitMenu() {
        assertEquals(MinecraftDoorAccessMenu.AddResolution.NOT_FOUND,
            MinecraftDoorAccessMenu.resolveAddition(record(DoorAccessState.WHITELIST), null));
        for (DoorAccessState state : DoorAccessState.values()) {
            assertEquals(MinecraftDoorAccessMenu.AddResolution.OWNER, MinecraftDoorAccessMenu.resolveAddition(record(state), OWNER));
            assertEquals(MinecraftDoorAccessMenu.AddResolution.ALREADY_LISTED, MinecraftDoorAccessMenu.resolveAddition(record(state), LISTED));
        }
        assertEquals(MinecraftDoorAccessMenu.AddResolution.ADD,
            MinecraftDoorAccessMenu.resolveAddition(DoorAccessRecord.unrestricted(ITEM, OWNER), STRANGER));
    }

    @Test
    public void unknownPlayerNamesFallBackToAShortenedIdentifier() {
        assertEquals("Astra", MinecraftDoorAccessMenu.resolveDisplayName("Astra", "fallback"));
        assertEquals("fallback", MinecraftDoorAccessMenu.resolveDisplayName(null, "fallback"));
        assertEquals("fallback", MinecraftDoorAccessMenu.resolveDisplayName("   ", "fallback"));
        assertEquals("00000000", MinecraftDoorAccessMenu.shortId(LISTED));
    }

    @Test
    public void iconsMatchTheBukkitMaterials() {
        assertSame(Items.STAINED_GLASS_PANE.black(), MinecraftDoorAccessMenu.stateIcon(DoorAccessState.NEUTRAL));
        assertSame(Items.STAINED_GLASS_PANE.green(), MinecraftDoorAccessMenu.stateIcon(DoorAccessState.WHITELIST));
        assertSame(Items.STAINED_GLASS_PANE.red(), MinecraftDoorAccessMenu.stateIcon(DoorAccessState.BLACKLIST));
        assertSame(Items.DYE.lime(), MinecraftDoorAccessMenu.openStateIcon(DoorOpenState.OPEN));
        assertSame(Items.DYE.gray(), MinecraftDoorAccessMenu.openStateIcon(DoorOpenState.CLOSED));
        for (DoorProjectionState state : DoorProjectionState.values()) {
            assertSame(Items.SPYGLASS, MinecraftDoorAccessMenu.projectionIcon(state));
        }
        assertThrows(NullPointerException.class, () -> MinecraftDoorAccessMenu.stateIcon(null));
    }

    @Test
    public void headerControlsSitAroundTheCenter() {
        assertEquals(-1, MinecraftDoorAccessMenu.PLACARD_POSITION);
        assertEquals(0, MinecraftDoorAccessMenu.OPEN_STATE_POSITION);
        assertEquals(1, MinecraftDoorAccessMenu.ADD_POSITION);
        assertEquals(2, MinecraftDoorAccessMenu.PROJECTION_POSITION);
        assertEquals(DoorOpenState.CLOSED, MinecraftDoorAccessMenu.nextOpenState(DoorOpenState.OPEN));
        assertEquals(DoorProjectionState.ON, MinecraftDoorAccessMenu.nextProjectionState(DoorProjectionState.INHERIT));
        assertEquals(DoorProjectionState.OFF, MinecraftDoorAccessMenu.nextProjectionState(DoorProjectionState.ON));
        assertEquals(DoorProjectionState.INHERIT, MinecraftDoorAccessMenu.nextProjectionState(DoorProjectionState.OFF));
    }

    @Test
    public void entriesFillRowsUnderTheHeaderAndTheViewportTracksTheList() {
        Set<Integer> occupied = new HashSet<>();
        for (int index = 0; index < 121; index++) {
            int row = MinecraftDoorAccessMenu.entryRow(index);
            int position = MinecraftDoorAccessMenu.entryPosition(index);
            assertTrue(row >= 1 && position >= -4 && position <= 4);
            assertTrue(occupied.add(row * MinecraftDoorAccessMenu.ROW_WIDTH + position));
        }
        assertEquals(-4, MinecraftDoorAccessMenu.entryPosition(0));
        assertEquals(2, MinecraftDoorAccessMenu.entryRow(MinecraftDoorAccessMenu.ROW_WIDTH));
        assertEquals(1, MinecraftDoorAccessMenu.viewportHeight(0));
        assertEquals(2, MinecraftDoorAccessMenu.viewportHeight(1));
        assertEquals(2, MinecraftDoorAccessMenu.viewportHeight(9));
        assertEquals(3, MinecraftDoorAccessMenu.viewportHeight(10));
        assertEquals(6, MinecraftDoorAccessMenu.viewportHeight(400));
        assertThrows(IllegalArgumentException.class, () -> MinecraftDoorAccessMenu.entryRow(-1));
    }

    private static DoorAccessRecord record(DoorAccessState state) {
        return DoorAccessRecord.unrestricted(ITEM, OWNER).withPlayerState(LISTED, state);
    }
}
