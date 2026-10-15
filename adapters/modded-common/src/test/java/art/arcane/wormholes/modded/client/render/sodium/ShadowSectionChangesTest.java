package art.arcane.wormholes.modded.client.render.sodium;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ShadowSectionChangesTest {
    @Test
    public void regularPassChangesStayWithSodiumGraphInvalidation() {
        ShadowSectionChanges changes = new ShadowSectionChanges();
        changes.sectionChanged(false);
        assertFalse(changes.claimForGraph(false));
    }

    @Test
    public void shadowPassChangeReachesTheNextRegularGraphPassOnce() {
        ShadowSectionChanges changes = new ShadowSectionChanges();
        changes.sectionChanged(true);
        assertTrue(changes.claimForGraph(false));
        assertFalse(changes.claimForGraph(false));
    }

    @Test
    public void shadowPassNeverClaimsItsOwnChanges() {
        ShadowSectionChanges changes = new ShadowSectionChanges();
        changes.sectionChanged(true);
        assertFalse(changes.claimForGraph(true));
        assertFalse(changes.claimForGraph(true));
        assertTrue(changes.claimForGraph(false));
    }

    @Test
    public void manyShadowPassChangesCollapseIntoOneGraphUpdate() {
        ShadowSectionChanges changes = new ShadowSectionChanges();
        for (int section = 0; section < 1728; section++) {
            changes.sectionChanged(true);
        }
        changes.sectionChanged(false);
        assertTrue(changes.claimForGraph(false));
        assertFalse(changes.claimForGraph(false));
    }

    @Test
    public void laterShadowPassChangesAreKeptAfterAClaim() {
        ShadowSectionChanges changes = new ShadowSectionChanges();
        changes.sectionChanged(true);
        assertTrue(changes.claimForGraph(false));
        changes.sectionChanged(true);
        assertTrue(changes.claimForGraph(false));
    }
}
