package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.client.ClientCellRules;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.render.client.ClientRecursionPlanner;
import art.arcane.wormholes.render.client.ClientViewSweep;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.SectionPos;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

public final class ClientNestedViews {
    private final ClientViewSession session;
    private final ClientRecursionPlanner planner;
    private final Int2ObjectOpenHashMap<View> views;
    private final IntOpenHashSet planned;
    private final IdentityHashMap<ClientPortalGeometry, ClientPortal> matched;
    private final LongOpenHashSet next;
    private final LongOpenHashSet touchedSections;
    private final LongArrayList scratch;
    private final int[] cell;
    private final double[] point;
    private ClientProjectionApplier applier;
    private ProjectionOverlay overlay;

    public ClientNestedViews(ClientViewSession session, int depthCap, LongOpenHashSet touchedSections) {
        this.session = Objects.requireNonNull(session, "session");
        this.touchedSections = Objects.requireNonNull(touchedSections, "touchedSections");
        this.planner = new ClientRecursionPlanner(depthCap);
        this.views = new Int2ObjectOpenHashMap<>(4);
        this.planned = new IntOpenHashSet(4);
        this.matched = new IdentityHashMap<>(4);
        this.next = new LongOpenHashSet(256);
        this.scratch = new LongArrayList(256);
        this.cell = new int[3];
        this.point = new double[3];
    }

    public void bind(ClientProjectionApplier activeApplier, ProjectionOverlay activeOverlay) {
        views.clear();
        applier = Objects.requireNonNull(activeApplier, "activeApplier");
        overlay = Objects.requireNonNull(activeOverlay, "activeOverlay");
    }

    public void unbind() {
        views.clear();
        applier = null;
        overlay = null;
    }

    public void forget() {
        views.clear();
    }

    public boolean owns(int portalKey) {
        return views.containsKey(portalKey);
    }

    public int size() {
        return views.size();
    }

    public int cells(int portalKey) {
        View view = views.get(portalKey);
        return view == null ? 0 : view.display.size();
    }

    public boolean displays(int portalKey, int x, int y, int z) {
        View view = views.get(portalKey);
        return view != null && view.display.contains(ProjectionCellKey.pack(x, y, z));
    }

    public void contentCell(int portalKey, int x, int y, int z, int[] out) {
        View view = views.get(portalKey);
        if (view == null || view.nestedContent == null) {
            out[0] = x;
            out[1] = y;
            out[2] = z;
            return;
        }
        view.nestedContent.space().contentCell(x, y, z, out, point);
    }

    public long update(List<ClientPortal> roots, double eyeX, double eyeY, double eyeZ) {
        if (applier == null) {
            return 0L;
        }
        double quantizedX = quantize(eyeX);
        double quantizedY = quantize(eyeY);
        double quantizedZ = quantize(eyeZ);
        planned.clear();
        long applied = 0L;
        for (int index = 0; index < roots.size(); index++) {
            ClientPortal root = roots.get(index);
            ClientPortalGeometry geometry = root.geometry();
            if (geometry.recursionDepth() <= 0 || geometry.nested().isEmpty() || !root.ready()) {
                continue;
            }
            List<ClientRecursionPlanner.NestedCone> cones = planner.plan(geometry, quantizedX, quantizedY, quantizedZ);
            if (cones.isEmpty()) {
                continue;
            }
            matched.clear();
            matched.put(geometry, root);
            for (int coneIndex = 0; coneIndex < cones.size(); coneIndex++) {
                ClientRecursionPlanner.NestedCone cone = cones.get(coneIndex);
                List<ClientPortalGeometry> ancestors = cone.ancestors();
                ClientPortal parent = matched.get(ancestors.get(ancestors.size() - 1));
                ClientPortal child = parent == null ? null : child(parent.portalKey(), cone.geometry());
                if (child == null || child.plate() == null || child.sweep() == null) {
                    continue;
                }
                matched.put(cone.geometry(), child);
                if (!planned.add(child.portalKey())) {
                    continue;
                }
                View view = views.get(child.portalKey());
                if (view == null) {
                    view = new View(child.portalKey());
                    views.put(child.portalKey(), view);
                }
                IntArrayList ancestorKeys = view.ancestorKeys;
                ancestorKeys.clear();
                for (int ancestor = 0; ancestor < ancestors.size(); ancestor++) {
                    ClientPortal known = matched.get(ancestors.get(ancestor));
                    if (known != null) {
                        ancestorKeys.add(known.portalKey());
                    }
                }
                applied += view.update(cone, child, parent, geometry, quantizedX, quantizedY, quantizedZ);
            }
        }
        ObjectIterator<Int2ObjectMap.Entry<View>> iterator = views.int2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            View view = iterator.next().getValue();
            if (!planned.contains(view.portalKey)) {
                view.clear();
                iterator.remove();
            }
        }
        return applied;
    }

    public void drop(int portalKey) {
        View view = views.remove(portalKey);
        if (view != null && applier != null) {
            view.clear();
        }
    }

    private ClientPortal child(int parentKey, ClientPortalGeometry geometry) {
        ObjectIterator<ClientPortal> portals = session.portals().values().iterator();
        while (portals.hasNext()) {
            ClientPortal portal = portals.next();
            ClientPortalGeometry candidate = portal.geometry();
            if (candidate.parentPortalKey() == parentKey && candidate.equals(geometry)) {
                return portal;
            }
        }
        return null;
    }

    private void touch(long key) {
        touchedSections.add(SectionPos.asLong(ProjectionCellKey.unpackX(key) >> 4, ProjectionCellKey.unpackY(key) >> 4,
            ProjectionCellKey.unpackZ(key) >> 4));
    }

    private static double quantize(double value) {
        return Math.round(value * ClientViewSweep.EYE_STEPS_PER_BLOCK) / ClientViewSweep.EYE_STEPS_PER_BLOCK;
    }

    private final class View {
        private final int portalKey;
        private final IntArrayList ancestorKeys;
        private final LongOpenHashSet content;
        private final LongOpenHashSet display;
        private ClientNestedContent nestedContent;
        private ClientCellRules.Policy policy;
        private ClientPortalGeometry rootGeometry;
        private int parentKey;
        private double eyeX;
        private double eyeY;
        private double eyeZ;
        private boolean eyeKnown;

        private View(int portalKey) {
            this.portalKey = portalKey;
            this.ancestorKeys = new IntArrayList(2);
            this.content = new LongOpenHashSet(256);
            this.display = new LongOpenHashSet(256);
        }

        private long update(ClientRecursionPlanner.NestedCone cone, ClientPortal child, ClientPortal parent, ClientPortalGeometry root,
                            double quantizedX, double quantizedY, double quantizedZ) {
            parentKey = parent.portalKey();
            ClientViewSweep sweep = child.sweep();
            drain(sweep);
            sweep.sweep(cone.contentEyeX(), cone.contentEyeY(), cone.contentEyeZ(), 0.0D, 0.0D, 0.0D);
            drain(sweep);
            boolean contentChanged = nestedContent == null || nestedContent.plate() != child.plate() || rootGeometry != root
                || child.contentDirty();
            if (contentChanged) {
                nestedContent = new ClientNestedContent(child.plate(), cone.space(), session.palette());
                policy = ClientCellRules.Policy.of(child.geometry(), nestedContent.backingState());
                rootGeometry = root;
                child.contentClean();
            }
            boolean eyeMoved = !eyeKnown || quantizedX != eyeX || quantizedY != eyeY || quantizedZ != eyeZ;
            eyeKnown = true;
            eyeX = quantizedX;
            eyeY = quantizedY;
            eyeZ = quantizedZ;
            if (!eyeMoved && !contentChanged) {
                return 0L;
            }
            next.clear();
            LongIterator cells = content.iterator();
            while (cells.hasNext()) {
                long key = cells.nextLong();
                cone.space().displayCell(ProjectionCellKey.unpackX(key), ProjectionCellKey.unpackY(key), ProjectionCellKey.unpackZ(key), cell, point);
                if (insideParent(parent, cell[0], cell[1], cell[2]) && cone.visible(cell[0] + 0.5D, cell[1] + 0.5D, cell[2] + 0.5D)) {
                    next.add(ProjectionCellKey.pack(cell[0], cell[1], cell[2]));
                }
            }
            long applied = 0L;
            scratch.clear();
            LongIterator shown = display.iterator();
            while (shown.hasNext()) {
                long key = shown.nextLong();
                if (!next.contains(key)) {
                    scratch.add(key);
                }
            }
            for (int index = 0; index < scratch.size(); index++) {
                long key = scratch.getLong(index);
                display.remove(key);
                release(key);
            }
            if (contentChanged) {
                LongIterator kept = display.iterator();
                while (kept.hasNext()) {
                    if (claim(kept.nextLong())) {
                        applied++;
                    }
                }
            }
            LongIterator entering = next.iterator();
            while (entering.hasNext()) {
                long key = entering.nextLong();
                if (display.contains(key)) {
                    continue;
                }
                if (claimable(key)) {
                    display.add(key);
                    if (claim(key)) {
                        applied++;
                    }
                }
            }
            return applied;
        }

        private boolean insideParent(ClientPortal parent, int x, int y, int z) {
            View parentView = views.get(parent.portalKey());
            if (parentView != null) {
                return parentView.display.contains(ProjectionCellKey.pack(x, y, z));
            }
            return parent.sweep().applied(x, y, z);
        }

        private void drain(ClientViewSweep sweep) {
            LongArrayList exited = sweep.exited();
            for (int index = 0; index < exited.size(); index++) {
                content.remove(exited.getLong(index));
            }
            exited.clear();
            LongArrayList entered = sweep.entered();
            for (int index = 0; index < entered.size(); index++) {
                content.add(entered.getLong(index));
            }
            entered.clear();
        }

        private boolean claimable(long key) {
            ProjectionOverlay.Entry entry = overlay.get(key);
            return entry == null || entry.portalKey() == portalKey || ancestorKeys.contains(entry.portalKey());
        }

        private boolean claim(long key) {
            ProjectionOverlay.Entry entry = overlay.get(key);
            if (entry != null && entry.portalKey() != portalKey) {
                if (!ancestorKeys.contains(entry.portalKey())) {
                    return false;
                }
                entry.portalKey(portalKey);
            }
            touch(key);
            return applier.enter(key, portalKey, nestedContent, policy, false);
        }

        private void release(long key) {
            if (applier.exit(key, portalKey)) {
                touch(key);
            }
            ClientPortal parent = session.portal(parentKey);
            if (parent == null || !parent.ready()) {
                return;
            }
            View parentView = views.get(parentKey);
            if (parentView != null) {
                if (parentView.display.contains(key)) {
                    parentView.claim(key);
                }
                return;
            }
            int x = ProjectionCellKey.unpackX(key);
            int y = ProjectionCellKey.unpackY(key);
            int z = ProjectionCellKey.unpackZ(key);
            ClientViewSweep sweep = parent.sweep();
            ProjectionOverlay.Entry entry = overlay.get(key);
            if (sweep.applied(x, y, z) && (entry == null || entry.portalKey() == parentKey)) {
                applier.enter(key, parentKey, parent.content(), parent.policy(), sweep.shell(x, y, z));
            }
        }

        private void clear() {
            LongIterator shown = display.iterator();
            while (shown.hasNext()) {
                release(shown.nextLong());
            }
            display.clear();
            content.clear();
        }
    }
}
