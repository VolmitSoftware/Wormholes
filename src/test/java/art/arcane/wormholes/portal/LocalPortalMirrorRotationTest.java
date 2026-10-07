package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.volmlib.util.inventorygui.Element;
import art.arcane.volmlib.util.inventorygui.ElementEvent;
import art.arcane.volmlib.util.inventorygui.Window;
import art.arcane.wormholes.ProjectionManager;
import art.arcane.wormholes.Wormholes;

public final class LocalPortalMirrorRotationTest
{
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	public void everyWallMirrorRotationStepChangesTheImageForEveryViewer(boolean nativeViewer)
	{
		World world = LocalPortalTestSupport.world("overworld");
		LocalPortal mirror = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		mirror.setMirrorMode(true);
		assertFalse(QuarterTurn.supportsQuarterTurns(mirror.getFrame()));
		Player viewer = LocalPortalTestSupport.FakeEntity.player("viewer", new Location(world, 0.0D, 64.0D, 0.0D)).player();
		ProjectionManager previous = Wormholes.projectionManager;
		ProjectionManager projections = mock(ProjectionManager.class, RETURNS_DEEP_STUBS);
		when(projections.clientView().nativeMesh(any())).thenReturn(nativeViewer);
		Wormholes.projectionManager = projections;
		try
		{
			Element element = new LocalPortalMenus(mirror).mirrorModeOption(viewer, mock(Window.class));
			for(int step = 0; step < 4; step++)
			{
				OpticTransform before = image(mirror);
				QuarterTurn rotation = mirror.getMirrorRotation();
				element.call(step % 2 == 0 ? ElementEvent.RIGHT : ElementEvent.SHIFT_RIGHT, element);
				assertNotEquals(before, image(mirror), "step " + step + " from " + rotation + " must change the mirror image");
				assertEquals(mirror.getMirrorRotation(), mirror.getMirrorRotation().coherentFor(mirror.getFrame()));
			}
		}
		finally
		{
			Wormholes.projectionManager = previous;
		}
	}

	private static OpticTransform image(LocalPortal mirror)
	{
		return OpticTransform.mirror(mirror.getFrame(), mirror.getOrigin(), mirror.getMirrorRotation());
	}
}
