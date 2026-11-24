package com.thecoberto.beverages;

import java.util.Collection;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Gives a placed machine its resource pack look: an item display showing the
 * machine's item model, a hair bigger than the block so it covers it. Without
 * the pack the item model is the plain block, so nothing changes. Optionally
 * also a name floating above it that shows when players come close.
 *
 * The model's display sits just above the block and is shifted down into it, so
 * it takes its light from the air above instead of rendering dark inside the block.
 */
final class BlockSkin {
	private static final String MODEL = "#model";
	private static final String LABEL = "#label";
	/** Fraction of the normal 64-block entity view distance: about 10 blocks. */
	private static final float LABEL_RANGE = 0.16f;

	private final NamespacedKey key;

	BlockSkin(JavaPlugin plugin) {
		this.key = new NamespacedKey(plugin, "block_skin");
	}

	private static String id(Block block) {
		return block.getWorld().getName() + "," + block.getX() + "," + block.getY() + "," + block.getZ();
	}

	private static Location spot(Block block) {
		return block.getLocation().add(0.5, 1.01, 0.5);
	}

	/** The yaw that turns the model's front toward the player who placed it. */
	static float facing(Player player) {
		return Math.round((player.getLocation().getYaw() + 180f) / 90f) * 90f;
	}

	void add(Block block, ItemStack model, float yaw) {
		add(block, model, yaw, null);
	}

	/** Replaces any skin the block had. Label null means no floating name. */
	void add(Block block, ItemStack model, float yaw, Component label) {
		remove(block);
		ItemStack shown = model.clone();
		shown.setAmount(1);
		String id = id(block);
		block.getWorld().spawn(spot(block), ItemDisplay.class, display -> {
			display.setItemStack(shown);
			display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
			display.setRotation(yaw, 0f);
			display.setTransformation(new Transformation(new Vector3f(0f, -0.51f, 0f), new AxisAngle4f(),
					new Vector3f(1.002f, 1.002f, 1.002f), new AxisAngle4f()));
			display.setPersistent(true);
			display.getPersistentDataContainer().set(key, PersistentDataType.STRING, id + MODEL);
		});
		if (label != null) {
			block.getWorld().spawn(block.getLocation().add(0.5, 1.35, 0.5), TextDisplay.class, text -> {
				text.text(label);
				text.setBillboard(Display.Billboard.CENTER);
				text.setViewRange(LABEL_RANGE);
				text.setShadowed(true);
				text.setVisibleByDefault(false); // shown only to players looking at the machine
				text.setPersistent(true);
				text.getPersistentDataContainer().set(key, PersistentDataType.STRING, id + LABEL);
			});
		}
	}

	void remove(Block block) {
		String id = id(block);
		for (Entity e : block.getWorld().getNearbyEntities(block.getLocation().add(0.5, 1.2, 0.5), 0.6, 0.6, 0.6)) {
			String tag = tagOf(e);
			if (tag != null && tag.startsWith(id + "#")) e.remove();
		}
	}

	/** Whether these entities already include the block's skin (and its label, if it should have one). */
	boolean hasSkin(Collection<Entity> entities, Block block, boolean withLabel) {
		String id = id(block);
		boolean model = false, label = false;
		for (Entity e : entities) {
			String tag = tagOf(e);
			if ((id + MODEL).equals(tag)) model = true;
			if ((id + LABEL).equals(tag)) label = true;
		}
		return model && (label || !withLabel);
	}

	/** The block's floating name, or null if it has none. */
	TextDisplay label(Block block) {
		String want = id(block) + LABEL;
		for (Entity e : block.getWorld().getNearbyEntities(block.getLocation().add(0.5, 1.35, 0.5), 0.3, 0.3, 0.3)) {
			if (e instanceof TextDisplay text && want.equals(tagOf(e))) return text;
		}
		return null;
	}

	/** Labels made before they were hidden by default become hidden. */
	void hideLabels(Collection<Entity> entities) {
		for (Entity e : entities) {
			String tag = tagOf(e);
			if (tag != null && tag.endsWith(LABEL) && e.isVisibleByDefault()) e.setVisibleByDefault(false);
		}
	}

	private String tagOf(Entity e) {
		if (!(e instanceof Display)) return null;
		String tag = e.getPersistentDataContainer().get(key, PersistentDataType.STRING);
		// Skins from before labels were tagged with just the position.
		return tag == null || tag.contains("#") ? tag : tag + MODEL;
	}
}
