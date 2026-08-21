package art.arcane.wormholes.door;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.platform.WormholesPlatform;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

/**
 * Creates, identifies, crafts, and unpacks survival dimensional-door items.
 *
 * <p>This class deliberately is not a Bukkit listener. The owning manager must
 * call {@link #handleCraft(CraftItemEvent)} from its craft listener,
 * {@link #handleCrafterCraft(Recipe)} from its crafter listener, and invoke
 * {@link #unpackPairKit(ItemStack)} only while atomically consuming that kit.</p>
 */
public final class DoorItemService
{
	public static final Material PAIR_KIT_MATERIAL = Material.BUNDLE;
	public static final Material PAIR_DOOR_MATERIAL = Material.OAK_DOOR;
	public static final Material PERSONAL_DOOR_MATERIAL = Material.DARK_OAK_DOOR;
	public static final Material PUBLIC_DOOR_MATERIAL = Material.PALE_OAK_DOOR;
	public static final Material PAIR_TRAPDOOR_MATERIAL = Material.OAK_TRAPDOOR;
	public static final Material PERSONAL_TRAPDOOR_MATERIAL = Material.DARK_OAK_TRAPDOOR;
	public static final Material PUBLIC_TRAPDOOR_MATERIAL = Material.PALE_OAK_TRAPDOOR;

	private final List<ItemStack> wormholeRunes;
	private final DoorItemPdcCodec codec;
	private final EnumMap<DoorCraftProduct, NamespacedKey> productRecipeKeys;
	private final NamespacedKey doorSkinRecipeKey;
	private final NamespacedKey trapdoorSkinRecipeKey;

	public DoorItemService(Plugin plugin, ItemStack exactWormholeRune)
	{
		Objects.requireNonNull(plugin, "plugin");
		Objects.requireNonNull(exactWormholeRune, "exactWormholeRune");
		if(exactWormholeRune.getType().isAir())
		{
			throw new IllegalArgumentException("Wormhole Rune cannot be air");
		}

		wormholeRunes = new CopyOnWriteArrayList<ItemStack>();
		acceptWormholeRune(exactWormholeRune);
		codec = new DoorItemPdcCodec(WormholesPlatform.pluginNamespace(plugin));
		productRecipeKeys = new EnumMap<>(DoorCraftProduct.class);
		for(DoorCraftProduct product : DoorCraftProduct.values())
		{
			productRecipeKeys.put(product, new NamespacedKey(plugin, product.recipeName()));
		}
		doorSkinRecipeKey = new NamespacedKey(plugin, "dimensional_door_skin");
		trapdoorSkinRecipeKey = new NamespacedKey(plugin, "dimensional_trapdoor_skin");
	}

	public NamespacedKey recipeKey(DoorCraftProduct product)
	{
		return productRecipeKeys.get(Objects.requireNonNull(product, "product"));
	}

	public ItemStack createPairKit()
	{
		return createPairKit(UUID.randomUUID());
	}

	public ItemStack createPairKit(UUID kitId)
	{
		return createPairKit(kitId, DoorForm.DOOR);
	}

	public ItemStack createPairKit(DoorForm form)
	{
		return createPairKit(UUID.randomUUID(), form);
	}

	public ItemStack createPairKit(UUID kitId, DoorForm form)
	{
		Objects.requireNonNull(kitId, "kitId");
		Objects.requireNonNull(form, "form");
		ItemStack item = styledItem(
			PAIR_KIT_MATERIAL,
			form == DoorForm.TRAPDOOR
				? WormholesMessages.ITEM_ENTANGLED_TRAPDOOR_PAIR
				: WormholesMessages.ITEM_ENTANGLED_PAIR,
			MessageArgs.empty());
		ItemMeta meta = item.getItemMeta();
		codec.encodePairKit(meta.getPersistentDataContainer(), kitId, form);
		item.setItemMeta(meta);
		return item;
	}

	public ItemStack createPersonalDoor()
	{
		return createPersonalDoor(DoorForm.DOOR);
	}

	public ItemStack createPersonalDoor(DoorForm form)
	{
		return createDoor(DoorItemIdentity.newPersonal(form));
	}

	public ItemStack createPublicDoor()
	{
		return createPublicDoor(DoorForm.DOOR);
	}

	public ItemStack createPublicDoor(DoorForm form)
	{
		return createDoor(DoorItemIdentity.newPublic(form));
	}

	public ItemStack createReturnDoor(UUID spaceId)
	{
		return createDoor(DoorItemIdentity.newReturn(Objects.requireNonNull(spaceId, "spaceId")));
	}

	public ItemStack createDoor(DoorItemIdentity identity)
	{
		Objects.requireNonNull(identity, "identity");
		return createDoor(identity, defaultMaterial(identity.kind(), identity.form()));
	}

	public ItemStack createDoor(DoorItemIdentity identity, Material material)
	{
		Objects.requireNonNull(identity, "identity");
		if(!DoorSkin.isSupportedSkin(Objects.requireNonNull(material, "material"), identity.form()))
		{
			throw new IllegalArgumentException(
				"Dimensional-door skins must be supported " + identity.form() + " materials");
		}
		boolean trapdoor = identity.isTrapdoor();
		ItemStack item = switch(identity.kind())
		{
			case PAIR -> styledItem(
				material,
				trapdoor ? WormholesMessages.ITEM_PAIRED_TRAPDOOR : WormholesMessages.ITEM_PAIRED_DOOR,
				WormholesLocalization.args(
					MessageArgument.untrusted("endpoint", identity.pairEndpoint().name()),
					MessageArgument.untrusted("other", identity.pairEndpoint().other().name())));
			case PERSONAL -> styledItem(
				material,
				trapdoor ? WormholesMessages.ITEM_PERSONAL_TRAPDOOR : WormholesMessages.ITEM_PERSONAL_DOOR,
				MessageArgs.empty());
			case PUBLIC -> styledItem(
				material,
				trapdoor ? WormholesMessages.ITEM_PUBLIC_TRAPDOOR : WormholesMessages.ITEM_PUBLIC_DOOR,
				MessageArgs.empty());
			case RETURN -> styledItem(
				material,
				WormholesMessages.ITEM_RETURN_DOOR,
				MessageArgs.empty());
		};

		ItemMeta meta = item.getItemMeta();
		codec.encodeIdentity(meta.getPersistentDataContainer(), identity);
		item.setItemMeta(meta);
		return item;
	}

	public Optional<DoorItemIdentity> decodeDoor(ItemStack item)
	{
		return decodeDoorIdentity(item)
			.filter(identity -> DoorSkin.isSupportedSkin(item.getType(), identity.form()));
	}

	public Optional<DoorItemIdentity> decodeDoorIdentity(ItemStack item)
	{
		if(item == null || item.getAmount() != 1)
		{
			return Optional.empty();
		}
		return decodeStoredIdentity(item);
	}

	private Optional<DoorItemIdentity> decodeStoredIdentity(ItemStack item)
	{
		if(item == null || !DoorSkin.isDoorLike(item.getType()) || !item.hasItemMeta())
		{
			return Optional.empty();
		}
		return codec.decodeIdentity(item.getItemMeta().getPersistentDataContainer());
	}

	public Optional<UUID> pairKitId(ItemStack item)
	{
		return pairKit(item).map(DoorItemPdcCodec.PairKit::kitId);
	}

	public Optional<DoorItemPdcCodec.PairKit> pairKit(ItemStack item)
	{
		if(item == null || item.getType() != PAIR_KIT_MATERIAL || item.getAmount() != 1 || !item.hasItemMeta())
		{
			return Optional.empty();
		}
		return codec.decodePairKit(item.getItemMeta().getPersistentDataContainer());
	}

	/**
	 * Produces deterministic endpoint identities for the kit. Replaying or
	 * creative-copying one kit therefore produces duplicate identities rather
	 * than minting additional independent pairs.
	 */
	public Optional<PairKitContents> unpackPairKit(ItemStack kit)
	{
		return pairKit(kit).map(stamp ->
		{
			DoorPairIdentity pair = pairIdentityForKit(stamp.kitId());
			return new PairKitContents(
				stamp.kitId(),
				pair,
				createDoor(endpointIdentity(pair, PairEndpoint.A, stamp.form())),
				createDoor(endpointIdentity(pair, PairEndpoint.B, stamp.form())));
		});
	}

	private static DoorItemIdentity endpointIdentity(DoorPairIdentity pair, PairEndpoint endpoint, DoorForm form)
	{
		return DoorItemIdentity.paired(pair.itemId(endpoint), pair.pairId(), endpoint, form);
	}

	public static DoorPairIdentity pairIdentityForKit(UUID kitId)
	{
		Objects.requireNonNull(kitId, "kitId");
		return new DoorPairIdentity(
			derivedId(kitId, "pair"),
			derivedId(kitId, "endpoint-a"),
			derivedId(kitId, "endpoint-b"));
	}

	public boolean registerRecipes()
	{
		unregisterRecipes();
		boolean pairAdded = WormholesPlatform.addRecipe(pairKitRecipe(), false);
		boolean personalAdded = WormholesPlatform.addRecipe(personalDoorRecipe(), false);
		boolean publicAdded = WormholesPlatform.addRecipe(publicDoorRecipe(), true);
		boolean skinAdded = WormholesPlatform.addRecipe(doorSkinRecipe(), false);
		boolean trapdoorPairAdded = WormholesPlatform.addRecipe(trapdoorPairKitRecipe(), false);
		boolean personalTrapdoorAdded = WormholesPlatform.addRecipe(personalTrapdoorRecipe(), false);
		boolean publicTrapdoorAdded = WormholesPlatform.addRecipe(publicTrapdoorRecipe(), true);
		boolean trapdoorSkinAdded = WormholesPlatform.addRecipe(trapdoorSkinRecipe(), false);
		return pairAdded && personalAdded && publicAdded && skinAdded
			&& trapdoorPairAdded && personalTrapdoorAdded && publicTrapdoorAdded && trapdoorSkinAdded;
	}

	public void acceptWormholeRune(ItemStack exactWormholeRune)
	{
		Objects.requireNonNull(exactWormholeRune, "exactWormholeRune");
		if(exactWormholeRune.getType().isAir())
		{
			throw new IllegalArgumentException("Wormhole Rune cannot be air");
		}
		ItemStack normalized = exactWormholeRune.clone();
		normalized.setAmount(1);
		for(ItemStack accepted : wormholeRunes)
		{
			if(accepted.isSimilar(normalized))
			{
				return;
			}
		}
		wormholeRunes.add(normalized);
	}

	public void unregisterRecipes()
	{
		WormholesPlatform.removeRecipe(recipeKey(DoorCraftProduct.PAIR_KIT), false);
		WormholesPlatform.removeRecipe(recipeKey(DoorCraftProduct.PERSONAL_DOOR), false);
		WormholesPlatform.removeRecipe(recipeKey(DoorCraftProduct.PUBLIC_DOOR), true);
		WormholesPlatform.removeRecipe(doorSkinRecipeKey, false);
		WormholesPlatform.removeRecipe(recipeKey(DoorCraftProduct.TRAPDOOR_PAIR_KIT), false);
		WormholesPlatform.removeRecipe(recipeKey(DoorCraftProduct.PERSONAL_TRAPDOOR), false);
		WormholesPlatform.removeRecipe(recipeKey(DoorCraftProduct.PUBLIC_TRAPDOOR), true);
		WormholesPlatform.removeRecipe(trapdoorSkinRecipeKey, false);
	}

	public ShapedRecipe pairKitRecipe()
	{
		return new ShapedRecipe(recipeKey(DoorCraftProduct.PAIR_KIT), craftTemplate(DoorCraftProduct.PAIR_KIT))
			.shape("EDE", "ORO", " D ")
			.setIngredient('E', Material.ENDER_EYE)
			.setIngredient('D', creationDoorIngredient())
			.setIngredient('O', Material.OBSIDIAN)
			.setIngredient('R', wormholeRuneChoice());
	}

	public ShapedRecipe personalDoorRecipe()
	{
		return new ShapedRecipe(recipeKey(DoorCraftProduct.PERSONAL_DOOR), craftTemplate(DoorCraftProduct.PERSONAL_DOOR))
			.shape(" R ", "CDE")
			.setIngredient('R', wormholeRuneChoice())
			.setIngredient('C', Material.RECOVERY_COMPASS)
			.setIngredient('D', creationDoorIngredient())
			.setIngredient('E', Material.ENDER_CHEST);
	}

	public ShapedRecipe publicDoorRecipe()
	{
		return new ShapedRecipe(recipeKey(DoorCraftProduct.PUBLIC_DOOR), craftTemplate(DoorCraftProduct.PUBLIC_DOOR))
			.shape("RDR", " E ", " L ")
			.setIngredient('R', wormholeRuneChoice())
			.setIngredient('D', creationDoorIngredient())
			.setIngredient('E', Material.ENDER_CHEST)
			.setIngredient('L', Material.LODESTONE);
	}

	public ShapedRecipe trapdoorPairKitRecipe()
	{
		return new ShapedRecipe(recipeKey(DoorCraftProduct.TRAPDOOR_PAIR_KIT), craftTemplate(DoorCraftProduct.TRAPDOOR_PAIR_KIT))
			.shape("EDE", "ORO", " D ")
			.setIngredient('E', Material.ENDER_EYE)
			.setIngredient('D', creationTrapdoorIngredient())
			.setIngredient('O', Material.OBSIDIAN)
			.setIngredient('R', wormholeRuneChoice());
	}

	public ShapedRecipe personalTrapdoorRecipe()
	{
		return new ShapedRecipe(recipeKey(DoorCraftProduct.PERSONAL_TRAPDOOR), craftTemplate(DoorCraftProduct.PERSONAL_TRAPDOOR))
			.shape(" R ", "CDE")
			.setIngredient('R', wormholeRuneChoice())
			.setIngredient('C', Material.RECOVERY_COMPASS)
			.setIngredient('D', creationTrapdoorIngredient())
			.setIngredient('E', Material.ENDER_CHEST);
	}

	public ShapedRecipe publicTrapdoorRecipe()
	{
		return new ShapedRecipe(recipeKey(DoorCraftProduct.PUBLIC_TRAPDOOR), craftTemplate(DoorCraftProduct.PUBLIC_TRAPDOOR))
			.shape("RDR", " E ", " L ")
			.setIngredient('R', wormholeRuneChoice())
			.setIngredient('D', creationTrapdoorIngredient())
			.setIngredient('E', Material.ENDER_CHEST)
			.setIngredient('L', Material.LODESTONE);
	}

	private static RecipeChoice.MaterialChoice creationDoorIngredient()
	{
		return new RecipeChoice.MaterialChoice(DoorSkin.doorMaterials());
	}

	/** Only hand-openable trapdoors can become dimensional trapdoors. */
	private static RecipeChoice.MaterialChoice creationTrapdoorIngredient()
	{
		return new RecipeChoice.MaterialChoice(DoorSkin.playerOperableTrapdoorMaterials());
	}

	private RecipeChoice.ExactChoice wormholeRuneChoice()
	{
		return new RecipeChoice.ExactChoice(List.copyOf(wormholeRunes));
	}

	public ShapelessRecipe doorSkinRecipe()
	{
		ItemStack template = styledItem(
			PAIR_DOOR_MATERIAL,
			WormholesMessages.ITEM_DOOR_SKIN,
			MessageArgs.empty());
		return new ShapelessRecipe(doorSkinRecipeKey, template)
			.addIngredient(new RecipeChoice.MaterialChoice(DoorSkin.doorMaterials()))
			.addIngredient(new RecipeChoice.MaterialChoice(DoorSkin.doorMaterials()));
	}

	public ShapelessRecipe trapdoorSkinRecipe()
	{
		ItemStack template = styledItem(
			PAIR_TRAPDOOR_MATERIAL,
			WormholesMessages.ITEM_TRAPDOOR_SKIN,
			MessageArgs.empty());
		return new ShapelessRecipe(trapdoorSkinRecipeKey, template)
			.addIngredient(new RecipeChoice.MaterialChoice(DoorSkin.trapdoorMaterials()))
			.addIngredient(new RecipeChoice.MaterialChoice(DoorSkin.playerOperableTrapdoorMaterials()));
	}

	public boolean isDoorSkinRecipe(Recipe recipe)
	{
		if(!(recipe instanceof Keyed keyed))
		{
			return false;
		}
		return doorSkinRecipeKey.equals(keyed.getKey()) || trapdoorSkinRecipeKey.equals(keyed.getKey());
	}

	public boolean isDoorRecipe(Recipe recipe)
	{
		return isDoorSkinRecipe(recipe) || productFor(recipe).isPresent();
	}

	public Optional<ItemStack> skinCraftResult(ItemStack[] matrix)
	{
		Objects.requireNonNull(matrix, "matrix");
		ArrayList<DoorSkinRecipe.Ingredient> ingredients = new ArrayList<>(2);
		for(ItemStack item : matrix)
		{
			if(item == null || item.getType().isAir())
			{
				continue;
			}
			Optional<DoorItemIdentity> identity = decodeStoredIdentity(item);
			if(identity.isPresent() && item.getAmount() != 1)
			{
				return Optional.empty();
			}
			ingredients.add(new DoorSkinRecipe.Ingredient(
				item.getType(),
				identity.orElse(null)));
		}
		return DoorSkinRecipe.resolve(ingredients)
			.map(result -> createDoor(result.identity(), result.material()));
	}

	/**
	 * Mints one unique result at the actual click. Shift crafting is cancelled
	 * so Bukkit never duplicates a single identity across a bulk result.
	 */
	public CraftHookResult handleCraft(CraftItemEvent event)
	{
		Objects.requireNonNull(event, "event");
		if(isDoorSkinRecipe(event.getRecipe()))
		{
			return handleSkinCraft(event);
		}
		Optional<DoorCraftProduct> product = productFor(event.getRecipe());
		if(product.isEmpty())
		{
			return CraftHookResult.NOT_A_DOOR_RECIPE;
		}
		if(event.isCancelled())
		{
			return CraftHookResult.ALREADY_CANCELLED;
		}
		if(event.isShiftClick())
		{
			event.setCancelled(true);
			return CraftHookResult.SHIFT_CRAFT_BLOCKED;
		}

		event.setCurrentItem(mint(product.get()));
		return CraftHookResult.IDENTITY_MINTED;
	}

	public CraftHookResult handleCrafterCraft(Recipe recipe)
	{
		Objects.requireNonNull(recipe, "recipe");
		return crafterCraftResult(isDoorRecipe(recipe));
	}

	static CraftHookResult crafterCraftResult(boolean doorRecipe)
	{
		if(doorRecipe)
		{
			return CraftHookResult.CRAFTER_BLOCKED;
		}
		return CraftHookResult.NOT_A_DOOR_RECIPE;
	}

	private CraftHookResult handleSkinCraft(CraftItemEvent event)
	{
		if(event.isCancelled())
		{
			return CraftHookResult.ALREADY_CANCELLED;
		}
		Optional<ItemStack> result = skinCraftResult(event.getInventory().getMatrix());
		if(result.isEmpty())
		{
			event.setCancelled(true);
			event.setCurrentItem(null);
			return CraftHookResult.INVALID_SKIN_RECIPE;
		}
		if(event.isShiftClick())
		{
			event.setCancelled(true);
			return CraftHookResult.SHIFT_CRAFT_BLOCKED;
		}
		event.setCurrentItem(result.get());
		return CraftHookResult.SKIN_CHANGED;
	}

	public Optional<DoorCraftProduct> productFor(Recipe recipe)
	{
		if(!(recipe instanceof Keyed keyed))
		{
			return Optional.empty();
		}
		NamespacedKey key = keyed.getKey();
		// Name match alone is not enough: another plugin may own the same recipe name.
		return DoorCraftProduct.forRecipeName(key.getKey()).filter(product -> recipeKey(product).equals(key));
	}

	public DoorItemPdcCodec codec()
	{
		return codec;
	}

	public ItemStack mint(DoorCraftProduct product)
	{
		Objects.requireNonNull(product, "product");
		DoorForm form = product.form();
		return switch(product.kind())
		{
			case PAIR -> createPairKit(form);
			case PERSONAL -> createPersonalDoor(form);
			case PUBLIC -> createPublicDoor(form);
			case RETURN -> throw new IllegalArgumentException("return doors are never crafted");
		};
	}

	private ItemStack craftTemplate(DoorCraftProduct product)
	{
		ItemStack template = switch(product)
		{
			case PAIR_KIT -> styledItem(
				PAIR_KIT_MATERIAL,
				WormholesMessages.ITEM_ENTANGLED_PAIR_RECIPE,
				MessageArgs.empty());
			case PERSONAL_DOOR -> styledItem(
				PERSONAL_DOOR_MATERIAL,
				WormholesMessages.ITEM_PERSONAL_DOOR_RECIPE,
				MessageArgs.empty());
			case PUBLIC_DOOR -> styledItem(
				PUBLIC_DOOR_MATERIAL,
				WormholesMessages.ITEM_PUBLIC_DOOR_RECIPE,
				MessageArgs.empty());
			case TRAPDOOR_PAIR_KIT -> styledItem(
				PAIR_KIT_MATERIAL,
				WormholesMessages.ITEM_ENTANGLED_TRAPDOOR_PAIR_RECIPE,
				MessageArgs.empty());
			case PERSONAL_TRAPDOOR -> styledItem(
				PERSONAL_TRAPDOOR_MATERIAL,
				WormholesMessages.ITEM_PERSONAL_TRAPDOOR_RECIPE,
				MessageArgs.empty());
			case PUBLIC_TRAPDOOR -> styledItem(
				PUBLIC_TRAPDOOR_MATERIAL,
				WormholesMessages.ITEM_PUBLIC_TRAPDOOR_RECIPE,
				MessageArgs.empty());
		};
		ItemMeta meta = template.getItemMeta();
		codec.encodeCraftProduct(meta.getPersistentDataContainer(), product);
		template.setItemMeta(meta);
		return template;
	}

	private static ItemStack styledItem(
		Material material,
		LinesKey key,
		MessageArgs arguments)
	{
		ItemStack item = WormholesPlatform.itemStack(material);
		ItemMeta meta = item.getItemMeta();
		// This project relocates Adventure; legacy string metadata keeps the
		// server-owned ItemMeta ABI unrelocated while still rendering cleanly.
		List<String> lines = Wormholes.text().legacyLines(key, arguments);
		meta.setDisplayName(lines.getFirst());
		meta.setLore(lines.subList(1, lines.size()));
		meta.setMaxStackSize(1);
		meta.setEnchantmentGlintOverride(true);
		item.setItemMeta(meta);
		return item;
	}

	public static Material defaultMaterial(DoorKind kind)
	{
		return defaultMaterial(kind, DoorForm.DOOR);
	}

	public static Material defaultMaterial(DoorKind kind, DoorForm form)
	{
		Objects.requireNonNull(kind, "kind");
		if(Objects.requireNonNull(form, "form") == DoorForm.TRAPDOOR)
		{
			return switch(kind)
			{
				case PAIR -> PAIR_TRAPDOOR_MATERIAL;
				case PERSONAL -> PERSONAL_TRAPDOOR_MATERIAL;
				case PUBLIC -> PUBLIC_TRAPDOOR_MATERIAL;
				case RETURN -> throw new IllegalArgumentException("return doors are always DOOR form");
			};
		}
		return switch(kind)
		{
			case PAIR -> PAIR_DOOR_MATERIAL;
			case PERSONAL -> PERSONAL_DOOR_MATERIAL;
			case PUBLIC -> PUBLIC_DOOR_MATERIAL;
			case RETURN -> PocketMaterials.returnDoorMaterialOrDefault(Settings.POCKET_SHELL.returnDoorMaterial());
		};
	}

	private static UUID derivedId(UUID kitId, String role)
	{
		String seed = "wormholes:door-pair:v1:" + kitId + ':' + role;
		return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
	}

	public NamespacedKey pairKitRecipeKey()
	{
		return recipeKey(DoorCraftProduct.PAIR_KIT);
	}

	public NamespacedKey personalDoorRecipeKey()
	{
		return recipeKey(DoorCraftProduct.PERSONAL_DOOR);
	}

	public NamespacedKey publicDoorRecipeKey()
	{
		return recipeKey(DoorCraftProduct.PUBLIC_DOOR);
	}

	public NamespacedKey doorSkinRecipeKey()
	{
		return doorSkinRecipeKey;
	}

	public NamespacedKey trapdoorPairKitRecipeKey()
	{
		return recipeKey(DoorCraftProduct.TRAPDOOR_PAIR_KIT);
	}

	public NamespacedKey personalTrapdoorRecipeKey()
	{
		return recipeKey(DoorCraftProduct.PERSONAL_TRAPDOOR);
	}

	public NamespacedKey publicTrapdoorRecipeKey()
	{
		return recipeKey(DoorCraftProduct.PUBLIC_TRAPDOOR);
	}

	public NamespacedKey trapdoorSkinRecipeKey()
	{
		return trapdoorSkinRecipeKey;
	}

	public enum CraftHookResult
	{
		NOT_A_DOOR_RECIPE,
		ALREADY_CANCELLED,
		SHIFT_CRAFT_BLOCKED,
		IDENTITY_MINTED,
		INVALID_SKIN_RECIPE,
		SKIN_CHANGED,
		CRAFTER_BLOCKED
	}

	public record PairKitContents(
		UUID kitId,
		DoorPairIdentity pairIdentity,
		ItemStack endpointA,
		ItemStack endpointB)
	{
		public PairKitContents
		{
			Objects.requireNonNull(kitId, "kitId");
			Objects.requireNonNull(pairIdentity, "pairIdentity");
			endpointA = Objects.requireNonNull(endpointA, "endpointA").clone();
			endpointB = Objects.requireNonNull(endpointB, "endpointB").clone();
		}

		@Override
		public ItemStack endpointA()
		{
			return endpointA.clone();
		}

		@Override
		public ItemStack endpointB()
		{
			return endpointB.clone();
		}
	}
}
