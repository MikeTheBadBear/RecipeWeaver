package io.lumine.mythic.bukkit;

import io.lumine.mythic.api.adapters.AbstractItemStack;
import org.bukkit.inventory.ItemStack;

/** Test shape of MythicMobs' published Bukkit item conversion method. */
public final class BukkitAdapter {
    private BukkitAdapter() { }

    public static ItemStack adapt(AbstractItemStack item) {
        return item.toBukkit();
    }
}
