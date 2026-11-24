package com.thecoberto.beverages;

import java.util.ArrayList;
import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;

/**
 * Paper can't add items, so drinks are potions with a colour, name and tag, the Mug
 * is a bowl, and the machines are blocks with a tag. Ids match the Bedrock add-on.
 */
final class Items {
	static final String MUG = "mug";
	static final String VENDING_MACHINE = "vending_machine";
	static final String BEVERAGE_FOUNTAIN = "beverage_fountain";

	static NamespacedKey KEY;

	private Items() {
	}

	static void init(Plugin plugin) {
		KEY = new NamespacedKey(plugin, "item");
	}

	/** This item's Beverages id, or null. */
	static String idOf(ItemStack stack) {
		if (stack == null || !stack.hasItemMeta()) return null;
		return stack.getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
	}

	static Drink drinkOf(ItemStack stack) {
		String id = idOf(stack);
		return id == null ? null : Drink.byId(id);
	}

	static boolean isMug(ItemStack stack) {
		return MUG.equals(idOf(stack));
	}

	static ItemStack drink(Drink drink) {
		ItemStack stack = new ItemStack(Material.POTION);
		PotionMeta meta = (PotionMeta) stack.getItemMeta();
		meta.setColor(Color.fromRGB(drink.color));
		// Potions name themselves from their contents and ignore item_name, so this is a custom name.
		meta.displayName(Component.text(drink.displayName, drink.nameColor).decoration(TextDecoration.ITALIC, false));
		List<Component> lore = new ArrayList<>();
		for (PotionEffect e : drink.effects) {
			if (e.getDuration() < 20) continue;
			lore.add(line(pretty(e.getType().getKey().getKey()) + level(e.getAmplifier()) + " (" + time(e.getDuration() / 20) + ")", NamedTextColor.BLUE));
		}
		switch (drink) {
			case SWEET_BERRY_SMOOTHIE -> lore.add(line("Fills your hunger", NamedTextColor.BLUE));
			case HOT_COCOA -> {
				lore.add(line("Clears Slowness", NamedTextColor.BLUE));
				lore.add(line("Regeneration II (0:20) if you're cold", NamedTextColor.BLUE));
			}
			case GLOW_BERRY_JUICE -> lore.add(line("Sense nearby monsters (0:30)", NamedTextColor.BLUE));
			default -> { }
		}
		if (drink.mug) lore.add(line("Gives the Mug back", NamedTextColor.GRAY));
		meta.lore(lore);
		meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
		// Picks the drink's texture in the server resource pack (4101 and up). Without the pack it looks like a potion.
		meta.setCustomModelData(4101 + drink.ordinal());
		meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, drink.id());
		stack.setItemMeta(meta);
		return stack;
	}

	static ItemStack mug(int amount) {
		return tagged(Material.BOWL, amount, MUG, 4201, Component.text("Mug"), "For Coffee and Hot Cocoa");
	}

	static ItemStack machine(String id, Material block) {
		return id.equals(VENDING_MACHINE)
				? tagged(block, 1, id, 4202, Component.text("Soda Vending Machine", NamedTextColor.RED), "Place it, then use it to buy Soda")
				: tagged(block, 1, id, 4203, Component.text("Beverage Fountain", NamedTextColor.AQUA), "Place it, then use it to pour drinks");
	}

	private static ItemStack tagged(Material material, int amount, String id, int model, Component name, String info) {
		ItemStack stack = new ItemStack(material, amount);
		ItemMeta meta = stack.getItemMeta();
		meta.itemName(name);
		meta.lore(List.of(line(info, NamedTextColor.GRAY)));
		meta.setCustomModelData(model);
		meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, id);
		stack.setItemMeta(meta);
		return stack;
	}

	static Component line(String text, NamedTextColor color) {
		return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
	}

	private static String pretty(String key) {
		StringBuilder out = new StringBuilder();
		for (String word : key.split("_")) {
			if (!out.isEmpty()) out.append(' ');
			out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return out.toString();
	}

	private static String level(int amplifier) {
		String[] roman = {"", " II", " III", " IV", " V"};
		return amplifier < roman.length ? roman[Math.max(0, amplifier)] : " " + (amplifier + 1);
	}

	private static String time(int seconds) {
		return seconds / 60 + ":" + String.format("%02d", seconds % 60);
	}
}
