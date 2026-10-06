package art.arcane.optics.client;

import java.util.Arrays;
import java.util.Objects;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.volume.ViewVolume;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.frame.ProjectorFrameTransform;
import art.arcane.optics.volume.PlaneWindow;
import art.arcane.optics.plate.PlateBox;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import art.arcane.optics.aperture.ApertureDescriptor;

public final class ClientSweep {
    public static final double EYE_STEPS_PER_BLOCK = 20.0D;
    public static final double EYE_QUANTUM_BLOCKS = 1.0D / EYE_STEPS_PER_BLOCK;
    public static final double DEFAULT_HYSTERESIS_BLOCKS = 0.25D;
    public static final long MAX_BOUNDS_CELLS = 1L << 24;

    private final double hysteresis;
    private final LongArrayList entered;
    private final LongArrayList exited;
    private final LongArrayList reshelled;
    private final double[] slabBounds;
    private final int[] axisMin;
    private final int[] axisMax;
    private final int[] cell;
    private ApertureDescriptor geometry;
    private ApertureCells aperture;
    private Box area;
    private Frame frame;
    private Layout layout;
    private double originX;
    private double originY;
    private double originZ;
    private double clearance;
    private double maxDepth;
    private boolean blackout;
    private long[] applied;
    private long[] shell;
    private long[] next;
    private long[] nextShell;
    private long[] outer;
    private boolean dirty;
    private boolean eyeKnown;
    private long eyeStepX;
    private long eyeStepY;
    private long eyeStepZ;
    private double eyeDot;
    private boolean eyeFrontSide;
    private int appliedCount;
    private int farSlab;
    private int farRightMin;
    private int farRightMax;
    private int farUpMin;
    private int farUpMax;
    private double farDepth;

    public ClientSweep(ApertureDescriptor geometry, PlateBox bounds, double hysteresis) {
        if (!Double.isFinite(hysteresis) || hysteresis < 0.0D) {
            throw new IllegalArgumentException("hysteresis must be a finite non-negative block distance");
        }
        this.hysteresis = hysteresis;
        this.entered = new LongArrayList(256);
        this.exited = new LongArrayList(256);
        this.reshelled = new LongArrayList(64);
        this.slabBounds = new double[4];
        this.axisMin = new int[3];
        this.axisMax = new int[3];
        this.cell = new int[3];
        this.applied = new long[0];
        this.shell = new long[0];
        reconfigure(geometry, bounds);
    }

    public void reconfigure(ApertureDescriptor geometry, PlateBox bounds) {
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(bounds, "bounds");
        if (!geometry.valid()) {
            throw new IllegalArgumentException("invalid client portal geometry: " + geometry);
        }
        if (bounds.cells() > MAX_BOUNDS_CELLS) {
            throw new IllegalArgumentException("sweep bounds of " + bounds.cells() + " cells exceed " + MAX_BOUNDS_CELLS);
        }
        clearOutput();
        this.geometry = geometry;
        this.aperture = geometry.aperture();
        this.area = aperture.getArea();
        this.frame = geometry.frame();
        Vec3d center = area.center();
        this.originX = center.getX();
        this.originY = center.getY();
        this.originZ = center.getZ();
        this.clearance = ProjectorFrameTransform.portalPlaneClearance(area, frame);
        this.maxDepth = geometry.depthBlocks() + clearance;
        this.blackout = geometry.blackoutPolicy() != ApertureDescriptor.BLACKOUT_OFF;
        Layout nextLayout = new Layout(bounds, ApertureDescriptor.axisOf(frame.getNormal()),
            ApertureDescriptor.axisOf(frame.getRight()), ApertureDescriptor.axisOf(frame.getUp()));
        if (layout == null || !layout.equals(nextLayout)) {
            remap(nextLayout);
        }
        this.dirty = true;
    }

    public boolean sweep(double eyeX, double eyeY, double eyeZ, double velocityX, double velocityY, double velocityZ) {
        clearOutput();
        long stepX = Math.round((eyeX + velocityX) * EYE_STEPS_PER_BLOCK);
        long stepY = Math.round((eyeY + velocityY) * EYE_STEPS_PER_BLOCK);
        long stepZ = Math.round((eyeZ + velocityZ) * EYE_STEPS_PER_BLOCK);
        if (!dirty && eyeKnown && stepX == eyeStepX && stepY == eyeStepY && stepZ == eyeStepZ) {
            return false;
        }
        dirty = false;
        eyeKnown = true;
        eyeStepX = stepX;
        eyeStepY = stepY;
        eyeStepZ = stepZ;
        cone(stepX / EYE_STEPS_PER_BLOCK, stepY / EYE_STEPS_PER_BLOCK, stepZ / EYE_STEPS_PER_BLOCK);
        emitChanges();
        return !entered.isEmpty() || !exited.isEmpty() || !reshelled.isEmpty();
    }

    public void clear() {
        clearOutput();
        for (int word = 0; word < applied.length; word++) {
            long bits = applied[word];
            while (bits != 0L) {
                int bit = Long.numberOfTrailingZeros(bits);
                bits &= bits - 1L;
                exited.add(layout.key((word << 6) + bit, cell));
            }
        }
        Arrays.fill(applied, 0L);
        Arrays.fill(shell, 0L);
        appliedCount = 0;
        eyeKnown = false;
        dirty = true;
    }

    public LongArrayList entered() {
        return entered;
    }

    public LongArrayList exited() {
        return exited;
    }

    public LongArrayList reshelled() {
        return reshelled;
    }

    public boolean applied(int x, int y, int z) {
        int index = layout.index(x, y, z);
        return index >= 0 && (applied[index >>> 6] & (1L << index)) != 0L;
    }

    public boolean shell(int x, int y, int z) {
        int index = layout.index(x, y, z);
        return index >= 0 && (shell[index >>> 6] & (1L << index)) != 0L;
    }

    public int appliedCount() {
        return appliedCount;
    }

    public void appliedKeys(LongArrayList out) {
        layout.resetCursor();
        for (int word = 0; word < applied.length; word++) {
            emit(applied[word], word, out);
        }
    }

    public double eyeDot() {
        return eyeDot;
    }

    public boolean eyeFrontSide() {
        return eyeFrontSide;
    }

    public ApertureDescriptor geometry() {
        return geometry;
    }

    public PlateBox bounds() {
        return layout.bounds;
    }

    public double hysteresis() {
        return hysteresis;
    }

    private void clearOutput() {
        entered.clear();
        exited.clear();
        reshelled.clear();
    }

    private void remap(Layout nextLayout) {
        int words = nextLayout.words();
        long[] remappedApplied = new long[words];
        long[] remappedShell = new long[words];
        int count = 0;
        for (int word = 0; word < applied.length; word++) {
            long bits = applied[word];
            while (bits != 0L) {
                int bit = Long.numberOfTrailingZeros(bits);
                bits &= bits - 1L;
                int index = (word << 6) + bit;
                long key = layout.key(index, cell);
                int remapped = nextLayout.index(cell[0], cell[1], cell[2]);
                if (remapped < 0) {
                    exited.add(key);
                    continue;
                }
                remappedApplied[remapped >>> 6] |= 1L << remapped;
                if ((shell[word] & (1L << bit)) != 0L) {
                    remappedShell[remapped >>> 6] |= 1L << remapped;
                }
                count++;
            }
        }
        layout = nextLayout;
        applied = remappedApplied;
        shell = remappedShell;
        next = new long[words];
        nextShell = new long[words];
        outer = new long[words];
        appliedCount = count;
    }

    private void cone(double eyeX, double eyeY, double eyeZ) {
        Arrays.fill(next, 0L);
        Arrays.fill(nextShell, 0L);
        Face normal = frame.getNormal();
        double relX = eyeX - originX;
        double relY = eyeY - originY;
        double relZ = eyeZ - originZ;
        eyeFrontSide = (relX * normal.x()) + (relY * normal.y()) + (relZ * normal.z()) >= 0.0D;
        Frame projectionFrame = frame.view(eyeFrontSide);
        Face projectionNormal = projectionFrame.getNormal();
        eyeDot = (relX * projectionNormal.x()) + (relY * projectionNormal.y()) + (relZ * projectionNormal.z());
        if (eyeFrontSide != geometry.frontSide() || layout.cells() == 0L || geometry.depthBlocks() <= 0) {
            return;
        }
        fill(next, projectionFrame, geometry.aperturePadding() + hysteresis, blackout, eyeX, eyeY, eyeZ);
        if (hysteresis <= 0.0D || appliedCount == 0) {
            return;
        }
        Arrays.fill(outer, 0L);
        fill(outer, projectionFrame, geometry.aperturePadding() + (hysteresis * 2.0D), false, eyeX, eyeY, eyeZ);
        for (int word = 0; word < next.length; word++) {
            next[word] |= applied[word] & outer[word];
        }
    }

    private void fill(long[] mask, Frame projectionFrame, double padding, boolean shellPass,
                      double eyeX, double eyeY, double eyeZ) {
        if (!bound(padding, eyeX, eyeY, eyeZ)) {
            return;
        }
        int normalAxis = layout.normalAxis;
        int rightAxis = layout.rightAxis;
        int upAxis = layout.upAxis;
        Face projectionRight = projectionFrame.getRight();
        Face projectionUp = projectionFrame.getUp();
        int rightSign = projectionRight.x() + projectionRight.y() + projectionRight.z();
        int upSign = projectionUp.x() + projectionUp.y() + projectionUp.z();
        double normalOrigin = component(normalAxis);
        double rightOrigin = component(rightAxis);
        double upOrigin = component(upAxis);
        double projectionFacing = axisComponent(projectionFrame.getNormal(), normalAxis);
        double localFacing = axisComponent(frame.getNormal(), normalAxis);
        PlaneWindow window = PlaneWindow.create(aperture, area, projectionFrame,
            originX, originY, originZ, padding, eyeDot);
        PlaneWindow blackoutWindow = shellPass
            ? PlaneWindow.create(aperture, area, projectionFrame, originX, originY, originZ, 0.0D, eyeDot)
            : null;
        boolean fullAperture = aperture.isFullCuboid();
        farSlab = Integer.MIN_VALUE;
        farDepth = Double.NEGATIVE_INFINITY;
        for (int n = axisMin[normalAxis]; n <= axisMax[normalAxis]; n++) {
            double cellDot = localFacing * ((n + 0.5D) - normalOrigin);
            if (!ProjectorFrameTransform.projectsBehindPortalPlane(cellDot, eyeFrontSide, clearance)
                || Math.abs(cellDot) > maxDepth) {
                continue;
            }
            double slabSignedDistance = projectionFacing * ((n + 0.5D) - normalOrigin);
            if (!window.slabWindow(eyeX, eyeY, eyeZ, slabSignedDistance, slabBounds)) {
                continue;
            }
            int rightMin = PlaneWindow.slabBlockMin(slabBounds[0], slabBounds[1], rightSign, rightOrigin, axisMin[rightAxis]);
            int rightMax = PlaneWindow.slabBlockMax(slabBounds[0], slabBounds[1], rightSign, rightOrigin, axisMax[rightAxis]);
            int upMin = PlaneWindow.slabBlockMin(slabBounds[2], slabBounds[3], upSign, upOrigin, axisMin[upAxis]);
            int upMax = PlaneWindow.slabBlockMax(slabBounds[2], slabBounds[3], upSign, upOrigin, axisMax[upAxis]);
            if (rightMin > rightMax || upMin > upMax) {
                continue;
            }
            if (fullAperture) {
                fillSlab(mask, n, rightMin, rightMax, upMin, upMax);
            } else {
                fillSlabCells(mask, window, n, rightMin, rightMax, upMin, upMax, slabSignedDistance, eyeX, eyeY, eyeZ);
            }
            if (shellPass) {
                markRim(mask, blackoutWindow, n, rightMin, rightMax, upMin, upMax, slabSignedDistance, eyeX, eyeY, eyeZ);
                trackFarSlab(blackoutWindow, n, cellDot, slabSignedDistance, rightSign, upSign, rightOrigin, upOrigin,
                    eyeX, eyeY, eyeZ);
            }
        }
        if (shellPass && farSlab != Integer.MIN_VALUE) {
            markFarFace(mask, blackoutWindow, fullAperture, projectionFacing * ((farSlab + 0.5D) - normalOrigin), eyeX, eyeY, eyeZ);
        }
    }

    private boolean bound(double padding, double eyeX, double eyeY, double eyeZ) {
        double depth = geometry.depthBlocks();
        ViewVolume frustum = new ViewVolume(new Vec3d(eyeX, eyeY, eyeZ), aperture,
            new ViewVolume.Options(depth, depth, geometry.nearPlanePadding(), geometry.frustumCullingRatio(), padding));
        Box region = frustum.getRegion();
        axisMin[0] = ProjectorFrameTransform.minBlockForCenter(region.getXa());
        axisMin[1] = ProjectorFrameTransform.minBlockForCenter(region.getYa());
        axisMin[2] = ProjectorFrameTransform.minBlockForCenter(region.getZa());
        axisMax[0] = ProjectorFrameTransform.maxBlockForCenter(region.getXb());
        axisMax[1] = ProjectorFrameTransform.maxBlockForCenter(region.getYb());
        axisMax[2] = ProjectorFrameTransform.maxBlockForCenter(region.getZb());
        int normalAxis = layout.normalAxis;
        double facing = axisComponent(frame.getNormal(), normalAxis);
        double normalOrigin = component(normalAxis);
        double signedMin = eyeFrontSide ? -maxDepth : clearance;
        double signedMax = eyeFrontSide ? -clearance : maxDepth;
        double centerA = normalOrigin + (signedMin / facing);
        double centerB = normalOrigin + (signedMax / facing);
        axisMin[normalAxis] = Math.max(axisMin[normalAxis], ProjectorFrameTransform.minBlockForCenter(Math.min(centerA, centerB)));
        axisMax[normalAxis] = Math.min(axisMax[normalAxis], ProjectorFrameTransform.maxBlockForCenter(Math.max(centerA, centerB)));
        for (int axis = 0; axis < 3; axis++) {
            axisMin[axis] = Math.max(axisMin[axis], layout.min[axis]);
            axisMax[axis] = Math.min(axisMax[axis], layout.max[axis]);
            if (axisMin[axis] > axisMax[axis]) {
                return false;
            }
        }
        return true;
    }

    private void fillSlab(long[] mask, int n, int rightMin, int rightMax, int upMin, int upMax) {
        int span = upMax - upMin;
        for (int r = rightMin; r <= rightMax; r++) {
            int start = layout.slabIndex(n, r, upMin);
            setRange(mask, start, start + span);
        }
    }

    private void fillSlabCells(long[] mask, PlaneWindow window, int n, int rightMin, int rightMax, int upMin, int upMax,
                               double slabSignedDistance, double eyeX, double eyeY, double eyeZ) {
        for (int r = rightMin; r <= rightMax; r++) {
            int start = layout.slabIndex(n, r, upMin);
            layout.coordinates(n, r, upMin, cell);
            window.prepareRow(layout.upAxis, eyeX, eyeY, eyeZ, cell[0] + 0.5D, cell[1] + 0.5D, cell[2] + 0.5D,
                slabSignedDistance);
            for (int u = upMin; u <= upMax; u++) {
                if (window.containsRowCell(u)) {
                    int index = start + (u - upMin);
                    mask[index >>> 6] |= 1L << index;
                }
            }
        }
    }

    private void markRim(long[] mask, PlaneWindow blackoutWindow, int n, int rightMin, int rightMax, int upMin, int upMax,
                         double slabSignedDistance, double eyeX, double eyeY, double eyeZ) {
        for (int r = rightMin; r <= rightMax; r++) {
            if (r == rightMin || r == rightMax) {
                for (int u = upMin; u <= upMax; u++) {
                    markRimCell(mask, blackoutWindow, n, r, u, slabSignedDistance, eyeX, eyeY, eyeZ);
                }
                continue;
            }
            markRimCell(mask, blackoutWindow, n, r, upMin, slabSignedDistance, eyeX, eyeY, eyeZ);
            if (upMax != upMin) {
                markRimCell(mask, blackoutWindow, n, r, upMax, slabSignedDistance, eyeX, eyeY, eyeZ);
            }
        }
    }

    private void markRimCell(long[] mask, PlaneWindow blackoutWindow, int n, int r, int u,
                             double slabSignedDistance, double eyeX, double eyeY, double eyeZ) {
        int index = layout.slabIndex(n, r, u);
        if ((mask[index >>> 6] & (1L << index)) == 0L) {
            return;
        }
        layout.coordinates(n, r, u, cell);
        if (blackoutWindow.intersectsBlockSilhouette(eyeX, eyeY, eyeZ, cell[0] + 0.5D, cell[1] + 0.5D, cell[2] + 0.5D,
            slabSignedDistance)) {
            return;
        }
        nextShell[index >>> 6] |= 1L << index;
    }

    private void trackFarSlab(PlaneWindow blackoutWindow, int n, double cellDot, double slabSignedDistance,
                              int rightSign, int upSign, double rightOrigin, double upOrigin,
                              double eyeX, double eyeY, double eyeZ) {
        double depth = Math.abs(cellDot);
        if (depth <= farDepth || !blackoutWindow.slabWindow(eyeX, eyeY, eyeZ, slabSignedDistance, slabBounds)) {
            return;
        }
        int rightAxis = layout.rightAxis;
        int upAxis = layout.upAxis;
        int rightMin = PlaneWindow.slabBlockMin(slabBounds[0], slabBounds[1], rightSign, rightOrigin, axisMin[rightAxis]);
        int rightMax = PlaneWindow.slabBlockMax(slabBounds[0], slabBounds[1], rightSign, rightOrigin, axisMax[rightAxis]);
        int upMin = PlaneWindow.slabBlockMin(slabBounds[2], slabBounds[3], upSign, upOrigin, axisMin[upAxis]);
        int upMax = PlaneWindow.slabBlockMax(slabBounds[2], slabBounds[3], upSign, upOrigin, axisMax[upAxis]);
        if (rightMin > rightMax || upMin > upMax) {
            return;
        }
        farDepth = depth;
        farSlab = n;
        farRightMin = rightMin;
        farRightMax = rightMax;
        farUpMin = upMin;
        farUpMax = upMax;
    }

    private void markFarFace(long[] mask, PlaneWindow blackoutWindow, boolean fullAperture, double slabSignedDistance,
                             double eyeX, double eyeY, double eyeZ) {
        for (int r = farRightMin; r <= farRightMax; r++) {
            for (int u = farUpMin; u <= farUpMax; u++) {
                int index = layout.slabIndex(farSlab, r, u);
                if ((mask[index >>> 6] & (1L << index)) == 0L) {
                    continue;
                }
                if (!fullAperture) {
                    layout.coordinates(farSlab, r, u, cell);
                    if (!blackoutWindow.containsRayIntersection(eyeX, eyeY, eyeZ, cell[0] + 0.5D, cell[1] + 0.5D, cell[2] + 0.5D,
                        slabSignedDistance)) {
                        continue;
                    }
                }
                nextShell[index >>> 6] |= 1L << index;
            }
        }
    }

    private void emitChanges() {
        int count = 0;
        layout.resetCursor();
        for (int word = 0; word < next.length; word++) {
            long after = next[word];
            emit(after & ~applied[word], word, entered);
            count += Long.bitCount(after);
        }
        layout.resetCursor();
        for (int word = 0; word < next.length; word++) {
            emit(applied[word] & ~next[word], word, exited);
        }
        layout.resetCursor();
        for (int word = 0; word < next.length; word++) {
            emit(next[word] & applied[word] & (shell[word] ^ nextShell[word]), word, reshelled);
        }
        appliedCount = count;
        long[] appliedSwap = applied;
        applied = next;
        next = appliedSwap;
        long[] shellSwap = shell;
        shell = nextShell;
        nextShell = shellSwap;
    }

    private void emit(long bits, int word, LongArrayList out) {
        while (bits != 0L) {
            int bit = Long.numberOfTrailingZeros(bits);
            bits &= bits - 1L;
            out.add(layout.nextKey((word << 6) + bit));
        }
    }

    private double component(int axis) {
        return axis == 0 ? originX : axis == 1 ? originY : originZ;
    }

    private static double axisComponent(Face direction, int axis) {
        return axis == 0 ? direction.x() : axis == 1 ? direction.y() : direction.z();
    }

    private static void setRange(long[] mask, int first, int last) {
        int firstWord = first >>> 6;
        int lastWord = last >>> 6;
        long firstMask = -1L << first;
        long lastMask = -1L >>> (63 - (last & 63));
        if (firstWord == lastWord) {
            mask[firstWord] |= firstMask & lastMask;
            return;
        }
        mask[firstWord] |= firstMask;
        Arrays.fill(mask, firstWord + 1, lastWord, -1L);
        mask[lastWord] |= lastMask;
    }

    private static final class Layout {
        private final PlateBox bounds;
        private final int normalAxis;
        private final int rightAxis;
        private final int upAxis;
        private final int[] min;
        private final int[] max;
        private final int sizeRight;
        private final int sizeUp;
        private final int slabCells;
        private int cursorIndex;
        private int cursorN;
        private int cursorR;
        private int cursorU;

        private Layout(PlateBox bounds, int normalAxis, int rightAxis, int upAxis) {
            this.bounds = bounds;
            this.normalAxis = normalAxis;
            this.rightAxis = rightAxis;
            this.upAxis = upAxis;
            int[] sizes = {bounds.sizeX(), bounds.sizeY(), bounds.sizeZ()};
            this.min = new int[] {bounds.minX(), bounds.minY(), bounds.minZ()};
            this.max = new int[] {bounds.minX() + sizes[0] - 1, bounds.minY() + sizes[1] - 1, bounds.minZ() + sizes[2] - 1};
            this.sizeRight = sizes[rightAxis];
            this.sizeUp = sizes[upAxis];
            this.slabCells = sizeRight * sizeUp;
        }

        private long cells() {
            return bounds.cells();
        }

        private int words() {
            return (int) ((bounds.cells() + 63L) >>> 6);
        }

        private int index(int x, int y, int z) {
            if (x < min[0] || x > max[0] || y < min[1] || y > max[1] || z < min[2] || z > max[2]) {
                return -1;
            }
            int n = normalAxis == 0 ? x : normalAxis == 1 ? y : z;
            int r = rightAxis == 0 ? x : rightAxis == 1 ? y : z;
            int u = upAxis == 0 ? x : upAxis == 1 ? y : z;
            return slabIndex(n, r, u);
        }

        private int slabIndex(int n, int r, int u) {
            return (((n - min[normalAxis]) * sizeRight) + (r - min[rightAxis])) * sizeUp + (u - min[upAxis]);
        }

        private void coordinates(int n, int r, int u, int[] out) {
            out[normalAxis] = n;
            out[rightAxis] = r;
            out[upAxis] = u;
        }

        private void resetCursor() {
            cursorIndex = Integer.MIN_VALUE;
        }

        private long nextKey(int index) {
            int delta = index - cursorIndex;
            if (cursorIndex != Integer.MIN_VALUE && delta >= 0 && cursorU + delta < sizeUp) {
                cursorU += delta;
            } else {
                cursorN = index / slabCells;
                int remainder = index - (cursorN * slabCells);
                cursorR = remainder / sizeUp;
                cursorU = remainder - (cursorR * sizeUp);
            }
            cursorIndex = index;
            int n = min[normalAxis] + cursorN;
            int r = min[rightAxis] + cursorR;
            int u = min[upAxis] + cursorU;
            int x = normalAxis == 0 ? n : rightAxis == 0 ? r : u;
            int y = normalAxis == 1 ? n : rightAxis == 1 ? r : u;
            int z = normalAxis == 2 ? n : rightAxis == 2 ? r : u;
            return CellKeys.pack(x, y, z);
        }

        private long key(int index, int[] out) {
            int n = index / slabCells;
            int remainder = index - (n * slabCells);
            int r = remainder / sizeUp;
            int u = remainder - (r * sizeUp);
            out[normalAxis] = min[normalAxis] + n;
            out[rightAxis] = min[rightAxis] + r;
            out[upAxis] = min[upAxis] + u;
            return CellKeys.pack(out[0], out[1], out[2]);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Layout that && bounds.equals(that.bounds)
                && normalAxis == that.normalAxis && rightAxis == that.rightAxis && upAxis == that.upAxis;
        }

        @Override
        public int hashCode() {
            return Objects.hash(bounds, normalAxis, rightAxis, upAxis);
        }
    }
}
