package dev.recipeweaver.smoke;

import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.RecipeWeaverApi;
import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationCraftBatch;
import dev.recipeweaver.api.StationCraftCompleteEvent;
import dev.recipeweaver.api.StationRecipeDefinition;
import dev.recipeweaver.api.WeatherCondition;
import dev.recipeweaver.api.CraftSoundAction;
import dev.recipeweaver.api.CraftAdvancementAction;
import dev.recipeweaver.api.CraftCommandAction;
import dev.recipeweaver.api.CraftCommandSender;
import dev.recipeweaver.api.ResultMetadataMode;
import dev.recipeweaver.api.WeightedResult;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.GrindstoneInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.recipe.CookingBookCategory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Exercises the production anvil and grindstone result listeners on a live server. */
final class StationRuleSmoke {
    static final NamespacedKey ANVIL = new NamespacedKey("recipeweaver", "smoke_rule_anvil");
    static final NamespacedKey GRINDSTONE = new NamespacedKey("recipeweaver", "smoke_rule_grindstone");
    static final NamespacedKey META_ANVIL = new NamespacedKey("recipeweaver", "smoke_metadata_anvil");
    static final NamespacedKey META_GRINDSTONE = new NamespacedKey("recipeweaver", "smoke_metadata_grindstone");
    static final NamespacedKey FULL_DATA_ANVIL = new NamespacedKey("recipeweaver", "smoke_full_data_anvil");
    static final NamespacedKey FULL_DATA_GRINDSTONE = new NamespacedKey("recipeweaver", "smoke_full_data_grindstone");
    static final NamespacedKey INGREDIENT_ANVIL = new NamespacedKey("recipeweaver", "smoke_ingredient_anvil");
    static final NamespacedKey INGREDIENT_GRINDSTONE = new NamespacedKey("recipeweaver", "smoke_ingredient_grindstone");
    static final NamespacedKey OPTIONAL_ANVIL = new NamespacedKey("recipeweaver", "smoke_optional_anvil");
    static final NamespacedKey REQUIRED_ANVIL = new NamespacedKey("recipeweaver", "smoke_required_anvil");
    static final NamespacedKey OPTIONAL_GRINDSTONE = new NamespacedKey("recipeweaver", "smoke_optional_grindstone");
    static final NamespacedKey WEIGHTED_ANVIL = new NamespacedKey("recipeweaver", "smoke_weighted_anvil");
    static final NamespacedKey WEIGHTED_GRINDSTONE = new NamespacedKey("recipeweaver", "smoke_weighted_grindstone");
    private static final NamespacedKey META_KEY = new NamespacedKey("recipeweaver", "smoke_result_copy");
    static final RecipeConditions CONDITIONS = new RecipeConditions("recipeweaver.smoke.station",
            null, null, -1, -1, WeatherCondition.ANY, 0, null, 0, null);
    static final CraftSoundAction ACTION_SOUND = new CraftSoundAction(
            NamespacedKey.minecraft("block.note_block.bell"), 1.0F, 0.8F, false);
    static final CraftAdvancementAction ACTION_ADVANCEMENT = new CraftAdvancementAction(
            NamespacedKey.minecraft("story/root"));
    static final CraftCommandAction ACTION_COMMAND = new CraftCommandAction(
            "recipeweaversmokeaction {player} {uuid}", CraftCommandSender.CONSOLE, false);
    private static final AtomicInteger COMMANDS = new AtomicInteger();

    private StationRuleSmoke() { }

    static boolean onCommand(CommandSender sender, String[] args) {
        check(sender == Bukkit.getConsoleSender() && args.length == 2
                        && args[0].equals("StationRuleSmoke")
                        && args[1].equals("12345678-1234-1234-1234-123456789ad1"),
                "Station action command used the wrong sender or placeholders");
        COMMANDS.incrementAndGet();
        return true;
    }

    static void verify(RecipeWeaverApi api, JavaPlugin harness) {
        COMMANDS.set(0);
        api.saveStationRecipe(recipe(ANVIL, StationKind.ANVIL, Material.FLINT,
                Material.GOLD_NUGGET, 2, 30));
        api.saveStationRecipe(recipe(GRINDSTONE, StationKind.GRINDSTONE, Material.FEATHER,
                Material.IRON_NUGGET, 1, 0));
        api.saveStationRecipe(metadataRecipe(META_ANVIL, StationKind.ANVIL,
                Material.PRISMARINE_SHARD, null, Material.GOLD_INGOT, 0));
        api.saveStationRecipe(metadataRecipe(META_GRINDSTONE, StationKind.GRINDSTONE,
                Material.RABBIT_HIDE, Material.BOW, Material.EMERALD, 1));
        api.saveStationRecipe(fullDataRecipe(FULL_DATA_ANVIL, StationKind.ANVIL));
        api.saveStationRecipe(fullDataRecipe(FULL_DATA_GRINDSTONE, StationKind.GRINDSTONE));
        api.saveStationRecipe(ingredientRecipe(INGREDIENT_ANVIL, StationKind.ANVIL,
                Material.STONE, 3, Material.GLASS_BOTTLE, Material.DIAMOND, Material.BUCKET));
        api.saveStationRecipe(ingredientRecipe(INGREDIENT_GRINDSTONE, StationKind.GRINDSTONE,
                Material.STRING, 2, Material.STICK, Material.IRON_INGOT, Material.FEATHER));
        ItemStack markedClay = named(Material.CLAY_BALL, "Station partial sample");
        api.saveStationRecipe(optionalRecipe(OPTIONAL_ANVIL, StationKind.ANVIL,
                markedClay, Material.REDSTONE, Material.GOLD_INGOT, true));
        api.saveStationRecipe(optionalRecipe(REQUIRED_ANVIL, StationKind.ANVIL,
                markedClay, Material.REDSTONE, Material.EMERALD, false));
        api.saveStationRecipe(optionalRecipe(OPTIONAL_GRINDSTONE, StationKind.GRINDSTONE,
                new ItemStack(Material.PAPER), Material.BONE_MEAL, Material.LAPIS_LAZULI, true));
        checkSaved(api);
        AtomicBoolean extensionAllowed = new AtomicBoolean();
        AtomicInteger extensionActions = new AtomicInteger();
        api.registerStationCondition(harness, ANVIL,
                (who, recipe, inputs) -> extensionAllowed.get());
        api.registerStationCondition(harness, GRINDSTONE,
                (who, recipe, inputs) -> extensionAllowed.get());
        api.registerStationAction(harness, ANVIL, (who, batch) -> {
            check(batch.kind() == StationKind.ANVIL
                            && batch.result().getType() == Material.GOLD_NUGGET,
                    "Anvil extension received the wrong committed result");
            extensionActions.incrementAndGet();
        });
        api.registerStationAction(harness, GRINDSTONE, (who, batch) -> {
            check(batch.kind() == StationKind.GRINDSTONE
                            && batch.result().getType() == Material.IRON_NUGGET,
                    "Grindstone extension received the wrong committed result");
            extensionActions.incrementAndGet();
        });
        List<StationCraftBatch> completed = new ArrayList<>();
        AtomicReference<Player> completedPlayer = new AtomicReference<>();
        Listener completedListener = new Listener() { };
        Bukkit.getPluginManager().registerEvent(StationCraftCompleteEvent.class, completedListener,
                EventPriority.NORMAL, (registered, fired) -> {
                    StationCraftCompleteEvent event = (StationCraftCompleteEvent) fired;
                    if (!event.batch().recipe().equals(ANVIL)
                            && !event.batch().recipe().equals(GRINDSTONE)) return;
                    completedPlayer.set(event.player());
                    completed.add(event.batch());
                }, harness);

        PersistentDataContainer data = new ItemStack(Material.STONE).getItemMeta()
                .getPersistentDataContainer();
        AtomicReference<ItemStack[]> storage = new AtomicReference<>(new ItemStack[36]);
        AtomicReference<ItemStack> cursor = new AtomicReference<>();
        AtomicReference<InventoryView> open = new AtomicReference<>();
        AtomicBoolean permitted = new AtomicBoolean();
        AtomicInteger sounds = new AtomicInteger();
        AtomicInteger awards = new AtomicInteger();
        Advancement advancement = Bukkit.getAdvancement(ACTION_ADVANCEMENT.advancement());
        check(advancement != null && !advancement.getCriteria().isEmpty(),
                "Station action advancement is missing");
        Set<String> remainingCriteria = new HashSet<>(advancement.getCriteria());
        int criteriaCount = remainingCriteria.size();
        AdvancementProgress progress = (AdvancementProgress) Proxy.newProxyInstance(
                AdvancementProgress.class.getClassLoader(), new Class<?>[] {AdvancementProgress.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getRemainingCriteria" -> List.copyOf(remainingCriteria);
                    case "awardCriteria" -> {
                        boolean awarded = remainingCriteria.remove(args[0]);
                        if (awarded) awards.incrementAndGet();
                        yield awarded;
                    }
                    default -> throw new UnsupportedOperationException("AdvancementProgress." + method.getName());
                });
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(
                PlayerInventory.class.getClassLoader(), new Class<?>[] {PlayerInventory.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getStorageContents" -> storage.get();
                    case "setStorageContents" -> { storage.set((ItemStack[]) args[0]); yield null; }
                    default -> throw new UnsupportedOperationException("PlayerInventory." + method.getName());
                });
        UUID id = UUID.fromString("12345678-1234-1234-1234-123456789ad1");
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> "StationRuleSmoke";
                    case "getInventory" -> inventory;
                    case "getItemOnCursor" -> cursor.get();
                    case "setItemOnCursor" -> { cursor.set((ItemStack) args[0]); yield null; }
                    case "getPersistentDataContainer" -> data;
                    case "getGameMode" -> GameMode.CREATIVE;
                    case "getLevel" -> 0;
                    case "getWorld" -> Bukkit.getWorlds().get(0);
                    case "getLocation" -> new Location(Bukkit.getWorlds().get(0), 0, 64, 0);
                    case "playSound" -> {
                        check(ACTION_SOUND.sound().toString().equals(args[1]),
                                "Station craft action used the wrong sound");
                        sounds.incrementAndGet();
                        yield null;
                    }
                    case "getAdvancementProgress" -> {
                        check(ACTION_ADVANCEMENT.advancement().equals(((Advancement) args[0]).getKey()),
                                "Station craft action used the wrong advancement");
                        yield progress;
                    }
                    case "hasPermission" -> permitted.get();
                    case "getOpenInventory" -> open.get();
                    case "isOnline" -> true;
                    case "updateInventory", "sendMessage" -> null;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> throw new UnsupportedOperationException("Player." + method.getName());
                });

        Inventory top = Bukkit.createInventory(null, 9);
        InventoryView anvil = view(player, top, InventoryType.ANVIL);
        open.set(anvil);
        top.setItem(0, new ItemStack(Material.FLINT, 2));
        top.setItem(2, new ItemStack(Material.GOLD_NUGGET));
        click(anvil, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        check(top.getItem(0).getAmount() == 2 && cursor.get() == null
                        && data.get(countKey(ANVIL), PersistentDataType.INTEGER) == null,
                "Anvil condition denial consumed an input or allowance");
        check(sounds.get() == 0 && COMMANDS.get() == 0 && awards.get() == 0,
                "Denied anvil craft ran an action");
        check(completed.isEmpty(), "Denied anvil craft published a completion event");
        permitted.set(true);
        check(preview(player, top, InventoryType.ANVIL) == null,
                "Anvil extension denial still showed a result");
        top.setItem(2, new ItemStack(Material.GOLD_NUGGET));
        click(anvil, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        check(top.getItem(0).getAmount() == 2 && cursor.get() == null
                        && extensionActions.get() == 0,
                "Anvil extension denial consumed input or ran an action");
        extensionAllowed.set(true);
        check(preview(player, top, InventoryType.ANVIL).getType() == Material.GOLD_NUGGET,
                "Anvil extension approval did not show a result");
        top.setItem(2, new ItemStack(Material.GOLD_NUGGET));
        ItemStack[] full = new ItemStack[36];
        Arrays.fill(full, new ItemStack(Material.COBBLESTONE, 64));
        storage.set(full);
        click(anvil, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        check(top.getItem(0).getAmount() == 2 && data.get(countKey(ANVIL),
                PersistentDataType.INTEGER) == null, "Full inventory spent anvil allowance");
        check(sounds.get() == 0 && COMMANDS.get() == 0 && awards.get() == 0,
                "Full-inventory anvil craft ran an action");
        check(completed.isEmpty(), "Full-inventory anvil craft published a completion event");

        storage.set(new ItemStack[36]);
        click(anvil, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        check(top.getItem(0).getAmount() == 1
                        && count(storage.get(), Material.GOLD_NUGGET) == 1
                        && Integer.valueOf(1).equals(data.get(countKey(ANVIL), PersistentDataType.INTEGER))
                        && data.get(delayKey(ANVIL), PersistentDataType.LONG) > System.currentTimeMillis(),
                "Anvil transaction did not commit result, count, and delay together");
        check(sounds.get() == 1 && COMMANDS.get() == 1 && awards.get() == criteriaCount,
                "Committed anvil craft did not run all post-craft actions");
        check(extensionActions.get() == 1, "Anvil extension action missed a committed craft");
        check(completed.size() == 1 && completedPlayer.get() == player
                        && completed.get(0).recipe().equals(ANVIL)
                        && completed.get(0).kind() == StationKind.ANVIL
                        && completed.get(0).result().getType() == Material.GOLD_NUGGET,
                "Anvil commit did not publish one correct completion event");
        top.setItem(2, new ItemStack(Material.GOLD_NUGGET));
        click(anvil, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        check(top.getItem(0).getAmount() == 1 && cursor.get() == null,
                "Anvil delay allowed an immediate second craft");
        check(sounds.get() == 1 && COMMANDS.get() == 1 && awards.get() == criteriaCount,
                "Anvil delay denial ran an action");
        data.set(delayKey(ANVIL), PersistentDataType.LONG, System.currentTimeMillis() - 1);
        top.setItem(2, new ItemStack(Material.GOLD_NUGGET));
        click(anvil, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        check(top.getItem(0) == null && cursor.get() != null
                        && cursor.get().getType() == Material.GOLD_NUGGET
                        && Integer.valueOf(2).equals(data.get(countKey(ANVIL), PersistentDataType.INTEGER)),
                "Anvil craft after delay did not consume its second allowance");
        check(sounds.get() == 2 && COMMANDS.get() == 2 && awards.get() == criteriaCount,
                "Second committed anvil craft did not run actions exactly once");
        check(extensionActions.get() == 2, "Anvil extension action did not run exactly once per craft");
        check(completed.size() == 2 && completed.get(1).recipe().equals(ANVIL),
                "Second anvil commit did not publish exactly one event");
        cursor.set(null);
        data.set(delayKey(ANVIL), PersistentDataType.LONG, System.currentTimeMillis() - 1);
        top.setItem(0, new ItemStack(Material.FLINT));
        top.setItem(2, new ItemStack(Material.GOLD_NUGGET));
        click(anvil, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        check(top.getItem(0).getAmount() == 1 && cursor.get() == null,
                "Exhausted anvil allowance still permitted crafting");
        check(sounds.get() == 2 && COMMANDS.get() == 2 && awards.get() == criteriaCount,
                "Exhausted anvil allowance ran an action");

        Inventory grindTop = Bukkit.createInventory(null, 9);
        InventoryView grind = view(player, grindTop, InventoryType.GRINDSTONE);
        open.set(grind);
        grindTop.setItem(0, new ItemStack(Material.FEATHER, 2));
        grindTop.setItem(2, new ItemStack(Material.IRON_NUGGET));
        permitted.set(false);
        click(grind, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        check(grindTop.getItem(0).getAmount() == 2 && cursor.get() == null
                        && data.get(countKey(GRINDSTONE), PersistentDataType.INTEGER) == null,
                "Grindstone condition denial consumed an input or allowance");
        check(sounds.get() == 2 && COMMANDS.get() == 2,
                "Denied grindstone craft ran an action");
        permitted.set(true);
        extensionAllowed.set(false);
        check(preview(player, grindTop, InventoryType.GRINDSTONE) == null,
                "Grindstone extension denial still showed a result");
        grindTop.setItem(2, new ItemStack(Material.IRON_NUGGET));
        click(grind, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        check(grindTop.getItem(0).getAmount() == 2 && cursor.get() == null
                        && extensionActions.get() == 2,
                "Grindstone extension denial consumed input or ran an action");
        extensionAllowed.set(true);
        check(preview(player, grindTop, InventoryType.GRINDSTONE).getType() == Material.IRON_NUGGET,
                "Grindstone extension approval did not show a result");
        grindTop.setItem(2, new ItemStack(Material.IRON_NUGGET));
        click(grind, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        check(grindTop.getItem(0).getAmount() == 1 && cursor.get() != null
                        && cursor.get().getType() == Material.IRON_NUGGET
                        && Integer.valueOf(1).equals(data.get(countKey(GRINDSTONE), PersistentDataType.INTEGER)),
                "Grindstone transaction did not commit its allowance");
        check(sounds.get() == 3 && COMMANDS.get() == 2,
                "Committed grindstone craft did not play its sound exactly once");
        check(extensionActions.get() == 3, "Grindstone extension action missed a committed craft");
        check(completed.size() == 3 && completed.get(2).recipe().equals(GRINDSTONE)
                        && completed.get(2).kind() == StationKind.GRINDSTONE
                        && completed.get(2).result().getType() == Material.IRON_NUGGET,
                "Grindstone commit did not publish one correct completion event");
        cursor.set(null);
        grindTop.setItem(2, new ItemStack(Material.IRON_NUGGET));
        click(grind, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        check(grindTop.getItem(0).getAmount() == 1 && cursor.get() == null,
                "Exhausted grindstone allowance still permitted crafting");
        check(sounds.get() == 3 && COMMANDS.get() == 2 && awards.get() == criteriaCount,
                "Exhausted grindstone allowance ran an action");
        check(extensionActions.get() == 3, "Denied station craft ran an extension action");
        check(completed.size() == 3, "Denied station craft published a completion event");
        HandlerList.unregisterAll(completedListener);
        check(api.unregisterStationCondition(harness, ANVIL)
                        && api.unregisterStationCondition(harness, GRINDSTONE)
                        && api.unregisterStationAction(harness, ANVIL)
                        && api.unregisterStationAction(harness, GRINDSTONE),
                "Station extension callbacks did not unregister");
        verifyMetadata(player, open, cursor, sounds);
        verifyFullData(player, open, cursor, storage);
        verifyStaleOwnedResults(api, player, open, cursor);
        verifyIngredients(player, open, cursor, storage);
        verifyOptional(player, open, cursor, storage);
        verifyWeighted(api, harness, player, open, cursor, storage);
        open.set(null);
    }

    static boolean weightedSaved(RecipeWeaverApi api) {
        return api.stationRecipe(WEIGHTED_ANVIL).map(recipe ->
                recipe.resultChoices().size() == 2
                        && recipe.resultChoices().get(0).weight() == 1
                        && recipe.resultChoices().get(1).weight() == 3).orElse(false)
                && api.stationRecipe(WEIGHTED_GRINDSTONE).map(recipe ->
                recipe.resultChoices().size() == 2
                        && recipe.resultChoices().get(0).weight() == 3
                        && recipe.resultChoices().get(1).weight() == 1).orElse(false);
    }

    private static void verifyWeighted(RecipeWeaverApi api, JavaPlugin harness, Player player,
                                       AtomicReference<InventoryView> open,
                                       AtomicReference<ItemStack> cursor,
                                       AtomicReference<ItemStack[]> storage) {
        api.saveStationRecipe(StationRecipeDefinition.withResults(WEIGHTED_ANVIL, StationKind.ANVIL,
                Arrays.asList(new ItemStack(Material.ECHO_SHARD), null),
                List.of(new WeightedResult(new ItemStack(Material.GOLD_INGOT), 1),
                        new WeightedResult(new ItemStack(Material.DIAMOND), 3)),
                "smoke", 200, 0, 0, true, false));
        api.saveStationRecipe(StationRecipeDefinition.withResults(WEIGHTED_GRINDSTONE, StationKind.GRINDSTONE,
                Arrays.asList(new ItemStack(Material.NAUTILUS_SHELL), null),
                List.of(new WeightedResult(new ItemStack(Material.IRON_INGOT), 3),
                        new WeightedResult(new ItemStack(Material.EMERALD), 1)),
                "smoke", 200, 0, 0, true, false));
        check(weightedSaved(api), "Weighted station recipes were not registered");
        List<StationCraftBatch> completions = new ArrayList<>();
        Listener listener = new Listener() { };
        Bukkit.getPluginManager().registerEvent(StationCraftCompleteEvent.class, listener,
                EventPriority.NORMAL, (registered, fired) -> {
                    StationCraftBatch batch = ((StationCraftCompleteEvent) fired).batch();
                    if (batch.recipe().equals(WEIGHTED_ANVIL)
                            || batch.recipe().equals(WEIGHTED_GRINDSTONE)) completions.add(batch);
                }, harness);
        try {
            Inventory backing = Bukkit.createInventory(null, 9);
            Inventory top = stationInventory(backing, InventoryType.ANVIL);
            InventoryView view = view(player, top, InventoryType.ANVIL);
            open.set(view);
            cursor.set(null);
            top.setItem(0, new ItemStack(Material.ECHO_SHARD, 2));
            top.setItem(2, new ItemStack(Material.GOLD_INGOT));
            click(view, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            check(top.getItem(0).getAmount() == 2 && cursor.get() == null
                            && top.getItem(2) == null,
                    "Weighted anvil take without a preview consumed or duplicated items");
            ItemStack selected = prepareStation(player, top, view, InventoryType.ANVIL);
            check(selected != null && (selected.getType() == Material.GOLD_INGOT
                            || selected.getType() == Material.DIAMOND),
                    "Weighted anvil preview chose an invalid result");
            ItemStack repeated = prepareStation(player, top, view, InventoryType.ANVIL);
            check(selected.equals(repeated), "Weighted anvil preview rerolled without input changes");
            top.setItem(2, new ItemStack(selected.getType() == Material.GOLD_INGOT
                    ? Material.DIAMOND : Material.GOLD_INGOT));
            click(view, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            check(top.getItem(0).getAmount() == 2 && cursor.get() == null
                            && completions.isEmpty(),
                    "Forged weighted anvil result committed");
            top.setItem(2, selected);
            click(view, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            check(top.getItem(0).getAmount() == 1 && selected.equals(cursor.get())
                            && completions.size() == 1
                            && selected.equals(completions.get(0).result()),
                    "Weighted anvil committed a different result or consumed the wrong amount");
            cursor.set(null);
            top.setItem(2, selected);
            click(view, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            check(top.getItem(0).getAmount() == 1 && cursor.get() == null
                            && completions.size() == 1,
                    "Weighted anvil preview was reused for a duplicate take");

            backing = Bukkit.createInventory(null, 9);
            top = stationInventory(backing, InventoryType.GRINDSTONE);
            view = view(player, top, InventoryType.GRINDSTONE);
            open.set(view);
            storage.set(new ItemStack[36]);
            top.setItem(0, new ItemStack(Material.NAUTILUS_SHELL, 2));
            selected = prepareStation(player, top, view, InventoryType.GRINDSTONE);
            check(selected != null && (selected.getType() == Material.IRON_INGOT
                            || selected.getType() == Material.EMERALD),
                    "Weighted grindstone preview chose an invalid result");
            top.setItem(0, new ItemStack(Material.NAUTILUS_SHELL, 3));
            top.setItem(2, selected);
            click(view, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
            check(top.getItem(0).getAmount() == 3 && completions.size() == 1
                            && count(storage.get(), selected.getType()) == 0,
                    "Changed grindstone inputs reused an old weighted preview");
            top.setItem(0, new ItemStack(Material.NAUTILUS_SHELL, 2));
            selected = prepareStation(player, top, view, InventoryType.GRINDSTONE);
            top.setItem(2, selected);
            click(view, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
            check(top.getItem(0).getAmount() == 1
                            && count(storage.get(), selected.getType()) == selected.getAmount()
                            && completions.size() == 2
                            && selected.equals(completions.get(1).result()),
                    "Weighted grindstone shift take duplicated or changed the selected result");
        } finally {
            HandlerList.unregisterAll(listener);
        }
    }

    static boolean optionalSaved(RecipeWeaverApi api) {
        return api.stationRecipe(OPTIONAL_ANVIL).map(recipe ->
                recipe.optional(1) && recipe.matchModes().get(0) == IngredientMatchMode.PARTIAL)
                .orElse(false)
                && api.stationRecipe(REQUIRED_ANVIL).map(recipe ->
                !recipe.optional(1) && recipe.matchModes().get(0) == IngredientMatchMode.PARTIAL)
                .orElse(false)
                && api.stationRecipe(OPTIONAL_GRINDSTONE).map(recipe ->
                recipe.optional(1) && recipe.matchModes().get(0) == IngredientMatchMode.PARTIAL)
                .orElse(false);
    }

    static boolean ingredientsSaved(RecipeWeaverApi api) {
        return api.stationRecipe(INGREDIENT_ANVIL).map(recipe ->
                recipe.ingredients().get(0).getAmount() == 3
                        && recipe.consumes(0) && !recipe.consumes(1)
                        && recipe.remainder(0).getType() == Material.BUCKET).orElse(false)
                && api.stationRecipe(INGREDIENT_GRINDSTONE).map(recipe ->
                recipe.ingredients().get(0).getAmount() == 2
                        && recipe.consumes(0) && !recipe.consumes(1)
                        && recipe.remainder(0).getType() == Material.FEATHER).orElse(false);
    }

    static boolean metadataSaved(RecipeWeaverApi api) {
        return api.stationRecipe(META_ANVIL).map(recipe ->
                recipe.resultMetadataMode() == ResultMetadataMode.NAME_LORE_CUSTOM_DATA
                        && recipe.resultCopySourceSlot() == 0).orElse(false)
                && api.stationRecipe(META_GRINDSTONE).map(recipe ->
                recipe.resultMetadataMode() == ResultMetadataMode.NAME_LORE_CUSTOM_DATA
                        && recipe.resultCopySourceSlot() == 1).orElse(false);
    }

    static boolean fullDataSaved(RecipeWeaverApi api) {
        return api.stationRecipe(FULL_DATA_ANVIL).map(recipe ->
                recipe.resultMetadataMode() == ResultMetadataMode.ALL_ITEM_DATA
                        && recipe.resultCopySourceSlot() == 0
                        && recipe.result().getAmount() == 2).orElse(false)
                && api.stationRecipe(FULL_DATA_GRINDSTONE).map(recipe ->
                recipe.resultMetadataMode() == ResultMetadataMode.ALL_ITEM_DATA
                        && recipe.resultCopySourceSlot() == 1
                        && recipe.result().getAmount() == 2).orElse(false);
    }

    static void checkSaved(RecipeWeaverApi api) {
        check(api.stationRecipe(ANVIL).map(recipe -> recipe.maxCraftsPerPlayer() == 2
                        && recipe.cooldownSeconds() == 30
                        && CONDITIONS.equals(recipe.conditions())
                        && ACTION_SOUND.equals(recipe.craftSound())
                        && ACTION_ADVANCEMENT.equals(recipe.craftAdvancement())
                        && ACTION_COMMAND.equals(recipe.craftCommand())).orElse(false),
                "Anvil player rules did not persist");
        check(api.stationRecipe(GRINDSTONE).map(recipe -> recipe.maxCraftsPerPlayer() == 1
                        && recipe.cooldownSeconds() == 0
                        && CONDITIONS.equals(recipe.conditions())
                        && ACTION_SOUND.equals(recipe.craftSound())
                        && recipe.craftAdvancement() == null
                        && recipe.craftCommand() == null).orElse(false),
                "Grindstone player rules did not persist");
        check(metadataSaved(api), "Station result metadata modes or sources did not persist");
        check(fullDataSaved(api), "Station full item data modes or sources did not persist");
        check(ingredientsSaved(api), "Station ingredient amounts, catalysts, or returns did not persist");
        check(optionalSaved(api), "Station optional inputs or partial modes did not persist");
    }

    private static StationRecipeDefinition optionalRecipe(NamespacedKey key, StationKind kind,
                                                          ItemStack first, Material second,
                                                          Material output, boolean optional) {
        return new StationRecipeDefinition(key, kind,
                List.of(first, new ItemStack(second)), new ItemStack(output), "smoke",
                200, 0, 0, true, false,
                List.of(IngredientMatchMode.PARTIAL, IngredientMatchMode.MATERIAL),
                List.of(List.of(), List.of()), Arrays.asList(null, null),
                CookingBookCategory.MISC, 0, 0, RecipeConditions.NONE,
                null, null, null, -1, ResultMetadataMode.NONE,
                List.of(true, true), Arrays.asList(null, null), List.of(false, optional));
    }

    private static ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    private static void verifyOptional(Player player, AtomicReference<InventoryView> open,
                                       AtomicReference<ItemStack> cursor,
                                       AtomicReference<ItemStack[]> storage) {
        ItemStack richClay = named(Material.CLAY_BALL, "Station partial sample");
        ItemMeta richMeta = richClay.getItemMeta();
        richMeta.setLore(List.of("Extra metadata allowed"));
        richClay.setItemMeta(richMeta);
        Inventory anvilTop = Bukkit.createInventory(null, 9);
        InventoryView anvilView = view(player, anvilTop, InventoryType.ANVIL);
        open.set(anvilView);
        anvilTop.setItem(0, richClay);
        ItemStack optionalPreview = preview(player, anvilTop, InventoryType.ANVIL);
        check(optionalPreview != null && optionalPreview.getType() == Material.GOLD_INGOT,
                "Optional anvil input was required or partial metadata did not match");
        anvilTop.setItem(2, optionalPreview);
        click(anvilView, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        check(anvilTop.getItem(0) == null && anvilTop.getItem(1) == null
                        && cursor.get().getType() == Material.GOLD_INGOT,
                "Absent optional anvil input was consumed or denied");
        cursor.set(null);
        anvilTop.setItem(0, richClay);
        anvilTop.setItem(1, new ItemStack(Material.REDSTONE, 2));
        ItemStack requiredPreview = preview(player, anvilTop, InventoryType.ANVIL);
        check(requiredPreview != null && requiredPreview.getType() == Material.EMERALD,
                "Required anvil recipe did not outrank its optional overlap");
        anvilTop.setItem(2, requiredPreview);
        click(anvilView, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        check(anvilTop.getItem(0) == null && anvilTop.getItem(1).getAmount() == 1
                        && count(storage.get(), Material.EMERALD) == 1,
                "Required anvil transaction did not consume the present second input");
        anvilTop.setItem(0, named(Material.CLAY_BALL, "Wrong sample"));
        anvilTop.setItem(1, null);
        check(preview(player, anvilTop, InventoryType.ANVIL) == null,
                "Partial anvil match accepted a wrong display name");

        Inventory grindTop = Bukkit.createInventory(null, 9);
        InventoryView grindView = view(player, grindTop, InventoryType.GRINDSTONE);
        open.set(grindView);
        grindTop.setItem(0, new ItemStack(Material.PAPER));
        ItemStack grindPreview = preview(player, grindTop, InventoryType.GRINDSTONE);
        check(grindPreview != null && grindPreview.getType() == Material.LAPIS_LAZULI,
                "Optional grindstone input was required");
        grindTop.setItem(2, grindPreview);
        click(grindView, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        check(grindTop.getItem(0) == null && grindTop.getItem(1) == null
                        && cursor.get().getType() == Material.LAPIS_LAZULI,
                "Absent optional grindstone input was consumed or denied");
        cursor.set(null);
        grindTop.setItem(0, new ItemStack(Material.PAPER));
        grindTop.setItem(1, new ItemStack(Material.BONE_MEAL, 2));
        grindTop.setItem(2, new ItemStack(Material.LAPIS_LAZULI));
        click(grindView, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        check(grindTop.getItem(0) == null && grindTop.getItem(1).getAmount() == 1
                        && cursor.get().getType() == Material.LAPIS_LAZULI,
                "Present optional grindstone input did not consume exactly one");
        cursor.set(null);
    }

    private static StationRecipeDefinition ingredientRecipe(NamespacedKey key, StationKind kind,
                                                            Material first, int amount, Material second,
                                                            Material output, Material returned) {
        return new StationRecipeDefinition(key, kind,
                List.of(new ItemStack(first, amount), new ItemStack(second)),
                new ItemStack(output), "smoke", 200, 0, 0, true, false,
                List.of(IngredientMatchMode.MATERIAL, IngredientMatchMode.EXACT),
                List.of(List.of(), List.of()), Arrays.asList(null, null),
                CookingBookCategory.MISC, 0, 0, RecipeConditions.NONE,
                null, null, null, -1, ResultMetadataMode.NONE,
                List.of(true, false), Arrays.asList(new ItemStack(returned), null));
    }

    private static void verifyIngredients(Player player, AtomicReference<InventoryView> open,
                                          AtomicReference<ItemStack> cursor,
                                          AtomicReference<ItemStack[]> storage) {
        Inventory anvilTop = Bukkit.createInventory(null, 9);
        InventoryView anvilView = view(player, anvilTop, InventoryType.ANVIL);
        open.set(anvilView);
        anvilTop.setItem(0, new ItemStack(Material.STONE, 5));
        anvilTop.setItem(1, new ItemStack(Material.GLASS_BOTTLE));
        check(preview(player, anvilTop, InventoryType.ANVIL).getType() == Material.DIAMOND,
                "Anvil amount and catalyst preview was absent");
        ItemStack[] full = new ItemStack[36];
        Arrays.fill(full, new ItemStack(Material.COBBLESTONE, 64));
        storage.set(full);
        anvilTop.setItem(2, new ItemStack(Material.DIAMOND));
        click(anvilView, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        check(anvilTop.getItem(0).getAmount() == 5 && anvilTop.getItem(1).getAmount() == 1
                        && cursor.get() == null && count(storage.get(), Material.BUCKET) == 0,
                "Anvil crafted even though its return item did not fit");
        storage.set(new ItemStack[36]);
        click(anvilView, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        check(anvilTop.getItem(0).getAmount() == 2
                        && anvilTop.getItem(1).getType() == Material.GLASS_BOTTLE
                        && count(storage.get(), Material.BUCKET) == 1
                        && cursor.get().getType() == Material.DIAMOND,
                "Anvil did not consume its amount, keep catalyst, and return an item");
        cursor.set(null);
        anvilTop.setItem(0, new ItemStack(Material.STONE, 3));
        anvilTop.setItem(2, new ItemStack(Material.DIAMOND));
        click(anvilView, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        check(anvilTop.getItem(0).getType() == Material.BUCKET
                        && anvilTop.getItem(1).getType() == Material.GLASS_BOTTLE
                        && cursor.get().getType() == Material.DIAMOND,
                "Anvil did not replace a fully consumed input with its return item");
        cursor.set(null);

        Inventory grindTop = Bukkit.createInventory(null, 9);
        InventoryView grindView = view(player, grindTop, InventoryType.GRINDSTONE);
        open.set(grindView);
        grindTop.setItem(0, new ItemStack(Material.STRING, 2));
        grindTop.setItem(1, new ItemStack(Material.STICK));
        check(preview(player, grindTop, InventoryType.GRINDSTONE).getType() == Material.IRON_INGOT,
                "Grindstone amount and catalyst preview was absent");
        grindTop.setItem(2, new ItemStack(Material.IRON_INGOT));
        click(grindView, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        check(grindTop.getItem(0).getType() == Material.FEATHER
                        && grindTop.getItem(1).getType() == Material.STICK
                        && count(storage.get(), Material.IRON_INGOT) == 1,
                "Grindstone shift craft did not commit amount, catalyst, and return item");
    }

    private static StationRecipeDefinition metadataRecipe(NamespacedKey key, StationKind kind,
                                                          Material first, Material second,
                                                          Material output, int sourceSlot) {
        return new StationRecipeDefinition(key, kind,
                Arrays.asList(new ItemStack(first), second == null ? null : new ItemStack(second)),
                new ItemStack(output), "smoke", 200, 0, 0, true, false,
                Arrays.asList(IngredientMatchMode.MATERIAL, IngredientMatchMode.MATERIAL),
                List.of(List.of(), List.of()), Arrays.asList(null, null),
                CookingBookCategory.MISC, 0, 0, RecipeConditions.NONE,
                null, null, null, sourceSlot, ResultMetadataMode.NAME_LORE_CUSTOM_DATA);
    }

    private static StationRecipeDefinition fullDataRecipe(NamespacedKey key, StationKind kind) {
        boolean anvil = kind == StationKind.ANVIL;
        return new StationRecipeDefinition(key, kind,
                anvil ? Arrays.asList(new ItemStack(Material.FIREWORK_ROCKET), null)
                        : List.of(new ItemStack(Material.BRICK),
                                new ItemStack(Material.FIREWORK_ROCKET)),
                FullItemDataSmoke.rocket(2, "station-template"), "smoke", 200, 0, 0,
                true, false, List.of(IngredientMatchMode.MATERIAL,
                        IngredientMatchMode.MATERIAL),
                List.of(List.of(), List.of()), Arrays.asList(null, null),
                CookingBookCategory.MISC, 0, 0, RecipeConditions.NONE,
                null, null, null, anvil ? 0 : 1, ResultMetadataMode.ALL_ITEM_DATA);
    }

    private static void verifyFullData(Player player, AtomicReference<InventoryView> open,
                                       AtomicReference<ItemStack> cursor,
                                       AtomicReference<ItemStack[]> storage) {
        cursor.set(null);
        storage.set(new ItemStack[36]);
        ItemStack anvilSource = FullItemDataSmoke.rocket(3, "anvil-input");
        Inventory anvilTop = Bukkit.createInventory(null, 9);
        InventoryView anvilView = view(player, anvilTop, InventoryType.ANVIL);
        open.set(anvilView);
        anvilTop.setItem(0, anvilSource);
        ItemStack anvilPreview = preview(player, anvilTop, InventoryType.ANVIL);
        FullItemDataSmoke.checkCopied(anvilSource, anvilPreview, 2, "Anvil preview");
        anvilTop.setItem(2, FullItemDataSmoke.rocket(2, "stale-template"));
        click(anvilView, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        check(anvilTop.getItem(0).getAmount() == 3 && cursor.get() == null
                        && anvilTop.getItem(2) == null,
                "Stale anvil full-data result bypassed input-based verification");
        anvilTop.setItem(2, anvilPreview);
        click(anvilView, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        check(anvilTop.getItem(0) != null && anvilTop.getItem(0).getAmount() == 2,
                "Anvil full-data result consumed the wrong amount");
        FullItemDataSmoke.checkCopied(anvilSource, cursor.get(), 2, "Anvil result click");

        cursor.set(null);
        ItemStack grindSource = FullItemDataSmoke.rocket(3, "grindstone-input");
        Inventory grindTop = Bukkit.createInventory(null, 9);
        InventoryView grindView = view(player, grindTop, InventoryType.GRINDSTONE);
        open.set(grindView);
        grindTop.setItem(0, new ItemStack(Material.BRICK));
        grindTop.setItem(1, grindSource);
        ItemStack grindPreview = preview(player, grindTop, InventoryType.GRINDSTONE);
        FullItemDataSmoke.checkCopied(grindSource, grindPreview, 2, "Grindstone preview");
        grindTop.setItem(2, grindPreview);
        click(grindView, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        check(grindTop.getItem(0) == null && grindTop.getItem(1) != null
                        && grindTop.getItem(1).getAmount() == 2,
                "Grindstone full-data result consumed the wrong inputs");
        FullItemDataSmoke.checkCopied(grindSource, storage.get()[0], 2,
                "Grindstone shift result click");
    }

    /** Reuses the same station inventory for prepare and click, as the server does. */
    private static void verifyStaleOwnedResults(RecipeWeaverApi api, Player player,
                                                AtomicReference<InventoryView> open,
                                                AtomicReference<ItemStack> cursor) {
        cursor.set(null);
        for (InventoryType type : List.of(InventoryType.ANVIL, InventoryType.GRINDSTONE)) {
            Inventory backing = Bukkit.createInventory(null, 9);
            Inventory typed = stationInventory(backing, type);
            InventoryView stationView = view(player, typed, type);
            open.set(stationView);
            ItemStack source = FullItemDataSmoke.rocket(2, "stale-input");
            if (type == InventoryType.ANVIL) {
                backing.setItem(0, source);
            } else {
                backing.setItem(0, new ItemStack(Material.BRICK));
                backing.setItem(1, source);
            }
            ItemStack preview = prepareStation(player, typed, stationView, type);
            check(preview != null && preview.getType() == Material.FIREWORK_ROCKET,
                    type + " owned preview was absent");
            backing.setItem(2, preview);
            backing.setItem(type == InventoryType.ANVIL ? 0 : 1, new ItemStack(Material.DIRT));
            click(stationView, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            check(backing.getItem(2) == null && cursor.get() == null,
                    type + " stale owned result was taken after its input changed");

            backing.setItem(type == InventoryType.ANVIL ? 0 : 1, source);
            preview = prepareStation(player, typed, stationView, type);
            check(preview != null, type + " owned preview did not recover");
            backing.setItem(2, preview);
            backing.setItem(type == InventoryType.ANVIL ? 0 : 1, new ItemStack(Material.DIRT));
            check(prepareStation(player, typed, stationView, type) == null
                            && backing.getItem(2) == null,
                    type + " prepare did not clear a stale owned preview");

            backing.setItem(type == InventoryType.ANVIL ? 0 : 1, source);
            preview = prepareStation(player, typed, stationView, type);
            check(preview != null, type + " preview did not recover after an input change");
            backing.setItem(2, preview);
            setLockdown(api, true);
            click(stationView, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            check(backing.getItem(2) == null && cursor.get() == null,
                    type + " lockdown allowed an owned result click");
            setLockdown(api, false);

            preview = prepareStation(player, typed, stationView, type);
            check(preview != null, type + " preview did not recover after lockdown");
            backing.setItem(2, preview);
            setLockdown(api, true);
            check(prepareStation(player, typed, stationView, type) == null
                            && backing.getItem(2) == null,
                    type + " prepare did not clear an owned result during lockdown");
            setLockdown(api, false);

            NamespacedKey key = type == InventoryType.ANVIL
                    ? FULL_DATA_ANVIL : FULL_DATA_GRINDSTONE;
            preview = prepareStation(player, typed, stationView, type);
            check(preview != null, type + " preview did not recover before recipe removal");
            backing.setItem(2, preview);
            check(api.removeStationRecipe(key), type + " recipe removal failed");
            click(stationView, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            check(backing.getItem(2) == null && cursor.get() == null,
                    type + " removed recipe allowed an owned result click");
            api.saveStationRecipe(fullDataRecipe(key,
                    type == InventoryType.ANVIL ? StationKind.ANVIL : StationKind.GRINDSTONE));

            preview = prepareStation(player, typed, stationView, type);
            check(preview != null, type + " preview did not recover after recipe restore");
            backing.setItem(2, preview);
            check(api.removeStationRecipe(key), type + " second recipe removal failed");
            check(prepareStation(player, typed, stationView, type) == null
                            && backing.getItem(2) == null,
                    type + " prepare did not clear a removed recipe's owned result");
            api.saveStationRecipe(fullDataRecipe(key,
                    type == InventoryType.ANVIL ? StationKind.ANVIL : StationKind.GRINDSTONE));
        }
    }

    private static void setLockdown(RecipeWeaverApi api, boolean enabled) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "rw lockdown");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "rw lockdown");
        check(api.lockedDown() == enabled, "Station preview test could not change lockdown");
    }

    private static void verifyMetadata(Player player, AtomicReference<InventoryView> open,
                                       AtomicReference<ItemStack> cursor, AtomicInteger sounds) {
        ItemStack anvilSource = marked(Material.PRISMARINE_SHARD, "Anvil source", "anvil");
        ItemStack anvilResult = copied(new ItemStack(Material.GOLD_INGOT), anvilSource);
        Inventory anvilTop = Bukkit.createInventory(null, 9);
        InventoryView anvilView = view(player, anvilTop, InventoryType.ANVIL);
        open.set(anvilView);
        anvilTop.setItem(0, anvilSource);
        ItemStack anvilPreview = preview(player, anvilTop, InventoryType.ANVIL);
        check(anvilPreview != null && anvilPreview.isSimilar(anvilResult),
                "Anvil prepare event did not copy actual input metadata");
        anvilTop.setItem(2, new ItemStack(Material.GOLD_INGOT));
        click(anvilView, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        check(anvilTop.getItem(0).getAmount() == 2 && cursor.get() == null
                        && anvilTop.getItem(2) == null,
                "Stale anvil result bypassed actual-input metadata copying");
        anvilTop.setItem(2, anvilResult);
        click(anvilView, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        check(anvilTop.getItem(0).getAmount() == 1
                        && cursor.get() != null && cursor.get().isSimilar(anvilResult)
                        && "anvil".equals(cursor.get().getItemMeta().getPersistentDataContainer()
                                .get(META_KEY, PersistentDataType.STRING)),
                "Committed anvil result did not copy actual input metadata");
        cursor.set(null);

        ItemStack grindSource = marked(Material.BOW, "Grindstone source", "grindstone");
        ItemStack grindResult = copied(new ItemStack(Material.EMERALD), grindSource);
        Inventory grindTop = Bukkit.createInventory(null, 9);
        InventoryView grindView = view(player, grindTop, InventoryType.GRINDSTONE);
        open.set(grindView);
        grindTop.setItem(0, new ItemStack(Material.RABBIT_HIDE));
        grindTop.setItem(1, grindSource);
        ItemStack grindPreview = preview(player, grindTop, InventoryType.GRINDSTONE);
        check(grindPreview != null && grindPreview.isSimilar(grindResult),
                "Grindstone prepare event did not copy the chosen second input");
        grindTop.setItem(2, new ItemStack(Material.EMERALD));
        click(grindView, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        check(grindTop.getItem(0) != null && grindTop.getItem(1).getAmount() == 2
                        && cursor.get() == null && grindTop.getItem(2) == null,
                "Stale grindstone result bypassed actual-input metadata copying");
        grindTop.setItem(2, grindResult);
        click(grindView, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        check(grindTop.getItem(0) == null && grindTop.getItem(1).getAmount() == 1
                        && cursor.get() != null && cursor.get().isSimilar(grindResult)
                        && "grindstone".equals(cursor.get().getItemMeta().getPersistentDataContainer()
                                .get(META_KEY, PersistentDataType.STRING)),
                "Committed grindstone result did not copy the chosen second input");
        check(sounds.get() == 3 && COMMANDS.get() == 2,
                "Station metadata crafting ran unrelated actions");
        cursor.set(null);
    }

    private static ItemStack marked(Material material, String name, String marker) {
        ItemStack item = new ItemStack(material, 2);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(List.of("Inherited lore"));
        meta.getPersistentDataContainer().set(META_KEY, PersistentDataType.STRING, marker);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack copied(ItemStack template, ItemStack source) {
        ItemStack result = template.clone();
        ItemMeta sourceMeta = source.getItemMeta();
        ItemMeta resultMeta = result.getItemMeta();
        resultMeta.setDisplayName(sourceMeta.getDisplayName());
        resultMeta.setLore(sourceMeta.getLore());
        resultMeta.getPersistentDataContainer().set(META_KEY, PersistentDataType.STRING,
                sourceMeta.getPersistentDataContainer().get(META_KEY, PersistentDataType.STRING));
        result.setItemMeta(resultMeta);
        return result;
    }

    private static ItemStack preview(Player player, Inventory backing, InventoryType type) {
        Inventory typed = stationInventory(backing, type);
        return prepareStation(player, typed, view(player, typed, type), type);
    }

    private static Inventory stationInventory(Inventory backing, InventoryType type) {
        Class<?> stationInventory = type == InventoryType.ANVIL
                ? AnvilInventory.class : GrindstoneInventory.class;
        return (Inventory) Proxy.newProxyInstance(stationInventory.getClassLoader(),
                new Class<?>[] {stationInventory}, (proxy, method, args) -> switch (method.getName()) {
                    case "getItem" -> backing.getItem((int) args[0]);
                    case "setItem" -> { backing.setItem((int) args[0], (ItemStack) args[1]); yield null; }
                    case "getSize" -> backing.getSize();
                    case "getContents" -> backing.getContents();
                    case "getHolder" -> null;
                    case "getType" -> type;
                    case "setRepairCost" -> null;
                    case "getRepairCost" -> 0;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> throw new UnsupportedOperationException("StationInventory." + method.getName());
                });
    }

    private static ItemStack prepareStation(Player player, Inventory typed,
                                            InventoryView typedView, InventoryType type) {
        if (type == InventoryType.ANVIL) {
            PrepareAnvilEvent event = prepareAnvil(player, typed, typedView);
            Bukkit.getPluginManager().callEvent(event);
            return event.getResult();
        }
        PrepareGrindstoneEvent event = new PrepareGrindstoneEvent(typedView, null);
        Bukkit.getPluginManager().callEvent(event);
        return event.getResult();
    }

    private static PrepareAnvilEvent prepareAnvil(Player player, Inventory top,
                                                   InventoryView legacyView) {
        try {
            var constructor = PrepareAnvilEvent.class.getConstructor(InventoryView.class, ItemStack.class);
            return constructor.newInstance(legacyView, null);
        } catch (NoSuchMethodException modernApi) {
            try {
                Class<?> anvilView = Class.forName("org.bukkit.inventory.view.AnvilView");
                Object view = Proxy.newProxyInstance(anvilView.getClassLoader(),
                        new Class<?>[] {anvilView}, (proxy, method, args) -> switch (method.getName()) {
                            case "getTopInventory" -> top;
                            case "getBottomInventory" -> legacyView.getBottomInventory();
                            case "getPlayer" -> player;
                            case "getType" -> InventoryType.ANVIL;
                            case "getTitle", "getOriginalTitle" -> "Station metadata smoke";
                            case "setTitle", "setRepairCost", "setRepairItemCountCost",
                                    "setMaximumRepairCost", "bypassEnchantmentLevelRestriction" -> null;
                            case "getRepairCost", "getRepairItemCountCost", "getMaximumRepairCost" -> 0;
                            case "getRenameText" -> null;
                            case "bypassesEnchantmentLevelRestriction" -> false;
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> throw new UnsupportedOperationException("AnvilView." + method.getName());
                        });
                var constructor = PrepareAnvilEvent.class.getConstructor(anvilView, ItemStack.class);
                return constructor.newInstance(view, null);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Could not create modern anvil preview probe", ex);
            }
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not create anvil preview probe", ex);
        }
    }

    private static StationRecipeDefinition recipe(NamespacedKey key, StationKind kind,
                                                  Material input, Material output, int limit,
                                                  int delaySeconds) {
        return new StationRecipeDefinition(key, kind,
                Arrays.asList(new ItemStack(input), null), new ItemStack(output), "smoke",
                200, 0, 0, true, false,
                List.of(IngredientMatchMode.EXACT, IngredientMatchMode.EXACT),
                List.of(List.of(), List.of()), Arrays.asList(null, null),
                CookingBookCategory.MISC, limit, delaySeconds, CONDITIONS,
                ACTION_SOUND, kind == StationKind.ANVIL ? ACTION_ADVANCEMENT : null,
                kind == StationKind.ANVIL ? ACTION_COMMAND : null);
    }

    private static InventoryView view(Player player, Inventory top, InventoryType type) {
        Inventory bottom = Bukkit.createInventory(null, 9);
        return new InventoryView() {
            @Override public Inventory getTopInventory() { return top; }
            @Override public Inventory getBottomInventory() { return bottom; }
            @Override public Player getPlayer() { return player; }
            @Override public InventoryType getType() { return type; }
            @Override public String getTitle() { return "Station rule smoke"; }
            @Override public String getOriginalTitle() { return getTitle(); }
            @Override public void setTitle(String title) { }
        };
    }

    private static void click(InventoryView view, ClickType click, InventoryAction action) {
        InventoryClickEvent event = new InventoryClickEvent(view,
                InventoryType.SlotType.RESULT, 2, click, action);
        Bukkit.getPluginManager().callEvent(event);
        check(event.isCancelled(), "Station result click escaped the production handler");
    }

    private static int count(ItemStack[] contents, Material material) {
        int found = 0;
        for (ItemStack item : contents) if (item != null && item.getType() == material) {
            found += item.getAmount();
        }
        return found;
    }

    private static NamespacedKey countKey(NamespacedKey recipe) {
        return new NamespacedKey("recipeweaver", "craft-count/"
                + recipe.getNamespace() + "/" + recipe.getKey());
    }

    private static NamespacedKey delayKey(NamespacedKey recipe) {
        return new NamespacedKey("recipeweaver", "cooldown/"
                + recipe.getNamespace() + "/" + recipe.getKey());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
