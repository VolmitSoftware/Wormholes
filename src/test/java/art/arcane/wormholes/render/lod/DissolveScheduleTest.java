package art.arcane.wormholes.render.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.render.ProjectedBlockClaim;
import art.arcane.wormholes.render.ProjectionCellKey;

final class DissolveScheduleTest {
    @Test
    void admissionRampsNearToFarOverTheConfiguredTicks() {
        DissolveSchedule schedule = new DissolveSchedule();
        assertEquals(1.0D, schedule.admittedFraction(0L), 1.0E-9D, "idle admits everything");
        schedule.beginAdmit(10L, 8);
        assertTrue(schedule.isActive());
        assertEquals(0.125D, schedule.admittedFraction(10L), 1.0E-9D);
        assertEquals(0.5D, schedule.admittedFraction(13L), 1.0E-9D);
        assertEquals(1.0D, schedule.admittedFraction(17L), 1.0E-9D);
        assertFalse(schedule.isActive(), "the schedule goes idle once the view is fully admitted");

        DissolveSchedule instant = new DissolveSchedule();
        instant.beginAdmit(0L, 0);
        assertEquals(1.0D, instant.admittedFraction(0L), 1.0E-9D);
        assertFalse(instant.isActive());
    }

    @Test
    void retirementRampsFarToNearAndReturningInterestCancelsIt() {
        DissolveSchedule schedule = new DissolveSchedule();
        schedule.beginRetire(100L, 4);
        assertTrue(schedule.isRetiring());
        assertEquals(0.75D, schedule.admittedFraction(100L), 1.0E-9D);
        assertEquals(0.25D, schedule.admittedFraction(102L), 1.0E-9D);
        assertFalse(schedule.retireComplete(103L));
        assertTrue(schedule.retireComplete(104L));
        assertEquals(0.0D, schedule.admittedFraction(104L), 1.0E-9D);

        DissolveSchedule cancelled = new DissolveSchedule();
        cancelled.beginRetire(100L, 8);
        assertEquals(0.5D, cancelled.admittedFraction(103L), 1.0E-9D);
        cancelled.cancelRetire();
        assertFalse(cancelled.isRetiring());
        assertEquals(1.0D, cancelled.admittedFraction(103L), 1.0E-9D, "interest inside the grace window keeps the full view");
        cancelled.beginRetire(200L, 8);
        assertEquals(0.875D, cancelled.admittedFraction(200L), 1.0E-9D, "a later retire starts from the full view again");
    }

    @Test
    void filterKeepsTheNearestCellsFirst() {
        Long2ObjectOpenHashMap<ProjectedBlockClaim> claims = new Long2ObjectOpenHashMap<ProjectedBlockClaim>();
        for (int depth = 1; depth <= 8; depth++) {
            claims.put(ProjectionCellKey.pack(0, 64, -depth), claim());
        }
        DissolveSchedule.filter(claims, 0.5D, 8.0D, key -> -ProjectionCellKey.unpackZ(key));
        assertEquals(4, claims.size());
        for (long key : claims.keySet()) {
            assertTrue(-ProjectionCellKey.unpackZ(key) <= 4, "only the near half survives at fraction 0.5");
        }
        DissolveSchedule.filter(claims, 1.0D, 8.0D, key -> -ProjectionCellKey.unpackZ(key));
        assertEquals(4, claims.size(), "fraction one keeps every claim");
        DissolveSchedule.filter(claims, 0.0D, 8.0D, key -> -ProjectionCellKey.unpackZ(key));
        assertTrue(claims.isEmpty());
    }

    private static ProjectedBlockClaim claim() {
        return new ProjectedBlockClaim(blockData(), null, ProjectedBlockClaim.NO_REMOTE_KEY, false);
    }

    private static BlockData blockData() {
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[] {BlockData.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getMaterial" -> Material.STONE;
                case "toString", "getAsString" -> "stone";
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == args[0]);
                case "clone" -> proxy;
                default -> null;
            });
    }
}
