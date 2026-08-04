package art.arcane.wormholes.door;

import java.util.Objects;
import java.util.Optional;

/**
 * Identity-bearing products minted by the dimensional-door crafting hook.
 *
 * <p>Each product names its own recipe, so the registered key, the key the craft
 * hook resolves back to a product, and the identity that gets minted all come
 * from one table instead of three parallel switches.</p>
 */
public enum DoorCraftProduct
{
	PAIR_KIT(DoorForm.DOOR, DoorKind.PAIR, "dimensional_door_pair_kit"),
	PERSONAL_DOOR(DoorForm.DOOR, DoorKind.PERSONAL, "personal_dimensional_door"),
	PUBLIC_DOOR(DoorForm.DOOR, DoorKind.PUBLIC, "public_dimensional_door"),
	TRAPDOOR_PAIR_KIT(DoorForm.TRAPDOOR, DoorKind.PAIR, "dimensional_trapdoor_pair_kit"),
	PERSONAL_TRAPDOOR(DoorForm.TRAPDOOR, DoorKind.PERSONAL, "personal_dimensional_trapdoor"),
	PUBLIC_TRAPDOOR(DoorForm.TRAPDOOR, DoorKind.PUBLIC, "public_dimensional_trapdoor");

	private final DoorForm form;
	private final DoorKind kind;
	private final String recipeName;

	DoorCraftProduct(DoorForm form, DoorKind kind, String recipeName)
	{
		this.form = form;
		this.kind = kind;
		this.recipeName = recipeName;
	}

	public DoorForm form()
	{
		return form;
	}

	public DoorKind kind()
	{
		return kind;
	}

	/** The key name this product's recipe is registered under, within the plugin namespace. */
	public String recipeName()
	{
		return recipeName;
	}

	public static Optional<DoorCraftProduct> forRecipeName(String recipeName)
	{
		Objects.requireNonNull(recipeName, "recipeName");
		for(DoorCraftProduct product : values())
		{
			if(product.recipeName.equals(recipeName))
			{
				return Optional.of(product);
			}
		}
		return Optional.empty();
	}
}
