package art.arcane.wormholes.portal;

import art.arcane.volmlib.util.json.JSONObject;

public interface IWritable
{
	public void loadJSON(JSONObject j);

	public void saveJSON(JSONObject j);

	public JSONObject toJSON();
}
