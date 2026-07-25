package art.arcane.wormholes.portal;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Location;
import org.bukkit.World;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.JSONObject;

final class LocalPortalLinking
{
	private final LocalPortal portal;
	private final AtomicBoolean destructionStarted = new AtomicBoolean();
	private volatile ITunnel tunnel;
	private volatile UUID dimensionalCounterpartId;
	private volatile DimensionalPortalKind dimensionalPortalKind = DimensionalPortalKind.NONE;

	LocalPortalLinking(LocalPortal portal)
	{
		this.portal = portal;
	}

	void save(JSONObject j)
	{
		if(tunnel != null)
		{
			j.put("tunnel", tunnel.toJSON());
		}
		if(dimensionalCounterpartId != null)
		{
			j.put("dimensionalCounterpartId", dimensionalCounterpartId.toString());
		}
		if(dimensionalPortalKind != DimensionalPortalKind.NONE)
		{
			j.put("dimensionalPortalKind", dimensionalPortalKind.name());
		}
	}

	void load(JSONObject j)
	{
		if(j.has("tunnel"))
		{
			tunnel = ITunnel.createTunnel(j.getJSONObject("tunnel"));
		}
		dimensionalCounterpartId = LocalPortalPersistence.resolveOptionalUuid(j.optString("dimensionalCounterpartId", ""));
		dimensionalPortalKind = DimensionalPortalKind.fromName(j.optString("dimensionalPortalKind", ""));
		if(normalizeMirrorState())
		{
			portal.save();
		}
	}

	boolean normalizeMirrorState()
	{
		if(tunnel == null || !portal.settings().isMirrorMode() || dimensionalPortalKind.isManagedPortal())
		{
			return false;
		}
		tunnel = null;
		return true;
	}

	ITunnel getTunnel()
	{
		return tunnel;
	}

	void assignTunnel(ITunnel tunnel)
	{
		this.tunnel = tunnel;
	}

	boolean hasTunnel()
	{
		ITunnel activeTunnel = tunnel;
		return activeTunnel != null && activeTunnel.getDestination() != null;
	}

	void setType(PortalType type)
	{
		PortalType requiredType = Objects.requireNonNull(type, "type");
		if(dimensionalPortalKind.isManagedPortal())
		{
			return;
		}
		if(portal.getType() == requiredType)
		{
			return;
		}

		boolean wasGateway = portal.isGateway();
		boolean rtpTransition = portal.getType() == PortalType.RTP || requiredType == PortalType.RTP;
		if(requiredType == PortalType.RTP)
		{
			portal.gate().assignOpen(false);
			portal.settings().assignMirrorMode(false);
			detachDimensionalPairIdentity();
			tunnel = null;
			portal.rtp().ensureDefaults();
		}
		else if(portal.getType() == PortalType.RTP)
		{
			portal.gate().assignOpen(false);
			portal.settings().assignMirrorMode(false);
			tunnel = null;
		}
		portal.assignType(requiredType);

		if(wasGateway != portal.isGateway())
		{
			detachDimensionalPairIdentity();
			tunnel = null;
		}
		if(rtpTransition)
		{
			portal.rtp().advanceConfigurationRevision();
			portal.gate().invalidateProjection();
		}

		portal.save();
		portal.gate().syncGatewayTickets();
		if(Wormholes.rtpRuntime != null)
		{
			Wormholes.rtpRuntime.synchronize(portal);
		}
	}

	boolean setDestination(IPortal destinationPortal)
	{
		if(portal.settings().isMirrorMode())
		{
			return false;
		}
		if(portal.getType() == PortalType.RTP
				|| (destinationPortal instanceof ILocalPortal localPortal && localPortal.getType() == PortalType.RTP))
		{
			return false;
		}
		if(dimensionalPortalKind.isReceiverOnly()
				|| (dimensionalPortalKind.isManagedPortal() && dimensionalCounterpartId != null))
		{
			return false;
		}
		detachDimensionalPairIdentity();
		if(destinationPortal instanceof ILocalPortal)
		{
			ILocalPortal p = (ILocalPortal) destinationPortal;

			if(p.getStructure().getWorld().equals(portal.getStructure().getWorld()))
			{
				tunnel = new LocalTunnel(p);
				portal.save();
			}

			else
			{
				tunnel = new DimensionalTunnel(p);
				portal.save();
			}
		}

		else if(destinationPortal instanceof IRemotePortal)
		{
			tunnel = new UniversalTunnel((IRemotePortal) destinationPortal);
			portal.save();
		}

		else
		{
			throw new RuntimeException("Unable to determine identity of new destination!");
		}

		portal.settings().syncLinkedLocalsIfEnabled();
		return true;
	}

	boolean linkRemote(String serverName, UUID portalId)
	{
		if(portal.settings().isMirrorMode())
		{
			return false;
		}
		if(portal.getType() == PortalType.RTP || dimensionalPortalKind.isManagedPortal())
		{
			return false;
		}
		detachDimensionalPairIdentity();
		tunnel = new UniversalTunnel(serverName, portalId);
		portal.save();
		return true;
	}

	void destroy()
	{
		if(!destructionStarted.compareAndSet(false, true))
		{
			return;
		}

		UUID destructionCounterpartId = resolveDimensionalCounterpartId();
		boolean explicitDimensionalCounterpart = dimensionalCounterpartId != null;
		ILocalPortal dimensionalCounterpart = destructionCounterpartId == null || Wormholes.portalManager == null
				? null : Wormholes.portalManager.getLocalPortal(destructionCounterpartId);
		portal.effects().incrementSequence();
		tunnel = null;

		AxisAlignedBB deletionArea = portal.getStructure().getArea();
		World deletionWorld = portal.getStructure().getWorld();
		Location deletionCenter = portal.getStructure().getCenter();
		Location anchor = deletionCenter != null ? deletionCenter : portal.getCenter();

		if(Wormholes.projectionManager != null)
		{
			Wormholes.projectionManager.removeProjector(portal);
		}
		if(Wormholes.portalManager != null)
		{
			Wormholes.portalManager.removeLocalPortal(portal);
			portal.deleteData();
		}
		playDeletionEffect(deletionArea, deletionWorld, deletionCenter, anchor);

		if(dimensionalCounterpart != null && (explicitDimensionalCounterpart || isReciprocalDimensionalCounterpart(dimensionalCounterpart)))
		{
			dimensionalCounterpart.destroy();
		}
		else if(dimensionalCounterpart == null && destructionCounterpartId != null && Wormholes.portalManager != null)
		{
			Wormholes.portalManager.deletePersistedPairedPortal(destructionCounterpartId, portal.getId());
		}
	}

	boolean isDestroyed()
	{
		return destructionStarted.get();
	}

	private UUID resolveDimensionalCounterpartId()
	{
		if(dimensionalCounterpartId != null)
		{
			return dimensionalCounterpartId;
		}
		if(tunnel instanceof DimensionalTunnel dimensionalTunnel)
		{
			return dimensionalTunnel.getDestinationId();
		}
		return null;
	}

	private boolean isReciprocalDimensionalCounterpart(ILocalPortal counterpart)
	{
		if(counterpart == portal)
		{
			return false;
		}
		if(portal.getId().equals(counterpart.getDimensionalCounterpartId()))
		{
			return true;
		}
		ITunnel counterpartTunnel = counterpart.getTunnel();
		return counterpartTunnel instanceof DimensionalTunnel dimensionalTunnel
				&& portal.getId().equals(dimensionalTunnel.getDestinationId());
	}

	private void playDeletionEffect(AxisAlignedBB deletionArea, World deletionWorld, Location deletionCenter, Location anchor)
	{
		if(anchor == null || anchor.getWorld() == null)
		{
			return;
		}
		FoliaScheduler.runRegion(Wormholes.instance, anchor, () ->
		{
			if(deletionArea != null && deletionWorld != null && Wormholes.effectManager != null)
			{
				Location deletionCorner = new Location(deletionWorld, Math.min(deletionArea.getXa(), deletionArea.getXb()), Math.min(deletionArea.getYa(), deletionArea.getYb()), Math.min(deletionArea.getZa(), deletionArea.getZb()));
				double sx = Math.abs(deletionArea.getXb() - deletionArea.getXa());
				double sy = Math.abs(deletionArea.getYb() - deletionArea.getYa());
				double sz = Math.abs(deletionArea.getZb() - deletionArea.getZa());
				Wormholes.effectManager.playPortalDeletion(deletionWorld, deletionCorner, sx, sy, sz);
			}
			if(deletionCenter != null && Wormholes.effectManager != null)
			{
				Wormholes.effectManager.playNotificationFail(Wormholes.text().legacy(
						WormholesMessages.PORTAL_DELETED,
						LocalPortalText.arguments("portal", portal.getName())), deletionCenter);
			}
		});
	}

	void unlink()
	{
		if(tunnel == null && dimensionalCounterpartId == null)
		{
			return;
		}
		detachDimensionalPairIdentity();
		tunnel = null;
		portal.save();
	}

	private void detachDimensionalPairIdentity()
	{
		UUID previousId = resolveDimensionalCounterpartId();
		if(previousId == null)
		{
			return;
		}
		dimensionalCounterpartId = null;
		if(Wormholes.portalManager != null)
		{
			ILocalPortal previous = Wormholes.portalManager.getLocalPortal(previousId);
			if(previous instanceof LocalPortal localPrevious)
			{
				localPrevious.linking().clearDimensionalCounterpartReference(portal.getId());
				localPrevious.linking().clearDimensionalTunnelReference(portal.getId());
			}
			else if(previous == null)
			{
				PortalManager manager = Wormholes.portalManager;
				boolean scheduled = FoliaScheduler.runAsync(Wormholes.instance,
						() -> manager.clearPersistedPairedPortalReference(previousId, portal.getId()));
				if(!scheduled)
				{
					manager.clearPersistedPairedPortalReference(previousId, portal.getId());
				}
			}
		}
		portal.save();
	}

	private void clearDimensionalCounterpartReference(UUID expectedId)
	{
		if(!Objects.equals(dimensionalCounterpartId, expectedId))
		{
			return;
		}
		dimensionalCounterpartId = null;
		portal.save();
	}

	private void clearDimensionalTunnelReference(UUID expectedId)
	{
		ITunnel activeTunnel = tunnel;
		if(!(activeTunnel instanceof DimensionalTunnel dimensionalTunnel)
				|| !Objects.equals(dimensionalTunnel.getDestinationId(), expectedId))
		{
			return;
		}
		tunnel = null;
		portal.save();
	}

	UUID getDimensionalCounterpartId()
	{
		return dimensionalCounterpartId;
	}

	void assignDimensionalCounterpartId(UUID counterpartId)
	{
		dimensionalCounterpartId = counterpartId;
	}

	void setDimensionalCounterpartId(UUID counterpartId)
	{
		if(portal.getType() == PortalType.RTP && counterpartId != null)
		{
			return;
		}
		if(Objects.equals(dimensionalCounterpartId, counterpartId))
		{
			return;
		}
		dimensionalCounterpartId = counterpartId;
		portal.save();
		portal.settings().syncLinkedLocalsIfEnabled();
	}

	DimensionalPortalKind getDimensionalPortalKind()
	{
		return dimensionalPortalKind;
	}

	void setDimensionalPortalKind(DimensionalPortalKind kind)
	{
		DimensionalPortalKind normalized = kind == null ? DimensionalPortalKind.NONE : kind;
		if(portal.getType() == PortalType.RTP && normalized.isManagedPortal())
		{
			return;
		}
		boolean kindChanged = dimensionalPortalKind != normalized;
		dimensionalPortalKind = normalized;
		boolean stateChanged = normalizeDimensionalState();
		if(!kindChanged && !stateChanged)
		{
			return;
		}
		if(stateChanged)
		{
			portal.gate().invalidateProjection();
		}
		portal.save();
	}

	boolean normalizeDimensionalState()
	{
		if(!dimensionalPortalKind.isManagedPortal())
		{
			return false;
		}
		LocalPortalSettings settings = portal.settings();
		boolean changed = false;
		if(portal.getType() != PortalType.PORTAL)
		{
			portal.assignType(PortalType.PORTAL);
			changed = true;
		}
		if(settings.isMirrorMode())
		{
			settings.assignMirrorMode(false);
			changed = true;
		}
		if(dimensionalPortalKind == DimensionalPortalKind.NETHER)
		{
			if(!settings.isOutgoingTraversalsEnabled())
			{
				settings.assignOutgoingTraversalsEnabled(true);
				changed = true;
			}
			if(!settings.isIncomingTraversalsEnabled())
			{
				settings.assignIncomingTraversalsEnabled(true);
				changed = true;
			}
			return changed;
		}
		if(dimensionalPortalKind == DimensionalPortalKind.END_SOURCE)
		{
			if(!settings.isOutgoingTraversalsEnabled())
			{
				settings.assignOutgoingTraversalsEnabled(true);
				changed = true;
			}
			if(settings.isIncomingTraversalsEnabled())
			{
				settings.assignIncomingTraversalsEnabled(false);
				changed = true;
			}
			return changed;
		}
		if(dimensionalCounterpartId == null && tunnel instanceof DimensionalTunnel dimensionalTunnel)
		{
			dimensionalCounterpartId = dimensionalTunnel.getDestinationId();
			changed = dimensionalCounterpartId != null;
		}
		if(tunnel != null)
		{
			tunnel = null;
			changed = true;
		}
		if(settings.getProjectionMode() != ProjectionMode.OFF)
		{
			settings.assignProjectionMode(ProjectionMode.OFF);
			changed = true;
		}
		if(settings.isOutgoingTraversalsEnabled())
		{
			settings.assignOutgoingTraversalsEnabled(false);
			changed = true;
		}
		if(!settings.isIncomingTraversalsEnabled())
		{
			settings.assignIncomingTraversalsEnabled(true);
			changed = true;
		}
		return changed;
	}
}
