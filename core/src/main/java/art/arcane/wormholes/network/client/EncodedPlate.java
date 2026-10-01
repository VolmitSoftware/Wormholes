package art.arcane.wormholes.network.client;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import art.arcane.wormholes.network.replication.XxHash64;
import art.arcane.wormholes.render.plate.PlateBox;

public final class EncodedPlate {
    private final PlateSectionBox sections;
    private final PlateBox cells;
    private final int backingState;
    private final Brick[] bricks;
    private final byte[][] bodies;
    private final int[] referencedIds;
    private final long totalBytes;
    private final int[] kindCounts;
    private volatile long saltedFor;
    private volatile long[] saltedHashes;

    EncodedPlate(PlateSectionBox sections, PlateBox cells, int backingState, Brick[] bricks, byte[][] bodies, int[] referencedIds,
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

    public PlateBox cells() {
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

    public ClientViewMessage.PlateBegin begin(int portalKey, int plateRevision, boolean withHashes, long salt) {
        long[] manifest = withHashes ? hashes(salt).clone() : null;
        return new ClientViewMessage.PlateBegin(portalKey, plateRevision, sections, cells, backingState, bricks.length, manifest);
    }

    public ClientViewMessage.PlateBricks bricksMessage(int portalKey, int plateRevision) {
        return new ClientViewMessage.PlateBricks(portalKey, plateRevision, Arrays.asList(bricks));
    }

    public ClientViewMessage.PlateBricks bricksMessage(int portalKey, int plateRevision, ClientViewMessage.BrickMiss.Plate missed) {
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
        return new ClientViewMessage.PlateBricks(portalKey, plateRevision, Arrays.asList(selected));
    }

    public ClientViewMessage.PlateEnd end(int portalKey, int plateRevision) {
        return new ClientViewMessage.PlateEnd(portalKey, plateRevision);
    }
}
