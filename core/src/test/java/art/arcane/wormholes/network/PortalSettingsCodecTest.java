package art.arcane.wormholes.network;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.RemotePortal;
import art.arcane.optics.math.Box;
import art.arcane.wormholes.util.RemoteWorld;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class PortalSettingsCodecTest {
    @Test
    public void localAndRemoteProjectionSettingsFollowTheSamePrecedence() {
        List<ProjectionCase> cases = List.of(
            new ProjectionCase(Map.of(), ProjectionMode.OFF, true),
            new ProjectionCase(Map.of("projectionMode", "ON"), ProjectionMode.ON, false),
            new ProjectionCase(Map.of("projectionMode", "MIRROR"), ProjectionMode.ON, true),
            new ProjectionCase(Map.of("projectionMode", "MIRROR", "projectionEnabled", "false", "mirrorMode", "false"), ProjectionMode.OFF, false),
            new ProjectionCase(Map.of("projectionMode", "OFF", "projectionEnabled", "true", "mirrorMode", "true"), ProjectionMode.ON, true),
            new ProjectionCase(Map.of("projectionEnabled", "true"), ProjectionMode.ON, true),
            new ProjectionCase(Map.of("projectionMode", "invalid", "projectionEnabled", "invalid", "mirrorMode", "invalid"), ProjectionMode.OFF, true),
            new ProjectionCase(Map.of("projectionMode", "ON", "mirrorMode", "invalid"), ProjectionMode.ON, true),
            new ProjectionCase(Map.of("projectionMode", "invalid", "mirrorMode", "false"), ProjectionMode.OFF, false));

        for (ProjectionCase test : cases) {
            LocalTarget local = new LocalTarget();
            RemotePortal remote = new RemotePortal(UUID.randomUUID(), new RemoteWorld("alpha", "minecraft:overworld"),
                new Vec3d(0, 64, 0), PortalType.PORTAL, true, new Box(0, 1, 64, 66, 0, 1));
            remote.setMirroredProjectionMode(ProjectionMode.OFF);
            remote.setMirroredMirrorMode(true);

            PortalSettingsCodec.applyToLocal(local.portal(), test.settings());
            PortalSettingsCodec.applyToRemote(remote, test.settings());

            assertEquals(test.projection(), local.projection, test.settings().toString());
            assertEquals(test.mirror(), local.mirror, test.settings().toString());
            assertEquals(test.projection(), remote.getMirroredProjectionMode(), test.settings().toString());
            assertEquals(test.mirror(), remote.isMirroredMirrorMode(), test.settings().toString());
        }
    }

    @Test
    public void projectionIsAppliedBeforeMirrorAndMissingFieldsDoNotInvokeSetters() {
        LocalTarget local = new LocalTarget();
        PortalSettingsCodec.applyToLocal(local.portal(), Map.of("projectionEnabled", "true", "mirrorMode", "false"));
        assertEquals(List.of("setProjectionMode", "setMirrorMode"), local.applied);

        local.applied.clear();
        PortalSettingsCodec.applyToLocal(local.portal(), Map.of());
        assertEquals(List.of(), local.applied);
    }

    private record ProjectionCase(Map<String, String> settings, ProjectionMode projection, boolean mirror) {
    }

    private static final class LocalTarget implements InvocationHandler {
        private final List<String> applied = new ArrayList<>();
        private ProjectionMode projection = ProjectionMode.OFF;
        private boolean mirror = true;

        private PortalSettingsTarget portal() {
            return (PortalSettingsTarget) Proxy.newProxyInstance(PortalSettingsTarget.class.getClassLoader(),
                new Class<?>[] {PortalSettingsTarget.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            switch (method.getName()) {
                case "setProjectionMode" -> projection = (ProjectionMode) arguments[0];
                case "setMirrorMode" -> mirror = ((Boolean) arguments[0]).booleanValue();
                default -> throw new AssertionError("Unexpected portal operation: " + method.getName());
            }
            applied.add(method.getName());
            return null;
        }
    }
}
