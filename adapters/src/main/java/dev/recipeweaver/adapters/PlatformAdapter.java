package dev.recipeweaver.adapters;

import dev.recipeweaver.api.RecipeDefinition;
import org.bukkit.NamespacedKey;

public interface PlatformAdapter {
    void register(RecipeDefinition recipe);
    void unregister(NamespacedKey key);
    String platformName();
    String minecraftVersion();
}
