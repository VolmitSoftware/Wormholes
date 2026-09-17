package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Portal physics, convoy traversal, and traversal cues. Changes hot-reload."
})
public class TransitConfig {
    @ConfigDescription("Momentum policy for portals that set none of their own: preserve, scale, clamp, zero, or impulse.")
    public String momentumDefault = "preserve";
    @ConfigDescription("Speed ceiling in blocks per tick applied by the clamp and scale momentum policies.")
    public double momentumMaxSpeed = 4.0D;
    @ConfigDescription("Orientation policy for portals that set none of their own: frame (existing behaviour), look, snap, or mirror.")
    public String orientationDefault = "frame";
    @ConfigDescription("Rotate the traveler's look when the exit points up or down so their up vector follows the exit frame.")
    public boolean gravityFlipEnabled = true;
    @ConfigDescription("Projectiles and dropped items keep their velocity and arc through local tunnels without a reentry cooldown.")
    public boolean objectTransitContinuous = true;
    @ConfigDescription("Move vehicles, passengers, and leashed mobs through a portal as one rig, or refuse the whole rig.")
    public boolean convoyEnabled = true;
    @ConfigDescription("Largest rig (vehicle, riders, leashed mobs) a portal moves as a unit.")
    public int convoyMaxEntities = 16;
    @ConfigDescription("Allow rigs through cross-server gateways when the peer negotiates convoy support.")
    public boolean convoyCrossServerEnabled = true;
    @ConfigDescription("Seconds a cross-server rig transfer may wait for the destination before the source restores the rig. Keep under 30.")
    public int convoyCrossServerTimeoutSec = 20;
    @ConfigDescription("Play threshold and arrival cues (sound and particles only).")
    public boolean cinematicsEnabled = true;
    @ConfigDescription("Size the arrival darkness mask by how many destination chunks still need to stream instead of using the fixed tick count.")
    public boolean arrivalMaskAdaptive = true;
    @ConfigDescription("Shortest adaptive arrival mask in ticks when any destination chunk is still missing.")
    public int arrivalMaskMinTicks = 5;
}
