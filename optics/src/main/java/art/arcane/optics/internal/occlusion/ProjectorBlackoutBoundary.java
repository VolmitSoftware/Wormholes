package art.arcane.optics.internal.occlusion;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

public final class ProjectorBlackoutBoundary {
    private static final int FACE_COUNT = 6;

    private final LongOpenHashSet[] faces;

    public ProjectorBlackoutBoundary() {
        faces = new LongOpenHashSet[FACE_COUNT];
        for (int index = 0; index < FACE_COUNT; index++) {
            faces[index] = new LongOpenHashSet(64);
        }
    }

    public static int faceMask(int axis, int sign) {
        return 1 << faceIndex(axis, sign);
    }

    public void clear() {
        for (LongOpenHashSet face : faces) {
            face.clear();
        }
    }

    public void clearFace(int axis, int sign) {
        faces[faceIndex(axis, sign)].clear();
    }

    public void add(long key, int mask) {
        for (int index = 0; index < FACE_COUNT; index++) {
            if ((mask & (1 << index)) != 0) {
                faces[index].add(key);
            }
        }
    }

    public LongSet cells(int axis, int sign) {
        return faces[faceIndex(axis, sign)];
    }

    public boolean containsOther(long key, int axis, int sign) {
        int excluded = faceIndex(axis, sign);
        for (int index = 0; index < FACE_COUNT; index++) {
            if (index != excluded && faces[index].contains(key)) {
                return true;
            }
        }
        return false;
    }

    boolean isEmpty() {
        for (LongOpenHashSet face : faces) {
            if (!face.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static int faceIndex(int axis, int sign) {
        if (axis < 0 || axis > 2 || sign == 0) {
            throw new IllegalArgumentException("Invalid blackout boundary face");
        }
        return (axis * 2) + (sign > 0 ? 1 : 0);
    }
}
