package art.arcane.wormholes.portal;

import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.geometry.Raycast;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.LocalPortalTransitRegistry.ReentryLatch;
import art.arcane.wormholes.portal.rtp.BukkitRtpRuntime;
import art.arcane.wormholes.service.WormholesHud;
import art.arcane.wormholes.service.WormholesTelemetry;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

final class LocalPortalTraversal
{
	private static final double REENTRY_EXIT_MARGIN = 2.0D;
	private static final double DEPARTURE_COMMITMENT_RADIUS_SQUARED = 256.0D;

	private final LocalPortal portal;
	private final LocalPortalRuntime runtime;

	LocalPortalTraversal(LocalPortal portal)
	{
		this(portal, LocalPortalRuntime.BUKKIT);
	}

	LocalPortalTraversal(LocalPortal portal, LocalPortalRuntime runtime)
	{
		this.portal = portal;
		this.runtime = runtime;
	}

	void update()
	{
		if(portal.getType() == PortalType.RTP)
		{
			portal.rtp().updateTick();
			return;
		}
		ITunnel activeTunnel = portal.getTunnel();
		IPortal destination = activeTunnel == null ? null : activeTunnel.getDestination();
		boolean tunnelPresent = activeTunnel != null && destination != null;
		boolean tunnelValid = tunnelPresent && activeTunnel.isValid();
		if(hasRtpDestination(activeTunnel))
		{
			portal.linking().assignTunnel(null);
			activeTunnel = null;
			tunnelPresent = false;
			portal.save();
		}
		boolean shouldBeOpen = tunnelValid || (portal.isMirrorMode() && portal.getProjectionMode() == ProjectionMode.ON);

		if(portal.isOpen())
		{
			if(portal.isAmbientAttended())
			{
				portal.playEffect(PortalEffect.AMBIENT_OPEN);
			}

			updateCaptures(activeTunnel, tunnelPresent);

			if(!shouldBeOpen)
			{
				if(tunnelPresent && activeTunnel.getTunnelType() != TunnelType.UNIVERSAL)
				{
					portal.linking().assignTunnel(null);
				}

				portal.close();
			}
		}

		else
		{
			if(portal.isAmbientAttended())
			{
				portal.playEffect(PortalEffect.AMBIENT_CLOSED);
			}

			if(shouldBeOpen)
			{
				portal.open();
			}
		}

		if(Settings.DEBUG_RENDERING)
		{
			portal.playEffect(PortalEffect.AMBIENT_DEBUG);
		}
	}

	private static boolean hasRtpDestination(ITunnel activeTunnel)
	{
		return (activeTunnel instanceof LocalTunnel localTunnel && localTunnel.hasRtpDestination())
				|| (activeTunnel instanceof DimensionalTunnel dimensionalTunnel && dimensionalTunnel.hasRtpDestination());
	}

	void updateCaptures(ITunnel activeTunnel, boolean tunnelPresent)
	{
		boolean rtp = portal.getType() == PortalType.RTP;
		if(!portal.isOpen() || !tunnelPresent && !rtp)
		{
			return;
		}

		if(portal.isMirrorMode())
		{
			return;
		}

		long now = System.currentTimeMillis();
		LocalPortalTransitRegistry.pruneTeleportCooldowns(now);
		for(Entity i : portal.getStructure().getCaptureZone().getEntities(portal.getStructure().getWorld()))
		{
			UUID entityId = i.getUniqueId();
			if(!rtp && i instanceof Player viewer)
			{
				ArrivalWarmer warmer = Wormholes.arrivalWarmer;
				if(warmer != null)
				{
					warmer.warmImminent(portal, viewer);
				}
			}
			ReentryLatch latch = LocalPortalTransitRegistry.activeReentryLatch(entityId, now);
			if(latch != null && portal.getId().equals(latch.portalId()))
			{
				if(isOccupyingPortal(i))
				{
					if(!latch.armed())
					{
						latch.arm();
						Wormholes.v("[latch] " + i.getName() + " inside portal " + portal.getId() + " - reentry latch ARMED (no teleport until they fully leave)");
					}
				}
				else if(LocalPortalTransitRegistry.shouldReleaseReentryLatchOutsidePortal(latch.armed(), latch.stampMillis(), now))
				{
					LocalPortalTransitRegistry.clearReentryLatch(entityId);
					Wormholes.v("[latch] " + i.getName() + " left portal " + portal.getId() + " - reentry latch CLEARED (eligible again)");
				}
				continue;
			}

			if(LocalPortalTransitRegistry.isTeleportInFlight(entityId, now))
			{
				continue;
			}

			Traversive traversive = rayTeleport(i);
			if(traversive == null)
			{
				continue;
			}

			if(rtp)
			{
				if(Wormholes.rtpRuntime == null || !BukkitRtpRuntime.physicallyTraversable(i))
				{
					continue;
				}
				if(LocalPortalTransitRegistry.isTeleportCoolingDown(entityId, now))
				{
					rejectCooldownTraversal(i, traversive);
					continue;
				}
				if(!portal.canDepart(i))
				{
					rejectTraversal(i, traversive);
					continue;
				}
				if(i.getVehicle() != null || !i.getPassengers().isEmpty())
				{
					bounceRejectedTraversal(i, traversive);
					continue;
				}
				if(!Wormholes.rtpRuntime.isReady(portal.getId()))
				{
					rejectUnreadyRtpTraversal(i, traversive);
					continue;
				}
				PortalTravelCost rtpCost = travelCost(i);
				PortalTravelCost.Status rtpCostStatus = rtpCost == null
						? PortalTravelCost.Status.AVAILABLE : rtpCost.status((Player) i);
				if(rtpCost != null && rtpCostStatus != PortalTravelCost.Status.AVAILABLE)
				{
					rejectCostTraversal(i, traversive, rtpCost, rtpCostStatus);
					continue;
				}
				completeRtpDispatch(i, traversive, Wormholes.rtpRuntime.traverse(portal, i, traversive));
				continue;
			}

			if(!canUseTunnel(i, activeTunnel))
			{
				rejectTraversal(i, traversive);
				continue;
			}

			if(LocalPortalTransitRegistry.isTeleportCoolingDown(entityId, now))
			{
				rejectCooldownTraversal(i, traversive);
				continue;
			}

			PortalTravelCost cost = travelCost(i);
			boolean crossServerHandoff = activeTunnel instanceof UniversalTunnel && Wormholes.traversalService != null;
			PortalTravelCost.Reservation reservation = null;
			if(cost != null)
			{
				if(crossServerHandoff)
				{
					PortalTravelCost.Status status = cost.status((Player) i);
					if(status != PortalTravelCost.Status.AVAILABLE)
					{
						rejectCostTraversal(i, traversive, cost, status);
						continue;
					}
				}
				else
				{
					PortalTravelCost.ReserveResult result = cost.reserve((Player) i);
					if(!result.successful())
					{
						rejectCostTraversal(i, traversive, cost, result.status());
						continue;
					}
					reservation = result.reservation();
				}
			}
			LocalPortalTransitRegistry.markTeleportCooldown(entityId, now);
			Wormholes.v("[cross] " + i.getName() + " crossing portal " + portal.getId() + " -> " + (activeTunnel instanceof UniversalTunnel ? "CROSS-SERVER handoff" : "local teleport"));
			if(!(activeTunnel instanceof UniversalTunnel && i instanceof Player))
			{
				portal.playEffect(PortalEffect.PUSH, traversive.getInPoint().toLocation(portal.getStructure().getWorld()));
			}
			if(crossServerHandoff)
			{
				LocalPortalTransitRegistry.markTeleportInFlight(entityId, now);
			}
			pushTraversive(traversive, activeTunnel, reservation);
		}
	}

	boolean isOccupyingPortal(Entity entity)
	{
		PortalStructure portalStructure = portal.getStructure();
		if(portalStructure == null || portalStructure.getArea() == null)
		{
			return false;
		}
		Location location = entity.getLocation();
		if(location.getWorld() == null || portalStructure.getWorld() == null || !portalStructure.getWorld().equals(location.getWorld()))
		{
			return false;
		}
		AxisAlignedBB area = portalStructure.getArea();
		return location.getX() >= area.getXa() - REENTRY_EXIT_MARGIN && location.getX() <= area.getXb() + REENTRY_EXIT_MARGIN
			&& location.getY() >= area.getYa() - REENTRY_EXIT_MARGIN && location.getY() <= area.getYb() + REENTRY_EXIT_MARGIN
			&& location.getZ() >= area.getZa() - REENTRY_EXIT_MARGIN && location.getZ() <= area.getZb() + REENTRY_EXIT_MARGIN;
	}

	private Traversive rayTeleport(Entity i)
	{
		Vector velocity = Wormholes.traversableManager.getVelocity(i);
		Location end = i.getLocation();
		Location start = end.clone().subtract(velocity);
		Vector crossingVelocity = velocity.lengthSquared() > 1.0E-4D ? velocity : end.getDirection().clone().multiply(0.2D);
		Traversive[] f = new Traversive[1];

		new Raycast(start, end, 0.09)
		{
			@Override
			public boolean shouldContinue(Location l)
			{
				if(portal.getStructure().contains(l))
				{
					f[0] = buildCrossing(i, start, l.toVector(), crossingVelocity);
					return false;
				}

				return true;
			}
		};

		if(f[0] == null && portal.getStructure().contains(end))
		{
			f[0] = buildCrossing(i, start, end.toVector(), crossingVelocity);
		}

		return f[0];
	}

	private Traversive buildCrossing(Entity i, Location start, Vector inPoint, Vector velocity)
	{
		double relX = start.getX() - portal.getOrigin().getX();
		double relY = start.getY() - portal.getOrigin().getY();
		double relZ = start.getZ() - portal.getOrigin().getZ();
		PortalFrame frame = portal.getFrame();
		boolean frontSide = ((relX * frame.getNormal().x()) + (relY * frame.getNormal().y()) + (relZ * frame.getNormal().z())) >= 0.0D;
		return new Traversive(i, frame.view(frontSide), portal.getOrigin(), inPoint, velocity, start.getDirection(), frontSide);
	}

	private boolean canUseTunnel(Entity entity, ITunnel activeTunnel)
	{
		if(!portal.canDepart(entity))
		{
			return false;
		}
		IPortal destination = activeTunnel == null ? null : activeTunnel.getDestination();
		if(destination == null)
		{
			return false;
		}
		if(destination instanceof ILocalPortal localDestination)
		{
			return localDestination.canArrive(entity);
		}
		if(destination instanceof RemotePortal remoteDestination)
		{
			return remoteDestination.acceptsInboundTraversal(entity);
		}
		return true;
	}

	private void pushTraversive(
			Traversive traversive,
			ITunnel activeTunnel,
			PortalTravelCost.Reservation reservation)
	{
		if(activeTunnel instanceof UniversalTunnel universal && Wormholes.traversalService != null && traversive.getObject() instanceof Entity entity)
		{
			if(entity instanceof Player player)
			{
				Wormholes.traversalService.beginPlayerHandoff(player, universal, traversive, portal);
				return;
			}
			Wormholes.traversalService.beginEntityTransfer(entity, universal, traversive, portal);
			return;
		}

		if(traversive.getObject() instanceof Entity undeliverable && !canDeliverThrough(activeTunnel))
		{
			refund(reservation);
			rejectUndeliverableTraversal(undeliverable, traversive);
			return;
		}

		IPortal destination = activeTunnel.getDestination();
		if(destination instanceof LocalPortal localDestination)
		{
			localDestination.receive(traversive, reservation);
			return;
		}
		refund(reservation);
		if(traversive.getObject() instanceof Entity undeliverable)
		{
			rejectUndeliverableTraversal(undeliverable, traversive);
		}
	}

	private static boolean canDeliverThrough(ITunnel activeTunnel)
	{
		if(activeTunnel == null || activeTunnel instanceof UniversalTunnel)
		{
			return false;
		}

		return activeTunnel.isValid();
	}

	private void rejectUndeliverableTraversal(Entity entity, Traversive traversive)
	{
		bounceRejectedTraversal(entity, traversive);

		if(entity instanceof Player player)
		{
			WormholesHud.notice(player, Wormholes.text().component(WormholesMessages.PORTAL_DESTINATION_UNAVAILABLE));
		}
	}

	private void rejectTraversal(Entity entity, Traversive traversive)
	{
		bounceRejectedTraversal(entity, traversive);
		notifyPortalDenied(entity);
	}

	private void rejectCooldownTraversal(Entity entity, Traversive traversive)
	{
		bounceRejectedTraversal(entity, traversive);
		if(entity instanceof Player player)
		{
			WormholesHud.notice(player, Wormholes.text().component(WormholesMessages.PORTAL_COOLDOWN));
		}
	}

	void rejectCostTraversal(Entity entity, Traversive traversive, PortalTravelCost cost, PortalTravelCost.Status status)
	{
		bounceRejectedTraversal(entity, traversive);
		if(entity instanceof Player player)
		{
			if(status == PortalTravelCost.Status.UNAVAILABLE)
			{
				WormholesHud.notice(player, Wormholes.text().component(WormholesMessages.PORTAL_COST_VAULT_UNAVAILABLE));
				return;
			}
			if(status == PortalTravelCost.Status.FAILED)
			{
				WormholesHud.notice(player, Wormholes.text().component(WormholesMessages.PORTAL_COST_TRANSACTION_FAILED));
				return;
			}
			if(cost instanceof VaultTravelCost vault)
			{
				WormholesHud.notice(player, Wormholes.text().component(
						WormholesMessages.PORTAL_COST_VAULT_INSUFFICIENT,
						LocalPortalText.arguments("amount", vault.getFormattedAmount())));
				return;
			}
			VanillaTravelCost vanilla = (VanillaTravelCost) cost;
			WormholesHud.notice(player, Wormholes.text().component(
					WormholesMessages.PORTAL_COST_INSUFFICIENT,
					LocalPortalText.arguments("quantity", vanilla.getQuantity(), "item", vanilla.getItemLabel())));
		}
	}

	void rejectUnreadyRtpTraversal(Entity entity, Traversive traversive)
	{
		bounceRejectedTraversal(entity, traversive);
		if(entity instanceof Player player)
		{
			WormholesHud.notice(player, Wormholes.text().component(WormholesMessages.PORTAL_RTP_NOT_READY));
		}
	}

	void completeRtpDispatch(Entity entity, Traversive traversive, boolean begun)
	{
		if(!begun)
		{
			rejectRefusedRtpTraversal(entity, traversive);
			return;
		}
		portal.departureHold().startRtpTraversalHold(entity, traversive);
	}

	private void rejectRefusedRtpTraversal(Entity entity, Traversive traversive)
	{
		bounceRejectedTraversal(entity, traversive);
		if(entity instanceof Player player)
		{
			WormholesHud.notice(player, Wormholes.text().component(WormholesMessages.PORTAL_RTP_TRAVERSAL_FAILED));
		}
		WormholesTelemetry.countFailure("TRAVERSAL_RTP_BEGIN_REFUSED");
	}

	void bounceFailedRtpTraversal(Entity entity, Traversive traversive)
	{
		rejectDeparture(entity, traversive);
		if(entity instanceof Player player)
		{
			WormholesHud.notice(player, Wormholes.text().component(WormholesMessages.PORTAL_RTP_TRAVERSAL_FAILED));
		}
	}

	private void bounceRejectedTraversal(Entity entity, Traversive traversive)
	{
		armRejectedReentry(entity);
		LocalPortalTransitRegistry.markTeleportCooldown(entity.getUniqueId(), System.currentTimeMillis());
		entity.setVelocity(sourceRejectionVelocity(traversive));
		PortalStructure portalStructure = portal.getStructure();
		World world = portalStructure == null || portalStructure.getWorld() == null ? entity.getWorld() : portalStructure.getWorld();
		portal.playEffect(PortalEffect.REJECT, traversive.getInPoint().toLocation(world));
	}

	private void notifyPortalDenied(Entity entity)
	{
		if(entity instanceof Player player)
		{
			WormholesHud.notice(player, Wormholes.text().component(WormholesMessages.PORTAL_ACCESS_DENIED));
		}
	}

	void receive(Traversive t)
	{
		receive(t, null);
	}

	void receive(Traversive t, PortalTravelCost.Reservation reservation)
	{
		if(t.getType().equals(TraversableType.PLAYER) || t.getType().equals(TraversableType.ENTITY))
		{
			Entity p = (Entity) t.getObject();
			if(!portal.canArrive(p))
			{
				refund(reservation);
				rejectTraversal(p, t);
				return;
			}
			Vector outVelocity = t.getOutVelocity(portal.getFrame());
			Vector outLook = t.getOutLook(portal.getFrame());
			Direction dx = Direction.closest(outVelocity);
			Location exit = t.getOutPoint(portal.getFrame(), portal.getOrigin()).toLocation(portal.getStructure().getWorld());

			Location target = exit.clone().add(dx.toVector().normalize().multiply(1.25));
			target.setDirection(outLook);

			boolean reloadExpected = target.getWorld() != null && !target.getWorld().equals(p.getWorld());

			UUID entityId = p.getUniqueId();
			if(!LocalPortalTransitRegistry.markTeleportInFlight(entityId, System.currentTimeMillis()))
			{
				refund(reservation);
				rejectUndeliverableTraversal(p, t);
				return;
			}

			ArrivalWarmer warmer = Wormholes.arrivalWarmer;
			if(warmer != null && target.getWorld() != null)
			{
				int warmRadius = p instanceof Player ? warmer.viewRadius((Player) p) : Settings.ARRIVAL_WARM_RADIUS_CHUNKS;
				warmer.warmAround(target.getWorld(), target.getBlockX(), target.getBlockZ(), warmRadius, Settings.ARRIVAL_WARM_HOLD_MILLIS);
			}

			runtime.teleport(p, target).whenComplete((success, error) ->
			{
				if(error != null || !Boolean.TRUE.equals(success))
				{
					LocalPortalTransitRegistry.clearTeleportInFlight(entityId);
					logTeleportFailure(p, "deliver", error);
					if(!runtime.dispatch(p, () ->
					{
						refund(reservation);
						rejectUndeliverableTraversal(p, t);
					}, 0L))
					{
						refund(reservation);
						Wormholes.w("Entity scheduler rejected the arrival bounce for " + p.getName() + " at portal " + portal.getId());
					}
					return;
				}
				if(!runtime.dispatch(p, () ->
				{
					commit(reservation);
					settleArrival(p, entityId, outVelocity, exit, reloadExpected);
				}, 0L))
				{
					commit(reservation);
					Wormholes.w("Entity scheduler rejected the arrival settle for " + p.getName() + " at portal " + portal.getId()
							+ "; the arrival was recorded without its landing effects");
					WormholesTelemetry.countTraversal();
					LocalPortalTransitRegistry.markTeleportCooldown(entityId, System.currentTimeMillis());
					LocalPortalTransitRegistry.latchReentry(entityId, portal.getId());
					LocalPortalTransitRegistry.clearTeleportInFlight(entityId);
				}
			});
			return;
		}
		refund(reservation);
	}

	private PortalTravelCost travelCost(Entity entity)
	{
		return entity instanceof Player ? portal.getTravelCost() : null;
	}

	private static void commit(PortalTravelCost.Reservation reservation)
	{
		if(reservation != null)
		{
			reservation.commit();
		}
	}

	private static void refund(PortalTravelCost.Reservation reservation)
	{
		if(reservation != null)
		{
			reservation.refund();
		}
	}

	private void settleArrival(Entity entity, UUID entityId, Vector outVelocity, Location exit, boolean reloadExpected)
	{
		entity.setVelocity(outVelocity);
		WormholesTelemetry.countTraversal();
		LocalPortalTransitRegistry.markTeleportCooldown(entityId, System.currentTimeMillis());
		LocalPortalTransitRegistry.latchReentry(entityId, portal.getId());
		LocalPortalTransitRegistry.clearTeleportInFlight(entityId);
		portal.playEffect(PortalEffect.PUSH, exit);
		if(entity instanceof Player player)
		{
			ArrivalTransition.apply(player, reloadExpected);
			if(Wormholes.projectionManager != null)
			{
				Wormholes.projectionManager.reprimeArrival(player);
			}
		}
	}

	private void logTeleportFailure(Entity entity, String action, Throwable error)
	{
		if(error == null)
		{
			Wormholes.w("Portal " + portal.getId() + " could not " + action + " " + entity.getName() + ": teleport was rejected");
			return;
		}
		if(Wormholes.instance == null)
		{
			return;
		}
		Wormholes.instance.getLogger().log(Level.WARNING, "Portal " + portal.getId() + " could not " + action + " " + entity.getName(), error);
	}

	Location computeExitTarget(Traversive t)
	{
		Vector outVelocity = t.getOutVelocity(portal.getFrame());
		Vector outLook = t.getOutLook(portal.getFrame());
		Direction dx = Direction.closest(outVelocity);
		Location exit = t.getOutPoint(portal.getFrame(), portal.getOrigin()).toLocation(portal.getStructure().getWorld());
		Location target = exit.clone().add(dx.toVector().normalize().multiply(1.25));
		target.setDirection(outLook);
		return target;
	}

	void completeRemoteArrival(Entity entity, Traversive t)
	{
		if(!portal.canArrive(entity))
		{
			rejectRemoteArrival(entity, t);
			return;
		}
		Vector outVelocity = t.getOutVelocity(portal.getFrame());
		entity.setVelocity(outVelocity);
		LocalPortalTransitRegistry.markTeleportCooldown(entity.getUniqueId(), System.currentTimeMillis());
		LocalPortalTransitRegistry.latchReentry(entity.getUniqueId(), portal.getId());
		WormholesTelemetry.countTraversal();
		Wormholes.v("[arrival] completeRemoteArrival " + entity.getName() + " settled near portal " + portal.getId() + ", latched + cooldown set");
		portal.playEffect(PortalEffect.PUSH, entity.getLocation());
		if(entity instanceof Player && Wormholes.projectionManager != null)
		{
			Wormholes.projectionManager.reprimeArrival((Player) entity);
		}
	}

	boolean canCompleteDeparture(Entity entity, Traversive traversive)
	{
		if(entity == null || traversive == null || !entity.isValid())
		{
			return false;
		}
		PortalStructure portalStructure = portal.getStructure();
		Location location = entity.getLocation();
		if(portalStructure == null || portalStructure.getWorld() == null || location.getWorld() == null
			|| !portalStructure.getWorld().equals(location.getWorld()))
		{
			return false;
		}
		return withinDepartureCommitmentRadius(traversive.getInPoint().distanceSquared(location.toVector()));
	}

	void confirmDeparture(Entity entity, Traversive t)
	{
		if(entity instanceof Player player && portal.effects().isPortalSoundEnabled())
		{
			player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.5F, 1.5F);
		}
		PortalStructure portalStructure = portal.getStructure();
		World world = portalStructure == null ? null : portalStructure.getWorld();
		if(world == null)
		{
			return;
		}
		Location location = t.getInPoint().toLocation(world);
		if(!FoliaScheduler.runRegion(Wormholes.instance, location, () -> portal.playEffect(PortalEffect.PUSH, location)))
		{
			Wormholes.w("Portal region rejected departure effect for " + portal.getId());
		}
	}

	void rejectDeparture(Entity entity, Traversive t)
	{
		World world = portal.getStructure() == null ? null : portal.getStructure().getWorld();
		if(world == null)
		{
			bounceRejectedTraversal(entity, t);
			return;
		}
		armRejectedReentry(entity);
		LocalPortalTransitRegistry.markTeleportCooldown(entity.getUniqueId(), System.currentTimeMillis());
		Location current = entity.getLocation();
		Location target = sourceRejectionPoint(t).toLocation(world);
		target.setYaw(current.getYaw());
		target.setPitch(current.getPitch());
		playRejectedDepartureEffect(t, world);
		runtime.teleport(entity, target).whenComplete((success, error) ->
		{
			if(error != null || !Boolean.TRUE.equals(success))
			{
				logTeleportFailure(entity, "return", error);
			}
			if(!runtime.dispatch(entity, () -> finishRejectedDeparture(entity, t), 0L))
			{
				Wormholes.w("Entity scheduler rejected departure bounce for " + entity.getName() + " at portal " + portal.getId());
			}
		});
	}

	private void finishRejectedDeparture(Entity entity, Traversive traversive)
	{
		if(!entity.isValid())
		{
			return;
		}
		entity.setVelocity(sourceRejectionVelocity(traversive));
	}

	private void playRejectedDepartureEffect(Traversive traversive, World world)
	{
		Location location = traversive.getInPoint().toLocation(world);
		if(!FoliaScheduler.runRegion(Wormholes.instance, location, () -> portal.playEffect(PortalEffect.REJECT, location)))
		{
			Wormholes.w("Portal region rejected departure bounce effect for " + portal.getId());
		}
	}

	private void armRejectedReentry(Entity entity)
	{
		PortalStructure portalStructure = portal.getStructure();
		if(portalStructure != null && portalStructure.getWorld() != null && portalStructure.getWorld().equals(entity.getWorld()))
		{
			LocalPortalTransitRegistry.latchRejectedReentry(entity.getUniqueId(), portal.getId());
		}
	}

	static Vector sourceRejectionPoint(Traversive traversive)
	{
		return traversive.getInPoint().clone().add(traversive.getInFrame().getNormal().toVector().normalize().multiply(1.25D));
	}

	static double sourceSideDistance(Traversive traversive, Vector point)
	{
		Vector normal = traversive.getInFrame().getNormal().toVector().normalize();
		return point.clone().subtract(traversive.getInPoint()).dot(normal);
	}

	static boolean withinDepartureCommitmentRadius(double distanceSquared)
	{
		return distanceSquared <= DEPARTURE_COMMITMENT_RADIUS_SQUARED;
	}

	static Vector sourceRejectionVelocity(Traversive traversive)
	{
		return traversive.getInFrame().getNormal().toVector().normalize().multiply(3.0D);
	}

	void rejectRemoteArrival(Entity entity, Traversive t)
	{
		Location target = computeExitTarget(t);
		if(entity instanceof Player player)
		{
			runtime.teleport(player, target).whenComplete((success, error) ->
			{
				if(error != null || !Boolean.TRUE.equals(success))
				{
					logTeleportFailure(player, "bounce", error);
				}
				if(!runtime.dispatch(player, () -> finishRejectedRemoteArrival(entity, t, target), 0L))
				{
					Wormholes.w("Entity scheduler rejected the remote arrival bounce for " + player.getName() + " at portal " + portal.getId());
				}
			});
			return;
		}
		entity.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
		finishRejectedRemoteArrival(entity, t, target);
	}

	private void finishRejectedRemoteArrival(Entity entity, Traversive t, Location target)
	{
		Vector outVelocity = t.getOutVelocity(portal.getFrame());
		if(outVelocity.lengthSquared() < 0.01D)
		{
			outVelocity = portal.getFrame().getNormal().toVector().normalize();
		}
		entity.setVelocity(outVelocity.multiply(2.0D));
		LocalPortalTransitRegistry.markTeleportCooldown(entity.getUniqueId(), System.currentTimeMillis());
		portal.playEffect(PortalEffect.REJECT, target);
		notifyPortalDenied(entity);
	}
}
