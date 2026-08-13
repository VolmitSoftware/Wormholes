package art.arcane.wormholes.door;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Objects;
import java.util.function.BiFunction;

final class PaperAsyncChatListener implements Listener
{
	private final BiFunction<Player, String, Boolean> delivery;

	PaperAsyncChatListener(BiFunction<Player, String, Boolean> delivery)
	{
		this.delivery = Objects.requireNonNull(delivery, "delivery");
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onChat(AsyncChatEvent event)
	{
		String text = PlainTextComponentSerializer.plainText().serialize(event.message());
		if(Boolean.TRUE.equals(delivery.apply(event.getPlayer(), text)))
		{
			event.setCancelled(true);
		}
	}
}
