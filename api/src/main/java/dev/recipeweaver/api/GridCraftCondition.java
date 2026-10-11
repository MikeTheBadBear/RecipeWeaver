package dev.recipeweaver.api;

import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Server-thread rule for a matching custom grid recipe. Inputs are defensive copies. */
@FunctionalInterface
public interface GridCraftCondition {
    boolean allows(Player player, GridRecipeDefinition recipe, List<ItemStack> inputs);
}
