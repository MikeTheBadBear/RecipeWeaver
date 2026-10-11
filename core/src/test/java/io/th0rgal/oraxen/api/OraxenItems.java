package io.th0rgal.oraxen.api;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import java.util.List;

public final class OraxenItems {
    private OraxenItems() { }
    public static ItemBuilder getItemById(String id) {
        return switch (id) {
            case "ruby" -> new ItemBuilder(Material.EMERALD, 3);
            case "CrystalBlade" -> new ItemBuilder(Material.DIAMOND_SWORD, 1);
            case "DawnBlade", "DAWNblade" -> new ItemBuilder(Material.GOLDEN_SWORD, 1);
            default -> null;
        };
    }
    public static List<String> getNames() { return List.of("ruby", "CrystalBlade", "DawnBlade", "DAWNblade"); }
    public static final class ItemBuilder {
        private final Material material;
        private final int amount;
        private ItemBuilder(Material material, int amount) {
            this.material = material;
            this.amount = amount;
        }
        public ItemStack build() { return new ItemStack(material, amount); }
    }
}
