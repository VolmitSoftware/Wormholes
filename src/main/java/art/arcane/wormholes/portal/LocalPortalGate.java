package art.arcane.wormholes.portal;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

final class LocalPortalGate
{
	private final LocalPortal portal;
	private volatile boolean open;
	private volatile boolean ambientAttended = true;
	private AxisAlignedBB view;
	private double viewRange;

	LocalPortalGate(LocalPortal portal)
	{
		this.portal = portal;
		open = false;
	}

	boolean isOpen()
	{
		return open;
	}

	void assignOpen(boolean open)
	{
		this.open = open;
	}

	void setOpen(boolean open)
	{
		boolean changed = this.open != open;
		this.open = open;
		if(changed && Wormholes.instance != null && Wormholes.effectManager != null)
		{
			if(open)
			{
				portal.playEffect(PortalEffect.OPEN);
			}
			else
			{
				portal.playEffect(PortalEffect.CLOSE);
			}
		}

		if(changed && portal.isGateway() && Wormholes.portalSyncService != null)
		{
			Wormholes.portalSyncService.broadcastPortal(portal);
		}
		if(changed)
		{
			Wormholes.v("QA_EVT {\"event\":\"portal_state\",\"status\":\"info\",\"details\":\"" + (open ? "open" : "closed")
					+ "\",\"context\":{\"portal\":\"" + portal.getId() + "\",\"gateway\":" + portal.isGateway() + "}}");
		}
	}

	boolean isAmbientAttended()
	{
		return ambientAttended;
	}

	void setAmbientAttended(boolean attended)
	{
		ambientAttended = attended;
	}

	boolean canDepart(Entity entity)
	{
		LocalPortalSettings settings = portal.settings();
		if(settings.isMirrorMode())
		{
			return false;
		}
		if(entity instanceof Player player && player.isOp())
		{
			return true;
		}
		return settings.isOutgoingTraversalsEnabled() && settings.allowsPortalPermission(entity);
	}

	boolean canArrive(Entity entity)
	{
		LocalPortalSettings settings = portal.settings();
		if(settings.isMirrorMode())
		{
			return false;
		}
		if(entity instanceof Player player && player.isOp())
		{
			return true;
		}
		return settings.isIncomingTraversalsEnabled() && settings.allowsPortalPermission(entity);
	}

	void setDirection(Direction d)
	{
		portal.applyFrame(portal.getFrame().withNormal(d));
		boolean mirrorRotationChanged = portal.settings().normalizeMirrorRotationForFrame();
		invalidateProjection();
		portal.save();
		if(mirrorRotationChanged)
		{
			portal.settings().broadcastSettingsIfEnabled();
			portal.settings().refreshOpenMenusUnlessApplyingRemote();
		}
		syncGatewayTickets();
	}

	void setFrame(PortalFrame frame)
	{
		portal.applyFrame(frame);
		boolean mirrorRotationChanged = portal.settings().normalizeMirrorRotationForFrame();
		invalidateProjection();
		portal.save();
		if(mirrorRotationChanged)
		{
			portal.settings().broadcastSettingsIfEnabled();
			portal.settings().refreshOpenMenusUnlessApplyingRemote();
		}
		syncGatewayTickets();
	}

	AxisAlignedBB getView()
	{
		if(view == null || viewRange != portal.getEffectiveActivationRange())
		{
			view = computeView();
		}
		return view;
	}

	void rebuildView()
	{
		view = computeView();
	}

	private AxisAlignedBB computeView()
	{
		double range = portal.getEffectiveActivationRange();
		viewRange = range;
		Vector pad = new Vector(-range, -range, -range);
		Vector padPositive = new Vector(range, range, range);
		return new AxisAlignedBB(portal.getStructure().getArea().min().add(pad), portal.getStructure().getArea().max().add(padPositive));
	}

	void syncGatewayTickets()
	{
		if(Wormholes.viewServer != null)
		{
			Wormholes.viewServer.syncGatewayTickets();
		}
	}

	void invalidateProjection()
	{
		if(Wormholes.projectionManager != null)
		{
			Wormholes.projectionManager.removeProjector(portal);
		}
	}
}
