package art.arcane.wormholes.modded.client.render;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class PortalEnvironmentRendererTest {
    @Test
    public void destinationDrawUsesRotatedViewAndRestoresOuterView() {
        Matrix4fStack modelView = new Matrix4fStack(4);
        Matrix4f outer = new Matrix4f().rotateY(0.4f);
        Matrix4f destination = new Matrix4f(outer).rotateY((float) Math.PI / 2);
        modelView.set(outer);

        PortalEnvironmentRenderer.renderWithView(modelView, destination,
            () -> assertEquals(destination, new Matrix4f(modelView)));

        assertEquals(outer, new Matrix4f(modelView));
    }

    @Test
    public void failedNestedReflectedDrawRestoresParentAndOuterViews() {
        Matrix4fStack modelView = new Matrix4fStack(4);
        Matrix4f outer = new Matrix4f().rotateX(0.3f);
        Matrix4f parent = new Matrix4f(outer).rotateY(0.7f);
        Matrix4f child = new Matrix4f(parent).scale(-1, 1, 1);
        modelView.set(outer);

        PortalEnvironmentRenderer.renderWithView(modelView, parent, () -> {
            assertThrows(IllegalStateException.class, () -> PortalEnvironmentRenderer.renderWithView(modelView, child, () -> {
                assertEquals(child, new Matrix4f(modelView));
                throw new IllegalStateException("Destination rendering failed");
            }));
            assertEquals(parent, new Matrix4f(modelView));
        });

        assertEquals(outer, new Matrix4f(modelView));
    }
}
