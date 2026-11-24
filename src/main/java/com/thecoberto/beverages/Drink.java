package com.thecoberto.beverages;

import java.util.List;
import java.util.Locale;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Every drink, including the Strong (Glowstone: +1 level, half the time) and
 * Extended (Redstone: double the time) versions. Same effects as the Bedrock
 * add-on's scripts/main.js.
 */
enum Drink {
	HASTE_POTION("Haste Potion", NamedTextColor.GOLD, 0x2F55C8, false, Material.LAPIS_LAZULI, fx(PotionEffectType.HASTE, 1800, 1)),
	SODA("Soda", NamedTextColor.WHITE, 0xB86BD6, false, Material.CHORUS_FRUIT, fx(PotionEffectType.SPEED, 180, 2)),
	APPLE_CIDER("Apple Cider", NamedTextColor.WHITE, 0xD9A43A, false, Material.APPLE, fx(PotionEffectType.NIGHT_VISION, 180, 0)),
	ICED_TEA("Iced Tea", NamedTextColor.WHITE, 0x8B5A2B, false, Material.ICE, fx(PotionEffectType.FIRE_RESISTANCE, 180, 0)),
	COFFEE("Coffee", NamedTextColor.WHITE, 0x3B2416, true, null, fx(PotionEffectType.HASTE, 120, 0), fx(PotionEffectType.SPEED, 30, 0)),
	// A few ticks of Saturation tops off hunger and saturation.
	SWEET_BERRY_SMOOTHIE("Sweet Berry Smoothie", NamedTextColor.WHITE, 0xC2185B, false, Material.SWEET_BERRIES, new PotionEffect(PotionEffectType.SATURATION, 5, 0)),
	HOT_COCOA("Hot Cocoa", NamedTextColor.GOLD, 0x5D3A1A, true, null, fx(PotionEffectType.REGENERATION, 10, 0)),
	GLOW_BERRY_JUICE("Glow Berry Juice", NamedTextColor.YELLOW, 0xFFD54F, false, Material.GLOW_BERRIES),

	HASTE_POTION_STRONG(HASTE_POTION, true),
	HASTE_POTION_LONG(HASTE_POTION, false),
	SODA_STRONG(SODA, true),
	SODA_LONG(SODA, false),
	APPLE_CIDER_STRONG(APPLE_CIDER, true),
	APPLE_CIDER_LONG(APPLE_CIDER, false),
	ICED_TEA_STRONG(ICED_TEA, true),
	ICED_TEA_LONG(ICED_TEA, false),
	COFFEE_STRONG(COFFEE, true),
	COFFEE_LONG(COFFEE, false);

	final String displayName;
	final TextColor nameColor;
	final int color;
	/** Comes in a Mug, and gives the Mug back. */
	final boolean mug;
	/** Brewed from an Awkward Potion with this, or null if it's crafted. */
	final Material brewedWith;
	final List<PotionEffect> effects;
	/** The drink this is a Strong or Extended version of, or null. */
	final Drink base;
	final boolean strong;

	Drink(String displayName, TextColor nameColor, int color, boolean mug, Material brewedWith, PotionEffect... effects) {
		this.displayName = displayName;
		this.nameColor = nameColor;
		this.color = color;
		this.mug = mug;
		this.brewedWith = brewedWith;
		this.effects = List.of(effects);
		this.base = null;
		this.strong = false;
	}

	Drink(Drink base, boolean strong) {
		this.displayName = (strong ? "Strong " : "Extended ") + base.displayName;
		this.nameColor = base.nameColor;
		this.color = base.color;
		this.mug = base.mug;
		this.brewedWith = null;
		this.effects = base.effects.stream()
				.map(e -> new PotionEffect(e.getType(), strong ? e.getDuration() / 2 : e.getDuration() * 2, e.getAmplifier() + (strong ? 1 : 0)))
				.toList();
		this.base = base;
		this.strong = strong;
	}

	private static PotionEffect fx(PotionEffectType type, int seconds, int amplifier) {
		return new PotionEffect(type, seconds * 20, amplifier);
	}

	String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	static Drink byId(String id) {
		for (Drink d : values()) {
			if (d.id().equalsIgnoreCase(id)) return d;
		}
		return null;
	}
}
