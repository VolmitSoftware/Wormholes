package art.arcane.wormholes.door;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.platform.BukkitRegionTaskProvider;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.service.WormholesAudience;
import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Boss;
import org.bukkit.entity.ComplexLivingEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Survival runtime for physical Dimensional Doors.
 *
 * <p>The live vanilla block state stays the traversal authority for the door
 * itself: each door or trapdoor can carry its portal while open or on contact
 * with its closed surface. Per-door access records
 * additionally gate which players may open, break, or step through a placed
 * door; return doors are never gated.</p>
 */
public final class DimensionalDoorManager implements Listener, AutoCloseable
{
	private static final double ARRIVAL_OFFSET = 1.0D;
	static final int[] DOOR_ARRIVAL_Y_OFFSETS = {0, -1, 1, -2, 2};
	private static final String ADMINISTRATOR_NODE = "wormholes.admin";
	private static final String ENTITY_MOVE_EVENT_CLASS = "io.papermc.paper.event.entity.EntityMoveEvent";
	private static final boolean ENTITY_MOVE_EVENT_AVAILABLE =
		isEntityMoveEventAvailable(DimensionalDoorManager.class.getClassLoader());
	private static final Runnable NO_OP = () -> {
	};

	private final Wormholes plugin;
	private final PocketWorldService pocketWorldService;
	private final DoorStateGuard guard;
	private final PocketStructureService pocketStructures;
	private final PocketSpaceIndex pockets;
	private final DoorRuntimeIndex runtimes;
	private final DoorTransitLedger ledger;
	private final DoorTransitCoordinator transits;
	private final PocketRescueService rescues;
	private final PocketResizeService resizes;
	private final PocketResizeJournal resizeJournal;
	private final PocketResizeWorkflow resizeWorkflow;
	private final Set<UUID> resizingPockets;
	private final DoorChunkLoader.RegionDispatch regions;
	private final DoorChunkLoader chunkLoader;
	private final DoorBlockProtection protection;
	private final DoorTransitFailures transitFailures;
	private final DoorAccessMenu accessMenu;
	private final DoorAccessFeedback accessFeedback;
	private final DoorAccessAuthorizer accessAuthorizer;

	private volatile DoorItemService items;
	private volatile Listener livingEntityMoveListener;

	public DimensionalDoorManager(Wormholes plugin, PocketWorldService pocketWorldService)
	{
		this.plugin = Objects.requireNonNull(plugin, "plugin");
		this.pocketWorldService = Objects.requireNonNull(pocketWorldService, "pocketWorldService");
		guard = new DoorStateGuard();
		pocketStructures = new PocketStructureService();
		pockets = new PocketSpaceIndex(pocketStructures);
		runtimes = new DoorRuntimeIndex(plugin, guard, pocketWorldService);
		ledger = new DoorTransitLedger(plugin);
		regions = new DoorChunkLoader.RegionDispatch()
		{
			@Override
			public boolean run(World world, int chunkX, int chunkZ, Runnable task)
			{
				return BukkitRegionTaskProvider.run(world, chunkX, chunkZ, task, () -> { }, 0L);
			}

			@Override
			public boolean run(
				World world,
				int chunkX,
				int chunkZ,
				Runnable task,
				Runnable retired)
			{
				return BukkitRegionTaskProvider.run(world, chunkX, chunkZ, task, retired, 0L);
			}
		};
		chunkLoader = new DoorChunkLoader(
			plugin.getLogger(),
			guard::closed,
			(world, chunkX, chunkZ) -> WormholesPlatform.loadChunk(plugin, world, chunkX, chunkZ, true),
			regions);
		DoorArrivalResolver arrivals = new DoorArrivalResolver(runtimes, chunkLoader);
		DoorTicketService tickets = new DoorTicketService(plugin, guard);
		DoorTravelerService travelers = new DoorTravelerService(plugin, guard);
		transitFailures = new DoorTransitFailures(plugin.getLogger());
		transits = new DoorTransitCoordinator(
			plugin,
			guard,
			ledger,
			runtimes,
			chunkLoader,
			regions,
			arrivals,
			tickets,
			travelers,
			pockets,
			pocketStructures,
			pocketWorldService,
			transitFailures);
		rescues = new PocketRescueService(plugin, guard, ledger, chunkLoader, arrivals, tickets, travelers);
		resizes = new PocketResizeService(plugin, pocketStructures);
		resizeJournal = PocketResizeJournal.under(plugin.getDataFolder().toPath());
		resizeWorkflow = new PocketResizeWorkflow(resizeJournal);
		resizingPockets = ConcurrentHashMap.newKeySet();
		protection = new DoorBlockProtection(guard, pockets);
		accessMenu = new DoorAccessMenu(this);
		accessFeedback = new DoorAccessFeedback(plugin);
		accessAuthorizer = new DoorAccessAuthorizer(plugin);
	}

	public void start() throws IOException
	{
		if(!guard.beginStart())
		{
			return;
		}
		DoorStateService state = guard.open(plugin.getDataFolder().toPath());
		List<PocketResizeIntent> pendingResizes = resizeJournal.load();
		for(PocketResizeIntent intent : pendingResizes)
		{
			resizingPockets.add(intent.spaceId());
			guard.quarantinePocket(intent.spaceId());
		}
		items = new DoorItemService(plugin, plugin.getBlockManager().getWormholeRune(1));
		if(!items.registerRecipes())
		{
			plugin.getLogger().warning("One or more dimensional-door recipes could not be registered.");
		}
		syncRecipeBooks();
		plugin.getServer().getPluginManager().registerEvents(this, plugin);
		plugin.getServer().getPluginManager().registerEvents(protection, plugin);
		registerLivingEntityMovement();
		// Projectiles, dropped items, and orbs fire no movement event; an open door
		// sweeps for them instead and feeds the same pipeline.
		runtimes.attachMovementSink(this::handleMovement);
		for(PlacedDoorEndpoint endpoint : state.endpoints())
		{
			runtimes.install(endpoint);
			runtimes.scheduleReconcile(endpoint, 1L);
		}
		for(PocketSpace space : state.spaces())
		{
			pockets.index(space);
		}
		pocketWorldService.whenReady().thenAccept(world ->
		{
			if(pendingResizes.isEmpty())
			{
				FoliaScheduler.runGlobal(plugin, () -> runtimes.reconcileWorld(world));
				return;
			}
			recoverPendingResizes(world, pendingResizes);
		});
		warnUnusablePocketMaterials();
		plugin.getLogger().info("Dimensional Doors ready: " + state.endpoints().size()
			+ " placed doors, " + state.spaces().size() + " pocket spaces.");
	}

	/** Config material names are only resolvable once the server's block registry exists. */
	private void warnUnusablePocketMaterials()
	{
		PocketShell configured = Settings.POCKET_SHELL;
		if(PocketMaterials.shellMaterial(configured.shellMaterial()).isEmpty())
		{
			plugin.getLogger().warning("pocketShellMaterial " + configured.shellMaterial()
				+ " is not a usable pocket wall; new pockets use " + PocketShell.DEFAULT_SHELL_MATERIAL + ".");
		}
		if(PocketMaterials.returnDoorMaterial(configured.returnDoorMaterial()).isEmpty())
		{
			plugin.getLogger().warning("pocketReturnDoorMaterial " + configured.returnDoorMaterial()
				+ " is not a hand-operable door; new pockets use "
				+ PocketShell.DEFAULT_RETURN_DOOR_MATERIAL + ".");
		}
	}

	private void registerLivingEntityMovement()
	{
		if(!ENTITY_MOVE_EVENT_AVAILABLE)
		{
			plugin.getLogger().info("Mob door traversal disabled: Paper's EntityMoveEvent is unavailable on this server.");
			return;
		}
		try
		{
			Class<?> listenerType = Class.forName("art.arcane.wormholes.door.PaperLivingEntityMoveListener");
			Constructor<?> constructor = listenerType.getDeclaredConstructor(LivingEntityMoveCallback.class);
			constructor.setAccessible(true);
			LivingEntityMoveCallback callback = this::onLivingEntityMove;
			Listener listener = (Listener) constructor.newInstance(callback);
			plugin.getServer().getPluginManager().registerEvents(listener, plugin);
			livingEntityMoveListener = listener;
		}
		catch(ReflectiveOperationException | LinkageError | ClassCastException ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not register dimensional-door mob movement", ex);
		}
	}

	static boolean isEntityMoveEventAvailable(ClassLoader loader)
	{
		try
		{
			Class.forName(ENTITY_MOVE_EVENT_CLASS, false, loader);
			return true;
		}
		catch(ClassNotFoundException | LinkageError absent)
		{
			return false;
		}
	}

	public DoorItemService items()
	{
		DoorItemService active = items;
		if(active == null)
		{
			throw new IllegalStateException("Dimensional Doors are not started");
		}
		return active;
	}

	public void onLanguageReload()
	{
		DoorItemService activeItems = items();
		activeItems.acceptWormholeRune(plugin.getBlockManager().getWormholeRune(1));
		if(!activeItems.registerRecipes())
		{
			plugin.getLogger().warning("One or more dimensional-door recipes could not be re-registered after a language reload.");
		}
		syncRecipeBooks();
	}

	/**
	 * A Bukkit recipe arrives locked, so the recipe book is told about every
	 * enabled door recipe on join and again whenever the recipe set changes.
	 */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onJoin(PlayerJoinEvent event)
	{
		syncRecipeBook(event.getPlayer());
	}

	public void syncRecipeBooks()
	{
		for(Player player : plugin.getServer().getOnlinePlayers())
		{
			syncRecipeBook(player);
		}
	}

	private void syncRecipeBook(Player player)
	{
		DoorItemService activeItems = items;
		if(activeItems == null || guard.closed())
		{
			return;
		}
		DoorRecipeBook.Plan plan = DoorRecipeBook.plan(
			activeItems.registeredRecipeKeys(), DoorAccessPolicy.canCraft(player));
		if(plan.isEmpty())
		{
			return;
		}
		if(!FoliaScheduler.runEntity(plugin, player,
			() -> DoorRecipeBook.synchronize(player, plan, key -> plugin.getServer().getRecipe(key) != null)))
		{
			plugin.getLogger().fine("Could not deliver the dimensional-door recipe book to " + player.getUniqueId());
		}
	}

	/** The pocket whose room contains {@code location}, if that is a pocket-world position at all. */
	public Optional<PocketSpace> pocketAt(Location location)
	{
		Objects.requireNonNull(location, "location");
		World world = location.getWorld();
		if(world == null || !PocketWorldService.isPocketWorld(world))
		{
			return Optional.empty();
		}
		PocketSpace space = pockets.spaceAt(location.getBlockX(), location.getBlockZ());
		if(space == null
			|| !pocketStructures.layout(space).contains(
				location.getBlockX(), location.getBlockY(), location.getBlockZ()))
		{
			return Optional.empty();
		}
		return Optional.of(space);
	}

	public List<PocketSpace> pockets()
	{
		return guard.state().spaces();
	}

	public PocketLayout layoutOf(PocketSpace space)
	{
		return pocketStructures.layout(Objects.requireNonNull(space, "space"));
	}

	/**
	 * Rebuilds one pocket room at a new size and material set, keeping everything
	 * already built inside it wherever the new walls still allow.
	 *
	 * <p>Runs asynchronously: the room's chunks are loaded, the work runs on the
	 * owning region thread, and {@code callback} is invoked there exactly once.
	 * A reshape that would destroy or displace anything is refused unless
	 * {@code confirmed} is set.</p>
	 */
	public void resizePocket(
		PocketSpace space,
		PocketShell target,
		boolean confirmed,
		Consumer<PocketResizeOutcome> callback)
	{
		Objects.requireNonNull(space, "space");
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(callback, "callback");
		PocketSpace current = guard.state().findPocketById(space.spaceId()).orElse(null);
		if(current == null)
		{
			plugin.getLogger().warning("Resize requested for missing pocket " + space.spaceId() + ".");
			callback.accept(PocketResizeOutcome.of(PocketResizeOutcome.Status.FAILED, space, target));
			return;
		}
		if(!resizingPockets.add(current.spaceId()))
		{
			plugin.getLogger().fine("Pocket " + current.spaceId() + " already has a resize in progress.");
			callback.accept(PocketResizeOutcome.of(PocketResizeOutcome.Status.FAILED, current, target));
			return;
		}
		if(current.shell().equals(target))
		{
			completeResize(current.spaceId(), callback, PocketResizeOutcome.unchanged(current, target));
			return;
		}
		Optional<World> pocketWorld = pocketWorldService.world();
		if(pocketWorld.isEmpty())
		{
			resizingPockets.remove(current.spaceId());
			callback.accept(PocketResizeOutcome.of(
				PocketResizeOutcome.Status.WORLD_UNAVAILABLE, current, target));
			return;
		}

		World world = pocketWorld.get();
		PocketSpace reshaped = current.withShell(target);
		PocketLayout widest = pocketStructures.layout(
			target.size() >= current.shell().size() ? reshaped : current);
		PocketLayout resized = pocketStructures.layout(reshaped);
		if(resized.minY() < world.getMinHeight() || resized.maxY() >= world.getMaxHeight())
		{
			resizingPockets.remove(current.spaceId());
			callback.accept(PocketResizeOutcome.of(
				PocketResizeOutcome.Status.DOES_NOT_FIT, current, target));
			return;
		}

		chunkLoader.loadPocket(world, current, widest,
			() -> applyResize(world, current, target, confirmed, callback),
			() -> completeResize(current.spaceId(), callback, PocketResizeOutcome.of(
				PocketResizeOutcome.Status.FAILED, current, target)));
	}

	private void applyResize(
		World world,
		PocketSpace space,
		PocketShell target,
		boolean confirmed,
		Consumer<PocketResizeOutcome> callback)
	{
		PocketSpace current = guard.state().findPocketById(space.spaceId()).orElse(null);
		if(current == null)
		{
			plugin.getLogger().warning(
				"Pocket " + space.spaceId() + " disappeared while its resize chunks were loading.");
			completeResize(space.spaceId(), callback,
				PocketResizeOutcome.of(PocketResizeOutcome.Status.FAILED, space, target));
			return;
		}
		try
		{
			current = PocketResizeWorkflow.validateScheduledSource(space, current);
		}
		catch(IllegalStateException ex)
		{
			plugin.getLogger().log(Level.WARNING,
				"Pocket " + space.spaceId() + " changed while its resize chunks were loading", ex);
			completeResize(space.spaceId(), callback,
				PocketResizeOutcome.of(PocketResizeOutcome.Status.FAILED, current, target));
			return;
		}
		PocketSpace source = current;
		try
		{
			PocketResizeService.Impact impact = resizes.assess(world, source, target);
			PocketResizePolicy.Decision decision = PocketResizePolicy.decide(impact, confirmed);
			if(decision == PocketResizePolicy.Decision.NON_EMPTY_CONTAINERS)
			{
				completeResize(source.spaceId(), callback,
					PocketResizeOutcome.nonEmptyContainers(source, target, impact));
				return;
			}
			if(decision == PocketResizePolicy.Decision.NEEDS_CONFIRMATION)
			{
				completeResize(source.spaceId(), callback,
					PocketResizeOutcome.needsConfirmation(source, target, impact));
				return;
			}
			CompletionStage<PocketSpace> operation = resizeWorkflow.execute(
				source,
				target,
				resizeActions(world, source));
			operation.whenComplete((ignored, error) ->
			{
				if(error != null)
				{
					failResize(source, target, callback, error);
					return;
				}
				completeResize(source.spaceId(), callback,
					PocketResizeOutcome.resized(source, target, impact));
			});
		}
		catch(IOException | RuntimeException ex)
		{
			failResize(source, target, callback, ex);
		}
	}

	private PocketResizeWorkflow.Actions resizeActions(World world, PocketSpace space)
	{
		return new PocketResizeWorkflow.Actions(
			(source, target) -> resizes.requireOwnership(world, source, target),
			(source, target) -> resizes.apply(world, source, target),
			(current, target) -> guard.mutate(() ->
				guard.state().reshapePocket(current.spaceId(), current.shell(), target)),
			(previous, updated) -> publishResize(world, previous, updated),
			(task, retired) -> regions.run(
				world,
				space.centerX() >> 4,
				space.centerZ() >> 4,
				task,
				retired));
	}

	private void failResize(
		PocketSpace source,
		PocketShell target,
		Consumer<PocketResizeOutcome> callback,
		Throwable failure)
	{
		plugin.getLogger().log(Level.SEVERE, "Could not resize pocket " + source.spaceId(), failure);
		if(resizeJournal.pending(source.spaceId()).isPresent())
		{
			guard.quarantinePocket(source.spaceId());
			plugin.getLogger().severe(
				"Pocket " + source.spaceId()
					+ " is quarantined until its pending resize recovers; unrelated dimensional doors remain available.");
		}
		else
		{
			resizingPockets.remove(source.spaceId());
		}
		callback.accept(PocketResizeOutcome.of(PocketResizeOutcome.Status.FAILED, source, target));
	}

	private void publishResize(World world, PocketSpace previous, PocketSpace updated) throws IOException
	{
		DoorItemIdentity returnIdentity = pocketStructures.layout(updated).returnDoorIdentity();
		PlacedDoorEndpoint previousReturn = guard.state()
			.findEndpointByItem(returnIdentity.itemId()).orElse(null);
		PlacedDoorEndpoint returnEndpoint = pocketStructures.returnEndpoint(world, updated);
		if(previousReturn == null)
		{
			guard.mutate(() -> guard.state().registerEndpoint(returnEndpoint));
		}
		else if(!previousReturn.equals(returnEndpoint))
		{
			guard.mutate(() -> guard.state().relocateEndpoint(previousReturn, returnEndpoint));
			runtimes.remove(previousReturn);
		}
		if(!previous.equals(updated))
		{
			pockets.reindex(previous, updated);
		}
		runtimes.reconcile(runtimes.install(returnEndpoint));
	}

	private void recoverPendingResizes(World world, List<PocketResizeIntent> pending)
	{
		AtomicInteger remaining = new AtomicInteger(pending.size());
		AtomicBoolean failed = new AtomicBoolean();
		for(PocketResizeIntent intent : pending)
		{
			PocketSpace current = guard.state().findPocketById(intent.spaceId()).orElse(null);
			if(current == null)
			{
				plugin.getLogger().severe(
					"Cannot recover pending resize for missing pocket " + intent.spaceId());
				finishResizeRecovery(world, remaining, failed, true);
				continue;
			}
			PocketSpace operationSource;
			try
			{
				operationSource = intent.operationSource(current);
			}
			catch(RuntimeException ex)
			{
				plugin.getLogger().log(Level.SEVERE,
					"Cannot reconcile pending resize for pocket " + intent.spaceId(), ex);
				finishResizeRecovery(world, remaining, failed, true);
				continue;
			}
			PocketSpace target = operationSource.withShell(intent.target());
			PocketSpace widestSpace = intent.target().size() >= intent.source().size()
				? target
				: operationSource;
			PocketLayout widest = pocketStructures.layout(widestSpace);
			PocketLayout resized = pocketStructures.layout(target);
			if(resized.minY() < world.getMinHeight() || resized.maxY() >= world.getMaxHeight())
			{
				plugin.getLogger().severe(
					"Pending resize no longer fits the pocket world for " + intent.spaceId());
				finishResizeRecovery(world, remaining, failed, true);
				continue;
			}
			chunkLoader.loadPocket(world, operationSource, widest,
				() -> recoverPendingResize(world, intent, remaining, failed),
				() -> finishResizeRecovery(world, remaining, failed, true));
		}
	}

	private void recoverPendingResize(
		World world,
		PocketResizeIntent intent,
		AtomicInteger remaining,
		AtomicBoolean failed)
	{
		PocketSpace current = guard.state().findPocketById(intent.spaceId()).orElse(null);
		if(current == null)
		{
			plugin.getLogger().severe(
				"Pocket " + intent.spaceId() + " disappeared while its recovery chunks were loading.");
			finishResizeRecovery(world, remaining, failed, true);
			return;
		}
		try
		{
			PocketSpace operationSource = intent.operationSource(current);
			PocketResizeService.Impact impact = resizes.assess(world, operationSource, intent.target());
			if(PocketResizePolicy.decide(impact, true)
				== PocketResizePolicy.Decision.NON_EMPTY_CONTAINERS)
			{
				throw new IllegalStateException(
					"pending resize would destroy a non-empty container in pocket " + intent.spaceId());
			}
			CompletionStage<PocketSpace> recovery = resizeWorkflow.recover(
				intent,
				current,
				resizeActions(world, operationSource));
			recovery.whenComplete((ignored, error) ->
			{
				if(error != null)
				{
					plugin.getLogger().log(Level.SEVERE,
						"Could not recover pending resize for pocket " + intent.spaceId(), error);
					finishResizeRecovery(world, remaining, failed, true);
					return;
				}
				resizingPockets.remove(intent.spaceId());
				guard.restorePocket(intent.spaceId());
				plugin.getLogger().info("Recovered pending resize for pocket " + intent.spaceId() + ".");
				finishResizeRecovery(world, remaining, failed, false);
			});
		}
		catch(RuntimeException ex)
		{
			plugin.getLogger().log(Level.SEVERE,
				"Could not recover pending resize for pocket " + intent.spaceId(), ex);
			finishResizeRecovery(world, remaining, failed, true);
		}
	}

	private void finishResizeRecovery(
		World world,
		AtomicInteger remaining,
		AtomicBoolean failed,
		boolean recoveryFailed)
	{
		if(recoveryFailed)
		{
			failed.set(true);
		}
		if(remaining.decrementAndGet() != 0)
		{
			return;
		}
		if(failed.get())
		{
			plugin.getLogger().severe(
				"Pending pocket resize recovery is incomplete; affected pockets remain quarantined while unrelated dimensional doors resume.");
		}
		FoliaScheduler.runGlobal(plugin, () -> runtimes.reconcileWorld(world));
	}

	private void completeResize(
		UUID spaceId,
		Consumer<PocketResizeOutcome> callback,
		PocketResizeOutcome outcome)
	{
		resizingPockets.remove(spaceId);
		callback.accept(outcome);
	}

	public DoorStateService state()
	{
		return guard.state();
	}

	public boolean beginDrain()
	{
		return guard.beginDrain();
	}

	public void resumeEntries()
	{
		guard.resumeEntries();
	}

	public boolean hasActiveTransits()
	{
		return ledger.hasActiveTransits();
	}

	public long failedTransits()
	{
		return transitFailures.failed();
	}

	public Map<String, Long> failedTransitBreakdown()
	{
		return transitFailures.breakdown();
	}

	void openAccessMenu(Player player, PlacedDoorEndpoint endpoint)
	{
		accessMenu.open(player, endpoint);
	}

	Optional<DoorAccessRecord> accessRecord(UUID itemId)
	{
		return guard.state().accessRecord(itemId);
	}

	Optional<PlacedDoorEndpoint> endpoint(UUID itemId)
	{
		return guard.state().findEndpointByItem(itemId);
	}

	boolean applyOpenState(PlacedDoorEndpoint expected, DoorOpenState openState) throws IOException
	{
		Objects.requireNonNull(expected, "expected");
		Objects.requireNonNull(openState, "openState");
		boolean changed = guard.mutate(() ->
		{
			PlacedDoorEndpoint current = guard.state()
				.findEndpointByItem(expected.identity().itemId())
				.orElse(null);
			return expected.equals(current)
				&& guard.state().setEndpointOpenState(expected.position(), openState);
		});
		if(!changed)
		{
			return false;
		}
		PlacedDoorEndpoint updated = guard.state()
			.findEndpointByItem(expected.identity().itemId())
			.orElseThrow(() -> new IllegalStateException("Updated dimensional door is missing"));
		if(!runtimes.replace(expected, updated))
		{
			plugin.getLogger().warning(
				"Saved OpenState for dimensional door " + updated.identity().itemId()
					+ " but could not schedule its live refresh");
		}
		return true;
	}

	boolean applyAccessState(UUID itemId, UUID playerId, DoorAccessState state) throws IOException
	{
		return guard.mutate(() -> guard.state().setAccessState(itemId, playerId, state));
	}

	boolean addAccessPlayer(UUID itemId, UUID playerId) throws IOException
	{
		return guard.mutate(() -> guard.state().addAccessPlayer(itemId, playerId));
	}

	boolean removeAccessPlayer(UUID itemId, UUID playerId) throws IOException
	{
		return guard.mutate(() -> guard.state().removeAccessPlayer(itemId, playerId));
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onCraft(CraftItemEvent event)
	{
		if(items().isDoorRecipe(event.getRecipe())
			&& (!(event.getWhoClicked() instanceof Player player) || !DoorAccessPolicy.canCraft(player)))
		{
			event.setCancelled(true);
			event.setCurrentItem(null);
			if(event.getWhoClicked() instanceof Player player)
			{
				WormholesAudience.sendMessage(player, Wormholes.text().component(player, WormholesMessages.COMMAND_NO_PERMISSION));
			}
			return;
		}
		DoorItemService.CraftHookResult result = items().handleCraft(event);
		if(result == DoorItemService.CraftHookResult.SHIFT_CRAFT_BLOCKED
			&& event.getWhoClicked() instanceof Player player)
		{
			WormholesAudience.sendMessage(player, Wormholes.text().component(player, WormholesMessages.DOOR_CRAFT_ONE));
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPrepareCraft(PrepareItemCraftEvent event)
	{
		if(items().isDoorRecipe(event.getRecipe())
			&& (!(event.getView().getPlayer() instanceof Player player) || !DoorAccessPolicy.canCraft(player)))
		{
			event.getInventory().setResult(null);
			return;
		}
		if(items().isDoorSkinRecipe(event.getRecipe()))
		{
			event.getInventory().setResult(items().skinCraftResult(event.getInventory().getMatrix()).orElse(null));
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onCrafterCraft(CrafterCraftEvent event)
	{
		if(items().handleCrafterCraft(event.getRecipe()) == DoorItemService.CraftHookResult.CRAFTER_BLOCKED)
		{
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPairKitUse(PlayerInteractEvent event)
	{
		if(event.getHand() == null
			|| !shouldUnpackPairKit(event.getAction(), event.useInteractedBlock(), event.useItemInHand()))
		{
			return;
		}
		ItemStack held = event.getItem();
		Optional<DoorItemService.PairKitContents> unpacked = items().unpackPairKit(held);
		if(unpacked.isEmpty())
		{
			return;
		}

		DoorItemService.PairKitContents contents = unpacked.get();
		try
		{
			guard.mutate(() -> guard.state().registerPair(contents.pairIdentity()));
		}
		catch(IOException | RuntimeException ex)
		{
			plugin.getLogger().log(Level.SEVERE, "Could not persist dimensional-door pair "
				+ contents.pairIdentity().pairId(), ex);
			WormholesAudience.sendMessage(event.getPlayer(), Wormholes.text().component(WormholesMessages.DOOR_PAIR_UNPACK_FAILED));
			return;
		}

		event.setCancelled(true);
		consumeHeldItem(event.getPlayer(), event.getHand());
		giveOrDrop(event.getPlayer(), contents.endpointA(), contents.endpointB());
		WormholesAudience.sendMessage(event.getPlayer(), Wormholes.text().component(WormholesMessages.DOOR_PAIR_UNPACKED));
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onDoorPlace(BlockPlaceEvent event)
	{
		Optional<DoorItemIdentity> carriedIdentity = items().decodeDoorIdentity(event.getItemInHand());
		if(carriedIdentity.isPresent()
			&& !DoorSkin.isSupportedSkin(event.getItemInHand().getType(), carriedIdentity.get().form()))
		{
			event.setCancelled(true);
			WormholesAudience.sendMessage(event.getPlayer(), Wormholes.text().component(WormholesMessages.DOOR_LEGACY_COMBINE));
			return;
		}
		Optional<DoorItemIdentity> decoded = items().decodeDoor(event.getItemInHand());
		if(decoded.isEmpty())
		{
			return;
		}
		DoorItemIdentity identity = decoded.get();
		if(identity.kind() == DoorKind.RETURN)
		{
			event.setCancelled(true);
			return;
		}
		if(!DoorAccessPolicy.canPlace(event.getPlayer()))
		{
			event.setCancelled(true);
			WormholesAudience.sendMessage(event.getPlayer(), Wormholes.text().component(WormholesMessages.COMMAND_NO_PERMISSION));
			return;
		}
		if(identity.kind() == DoorKind.PAIR && guard.state().findPair(identity.pairId()).isEmpty())
		{
			event.setCancelled(true);
			WormholesAudience.sendMessage(event.getPlayer(), Wormholes.text().component(WormholesMessages.DOOR_PAIR_MISSING));
			return;
		}

		// A fresh placement is always live while open; its access window can invert that state later.
		Optional<VanillaDoorSnapshot> captured = VanillaDoorSnapshot.capture(event.getBlockPlaced());
		if(captured.isEmpty() || captured.get().plane().form() != identity.form())
		{
			event.setCancelled(true);
			return;
		}
		DoorPosition position = position(event.getBlockPlaced(), captured.get().plane().blockY());
		PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(position, identity);
		UUID placedBy = event.getPlayer().getUniqueId();
		boolean ownedBeforePlacement;
		try
		{
			ownedBeforePlacement = guard.state().accessRecord(identity.itemId()).isPresent();
			if(!guard.mutate(() -> guard.state().registerEndpoint(endpoint, placedBy)))
			{
				return;
			}
		}
		catch(IOException | RuntimeException ex)
		{
			event.setCancelled(true);
			plugin.getLogger().log(Level.WARNING, "Rejected dimensional-door placement for " + identity.itemId(), ex);
			WormholesAudience.sendMessage(event.getPlayer(), Wormholes.text().component(WormholesMessages.DOOR_ALREADY_PLACED));
			return;
		}
		runtimes.install(endpoint).update(captured.get());
		if(!runtimes.schedulePlacementConfirmation(endpoint))
		{
			event.setCancelled(true);
			rollBackPlacement(endpoint, ownedBeforePlacement);
			return;
		}
		if(consumesPlacedDoorItem(event.getPlayer().getGameMode()))
		{
			consumeHeldItem(event.getPlayer(), event.getHand());
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onDoorBreakProtection(BlockBreakEvent event)
	{
		Optional<PlacedDoorEndpoint> direct = protection.endpointForDoorBlock(event.getBlock());
		if(direct.isPresent())
		{
			PlacedDoorEndpoint endpoint = direct.get();
			if(endpoint.identity().kind() == DoorKind.RETURN)
			{
				event.setCancelled(true);
				WormholesAudience.sendMessage(event.getPlayer(), Wormholes.text().component(WormholesMessages.DOOR_EXIT_ANCHORED));
				return;
			}
			if(!canUseDoor(endpoint, event.getPlayer()))
			{
				event.setCancelled(true);
				accessFeedback.deny(event.getPlayer(), endpoint, planeOf(endpoint), event.getBlock().getWorld());
			}
			return;
		}

		Optional<PlacedDoorEndpoint> supported = protection.endpointSupportedBy(event.getBlock());
		if(supported.isPresent())
		{
			event.setCancelled(true);
			WormholesAudience.sendMessage(event.getPlayer(), Wormholes.text().component(WormholesMessages.DOOR_BREAK_FIRST));
			return;
		}
		if(protection.isPocketCore(event.getBlock()))
		{
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onDoorBreakCommit(BlockBreakEvent event)
	{
		Optional<PlacedDoorEndpoint> direct = protection.endpointForDoorBlock(event.getBlock());
		if(direct.isEmpty() || direct.get().identity().kind() == DoorKind.RETURN)
		{
			return;
		}
		PlacedDoorEndpoint endpoint = direct.get();
		Optional<PlacedDoorEndpoint> mate = guard.state().findMate(endpoint.identity());
		Material liveMaterial = event.getBlock().getWorld()
			.getBlockAt(endpoint.position().x(), endpoint.position().y(), endpoint.position().z())
			.getType();
		DoorForm form = endpoint.identity().form();
		Material droppedMaterial = DoorSkin.isSupportedSkin(liveMaterial, form)
			? liveMaterial
			: DoorItemService.defaultMaterial(endpoint.identity().kind(), form);
		event.setDropItems(false);
		try
		{
			Optional<PlacedDoorEndpoint> removed = guard.mutate(() -> guard.state().removeEndpoint(endpoint.position()));
			if(removed.isEmpty())
			{
				return;
			}
		}
		catch(IOException ex)
		{
			event.setCancelled(true);
			plugin.getLogger().log(Level.SEVERE, "Could not save dimensional-door break", ex);
			return;
		}
		runtimes.remove(endpoint);
		mate.ifPresent(placedMate -> runtimes.scheduleReconcile(placedMate, 1L));
		event.getBlock().getWorld().dropItemNaturally(
			event.getBlock().getLocation().add(0.5D, 0.5D, 0.5D),
			items().createDoor(endpoint.identity(), droppedMaterial));
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onDoorAccessInteract(PlayerInteractEvent event)
	{
		if(event.getAction() != Action.RIGHT_CLICK_BLOCK)
		{
			return;
		}
		Block clicked = event.getClickedBlock();
		if(clicked == null)
		{
			return;
		}
		Optional<PlacedDoorEndpoint> resolved = protection.endpointForDoorBlock(clicked);
		if(resolved.isEmpty() || resolved.get().identity().kind() == DoorKind.RETURN)
		{
			return;
		}
		PlacedDoorEndpoint endpoint = resolved.get();
		Player player = event.getPlayer();
		DoorAccessRecord record = guard.state().accessRecord(endpoint.identity().itemId()).orElse(null);
		if(shouldOpenAccessMenu(
			record != null,
			player.isSneaking(),
			isMainHandEmpty(player),
			DoorAccessPolicy.canManage(record, player.getUniqueId(), isDoorAdministrator(player))))
		{
			event.setUseInteractedBlock(Event.Result.DENY);
			event.setUseItemInHand(Event.Result.DENY);
			if(event.getHand() == EquipmentSlot.HAND)
			{
				scheduleAccessMenu(player, endpoint);
			}
			return;
		}
		if(DoorAccessPolicy.canUse(record, player.getUniqueId(), player.hasPermission(DoorAccessPolicy.BYPASS_NODE)))
		{
			return;
		}
		event.setUseInteractedBlock(Event.Result.DENY);
		event.setCancelled(true);
		accessFeedback.deny(player, endpoint, planeOf(endpoint), clicked.getWorld());
	}


	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onDoorInteract(PlayerInteractEvent event)
	{
		Block clicked = event.getClickedBlock();
		if(clicked != null && protection.endpointForDoorBlock(clicked).isPresent())
		{
			runtimes.scheduleNearby(clicked, 1L);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onRedstone(BlockRedstoneEvent event)
	{
		runtimes.scheduleNearby(event.getBlock(), 1L);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPhysics(BlockPhysicsEvent event)
	{
		runtimes.scheduleNearby(event.getBlock(), 1L);
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onChunkLoad(ChunkLoadEvent event)
	{
		runtimes.reconcileLoadedChunk(event.getChunk());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onChunkUnload(ChunkUnloadEvent event)
	{
		runtimes.forgetUnloadedChunk(event.getChunk());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onWorldLoad(WorldLoadEvent event)
	{
		runtimes.reconcileWorld(event.getWorld());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(PlayerQuitEvent event)
	{
		ledger.forget(event.getPlayer());
		accessFeedback.forget(event.getPlayer().getUniqueId());
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPocketDamage(EntityDamageEvent event)
	{
		if(!(event.getEntity() instanceof Player player))
		{
			return;
		}
		if(!PocketWorldService.isPocketWorld(player.getWorld()))
		{
			return;
		}
		AttributeInstance maximumHealthAttribute = player.getAttribute(Attribute.MAX_HEALTH);
		double maximumHealth = maximumHealthAttribute == null
			? player.getHealth()
			: maximumHealthAttribute.getValue();
		PocketRescuePolicy.Decision decision = PocketRescuePolicy.evaluate(
			player.getHealth(),
			maximumHealth,
			event.getFinalDamage(),
			ledger.isRescuing(player.getUniqueId()));
		if(!decision.preventsDamage())
		{
			return;
		}

		event.setCancelled(true);
		player.setHealth(decision.retainedHealth());
		player.setFallDistance(0.0F);
		player.setFireTicks(0);
		player.setNoDamageTicks(Math.max(player.getNoDamageTicks(), 40));
		if(!decision.startsEjection() || !ledger.claim(player))
		{
			return;
		}
		if(!ledger.claimRescue(player))
		{
			ledger.release(player);
			return;
		}
		rescues.begin(player);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onMove(PlayerMoveEvent event)
	{
		if(!WormholesPlatform.hasChangedPosition(event) || event.getTo() == null)
		{
			return;
		}
		handleMovement(event.getPlayer(), event.getFrom(), event.getTo());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onVehicleMove(VehicleMoveEvent event)
	{
		handleMovement(event.getVehicle(), event.getFrom(), event.getTo());
	}

	private void onLivingEntityMove(LivingEntity entity, Location from, Location to)
	{
		handleMovement(entity, from, to);
	}

	private void handleMovement(Entity traveler, Location fromLocation, Location toLocation)
	{
		if(guard.closed() || traveler.isDead() || !traveler.isValid()
			|| !hasChangedPosition(fromLocation, toLocation)
			|| fromLocation.getWorld() == null || toLocation.getWorld() == null
			|| !fromLocation.getWorld().getUID().equals(toLocation.getWorld().getUID()))
		{
			return;
		}
		UUID travelerId = traveler.getUniqueId();
		if(ledger.isTraveling(travelerId)
			|| ledger.hasCooldown(travelerId, System.nanoTime()))
		{
			return;
		}
		List<DoorSpatialIndex.Entry<RuntimeDoor>> candidates = runtimes.nearby(
			toLocation.getWorld().getUID(), toLocation.getBlockX(), toLocation.getBlockZ(), 1);
		if(candidates.isEmpty())
		{
			maybeEjectEscapedTraveler(traveler, fromLocation, toLocation);
			return;
		}
		Optional<DoorTransitAttempt> prepared = prepareTransitAttempt(
			traveler, travelerId, fromLocation, toLocation, candidates);
		if(prepared.isEmpty())
		{
			maybeEjectEscapedTraveler(traveler, fromLocation, toLocation);
			return;
		}
		DoorTransitAttempt attempt = prepared.get();
		accessAuthorizer.resolve(
			traveler,
			credentials -> transits.begin(attempt, credentials),
			NO_OP);
	}

	private Optional<DoorTransitAttempt> prepareTransitAttempt(
		Entity traveler,
		UUID travelerId,
		Location fromLocation,
		Location toLocation,
		List<DoorSpatialIndex.Entry<RuntimeDoor>> candidates)
	{
		DoorVec3 from = vector(fromLocation);
		DoorVec3 to = vector(toLocation);
		double travelerHalfWidth = traveler.getWidth() / 2.0D;
		double travelerHeight = traveler.getHeight();
		DoorTravelerClass travelerClass = travelerClass(traveler);
		DoorVec3 velocity = travelerClass == DoorTravelerClass.OBJECT ? momentum(traveler) : null;
		for(DoorSpatialIndex.Entry<RuntimeDoor> indexed : candidates)
		{
			RuntimeDoor runtime = indexed.value();
			Optional<DoorwayCrossing> crossing = DoorTransitGate.detect(
				runtime.plane(), from, to, travelerHalfWidth, travelerHeight);
			if(crossing.isEmpty())
			{
				continue;
			}
			PlacedDoorEndpoint endpoint = runtime.endpoint();
			if(!canTravelerEnter(endpoint.identity().kind(), traveler))
			{
				continue;
			}
			World sourceWorld = toLocation.getWorld();
			if(!WormholesPlatform.isOwnedByCurrentRegion(
				sourceWorld, endpoint.position().x() >> 4, endpoint.position().z() >> 4))
			{
				continue;
			}
			Optional<VanillaDoorSnapshot> captured = runtimes.capture(endpoint, sourceWorld);
			if(captured.isEmpty())
			{
				runtimes.reconcile(runtime);
				continue;
			}
			VanillaDoorSnapshot crossingSnapshot = captured.get();
			runtime.update(crossingSnapshot);
			Optional<DoorwayCrossing> liveCrossing = DoorTransitGate.detect(
				crossingSnapshot.plane(), from, to, travelerHalfWidth, travelerHeight);
			if(liveCrossing.isEmpty() || !crossingSnapshot.portalLive())
			{
				continue;
			}
			DoorTransit transit = new DoorTransit(
				crossingSnapshot.plane(),
				liveCrossing.get(),
				toLocation.getYaw(),
				toLocation.getPitch(),
				travelerHalfWidth,
				travelerHeight,
				travelerClass,
				velocity);
			return Optional.of(new DoorTransitAttempt(
				traveler,
				travelerId,
				sourceWorld,
				runtime,
				crossingSnapshot,
				transit));
		}
		return Optional.empty();
	}

	private void maybeEjectEscapedTraveler(Entity traveler, Location fromLocation, Location toLocation)
	{
		if(!(traveler instanceof Player player)
			|| player.getGameMode() == GameMode.SPECTATOR
			|| !PocketWorldService.isPocketWorld(toLocation.getWorld()))
		{
			return;
		}
		PocketSpace space = pockets.spaceAt(toLocation.getBlockX(), toLocation.getBlockZ());
		if(space == null)
		{
			space = pockets.spaceAt(fromLocation.getBlockX(), fromLocation.getBlockZ());
		}
		if(space != null && !PocketEscapePolicy.isEscaped(
			pocketStructures.layout(space),
			toLocation.getBlockX(),
			toLocation.getBlockY(),
			toLocation.getBlockZ()))
		{
			return;
		}
		if(!ledger.claim(player))
		{
			return;
		}
		if(!ledger.claimRescue(player))
		{
			ledger.release(player);
			return;
		}
		ledger.markEscapeGlitch(player.getUniqueId());
		rescues.playEscapeGlitch(player);
		rescues.begin(player);
	}

	private boolean canUseDoor(PlacedDoorEndpoint endpoint, Player player)
	{
		if(!isAccessGated(endpoint.identity().kind()))
		{
			return true;
		}
		return DoorAccessPolicy.canUse(
			guard.state().accessRecord(endpoint.identity().itemId()).orElse(null),
			player.getUniqueId(),
			player.hasPermission(DoorAccessPolicy.BYPASS_NODE));
	}

	private DoorwayPlane planeOf(PlacedDoorEndpoint endpoint)
	{
		RuntimeDoor runtime = runtimes.runtime(endpoint.identity().itemId());
		return runtime == null ? null : runtime.plane();
	}

	private void rollBackPlacement(PlacedDoorEndpoint endpoint, boolean ownedBeforePlacement)
	{
		try
		{
			guard.mutate(() -> guard.state().removeEndpoint(endpoint.position()));
			if(!ownedBeforePlacement)
			{
				guard.mutate(() -> guard.state().removeAccessRecord(endpoint.identity().itemId()));
			}
		}
		catch(IOException ex)
		{
			plugin.getLogger().log(Level.SEVERE, "Could not roll back an unscheduled door placement", ex);
		}
		runtimes.remove(endpoint);
	}

	private void scheduleAccessMenu(Player player, PlacedDoorEndpoint endpoint)
	{
		if(!FoliaScheduler.runEntity(plugin, player, () -> openAccessMenu(player, endpoint)))
		{
			openAccessMenu(player, endpoint);
		}
	}

	static DoorVec3 arrivalPoint(DoorwayPlane plane, DoorTransit transit)
	{
		Objects.requireNonNull(plane, "plane");
		Objects.requireNonNull(transit, "transit");
		return arrivalPoint(
			plane,
			transit,
			DoorPlanePairing.arrivalSideSign(transit.sourcePlane(), plane, transit.direction()));
	}

	/**
	 * The nominal landing point one side off a plane. A vertical plane pushes the
	 * traveler a stride clear of the doorway; a horizontal one places its feet on
	 * the plate for an upward exit and a full body below it for a downward one, so
	 * a fall keeps falling.
	 */
	static DoorVec3 arrivalPoint(DoorwayPlane plane, DoorTransit transit, int sideSign)
	{
		Objects.requireNonNull(plane, "plane");
		Objects.requireNonNull(transit, "transit");
		if(transit.travelerClass() == DoorTravelerClass.OBJECT)
		{
			DoorVec3 aperturePoint = plane.equals(transit.sourcePlane())
				? transit.crossing().point()
				: DoorPlanePairing.mapAperturePoint(transit.sourcePlane(), plane, transit.crossing());
			if(plane.horizontal())
			{
				double y = horizontalArrivalY(plane, transit, sideSign);
				return new DoorVec3(aperturePoint.x(), y, aperturePoint.z());
			}
			double offset = arrivalOffset(transit) * sideSign;
			return new DoorVec3(
				aperturePoint.x() + (plane.normalX() * offset),
				aperturePoint.y(),
				aperturePoint.z() + (plane.normalZ() * offset));
		}
		if(plane.horizontal())
		{
			double y = horizontalArrivalY(plane, transit, sideSign);
			return new DoorVec3(plane.blockX() + 0.5D, y, plane.blockZ() + 0.5D);
		}
		return plane.sidePoint(sideSign, arrivalOffset(transit));
	}

	private static double horizontalArrivalY(DoorwayPlane plane, DoorTransit transit, int sideSign)
	{
		if(plane.contactSurface())
		{
			double surfaceY = plane.exposedSurfaceY(sideSign);
			return sideSign > 0 ? surfaceY : surfaceY - transit.height();
		}
		return sideSign > 0 ? plane.planeY() : plane.planeY() - transit.height();
	}

	static float arrivalYaw(DoorwayPlane source, DoorwayPlane destination, DoorTransit transit)
	{
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(destination, "destination");
		Objects.requireNonNull(transit, "transit");
		return DoorPlanePairing.arrivalYaw(source, destination, transit.yaw());
	}

	static Optional<DoorVec3> findSafeVerticalDoorStanding(
		DoorVec3 nominal,
		Predicate<DoorVec3> isSafe)
	{
		return findSafeVerticalDoorStanding(nominal, DOOR_ARRIVAL_Y_OFFSETS, isSafe);
	}

	static Optional<DoorVec3> findSafeVerticalDoorStanding(
		DoorVec3 nominal,
		int[] verticalOffsets,
		Predicate<DoorVec3> isSafe)
	{
		Objects.requireNonNull(nominal, "nominal");
		Objects.requireNonNull(verticalOffsets, "verticalOffsets");
		Objects.requireNonNull(isSafe, "isSafe");
		for(int yOffset : verticalOffsets)
		{
			DoorVec3 candidate = new DoorVec3(nominal.x(), nominal.y() + yOffset, nominal.z());
			if(isSafe.test(candidate))
			{
				return Optional.of(candidate);
			}
		}
		return Optional.empty();
	}

	private static double arrivalOffset(DoorTransit transit)
	{
		return Math.max(ARRIVAL_OFFSET, 0.5D + transit.halfWidth() + DoorwayPlane.PORTAL_RECESS);
	}

	private static DoorPosition position(Block block, int lowerY)
	{
		return new DoorPosition(
			block.getWorld().getUID(), WorldIdentity.serialize(block.getWorld()), block.getX(), lowerY, block.getZ());
	}

	private static boolean isDoorAdministrator(Player player)
	{
		return player.isOp() || player.hasPermission(ADMINISTRATOR_NODE);
	}

	private static boolean isMainHandEmpty(Player player)
	{
		ItemStack held = player.getInventory().getItemInMainHand();
		return held == null || held.getType() == Material.AIR || held.getAmount() <= 0;
	}

	private static DoorVec3 vector(Location location)
	{
		return new DoorVec3(location.getX(), location.getY(), location.getZ());
	}

	private static DoorTravelerClass travelerClass(Entity traveler)
	{
		return traveler instanceof LivingEntity || traveler instanceof Vehicle
			? DoorTravelerClass.LIVING
			: DoorTravelerClass.OBJECT;
	}

	private static DoorVec3 momentum(Entity traveler)
	{
		Vector velocity;
		try
		{
			velocity = traveler.getVelocity();
		}
		catch(Throwable ex)
		{
			return null;
		}
		if(velocity == null
			|| !Double.isFinite(velocity.getX())
			|| !Double.isFinite(velocity.getY())
			|| !Double.isFinite(velocity.getZ()))
		{
			return null;
		}
		return new DoorVec3(velocity.getX(), velocity.getY(), velocity.getZ());
	}

	private static boolean hasChangedPosition(Location from, Location to)
	{
		return from.getX() != to.getX()
			|| from.getY() != to.getY()
			|| from.getZ() != to.getZ()
			|| !Objects.equals(from.getWorld(), to.getWorld());
	}

	private static boolean canTravelerEnter(DoorKind kind, Entity traveler)
	{
		boolean constrained = traveler.isInsideVehicle()
			|| !traveler.getPassengers().isEmpty()
			|| (traveler instanceof LivingEntity living && WormholesPlatform.isLeashed(living));
		return DoorTravelerPolicy.canEnter(
			kind,
			traveler instanceof Player,
			traveler instanceof Mob || traveler instanceof Vehicle,
			DoorEntitySweep.isSweepable(traveler),
			traveler instanceof Boss,
			traveler instanceof ComplexLivingEntity,
			constrained,
			traveler.getWidth(),
			traveler.getHeight());
	}

	static boolean shouldUnpackPairKit(
		Action action,
		Event.Result blockUse,
		Event.Result itemUse)
	{
		if(itemUse == Event.Result.DENY)
		{
			return false;
		}
		return action == Action.RIGHT_CLICK_AIR
			|| (action == Action.RIGHT_CLICK_BLOCK && blockUse != Event.Result.DENY);
	}

	static boolean shouldOpenAccessMenu(boolean recordPresent, boolean sneaking, boolean mainHandEmpty, boolean canManage)
	{
		return recordPresent && sneaking && mainHandEmpty && canManage;
	}

	static boolean isAccessGated(DoorKind kind)
	{
		return Objects.requireNonNull(kind, "kind") != DoorKind.RETURN;
	}

	static boolean consumesPlacedDoorItem(GameMode gameMode)
	{
		return gameMode == GameMode.CREATIVE;
	}

	static void consumeHeldItem(Player player, EquipmentSlot slot)
	{
		if(slot == EquipmentSlot.OFF_HAND)
		{
			player.getInventory().setItemInOffHand(null);
		}
		else
		{
			player.getInventory().setItemInMainHand(null);
		}
	}

	private static void giveOrDrop(Player player, ItemStack... stacks)
	{
		Map<Integer, ItemStack> overflow = player.getInventory().addItem(stacks);
		for(ItemStack stack : overflow.values())
		{
			player.getWorld().dropItemNaturally(player.getLocation(), stack);
		}
	}

	@Override
	public void close()
	{
		if(!guard.markClosed())
		{
			return;
		}
		HandlerList.unregisterAll(this);
		HandlerList.unregisterAll(protection);
		Listener entityMoveListener = livingEntityMoveListener;
		if(entityMoveListener != null)
		{
			HandlerList.unregisterAll(entityMoveListener);
			livingEntityMoveListener = null;
		}
		DoorItemService activeItems = items;
		if(activeItems != null)
		{
			activeItems.unregisterRecipes();
		}
		runtimes.close();
		ledger.clear();
		pockets.clear();
		accessFeedback.clear();
	}
}
