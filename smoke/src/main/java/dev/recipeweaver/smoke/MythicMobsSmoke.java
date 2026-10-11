package dev.recipeweaver.smoke;

import dev.recipeweaver.api.RecipeWeaverApi;
import java.util.Collection;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

/** Optional real-server check of RecipeWeaver's MythicMobs item provider. */
final class MythicMobsSmoke {
    private static final NamespacedKey SAMPLE = new NamespacedKey("mythicmobs", "skeletonkingsword");
    private static final NamespacedKey MISSING = new NamespacedKey("mythicmobs", "recipeweaver_missing_sample");
    private static final NamespacedKey VANILLA_FALLBACK = new NamespacedKey("mythicmobs", "diamond");

    private MythicMobsSmoke() { }

    static void verifyIfInstalled(RecipeWeaverApi api, Plugin harness) {
        Plugin mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
        if (mythic == null) return;
        if (!mythic.isEnabled()) {
            throw new IllegalStateException("MythicMobs is installed but disabled");
        }

        ItemStack sword = api.resolveItem(SAMPLE).orElseThrow(() ->
                new IllegalStateException("RecipeWeaver did not resolve MythicMobs SkeletonKingSword"));
        ItemMeta meta = sword.getItemMeta();
        String name = meta != null && meta.hasDisplayName()
                ? ChatColor.stripColor(meta.getDisplayName()) : "";
        if (sword.getType() != Material.DIAMOND_SWORD || sword.getAmount() != 1
                || name == null || !name.contains("Greatsword of the Skeleton King")) {
            throw new IllegalStateException("MythicMobs sample item was not generated: "
                    + sword.getType() + " x" + sword.getAmount() + " name=" + name);
        }

        sword.setAmount(7);
        ItemStack again = api.resolveItem(SAMPLE).orElseThrow(() ->
                new IllegalStateException("MythicMobs sample vanished on second resolution"));
        if (again.getAmount() != 1 || api.resolveItem(MISSING).isPresent()) {
            throw new IllegalStateException("MythicMobs item lookup leaked a mutation or resolved an unknown ID");
        }
        verifyVanillaFallbackRejected(api, mythic);
        harness.getLogger().info("RW_MYTHICMOBS_VANILLA_FALLBACK_PASS");
        harness.getLogger().info("RW_MYTHICMOBS_ITEM_PASS " + sword.getType() + " " + name);
    }

    private static void verifyVanillaFallbackRejected(RecipeWeaverApi api, Plugin mythic) {
        try {
            Object manager = mythic.getClass().getMethod("getItemManager").invoke(mythic);
            Object names = manager.getClass().getMethod("getItemNames").invoke(manager);
            if (!(names instanceof Collection<?> known) || known.stream()
                    .anyMatch(name -> name instanceof String id && id.equalsIgnoreCase("diamond"))) {
                throw new IllegalStateException("MythicMobs sample conflicts with a configured diamond item");
            }
            Object fallback = manager.getClass().getMethod("getItemStack", String.class)
                    .invoke(manager, "diamond");
            if (!(fallback instanceof ItemStack stack) || stack.getType() != Material.DIAMOND
                    || stack.getAmount() != 1) {
                throw new IllegalStateException("MythicMobs vanilla fallback fixture did not return DIAMOND");
            }
            if (api.resolveItem(VANILLA_FALLBACK).isPresent()) {
                throw new IllegalStateException("RecipeWeaver accepted MythicMobs' vanilla fallback as a custom item");
            }
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("MythicMobs vanilla fallback API was unavailable", ex);
        }
    }
}
