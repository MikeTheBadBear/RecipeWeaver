package dev.recipeweaver.smoke;

import dev.recipeweaver.api.GridRecipeDefinition;
import dev.recipeweaver.api.BackupFormat;
import dev.recipeweaver.api.GridTier;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeDefinition;
import dev.recipeweaver.api.RecipeKind;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.RecipeWeaverApi;
import dev.recipeweaver.api.SqlMirrorMode;
import dev.recipeweaver.api.SqlReadPolicy;
import dev.recipeweaver.api.SqlSourceStatus;
import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationRecipeDefinition;
import dev.recipeweaver.api.WeightedResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

import org.bukkit.inventory.ItemCraftResult;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.CraftingRecipe;
import org.bukkit.inventory.recipe.CookingBookCategory;
import org.bukkit.inventory.recipe.CraftingBookCategory;
import org.bukkit.plugin.java.JavaPlugin;

/** Isolated real-server probe. This module is never bundled into the installable plugin. */
public final class SmokePlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        try {
            var actionCommand = getCommand("recipeweaversmokeaction");
            if (actionCommand == null) throw new IllegalStateException("Smoke action command missing");
            actionCommand.setExecutor((sender, command, label, args) -> {
                if (args.length > 0 && args[0].equals("cooking")) {
                    return NativeFurnacePhysicalSmoke.onActionCommand(sender, args);
                }
                if (args.length > 0 && args[0].equals("cauldron")) {
                    return CauldronRulesSmoke.onCommand(sender, args);
                }
                return StationRuleSmoke.onCommand(sender, args);
            });
            Runnable liveChecks = verify();
            Bukkit.getScheduler().runTaskLater(this, () -> {
                try { liveChecks.run(); }
                catch (Throwable ex) { failSmoke(ex); }
            }, 260L);
        } catch (Throwable ex) {
            getLogger().severe("RW_SMOKE_FAIL " + ex);
            ex.printStackTrace();
            Bukkit.getScheduler().runTask(this, Bukkit::shutdown);
        }
    }

    private Runnable verify() throws Exception {
        RecipeWeaverApi api = Bukkit.getServicesManager().load(RecipeWeaverApi.class);
        check(api != null, "RecipeWeaver service missing");
        NamespacedKey providerKey = new NamespacedKey("smoke", "provider_item");
        ItemStack provided = new ItemStack(Material.DIAMOND, 2);
        check(api.registerItemProvider(this, "smoke", ignored -> provided),
                "Item provider registration failed");
        ItemStack firstProvided = api.resolveItem(providerKey).orElseThrow();
        firstProvided.setAmount(9);
        check(api.resolveItem(providerKey).orElseThrow().getAmount() == 2,
                "Item provider result was not cloned");
        check(api.unregisterItemProvider(this, "smoke"), "Item provider removal failed");
        check(api.resolveItem(providerKey).isEmpty(), "Removed item provider still resolves");
        getLogger().info("RW_ITEM_PROVIDER_PASS");
        getLogger().info("RW_SQL_MIRROR_PRIOR_SAVED="
                + (api.sqlMirrorMode() == SqlMirrorMode.SQLITE && api.sqlMirrorFailure() == null));
        getLogger().info("RW_MULTI_SQL_PRIOR_SAVED="
                + (api.sqlMirrorModes().equals(List.of(SqlMirrorMode.SQLITE, SqlMirrorMode.H2))
                        && api.sqlMirrorFailures().isEmpty()));
        getLogger().info("RW_SQL_RECOVERY_PRIOR_SAVED="
                + (api.sqlReadPolicy() == SqlReadPolicy.RECOVER_ON_STARTUP
                        && api.customItem(new NamespacedKey("recipeweaver", "smoke_multi_sql"))
                                .map(item -> item.getAmount() == 3).orElse(false)));
        getLogger().info("RW_SQL_PREFERENCE_PRIOR_SAVED="
                + (api.sqlReadPolicy() == SqlReadPolicy.PREFER_SQL_AT_STARTUP
                        && api.startupSqlSource() == SqlMirrorMode.SQLITE
                        && api.customItem(new NamespacedKey("recipeweaver", "smoke_local_only")).isEmpty()
                        && api.customItem(new NamespacedKey("recipeweaver", "smoke_multi_sql"))
                                .map(item -> item.getAmount() == 3).orElse(false)));
        getLogger().info("RW_GRID_SHIFT_PRIOR_SAVED=" + api.gridRecipe(
                GridCommandSmoke.KEY).map(recipe -> recipe.maxShiftBatches() == 2).orElse(false));
        getLogger().info("RW_GRID_COOLDOWN_PRIOR_SAVED=" + api.gridRecipe(
                GridCommandSmoke.COOLDOWN_KEY).map(recipe -> recipe.cooldownSeconds() == 5).orElse(false));
        getLogger().info("RW_GRID_METADATA_PRIOR_SAVED=" + api.gridRecipe(
                GridCommandSmoke.METADATA_KEY).map(recipe -> recipe.resultMetadataMode()
                        == dev.recipeweaver.api.ResultMetadataMode.ALL_COMMON).orElse(false));
        getLogger().info("RW_FULL_ITEM_DATA_GRID_PRIOR_SAVED="
                + GridCommandSmoke.fullDataSaved(api));
        getLogger().info("RW_GRID_CRAFT_LIMIT_PRIOR_SAVED=" + api.gridRecipe(
                GridCommandSmoke.CRAFT_LIMIT_KEY).map(recipe -> recipe.maxCraftsPerPlayer() == 3)
                .orElse(false));
        getLogger().info("RW_REMOTE_SQL_PRIOR_SAVED="
                + (api.remoteSqlBackend() == SqlMirrorMode.POSTGRESQL
                        && api.remoteSqlJdbcUrl().equals(
                                "jdbc:postgresql://127.0.0.1:5432/recipeweaver_smoke")
                        && api.remoteSqlUsername().equals("smoke_admin")
                        && api.remoteSqlPasswordConfigured()));
        var sqlStoreProbe = SqlStoreSmoke.start(this);
        NamespacedKey workbenchKey = key("smoke_workbench");
        NamespacedKey hiddenWorkbenchKey = key("smoke_hidden_workbench");
        NamespacedKey hiddenStationKey = key("smoke_hidden_station");
        NamespacedKey shapedKey = key("smoke_shaped");
        NamespacedKey exactChoicesKey = key("smoke_workbench_exact_choices");
        NamespacedKey tagChoicesKey = key("smoke_workbench_tag_choices");
        NamespacedKey furnaceKey = key("smoke_furnace");
        NamespacedKey stationChoicesKey = key("smoke_furnace_choices");
        NamespacedKey cutterKey = key("smoke_stonecutter");
        NamespacedKey trimKey = key("smoke_trim");
        NamespacedKey gridKey = key("smoke_elite_grid");
        NamespacedKey weightedGridKey = key("smoke_weighted_grid");
        boolean survivedRestart = api.recipe(workbenchKey).isPresent()
                && api.stationRecipe(furnaceKey).isPresent();
        getLogger().info("RW_SMOKE_PRIOR_SAVED=" + survivedRestart);
        getLogger().info("RW_NATIVE_BOOK_PRIOR_SAVED="
                + (api.recipe(hiddenWorkbenchKey).map(RecipeDefinition::hidden).orElse(false)
                        && api.stationRecipe(hiddenStationKey)
                                .map(StationRecipeDefinition::hidden).orElse(false)));
        getLogger().info("RW_NATIVE_CATEGORY_PRIOR_SAVED="
                + nativeCategoriesSaved(api, hiddenWorkbenchKey, hiddenStationKey));
        getLogger().info("RW_WORKBENCH_CHOICES_PRIOR_SAVED=" + api.recipe(tagChoicesKey)
                .map(saved -> saved.matchModes().get(0) == IngredientMatchMode.MATERIAL
                        && saved.alternatives().get(0).size() == 1
                        && NamespacedKey.fromString("minecraft:planks").equals(saved.itemTags().get(0)))
                .orElse(false));
        getLogger().info("RW_STATION_CHOICES_PRIOR_SAVED=" + api.stationRecipe(stationChoicesKey)
                .map(saved -> saved.matchModes().get(0) == IngredientMatchMode.MATERIAL
                        && saved.alternatives().get(0).size() == 1
                        && NamespacedKey.fromString("minecraft:planks").equals(saved.itemTags().get(0)))
                .orElse(false));
        getLogger().info("RW_STATION_METADATA_PRIOR_SAVED=" + StationRuleSmoke.metadataSaved(api));
        getLogger().info("RW_FULL_ITEM_DATA_STATION_PRIOR_SAVED="
                + StationRuleSmoke.fullDataSaved(api));
        getLogger().info("RW_STATION_INGREDIENTS_PRIOR_SAVED=" + StationRuleSmoke.ingredientsSaved(api));
        getLogger().info("RW_STATION_OPTIONAL_PRIOR_SAVED=" + StationRuleSmoke.optionalSaved(api));
        getLogger().info("RW_STATION_WEIGHTED_PRIOR_SAVED=" + StationRuleSmoke.weightedSaved(api));
        getLogger().info("RW_NATIVE_SMITHING_GUARD_PRIOR_SAVED="
                + NativeSmithingGuardSmoke.saved(api));
        getLogger().info("RW_STATION_RULES_PRIOR_SAVED="
                + (api.stationRecipe(StationRuleSmoke.ANVIL)
                        .map(saved -> saved.maxCraftsPerPlayer() == 2 && saved.cooldownSeconds() == 30
                                && StationRuleSmoke.CONDITIONS.equals(saved.conditions())
                                && StationRuleSmoke.ACTION_SOUND.equals(saved.craftSound())
                                && StationRuleSmoke.ACTION_ADVANCEMENT.equals(saved.craftAdvancement())
                                && StationRuleSmoke.ACTION_COMMAND.equals(saved.craftCommand()))
                        .orElse(false)
                    && api.stationRecipe(StationRuleSmoke.GRINDSTONE)
                        .map(saved -> saved.maxCraftsPerPlayer() == 1 && saved.cooldownSeconds() == 0
                                && StationRuleSmoke.CONDITIONS.equals(saved.conditions())
                                && StationRuleSmoke.ACTION_SOUND.equals(saved.craftSound()))
                        .orElse(false)));
        getLogger().info("RW_GRID_PRIOR_SAVED=" + api.gridRecipe(gridKey).isPresent());
        getLogger().info("RW_GRID_AMOUNT_PRIOR_SAVED=" + api.gridRecipe(gridKey)
                .map(saved -> saved.ingredients().get(0) != null
                        && saved.ingredients().get(0).getAmount() == 3).orElse(false));
        getLogger().info("RW_GRID_OPTIONS_PRIOR_SAVED=" + api.gridRecipe(gridKey)
                .map(saved -> saved.matchModes().get(0) == IngredientMatchMode.MATERIAL
                        && saved.priority() == 7 && !saved.allowMirror()).orElse(false));
        getLogger().info("RW_GRID_CHOICES_PRIOR_SAVED=" + api.gridRecipe(gridKey)
                .map(saved -> saved.alternatives().get(0).size() == 1
                        && saved.alternatives().get(0).get(0).getType() == Material.DIRT
                        && NamespacedKey.fromString("minecraft:planks").equals(saved.itemTags().get(0)))
                .orElse(false));
        getLogger().info("RW_GRID_RESULTS_PRIOR_SAVED=" + api.gridRecipe(weightedGridKey)
                .map(saved -> saved.resultChoices().size() == 2
                        && saved.resultChoices().get(0).weight() == 2
                        && saved.resultChoices().get(1).weight() == 3).orElse(false));
        getLogger().info("RW_GRID_USES_PRIOR_SAVED=" + api.gridRecipe(weightedGridKey)
                .map(saved -> !saved.consumes(15) && saved.consumes(0)
                        && saved.remainder(0) != null
                        && saved.remainder(0).getType() == Material.GOLD_NUGGET).orElse(false));
        getLogger().info("RW_GRID_CONDITIONS_PRIOR_SAVED=" + api.gridRecipe(GridConditionSmoke.KEY)
                .map(saved -> saved.conditions().permission() != null
                        && saved.conditions().biome() != null
                        && saved.conditions().advancement() != null
                        && saved.conditions().scoreboardObjective() != null).orElse(false));
        getLogger().info("RW_GRID_OPTIONAL_PRIOR_SAVED=" + api.gridRecipe(GridOptionalSmoke.KEY)
                .map(saved -> saved.optional(1)
                        && saved.result().getType() == Material.ECHO_SHARD).orElse(false));
        getLogger().info("RW_GRID_SOUND_PRIOR_SAVED=" + api.gridRecipe(GridSoundSmoke.KEY)
                .map(saved -> GridSoundSmoke.SAVED_SOUND.equals(saved.craftSound())).orElse(false));
        getLogger().info("RW_GRID_ADVANCEMENT_PRIOR_SAVED=" + api.gridRecipe(GridAdvancementSmoke.KEY)
                .map(saved -> GridAdvancementSmoke.SAVED_ACTION.equals(saved.craftAdvancement())).orElse(false));
        getLogger().info("RW_GRID_PARTIAL_PRIOR_SAVED=" + api.gridRecipe(GridPartialSmoke.KEY)
                .map(GridPartialSmoke::savedPrior).orElse(false));
        getLogger().info("RW_GRID_COMMAND_PRIOR_SAVED=" + api.gridRecipe(GridCommandSmoke.KEY)
                .map(saved -> GridCommandSmoke.SAVED_ACTION.equals(saved.craftCommand())).orElse(false));
        getLogger().info("RW_ITEM_METADATA_PRIOR_SAVED=" + api.customItem(ItemMetadataSmoke.KEY)
                .map(ItemMetadataSmoke::saved).orElse(false));
        boolean componentPriorSaved = ItemComponentSmoke.saved(api);
        getLogger().info("RW_ITEM_COMPONENT_PRIOR_SAVED=" + componentPriorSaved);
        if (!componentPriorSaved) {
            try { ItemComponentSmoke.checkRecovered(api); }
            catch (IllegalStateException ex) { getLogger().warning(ex.getMessage()); }
        }
        boolean advancedPriorSaved = AdvancedItemSmoke.saved(api);
        getLogger().info("RW_ADVANCED_ITEM_PRIOR_SAVED=" + advancedPriorSaved);
        if (!advancedPriorSaved) {
            try { AdvancedItemSmoke.checkRecovered(api); }
            catch (IllegalStateException ex) { getLogger().warning(ex.getMessage()); }
        }
        getLogger().info("RW_VANILLA_PRIOR_SAVED=" +
                (api.disabledVanillaRecipes().contains(VanillaRecipeSmoke.VANILLA_KEY)
                        && Bukkit.getRecipe(VanillaRecipeSmoke.VANILLA_KEY) == null));
        getLogger().info("RW_NATIVE_CRAFT_GUARD_PRIOR_SAVED="
                + NativeCraftGuardSmoke.saved(api));
        List<ItemStack> grid = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        grid.set(0, new ItemStack(Material.DIRT));
        api.saveRecipe(materialWorkbench(workbenchKey, RecipeKind.SHAPELESS, grid,
                new ItemStack(Material.DIAMOND), "smoke", true, false));
        check(api.recipe(workbenchKey).isPresent(), "Workbench save/read failed");
        check(Bukkit.getRecipe(workbenchKey) != null, "Workbench registry missing");
        List<ItemStack> shapedGrid = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        shapedGrid.set(0, new ItemStack(Material.STONE));
        shapedGrid.set(1, new ItemStack(Material.DIRT));
        api.saveRecipe(new RecipeDefinition(shapedKey, RecipeKind.SHAPED, shapedGrid,
                new ItemStack(Material.EMERALD), "smoke", true, false,
                java.util.Collections.nCopies(9, IngredientMatchMode.MATERIAL),
                java.util.Collections.nCopies(9, List.of()),
                java.util.Collections.nCopies(9, null)));
        check(Bukkit.getRecipe(shapedKey) != null, "Shaped registry missing");
        ItemStack[] shapelessMatrix = new ItemStack[9];
        shapelessMatrix[0] = new ItemStack(Material.DIRT, 2);
        ItemCraftResult shapelessCraft = Bukkit.craftItemResult(shapelessMatrix, Bukkit.getWorlds().get(0));
        check(shapelessCraft.getResult() != null && shapelessCraft.getResult().getType() == Material.DIAMOND,
                "Shapeless craft failed");
        check(shapelessCraft.getResultingMatrix()[0] != null
                && shapelessCraft.getResultingMatrix()[0].getAmount() == 1,
                "Shapeless consumption failed");
        ItemStack[] shapedMatrix = new ItemStack[9];
        shapedMatrix[0] = new ItemStack(Material.STONE);
        shapedMatrix[1] = new ItemStack(Material.DIRT);
        ItemCraftResult shapedCraft = Bukkit.craftItemResult(shapedMatrix, Bukkit.getWorlds().get(0));
        check(shapedCraft.getResult() != null && shapedCraft.getResult().getType() == Material.EMERALD,
                "Shaped craft failed");

        ItemStack[] mirror = new ItemStack[9];
        mirror[0] = new ItemStack(Material.DIRT);
        mirror[1] = new ItemStack(Material.STONE);
        ItemStack[] shifted = new ItemStack[9];
        shifted[4] = new ItemStack(Material.STONE);
        shifted[5] = new ItemStack(Material.DIRT);
        ItemStack[] shiftedMirror = new ItemStack[9];
        shiftedMirror[4] = new ItemStack(Material.DIRT);
        shiftedMirror[5] = new ItemStack(Material.STONE);
        check(craftType(mirror) == Material.EMERALD, "Mirrored shaped craft failed");
        check(craftType(shifted) == Material.EMERALD, "Shifted shaped craft failed");
        check(craftType(shiftedMirror) == Material.EMERALD, "Shifted and mirrored shaped craft failed");
        getLogger().info("RW_SHAPE_VARIANTS_PASS");
        List<ItemStack> exactInputs = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        exactInputs.set(0, new ItemStack(Material.LAPIS_LAZULI));
        exactInputs.set(1, new ItemStack(Material.NETHERITE_SCRAP));
        List<List<ItemStack>> exactAlternatives = emptyWorkbenchAlternatives();
        exactAlternatives.get(0).add(new ItemStack(Material.REDSTONE));
        api.saveRecipe(new RecipeDefinition(exactChoicesKey, RecipeKind.SHAPELESS, exactInputs,
                new ItemStack(Material.GOLD_NUGGET), "smoke", true, false,
                java.util.Collections.nCopies(9, IngredientMatchMode.EXACT), exactAlternatives,
                java.util.Collections.nCopies(9, null)));
        List<ItemStack> tagInputs = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        tagInputs.set(0, new ItemStack(Material.OAK_PLANKS));
        tagInputs.set(1, new ItemStack(Material.FLINT));
        List<List<ItemStack>> tagAlternatives = emptyWorkbenchAlternatives();
        tagAlternatives.get(0).add(new ItemStack(Material.DIRT));
        List<NamespacedKey> tagKeys = new ArrayList<>(java.util.Collections.nCopies(9, null));
        tagKeys.set(0, NamespacedKey.fromString("minecraft:planks"));
        List<IngredientMatchMode> tagModes = new ArrayList<>(
                java.util.Collections.nCopies(9, IngredientMatchMode.EXACT));
        tagModes.set(0, IngredientMatchMode.MATERIAL);
        api.saveRecipe(new RecipeDefinition(tagChoicesKey, RecipeKind.SHAPED, tagInputs,
                new ItemStack(Material.AMETHYST_SHARD), "smoke", true, false,
                tagModes, tagAlternatives, tagKeys));
        try {
            List<NamespacedKey> invalidTags = new ArrayList<>(tagKeys);
            invalidTags.set(0, key("missing_item_tag"));
            api.saveRecipeAsync(new RecipeDefinition(key("smoke_bad_workbench_tag"),
                    RecipeKind.SHAPED, tagInputs, new ItemStack(Material.AMETHYST_SHARD),
                    "smoke", true, false, tagModes, tagAlternatives, invalidTags));
            throw new IllegalStateException("Unknown workbench item tag was accepted");
        } catch (IllegalArgumentException expected) {
            check(api.recipe(key("smoke_bad_workbench_tag")).isEmpty(),
                    "Invalid workbench tag entered storage");
        }
        checkWorkbenchChoices(api);
        getLogger().info("RW_WORKBENCH_CHOICES_PASS");
        NamespacedKey sameOutputCutterKey = key("smoke_stonecutter_same_output");
        // A previous smoke run can leave this intentionally ambiguous choice on disk.
        // Remove it before the independent stale-result guard tests its unique key.
        api.removeStationRecipe(sameOutputCutterKey);
        api.saveStationRecipe(station(furnaceKey, StationKind.FURNACE,
                List.of(new ItemStack(Material.DIRT)), new ItemStack(Material.EMERALD)));
        api.saveStationRecipe(station(cutterKey, StationKind.STONECUTTER,
                List.of(new ItemStack(Material.STONE)), new ItemStack(Material.GOLD_INGOT)));
        api.saveStationRecipe(station(trimKey, StationKind.SMITHING_TRIM,
                List.of(new ItemStack(Material.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE),
                        new ItemStack(Material.DIAMOND_CHESTPLATE), new ItemStack(Material.REDSTONE)), null));
        List<NamespacedKey> stationTags = List.of(NamespacedKey.fromString("minecraft:planks"));
        api.saveStationRecipe(new StationRecipeDefinition(stationChoicesKey, StationKind.FURNACE,
                List.of(new ItemStack(Material.OAK_PLANKS)), new ItemStack(Material.GOLD_NUGGET),
                "smoke", 200, 0, 0, true, false,
                List.of(IngredientMatchMode.MATERIAL), List.of(List.of(new ItemStack(Material.REDSTONE))),
                stationTags));
        try {
            api.saveStationRecipeAsync(new StationRecipeDefinition(key("smoke_bad_station_tag"),
                    StationKind.FURNACE, List.of(new ItemStack(Material.OAK_PLANKS)),
                    new ItemStack(Material.GOLD_NUGGET), "smoke", 200, 0, 0, true, false,
                    List.of(IngredientMatchMode.MATERIAL), List.of(List.of()),
                    List.of(key("missing_station_tag"))));
            throw new IllegalStateException("Unknown station item tag was accepted");
        } catch (IllegalArgumentException expected) {
            check(api.stationRecipe(key("smoke_bad_station_tag")).isEmpty(),
                    "Invalid station tag entered storage");
        }
        checkStationChoices(api);
        getLogger().info("RW_STATION_CHOICES_PASS");
        check(api.stationRecipe(furnaceKey).isPresent(), "Furnace save/read failed");
        check(Bukkit.getRecipe(furnaceKey) != null, "Furnace registry missing");
        check(Bukkit.getRecipe(cutterKey) != null, "Stonecutter registry missing");
        NativeStonecutterGuardSmoke.verify(api, cutterKey);
        getLogger().info("RW_NATIVE_STONECUTTER_CONDITIONS_PASS");
        getLogger().info("RW_STONECUTTER_GUARD_PASS");
        NativeStonecutterGuardSmoke.verifyVanillaExclusion(api);
        getLogger().info("RW_VANILLA_STONECUTTER_GUARD_PASS");
        check(Bukkit.getRecipe(trimKey) != null, "Smithing trim registry missing");
        api.saveStationRecipe(station(key("smoke_blast"), StationKind.BLAST_FURNACE,
                List.of(new ItemStack(Material.DIRT)), new ItemStack(Material.DIAMOND)));
        api.saveStationRecipe(station(key("smoke_smoker"), StationKind.SMOKER,
                List.of(new ItemStack(Material.DIRT)), new ItemStack(Material.DIAMOND)));
        api.saveStationRecipe(station(key("smoke_campfire"), StationKind.CAMPFIRE,
                List.of(new ItemStack(Material.DIRT)), new ItemStack(Material.DIAMOND)));
        api.saveStationRecipe(station(key("smoke_smithing"), StationKind.SMITHING_TRANSFORM,
                List.of(new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                        new ItemStack(Material.DIAMOND_SWORD), new ItemStack(Material.NETHERITE_INGOT)),
                new ItemStack(Material.NETHERITE_SWORD)));
        api.saveStationRecipe(station(key("smoke_anvil"), StationKind.ANVIL,
                Arrays.asList(new ItemStack(Material.STONE), null), new ItemStack(Material.DIAMOND)));
        api.saveStationRecipe(station(key("smoke_grindstone"), StationKind.GRINDSTONE,
                Arrays.asList(new ItemStack(Material.STONE), null), new ItemStack(Material.EMERALD)));
        check(Bukkit.getRecipe(key("smoke_blast")) != null, "Blast furnace registry missing");
        check(Bukkit.getRecipe(key("smoke_smoker")) != null, "Smoker registry missing");
        check(Bukkit.getRecipe(key("smoke_campfire")) != null, "Campfire registry missing");
        check(Bukkit.getRecipe(key("smoke_smithing")) != null, "Smithing transform registry missing");
        NativeCookingGuardSmoke.verifyOwned(api, furnaceKey, key("smoke_blast"),
                key("smoke_smoker"), key("smoke_campfire"));
        getLogger().info("RW_NATIVE_COOKING_GUARD_PASS");
        NativeCookingGuardSmoke.verifyCookingExtensionConditions(this, api, furnaceKey,
                key("smoke_blast"), key("smoke_smoker"), key("smoke_campfire"));
        getLogger().info("RW_NATIVE_COOKING_EXTENSION_VETO_SYNTHETIC_PASS");
        var physicalCampfire = NativeCookingGuardSmoke.startPhysicalCampfirePulse(
                this, api, key("smoke_campfire"));
        var physicalFurnaces = NativeCookingGuardSmoke.startPhysicalFurnacePulse(
                this, api, furnaceKey, key("smoke_blast"), key("smoke_smoker"));
        NativeCookingGuardSmoke.verifyVanillaExclusion(api);
        getLogger().info("RW_VANILLA_COOKING_GUARD_PASS");
        check(api.stationRecipe(key("smoke_anvil")).isPresent(), "Anvil save/read failed");
        check(api.stationRecipe(key("smoke_grindstone")).isPresent(), "Grindstone save/read failed");
        StationRuleSmoke.verify(api, this);
        getLogger().info("RW_STATION_WEIGHTED_PASS");
        getLogger().info("RW_STATION_STALE_RESULT_PASS");
        NativeSmithingGuardSmoke.verify(api);
        getLogger().info("RW_NATIVE_SMITHING_CONDITIONS_PASS");
        getLogger().info("RW_NATIVE_SMITHING_GUARD_PASS");
        getLogger().info("RW_STATION_EXTENSIONS_PASS");
        getLogger().info("RW_STATION_RULES_PASS");
        getLogger().info("RW_STATION_CONDITIONS_PASS");
        getLogger().info("RW_STATION_ACTIONS_PASS");
        getLogger().info("RW_STATION_METADATA_PASS");
        getLogger().info("RW_FULL_ITEM_DATA_STATION_PASS");
        getLogger().info("RW_STATION_INGREDIENTS_PASS");
        getLogger().info("RW_STATION_OPTIONAL_PASS");

        List<ItemStack> eliteInputs = new ArrayList<>(Arrays.asList(new ItemStack[16]));
        eliteInputs.set(0, new ItemStack(Material.STONE, 3));
        eliteInputs.set(15, new ItemStack(Material.DIRT));
        List<IngredientMatchMode> eliteModes = new ArrayList<>(
                java.util.Collections.nCopies(16, IngredientMatchMode.EXACT));
        eliteModes.set(0, IngredientMatchMode.MATERIAL);
        List<List<ItemStack>> eliteAlternatives = new ArrayList<>();
        for (int i = 0; i < 16; i++) eliteAlternatives.add(new ArrayList<>());
        eliteAlternatives.get(0).add(new ItemStack(Material.DIRT));
        List<NamespacedKey> eliteTags = new ArrayList<>(java.util.Collections.nCopies(16, null));
        eliteTags.set(0, NamespacedKey.fromString("minecraft:planks"));
        api.saveGridRecipe(new GridRecipeDefinition(gridKey, GridTier.ELITE, RecipeKind.SHAPED,
                4, eliteInputs, new ItemStack(Material.LAPIS_LAZULI), "smoke", true, false,
                eliteModes, 7, false, eliteAlternatives, eliteTags));
        check(api.gridRecipe(gridKey).isPresent(), "Elite grid save/read failed");
        check(api.gridRecipe(gridKey).orElseThrow().ingredients().get(0).getAmount() == 3,
                "Elite grid ingredient amount was not saved");
        checkGridOptions(api.gridRecipe(gridKey).orElseThrow());
        List<NamespacedKey> missingTag = new ArrayList<>(eliteTags);
        missingTag.set(0, key("missing_item_tag_smoke"));
        boolean rejectedMissingTag = false;
        try {
            api.saveGridRecipeAsync(new GridRecipeDefinition(key("smoke_bad_tag"),
                    GridTier.ELITE, RecipeKind.SHAPED, 4, eliteInputs,
                    new ItemStack(Material.GOLD_INGOT), "smoke", true, false,
                    eliteModes, 0, false, eliteAlternatives, missingTag));
        } catch (IllegalArgumentException expected) {
            rejectedMissingTag = true;
        }
        check(rejectedMissingTag, "Unknown item tag was accepted for a grid recipe");
        check(Bukkit.getRecipe(gridKey) == null, "Elite grid must not register as a native 3x3 recipe");
        NamespacedKey lowerPriorityKey = key("smoke_aaa_grid_priority");
        api.saveGridRecipe(new GridRecipeDefinition(lowerPriorityKey, GridTier.ELITE,
                RecipeKind.SHAPED, 4, eliteInputs, new ItemStack(Material.GOLD_INGOT),
                "smoke", true, false, eliteModes, 0, false));
        List<ItemStack> overlappingInput = new ArrayList<>(Arrays.asList(new ItemStack[16]));
        overlappingInput.set(0, new ItemStack(Material.STONE, 3));
        overlappingInput.set(15, new ItemStack(Material.DIRT));
        check(gridResult(api, overlappingInput) == Material.LAPIS_LAZULI,
                "Higher-priority custom grid recipe did not win the overlap");
        check(api.removeGridRecipe(lowerPriorityKey), "Could not remove lower-priority smoke grid");
        getLogger().info("RW_GRID_API_PASS");
        getLogger().info("RW_GRID_OPTIONS_PASS");
        getLogger().info("RW_GRID_CHOICES_PASS");
        List<ItemStack> weightedInputs = new ArrayList<>(Arrays.asList(new ItemStack[16]));
        weightedInputs.set(0, new ItemStack(Material.COPPER_INGOT));
        weightedInputs.set(15, new ItemStack(Material.STONE));
        List<List<ItemStack>> weightedAlternatives = new ArrayList<>();
        for (int i = 0; i < 16; i++) weightedAlternatives.add(List.of());
        List<Boolean> weightedConsume = new ArrayList<>(java.util.Collections.nCopies(16, true));
        weightedConsume.set(15, false);
        List<ItemStack> weightedRemainders = new ArrayList<>(java.util.Collections.nCopies(16, null));
        weightedRemainders.set(0, new ItemStack(Material.GOLD_NUGGET));
        api.saveGridRecipe(new GridRecipeDefinition(weightedGridKey, GridTier.ELITE, RecipeKind.SHAPED,
                4, weightedInputs, List.of(new WeightedResult(new ItemStack(Material.EMERALD), 2),
                        new WeightedResult(new ItemStack(Material.DIAMOND), 3)), "smoke", true, false,
                java.util.Collections.nCopies(16, IngredientMatchMode.EXACT), 0, true,
                weightedAlternatives, java.util.Collections.nCopies(16, null),
                weightedConsume, weightedRemainders));
        check(api.gridRecipe(weightedGridKey).orElseThrow().totalResultWeight() == 5,
                "Weighted grid API lost result weights");
        boolean sawEmerald = false;
        boolean sawDiamond = false;
        for (int i = 0; i < 128; i++) {
            Material material = gridResult(api, weightedInputs);
            check(material == Material.EMERALD || material == Material.DIAMOND,
                    "Weighted grid returned an unexpected result");
            sawEmerald |= material == Material.EMERALD;
            sawDiamond |= material == Material.DIAMOND;
        }
        check(sawEmerald && sawDiamond, "Weighted grid did not expose both results");
        checkGridPreview(api, weightedInputs);
        getLogger().info("RW_GRID_RESULTS_PASS");
        checkGridUses(api.gridRecipe(weightedGridKey).orElseThrow(), weightedInputs);
        getLogger().info("RW_GRID_USES_PASS");
        RecipeConditions conditionExpected = GridConditionSmoke.verify(api);
        getLogger().info("RW_GRID_CONDITIONS_PASS");
        NativeGridFallbackSmoke.verify(api);
        getLogger().info("RW_NATIVE_GRID_FALLBACK_PASS");
        GridOptionalSmoke.verify(api);
        getLogger().info("RW_GRID_OPTIONAL_PASS");
        GridSoundSmoke.verify(api);
        getLogger().info("RW_GRID_SOUND_PASS");
        GridAdvancementSmoke.verify(api);
        getLogger().info("RW_GRID_ADVANCEMENT_PASS");
        GridPartialSmoke.verify(api);
        getLogger().info("RW_GRID_PARTIAL_PASS");
        GridCommandSmoke.verify(api, this);
        FieldMatchSmoke.verify(api, this);
        ResultMetadataSmoke.verifyArmorTrim();
        getLogger().info("RW_ARMOR_TRIM_RESULT_COPY_PASS");
        ResultMetadataSmoke.verifyMaxDurability();
        getLogger().info("RW_MAX_DURABILITY_RESULT_COPY_PASS");
        ResultMetadataSmoke.verifyCompatibleItemComponents();
        getLogger().info("RW_COMPATIBLE_COMPONENT_RESULT_COPY_PASS");
        ResultMetadataSmoke.verifyPotionBaseClearing();
        getLogger().info("RW_POTION_BASE_RESULT_COPY_PASS");
        ResultMetadataSmoke.verifyStoredEnchantments();
        getLogger().info("RW_STORED_ENCHANT_RESULT_COPY_PASS");
        ResultMetadataSmoke.verifyCrossbowProjectiles();
        getLogger().info("RW_CROSSBOW_RESULT_COPY_PASS");
        ResultMetadataSmoke.verifyStewEffects();
        getLogger().info("RW_STEW_RESULT_COPY_PASS");
        ResultMetadataSmoke.verifyFireworkEffects();
        getLogger().info("RW_FIREWORK_RESULT_COPY_PASS");
        ResultMetadataSmoke.verifyTropicalFishVariant();
        getLogger().info("RW_TROPICAL_FISH_RESULT_COPY_PASS");
        ResultMetadataSmoke.verifyFireworkStarEffect();
        getLogger().info("RW_FIREWORK_STAR_RESULT_COPY_PASS");
        ResultMetadataSmoke.verifyWrittenBook();
        getLogger().info("RW_WRITTEN_BOOK_RESULT_COPY_PASS");
        ResultMetadataSmoke.verifyCompassLodestone();
        getLogger().info("RW_COMPASS_RESULT_COPY_PASS");
        ResultMetadataSmoke.verifyBundleContents();
        getLogger().info("RW_BUNDLE_RESULT_COPY_PASS");
        ResultMetadataSmoke.verifyRepairCost();
        getLogger().info("RW_REPAIR_COST_RESULT_COPY_PASS");
        ResultMetadataSmoke.verifyAxolotlVariant();
        getLogger().info("RW_AXOLOTL_RESULT_COPY_PASS");
        ResultMetadataSmoke.verifyKnowledgeBookRecipes();
        getLogger().info("RW_KNOWLEDGE_BOOK_RESULT_COPY_PASS");
        ResultMetadataSmoke.verifyGoatHornInstrument();
        getLogger().info("RW_GOAT_HORN_RESULT_COPY_PASS");
        ResultMetadataSmoke.verifyOminousBottleLevel();
        getLogger().info("RW_OMINOUS_BOTTLE_RESULT_COPY_PASS");
        ResultMetadataSmoke.verifyMapAppearance();
        getLogger().info("RW_MAP_RESULT_COPY_PASS");
        ResultMetadataSmoke.verifyBlockItemState();
        getLogger().info("RW_BLOCK_ITEM_RESULT_COPY_PASS");
        boolean modernResultCopy = ResultMetadataSmoke.verifyModernItemComponents();
        getLogger().info("RW_RESULT_NAME_TYPED_PASS");
        getLogger().info(modernResultCopy ? "RW_RESULT_MODERN_COMPONENTS_PASS"
                : "RW_RESULT_MODERN_COMPONENTS_UNSUPPORTED_API");
        ResultMetadataSmoke.verifyFoodConsumptionCopy();
        getLogger().info("RW_RESULT_FOOD_CONSUMPTION_PASS");
        ResultMetadataSmoke.verifyAttributeModifierCopy();
        getLogger().info("RW_RESULT_ATTRIBUTE_MODIFIER_PASS");
        ResultMetadataSmoke.verifyPersistentCompoundCopy();
        getLogger().info("RW_RESULT_PERSISTENT_COMPOUND_PASS");
        boolean richModelData = ResultMetadataSmoke.verifyRichCustomModelDataCopy();
        getLogger().info("RW_COPY_MODEL_DATA_PASS");
        getLogger().info(richModelData ? "RW_COPY_RICH_MODEL_DATA_PASS"
                : "RW_COPY_RICH_MODEL_DATA_UNSUPPORTED_PASS");
        getLogger().info(ResultMetadataSmoke.verifyUseRemainderCopy()
                ? "RW_COPY_USE_REMAINDER_PASS" : "RW_COPY_USE_REMAINDER_UNSUPPORTED_PASS");
        getLogger().info(ResultMetadataSmoke.verifyUseCooldownCopy()
                ? "RW_COPY_USE_COOLDOWN_PASS" : "RW_COPY_USE_COOLDOWN_UNSUPPORTED_PASS");
        getLogger().info(ResultMetadataSmoke.verifyEquippableCopy()
                ? "RW_COPY_EQUIPPABLE_PASS" : "RW_COPY_EQUIPPABLE_UNSUPPORTED_PASS");
        ResultMetadataSmoke.verifyCustomDataReplacement();
        getLogger().info("RW_COPY_CUSTOM_DATA_REPLACEMENT_PASS");
        getLogger().info(ResultMetadataSmoke.verifyGliderCopy()
                ? "RW_COPY_GLIDER_PASS" : "RW_COPY_GLIDER_UNSUPPORTED_PASS");
        getLogger().info(ResultMetadataSmoke.verifyAdditionalTradeCostCopy()
                ? "RW_COPY_ADDITIONAL_TRADE_COST_PASS"
                : "RW_COPY_ADDITIONAL_TRADE_COST_UNSUPPORTED_PASS");
        getLogger().info(ResultMetadataSmoke.verifyMinimumAttackChargeCopy()
                ? "RW_COPY_MINIMUM_ATTACK_CHARGE_PASS"
                : "RW_COPY_MINIMUM_ATTACK_CHARGE_UNSUPPORTED_PASS");
        getLogger().info(ResultMetadataSmoke.verifyBreakSoundCopy()
                ? "RW_COPY_BREAK_SOUND_PASS" : "RW_COPY_BREAK_SOUND_UNSUPPORTED_PASS");
        getLogger().info(ResultMetadataSmoke.verifyJukeboxPlayableCopy()
                ? "RW_COPY_JUKEBOX_PASS" : "RW_COPY_JUKEBOX_UNSUPPORTED_PASS");
        getLogger().info(ResultMetadataSmoke.verifyDamageResistanceCopy()
                ? "RW_COPY_DAMAGE_RESISTANCE_PASS"
                : "RW_COPY_DAMAGE_RESISTANCE_UNSUPPORTED_PASS");
        ResultMetadataSmoke.verifyWritableBookPagesCopy();
        getLogger().info("RW_COPY_WRITABLE_BOOK_PAGES_PASS");
        ResultMetadataSmoke.verifyHeadProfileCopy();
        getLogger().info("RW_COPY_HEAD_PROFILE_PASS");
        ResultMetadataSmoke.verifyHeadNoteSoundCopy();
        getLogger().info("RW_COPY_HEAD_NOTE_SOUND_PASS");
        ResultMetadataSmoke.verifyShulkerBlockStateCopy();
        getLogger().info("RW_COPY_SHULKER_BLOCK_STATE_PASS");
        ResultMetadataSmoke.verifyCustomDataOnlyCopy();
        getLogger().info("RW_COPY_CUSTOM_DATA_ONLY_PASS");
        ResultMetadataSmoke.verifyModelOnlyCopy();
        getLogger().info("RW_COPY_MODEL_ONLY_PASS");
        ResultMetadataSmoke.verifyEnchantmentsOnlyCopy();
        getLogger().info("RW_COPY_ENCHANTMENTS_ONLY_PASS");
        ResultMetadataSmoke.verifyFoodOnlyCopy();
        getLogger().info("RW_COPY_FOOD_ONLY_PASS");
        ResultMetadataSmoke.verifyToolOnlyCopy();
        getLogger().info("RW_COPY_TOOL_ONLY_PASS");
        ResultMetadataSmoke.verifyAttributeModifiersOnlyCopy();
        getLogger().info("RW_COPY_ATTRIBUTE_MODIFIERS_ONLY_PASS");
        ResultMetadataSmoke.verifyDamageOnlyCopy();
        getLogger().info("RW_COPY_DAMAGE_ONLY_PASS");
        ResultMetadataSmoke.verifyItemFlagsOnlyCopy();
        getLogger().info("RW_COPY_ITEM_FLAGS_ONLY_PASS");
        ResultMetadataSmoke.verifyUnbreakableOnlyCopy();
        getLogger().info("RW_COPY_UNBREAKABLE_ONLY_PASS");
        ResultMetadataSmoke.verifyArmorTrimOnlyCopy();
        getLogger().info("RW_COPY_ARMOR_TRIM_ONLY_PASS");
        ResultMetadataSmoke.verifyPotionOnlyCopy();
        getLogger().info("RW_COPY_POTION_ONLY_PASS");
        ResultMetadataSmoke.verifyStoredEnchantmentsOnlyCopy();
        getLogger().info("RW_COPY_STORED_ENCHANTMENTS_ONLY_PASS");
        ResultMetadataSmoke.verifyBannerPatternsOnlyCopy();
        getLogger().info("RW_COPY_BANNER_PATTERNS_ONLY_PASS");
        ResultMetadataSmoke.verifyCrossbowProjectilesOnlyCopy();
        getLogger().info("RW_COPY_CROSSBOW_PROJECTILES_ONLY_PASS");
        ResultMetadataSmoke.verifyFireworkRocketOnlyCopy();
        getLogger().info("RW_COPY_FIREWORK_ROCKET_ONLY_PASS");
        ResultMetadataSmoke.verifyFireworkStarOnlyCopy();
        getLogger().info("RW_COPY_FIREWORK_STAR_ONLY_PASS");
        ResultMetadataSmoke.verifyBundleContentsOnlyCopy();
        getLogger().info("RW_COPY_BUNDLE_CONTENTS_ONLY_PASS");
        getLogger().info("RW_FULL_ITEM_DATA_GRID_PASS");
        getLogger().info("RW_GRID_COMMAND_PASS");
        getLogger().info("RW_GRID_EVENT_API_PASS");
        ItemMetadataSmoke.verify(api);
        getLogger().info("RW_ITEM_METADATA_PASS");
        ItemComponentSmoke.verify(api, this);
        getLogger().info("RW_ITEM_COMPONENT_PASS");
        ItemComponentSmoke.verifyCompoundArrayEditor();
        getLogger().info("RW_ITEM_COMPOUND_ARRAY_EDITOR_PASS");
        AdvancedItemSmoke.verify(api, this);
        AdvancedItemSmoke.verifyModernComponents(api, this);
        AdvancedItemSmoke.verifyAttributeModifiers(api, this);
        AdvancedItemSmoke.verifyItemFlagPartialMatching(this);
        AdvancedItemSmoke.verifyPersistentCompound(api, this);
        AdvancedItemSmoke.verifyItemTextImport(api, this);
        boolean richModelEditor = AdvancedItemSmoke.verifyRichModelDataEditor(api, this);
        boolean useRemainderEditor = AdvancedItemSmoke.verifyUseRemainderEditor(api, this);
        boolean useCooldownEditor = AdvancedItemSmoke.verifyUseCooldownEditor(api, this);
        boolean equippableEditor = AdvancedItemSmoke.verifyEquippableEditor(api, this);
        boolean gliderEditor = AdvancedItemSmoke.verifyGliderEditor(api, this);
        boolean tradeCostEditor = AdvancedItemSmoke.verifyAdditionalTradeCostEditor(api, this);
        boolean attackChargeEditor = AdvancedItemSmoke.verifyMinimumAttackChargeEditor(api, this);
        boolean breakSoundEditor = AdvancedItemSmoke.verifyBreakSoundEditor(api, this);
        boolean jukeboxEditor = AdvancedItemSmoke.verifyJukeboxPlayableEditor(api, this);
        boolean damageResistanceEditor = AdvancedItemSmoke.verifyDamageResistanceEditor(api, this);
        AdvancedItemSmoke.verifyWritableBookPagesEditor(api, this);
        AdvancedItemSmoke.verifyHeadProfileEditor(api, this);
        AdvancedItemSmoke.verifyHeadNoteSoundEditor(api, this);
        AdvancedItemSmoke.verifyShulkerContentsEditor(api, this);
        AdvancedItemSmoke.verifyGroupedToolRules(api, this);
        AdvancedItemSmoke.verifyBundleSlotEditor(api, this);
        getLogger().info("RW_ADVANCED_ITEM_PASS");
        getLogger().info("RW_TYPED_ITEM_DATA_PASS");
        VanillaRecipeSmoke.verify(api);
        getLogger().info("RW_VANILLA_OVERRIDE_PASS");
        NativeCraftGuardSmoke.verify(api);
        getLogger().info("RW_NATIVE_CRAFT_GUARD_PASS");
        getLogger().info("RW_WORKBENCH_CONDITIONS_PASS");
        if (NativeCrafterGuardSmoke.verify(api, shapedKey)) {
            getLogger().info("RW_NATIVE_CRAFTER_GUARD_PASS");
        }
        var physicalCrafter = new java.util.concurrent.CompletableFuture<Void>();

        NamespacedKey customItemKey = key("smoke_custom_item");
        api.saveCustomItem(customItemKey, new ItemStack(Material.GOLDEN_APPLE, 2));
        reloadForLegacyAssertions(api);
        check(AdvancedItemSmoke.itemTextSaved(api),
                "Imported Minecraft item text did not survive application reload");
        getLogger().info("RW_ITEM_TEXT_PERSISTED_PASS");
        if (richModelEditor) {
            check(AdvancedItemSmoke.richModelDataSaved(api),
                    "Rich custom model data did not survive application reload");
            getLogger().info("RW_PERSIST_RICH_MODEL_DATA_PASS");
        }
        if (useRemainderEditor) {
            check(AdvancedItemSmoke.useRemainderSaved(api),
                    "Use remainder did not survive application reload");
            getLogger().info("RW_PERSIST_USE_REMAINDER_PASS");
        }
        if (useCooldownEditor) {
            check(AdvancedItemSmoke.useCooldownSaved(api),
                    "Use cooldown did not survive application reload");
            getLogger().info("RW_PERSIST_USE_COOLDOWN_PASS");
        }
        if (equippableEditor) {
            check(AdvancedItemSmoke.equippableSaved(api),
                    "Equippable did not survive application reload");
            getLogger().info("RW_PERSIST_EQUIPPABLE_PASS");
        }
        if (gliderEditor) {
            check(AdvancedItemSmoke.gliderSaved(api),
                    "Glider did not survive application reload");
            getLogger().info("RW_PERSIST_GLIDER_PASS");
        }
        if (tradeCostEditor) {
            check(AdvancedItemSmoke.tradeCostSaved(api),
                    "Additional trade cost did not survive application reload");
            getLogger().info("RW_PERSIST_TRADE_COST_PASS");
        }
        if (attackChargeEditor) {
            check(AdvancedItemSmoke.attackChargeSaved(api),
                    "Minimum attack charge did not survive application reload");
            getLogger().info("RW_PERSIST_ATTACK_CHARGE_PASS");
        }
        if (breakSoundEditor) {
            check(AdvancedItemSmoke.breakSoundSaved(api),
                    "Break sound did not survive application reload");
            getLogger().info("RW_PERSIST_BREAK_SOUND_PASS");
        }
        if (jukeboxEditor) {
            check(AdvancedItemSmoke.jukeboxSaved(api),
                    "Jukebox song did not survive application reload");
            getLogger().info("RW_PERSIST_JUKEBOX_PASS");
        }
        if (damageResistanceEditor) {
            check(AdvancedItemSmoke.damageResistanceSaved(api),
                    "Damage resistance did not survive application reload");
            getLogger().info("RW_PERSIST_DAMAGE_RESISTANCE_PASS");
        }
        check(AdvancedItemSmoke.writablePagesSaved(api),
                "Writable book pages did not survive application reload");
        getLogger().info("RW_PERSIST_WRITABLE_PAGES_PASS");
        check(AdvancedItemSmoke.headProfileSaved(api),
                "Player-head profile did not survive application reload");
        getLogger().info("RW_PERSIST_HEAD_PROFILE_PASS");
        check(AdvancedItemSmoke.headNoteSoundSaved(api),
                "Player-head note sound did not survive application reload");
        getLogger().info("RW_PERSIST_HEAD_NOTE_SOUND_PASS");
        check(AdvancedItemSmoke.shulkerContentsSaved(api),
                "Shulker contents did not survive application reload");
        getLogger().info("RW_PERSIST_SHULKER_CONTENTS_PASS");
        check(AdvancedItemSmoke.groupedToolRuleSaved(api),
                "Grouped tool rules did not survive application reload");
        getLogger().info("RW_PERSIST_GROUPED_TOOL_RULE_PASS");
        check(AdvancedItemSmoke.bundleSlotEditorSaved(api),
                "Bundle slot edits did not survive application reload");
        getLogger().info("RW_PERSIST_BUNDLE_SLOT_EDITOR_PASS");
        checkWorkbenchChoices(api);
        checkStationChoices(api);
        check(api.customItem(customItemKey).orElseThrow().getAmount() == 2,
                "Queued custom-item save was not loaded");

        check(api.recipe(workbenchKey).isPresent(), "Workbench reload lost data");
        check(api.stationRecipe(furnaceKey).isPresent(), "Station reload lost data");
        check(api.gridRecipe(gridKey).isPresent(), "Grid reload lost data");
        check(api.gridRecipe(gridKey).orElseThrow().ingredients().get(0).getAmount() == 3,
                "Grid reload lost ingredient amount");
        checkGridOptions(api.gridRecipe(gridKey).orElseThrow());
        check(api.gridRecipe(weightedGridKey).orElseThrow().totalResultWeight() == 5,
                "Weighted grid reload lost result weights");
        checkGridUses(api.gridRecipe(weightedGridKey).orElseThrow(), weightedInputs);
        check(conditionExpected.equals(api.gridRecipe(GridConditionSmoke.KEY)
                        .orElseThrow().conditions()), "Grid reload lost conditions");
        GridOptionalSmoke.checkRecovered(api);
        GridSoundSmoke.checkRecovered(api);
        GridAdvancementSmoke.checkRecovered(api);
        GridPartialSmoke.checkRecovered(api);
        GridCommandSmoke.checkRecovered(api);
        FieldMatchSmoke.checkRecovered(api);
        getLogger().info("RW_PERSIST_FIELD_MATCH_MODES_PASS");
        check(StationRuleSmoke.fullDataSaved(api), "Station full item data reload lost recipes");
        check(StationRuleSmoke.weightedSaved(api), "Weighted station reload lost result choices");
        NativeSmithingGuardSmoke.checkSaved(api);
        ItemMetadataSmoke.checkRecovered(api);
        ItemComponentSmoke.checkRecovered(api);
        AdvancedItemSmoke.checkRecovered(api);
        VanillaRecipeSmoke.checkRecovered(api);
        NativeCraftGuardSmoke.checkSaved(api);
        getLogger().info("RW_GRID_AMOUNT_PASS");
        check(Bukkit.getRecipe(workbenchKey) != null, "Workbench reload lost registration");
        check(Bukkit.getRecipe(furnaceKey) != null, "Station reload lost registration");

        NamespacedKey queuedRecipeKey = key("smoke_async_workbench");
        List<ItemStack> queuedInputs = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        queuedInputs.set(0, new ItemStack(Material.REDSTONE));
        api.saveRecipeAsync(materialWorkbench(queuedRecipeKey, RecipeKind.SHAPELESS, queuedInputs,
                new ItemStack(Material.EMERALD), "smoke", true, false));
        reloadForLegacyAssertions(api);
        check(api.recipe(queuedRecipeKey).isPresent(), "Queued workbench save was not loaded");
        check(Bukkit.getRecipe(queuedRecipeKey) != null, "Queued workbench save was not registered");
        api.removeRecipeAsync(queuedRecipeKey);
        reloadForLegacyAssertions(api);
        check(api.recipe(queuedRecipeKey).isEmpty(), "Queued workbench deletion was not loaded");
        check(Bukkit.getRecipe(queuedRecipeKey) == null, "Queued workbench deletion remained registered");
        getLogger().info("RW_RECIPE_ASYNC_PASS");

        NamespacedKey queuedStationKey = key("smoke_async_furnace");
        NamespacedKey queuedGridKey = key("smoke_async_grid");
        api.saveStationRecipeAsync(station(queuedStationKey, StationKind.FURNACE,
                List.of(new ItemStack(Material.REDSTONE)), new ItemStack(Material.DIAMOND)));
        List<ItemStack> queuedGridInputs = new ArrayList<>(Arrays.asList(new ItemStack[4]));
        queuedGridInputs.set(0, new ItemStack(Material.STONE, 2));
        api.saveGridRecipeAsync(new GridRecipeDefinition(queuedGridKey, GridTier.ELITE, RecipeKind.SHAPED,
                2, queuedGridInputs, new ItemStack(Material.EMERALD), "smoke", true, false));
        reloadForLegacyAssertions(api);
        check(api.stationRecipe(queuedStationKey).isPresent(), "Queued station save was not loaded");
        check(Bukkit.getRecipe(queuedStationKey) != null, "Queued station save was not registered");
        check(api.gridRecipe(queuedGridKey).orElseThrow().ingredients().get(0).getAmount() == 2,
                "Queued grid save lost its ingredient amount");
        api.removeStationRecipeAsync(queuedStationKey);
        api.removeGridRecipeAsync(queuedGridKey);
        reloadForLegacyAssertions(api);
        check(api.stationRecipe(queuedStationKey).isEmpty(), "Queued station deletion was not loaded");
        check(Bukkit.getRecipe(queuedStationKey) == null, "Queued station deletion remained registered");
        check(api.gridRecipe(queuedGridKey).isEmpty(), "Queued grid deletion was not loaded");
        getLogger().info("RW_STATION_GRID_ASYNC_PASS");

        api.setBookItemEnabledAsync(true);
        api.setBackupRetentionAsync(7);
        api.setLockdownAsync(true);
        reloadForLegacyAssertions(api);
        check(api.bookItemEnabled() && api.backupRetention() == 7 && api.lockedDown(),
                "Global settings API changes were not durable after reload");
        check(Bukkit.getRecipe(workbenchKey) == null,
                "Global lockdown API left a native recipe registered");
        api.setLockdownAsync(false);
        api.setBookItemEnabledAsync(false);
        api.setBackupRetentionAsync(0);
        reloadForLegacyAssertions(api);
        check(!api.lockedDown() && !api.bookItemEnabled() && api.backupRetention() == 0,
                "Global settings API reset was not durable after reload");
        getLogger().info("RW_GLOBAL_SETTINGS_API_PASS");

        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "rw lockdown");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "rw lockdown");
        check(api.lockedDown(), "Lockdown was not enabled");
        check(Bukkit.getRecipe(workbenchKey) == null, "Workbench active during lockdown");
        check(Bukkit.getRecipe(furnaceKey) == null, "Furnace active during lockdown");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "rw lockdown");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "rw lockdown");
        check(!api.lockedDown(), "Lockdown was not disabled");
        reloadForLegacyAssertions(api);
        check(!api.lockedDown(), "Queued settings writes were not durable after reload");
        getLogger().info("RW_SETTINGS_ASYNC_PASS");
        check(Bukkit.getRecipe(workbenchKey) != null, "Workbench not restored");
        check(Bukkit.getRecipe(furnaceKey) != null, "Furnace not restored");

        Path backups = Bukkit.getPluginManager().getPlugin("RecipeWeaver").getDataFolder()
                .toPath().resolve("backups");
        api.setBackupFormatAsync(BackupFormat.ZIP);
        reloadForLegacyAssertions(api);
        check(api.backupFormat() == BackupFormat.ZIP,
                "Backup smoke setup did not restore ZIP format after an earlier interrupted run");
        Set<String> before = backupNames(backups);
        api.saveCustomItem(customItemKey, new ItemStack(Material.GOLDEN_APPLE, 3));
        java.util.UUID discoveryPlayer = java.util.UUID.fromString("00000000-0000-0000-0000-000000000512");
        org.bukkit.entity.Player synthetic = (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.entity.Player.class.getClassLoader(),
                new Class<?>[] {org.bukkit.entity.Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> discoveryPlayer;
                    case "getName" -> "RecipeWeaverSmoke";
                    default -> null;
                });
        List<ItemStack> hiddenWorkbenchInputs = new ArrayList<>(Arrays.asList(new ItemStack[9]));
        hiddenWorkbenchInputs.set(0, new ItemStack(Material.PURPLE_DYE));
        api.saveRecipe(categoryWorkbench(hiddenWorkbenchKey, hiddenWorkbenchInputs, true));
        api.saveStationRecipe(categoryStation(hiddenStationKey, true));
        check(Bukkit.getRecipe(hiddenWorkbenchKey) != null
                        && Bukkit.getRecipe(hiddenStationKey) != null,
                "Hidden native recipes were not registered for crafting");
        check(nativeCategoriesSaved(api, hiddenWorkbenchKey, hiddenStationKey),
                "Native recipe-book categories were not registered");
        getLogger().info("RW_NATIVE_CATEGORY_PASS");
        var visibleDiscovery = (org.bukkit.event.player.PlayerRecipeDiscoverEvent)
                discoveryEvent(synthetic, workbenchKey);
        Bukkit.getPluginManager().callEvent(visibleDiscovery);
        check(!visibleDiscovery.isCancelled(), "Visible workbench recipe discovery was blocked");
        var hiddenWorkbenchDiscovery = (org.bukkit.event.player.PlayerRecipeDiscoverEvent)
                discoveryEvent(synthetic, hiddenWorkbenchKey);
        Bukkit.getPluginManager().callEvent(hiddenWorkbenchDiscovery);
        check(hiddenWorkbenchDiscovery.isCancelled(), "Hidden workbench discovery was not blocked");
        var hiddenStationDiscovery = (org.bukkit.event.player.PlayerRecipeDiscoverEvent)
                discoveryEvent(synthetic, hiddenStationKey);
        Bukkit.getPluginManager().callEvent(hiddenStationDiscovery);
        check(hiddenStationDiscovery.isCancelled(), "Hidden station discovery was not blocked");
        NamespacedKey customOnlyBookKey = key("smoke_anvil");
        check(Bukkit.getRecipe(customOnlyBookKey) == null,
                "Custom-only anvil unexpectedly entered the Bukkit recipe registry");
        api.discoverRecipe(synthetic, customOnlyBookKey).toCompletableFuture().join();
        var customOnlyDiscovery = (org.bukkit.event.player.PlayerRecipeDiscoverEvent)
                discoveryEvent(synthetic, customOnlyBookKey);
        Bukkit.getPluginManager().callEvent(customOnlyDiscovery);
        check(customOnlyDiscovery.isCancelled(),
                "Earned custom-only station key entered the vanilla recipe book");
        getLogger().info("RW_CUSTOM_ONLY_NATIVE_BOOK_GUARD_PASS");
        getLogger().info("RW_NATIVE_BOOK_PASS");
        StationDiscoverySmoke.fire(synthetic, furnaceKey, cutterKey, key("smoke_smithing"));
        api.saveStationRecipe(station(sameOutputCutterKey, StationKind.STONECUTTER,
                List.of(new ItemStack(Material.STONE)), new ItemStack(Material.GOLD_INGOT)));
        getLogger().info(StationDiscoverySmoke.verifySelectedStonecutterApi(
                api, cutterKey, sameOutputCutterKey)
                ? "RW_STONECUTTER_SELECTED_API_PASS"
                : "RW_STONECUTTER_SELECTED_API_UNSUPPORTED_PASS");
        getLogger().info(NativeStonecutterGuardSmoke.verifySelectedGuardApi(
                api, cutterKey, sameOutputCutterKey)
                ? "RW_STONECUTTER_SELECTED_GUARD_SYNTHETIC_PASS"
                : "RW_STONECUTTER_SELECTED_GUARD_UNSUPPORTED_PASS");
        check(api.removeStationRecipe(sameOutputCutterKey),
                "Selected stonecutter smoke choice was not removed");
        NamespacedKey sameOutputFurnaceKey = key("smoke_furnace_same_output");
        api.saveStationRecipe(station(sameOutputFurnaceKey, StationKind.FURNACE,
                List.of(new ItemStack(Material.DEEPSLATE)), new ItemStack(Material.EMERALD)));
        check(Bukkit.getRecipe(sameOutputFurnaceKey) != null,
                "Equal-output furnace recipe was not registered");
        java.util.UUID ambiguousFurnacePlayer = java.util.UUID.fromString(
                "00000000-0000-0000-0000-000000000514");
        org.bukkit.entity.Player ambiguousSynthetic = (org.bukkit.entity.Player)
                java.lang.reflect.Proxy.newProxyInstance(org.bukkit.entity.Player.class.getClassLoader(),
                        new Class<?>[]{org.bukkit.entity.Player.class}, (proxy, method, arguments) ->
                                switch (method.getName()) {
                                    case "getUniqueId" -> ambiguousFurnacePlayer;
                                    case "getName" -> "RecipeWeaverAmbiguousFurnace";
                                    default -> null;
                                });
        StationDiscoverySmoke.fireAmbiguousFurnaceExtraction(ambiguousSynthetic, furnaceKey,
                sameOutputFurnaceKey, stationChoicesKey);
        java.util.UUID furnaceClickPlayer = java.util.UUID.fromString(
                "00000000-0000-0000-0000-000000000513");
        org.bukkit.entity.Player clickSynthetic = (org.bukkit.entity.Player)
                java.lang.reflect.Proxy.newProxyInstance(org.bukkit.entity.Player.class.getClassLoader(),
                        new Class<?>[]{org.bukkit.entity.Player.class}, (proxy, method, arguments) ->
                                switch (method.getName()) {
                                    case "getUniqueId" -> furnaceClickPlayer;
                                    case "getName" -> "RecipeWeaverFurnaceClick";
                                    default -> null;
                                });
        StationDiscoverySmoke.fireFurnaceResultClick(clickSynthetic, furnaceKey);
        api.discoverRecipe(synthetic, gridKey);
        String backupName = api.createBackupAsync().toCompletableFuture().join();
        Set<String> created = backupNames(backups);
        created.removeAll(before);
        check(created.size() == 1 && created.contains(backupName),
                "Async backup API did not create one snapshot");
        getLogger().info("RW_BACKUP_ASYNC_PASS " + backupName);
        Path discoveryFile = backups.getParent().resolve("discoveries").resolve(discoveryPlayer + ".yml");
        check(Files.isRegularFile(discoveryFile)
                && Files.readString(discoveryFile).contains(workbenchKey.toString())
                && Files.readString(discoveryFile).contains(gridKey.toString())
                && Files.readString(discoveryFile).contains(furnaceKey.toString())
                && Files.readString(discoveryFile).contains(cutterKey.toString())
                && Files.readString(discoveryFile).contains(key("smoke_smithing").toString()),
                "Queued discovery writes were not flushed before backup");
        check(!Files.readString(discoveryFile).contains(hiddenWorkbenchKey.toString())
                        && !Files.readString(discoveryFile).contains(hiddenStationKey.toString()),
                "Hidden native recipe discovery was persisted");
        check(api.hasDiscoveredRecipe(synthetic, furnaceKey)
                        && api.hasDiscoveredRecipe(synthetic, cutterKey)
                        && api.hasDiscoveredRecipe(synthetic, key("smoke_smithing")),
                "Player station completion events did not discover their recipes");
        getLogger().info("RW_STATION_DISCOVERY_PASS");
        Path ambiguousDiscovery = backups.getParent().resolve("discoveries")
                .resolve(ambiguousFurnacePlayer + ".yml");
        check(Files.isRegularFile(ambiguousDiscovery)
                        && Files.readString(ambiguousDiscovery).contains(furnaceKey.toString())
                        && Files.readString(ambiguousDiscovery).contains(sameOutputFurnaceKey.toString())
                        && !Files.readString(ambiguousDiscovery).contains(stationChoicesKey.toString()),
                "Equal-output furnace extraction did not discover both used recipes only");
        getLogger().info("RW_FURNACE_AMBIGUOUS_DISCOVERY_PASS");
        Path clickDiscovery = backups.getParent().resolve("discoveries")
                .resolve(furnaceClickPlayer + ".yml");
        check(Files.isRegularFile(clickDiscovery)
                        && Files.readString(clickDiscovery).contains(furnaceKey.toString())
                        && api.hasDiscoveredRecipe(clickSynthetic, furnaceKey),
                "Furnace result click did not persist its player discovery");
        getLogger().info("RW_FURNACE_CLICK_DISCOVERY_PASS");
        check(api.hasDiscoveredRecipe(synthetic, gridKey),
                "Bukkit service API did not report the durable grid discovery");
        getLogger().info("RW_DISCOVERY_API_PASS");
        try (java.util.zip.ZipFile snapshot = new java.util.zip.ZipFile(backups.resolve(backupName).toFile())) {
            check(snapshot.getEntry("discoveries/" + discoveryPlayer + ".yml") != null,
                    "Backup omitted the queued discovery");
            check(snapshot.getEntry("items.yml") != null, "Backup omitted the custom-item catalog");
        }
        getLogger().info("RW_DISCOVERY_ASYNC_PASS");
        reloadForLegacyAssertions(api);
        check(api.customItem(customItemKey).orElseThrow().getAmount() == 3,
                "Backup barrier did not flush the custom-item update");
        api.removeCustomItem(customItemKey);
        reloadForLegacyAssertions(api);
        check(api.customItem(customItemKey).isEmpty(), "Queued custom-item deletion was not loaded");
        api.saveRecipe(materialWorkbench(workbenchKey, RecipeKind.SHAPELESS, grid,
                new ItemStack(Material.GOLD_INGOT), "smoke", true, false));
        check(api.recipe(workbenchKey).orElseThrow().result().getType() == Material.GOLD_INGOT,
                "Backup mutation did not save");
        api.saveGridRecipe(new GridRecipeDefinition(gridKey, GridTier.ELITE, RecipeKind.SHAPED,
                4, eliteInputs, new ItemStack(Material.GOLD_INGOT), "smoke", true, false));
        check(api.gridRecipe(gridKey).orElseThrow().result().getType() == Material.GOLD_INGOT,
                "Grid backup mutation did not save");
        api.saveGridRecipe(new GridRecipeDefinition(weightedGridKey, GridTier.ELITE, RecipeKind.SHAPED,
                4, weightedInputs, new ItemStack(Material.GOLD_INGOT), "smoke", true, false));
        check(api.gridRecipe(weightedGridKey).orElseThrow().resultChoices().size() == 1,
                "Weighted grid backup mutation did not save");
        GridOptionalSmoke.mutateForRestore(api);
        GridSoundSmoke.mutateForRestore(api);
        GridAdvancementSmoke.mutateForRestore(api);
        GridPartialSmoke.mutateForRestore(api);
        GridCommandSmoke.mutateForRestore(api);
        ItemMetadataSmoke.mutateForRestore(api);
        ItemComponentSmoke.mutateForRestore(api);
        AdvancedItemSmoke.mutateForRestore(api);
        VanillaRecipeSmoke.mutateForRestore(api);
        NativeCraftGuardSmoke.mutateForRestore(api);
        NativeSmithingGuardSmoke.mutateForRestore(api);
        api.saveRecipe(categoryWorkbench(hiddenWorkbenchKey, hiddenWorkbenchInputs, false));
        api.saveStationRecipe(categoryStation(hiddenStationKey, false));
        check(!api.recipe(hiddenWorkbenchKey).orElseThrow().hidden()
                        && !api.stationRecipe(hiddenStationKey).orElseThrow().hidden(),
                "Native visibility mutation did not publish");
        var initialRestore = api.restoreDataAsync(backupName).toCompletableFuture();
        check(!initialRestore.isDone(), "Restore completed inline on the server thread");
        java.util.concurrent.CompletableFuture<Runnable> afterRestore =
                initialRestore.thenApply(previous -> {
            try {
                check(Bukkit.isPrimaryThread(), "Restore publication ran off the server thread");
                check(api.recipe(workbenchKey).orElseThrow().result().getType() == Material.DIAMOND,
                "Restore did not recover saved workbench data");
        check(Bukkit.getRecipe(workbenchKey) != null
                && Bukkit.getRecipe(workbenchKey).getResult().getType() == Material.DIAMOND,
                "Restore did not recover native registration");
        checkWorkbenchChoices(api);
        checkStationChoices(api);
        check(api.recipe(hiddenWorkbenchKey).orElseThrow().hidden()
                        && api.stationRecipe(hiddenStationKey).orElseThrow().hidden()
                        && Bukkit.getRecipe(hiddenWorkbenchKey) != null
                        && Bukkit.getRecipe(hiddenStationKey) != null,
                "Restore did not recover hidden native recipes");
        check(nativeCategoriesSaved(api, hiddenWorkbenchKey, hiddenStationKey),
                "Restore did not recover native recipe-book categories");
        check(api.gridRecipe(gridKey).orElseThrow().ingredients().get(0).getAmount() == 3,
                "Restore lost grid ingredient amount");
        checkGridOptions(api.gridRecipe(gridKey).orElseThrow());
        check(api.gridRecipe(gridKey).orElseThrow().result().getType() == Material.LAPIS_LAZULI,
                "Restore did not recover elite grid data");
        check(api.gridRecipe(weightedGridKey).orElseThrow().resultChoices().size() == 2
                        && api.gridRecipe(weightedGridKey).orElseThrow().totalResultWeight() == 5,
                "Restore did not recover weighted grid results");
        checkGridUses(api.gridRecipe(weightedGridKey).orElseThrow(), weightedInputs);
        check(conditionExpected.equals(api.gridRecipe(GridConditionSmoke.KEY)
                        .orElseThrow().conditions()), "Grid restore lost conditions");
        GridOptionalSmoke.checkRecovered(api);
        GridSoundSmoke.checkRecovered(api);
        GridAdvancementSmoke.checkRecovered(api);
        GridPartialSmoke.checkRecovered(api);
        GridCommandSmoke.checkRecovered(api);
        check(StationRuleSmoke.fullDataSaved(api), "Station full item data restore lost recipes");
        NativeSmithingGuardSmoke.checkSaved(api);
        ItemMetadataSmoke.checkRecovered(api);
        ItemComponentSmoke.checkRecovered(api);
        AdvancedItemSmoke.checkRecovered(api);
        VanillaRecipeSmoke.checkRecovered(api);
        NativeCraftGuardSmoke.checkSaved(api);
        check(api.customItem(customItemKey).orElseThrow().getAmount() == 3,
                "Restore did not recover the custom item");
        getLogger().info("RW_ITEM_ASYNC_PASS");
        check(backupNames(backups).size() == before.size() + 2,
                "Restore did not retain the pre-restore snapshot");
        getLogger().info("RW_SMOKE_BACKUP_RESTORE_PASS " + backupName);
        Runnable brew = StationSmoke.brewing(api, Bukkit.getWorlds().get(0), synthetic);
        Runnable auto = AutoCrafterSmoke.prepare(api, Bukkit.getWorlds().get(0), this);
        Set<String> beforeCommand = backupNames(backups);
        var asyncReload = api.reloadDataAsync().toCompletableFuture();
        check(!asyncReload.isDone(), "Async reload completed inline on the server thread");
        check(api.saveCustomItem(customItemKey, new ItemStack(Material.GOLDEN_APPLE, 5))
                        .toCompletableFuture().isCompletedExceptionally(),
                "Custom-item mutation was not fenced during reload");
        check(api.createBackupAsync().toCompletableFuture().isCompletedExceptionally(),
                "Backup was not fenced during reload");
        java.util.UUID freshDiscoveryId = java.util.UUID.randomUUID();
        org.bukkit.entity.Player freshDiscoveryPlayer = (org.bukkit.entity.Player)
                java.lang.reflect.Proxy.newProxyInstance(
                        org.bukkit.entity.Player.class.getClassLoader(),
                        new Class<?>[] {org.bukkit.entity.Player.class},
                        (proxy, method, arguments) -> switch (method.getName()) {
                            case "getUniqueId" -> freshDiscoveryId;
                            case "getName" -> "RecipeWeaverReloadSmoke";
                            default -> null;
                        });
        var deferredDiscovery = api.discoverRecipe(freshDiscoveryPlayer, furnaceKey).toCompletableFuture();
        check(!deferredDiscovery.isDone(), "Discovery was not deferred during reload");
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Void>>
                commandReload = new java.util.concurrent.atomic.AtomicReference<>();
        asyncReload.whenComplete((ignored, failure) -> {
            if (failure != null) return;
            java.util.concurrent.CompletableFuture<Void> command = startCommandReload(api);
            commandReload.set(command);
            command.whenComplete((done, commandFailure) -> {
                if (commandFailure == null) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "rw backup");
                    try {
                        NativeCrafterGuardSmoke.startPhysicalCrafterPulse(this, api, shapedKey,
                                exactChoicesKey)
                                .whenComplete((ignoredCrafter, crafterFailure) -> {
                                    if (crafterFailure == null) physicalCrafter.complete(null);
                                    else physicalCrafter.completeExceptionally(crafterFailure);
                                });
                    } catch (Throwable crafterFailure) {
                        physicalCrafter.completeExceptionally(crafterFailure);
                    }
                }
            });
        });
        return () -> {
            check(physicalCampfire.isDone() && !physicalCampfire.isCompletedExceptionally(),
                    "Physical campfire guard pulse did not complete successfully");
            physicalCampfire.join();
            check(physicalFurnaces.isDone() && !physicalFurnaces.isCompletedExceptionally(),
                    "Physical furnace, blast furnace, and smoker pulses did not complete successfully");
            physicalFurnaces.join();
            check(physicalCrafter.isDone(), "Physical crafter pulse did not complete");
            physicalCrafter.join();
            check(sqlStoreProbe.isDone() && !sqlStoreProbe.isCompletedExceptionally(),
                    "SQL store probe did not finish on RecipeWeaver-IO");
            var sqlResult = sqlStoreProbe.join();
            getLogger().info("RW_SQL_STORE_PASS");
            getLogger().info("RW_SQL_GLOBAL_GUARD_PASS");
            getLogger().info("RW_SQL_CONCURRENT_GUARD_PASS");
            getLogger().info("RW_SQL_PRIOR_SAVED="
                    + (sqlResult.sqlitePrior() && sqlResult.h2Prior()));
            check(asyncReload.isDone() && !asyncReload.isCompletedExceptionally(),
                    "Async reload did not finish on the live server");
            check(api.recipe(workbenchKey).isPresent()
                    && Bukkit.getRecipe(workbenchKey) != null
                    && api.customItem(customItemKey).orElseThrow().getAmount() == 3,
                    "Async reload lost persisted recipe or custom item");
            check(deferredDiscovery.isDone() && !deferredDiscovery.isCompletedExceptionally()
                            && deferredDiscovery.join()
                            && api.hasDiscoveredRecipe(freshDiscoveryPlayer, furnaceKey),
                    "Discovery during reload was not saved after publication");
            getLogger().info("RW_RELOAD_ASYNC_PASS");
            java.util.concurrent.CompletableFuture<Void> command = commandReload.get();
            check(command != null && command.isDone() && !command.isCompletedExceptionally(),
                    "Async /rw reload command did not complete");
            getLogger().info("RW_RELOAD_COMMAND_PASS");
            try {
                Set<String> commandArchives = backupNames(backups);
                commandArchives.removeAll(beforeCommand);
                check(commandArchives.size() == 1, "Backup command did not finish on the live server");
                getLogger().info("RW_BACKUP_COMMAND_PASS");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "rw backups");
            } catch (IOException ex) {
                throw new IllegalStateException("Could not inspect command backup", ex);
            }
            StationSmoke.cauldronAsync(api, Bukkit.getWorlds().get(0), this)
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) { failSmoke(failure); return; }
                        try {
                            check(Bukkit.isPrimaryThread(),
                                    "Cauldron transaction callback ran off the server thread");
                            getLogger().info("RW_CAULDRON_RUNTIME_PASS");
                            StationSmoke.cauldronConditionsAsync(api, Bukkit.getWorlds().get(0), this)
                                    .thenCompose(conditionsDone -> {
                                        check(Bukkit.isPrimaryThread(),
                                                "Cauldron condition callback ran off the server thread");
                                        check(StationSmoke.cauldronConditionsSaved(api),
                                                "Conditional cauldron recipe did not survive reload");
                                        getLogger().info("RW_CAULDRON_CONDITIONS_PASS");
                                        return StationSmoke.cauldronWeightedAsync(api,
                                                Bukkit.getWorlds().get(0), this);
                                    }).thenCompose(weightedDone -> {
                                        check(Bukkit.isPrimaryThread(),
                                                "Weighted cauldron callback ran off the server thread");
                                        check(StationSmoke.cauldronWeightedSaved(api),
                                                "Weighted cauldron choices did not survive reload");
                                        getLogger().info("RW_CAULDRON_WEIGHTED_PASS");
                                        return StationSmoke.cauldronExtensionsAsync(api,
                                                Bukkit.getWorlds().get(0), this);
                                    }).thenCompose(extensionsDone -> {
                                        check(Bukkit.isPrimaryThread(),
                                                "Cauldron extension callback ran off the server thread");
                                        getLogger().info("RW_CAULDRON_EXTENSIONS_PASS");
                                        return CauldronRulesSmoke.verifyAsync(api,
                                                Bukkit.getWorlds().get(0), this);
                                    }).whenComplete((rulesDone, rulesFailure) -> {
                                        if (rulesFailure != null) { failSmoke(rulesFailure); return; }
                                        try {
                                            check(Bukkit.isPrimaryThread(),
                                                    "Cauldron rule callback ran off the server thread");
                                            brew.run();
                                            getLogger().info("RW_BREW_RUNTIME_PASS");
                                            getLogger().info("RW_BREW_DISCOVERY_PASS");
                                            StationSmoke.brewingRecipeChangeAsync(api,
                                                    Bukkit.getWorlds().get(0), this)
                                                    .thenCompose(ignoredRecipeChange -> {
                                                        getLogger().info("RW_BREWING_RECIPE_CHANGE_PASS");
                                                        return StationSmoke.brewingConditionsAsync(api,
                                                                Bukkit.getWorlds().get(0), this);
                                                    }).whenComplete((ignoredBrewing, brewingFailure) -> {
                                                        if (brewingFailure != null) {
                                                            failSmoke(brewingFailure);
                                                            return;
                                                        }
                                                        try {
                                                            check(Bukkit.isPrimaryThread(),
                                                                    "Brewing condition callback ran off the server thread");
                                                            check(StationSmoke.brewingConditionsSaved(api,
                                                                            Bukkit.getWorlds().get(0).getName()),
                                                                    "Brewing conditions did not survive reload");
                                                            getLogger().info("RW_BREWING_CONDITIONS_PASS");
                                                            StationSmoke.brewingWeightedAsync(api,
                                                                    Bukkit.getWorlds().get(0), this)
                                                                    .thenCompose(weightedBrewing -> {
                                                                        check(Bukkit.isPrimaryThread(),
                                                                                "Weighted brewing callback ran off the server thread");
                                                                        getLogger().info("RW_BREWING_WEIGHTED_PASS");
                                                                        return StationSmoke.brewingAdvancedRulesAsync(api,
                                                                                Bukkit.getWorlds().get(0), this);
                                                                    }).thenCompose(advancedBrewing -> {
                                                                        check(Bukkit.isPrimaryThread(),
                                                                                "Advanced brewing callback ran off the server thread");
                                                                        getLogger().info("RW_BREWING_ADVANCED_RULES_PHYSICAL_PASS");
                                                                        return StationSmoke.brewingExtensionsAsync(api,
                                                                                Bukkit.getWorlds().get(0), this);
                                                                    }).whenComplete((brewingExtensions, extensionFailure) -> {
                                                                        if (extensionFailure != null) {
                                                                            failSmoke(extensionFailure);
                                                                            return;
                                                                        }
                                                                        try {
                                                                            check(Bukkit.isPrimaryThread(),
                                                                                    "Brewing extension callback ran off the server thread");
                                                                            getLogger().info("RW_BREWING_EXTENSIONS_PASS");
                                                                            auto.run();
                                                                            getLogger().info("RW_AUTOCRAFTER_RUNTIME_PASS");
                                                                            startTableSmoke(api, backupName, backups,
                                                                                    workbenchKey, gridKey,
                                                                                    customItemKey, grid, eliteInputs);
                                                                        } catch (Throwable ex) { failSmoke(ex); }
                                                                    });
                                                        } catch (Throwable ex) { failSmoke(ex); }
                                                    });
                                        } catch (Throwable ex) { failSmoke(ex); }
                                    });
                        } catch (Throwable ex) { failSmoke(ex); }
                    });
        };
            } catch (IOException ex) {
                throw new IllegalStateException("Could not inspect restored backups", ex);
            }
        });
        return () -> {
            check(afterRestore.isDone(), "Initial async restore did not finish on the live server");
            afterRestore.join().run();
        };
    }

    /** Uses a test-only reflection bridge to round-trip table state on a live Bukkit world. */
    private void startTableSmoke(RecipeWeaverApi api, String backupName, Path backups,
                                 NamespacedKey workbenchKey, NamespacedKey gridKey,
                                 NamespacedKey customItemKey, List<ItemStack> grid,
                                 List<ItemStack> eliteInputs) {
        var location = new org.bukkit.Location(Bukkit.getWorlds().get(0), 5012, 70, -5012);
        String position = location.getWorld().getUID() + "_5012_70_-5012";
        Path tableFile = backups.getParent().resolve("tables.yml");
        var saved = tableCall(api, "putTableAsync",
                new Class<?>[] {org.bukkit.Location.class, GridTier.class},
                location, GridTier.ADVANCED);
        check(!saved.isDone(), "Table file save completed inline on the server thread");
        check(api.createBackupAsync().toCompletableFuture().isCompletedExceptionally(),
                "Backup was not fenced during a pending table save");
        check(api.reloadDataAsync().toCompletableFuture().isCompletedExceptionally(),
                "Reload was not fenced during a pending table save");
        check(api.restoreDataAsync(backupName).toCompletableFuture().isCompletedExceptionally(),
                "Restore was not fenced during a pending table save");
        saved.whenComplete((ignored, saveFailure) -> {
            if (saveFailure != null) { failSmoke(saveFailure); return; }
            try {
                check(Bukkit.isPrimaryThread(), "Table save callback ran off the server thread");
                check(Files.readString(tableFile).contains(position),
                        "Asynchronous table save was not durable");
                java.util.concurrent.CompletableFuture<Boolean> removed = tableCall(api,
                        "removeTableAsync", new Class<?>[] {org.bukkit.Location.class}, location);
                removed.whenComplete((didRemove, removeFailure) -> {
                    if (removeFailure != null) { failSmoke(removeFailure); return; }
                    try {
                        check(Bukkit.isPrimaryThread(), "Table removal callback ran off the server thread");
                        check(didRemove && !Files.readString(tableFile).contains(position),
                                "Asynchronous table deletion was not durable");
                        getLogger().info("RW_TABLE_ASYNC_PASS");
                        startRestoreSmoke(api, backupName, backups, workbenchKey, gridKey,
                                customItemKey, grid, eliteInputs);
                    } catch (Throwable ex) { failSmoke(ex); }
                });
            } catch (Throwable ex) { failSmoke(ex); }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> java.util.concurrent.CompletableFuture<T> tableCall(
            RecipeWeaverApi api, String method, Class<?>[] parameters, Object... args) {
        try {
            var reflected = api.getClass().getDeclaredMethod(method, parameters);
            reflected.setAccessible(true);
            return ((java.util.concurrent.CompletionStage<T>) reflected.invoke(api, args))
                    .toCompletableFuture();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not call live table storage", ex);
        }
    }

    /** Exercises the production asynchronous API and command restore paths after earlier smoke checks. */
    private void startRestoreSmoke(RecipeWeaverApi api, String backupName, Path backups,
                                   NamespacedKey workbenchKey, NamespacedKey gridKey,
                                   NamespacedKey customItemKey, List<ItemStack> grid,
                                   List<ItemStack> eliteInputs) {
        api.saveRecipe(materialWorkbench(workbenchKey, RecipeKind.SHAPELESS, grid,
                new ItemStack(Material.GOLD_INGOT), "smoke", true, false));
        var restored = api.restoreDataAsync(backupName).toCompletableFuture();
        check(!restored.isDone(), "Restore API completed inline on the server thread");
        check(api.saveCustomItem(customItemKey, new ItemStack(Material.GOLDEN_APPLE, 5))
                        .toCompletableFuture().isCompletedExceptionally(),
                "Custom-item mutation was not fenced during restore");
        check(api.createBackupAsync().toCompletableFuture().isCompletedExceptionally(),
                "Backup was not fenced during restore");
        restored.whenComplete((previous, failure) -> {
            if (failure != null) { failSmoke(failure); return; }
            try {
                check(Bukkit.isPrimaryThread(), "Restore API published off the server thread");
                check(Files.isRegularFile(backups.resolve(previous)),
                        "Restore API omitted the pre-restore recovery backup");
                check(api.recipe(workbenchKey).orElseThrow().result().getType() == Material.DIAMOND,
                        "Restore API did not recover workbench data");
                check(Bukkit.getRecipe(workbenchKey) != null
                                && Bukkit.getRecipe(workbenchKey).getResult().getType() == Material.DIAMOND,
                        "Restore API did not recover native registration");
                checkWorkbenchChoices(api);
                checkStationChoices(api);
                check(api.gridRecipe(gridKey).orElseThrow().result().getType() == Material.LAPIS_LAZULI
                                && api.gridRecipe(gridKey).orElseThrow().ingredients().get(0).getAmount() == 3,
                        "Restore API lost elite grid data");
                checkGridOptions(api.gridRecipe(gridKey).orElseThrow());
                check(api.customItem(customItemKey).orElseThrow().getAmount() == 3,
                        "Restore API lost custom item");
                getLogger().info("RW_RESTORE_ASYNC_PASS");
                api.saveGridRecipe(new GridRecipeDefinition(gridKey, GridTier.ELITE, RecipeKind.SHAPED,
                        4, eliteInputs, new ItemStack(Material.GOLD_INGOT), "smoke", true, false));
                var commandRestore = startCommandRestore(api, backupName);
                check(!commandRestore.isDone(), "Restore command completed inline on the server thread");
                commandRestore.whenComplete((commandPrevious, commandFailure) -> {
                    if (commandFailure != null) { failSmoke(commandFailure); return; }
                    try {
                        check(Bukkit.isPrimaryThread(), "Restore command published off the server thread");
                        check(Files.isRegularFile(backups.resolve(commandPrevious)),
                                "Restore command omitted the pre-restore recovery backup");
                        check(api.gridRecipe(gridKey).orElseThrow().result().getType()
                                        == Material.LAPIS_LAZULI,
                                "Restore command did not recover elite grid data");
                        getLogger().info("RW_RESTORE_COMMAND_PASS");
                        startDirectoryBackupSmoke(api, backups, customItemKey);
                    } catch (Throwable ex) { failSmoke(ex); }
                });
            } catch (Throwable ex) { failSmoke(ex); }
        });
    }

    private java.util.concurrent.CompletableFuture<String> startCommandRestore(
            RecipeWeaverApi api, String name) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "rw restore " + name + " confirm");
        try {
            var field = api.getClass().getDeclaredField("activeRestore");
            field.setAccessible(true);
            Object value = field.get(api);
            if (!(value instanceof java.util.concurrent.CompletableFuture<?> future)) {
                throw new IllegalStateException("/rw restore did not start an asynchronous operation");
            }
            @SuppressWarnings("unchecked")
            java.util.concurrent.CompletableFuture<String> typed =
                    (java.util.concurrent.CompletableFuture<String>) future;
            return typed;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not inspect command restore", ex);
        }
    }

    private void startDirectoryBackupSmoke(RecipeWeaverApi api, Path backups,
                                           NamespacedKey customItemKey) {
        api.setBackupFormatAsync(BackupFormat.DIRECTORY).whenComplete((ignored, formatFailure) -> {
            if (formatFailure != null) { failSmoke(formatFailure); return; }
            try {
                check(Bukkit.isPrimaryThread() && api.backupFormat() == BackupFormat.DIRECTORY,
                        "Directory backup format was not published on the server thread");
                api.createBackupAsync().whenComplete((name, backupFailure) -> {
                    if (backupFailure != null) { failSmoke(backupFailure); return; }
                    Bukkit.getScheduler().runTask(this, () -> {
                        try {
                            check(name.endsWith(".dir")
                                            && Files.isRegularFile(backups.resolve(name).resolve("items.yml")),
                                    "Directory backup did not contain the item catalog");
                            api.listBackupsAsync().whenComplete((names, listFailure) -> {
                                if (listFailure != null) { failSmoke(listFailure); return; }
                                Bukkit.getScheduler().runTask(this, () -> {
                                    try {
                                        check(names.contains(name), "Directory backup was not listed");
                                        api.saveCustomItem(customItemKey,
                                                new ItemStack(Material.GOLDEN_APPLE, 6))
                                                .whenComplete((saved, saveFailure) -> {
                                            if (saveFailure != null) { failSmoke(saveFailure); return; }
                                            try {
                                                check(Bukkit.isPrimaryThread(), "Item save callback left main thread");
                                                api.restoreDataAsync(name).whenComplete((previous, restoreFailure) -> {
                                                    if (restoreFailure != null) { failSmoke(restoreFailure); return; }
                                                    try {
                                                        check(Bukkit.isPrimaryThread()
                                                                        && previous.endsWith(".dir")
                                                                        && Files.isDirectory(backups.resolve(previous)),
                                                                "Directory restore did not keep a recovery snapshot");
                                                        check(api.customItem(customItemKey).orElseThrow().getAmount() == 3,
                                                                "Directory restore did not recover the saved item");
                                                        api.setBackupFormatAsync(BackupFormat.ZIP)
                                                                .whenComplete((reset, resetFailure) -> {
                                                            if (resetFailure != null) { failSmoke(resetFailure); return; }
                                                            try {
                                                                check(api.backupFormat() == BackupFormat.ZIP,
                                                                        "Backup format was not reset to ZIP");
                                                                getLogger().info("RW_DIRECTORY_BACKUP_PASS");
                                                                finishShutdownSmoke(api);
                                                            } catch (Throwable ex) { failSmoke(ex); }
                                                        });
                                                    } catch (Throwable ex) { failSmoke(ex); }
                                                });
                                            } catch (Throwable ex) { failSmoke(ex); }
                                        });
                                    } catch (Throwable ex) { failSmoke(ex); }
                                });
                            });
                        } catch (Throwable ex) { failSmoke(ex); }
                    });
                });
            } catch (Throwable ex) { failSmoke(ex); }
        });
    }

    private void finishShutdownSmoke(RecipeWeaverApi api) {
        SqlStoreSmoke.snapshotRoundTrip(this, api).whenComplete((backup, failure) -> {
            if (failure != null) { failSmoke(failure); return; }
            Runnable finish = () -> {
                try {
                    getLogger().info("RW_SQL_SNAPSHOT_PASS " + backup);
                    finishMirrorSmoke(api);
                } catch (Throwable ex) { failSmoke(ex); }
            };
            if (Bukkit.isPrimaryThread()) finish.run();
            else Bukkit.getScheduler().runTask(this, finish);
        });
    }

    private void finishMirrorSmoke(RecipeWeaverApi api) {
        api.setSqlMirrorModeAsync(SqlMirrorMode.SQLITE).whenComplete((selected, selectFailure) -> {
            if (selectFailure != null) { failSmoke(selectFailure); return; }
            Bukkit.getScheduler().runTask(this, () -> {
                try {
                    check(api.sqlMirrorMode() == SqlMirrorMode.SQLITE
                                    && api.sqlMirrorFailure() == null,
                            "SQLite live mirror did not become current");
                    api.saveCustomItem(new NamespacedKey("recipeweaver", "smoke_sql_mirror"),
                            new ItemStack(Material.GOLDEN_APPLE, 2)).whenComplete((saved, saveFailure) -> {
                        if (saveFailure != null) { failSmoke(saveFailure); return; }
                        try {
                            SqlStoreSmoke.verifyLiveMirror(this).whenComplete((verified, verifyFailure) -> {
                                if (verifyFailure != null) { failSmoke(verifyFailure); return; }
                                Bukkit.getScheduler().runTask(this, () -> {
                                    try {
                                        getLogger().info("RW_SQL_MIRROR_PASS");
                                        finishMultiSqlSmoke(api);
                                    } catch (Throwable ex) { failSmoke(ex); }
                                });
                            });
                        } catch (ReflectiveOperationException ex) { failSmoke(ex); }
                    });
                } catch (Throwable ex) { failSmoke(ex); }
            });
        });
    }

    private void finishMultiSqlSmoke(RecipeWeaverApi api) {
        List<SqlMirrorMode> selected = List.of(SqlMirrorMode.SQLITE, SqlMirrorMode.H2);
        api.setSqlMirrorModesAsync(selected)
                .thenCompose(ignored -> resolveSmokeSqlConflicts(api))
                .whenComplete((ignored, selectionFailure) -> {
            if (selectionFailure != null) { failSmoke(selectionFailure); return; }
            Bukkit.getScheduler().runTask(this, () -> {
                try {
                    check(api.sqlMirrorModes().equals(selected) && api.sqlMirrorFailures().isEmpty(),
                            "Two SQL mirrors were not selected and synchronized");
                    api.saveCustomItem(new NamespacedKey("recipeweaver", "smoke_multi_sql"),
                            new ItemStack(Material.EMERALD, 3)).whenComplete((saved, saveFailure) -> {
                        if (saveFailure != null) { failSmoke(saveFailure); return; }
                        try {
                            SqlStoreSmoke.verifyLiveMirrors(this).whenComplete((verified, verifyFailure) -> {
                                if (verifyFailure != null) { failSmoke(verifyFailure); return; }
                                Bukkit.getScheduler().runTask(this, () -> {
                                    try {
                                        api.setSqlMirrorModesAsync(List.of(SqlMirrorMode.H2,
                                                        SqlMirrorMode.SQLITE))
                                                .thenCompose(ordered -> api.reloadDataAsync())
                                                .whenComplete((reloaded, reloadFailure) -> {
                                                    if (reloadFailure != null) {
                                                        failSmoke(reloadFailure);
                                                        return;
                                                    }
                                                    Bukkit.getScheduler().runTask(this, () -> {
                                                        try {
                                                            check(api.sqlMirrorModes().equals(List.of(
                                                                            SqlMirrorMode.H2,
                                                                            SqlMirrorMode.SQLITE)),
                                                                    "SQL mirror order did not survive reload");
                                                            finishMultiSqlRetry(api, selected);
                                                        } catch (Throwable ex) { failSmoke(ex); }
                                                    });
                                                });
                                    } catch (Throwable ex) { failSmoke(ex); }
                                });
                            });
                        } catch (ReflectiveOperationException ex) { failSmoke(ex); }
                    });
                } catch (Throwable ex) { failSmoke(ex); }
            });
        });
    }

    private java.util.concurrent.CompletionStage<Void> resolveSmokeSqlConflicts(RecipeWeaverApi api) {
        var failures = api.sqlMirrorFailures();
        if (failures.isEmpty()) return java.util.concurrent.CompletableFuture.completedFuture(null);
        return api.auditSqlSourcesAsync().thenCompose(statuses -> {
            java.util.concurrent.CompletionStage<Void> pending =
                    java.util.concurrent.CompletableFuture.completedFuture(null);
            for (SqlSourceStatus status : statuses) {
                if (!failures.containsKey(status.backend())) continue;
                check(status.sha256() != null, "Could not inspect conflicted " + status.backend());
                pending = pending.thenCompose(ignored -> api.replaceSqlMirrorFromLocalAsync(
                        status.backend(), status.sha256()).thenApply(replaced -> {
                    check(replaced, "Could not choose local files for " + status.backend());
                    return null;
                }));
            }
            return pending.thenRun(() -> getLogger().info("RW_SQL_CONFLICT_CHOICE_PASS"));
        });
    }

    private void finishMultiSqlRetry(RecipeWeaverApi api, List<SqlMirrorMode> selected) {
        api.setSqlMirrorModesAsync(selected)
                .thenCompose(ignored -> api.resyncSqlMirrorAsync(SqlMirrorMode.H2))
                .whenComplete((resynced, retryFailure) -> {
                    if (retryFailure != null || !resynced) {
                        failSmoke(retryFailure == null
                                ? new IllegalStateException("H2 targeted retry failed") : retryFailure);
                        return;
                    }
                    Bukkit.getScheduler().runTask(this, () -> {
                        try {
                            check(api.sqlMirrorModes().equals(selected)
                                            && api.sqlMirrorFailures().isEmpty(),
                                    "SQL mirrors did not return to the saved order");
                            getLogger().info("RW_MULTI_SQL_PASS");
                            finishSqlSourceAuditSmoke(api);
                        } catch (Throwable ex) { failSmoke(ex); }
                    });
                });
    }

    private void finishSqlSourceAuditSmoke(RecipeWeaverApi api) {
        api.auditSqlSourcesAsync().whenComplete((statuses, auditFailure) -> {
            if (auditFailure != null) { failSmoke(auditFailure); return; }
            Bukkit.getScheduler().runTask(this, () -> {
                try {
                    check(statuses.size() == 2
                                    && statuses.get(0).backend() == SqlMirrorMode.SQLITE
                                    && statuses.get(1).backend() == SqlMirrorMode.H2
                                    && statuses.stream().allMatch(SqlSourceStatus::current),
                            "Ordered SQL source audit did not find two current targets");
                    check(statuses.get(0).sha256().equals(statuses.get(1).sha256()),
                            "SQL source digests differ after synchronization");
                    api.prepareOrderedSqlRestoreAsync().whenComplete((prepared, prepareFailure) -> {
                        if (prepareFailure != null) { failSmoke(prepareFailure); return; }
                        Bukkit.getScheduler().runTask(this, () -> {
                            try {
                                check(prepared.backend() == SqlMirrorMode.SQLITE,
                                        "Ordered SQL restore chose the wrong first source");
                                Path dataRoot = Bukkit.getPluginManager().getPlugin("RecipeWeaver")
                                        .getDataFolder().toPath();
                                check(Files.isRegularFile(dataRoot.resolve("backups")
                                                .resolve(prepared.backupName()).resolve("settings.yml")),
                                        "Ordered SQL restore did not prepare a settings backup");
                                getLogger().info("RW_SQL_SOURCE_AUDIT_PASS "
                                        + prepared.backend() + " " + prepared.backupName());
                                api.prepareSqlSourceRestoreAsync(SqlMirrorMode.H2,
                                        statuses.get(1).sha256()).whenComplete((exact, exactFailure) -> {
                                    if (exactFailure != null) { failSmoke(exactFailure); return; }
                                    Bukkit.getScheduler().runTask(this, () -> {
                                        try {
                                            check(exact.backend() == SqlMirrorMode.H2
                                                            && Files.isRegularFile(dataRoot.resolve("backups")
                                                                    .resolve(exact.backupName())
                                                                    .resolve("settings.yml")),
                                                    "Audited exact SQL source was not prepared");
                                            getLogger().info("RW_SQL_EXACT_SOURCE_PASS");
                                            finishSqlRecoveryPolicySmoke(api);
                                        } catch (Throwable ex) { failSmoke(ex); }
                                    });
                                });
                            } catch (Throwable ex) { failSmoke(ex); }
                        });
                    });
                } catch (Throwable ex) { failSmoke(ex); }
            });
        });
    }

    private void finishSqlRecoveryPolicySmoke(RecipeWeaverApi api) {
        api.setSqlReadPolicyAsync(SqlReadPolicy.RECOVER_ON_STARTUP)
                .thenCompose(ignored -> api.reloadDataAsync())
                .whenComplete((reloaded, failure) -> {
                    if (failure != null) { failSmoke(failure); return; }
                    Bukkit.getScheduler().runTask(this, () -> {
                        try {
                            check(api.sqlReadPolicy() == SqlReadPolicy.RECOVER_ON_STARTUP
                                            && api.sqlMirrorModes().equals(List.of(
                                                    SqlMirrorMode.SQLITE, SqlMirrorMode.H2)),
                                    "SQL recovery policy did not survive reload");
                            getLogger().info("RW_SQL_RECOVERY_POLICY_PASS");
                            api.setSqlReadPolicyAsync(SqlReadPolicy.PREFER_SQL_AT_STARTUP)
                                    .thenCompose(ignored -> api.reloadDataAsync())
                                    .whenComplete((preferred, preferenceFailure) -> {
                                        if (preferenceFailure != null) {
                                            failSmoke(preferenceFailure);
                                            return;
                                        }
                                        Bukkit.getScheduler().runTask(this, () -> {
                                            try {
                                                check(api.sqlReadPolicy()
                                                                == SqlReadPolicy.PREFER_SQL_AT_STARTUP,
                                                        "Preferred SQL startup policy did not survive reload");
                                                getLogger().info("RW_SQL_PREFERENCE_POLICY_PASS");
                                                finishSqlRuntimeReloadSmoke(api);
                                            } catch (Throwable ex) { failSmoke(ex); }
                                        });
                                    });
                        } catch (Throwable ex) { failSmoke(ex); }
                    });
                });
    }

    private void finishSqlRuntimeReloadSmoke(RecipeWeaverApi api) {
        api.setSqlReadPolicyAsync(SqlReadPolicy.PREFER_SQL_AT_STARTUP_AND_RELOAD)
                .whenComplete((saved, policyFailure) -> {
                    if (policyFailure != null) { failSmoke(policyFailure); return; }
                    try {
                        check(Bukkit.isPrimaryThread(),
                                "SQL read-policy completion ran off the server thread");
                    } catch (Throwable ex) { failSmoke(ex); return; }
                    Bukkit.getScheduler().runTask(this, () -> {
                        try {
                            check(api.sqlReadPolicy()
                                            == SqlReadPolicy.PREFER_SQL_AT_STARTUP_AND_RELOAD
                                            && api.sqlMirrorFailures().isEmpty(),
                                    "Runtime SQL read policy or its mirror did not save");
                            getLogger().info("RW_SQL_RUNTIME_POLICY_PASS");
                            Path items = Bukkit.getPluginManager().getPlugin("RecipeWeaver")
                                    .getDataFolder().toPath().resolve("items.yml");
                            java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                                try { return Files.readString(items); }
                                catch (IOException ex) { throw new java.util.concurrent.CompletionException(ex); }
                            }).whenComplete((raw, readFailure) -> {
                                if (readFailure != null) { failSmoke(readFailure); return; }
                                Bukkit.getScheduler().runTask(this, () -> {
                                    try {
                                        org.bukkit.configuration.file.YamlConfiguration yaml =
                                                new org.bukkit.configuration.file.YamlConfiguration();
                                        yaml.loadFromString(raw);
                                        NamespacedKey localOnly = key("smoke_runtime_local_only");
                                        check(api.customItem(localOnly).isEmpty(),
                                                "Runtime SQL local-only fixture already exists");
                                        String encoded = java.util.Base64.getUrlEncoder().withoutPadding()
                                                .encodeToString(localOnly.toString().getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8));
                                        yaml.set("items." + encoded, new ItemStack(Material.STONE));
                                        String changed = yaml.saveToString();
                                        java.util.concurrent.CompletableFuture.runAsync(() -> {
                                            try { Files.writeString(items, changed); }
                                            catch (IOException ex) {
                                                throw new java.util.concurrent.CompletionException(ex);
                                            }
                                        }).whenComplete((written, writeFailure) -> {
                                            if (writeFailure != null) { failSmoke(writeFailure); return; }
                                            Bukkit.getScheduler().runTask(this,
                                                    () -> finishSqlRuntimeReloadSelection(api, localOnly));
                                        });
                                    } catch (Throwable ex) { failSmoke(ex); }
                                });
                            });
                        } catch (Throwable ex) { failSmoke(ex); }
                    });
                });
    }

    private void finishSqlRuntimeReloadSelection(RecipeWeaverApi api, NamespacedKey localOnly) {
        api.reloadDataAsync().whenComplete((loaded, failure) -> {
            if (failure != null) { failSmoke(failure); return; }
            Bukkit.getScheduler().runTask(this, () -> {
                try {
                    check(api.lastReloadSqlSource() == SqlMirrorMode.SQLITE
                                    && api.customItem(localOnly).isEmpty(),
                            "Runtime reload did not choose the first SQL source");
                    getLogger().info("RW_SQL_RUNTIME_RELOAD_PASS");
                    api.listBackupsAsync().whenComplete((before, listingFailure) -> {
                        if (listingFailure != null) { failSmoke(listingFailure); return; }
                        Bukkit.getScheduler().runTask(this, () ->
                                finishSqlRuntimeNoop(api, before.size()));
                    });
                } catch (Throwable ex) { failSmoke(ex); }
            });
        });
    }

    private void finishSqlRuntimeNoop(RecipeWeaverApi api, int backupCount) {
        api.reloadDataAsync().whenComplete((reloaded, failure) -> {
            if (failure != null) { failSmoke(failure); return; }
            Bukkit.getScheduler().runTask(this, () -> {
                try {
                    check(api.lastReloadSqlSource() == SqlMirrorMode.NONE,
                            "Identical runtime SQL source replaced local files");
                    api.listBackupsAsync().whenComplete((after, listingFailure) -> {
                        if (listingFailure != null) { failSmoke(listingFailure); return; }
                        Bukkit.getScheduler().runTask(this, () -> {
                            try {
                                check(after.size() == backupCount,
                                        "Identical runtime SQL reload created a backup");
                                getLogger().info("RW_SQL_RUNTIME_NOOP_PASS");
                                SqlReloadRecoverySmoke.verify(this, api).whenComplete((ignored, recoveryFailure) -> {
                                    if (recoveryFailure != null) { failSmoke(recoveryFailure); return; }
                                    getLogger().info("RW_SQL_RAW_LOCAL_RELOAD_RECOVERY_PASS");
                                    Bukkit.getScheduler().runTask(this,
                                            () -> finishSqlRuntimeInvalidFallback(api));
                                });
                            } catch (Throwable ex) { failSmoke(ex); }
                        });
                    });
                } catch (Throwable ex) { failSmoke(ex); }
            });
        });
    }

    private void finishSqlRuntimeInvalidFallback(RecipeWeaverApi api) {
        try {
            SqlStoreSmoke.corruptSqliteItems().whenComplete((corrupted, corruptFailure) -> {
                if (corruptFailure != null) { failSmoke(corruptFailure); return; }
                Bukkit.getScheduler().runTask(this, () -> api.reloadDataAsync()
                        .whenComplete((loaded, reloadFailure) -> {
                            if (reloadFailure != null) { failSmoke(reloadFailure); return; }
                            Bukkit.getScheduler().runTask(this, () -> {
                                try {
                                    check(api.lastReloadSqlSource() == SqlMirrorMode.NONE
                                                    && api.sqlReadPolicy()
                                                            == SqlReadPolicy.PREFER_SQL_AT_STARTUP_AND_RELOAD,
                                            "Invalid SQLite did not fall back to valid data");
                                    getLogger().info("RW_SQL_RUNTIME_INVALID_FALLBACK_PASS");
                                    api.setSqlReadPolicyAsync(SqlReadPolicy.PREFER_SQL_AT_STARTUP)
                                            .thenCompose(ignored -> resolveSmokeSqlConflicts(api))
                                            .whenComplete((reset, resetFailure) -> {
                                                if (resetFailure != null) failSmoke(resetFailure);
                                                else Bukkit.getScheduler().runTask(this,
                                                        () -> finishRemoteSqlProfileSmoke(api));
                                            });
                                } catch (Throwable ex) { failSmoke(ex); }
                            });
                        }));
            });
        } catch (ReflectiveOperationException ex) { failSmoke(ex); }
    }

    private void finishRemoteSqlProfileSmoke(RecipeWeaverApi api) {
        api.setRemoteSqlConnectionAsync(SqlMirrorMode.POSTGRESQL,
                "jdbc:postgresql://127.0.0.1:5432/recipeweaver_smoke",
                "smoke_admin", "smoke-placeholder").whenComplete((saved, failure) -> {
            if (failure != null) { failSmoke(failure); return; }
            try {
                check(Bukkit.isPrimaryThread(),
                        "Remote SQL profile completion ran off the server thread");
            } catch (Throwable ex) { failSmoke(ex); return; }
            getLogger().info("RW_SQL_SETTINGS_MAIN_THREAD_PASS");
            Bukkit.getScheduler().runTask(this, () -> {
                try {
                    check(api.remoteSqlBackend() == SqlMirrorMode.POSTGRESQL
                                    && api.remoteSqlPasswordConfigured()
                                    && api.sqlMirrorMode() == SqlMirrorMode.SQLITE,
                            "Remote profile save disturbed the local mirror");
                    api.reloadDataAsync().whenComplete((reloaded, reloadFailure) -> {
                        if (reloadFailure != null) { failSmoke(reloadFailure); return; }
                        Bukkit.getScheduler().runTask(this, () -> {
                            try {
                                check(api.remoteSqlBackend() == SqlMirrorMode.POSTGRESQL
                                                && api.remoteSqlUsername().equals("smoke_admin")
                                                && api.remoteSqlPasswordConfigured(),
                                        "Remote profile did not survive reload");
                                getLogger().info("RW_REMOTE_SQL_PROFILE_PASS");
                                finishShutdownAfterSql(api);
                            } catch (Throwable ex) { failSmoke(ex); }
                        });
                    });
                } catch (Throwable ex) { failSmoke(ex); }
            });
        });
    }

    private void finishShutdownAfterSql(RecipeWeaverApi api) throws Exception {
        MythicMobsSmoke.verifyIfInstalled(api, this);
        PlaceholderIntegrationSmoke.verifyIfInstalled(this);
        var shutdownSave = api.saveCustomItem(
                new NamespacedKey("recipeweaver", "smoke_shutdown"),
                new ItemStack(Material.CLOCK)).toCompletableFuture();
        check(!shutdownSave.isDone(), "Shutdown save completed inline on the server thread");
        getLogger().info("RW_SHUTDOWN_SAVE_QUEUED");
        getLogger().info("RW_SMOKE_PASS all API, registry, reload, restore, lockdown, and persistence checks");
        Bukkit.shutdown();
    }

    private static RecipeDefinition materialWorkbench(NamespacedKey key, RecipeKind kind,
            List<ItemStack> ingredients, ItemStack result, String group,
            boolean enabled, boolean hidden) {
        return new RecipeDefinition(key, kind, ingredients, result, group, enabled, hidden,
                java.util.Collections.nCopies(9, IngredientMatchMode.MATERIAL),
                java.util.Collections.nCopies(9, List.of()),
                java.util.Collections.nCopies(9, null));
    }

    private static List<List<ItemStack>> emptyWorkbenchAlternatives() {
        List<List<ItemStack>> result = new ArrayList<>(9);
        for (int slot = 0; slot < 9; slot++) result.add(new ArrayList<>());
        return result;
    }

    private static void checkWorkbenchChoices(RecipeWeaverApi api) {
        RecipeDefinition exact = api.recipe(key("smoke_workbench_exact_choices")).orElseThrow();
        RecipeDefinition tagged = api.recipe(key("smoke_workbench_tag_choices")).orElseThrow();
        check(exact.alternatives().get(0).size() == 1
                        && exact.alternatives().get(0).get(0).getType() == Material.REDSTONE,
                "Workbench exact alternative was not retained");
        check(tagged.matchModes().get(0) == IngredientMatchMode.MATERIAL
                        && tagged.alternatives().get(0).get(0).getType() == Material.DIRT
                        && NamespacedKey.fromString("minecraft:planks").equals(tagged.itemTags().get(0)),
                "Workbench material choice or tag was not retained");
        if (Bukkit.getBukkitVersion().startsWith("1.20.6")) {
            check(Bukkit.getRecipe(exact.key()) == null && Bukkit.getRecipe(tagged.key()) == null,
                    "Unobservable crafter retained exact-choice recipes");
            return;
        }
        check(Bukkit.getRecipe(exact.key()) != null && Bukkit.getRecipe(tagged.key()) != null,
                "Workbench choice recipes were not registered");
        ItemStack[] exactMatrix = new ItemStack[9];
        exactMatrix[0] = new ItemStack(Material.REDSTONE);
        exactMatrix[7] = new ItemStack(Material.NETHERITE_SCRAP);
        check(craftType(exactMatrix) == Material.GOLD_NUGGET,
                "Workbench exact alternative did not craft");
        ItemStack[] tagMatrix = new ItemStack[9];
        tagMatrix[0] = new ItemStack(Material.BIRCH_PLANKS);
        tagMatrix[1] = new ItemStack(Material.FLINT);
        check(craftType(tagMatrix) == Material.AMETHYST_SHARD,
                "Workbench item tag did not craft");
        tagMatrix[0] = new ItemStack(Material.DIRT);
        check(craftType(tagMatrix) == Material.AMETHYST_SHARD,
                "Workbench material alternative did not craft");
        tagMatrix[0] = new ItemStack(Material.STONE);
        check(craftType(tagMatrix) != Material.AMETHYST_SHARD,
                "Workbench tag choice matched an unrelated material");
    }

    private static void checkStationChoices(RecipeWeaverApi api) {
        NamespacedKey key = key("smoke_furnace_choices");
        StationRecipeDefinition saved = api.stationRecipe(key).orElseThrow();
        check(saved.matchModes().get(0) == IngredientMatchMode.MATERIAL
                        && saved.alternatives().get(0).size() == 1
                        && saved.alternatives().get(0).get(0).getType() == Material.REDSTONE
                        && NamespacedKey.fromString("minecraft:planks").equals(saved.itemTags().get(0)),
                "Station choices were not retained");
        check(Bukkit.getRecipe(key) instanceof org.bukkit.inventory.FurnaceRecipe,
                "Furnace choice recipe was not registered");
        org.bukkit.inventory.FurnaceRecipe nativeRecipe =
                (org.bukkit.inventory.FurnaceRecipe) Bukkit.getRecipe(key);
        check(nativeRecipe.getInputChoice().test(new ItemStack(Material.BIRCH_PLANKS)),
                "Furnace item tag did not match");
        check(nativeRecipe.getInputChoice().test(new ItemStack(Material.REDSTONE)),
                "Furnace alternative did not match");
        check(!nativeRecipe.getInputChoice().test(new ItemStack(Material.STONE)),
                "Furnace choice matched an unrelated material");
    }

    private static void checkGridOptions(GridRecipeDefinition recipe) {
        check(recipe.matchModes().get(0) == IngredientMatchMode.MATERIAL
                        && recipe.priority() == 7 && !recipe.allowMirror(),
                "Grid matching options were not retained");
        check(recipe.alternatives().get(0).size() == 1
                        && recipe.alternatives().get(0).get(0).getType() == Material.DIRT
                        && NamespacedKey.fromString("minecraft:planks").equals(recipe.itemTags().get(0)),
                "Grid alternatives or item tag were not retained");
        List<ItemStack> actual = new ArrayList<>(Arrays.asList(new ItemStack[16]));
        ItemStack namedStone = new ItemStack(Material.STONE, 4);
        var meta = namedStone.getItemMeta();
        meta.setDisplayName("Different metadata");
        namedStone.setItemMeta(meta);
        actual.set(0, namedStone);
        actual.set(15, new ItemStack(Material.DIRT));
        int[] counts = gridConsumption(recipe, actual);
        check(counts != null && counts[0] == 3 && counts[15] == 1,
                "Material grid input did not match its amount");
        actual.set(0, new ItemStack(Material.DIRT, 4));
        counts = gridConsumption(recipe, actual);
        check(counts != null && counts[0] == 3 && counts[15] == 1,
                "Grid alternative did not match its required amount");
        actual.set(0, new ItemStack(Material.OAK_PLANKS, 4));
        counts = gridConsumption(recipe, actual);
        check(counts != null && counts[0] == 3 && counts[15] == 1,
                "Bukkit item tag did not match its required amount");
        actual.set(0, namedStone);
        namedStone.setAmount(2);
        check(gridConsumption(recipe, actual) == null,
                "Grid matched a stack smaller than the required amount");
        actual.set(0, null);
        actual.set(15, null);
        namedStone.setAmount(4);
        actual.set(3, namedStone);
        actual.set(12, new ItemStack(Material.DIRT));
        check(gridConsumption(recipe, actual) == null,
                "Grid matched a mirrored pattern while mirroring was disabled");
    }

    private static int[] gridConsumption(GridRecipeDefinition recipe, List<ItemStack> actual) {
        try {
            Class<?> matcher = Class.forName("dev.recipeweaver.core.GridMatcher");
            var method = matcher.getDeclaredMethod("consumption", GridRecipeDefinition.class, List.class);
            method.setAccessible(true);
            return (int[]) method.invoke(null, recipe, actual);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not inspect live grid matching", ex);
        }
    }

    static Material gridResult(RecipeWeaverApi api, List<ItemStack> actual) {
        try {
            var field = api.getClass().getDeclaredField("gridMenus");
            field.setAccessible(true);
            Object menus = field.get(api);
            var find = menus.getClass().getDeclaredMethod("find", org.bukkit.entity.Player.class,
                    org.bukkit.World.class,
                    GridTier.class, int.class, List.class);
            find.setAccessible(true);
            Object match = find.invoke(menus, null, Bukkit.getWorlds().get(0),
                    GridTier.ELITE, 4, actual);
            if (match == null) return null;
            var result = match.getClass().getDeclaredMethod("result");
            result.setAccessible(true);
            return ((ItemStack) result.invoke(match)).getType();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not inspect live grid priority", ex);
        }
    }

    private static void checkGridPreview(RecipeWeaverApi api, List<ItemStack> actual) {
        try {
            var field = api.getClass().getDeclaredField("gridMenus");
            field.setAccessible(true);
            Object menus = field.get(api);
            Class<?> holderType = Class.forName("dev.recipeweaver.core.GridMenus$Holder");
            Class<?> menuType = Class.forName("dev.recipeweaver.core.GridMenus$Menu");
            Object craft = Arrays.stream(menuType.getEnumConstants())
                    .filter(value -> value.toString().equals("CRAFT")).findFirst().orElseThrow();
            var constructor = holderType.getDeclaredConstructor(java.util.UUID.class,
                    menuType, int.class, GridTier.class, int.class);
            constructor.setAccessible(true);
            Object holder = constructor.newInstance(java.util.UUID.randomUUID(), craft, 0, GridTier.ELITE, 4);
            var preview = menus.getClass().getDeclaredMethod("preview", org.bukkit.entity.Player.class,
                    holderType, List.class);
            preview.setAccessible(true);
            org.bukkit.entity.Player synthetic = (org.bukkit.entity.Player) java.lang.reflect.Proxy
                    .newProxyInstance(org.bukkit.entity.Player.class.getClassLoader(),
                            new Class<?>[] {org.bukkit.entity.Player.class}, (proxy, method, args) ->
                                    switch (method.getName()) {
                                        case "getWorld" -> Bukkit.getWorlds().get(0);
                                        case "hasPermission" -> false;
                                        default -> null;
                                    });
            Object first = preview.invoke(menus, synthetic, holder, actual);
            Object repeated = preview.invoke(menus, synthetic, holder, actual);
            check(first != null && first == repeated, "Grid preview rerolled unchanged inputs");
            List<ItemStack> changed = new ArrayList<>(actual);
            changed.set(0, new ItemStack(Material.COPPER_INGOT, 2));
            Object afterChange = preview.invoke(menus, synthetic, holder, changed);
            check(afterChange != null && afterChange != first, "Grid preview ignored changed inputs");
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not inspect live grid preview", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static void checkGridUses(GridRecipeDefinition recipe, List<ItemStack> actual) {
        check(recipe.consumes(0) && !recipe.consumes(15)
                        && recipe.remainder(0) != null
                        && recipe.remainder(0).getType() == Material.GOLD_NUGGET,
                "Grid ingredient use flags or remainder were not retained");
        try {
            Class<?> matcher = Class.forName("dev.recipeweaver.core.GridMatcher");
            var planMethod = matcher.getDeclaredMethod("plan", GridRecipeDefinition.class, List.class);
            planMethod.setAccessible(true);
            Object plan = planMethod.invoke(null, recipe, actual);
            check(plan != null, "Grid use recipe did not match live inputs");
            var apply = matcher.getDeclaredMethod("apply", GridRecipeDefinition.class,
                    List.class, plan.getClass());
            apply.setAccessible(true);
            Object effect = apply.invoke(null, recipe, actual, plan);
            var nextInputs = effect.getClass().getDeclaredMethod("inputs");
            var returned = effect.getClass().getDeclaredMethod("returned");
            nextInputs.setAccessible(true);
            returned.setAccessible(true);
            List<ItemStack> next = (List<ItemStack>) nextInputs.invoke(effect);
            check(next.get(0).getType() == Material.GOLD_NUGGET
                            && next.get(15).getType() == Material.STONE
                            && ((List<?>) returned.invoke(effect)).isEmpty(),
                    "Grid use did not replace consumed input or keep the catalyst");
            List<ItemStack> largerStack = new ArrayList<>(actual);
            largerStack.set(0, new ItemStack(Material.COPPER_INGOT, 2));
            Object largerPlan = planMethod.invoke(null, recipe, largerStack);
            Object largerEffect = apply.invoke(null, recipe, largerStack, largerPlan);
            List<ItemStack> largerNext = (List<ItemStack>) nextInputs.invoke(largerEffect);
            List<ItemStack> overflow = (List<ItemStack>) returned.invoke(largerEffect);
            check(largerNext.get(0).getType() == Material.COPPER_INGOT
                            && largerNext.get(0).getAmount() == 1
                            && overflow.size() == 1
                            && overflow.get(0).getType() == Material.GOLD_NUGGET,
                    "Grid remainder was lost when input stack had items left");
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not inspect live grid ingredient use", ex);
        }
    }

    private void failSmoke(Throwable ex) {
        getLogger().severe("RW_SMOKE_FAIL " + ex);
        ex.printStackTrace();
        if (Bukkit.isPrimaryThread()) Bukkit.shutdown();
        else Bukkit.getScheduler().runTask(this, Bukkit::shutdown);
    }

    private static java.util.concurrent.CompletableFuture<Void> startCommandReload(
            RecipeWeaverApi api) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "rw reload");
        try {
            var field = api.getClass().getDeclaredField("activeReload");
            field.setAccessible(true);
            Object value = field.get(api);
            if (!(value instanceof java.util.concurrent.CompletableFuture<?> future)) {
                throw new IllegalStateException("/rw reload did not start an asynchronous operation");
            }
            @SuppressWarnings("unchecked")
            java.util.concurrent.CompletableFuture<Void> typed =
                    (java.util.concurrent.CompletableFuture<Void>) future;
            return typed;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not inspect command reload", ex);
        }
    }

    /** Test-only immediate assertion bridge; raw file reads still run on RecipeWeaver-IO. */
    static void reloadForLegacyAssertions(RecipeWeaverApi api) {
        try {
            var ioField = api.getClass().getDeclaredField("io");
            ioField.setAccessible(true);
            Object worker = ioField.get(api);
            var flush = worker.getClass().getDeclaredMethod("flush");
            flush.setAccessible(true);
            ((java.util.concurrent.CompletableFuture<?>) flush.invoke(worker)).join();

            Class<?> snapshotType = Class.forName("dev.recipeweaver.core.DiskSnapshot");
            var read = snapshotType.getDeclaredMethod("read", Path.class);
            read.setAccessible(true);
            Path root = ((JavaPlugin) api).getDataFolder().toPath();
            java.util.concurrent.Callable<Object> capture = () -> {
                try { return read.invoke(null, root); }
                catch (java.lang.reflect.InvocationTargetException ex) {
                    throw new IOException("Test snapshot failed", ex.getCause());
                }
            };
            var submit = worker.getClass().getDeclaredMethod(
                    "submit", java.util.concurrent.Callable.class);
            submit.setAccessible(true);
            Object snapshot = ((java.util.concurrent.CompletableFuture<?>) submit.invoke(
                    worker, capture)).join();
            var apply = api.getClass().getDeclaredMethod("applySnapshot", snapshotType);
            apply.setAccessible(true);
            apply.invoke(api, snapshot);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            throw new IllegalStateException("Test reload failed", ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not invoke test reload bridge", ex);
        }
    }

    private static org.bukkit.event.Event discoveryEvent(org.bukkit.entity.Player player,
                                                         NamespacedKey key) throws ReflectiveOperationException {
        Class<org.bukkit.event.player.PlayerRecipeDiscoverEvent> type =
                org.bukkit.event.player.PlayerRecipeDiscoverEvent.class;
        try {
            return type.getConstructor(org.bukkit.entity.Player.class, NamespacedKey.class, boolean.class)
                    .newInstance(player, key, false);
        } catch (NoSuchMethodException olderApi) {
            return type.getConstructor(org.bukkit.entity.Player.class, NamespacedKey.class)
                    .newInstance(player, key);
        }
    }

    private static RecipeDefinition categoryWorkbench(NamespacedKey key,
                                                      List<ItemStack> inputs, boolean hidden) {
        return new RecipeDefinition(key, RecipeKind.SHAPELESS, inputs,
                new ItemStack(Material.GOLD_NUGGET), "smoke_hidden", true, hidden,
                java.util.Collections.nCopies(9, IngredientMatchMode.MATERIAL),
                java.util.Collections.nCopies(9, List.of()),
                java.util.Collections.nCopies(9, null), CraftingBookCategory.EQUIPMENT);
    }

    private static StationRecipeDefinition categoryStation(NamespacedKey key, boolean hidden) {
        return new StationRecipeDefinition(key, StationKind.FURNACE,
                List.of(new ItemStack(Material.BASALT)), new ItemStack(Material.IRON_NUGGET),
                "smoke_hidden", 100, 0, 1, true, hidden,
                List.of(IngredientMatchMode.EXACT), List.of(List.of()),
                java.util.Collections.nCopies(1, null), CookingBookCategory.BLOCKS);
    }

    private static boolean nativeCategoriesSaved(RecipeWeaverApi api,
                                                 NamespacedKey workbench, NamespacedKey station) {
        return api.recipe(workbench).map(recipe ->
                        recipe.bookCategory() == CraftingBookCategory.EQUIPMENT).orElse(false)
                && api.stationRecipe(station).map(recipe ->
                        recipe.bookCategory() == CookingBookCategory.BLOCKS).orElse(false)
                && Bukkit.getRecipe(workbench) instanceof CraftingRecipe crafting
                && crafting.getCategory() == CraftingBookCategory.EQUIPMENT
                && Bukkit.getRecipe(station) instanceof CookingRecipe<?> cooking
                && cooking.getCategory() == CookingBookCategory.BLOCKS;
    }

    private static Set<String> backupNames(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return new java.util.HashSet<>();
        try (var paths = Files.list(directory)) {
            return paths.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("RecipeWeaver-backup-") && name.endsWith(".zip"))
                    .collect(Collectors.toCollection(java.util.HashSet::new));
        }
    }


    private static Material craftType(ItemStack[] matrix) {
        ItemStack result = Bukkit.craftItemResult(matrix, Bukkit.getWorlds().get(0)).getResult();
        return result == null ? Material.AIR : result.getType();
    }
    private StationRecipeDefinition station(NamespacedKey key, StationKind kind,
                                            List<ItemStack> inputs, ItemStack result) {
        return new StationRecipeDefinition(key, kind, inputs, result, "smoke", 100,
                0.5f, 1, true, false);
    }

    private static NamespacedKey key(String value) { return new NamespacedKey("recipeweaver", value); }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
