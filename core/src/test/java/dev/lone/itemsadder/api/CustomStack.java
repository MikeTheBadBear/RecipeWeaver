package dev.lone.itemsadder.api;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public final class CustomStack {
    private CustomStack() { }
    public static CustomStack getInstance(String id) {
        return id.equals("demo:ruby") ? new CustomStack() : null;
    }
    public ItemStack getItemStack() { return new ItemStack(Material.REDSTONE, 2); }
}
