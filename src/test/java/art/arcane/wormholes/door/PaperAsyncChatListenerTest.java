package art.arcane.wormholes.door;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PaperAsyncChatListenerTest
{
	@Test
	void capturedInputUsesThePlainSignedMessageAndCancelsChat()
	{
		AtomicReference<String> captured = new AtomicReference<>();
		PaperAsyncChatListener listener = new PaperAsyncChatListener((player, text) ->
		{
			captured.set(text);
			return true;
		});
		AsyncChatEvent event = event("typed input", "decorated input");

		listener.onChat(event);

		assertEquals("typed input", captured.get());
		assertTrue(event.isCancelled());
	}

	@Test
	void ordinaryChatRemainsUncancelledWhenNoPromptAcceptsIt()
	{
		PaperAsyncChatListener listener = new PaperAsyncChatListener((player, text) -> false);
		AsyncChatEvent event = event("ordinary chat", "ordinary chat");

		listener.onChat(event);

		assertFalse(event.isCancelled());
	}

	@Test
	void emptyPromptInputIsDeliveredWithoutConversion()
	{
		AtomicReference<String> captured = new AtomicReference<>();
		PaperAsyncChatListener listener = new PaperAsyncChatListener((player, text) ->
		{
			captured.set(text);
			return true;
		});
		AsyncChatEvent event = event("", "decorated input");

		listener.onChat(event);

		assertEquals("", captured.get());
		assertTrue(event.isCancelled());
	}

	private static AsyncChatEvent event(String signedText, String renderedText)
	{
		Component rendered = Component.text(renderedText);
		SignedMessage signed = SignedMessage.system(signedText, rendered);
		return new AsyncChatEvent(
			true,
			player(),
			Set.of(),
			ChatRenderer.defaultRenderer(),
			rendered,
			rendered,
			signed);
	}

	private static Player player()
	{
		return (Player) Proxy.newProxyInstance(
			Player.class.getClassLoader(),
			new Class<?>[]{Player.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "toString" -> "chat-player";
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == arguments[0];
				default -> defaultValue(method.getReturnType());
			});
	}

	private static Object defaultValue(Class<?> type)
	{
		if(!type.isPrimitive())
		{
			return null;
		}
		if(type == boolean.class)
		{
			return false;
		}
		if(type == char.class)
		{
			return '\0';
		}
		return 0;
	}
}
