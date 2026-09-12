package art.arcane.wormholes.render;

import java.util.List;

import org.bukkit.entity.Player;

/** Test access to the package-private blackout display renderer for the Bedrock profile test. */
public final class ProjectorBlackoutDisplayRendererBedrockProbe implements AutoCloseable {
    private final ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.install();
    private final ProjectorBlackoutDisplayRenderer renderer = new ProjectorBlackoutDisplayRenderer(new EntityRenderPacketChannel());

    public boolean prepareForViewer(Player observer) {
        List<ProjectorBlackoutMesh.Panel> panels = List.of(new ProjectorBlackoutMesh.Panel(2, -1, -4, 0, 64, 2, 2));
        boolean prepared = renderer.prepare(observer, panels, 41, 48.0D);
        renderer.finish(observer);
        return prepared;
    }

    public int packetsSent() {
        return recorder.sent().size();
    }

    @Override
    public void close() {
        recorder.uninstall();
    }
}
