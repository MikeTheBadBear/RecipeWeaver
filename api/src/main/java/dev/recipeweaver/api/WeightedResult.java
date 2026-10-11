package dev.recipeweaver.api;

import java.util.Objects;
import org.bukkit.inventory.ItemStack;

/** One possible grid craft output. Weight is relative to the other outcomes. */
public final class WeightedResult {
    public static final int MAX_WEIGHT = 10_000;
    private final ItemStack item;
    private final int weight;

    public WeightedResult(ItemStack item, int weight) {
        Objects.requireNonNull(item, "item");
        if (item.getType().isAir() || item.getAmount() < 1
                || item.getAmount() > item.getMaxStackSize()) {
            throw new IllegalArgumentException("A grid result must fit one item stack");
        }
        if (weight < 1 || weight > MAX_WEIGHT) {
            throw new IllegalArgumentException("Result weight must be 1 through " + MAX_WEIGHT);
        }
        this.item = item.clone();
        this.weight = weight;
    }

    public ItemStack item() { return item.clone(); }
    public int weight() { return weight; }
}
