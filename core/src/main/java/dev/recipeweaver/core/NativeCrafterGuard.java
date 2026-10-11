package dev.recipeweaver.core;

import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeDefinition;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Crafter;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

/** Rechecks automatic crafts where the server exposes CrafterCraftEvent. */
final class NativeCrafterGuard implements Listener {
    private final RecipeWeaverPlugin plugin;

    NativeCrafterGuard(RecipeWeaverPlugin plugin) { this.plugin = plugin; }

    @SuppressWarnings("unchecked")
    boolean register() {
        try {
            Class<?> type = Class.forName("org.bukkit.event.block.CrafterCraftEvent");
            if (!Event.class.isAssignableFrom(type) || !Cancellable.class.isAssignableFrom(type)) {
                plugin.logAsync(Level.WARNING, "Crafter craft event is not cancellable; protected native recipes will be withheld");
                return false;
            }
            Method getRecipe = type.getMethod("getRecipe");
            Method getResult = type.getMethod("getResult");
            Method getBlock = type.getMethod("getBlock");
            if (!Recipe.class.isAssignableFrom(getRecipe.getReturnType())
                    || !ItemStack.class.isAssignableFrom(getResult.getReturnType())
                    || !Block.class.isAssignableFrom(getBlock.getReturnType())) {
                plugin.logAsync(Level.WARNING, "Crafter craft event has incompatible accessors; protected native recipes will be withheld");
                return false;
            }
            Method getRemainingItems;
            try { getRemainingItems = type.getMethod("getRemainingItems"); }
            catch (NoSuchMethodException ignored) { getRemainingItems = null; }
            Method remainingItems = getRemainingItems;
            Bukkit.getPluginManager().registerEvent((Class<? extends Event>) type, this,
                    EventPriority.HIGHEST, (listener, event) -> onCraft(event, getRecipe,
                            getResult, getBlock, remainingItems), plugin, true);
            return true;
        } catch (ClassNotFoundException ignored) {
            // 1.20.6 has the crafter block but no cancellable Bukkit craft event.
            return false;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            plugin.logAsync(Level.WARNING, "Crafter craft event is unavailable; protected native recipes will be withheld: " + ex);
            return false;
        }
    }

    /** Without a cancellable event, only native material choices can be trusted. */
    static boolean nativeRegistrationAllowed(RecipeDefinition recipe, boolean guarded) {
        if (recipe == null || !recipe.enabled()) return false;
        if (guarded) return true;
        if (!recipe.conditions().unrestricted()) return false;
        for (int slot = 0; slot < 9; slot++) {
            if (recipe.hasIngredient(slot)
                    && recipe.matchModes().get(slot) != IngredientMatchMode.MATERIAL) {
                return false;
            }
        }
        return true;
    }

    private void onCraft(Event event, Method getRecipe, Method getResult, Method getBlock,
                         Method getRemainingItems) {
        try {
            Object selected = getRecipe.invoke(event);
            if (!(selected instanceof Recipe recipe) || !(recipe instanceof Keyed keyed)) return;
            ItemStack result = (ItemStack) getResult.invoke(event);
            NamespacedKey key = keyed.getKey();
            if (plugin.disabledVanillaRecipes().contains(key)) {
                ((Cancellable) event).setCancelled(true);
                return;
            }
            if (!key.getNamespace().equals("recipeweaver")) return;
            Block block = (Block) getBlock.invoke(event);
            ItemStack[] matrix = block.getState() instanceof Crafter crafter
                    ? crafter.getInventory().getContents() : null;
            RecipeDefinition saved = plugin.recipe(key).orElse(null);
            if (matrix == null || !NativeCraftGuard.allowsAutomatedRecipe(saved,
                    plugin.lockedDown(), matrix, result, block.getWorld(), block.getLocation())
                    || getRemainingItems != null && !safeRemainingItems(
                            (List<?>) getRemainingItems.invoke(event), matrix)) {
                ((Cancellable) event).setCancelled(true);
            }
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException ex) {
            ((Cancellable) event).setCancelled(true);
            plugin.logAsync(Level.WARNING, "Could not validate automatic craft: " + ex);
        }
    }

    /** Native RecipeWeaver recipes use Bukkit's ordinary one-item container remainders. */
    static boolean safeRemainingItems(List<?> remaining, ItemStack[] matrix) {
        if (remaining == null || matrix == null || matrix.length != 9) return false;
        int occupied = 0;
        for (ItemStack input : matrix) {
            if (input != null && !input.getType().isAir()) occupied++;
        }
        boolean compact = remaining.size() == occupied && occupied != 9;
        if (!compact && remaining.size() != 9) return false;
        int compactSlot = 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack input = matrix[slot];
            if (compact && (input == null || input.getType().isAir())) continue;
            Material expected = input == null || input.getType().isAir()
                    ? null : input.getType().getCraftingRemainingItem();
            Object value = remaining.get(compact ? compactSlot++ : slot);
            if (value != null && !(value instanceof ItemStack)) return false;
            if (expected == null) {
                if (value instanceof ItemStack item && !item.getType().isAir()
                        && item.getAmount() > 0) return false;
            } else if (!(value instanceof ItemStack item) || item.getType() != expected
                    || item.getAmount() != 1
                    || !item.isSimilar(new ItemStack(expected))) {
                return false;
            }
        }
        return true;
    }
}
