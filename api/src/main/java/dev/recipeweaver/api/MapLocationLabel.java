package dev.recipeweaver.api;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.persistence.PersistentDataType;

/** RecipeWeaver's map location label, stored independently of the obsolete Bukkit setter. */
public final class MapLocationLabel {
    public static final NamespacedKey STORAGE_KEY =
            new NamespacedKey("recipeweaver", "map_location_name");

    private MapLocationLabel() { }

    public static String read(ItemMeta meta) {
        if (!(meta instanceof MapMeta)) return null;
        String stored = meta.getPersistentDataContainer().get(STORAGE_KEY,
                PersistentDataType.STRING);
        return stored == null || stored.isEmpty() ? null : stored;
    }

    public static void write(ItemMeta meta, String label) {
        if (!(meta instanceof MapMeta)) {
            throw new IllegalArgumentException("A map location label requires a filled map");
        }
        // Empty is an explicit clear, including for maps imported from other providers.
        meta.getPersistentDataContainer().set(STORAGE_KEY, PersistentDataType.STRING,
                label == null ? "" : label);
    }
}
