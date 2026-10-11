package dev.recipeweaver.api;

import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Server-thread rule for an owned anvil, grindstone, cauldron, brewing, or cooking recipe.
 * Inputs are defensive copies. Furnace, blast furnace, smoker, and campfire rules receive
 * null for player and the single cooking input, and run before each native result commits.
 * A cauldron rule sees both one-item inputs when the second item is offered and is checked again
 * after its durable removal, before water and ingredients are committed. Brewing supplies null
 * for player and the ingredient plus one bottle; the rule may be checked repeatedly while brewing. */
@FunctionalInterface
public interface StationCraftCondition {
    boolean allows(Player player, StationRecipeDefinition recipe, List<ItemStack> inputs);
}
