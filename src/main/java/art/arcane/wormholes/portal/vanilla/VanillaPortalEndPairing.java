package art.arcane.wormholes.portal.vanilla;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.util.Direction;

final class VanillaPortalEndPairing
{
	private final VanillaPortalIndex index;
	private final VanillaPortalEndSites sites;

	VanillaPortalEndPairing(VanillaPortalIndex index, VanillaPortalEndSites sites)
	{
		this.index = index;
		this.sites = sites;
	}

	void pair(Location frame)
	{
		try
		{
			World world = frame.getWorld();
			if(world == null || world.getEnvironment() == World.Environment.THE_END)
			{
				return;
			}
			int fy = frame.getBlockY();
			Set<Block> endPortalBlocks = new HashSet<Block>();
			int sumX = 0;
			int sumZ = 0;
			for(int dx = -4; dx <= 4; dx++)
			{
				for(int dz = -4; dz <= 4; dz++)
				{
					Block b = world.getBlockAt(frame.getBlockX() + dx, fy, frame.getBlockZ() + dz);
					if(b.getType() == Material.END_PORTAL)
					{
						endPortalBlocks.add(b);
						sumX += b.getX();
						sumZ += b.getZ();
					}
				}
			}
			int count = endPortalBlocks.size();
			if(count < 9)
			{
				return;
			}
			int ccx = Math.round((float) sumX / count);
			int ccz = Math.round((float) sumZ / count);
			Set<Block> window = PortalSiteBuilder.horizontalWindowCells(world, ccx, fy, ccz, VanillaPortalEndSites.WINDOW_HALF);
			if(index.coversCells(window))
			{
				return;
			}
			ILocalPortal sourcePortal = PortalFactory.createFromCells(window, PortalFrame.canonical(Direction.U), PortalType.PORTAL, VanillaPortalIndex.END_TAG, DimensionalPortalKind.END_SOURCE);
			if(sourcePortal == null)
			{
				return;
			}
			Wormholes.w("[vanilla-portal] end portal formed @ " + ccx + "," + fy + "," + ccz + "; resolving one-way arrival");
			World end = WorldPairing.pairedEnd(world);
			if(end == null)
			{
				VanillaPortalCleanup.destroyIfUnlinked(sourcePortal);
				Wormholes.w("[vanilla-portal] No paired End world for " + world.getName() + "; end portal left one-sided.");
				return;
			}
			ILocalPortal existing = index.findReusableEndArrival(end);
			if(existing != null)
			{
				if(PortalFactory.linkOneWay(sourcePortal, existing))
				{
					VanillaPortalCleanup.clearCells(endPortalBlocks, Material.END_PORTAL);
					Wormholes.w("[vanilla-portal] reused existing End arrival, linked one way");
					return;
				}
			}
			VanillaPortalEndSites.BuildTarget reservation = sites.reserve(end);
			if(reservation == null)
			{
				VanillaPortalCleanup.destroyIfUnlinked(sourcePortal);
				return;
			}
			VanillaPortalEndSites.EndTarget target = reservation.target();
			WormholesPlatform.loadChunk(Wormholes.instance, end, target.x() >> 4, target.z() >> 4).whenComplete((chunk, error) ->
			{
				if(error != null || chunk == null)
				{
					sites.release(reservation);
					VanillaPortalCleanup.destroyIfUnlinked(sourcePortal);
					Wormholes.w("[vanilla-portal] End chunk load failed: " + error);
					return;
				}
				boolean targetScheduled = FoliaScheduler.runRegion(Wormholes.instance, end, target.x() >> 4, target.z() >> 4, () ->
				{
					try
					{
						if(sourcePortal.isDestroyed())
						{
							return;
						}
						ILocalPortal reuse = index.findReusableEndArrival(end);
						ILocalPortal counterpart = reuse;
						if(counterpart == null)
						{
							int surfaceY = scanEndSurface(end, target.x(), target.z());
							VanillaPortalEndSites.EndDestinationPlan plan = VanillaPortalEndSites.endDestinationPlan(target.x(), target.z(), surfaceY, end.getMinHeight(), end.getMaxHeight());
							Set<Block> built = PortalSiteBuilder.buildHorizontalWindow(end, plan.x(), plan.y(), plan.z(), VanillaPortalEndSites.WINDOW_HALF);
							if(built.isEmpty())
							{
								VanillaPortalCleanup.destroyIfUnlinked(sourcePortal);
								return;
							}
							counterpart = PortalFactory.createReceiverFromCells(built, PortalFrame.canonical(Direction.U), PortalType.PORTAL, VanillaPortalIndex.END_TAG, DimensionalPortalKind.END_ARRIVAL);
						}
						if(counterpart != null && PortalFactory.linkOneWay(sourcePortal, counterpart))
						{
							VanillaPortalCleanup.clearCells(endPortalBlocks, Material.END_PORTAL);
							Wormholes.w("[vanilla-portal] End arrival placed " + VanillaPortalEndSites.COUNTERPART_RISE + " blocks above safe ground at " + target.x() + "," + target.z() + " + linked one way");
						}
						else
						{
							if(counterpart != null && counterpart != reuse)
							{
								VanillaPortalCleanup.destroyIfUnlinked(counterpart);
							}
							VanillaPortalCleanup.destroyIfUnlinked(sourcePortal);
						}
					}
					catch(Throwable ex)
					{
						VanillaPortalCleanup.destroyIfUnlinked(sourcePortal);
						Wormholes.instance.getLogger().log(Level.WARNING, "[vanilla-portal] End counterpart build failed", ex);
					}
					finally
					{
						sites.release(reservation);
					}
				});
				if(!targetScheduled)
				{
					sites.release(reservation);
					VanillaPortalCleanup.destroyIfUnlinked(sourcePortal);
				}
			});
		}
		catch(Throwable ex)
		{
			Wormholes.instance.getLogger().log(Level.WARNING, "[vanilla-portal] end pair build failed", ex);
		}
	}

	private static int scanEndSurface(World world, int x, int z)
	{
		int top = Math.min(world.getMaxHeight() - 1, 180);
		for(int y = top; y > world.getMinHeight(); y--)
		{
			if(!world.getBlockAt(x, y, z).getType().isAir())
			{
				return y;
			}
		}
		return world.getMinHeight() + 50;
	}
}
