package dev.recipeweaver.smoke;

import dev.recipeweaver.api.RecipeWeaverApi;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

/** Test-only real-server metadata editor and persistence probe. */
final class ItemMetadataSmoke {
    static final NamespacedKey KEY = new NamespacedKey("recipeweaver", "smoke_item_metadata");

    private ItemMetadataSmoke() { }

    static void verify(RecipeWeaverApi api) {
        ItemStack item = new ItemStack(Material.DIAMOND_CHESTPLATE);
        check(item.getItemMeta() instanceof ArmorMeta,
                "Server did not provide armor metadata for a chestplate");
        invoke("replaceLore", item, "&aFirst line\n&bSecond line");
        invoke("setDamage", item, "17");
        invoke("setEnchantment", item, "minecraft:protection 4");
        invoke("setTrim", item, "minecraft:iron minecraft:sentry");
        invoke("setCustomModelData", item, "1234");
        invoke("toggleUnbreakable", item);
        invoke("toggleFlag", item, ItemFlag.HIDE_ENCHANTS);
        check(saved(item), "Live item metadata editor did not apply all seven controls");
        api.saveCustomItem(KEY, item);
        SmokePlugin.reloadForLegacyAssertions(api);
        checkRecovered(api);
    }

    static void mutateForRestore(RecipeWeaverApi api) {
        api.saveCustomItem(KEY, new ItemStack(Material.DIAMOND_CHESTPLATE));
        SmokePlugin.reloadForLegacyAssertions(api);
        check(!saved(api.customItem(KEY).orElseThrow()),
                "Item metadata backup mutation did not save");
    }

    static void checkRecovered(RecipeWeaverApi api) {
        check(saved(api.customItem(KEY).orElseThrow()),
                "Custom item metadata was not recovered");
    }

    static boolean saved(ItemStack item) {
        if (item.getType() != Material.DIAMOND_CHESTPLATE) return false;
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof ArmorMeta armor) || !(meta instanceof Damageable damageable)) return false;
        return List.of(ChatColor.GREEN + "First line", ChatColor.AQUA + "Second line")
                        .equals(meta.getLore())
                && damageable.getDamage() == 17
                && meta.getEnchantLevel(Registry.ENCHANTMENT.get(
                        NamespacedKey.minecraft("protection"))) == 4
                && armor.hasTrim()
                && NamespacedKey.minecraft("iron").equals(armor.getTrim().getMaterial().getKey())
                && NamespacedKey.minecraft("sentry").equals(armor.getTrim().getPattern().getKey())
                && meta.hasCustomModelData() && meta.getCustomModelData() == 1234
                && meta.isUnbreakable() && meta.hasItemFlag(ItemFlag.HIDE_ENCHANTS);
    }

    private static void invoke(String name, ItemStack item, Object... values) {
        try {
            ClassLoader loader = Bukkit.getPluginManager().getPlugin("RecipeWeaver")
                    .getClass().getClassLoader();
            Class<?> editor = Class.forName("dev.recipeweaver.core.ItemDraftEditor", true, loader);
            Class<?>[] parameters = new Class<?>[values.length + 1];
            parameters[0] = ItemStack.class;
            for (int i = 0; i < values.length; i++) parameters[i + 1] = values[i].getClass();
            Method method = editor.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            Object[] arguments = new Object[values.length + 1];
            arguments[0] = item;
            System.arraycopy(values, 0, arguments, 1, values.length);
            method.invoke(null, arguments);
        } catch (InvocationTargetException ex) {
            throw new IllegalStateException("Live item editor rejected " + name, ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not invoke live item editor " + name, ex);
        }
    }

    private static void check(boolean passed, String detail) {
        if (!passed) throw new IllegalStateException(detail);
    }
}
