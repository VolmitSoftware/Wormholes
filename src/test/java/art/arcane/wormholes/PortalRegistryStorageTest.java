package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PortalRegistryStorageTest
{
	@TempDir
	Path temporaryDirectory;

	@Test
	void enumeratesOnlyJsonFilesRecursively() throws Exception
	{
		Path portalFolder = temporaryDirectory.resolve("portals");
		Path first = portalFolder.resolve("a").resolve("b").resolve("first.json");
		Path second = portalFolder.resolve("c").resolve("d").resolve("second.json");
		Files.createDirectories(first.getParent());
		Files.createDirectories(second.getParent());
		Files.writeString(first, "{}");
		Files.writeString(second, "{}");
		Files.writeString(second.resolveSibling("ignored.txt"), "{}");

		PortalRegistryStorage.PortalFileListing listing =
			new PortalRegistryStorage(portalFolder.toFile()).listPortalFiles();

		assertEquals(List.of(first.toFile(), second.toFile()), listing.files());
		assertEquals(List.of(), listing.failures());
	}

	@Test
	void enumerationFailureIsNotReportedAsAnEmptyRegistry() throws Exception
	{
		Path occupied = temporaryDirectory.resolve("not-a-directory");
		Files.writeString(occupied, "occupied");

		assertThrows(
			IOException.class,
			() -> new PortalRegistryStorage(occupied.toFile()).listPortalFiles()
		);
	}
}
