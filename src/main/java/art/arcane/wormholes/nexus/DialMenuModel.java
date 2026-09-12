package art.arcane.wormholes.nexus;

import java.util.List;
import java.util.UUID;

/** Paging and highlight rules for the dial menu. Pure, so the layout is tested without a window. */
final class DialMenuModel {
    static final int ENTRIES_PER_PAGE = 45;

    private DialMenuModel() {
    }

    /** Every address this portal can dial, in address order, excluding the portal itself. */
    static List<NetworkMember> dialable(PortalNetwork network, UUID selfPortalId) {
        if (network == null) {
            return List.of();
        }
        return network.membersByAddress().stream()
                .filter(member -> selfPortalId == null || !member.portalId().equals(selfPortalId))
                .toList();
    }

    static int pageCount(int entryCount) {
        return Math.max(1, (entryCount + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE);
    }

    static int clampPage(int page, int pageCount) {
        return Math.max(0, Math.min(pageCount - 1, page));
    }

    static int pageStart(int page) {
        return page * ENTRIES_PER_PAGE;
    }

    static int pageEnd(int entryCount, int page) {
        return Math.min(entryCount, pageStart(page) + ENTRIES_PER_PAGE);
    }

    static int indexOf(List<NetworkMember> members, String address) {
        if (address == null || address.isBlank()) {
            return -1;
        }
        String normalized = NetworkMember.normalizeAddress(address);
        for (int index = 0; index < members.size(); index++) {
            if (members.get(index).address().equals(normalized)) {
                return index;
            }
        }
        return -1;
    }

    /** The page the menu opens on: the one holding the current address, or the first. */
    static int pageOf(int index) {
        return index < 0 ? 0 : index / ENTRIES_PER_PAGE;
    }

    static boolean isCurrent(NetworkMember member, String address) {
        return address != null && !address.isBlank()
                && member.address().equals(NetworkMember.normalizeAddress(address));
    }
}
