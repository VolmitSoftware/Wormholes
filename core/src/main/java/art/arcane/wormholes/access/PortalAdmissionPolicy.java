package art.arcane.wormholes.access;

import java.util.UUID;

public final class PortalAdmissionPolicy {
    private PortalAdmissionPolicy() {
    }

    public static boolean allows(Admission admission) {
        if (admission.bypass()) {
            return true;
        }
        if (!admission.hasAccess()) {
            return admission.permissionAllowed();
        }
        if (admission.role() == PortalRole.DENIED) {
            return false;
        }
        if (admission.role() != null && admission.role().trusted()
            || admission.playerId().equals(admission.ownerId()) || admission.groupAllowed()) {
            return true;
        }
        return !admission.whitelistOnly() && admission.permissionAllowed();
    }

    public record Admission(UUID playerId, UUID ownerId, PortalRole role, boolean bypass, boolean hasAccess,
                            boolean groupAllowed, boolean whitelistOnly, boolean permissionAllowed) {
    }
}
