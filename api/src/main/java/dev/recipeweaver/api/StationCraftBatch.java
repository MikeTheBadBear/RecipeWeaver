package dev.recipeweaver.api;

import java.util.Objects;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

/** One committed station result. Brewing creates one batch for each finished bottle. */
public record StationCraftBatch(NamespacedKey recipe, StationKind kind, ItemStack result) {
    public StationCraftBatch {
        Objects.requireNonNull(recipe, "recipe");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(result, "result");
        if (kind != StationKind.ANVIL && kind != StationKind.GRINDSTONE
                && kind != StationKind.CAULDRON && kind != StationKind.BREWING
                && kind != StationKind.FURNACE && kind != StationKind.BLAST_FURNACE
                && kind != StationKind.SMOKER && kind != StationKind.CAMPFIRE) {
            throw new IllegalArgumentException("This station kind has no proven committed batch");
        }
        result = result.clone();
    }

    @Override public ItemStack result() { return result.clone(); }
}
