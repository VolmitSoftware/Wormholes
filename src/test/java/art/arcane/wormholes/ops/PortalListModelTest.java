package art.arcane.wormholes.ops;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalListModelTest {
    private static final UUID OWNER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000000");

    @Test
    void filtersNarrowByWorldTypeOwnerAndState() {
        List<PortalListModel.PortalRow> rows = List.of(
            row("hub", "world", "PORTAL", true, true),
            row("nether", "world_nether", "GATEWAY", false, false),
            row("rtp", "world", "RTP", true, false));

        assertEquals(2, PortalListModel.page(rows,
            new PortalListModel.Filters("world", "", "", ""), 1).total());
        assertEquals(1, PortalListModel.page(rows,
            new PortalListModel.Filters("", "gateway", "", ""), 1).total());
        assertEquals(3, PortalListModel.page(rows,
            new PortalListModel.Filters("", "", OWNER.toString(), ""), 1).total());
        assertEquals(3, PortalListModel.page(rows,
            new PortalListModel.Filters("", "", "Steve", ""), 1).total());
        assertEquals(2, PortalListModel.page(rows,
            new PortalListModel.Filters("", "", "", "open"), 1).total());
        assertEquals(1, PortalListModel.page(rows,
            new PortalListModel.Filters("", "", "", "closed"), 1).total());
        assertEquals(1, PortalListModel.page(rows,
            new PortalListModel.Filters("", "", "", "linked"), 1).total());
        assertEquals(2, PortalListModel.page(rows,
            new PortalListModel.Filters("", "", "", "unlinked"), 1).total());
        assertEquals(0, PortalListModel.page(rows,
            new PortalListModel.Filters("", "", "", "sideways"), 1).total());
    }

    @Test
    void pagesAreOneBasedAndClampedToTheLastPage() {
        List<PortalListModel.PortalRow> rows = new ArrayList<>();
        for (int index = 0; index < PortalListModel.PAGE_SIZE * 2 + 3; index++) {
            rows.add(row("portal-" + index, "world", "PORTAL", true, true));
        }

        PortalListModel.Page first = PortalListModel.page(rows, PortalListModel.Filters.none(), 1);
        assertEquals(PortalListModel.PAGE_SIZE, first.rows().size());
        assertEquals(3, first.pages());
        assertEquals(rows.size(), first.total());
        assertEquals("portal-0", first.rows().get(0).name());

        PortalListModel.Page last = PortalListModel.page(rows, PortalListModel.Filters.none(), 99);
        assertEquals(3, last.page());
        assertEquals(3, last.rows().size());
        assertEquals("portal-16", last.rows().get(0).name());
        assertEquals("portal-18", last.rows().get(2).name());

        PortalListModel.Page zero = PortalListModel.page(rows, PortalListModel.Filters.none(), 0);
        assertEquals(1, zero.page());
    }

    @Test
    void anEmptyMatchStillReportsOnePage() {
        PortalListModel.Page page = PortalListModel.page(List.of(), PortalListModel.Filters.none(), 1);
        assertTrue(page.rows().isEmpty());
        assertEquals(1, page.pages());
        assertEquals(0, page.total());
    }

    private static PortalListModel.PortalRow row(String name, String world, String type, boolean open,
                                                 boolean linked) {
        return new PortalListModel.PortalRow(UUID.randomUUID(), name, world, type, open, linked,
            linked ? "elsewhere" : "", OWNER, "Steve");
    }
}
