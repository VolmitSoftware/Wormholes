package art.arcane.wormholes.clientgametest;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ArrivalPlacementTap {
    private static final List<Placement> PLACEMENTS = new ArrayList<>();
    private static final List<Arrival> ARRIVALS = new ArrayList<>();

    private ArrivalPlacementTap() {
    }

    public static synchronized void reset() {
        PLACEMENTS.clear();
        ARRIVALS.clear();
    }

    public static synchronized void placed(Placement placement) {
        PLACEMENTS.add(placement);
    }

    public static synchronized void arrived(Minecraft minecraft, String crossing) {
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        ARRIVALS.add(new Arrival(minecraft.level.dimension().identifier().toString(), minecraft.player.position(), crossing,
            TravelTap.gameTickIndex()));
    }

    static synchronized Placement placement(String level) {
        for (Placement placement : PLACEMENTS) {
            if (placement.moved() && placement.level().equals(level)) {
                return placement;
            }
        }
        return null;
    }

    static synchronized Arrival arrival(String level) {
        for (Arrival arrival : ARRIVALS) {
            if (arrival.level().equals(level)) {
                return arrival;
            }
        }
        return null;
    }

    public record Placement(String level, Vec3 pose, Vec3 feet, boolean moved, int serverTick) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "server move %s into %s pose %.3f,%.3f,%.3f feet %.3f,%.3f,%.3f at server tick %d",
                moved ? "placed" : "refused", level, pose.x, pose.y, pose.z, feet.x, feet.y, feet.z, serverTick);
        }
    }

    public record Arrival(String level, Vec3 feet, String crossing, long clientTick) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "client %s crossing into %s feet %.3f,%.3f,%.3f at client tick %d", crossing, level, feet.x, feet.y,
                feet.z, clientTick);
        }
    }
}
