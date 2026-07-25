package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

public final class ProjectionCellKeyOwnershipTest {
    private static final Path RENDER_SOURCES = Path.of("src/main/java/art/arcane/wormholes/render");
    private static final String SHARED_DEFINITION = "ProjectionCellKey.java";
    private static final String LAYOUT_MASK = "0x3FFFFFFL";
    private static final String LAYOUT_SHIFT = "<< 38";

    @Test
    public void theSharedDefinitionStillOwnsTheProjectedCellBitLayout() throws IOException {
        String definition = Files.readString(RENDER_SOURCES.resolve(SHARED_DEFINITION), StandardCharsets.UTF_8);

        assertTrue(definition.contains(LAYOUT_MASK) && definition.contains(LAYOUT_SHIFT),
            "ProjectionCellKey must remain the one place the projected cell bit layout is written down");
    }

    @Test
    public void noClaimKeyProducerCarriesItsOwnCopyOfTheCellBitLayout() throws IOException {
        List<Path> participants = claimKeyParticipants();

        assertTrue(participants.stream().anyMatch(path -> path.getFileName().toString().equals("PortalSkinRenderer.java")),
            "the skin renderer submits claims to the arbiter, so it must be covered by this guard");
        assertTrue(participants.size() >= 3, "the claim key participant scan found suspiciously few files");

        List<String> offenders = new ArrayList<String>();
        for (Path participant : participants) {
            String body = Files.readString(participant, StandardCharsets.UTF_8);
            if (body.contains(LAYOUT_MASK) || body.contains(LAYOUT_SHIFT)) {
                offenders.add(participant.getFileName().toString());
            }
        }

        assertEquals(List.of(), offenders,
            "every producer and consumer of ProjectionClaimArbiter claim keys must pack through ProjectionCellKey; "
                + "a private copy of the layout lets the two drift, and then claims are released against the wrong "
                + "cells and fake blocks leak permanently");
    }

    @Test
    public void portalSkinFluidClaimsAreKeyedByTheSharedCellLayout() {
        int[][] cells = new int[][] {
            {0, 0, 0},
            {1, 64, 2},
            {-1, -64, -2},
            {30_000_000, 319, -30_000_000},
            {-30_000_000, -2032, 30_000_000}
        };
        List<Vector> positions = new ArrayList<Vector>();
        for (int[] cell : cells) {
            positions.add(new Vector(cell[0], cell[1], cell[2]));
        }

        Long2ObjectOpenHashMap<ProjectedBlockClaim> claims = PortalSkinRenderer.fluidClaims(positions, null);

        assertEquals(cells.length, claims.size(), "every skinned cell must produce exactly one distinct claim key");
        for (int[] cell : cells) {
            long key = ProjectionCellKey.pack(cell[0], cell[1], cell[2]);
            assertTrue(claims.containsKey(key),
                "the skin renderer must key its claims with the same layout the arbiter decodes with");
            assertEquals(cell[0], ProjectionCellKey.unpackX(key), "x round trip");
            assertEquals(cell[1], ProjectionCellKey.unpackY(key), "y round trip");
            assertEquals(cell[2], ProjectionCellKey.unpackZ(key), "z round trip");
        }
    }

    private static List<Path> claimKeyParticipants() throws IOException {
        List<Path> participants = new ArrayList<Path>();
        try (Stream<Path> sources = Files.list(RENDER_SOURCES)) {
            List<Path> files = sources.filter(path -> path.getFileName().toString().endsWith(".java"))
                .filter(path -> !path.getFileName().toString().equals(SHARED_DEFINITION))
                .sorted()
                .toList();
            for (Path file : files) {
                if (Files.readString(file, StandardCharsets.UTF_8).contains("ProjectedBlockClaim")) {
                    participants.add(file);
                }
            }
        }
        return participants;
    }
}
