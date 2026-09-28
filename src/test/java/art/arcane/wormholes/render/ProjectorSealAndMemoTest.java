package art.arcane.wormholes.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

public final class ProjectorSealAndMemoTest {
    @Test
    public void aSealIsInertUntilAPassEnablesIt() {
        ProjectorBlackoutSeal seal = new ProjectorBlackoutSeal();
        assertFalse(seal.isEnabled());
        seal.disable();
        assertFalse(seal.isEnabled());
    }

}
