package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.ILocalPortal;

public final class EffectManagerLookLabelTest
{
	@Test
	public void nearbyPublicLabelsKeepNonToolViewersOnTheFastScan()
	{
		assertFalse(EffectManager.hasPublicLookLabel(List.of(portal(false), portal(false))));
		assertTrue(EffectManager.hasPublicLookLabel(List.of(portal(false), portal(true))));
	}

	private static ILocalPortal portal(boolean publicLookLabel)
	{
		return (ILocalPortal) Proxy.newProxyInstance(
				ILocalPortal.class.getClassLoader(),
				new Class<?>[] { ILocalPortal.class },
				(proxy, method, arguments) -> switch(method.getName())
				{
					case "isPublicLookLabel" -> Boolean.valueOf(publicLookLabel);
					case "toString" -> "LookLabelPortal[" + publicLookLabel + "]";
					case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
					case "equals" -> Boolean.valueOf(proxy == arguments[0]);
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}
}
