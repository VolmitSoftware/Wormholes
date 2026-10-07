package art.arcane.wormholes.config.toml;

import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamOptions;
import art.arcane.wormholes.network.client.RemoteViewOptions;
import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "ClientView: clients running the Wormholes mod receive portal plates and sweep them locally instead of server-side block packets. Changes hot-reload."
})
public class ClientViewConfig {
    public static final int MIN_FRAME_KB = 64;
    public static final int MAX_FRAME_KB = 1024;

    @ConfigDescription("Offer ClientView to clients running the Wormholes mod. Off keeps every client on the vanilla projection path.")
    public boolean enabled = true;
    @ConfigDescription("Negotiate during the configuration phase where the platform allows it, so the first frame after join is already ClientView.")
    public boolean configurationHandshake = true;
    @ConfigDescription("Extra milliseconds to wait for HELLO from a modded-brand client, 0 to 5000. Vanilla brands never wait.")
    public int helloGraceMillis = 100;
    @ConfigDescription("Frame split size in KiB, 64 to 1024.")
    public int maxFrameKb = 512;
    @ConfigDescription("Unacknowledged frame groups before the lane pauses sending to that client, 0 to 255. 0 never pauses.")
    public int ackWindowFrames = 8;
    @ConfigDescription("Send brick hash manifests and honour BRICK_MISS so repeated bricks cost 12 bytes.")
    public boolean brickCache = true;
    @ConfigDescription("Ship destination light nibbles inside bricks.")
    public boolean destinationLight = true;
    @ConfigDescription("Stream one 20 Hz entity frame per attended portal shared by all attending observers.")
    public boolean entityFrames = true;
    @ConfigDescription("Hand plates over by reference on memory connections such as singleplayer.")
    public boolean zeroCopy = true;
    @ConfigDescription("Stream the standby RTP plate while the portal is attended. Off keeps the next destination hidden until traversal.")
    public boolean standbyPrestream = false;
    @ConfigDescription("Accept VIEW_STATS telemetry from clients for /wormholes clientview.")
    public boolean viewStats = true;
    @ConfigDescription("Let modded clients draw mirror portals from their own loaded chunks, so mirror plates are never built or streamed for them.")
    public boolean clientMirror = true;
    @ConfigDescription("Stream nested mirror and portal destination views. Native rendering supports up to three nested steps and sixteen nested views per primary view.")
    public boolean clientRecursion = true;
    @ConfigDescription("Fabric, Forge and NeoForge servers only: let clients running the Wormholes mod walk through portals with no teleport, respawn or loading screen, with destination chunks and entities streamed ahead. Off keeps prepared travel.")
    public boolean seamlessTravel = true;
    @ConfigDescription("Fabric, Forge and NeoForge servers only: portal destinations streamed ahead per seamless player, 1 to 4.")
    public int remoteViewRoutes = RemoteViewOptions.DEFAULT.routes();
    @ConfigDescription("Fabric, Forge and NeoForge servers only: destination chunk columns streamed per seamless player per tick, 1 to 64. The client's own acknowledgement can lower it further.")
    public int remoteViewChunksPerTick = RemoteViewOptions.DEFAULT.chunksPerTick();
    @ConfigDescription("Fabric, Forge and NeoForge servers only: destination bytes streamed per seamless player per tick, 16384 to 2097152.")
    public int remoteViewBytesPerTick = RemoteViewOptions.DEFAULT.bytesPerTick();

    public void normalizeRuntimeBounds() {
        helloGraceMillis = clamp(helloGraceMillis, 0, ViewStreamLimits.MAX_HELLO_GRACE_MILLIS);
        maxFrameKb = clamp(maxFrameKb, MIN_FRAME_KB, MAX_FRAME_KB);
        ackWindowFrames = clamp(ackWindowFrames, 0, ViewStreamLimits.MAX_ACK_WINDOW_FRAMES);
        RemoteViewOptions remote = remoteView();
        remoteViewRoutes = remote.routes();
        remoteViewChunksPerTick = remote.chunksPerTick();
        remoteViewBytesPerTick = remote.bytesPerTick();
    }

    public int maxFrameBytes() {
        return clamp(maxFrameKb, MIN_FRAME_KB, MAX_FRAME_KB) * 1024;
    }

    public ViewStreamOptions options(int interestGraceTicks) {
        return new ViewStreamOptions(enabled, configurationHandshake, helloGraceMillis, maxFrameBytes(), ackWindowFrames, brickCache,
            destinationLight, entityFrames, zeroCopy, standbyPrestream, viewStats, clientMirror, clientRecursion, interestGraceTicks,
            remoteView().withheldCaps());
    }

    public RemoteViewOptions remoteView() {
        return new RemoteViewOptions(seamlessTravel, remoteViewRoutes, remoteViewChunksPerTick, remoteViewBytesPerTick);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
