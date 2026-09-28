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
	PAIR_KIT(DoorForm.DOOR, DoorKind.PAIR, "dimensional_door_pair_kit",
		"EDE|ORO| D ", "E=ENDER_EYE, D=#doors, O=OBSIDIAN, R=#wormhole-rune"),
	PERSONAL_DOOR(DoorForm.DOOR, DoorKind.PERSONAL, "personal_dimensional_door",
		" R |CDE", "R=#wormhole-rune, C=RECOVERY_COMPASS, D=#doors, E=ENDER_CHEST"),
	PUBLIC_DOOR(DoorForm.DOOR, DoorKind.PUBLIC, "public_dimensional_door",
		"RDR| E | L ", "R=#wormhole-rune, D=#doors, E=ENDER_CHEST, L=LODESTONE"),
	TRAPDOOR_PAIR_KIT(DoorForm.TRAPDOOR, DoorKind.PAIR, "dimensional_trapdoor_pair_kit",
		"EDE|ORO| D ", "E=ENDER_EYE, D=#trapdoors, O=OBSIDIAN, R=#wormhole-rune"),
	PERSONAL_TRAPDOOR(DoorForm.TRAPDOOR, DoorKind.PERSONAL, "personal_dimensional_trapdoor",
		" R |CDE", "R=#wormhole-rune, C=RECOVERY_COMPASS, D=#trapdoors, E=ENDER_CHEST"),
	PUBLIC_TRAPDOOR(DoorForm.TRAPDOOR, DoorKind.PUBLIC, "public_dimensional_trapdoor",
		"RDR| E | L ", "R=#wormhole-rune, D=#trapdoors, E=ENDER_CHEST, L=LODESTONE");

	private final DoorForm form;
	private final DoorKind kind;
	private final String recipeName;
	private final String defaultShape;
	private final String defaultIngredients;

	DoorCraftProduct(
		DoorForm form,
		DoorKind kind,
		String recipeName,
		String defaultShape,
		String defaultIngredients)
	{
		this.form = form;
		this.kind = kind;
		this.recipeName = recipeName;
		this.defaultShape = defaultShape;
		this.defaultIngredients = defaultIngredients;
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

	/** Shipped grid for this product, in the same text form the config uses. */
	public String defaultShape()
	{
		return defaultShape;
	}

	/** Shipped ingredient list for this product, in the same text form the config uses. */
	public String defaultIngredients()
	{
		return defaultIngredients;
	}

	public DoorRecipeSpec defaultSpec()
	{
		return DoorRecipeSpec.parse(defaultShape, defaultIngredients);
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
