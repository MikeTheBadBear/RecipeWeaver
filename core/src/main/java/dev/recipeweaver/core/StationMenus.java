package dev.recipeweaver.core;

import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.StationRecipeDefinition;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.WeatherCondition;
import dev.recipeweaver.api.CraftSoundAction;
import dev.recipeweaver.api.CraftAdvancementAction;
import dev.recipeweaver.api.CraftCommandAction;
import dev.recipeweaver.api.CraftCommandSender;
import dev.recipeweaver.api.ResultMetadataMode;
import dev.recipeweaver.api.WeightedResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.recipe.CookingBookCategory;
import org.bukkit.inventory.meta.ItemMeta;

/** Inventory editor for native workstation recipes. */
final class StationMenus implements Listener {
    private static final int[] INPUT_SLOTS = {10, 11, 12};
    private final RecipeWeaverPlugin plugin;
    private final TextPrompt prompt;
    private final BookPrompt bookPrompt;
    private final Map<UUID, Draft> drafts = new HashMap<>();
    private final Set<UUID> pendingWrites = new HashSet<>();

    StationMenus(RecipeWeaverPlugin plugin) {
        this.plugin = plugin;
        this.prompt = new TextPrompt(plugin);
        this.bookPrompt = plugin.bookPrompt();
        Bukkit.getPluginManager().registerEvents(prompt, plugin);
    }

    void openTypes(Player player) {
        Holder holder = new Holder(player.getUniqueId(), true);
        Inventory inventory = create(holder, 27, "RecipeWeaver Stations");
        StationKind[] kinds = StationKind.values();
        for (int i = 0; i < kinds.length; i++) {
            inventory.setItem(i + 9, icon(materialFor(kinds[i]), kinds[i].name().replace('_', ' '),
                    kinds[i].slots() + " ingredient slots"));
        }
        inventory.setItem(22, icon(Material.ARROW, "Back to studio"));
        player.openInventory(inventory);
    }

    void openExisting(Player player, StationRecipeDefinition recipe) {
        drafts.put(player.getUniqueId(), new Draft(recipe));
        openEditor(player);
    }

    private void openEditor(Player player) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null) { openTypes(player); return; }
        Holder holder = new Holder(player.getUniqueId(), false);
        Inventory inventory = create(holder, 54, "Station: " + draft.kind.name());
        for (int i = 0; i < draft.kind.slots(); i++) {
            inventory.setItem(INPUT_SLOTS[i], draft.ingredients[i] == null
                    ? icon(Material.GRAY_STAINED_GLASS_PANE, inputName(draft.kind, i), "Click with an item to set")
                    : showIngredient(draft.ingredients[i], draft.matchModes[i],
                            draft.alternatives.get(i).size(), draft.itemTags[i]));
        }
        inventory.setItem(31, icon(Material.HOPPER, "Ingredient choices",
                "Left click a slot with a cursor item to set its sample",
                "Right click with an empty cursor: exact / material",
                "Shift-left click a sample: alternatives, amounts, and consumption"));
        if (supportsConditions(draft.kind)) {
            inventory.setItem(32, icon(Material.COMPARATOR,
                    "Craft conditions: " + draft.conditionCount(),
                    "Click to edit permission, world, biome, time, weather,",
                    "level, scoreboard, and advancement requirements",
                    draft.kind.cooking()
                            ? "Cooking has no player: player-only rules block it"
                            : draft.kind == StationKind.BREWING
                            ? "Brewing has no player: player-only rules block it"
                            : draft.kind == StationKind.CAULDRON
                            ? "Checked on both inputs and before the mix commits"
                            : "Conditions are checked when the result is taken"));
        }
        if (draft.kind == StationKind.ANVIL || draft.kind == StationKind.GRINDSTONE
                || draft.kind == StationKind.CAULDRON || draft.kind.cooking()) {
            inventory.setItem(33, icon(Material.NOTE_BLOCK,
                    "Craft actions: " + draft.actionCount(),
                    draft.kind.cooking()
                            ? "Click to edit post-cook sound and console command"
                            : "Click to edit post-craft sound, command, and advancement"));
        }
        if (draft.kind == StationKind.ANVIL || draft.kind == StationKind.GRINDSTONE
                || draft.kind == StationKind.CAULDRON || draft.kind == StationKind.BREWING) {
            inventory.setItem(34, icon(Material.WRITABLE_BOOK,
                    "Result metadata: " + draft.resultMetadataMode,
                    "Source: " + (draft.resultCopySourceSlot < 0
                            ? "none" : "ingredient " + (draft.resultCopySourceSlot + 1)),
                    "Shift-right click an ingredient to choose its source",
                    "Click to cycle name, lore, common fields, and all item data",
                    "All item data needs the same source and result material"));
        }
        if (supportsWeightedResults(draft.kind)) inventory.setItem(35, icon(Material.CHEST,
                "Weighted results: " + draft.resultChoices.size(),
                "Click to edit up to " + StationRecipeDefinition.MAX_RESULTS + " outcomes",
                draft.kind == StationKind.CAULDRON
                        ? "One result is rolled when the second item is mixed"
                        : draft.kind == StationKind.BREWING
                        ? "Each brewed bottle rolls one result"
                        : "The preview rolls once and stays fixed until inputs change"));
        if (draft.kind == StationKind.SMITHING_TRIM) {
            inventory.setItem(24, icon(Material.SMITHING_TABLE, "Dynamic armor trim", "Uses the base item and selected trim"));
        } else {
            inventory.setItem(24, draft.result == null ? icon(Material.BARRIER, "Result", "Click with an item")
                    : draft.result.clone());
        }
        inventory.setItem(37, icon(Material.ITEM_FRAME,
                "Group: " + (draft.group.isBlank() ? "none" : draft.group),
                "Click to edit", "Enter none to clear"));
        if (draft.kind.cooking()) inventory.setItem(36, icon(Material.BOOKSHELF,
                "Vanilla book category: " + draft.bookCategory,
                "Click to cycle cooking categories"));
        inventory.setItem(38, icon(Material.CLOCK, "Cooking ticks: " + draft.cookingTicks, "Used by cooking stations"));
        inventory.setItem(39, icon(Material.EXPERIENCE_BOTTLE, "Experience: " + draft.experience, "Used by cooking stations"));
        inventory.setItem(40, icon(Material.EXPERIENCE_BOTTLE, "Anvil levels: " + draft.anvilLevels));
        inventory.setItem(41, icon(draft.hidden ? Material.REDSTONE_TORCH : Material.TORCH,
                "Hidden: " + draft.hidden, "Click to switch"));
        inventory.setItem(42, icon(draft.enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                "Enabled: " + draft.enabled, "Click to switch"));
        if (draft.kind == StationKind.ANVIL || draft.kind == StationKind.GRINDSTONE) {
            inventory.setItem(43, icon(Material.IRON_BARS,
                    "Player craft limit: " + draft.maxCraftsPerPlayer,
                    "0 = unlimited; applies after each successful result take"));
            inventory.setItem(44, icon(Material.REPEATER,
                    "Player craft delay: " + draft.cooldownSeconds + " seconds",
                    "0 = off; starts after a successful result take"));
        }
        inventory.setItem(45, icon(Material.ARROW, "Back to station types"));
        inventory.setItem(49, icon(Material.EMERALD_BLOCK, "Save station recipe", "Choose an ID"));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        int slot = event.getRawSlot();
        if (slot >= event.getView().getTopInventory().getSize()) {
            if (event.isShiftClick()) event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        if (!holder.owner.equals(player.getUniqueId())) return;
        if (!player.hasPermission("recipeweaver.studio")) return;
        if (slot < 0) return;
        if (holder.types) {
            int index = slot - 9;
            if (index >= 0 && index < StationKind.values().length) {
                drafts.put(player.getUniqueId(), new Draft(StationKind.values()[index]));
                openEditor(player);
            } else if (slot == 22) plugin.menus().openHome(player);
            return;
        }
        if (holder.results) {
            clickResults(player, slot, event.getClick(), event.getCursor());
        } else if (holder.conditions) {
            clickConditions(player, slot);
        } else if (holder.actions) {
            clickActions(player, slot);
        } else if (holder.ingredientSlot >= 0) {
            clickIngredient(player, holder.ingredientSlot, slot, event.getClick(), event.getCursor());
        } else clickEditor(player, slot, event.getClick(), event.getCursor());
    }

    private void clickEditor(Player player, int slot, ClickType click, ItemStack cursor) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null) { openTypes(player); return; }
        for (int i = 0; i < draft.kind.slots(); i++) {
            if (slot == INPUT_SLOTS[i]) {
                if (click == ClickType.SHIFT_LEFT && draft.ingredients[i] != null) {
                    openIngredientEditor(player, i);
                    return;
                }
                if (click == ClickType.SHIFT_RIGHT && draft.ingredients[i] != null
                        && (draft.kind == StationKind.ANVIL || draft.kind == StationKind.GRINDSTONE
                                || draft.kind == StationKind.CAULDRON
                                || draft.kind == StationKind.BREWING)) {
                    draft.resultCopySourceSlot = i;
                    if (draft.resultMetadataMode == ResultMetadataMode.NONE) {
                        draft.resultMetadataMode = ResultMetadataMode.NAME_AND_LORE;
                    }
                    openEditor(player);
                    return;
                }
                if (click == ClickType.RIGHT && copy(cursor, true) == null && draft.ingredients[i] != null) {
                    cycleMatchMode(player, draft, i);
                } else if (click == ClickType.LEFT || click == ClickType.RIGHT) {
                    ItemStack sample = copy(cursor, draft.kind != StationKind.ANVIL
                            && draft.kind != StationKind.GRINDSTONE
                            && draft.kind != StationKind.CAULDRON
                            && !(draft.kind == StationKind.BREWING && i == 0));
                    if (sample == null || draft.ingredients[i] == null
                            || !draft.ingredients[i].isSimilar(sample)) {
                        draft.matchModes[i] = IngredientMatchMode.EXACT;
                        draft.alternatives.get(i).clear();
                        draft.itemTags[i] = null;
                        draft.consumeSlots[i] = true;
                        draft.remainders[i] = null;
                        draft.optionalSlots[i] = false;
                    }
                    draft.ingredients[i] = sample;
                    if (sample == null && draft.resultCopySourceSlot == i) {
                        draft.resultCopySourceSlot = -1;
                        draft.resultMetadataMode = ResultMetadataMode.NONE;
                    }
                }
                openEditor(player);
                return;
            }
        }
        switch (slot) {
            case 32 -> {
                if (supportsConditions(draft.kind)) {
                    openConditionsEditor(player);
                }
            }
            case 33 -> {
                if (draft.kind == StationKind.ANVIL || draft.kind == StationKind.GRINDSTONE
                        || draft.kind == StationKind.CAULDRON || draft.kind.cooking()) {
                    openActionsEditor(player);
                }
            }
            case 34 -> {
                if (draft.kind != StationKind.ANVIL && draft.kind != StationKind.GRINDSTONE
                        && draft.kind != StationKind.CAULDRON
                        && draft.kind != StationKind.BREWING) return;
                ResultMetadataMode[] modes = ResultMetadataMode.values();
                draft.resultMetadataMode = modes[(draft.resultMetadataMode.ordinal() + 1) % modes.length];
                if (draft.resultMetadataMode == ResultMetadataMode.NONE) {
                    draft.resultCopySourceSlot = -1;
                } else if (draft.resultCopySourceSlot < 0) {
                    draft.resultCopySourceSlot = 0;
                }
                openEditor(player);
            }
            case 35 -> {
                if (supportsWeightedResults(draft.kind)) {
                    openResultsEditor(player);
                }
            }
            case 36 -> {
                if (!draft.kind.cooking()) return;
                CookingBookCategory[] categories = CookingBookCategory.values();
                draft.bookCategory = categories[(draft.bookCategory.ordinal() + 1) % categories.length];
                openEditor(player);
            }
            case 24 -> {
                if (draft.kind != StationKind.SMITHING_TRIM) {
                    draft.result = copy(cursor, false);
                    if (draft.result == null) draft.resultChoices.clear();
                    else if (draft.resultChoices.isEmpty()) {
                        draft.resultChoices.add(new WeightedResult(draft.result, 1));
                    } else {
                        int weight = draft.resultChoices.get(0).weight();
                        draft.resultChoices.set(0, new WeightedResult(draft.result, weight));
                    }
                }
                openEditor(player);
            }
            case 37 -> prompt.open(player, "Group", draft.group, value -> {
                draft.group = StudioMenus.groupInput(value);
                openEditor(player);
            }, () -> openEditor(player));
            case 38 -> editNumber(player, draft, "Cooking ticks", Integer.toString(draft.cookingTicks), value -> {
                int number = Integer.parseInt(value);
                if (number < 1 || number > 72000) throw new IllegalArgumentException("Use 1-72000 ticks");
                draft.cookingTicks = number;
            });
            case 39 -> editNumber(player, draft, "Experience", Float.toString(draft.experience), value -> {
                float number = Float.parseFloat(value);
                if (!Float.isFinite(number) || number < 0) throw new IllegalArgumentException("Use nonnegative XP");
                draft.experience = number;
            });
            case 40 -> editNumber(player, draft, "Anvil levels", Integer.toString(draft.anvilLevels), value -> {
                int number = Integer.parseInt(value);
                if (number < 0 || number > 40) throw new IllegalArgumentException("Use 0-40 levels");
                draft.anvilLevels = number;
            });
            case 41 -> { draft.hidden = !draft.hidden; openEditor(player); }
            case 42 -> { draft.enabled = !draft.enabled; openEditor(player); }
            case 43 -> {
                if (draft.kind != StationKind.ANVIL && draft.kind != StationKind.GRINDSTONE) return;
                editNumber(player, draft, "Player craft limit", Integer.toString(draft.maxCraftsPerPlayer), value -> {
                    int number = Integer.parseInt(value);
                    if (number < 0 || number > 1_000_000) throw new IllegalArgumentException("Use 0-1000000 crafts");
                    draft.maxCraftsPerPlayer = number;
                });
            }
            case 44 -> {
                if (draft.kind != StationKind.ANVIL && draft.kind != StationKind.GRINDSTONE) return;
                editNumber(player, draft, "Craft delay seconds", Integer.toString(draft.cooldownSeconds), value -> {
                    int number = Integer.parseInt(value);
                    if (number < 0 || number > 86400) throw new IllegalArgumentException("Use 0-86400 seconds");
                    draft.cooldownSeconds = number;
                });
            }
            case 45 -> { drafts.remove(player.getUniqueId()); openTypes(player); }
            case 49 -> prompt.open(player, "Station recipe ID", draft.existingKey == null ? "new_station_recipe" : draft.existingKey.getKey(), value ->
                    save(player, draft, value), () -> openEditor(player));
            default -> { }
        }
    }

    private void openResultsEditor(Player player) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null) { openTypes(player); return; }
        if (!supportsWeightedResults(draft.kind)) {
            openEditor(player);
            return;
        }
        Holder holder = new Holder(player.getUniqueId(), false, -1, false, false, true);
        Inventory inventory = create(holder, 54, "Station Weighted Results");
        for (int slot = 0; slot < StationRecipeDefinition.MAX_RESULTS; slot++) {
            if (slot < draft.resultChoices.size()) {
                WeightedResult choice = draft.resultChoices.get(slot);
                ItemStack shown = choice.item();
                ItemMeta meta = shown.getItemMeta();
                List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore())
                        : new ArrayList<>();
                lore.add(ChatColor.AQUA + "Weight: " + choice.weight());
                lore.add(ChatColor.GRAY + "Left click with an item to replace");
                lore.add(ChatColor.GRAY + "Left click empty to remove");
                lore.add(ChatColor.GRAY + "Right click empty to edit weight");
                meta.setLore(lore);
                shown.setItemMeta(meta);
                inventory.setItem(slot, shown);
            } else {
                inventory.setItem(slot, icon(Material.GRAY_STAINED_GLASS_PANE,
                        "Result " + (slot + 1), "Left click with an item to add"));
            }
        }
        inventory.setItem(17, icon(Material.PAPER, "Result choices",
                "Weights 1 through " + WeightedResult.MAX_WEIGHT,
                "Anvil, grindstone, cauldron, and brewing results can vary",
                draft.kind == StationKind.BREWING
                        ? "Brewing result choices must each have amount 1" : ""));
        inventory.setItem(53, icon(Material.ARROW, "Back to station editor"));
        player.openInventory(inventory);
    }

    private void clickResults(Player player, int slot, ClickType click, ItemStack cursor) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null) { openTypes(player); return; }
        if (slot == 53) { openEditor(player); return; }
        if (slot < 0 || slot >= StationRecipeDefinition.MAX_RESULTS) return;
        List<WeightedResult> choices = draft.resultChoices;
        if (click == ClickType.RIGHT && copy(cursor, false) == null && slot < choices.size()) {
            WeightedResult old = choices.get(slot);
            prompt.open(player, "Result weight", Integer.toString(old.weight()), value -> {
                if (!player.hasPermission("recipeweaver.studio")) return;
                try { choices.set(slot, new WeightedResult(old.item(), Integer.parseInt(value))); }
                catch (IllegalArgumentException ex) {
                    player.sendMessage(ChatColor.RED + "Weight must be 1 through "
                            + WeightedResult.MAX_WEIGHT + ".");
                }
                openResultsEditor(player);
            }, () -> openResultsEditor(player));
            return;
        }
        if (click != ClickType.LEFT) return;
        ItemStack sample = copy(cursor, false);
        if (sample != null) {
            if (draft.kind == StationKind.BREWING && sample.getAmount() != 1) {
                player.sendMessage(ChatColor.RED + "Brewing result choices must each have amount 1.");
                openResultsEditor(player);
                return;
            }
            if (slot > choices.size()) return;
            int weight = slot < choices.size() ? choices.get(slot).weight() : 1;
            try {
                WeightedResult choice = new WeightedResult(sample, weight);
                if (slot < choices.size()) choices.set(slot, choice);
                else choices.add(choice);
            } catch (IllegalArgumentException ex) {
                player.sendMessage(ChatColor.RED + ex.getMessage());
            }
        } else if (slot < choices.size()) {
            choices.remove(slot);
        }
        draft.result = choices.isEmpty() ? null : choices.get(0).item();
        openResultsEditor(player);
    }

    private void openConditionsEditor(Player player) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null) { openTypes(player); return; }
        Holder holder = new Holder(player.getUniqueId(), false, -1, true);
        Inventory inventory = create(holder, 27, "Station Craft Conditions");
        if (draft.kind.cooking() || draft.kind == StationKind.BREWING) inventory.setItem(4, icon(
                draft.kind.cooking() ? Material.FURNACE : Material.BREWING_STAND,
                "Automated station conditions",
                draft.kind.cooking()
                        ? "World, biome, time, and weather are checked at cook completion"
                        : "World, biome, time, and weather are checked at brew completion",
                "Permission, level, score, and advancement need a player",
                "Setting any player rule blocks this automated station"));
        inventory.setItem(10, icon(Material.NAME_TAG, "Permission: " + shown(draft.permission),
                "Enter a permission; type none to clear"));
        inventory.setItem(11, icon(Material.GRASS_BLOCK, "World: " + shown(draft.world),
                "Enter a world name; type none to clear"));
        inventory.setItem(12, icon(Material.OAK_SAPLING, "Biome: " + shown(draft.biome),
                "Enter a namespaced biome; type none to clear"));
        inventory.setItem(13, icon(Material.CLOCK,
                "Time: " + (draft.timeStart < 0 ? "any" : draft.timeStart + "-" + draft.timeEnd),
                "Enter start-end in Minecraft ticks; type none to clear"));
        inventory.setItem(14, icon(Material.WATER_BUCKET, "Weather: " + draft.weather,
                "Click to cycle any / clear / rain / thunder"));
        inventory.setItem(15, icon(Material.EXPERIENCE_BOTTLE,
                "Minimum level: " + draft.minimumLevel, "Enter 0 through 100000"));
        inventory.setItem(16, icon(Material.PAPER,
                "Score objective: " + shown(draft.scoreboardObjective),
                "Enter an objective name; type none to clear"));
        inventory.setItem(19, icon(Material.COMPARATOR,
                "Minimum score: " + draft.minimumScore,
                "Set a score objective first"));
        inventory.setItem(20, icon(Material.BOOK,
                "Advancement: " + shown(draft.advancement),
                "Enter a namespaced advancement; type none to clear"));
        inventory.setItem(22, icon(Material.BARRIER, "Clear all conditions"));
        inventory.setItem(26, icon(Material.ARROW, "Back to station editor"));
        player.openInventory(inventory);
    }

    private void editCondition(Player player, String title, String initial,
                               java.util.function.Consumer<String> accepted) {
        prompt.open(player, title, initial, value -> {
            if (!player.hasPermission("recipeweaver.studio")) return;
            try { accepted.accept(value); }
            catch (IllegalArgumentException ex) {
                player.sendMessage(ChatColor.RED + "Invalid condition: " + ex.getMessage());
            }
            openConditionsEditor(player);
        }, () -> openConditionsEditor(player));
    }

    private void clickConditions(Player player, int slot) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null) { openTypes(player); return; }
        switch (slot) {
            case 10 -> editCondition(player, "Permission", shown(draft.permission), value -> {
                String candidate = optional(value);
                if (candidate != null && (candidate.length() > 128
                        || !candidate.matches("[A-Za-z0-9._*:-]+"))) {
                    throw new IllegalArgumentException("Permission must be a valid node");
                }
                draft.permission = candidate;
            });
            case 11 -> editCondition(player, "World", shown(draft.world), value -> {
                String candidate = optional(value);
                if (candidate != null && (candidate.length() > 128
                        || candidate.chars().anyMatch(Character::isISOControl))) {
                    throw new IllegalArgumentException("World name is invalid");
                }
                draft.world = candidate;
            });
            case 12 -> editCondition(player, "Biome key", shown(draft.biome), value ->
                    draft.biome = conditionKey(value));
            case 13 -> editCondition(player, "Time range", draft.timeStart < 0
                    ? "none" : draft.timeStart + "-" + draft.timeEnd, value -> {
                if (optional(value) == null) {
                    draft.timeStart = -1;
                    draft.timeEnd = -1;
                    return;
                }
                String[] parts = value.split("-", -1);
                if (parts.length != 2) throw new IllegalArgumentException("Use start-end or none");
                int start = Integer.parseInt(parts[0]);
                int end = Integer.parseInt(parts[1]);
                if (start < 0 || start > 23999 || end < 0 || end > 23999) {
                    throw new IllegalArgumentException("Time bounds must be 0 through 23999");
                }
                draft.timeStart = start;
                draft.timeEnd = end;
            });
            case 14 -> {
                draft.weather = WeatherCondition.values()[
                        (draft.weather.ordinal() + 1) % WeatherCondition.values().length];
                openConditionsEditor(player);
            }
            case 15 -> editCondition(player, "Minimum level",
                    Integer.toString(draft.minimumLevel), value -> {
                int level = Integer.parseInt(value);
                if (level < 0 || level > 100000) throw new IllegalArgumentException("Use 0 through 100000");
                draft.minimumLevel = level;
            });
            case 16 -> editCondition(player, "Score objective",
                    shown(draft.scoreboardObjective), value -> {
                String candidate = optional(value);
                if (candidate != null && (candidate.length() > 128
                        || candidate.chars().anyMatch(Character::isISOControl))) {
                    throw new IllegalArgumentException("Score objective is invalid");
                }
                draft.scoreboardObjective = candidate;
                if (candidate == null) draft.minimumScore = 0;
            });
            case 19 -> editCondition(player, "Minimum score",
                    Integer.toString(draft.minimumScore), value -> {
                if (draft.scoreboardObjective == null) {
                    throw new IllegalArgumentException("Set a score objective first");
                }
                draft.minimumScore = Integer.parseInt(value);
            });
            case 20 -> editCondition(player, "Advancement key", shown(draft.advancement), value -> {
                NamespacedKey key = conditionKey(value);
                if (key != null && Bukkit.getAdvancement(key) == null) {
                    throw new IllegalArgumentException("Advancement does not exist on this server");
                }
                draft.advancement = key;
            });
            case 22 -> { draft.clearConditions(); openConditionsEditor(player); }
            case 26 -> openEditor(player);
            default -> { }
        }
    }

    private void openActionsEditor(Player player) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null) { openTypes(player); return; }
        Holder holder = new Holder(player.getUniqueId(), false, -1, false, true);
        Inventory inventory = create(holder, 27, "Station Craft Actions");
        inventory.setItem(10, icon(Material.NOTE_BLOCK, "Sound: " + shown(draft.soundKey),
                "Enter a namespaced sound key; custom sounds are allowed"));
        inventory.setItem(11, icon(Material.REDSTONE, "Sound volume: " + draft.soundVolume,
                "Enter a value above 0 through 4"));
        inventory.setItem(12, icon(Material.COMPARATOR, "Sound pitch: " + draft.soundPitch,
                "Enter 0.5 through 2"));
        inventory.setItem(13, icon(Material.CLOCK,
                draft.soundPerBatch ? "Sound per craft" : "Sound once per click",
                "Each accepted station result counts as one craft"));
        String summary = draft.commandText == null ? "none" : draft.commandText.length() > 36
                ? draft.commandText.substring(0, 36) + "..." : draft.commandText;
        inventory.setItem(14, icon(Material.WRITABLE_BOOK, "Command: " + summary,
                draft.kind.cooking()
                        ? "Edit one page; cooking runs without a player"
                        : "Edit one page in a temporary book; {player}, {uuid},",
                draft.kind.cooking()
                        ? "No {player}, {uuid}, or PlaceholderAPI %tokens%"
                        : "and optional PlaceholderAPI %tokens% are supported"));
        inventory.setItem(15, icon(draft.commandSender == CraftCommandSender.CONSOLE
                ? Material.COMMAND_BLOCK : Material.PLAYER_HEAD,
                draft.kind.cooking() ? "Run command as: CONSOLE only"
                        : "Run command as: " + draft.commandSender));
        inventory.setItem(16, icon(Material.CLOCK,
                draft.commandPerBatch ? "Command per craft" : "Command once per click",
                "Each accepted station result counts as one craft"));
        if (!draft.kind.cooking()) inventory.setItem(19, icon(Material.BOOK,
                "Grant advancement: " + shown(draft.grantedAdvancement),
                "Enter a namespaced advancement"));
        inventory.setItem(20, icon(Material.BARRIER, "Clear craft sound"));
        inventory.setItem(21, icon(Material.BARRIER, "Clear craft command"));
        if (!draft.kind.cooking()) inventory.setItem(22, icon(Material.BARRIER,
                "Clear craft advancement"));
        inventory.setItem(26, icon(Material.ARROW, "Back to station editor"));
        player.openInventory(inventory);
    }

    private void editAction(Player player, String title, String initial,
                            java.util.function.Consumer<String> accepted) {
        prompt.open(player, title, initial, value -> {
            if (!player.hasPermission("recipeweaver.studio")) return;
            try { accepted.accept(value); }
            catch (IllegalArgumentException ex) {
                player.sendMessage(ChatColor.RED + "Invalid action: " + ex.getMessage());
            }
            openActionsEditor(player);
        }, () -> openActionsEditor(player));
    }

    private void clickActions(Player player, int slot) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null) { openTypes(player); return; }
        switch (slot) {
            case 10 -> editAction(player, "Craft sound key", shown(draft.soundKey), value -> {
                NamespacedKey key = conditionKey(value);
                if (key == null) throw new IllegalArgumentException("Enter a sound key");
                draft.soundKey = key;
            });
            case 11 -> editAction(player, "Sound volume", Float.toString(draft.soundVolume), value -> {
                float volume = Float.parseFloat(value);
                new CraftSoundAction(NamespacedKey.minecraft("block.note_block.bell"),
                        volume, draft.soundPitch, draft.soundPerBatch);
                draft.soundVolume = volume;
            });
            case 12 -> editAction(player, "Sound pitch", Float.toString(draft.soundPitch), value -> {
                float pitch = Float.parseFloat(value);
                new CraftSoundAction(NamespacedKey.minecraft("block.note_block.bell"),
                        draft.soundVolume, pitch, draft.soundPerBatch);
                draft.soundPitch = pitch;
            });
            case 13 -> { draft.soundPerBatch = !draft.soundPerBatch; openActionsEditor(player); }
            case 14 -> bookPrompt.open(player, draft.commandText, value -> {
                if (!player.hasPermission("recipeweaver.studio")) return;
                try {
                    String command = value.strip();
                    if (command.startsWith("/")) command = command.substring(1);
                    if (draft.kind.cooking()) {
                        command = validateAutomatedCommand(command);
                        draft.commandSender = CraftCommandSender.CONSOLE;
                    }
                    draft.commandText = new CraftCommandAction(command,
                            draft.commandSender, draft.commandPerBatch).command();
                } catch (IllegalArgumentException ex) {
                    player.sendMessage(ChatColor.RED + "Invalid action: " + ex.getMessage());
                }
                openActionsEditor(player);
            }, () -> openActionsEditor(player));
            case 15 -> {
                if (draft.kind.cooking()) return;
                draft.commandSender = draft.commandSender == CraftCommandSender.CONSOLE
                        ? CraftCommandSender.PLAYER : CraftCommandSender.CONSOLE;
                openActionsEditor(player);
            }
            case 16 -> { draft.commandPerBatch = !draft.commandPerBatch; openActionsEditor(player); }
            case 19 -> {
                if (draft.kind.cooking()) return;
                editAction(player, "Advancement to grant",
                        shown(draft.grantedAdvancement), value -> {
                    NamespacedKey key = conditionKey(value);
                    if (key != null && Bukkit.getAdvancement(key) == null) {
                        throw new IllegalArgumentException("Advancement does not exist on this server");
                    }
                    draft.grantedAdvancement = key;
                });
            }
            case 20 -> { draft.soundKey = null; openActionsEditor(player); }
            case 21 -> { draft.commandText = null; openActionsEditor(player); }
            case 22 -> {
                if (draft.kind.cooking()) return;
                draft.grantedAdvancement = null;
                openActionsEditor(player);
            }
            case 26 -> openEditor(player);
            default -> { }
        }
    }

    /** Automated stations have no player context for command substitutions. */
    static String validateAutomatedCommand(String command) {
        String value = command == null ? "" : command.strip();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("{player}") || lower.contains("{uuid}")
                || value.matches("(?s).*%[^%]+%.*")) {
            throw new IllegalArgumentException("Cooking commands cannot use player placeholders");
        }
        return value;
    }

    private static String optional(String value) {
        return value.equalsIgnoreCase("none") ? null : value;
    }

    private static String shown(Object value) { return value == null ? "none" : value.toString(); }

    private static NamespacedKey conditionKey(String value) {
        String raw = optional(value);
        if (raw == null) return null;
        NamespacedKey key = raw.contains(":") ? NamespacedKey.fromString(raw.toLowerCase(Locale.ROOT)) : null;
        if (key == null || !raw.equalsIgnoreCase(key.toString())) {
            throw new IllegalArgumentException("Use a namespaced key such as minecraft:plains");
        }
        return key;
    }

    static String[] conditionLines(RecipeConditions conditions) {
        List<String> lines = new ArrayList<>();
        if (conditions.permission() != null) lines.add("Permission: " + conditions.permission());
        if (conditions.world() != null) lines.add("World: " + conditions.world());
        if (conditions.biome() != null) lines.add("Biome: " + conditions.biome());
        if (conditions.timeStart() >= 0) {
            lines.add("Time: " + conditions.timeStart() + "-" + conditions.timeEnd());
        }
        if (conditions.weather() != WeatherCondition.ANY) lines.add("Weather: " + conditions.weather());
        if (conditions.minimumLevel() > 0) lines.add("Level: " + conditions.minimumLevel());
        if (conditions.scoreboardObjective() != null) {
            lines.add("Score: " + conditions.scoreboardObjective() + " >= " + conditions.minimumScore());
        }
        if (conditions.advancement() != null) lines.add("Advancement: " + conditions.advancement());
        return lines.toArray(String[]::new);
    }

    private void openIngredientEditor(Player player, int index) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null || draft.ingredients[index] == null) { openEditor(player); return; }
        Holder holder = new Holder(player.getUniqueId(), false, index);
        Inventory inventory = create(holder, 54, "Station Ingredient Choices");
        List<ItemStack> choices = draft.alternatives.get(index);
        for (int i = 0; i < StationRecipeDefinition.MAX_ALTERNATIVES_PER_SLOT; i++) {
            inventory.setItem(i, i < choices.size() ? showAlternative(choices.get(i))
                    : icon(Material.GRAY_STAINED_GLASS_PANE, "Alternative " + (i + 1),
                            "Click with an item on your cursor to add"));
        }
        inventory.setItem(16, showIngredient(draft.ingredients[index], draft.matchModes[index],
                choices.size(), draft.itemTags[index]));
        inventory.setItem(17, icon(Material.PAPER,
                "Alternatives: " + choices.size() + "/" + StationRecipeDefinition.MAX_ALTERNATIVES_PER_SLOT,
                "Click with a cursor item to add or replace",
                "Click with an empty cursor to remove",
                "Alternatives use the sample's required amount"));
        if (draft.kind == StationKind.ANVIL || draft.kind == StationKind.GRINDSTONE
                || draft.kind == StationKind.CAULDRON || draft.kind == StationKind.BREWING) {
            if (draft.kind != StationKind.BREWING || index == 0) {
                inventory.setItem(24, icon(Material.CLOCK,
                        "Required amount: " + draft.ingredients[index].getAmount(),
                        "Click to enter the exact input amount"));
            }
            if (draft.kind != StationKind.CAULDRON && (draft.kind != StationKind.BREWING || index == 0)
                    || draft.kind == StationKind.CAULDRON && index == 1) {
                inventory.setItem(25, icon(draft.consumeSlots[index] ? Material.LIME_DYE : Material.BLUE_DYE,
                        "Consume ingredient: " + draft.consumeSlots[index],
                        "Click to keep this input as a catalyst"));
            }
            ItemStack returned = draft.remainders[index];
            inventory.setItem(34, returned == null
                    ? icon(Material.BUCKET, "Returned item: none",
                            "Click with an item on your cursor to set")
                    : showAlternative(returned));
            if (index > 0 && (draft.kind == StationKind.ANVIL
                    || draft.kind == StationKind.GRINDSTONE)) inventory.setItem(43, icon(
                    draft.optionalSlots[index] ? Material.LIME_DYE : Material.GRAY_DYE,
                    "Optional ingredient: " + draft.optionalSlots[index],
                    "A present input must match and follows consume/return rules"));
        }
        inventory.setItem(26, icon(Material.NAME_TAG,
                "Item tag: " + (draft.itemTags[index] == null ? "none" : draft.itemTags[index]),
                "Enter a namespaced Bukkit item tag",
                "A tag uses material matching"));
        inventory.setItem(35, icon(Material.BARRIER, "Clear item tag"));
        inventory.setItem(44, icon(Material.COMPARATOR,
                "Match mode: " + draft.matchModes[index],
                draft.kind == StationKind.ANVIL || draft.kind == StationKind.GRINDSTONE
                        ? "Click to switch exact / partial / material"
                        : "Click to switch exact / material"));
        inventory.setItem(53, icon(Material.ARROW, "Back to station editor"));
        player.openInventory(inventory);
    }

    private void clickIngredient(Player player, int index, int slot, ClickType click, ItemStack cursor) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null || draft.ingredients[index] == null) { openEditor(player); return; }
        if (slot == 53) { openEditor(player); return; }
        if (slot == 43 && index > 0 && (draft.kind == StationKind.ANVIL
                || draft.kind == StationKind.GRINDSTONE)) {
            draft.optionalSlots[index] = !draft.optionalSlots[index];
            openIngredientEditor(player, index);
            return;
        }
        if (slot == 24 && (draft.kind == StationKind.ANVIL
                || draft.kind == StationKind.GRINDSTONE
                || draft.kind == StationKind.CAULDRON
                || draft.kind == StationKind.BREWING && index == 0)) {
            prompt.open(player, "Required input amount",
                    Integer.toString(draft.ingredients[index].getAmount()), value -> {
                try {
                    int amount = Integer.parseInt(value.strip());
                    ItemStack sample = draft.ingredients[index].clone();
                    if (amount < 1 || amount > sample.getMaxStackSize()) {
                        throw new IllegalArgumentException("Input amount must fit one stack.");
                    }
                    sample.setAmount(amount);
                    draft.ingredients[index] = sample;
                } catch (NumberFormatException ex) {
                    player.sendMessage(ChatColor.RED + "Enter a whole number of items.");
                } catch (IllegalArgumentException ex) {
                    player.sendMessage(ChatColor.RED + ex.getMessage());
                }
                openIngredientEditor(player, index);
            }, () -> openIngredientEditor(player, index));
            return;
        }
        if (slot == 25 && (draft.kind == StationKind.ANVIL
                || draft.kind == StationKind.GRINDSTONE
                || draft.kind == StationKind.CAULDRON && index == 1
                || draft.kind == StationKind.BREWING && index == 0)) {
            draft.consumeSlots[index] = !draft.consumeSlots[index];
            if (!draft.consumeSlots[index]) draft.remainders[index] = null;
            openIngredientEditor(player, index);
            return;
        }
        if (slot == 34 && (draft.kind == StationKind.ANVIL
                || draft.kind == StationKind.GRINDSTONE
                || draft.kind == StationKind.CAULDRON
                || draft.kind == StationKind.BREWING)) {
            ItemStack returned = copy(cursor, false);
            if (returned != null && returned.getAmount() > returned.getMaxStackSize()) {
                player.sendMessage(ChatColor.RED + "A returned item must fit one stack.");
            } else {
                draft.remainders[index] = returned;
                if (returned != null) draft.consumeSlots[index] = true;
            }
            openIngredientEditor(player, index);
            return;
        }
        if (slot >= 0 && slot < StationRecipeDefinition.MAX_ALTERNATIVES_PER_SLOT) {
            if (click != ClickType.LEFT && click != ClickType.RIGHT) return;
            List<ItemStack> choices = draft.alternatives.get(index);
            ItemStack alternative = copy(cursor, true);
            if (alternative != null) {
                if (draft.kind == StationKind.BREWING && index == 1
                        && !StationRecipeDefinition.bottleMaterial(alternative.getType())) {
                    player.sendMessage(ChatColor.RED + "Brewing bottle alternatives must fit a bottle slot.");
                } else if (slot < choices.size()) choices.set(slot, alternative);
                else if (choices.size() < StationRecipeDefinition.MAX_ALTERNATIVES_PER_SLOT) {
                    choices.add(alternative);
                }
            } else if (slot < choices.size()) choices.remove(slot);
            openIngredientEditor(player, index);
            return;
        }
        if (slot == 26) {
            prompt.open(player, "Item tag", draft.itemTags[index] == null
                    ? "minecraft:planks" : draft.itemTags[index].toString(), value -> {
                if (!player.hasPermission("recipeweaver.studio")) return;
                String raw = value.toLowerCase(Locale.ROOT);
                NamespacedKey key = raw.contains(":") ? NamespacedKey.fromString(raw) : null;
                Tag<Material> tag = key == null ? null
                        : Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class);
                if (tag == null || tag.getValues().isEmpty()) {
                    player.sendMessage(ChatColor.RED + "Enter an existing namespaced item tag.");
                } else if (draft.kind == StationKind.BREWING && index == 1
                        && tag.getValues().stream().anyMatch(material ->
                                !StationRecipeDefinition.bottleMaterial(material))) {
                    player.sendMessage(ChatColor.RED + "That tag contains items outside the bottle slot.");
                } else {
                    draft.itemTags[index] = key;
                    draft.matchModes[index] = IngredientMatchMode.MATERIAL;
                }
                openIngredientEditor(player, index);
            }, () -> openIngredientEditor(player, index));
        } else if (slot == 35) {
            draft.itemTags[index] = null;
            openIngredientEditor(player, index);
        } else if (slot == 44) {
            cycleMatchMode(player, draft, index);
            openIngredientEditor(player, index);
        }
    }

    private static void cycleMatchMode(Player player, Draft draft, int index) {
        IngredientMatchMode current = draft.matchModes[index];
        IngredientMatchMode next = draft.kind == StationKind.ANVIL
                || draft.kind == StationKind.GRINDSTONE
                ? switch (current) {
                    case EXACT -> IngredientMatchMode.PARTIAL;
                    case PARTIAL -> IngredientMatchMode.CUSTOM_DATA_ONLY;
                    case CUSTOM_DATA_ONLY -> IngredientMatchMode.NAME_ONLY;
                    case NAME_ONLY -> IngredientMatchMode.LORE_ONLY;
                    case LORE_ONLY -> IngredientMatchMode.MODEL_ONLY;
                    case MODEL_ONLY -> IngredientMatchMode.ENCHANTMENTS_ONLY;
                    case ENCHANTMENTS_ONLY -> IngredientMatchMode.DAMAGE_ONLY;
                    case DAMAGE_ONLY -> IngredientMatchMode.ITEM_FLAGS_ONLY;
                    case ITEM_FLAGS_ONLY -> IngredientMatchMode.ATTRIBUTE_MODIFIERS_ONLY;
                    case ATTRIBUTE_MODIFIERS_ONLY -> IngredientMatchMode.UNBREAKABLE_ONLY;
                    case UNBREAKABLE_ONLY -> IngredientMatchMode.ARMOR_TRIM_ONLY;
                    case ARMOR_TRIM_ONLY -> IngredientMatchMode.POTION_ONLY;
                    case POTION_ONLY -> IngredientMatchMode.POTION_BASE_ONLY;
                    case POTION_BASE_ONLY -> IngredientMatchMode.POTION_COLOR_ONLY;
                    case POTION_COLOR_ONLY -> IngredientMatchMode.STORED_ENCHANTMENTS_ONLY;
                    case STORED_ENCHANTMENTS_ONLY -> IngredientMatchMode.BANNER_PATTERNS_ONLY;
                    case BANNER_PATTERNS_ONLY -> IngredientMatchMode.CROSSBOW_PROJECTILES_ONLY;
                    case CROSSBOW_PROJECTILES_ONLY -> IngredientMatchMode.FIREWORK_ONLY;
                    case FIREWORK_ONLY -> IngredientMatchMode.BUNDLE_CONTENTS_ONLY;
                    case BUNDLE_CONTENTS_ONLY -> IngredientMatchMode.FIRE_RESISTANT_ONLY;
                    case FIRE_RESISTANT_ONLY -> IngredientMatchMode.HIDE_TOOLTIP_ONLY;
                    case HIDE_TOOLTIP_ONLY -> IngredientMatchMode.MAX_STACK_SIZE_ONLY;
                    case MAX_STACK_SIZE_ONLY -> IngredientMatchMode.RARITY_ONLY;
                    case RARITY_ONLY -> IngredientMatchMode.REPAIR_COST_ONLY;
                    case REPAIR_COST_ONLY -> IngredientMatchMode.LEATHER_COLOR_ONLY;
                    case LEATHER_COLOR_ONLY -> IngredientMatchMode.SUSPICIOUS_STEW_ONLY;
                    case SUSPICIOUS_STEW_ONLY -> IngredientMatchMode.FOOD_ONLY;
                    case FOOD_ONLY -> IngredientMatchMode.TOOL_ONLY;
                    case TOOL_ONLY -> IngredientMatchMode.WRITTEN_BOOK_ONLY;
                    case WRITTEN_BOOK_ONLY -> IngredientMatchMode.WRITTEN_BOOK_TITLE_ONLY;
                    case WRITTEN_BOOK_TITLE_ONLY -> IngredientMatchMode.WRITTEN_BOOK_AUTHOR_ONLY;
                    case WRITTEN_BOOK_AUTHOR_ONLY -> IngredientMatchMode.WRITABLE_BOOK_ONLY;
                    case WRITABLE_BOOK_ONLY -> IngredientMatchMode.COMPASS_ONLY;
                    case COMPASS_ONLY -> IngredientMatchMode.MAP_COLOR_ONLY;
                    case MAP_COLOR_ONLY -> IngredientMatchMode.MAP_SCALING_ONLY;
                    case MAP_SCALING_ONLY -> IngredientMatchMode.MAP_LOCATION_NAME_ONLY;
                    case MAP_LOCATION_NAME_ONLY -> IngredientMatchMode.TROPICAL_FISH_ONLY;
                    case TROPICAL_FISH_ONLY -> IngredientMatchMode.KNOWLEDGE_BOOK_ONLY;
                    case KNOWLEDGE_BOOK_ONLY -> IngredientMatchMode.HEAD_SOUND_ONLY;
                    case HEAD_SOUND_ONLY -> IngredientMatchMode.MATERIAL;
                    case MATERIAL -> IngredientMatchMode.EXACT;
                }
                : current == IngredientMatchMode.EXACT
                        ? IngredientMatchMode.MATERIAL : IngredientMatchMode.EXACT;
        if (draft.itemTags[index] != null && next != IngredientMatchMode.MATERIAL) {
            player.sendMessage(ChatColor.YELLOW + "Clear the item tag before leaving material matching.");
        } else draft.matchModes[index] = next;
    }

    private static ItemStack showIngredient(ItemStack item, IngredientMatchMode mode,
                                            int alternatives, NamespacedKey tag) {
        ItemStack shown = item.clone();
        ItemMeta meta = shown.getItemMeta();
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add(ChatColor.AQUA + "Match: " + mode.name().toLowerCase(Locale.ROOT));
        if (alternatives > 0) lore.add(ChatColor.AQUA + "Alternatives: " + alternatives);
        if (tag != null) lore.add(ChatColor.AQUA + "Item tag: " + tag);
        meta.setLore(lore);
        shown.setItemMeta(meta);
        return shown;
    }

    private static ItemStack showAlternative(ItemStack item) {
        ItemStack shown = item.clone();
        ItemMeta meta = shown.getItemMeta();
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add(ChatColor.GRAY + "Click with a cursor item to replace");
        lore.add(ChatColor.GRAY + "Click with an empty cursor to remove");
        meta.setLore(lore);
        shown.setItemMeta(meta);
        return shown;
    }

    private void editNumber(Player player, Draft draft, String label, String initial, NumberSetter setter) {
        prompt.open(player, label, initial, value -> {
            try { setter.set(value); }
            catch (NumberFormatException ex) { player.sendMessage(ChatColor.RED + "Enter a valid number."); }
            catch (IllegalArgumentException ex) { player.sendMessage(ChatColor.RED + ex.getMessage()); }
            openEditor(player);
        }, () -> openEditor(player));
    }

    private void save(Player player, Draft draft, String rawId) {
        if (!player.hasPermission("recipeweaver.studio")) return;
        NamespacedKey key = NamespacedKey.fromString(rawId.toLowerCase(Locale.ROOT), plugin);
        if (key == null || !key.getNamespace().equals("recipeweaver")) {
            player.sendMessage(ChatColor.RED + "Use a RecipeWeaver ID with lowercase letters, digits, _, -, or /.");
            openEditor(player);
            return;
        }
        if (draft.existingKey != null && !draft.existingKey.equals(key)) {
            player.sendMessage(ChatColor.RED + "Rename is not supported; keep the original recipe ID.");
            openEditor(player);
            return;
        }
        if ((plugin.stationRecipe(key).isPresent() || plugin.recipe(key).isPresent())
                && !key.equals(draft.existingKey)) {
            player.sendMessage(ChatColor.RED + "That recipe ID already exists.");
            openEditor(player);
            return;
        }
        try {
            StationRecipeDefinition recipe = new StationRecipeDefinition(key, draft.kind,
                    Arrays.asList(draft.ingredients).subList(0, draft.kind.slots()), draft.result,
                    draft.group, draft.cookingTicks, draft.experience, draft.anvilLevels,
                    draft.enabled, draft.hidden,
                    Arrays.asList(draft.matchModes).subList(0, draft.kind.slots()),
                    draft.alternatives.subList(0, draft.kind.slots()),
                    Arrays.asList(draft.itemTags).subList(0, draft.kind.slots()),
                    draft.bookCategory, draft.maxCraftsPerPlayer, draft.cooldownSeconds,
                    draft.conditions(), draft.soundAction(), draft.advancementAction(),
                    draft.commandAction(), draft.resultCopySourceSlot,
                    draft.resultMetadataMode,
                    Arrays.asList(draft.consumeSlots).subList(0, draft.kind.slots()),
                    Arrays.asList(draft.remainders).subList(0, draft.kind.slots()),
                    Arrays.asList(draft.optionalSlots).subList(0, draft.kind.slots()),
                    draft.resultChoices);
            UUID playerId = player.getUniqueId();
            if (!pendingWrites.add(playerId)) {
                player.sendMessage(ChatColor.YELLOW + "Station recipe save is still in progress.");
                return;
            }
            try {
                plugin.saveStationRecipeAsync(recipe).whenComplete((ignored, failure) -> {
                    pendingWrites.remove(playerId);
                    if (!player.isOnline()) return;
                    if (failure == null) {
                        drafts.remove(playerId);
                        player.sendMessage(ChatColor.GREEN + "Saved station recipe " + key);
                        openTypes(player);
                    } else {
                        player.sendMessage(ChatColor.RED + "Station recipe save failed: " + failure.getMessage());
                        openEditor(player);
                    }
                });
            } catch (RuntimeException ex) {
                pendingWrites.remove(playerId);
                throw ex;
            }
        } catch (IllegalArgumentException | IllegalStateException ex) {
            player.sendMessage(ChatColor.RED + "Cannot save recipe: " + ex.getMessage());
            openEditor(player);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder)) return;
        if (event.getRawSlots().stream().anyMatch(slot -> slot < event.getView().getTopInventory().getSize())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { drafts.remove(event.getPlayer().getUniqueId()); }

    private Inventory create(Holder holder, int size, String title) {
        Inventory inventory = Bukkit.createInventory(holder, size, title);
        holder.inventory = inventory;
        return inventory;
    }

    private static ItemStack copy(ItemStack input, boolean one) {
        if (input == null || input.getType().isAir()) return null;
        ItemStack result = input.clone();
        if (one) result.setAmount(1);
        return result;
    }

    private static ItemStack icon(Material material, String title, String... description) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + title);
        List<String> lore = new ArrayList<>();
        for (String line : description) lore.add(ChatColor.GRAY + line);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static String inputName(StationKind kind, int index) {
        if (kind == StationKind.SMITHING_TRANSFORM || kind == StationKind.SMITHING_TRIM) {
            return new String[] {"Template", "Base", "Addition"}[index];
        }
        if (kind == StationKind.BREWING) return index == 0 ? "Brewing ingredient" : "Bottle input";
        if (kind == StationKind.CAULDRON) return index == 0 ? "First mix item" : "Second mix item";
        return "Ingredient " + (index + 1);
    }

    private static Material materialFor(StationKind kind) {
        return switch (kind) {
            case FURNACE -> Material.FURNACE;
            case BLAST_FURNACE -> Material.BLAST_FURNACE;
            case SMOKER -> Material.SMOKER;
            case CAMPFIRE -> Material.CAMPFIRE;
            case STONECUTTER -> Material.STONECUTTER;
            case SMITHING_TRANSFORM, SMITHING_TRIM -> Material.SMITHING_TABLE;
            case ANVIL -> Material.ANVIL;
            case GRINDSTONE -> Material.GRINDSTONE;
            case BREWING -> Material.BREWING_STAND;
            case CAULDRON -> Material.CAULDRON;
        };
    }

    private static boolean supportsConditions(StationKind kind) {
        return kind.cooking() || kind == StationKind.ANVIL
                || kind == StationKind.GRINDSTONE || kind == StationKind.STONECUTTER
                || kind == StationKind.SMITHING_TRANSFORM
                || kind == StationKind.SMITHING_TRIM || kind == StationKind.CAULDRON
                || kind == StationKind.BREWING;
    }

    private static boolean supportsWeightedResults(StationKind kind) {
        return kind == StationKind.ANVIL || kind == StationKind.GRINDSTONE
                || kind == StationKind.CAULDRON || kind == StationKind.BREWING;
    }

    private interface NumberSetter { void set(String input); }

    private static final class Holder implements InventoryHolder {
        private final UUID owner;
        private final boolean types;
        private final int ingredientSlot;
        private final boolean conditions;
        private final boolean actions;
        private final boolean results;
        private Inventory inventory;

        private Holder(UUID owner, boolean types) { this(owner, types, -1); }
        private Holder(UUID owner, boolean types, int ingredientSlot) {
            this(owner, types, ingredientSlot, false);
        }
        private Holder(UUID owner, boolean types, int ingredientSlot, boolean conditions) {
            this(owner, types, ingredientSlot, conditions, false);
        }
        private Holder(UUID owner, boolean types, int ingredientSlot,
                       boolean conditions, boolean actions) {
            this(owner, types, ingredientSlot, conditions, actions, false);
        }
        private Holder(UUID owner, boolean types, int ingredientSlot,
                       boolean conditions, boolean actions, boolean results) {
            this.owner = owner;
            this.types = types;
            this.ingredientSlot = ingredientSlot;
            this.conditions = conditions;
            this.actions = actions;
            this.results = results;
        }
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class Draft {
        private final StationKind kind;
        private NamespacedKey existingKey;
        private final ItemStack[] ingredients = new ItemStack[3];
        private final IngredientMatchMode[] matchModes = new IngredientMatchMode[3];
        private final List<List<ItemStack>> alternatives = new ArrayList<>(3);
        private final NamespacedKey[] itemTags = new NamespacedKey[3];
        private final Boolean[] consumeSlots = new Boolean[3];
        private final ItemStack[] remainders = new ItemStack[3];
        private final Boolean[] optionalSlots = new Boolean[3];
        private final List<WeightedResult> resultChoices = new ArrayList<>();
        private ItemStack result;
        private String group = "";
        private CookingBookCategory bookCategory = CookingBookCategory.MISC;
        private int cookingTicks = 200;
        private float experience;
        private int anvilLevels = 1;
        private boolean enabled = true;
        private boolean hidden;
        private int maxCraftsPerPlayer;
        private int cooldownSeconds;
        private String permission;
        private String world;
        private NamespacedKey biome;
        private int timeStart = -1;
        private int timeEnd = -1;
        private WeatherCondition weather = WeatherCondition.ANY;
        private int minimumLevel;
        private String scoreboardObjective;
        private int minimumScore;
        private NamespacedKey advancement;
        private NamespacedKey soundKey;
        private float soundVolume = 1.0F;
        private float soundPitch = 1.0F;
        private boolean soundPerBatch;
        private NamespacedKey grantedAdvancement;
        private String commandText;
        private CraftCommandSender commandSender = CraftCommandSender.CONSOLE;
        private boolean commandPerBatch;
        private int resultCopySourceSlot = -1;
        private ResultMetadataMode resultMetadataMode = ResultMetadataMode.NONE;

        private Draft(StationKind kind) {
            this.kind = kind;
            Arrays.fill(matchModes, IngredientMatchMode.EXACT);
            Arrays.fill(consumeSlots, true);
            Arrays.fill(optionalSlots, false);
            for (int i = 0; i < 3; i++) alternatives.add(new ArrayList<>());
        }

        private Draft(StationRecipeDefinition recipe) {
            this(recipe.kind());
            existingKey = recipe.key();
            List<ItemStack> saved = recipe.ingredients();
            List<List<ItemStack>> savedAlternatives = recipe.alternatives();
            for (int i = 0; i < saved.size(); i++) {
                ingredients[i] = saved.get(i);
                matchModes[i] = recipe.matchModes().get(i);
                alternatives.get(i).addAll(savedAlternatives.get(i));
                itemTags[i] = recipe.itemTags().get(i);
                consumeSlots[i] = recipe.consumes(i);
                remainders[i] = recipe.remainder(i);
                optionalSlots[i] = recipe.optional(i);
            }
            result = recipe.result();
            resultChoices.addAll(recipe.resultChoices());
            group = recipe.group();
            bookCategory = recipe.bookCategory();
            cookingTicks = recipe.cookingTicks();
            experience = recipe.experience();
            anvilLevels = recipe.anvilLevels();
            enabled = recipe.enabled();
            hidden = recipe.hidden();
            maxCraftsPerPlayer = recipe.maxCraftsPerPlayer();
            cooldownSeconds = recipe.cooldownSeconds();
            RecipeConditions conditions = recipe.conditions();
            permission = conditions.permission();
            world = conditions.world();
            biome = conditions.biome();
            timeStart = conditions.timeStart();
            timeEnd = conditions.timeEnd();
            weather = conditions.weather();
            minimumLevel = conditions.minimumLevel();
            scoreboardObjective = conditions.scoreboardObjective();
            minimumScore = conditions.minimumScore();
            advancement = conditions.advancement();
            CraftSoundAction sound = recipe.craftSound();
            if (sound != null) {
                soundKey = sound.sound();
                soundVolume = sound.volume();
                soundPitch = sound.pitch();
                soundPerBatch = sound.perBatch();
            }
            CraftAdvancementAction grant = recipe.craftAdvancement();
            if (grant != null) grantedAdvancement = grant.advancement();
            CraftCommandAction command = recipe.craftCommand();
            if (command != null) {
                commandText = command.command();
                commandSender = command.sender();
                commandPerBatch = command.perBatch();
            }
            resultCopySourceSlot = recipe.resultCopySourceSlot();
            resultMetadataMode = recipe.resultMetadataMode();
        }

        private CraftSoundAction soundAction() {
            return soundKey == null ? null : new CraftSoundAction(
                    soundKey, soundVolume, soundPitch, soundPerBatch);
        }

        private CraftAdvancementAction advancementAction() {
            return grantedAdvancement == null ? null : new CraftAdvancementAction(grantedAdvancement);
        }

        private CraftCommandAction commandAction() {
            return commandText == null ? null : new CraftCommandAction(
                    commandText, commandSender, commandPerBatch);
        }

        private int actionCount() {
            return (soundKey == null ? 0 : 1) + (commandText == null ? 0 : 1)
                    + (grantedAdvancement == null ? 0 : 1);
        }

        private RecipeConditions conditions() {
            return new RecipeConditions(permission, world, biome, timeStart, timeEnd,
                    weather, minimumLevel, scoreboardObjective, minimumScore, advancement);
        }

        private int conditionCount() {
            int count = 0;
            if (permission != null) count++;
            if (world != null) count++;
            if (biome != null) count++;
            if (timeStart >= 0) count++;
            if (weather != WeatherCondition.ANY) count++;
            if (minimumLevel > 0) count++;
            if (scoreboardObjective != null) count++;
            if (advancement != null) count++;
            return count;
        }

        private void clearConditions() {
            permission = null;
            world = null;
            biome = null;
            timeStart = -1;
            timeEnd = -1;
            weather = WeatherCondition.ANY;
            minimumLevel = 0;
            scoreboardObjective = null;
            minimumScore = 0;
            advancement = null;
        }
    }
}
