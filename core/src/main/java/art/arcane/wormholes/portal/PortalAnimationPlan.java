package art.arcane.wormholes.portal;

import art.arcane.wormholes.config.VisualQualityProfile;

public final class PortalAnimationPlan {
    private PortalAnimationPlan() { }

	public static int formationDisplayCap(VisualQualityProfile profile)
	{
		return switch(profile)
		{
			case PERFORMANCE -> 8;
			case BALANCED -> 16;
			case AUTO -> 18;
			case CINEMATIC -> 24;
		};
	}

	public static CloseEffectPlan closeEffectPlan(VisualQualityProfile profile)
	{
		return switch(profile)
		{
			case PERFORMANCE -> new CloseEffectPlan(4, 3, 14);
			case BALANCED -> new CloseEffectPlan(6, 4, 22);
			case AUTO -> new CloseEffectPlan(6, 4, 26);
			case CINEMATIC -> new CloseEffectPlan(8, 5, 30);
		};
	}

	public static KawooshPlan kawooshPlan(VisualQualityProfile profile)
	{
		return switch(profile)
		{
			case PERFORMANCE -> new KawooshPlan(2, 6, 20, 8, 2);
			case BALANCED -> new KawooshPlan(3, 9, 40, 18, 4);
			case AUTO -> new KawooshPlan(3, 11, 44, 20, 5);
			case CINEMATIC -> new KawooshPlan(3, 14, 48, 24, 6);
		};
	}

	public static int openingRingPoints(VisualQualityProfile profile)
	{
		return switch(profile)
		{
			case PERFORMANCE -> 6;
			case BALANCED -> 10;
			case AUTO -> 12;
			case CINEMATIC -> 16;
		};
	}

	public static OpeningSoundPlan openingSoundPlan(double multiplier)
	{
		return new OpeningSoundPlan(
				volume(multiplier, 0.2f),
				volume(multiplier, 0.225f),
				volume(multiplier, 0.2f),
				volume(multiplier, 0.075f));
	}

	public static double ellipseRadius(double halfA, double halfB, double angle)
	{
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		return 1.0D / Math.sqrt((cos * cos) / (halfA * halfA) + (sin * sin) / (halfB * halfB));
	}

	public static double[] outwardShardVelocity(int normalAxis, int planeA, int planeB, double radialA, double radialB, double normalDirection)
	{
		double[] velocity = new double[3];
		velocity[normalAxis] = normalDirection * 0.65D;
		velocity[planeA] = radialA;
		velocity[planeB] = radialB;
		return velocity;
	}

    private static float volume(double multiplier, float value) {
        return (float) (Math.max(0, Math.min(4, multiplier)) * value);
    }

	public record CloseEffectPlan(int branches, int segments, int shards)
	{
	}

	public record KawooshPlan(int arms, int armPoints, int impactReverse, int impactEndRod, int surgeCount)
	{
	}

	public record OpeningSoundPlan(float frameVolume, float portalImpactVolume, float beaconImpactVolume, float sonicBoomVolume)
	{
	}
}
