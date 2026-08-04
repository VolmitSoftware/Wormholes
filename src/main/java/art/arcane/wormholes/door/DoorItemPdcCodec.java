package art.arcane.wormholes.door;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Versioned Persistent Data Container encoding for dimensional-door items.
 */
public final class DoorItemPdcCodec
{
	private static final int CURRENT_SCHEMA = 3;
	private static final int FORM_SCHEMA = 3;
	private static final int KIND_SCHEMA = 2;
	private static final int LEGACY_SCHEMA = 1;

	private final NamespacedKey schemaKey;
	private final NamespacedKey itemIdKey;
	private final NamespacedKey kindKey;
	private final NamespacedKey formKey;
	private final NamespacedKey pairIdKey;
	private final NamespacedKey pairEndpointKey;
	private final NamespacedKey spaceIdKey;
	private final NamespacedKey pairKitIdKey;
	private final NamespacedKey pairKitFormKey;
	private final NamespacedKey craftProductKey;

	public DoorItemPdcCodec(String namespace)
	{
		Objects.requireNonNull(namespace, "namespace");
		schemaKey = new NamespacedKey(namespace, "door_schema");
		itemIdKey = new NamespacedKey(namespace, "door_item_id");
		kindKey = new NamespacedKey(namespace, "door_kind");
		formKey = new NamespacedKey(namespace, "door_form");
		pairIdKey = new NamespacedKey(namespace, "door_pair_id");
		pairEndpointKey = new NamespacedKey(namespace, "door_pair_endpoint");
		spaceIdKey = new NamespacedKey(namespace, "door_space_id");
		pairKitIdKey = new NamespacedKey(namespace, "door_pair_kit_id");
		pairKitFormKey = new NamespacedKey(namespace, "door_pair_kit_form");
		craftProductKey = new NamespacedKey(namespace, "door_craft_product");
	}

	public void encodeIdentity(PersistentDataContainer data, DoorItemIdentity identity)
	{
		Objects.requireNonNull(data, "data");
		Objects.requireNonNull(identity, "identity");
		clearIdentity(data);
		data.remove(pairKitIdKey);
		data.remove(pairKitFormKey);
		data.remove(craftProductKey);
		data.set(schemaKey, PersistentDataType.INTEGER, CURRENT_SCHEMA);
		data.set(itemIdKey, PersistentDataType.STRING, identity.itemId().toString());
		data.set(kindKey, PersistentDataType.STRING, identity.kind().name());
		data.set(formKey, PersistentDataType.STRING, identity.form().name());
		if(identity.pairId() != null)
		{
			data.set(pairIdKey, PersistentDataType.STRING, identity.pairId().toString());
		}
		if(identity.pairEndpoint() != null)
		{
			data.set(pairEndpointKey, PersistentDataType.STRING, identity.pairEndpoint().name());
		}
		if(identity.spaceId() != null)
		{
			data.set(spaceIdKey, PersistentDataType.STRING, identity.spaceId().toString());
		}
	}

	/** Malformed or unsupported data is inert rather than crashing an event. */
	public Optional<DoorItemIdentity> decodeIdentity(PersistentDataContainer data)
	{
		Objects.requireNonNull(data, "data");
		try
		{
			Integer schema = data.get(schemaKey, PersistentDataType.INTEGER);
			String itemId = data.get(itemIdKey, PersistentDataType.STRING);
			String kind = data.get(kindKey, PersistentDataType.STRING);
			if(schema == null
				|| schema < LEGACY_SCHEMA
				|| schema > CURRENT_SCHEMA
				|| itemId == null
				|| kind == null)
			{
				return Optional.empty();
			}

			String pairId = data.get(pairIdKey, PersistentDataType.STRING);
			String pairEndpoint = data.get(pairEndpointKey, PersistentDataType.STRING);
			String spaceId = data.get(spaceIdKey, PersistentDataType.STRING);
			return Optional.of(new DoorItemIdentity(
				UUID.fromString(itemId),
				decodeKind(schema, kind),
				decodeForm(schema, data.get(formKey, PersistentDataType.STRING)),
				optionalUuid(pairId),
				pairEndpoint == null ? null : PairEndpoint.valueOf(pairEndpoint),
				optionalUuid(spaceId)));
		}
		catch(RuntimeException ignored)
		{
			return Optional.empty();
		}
	}

	public void clearIdentity(PersistentDataContainer data)
	{
		Objects.requireNonNull(data, "data");
		data.remove(schemaKey);
		data.remove(itemIdKey);
		data.remove(kindKey);
		data.remove(formKey);
		data.remove(pairIdKey);
		data.remove(pairEndpointKey);
		data.remove(spaceIdKey);
	}

	public void encodePairKit(PersistentDataContainer data, UUID kitId)
	{
		encodePairKit(data, kitId, DoorForm.DOOR);
	}

	public void encodePairKit(PersistentDataContainer data, UUID kitId, DoorForm form)
	{
		Objects.requireNonNull(data, "data");
		Objects.requireNonNull(kitId, "kitId");
		Objects.requireNonNull(form, "form");
		clearIdentity(data);
		data.remove(craftProductKey);
		data.set(pairKitIdKey, PersistentDataType.STRING, kitId.toString());
		data.set(pairKitFormKey, PersistentDataType.STRING, form.name());
	}

	public Optional<UUID> decodePairKitId(PersistentDataContainer data)
	{
		return decodePairKit(data).map(PairKit::kitId);
	}

	/** Kits stamped before trapdoors existed carry no form and unpack as doors. */
	public Optional<PairKit> decodePairKit(PersistentDataContainer data)
	{
		Objects.requireNonNull(data, "data");
		try
		{
			String value = data.get(pairKitIdKey, PersistentDataType.STRING);
			if(value == null)
			{
				return Optional.empty();
			}
			String form = data.get(pairKitFormKey, PersistentDataType.STRING);
			return Optional.of(new PairKit(
				UUID.fromString(value),
				form == null ? DoorForm.DOOR : DoorForm.valueOf(form)));
		}
		catch(IllegalArgumentException ignored)
		{
			return Optional.empty();
		}
	}

	public void encodeCraftProduct(PersistentDataContainer data, DoorCraftProduct product)
	{
		Objects.requireNonNull(data, "data");
		Objects.requireNonNull(product, "product");
		data.remove(pairKitIdKey);
		data.remove(pairKitFormKey);
		clearIdentity(data);
		data.set(craftProductKey, PersistentDataType.STRING, product.name());
	}

	public Optional<DoorCraftProduct> decodeCraftProduct(PersistentDataContainer data)
	{
		Objects.requireNonNull(data, "data");
		try
		{
			String value = data.get(craftProductKey, PersistentDataType.STRING);
			return value == null ? Optional.empty() : Optional.of(DoorCraftProduct.valueOf(value));
		}
		catch(IllegalArgumentException ignored)
		{
			return Optional.empty();
		}
	}

	/**
	 * Schemas written before trapdoors existed carry no form and are hinged doors.
	 * From the form schema on the key is mandatory: an item stamped current but
	 * missing it has been tampered with and must not silently become a door.
	 */
	private static DoorForm decodeForm(int schema, String value)
	{
		if(schema < FORM_SCHEMA)
		{
			return DoorForm.DOOR;
		}
		if(value == null)
		{
			throw new IllegalArgumentException("schema " + schema + " door items must carry a form");
		}
		return DoorForm.valueOf(value);
	}

	private static DoorKind decodeKind(int schema, String value)
	{
		if(schema >= KIND_SCHEMA)
		{
			return DoorKind.valueOf(value);
		}
		return switch(value)
		{
			case "PAIRED" -> DoorKind.PAIR;
			case "IRON" -> DoorKind.PUBLIC;
			case "PERSONAL" -> DoorKind.PERSONAL;
			case "RETURN" -> DoorKind.RETURN;
			default -> throw new IllegalArgumentException("Unknown legacy door kind " + value);
		};
	}

	private static UUID optionalUuid(String value)
	{
		return value == null ? null : UUID.fromString(value);
	}

	/** The kit identity plus the form both endpoints unpack into. */
	public record PairKit(UUID kitId, DoorForm form)
	{
		public PairKit
		{
			Objects.requireNonNull(kitId, "kitId");
			Objects.requireNonNull(form, "form");
		}
	}
}
