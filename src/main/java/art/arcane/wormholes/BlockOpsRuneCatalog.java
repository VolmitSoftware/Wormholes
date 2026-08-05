package art.arcane.wormholes;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.portal.PortalType;

final class BlockOpsRuneCatalog
{
	private static final List<String> RECIPE_NAMES = List.of("portal_wand", "portal_rune", "wormhole_rune");

	private final List<ItemStack> acceptedWandTemplates = new CopyOnWriteArrayList<ItemStack>();
	private final List<ItemStack> acceptedPortalRuneTemplates = new CopyOnWriteArrayList<ItemStack>();
	private final List<ItemStack> acceptedWormholeRuneTemplates = new CopyOnWriteArrayList<ItemStack>();
	private final List<ItemStack> acceptedGatewayRuneTemplates = new CopyOnWriteArrayList<ItemStack>();
	private volatile ItemStack wandTemplate;
	private volatile ItemStack portalRuneTemplate;
	private volatile ItemStack wormholeRuneTemplate;
	private volatile ItemStack gatewayRuneTemplate;

	BlockOpsRuneCatalog()
	{
		WormholesLocalization english = WormholesLocalization.english();
		wandTemplate = buildTemplate(Material.BLAZE_ROD, WormholesMessages.ITEM_PORTAL_WAND, english);
		portalRuneTemplate = buildTemplate(Material.PRISMARINE, WormholesMessages.ITEM_PORTAL_RUNE, english);
		wormholeRuneTemplate = buildTemplate(Material.DARK_PRISMARINE, WormholesMessages.ITEM_WORMHOLE_RUNE, english);
		gatewayRuneTemplate = buildTemplate(Material.BLACK_STAINED_GLASS, WormholesMessages.ITEM_GATEWAY_RUNE, english);
		refreshLocalizedTemplates();
		registerRecipes();
	}

	private static ItemStack buildTemplate(Material material, LinesKey key, WormholesLocalization localization)
	{
		ItemStack is = new ItemStack(material);
		ItemMeta meta = is.getItemMeta();
		meta.addEnchant(Enchantment.INFINITY, 1, true);
		meta.setDisplayName(localization.legacyLines(key).getFirst());
		is.setItemMeta(meta);

		return is;
	}

	void onLanguageReload()
	{
		refreshLocalizedTemplates();
		registerRecipes();
	}

	private void refreshLocalizedTemplates()
	{
		rememberTemplate(acceptedWandTemplates, wandTemplate);
		rememberTemplate(acceptedPortalRuneTemplates, portalRuneTemplate);
		rememberTemplate(acceptedWormholeRuneTemplates, wormholeRuneTemplate);
		rememberTemplate(acceptedGatewayRuneTemplates, gatewayRuneTemplate);
		WormholesLocalization localization = Wormholes.text();
		wandTemplate = buildTemplate(Material.BLAZE_ROD, WormholesMessages.ITEM_PORTAL_WAND, localization);
		portalRuneTemplate = buildTemplate(Material.PRISMARINE, WormholesMessages.ITEM_PORTAL_RUNE, localization);
		wormholeRuneTemplate = buildTemplate(Material.DARK_PRISMARINE, WormholesMessages.ITEM_WORMHOLE_RUNE, localization);
		gatewayRuneTemplate = buildTemplate(Material.BLACK_STAINED_GLASS, WormholesMessages.ITEM_GATEWAY_RUNE, localization);
		rememberTemplate(acceptedWandTemplates, wandTemplate);
		rememberTemplate(acceptedPortalRuneTemplates, portalRuneTemplate);
		rememberTemplate(acceptedWormholeRuneTemplates, wormholeRuneTemplate);
		rememberTemplate(acceptedGatewayRuneTemplates, gatewayRuneTemplate);
	}

	private static void rememberTemplate(List<ItemStack> acceptedTemplates, ItemStack template)
	{
		for(ItemStack acceptedTemplate : acceptedTemplates)
		{
			if(acceptedTemplate.isSimilar(template))
			{
				return;
			}
		}
		acceptedTemplates.add(template.clone());
	}

	private boolean matchesAnyTemplate(ItemStack item, List<ItemStack> acceptedTemplates)
	{
		for(ItemStack acceptedTemplate : acceptedTemplates)
		{
			if(isTemplateMatch(item, acceptedTemplate))
			{
				return true;
			}
		}
		return false;
	}

	private boolean isTemplateMatch(ItemStack item, ItemStack template)
	{
		return item != null && item.getType() == template.getType() && item.isSimilar(template);
	}

	PortalType placedRuneType(ItemStack inHand)
	{
		if(matchesAnyTemplate(inHand, acceptedPortalRuneTemplates))
		{
			return PortalType.PORTAL;
		}

		if(matchesAnyTemplate(inHand, acceptedWormholeRuneTemplates))
		{
			return PortalType.WORMHOLE;
		}

		if(matchesAnyTemplate(inHand, acceptedGatewayRuneTemplates))
		{
			return PortalType.GATEWAY;
		}

		return null;
	}

	void registerRecipes()
	{
		unregisterAllRecipes();

		//@builder
		registerRecipe(new ShapedRecipe(new NamespacedKey(Wormholes.instance, "portal_wand"), getWand())
				.shape("d d", " r ", " d ")
				.setIngredient('d', Material.GLOWSTONE_DUST)
				.setIngredient('r', Material.BLAZE_ROD));
		registerRecipe(new ShapedRecipe(new NamespacedKey(Wormholes.instance, "portal_rune"), getPortalRune(4))
				.shape("pbp", "bdb", "pbp")
				.setIngredient('d', Material.BLAZE_POWDER)
				.setIngredient('b', Material.PRISMARINE_CRYSTALS)
				.setIngredient('p', Material.ENDER_PEARL));
		registerRecipe(new ShapedRecipe(new NamespacedKey(Wormholes.instance, "wormhole_rune"), getWormholeRune(4))
				.shape("pbp", "bdb", "pbp")
				.setIngredient('d', Material.NETHER_STAR)
				.setIngredient('b', Material.PRISMARINE_SHARD)
				.setIngredient('p', Material.ENDER_EYE));
		//@done
	}

	private void registerRecipe(Recipe r)
	{
		if(!(r instanceof Keyed keyed))
		{
			return;
		}
		try
		{
			if(WormholesPlatform.addRecipe(r, false))
			{
				Wormholes.instance.getLogger().info("Registered Recipe: " + keyed.getKey());
				return;
			}
			Wormholes.instance.getLogger().warning("Recipe could not be registered: " + keyed.getKey());
		}
		catch(RuntimeException ex)
		{
			Wormholes.instance.getLogger().log(Level.WARNING,
				"Recipe registration failed: " + keyed.getKey(), ex);
		}
	}

	void unregisterAllRecipes()
	{
		for(String recipeName : RECIPE_NAMES)
		{
			NamespacedKey recipeKey = new NamespacedKey(Wormholes.instance, recipeName);
			if(WormholesPlatform.removeRecipe(recipeKey, false))
			{
				Wormholes.instance.getLogger().info("Unregistering Recipe: " + recipeKey);
			}
		}
	}

	boolean isPortalTool(ItemStack item)
	{
		return isWand(item) || isPortalRune(item);
	}

	boolean isWand(ItemStack item)
	{
		return matchesAnyTemplate(item, acceptedWandTemplates);
	}

	boolean isPortalRune(ItemStack item)
	{
		return matchesAnyTemplate(item, acceptedPortalRuneTemplates)
				|| matchesAnyTemplate(item, acceptedWormholeRuneTemplates)
				|| matchesAnyTemplate(item, acceptedGatewayRuneTemplates);
	}

	ItemStack getWand()
	{
		return wandTemplate.clone();
	}

	ItemStack getPortalRune(int c)
	{
		ItemStack is = portalRuneTemplate.clone();
		is.setAmount(c);

		return is;
	}

	ItemStack getWormholeRune(int c)
	{
		ItemStack is = wormholeRuneTemplate.clone();
		is.setAmount(c);

		return is;
	}

	ItemStack getGatewayRune(int c)
	{
		ItemStack is = gatewayRuneTemplate.clone();
		is.setAmount(c);

		return is;
	}

	ItemStack get(PortalType t, int stack)
	{
		switch(t)
		{
			case GATEWAY:
				return getGatewayRune(stack);
			case PORTAL:
				return getPortalRune(stack);
			case WORMHOLE:
				return getWormholeRune(stack);
			case RTP:
				return null;
		}

		return null;
	}
}
