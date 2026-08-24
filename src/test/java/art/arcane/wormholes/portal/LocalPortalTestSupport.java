package art.arcane.wormholes.portal;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import art.arcane.wormholes.util.Cuboid;

final class LocalPortalTestSupport
{
	private LocalPortalTestSupport()
	{
	}

	static World world(String name)
	{
		UUID worldId = UUID.nameUUIDFromBytes(("local-portal-world-" + name).getBytes(StandardCharsets.UTF_8));
		NamespacedKey key = new NamespacedKey("minecraft", name);
		InvocationHandler handler = (Object proxy, Method method, Object[] arguments) -> switch(method.getName())
		{
			case "getUID" -> worldId;
			case "getKey" -> key;
			case "getName" -> name;
			case "getMinHeight" -> Integer.valueOf(-64);
			case "getMaxHeight" -> Integer.valueOf(320);
			case "getSeaLevel" -> Integer.valueOf(63);
			case "getEnvironment" -> World.Environment.NORMAL;
			case "equals" -> Boolean.valueOf(proxy == arguments[0]);
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "toString" -> "LocalPortalTestWorld[" + name + "]";
			default -> defaultValue(method.getReturnType());
		};
		return (World) Proxy.newProxyInstance(LocalPortalTestSupport.class.getClassLoader(), new Class<?>[] {World.class}, handler);
	}

	static LocalPortal portal(World world, PortalType type)
	{
		PortalStructure structure = new PortalStructure();
		structure.setWorld(world);
		structure.setArea(new Cuboid(new Location(world, 0.0D, 64.0D, 0.0D), new Location(world, 0.0D, 66.0D, 2.0D)));
		LocalPortal portal = new LocalPortal(UUID.randomUUID(), type, structure);
		portal.setAmbientAttended(false);
		return portal;
	}

	static Traversive traversive(LocalPortal portal, Entity entity, Vector inPoint)
	{
		return new Traversive(entity, portal.getFrame().view(true), portal.getOrigin(), inPoint,
				new Vector(0.0D, 0.0D, -0.4D), new Vector(0.0D, 0.0D, -1.0D), true);
	}

	static LocalPortalRuntime rejectingRuntime()
	{
		return new LocalPortalRuntime()
		{
			@Override
			public boolean dispatch(Entity entity, Runnable task, Runnable retired, long delayTicks)
			{
				return false;
			}

			@Override
			public boolean dispatchRegion(World world, int chunkX, int chunkZ, Runnable task, long delayTicks)
			{
				return false;
			}

			@Override
			public CompletionStage<Boolean> teleport(Entity entity, Location target)
			{
				return CompletableFuture.completedFuture(Boolean.FALSE);
			}
		};
	}

	static LocalPortalRuntime failedTeleportRuntime(Throwable failure)
	{
		return new LocalPortalRuntime()
		{
			@Override
			public boolean dispatch(Entity entity, Runnable task, Runnable retired, long delayTicks)
			{
				task.run();
				return true;
			}

			@Override
			public boolean dispatchRegion(World world, int chunkX, int chunkZ, Runnable task, long delayTicks)
			{
				task.run();
				return true;
			}

			@Override
			public CompletionStage<Boolean> teleport(Entity entity, Location target)
			{
				return failure == null
						? CompletableFuture.completedFuture(Boolean.FALSE)
						: CompletableFuture.failedFuture(failure);
			}
		};
	}

	static LocalPortalRuntime teleportingRejectingRuntime()
	{
		return new LocalPortalRuntime()
		{
			@Override
			public boolean dispatch(Entity entity, Runnable task, Runnable retired, long delayTicks)
			{
				return false;
			}

			@Override
			public boolean dispatchRegion(World world, int chunkX, int chunkZ, Runnable task, long delayTicks)
			{
				return false;
			}

			@Override
			public CompletionStage<Boolean> teleport(Entity entity, Location target)
			{
				return CompletableFuture.completedFuture(Boolean.TRUE);
			}
		};
	}

	static LocalPortalRuntime runOnceThenRejectingRuntime()
	{
		int[] dispatches = new int[1];
		return new LocalPortalRuntime()
		{
			@Override
			public boolean dispatch(Entity entity, Runnable task, Runnable retired, long delayTicks)
			{
				if(dispatches[0]++ > 0)
				{
					return false;
				}
				task.run();
				return true;
			}

			@Override
			public boolean dispatchRegion(World world, int chunkX, int chunkZ, Runnable task, long delayTicks)
			{
				task.run();
				return true;
			}

			@Override
			public CompletionStage<Boolean> teleport(Entity entity, Location target)
			{
				return CompletableFuture.completedFuture(Boolean.TRUE);
			}
		};
	}

	static LocalPortalRuntime deferringRuntime(List<Runnable> pending, boolean teleportSucceeds)
	{
		return new LocalPortalRuntime()
		{
			@Override
			public boolean dispatch(Entity entity, Runnable task, Runnable retired, long delayTicks)
			{
				pending.add(task);
				return true;
			}

			@Override
			public boolean dispatchRegion(World world, int chunkX, int chunkZ, Runnable task, long delayTicks)
			{
				pending.add(task);
				return true;
			}

			@Override
			public CompletionStage<Boolean> teleport(Entity entity, Location target)
			{
				return CompletableFuture.completedFuture(Boolean.valueOf(teleportSucceeds));
			}
		};
	}

	static Object defaultValue(Class<?> type)
	{
		if(!type.isPrimitive())
		{
			return null;
		}
		if(type == boolean.class)
		{
			return Boolean.FALSE;
		}
		if(type == byte.class)
		{
			return Byte.valueOf((byte) 0);
		}
		if(type == short.class)
		{
			return Short.valueOf((short) 0);
		}
		if(type == int.class)
		{
			return Integer.valueOf(0);
		}
		if(type == long.class)
		{
			return Long.valueOf(0L);
		}
		if(type == float.class)
		{
			return Float.valueOf(0.0F);
		}
		if(type == double.class)
		{
			return Double.valueOf(0.0D);
		}
		if(type == char.class)
		{
			return Character.valueOf('\0');
		}
		return null;
	}

	static final class FakeEntity implements InvocationHandler
	{
		private final UUID id;
		private final String name;
		private final Entity proxy;
		private final List<Location> teleports;
		private Location location;
		private Vector velocity;
		private boolean valid;

		private FakeEntity(String name, Location location, boolean player)
		{
			this.id = UUID.randomUUID();
			this.name = name;
			this.location = location.clone();
			this.velocity = new Vector(0.0D, 0.0D, 0.0D);
			this.valid = true;
			this.teleports = new ArrayList<Location>();
			this.proxy = (Entity) Proxy.newProxyInstance(
					LocalPortalTestSupport.class.getClassLoader(),
					new Class<?>[] {player ? Player.class : Entity.class},
					this);
		}

		static FakeEntity entity(String name, Location location)
		{
			return new FakeEntity(name, location, false);
		}

		static FakeEntity player(String name, Location location)
		{
			return new FakeEntity(name, location, true);
		}

		Entity entity()
		{
			return proxy;
		}

		Player player()
		{
			return (Player) proxy;
		}

		UUID id()
		{
			return id;
		}

		Vector velocity()
		{
			return velocity.clone();
		}

		List<Location> teleports()
		{
			return teleports;
		}

		void invalidate()
		{
			valid = false;
		}

		@Override
		public Object invoke(Object instance, Method method, Object[] arguments)
		{
			return switch(method.getName())
			{
				case "getUniqueId" -> id;
				case "getName" -> name;
				case "getLocation" -> location.clone();
				case "getWorld" -> location.getWorld();
				case "getVelocity" -> velocity.clone();
				case "setVelocity" ->
				{
					velocity = ((Vector) arguments[0]).clone();
					yield null;
				}
				case "teleport" ->
				{
					Location target = (Location) arguments[0];
					teleports.add(target.clone());
					location = target.clone();
					yield Boolean.TRUE;
				}
				case "isValid" -> Boolean.valueOf(valid);
				case "isOnline" -> Boolean.valueOf(valid);
				case "isOp" -> Boolean.FALSE;
				case "getPassengers" -> List.of();
				case "getVehicle" -> null;
				case "equals" -> Boolean.valueOf(instance == arguments[0]);
				case "hashCode" -> Integer.valueOf(System.identityHashCode(instance));
				case "toString" -> "LocalPortalTestEntity[" + name + "]";
				default -> defaultValue(method.getReturnType());
			};
		}
	}
}
