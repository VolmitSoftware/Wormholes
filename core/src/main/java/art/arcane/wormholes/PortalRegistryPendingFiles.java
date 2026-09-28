package art.arcane.wormholes;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

final class PortalRegistryPendingFiles
{
	private final List<File> files = new ArrayList<File>();

	synchronized boolean add(File file)
	{
		if(file == null || files.contains(file))
		{
			return false;
		}
		return files.add(file);
	}

	synchronized boolean remove(File file)
	{
		return files.remove(file);
	}

	synchronized void clear()
	{
		files.clear();
	}

	synchronized boolean isEmpty()
	{
		return files.isEmpty();
	}

	synchronized int size()
	{
		return files.size();
	}

	synchronized void resolve(Predicate<File> resolved)
	{
		Iterator<File> iterator = files.iterator();

		while(iterator.hasNext())
		{
			if(resolved.test(iterator.next()))
			{
				iterator.remove();
			}
		}
	}
}
