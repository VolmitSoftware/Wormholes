package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.wormholes.modded.client.ClientRenderAccess;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4d;
import org.joml.Vector4d;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;

public class StencilDepthShaderTest {
    private static final String DEFINE = ClipShaderTransformation.DEPTH_ZERO_TO_ONE_DEFINE;
    private static final Pattern DEPTH_REMAP = Pattern.compile("depth = depth \\* ([0-9.]+) \\+ ([0-9.]+);");
    private static final Pattern FILL_DEPTH = Pattern.compile("gl_Position = vec4\\(uv \\* 2\\.0 - 1\\.0, (-?[0-9.]+), 1\\.0\\);");

    @Test
    public void stencilFragmentUsesDeviceDepthConventionWithoutClippingOrCompilerDefines() throws IOException {
        String source = resource("portal_stencil_aperture.fsh");
        for (boolean zeroToOne : new boolean[] {false, true}) {
            String transformed = transform(source, "portal_stencil_aperture.fsh", ShaderType.FRAGMENT, zeroToOne);
            assertTrue(transformed.startsWith("#version 330\n#define " + DEFINE + " " + (zeroToOne ? 1 : 0) + "\n"));
            for (boolean reversed : new boolean[] {false, true}) {
                Matrix4d projection = new Matrix4d().setPerspective(Math.toRadians(70.0D), 1.5D,
                    reversed ? 256.0D : 0.05D, reversed ? 0.05D : 256.0D, zeroToOne);
                Vector4d portal = projection.transform(new Vector4d(0.0D, 0.0D, -8.0D, 1.0D));
                Vector4d foreground = projection.transform(new Vector4d(0.0D, 0.0D, -3.0D, 1.0D));
                Vector4d background = projection.transform(new Vector4d(0.0D, 0.0D, -20.0D, 1.0D));
                double actual = apertureDepth(preprocess(transformed), portal);
                double expected = windowDepth(portal, zeroToOne);
                assertEquals(expected, actual, 0.0000001D);
                assertTrue(reversed ? actual < windowDepth(foreground, zeroToOne) : actual > windowDepth(foreground, zeroToOne));
                assertTrue(reversed ? actual > windowDepth(background, zeroToOne) : actual < windowDepth(background, zeroToOne));
            }
        }
    }

    @Test
    public void stencilFillRemainsInsideTheClipVolumeAtFarDepth() throws IOException {
        String source = resource("portal_stencil_fill.vsh");
        boolean reversed = ClientRenderAccess.reversedDepth();
        for (boolean zeroToOne : new boolean[] {false, true}) {
            String transformed = transform(source, "portal_stencil_fill.vsh", ShaderType.VERTEX, zeroToOne);
            Matcher position = FILL_DEPTH.matcher(preprocess(transformed));
            assertTrue(position.find());
            double clipDepth = Double.parseDouble(position.group(1));
            assertTrue(clipDepth >= (zeroToOne ? 0.0D : -1.0D) && clipDepth <= 1.0D);
            double windowDepth = zeroToOne ? clipDepth : clipDepth * 0.5D + 0.5D;
            assertEquals(reversed ? 0.0D : 1.0D, windowDepth, 0.0D);
        }
    }

    @Test
    public void stencilMaskAndDepthWriteShareTheCorrectedApertureShader() {
        Identifier aperture = Identifier.fromNamespaceAndPath("wormholes", "core/portal_stencil_aperture");
        assertEquals(aperture, StencilPipelines.MASK.getShaders().get(ShaderType.FRAGMENT));
        assertEquals(aperture, StencilPipelines.MASK_SHAPED.getShaders().get(ShaderType.FRAGMENT));
        assertEquals(aperture, StencilPipelines.DEPTH.getShaders().get(ShaderType.FRAGMENT));
        assertEquals(aperture, StencilPipelines.DEPTH_SHAPED.getShaders().get(ShaderType.FRAGMENT));
        assertFalse(StencilPipelines.MASK.getDepthStencilState().writeDepth());
        assertTrue(StencilPipelines.DEPTH.getDepthStencilState().writeDepth());
        assertEquals(ClientRenderAccess.reversedDepth() ? "GREATER_THAN_OR_EQUAL" : "LESS_THAN_OR_EQUAL",
            StencilPipelines.MASK.getDepthStencilState().depthTest().name());
    }

    @Test
    public void externalShaderSourcesAreUnchanged() throws IOException {
        String source = resource("portal_stencil_aperture.fsh");
        assertEquals(source, PortalClipShaders.shader(Identifier.fromNamespaceAndPath("example", "shaders/core/aperture.fsh"),
            ShaderType.FRAGMENT, source));
    }

    private static String transform(String source, String file, ShaderType type, boolean zeroToOne) {
        try (MockedStatic<ClientRenderAccess> access = mockStatic(ClientRenderAccess.class)) {
            access.when(ClientRenderAccess::zeroToOneDepth).thenReturn(zeroToOne);
            return PortalClipShaders.shader(Identifier.fromNamespaceAndPath("wormholes", "shaders/core/" + file), type, source);
        }
    }

    private static String resource(String file) throws IOException {
        try (InputStream input = StencilDepthShaderTest.class.getResourceAsStream("/assets/wormholes/shaders/core/" + file)) {
            if (input == null) {
                throw new IOException("Missing shader " + file);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String preprocess(String source) {
        boolean zeroToOne = source.contains("#define " + DEFINE + " 1\n");
        boolean active = true;
        StringBuilder result = new StringBuilder();
        for (String line : source.split("\n")) {
            String directive = line.strip();
            if (directive.equals("#if " + DEFINE)) {
                active = zeroToOne;
            } else if (directive.equals("#if !" + DEFINE)) {
                active = !zeroToOne;
            } else if (directive.equals("#else")) {
                active = !active;
            } else if (directive.equals("#endif")) {
                active = true;
            } else if (active) {
                result.append(line).append('\n');
            }
        }
        return result.toString();
    }

    private static double apertureDepth(String source, Vector4d clip) {
        assertTrue(source.contains("float depth = portalDepth.x / portalDepth.y;"));
        assertTrue(source.contains("gl_FragDepth = clamp(depth, 0.0, 1.0);"));
        double depth = clip.z / clip.w;
        Matcher remap = DEPTH_REMAP.matcher(source);
        if (remap.find()) {
            depth = depth * Double.parseDouble(remap.group(1)) + Double.parseDouble(remap.group(2));
        }
        return Math.clamp(depth, 0.0D, 1.0D);
    }

    private static double windowDepth(Vector4d clip, boolean zeroToOne) {
        return zeroToOne ? clip.z / clip.w : clip.z / clip.w * 0.5D + 0.5D;
    }
}
