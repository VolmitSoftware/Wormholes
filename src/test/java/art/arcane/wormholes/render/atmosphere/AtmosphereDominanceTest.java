package art.arcane.wormholes.render.atmosphere;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.render.ProjectedBlockClaim;
import art.arcane.wormholes.render.ProjectionCellKey;

final class AtmosphereDominanceTest {
    private static final int DESTINATION_BIOME = 7;

    @Test
    void aSectionIsRetintedOnlyWhenTheCoveredQuartCellsReachTheDominanceFraction() {
        Long2ObjectOpenHashMap<ProjectedBlockClaim> claims = new Long2ObjectOpenHashMap<ProjectedBlockClaim>();
        coverQuartCells(claims, 0, 4, 0, 40);

        Long2IntOpenHashMap dominated = AtmosphereDominance.compute(claims, 0.6D, key -> DESTINATION_BIOME);
        assertEquals(40, dominated.size(), "every covered quart cell of a dominated section is retinted");
        for (long cell : dominated.keySet()) {
            assertEquals(DESTINATION_BIOME, dominated.get(cell));
            assertEquals(4, ProjectionCellKey.unpackY(cell) >> 2, "cells are keyed by quart coordinates: section 4 holds block y 64..79");
        }

        Long2ObjectOpenHashMap<ProjectedBlockClaim> sparse = new Long2ObjectOpenHashMap<ProjectedBlockClaim>();
        coverQuartCells(sparse, 0, 4, 0, 30);
        assertTrue(AtmosphereDominance.compute(sparse, 0.6D, key -> DESTINATION_BIOME).isEmpty(),
            "30 of 64 quart cells stay below a 0.6 dominance");
    }

    @Test
    void sectionsAreJudgedIndependentlyAndUnknownBiomesAreSkipped() {
        Long2ObjectOpenHashMap<ProjectedBlockClaim> claims = new Long2ObjectOpenHashMap<ProjectedBlockClaim>();
        coverQuartCells(claims, 0, 4, 0, 64);
        coverQuartCells(claims, 0, 5, 0, 8);

        Long2IntOpenHashMap dominated = AtmosphereDominance.compute(claims, 0.5D, key -> DESTINATION_BIOME);
        assertEquals(64, dominated.size());
        for (long cell : dominated.keySet()) {
            assertEquals(4, ProjectionCellKey.unpackY(cell) >> 2, "only the dominated section 4 is retinted");
        }

        Long2IntOpenHashMap unknown = AtmosphereDominance.compute(claims, 0.5D, key -> -1);
        assertTrue(unknown.isEmpty(), "a biome the client registry cannot name is never sent");
    }

    @Test
    void manyClaimsInOneQuartCellCountOnce() {
        Long2ObjectOpenHashMap<ProjectedBlockClaim> claims = new Long2ObjectOpenHashMap<ProjectedBlockClaim>();
        for (int x = 0; x < 4; x++) {
            for (int y = 64; y < 68; y++) {
                for (int z = 0; z < 4; z++) {
                    claims.put(ProjectionCellKey.pack(x, y, z), claim(x, y, z));
                }
            }
        }
        assertTrue(AtmosphereDominance.compute(claims, 0.6D, key -> DESTINATION_BIOME).isEmpty(),
            "64 blocks in a single quart cell cover 1 of 64 cells");
    }

    private static void coverQuartCells(Long2ObjectOpenHashMap<ProjectedBlockClaim> claims,
                                        int sectionX, int sectionY, int sectionZ, int quartCells) {
        int added = 0;
        for (int qy = 0; qy < 4 && added < quartCells; qy++) {
            for (int qz = 0; qz < 4 && added < quartCells; qz++) {
                for (int qx = 0; qx < 4 && added < quartCells; qx++) {
                    int x = (sectionX << 4) + (qx << 2);
                    int y = (sectionY << 4) + (qy << 2);
                    int z = (sectionZ << 4) + (qz << 2);
                    claims.put(ProjectionCellKey.pack(x, y, z), claim(x, y, z));
                    added++;
                }
            }
        }
    }

    private static ProjectedBlockClaim claim(int x, int y, int z) {
        return new ProjectedBlockClaim(blockData(Material.STONE), null, ProjectionCellKey.pack(x + 100, y, z + 100), false);
    }

    private static BlockData blockData(Material material) {
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[] {BlockData.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getMaterial" -> material;
                case "toString", "getAsString" -> material.name();
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == args[0]);
                case "clone" -> proxy;
                default -> null;
            });
    }
}
