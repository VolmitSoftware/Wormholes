package art.arcane.wormholes.portal;

import art.arcane.optics.math.Face;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

public final class PortalConstruction {
    private PortalConstruction() {
    }

    public static Set<Cell> connectedCells(Cell start, PortalType type, Function<Cell, PortalType> lookup) {
        Set<Cell> connected = new HashSet<>();
        Set<Cell> visited = new HashSet<>();
        ArrayDeque<Cell> search = new ArrayDeque<>();
        search.add(start);
        while (!search.isEmpty()) {
            Cell cell = search.removeFirst();
            if (!visited.add(cell) || lookup.apply(cell) != type) {
                continue;
            }
            connected.add(cell);
            search.addLast(new Cell(cell.x() + 1, cell.y(), cell.z()));
            search.addLast(new Cell(cell.x() - 1, cell.y(), cell.z()));
            search.addLast(new Cell(cell.x(), cell.y() + 1, cell.z()));
            search.addLast(new Cell(cell.x(), cell.y() - 1, cell.z()));
            search.addLast(new Cell(cell.x(), cell.y(), cell.z() + 1));
            search.addLast(new Cell(cell.x(), cell.y(), cell.z() - 1));
        }
        return Set.copyOf(connected);
    }

    public record Cell(int x, int y, int z) {
    }

	public static boolean isCoplanarPortalArea(int xDepth, int yDepth, int zDepth)
	{
		int flatAxes = 0;
		if(xDepth == 0)
		{
			flatAxes++;
		}
		if(yDepth == 0)
		{
			flatAxes++;
		}
		if(zDepth == 0)
		{
			flatAxes++;
		}

		return flatAxes >= 1;
	}

	public static Face derivePortalNormal(int xDepth, int yDepth, int zDepth, double lookX, double lookY, double lookZ)
	{
		double ax = xDepth == 0 ? Math.abs(lookX) : -1.0D;
		double ay = yDepth == 0 ? Math.abs(lookY) : -1.0D;
		double az = zDepth == 0 ? Math.abs(lookZ) : -1.0D;

		if(ax >= ay && ax >= az)
		{
			return lookX >= 0.0D ? Face.E : Face.W;
		}

		if(ay >= az)
		{
			return lookY >= 0.0D ? Face.U : Face.D;
		}

		return lookZ >= 0.0D ? Face.S : Face.N;
	}

}
