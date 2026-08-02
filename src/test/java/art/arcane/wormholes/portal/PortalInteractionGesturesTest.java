package art.arcane.wormholes.portal;

import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalInteractionGesturesTest
{
	@Test
	void sneakingRightClickWithEmptyMainHandOpensTheMenu()
	{
		assertTrue(PortalInteractionGestures.opensPortalMenu(true, true, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND));
		assertTrue(PortalInteractionGestures.opensPortalMenu(true, true, Action.RIGHT_CLICK_BLOCK, null));
	}

	@Test
	void standingStillHoldingSomethingOrLeftClickingNeverOpensTheMenu()
	{
		assertFalse(PortalInteractionGestures.opensPortalMenu(false, true, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND));
		assertFalse(PortalInteractionGestures.opensPortalMenu(true, false, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND));
		assertFalse(PortalInteractionGestures.opensPortalMenu(true, true, Action.LEFT_CLICK_BLOCK, EquipmentSlot.HAND));
		assertFalse(PortalInteractionGestures.opensPortalMenu(true, true, Action.LEFT_CLICK_AIR, EquipmentSlot.HAND));
		assertFalse(PortalInteractionGestures.opensPortalMenu(true, true, Action.PHYSICAL, EquipmentSlot.HAND));
	}

	@Test
	void anEmptyHandedAirClickIsNeverPartOfTheGesture()
	{
		assertFalse(PortalInteractionGestures.opensPortalMenu(true, true, Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND));
		assertFalse(PortalInteractionGestures.opensPortalMenu(true, true, Action.RIGHT_CLICK_AIR, null));
		assertFalse(PortalInteractionGestures.deniesOffHandUse(true, true, Action.RIGHT_CLICK_AIR, EquipmentSlot.OFF_HAND));
	}

	@Test
	void offHandCopyOfTheInteractionNeverOpensASecondMenu()
	{
		assertFalse(PortalInteractionGestures.opensPortalMenu(true, true, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.OFF_HAND));
	}

	@Test
	void offHandCopyOfTheMenuGestureIsSuppressedSoNothingIsPlaced()
	{
		assertTrue(PortalInteractionGestures.deniesOffHandUse(true, true, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.OFF_HAND));
		assertFalse(PortalInteractionGestures.deniesOffHandUse(true, true, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND));
		assertFalse(PortalInteractionGestures.deniesOffHandUse(true, true, Action.RIGHT_CLICK_BLOCK, null));
		assertFalse(PortalInteractionGestures.deniesOffHandUse(false, true, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.OFF_HAND));
		assertFalse(PortalInteractionGestures.deniesOffHandUse(true, false, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.OFF_HAND));
	}

	@Test
	void aSingleHandNeverBothOpensTheMenuAndOnlySuppressesTheClick()
	{
		for(Action action : Action.values())
		{
			for(EquipmentSlot hand : EquipmentSlot.values())
			{
				for(int mask = 0; mask < 4; mask++)
				{
					boolean sneaking = (mask & 1) != 0;
					boolean mainHandEmpty = (mask & 2) != 0;
					boolean opens = PortalInteractionGestures.opensPortalMenu(sneaking, mainHandEmpty, action, hand);
					boolean denies = PortalInteractionGestures.deniesOffHandUse(sneaking, mainHandEmpty, action, hand);
					assertFalse(opens && denies, action + " " + hand + " sneaking=" + sneaking + " empty=" + mainHandEmpty);
				}
			}
		}
	}

	@Test
	void surfaceSkinsOnlyApplyForHeldNonToolItems()
	{
		assertTrue(PortalInteractionGestures.appliesSurfaceSkin(false, false));
		assertFalse(PortalInteractionGestures.appliesSurfaceSkin(true, false));
		assertFalse(PortalInteractionGestures.appliesSurfaceSkin(false, true));
		assertFalse(PortalInteractionGestures.appliesSurfaceSkin(true, true));
	}

	@Test
	void menuGestureAndSurfaceSkinHandlingAreMutuallyExclusive()
	{
		for(Action action : Action.values())
		{
			for(int mask = 0; mask < 8; mask++)
			{
				boolean sneaking = (mask & 1) != 0;
				boolean mainHandEmpty = (mask & 2) != 0;
				boolean portalToolHeld = (mask & 4) != 0;
				boolean menu = PortalInteractionGestures.opensPortalMenu(sneaking, mainHandEmpty, action, EquipmentSlot.HAND);
				boolean skin = PortalInteractionGestures.appliesSurfaceSkin(portalToolHeld, mainHandEmpty);
				assertFalse(menu && skin, action + " sneaking=" + sneaking + " empty=" + mainHandEmpty + " tool=" + portalToolHeld);
			}
		}
	}
}
