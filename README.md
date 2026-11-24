# Beverages (Paper plugin)

Paper port of the Bedrock add-on [beverages-minecraft-bedrock](https://github.com/TheCoberto/beverages-minecraft-bedrock): brewable and craftable drinks, the Mug, and two machines that sell them.

## Drinks

| Drink | How to make | Effect |
|---|---|---|
| Haste Potion | Brew: Awkward Potion + Lapis Lazuli | Haste II, 30 min |
| Soda | Brew: Awkward Potion + Chorus Fruit | Speed III, 3 min |
| Apple Cider | Brew: Awkward Potion + Apple | Night Vision, 3 min |
| Iced Tea | Brew: Awkward Potion + Ice | Fire Resistance, 3 min |
| Sweet Berry Smoothie | Brew: Awkward Potion + Sweet Berries | Instantly refills hunger |
| Glow Berry Juice | Brew: Awkward Potion + Glow Berries | Particles over nearby monsters for 30s (only you see them) |
| Hot Cocoa | Craft: Mug + Cocoa Beans + Sugar + Milk Bucket | Clears Slowness, Regeneration I 10s (Regeneration II 20s if you're cold) |
| Coffee | Craft: Mug + 2 Cocoa Beans | Haste I 2 min + Speed I 30s |

**Mug:** 5 Bricks in a `B B / BBB` shape makes 2 Mugs. Mug drinks give the Mug back.

**Upgrades** (Haste Potion, Soda, Apple Cider, Iced Tea, Coffee), crafted with the drink:
- **+ Glowstone Dust**: Strong version, one level higher, half the duration.
- **+ Redstone**: Extended version, double the duration.

You're "cold" for Hot Cocoa when standing on or in snow or ice, or for a minute after.

## Machines

| Block | Recipe | What it does |
|---|---|---|
| Soda Vending Machine | `III / GSG / IRI` (Iron Ingot, Glass Pane, Soda, Redstone) | Sells Soda, Strong Soda and Extended Soda. Default: 1 Diamond. |
| Beverage Fountain | `QMQ / ICI / QQQ` (Quartz Block, Mug, Iron Ingot, Cauldron) | Pours any drink. Default: free, but you need an empty Mug for each. |

Right-click a machine to open its menu. Players with `beverages.admin` (ops) get a **Settings** button: price paid in an item (click with the item in your hand), Money (through Vault) or Free, the default amount, whether the fountain needs a Mug, and a price per drink. Machines drop themselves when broken, and explosions and pistons leave them alone.

The blocks are set in `config.yml` (default: Iron Block and Quartz Pillar).

## Commands

`/beverages give <item> [player]` (ops): any drink id (like `soda_strong`), `mug`, `vending_machine` or `beverage_fountain`.

## Differences from the Bedrock version

Paper can't add new items, blocks or textures, so drinks are coloured potions with a name and description, the Mug is a bowl, and the machines are ordinary-looking blocks. Mugs and drinks only work in this plugin's recipes. Money is the server's Vault economy instead of the `Money` scoreboard, and the menus are chest menus.

## Build
```
./gradlew build
```
Needs JDK 25 or newer to run Gradle. The jar ends up in `build/libs/`. Requires Paper 1.21.1 or newer; Vault is optional (for Money prices).
