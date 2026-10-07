package art.arcane.wormholes.modded.seamless;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RemoteViewerVisibilityTest {
    @Test
    public void deliveredEntityWithinTrackingRangeOfTheArrivalIsVisible() {
        assertTrue(RemoteViewer.visible(new RemoteViewer.Sight(false, false, true, true, 30.0D, 20.0D, 80, 8)));
    }

    @Test
    public void rangeIsTheSmallerOfTrackingRangeAndWindowRadius() {
        assertFalse(RemoteViewer.visible(new RemoteViewer.Sight(false, false, true, true, 81.0D, 0.0D, 80, 8)));
        assertFalse(RemoteViewer.visible(new RemoteViewer.Sight(false, false, true, true, 49.0D, 0.0D, 128, 3)));
        assertTrue(RemoteViewer.visible(new RemoteViewer.Sight(false, false, true, true, 48.0D, 0.0D, 128, 3)));
    }

    @Test
    public void viewerItselfVanillaTrackedHiddenOrUndeliveredEntitiesAreNotRouted() {
        assertFalse(RemoteViewer.visible(new RemoteViewer.Sight(true, false, true, true, 1.0D, 1.0D, 80, 8)));
        assertFalse(RemoteViewer.visible(new RemoteViewer.Sight(false, true, true, true, 1.0D, 1.0D, 80, 8)));
        assertFalse(RemoteViewer.visible(new RemoteViewer.Sight(false, false, false, true, 1.0D, 1.0D, 80, 8)));
        assertFalse(RemoteViewer.visible(new RemoteViewer.Sight(false, false, true, false, 1.0D, 1.0D, 80, 8)));
    }
}
