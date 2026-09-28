package art.arcane.wormholes.portal.rtp;

import java.util.Objects;

public final class RtpSettings
{
	static final double MINIMUM_COORDINATE = -30_000_000.0D;
	static final double MAXIMUM_COORDINATE = 30_000_000.0D;
	static final int MAXIMUM_RADIUS = 30_000_000;
	static final int DEFAULT_MINIMUM_RADIUS = 512;
	static final int DEFAULT_MAXIMUM_RADIUS = 4096;
	static final long DEFAULT_CYCLE_DURATION_MILLIS = 300_000L;
	static final long DEFAULT_LEASE_IDLE_MILLIS = 30_000L;
	static final long DEFAULT_PRIVATE_RELEASE_MILLIS = 15_000L;
	static final long MINIMUM_CYCLE_DURATION_MILLIS = 15_000L;
	static final long MAXIMUM_CYCLE_DURATION_MILLIS = 86_400_000L;
	static final long MINIMUM_LEASE_IDLE_MILLIS = 5_000L;
	static final long MAXIMUM_LEASE_IDLE_MILLIS = 600_000L;
	static final long MINIMUM_PRIVATE_RELEASE_MILLIS = 5_000L;
	static final long MAXIMUM_PRIVATE_RELEASE_MILLIS = 300_000L;

	private final RtpWorld sourceWorld;
	private final String sourceWorldKey;
	private final RtpWorld targetWorld;
	private final String targetWorldOverrideKey;
	private final RtpCenterMode centerMode;
	private final Double customCenterX;
	private final Double customCenterZ;
	private final int minimumRadius;
	private final int maximumRadius;
	private final RtpVerticalMode verticalMode;
	private final RtpSafetyMode safetyMode;
	private final int lowerY;
	private final int upperY;
	private final int preferredY;
	private final RtpAllocationMode allocationMode;
	private final RtpRotationMode rotationMode;
	private final long cycleDurationMillis;
	private final long leaseIdleMillis;
	private final long privateReleaseMillis;
	private final boolean rimEnabled;
	private final boolean soundEnabled;
	private final String targetBiomeKey;

	private RtpSettings(Builder builder)
	{
		sourceWorld = builder.sourceWorld;
		sourceWorldKey = sourceWorld.key();
		targetWorld = builder.targetWorld;
		targetWorldOverrideKey = builder.targetWorldOverrideKey;
		centerMode = Objects.requireNonNull(builder.centerMode, "centerMode");
		customCenterX = builder.customCenterX;
		customCenterZ = builder.customCenterZ;
		validateCustomCenter(centerMode, customCenterX, customCenterZ);
		if(builder.minimumRadius < 0)
		{
			throw new IllegalArgumentException("minimum radius must be non-negative");
		}
		if(builder.maximumRadius <= builder.minimumRadius)
		{
			throw new IllegalArgumentException("maximum radius must be greater than minimum radius");
		}
		if(builder.maximumRadius > MAXIMUM_RADIUS)
		{
			throw new IllegalArgumentException("maximum radius exceeds the Minecraft coordinate range");
		}
		minimumRadius = builder.minimumRadius;
		maximumRadius = builder.maximumRadius;
		verticalMode = Objects.requireNonNull(builder.verticalMode, "verticalMode");
		safetyMode = Objects.requireNonNull(builder.safetyMode, "safetyMode");
		RtpWorld boundsWorld = targetWorld == null ? sourceWorld : targetWorld;
		int minimumFeetY = boundsWorld.minimumHeight() + 1;
		int maximumFeetY = boundsWorld.maximumHeight() - 2;
		int clampedLowerY = clamp(builder.lowerY, minimumFeetY, maximumFeetY);
		int clampedUpperY = clamp(builder.upperY, minimumFeetY, maximumFeetY);
		if(clampedLowerY > clampedUpperY)
		{
			throw new IllegalArgumentException("lower Y must not exceed upper Y");
		}
		lowerY = clampedLowerY;
		upperY = clampedUpperY;
		preferredY = clamp(builder.preferredY, lowerY, upperY);
		allocationMode = Objects.requireNonNull(builder.allocationMode, "allocationMode");
		rotationMode = Objects.requireNonNull(builder.rotationMode, "rotationMode");
		cycleDurationMillis = clamp(builder.cycleDurationMillis, MINIMUM_CYCLE_DURATION_MILLIS, MAXIMUM_CYCLE_DURATION_MILLIS);
		leaseIdleMillis = clamp(builder.leaseIdleMillis, MINIMUM_LEASE_IDLE_MILLIS, MAXIMUM_LEASE_IDLE_MILLIS);
		privateReleaseMillis = clamp(builder.privateReleaseMillis, MINIMUM_PRIVATE_RELEASE_MILLIS, MAXIMUM_PRIVATE_RELEASE_MILLIS);
		rimEnabled = builder.rimEnabled;
		soundEnabled = builder.soundEnabled;
		targetBiomeKey = builder.targetBiomeKey;
	}

	public static RtpSettings defaults(RtpWorld world)
	{
		return builder(world).build();
	}

	public static Builder builder(RtpWorld world)
	{
		return new Builder(world);
	}

	public Builder toBuilder()
	{
		return new Builder(this);
	}

	public String getSourceWorldKey()
	{
		return sourceWorldKey;
	}

	public RtpWorld getTargetWorld()
	{
		return targetWorld;
	}

	public String getTargetWorldKey()
	{
		return targetWorldOverrideKey == null ? sourceWorldKey : targetWorldOverrideKey;
	}

	public boolean isSourceWorldTarget()
	{
		return targetWorldOverrideKey == null;
	}

	public RtpCenterMode getCenterMode()
	{
		return centerMode;
	}

	public Double getCustomCenterX()
	{
		return customCenterX;
	}

	public Double getCustomCenterZ()
	{
		return customCenterZ;
	}

	public int getMinimumRadius()
	{
		return minimumRadius;
	}

	public int getMaximumRadius()
	{
		return maximumRadius;
	}

	public RtpVerticalMode getVerticalMode()
	{
		return verticalMode;
	}

	public RtpSafetyMode getSafetyMode()
	{
		return safetyMode;
	}

	public int getLowerY()
	{
		return lowerY;
	}

	public int getUpperY()
	{
		return upperY;
	}

	public int getPreferredY()
	{
		return preferredY;
	}

	public RtpAllocationMode getAllocationMode()
	{
		return allocationMode;
	}

	public RtpRotationMode getRotationMode()
	{
		return rotationMode;
	}

	public long getCycleDurationMillis()
	{
		return cycleDurationMillis;
	}

	public long getLeaseIdleMillis()
	{
		return leaseIdleMillis;
	}

	public long getPrivateReleaseMillis()
	{
		return privateReleaseMillis;
	}

	public boolean isRimEnabled()
	{
		return rimEnabled;
	}

	public boolean isSoundEnabled()
	{
		return soundEnabled;
	}

	public String getTargetBiomeKey()
	{
		return targetBiomeKey;
	}

	public boolean hasSameRouteAs(RtpSettings other)
	{
		RtpSettings requiredOther = Objects.requireNonNull(other, "other");
		return minimumRadius == requiredOther.minimumRadius
				&& maximumRadius == requiredOther.maximumRadius
				&& lowerY == requiredOther.lowerY
				&& upperY == requiredOther.upperY
				&& preferredY == requiredOther.preferredY
				&& cycleDurationMillis == requiredOther.cycleDurationMillis
				&& leaseIdleMillis == requiredOther.leaseIdleMillis
				&& privateReleaseMillis == requiredOther.privateReleaseMillis
				&& sourceWorldKey.equals(requiredOther.sourceWorldKey)
				&& Objects.equals(targetWorldOverrideKey, requiredOther.targetWorldOverrideKey)
				&& centerMode == requiredOther.centerMode
				&& Objects.equals(customCenterX, requiredOther.customCenterX)
				&& Objects.equals(customCenterZ, requiredOther.customCenterZ)
				&& verticalMode == requiredOther.verticalMode
				&& safetyMode == requiredOther.safetyMode
				&& allocationMode == requiredOther.allocationMode
				&& rotationMode == requiredOther.rotationMode
				&& Objects.equals(targetBiomeKey, requiredOther.targetBiomeKey);
	}

	@Override
	public boolean equals(Object object)
	{
		if(this == object)
		{
			return true;
		}
		if(!(object instanceof RtpSettings other))
		{
			return false;
		}
		return minimumRadius == other.minimumRadius
				&& maximumRadius == other.maximumRadius
				&& lowerY == other.lowerY
				&& upperY == other.upperY
				&& preferredY == other.preferredY
				&& cycleDurationMillis == other.cycleDurationMillis
				&& leaseIdleMillis == other.leaseIdleMillis
				&& privateReleaseMillis == other.privateReleaseMillis
				&& rimEnabled == other.rimEnabled
				&& soundEnabled == other.soundEnabled
				&& sourceWorldKey.equals(other.sourceWorldKey)
				&& Objects.equals(targetWorldOverrideKey, other.targetWorldOverrideKey)
				&& centerMode == other.centerMode
				&& Objects.equals(customCenterX, other.customCenterX)
				&& Objects.equals(customCenterZ, other.customCenterZ)
				&& verticalMode == other.verticalMode
				&& safetyMode == other.safetyMode
				&& allocationMode == other.allocationMode
				&& rotationMode == other.rotationMode
				&& Objects.equals(targetBiomeKey, other.targetBiomeKey);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(sourceWorldKey, targetWorldOverrideKey, centerMode, customCenterX, customCenterZ,
				Integer.valueOf(minimumRadius), Integer.valueOf(maximumRadius), verticalMode, safetyMode, Integer.valueOf(lowerY),
				Integer.valueOf(upperY), Integer.valueOf(preferredY), allocationMode, rotationMode,
				Long.valueOf(cycleDurationMillis), Long.valueOf(leaseIdleMillis), Long.valueOf(privateReleaseMillis),
				Boolean.valueOf(rimEnabled), Boolean.valueOf(soundEnabled), targetBiomeKey);
	}

	private static void validateCustomCenter(RtpCenterMode centerMode, Double customCenterX, Double customCenterZ)
	{
		if((customCenterX == null) != (customCenterZ == null))
		{
			throw new IllegalArgumentException("custom center X and Z must both be present or absent");
		}
		if(customCenterX != null && (!isBoundedCoordinate(customCenterX.doubleValue())
				|| !isBoundedCoordinate(customCenterZ.doubleValue())))
		{
			throw new IllegalArgumentException("custom center coordinates are outside the Minecraft coordinate range");
		}
		if(centerMode == RtpCenterMode.CUSTOM && customCenterX == null)
		{
			throw new IllegalArgumentException("custom center coordinates are required in custom mode");
		}
	}

	static boolean isBoundedCoordinate(double value)
	{
		return Double.isFinite(value) && value >= MINIMUM_COORDINATE && value <= MAXIMUM_COORDINATE;
	}

	static int clamp(int value, int minimum, int maximum)
	{
		return Math.max(minimum, Math.min(maximum, value));
	}

	static long clamp(long value, long minimum, long maximum)
	{
		return Math.max(minimum, Math.min(maximum, value));
	}

	public static final class Builder
	{
		private final RtpWorld sourceWorld;
		RtpWorld targetWorld;
		private String targetWorldOverrideKey;
		private RtpCenterMode centerMode;
		private Double customCenterX;
		private Double customCenterZ;
		private int minimumRadius;
		private int maximumRadius;
		private RtpVerticalMode verticalMode;
		private RtpSafetyMode safetyMode;
		private int lowerY;
		private int upperY;
		private int preferredY;
		private RtpAllocationMode allocationMode;
		private RtpRotationMode rotationMode;
		private long cycleDurationMillis;
		private long leaseIdleMillis;
		private long privateReleaseMillis;
		private boolean rimEnabled;
		private boolean soundEnabled;
		private String targetBiomeKey;

		private Builder(RtpWorld sourceWorld)
		{
			this.sourceWorld = Objects.requireNonNull(sourceWorld, "sourceWorld");
			targetWorld = sourceWorld;
			targetWorldOverrideKey = null;
			centerMode = RtpCenterMode.PORTAL_RELATIVE;
			customCenterX = null;
			customCenterZ = null;
			minimumRadius = DEFAULT_MINIMUM_RADIUS;
			maximumRadius = DEFAULT_MAXIMUM_RADIUS;
			verticalMode = RtpVerticalMode.SURFACE;
			safetyMode = RtpSafetyMode.SAFE;
			applyWorldDefaults(sourceWorld);
			allocationMode = RtpAllocationMode.SHARED;
			rotationMode = RtpRotationMode.ON_TRAVERSAL;
			cycleDurationMillis = DEFAULT_CYCLE_DURATION_MILLIS;
			leaseIdleMillis = DEFAULT_LEASE_IDLE_MILLIS;
			privateReleaseMillis = DEFAULT_PRIVATE_RELEASE_MILLIS;
			rimEnabled = true;
			soundEnabled = true;
			targetBiomeKey = null;
		}

		private Builder(RtpSettings settings)
		{
			RtpSettings requiredSettings = Objects.requireNonNull(settings, "settings");
			sourceWorld = requiredSettings.sourceWorld;
			targetWorld = requiredSettings.targetWorld;
			targetWorldOverrideKey = requiredSettings.targetWorldOverrideKey;
			centerMode = requiredSettings.centerMode;
			customCenterX = requiredSettings.customCenterX;
			customCenterZ = requiredSettings.customCenterZ;
			minimumRadius = requiredSettings.minimumRadius;
			maximumRadius = requiredSettings.maximumRadius;
			verticalMode = requiredSettings.verticalMode;
			safetyMode = requiredSettings.safetyMode;
			lowerY = requiredSettings.lowerY;
			upperY = requiredSettings.upperY;
			preferredY = requiredSettings.preferredY;
			allocationMode = requiredSettings.allocationMode;
			rotationMode = requiredSettings.rotationMode;
			cycleDurationMillis = requiredSettings.cycleDurationMillis;
			leaseIdleMillis = requiredSettings.leaseIdleMillis;
			privateReleaseMillis = requiredSettings.privateReleaseMillis;
			rimEnabled = requiredSettings.rimEnabled;
			soundEnabled = requiredSettings.soundEnabled;
			targetBiomeKey = requiredSettings.targetBiomeKey;
		}

		public Builder targetWorld(RtpWorld world)
		{
			RtpWorld requiredWorld = Objects.requireNonNull(world, "world");
			String worldKey = requiredWorld.key();
			String sourceKey = sourceWorld.key();
			targetWorld = requiredWorld;
			targetWorldOverrideKey = sourceKey.equals(worldKey) ? null : worldKey;
			applyWorldDefaults(requiredWorld);
			return this;
		}

		Builder targetWorld(String worldKey, RtpWorld world)
		{
			String requiredWorldKey = Objects.requireNonNull(worldKey, "worldKey");
			if(sourceWorld.key().equals(requiredWorldKey))
			{
				return targetWorld(sourceWorld);
			}
			targetWorldOverrideKey = requiredWorldKey;
			targetWorld = world;
			if(world != null)
			{
				applyWorldDefaults(world);
			}
			return this;
		}

		public Builder centerMode(RtpCenterMode mode)
		{
			centerMode = Objects.requireNonNull(mode, "mode");
			return this;
		}

		public Builder customCenter(double x, double z)
		{
			customCenterX = Double.valueOf(x);
			customCenterZ = Double.valueOf(z);
			return this;
		}

		public Builder clearCustomCenter()
		{
			customCenterX = null;
			customCenterZ = null;
			return this;
		}

		public Builder radii(int minimum, int maximum)
		{
			minimumRadius = minimum;
			maximumRadius = maximum;
			return this;
		}

		public Builder verticalMode(RtpVerticalMode mode)
		{
			verticalMode = Objects.requireNonNull(mode, "mode");
			return this;
		}

		public Builder safetyMode(RtpSafetyMode mode)
		{
			safetyMode = Objects.requireNonNull(mode, "mode");
			return this;
		}

		public Builder yBounds(int lower, int upper)
		{
			lowerY = lower;
			upperY = upper;
			return this;
		}

		public Builder preferredY(int preferred)
		{
			preferredY = preferred;
			return this;
		}

		public Builder allocationMode(RtpAllocationMode mode)
		{
			allocationMode = Objects.requireNonNull(mode, "mode");
			return this;
		}

		public Builder rotationMode(RtpRotationMode mode)
		{
			rotationMode = Objects.requireNonNull(mode, "mode");
			return this;
		}

		public Builder cycleDurationMillis(long durationMillis)
		{
			cycleDurationMillis = durationMillis;
			return this;
		}

		public Builder leaseIdleMillis(long idleMillis)
		{
			leaseIdleMillis = idleMillis;
			return this;
		}

		public Builder privateReleaseMillis(long releaseMillis)
		{
			privateReleaseMillis = releaseMillis;
			return this;
		}

		public Builder rimEnabled(boolean enabled)
		{
			rimEnabled = enabled;
			return this;
		}

		public Builder soundEnabled(boolean enabled)
		{
			soundEnabled = enabled;
			return this;
		}

		public Builder targetBiomeKey(String biomeKey)
		{
			targetBiomeKey = RtpBiomeMatcher.normalize(biomeKey);
			return this;
		}

		public RtpSettings build()
		{
			return new RtpSettings(this);
		}

		private void applyWorldDefaults(RtpWorld world)
		{
			lowerY = world.minimumHeight() + 1;
			upperY = world.maximumHeight() - 2;
			preferredY = clamp(world.seaLevel() + 1, lowerY, upperY);
		}
	}
}
