package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.ProjectionManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.menu.BukkitMenuViewer;
import art.arcane.wormholes.menu.host.BukkitPortalMenuHost;
import art.arcane.wormholes.menu.portal.PortalEdit;
import art.arcane.wormholes.menu.portal.PortalEditOutcome;

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
		PortalManager portals = mock(PortalManager.class);
		when(portals.getLocalPortal(mirror.getId())).thenReturn(mirror);
		BukkitPortalMenuHost host = new BukkitPortalMenuHost(portals);
		Player player = mock(Player.class);
		when(player.getUniqueId()).thenReturn(UUID.randomUUID());
		when(player.isOp()).thenReturn(true);
		BukkitMenuViewer viewer = new BukkitMenuViewer(player);
		ProjectionManager previous = Wormholes.projectionManager;
		ProjectionManager projections = mock(ProjectionManager.class, RETURNS_DEEP_STUBS);
		when(projections.clientView().nativeMesh(any())).thenReturn(nativeViewer);
		Wormholes.projectionManager = projections;
		try
		{
			for(int step = 0; step < 4; step++)
			{
				OpticTransform before = image(mirror);
				QuarterTurn rotation = mirror.getMirrorRotation();
				PortalEditOutcome outcome = host.edit(viewer, mirror.getId(), new PortalEdit.MirrorRotation(step % 2 == 0));
				assertInstanceOf(PortalEditOutcome.Applied.class, outcome);
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
