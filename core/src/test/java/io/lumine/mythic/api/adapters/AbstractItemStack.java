package io.lumine.mythic.api.adapters;

import org.bukkit.inventory.ItemStack;

/** Test shape of MythicMobs' published abstract item contract. */
@FunctionalInterface
public interface AbstractItemStack {
    ItemStack toBukkit();
}
