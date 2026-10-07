package art.arcane.optics.internal.stream;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import art.arcane.optics.math.BlockBox;
import art.arcane.optics.stream.Brick;
import art.arcane.optics.stream.PlateSectionBox;
import art.arcane.optics.stream.ViewStreamMessage;

public final class EncodedPlate {
    private final PlateSectionBox sections;
    private final BlockBox cells;
    private final int backingState;
    private final Brick[] bricks;
    private final byte[][] bodies;
    private final int[] referencedIds;
    private final long totalBytes;
    private final int[] kindCounts;
    private volatile long saltedFor;
    private volatile long[] saltedHashes;

    public EncodedPlate(PlateSectionBox sections, BlockBox cells, int backingState, Brick[] bricks, byte[][] bodies, int[] referencedIds,
                 int[] kindCounts) {
        this.sections = Objects.requireNonNull(sections, "sections");
        this.cells = Objects.requireNonNull(cells, "cells");
        this.backingState = backingState;
        this.bricks = bricks;
        this.bodies = bodies;
        this.referencedIds = referencedIds;
        this.kindCounts = kindCounts;
        long total = 0L;
        for (byte[] body : bodies) {
            total += body.length + 2;
        }
        this.totalBytes = total;
    }

    public PlateSectionBox sections() {
        return sections;
    }

    public BlockBox cells() {
        return cells;
    }

    public int backingState() {
        return backingState;
    }

    public int brickCount() {
        return bricks.length;
    }

    public Brick brick(int index) {
        return bricks[index];
    }

    public List<Brick> bricks() {
        return List.of(bricks);
    }

    public byte[] body(int index) {
        return bodies[index];
    }

    public int[] referencedIds() {
        return referencedIds.clone();
    }

    public long totalBytes() {
        return totalBytes;
    }

    public int kindCount(int kindOrdinal) {
        return kindCounts[kindOrdinal];
    }

    public long[] hashes(long salt) {
        long[] cached = saltedHashes;
        if (cached != null && saltedFor == salt) {
            return cached;
        }
        long[] hashes = new long[bodies.length];
        for (int i = 0; i < bodies.length; i++) {
            hashes[i] = XxHash64.hash(bodies[i], 0, bodies[i].length, salt);
        }
        saltedFor = salt;
        saltedHashes = hashes;
        return hashes;
    }

    public ViewStreamMessage.PlateBegin begin(int portalKey, int plateRevision, boolean withHashes, long salt) {
        long[] manifest = withHashes ? hashes(salt).clone() : null;
        return new ViewStreamMessage.PlateBegin(portalKey, plateRevision, sections, cells, backingState, bricks.length, manifest);
    }

    public ViewStreamMessage.PlateBricks bricksMessage(int portalKey, int plateRevision) {
        return new ViewStreamMessage.PlateBricks(portalKey, plateRevision, Arrays.asList(bricks));
    }

    public ViewStreamMessage.PlateBricks bricksMessage(int portalKey, int plateRevision, ViewStreamMessage.BrickMiss.Plate missed) {
        int count = 0;
        for (int i = 0; i < bricks.length; i++) {
            if (missed.missed(i)) {
                count++;
            }
        }
        Brick[] selected = new Brick[count];
        int cursor = 0;
        for (int i = 0; i < bricks.length; i++) {
            if (missed.missed(i)) {
                selected[cursor++] = bricks[i];
            }
        }
        return new ViewStreamMessage.PlateBricks(portalKey, plateRevision, Arrays.asList(selected));
    }

    public ViewStreamMessage.PlateEnd end(int portalKey, int plateRevision) {
        return new ViewStreamMessage.PlateEnd(portalKey, plateRevision);
    }
}
