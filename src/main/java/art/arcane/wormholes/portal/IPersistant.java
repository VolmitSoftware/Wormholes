package art.arcane.wormholes.portal;

import java.io.IOException;

public interface IPersistant
{
	public void save();

	public boolean needsSaving();

	public PortalSaveSnapshot prepareSave();

	public void writeSave(PortalSaveSnapshot snapshot) throws IOException;

	public void rejectSave();

	public void saveNow() throws IOException;

	public void deleteData();
}
