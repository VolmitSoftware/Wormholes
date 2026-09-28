package art.arcane.wormholes.portal;

import org.bukkit.Material;

public final class BukkitPortalSurfaces {
    private BukkitPortalSurfaces() {
    }

    public static boolean isTransparentSkin(String skin) {
        return PortalSurfaceSkins.isTransparentSkin(skin, BukkitPortalSurfaces::isNonOccludingBlock);
    }

    private static boolean isNonOccludingBlock(String id) {
        Material material = Material.matchMaterial(id);
        return material != null && material.isBlock() && !material.isOccluding();
    }
}
