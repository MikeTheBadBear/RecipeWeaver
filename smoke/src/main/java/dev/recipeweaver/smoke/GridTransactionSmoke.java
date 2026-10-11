package dev.recipeweaver.smoke;

import dev.recipeweaver.api.GridTier;
import dev.recipeweaver.api.RecipeWeaverApi;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.Plugin;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** Fires production grid result-click handling with a synthetic player on a live server. */
final class GridTransactionSmoke {
    private GridTransactionSmoke() { }

    static void verify(RecipeWeaverApi api, Plugin smokePlugin) throws ReflectiveOperationException {
        Plugin production = Bukkit.getPluginManager().getPlugin("RecipeWeaver");
        Class<?> pluginClass = production.getClass();
        Field menusField = pluginClass.getDeclaredField("gridMenus");
        menusField.setAccessible(true);
        Object menus = menusField.get(production);
        ClassLoader loader = pluginClass.getClassLoader();
        Class<?> menuType = Class.forName("dev.recipeweaver.core.GridMenus$Menu", true, loader);
        Class<?> holderType = Class.forName("dev.recipeweaver.core.GridMenus$Holder", true, loader);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object craftMenu = Enum.valueOf((Class<? extends Enum>) menuType, "CRAFT");
        Constructor<?> constructor = holderType.getDeclaredConstructor(
                UUID.class, menuType, int.class, GridTier.class, int.class);
        constructor.setAccessible(true);
        UUID playerId = UUID.fromString("12345678-1234-1234-1234-123456789acf");
        InventoryHolder holder = (InventoryHolder) constructor.newInstance(
                playerId, craftMenu, 0, GridTier.ELITE, 2);
        Inventory top = Bukkit.createInventory(holder, 54, "RecipeWeaver grid transaction smoke");
        Field inventoryField = holderType.getDeclaredField("inventory");
        inventoryField.setAccessible(true);
        inventoryField.set(holder, top);
        top.setItem(0, new ItemStack(Material.BLAZE_ROD, 5));
        top.setItem(7, new ItemStack(Material.GOLD_INGOT));

        AtomicReference<ItemStack[]> storage = new AtomicReference<>(new ItemStack[36]);
        AtomicReference<ItemStack> cursor = new AtomicReference<>();
        AtomicReference<InventoryView> open = new AtomicReference<>();
        PersistentDataContainer playerData = new ItemStack(Material.STONE)
                .getItemMeta().getPersistentDataContainer();
        PlayerInventory playerInventory = (PlayerInventory) Proxy.newProxyInstance(
                PlayerInventory.class.getClassLoader(), new Class<?>[]{PlayerInventory.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getStorageContents" -> storage.get();
                    case "setStorageContents" -> { storage.set((ItemStack[]) args[0]); yield null; }
                    default -> throw new UnsupportedOperationException("Inventory " + method.getName());
                });
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> playerId;
                    case "getName" -> "GridTransactionSmoke";
                    case "getWorld" -> Bukkit.getWorlds().get(0);
                    case "getInventory" -> playerInventory;
                    case "getItemOnCursor" -> cursor.get();
                    case "setItemOnCursor" -> { cursor.set((ItemStack) args[0]); yield null; }
                    case "getOpenInventory" -> open.get();
                    case "getPersistentDataContainer" -> playerData;
                    case "isOnline" -> true;
                    case "updateInventory" -> null;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "GridTransactionSmokePlayer";
                    default -> throw new UnsupportedOperationException("Player " + method.getName());
                });
        Inventory bottom = Bukkit.createInventory(null, 9);
        InventoryView view = new InventoryView() {
            @Override public Inventory getTopInventory() { return top; }
            @Override public Inventory getBottomInventory() { return bottom; }
            @Override public Player getPlayer() { return player; }
            @Override public InventoryType getType() { return InventoryType.CHEST; }
            @Override public String getTitle() { return "RecipeWeaver grid transaction smoke"; }
            @Override public String getOriginalTitle() { return getTitle(); }
            @Override public void setTitle(String title) { }
        };
        open.set(view);

        AtomicBoolean gridAllowed = new AtomicBoolean(false);
        AtomicInteger extensionBatches = new AtomicInteger();
        api.registerGridCondition(smokePlugin, GridCommandSmoke.KEY,
                (who, recipe, inputs) -> gridAllowed.get());
        api.registerGridAction(smokePlugin, GridCommandSmoke.KEY, true, (who, batch) -> {
            check(batch.result().getType() == Material.GOLD_INGOT,
                    "Grid extension action received the wrong result");
            extensionBatches.incrementAndGet();
        });
        click(view, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        check(count(storage.get(), Material.GOLD_INGOT) == 0
                        && extensionBatches.get() == 0,
                "Grid extension condition did not block its recipe result");
        storage.set(new ItemStack[36]);
        top.setItem(0, new ItemStack(Material.BLAZE_ROD, 5));
        top.setItem(1, null);
        gridAllowed.set(true);

        click(view, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        check(top.getItem(0) != null && top.getItem(0).getAmount() == 3,
                "Shift click ignored its two-batch recipe limit");
        check(count(storage.get(), Material.GOLD_INGOT) == 2,
                "Shift click did not deliver exactly two results");
        check(extensionBatches.get() == 2,
                "Grid extension action did not run once for each committed shift batch");

        click(view, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        check(top.getItem(0) != null && top.getItem(0).getAmount() == 2,
                "Normal click did not consume exactly one ingredient");
        check(cursor.get() != null && cursor.get().getType() == Material.GOLD_INGOT
                        && cursor.get().getAmount() == 1,
                "Normal click did not deliver exactly one result to the cursor");
        check(extensionBatches.get() == 3,
                "Grid extension action did not run after a normal committed click");

        ItemStack[] full = new ItemStack[36];
        Arrays.fill(full, new ItemStack(Material.COBBLESTONE, 64));
        storage.set(full);
        click(view, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        check(top.getItem(0) != null && top.getItem(0).getAmount() == 2
                        && count(storage.get(), Material.GOLD_INGOT) == 0,
                "Full inventory allowed shift crafting or duplicated output");
        check(extensionBatches.get() == 3,
                "Failed grid transaction ran an extension action");

        storage.set(new ItemStack[36]);
        cursor.set(null);
        top.setItem(0, new ItemStack(Material.DIAMOND, 2));
        top.setItem(7, new ItemStack(Material.EMERALD));
        click(view, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        check(top.getItem(0) != null && top.getItem(0).getAmount() == 1
                        && cursor.get() != null && cursor.get().getType() == Material.EMERALD,
                "Cooldown recipe failed its first committed click");
        cursor.set(null);
        click(view, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        check(top.getItem(0) != null && top.getItem(0).getAmount() == 1 && cursor.get() == null,
                "Cooldown recipe allowed an immediate second click");
        NamespacedKey cooldown = new NamespacedKey("recipeweaver", "cooldown/"
                + GridCommandSmoke.COOLDOWN_KEY.getNamespace() + "/"
                + GridCommandSmoke.COOLDOWN_KEY.getKey());
        playerData.set(cooldown, PersistentDataType.LONG, System.currentTimeMillis() - 1);
        click(view, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        check(top.getItem(0) == null && cursor.get() != null
                        && cursor.get().getType() == Material.EMERALD,
                "Expired cooldown did not allow the next click");

        cursor.set(null);
        ItemStack source = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta sourceMeta = source.getItemMeta();
        sourceMeta.setDisplayName("Matched metadata source");
        sourceMeta.setLore(java.util.List.of("Inherited lore"));
        sourceMeta.setUnbreakable(true);
        sourceMeta.setCustomModelData(321);
        sourceMeta.setEnchantmentGlintOverride(true);
        sourceMeta.setFireResistant(true);
        sourceMeta.setHideTooltip(true);
        sourceMeta.setMaxStackSize(16);
        sourceMeta.setRarity(ItemRarity.RARE);
        sourceMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        sourceMeta.addEnchant(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking")), 2, true);
        ((Damageable) sourceMeta).setDamage(17);
        NamespacedKey marker = new NamespacedKey("recipeweaver", "smoke_copied_data");
        sourceMeta.getPersistentDataContainer().set(marker, PersistentDataType.STRING, "copied");
        source.setItemMeta(sourceMeta);
        top.setItem(0, source);
        top.setItem(7, new ItemStack(Material.IRON_SWORD));
        click(view, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        ItemStack output = cursor.get();
        check(output != null && output.getType() == Material.IRON_SWORD
                        && output.getAmount() == 1
                        && output.getItemMeta().getDisplayName().equals("Matched metadata source")
                        && output.getItemMeta().getLore().equals(java.util.List.of("Inherited lore"))
                        && output.getItemMeta().isUnbreakable()
                        && output.getItemMeta().getCustomModelData() == 321
                        && output.getItemMeta().hasEnchantmentGlintOverride()
                        && Boolean.TRUE.equals(output.getItemMeta().getEnchantmentGlintOverride())
                        && output.getItemMeta().isFireResistant()
                        && output.getItemMeta().isHideTooltip()
                        && output.getItemMeta().hasMaxStackSize()
                        && output.getItemMeta().getMaxStackSize() == 16
                        && output.getItemMeta().hasRarity()
                        && output.getItemMeta().getRarity() == ItemRarity.RARE
                        && output.getItemMeta().hasItemFlag(ItemFlag.HIDE_ENCHANTS)
                        && output.getItemMeta().getEnchantLevel(Registry.ENCHANTMENT.get(
                                NamespacedKey.minecraft("unbreaking"))) == 2
                        && output.getItemMeta() instanceof Damageable damageable
                        && damageable.getDamage() == 17
                        && "copied".equals(output.getItemMeta().getPersistentDataContainer()
                                .get(marker, PersistentDataType.STRING)),
                "Grid result did not copy common components from the matched ingredient");

        cursor.set(null);
        storage.set(new ItemStack[36]);
        ItemStack fullDataSource = FullItemDataSmoke.rocket(3, "grid-input");
        top.setItem(0, fullDataSource);
        top.setItem(7, null);
        var refresh = menus.getClass().getDeclaredMethod("refresh", Player.class, holderType);
        refresh.setAccessible(true);
        refresh.invoke(menus, player, holder);
        FullItemDataSmoke.checkCopied(fullDataSource, top.getItem(7), 2, "Grid preview");
        click(view, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        FullItemDataSmoke.checkCopied(fullDataSource, cursor.get(), 2, "Grid result click");
        check(top.getItem(0) != null && top.getItem(0).getAmount() == 2
                        && fullDataSource.getAmount() == 3,
                "Grid full-data click consumed the wrong amount or changed its source object");
        cursor.set(null);
        refresh.invoke(menus, player, holder);
        FullItemDataSmoke.checkCopied(fullDataSource, top.getItem(7), 2,
                "Grid preview after a committed click");
        click(view, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        check(top.getItem(0) == null, "Grid full-data shift click left a consumed input");
        FullItemDataSmoke.checkCopied(fullDataSource, storage.get()[0], 4,
                "Grid shift result transaction");

        cursor.set(null);
        storage.set(new ItemStack[36]);
        top.setItem(0, new ItemStack(Material.BLAZE_ROD, 2));
        top.setItem(1, new ItemStack(Material.COAL, 3));
        top.setItem(7, new ItemStack(Material.COPPER_INGOT));
        click(view, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        check(top.getItem(0) == null && top.getItem(1) != null
                        && top.getItem(1).getAmount() == 2
                        && count(storage.get(), Material.COPPER_INGOT) == 2
                        && count(storage.get(), Material.LAPIS_LAZULI) == 1,
                "Switching grid recipes bypassed the second recipe's one-batch cap");

        cursor.set(null);
        ItemStack[] fullForLimit = new ItemStack[36];
        Arrays.fill(fullForLimit, new ItemStack(Material.COBBLESTONE, 64));
        storage.set(fullForLimit);
        top.setItem(0, new ItemStack(Material.FLINT, 5));
        top.setItem(1, null);
        top.setItem(7, new ItemStack(Material.GOLD_NUGGET));
        NamespacedKey craftCount = new NamespacedKey("recipeweaver", "craft-count/"
                + GridCommandSmoke.CRAFT_LIMIT_KEY.getNamespace() + "/"
                + GridCommandSmoke.CRAFT_LIMIT_KEY.getKey());
        click(view, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        check(top.getItem(0).getAmount() == 5
                        && !playerData.has(craftCount, PersistentDataType.INTEGER),
                "Failed inventory transaction spent a persistent craft allowance");
        storage.set(new ItemStack[36]);
        click(view, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        check(top.getItem(0).getAmount() == 2
                        && count(storage.get(), Material.GOLD_NUGGET) == 3
                        && Integer.valueOf(3).equals(playerData.get(craftCount, PersistentDataType.INTEGER)),
                "Shift craft exceeded or failed to save its per-player lifetime limit");
        click(view, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        check(top.getItem(0).getAmount() == 2
                        && count(storage.get(), Material.GOLD_NUGGET) == 3,
                "Exhausted per-player craft allowance still permitted crafting");

        cursor.set(null);
        storage.set(new ItemStack[36]);
        ItemStack shulkerInput = new ItemStack(Material.RED_SHULKER_BOX);
        ResultMetadataSmoke.setShulkerSlot(shulkerInput, 0, new ItemStack(Material.DIAMOND, 2));
        top.setItem(0, shulkerInput);
        top.setItem(1, null);
        top.setItem(7, null);
        refresh.invoke(menus, player, holder);
        ItemStack shulkerPreview = top.getItem(7);
        check(shulkerPreview != null && shulkerPreview.getType() == Material.BLUE_SHULKER_BOX
                        && ResultMetadataSmoke.shulkerSlot(shulkerPreview, 0).getType()
                                == Material.DIAMOND
                        && ResultMetadataSmoke.shulkerSlot(shulkerPreview, 0).getAmount() == 2,
                "Shulker grid preview did not copy exactly two nested diamonds");
        click(view, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        ItemStack shulkerOutput = cursor.get();
        check(top.getItem(0) == null && shulkerOutput != null
                        && shulkerOutput.getType() == Material.BLUE_SHULKER_BOX
                        && shulkerOutput.getAmount() == 1
                        && ResultMetadataSmoke.shulkerSlot(shulkerOutput, 0).getType()
                                == Material.DIAMOND
                        && ResultMetadataSmoke.shulkerSlot(shulkerOutput, 0).getAmount() == 2
                        && count(storage.get(), Material.BLUE_SHULKER_BOX) == 0,
                "Shulker grid click did not consume one input and deliver one copied output");
        cursor.set(null);
        top.setItem(7, shulkerOutput.clone());
        click(view, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        check(top.getItem(0) == null && cursor.get() == null
                        && count(storage.get(), Material.BLUE_SHULKER_BOX) == 0,
                "Stale shulker result click duplicated nested items without an ingredient");
        check(api.unregisterGridCondition(smokePlugin, GridCommandSmoke.KEY)
                        && api.unregisterGridAction(smokePlugin, GridCommandSmoke.KEY),
                "Grid extension callbacks did not unregister");
    }

    private static void click(InventoryView view, ClickType click, InventoryAction action) {
        InventoryClickEvent event = new InventoryClickEvent(view,
                InventoryType.SlotType.RESULT, 7, click, action);
        Bukkit.getPluginManager().callEvent(event);
        check(event.isCancelled(), "Grid result click escaped the production handler");
    }

    private static int count(ItemStack[] storage, Material type) {
        int total = 0;
        for (ItemStack item : storage) if (item != null && item.getType() == type) {
            total += item.getAmount();
        }
        return total;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
