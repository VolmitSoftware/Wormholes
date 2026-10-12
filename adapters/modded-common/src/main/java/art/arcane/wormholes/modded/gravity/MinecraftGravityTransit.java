package art.arcane.wormholes.modded.gravity;

import art.arcane.optics.crossing.ArrivalMomentum;
import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.crossing.OrientationRule;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.crossing.Pose;
import art.arcane.optics.crossing.PoseTransform;
import art.arcane.optics.crossing.TravellerState;
import art.arcane.optics.crossing.TravellerTransform;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.Similarity;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.transit.TransitOrientation;

public final class MinecraftGravityTransit {
    private MinecraftGravityTransit() {
    }

    public static Arrival arrive(Pose source, GravityFrame gravity, Passage passage) {
        TravelMessage.ArrivalRules rules = passage.rules();
        GravityFrame next = rules.rotateGravity() ? gravity.transformed(passage.toward().rigid().permutation()) : gravity;
        if (rules.rotateGravity() && rules.orientation() == OrientationRule.FRAME) {
            TravellerState mapped = TravellerTransform.apply(new TravellerState(source, 1.0D, gravity), passage.toward(), rules.scale());
            return new Arrival(mapped.pose().withVelocity(ArrivalMomentum.apply(mapped.pose().velocity(), rules.momentum(),
                rules.momentum().maxSpeed())), next);
        }
        Pose world = PoseTransform.orient(source, gravity.orientation());
        Pose arrived = TransitOrientation.arrive(world, passage.crossing(), passage.toward(), passage.exit(), rules.orientation(),
            !rules.rotateGravity() && rules.gravityFlip(), rules.momentum(), rules.momentum().maxSpeed());
        return new Arrival(PoseTransform.orient(arrived, next.orientation().inverse()), next);
    }

    public record Passage(PlaneCrossing crossing, Similarity toward, Frame exit, TravelMessage.ArrivalRules rules) {
    }

    public record Arrival(Pose pose, GravityFrame gravity) {
    }
}
