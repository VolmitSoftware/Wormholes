package art.arcane.wormholes.portal.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.UUID;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.Mutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.SettingsSnapshot;

public final class RtpPortalEditorModelTest
{
	@Test
	public void settingsSnapshotAndMutationPreserveSoundPolicy()
	{
		World source = world("overworld", -64, 320, 63);
		SettingsSnapshot defaults = SettingsSnapshot.from(RtpSettings.defaults(BukkitRtpRuntime.worldSettings(source)));

		assertTrue(defaults.rimEnabled());
		assertTrue(defaults.soundEnabled());
		assertEquals(RtpSafetyMode.SAFE, defaults.safetyMode());
		RtpSettings muted = RtpPortalEditorModel.applyMutation(
				RtpSettings.defaults(BukkitRtpRuntime.worldSettings(source)),
				new RtpPortalEditorModel.SoundMutation(false),
				BukkitRtpRuntime.worldSettings(source),
				key -> BukkitRtpRuntime.worldSettings(source));
		assertFalse(muted.isSoundEnabled());
	}

	@Test
	public void productionMutationApplicationCoversRoutingAndPresentationControls()
	{
		World source = world("overworld", -64, 320, 63);
		World target = world("the_nether", 0, 256, 32);
		BukkitRtpRuntime.WorldResolver resolver = key -> "minecraft:the_nether".equals(key) ? target : source;
		RtpSettings settings = RtpSettings.defaults(BukkitRtpRuntime.worldSettings(source));

		settings = apply(settings, new RtpPortalEditorModel.TargetWorldMutation("minecraft:the_nether"), source, resolver);
		settings = apply(settings, new RtpPortalEditorModel.CenterModeMutation(RtpCenterMode.CUSTOM, 12.5D, -7.25D), source, resolver);
		settings = apply(settings, new RtpPortalEditorModel.RadiiMutation(96, 2048), source, resolver);
		settings = apply(settings, new RtpPortalEditorModel.VerticalModeMutation(RtpVerticalMode.PREFERRED_AVERAGE), source, resolver);
		settings = apply(settings, new RtpPortalEditorModel.SafetyModeMutation(RtpSafetyMode.UNSAFE), source, resolver);
		settings = apply(settings, new RtpPortalEditorModel.YMutation(5, 200, 88), source, resolver);
		settings = apply(settings, new RtpPortalEditorModel.AllocationMutation(RtpAllocationMode.PER_PLAYER), source, resolver);
		settings = apply(settings, new RtpPortalEditorModel.CycleDurationMutation(600_000L), source, resolver);
		settings = apply(settings, new RtpPortalEditorModel.LeaseIdleMutation(45_000L), source, resolver);
		settings = apply(settings, new RtpPortalEditorModel.ReservationTimeoutMutation(25_000L), source, resolver);
		settings = apply(settings, new RtpPortalEditorModel.RimMutation(false), source, resolver);
		settings = apply(settings, new RtpPortalEditorModel.SoundMutation(false), source, resolver);
		settings = apply(settings, new RtpPortalEditorModel.TargetBiomeMutation(" Minecraft:Swamp "), source, resolver);

		assertEquals("minecraft:the_nether", settings.getTargetWorldKey());
		assertEquals("minecraft:swamp", settings.getTargetBiomeKey());
		assertEquals(RtpCenterMode.CUSTOM, settings.getCenterMode());
		assertEquals(96, settings.getMinimumRadius());
		assertEquals(2048, settings.getMaximumRadius());
		assertEquals(RtpSafetyMode.UNSAFE, settings.getSafetyMode());
		assertEquals(RtpAllocationMode.PER_PLAYER, settings.getAllocationMode());
		assertEquals(600_000L, settings.getCycleDurationMillis());
		assertEquals(45_000L, settings.getLeaseIdleMillis());
		assertEquals(25_000L, settings.getPrivateReleaseMillis());
		assertFalse(settings.isRimEnabled());
		assertFalse(settings.isSoundEnabled());
	}

	@Test
	public void settingsSnapshotRejectsInvalidCustomState()
	{
		SettingsSnapshot source = settings();
		assertThrows(IllegalArgumentException.class, () -> new SettingsSnapshot(
				source.sourceWorldKey(), source.targetWorldKey(), RtpCenterMode.CUSTOM, null, null,
				source.minimumRadius(), source.maximumRadius(), source.verticalMode(), source.safetyMode(),
				source.lowerY(), source.upperY(), source.preferredY(),
				source.allocationMode(), source.rotationMode(), source.cycleDurationMillis(), source.leaseIdleMillis(),
				source.reservationTimeoutMillis(), source.rimEnabled(), source.soundEnabled(), source.targetBiomeKey()));
	}

	@Test
	public void settingsSnapshotAcceptsRuntimeCoordinateAndRadiusBoundaries()
	{
		World source = world("overworld", -64, 320, 63);
		RtpSettings settings = RtpSettings.builder(BukkitRtpRuntime.worldSettings(source))
				.centerMode(RtpCenterMode.CUSTOM)
				.customCenter(RtpSettings.MINIMUM_COORDINATE, RtpSettings.MAXIMUM_COORDINATE)
				.radii(RtpSettings.MAXIMUM_RADIUS - 1, RtpSettings.MAXIMUM_RADIUS)
				.build();

		SettingsSnapshot snapshot = SettingsSnapshot.from(settings);

		assertEquals(RtpSettings.MINIMUM_COORDINATE, snapshot.customCenterX().doubleValue());
		assertEquals(RtpSettings.MAXIMUM_COORDINATE, snapshot.customCenterZ().doubleValue());
		assertEquals(RtpSettings.MAXIMUM_RADIUS, snapshot.maximumRadius());
	}

	private static RtpSettings apply(RtpSettings settings, Mutation mutation, World source, BukkitRtpRuntime.WorldResolver resolver)
	{
		return RtpPortalEditorModel.applyMutation(settings, mutation, BukkitRtpRuntime.worldSettings(source), key -> {
            World target = resolver.resolve(key);
            return target == null ? null : BukkitRtpRuntime.worldSettings(target);
        });
	}

	private static SettingsSnapshot settings()
	{
		return new SettingsSnapshot("minecraft:overworld", "minecraft:overworld", RtpCenterMode.PORTAL_RELATIVE,
				null, null, 512, 4096, RtpVerticalMode.SURFACE, RtpSafetyMode.SAFE, -63, 318, 64,
				RtpAllocationMode.SHARED, RtpRotationMode.STATIC, 300_000L, 30_000L, 15_000L, true, true, null);
	}

	private static World world(String key, int minimumHeight, int maximumHeight, int seaLevel)
	{
		NamespacedKey namespacedKey = NamespacedKey.minecraft(key);
		return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] { World.class }, (proxy, method, arguments) -> switch(method.getName())
		{
			case "getName" -> key;
			case "getKey" -> namespacedKey;
			case "getUID" -> UUID.nameUUIDFromBytes(namespacedKey.toString().getBytes());
			case "getMinHeight" -> Integer.valueOf(minimumHeight);
			case "getMaxHeight" -> Integer.valueOf(maximumHeight);
			case "getSeaLevel" -> Integer.valueOf(seaLevel);
			case "toString" -> "RtpPortalEditorTestWorld[" + namespacedKey + "]";
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "equals" -> Boolean.valueOf(proxy == arguments[0]);
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}
}
