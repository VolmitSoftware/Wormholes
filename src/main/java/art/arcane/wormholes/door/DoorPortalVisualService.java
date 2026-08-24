package art.arcane.wormholes.door;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.platform.WormholesPlatform;
import org.bukkit.Axis;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Owns the bright portal plane shown inside an open dimensional door. */
final class DoorPortalVisualService implements AutoCloseable
{
	static final Material PORTAL_MATERIAL = Material.CRYING_OBSIDIAN;
	static final Material PORTAL_OVERLAY_MATERIAL = Material.NETHER_PORTAL;
	private static final float PORTAL_INSET = 0.0625F;
	private static final float PORTAL_RECESS = (float) DoorwayPlane.PORTAL_RECESS;
	private static final float PORTAL_WIDTH = 1.0F - PORTAL_INSET;
	private static final float PORTAL_HEIGHT = 2.0F - (PORTAL_INSET * 2.0F);
	private static final float PORTAL_THICKNESS = (float) DoorwayPlane.PORTAL_THICKNESS;
	private static final float CONTACT_PORTAL_THICKNESS =
		(float) DoorwayPlane.TRAPDOOR_PLATE_THICKNESS + 0.02F;
	private static final float PORTAL_OVERLAY_THICKNESS = 0.15F;
	private static final int SPARKLE_PERIOD_TICKS = 16;
	static final int MAX_ANIMATION_TASKS_PER_PASS = 64;
	static final int MAX_ANIMATION_TASKS_IN_FLIGHT = 64;
	private static final int ATTENDANCE_PERIOD_PASSES =
		DoorPortalAnimation.ATTENDANCE_PERIOD_TICKS / DoorPortalAnimation.FRAME_PERIOD_TICKS;
	private static final long LOOP_RETRY_SECONDS = 1L;
	private static final double AMBIENT_SOUND_CHANCE = 0.008D;
	private static final Particle.DustTransition SURFACE_DUST =
		new Particle.DustTransition(Color.fromRGB(185, 105, 255), Color.fromRGB(20, 5, 35), 0.7F);

	private final Plugin plugin;
	private final NamespacedKey markerKey;
	private final ViewerLookup viewerLookup;
	private final ConcurrentHashMap<UUID, Visual> visuals;
	private final ConcurrentHashMap<UUID, AnimationTarget> animationTargets;
	private final DoorVisualAnimationBudget<AnimationTarget> animationBudget;
	private final Set<ChunkMarker> cleanedChunks;
	private final AtomicBoolean closed;
	private final AtomicBoolean animationLoopRunning;
	private final AtomicBoolean animationLoopRetryScheduled;

	DoorPortalVisualService(Plugin plugin)
	{
		this(plugin, DoorPortalVisualService::hasTrackedViewer);
	}

	DoorPortalVisualService(Plugin plugin, ViewerLookup viewerLookup)
	{
		this.plugin = Objects.requireNonNull(plugin, "plugin");
		markerKey = new NamespacedKey(plugin, "dimensional_door_visual");
		this.viewerLookup = Objects.requireNonNull(viewerLookup, "viewerLookup");
		visuals = new ConcurrentHashMap<>();
		animationTargets = new ConcurrentHashMap<UUID, AnimationTarget>();
		animationBudget = new DoorVisualAnimationBudget<AnimationTarget>(new DoorVisualAnimationBudget.Policy(
			MAX_ANIMATION_TASKS_PER_PASS,
			MAX_ANIMATION_TASKS_IN_FLIGHT,
			ATTENDANCE_PERIOD_PASSES,
			DoorPortalAnimation.FRAME_PERIOD_TICKS));
		cleanedChunks = ConcurrentHashMap.newKeySet();
		closed = new AtomicBoolean();
		animationLoopRunning = new AtomicBoolean();
		animationLoopRetryScheduled = new AtomicBoolean();
	}

	void show(PlacedDoorEndpoint endpoint, VanillaDoorSnapshot snapshot)
	{
		Objects.requireNonNull(endpoint, "endpoint");
		Objects.requireNonNull(snapshot, "snapshot");
		if(closed.get())
		{
			return;
		}
		UUID doorId = endpoint.identity().itemId();
		Visual current = visuals.get(doorId);
		if(current != null && current.isValid())
		{
			return;
		}
		if(current != null && visuals.remove(doorId, current))
		{
			retireAnimation(doorId, current);
			current.remove();
		}

		DoorwayPlane plane = snapshot.plane();
		World world = world(endpoint);
		if(world == null)
		{
			return;
		}
		Location anchor = new Location(world, plane.blockX() + 0.5D, plane.blockY(), plane.blockZ() + 0.5D);
		BlockFace panelFace = panelFace(plane);
		PortalPlaneGeometry geometry = planeGeometry(plane, snapshot.hinge());
		if(closed.get())
		{
			return;
		}
		BlockDisplay backing = spawnBacking(world, anchor, doorId, geometry);
		if(closed.get())
		{
			remove(backing);
			return;
		}
		PortalPlaneGeometry overlayGeometry = overlayGeometry(geometry, panelFace);
		BlockDisplay overlay;
		try
		{
			overlay = spawnOverlay(
				world,
				anchor,
				doorId,
				panelFace,
				overlayGeometry);
		}
		catch(RuntimeException | Error failure)
		{
			remove(backing);
			throw failure;
		}
		if(closed.get())
		{
			remove(backing);
			remove(overlay);
			return;
		}
		Visual replacement = new Visual(endpoint.position(), backing, overlay);
		Visual raced = visuals.put(doorId, replacement);
		if(raced != null && raced != replacement)
		{
			retireAnimation(doorId, raced);
			raced.remove();
		}
		if(closed.get())
		{
			visuals.remove(doorId, replacement);
			retireAnimation(doorId, replacement);
			replacement.remove();
			return;
		}
		registerAnimation(doorId, replacement, world, anchor, panelFace, overlayGeometry);
	}

	/** The surface normal of the visible panel: flat and upward for a trapdoor. */
	static BlockFace panelFace(DoorwayPlane plane)
	{
		Objects.requireNonNull(plane, "plane");
		return plane.horizontal() ? BlockFace.UP : plane.facing();
	}

	private void registerAnimation(
		UUID doorId,
		Visual visual,
		World world,
		Location anchor,
		BlockFace facing,
		PortalPlaneGeometry overlayGeometry)
	{
		AnimationTarget target = new AnimationTarget(
			doorId,
			visual,
			world,
			anchor,
			facing,
			overlayGeometry);
		AnimationTarget replaced = animationTargets.put(doorId, target);
		if(replaced != null)
		{
			animationBudget.retire(replaced);
		}
		animationBudget.register(target);
		if(closed.get() || visuals.get(doorId) != visual)
		{
			retireAnimation(target);
			return;
		}
		startAnimationLoop();
	}

	private void startAnimationLoop()
	{
		if(closed.get() || !animationLoopRunning.compareAndSet(false, true))
		{
			return;
		}
		scheduleAnimationPass(DoorPortalAnimation.FRAME_PERIOD_TICKS);
	}

	private void scheduleAnimationPass(long delayTicks)
	{
		if(closed.get() || !animationLoopRunning.get())
		{
			animationLoopRunning.set(false);
			return;
		}
		boolean scheduled;
		try
		{
			scheduled = FoliaScheduler.runAsync(plugin, this::runAnimationPass, delayTicks);
		}
		catch(Throwable failure)
		{
			plugin.getLogger().log(Level.WARNING, "Could not schedule dimensional-door visual maintenance", failure);
			scheduled = false;
		}
		if(scheduled)
		{
			return;
		}
		if(!canRetryAnimationLoop() || !animationLoopRetryScheduled.compareAndSet(false, true))
		{
			animationLoopRunning.set(false);
			return;
		}
		CompletableFuture.delayedExecutor(LOOP_RETRY_SECONDS, TimeUnit.SECONDS).execute(() ->
		{
			animationLoopRetryScheduled.set(false);
			if(!closed.get() && animationLoopRunning.get())
			{
				scheduleAnimationPass(DoorPortalAnimation.FRAME_PERIOD_TICKS);
			}
		});
	}

	private boolean canRetryAnimationLoop()
	{
		return !closed.get() && plugin.isEnabled();
	}

	private void runAnimationPass()
	{
		if(closed.get())
		{
			animationLoopRunning.set(false);
			return;
		}
		try
		{
			for(DoorVisualAnimationBudget.AttendanceCheck<AnimationTarget> check : animationBudget.advanceAttendanceChecks())
			{
				AnimationTarget target = check.key();
				if(!isAnimationCurrent(target))
				{
					retireAnimation(target);
					continue;
				}
				animationBudget.reportAttendance(check, hasNearbyViewer(target));
			}
			for(DoorVisualAnimationBudget.Admission<AnimationTarget> admission : animationBudget.acquire())
			{
				dispatchAnimation(admission);
			}
		}
		catch(Throwable failure)
		{
			plugin.getLogger().log(Level.WARNING, "Dimensional-door visual maintenance failed", failure);
		}
		finishAnimationPass();
	}

	private void finishAnimationPass()
	{
		if(closed.get())
		{
			animationLoopRunning.set(false);
			return;
		}
		if(animationTargets.isEmpty())
		{
			animationLoopRunning.set(false);
			if(!animationTargets.isEmpty())
			{
				startAnimationLoop();
			}
			return;
		}
		scheduleAnimationPass(DoorPortalAnimation.FRAME_PERIOD_TICKS);
	}

	private void dispatchAnimation(DoorVisualAnimationBudget.Admission<AnimationTarget> admission)
	{
		AnimationTarget target = admission.key();
		if(!isAnimationCurrent(target))
		{
			retireAnimation(target);
			animationBudget.complete(admission);
			return;
		}
		Runnable task = () -> runAnimation(target, admission);
		Runnable retired = () -> retryAnimation(target, admission);
		boolean scheduled;
		try
		{
			scheduled = WormholesPlatform.scheduleEntity(plugin, target.visual.overlay(), task, retired, 0L);
		}
		catch(Throwable failure)
		{
			animationBudget.reject(admission);
			plugin.getLogger().log(Level.WARNING, "Could not schedule dimensional-door visual owner task", failure);
			return;
		}
		if(!scheduled)
		{
			animationBudget.reject(admission);
		}
	}

	private void runAnimation(
		AnimationTarget target,
		DoorVisualAnimationBudget.Admission<AnimationTarget> admission)
	{
		try
		{
			if(!animationBudget.isActive(admission))
			{
				return;
			}
			if(!isAnimationCurrent(target) || !shouldContinueAnimating(target.doorId, target.visual))
			{
				retireAnimation(target);
				return;
			}
			animateFrame(
				target.visual,
				target.world,
				target.anchor,
				target.facing,
				target.overlayGeometry,
				admission.animationTick());
		}
		catch(RuntimeException | Error failure)
		{
			retireAnimation(target);
			throw failure;
		}
		finally
		{
			animationBudget.complete(admission);
		}
	}

	private void retryAnimation(
		AnimationTarget target,
		DoorVisualAnimationBudget.Admission<AnimationTarget> admission)
	{
		if(closed.get() || !isAnimationCurrent(target))
		{
			retireAnimation(target);
			animationBudget.complete(admission);
			return;
		}
		animationBudget.reject(admission);
	}

	private boolean isAnimationCurrent(AnimationTarget target)
	{
		return !closed.get()
			&& animationTargets.get(target.doorId) == target
			&& visuals.get(target.doorId) == target.visual
			&& target.visual.isValid();
	}

	private boolean hasNearbyViewer(AnimationTarget target)
	{
		return viewerLookup.hasPlayerWithin(
			target.visual.position().worldId(),
			target.anchor.getX(),
			target.anchor.getY(),
			target.anchor.getZ(),
			DoorPortalAnimation.ATTENDANCE_RANGE_SQUARED);
	}

	private void retireAnimation(UUID doorId, Visual visual)
	{
		AnimationTarget target = animationTargets.get(doorId);
		if(target != null && target.visual == visual)
		{
			retireAnimation(target);
		}
	}

	private void retireAnimation(AnimationTarget target)
	{
		animationTargets.remove(target.doorId, target);
		animationBudget.retire(target);
	}

	boolean shouldContinueAnimating(UUID doorId, Visual visual)
	{
		return !closed.get() && visuals.get(doorId) == visual && visual.isValid();
	}

	boolean hasNearbyViewer(World world, Location anchor)
	{
		return viewerLookup.hasPlayerWithin(
			world.getUID(),
			anchor.getX(),
			anchor.getY(),
			anchor.getZ(),
			DoorPortalAnimation.ATTENDANCE_RANGE_SQUARED);
	}

	private static boolean hasTrackedViewer(
		UUID worldId,
		double x,
		double y,
		double z,
		double rangeSquared)
	{
		PortalManager portalManager = Wormholes.portalManager;
		return portalManager != null
			&& portalManager.hasPlayerWithin(worldId, x, y, z, rangeSquared);
	}

	void animateFrame(
		Visual visual,
		World world,
		Location anchor,
		BlockFace facing,
		PortalPlaneGeometry overlayGeometry,
		int tick)
	{
		BlockDisplay overlay = visual.overlay();
		PortalPlaneGeometry frame = DoorPortalAnimation.frame(overlayGeometry, facing, tick);
		overlay.setInterpolationDelay(0);
		overlay.setInterpolationDuration(DoorPortalAnimation.FRAME_PERIOD_TICKS);
		overlay.setTransformation(new Transformation(
			new Vector3f(frame.translationX(), frame.translationY(), frame.translationZ()),
			new Quaternionf(),
			new Vector3f(frame.scaleX(), frame.scaleY(), frame.scaleZ()),
			new Quaternionf()));
		if(!Settings.ENABLE_PARTICLES)
		{
			return;
		}
		for(int arm = 0; arm < DoorPortalAnimation.ORBIT_ARMS; arm++)
		{
			double[] point = DoorPortalAnimation.orbitPoint(overlayGeometry, facing, tick, arm);
			Particle trail = (arm & 1) == 0 ? Particle.PORTAL : Particle.REVERSE_PORTAL;
			world.spawnParticle(
				trail,
				anchor.getX() + point[0],
				anchor.getY() + point[1],
				anchor.getZ() + point[2],
				1, 0.03D, 0.03D, 0.03D, 0.015D);
		}
		ThreadLocalRandom random = ThreadLocalRandom.current();
		if(tick % SPARKLE_PERIOD_TICKS == 0)
		{
			double[] point = DoorPortalAnimation.scatterPoint(
				overlayGeometry, facing, random.nextDouble(), random.nextDouble());
			world.spawnParticle(
				Particle.DUST_COLOR_TRANSITION,
				anchor.getX() + point[0],
				anchor.getY() + point[1],
				anchor.getZ() + point[2],
				1, 0.0D, 0.0D, 0.0D, 0.0D, SURFACE_DUST);
		}
		if(random.nextDouble() < AMBIENT_SOUND_CHANCE)
		{
			world.playSound(
				anchor.clone().add(0.0D, 1.0D, 0.0D),
				DimensionalDoorSounds.portalAmbientSound(),
				SoundCategory.BLOCKS,
				Settings.portalSoundVolume(0.3F),
				0.65F + (random.nextFloat() * 0.3F));
		}
	}

	private BlockDisplay spawnBacking(
		World world,
		Location anchor,
		UUID doorId,
		PortalPlaneGeometry geometry)
	{
		return world.spawn(anchor, BlockDisplay.class, spawned -> configureDisplay(
			spawned, doorId, PORTAL_MATERIAL.createBlockData(), geometry));
	}

	private BlockDisplay spawnOverlay(
		World world,
		Location anchor,
		UUID doorId,
		BlockFace facing,
		PortalPlaneGeometry geometry)
	{
		return world.spawn(anchor, BlockDisplay.class, spawned -> configureDisplay(
			spawned, doorId, portalOverlayData(facing), geometry));
	}

	private void configureDisplay(
		BlockDisplay display,
		UUID doorId,
		BlockData blockData,
		PortalPlaneGeometry geometry)
	{
		display.setBlock(blockData);
		display.setTransformation(new Transformation(
			new Vector3f(geometry.translationX(), geometry.translationY(), geometry.translationZ()),
			new Quaternionf(),
			new Vector3f(geometry.scaleX(), geometry.scaleY(), geometry.scaleZ()),
			new Quaternionf()));
		display.setBrightness(new Display.Brightness(15, 15));
		display.setDisplayWidth(PORTAL_WIDTH);
		display.setDisplayHeight(PORTAL_HEIGHT);
		display.setViewRange(32.0F);
		display.setShadowRadius(0.0F);
		display.setShadowStrength(0.0F);
		display.setPersistent(false);
		display.setInvulnerable(true);
		display.setGravity(false);
		display.setSilent(true);
		display.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, doorId.toString());
	}

	private static BlockData portalOverlayData(BlockFace facing)
	{
		Orientable blockData = (Orientable) PORTAL_OVERLAY_MATERIAL.createBlockData();
		blockData.setAxis(overlayAxis(facing));
		return blockData;
	}

	void hide(UUID doorId)
	{
		Visual visual = visuals.remove(Objects.requireNonNull(doorId, "doorId"));
		if(visual != null)
		{
			retireAnimation(doorId, visual);
			visual.remove();
		}
	}

	void cleanChunk(Chunk chunk)
	{
		Objects.requireNonNull(chunk, "chunk");
		ChunkMarker marker = new ChunkMarker(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
		if(!cleanedChunks.add(marker))
		{
			return;
		}
		for(Entity entity : chunk.getEntities())
		{
			if(!(entity instanceof BlockDisplay display))
			{
				continue;
			}
			String encoded = display.getPersistentDataContainer().get(markerKey, PersistentDataType.STRING);
			if(encoded == null)
			{
				continue;
			}
			try
			{
				UUID doorId = UUID.fromString(encoded);
				Visual tracked = visuals.get(doorId);
				if(tracked == null || !tracked.contains(display))
				{
					display.remove();
				}
			}
			catch(IllegalArgumentException ignored)
			{
				display.remove();
			}
		}
	}

	void unloadChunk(Chunk chunk)
	{
		UUID worldId = chunk.getWorld().getUID();
		int chunkX = chunk.getX();
		int chunkZ = chunk.getZ();
		cleanedChunks.remove(new ChunkMarker(worldId, chunkX, chunkZ));
		for(Map.Entry<UUID, Visual> entry : visuals.entrySet())
		{
			DoorPosition position = entry.getValue().position();
			if(position.worldId().equals(worldId)
				&& Math.floorDiv(position.x(), 16) == chunkX
				&& Math.floorDiv(position.z(), 16) == chunkZ
				&& visuals.remove(entry.getKey(), entry.getValue()))
			{
				retireAnimation(entry.getKey(), entry.getValue());
				entry.getValue().remove();
			}
		}
	}

	@Override
	public void close()
	{
		if(!closed.compareAndSet(false, true))
		{
			return;
		}
		animationLoopRunning.set(false);
		animationLoopRetryScheduled.set(false);
		animationBudget.close();
		animationTargets.clear();
		for(Map.Entry<UUID, Visual> entry : Map.copyOf(visuals).entrySet())
		{
			Visual visual = entry.getValue();
			if(!visual.hasValidDisplay() || visual.isOwnedByCurrentRegion())
			{
				visual.remove();
				continue;
			}
			World world = world(visual.position());
			if(world != null)
			{
				FoliaScheduler.runRegion(plugin, world, visual.position().x() >> 4, visual.position().z() >> 4, () ->
					visual.remove());
			}
		}
		visuals.clear();
		cleanedChunks.clear();
	}

	/**
	 * Panel geometry for one plane. A trapdoor's veil is a flat one-by-one slab
	 * lying in the plate plane, so the hinge - a hinged-door concept - is ignored.
	 */
	static PortalPlaneGeometry planeGeometry(DoorwayPlane plane, Door.Hinge hinge)
	{
		Objects.requireNonNull(plane, "plane");
		if(!plane.horizontal())
		{
			PortalPlaneGeometry vertical = geometry(plane.facing(), hinge);
			return plane.contactSurface() ? contactGeometry(vertical, plane.facing()) : vertical;
		}
		float thickness = plane.contactSurface() ? CONTACT_PORTAL_THICKNESS : PORTAL_THICKNESS;
		return new PortalPlaneGeometry(
			-PORTAL_WIDTH / 2.0F,
			(float) (plane.planeY() - plane.blockY()) - (thickness / 2.0F),
			-PORTAL_WIDTH / 2.0F,
			PORTAL_WIDTH,
			thickness,
			PORTAL_WIDTH);
	}

	private static PortalPlaneGeometry contactGeometry(PortalPlaneGeometry geometry, BlockFace facing)
	{
		return switch(facing)
		{
			case NORTH, SOUTH -> new PortalPlaneGeometry(
				geometry.translationX(),
				geometry.translationY(),
				geometry.translationZ() + ((geometry.scaleZ() - CONTACT_PORTAL_THICKNESS) / 2.0F),
				geometry.scaleX(),
				geometry.scaleY(),
				CONTACT_PORTAL_THICKNESS);
			case EAST, WEST -> new PortalPlaneGeometry(
				geometry.translationX() + ((geometry.scaleX() - CONTACT_PORTAL_THICKNESS) / 2.0F),
				geometry.translationY(),
				geometry.translationZ(),
				CONTACT_PORTAL_THICKNESS,
				geometry.scaleY(),
				geometry.scaleZ());
			default -> throw new IllegalArgumentException("Door portal facing must be cardinal: " + facing);
		};
	}

	static PortalPlaneGeometry geometry(BlockFace facing, Door.Hinge hinge)
	{
		Objects.requireNonNull(facing, "facing");
		Objects.requireNonNull(hinge, "hinge");
		float lateralTranslation = lateralTranslation(facing, hinge);
		return switch(facing)
		{
			case NORTH -> new PortalPlaneGeometry(
				lateralTranslation,
				PORTAL_INSET,
				0.5F - PORTAL_RECESS - PORTAL_THICKNESS,
				PORTAL_WIDTH,
				PORTAL_HEIGHT,
				PORTAL_THICKNESS);
			case SOUTH -> new PortalPlaneGeometry(
				lateralTranslation,
				PORTAL_INSET,
				-0.5F + PORTAL_RECESS,
				PORTAL_WIDTH,
				PORTAL_HEIGHT,
				PORTAL_THICKNESS);
			case EAST -> new PortalPlaneGeometry(
				-0.5F + PORTAL_RECESS,
				PORTAL_INSET,
				lateralTranslation,
				PORTAL_THICKNESS,
				PORTAL_HEIGHT,
				PORTAL_WIDTH);
			case WEST -> new PortalPlaneGeometry(
				0.5F - PORTAL_RECESS - PORTAL_THICKNESS,
				PORTAL_INSET,
				lateralTranslation,
				PORTAL_THICKNESS,
				PORTAL_HEIGHT,
				PORTAL_WIDTH);
			default -> throw new IllegalArgumentException("Door portal facing must be cardinal: " + facing);
		};
	}

	static PortalPlaneGeometry overlayGeometry(PortalPlaneGeometry backing, BlockFace facing)
	{
		Objects.requireNonNull(backing, "backing");
		Objects.requireNonNull(facing, "facing");
		return switch(facing)
		{
			case NORTH, SOUTH -> overlayAlongZ(backing);
			case EAST, WEST -> overlayAlongX(backing);
			case UP, DOWN -> overlayAlongY(backing);
			default -> throw new IllegalArgumentException("Door portal facing must be axial: " + facing);
		};
	}

	private static PortalPlaneGeometry overlayAlongX(PortalPlaneGeometry backing)
	{
		float thickness = Math.max(PORTAL_OVERLAY_THICKNESS, backing.scaleX() + 0.02F);
		return new PortalPlaneGeometry(
			backing.translationX() + (backing.scaleX() / 2.0F) - (thickness / 2.0F),
			backing.translationY(),
			backing.translationZ(),
			thickness,
			backing.scaleY(),
			backing.scaleZ());
	}

	private static PortalPlaneGeometry overlayAlongY(PortalPlaneGeometry backing)
	{
		float thickness = Math.max(PORTAL_OVERLAY_THICKNESS, backing.scaleY() + 0.02F);
		return new PortalPlaneGeometry(
			backing.translationX(),
			backing.translationY() + (backing.scaleY() / 2.0F) - (thickness / 2.0F),
			backing.translationZ(),
			backing.scaleX(),
			thickness,
			backing.scaleZ());
	}

	private static PortalPlaneGeometry overlayAlongZ(PortalPlaneGeometry backing)
	{
		float thickness = Math.max(PORTAL_OVERLAY_THICKNESS, backing.scaleZ() + 0.02F);
		return new PortalPlaneGeometry(
			backing.translationX(),
			backing.translationY(),
			backing.translationZ() + (backing.scaleZ() / 2.0F) - (thickness / 2.0F),
			backing.scaleX(),
			backing.scaleY(),
			thickness);
	}

	/** A nether portal block only ever lies on X or Z, so a flat panel picks X. */
	static Axis overlayAxis(BlockFace facing)
	{
		Objects.requireNonNull(facing, "facing");
		return switch(facing)
		{
			case NORTH, SOUTH, UP, DOWN -> Axis.X;
			case EAST, WEST -> Axis.Z;
			default -> throw new IllegalArgumentException("Door portal facing must be axial: " + facing);
		};
	}

	private static float lateralTranslation(BlockFace facing, Door.Hinge hinge)
	{
		int hingeSign = hinge == Door.Hinge.LEFT ? 1 : -1;
		int farSideSign = switch(facing)
		{
			case NORTH, SOUTH -> -facing.getModZ() * hingeSign;
			case EAST, WEST -> facing.getModX() * hingeSign;
			default -> throw new IllegalArgumentException("Door portal facing must be cardinal: " + facing);
		};
		return farSideSign > 0 ? -0.5F + PORTAL_INSET : -0.5F;
	}

	private World world(PlacedDoorEndpoint endpoint)
	{
		return world(endpoint.position());
	}

	private static void remove(BlockDisplay display)
	{
		if(display.isValid())
		{
			display.remove();
		}
	}

	private World world(DoorPosition position)
	{
		World byId = plugin.getServer().getWorld(position.worldId());
		return byId == null ? WorldIdentity.resolve(position.worldKey()).orElse(null) : byId;
	}

	@FunctionalInterface
	interface ViewerLookup
	{
		boolean hasPlayerWithin(
			UUID worldId,
			double x,
			double y,
			double z,
			double rangeSquared);
	}

	record Visual(DoorPosition position, BlockDisplay backing, BlockDisplay overlay)
	{
		Visual
		{
			Objects.requireNonNull(position, "position");
			Objects.requireNonNull(backing, "backing");
			Objects.requireNonNull(overlay, "overlay");
		}

		private boolean isValid()
		{
			return backing.isValid() && overlay.isValid();
		}

		private boolean hasValidDisplay()
		{
			return backing.isValid() || overlay.isValid();
		}

		private boolean contains(BlockDisplay display)
		{
			return backing == display || overlay == display;
		}

		private boolean isOwnedByCurrentRegion()
		{
			return backing.isValid()
				? WormholesPlatform.isOwnedByCurrentRegion(backing)
				: WormholesPlatform.isOwnedByCurrentRegion(overlay);
		}

		private void remove()
		{
			DoorPortalVisualService.remove(backing);
			DoorPortalVisualService.remove(overlay);
		}
	}

	private static final class AnimationTarget
	{
		private final UUID doorId;
		private final Visual visual;
		private final World world;
		private final Location anchor;
		private final BlockFace facing;
		private final PortalPlaneGeometry overlayGeometry;

		private AnimationTarget(
			UUID doorId,
			Visual visual,
			World world,
			Location anchor,
			BlockFace facing,
			PortalPlaneGeometry overlayGeometry)
		{
			this.doorId = Objects.requireNonNull(doorId, "doorId");
			this.visual = Objects.requireNonNull(visual, "visual");
			this.world = Objects.requireNonNull(world, "world");
			this.anchor = Objects.requireNonNull(anchor, "anchor");
			this.facing = Objects.requireNonNull(facing, "facing");
			this.overlayGeometry = Objects.requireNonNull(overlayGeometry, "overlayGeometry");
		}
	}

	private record ChunkMarker(UUID worldId, int chunkX, int chunkZ)
	{
		private ChunkMarker
		{
			Objects.requireNonNull(worldId, "worldId");
		}
	}

	record PortalPlaneGeometry(
		float translationX,
		float translationY,
		float translationZ,
		float scaleX,
		float scaleY,
		float scaleZ)
	{
	}
}
