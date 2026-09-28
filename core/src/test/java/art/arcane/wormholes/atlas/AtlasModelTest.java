package art.arcane.wormholes.atlas;

import art.arcane.wormholes.atlas.AtlasModel.Filter;
import art.arcane.wormholes.atlas.AtlasModel.Row;
import art.arcane.wormholes.atlas.AtlasModel.SortMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtlasModelTest {
    private static final UUID MARKET = UUID.randomUUID();
    private static final UUID DOCKS = UUID.randomUUID();
    private static final UUID ATTIC = UUID.randomUUID();

    @Test
    void discoveryHidesUnlistedPortalsTheePlayerHasNeverStoodAt() {
        AtlasPlayerState state = new AtlasPlayerState(UUID.randomUUID());
        state.discover(MARKET);
        List<Row> candidates = List.of(
                row(MARKET, "market", "world", 10.0D, true, false),
                row(DOCKS, "docks", "world", 20.0D, true, false),
                row(ATTIC, "attic", "world", 30.0D, true, true));

        List<Row> visible = AtlasModel.visible(candidates, state, true, Filter.ALL);

        assertEquals(List.of("market", "attic"), visible.stream().map(Row::name).toList());
    }

    @Test
    void turningDiscoveryOffShowsEveryPortalThePlayerMayUse() {
        AtlasPlayerState state = new AtlasPlayerState(UUID.randomUUID());
        List<Row> candidates = List.of(
                row(MARKET, "market", "world", 10.0D, true, false),
                row(DOCKS, "docks", "world", 20.0D, true, false));

        assertEquals(2, AtlasModel.visible(candidates, state, false, Filter.ALL).size());
        assertTrue(AtlasModel.visible(candidates, state, true, Filter.ALL).isEmpty());
    }

    @Test
    void theFavoritesFilterKeepsOnlyPinnedPortalsAndRecentsKeepsRecentOrder() {
        AtlasPlayerState state = new AtlasPlayerState(UUID.randomUUID());
        state.toggleFavorite(DOCKS, 27);
        state.recordRecent(MARKET, 10);
        state.recordRecent(ATTIC, 10);
        List<Row> candidates = List.of(
                row(MARKET, "market", "world", 10.0D, true, true),
                row(DOCKS, "docks", "world", 20.0D, true, true),
                row(ATTIC, "attic", "world", 30.0D, true, true));

        assertEquals(List.of("docks"),
                AtlasModel.visible(candidates, state, false, Filter.FAVORITES).stream().map(Row::name).toList());
        assertEquals(List.of("attic", "market"),
                AtlasModel.visible(candidates, state, false, Filter.RECENTS).stream().map(Row::name).toList());
    }

    @Test
    void smartSortPutsFavouritesFirstThenOpenPortalsThenTheClosest() {
        AtlasPlayerState state = new AtlasPlayerState(UUID.randomUUID());
        state.toggleFavorite(ATTIC, 27);
        List<Row> candidates = List.of(
                row(MARKET, "market", "world", 90.0D, true, true),
                row(DOCKS, "docks", "world", 10.0D, false, true),
                row(ATTIC, "attic", "world", 500.0D, true, true));

        List<Row> sorted = AtlasModel.sorted(AtlasModel.visible(candidates, state, false, Filter.ALL), state, SortMode.SMART);

        assertEquals(List.of("attic", "market", "docks"), sorted.stream().map(Row::name).toList());
    }

    @Test
    void nameWorldAndDistanceSortsOrderRowsTheWayTheirNamesSay() {
        AtlasPlayerState state = new AtlasPlayerState(UUID.randomUUID());
        List<Row> candidates = List.of(
                row(MARKET, "market", "nether", 90.0D, true, true),
                row(DOCKS, "docks", "world", 10.0D, true, true),
                row(ATTIC, "attic", "world", 500.0D, true, true));

        assertEquals(List.of("attic", "docks", "market"),
                AtlasModel.sorted(candidates, state, SortMode.NAME).stream().map(Row::name).toList());
        assertEquals(List.of("market", "attic", "docks"),
                AtlasModel.sorted(candidates, state, SortMode.WORLD).stream().map(Row::name).toList());
        assertEquals(List.of("docks", "market", "attic"),
                AtlasModel.sorted(candidates, state, SortMode.DISTANCE).stream().map(Row::name).toList());
    }

    @Test
    void pagingMatchesTheFortyFiveSlotDestinationGrid() {
        assertEquals(1, AtlasModel.pageCount(0));
        assertEquals(1, AtlasModel.pageCount(45));
        assertEquals(2, AtlasModel.pageCount(46));
        assertEquals(0, AtlasModel.clampPage(-1, 2));
        assertEquals(1, AtlasModel.clampPage(7, 2));
        assertEquals(45, AtlasModel.pageStart(1));
        assertEquals(46, AtlasModel.pageEnd(46, 1));
    }

    @Test
    void sortModesCycleBackToTheStart() {
        assertEquals(SortMode.NAME, SortMode.SMART.next());
        assertEquals(SortMode.SMART, SortMode.DISTANCE.next());
        assertEquals(Filter.FAVORITES, Filter.ALL.next());
        assertEquals(Filter.ALL, Filter.RECENTS.next());
    }

    private static Row row(UUID portalId, String name, String world, double distanceSquared, boolean open, boolean listed) {
        return new Row(portalId, name, world, "", "", distanceSquared, open, false, listed);
    }
}
