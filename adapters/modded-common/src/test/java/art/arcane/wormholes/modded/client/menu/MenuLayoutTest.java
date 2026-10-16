package art.arcane.wormholes.modded.client.menu;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MenuLayoutTest {
    @Test
    public void wideScreensUseTheSettingsAndListRowWidths() {
        assertEquals(new MenuLayout.Grid(1, 310, 310), MenuLayout.grid(480, false, 5));
        assertEquals(new MenuLayout.Grid(1, 280, 280), MenuLayout.grid(480, true, 30));
    }

    @Test
    public void narrowScreensKeepTheScrollbarOnScreen() {
        assertEquals(new MenuLayout.Grid(1, 282, 282), MenuLayout.grid(320, false, 4));
        assertEquals(new MenuLayout.Grid(1, 280, 280), MenuLayout.grid(320, true, 4));
        MenuLayout.Grid narrow = MenuLayout.grid(300, false, 4);
        int rowRight = 150 + narrow.rowWidth() / 2;
        assertTrue(rowRight + 8 + 6 <= 300);
    }

    @Test
    public void settingsWithEightRowsSplitIntoTwoColumnsFrom416() {
        assertEquals(new MenuLayout.Grid(2, 182, 372), MenuLayout.grid(416, false, 8));
        assertEquals(new MenuLayout.Grid(2, 200, 408), MenuLayout.grid(480, false, 30));
    }

    @Test
    public void listsShortScreensAndNarrowScreensStayInOneColumn() {
        assertEquals(1, MenuLayout.grid(415, false, 8).columns());
        assertEquals(1, MenuLayout.grid(480, false, 7).columns());
        assertEquals(1, MenuLayout.grid(480, true, 30).columns());
    }

    @Test
    public void contextLineIsDroppedWhenTheContentWouldFallBelowThreeRows() {
        assertEquals(new MenuLayout.Header(38, true), MenuLayout.header(true, 240));
        assertEquals(new MenuLayout.Header(33, false), MenuLayout.header(true, 140));
        assertEquals(new MenuLayout.Header(33, false), MenuLayout.header(false, 240));
    }

    @Test
    public void listRegionSitsBetweenHeaderFeedbackAndFooter() {
        MenuLayout.Header header = MenuLayout.header(true, 240);
        assertEquals(38, MenuLayout.listTop(header, false));
        assertEquals(58, MenuLayout.listTop(header, true));
        assertEquals(207, MenuLayout.listBottom(240));
    }

    @Test
    public void feedbackBandSitsBelowTheHeaderDividerInsideTheContentArea() {
        MenuLayout.Header header = MenuLayout.header(false, 240);
        int plateTop = MenuLayout.feedbackY(header);
        assertEquals(header.height() + 4, plateTop);
        assertEquals(plateTop + 12 + 4, MenuLayout.listTop(header, true));
        assertEquals(20, MenuLayout.listTop(header, true) - MenuLayout.listTop(header, false));
        assertEquals(174, MenuLayout.listBottom(240) - header.height());
    }

    @Test
    public void rowHeightsFollowTheLineCount() {
        assertEquals(24, MenuLayout.rowHeight(false));
        assertEquals(28, MenuLayout.rowHeight(true));
    }

    @Test
    public void toggleRowsPlaceIconLabelAndControl() {
        MenuLayout.Row row = MenuLayout.row(MenuLayout.Control.TOGGLE, 10, 100, 310, 24, 0, 40, 0);
        assertEquals(12, row.iconX());
        assertEquals(104, row.iconY());
        assertEquals(32, row.labelX());
        assertEquals(108, row.textY());
        assertEquals(new MenuLayout.Rect(258, 102, 60, 20), row.control());
        assertEquals(254, row.labelRight());
    }

    @Test
    public void twoLineRowsPlaceBothLines() {
        MenuLayout.Row row = MenuLayout.row(MenuLayout.Control.ROW, 0, 0, 280, 28, 0, 0, 0);
        assertEquals(6, row.iconY());
        assertEquals(4, row.textY());
        assertEquals(15, row.secondY());
        assertEquals(new MenuLayout.Rect(0, 4, 278, 20), row.control());
        assertEquals(new MenuLayout.Rect(0, 1, 278, 26), row.entry());
    }

    @Test
    public void sideButtonsSitAtTheRightEdge() {
        MenuLayout.Row row = MenuLayout.row(MenuLayout.Control.ROW, 0, 0, 310, 24, 2, 0, 0);
        assertEquals(new MenuLayout.Rect(264, 2, 20, 20), row.side(0));
        assertEquals(new MenuLayout.Rect(288, 2, 20, 20), row.side(1));
        assertEquals(new MenuLayout.Rect(0, 2, 260, 20), row.control());
    }

    @Test
    public void numberRowsSplitIntoStepButtonsAndSlider() {
        MenuLayout.Row row = MenuLayout.row(MenuLayout.Control.NUMBER, 0, 0, 310, 24, 0, 30, 0);
        assertEquals(new MenuLayout.Rect(164, 2, 20, 20), row.decrement());
        assertEquals(new MenuLayout.Rect(186, 2, 100, 20), row.slider());
        assertEquals(new MenuLayout.Rect(288, 2, 20, 20), row.increment());
        assertEquals(160, row.labelRight());
    }

    @Test
    public void numberSlidersShrinkInNarrowColumns() {
        MenuLayout.Row row = MenuLayout.row(MenuLayout.Control.NUMBER, 0, 0, 182, 24, 0, 30, 0);
        assertEquals(new MenuLayout.Rect(96, 2, 62, 20), row.slider());
        assertEquals(new MenuLayout.Rect(74, 2, 20, 20), row.decrement());
        assertEquals(70, row.labelRight());
        assertTrue(row.labelRight() - row.labelX() >= MenuLayout.LABEL_MIN);
    }

    @Test
    public void numberSlidersKeepRoomForTheirValueText() {
        MenuLayout.Row row = MenuLayout.row(MenuLayout.Control.NUMBER, 0, 0, 200, 24, 0, 90, 58);
        assertEquals(new MenuLayout.Rect(114, 2, 62, 20), row.slider());
        assertEquals(new MenuLayout.Rect(92, 2, 20, 20), row.decrement());
        assertEquals(new MenuLayout.Rect(178, 2, 20, 20), row.increment());
        assertEquals(88, row.labelRight());
        assertEquals(50, MenuLayout.row(MenuLayout.Control.NUMBER, 0, 0, 200, 24, 0, 90, 30).slider().width());
    }

    @Test
    public void textRowsGiveTheLabelAtMostTwoFifths() {
        MenuLayout.Row wide = MenuLayout.row(MenuLayout.Control.TEXT, 0, 0, 310, 24, 0, 200, 0);
        assertEquals(new MenuLayout.Rect(140, 2, 146, 20), wide.edit());
        assertEquals(new MenuLayout.Rect(288, 2, 20, 20), wide.apply());
        MenuLayout.Row shortLabel = MenuLayout.row(MenuLayout.Control.TEXT, 0, 0, 310, 24, 0, 30, 0);
        assertEquals(new MenuLayout.Rect(56, 2, 230, 20), shortLabel.edit());
    }

    @Test
    public void cycleAndCaptureControlsKeepTheirPreferredWidthWhenTheyFit() {
        assertEquals(120, MenuLayout.row(MenuLayout.Control.CYCLE, 0, 0, 310, 24, 0, 30, 0).control().width());
        assertEquals(120, MenuLayout.row(MenuLayout.Control.CAPTURE, 0, 0, 310, 24, 1, 30, 0).control().width());
        assertEquals(106, MenuLayout.row(MenuLayout.Control.CYCLE, 0, 0, 182, 24, 0, 30, 0).control().width());
    }

    @Test
    public void longLabelsKeepHalfOfANarrowColumn() {
        MenuLayout.Row cycle = MenuLayout.row(MenuLayout.Control.CYCLE, 0, 0, 182, 24, 0, 300, 0);
        assertEquals(75, cycle.control().width());
        assertEquals(79, cycle.labelRight() - cycle.labelX());
        MenuLayout.Row number = MenuLayout.row(MenuLayout.Control.NUMBER, 0, 0, 182, 24, 0, 300, 0);
        assertEquals(new MenuLayout.Rect(108, 2, 50, 20), number.slider());
        assertEquals(60, number.labelRight() - number.labelX());
    }

    @Test
    public void labelOnlyRowsUseTheWholeWidthForText() {
        MenuLayout.Row row = MenuLayout.row(MenuLayout.Control.NONE, 0, 0, 310, 24, 0, 30, 0);
        assertEquals(308, row.labelRight());
        assertEquals(0, row.control().width());
    }

    @Test
    public void footerCentresASingleButton() {
        MenuLayout.Footer footer = MenuLayout.footer(427, 240, 310, false, 0, false);
        assertEquals(new MenuLayout.Rect(113, 213, 200, 20), footer.back());
        assertNull(footer.primary());
        assertNull(footer.previous());
    }

    @Test
    public void footerPairsBackAndPrimary() {
        MenuLayout.Footer footer = MenuLayout.footer(427, 240, 310, false, 0, true);
        assertEquals(new MenuLayout.Rect(59, 213, 150, 20), footer.back());
        assertEquals(new MenuLayout.Rect(217, 213, 150, 20), footer.primary());
    }

    @Test
    public void footerCentresPagingBetweenBackAndPrimary() {
        MenuLayout.Footer footer = MenuLayout.footer(427, 240, 310, true, 54, true);
        assertEquals(new MenuLayout.Rect(58, 213, 96, 20), footer.back());
        assertEquals(new MenuLayout.Rect(272, 213, 96, 20), footer.primary());
        assertEquals(new MenuLayout.Rect(162, 213, 20, 20), footer.previous());
        assertEquals(new MenuLayout.Rect(186, 213, 54, 20), footer.label());
        assertEquals(new MenuLayout.Rect(244, 213, 20, 20), footer.next());
        assertFalse(footer.back().intersects(footer.previous()));
        assertFalse(footer.next().intersects(footer.primary()));
    }

    @Test
    public void footerPagingWithoutPrimaryKeepsBackOnTheLeft() {
        MenuLayout.Footer footer = MenuLayout.footer(320, 240, 282, true, 60, false);
        assertEquals(19, footer.back().x());
        assertNull(footer.primary());
        assertFalse(footer.back().intersects(footer.previous()));
    }

    @Test
    public void confirmationCentresTheMessageAndButtons() {
        MenuLayout.Confirm confirm = MenuLayout.confirm(427, 240, 3);
        assertEquals(280, confirm.wrapWidth());
        assertEquals(92, confirm.textTop());
        assertEquals(new MenuLayout.Rect(61, 127, 150, 20), confirm.confirm());
        assertEquals(new MenuLayout.Rect(215, 127, 150, 20), confirm.cancel());
        assertTrue(confirm.panel().x() <= confirm.confirm().x());
        assertTrue(confirm.panel().right() >= confirm.cancel().right());
    }

    @Test
    public void confirmationCapsLinesAndShrinksButtonsOnNarrowScreens() {
        MenuLayout.Confirm confirm = MenuLayout.confirm(300, 240, 20);
        assertEquals(15, confirm.lines());
        assertEquals(250, confirm.wrapWidth());
        assertTrue(confirm.confirm().x() >= MenuLayout.MARGIN_MIN);
        assertTrue(confirm.cancel().right() <= 300 - MenuLayout.MARGIN_MIN);
        assertFalse(confirm.confirm().intersects(confirm.cancel()));
    }

    @Test
    public void controlRowLabelAreaEndsBeforeTheControl() {
        MenuLayout.Row row = MenuLayout.row(MenuLayout.Control.TOGGLE, 10, 100, 310, 24, 0, 40, 0);
        assertEquals(new MenuLayout.Rect(10, 101, 244, 22), row.label());
        MenuLayout.Row plain = MenuLayout.row(MenuLayout.Control.NONE, 0, 0, 310, 24, 0, 30, 0);
        assertEquals(plain.entry(), plain.label());
    }

    @Test
    public void pickerCentresAPanelWithRoomForTheScrollbarAndACancelButton() {
        MenuLayout.Picker picker = MenuLayout.picker(480, 270, 10);
        assertEquals(200, picker.rowWidth());
        assertEquals(new MenuLayout.Rect(126, 33, 228, 204), picker.panel());
        assertEquals(new MenuLayout.Rect(140, 243, 200, 20), picker.cancel());
        int rowRight = picker.panel().centerX() + picker.rowWidth() / 2;
        assertTrue(rowRight + 8 + 6 <= picker.panel().right());
    }

    @Test
    public void pickerPanelStaysInsideTheMarginsOnNarrowScreens() {
        MenuLayout.Picker picker = MenuLayout.picker(200, 240, 10);
        assertEquals(156, picker.rowWidth());
        assertTrue(picker.panel().x() >= MenuLayout.MARGIN);
        assertTrue(picker.panel().right() <= 200 - MenuLayout.MARGIN);
        assertTrue(picker.cancel().x() >= MenuLayout.MARGIN_MIN);
        assertFalse(picker.panel().intersects(picker.cancel()));
    }

    @Test
    public void pickerPanelShrinksToShortOptionLists() {
        assertEquals(new MenuLayout.Rect(366, 33, 228, 244), MenuLayout.picker(960, 540, 10).panel());
        assertEquals(new MenuLayout.Rect(366, 33, 228, 52), MenuLayout.picker(960, 540, 2).panel());
    }

    @Test
    public void footerSpanKeepsTheSideMargins() {
        assertEquals(310, MenuLayout.footerSpan(427));
        assertEquals(190, MenuLayout.footerSpan(200));
    }

    @Test
    public void rectanglesReportContainmentAndOverlap() {
        MenuLayout.Rect rect = new MenuLayout.Rect(10, 10, 20, 20);
        assertTrue(rect.contains(10, 10));
        assertFalse(rect.contains(30, 30));
        assertEquals(20, rect.centerX());
        assertTrue(rect.intersects(new MenuLayout.Rect(29, 29, 5, 5)));
        assertFalse(rect.intersects(new MenuLayout.Rect(30, 10, 5, 5)));
    }
}
