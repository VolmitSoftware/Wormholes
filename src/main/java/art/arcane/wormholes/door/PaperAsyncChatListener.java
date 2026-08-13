package art.arcane.wormholes.door;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.function.BiFunction;

final class PaperAsyncChatListener implements Listener
{
	private final BiFunction<Player, String, Boolean> delivery;
	private final Method signedMessageAccessor;
	private final Method messageAccessor;

	PaperAsyncChatListener(BiFunction<Player, String, Boolean> delivery)
	{
		this.delivery = Objects.requireNonNull(delivery, "delivery");
		try
		{
			signedMessageAccessor = AsyncChatEvent.class.getMethod("signedMessage");
			messageAccessor = signedMessageAccessor.getReturnType().getMethod("message");
		}
		catch(NoSuchMethodException exception)
		{
			throw new IllegalStateException("Paper signed-chat API does not expose its plain message", exception);
		}
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onChat(AsyncChatEvent event)
	{
		String text = readMessage(event);
		if(Boolean.TRUE.equals(delivery.apply(event.getPlayer(), text)))
		{
			event.setCancelled(true);
		}
	}

	private String readMessage(AsyncChatEvent event)
	{
		try
		{
			Object signedMessage = signedMessageAccessor.invoke(event, (Object[]) null);
			return (String) messageAccessor.invoke(
				Objects.requireNonNull(signedMessage, "signedMessage"),
				(Object[]) null);
		}
		catch(ReflectiveOperationException | ClassCastException exception)
		{
			throw new IllegalStateException("Could not read Paper signed chat input", exception);
		}
	}
}
