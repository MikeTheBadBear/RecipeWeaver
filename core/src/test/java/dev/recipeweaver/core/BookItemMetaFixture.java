package dev.recipeweaver.core;

import java.lang.reflect.Proxy;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Read-only item names for book tests on MockBukkit versions without item-name components. */
final class BookItemMetaFixture {
    private BookItemMetaFixture() { }

    static ItemStack item(Material material, String displayName, String itemName) {
        ItemMeta meta = (ItemMeta) Proxy.newProxyInstance(
                ItemMeta.class.getClassLoader(), new Class<?>[] {ItemMeta.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasDisplayName" -> displayName != null;
                    case "getDisplayName" -> displayName;
                    case "hasItemName" -> itemName != null;
                    case "getItemName" -> itemName;
                    case "toString" -> "Book item meta fixture";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new ItemStack(material) {
            @Override public ItemMeta getItemMeta() { return meta; }
        };
    }
}
