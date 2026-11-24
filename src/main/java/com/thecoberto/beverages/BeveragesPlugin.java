package com.thecoberto.beverages;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.papermc.paper.potion.PotionMix;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

/**
 * Beverages for Paper: a port of the Bedrock add-on. Brewable and craftable
 * drinks, the Mug, and the Soda Vending Machine and Beverage Fountain.
 */
public final class BeveragesPlugin extends JavaPlugin implements Listener {
	private static final Set<Material> COLD_BLOCKS = Set.of(Material.POWDER_SNOW, Material.SNOW, Material.SNOW_BLOCK,
			Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE, Material.FROSTED_ICE);
	private static final long COLD_MEMORY_MS = 60_000; // still "cold" for a minute after leaving the snow
	private static final long RADAR_MS = 30_000;
	private static final double RADAR_RANGE = 32;

	private final Map<UUID, Long> lastCold = new HashMap<>();
	private final Map<UUID, Long> radarUntil = new HashMap<>();
	private Machines machines;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		Items.init(this);
		machines = new Machines(this);
		getServer().getPluginManager().registerEvents(this, this);
		getServer().getPluginManager().registerEvents(machines, this);
		addRecipes();
		Bukkit.getScheduler().runTaskTimer(this, this::tick, 10, 10);
	}

	// ---------- Drinking ----------

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onDrink(PlayerItemConsumeEvent event) {
		Drink drink = Items.drinkOf(event.getItem());
		if (drink == null) return;
		Player player = event.getPlayer();
		if (drink == Drink.HOT_COCOA) player.removePotionEffect(PotionEffectType.SLOWNESS);
		for (PotionEffect effect : drink.effects) player.addPotionEffect(effect);
		// Like Bedrock's food component: a drink is worth 1 hunger point.
		player.setFoodLevel(Math.min(20, player.getFoodLevel() + 1));
		switch (drink.base == null ? drink : drink.base) {
			case HASTE_POTION -> player.playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
			case SODA -> player.playSound(player, Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.6f);
			case GLOW_BERRY_JUICE -> {
				player.playSound(player, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1f);
				radarUntil.put(player.getUniqueId(), System.currentTimeMillis() + RADAR_MS);
				player.sendActionBar(Component.text("You sense nearby monsters...", NamedTextColor.YELLOW));
			}
			case HOT_COCOA -> {
				if (isCold(player)) {
					player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 20 * 20, 1));
					player.sendActionBar(Component.text("The cocoa warms you right up!", NamedTextColor.GOLD));
				}
			}
			default -> { }
		}
		// Mug drinks give the Mug back instead of a glass bottle.
		if (drink.mug) event.setReplacement(Items.mug(1));
	}

	private boolean isTouchingCold(Player player) {
		Block feet = player.getLocation().getBlock();
		return COLD_BLOCKS.contains(feet.getType()) || COLD_BLOCKS.contains(feet.getRelative(0, -1, 0).getType());
	}

	private boolean isCold(Player player) {
		if (isTouchingCold(player)) return true;
		Long last = lastCold.get(player.getUniqueId());
		return last != null && System.currentTimeMillis() - last <= COLD_MEMORY_MS;
	}

	/** Twice a second: remember who's been in the cold, and show the Glow Berry Juice radar. */
	private void tick() {
		long now = System.currentTimeMillis();
		for (Player player : Bukkit.getOnlinePlayers()) {
			if (isTouchingCold(player)) lastCold.put(player.getUniqueId(), now);
			Long until = radarUntil.get(player.getUniqueId());
			if (until == null) continue;
			if (now > until) {
				radarUntil.remove(player.getUniqueId());
				continue;
			}
			// Only the drinker sees the particles over each monster's head.
			for (Entity entity : player.getNearbyEntities(RADAR_RANGE, RADAR_RANGE, RADAR_RANGE)) {
				if (!(entity instanceof Enemy) || !(entity instanceof LivingEntity mob)) continue;
				Location head = mob.getEyeLocation().add(0, 0.7, 0);
				player.spawnParticle(Particle.END_ROD, head, 1, 0, 0, 0, 0);
			}
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		lastCold.remove(event.getPlayer().getUniqueId());
		radarUntil.remove(event.getPlayer().getUniqueId());
	}

	// ---------- Brewing and crafting ----------

	private NamespacedKey key(String name) {
		return new NamespacedKey(this, name);
	}

	private void addRecipes() {
		RecipeChoice awkward = PotionMix.createPredicateChoice(item -> item.getType() == Material.POTION
				&& item.getItemMeta() instanceof PotionMeta meta && meta.getBasePotionType() == PotionType.AWKWARD
				&& Items.idOf(item) == null);
		RecipeChoice mug = new RecipeChoice.ExactChoice(Items.mug(1));

		for (Drink drink : Drink.values()) {
			if (drink.brewedWith != null) {
				Bukkit.getPotionBrewer().addPotionMix(new PotionMix(key(drink.id() + "_brewing"), Items.drink(drink),
						awkward, new RecipeChoice.MaterialChoice(drink.brewedWith)));
			}
			if (drink.base != null) {
				ShapelessRecipe upgrade = new ShapelessRecipe(key(drink.id() + "_crafting"), Items.drink(drink));
				upgrade.addIngredient(new RecipeChoice.ExactChoice(Items.drink(drink.base)));
				upgrade.addIngredient(drink.strong ? Material.GLOWSTONE_DUST : Material.REDSTONE);
				Bukkit.addRecipe(upgrade);
			}
		}

		ShapedRecipe mugs = new ShapedRecipe(key("mug_crafting"), Items.mug(2));
		mugs.shape("B B", "BBB");
		mugs.setIngredient('B', Material.BRICK);
		Bukkit.addRecipe(mugs);

		ShapelessRecipe coffee = new ShapelessRecipe(key("coffee_crafting"), Items.drink(Drink.COFFEE));
		coffee.addIngredient(mug);
		coffee.addIngredient(2, Material.COCOA_BEANS);
		Bukkit.addRecipe(coffee);

		ShapelessRecipe cocoa = new ShapelessRecipe(key("hot_cocoa_crafting"), Items.drink(Drink.HOT_COCOA));
		cocoa.addIngredient(mug);
		cocoa.addIngredient(Material.COCOA_BEANS).addIngredient(Material.SUGAR).addIngredient(Material.MILK_BUCKET);
		Bukkit.addRecipe(cocoa);

		ShapedRecipe vending = new ShapedRecipe(key("vending_machine_crafting"), machines.item(Items.VENDING_MACHINE));
		vending.shape("III", "GSG", "IRI");
		vending.setIngredient('I', Material.IRON_INGOT).setIngredient('G', Material.GLASS_PANE)
				.setIngredient('S', new RecipeChoice.ExactChoice(Items.drink(Drink.SODA))).setIngredient('R', Material.REDSTONE);
		Bukkit.addRecipe(vending);

		ShapedRecipe fountain = new ShapedRecipe(key("beverage_fountain_crafting"), machines.item(Items.BEVERAGE_FOUNTAIN));
		fountain.shape("QMQ", "ICI", "QQQ");
		fountain.setIngredient('Q', Material.QUARTZ_BLOCK).setIngredient('M', mug)
				.setIngredient('I', Material.IRON_INGOT).setIngredient('C', Material.CAULDRON);
		Bukkit.addRecipe(fountain);
	}

	/** Drinks and Mugs only go into this plugin's recipes (a Mug is a bowl, so it would make stew). */
	@EventHandler
	public void onPrepareCraft(PrepareItemCraftEvent event) {
		Recipe recipe = event.getRecipe();
		if (recipe instanceof Keyed keyed && keyed.getKey().getNamespace().equals(getName().toLowerCase(Locale.ROOT))) return;
		for (ItemStack item : event.getInventory().getMatrix()) {
			if (Items.idOf(item) != null) {
				event.getInventory().setResult(null);
				return;
			}
		}
	}

	// ---------- Commands ----------

	private List<String> giveIds() {
		List<String> ids = new ArrayList<>();
		for (Drink d : Drink.values()) ids.add(d.id());
		ids.addAll(List.of(Items.MUG, Items.VENDING_MACHINE, Items.BEVERAGE_FOUNTAIN));
		return ids;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length < 2 || !args[0].equalsIgnoreCase("give")) return false;
		String id = args[1].toLowerCase(Locale.ROOT);
		ItemStack item;
		Drink drink = Drink.byId(id);
		if (drink != null) item = Items.drink(drink);
		else if (id.equals(Items.MUG)) item = Items.mug(1);
		else if (id.equals(Items.VENDING_MACHINE) || id.equals(Items.BEVERAGE_FOUNTAIN)) item = machines.item(id);
		else {
			sender.sendMessage(Component.text("Unknown item. Items: " + String.join(", ", giveIds()), NamedTextColor.RED));
			return true;
		}
		Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[2]) : sender instanceof Player p ? p : null;
		if (target == null) {
			sender.sendMessage(Component.text(args.length >= 3 ? "No player called " + args[2] + " is online." : "Say which player.", NamedTextColor.RED));
			return true;
		}
		target.getInventory().addItem(item).values().forEach(left -> target.getWorld().dropItem(target.getLocation(), left));
		sender.sendMessage(Component.text("Gave " + target.getName() + " " + id + "."));
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		List<String> options = new ArrayList<>();
		if (args.length == 1) options.add("give");
		else if (args.length == 2) options.addAll(giveIds());
		else if (args.length == 3) Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
		String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
		return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
	}
}
