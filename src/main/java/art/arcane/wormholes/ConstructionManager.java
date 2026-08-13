package art.arcane.wormholes;

import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.Listener;
import org.bukkit.util.Vector;

import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.util.Axis;
import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.Direction;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;

public class ConstructionManager implements Listener
{
	private static final long CONSTRUCT_REGION_DELAY_TICKS = 0L;

	public ConstructionManager()
	{
		Wormholes.v("Starting Construction Manager");
	}

	public boolean constructPortal(UUID ownerId, Set<Block> blocks, PortalType type, Vector look)
	{
		if(blocks == null || blocks.isEmpty())
		{
			return false;
		}

		boolean[] openedInline = new boolean[1];
		boolean[] completedInline = new boolean[1];
		boolean started = startConstruct(ownerId, blocks, type, look, opened ->
		{
			completedInline[0] = true;
			openedInline[0] = opened.booleanValue();
		});
		if(completedInline[0])
		{
			return openedInline[0];
		}
		return started;
	}

	boolean startConstruct(UUID ownerId, Set<Block> blocks, PortalType type, Vector look, Consumer<Boolean> onSettled)
	{
		if(blocks == null || blocks.isEmpty())
		{
			return false;
		}

		Block firstBlock = blocks.iterator().next();
		Location anchor = firstBlock.getLocation();
		return dispatchConstruct(
				() -> FoliaScheduler.isOwnedByCurrentRegion(anchor),
				(task, delayTicks) -> FoliaScheduler.runRegion(Wormholes.instance, anchor, task, delayTicks),
				() -> performConstruct(blocks, type, look, ownerId),
				onSettled);
	}

	static long constructRegionDelayTicks()
	{
		return CONSTRUCT_REGION_DELAY_TICKS;
	}

	static boolean dispatchConstruct(BooleanSupplier ownsRegion, ConstructRegionHop hop, BooleanSupplier construct, Consumer<Boolean> onSettled)
	{
		if(ownsRegion.getAsBoolean())
		{
			settleConstruct(construct, onSettled);
			return true;
		}

		return hop.schedule(() -> settleConstruct(construct, onSettled), constructRegionDelayTicks());
	}

	private static void settleConstruct(BooleanSupplier construct, Consumer<Boolean> onSettled)
	{
		boolean opened = false;
		try
		{
			opened = construct.getAsBoolean();
		}
		finally
		{
			if(onSettled != null)
			{
				onSettled.accept(Boolean.valueOf(opened));
			}
		}
	}

	private boolean performConstruct(Set<Block> blocks, PortalType type, Vector look, UUID ownerId)
	{
		Cuboid c = null;

		for(Block i : blocks)
		{
			if(c == null)
			{
				c = new Cuboid(i.getLocation());
			}
			else
			{
				c = c.getBoundingCuboid(new Cuboid(i.getLocation()));
			}
		}

		if(c == null)
		{
			return false;
		}

		int xDepth = c.depth(Axis.X);
		int yDepth = c.depth(Axis.Y);
		int zDepth = c.depth(Axis.Z);

		if(isCoplanarPortalArea(xDepth, yDepth, zDepth))
		{
			Location center = c.getCenter();
			double lookX = look == null ? 0.0D : look.getX();
			double lookY = look == null ? 0.0D : look.getY();
			double lookZ = look == null ? -1.0D : look.getZ();
			Direction normal = derivePortalNormal(xDepth, yDepth, zDepth, lookX, lookY, lookZ);
			PortalStructure s = new PortalStructure();
			s.setBlocks(blocks);
			LocalPortal portal = createPortal(s, type);
			if(ownerId != null)
			{
				portal.setOwner(ownerId);
			}
			portal.setFrame(PortalFrame.fromDirectionAndLook(normal, look));
			portal.open();
			portal.save();
			Wormholes.portalManager.addLocalPortal(portal);
			Wormholes.effectManager.playNotificationSuccess(Wormholes.text().legacy(WormholesMessages.PORTAL_OPENED), center);
			return true;
		}

		Wormholes.effectManager.playNotificationFail(Wormholes.text().legacy(WormholesMessages.PORTAL_MUST_BE_FLAT), new KList<Block>(blocks).getRandom().getLocation());
		Wormholes.effectManager.playPortalFailOpen(blocks);
		Wormholes.blockManager.refund(blocks, type);
		return false;
	}

	static boolean isCoplanarPortalArea(int xDepth, int yDepth, int zDepth)
	{
		int flatAxes = 0;
		if(xDepth == 0)
		{
			flatAxes++;
		}
		if(yDepth == 0)
		{
			flatAxes++;
		}
		if(zDepth == 0)
		{
			flatAxes++;
		}

		return flatAxes >= 1;
	}

	static Direction derivePortalNormal(int xDepth, int yDepth, int zDepth, double lookX, double lookY, double lookZ)
	{
		double ax = xDepth == 0 ? Math.abs(lookX) : -1.0D;
		double ay = yDepth == 0 ? Math.abs(lookY) : -1.0D;
		double az = zDepth == 0 ? Math.abs(lookZ) : -1.0D;

		if(ax >= ay && ax >= az)
		{
			return lookX >= 0.0D ? Direction.E : Direction.W;
		}

		if(ay >= az)
		{
			return lookY >= 0.0D ? Direction.U : Direction.D;
		}

		return lookZ >= 0.0D ? Direction.S : Direction.N;
	}

	private LocalPortal createPortal(PortalStructure s, PortalType type)
	{
		return new LocalPortal(UUID.randomUUID(), type, s);
	}

	@FunctionalInterface
	interface ConstructRegionHop
	{
		boolean schedule(Runnable task, long delayTicks);
	}

}
