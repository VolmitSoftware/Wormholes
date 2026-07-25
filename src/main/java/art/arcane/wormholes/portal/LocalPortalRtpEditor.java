package art.arcane.wormholes.portal;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.rtp.RtpPortalEditor;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;
import art.arcane.volmlib.util.inventorygui.UIWindow;
import art.arcane.volmlib.util.inventorygui.WindowResolution;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;

final class LocalPortalRtpEditor
{
	private final LocalPortal portal;
	private final LocalPortalMenus menus;
	private final Map<UUID, Session> sessions = new ConcurrentHashMap<UUID, Session>();

	LocalPortalRtpEditor(LocalPortal portal, LocalPortalMenus menus)
	{
		this.portal = portal;
		this.menus = menus;
	}

	void open(Player viewer)
	{
		if(!menus.ensureCanManage(viewer))
		{
			return;
		}
		RtpSettings settings = portal.getRtpSettings();
		if(portal.getType() != PortalType.RTP || settings == null)
		{
			menus.text().notifySetting(viewer, WormholesMessages.PORTAL_NOT_RTP);
			portal.uiOpenPortalMenu(viewer);
			return;
		}
		Wormholes.v("QA_EVT {\"event\":\"rtp_editor_open\",\"status\":\"info\",\"details\":\"configuration\",\"context\":{\"portal\":\""
				+ portal.getId() + "\",\"allocation\":\"" + settings.getAllocationMode().name()
				+ "\",\"rotation\":\"" + settings.getRotationMode().name() + "\"}}");
		Session replacement = new Session(viewer);
		Session previous = sessions.put(viewer.getUniqueId(), replacement);
		if(previous != null)
		{
			previous.close();
		}
		replacement.open();
	}

	private final class Session implements RtpPortalEditor.Host
	{
		private final UUID viewerId;
		private final UIWindow window;
		private final RtpPortalEditor editor;
		private long baseRevision;

		private Session(Player viewer)
		{
			viewerId = viewer.getUniqueId();
			window = new UIWindow(Wormholes.instance, viewer);
			window.setResolution(WindowResolution.W9_H6);
			window.onClosed(closed -> sessions.remove(viewerId, this));
			editor = new RtpPortalEditor(this);
			Objects.requireNonNull(portal.getRtpSettings(), "RTP settings");
			baseRevision = portal.rtp().revision();
		}

		private void open()
		{
			editor.populate(window, viewerId);
			window.setVisible(true);
		}

		private void close()
		{
			if(window.isVisible())
			{
				window.close();
			}
			sessions.remove(viewerId, this);
		}

		@Override
		public RtpPortalEditorModel.EditorSnapshot snapshot(UUID requestedViewerId)
		{
			if(!viewerId.equals(requestedViewerId) || portal.getType() != PortalType.RTP || portal.getRtpSettings() == null)
			{
				throw new IllegalStateException("RTP editor session is stale");
			}
			baseRevision = portal.rtp().revision();
			RtpSettings settings = portal.getRtpSettings();
			ArrayList<RtpPortalEditorModel.WorldOption> worlds = new ArrayList<RtpPortalEditorModel.WorldOption>();
			for(World world : Bukkit.getWorlds())
			{
				if(PocketWorldService.isPocketWorld(world))
				{
					continue;
				}
				worlds.add(RtpPortalEditorModel.WorldOption.from(world));
			}
			boolean targetWorldAvailable = portal.rtp().resolveRtpWorld(settings.getTargetWorldKey()) != null;
			RtpPortalEditorModel.StatusSnapshot status = Wormholes.rtpRuntime == null
					? idleStatus(targetWorldAvailable)
					: Wormholes.rtpRuntime.editorStatus(portal.getId()).orElseGet(() -> idleStatus(targetWorldAvailable));
			Location center = Objects.requireNonNull(portal.getStructure().getCenter(), "portal center");
			return new RtpPortalEditorModel.EditorSnapshot(
					baseRevision,
					Wormholes.text().plain(WormholesMessages.PORTAL_RTP_EDITOR_TITLE, LocalPortalText.arguments("portal", portal.getName())),
					RtpPortalEditorModel.SettingsSnapshot.from(settings),
					status,
					worlds,
					center.getX(),
					center.getZ());
		}

		@Override
		public void mutate(UUID requestedViewerId, long expectedRevision, RtpPortalEditorModel.Mutation mutation)
		{
			Player viewer = Bukkit.getPlayer(requestedViewerId);
			if(viewer == null || !viewerId.equals(requestedViewerId))
			{
				return;
			}
			FoliaScheduler.runEntity(Wormholes.instance, viewer, () -> mutateForViewer(viewer, expectedRevision, mutation));
		}

		@Override
		public void reset(UUID requestedViewerId, long expectedRevision)
		{
			Player viewer = Bukkit.getPlayer(requestedViewerId);
			if(viewer == null || !viewerId.equals(requestedViewerId))
			{
				return;
			}
			FoliaScheduler.runEntity(Wormholes.instance, viewer, () -> resetForViewer(viewer, expectedRevision));
		}

		@Override
		public void manual(UUID requestedViewerId, long expectedRevision, RtpPortalEditorModel.ManualAction action)
		{
			Player viewer = Bukkit.getPlayer(requestedViewerId);
			if(viewer == null || !viewerId.equals(requestedViewerId))
			{
				return;
			}
			FoliaScheduler.runEntity(Wormholes.instance, viewer, () -> manualForViewer(viewer, expectedRevision, action));
		}

		@Override
		public void back(UUID requestedViewerId)
		{
			Player viewer = Bukkit.getPlayer(requestedViewerId);
			if(viewer == null || !viewerId.equals(requestedViewerId))
			{
				return;
			}
			FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
			{
				close();
				portal.uiOpenPortalMenu(viewer);
			});
		}

		private void mutateForViewer(Player viewer, long expectedRevision, RtpPortalEditorModel.Mutation mutation)
		{
			if(!menus.ensureCanManage(viewer))
			{
				close();
				return;
			}
			Runnable mutationTask = () ->
			{
				if(portal.getType() != PortalType.RTP || portal.getRtpSettings() == null)
				{
					refresh(WormholesMessages.PORTAL_NOT_RTP);
					return;
				}
				if(baseRevision != expectedRevision || portal.rtp().revision() != baseRevision)
				{
					baseRevision = portal.rtp().revision();
					refresh(WormholesMessages.PORTAL_RTP_EDITOR_REFRESHED);
					return;
				}
				try
				{
					World sourceWorld = Objects.requireNonNull(portal.getStructure().getWorld(), "portal source world");
					portal.rtp().applyRtpSettings(RtpPortalEditorModel.applyMutation(
							portal.getRtpSettings(),
							mutation,
							sourceWorld,
							portal.rtp()::resolveRtpWorld));
					baseRevision = portal.rtp().revision();
					Wormholes.v("QA_EVT {\"event\":\"rtp_editor_apply\",\"status\":\"pass\",\"details\":\""
							+ mutation.getClass().getSimpleName() + "\",\"context\":{\"portal\":\"" + portal.getId()
							+ "\",\"revision\":" + baseRevision + "}}");
					refresh(WormholesMessages.PORTAL_RTP_APPLIED);
				}
				catch(IllegalArgumentException | IllegalStateException exception)
				{
					refresh(WormholesMessages.PORTAL_RTP_SETTING_REJECTED, LocalPortalText.arguments("reason", exception.getMessage()));
				}
			};
			if(!portal.rtp().runSourceTask(mutationTask))
			{
				refresh(WormholesMessages.PORTAL_REGION_UNAVAILABLE);
			}
		}

		private void resetForViewer(Player viewer, long expectedRevision)
		{
			if(!menus.ensureCanManage(viewer))
			{
				close();
				return;
			}
			Runnable resetTask = () ->
			{
				if(portal.getType() != PortalType.RTP || portal.getRtpSettings() == null)
				{
					refresh(WormholesMessages.PORTAL_NOT_RTP);
					return;
				}
				if(baseRevision != expectedRevision || portal.rtp().revision() != baseRevision)
				{
					baseRevision = portal.rtp().revision();
					refresh(WormholesMessages.PORTAL_RTP_EDITOR_REFRESHED);
					return;
				}
				RtpSettings defaults = portal.rtp().defaultRtpSettings();
				if(defaults == null)
				{
					refresh(WormholesMessages.PORTAL_REGION_UNAVAILABLE);
					return;
				}
				portal.rtp().applyRtpSettings(defaults);
				baseRevision = portal.rtp().revision();
				Wormholes.v("QA_EVT {\"event\":\"rtp_editor_apply\",\"status\":\"pass\",\"details\":\"reset_defaults\",\"context\":{\"portal\":\""
						+ portal.getId() + "\",\"revision\":" + baseRevision + "}}");
				refresh(WormholesMessages.PORTAL_RTP_RESET_DEFAULTS);
			};
			if(!portal.rtp().runSourceTask(resetTask))
			{
				refresh(WormholesMessages.PORTAL_REGION_UNAVAILABLE);
			}
		}

		private void manualForViewer(Player viewer, long expectedRevision, RtpPortalEditorModel.ManualAction action)
		{
			if(!menus.ensureCanManage(viewer))
			{
				close();
				return;
			}
			if(portal.getType() != PortalType.RTP || portal.getRtpSettings() == null || baseRevision != expectedRevision
					|| portal.rtp().revision() != baseRevision)
			{
				refresh(WormholesMessages.PORTAL_RTP_EDITOR_REFRESHED);
				return;
			}
			if(Wormholes.rtpRuntime == null)
			{
				refresh(WormholesMessages.PORTAL_RTP_RUNTIME_UNAVAILABLE);
				return;
			}
			if(action == RtpPortalEditorModel.ManualAction.REROLL)
			{
				Wormholes.rtpRuntime.requestManualReroll(portal.getId()).whenComplete((accepted, failure) ->
						refresh(failure != null
								? WormholesMessages.PORTAL_RTP_REROLL_FAILED
								: Boolean.TRUE.equals(accepted)
										? WormholesMessages.PORTAL_RTP_REROLL_PREPARING
										: WormholesMessages.PORTAL_RTP_REROLL_UNAVAILABLE));
				return;
			}
			Wormholes.rtpRuntime.requestPoolRebuild(portal.getId()).whenComplete((removed, failure) ->
					refresh(failure == null
							? WormholesMessages.PORTAL_RTP_POOL_REBUILDING
							: WormholesMessages.PORTAL_RTP_POOL_FAILED));
		}

		private void refresh(TextKey message)
		{
			refresh(message, MessageArgs.empty());
		}

		private void refresh(TextKey message, MessageArgs arguments)
		{
			Player viewer = Bukkit.getPlayer(viewerId);
			if(viewer == null)
			{
				sessions.remove(viewerId, this);
				return;
			}
			FoliaScheduler.runEntity(Wormholes.instance, viewer, () ->
			{
				if(message != null)
				{
					menus.text().notifySetting(viewer, message, arguments);
				}
				if(portal.getType() != PortalType.RTP || portal.getRtpSettings() == null)
				{
					close();
					portal.uiOpenPortalMenu(viewer);
					return;
				}
				if(window.isVisible())
				{
					editor.populate(window, viewerId);
					window.updateInventory();
				}
			});
		}

		private RtpPortalEditorModel.StatusSnapshot idleStatus(boolean targetWorldAvailable)
		{
			return new RtpPortalEditorModel.StatusSnapshot(
					targetWorldAvailable
							? RtpPortalEditorModel.StatusState.IDLE
							: RtpPortalEditorModel.StatusState.TARGET_WORLD_UNAVAILABLE,
					targetWorldAvailable,
					true,
					false,
					false,
					0L,
					0L,
					0,
					0,
					0,
					0);
		}
	}
}
