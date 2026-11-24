package com.thecoberto.beverages;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.player.PlayerQuitEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Soda Vending Machine and Beverage Fountain, like the Bedrock add-on's machines.js.
 * Each placed machine keeps its own prices, saved in its chunk. Players with
 * beverages.admin get a Settings button: currency (item, Money through Vault, or
 * free), default amount, needs a Mug (fountain), and a price per drink.
 */
final class Machines implements Listener {
	private static final String ADMIN = "beverages.admin";

	private enum Type {
		VENDING(Items.VENDING_MACHINE, "Soda Vending Machine", NamedTextColor.RED, false, "money", 1, false,
				Drink.SODA, Drink.SODA_STRONG, Drink.SODA_LONG),
		FOUNTAIN(Items.BEVERAGE_FOUNTAIN, "Beverage Fountain", NamedTextColor.AQUA, true, "money", 1, true,
				Drink.SODA, Drink.COFFEE, Drink.ICED_TEA, Drink.APPLE_CIDER, Drink.HOT_COCOA,
				Drink.SWEET_BERRY_SMOOTHIE, Drink.GLOW_BERRY_JUICE, Drink.HASTE_POTION);

		final String id;
		final String title;
		final NamedTextColor color;
		final boolean allowMug;
		final String defaultCurrency;
		final int defaultAmount;
		final boolean defaultMug;
		final List<Drink> drinks;

		Type(String id, String title, NamedTextColor color, boolean allowMug, String currency, int amount, boolean mug, Drink... drinks) {
			this.id = id;
			this.title = title;
			this.color = color;
			this.allowMug = allowMug;
			this.defaultCurrency = currency;
			this.defaultAmount = amount;
			this.defaultMug = mug;
			this.drinks = List.of(drinks);
		}

		static Type byId(String id) {
			for (Type t : values()) {
				if (t.id.equals(id)) return t;
			}
			return null;
		}
	}

	/** One machine's prices. Saved as "type|currency|item|amount|mug|drink=price,...". */
	private static final class Settings {
		Type type;
		String currency;
		Material item = Material.DIAMOND;
		int amount;
		boolean needsMug;
		final Map<Drink, Integer> prices = new LinkedHashMap<>();

		static Settings defaults(Type type) {
			Settings s = new Settings();
			s.type = type;
			s.currency = type.defaultCurrency;
			s.amount = type.defaultAmount;
			s.needsMug = type.defaultMug;
			return s;
		}

		static Settings parse(String raw) {
			String[] parts = raw.split("\\|", -1);
			Type type = Type.byId(parts[0]);
			if (type == null) return null;
			Settings s = defaults(type);
			try {
				s.currency = parts[1];
				Material m = Material.matchMaterial(parts[2]);
				if (m != null && m.isItem()) s.item = m;
				s.amount = Integer.parseInt(parts[3]);
				s.needsMug = Boolean.parseBoolean(parts[4]);
				if (!parts[5].isEmpty()) {
					for (String pair : parts[5].split(",")) {
						String[] kv = pair.split("=");
						Drink d = Drink.byId(kv[0]);
						if (d != null) s.prices.put(d, Integer.parseInt(kv[1]));
					}
				}
			} catch (RuntimeException e) {
				// Damaged: keep what was read so far.
			}
			return s;
		}

		String serialize() {
			List<String> pairs = new ArrayList<>();
			prices.forEach((d, p) -> pairs.add(d.id() + "=" + p));
			return String.join("|", type.id, currency, item.getKey().getKey(), String.valueOf(amount),
					String.valueOf(needsMug), String.join(",", pairs));
		}

		int priceOf(Drink drink) {
			if (currency.equals("free")) return 0;
			Integer own = prices.get(drink);
			if (own != null) return own;
			return currency.equals("money") ? suggestedPrice(drink) : amount;
		}
	}

	/**
	 * Money price for a drink nobody priced yet, by how strong it is. Haste
	 * Potion (Haste II for 30 minutes) is the strongest.
	 */
	static int suggestedPrice(Drink drink) {
		return switch (drink) {
			case HASTE_POTION -> 5000;
			case HASTE_POTION_STRONG -> 4000; // Haste III, but half as long
			case HASTE_POTION_LONG -> 8000; // an hour
			case SODA -> 1500; // Speed III, 3 minutes
			case SODA_STRONG -> 2000;
			case SODA_LONG -> 2500;
			case ICED_TEA -> 1500; // Fire Resistance, 3 minutes
			case ICED_TEA_STRONG -> 1500;
			case ICED_TEA_LONG -> 2500;
			case COFFEE -> 1000; // Haste I for 2 minutes and a little Speed
			case COFFEE_STRONG -> 1500;
			case COFFEE_LONG -> 1750;
			case HOT_COCOA -> 1000; // Regeneration, clears Slowness
			case GLOW_BERRY_JUICE -> 1000; // senses monsters
			case APPLE_CIDER -> 750; // Night Vision, 3 minutes
			case APPLE_CIDER_STRONG -> 750;
			case APPLE_CIDER_LONG -> 1250;
			case SWEET_BERRY_SMOOTHIE -> 500; // fills hunger
		};
	}

	private record MachineMenu(Block block, boolean settings) implements InventoryHolder {
		@Override
		public Inventory getInventory() {
			return null;
		}
	}

	private final JavaPlugin plugin;
	private final Map<Type, Material> blocks = new LinkedHashMap<>();
	private final BlockSkin skin;

	Machines(JavaPlugin plugin) {
		this.plugin = plugin;
		this.skin = new BlockSkin(plugin);
		blocks.put(Type.VENDING, block("blocks.vending-machine", Material.IRON_BLOCK));
		blocks.put(Type.FOUNTAIN, block("blocks.beverage-fountain", Material.QUARTZ_PILLAR));
		// Machines placed before skins existed (or in chunks already loaded) get one now.
		Bukkit.getScheduler().runTask(plugin, () -> {
			for (World world : Bukkit.getWorlds()) {
				for (Chunk chunk : world.getLoadedChunks()) {
					if (!chunk.isEntitiesLoaded()) continue;
					List<Entity> loaded = List.of(chunk.getEntities());
					addMissingSkins(chunk, loaded);
					skin.hideLabels(loaded);
				}
			}
		});
		Bukkit.getScheduler().runTaskTimer(plugin, this::updateLabels, 20L, 4L);
	}

	// ---------- Floating names, shown while looking at the machine ----------

	/** Player -> the block they're looking at (position key), and the label shown to them. */
	private final Map<UUID, String> lookedAt = new ConcurrentHashMap<>();
	private final Map<UUID, TextDisplay> shownLabel = new ConcurrentHashMap<>();

	private void updateLabels() {
		for (Player player : Bukkit.getOnlinePlayers()) {
			Block block = player.getTargetBlockExact(6);
			String where = block == null ? "" : block.getWorld().getName() + "," + block.getX() + "," + block.getY() + "," + block.getZ();
			if (where.equals(lookedAt.put(player.getUniqueId(), where))) continue; // still looking at the same block
			TextDisplay old = shownLabel.remove(player.getUniqueId());
			if (old != null && old.isValid()) player.hideEntity(plugin, old);
			if (block == null || settingsAt(block) == null) continue;
			TextDisplay label = skin.label(block);
			if (label == null) continue;
			player.showEntity(plugin, label);
			shownLabel.put(player.getUniqueId(), label);
		}
	}

	private Material block(String path, Material fallback) {
		Material m = Material.matchMaterial(plugin.getConfig().getString(path, fallback.name()));
		if (m == null || !m.isBlock()) {
			plugin.getLogger().warning(path + " isn't a block. Using " + fallback + ".");
			return fallback;
		}
		return m;
	}

	ItemStack item(String id) {
		Type type = Type.byId(id);
		return Items.machine(id, blocks.get(type));
	}

	// ---------- Where machines are ----------

	private NamespacedKey keyAt(Block block) {
		return new NamespacedKey(plugin, "machine_" + block.getX() + "_" + block.getY() + "_" + block.getZ()
				+ (block.getY() < 0 ? "_neg" : ""));
	}

	private Settings settingsAt(Block block) {
		PersistentDataContainer pdc = block.getChunk().getPersistentDataContainer();
		String raw = pdc.get(keyAt(block), PersistentDataType.STRING);
		if (raw == null) return null;
		Settings s = Settings.parse(raw);
		// The block was replaced some other way (like /setblock): forget it.
		if (s == null || block.getType() != blocks.get(s.type)) {
			pdc.remove(keyAt(block));
			skin.remove(block);
			return null;
		}
		return s;
	}

	@EventHandler
	public void onEntitiesLoad(EntitiesLoadEvent event) {
		addMissingSkins(event.getChunk(), event.getEntities());
		skin.hideLabels(event.getEntities());
	}

	private void addMissingSkins(Chunk chunk, Collection<Entity> entities) {
		for (NamespacedKey k : chunk.getPersistentDataContainer().getKeys()) {
			if (!k.getNamespace().equals(plugin.getName().toLowerCase(Locale.ROOT)) || !k.getKey().startsWith("machine_")) continue;
			String[] xyz = k.getKey().substring("machine_".length()).replace("_neg", "").split("_");
			if (xyz.length != 3) continue;
			Block block;
			try {
				block = chunk.getWorld().getBlockAt(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
			} catch (NumberFormatException e) {
				continue;
			}
			Settings s = settingsAt(block);
			if (s != null && !skin.hasSkin(entities, block, true)) skin.add(block, item(s.type.id), 0f, label(s.type));
		}
	}

	private void save(Block block, Settings settings) {
		block.getChunk().getPersistentDataContainer().set(keyAt(block), PersistentDataType.STRING, settings.serialize());
	}

	private void forget(Block block) {
		block.getChunk().getPersistentDataContainer().remove(keyAt(block));
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlace(BlockPlaceEvent event) {
		Type type = Type.byId(Items.idOf(event.getItemInHand()));
		if (type == null) return;
		save(event.getBlockPlaced(), Settings.defaults(type));
		skin.add(event.getBlockPlaced(), item(type.id), BlockSkin.facing(event.getPlayer()), label(type));
	}

	private static Component label(Type type) {
		return Component.text(type.title, type.color);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBreak(BlockBreakEvent event) {
		Settings s = settingsAt(event.getBlock());
		if (s == null) return;
		forget(event.getBlock());
		skin.remove(event.getBlock());
		event.setDropItems(false);
		if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
			event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation().add(0.5, 0.5, 0.5), item(s.type.id));
		}
	}

	// Explosions and pistons would lose the machine's settings, so they leave machines alone.
	@EventHandler(ignoreCancelled = true)
	public void onEntityExplode(EntityExplodeEvent event) {
		event.blockList().removeIf(b -> settingsAt(b) != null);
	}

	@EventHandler(ignoreCancelled = true)
	public void onBlockExplode(BlockExplodeEvent event) {
		event.blockList().removeIf(b -> settingsAt(b) != null);
	}

	@EventHandler(ignoreCancelled = true)
	public void onPistonExtend(BlockPistonExtendEvent event) {
		if (event.getBlocks().stream().anyMatch(b -> settingsAt(b) != null)) event.setCancelled(true);
	}

	@EventHandler(ignoreCancelled = true)
	public void onPistonRetract(BlockPistonRetractEvent event) {
		if (event.getBlocks().stream().anyMatch(b -> settingsAt(b) != null)) event.setCancelled(true);
	}

	// ---------- Menus ----------

	@EventHandler(priority = EventPriority.HIGH)
	public void onInteract(PlayerInteractEvent event) {
		if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) return;
		if (event.getPlayer().isSneaking() && !event.getPlayer().getInventory().getItemInMainHand().getType().isAir()) return;
		Settings s = settingsAt(event.getClickedBlock());
		if (s == null) return;
		event.setCancelled(true);
		openMenu(event.getPlayer(), event.getClickedBlock(), s);
	}

	private void openMenu(Player player, Block block, Settings s) {
		Inventory inv = Bukkit.createInventory(new MachineMenu(block, false), 27, Component.text(s.type.title, s.type.color));
		int slot = s.type.drinks.size() <= 3 ? 12 : 9;
		for (Drink drink : s.type.drinks) {
			ItemStack icon = Items.drink(drink);
			ItemMeta meta = icon.getItemMeta();
			List<Component> lore = new ArrayList<>(meta.lore());
			lore.add(Component.empty());
			lore.add(Items.line("Price: " + priceLabel(s, s.priceOf(drink)), NamedTextColor.GREEN));
			meta.lore(lore);
			icon.setItemMeta(meta);
			inv.setItem(slot++, icon);
		}
		List<Component> info = new ArrayList<>();
		if (s.currency.equals("money") && economy() != null) info.add(Items.line("Balance: " + economy().format(economy().getBalance(player)), NamedTextColor.GREEN));
		if (s.needsMug) info.add(Items.line("Bring an empty Mug for each drink.", NamedTextColor.GOLD));
		if (!info.isEmpty()) inv.setItem(22, button(Material.PAPER, "Info", info));
		if (player.hasPermission(ADMIN)) inv.setItem(26, button(Material.COMPARATOR, "Settings", List.of(Items.line("Operators only", NamedTextColor.GRAY))));
		player.openInventory(inv);
	}

	private void openSettings(Player player, Block block, Settings s) {
		Inventory inv = Bukkit.createInventory(new MachineMenu(block, true), 36, Component.text(s.type.title + " Settings", s.type.color));
		String currencyName = switch (s.currency) {
			case "money" -> "Money";
			case "free" -> "Free";
			default -> "Item";
		};
		inv.setItem(1, button(Material.GOLD_INGOT, "Price paid in: " + currencyName, List.of(Items.line("Click to change: Item, Money, Free", NamedTextColor.GRAY))));
		inv.setItem(3, button(s.item, "Item: " + pretty(s.item), List.of(Items.line("Click to use the item in your hand", NamedTextColor.GRAY))));
		inv.setItem(5, button(Material.EMERALD, "Default amount: " + s.amount,
				List.of(Items.line("Click to type a new amount in chat", NamedTextColor.GRAY))));
		if (s.type.allowMug) {
			inv.setItem(7, button(Material.BOWL, "Needs an empty Mug: " + (s.needsMug ? "Yes" : "No"), List.of(Items.line("Click to change", NamedTextColor.GRAY))));
		}
		int slot = 18;
		for (Drink drink : s.type.drinks) {
			Integer own = s.prices.get(drink);
			ItemStack icon = button(Material.POTION, drink.displayName + ": " + (own == null ? "default (" + priceLabel(s, s.priceOf(drink)) + ")" : own == 0 ? "free" : own),
					List.of(Items.line("Click to type a price in chat", NamedTextColor.GRAY),
							Items.line("Shift click: back to the default", NamedTextColor.GRAY)));
			icon.setItemMeta(withColor(icon, drink));
			inv.setItem(slot++, icon);
		}
		inv.setItem(35, button(Material.ARROW, "Back", List.of()));
		player.openInventory(inv);
	}

	private static ItemMeta withColor(ItemStack icon, Drink drink) {
		org.bukkit.inventory.meta.PotionMeta meta = (org.bukkit.inventory.meta.PotionMeta) icon.getItemMeta();
		meta.setColor(org.bukkit.Color.fromRGB(drink.color));
		meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
		return meta;
	}

	private static ItemStack button(Material material, String name, List<Component> lore) {
		ItemStack stack = new ItemStack(material);
		ItemMeta meta = stack.getItemMeta();
		// A custom name, not item_name: potions ignore item_name and call themselves "Uncraftable Potion".
		meta.displayName(Component.text(name, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
		meta.lore(lore);
		stack.setItemMeta(meta);
		return stack;
	}

	@EventHandler
	public void onDrag(InventoryDragEvent event) {
		if (event.getInventory().getHolder() instanceof MachineMenu) event.setCancelled(true);
	}

	@EventHandler
	public void onClick(InventoryClickEvent event) {
		if (!(event.getInventory().getHolder() instanceof MachineMenu menu)) return;
		event.setCancelled(true);
		if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) return;
		Settings s = settingsAt(menu.block());
		if (s == null) {
			player.closeInventory();
			return;
		}
		int slot = event.getSlot();
		if (!menu.settings()) {
			if (slot == 26 && player.hasPermission(ADMIN)) {
				openSettings(player, menu.block(), s);
				return;
			}
			int first = s.type.drinks.size() <= 3 ? 12 : 9;
			int index = slot - first;
			if (index >= 0 && index < s.type.drinks.size()) {
				player.closeInventory();
				buy(player, s.type.drinks.get(index), s);
			}
			return;
		}

		if (!player.hasPermission(ADMIN)) {
			player.closeInventory();
			return;
		}
		ClickType click = event.getClick();
		switch (slot) {
			case 1 -> s.currency = switch (s.currency) {
				case "item" -> "money";
				case "money" -> "free";
				default -> "item";
			};
			case 3 -> {
				Material hand = player.getInventory().getItemInMainHand().getType();
				if (hand.isAir()) {
					player.sendMessage(Component.text("Hold the item to charge in your main hand, then click.", NamedTextColor.RED));
					return;
				}
				s.item = hand;
			}
			case 5 -> {
				askPrice(player, menu.block(), null);
				return;
			}
			case 7 -> {
				if (s.type.allowMug) s.needsMug = !s.needsMug;
			}
			case 35 -> {
				openMenu(player, menu.block(), s);
				return;
			}
			default -> {
				int index = slot - 18;
				if (index < 0 || index >= s.type.drinks.size()) return;
				Drink drink = s.type.drinks.get(index);
				if (click.isShiftClick()) s.prices.remove(drink);
				else {
					askPrice(player, menu.block(), drink);
					return;
				}
			}
		}
		if (s.currency.equals("money") && economy() == null) {
			player.sendMessage(Component.text("Money needs Vault and an economy plugin. Using Item instead.", NamedTextColor.RED));
			s.currency = "item";
		}
		save(menu.block(), s);
		openSettings(player, menu.block(), s);
	}

	// ---------- Typing prices in chat ----------

	/** A price someone was asked to type. Drink null means the machine's default amount. */
	private record PriceQuestion(Block block, Drink drink) {
	}

	private final Map<UUID, PriceQuestion> asking = new ConcurrentHashMap<>();

	private void askPrice(Player player, Block block, Drink drink) {
		asking.put(player.getUniqueId(), new PriceQuestion(block, drink));
		player.closeInventory();
		String what = drink == null ? "the default amount" : "the price of " + drink.displayName;
		player.sendMessage(Component.text("Type " + what + " in chat", NamedTextColor.GOLD)
				.append(Component.text(drink == null ? " (1 or more), or \"cancel\"." : " (0 is free), \"default\", or \"cancel\".", NamedTextColor.GRAY)));
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onChat(AsyncChatEvent event) {
		PriceQuestion question = asking.remove(event.getPlayer().getUniqueId());
		if (question == null) return;
		event.setCancelled(true); // the answer isn't chat
		String answer = PlainTextComponentSerializer.plainText().serialize(event.message()).trim().toLowerCase(Locale.ROOT);
		Player player = event.getPlayer();
		Bukkit.getScheduler().runTask(plugin, () -> answerPrice(player, question, answer));
	}

	private void answerPrice(Player player, PriceQuestion question, String answer) {
		if (!player.isOnline() || !player.hasPermission(ADMIN)) return;
		Settings s = settingsAt(question.block());
		if (s == null) {
			player.sendMessage(Component.text("That machine is gone.", NamedTextColor.RED));
			return;
		}
		if (!answer.equals("cancel")) {
			if (question.drink() != null && answer.equals("default")) s.prices.remove(question.drink());
			else {
				int value;
				try {
					value = Integer.parseInt(answer.replace(",", ""));
				} catch (NumberFormatException e) {
					value = -1;
				}
				int least = question.drink() == null ? 1 : 0;
				if (value < least) {
					player.sendMessage(Component.text("\"" + answer + "\" isn't a number from " + least + " up. Click it again to retry.", NamedTextColor.RED));
					openSettings(player, question.block(), s);
					return;
				}
				if (question.drink() == null) s.amount = value;
				else s.prices.put(question.drink(), value);
			}
			save(question.block(), s);
		}
		openSettings(player, question.block(), s);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		asking.remove(event.getPlayer().getUniqueId());
		lookedAt.remove(event.getPlayer().getUniqueId());
		shownLabel.remove(event.getPlayer().getUniqueId());
	}

	// ---------- Paying ----------

	private void buy(Player player, Drink drink, Settings s) {
		int price = s.priceOf(drink);
		String currency = price == 0 ? "free" : s.currency;
		Economy eco = economy();
		List<String> missing = new ArrayList<>();
		if (currency.equals("item")) {
			int have = count(player, s.item);
			if (have < price) missing.add((price - have) + " more " + pretty(s.item));
		}
		if (currency.equals("money")) {
			if (eco == null) missing.add("an economy plugin on the server");
			else if (!eco.has(player, price)) missing.add(eco.format(price - eco.getBalance(player)) + " more");
		}
		if (s.needsMug && !player.getInventory().containsAtLeast(Items.mug(1), 1)) missing.add("an empty Mug");
		if (!missing.isEmpty()) {
			player.sendMessage(Component.text("You need " + String.join(" and ", missing) + ".", NamedTextColor.RED));
			player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
			return;
		}

		if (currency.equals("item")) player.getInventory().removeItem(new ItemStack(s.item, price));
		if (currency.equals("money")) eco.withdrawPlayer(player, price);
		if (s.needsMug) player.getInventory().removeItem(Items.mug(1));

		player.getInventory().addItem(Items.drink(drink)).values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
		Drink base = drink.base == null ? drink : drink.base;
		player.playSound(player, base == Drink.SODA ? Sound.BLOCK_FIRE_EXTINGUISH : Sound.ITEM_BUCKET_FILL, 0.7f, 1f);
		player.sendActionBar(Component.text("Enjoy your " + drink.displayName + "!", NamedTextColor.GREEN));
	}

	/** Plain items only: a renamed or custom item doesn't count as payment. */
	private static int count(Player player, Material material) {
		int total = 0;
		for (ItemStack stack : player.getInventory().getStorageContents()) {
			if (stack != null && stack.getType() == material && Items.idOf(stack) == null) total += stack.getAmount();
		}
		return total;
	}

	private String priceLabel(Settings s, int amount) {
		if (s.currency.equals("free") || amount == 0) return "Free";
		if (s.currency.equals("money")) return economy() != null ? economy().format(amount) : amount + " Money";
		return amount + " " + pretty(s.item);
	}

	private static String pretty(Material material) {
		StringBuilder out = new StringBuilder();
		for (String word : material.getKey().getKey().split("_")) {
			if (!out.isEmpty()) out.append(' ');
			out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.ROOT));
		}
		return out.toString();
	}

	private Economy economy() {
		if (Bukkit.getPluginManager().getPlugin("Vault") == null) return null;
		RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
		return rsp == null ? null : rsp.getProvider();
	}
}
