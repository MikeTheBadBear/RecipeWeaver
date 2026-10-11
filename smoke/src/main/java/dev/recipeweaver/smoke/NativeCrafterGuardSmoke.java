package dev.recipeweaver.smoke;

import dev.recipeweaver.api.RecipeDefinition;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.RecipeWeaverApi;
import dev.recipeweaver.api.WeatherCondition;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Crafter;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.inventory.CraftingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import java.util.concurrent.CompletableFuture;
import org.bukkit.plugin.java.JavaPlugin;

/** Fires the native automatic craft event where that event exists. */
final class NativeCrafterGuardSmoke {
    private NativeCrafterGuardSmoke() { }

    /** Pulses a placed crafter with redstone and checks its actual inventories. */
    static CompletableFuture<Void> startPhysicalCrafterPulse(JavaPlugin smoke,
            RecipeWeaverApi api, NamespacedKey key, NamespacedKey exactKey) {
        return NativeCrafterPhysicalSmoke.start(smoke, api, key, exactKey);
    }

    static boolean verify(RecipeWeaverApi api, NamespacedKey key) {
        Class<?> eventType;
        try { eventType = Class.forName("org.bukkit.event.block.CrafterCraftEvent"); }
        catch (ClassNotFoundException ignored) { return false; }
        RecipeDefinition saved = api.recipe(key).orElseThrow();
        CraftingRecipe selected = (CraftingRecipe) Bukkit.getRecipe(key);
        check(selected != null, "Automatic craft recipe was not registered");
        Block block = Bukkit.getWorlds().get(0).getBlockAt(319, 80, 319);
        Material previous = block.getType();
        block.setType(Material.CRAFTER);
        try {
            setInput(block, 0, Material.STONE);
            setInput(block, 1, Material.DIRT);
            check(!fire(eventType, block, selected, saved.result()).isCancelled(),
                    "Valid RecipeWeaver automatic craft was denied");

            setInput(block, 0, Material.COBBLESTONE);
            check(fire(eventType, block, selected, saved.result()).isCancelled(),
                    "Automatic craft accepted a changed actual ingredient");
            setInput(block, 0, Material.STONE);
            check(fire(eventType, block, selected, new ItemStack(Material.DIAMOND)).isCancelled(),
                    "Automatic craft accepted a changed output");
            if (supportsRemainingItems(eventType)) {
                List<ItemStack> injected = emptyRemainingItems();
                injected.set(0, new ItemStack(Material.DIAMOND, 64));
                check(fire(eventType, block, selected, saved.result(), injected).isCancelled(),
                        "Automatic craft accepted a forged 64-item container remainder");
            }

            RecipeConditions worldOnly = new RecipeConditions(null, block.getWorld().getName(),
                    null, -1, -1, WeatherCondition.ANY, 0, null, 0, null);
            RecipeConditions playerOnly = new RecipeConditions("recipeweaver.smoke.crafter",
                    null, null, -1, -1, WeatherCondition.ANY, 0, null, 0, null);
            try {
                api.saveRecipe(withConditions(saved, worldOnly));
                check(!fire(eventType, block, selected, saved.result()).isCancelled(),
                        "Crafter denied a matching world condition");
                api.saveRecipe(withConditions(saved, playerOnly));
                check(fire(eventType, block, selected, saved.result()).isCancelled(),
                        "Crafter accepted a player-only permission condition");
            } finally {
                api.saveRecipe(saved);
            }

            check(api.removeRecipe(key), "Automatic craft fixture removal failed");
            check(fire(eventType, block, selected, saved.result()).isCancelled(),
                    "Automatic craft accepted a removed RecipeWeaver recipe");
            api.saveRecipe(saved);
            check(Bukkit.getRecipe(key) != null, "Automatic craft fixture restore failed");

            CraftingRecipe vanilla = vanilla();
            check(vanilla != null, "No vanilla automatic craft fixture is available");
            NamespacedKey vanillaKey = vanilla.getKey();
            check(!fire(eventType, block, vanilla, vanilla.getResult()).isCancelled(),
                    "Allowed vanilla automatic craft was denied");
            api.setVanillaRecipeDisabledAsync(vanillaKey, true);
            check(api.disabledVanillaRecipes().contains(vanillaKey),
                    "Vanilla automatic craft exclusion did not become active");
            check(fire(eventType, block, vanilla, vanilla.getResult()).isCancelled(),
                    "Automatic crafter ignored a vanilla exclusion");
            api.setVanillaRecipeDisabledAsync(vanillaKey, false);
            check(!api.disabledVanillaRecipes().contains(vanillaKey),
                    "Vanilla automatic craft exclusion was not cleared");
        } finally {
            block.setType(previous);
        }
        return true;
    }

    private static void setInput(Block block, int slot, Material material) {
        Crafter crafter = (Crafter) block.getState();
        crafter.getInventory().setItem(slot, new ItemStack(material));
        check(((Crafter) block.getState()).getInventory().getItem(slot).getType() == material,
                "Crafter input did not reach the live block inventory");
    }

    private static RecipeDefinition withConditions(RecipeDefinition saved,
                                                   RecipeConditions conditions) {
        return new RecipeDefinition(saved.key(), saved.kind(), saved.ingredients(),
                saved.result(), saved.group(), saved.enabled(), saved.hidden(),
                saved.matchModes(), saved.alternatives(), saved.itemTags(),
                saved.bookCategory(), conditions);
    }

    private static Cancellable fire(Class<?> type, Block block, CraftingRecipe recipe,
                                    ItemStack result) {
        return fire(type, block, recipe, result, emptyRemainingItems());
    }

    private static Cancellable fire(Class<?> type, Block block, CraftingRecipe recipe,
                                    ItemStack result, List<ItemStack> remainingItems) {
        try {
            Event event;
            try {
                Constructor<?> constructor = type.getConstructor(Block.class, CraftingRecipe.class,
                        ItemStack.class, List.class);
                event = (Event) constructor.newInstance(block, recipe, result.clone(),
                        remainingItems);
            } catch (NoSuchMethodException ignored) {
                Constructor<?> constructor = type.getConstructor(Block.class,
                        CraftingRecipe.class, ItemStack.class);
                event = (Event) constructor.newInstance(block, recipe, result.clone());
            }
            Bukkit.getPluginManager().callEvent(event);
            return (Cancellable) event;
        } catch (ReflectiveOperationException ex) {
            Throwable cause = ex instanceof InvocationTargetException invocation
                    ? invocation.getCause() : ex;
            throw new IllegalStateException("Could not fire automatic craft event", cause);
        }
    }

    private static boolean supportsRemainingItems(Class<?> type) {
        try {
            type.getMethod("getRemainingItems");
            type.getConstructor(Block.class, CraftingRecipe.class, ItemStack.class, List.class);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private static List<ItemStack> emptyRemainingItems() {
        return new ArrayList<>(Collections.nCopies(9, null));
    }

    private static CraftingRecipe vanilla() {
        var recipes = Bukkit.recipeIterator();
        while (recipes.hasNext()) {
            Recipe recipe = recipes.next();
            if (recipe instanceof CraftingRecipe crafting
                    && crafting.getKey().getNamespace().equals("minecraft")) return crafting;
        }
        return null;
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }
}
