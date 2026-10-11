package dev.recipeweaver.smoke;

import dev.recipeweaver.api.CraftCommandAction;
import dev.recipeweaver.api.CraftCommandSender;
import dev.recipeweaver.api.GridCraftBatch;
import dev.recipeweaver.api.GridCraftCompleteEvent;
import dev.recipeweaver.api.GridRecipeDefinition;
import dev.recipeweaver.api.GridTier;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.RecipeKind;
import dev.recipeweaver.api.ResultMetadataMode;
import dev.recipeweaver.api.RecipeWeaverApi;
import dev.recipeweaver.api.WeightedResult;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Test-only probe for command storage, live dispatch, and the public craft event API. */
final class GridCommandSmoke {
    static final NamespacedKey KEY = new NamespacedKey("recipeweaver", "smoke_grid_command");
    static final NamespacedKey COOLDOWN_KEY = new NamespacedKey("recipeweaver", "smoke_grid_cooldown");
    static final NamespacedKey METADATA_KEY = new NamespacedKey("recipeweaver", "smoke_grid_metadata");
    static final NamespacedKey FULL_DATA_KEY = new NamespacedKey("recipeweaver", "smoke_grid_full_item_data");
    static final NamespacedKey CRAFT_LIMIT_KEY = new NamespacedKey("recipeweaver", "smoke_grid_craft_limit");
    static final NamespacedKey SWITCH_FIRST_KEY = new NamespacedKey("recipeweaver", "smoke_grid_switch_a");
    static final NamespacedKey SWITCH_SECOND_KEY = new NamespacedKey("recipeweaver", "smoke_grid_switch_b");
    static final NamespacedKey SHULKER_COPY_KEY = new NamespacedKey("recipeweaver", "smoke_grid_shulker_copy");
    static final NamespacedKey CUSTOM_DATA_MATCH_KEY = new NamespacedKey("recipeweaver", "smoke_grid_custom_data_match");
    private static final NamespacedKey SELECTED_RANK = new NamespacedKey("recipeweaver", "smoke_selected_rank");
    static final CraftCommandAction SAVED_ACTION = new CraftCommandAction(
            "rw info {player} {uuid}", CraftCommandSender.CONSOLE, false);

    private GridCommandSmoke() { }

    static void verify(RecipeWeaverApi api, Plugin plugin) {
        api.saveGridRecipe(recipe(SAVED_ACTION));
        api.saveGridRecipe(cooldownRecipe());
        api.saveGridRecipe(metadataRecipe());
        api.saveGridRecipe(fullDataRecipe());
        api.saveGridRecipe(craftLimitRecipe());
        api.saveGridRecipe(switchFirstRecipe());
        api.saveGridRecipe(switchSecondRecipe());
        api.saveGridRecipe(shulkerCopyRecipe());
        api.saveGridRecipe(customDataOnlyRecipe());
        checkRecovered(api);
        plugin.getLogger().info("RW_GRID_CUSTOM_DATA_ONLY_MATCH_PASS");
        plugin.getLogger().info("RW_GRID_SHIFT_LIMIT_PASS");
        try {
            GridTransactionSmoke.verify(api, plugin);
            plugin.getLogger().info("RW_GRID_TRANSACTION_PASS");
            plugin.getLogger().info("RW_GRID_EXTENSIONS_PASS");
            plugin.getLogger().info("RW_GRID_COOLDOWN_PASS");
            plugin.getLogger().info("RW_GRID_METADATA_PASS");
            plugin.getLogger().info("RW_GRID_COMMON_COMPONENTS_PASS");
            plugin.getLogger().info("RW_GRID_CRAFT_LIMIT_PASS");
            plugin.getLogger().info("RW_GRID_SHULKER_NO_DUP_PASS");
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not run live grid transaction probe", ex);
        }
        probeDispatch();
        probeEvent(plugin);
    }

    static void mutateForRestore(RecipeWeaverApi api) {
        api.saveGridRecipe(recipe(new CraftCommandAction(
                "say changed", CraftCommandSender.PLAYER, true), 1));
        check(!SAVED_ACTION.equals(api.gridRecipe(KEY).orElseThrow().craftCommand()),
                "Grid command backup mutation did not save");
        check(api.gridRecipe(KEY).orElseThrow().maxShiftBatches() == 1,
                "Grid shift limit backup mutation did not save");
    }

    static void checkRecovered(RecipeWeaverApi api) {
        check(SAVED_ACTION.equals(api.gridRecipe(KEY).orElseThrow().craftCommand()),
                "Grid command action was not recovered");
        check(api.gridRecipe(KEY).orElseThrow().maxShiftBatches() == 2,
                "Grid shift limit was not recovered");
        check(api.gridRecipe(COOLDOWN_KEY).orElseThrow().cooldownSeconds() == 5,
                "Grid cooldown was not recovered");
        check(api.gridRecipe(METADATA_KEY).orElseThrow().resultMetadataMode()
                        == ResultMetadataMode.ALL_COMMON,
                "Grid result metadata setting was not recovered");
        check(fullDataSaved(api), "Grid full item data setting was not recovered");
        check(api.gridRecipe(CRAFT_LIMIT_KEY).orElseThrow().maxCraftsPerPlayer() == 3,
                "Grid per-player craft limit was not recovered");
        check(api.gridRecipe(SWITCH_SECOND_KEY).orElseThrow().maxShiftBatches() == 1,
                "Second grid shift cap was not recovered");
        check(api.gridRecipe(SHULKER_COPY_KEY).orElseThrow().resultMetadataMode()
                        == ResultMetadataMode.ALL_COMMON,
                "Shulker result copy recipe was not recovered");
        check(customDataOnlyMatches(api),
                "Custom-data-only grid ingredient mode or typed PDC comparison was not recovered");
    }

    static boolean fullDataSaved(RecipeWeaverApi api) {
        return api.gridRecipe(FULL_DATA_KEY).map(recipe ->
                recipe.resultMetadataMode() == ResultMetadataMode.ALL_ITEM_DATA
                        && recipe.resultCopySourceSlot() == 0).orElse(false);
    }

    private static GridRecipeDefinition fullDataRecipe() {
        List<ItemStack> ingredients = new ArrayList<>(Arrays.asList(new ItemStack[4]));
        ingredients.set(0, new ItemStack(Material.FIREWORK_ROCKET));
        return new GridRecipeDefinition(FULL_DATA_KEY, GridTier.ELITE, RecipeKind.SHAPED, 2,
                ingredients, List.of(new WeightedResult(FullItemDataSmoke.rocket(2, "template"), 1)),
                "smoke", true, false, Collections.nCopies(4, IngredientMatchMode.MATERIAL),
                0, true, Collections.nCopies(4, List.of()), Collections.nCopies(4, null),
                Collections.nCopies(4, true), Collections.nCopies(4, null),
                Collections.nCopies(4, false), RecipeConditions.NONE, null, null, null,
                64, 0, 0, ResultMetadataMode.ALL_ITEM_DATA);
    }

    private static GridRecipeDefinition recipe(CraftCommandAction action) {
        return recipe(action, 2);
    }

    private static GridRecipeDefinition recipe(CraftCommandAction action, int maxShiftBatches) {
        List<ItemStack> ingredients = new ArrayList<>(Arrays.asList(new ItemStack[4]));
        ingredients.set(0, new ItemStack(Material.BLAZE_ROD));
        return new GridRecipeDefinition(KEY, GridTier.ELITE, RecipeKind.SHAPED, 2,
                ingredients, List.of(new WeightedResult(new ItemStack(Material.GOLD_INGOT), 1)),
                "smoke", true, false, Collections.nCopies(4, IngredientMatchMode.EXACT),
                0, true, Collections.nCopies(4, List.of()), Collections.nCopies(4, null),
                Collections.nCopies(4, true), Collections.nCopies(4, null),
                Collections.nCopies(4, false), RecipeConditions.NONE, null, null, action,
                maxShiftBatches);
    }

    private static GridRecipeDefinition cooldownRecipe() {
        List<ItemStack> ingredients = new ArrayList<>(Arrays.asList(new ItemStack[4]));
        ingredients.set(0, new ItemStack(Material.DIAMOND));
        return new GridRecipeDefinition(COOLDOWN_KEY, GridTier.ELITE, RecipeKind.SHAPED, 2,
                ingredients, List.of(new WeightedResult(new ItemStack(Material.EMERALD), 1)),
                "smoke", true, false, Collections.nCopies(4, IngredientMatchMode.EXACT),
                0, true, Collections.nCopies(4, List.of()), Collections.nCopies(4, null),
                Collections.nCopies(4, true), Collections.nCopies(4, null),
                Collections.nCopies(4, false), RecipeConditions.NONE, null, null, null,
                64, 5);
    }

    private static GridRecipeDefinition metadataRecipe() {
        List<ItemStack> ingredients = new ArrayList<>(Arrays.asList(new ItemStack[4]));
        ingredients.set(0, new ItemStack(Material.DIAMOND_SWORD));
        return new GridRecipeDefinition(METADATA_KEY, GridTier.ELITE, RecipeKind.SHAPED, 2,
                ingredients, List.of(new WeightedResult(new ItemStack(Material.IRON_SWORD), 1)),
                "smoke", true, false, Collections.nCopies(4, IngredientMatchMode.MATERIAL),
                0, true, Collections.nCopies(4, List.of()), Collections.nCopies(4, null),
                Collections.nCopies(4, true), Collections.nCopies(4, null),
                Collections.nCopies(4, false), RecipeConditions.NONE, null, null, null,
                64, 0, 0, ResultMetadataMode.ALL_COMMON);
    }

    private static GridRecipeDefinition craftLimitRecipe() {
        List<ItemStack> ingredients = new ArrayList<>(Arrays.asList(new ItemStack[4]));
        ingredients.set(0, new ItemStack(Material.FLINT));
        return new GridRecipeDefinition(CRAFT_LIMIT_KEY, GridTier.ELITE, RecipeKind.SHAPED, 2,
                ingredients, List.of(new WeightedResult(new ItemStack(Material.GOLD_NUGGET), 1)),
                "smoke", true, false, Collections.nCopies(4, IngredientMatchMode.EXACT),
                0, true, Collections.nCopies(4, List.of()), Collections.nCopies(4, null),
                Collections.nCopies(4, true), Collections.nCopies(4, null),
                Collections.nCopies(4, false), RecipeConditions.NONE, null, null, null,
                64, 0, -1, ResultMetadataMode.NONE, 3);
    }

    private static GridRecipeDefinition shulkerCopyRecipe() {
        List<ItemStack> ingredients = new ArrayList<>(Arrays.asList(new ItemStack[4]));
        ingredients.set(0, new ItemStack(Material.RED_SHULKER_BOX));
        ItemStack template = new ItemStack(Material.BLUE_SHULKER_BOX);
        ResultMetadataSmoke.setShulkerSlot(template, 0, new ItemStack(Material.DIRT, 4));
        return new GridRecipeDefinition(SHULKER_COPY_KEY, GridTier.ELITE, RecipeKind.SHAPED, 2,
                ingredients, List.of(new WeightedResult(template, 1)),
                "smoke", true, false, Collections.nCopies(4, IngredientMatchMode.MATERIAL),
                0, true, Collections.nCopies(4, List.of()), Collections.nCopies(4, null),
                Collections.nCopies(4, true), Collections.nCopies(4, null),
                Collections.nCopies(4, false), RecipeConditions.NONE, null, null, null,
                64, 0, 0, ResultMetadataMode.ALL_COMMON);
    }

    private static GridRecipeDefinition customDataOnlyRecipe() {
        ItemStack sample = new ItemStack(Material.PAPER);
        ItemMeta meta = sample.getItemMeta();
        meta.getPersistentDataContainer().set(SELECTED_RANK, PersistentDataType.INTEGER, 7);
        sample.setItemMeta(meta);
        List<ItemStack> ingredients = new ArrayList<>(Arrays.asList(new ItemStack[4]));
        ingredients.set(0, sample);
        return new GridRecipeDefinition(CUSTOM_DATA_MATCH_KEY, GridTier.ELITE,
                RecipeKind.SHAPED, 2, ingredients, new ItemStack(Material.EMERALD),
                "smoke", true, false,
                List.of(IngredientMatchMode.CUSTOM_DATA_ONLY, IngredientMatchMode.EXACT,
                        IngredientMatchMode.EXACT, IngredientMatchMode.EXACT), 0, true);
    }

    private static boolean customDataOnlyMatches(RecipeWeaverApi api) {
        GridRecipeDefinition recipe = api.gridRecipe(CUSTOM_DATA_MATCH_KEY).orElseThrow();
        if (recipe.matchModes().get(0) != IngredientMatchMode.CUSTOM_DATA_ONLY) return false;
        ItemStack allowed = new ItemStack(Material.PAPER);
        ItemMeta allowedMeta = allowed.getItemMeta();
        allowedMeta.setDisplayName("Unrelated name");
        allowedMeta.getPersistentDataContainer().set(SELECTED_RANK,
                PersistentDataType.INTEGER, 7);
        allowedMeta.getPersistentDataContainer().set(
                new NamespacedKey("recipeweaver", "smoke_extra"), PersistentDataType.STRING, "extra");
        allowed.setItemMeta(allowedMeta);
        ItemStack mismatch = allowed.clone();
        ItemMeta mismatchMeta = mismatch.getItemMeta();
        mismatchMeta.getPersistentDataContainer().set(SELECTED_RANK,
                PersistentDataType.INTEGER, 8);
        mismatch.setItemMeta(mismatchMeta);
        return recipe.matchesItemSample(0, allowed)
                && !recipe.matchesItemSample(0, mismatch)
                && !recipe.matchesItemSample(0, new ItemStack(Material.BOOK));
    }

    private static GridRecipeDefinition switchFirstRecipe() {
        List<ItemStack> ingredients = new ArrayList<>(Arrays.asList(new ItemStack[4]));
        ingredients.set(0, new ItemStack(Material.BLAZE_ROD));
        ingredients.set(1, new ItemStack(Material.COAL));
        return new GridRecipeDefinition(SWITCH_FIRST_KEY, GridTier.ELITE, RecipeKind.SHAPELESS, 2,
                ingredients, List.of(new WeightedResult(new ItemStack(Material.COPPER_INGOT), 1)),
                "smoke", true, false, Collections.nCopies(4, IngredientMatchMode.MATERIAL),
                0, true, Collections.nCopies(4, List.of()), Collections.nCopies(4, null),
                Arrays.asList(true, false, true, true), Collections.nCopies(4, null),
                Arrays.asList(false, true, false, false), RecipeConditions.NONE, null, null, null,
                64);
    }

    private static GridRecipeDefinition switchSecondRecipe() {
        List<ItemStack> ingredients = new ArrayList<>(Arrays.asList(new ItemStack[4]));
        ingredients.set(0, new ItemStack(Material.COAL));
        return new GridRecipeDefinition(SWITCH_SECOND_KEY, GridTier.ELITE, RecipeKind.SHAPELESS, 2,
                ingredients, List.of(new WeightedResult(new ItemStack(Material.LAPIS_LAZULI), 1)),
                "smoke", true, false, Collections.nCopies(4, IngredientMatchMode.MATERIAL),
                0, true, Collections.nCopies(4, List.of()), Collections.nCopies(4, null),
                Collections.nCopies(4, true), Collections.nCopies(4, null),
                Collections.nCopies(4, false), RecipeConditions.NONE, null, null, null,
                1);
    }

    private static Player syntheticPlayer() {
        UUID id = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> "SmokeCrafter";
                    case "getUniqueId" -> id;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private static void probeDispatch() {
        try {
            ClassLoader loader = Bukkit.getPluginManager().getPlugin("RecipeWeaver")
                    .getClass().getClassLoader();
            var dispatcher = Class.forName("dev.recipeweaver.core.GridCraftCommands", true, loader)
                    .getDeclaredMethod("execute", Player.class, List.class);
            dispatcher.setAccessible(true);
            List<String> failures = (List<String>) dispatcher.invoke(null, syntheticPlayer(),
                    List.of(SAVED_ACTION, SAVED_ACTION));
            check(failures.isEmpty(), "Live craft command dispatcher failed: " + failures);
            var injectable = Class.forName("dev.recipeweaver.core.GridCraftCommands", true, loader)
                    .getDeclaredMethod("execute", Player.class, List.class, BiPredicate.class);
            injectable.setAccessible(true);
            List<String> calls = new ArrayList<>();
            CraftCommandAction playerAction = new CraftCommandAction(
                    "rw info {player}", CraftCommandSender.PLAYER, true);
            BiPredicate<CommandSender, String> capture = (sender, command) -> {
                calls.add((sender instanceof Player ? "player:" : "console:") + command);
                return true;
            };
            List<String> captureFailures = (List<String>) injectable.invoke(null, syntheticPlayer(),
                    List.of(SAVED_ACTION, SAVED_ACTION, playerAction, playerAction), capture);
            check(captureFailures.isEmpty() && calls.equals(List.of(
                    "console:rw info SmokeCrafter 12345678-1234-1234-1234-123456789abc",
                    "player:rw info SmokeCrafter", "player:rw info SmokeCrafter")),
                    "Command sender, cadence, or placeholders were wrong: " + calls);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not run live craft command dispatcher", ex);
        }
    }

    private static void probeEvent(Plugin plugin) {
        AtomicInteger seen = new AtomicInteger();
        Listener listener = new Listener() { };
        Bukkit.getPluginManager().registerEvent(GridCraftCompleteEvent.class, listener,
                EventPriority.NORMAL, (registered, event) -> {
                    GridCraftCompleteEvent craft = (GridCraftCompleteEvent) event;
                    check(craft.player().getName().equals("SmokeCrafter"),
                            "Grid craft event lost its player");
                    check(craft.batches().size() == 1 && craft.batches().get(0).recipe().equals(KEY),
                            "Grid craft event lost its batch");
                    seen.incrementAndGet();
                }, plugin);
        try {
            Bukkit.getPluginManager().callEvent(new GridCraftCompleteEvent(syntheticPlayer(),
                    List.of(new GridCraftBatch(KEY, new ItemStack(Material.GOLD_INGOT)))));
            check(seen.get() == 1, "Bukkit did not deliver the public grid craft event");
        } finally {
            HandlerList.unregisterAll(listener);
        }
    }

    private static void check(boolean passed, String detail) {
        if (!passed) throw new IllegalStateException(detail);
    }
}
