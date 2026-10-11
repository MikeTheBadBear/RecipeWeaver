package dev.recipeweaver.smoke;

import java.util.List;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;

/** Item-specific server metadata and persistent data for full-result-copy probes. */
final class FullItemDataSmoke {
    private static final NamespacedKey MARKER = new NamespacedKey(
            "recipeweaver", "smoke_full_item_data");

    private FullItemDataSmoke() { }

    static ItemStack rocket(int amount, String marker) {
        ItemStack item = new ItemStack(Material.FIREWORK_ROCKET, amount);
        FireworkMeta meta = (FireworkMeta) item.getItemMeta();
        meta.setPower(3);
        meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.STAR)
                .withColor(Color.PURPLE).trail(true).build());
        meta.setDisplayName("Full data " + marker);
        meta.setLore(List.of("Metadata from " + marker));
        meta.getPersistentDataContainer().set(MARKER, PersistentDataType.STRING, marker);
        item.setItemMeta(meta);
        return item;
    }

    static void checkCopied(ItemStack source, ItemStack actual, int amount, String path) {
        ItemStack expected = source.clone();
        expected.setAmount(amount);
        if (actual == null || actual.getAmount() != amount || !actual.isSimilar(expected)
                || !(actual.getItemMeta() instanceof FireworkMeta meta)
                || meta.getPower() != 3 || meta.getEffectsSize() != 1
                || !source.getItemMeta().getPersistentDataContainer().get(MARKER,
                        PersistentDataType.STRING).equals(meta.getPersistentDataContainer()
                                .get(MARKER, PersistentDataType.STRING))) {
            throw new IllegalStateException(path + " lost source firework data or persistent data");
        }
    }
}
