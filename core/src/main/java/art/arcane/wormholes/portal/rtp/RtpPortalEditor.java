package art.arcane.wormholes.portal.rtp;

import static art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.MAXIMUM_COORDINATE;
import static art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.MAXIMUM_CYCLE_MILLIS;
import static art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.MAXIMUM_LEASE_MILLIS;
import static art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.MAXIMUM_RADIUS;
import static art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.MAXIMUM_RESERVATION_MILLIS;
import static art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.MINIMUM_COORDINATE;
import static art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.MINIMUM_CYCLE_MILLIS;
import static art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.MINIMUM_LEASE_MILLIS;
import static art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.MINIMUM_RESERVATION_MILLIS;

import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;


import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.AllocationMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.CenterModeMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.CustomCenterMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.CycleDurationMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.EditorSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.LeaseIdleMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.ManualAction;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.Mutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.RadiiMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.ReservationTimeoutMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.ResetCenterTargetMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.RimMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.RotationMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.SafetyModeMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.SettingsSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.SoundMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.StatusSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.StatusState;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.TargetWorldMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.VerticalModeMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.WorldOption;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.YMutation;

public final class RtpPortalEditor
{
	private static final int WORLDS_PER_PAGE = 8;
	private static final int[] WORLD_POSITIONS = new int[] { -3, -1, 1, 3 };
	private static final int BIOMES_PER_PAGE = 28;
	private static final int BIOME_ROW_WIDTH = 7;

	private final Host host;
	private Page page;
	private NumericField numericField;
	private ManualAction confirmationAction;
	private int worldPage;
	private int biomePage;
	private boolean configured;

	public RtpPortalEditor(Host host)
	{
		this.host = Objects.requireNonNull(host, "host");
		page = Page.OVERVIEW;
	}

	public void populate(View window, UUID viewerId)
	{
		View requiredWindow = Objects.requireNonNull(window, "window");
		UUID requiredViewerId = Objects.requireNonNull(viewerId, "viewerId");
		EditorSnapshot snapshot = Objects.requireNonNull(host.snapshot(requiredViewerId), "snapshot");
		requiredWindow.batch(() ->
		{
			if(!configured)
			{
				requiredWindow.configure(snapshot.title());
				configured = true;
			}
			requiredWindow.clearElements();
			switch(page)
			{
				case OVERVIEW -> populateOverview(requiredWindow, requiredViewerId, snapshot);
				case DESTINATION -> populateDestination(requiredWindow, requiredViewerId, snapshot);
				case BIOME -> populateBiome(requiredWindow, requiredViewerId, snapshot);
				case LANDING -> populateLanding(requiredWindow, requiredViewerId, snapshot);
				case ROUTING -> populateRouting(requiredWindow, requiredViewerId, snapshot);
				case EFFECTS -> populateEffects(requiredWindow, requiredViewerId, snapshot);
				case NUMERIC -> populateNumeric(requiredWindow, requiredViewerId, snapshot);
				case MANUAL_CONFIRM -> populateManualConfirmation(requiredWindow, requiredViewerId, snapshot);
			}
		});
	}

	private void populateOverview(View window, UUID viewerId, EditorSnapshot snapshot)
	{
		window.setElement(0, 0, statusElement(snapshot));
		window.setElement(-3, 2, pageElement(window, viewerId, "rtp-open-destination",
				WormholesMessages.RTP_OVERVIEW_DESTINATION, Icon.RECOVERY_COMPASS, Page.DESTINATION));
		window.setElement(-1, 2, pageElement(window, viewerId, "rtp-open-landing",
				WormholesMessages.RTP_OVERVIEW_LANDING, Icon.GRASS_BLOCK, Page.LANDING));
		window.setElement(1, 2, pageElement(window, viewerId, "rtp-open-routing",
				WormholesMessages.RTP_OVERVIEW_ROUTING, Icon.CLOCK, Page.ROUTING));
		window.setElement(3, 2, pageElement(window, viewerId, "rtp-open-effects",
				WormholesMessages.RTP_OVERVIEW_EFFECTS, Icon.GLOWSTONE_DUST, Page.EFFECTS));
		window.setElement(0, 4, actionElement("rtp-reset-defaults", WormholesMessages.RTP_RESET_DEFAULTS,
				MessageArgs.empty(), Icon.TNT_MINECART,
				() -> host.reset(viewerId, snapshot.configurationRevision())));
		window.setElement(0, 5, portalBackElement(window, viewerId));
	}

	private void populateDestination(View window, UUID viewerId, EditorSnapshot snapshot)
	{
		SettingsSnapshot settings = snapshot.settings();
		window.setElement(0, 0, headerElement(WormholesMessages.RTP_DESTINATION_HEADER, Icon.RECOVERY_COMPASS));
		List<WorldOption> worlds = snapshot.loadedWorlds();
		int pageCount = Math.max(1, (worlds.size() + WORLDS_PER_PAGE - 1) / WORLDS_PER_PAGE);
		worldPage = clamp(worldPage, 0, pageCount - 1);
		int start = worldPage * WORLDS_PER_PAGE;
		int end = Math.min(worlds.size(), start + WORLDS_PER_PAGE);
		for(int index = start; index < end; index++)
		{
			WorldOption world = worlds.get(index);
			int pageIndex = index - start;
			int row = 1 + pageIndex / WORLD_POSITIONS.length;
			int position = WORLD_POSITIONS[pageIndex % WORLD_POSITIONS.length];
			boolean selected = world.key().equalsIgnoreCase(settings.targetWorldKey());
			window.setElement(position, row, choiceElement(
					"rtp-world-" + pageIndex,
					selected ? WormholesMessages.RTP_WORLD_CURRENT : WormholesMessages.RTP_WORLD_AVAILABLE,
					arguments(MessageArgument.untrusted("world", world.displayName())),
					Icon.ENDER_EYE,
					selected,
					() -> mutate(snapshot, viewerId, new TargetWorldMutation(world.key()))));
		}
		if(worldPage > 0)
		{
			window.setElement(-4, 3, actionElement("rtp-world-previous", WormholesMessages.RTP_PREVIOUS_WORLDS,
					MessageArgs.empty(), Icon.ARROW, () -> changeWorldPage(window, viewerId, -1)));
		}
		if(worldPage + 1 < pageCount)
		{
			window.setElement(4, 3, actionElement("rtp-world-next", WormholesMessages.RTP_NEXT_WORLDS,
					MessageArgs.empty(), Icon.ARROW, () -> changeWorldPage(window, viewerId, 1)));
		}
		boolean portalRelative = settings.centerMode() == RtpCenterMode.PORTAL_RELATIVE;
		window.setElement(-2, 3, choiceElement("rtp-center-portal",
				portalRelative ? WormholesMessages.RTP_CENTER_PORTAL_SELECTED : WormholesMessages.RTP_CENTER_PORTAL_AVAILABLE,
				MessageArgs.empty(), Icon.COMPASS,
				portalRelative,
				() -> mutate(snapshot, viewerId, centerModeMutation(snapshot, RtpCenterMode.PORTAL_RELATIVE))));
		boolean custom = settings.centerMode() == RtpCenterMode.CUSTOM;
		window.setElement(2, 3, choiceElement("rtp-center-custom",
				custom ? WormholesMessages.RTP_CENTER_CUSTOM_SELECTED : WormholesMessages.RTP_CENTER_CUSTOM_AVAILABLE,
				MessageArgs.empty(), Icon.LODESTONE,
				custom,
				() -> mutate(snapshot, viewerId, centerModeMutation(snapshot, RtpCenterMode.CUSTOM))));
		if(settings.centerMode() == RtpCenterMode.CUSTOM)
		{
			window.setElement(-3, 4, numericLink(window, viewerId, "rtp-center-x", WormholesMessages.RTP_LABEL_CENTER_X,
					formatCoordinate(Objects.requireNonNull(settings.customCenterX()).doubleValue()), Icon.COMPASS, NumericField.CENTER_X));
			window.setElement(-1, 4, numericLink(window, viewerId, "rtp-center-z", WormholesMessages.RTP_LABEL_CENTER_Z,
					formatCoordinate(Objects.requireNonNull(settings.customCenterZ()).doubleValue()), Icon.COMPASS, NumericField.CENTER_Z));
		}
		window.setElement(1, 4, numericLink(window, viewerId, "rtp-minimum-radius", WormholesMessages.RTP_LABEL_MIN_RADIUS,
				Integer.toString(settings.minimumRadius()), Icon.SPYGLASS, NumericField.MINIMUM_RADIUS));
		window.setElement(3, 4, numericLink(window, viewerId, "rtp-maximum-radius", WormholesMessages.RTP_LABEL_MAX_RADIUS,
				Integer.toString(settings.maximumRadius()), Icon.TARGET, NumericField.MAXIMUM_RADIUS));
		window.setElement(-3, 5, actionElement("rtp-center-reset", WormholesMessages.RTP_RESET_CENTER,
				MessageArgs.empty(), Icon.BARRIER,
				() -> mutate(snapshot, viewerId, new ResetCenterTargetMutation())));
		String biomeValue = selectedBiomeName(viewerId, settings.targetBiomeKey());
		window.setElement(3, 5, actionElement("rtp-target-biome", WormholesMessages.RTP_BIOME_LINK,
				arguments(MessageArgument.untrusted("value", biomeValue)),
				Icon.OAK_SAPLING, () -> navigate(window, viewerId, Page.BIOME)));
		window.setElement(0, 5, submenuBackElement(window, viewerId));
	}

	private String selectedBiomeName(UUID viewerId, String biomeKey)
	{
		if(biomeKey == null)
		{
			return host.text(WormholesMessages.RTP_BIOME_ANY_LABEL);
		}
		for(RtpPortalEditorModel.BiomeOption biome : host.biomeOptions(viewerId))
		{
			if(biome.key().equalsIgnoreCase(biomeKey))
			{
				return biome.displayName();
			}
		}
		return host.text(WormholesMessages.RTP_BIOME_UNAVAILABLE_LABEL);
	}

	private void populateBiome(View window, UUID viewerId, EditorSnapshot snapshot)
	{
		SettingsSnapshot settings = snapshot.settings();
		window.setElement(0, 0, headerElement(WormholesMessages.RTP_BIOME_HEADER, Icon.OAK_SAPLING));
		boolean anySelected = settings.targetBiomeKey() == null;
		window.setElement(-4, 1, choiceElement("rtp-biome-any",
				anySelected ? WormholesMessages.RTP_BIOME_ANY_SELECTED : WormholesMessages.RTP_BIOME_ANY_AVAILABLE,
				MessageArgs.empty(), Icon.GLASS,
				anySelected,
				() -> mutate(snapshot, viewerId, new RtpPortalEditorModel.TargetBiomeMutation(null))));
		List<RtpPortalEditorModel.BiomeOption> biomes = host.biomeOptions(viewerId);
		if(biomes.isEmpty())
		{
			window.setElement(0, 2, infoElement("rtp-biome-empty", WormholesMessages.RTP_BIOME_EMPTY,
					MessageArgs.empty(), Icon.BARRIER, false));
		}
		int pageCount = Math.max(1, (biomes.size() + BIOMES_PER_PAGE - 1) / BIOMES_PER_PAGE);
		biomePage = clamp(biomePage, 0, pageCount - 1);
		int start = biomePage * BIOMES_PER_PAGE;
		int end = Math.min(biomes.size(), start + BIOMES_PER_PAGE);
		for(int index = start; index < end; index++)
		{
			RtpPortalEditorModel.BiomeOption biome = biomes.get(index);
			int pageIndex = index - start;
			int row = 1 + pageIndex / BIOME_ROW_WIDTH;
			int position = pageIndex % BIOME_ROW_WIDTH - 3;
			boolean selected = biome.key().equalsIgnoreCase(settings.targetBiomeKey());
			window.setElement(position, row, choiceElement(
					"rtp-biome-" + pageIndex,
					selected ? WormholesMessages.RTP_BIOME_CURRENT : WormholesMessages.RTP_BIOME_AVAILABLE,
					arguments(
							MessageArgument.untrusted("biome", biome.displayName()),
							MessageArgument.untrusted("key", biome.key())),
					biome.irisBiome() ? Icon.AMETHYST_SHARD : Icon.FERN,
					selected,
					() -> mutate(snapshot, viewerId, new RtpPortalEditorModel.TargetBiomeMutation(biome.key()))));
		}
		if(biomePage > 0)
		{
			window.setElement(-4, 5, actionElement("rtp-biome-previous", WormholesMessages.RTP_PREVIOUS_BIOMES,
					MessageArgs.empty(), Icon.ARROW, () -> changeBiomePage(window, viewerId, -1)));
		}
		if(biomePage + 1 < pageCount)
		{
			window.setElement(4, 5, actionElement("rtp-biome-next", WormholesMessages.RTP_NEXT_BIOMES,
					MessageArgs.empty(), Icon.ARROW, () -> changeBiomePage(window, viewerId, 1)));
		}
		window.setElement(0, 5, actionElement("rtp-biome-back", WormholesMessages.RTP_BACK_CATEGORY,
				MessageArgs.empty(), Icon.ARROW, () -> navigate(window, viewerId, Page.DESTINATION)));
	}

	private void populateLanding(View window, UUID viewerId, EditorSnapshot snapshot)
	{
		SettingsSnapshot settings = snapshot.settings();
		window.setElement(0, 0, headerElement(WormholesMessages.RTP_LANDING_HEADER, Icon.GRASS_BLOCK));
		boolean surface = settings.verticalMode() == RtpVerticalMode.SURFACE;
		window.setElement(-2, 1, choiceElement("rtp-vertical-surface",
				surface ? WormholesMessages.RTP_SURFACE_SELECTED : WormholesMessages.RTP_SURFACE_AVAILABLE,
				MessageArgs.empty(), Icon.GRASS_BLOCK,
				surface,
				() -> mutate(snapshot, viewerId, new VerticalModeMutation(RtpVerticalMode.SURFACE))));
		boolean preferred = settings.verticalMode() == RtpVerticalMode.PREFERRED_AVERAGE;
		window.setElement(2, 1, choiceElement("rtp-vertical-preferred",
				preferred ? WormholesMessages.RTP_PREFERRED_SELECTED : WormholesMessages.RTP_PREFERRED_AVAILABLE,
				MessageArgs.empty(), Icon.AMETHYST_SHARD,
				preferred,
				() -> mutate(snapshot, viewerId, new VerticalModeMutation(RtpVerticalMode.PREFERRED_AVERAGE))));
		boolean safeLanding = settings.safetyMode() == RtpSafetyMode.SAFE;
		window.setElement(0, 2, choiceElement("rtp-surface-policy", WormholesMessages.RTP_SAFE_LANDING,
				arguments(MessageArgument.untrusted("mode", settings.safetyMode().name())),
				safeLanding ? Icon.SHIELD : Icon.LAVA_BUCKET,
				safeLanding,
				() -> mutate(snapshot, viewerId, new SafetyModeMutation(
						safeLanding ? RtpSafetyMode.UNSAFE : RtpSafetyMode.SAFE))));
		window.setElement(-2, 3, numericLink(window, viewerId, "rtp-lower-y", WormholesMessages.RTP_LABEL_LOWER_Y,
				Integer.toString(settings.lowerY()), Icon.LADDER, NumericField.LOWER_Y));
		window.setElement(0, 3, numericLink(window, viewerId, "rtp-upper-y", WormholesMessages.RTP_LABEL_UPPER_Y,
				Integer.toString(settings.upperY()), Icon.LADDER, NumericField.UPPER_Y));
		if(settings.verticalMode() == RtpVerticalMode.PREFERRED_AVERAGE)
		{
			window.setElement(2, 3, numericLink(window, viewerId, "rtp-preferred-y", WormholesMessages.RTP_LABEL_PREFERRED_Y,
					Integer.toString(settings.preferredY()), Icon.AMETHYST_SHARD, NumericField.PREFERRED_Y));
		}
		window.setElement(0, 5, submenuBackElement(window, viewerId));
	}

	private void populateRouting(View window, UUID viewerId, EditorSnapshot snapshot)
	{
		SettingsSnapshot settings = snapshot.settings();
		window.setElement(0, 0, headerElement(WormholesMessages.RTP_ROUTING_HEADER, Icon.CLOCK));
		boolean shared = settings.allocationMode() == RtpAllocationMode.SHARED;
		window.setElement(-2, 1, choiceElement("rtp-allocation-shared",
				shared ? WormholesMessages.RTP_SHARED_SELECTED : WormholesMessages.RTP_SHARED_AVAILABLE,
				MessageArgs.empty(), Icon.ENDER_CHEST,
				shared,
				() -> mutate(snapshot, viewerId, new AllocationMutation(RtpAllocationMode.SHARED))));
		window.setElement(2, 1, choiceElement("rtp-allocation-private",
				shared ? WormholesMessages.RTP_PRIVATE_AVAILABLE : WormholesMessages.RTP_PRIVATE_SELECTED,
				MessageArgs.empty(), Icon.PLAYER_HEAD,
				!shared,
				() -> mutate(snapshot, viewerId, new AllocationMutation(RtpAllocationMode.PER_PLAYER))));
		if(shared)
		{
			boolean staticRotation = settings.rotationMode() == RtpRotationMode.STATIC;
			window.setElement(-2, 2, choiceElement("rtp-rotation-static",
					staticRotation ? WormholesMessages.RTP_STATIC_SELECTED : WormholesMessages.RTP_STATIC_AVAILABLE,
					MessageArgs.empty(), Icon.ENDER_PEARL,
					staticRotation,
					() -> mutate(snapshot, viewerId, new RotationMutation(RtpRotationMode.STATIC))));
			boolean timedRotation = settings.rotationMode() == RtpRotationMode.TIMED;
			window.setElement(0, 2, choiceElement("rtp-rotation-timed",
					timedRotation ? WormholesMessages.RTP_TIMED_SELECTED : WormholesMessages.RTP_TIMED_AVAILABLE,
					MessageArgs.empty(), Icon.CLOCK,
					timedRotation,
					() -> mutate(snapshot, viewerId, new RotationMutation(RtpRotationMode.TIMED))));
			boolean traversalRotation = settings.rotationMode() == RtpRotationMode.ON_TRAVERSAL;
			window.setElement(2, 2, choiceElement("rtp-rotation-traversal",
					traversalRotation ? WormholesMessages.RTP_TRIP_SELECTED : WormholesMessages.RTP_TRIP_AVAILABLE,
					MessageArgs.empty(), Icon.FIREWORK_ROCKET,
					traversalRotation,
					() -> mutate(snapshot, viewerId, new RotationMutation(RtpRotationMode.ON_TRAVERSAL))));
			if(settings.rotationMode() == RtpRotationMode.TIMED)
			{
				window.setElement(-3, 3, numericLink(window, viewerId, "rtp-cycle-duration", WormholesMessages.RTP_LABEL_CYCLE,
						formatDuration(settings.cycleDurationMillis()), Icon.CLOCK, NumericField.CYCLE_DURATION));
			}
		}
		else
		{
			window.setElement(-3, 3, numericLink(window, viewerId, "rtp-cycle-duration", WormholesMessages.RTP_LABEL_PRIVATE_ROTATION,
					formatDuration(settings.cycleDurationMillis()), Icon.CLOCK, NumericField.CYCLE_DURATION));
		}
		window.setElement(0, 3, numericLink(window, viewerId, "rtp-lease-idle", WormholesMessages.RTP_LABEL_LEASE,
				formatDuration(settings.leaseIdleMillis()), Icon.LEAD, NumericField.LEASE_IDLE));
		window.setElement(3, 3, numericLink(window, viewerId, "rtp-reservation-timeout", WormholesMessages.RTP_LABEL_RELEASE,
				formatDuration(settings.reservationTimeoutMillis()), Icon.TRIPWIRE_HOOK, NumericField.RESERVATION_TIMEOUT));
		ManualAction action = settings.allocationMode() == RtpAllocationMode.SHARED ? ManualAction.REROLL : ManualAction.REBUILD_POOL;
		LinesKey actionKey = action == ManualAction.REROLL ? WormholesMessages.RTP_MANUAL_REROLL : WormholesMessages.RTP_REBUILD_POOL;
		String actionId = action == ManualAction.REROLL ? "rtp-manual-reroll" : "rtp-rebuild-pool";
		window.setElement(0, 4, actionElement(actionId, actionKey,
				arguments(MessageArgument.untrusted("description", host.text(WormholesMessages.RTP_ACTION_CONFIRM))),
				Icon.FIRE_CHARGE,
				() -> openManualConfirmation(window, viewerId, action)));
		window.setElement(0, 5, submenuBackElement(window, viewerId));
	}

	private void populateEffects(View window, UUID viewerId, EditorSnapshot snapshot)
	{
		SettingsSnapshot settings = snapshot.settings();
		window.setElement(0, 0, headerElement(WormholesMessages.RTP_EFFECTS_HEADER, Icon.GLOWSTONE_DUST));
		window.setElement(-2, 1, choiceElement("rtp-rim-on",
				settings.rimEnabled() ? WormholesMessages.RTP_RIM_ON_SELECTED : WormholesMessages.RTP_RIM_ON_AVAILABLE,
				MessageArgs.empty(), Icon.GLOWSTONE_DUST,
				settings.rimEnabled(), () -> mutate(snapshot, viewerId, new RimMutation(true))));
		window.setElement(2, 1, choiceElement("rtp-rim-off",
				settings.rimEnabled() ? WormholesMessages.RTP_RIM_OFF_AVAILABLE : WormholesMessages.RTP_RIM_OFF_SELECTED,
				MessageArgs.empty(), Icon.GUNPOWDER,
				!settings.rimEnabled(), () -> mutate(snapshot, viewerId, new RimMutation(false))));
		window.setElement(-2, 3, choiceElement("rtp-sound-on",
				settings.soundEnabled() ? WormholesMessages.RTP_SOUND_ON_SELECTED : WormholesMessages.RTP_SOUND_ON_AVAILABLE,
				MessageArgs.empty(), Icon.JUKEBOX,
				settings.soundEnabled(), () -> mutate(snapshot, viewerId, new SoundMutation(true))));
		window.setElement(2, 3, choiceElement("rtp-sound-off",
				settings.soundEnabled() ? WormholesMessages.RTP_SOUND_OFF_AVAILABLE : WormholesMessages.RTP_SOUND_OFF_SELECTED,
				MessageArgs.empty(), Icon.NOTE_BLOCK,
				!settings.soundEnabled(), () -> mutate(snapshot, viewerId, new SoundMutation(false))));
		window.setElement(0, 5, submenuBackElement(window, viewerId));
	}

	private void populateNumeric(View window, UUID viewerId, EditorSnapshot snapshot)
	{
		NumericControl control = numericControl(snapshot, numericField);
		window.setElement(0, 0, infoElement(
				"rtp-page-title",
				WormholesMessages.RTP_NUMERIC_HEADER,
				arguments(
						MessageArgument.untrusted("label", host.text(control.label())),
						MessageArgument.untrusted("description", host.text(control.description()))),
				control.material(),
				false));
		if(!control.enabled())
		{
			window.setElement(0, 2, infoElement("rtp-numeric-unavailable", WormholesMessages.RTP_TARGET_UNAVAILABLE,
					MessageArgs.empty(), Icon.BARRIER, false));
		}
		else
		{
			window.setElement(-4, 2, adjustmentElement(snapshot, viewerId, control, false, true));
			window.setElement(-2, 2, adjustmentElement(snapshot, viewerId, control, false, false));
			window.setElement(0, 2, infoElement("rtp-numeric-value", WormholesMessages.RTP_NUMERIC_VALUE,
					arguments(MessageArgument.untrusted("value", control.value())), control.material(), true));
			window.setElement(2, 2, adjustmentElement(snapshot, viewerId, control, true, false));
			window.setElement(4, 2, adjustmentElement(snapshot, viewerId, control, true, true));
		}
		window.setElement(0, 5, numericBackElement(window, viewerId));
	}

	private void populateManualConfirmation(View window, UUID viewerId, EditorSnapshot snapshot)
	{
		ManualAction action = Objects.requireNonNull(confirmationAction, "confirmationAction");
		window.setElement(0, 0, headerElement(
				action == ManualAction.REROLL ? WormholesMessages.RTP_CONFIRM_REROLL : WormholesMessages.RTP_CONFIRM_REBUILD,
				Icon.FIRE_CHARGE));
		window.setElement(-2, 2, actionElement("rtp-manual-confirm", WormholesMessages.RTP_CONFIRM,
				MessageArgs.empty(), Icon.LIME_DYE, () ->
				{
					page = Page.ROUTING;
					host.manual(viewerId, snapshot.configurationRevision(), action);
				}));
		window.setElement(2, 2, actionElement("rtp-manual-cancel", WormholesMessages.RTP_CANCEL,
				MessageArgs.empty(), Icon.RED_DYE, () -> navigate(window, viewerId, Page.ROUTING)));
	}

	private Entry statusElement(EditorSnapshot snapshot)
	{
		SettingsSnapshot settings = snapshot.settings();
		StatusSnapshot status = snapshot.status();
		Entry element = new Entry("rtp-status");
		element.text(
				WormholesMessages.RTP_STATUS_HEADER,
				arguments(MessageArgument.untrusted("state", statusLabel(status.state()))));
		element.setMaterial(statusMaterial(status.state()));
		element.setEnchanted(status.state() == StatusState.READY);
		if(status.state() == StatusState.BACKOFF)
		{
			element.addLore(host.text(
					WormholesMessages.RTP_STATUS_RETRY,
					arguments(MessageArgument.untrusted("duration", formatDuration(status.remainingBackoffMillis())))));
		}
		if(settings.allocationMode() == RtpAllocationMode.SHARED)
		{
			element.addLore(host.text(
					WormholesMessages.RTP_STATUS_ACTIVE,
					arguments(MessageArgument.untrusted("readiness", readiness(status.activeReady())))));
			element.addLore(host.text(
					WormholesMessages.RTP_STATUS_STANDBY,
					arguments(MessageArgument.untrusted("readiness", readiness(status.standbyReady())))));
			element.addLore(host.text(
					WormholesMessages.RTP_STATUS_ROTATION,
					arguments(MessageArgument.untrusted("rotation", rotationLabel(settings.rotationMode())))));
		}
		else
		{
			element.addLore(host.text(
					WormholesMessages.RTP_STATUS_POOL,
					arguments(
							MessageArgument.untrusted("free", status.freeCount()),
							MessageArgument.untrusted("reserved", status.reservedCount()))));
		}
		if(!status.targetWorldAvailable())
		{
			element.addLore(host.text(WormholesMessages.RTP_STATUS_TARGET_MISSING));
		}
		if(!status.integrationAvailable())
		{
			element.addLore(host.text(WormholesMessages.RTP_STATUS_ACCESS_FAILED));
		}
		return element;
	}

	private Entry headerElement(LinesKey key, Icon material)
	{
		return infoElement("rtp-page-title", key, MessageArgs.empty(), material, false);
	}

	private Entry pageElement(View window, UUID viewerId, String id, LinesKey key, Icon material, Page target)
	{
		return actionElement(id, key, MessageArgs.empty(), material, () -> navigate(window, viewerId, target));
	}

	private Entry numericLink(View window, UUID viewerId, String id, TextKey label, String value, Icon material, NumericField field)
	{
		return actionElement(
				id,
				WormholesMessages.RTP_NUMERIC_LINK,
				arguments(
						MessageArgument.untrusted("label", host.text(label)),
						MessageArgument.untrusted("value", value)),
				material,
				() -> openNumeric(window, viewerId, field));
	}

	private Entry choiceElement(
			String id,
			LinesKey key,
			MessageArgs arguments,
			Icon material,
			boolean selected,
			Runnable action)
	{
		Entry element = localizedElement(id, key, arguments, material);
		element.setEnchanted(selected);
		element.action = action;
		return element;
	}

	private Entry actionElement(String id, LinesKey key, MessageArgs arguments, Icon material, Runnable action)
	{
		Entry element = localizedElement(id, key, arguments, material);
		element.action = action;
		return element;
	}

	private Entry infoElement(String id, LinesKey key, MessageArgs arguments, Icon material, boolean enchanted)
	{
		Entry element = localizedElement(id, key, arguments, material);
		element.setEnchanted(enchanted);
		return element;
	}

	private Entry localizedElement(String id, LinesKey key, MessageArgs arguments, Icon material)
	{
		Entry element = new Entry(id);
		element.setMaterial(material);
		element.text(key, arguments);
		return element;
	}

	private Entry adjustmentElement(EditorSnapshot snapshot, UUID viewerId, NumericControl control, boolean increase, boolean large)
	{
		Mutation mutation = increase
				? large ? control.increaseLarge() : control.increaseSmall()
				: large ? control.decreaseLarge() : control.decreaseSmall();
		String direction = increase ? "+" : "-";
		String step = large ? control.largeStep() : control.smallStep();
		String id = "rtp-numeric-" + (increase ? "increase" : "decrease") + (large ? "-large" : "-small");
		return actionElement(id, WormholesMessages.RTP_NUMERIC_ADJUST,
				arguments(
						MessageArgument.untrusted("direction", direction),
						MessageArgument.untrusted("step", step)),
				increase ? Icon.LIME_DYE : Icon.RED_DYE, () -> mutate(snapshot, viewerId, mutation));
	}

	private Entry submenuBackElement(View window, UUID viewerId)
	{
		return actionElement("rtp-submenu-back", WormholesMessages.RTP_BACK_OVERVIEW, MessageArgs.empty(), Icon.ARROW,
				() -> navigate(window, viewerId, Page.OVERVIEW));
	}

	private Entry numericBackElement(View window, UUID viewerId)
	{
		return actionElement("rtp-numeric-back", WormholesMessages.RTP_BACK_CATEGORY, MessageArgs.empty(), Icon.ARROW,
				() -> navigate(window, viewerId, numericParent(numericField)));
	}

	private Entry portalBackElement(View window, UUID viewerId)
	{
		return actionElement("rtp-back", WormholesMessages.RTP_BACK_PORTAL, MessageArgs.empty(), Icon.ARROW, () ->
		{
			window.close();
			host.back(viewerId);
		});
	}

	private void navigate(View window, UUID viewerId, Page target)
	{
		page = target;
		populate(window, viewerId);
		window.updateInventory();
	}

	private void openNumeric(View window, UUID viewerId, NumericField field)
	{
		numericField = Objects.requireNonNull(field, "field");
		navigate(window, viewerId, Page.NUMERIC);
	}

	private void openManualConfirmation(View window, UUID viewerId, ManualAction action)
	{
		confirmationAction = Objects.requireNonNull(action, "action");
		navigate(window, viewerId, Page.MANUAL_CONFIRM);
	}

	private void changeWorldPage(View window, UUID viewerId, int delta)
	{
		worldPage += delta;
		navigate(window, viewerId, Page.DESTINATION);
	}

	private void changeBiomePage(View window, UUID viewerId, int delta)
	{
		biomePage += delta;
		navigate(window, viewerId, Page.BIOME);
	}

	private void mutate(EditorSnapshot snapshot, UUID viewerId, Mutation mutation)
	{
		host.mutate(viewerId, snapshot.configurationRevision(), mutation);
	}

	private CenterModeMutation centerModeMutation(EditorSnapshot snapshot, RtpCenterMode mode)
	{
		SettingsSnapshot settings = snapshot.settings();
		Double customX = settings.customCenterX();
		Double customZ = settings.customCenterZ();
		if(mode == RtpCenterMode.CUSTOM && customX == null)
		{
			customX = Double.valueOf(snapshot.sourceCenterX());
			customZ = Double.valueOf(snapshot.sourceCenterZ());
		}
		return new CenterModeMutation(mode, customX, customZ);
	}

	private NumericControl numericControl(EditorSnapshot snapshot, NumericField field)
	{
		SettingsSnapshot settings = snapshot.settings();
		return switch(field)
		{
			case CENTER_X -> coordinateControl(settings, true);
			case CENTER_Z -> coordinateControl(settings, false);
			case MINIMUM_RADIUS -> new NumericControl(WormholesMessages.RTP_LABEL_MIN_RADIUS, Integer.toString(settings.minimumRadius()),
					WormholesMessages.RTP_DESCRIPTION_MIN_RADIUS, Icon.SPYGLASS, "128", "1024",
					adjustMinimumRadius(settings, -128), adjustMinimumRadius(settings, 128),
					adjustMinimumRadius(settings, -1024), adjustMinimumRadius(settings, 1024), true);
			case MAXIMUM_RADIUS -> new NumericControl(WormholesMessages.RTP_LABEL_MAX_RADIUS, Integer.toString(settings.maximumRadius()),
					WormholesMessages.RTP_DESCRIPTION_MAX_RADIUS, Icon.TARGET, "128", "1024",
					adjustMaximumRadius(settings, -128), adjustMaximumRadius(settings, 128),
					adjustMaximumRadius(settings, -1024), adjustMaximumRadius(settings, 1024), true);
			case LOWER_Y -> yControl(snapshot, YField.LOWER);
			case UPPER_Y -> yControl(snapshot, YField.UPPER);
			case PREFERRED_Y -> yControl(snapshot, YField.PREFERRED);
			case CYCLE_DURATION -> new NumericControl(
					settings.allocationMode() == RtpAllocationMode.PER_PLAYER
							? WormholesMessages.RTP_LABEL_PRIVATE_ROTATION
							: WormholesMessages.RTP_LABEL_CYCLE,
					formatDuration(settings.cycleDurationMillis()),
					settings.allocationMode() == RtpAllocationMode.PER_PLAYER
							? WormholesMessages.RTP_DESCRIPTION_PRIVATE_ROTATION
							: WormholesMessages.RTP_DESCRIPTION_CYCLE,
					Icon.CLOCK, formatDuration(30_000L), formatDuration(300_000L),
					new CycleDurationMutation(clamp(settings.cycleDurationMillis() - 30_000L, MINIMUM_CYCLE_MILLIS, MAXIMUM_CYCLE_MILLIS)),
					new CycleDurationMutation(clamp(settings.cycleDurationMillis() + 30_000L, MINIMUM_CYCLE_MILLIS, MAXIMUM_CYCLE_MILLIS)),
					new CycleDurationMutation(clamp(settings.cycleDurationMillis() - 300_000L, MINIMUM_CYCLE_MILLIS, MAXIMUM_CYCLE_MILLIS)),
					new CycleDurationMutation(clamp(settings.cycleDurationMillis() + 300_000L, MINIMUM_CYCLE_MILLIS, MAXIMUM_CYCLE_MILLIS)), true);
			case LEASE_IDLE -> new NumericControl(WormholesMessages.RTP_LABEL_LEASE, formatDuration(settings.leaseIdleMillis()),
					WormholesMessages.RTP_DESCRIPTION_LEASE, Icon.LEAD, formatDuration(5_000L), formatDuration(30_000L),
					new LeaseIdleMutation(clamp(settings.leaseIdleMillis() - 5_000L, MINIMUM_LEASE_MILLIS, MAXIMUM_LEASE_MILLIS)),
					new LeaseIdleMutation(clamp(settings.leaseIdleMillis() + 5_000L, MINIMUM_LEASE_MILLIS, MAXIMUM_LEASE_MILLIS)),
					new LeaseIdleMutation(clamp(settings.leaseIdleMillis() - 30_000L, MINIMUM_LEASE_MILLIS, MAXIMUM_LEASE_MILLIS)),
					new LeaseIdleMutation(clamp(settings.leaseIdleMillis() + 30_000L, MINIMUM_LEASE_MILLIS, MAXIMUM_LEASE_MILLIS)), true);
			case RESERVATION_TIMEOUT -> new NumericControl(WormholesMessages.RTP_LABEL_RELEASE, formatDuration(settings.reservationTimeoutMillis()),
					WormholesMessages.RTP_DESCRIPTION_RELEASE, Icon.TRIPWIRE_HOOK, formatDuration(5_000L), formatDuration(30_000L),
					new ReservationTimeoutMutation(clamp(settings.reservationTimeoutMillis() - 5_000L, MINIMUM_RESERVATION_MILLIS, MAXIMUM_RESERVATION_MILLIS)),
					new ReservationTimeoutMutation(clamp(settings.reservationTimeoutMillis() + 5_000L, MINIMUM_RESERVATION_MILLIS, MAXIMUM_RESERVATION_MILLIS)),
					new ReservationTimeoutMutation(clamp(settings.reservationTimeoutMillis() - 30_000L, MINIMUM_RESERVATION_MILLIS, MAXIMUM_RESERVATION_MILLIS)),
					new ReservationTimeoutMutation(clamp(settings.reservationTimeoutMillis() + 30_000L, MINIMUM_RESERVATION_MILLIS, MAXIMUM_RESERVATION_MILLIS)), true);
		};
	}

	private NumericControl coordinateControl(SettingsSnapshot settings, boolean xAxis)
	{
		double x = Objects.requireNonNull(settings.customCenterX(), "customCenterX").doubleValue();
		double z = Objects.requireNonNull(settings.customCenterZ(), "customCenterZ").doubleValue();
		double current = xAxis ? x : z;
		return new NumericControl(
				xAxis ? WormholesMessages.RTP_LABEL_CENTER_X : WormholesMessages.RTP_LABEL_CENTER_Z,
				formatCoordinate(current),
				WormholesMessages.RTP_DESCRIPTION_CENTER, Icon.COMPASS, "16", "256",
				centerMutation(x, z, xAxis, -16.0D), centerMutation(x, z, xAxis, 16.0D),
				centerMutation(x, z, xAxis, -256.0D), centerMutation(x, z, xAxis, 256.0D), true);
	}

	private NumericControl yControl(EditorSnapshot snapshot, YField field)
	{
		SettingsSnapshot settings = snapshot.settings();
		WorldOption target = snapshot.world(settings.targetWorldKey());
		int current = switch(field)
		{
			case LOWER -> settings.lowerY();
			case UPPER -> settings.upperY();
			case PREFERRED -> settings.preferredY();
		};
		TextKey label = switch(field)
		{
			case LOWER -> WormholesMessages.RTP_LABEL_LOWER_Y;
			case UPPER -> WormholesMessages.RTP_LABEL_UPPER_Y;
			case PREFERRED -> WormholesMessages.RTP_LABEL_PREFERRED_Y;
		};
		boolean enabled = target != null;
		return new NumericControl(label, Integer.toString(current), WormholesMessages.RTP_DESCRIPTION_Y,
				field == YField.PREFERRED ? Icon.AMETHYST_SHARD : Icon.LADDER, "1", "8",
				enabled ? adjustY(settings, target, field, -1) : null,
				enabled ? adjustY(settings, target, field, 1) : null,
				enabled ? adjustY(settings, target, field, -8) : null,
				enabled ? adjustY(settings, target, field, 8) : null,
				enabled);
	}

	private CustomCenterMutation centerMutation(double x, double z, boolean xAxis, double delta)
	{
		double adjusted = clamp((xAxis ? x : z) + delta, MINIMUM_COORDINATE, MAXIMUM_COORDINATE);
		return xAxis ? new CustomCenterMutation(adjusted, z) : new CustomCenterMutation(x, adjusted);
	}

	private RadiiMutation adjustMinimumRadius(SettingsSnapshot settings, int delta)
	{
		int selected = clamp(settings.minimumRadius() + delta, 0, MAXIMUM_RADIUS);
		if(selected < settings.maximumRadius())
		{
			return new RadiiMutation(selected, settings.maximumRadius());
		}
		return selected < MAXIMUM_RADIUS
				? new RadiiMutation(selected, selected + 1)
				: new RadiiMutation(MAXIMUM_RADIUS - 1, MAXIMUM_RADIUS);
	}

	private RadiiMutation adjustMaximumRadius(SettingsSnapshot settings, int delta)
	{
		int selected = clamp(settings.maximumRadius() + delta, 0, MAXIMUM_RADIUS);
		if(selected > settings.minimumRadius())
		{
			return new RadiiMutation(settings.minimumRadius(), selected);
		}
		return selected > 0 ? new RadiiMutation(selected - 1, selected) : new RadiiMutation(0, 1);
	}

	private YMutation adjustY(SettingsSnapshot settings, WorldOption target, YField field, int delta)
	{
		int lower = settings.lowerY();
		int upper = settings.upperY();
		int preferred = settings.preferredY();
		switch(field)
		{
			case LOWER ->
			{
				lower = Math.min(clamp(lower + delta, target.minimumFeetY(), target.maximumFeetY()), upper);
				preferred = Math.max(preferred, lower);
			}
			case UPPER ->
			{
				upper = Math.max(clamp(upper + delta, target.minimumFeetY(), target.maximumFeetY()), lower);
				preferred = Math.min(preferred, upper);
			}
			case PREFERRED -> preferred = clamp(preferred + delta, lower, upper);
		}
		return new YMutation(lower, upper, preferred);
	}

	private Page numericParent(NumericField field)
	{
		return switch(field)
		{
			case CENTER_X, CENTER_Z, MINIMUM_RADIUS, MAXIMUM_RADIUS -> Page.DESTINATION;
			case LOWER_Y, UPPER_Y, PREFERRED_Y -> Page.LANDING;
			case CYCLE_DURATION, LEASE_IDLE, RESERVATION_TIMEOUT -> Page.ROUTING;
		};
	}

	private static Icon statusMaterial(StatusState state)
	{
		return switch(state)
		{
			case READY -> Icon.BEACON;
			case WARMING, REROLLING -> Icon.CLOCK;
			case BACKOFF, FAILED, INTEGRATION_FAILED, TARGET_WORLD_UNAVAILABLE -> Icon.BARRIER;
			case IDLE -> Icon.COMPASS;
		};
	}

	private String statusLabel(StatusState state)
	{
		TextKey key = switch(state)
		{
			case READY -> WormholesMessages.RTP_STATUS_READY;
			case WARMING -> WormholesMessages.RTP_STATUS_WARMING;
			case REROLLING -> WormholesMessages.RTP_STATUS_REROLLING;
			case BACKOFF -> WormholesMessages.RTP_STATUS_BACKOFF;
			case TARGET_WORLD_UNAVAILABLE -> WormholesMessages.RTP_STATUS_WORLD_UNAVAILABLE;
			case INTEGRATION_FAILED -> WormholesMessages.RTP_STATUS_INTEGRATION_FAILED;
			case FAILED -> WormholesMessages.RTP_STATUS_FAILED;
			case IDLE -> WormholesMessages.RTP_STATUS_IDLE;
		};
		return host.text(key);
	}

	private String readiness(boolean ready)
	{
		return host.text(ready ? WormholesMessages.LABEL_READY : WormholesMessages.LABEL_PREPARING);
	}

	private String rotationLabel(RtpRotationMode mode)
	{
		TextKey key = switch(mode)
		{
			case STATIC -> WormholesMessages.RTP_ROTATION_STATIC;
			case TIMED -> WormholesMessages.RTP_ROTATION_TIMED;
			case ON_TRAVERSAL -> WormholesMessages.RTP_ROTATION_TRIP;
		};
		return host.text(key);
	}

	private static String formatCoordinate(double coordinate)
	{
		return Double.toString(coordinate);
	}

	private String formatDuration(long durationMillis)
	{
		if(durationMillis % 3_600_000L == 0L)
		{
			return host.text(WormholesMessages.RTP_DURATION_HOURS, durationArguments(durationMillis / 3_600_000L));
		}
		if(durationMillis % 60_000L == 0L)
		{
			return host.text(WormholesMessages.RTP_DURATION_MINUTES, durationArguments(durationMillis / 60_000L));
		}
		if(durationMillis % 1_000L == 0L)
		{
			return host.text(WormholesMessages.RTP_DURATION_SECONDS, durationArguments(durationMillis / 1_000L));
		}
		String seconds = String.format(Locale.ROOT, "%.1f", Double.valueOf(durationMillis / 1_000.0D));
		return host.text(WormholesMessages.RTP_DURATION_DECIMAL_SECONDS, durationArguments(seconds));
	}

	private static MessageArgs durationArguments(Object value)
	{
		return arguments(MessageArgument.untrusted("value", value));
	}

	private static int clamp(int value, int minimum, int maximum)
	{
		return Math.max(minimum, Math.min(maximum, value));
	}

	private static long clamp(long value, long minimum, long maximum)
	{
		return Math.max(minimum, Math.min(maximum, value));
	}

	private static double clamp(double value, double minimum, double maximum)
	{
		return Math.max(minimum, Math.min(maximum, value));
	}

	public interface Host
	{
        String text(TextKey key, MessageArgs arguments);

        default String text(TextKey key) {
            return text(key, MessageArgs.empty());
        }

		EditorSnapshot snapshot(UUID viewerId);

		List<RtpPortalEditorModel.BiomeOption> biomeOptions(UUID viewerId);

		void mutate(UUID viewerId, long expectedRevision, Mutation mutation);

		void reset(UUID viewerId, long expectedRevision);

		void manual(UUID viewerId, long expectedRevision, ManualAction action);

		void back(UUID viewerId);
	}

    private static MessageArgs arguments(MessageArgument... values) {
        MessageArgs.Builder builder = MessageArgs.builder();
        for (MessageArgument value : values) {
            builder.add(value);
        }
        return builder.build();
    }

    public interface View {
        void configure(String title);
        void clearElements();
        void setElement(int position, int row, Entry entry);
        void updateInventory();
        void close();
        default void batch(Runnable operation) {
            operation.run();
        }
    }

    public enum Icon {
        AMETHYST_SHARD, ARROW, BARRIER, BEACON, BLACK_STAINED_GLASS_PANE, CLOCK, COMPASS, ENDER_CHEST, ENDER_EYE, ENDER_PEARL, FERN, FIREWORK_ROCKET, FIRE_CHARGE, GLASS, GLOWSTONE_DUST, GRASS_BLOCK, GUNPOWDER, JUKEBOX, LADDER, LAVA_BUCKET, LEAD, LIME_DYE, LODESTONE, NOTE_BLOCK, OAK_SAPLING, PLAYER_HEAD, RECOVERY_COMPASS, RED_DYE, SHIELD, SPYGLASS, TARGET, TNT_MINECART, TRIPWIRE_HOOK
    }

    public static final class Entry {
        private final String id;
        private final List<String> lore = new ArrayList<>();
        private Icon icon;
        private LinesKey key;
        private MessageArgs arguments = MessageArgs.empty();
        private boolean selected;
        private Runnable action;

        private Entry(String id) {
            this.id = id;
        }

        public String id() { return id; }
        public Icon icon() { return icon; }
        public LinesKey key() { return key; }
        public MessageArgs arguments() { return arguments; }
        public boolean selected() { return selected; }
        public List<String> lore() { return List.copyOf(lore); }
        public void activate() {
            if (action != null) {
                action.run();
            }
        }
        private void text(LinesKey key, MessageArgs arguments) {
            this.key = key;
            this.arguments = arguments;
        }
        private void setMaterial(Icon icon) { this.icon = icon; }
        private void setEnchanted(boolean selected) { this.selected = selected; }
        private void addLore(String text) { lore.add(text); }
    }

	private record NumericControl(
			TextKey label,
			String value,
			TextKey description,
			Icon material,
			String smallStep,
			String largeStep,
			Mutation decreaseSmall,
			Mutation increaseSmall,
			Mutation decreaseLarge,
			Mutation increaseLarge,
			boolean enabled)
	{
		private NumericControl
		{
			Objects.requireNonNull(label, "label");
			Objects.requireNonNull(value, "value");
			Objects.requireNonNull(description, "description");
			Objects.requireNonNull(material, "material");
			Objects.requireNonNull(smallStep, "smallStep");
			Objects.requireNonNull(largeStep, "largeStep");
			if(enabled)
			{
				Objects.requireNonNull(decreaseSmall, "decreaseSmall");
				Objects.requireNonNull(increaseSmall, "increaseSmall");
				Objects.requireNonNull(decreaseLarge, "decreaseLarge");
				Objects.requireNonNull(increaseLarge, "increaseLarge");
			}
		}
	}

	private enum Page
	{
		OVERVIEW,
		DESTINATION,
		BIOME,
		LANDING,
		ROUTING,
		EFFECTS,
		NUMERIC,
		MANUAL_CONFIRM
	}

	private enum NumericField
	{
		CENTER_X,
		CENTER_Z,
		MINIMUM_RADIUS,
		MAXIMUM_RADIUS,
		LOWER_Y,
		UPPER_Y,
		PREFERRED_Y,
		CYCLE_DURATION,
		LEASE_IDLE,
		RESERVATION_TIMEOUT
	}

	private enum YField
	{
		LOWER,
		UPPER,
		PREFERRED
	}
}
