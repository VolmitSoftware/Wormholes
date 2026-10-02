package art.arcane.wormholes.modded.client.render;

import org.joml.Matrix4f;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import art.arcane.wormholes.util.Direction;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.joml.Vector4f;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PortalProjectionTest {
    @Test
    public void nestedDestinationsComposeRotationsTranslationsAndReflectionsInBranchOrder() {
        ClientViewEnvironment.Transform root = new ClientViewEnvironment.Transform(Direction.S, Direction.U, Direction.W,
            new GeometryVector(100, 20, -50));
        ClientViewEnvironment.Transform child = new ClientViewEnvironment.Transform(Direction.W, Direction.U, Direction.S,
            new GeometryVector(6, 0, 0));
        Matrix4d rootMatrix = PortalProjection.destinationToSource(root);
        Matrix4d composed = new Matrix4d(rootMatrix).mul(PortalProjection.destinationToSource(child));
        Vector3d displayed = composed.transformPosition(new Vector3d(2, 3, 4));
        assertEquals(96, displayed.x, 0.000000001);
        assertEquals(23, displayed.y, 0.000000001);
        assertEquals(-46, displayed.z, 0.000000001);
        assertEquals(-1, composed.determinant3x3(), 0.000000001);
        GeometryVector rootDestination = root.destinationPoint(displayed.x, displayed.y, displayed.z);
        GeometryVector original = child.destinationPoint(rootDestination.x(), rootDestination.y(), rootDestination.z());
        assertEquals(new GeometryVector(2, 3, 4), original);
        assertEquals(1, rootMatrix.determinant3x3(), 0.000000001);
        assertEquals(100, rootMatrix.m30(), 0.000000001);
    }

    @Test
    public void closeObliqueAperturesKeepPlanarDepthAndOccludeWorldGeometryBehindThem() throws IOException {
        String vertexShader = shader("portal_composite.vsh");
        String fragmentShader = shader("portal_composite.fsh");
        assertTrue(vertexShader.contains("portalDepth = gl_Position.zw;"));
        assertTrue(vertexShader.contains("gl_Position.z = gl_Position.w;"));
        assertTrue(fragmentShader.contains("portalDepth.x / portalDepth.y"));
        assertTrue(fragmentShader.contains("gl_FragDepth = min(depth, 1.0);"));
        for (boolean zeroToOne : new boolean[] {false, true}) {
            Matrix4f projection = new Matrix4f().setPerspective(1.1f, 1.7f, 256, 0.05f, zeroToOne);
            Vector4f[] corners = {new Vector4f(-0.01f, -0.01f, -0.01f, 1), new Vector4f(0.5f, -0.01f, -1, 1),
                new Vector4f(0.5f, 0.5f, -1, 1)};
            float[] barycentric = {0.05f, 0.45f, 0.5f};
            Vector4f sample = new Vector4f(0, 0, 0, 0);
            float inverseW = 0;
            float originalZ = 0;
            float originalW = 0;
            float vertexClampedZ = 0;
            for (int index = 0; index < corners.length; index++) {
                Vector4f clip = projection.transform(new Vector4f(corners[index]));
                float weight = barycentric[index] / clip.w;
                sample.fma(weight, corners[index]);
                inverseW += weight;
                originalZ += weight * clip.z;
                originalW += weight * clip.w;
                vertexClampedZ += weight * Math.min(clip.z, clip.w);
            }
            sample.div(inverseW);
            Vector4f exact = projection.transform(sample);
            float scale = zeroToOne ? 1 : 0.5f;
            float bias = zeroToOne ? 0 : 0.5f;
            float exactDepth = exact.z / exact.w * scale + bias;
            float fragmentDepth = originalZ / originalW * scale + bias;
            float oldDepth = vertexClampedZ * scale + bias;
            assertEquals(exactDepth, fragmentDepth, 0.000001f);
            assertTrue(exactDepth < 1);
            assertTrue(exactDepth - oldDepth > 0.1f);
            float worldBehindPortal = (oldDepth + exactDepth) * 0.5f;
            assertTrue(fragmentDepth > worldBehindPortal);
            assertTrue(oldDepth < worldBehindPortal);
            float worldInFrontOfPortal = (exactDepth + 1) * 0.5f;
            assertTrue(fragmentDepth < worldInFrontOfPortal);
        }
    }

    private static String shader(String name) throws IOException {
        try (InputStream input = PortalProjectionTest.class.getResourceAsStream("/assets/wormholes/shaders/core/" + name)) {
            if (input == null) {
                throw new IOException("Missing shader " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void nestedMirrorsComposeAtExactHalfBlockPlanes() {
        Matrix4d wall = PortalProjection.destinationToSource(new ClientViewEnvironment.Transform(Direction.E, Direction.U, Direction.N,
            new GeometryVector(0, 0, 41)));
        Matrix4d floor = PortalProjection.destinationToSource(new ClientViewEnvironment.Transform(Direction.E, Direction.D, Direction.S,
            new GeometryVector(0, 17, 0)));
        Vector3d once = wall.transformPosition(new Vector3d(1, 11, 23));
        assertEquals(18.0, once.z, 0.000000001);
        assertEquals(-1.0, wall.determinant3x3(), 0.000000001);
        Matrix4d composed = new Matrix4d(wall).mul(floor);
        Vector3d twice = composed.transformPosition(new Vector3d(1, 6, 23));
        assertEquals(11.0, twice.y, 0.000000001);
        assertEquals(18.0, twice.z, 0.000000001);
        assertEquals(1.0, composed.determinant3x3(), 0.000000001);
        Vector3d original = new Matrix4d(composed).invert().transformPosition(twice);
        assertEquals(1.0, original.x, 0.000000001);
        assertEquals(6.0, original.y, 0.000000001);
        assertEquals(23.0, original.z, 0.000000001);
    }

    @Test
    public void reversedDepthClipsAtPortalForBothBackendRanges() {
        for (boolean zeroToOne : new boolean[] {false, true}) {
            Matrix4f original = new Matrix4f().setPerspective(1.1f, 1.7f, 256.0f, 0.05f, zeroToOne);
            Matrix4f clipped = PortalProjection.clip(original, new Matrix4f(), new Vector4f(0, 0, 1, 2), zeroToOne);
            Vector4f before = clipped.transform(new Vector4f(0, 0, -1, 1));
            Vector4f behind = clipped.transform(new Vector4f(0, 0, -3, 1));
            Vector4f plane = clipped.transform(new Vector4f(0, 0, -2, 1));
            assertTrue(before.z > before.w);
            assertTrue(behind.z < behind.w);
            assertEquals(plane.w, plane.z, 0.00001f);
            assertTrue(behind.z >= (zeroToOne ? 0 : -behind.w));
        }
    }

    @Test
    public void obliqueClipPreservesScreenCoordinatesWithRotatedCamera() {
        Matrix4f original = new Matrix4f().setPerspective(1.1f, 1.7f, 256.0f, 0.05f, true);
        Matrix4f view = new Matrix4f().rotateY(0.4f).rotateX(-0.2f);
        Matrix4f clipped = PortalProjection.clip(original, view, new Vector4f(0, 0, 1, 2), true);
        for (Vector4f point : new Vector4f[] {new Vector4f(0.3f, 0.8f, -2, 1), new Vector4f(-1, 2, -8, 1)}) {
            Vector4f eye = view.transform(new Vector4f(point));
            Vector4f a = original.transform(new Vector4f(eye));
            Vector4f b = clipped.transform(new Vector4f(eye));
            assertEquals(a.x, b.x, 0.000001f);
            assertEquals(a.y, b.y, 0.000001f);
            assertEquals(a.w, b.w, 0.000001f);
            if (point.z == -2) {
                assertEquals(b.w, b.z, 0.00001f);
            }
        }
    }
}
