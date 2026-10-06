package art.arcane.wormholes.modded;

import art.arcane.wormholes.PortalToolHolderPolicy;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.math.Box;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class MinecraftPortalLookLabels implements AutoCloseable {
    private static final int LOOKING_SCAN_INTERVAL_TICKS = 3;
    private static final int TOOL_HOLDER_FALLBACK_INTERVAL_TICKS = 40;
    private static final int MAX_TOOL_HOLDER_VALIDATIONS_PER_SCAN = 64;
    private static final double TOOL_PREVIEW_RANGE = 32.0D;
    private static final double LOOK_RANGE_SQUARED = 64.0D;
    private static final double LOOK_DISTANCE = 16.0D;
    private static final int LOOK_SEGMENTS = (int) Math.ceil(LOOK_DISTANCE / 0.9D);
    private static final String GOLD_BOLD = "§6§l";

    private final WormholesModRuntime runtime;
    private final PortalToolHolderPolicy holders = new PortalToolHolderPolicy(TOOL_HOLDER_FALLBACK_INTERVAL_TICKS);
    private final MinecraftPortalToolPreview preview;
    private final Map<UUID, HeldTools> held = new HashMap<>();
    private final Map<UUID, ServerPlayer> online = new HashMap<>();
    private MinecraftPortalCandidates candidates = MinecraftPortalCandidates.EMPTY;
    private long candidatesRevision = -1L;
    private int portalCount;
    private long ticks;
    private long scanTick;

    MinecraftPortalLookLabels(WormholesModRuntime runtime) {
        this.runtime = runtime;
        preview = new MinecraftPortalToolPreview(runtime);
    }

    void tick() {
        runtime.requireServerThread();
        if (++ticks % LOOKING_SCAN_INTERVAL_TICKS != 0L) {
            return;
        }
        scanTick += LOOKING_SCAN_INTERVAL_TICKS;
        refreshCandidates();
        if (portalCount == 0) {
            return;
        }
        online.clear();
        for (ServerPlayer player : runtime.server().getPlayerList().getPlayers()) {
            online.put(player.getUUID(), player);
            trackHeldTools(player);
        }
        List<PortalToolHolderPolicy.Admission> admissions = holders.acquireValidations(online.keySet(), scanTick,
            MAX_TOOL_HOLDER_VALIDATIONS_PER_SCAN);
        for (PortalToolHolderPolicy.Admission admission : admissions) {
            ServerPlayer player = online.get(admission.playerId());
            if (player == null) {
                holders.rejectValidation(admission);
                continue;
            }
            validatePortalToolHolder(player, admission);
        }
        online.clear();
    }

    void playerDisconnected(ServerPlayer player) {
        holders.remove(player.getUUID());
        held.remove(player.getUUID());
    }

    @Override
    public void close() {
        holders.clear();
        held.clear();
        online.clear();
        preview.clear();
        candidates = MinecraftPortalCandidates.EMPTY;
        candidatesRevision = -1L;
        portalCount = 0;
    }

    static boolean hasPublicLookLabel(List<MinecraftPortal> portals) {
        for (MinecraftPortal portal : portals) {
            if (portal.isPublicLookLabel()) {
                return true;
            }
        }
        return false;
    }

    static boolean isLookingAt(ApertureCells geometry, Vec3 position, Vec3 eye, Vec3 look) {
        Box area = geometry.getArea();
        if (area == null || position.distanceToSqr((area.getXa() + area.getXb()) * 0.5D,
            (area.getYa() + area.getYb()) * 0.5D, (area.getZa() + area.getZb()) * 0.5D) >= LOOK_RANGE_SQUARED) {
            return false;
        }
        for (int segment = 0; segment <= LOOK_SEGMENTS; segment++) {
            double distance = LOOK_DISTANCE * segment / LOOK_SEGMENTS;
            double x = eye.x + look.x * distance;
            double y = eye.y + look.y * distance;
            double z = eye.z + look.z * distance;
            if (area.containsPrimitive(x, y, z) && geometry.containsBlock((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))) {
                return true;
            }
        }
        return false;
    }

    private void refreshCandidates() {
        long revision = runtime.portals().revision();
        if (revision == candidatesRevision) {
            return;
        }
        List<MinecraftPortal> portals = runtime.portals().snapshot();
        candidates = MinecraftPortalCandidates.capture(portals, TOOL_PREVIEW_RANGE);
        candidatesRevision = revision;
        portalCount = portals.size();
        preview.retain(portals);
    }

    private void trackHeldTools(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        HeldTools tools = held.get(player.getUUID());
        if (tools == null) {
            tools = new HeldTools();
            held.put(player.getUUID(), tools);
        } else if (tools.matches(main, off)) {
            return;
        }
        tools.update(main, off);
        holders.markDirty(player.getUUID());
    }

    private void validatePortalToolHolder(ServerPlayer player, PortalToolHolderPolicy.Admission admission) {
        boolean completed = false;
        try {
            if (player.hasDisconnected()) {
                holders.remove(player.getUUID());
                completed = true;
                return;
            }
            boolean holdingPortalTool = MinecraftPortalTools.isPortalTool(player.getMainHandItem())
                || MinecraftPortalTools.isPortalTool(player.getOffhandItem());
            List<MinecraftPortal> nearbyPortals = candidates.near(player.level().dimension(), player.getX(), player.getZ());
            boolean publicLookViewer = hasPublicLookLabel(nearbyPortals);
            holders.completeValidation(admission, holdingPortalTool || publicLookViewer, scanTick);
            completed = true;
            if (!holdingPortalTool && !publicLookViewer) {
                return;
            }
            if (holdingPortalTool) {
                preview.render(player, nearbyPortals);
            }
            scanLookingPortals(player, nearbyPortals, holdingPortalTool);
        } finally {
            if (!completed) {
                holders.rejectValidation(admission);
            }
        }
    }

    private void scanLookingPortals(ServerPlayer player, List<MinecraftPortal> portals, boolean holdingPortalTool) {
        if (runtime.menus().choosingDirection(player)) {
            return;
        }
        Vec3 position = player.position();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        for (MinecraftPortal portal : portals) {
            if (!holdingPortalTool && !portal.isPublicLookLabel()) {
                continue;
            }
            if (isLookingAt(portal.getGeometry(), position, eye, look)) {
                onLooking(player, portal, holdingPortalTool);
            }
        }
    }

    private void onLooking(ServerPlayer player, MinecraftPortal portal, boolean holdingWand) {
        if (holdingWand) {
            runtime.menus().shortTitles().send(player, portal.getId(), MinecraftPortalText.router(runtime, portal, false));
        } else if (portal.isPublicLookLabel()) {
            runtime.menus().shortTitles().send(player, portal.getId(), GOLD_BOLD + portal.getName());
        }
    }

    private static final class HeldTools {
        private ItemStack main;
        private ItemStack off;
        private boolean mainEmpty;
        private boolean offEmpty;

        private boolean matches(ItemStack currentMain, ItemStack currentOff) {
            return main == currentMain && off == currentOff && mainEmpty == currentMain.isEmpty() && offEmpty == currentOff.isEmpty();
        }

        private void update(ItemStack currentMain, ItemStack currentOff) {
            main = currentMain;
            off = currentOff;
            mainEmpty = currentMain.isEmpty();
            offEmpty = currentOff.isEmpty();
        }
    }
}
