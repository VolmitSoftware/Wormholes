package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PocketShellTest {
    @Test
    void defaultsMatchTheOriginalHardCodedRoomSoUntouchedPocketsNeverMove() {
        PocketShell defaults = PocketShell.defaults();

        assertEquals(32, defaults.size());
        assertEquals("SMOOTH_STONE", defaults.shellMaterial());
        assertEquals("CRIMSON_DOOR", defaults.returnDoorMaterial());
        assertSame(defaults, PocketShell.defaults());
    }

    @Test
    void sizesOutsideTheSupportedRangeAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new PocketShell(PocketShell.MIN_SIZE - 1, "STONE", "OAK_DOOR"));
        assertThrows(IllegalArgumentException.class,
            () -> new PocketShell(PocketShell.MAX_SIZE + 1, "STONE", "OAK_DOOR"));
        assertFalse(PocketShell.isSupportedSize(0));
        assertFalse(PocketShell.isSupportedSize(PocketShell.MAX_SIZE + 1));
        assertTrue(PocketShell.isSupportedSize(PocketShell.MIN_SIZE));
        assertTrue(PocketShell.isSupportedSize(PocketShell.MAX_SIZE));
    }

    @Test
    void materialNamesAreNormalizedAndNamespacesAreDropped() {
        PocketShell shell = new PocketShell(64, "minecraft:polished_deepslate", " warped_door ");

        assertEquals("POLISHED_DEEPSLATE", shell.shellMaterial());
        assertEquals("WARPED_DOOR", shell.returnDoorMaterial());
        assertThrows(IllegalArgumentException.class, () -> new PocketShell(32, "  ", "OAK_DOOR"));
        assertThrows(IllegalArgumentException.class, () -> new PocketShell(32, "minecraft:", "OAK_DOOR"));
        assertThrows(NullPointerException.class, () -> new PocketShell(32, null, "OAK_DOOR"));
    }

    @Test
    void withersReturnTheSameInstanceWhenNothingActuallyChanges() {
        PocketShell shell = new PocketShell(48, "STONE", "OAK_DOOR");

        assertSame(shell, shell.withSize(48));
        assertSame(shell, shell.withShellMaterial("minecraft:stone"));
        assertSame(shell, shell.withReturnDoorMaterial("oak_door"));
        assertEquals(64, shell.withSize(64).size());
        assertEquals("OAK_DOOR", shell.withSize(64).returnDoorMaterial());
        assertEquals("OBSIDIAN", shell.withShellMaterial("obsidian").shellMaterial());
        assertEquals(48, shell.withShellMaterial("obsidian").size());
    }

    @Test
    void reshapingASpaceKeepsItsAllocationIdentity() {
        PocketSpace space = new PocketSpace(
            new java.util.UUID(0, 1),
            PocketBinding.personal(new java.util.UUID(0, 2)),
            0L,
            8,
            128,
            8,
            PocketShell.defaults()
        );
        PocketSpace reshaped = space.withShell(new PocketShell(64, "OBSIDIAN", "OAK_DOOR"));

        assertEquals(space.withShell(reshaped.shell()), reshaped);
        assertEquals(64, reshaped.shell().size());
        assertSame(space, space.withShell(PocketShell.defaults()));
    }
}
