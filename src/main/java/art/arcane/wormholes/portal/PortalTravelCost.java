package art.arcane.wormholes.portal;

import org.bukkit.entity.Player;

import art.arcane.volmlib.util.json.JSONObject;

public interface PortalTravelCost
{
	Type getType();

	Status status(Player player);

	ReserveResult reserve(Player player);

	JSONObject toJson();

	static PortalTravelCost fromJson(JSONObject json)
	{
		if(json == null)
		{
			return null;
		}
		String typeName = json.optString("type", "");
		Type type;
		try
		{
			type = Type.valueOf(typeName);
		}
		catch(IllegalArgumentException exception)
		{
			throw new IllegalArgumentException("Unknown portal travel cost type: " + typeName, exception);
		}
		return switch(type)
		{
			case VANILLA -> VanillaTravelCost.fromJson(json);
			case VAULT -> VaultTravelCost.fromJson(json);
		};
	}

	enum Type
	{
		VANILLA,
		VAULT
	}

	enum Status
	{
		AVAILABLE,
		INSUFFICIENT,
		UNAVAILABLE,
		FAILED
	}

	record ReserveResult(Status status, Reservation reservation)
	{
		public static ReserveResult reserved(Reservation reservation)
		{
			return new ReserveResult(Status.AVAILABLE, reservation);
		}

		public static ReserveResult failed(Status status)
		{
			if(status == Status.AVAILABLE)
			{
				throw new IllegalArgumentException("A failed reservation cannot be available");
			}
			return new ReserveResult(status, null);
		}

		public boolean successful()
		{
			return status == Status.AVAILABLE && reservation != null;
		}
	}

	interface Reservation
	{
		void commit();

		void refund();
	}
}
