package dev.recipeweaver.core;

import java.util.logging.Level;
import dev.recipeweaver.api.CraftAdvancementAction;
import dev.recipeweaver.api.CraftCommandAction;
import dev.recipeweaver.api.CraftCommandSender;
import dev.recipeweaver.api.GridCraftBatch;
import dev.recipeweaver.api.GridCraftCompleteEvent;
import dev.recipeweaver.api.GridRecipeDefinition;
import dev.recipeweaver.api.CraftSoundAction;
import dev.recipeweaver.api.GridTier;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeKind;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.ResultMetadataMode;
import dev.recipeweaver.api.WeightedResult;
import dev.recipeweaver.api.WeatherCondition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemCraftResult;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** In-game grid recipe editor and the inventory transaction for placed tables. */
final class GridMenus implements Listener {
    private final RecipeWeaverPlugin plugin;
    private final TextPrompt prompt;
    private final BookPrompt bookPrompt;
    private final Map<UUID, Draft> drafts = new HashMap<>();
    private final Set<UUID> pendingWrites = new HashSet<>();
    private final Set<NamespacedKey> metadataWarnings = new HashSet<>();

    GridMenus(RecipeWeaverPlugin plugin) {
        this.plugin = plugin;
        prompt = new TextPrompt(plugin);
        Bukkit.getPluginManager().registerEvents(prompt, plugin);
        bookPrompt = plugin.bookPrompt();
    }

    void openHome(Player player) {
        bookPrompt.cancel(player);
        if (!player.hasPermission("recipeweaver.studio")) return;
        Holder holder = new Holder(player.getUniqueId(), Menu.HOME, 0, null, 0);
        Inventory inventory = create(holder, 27, "RecipeWeaver Tables");
        inventory.setItem(10, icon(Material.CRAFTING_TABLE, "New advanced recipe", "3x3 custom table"));
        inventory.setItem(12, icon(Material.NETHER_STAR, "New elite recipe", "Choose a 2x2 through 6x6 grid"));
        if (player.hasPermission("recipeweaver.admin")) {
            inventory.setItem(14, icon(Material.CRAFTING_TABLE, "Get advanced table", "Place to open the 3x3 table"));
            inventory.setItem(16, icon(Material.SMITHING_TABLE, "Get elite table", "Place to open the elite grid"));
        }
        inventory.setItem(22, icon(Material.BOOK, "Saved table recipes", "Browse and edit"));
        inventory.setItem(26, icon(Material.ARROW, "Back to studio"));
        player.openInventory(inventory);
    }

    void openExisting(Player player, GridRecipeDefinition recipe) {
        if (!player.hasPermission("recipeweaver.studio")) return;
        drafts.put(player.getUniqueId(), new Draft(recipe));
        openEditor(player);
    }

    void openGridDetail(Player player, GridRecipeDefinition recipe) {
        if (!player.hasPermission("recipeweaver.recipes")) return;
        Holder holder = new Holder(player.getUniqueId(), Menu.DETAIL, 0, recipe.tier(), recipe.size(), recipe.key());
        Inventory inventory = create(holder, 54, "Grid Recipe: " + recipe.key().getKey());
        List<ItemStack> ingredients = recipe.ingredients();
        List<List<ItemStack>> alternatives = recipe.alternatives();
        List<NamespacedKey> tags = recipe.itemTags();
        for (int row = 0; row < recipe.size(); row++) {
            for (int col = 0; col < recipe.size(); col++) {
                int index = row * recipe.size() + col;
                ItemStack item = ingredients.get(index);
                if (item != null) inventory.setItem(row * 9 + col,
                        showIngredient(item, recipe.matchModes().get(index),
                                alternatives.get(index).size(), tags.get(index),
                                recipe.optional(index), recipe.consumes(index), recipe.remainder(index)));
            }
        }
        inventory.setItem(7, recipe.result());
        if (recipe.resultChoices().size() > 1) {
            List<String> outcomes = new ArrayList<>();
            for (WeightedResult choice : recipe.resultChoices()) {
                ItemStack item = choice.item();
                outcomes.add(item.getType() + " x" + item.getAmount() + " | weight "
                        + choice.weight() + "/" + recipe.totalResultWeight());
            }
            inventory.setItem(8, icon(Material.GOLD_NUGGET,
                    "Possible results: " + recipe.resultChoices().size(),
                    outcomes.toArray(String[]::new)));
        }
        inventory.setItem(17, icon(Material.CRAFTING_TABLE,
                recipe.tier() + " " + recipe.size() + "x" + recipe.size(),
                recipe.kind() + " | Group: " + recipe.group(),
                 "Priority: " + recipe.priority() + " | Mirror: " + recipe.allowMirror(),
                 "Cooldown: " + recipe.cooldownSeconds() + " seconds per player",
                 "Craft limit: " + (recipe.maxCraftsPerPlayer() == 0
                         ? "unlimited" : recipe.maxCraftsPerPlayer() + " per player"),
                 "Result metadata: " + recipe.resultMetadataMode()
                        + (recipe.resultCopySourceSlot() < 0 ? ""
                                : " from slot " + (recipe.resultCopySourceSlot() + 1))));
        if (!recipe.conditions().unrestricted()) {
            inventory.setItem(26, icon(Material.COMPARATOR, "Craft conditions",
                    conditionLines(recipe.conditions())));
        }
        if (recipe.craftSound() != null) {
            inventory.setItem(35, icon(Material.NOTE_BLOCK, "Craft sound: "
                    + recipe.craftSound().sound(),
                    recipe.craftSound().perBatch() ? "Per craft batch" : "Once per click"));
        }
        if (recipe.craftAdvancement() != null) {
            inventory.setItem(44, icon(Material.BOOK, "Grants advancement: "
                    + recipe.craftAdvancement().advancement()));
        }
        if (recipe.craftCommand() != null) {
            inventory.setItem(6, icon(Material.COMMAND_BLOCK, "Runs a craft command",
                    "As " + recipe.craftCommand().sender().name().toLowerCase(Locale.ROOT),
                    recipe.craftCommand().perBatch() ? "Per craft batch" : "Once per click"));
        }
        inventory.setItem(52, icon(Material.ARROW, "Back to recipe book"));
        if (player.hasPermission("recipeweaver.studio")) {
            inventory.setItem(53, icon(Material.WRITABLE_BOOK, "Edit grid recipe"));
        }
        player.openInventory(inventory);
    }

    private void openEditor(Player player) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null) { openHome(player); return; }
        Holder holder = new Holder(player.getUniqueId(), Menu.EDITOR, 0, draft.tier, draft.size);
        Inventory inventory = create(holder, 54, "RecipeWeaver Grid Editor");
        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 6; col++) {
                int slot = row * 9 + col;
                ItemStack item = draft.ingredients[row * 6 + col];
                inventory.setItem(slot, row < draft.size && col < draft.size
                        ? (item == null ? null : showIngredient(item,
                                draft.matchModes[row * 6 + col],
                                draft.alternatives.get(row * 6 + col).size(),
                                draft.itemTags[row * 6 + col], draft.optionalSlots[row * 6 + col],
                                draft.consumeSlots[row * 6 + col],
                                draft.remainders[row * 6 + col]))
                        : icon(Material.GRAY_STAINED_GLASS_PANE, "Outside selected grid"));
            }
        }
        inventory.setItem(7, draft.resultChoices.isEmpty()
                ? icon(Material.BARRIER, "Result", "Click with an item on your cursor")
                : draft.resultChoices.get(0).item());
        inventory.setItem(6, icon(Material.COMMAND_BLOCK,
                "Craft command: " + (draft.commandText == null ? "none" : "configured"),
                "Click to edit command, sender, and cadence"));
        inventory.setItem(8, icon(Material.CRAFTING_TABLE, "Grid: " + draft.size + "x" + draft.size,
                draft.tier == GridTier.ELITE ? "Click to change size" : "Advanced tables use 3x3"));
        inventory.setItem(16, icon(Material.NAME_TAG, "Recipe ID",
                draft.existingKey == null ? "Assigned when saved" : draft.existingKey.toString()));
        inventory.setItem(15, icon(Material.GOLD_NUGGET,
                "Weighted results: " + draft.resultChoices.size(),
                "Click to edit result choices and weights",
                "Main result slot sets the first choice",
                "Empty click on result opens the choice editor"));
        inventory.setItem(17, icon(Material.PAPER, "Type: " + draft.kind, "Click to switch"));
        inventory.setItem(25, icon(Material.HOPPER, "Ingredient amounts",
                "Left click a grid slot with a stack to set it",
                "Right click with an empty cursor: exact / partial / material",
                "Shift-left click a sample: alternatives and item tag",
                "Stack amount is always the required count"));
        inventory.setItem(26, icon(Material.ITEM_FRAME,
                "Group: " + (draft.group.isBlank() ? "none" : draft.group),
                "Click to edit", "Enter none to clear"));
        inventory.setItem(34, icon(Material.COMPARATOR, "Priority: " + draft.priority,
                "Higher priority wins overlapping custom grid recipes", "Click to enter -1000 through 1000"));
        inventory.setItem(35, icon(draft.enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                "Enabled: " + draft.enabled, "Click to switch"));
        inventory.setItem(44, icon(draft.hidden ? Material.REDSTONE_TORCH : Material.TORCH,
                "Hidden: " + draft.hidden, "Click to switch"));
        inventory.setItem(43, icon(draft.allowMirror ? Material.MAP : Material.BARRIER,
                "Allow mirror: " + draft.allowMirror, "For shaped recipes; click to switch"));
        inventory.setItem(42, icon(Material.COMPARATOR,
                "Craft conditions: " + draft.conditionCount(),
                "Click to edit player and world requirements"));
        inventory.setItem(24, icon(Material.NOTE_BLOCK,
                "Craft sound: " + shown(draft.soundKey),
                "Click to edit sound, volume, pitch, and cadence"));
        inventory.setItem(33, icon(Material.BOOK,
                "Grant advancement: " + shown(draft.grantedAdvancement),
                "Click to enter a namespaced advancement key",
                "Type none to clear; unavailable keys are skipped"));
        inventory.setItem(49, icon(Material.HOPPER,
                "Shift craft limit: " + draft.maxShiftBatches + " batches",
                "Click to enter 1 through 64 for one shift-click"));
        inventory.setItem(48, icon(Material.WRITABLE_BOOK,
                "Result metadata: " + draft.resultMetadataMode,
                "Source: " + (draft.resultCopySourceSlot < 0
                        ? "none" : "row " + (draft.resultCopySourceSlot / 6 + 1)
                                + ", column " + (draft.resultCopySourceSlot % 6 + 1)),
                "Shift-right click an ingredient to choose its source",
                "Click to cycle name, lore, common fields, and all item data",
                "All item data needs the same source and result material"));
        inventory.setItem(50, icon(Material.CLOCK,
                 "Craft cooldown: " + draft.cooldownSeconds + " seconds",
                 "Per player and recipe after a committed click",
                 "Click to enter 0 (off) through 86400",
                 "Lifetime craft limit: " + (draft.maxCraftsPerPlayer == 0
                         ? "unlimited" : draft.maxCraftsPerPlayer),
                 "Shift-click to edit the per-player craft limit"));
        if (draft.existingKey != null) {
            inventory.setItem(51, icon(Material.BARRIER, "Delete recipe", "Requires typing DELETE"));
        }
        inventory.setItem(52, icon(Material.ARROW, "Back to tables"));
        inventory.setItem(53, icon(Material.EMERALD_BLOCK, "Save recipe", "Choose an ID"));
        player.openInventory(inventory);
    }

    private void openIngredientEditor(Player player, int index) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null || !GridMatcher.present(draft.ingredients[index])) {
            openEditor(player);
            return;
        }
        Holder holder = new Holder(player.getUniqueId(), Menu.INGREDIENT, 0,
                draft.tier, draft.size, null, index);
        Inventory inventory = create(holder, 54, "Grid Ingredient Choices");
        List<ItemStack> choices = draft.alternatives.get(index);
        for (int i = 0; i < GridRecipeDefinition.MAX_ALTERNATIVES_PER_SLOT; i++) {
            inventory.setItem(i, i < choices.size()
                    ? showAlternative(choices.get(i))
                    : icon(Material.GRAY_STAINED_GLASS_PANE, "Alternative " + (i + 1),
                            "Click with an item on your cursor to add"));
        }
        inventory.setItem(16, showIngredient(draft.ingredients[index], draft.matchModes[index],
                choices.size(), draft.itemTags[index], draft.optionalSlots[index],
                draft.consumeSlots[index], draft.remainders[index]));
        inventory.setItem(17, icon(Material.PAPER,
                "Alternatives: " + choices.size() + "/" + GridRecipeDefinition.MAX_ALTERNATIVES_PER_SLOT,
                "Click an alternative with a cursor item to replace it",
                "Click an alternative with an empty cursor to remove it",
                "The sample stack amount is required for every choice"));
        inventory.setItem(25, icon(draft.consumeSlots[index] ? Material.LIME_DYE : Material.BLUE_DYE,
                "Consume ingredient: " + draft.consumeSlots[index],
                "Click to switch consume / keep",
                "At least one recipe ingredient must be consumed"));
        inventory.setItem(26, icon(Material.NAME_TAG,
                "Item tag: " + (draft.itemTags[index] == null ? "none" : draft.itemTags[index]),
                "Click to enter a namespaced item tag",
                "Example: minecraft:planks"));
        inventory.setItem(35, icon(Material.BARRIER, "Clear item tag"));
        if (draft.remainders[index] == null) {
            inventory.setItem(34, icon(Material.BUCKET, "Remainder: none",
                    "Click with an item to return it after crafting"));
        } else {
            ItemStack shown = draft.remainders[index].clone();
            ItemMeta meta = shown.getItemMeta();
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add(ChatColor.AQUA + "Remainder after consuming this ingredient");
            lore.add(ChatColor.GRAY + "Click empty to clear; click with an item to replace");
            meta.setLore(lore);
            shown.setItemMeta(meta);
            inventory.setItem(34, shown);
        }
        inventory.setItem(43, icon(draft.optionalSlots[index] ? Material.LIME_DYE : Material.GRAY_DYE,
                "Optional ingredient: " + draft.optionalSlots[index],
                "Click to allow this slot to be empty",
                "A matching item is used when present",
                "At least one required input must be consumed"));
        inventory.setItem(44, icon(Material.COMPARATOR,
                "Match mode: " + draft.matchModes[index],
                "Click to cycle exact / partial / material",
                "Partial requires sample metadata; extra item metadata is allowed"));
        inventory.setItem(53, icon(Material.ARROW, "Back to grid editor"));
        player.openInventory(inventory);
    }

    private void openResultsEditor(Player player) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null) { openHome(player); return; }
        Holder holder = new Holder(player.getUniqueId(), Menu.RESULTS, 0, draft.tier, draft.size);
        Inventory inventory = create(holder, 54, "Grid Weighted Results");
        for (int i = 0; i < GridRecipeDefinition.MAX_RESULTS; i++) {
            if (i < draft.resultChoices.size()) {
                WeightedResult choice = draft.resultChoices.get(i);
                ItemStack shown = choice.item();
                ItemMeta meta = shown.getItemMeta();
                List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.add(ChatColor.AQUA + "Weight: " + choice.weight());
                lore.add(ChatColor.GRAY + "Left click with an item to replace");
                lore.add(ChatColor.GRAY + "Left click empty to remove");
                lore.add(ChatColor.GRAY + "Right click empty to edit weight");
                meta.setLore(lore);
                shown.setItemMeta(meta);
                inventory.setItem(i, shown);
            } else {
                inventory.setItem(i, icon(Material.GRAY_STAINED_GLASS_PANE,
                        "Result " + (i + 1), "Left click with an item to add"));
            }
        }
        inventory.setItem(17, icon(Material.PAPER, "Result choices",
                "Up to " + GridRecipeDefinition.MAX_RESULTS + " outcomes",
                "Weights 1 through " + WeightedResult.MAX_WEIGHT,
                "Each craft rolls once; preview stays fixed until the grid changes"));
        inventory.setItem(53, icon(Material.ARROW, "Back to grid editor"));
        player.openInventory(inventory);
    }

    private void openConditionsEditor(Player player) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null) { openHome(player); return; }
        Holder holder = new Holder(player.getUniqueId(), Menu.CONDITIONS, 0, draft.tier, draft.size);
        Inventory inventory = create(holder, 27, "Grid Craft Conditions");
        inventory.setItem(10, icon(Material.NAME_TAG, "Permission: " + shown(draft.permission),
                "Click to enter a permission; type none to clear"));
        inventory.setItem(11, icon(Material.GRASS_BLOCK, "World: " + shown(draft.world),
                "Click to enter a world name; type none to clear"));
        inventory.setItem(12, icon(Material.OAK_SAPLING, "Biome: " + shown(draft.biome),
                "Click to enter a namespaced biome; type none to clear"));
        inventory.setItem(13, icon(Material.CLOCK,
                "Time: " + (draft.timeStart < 0 ? "any" : draft.timeStart + "-" + draft.timeEnd),
                "Enter start-end in Minecraft ticks, 0 through 23999",
                "Ranges may cross midnight; type none to clear"));
        inventory.setItem(14, icon(Material.WATER_BUCKET, "Weather: " + draft.weather,
                "Click to cycle any / clear / rain / thunder"));
        inventory.setItem(15, icon(Material.EXPERIENCE_BOTTLE,
                "Minimum level: " + draft.minimumLevel, "Click to enter 0 through 100000"));
        inventory.setItem(16, icon(Material.PAPER,
                "Score objective: " + shown(draft.scoreboardObjective),
                "Click to enter objective name; type none to clear"));
        inventory.setItem(19, icon(Material.COMPARATOR,
                "Minimum score: " + draft.minimumScore,
                "Requires a score objective; click to edit"));
        inventory.setItem(20, icon(Material.BOOK,
                "Advancement: " + shown(draft.advancement),
                "Click to enter a namespaced advancement; type none to clear"));
        inventory.setItem(22, icon(Material.BARRIER, "Clear all conditions"));
        inventory.setItem(26, icon(Material.ARROW, "Back to grid editor"));
        player.openInventory(inventory);
    }

    private void openSoundEditor(Player player) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null) { openHome(player); return; }
        Holder holder = new Holder(player.getUniqueId(), Menu.SOUND, 0, draft.tier, draft.size);
        Inventory inventory = create(holder, 27, "Grid Craft Sound");
        inventory.setItem(10, icon(Material.NOTE_BLOCK, "Sound: " + shown(draft.soundKey),
                "Enter a namespaced sound key; custom sounds are allowed"));
        inventory.setItem(12, icon(Material.REDSTONE, "Volume: " + draft.soundVolume,
                "Click to enter a value above 0 through 4"));
        inventory.setItem(14, icon(Material.COMPARATOR, "Pitch: " + draft.soundPitch,
                "Click to enter 0.5 through 2"));
        inventory.setItem(16, icon(Material.CLOCK,
                draft.soundPerBatch ? "Play per craft batch" : "Play once per click",
                "Click to switch; shift crafting can make multiple batches"));
        inventory.setItem(22, icon(Material.BARRIER, "Clear craft sound"));
        inventory.setItem(26, icon(Material.ARROW, "Back to grid editor"));
        player.openInventory(inventory);
    }

    private void openCommandEditor(Player player) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null) { openHome(player); return; }
        Holder holder = new Holder(player.getUniqueId(), Menu.COMMAND, 0, draft.tier, draft.size);
        Inventory inventory = create(holder, 27, "Grid Craft Command");
        String summary = draft.commandText == null ? "none" : draft.commandText.length() > 36
                ? draft.commandText.substring(0, 36) + "..." : draft.commandText;
        inventory.setItem(10, icon(Material.WRITABLE_BOOK, "Command: " + summary,
                "Click to edit one page in a temporary book",
                "{player}, {uuid}, and optional PlaceholderAPI %tokens%",
                "are replaced after crafting"));
        inventory.setItem(12, icon(draft.commandSender == CraftCommandSender.CONSOLE
                ? Material.COMMAND_BLOCK : Material.PLAYER_HEAD,
                "Run as: " + draft.commandSender, "Click to switch console / player"));
        inventory.setItem(14, icon(Material.CLOCK,
                draft.commandPerBatch ? "Run per craft batch" : "Run once per click",
                "Click to switch; shift crafting may commit multiple batches"));
        inventory.setItem(22, icon(Material.BARRIER, "Clear craft command"));
        inventory.setItem(26, icon(Material.ARROW, "Back to grid editor"));
        player.openInventory(inventory);
    }

    private void clickCommand(Player player, int slot) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null) { openHome(player); return; }
        switch (slot) {
            case 10 -> bookPrompt.open(player, draft.commandText, value -> {
                if (!player.hasPermission("recipeweaver.studio")) return;
                try {
                    String text = value.strip();
                    if (text.startsWith("/")) text = text.substring(1);
                    draft.commandText = new CraftCommandAction(text,
                            draft.commandSender, draft.commandPerBatch).command();
                } catch (IllegalArgumentException ex) {
                    player.sendMessage(ChatColor.RED + ex.getMessage());
                }
                openCommandEditor(player);
            }, () -> openCommandEditor(player));
            case 12 -> {
                draft.commandSender = draft.commandSender == CraftCommandSender.CONSOLE
                        ? CraftCommandSender.PLAYER : CraftCommandSender.CONSOLE;
                openCommandEditor(player);
            }
            case 14 -> { draft.commandPerBatch = !draft.commandPerBatch; openCommandEditor(player); }
            case 22 -> { draft.commandText = null; openCommandEditor(player); }
            case 26 -> openEditor(player);
            default -> { }
        }
    }

    private void clickSound(Player player, int slot) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null) { openHome(player); return; }
        switch (slot) {
            case 10 -> prompt.open(player, "Craft sound key", shown(draft.soundKey), value -> {
                if (!player.hasPermission("recipeweaver.studio")) return;
                try {
                    NamespacedKey key = conditionKey(value);
                    if (key == null) throw new IllegalArgumentException("Enter a sound key");
                    draft.soundKey = key;
                } catch (IllegalArgumentException ex) {
                    player.sendMessage(ChatColor.RED + ex.getMessage());
                }
                openSoundEditor(player);
            }, () -> openSoundEditor(player));
            case 12 -> prompt.open(player, "Sound volume", Float.toString(draft.soundVolume), value -> {
                if (!player.hasPermission("recipeweaver.studio")) return;
                try {
                    float volume = Float.parseFloat(value);
                    new CraftSoundAction(NamespacedKey.minecraft("block.note_block.bell"),
                            volume, draft.soundPitch, draft.soundPerBatch);
                    draft.soundVolume = volume;
                } catch (IllegalArgumentException ex) {
                    player.sendMessage(ChatColor.RED + ex.getMessage());
                }
                openSoundEditor(player);
            }, () -> openSoundEditor(player));
            case 14 -> prompt.open(player, "Sound pitch", Float.toString(draft.soundPitch), value -> {
                if (!player.hasPermission("recipeweaver.studio")) return;
                try {
                    float pitch = Float.parseFloat(value);
                    new CraftSoundAction(NamespacedKey.minecraft("block.note_block.bell"),
                            draft.soundVolume, pitch, draft.soundPerBatch);
                    draft.soundPitch = pitch;
                } catch (IllegalArgumentException ex) {
                    player.sendMessage(ChatColor.RED + ex.getMessage());
                }
                openSoundEditor(player);
            }, () -> openSoundEditor(player));
            case 16 -> { draft.soundPerBatch = !draft.soundPerBatch; openSoundEditor(player); }
            case 22 -> { draft.soundKey = null; openSoundEditor(player); }
            case 26 -> openEditor(player);
            default -> { }
        }
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
        if (draft == null) { openHome(player); return; }
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

    private static String optional(String value) {
        return value.equalsIgnoreCase("none") ? null : value;
    }

    private static NamespacedKey conditionKey(String value) {
        String raw = optional(value);
        if (raw == null) return null;
        NamespacedKey key = raw.contains(":") ? NamespacedKey.fromString(raw.toLowerCase(Locale.ROOT)) : null;
        if (key == null || !raw.equalsIgnoreCase(key.toString())) {
            throw new IllegalArgumentException("Use a namespaced key such as minecraft:plains");
        }
        return key;
    }

    private static String shown(Object value) { return value == null ? "none" : value.toString(); }

    private static String[] conditionLines(RecipeConditions conditions) {
        List<String> lines = new ArrayList<>();
        if (conditions.permission() != null) lines.add("Permission: " + conditions.permission());
        if (conditions.world() != null) lines.add("World: " + conditions.world());
        if (conditions.biome() != null) lines.add("Biome: " + conditions.biome());
        if (conditions.timeStart() >= 0) lines.add("Time: " + conditions.timeStart()
                + "-" + conditions.timeEnd());
        if (conditions.weather() != WeatherCondition.ANY) lines.add("Weather: " + conditions.weather());
        if (conditions.minimumLevel() > 0) lines.add("Level: " + conditions.minimumLevel());
        if (conditions.scoreboardObjective() != null) lines.add("Score: "
                + conditions.scoreboardObjective() + " >= " + conditions.minimumScore());
        if (conditions.advancement() != null) lines.add("Advancement: " + conditions.advancement());
        return lines.toArray(String[]::new);
    }

    private void clickResults(Player player, int slot, ClickType click, ItemStack cursor) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null) { openHome(player); return; }
        if (slot == 53) { openEditor(player); return; }
        if (slot < 0 || slot >= GridRecipeDefinition.MAX_RESULTS) return;
        List<WeightedResult> choices = draft.resultChoices;
        if (click == ClickType.RIGHT && !GridMatcher.present(cursor) && slot < choices.size()) {
            WeightedResult old = choices.get(slot);
            prompt.open(player, "Result weight", Integer.toString(old.weight()), value -> {
                if (!player.hasPermission("recipeweaver.studio")) return;
                try {
                    choices.set(slot, new WeightedResult(old.item(), Integer.parseInt(value)));
                } catch (IllegalArgumentException ex) {
                    player.sendMessage(ChatColor.RED + "Weight must be 1 through " + WeightedResult.MAX_WEIGHT + ".");
                }
                openResultsEditor(player);
            }, () -> openResultsEditor(player));
            return;
        }
        if (click != ClickType.LEFT) return;
        if (GridMatcher.present(cursor)) {
            if (slot > choices.size()) return;
            int weight = slot < choices.size() ? choices.get(slot).weight() : 1;
            try {
                WeightedResult choice = new WeightedResult(cursor, weight);
                if (slot < choices.size()) choices.set(slot, choice);
                else choices.add(choice);
            } catch (IllegalArgumentException ex) {
                player.sendMessage(ChatColor.RED + ex.getMessage());
            }
        } else if (slot < choices.size()) {
            choices.remove(slot);
        }
        openResultsEditor(player);
    }

    private void clickIngredient(Player player, int index, int slot,
                                 ClickType click, ItemStack cursor) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null || !GridMatcher.present(draft.ingredients[index])) {
            openEditor(player);
            return;
        }
        if (slot == 53) { openEditor(player); return; }
        if (slot >= 0 && slot < GridRecipeDefinition.MAX_ALTERNATIVES_PER_SLOT) {
            if (click != ClickType.LEFT && click != ClickType.RIGHT) return;
            List<ItemStack> choices = draft.alternatives.get(index);
            if (GridMatcher.present(cursor)) {
                ItemStack alternative = cursor.clone();
                alternative.setAmount(1);
                if (slot < choices.size()) choices.set(slot, alternative);
                else if (choices.size() < GridRecipeDefinition.MAX_ALTERNATIVES_PER_SLOT) {
                    choices.add(alternative);
                }
            } else if (slot < choices.size()) choices.remove(slot);
            openIngredientEditor(player, index);
            return;
        }
        if (slot == 25) {
            draft.consumeSlots[index] = !draft.consumeSlots[index];
            if (!draft.consumeSlots[index]) draft.remainders[index] = null;
            openIngredientEditor(player, index);
        } else if (slot == 34) {
            ItemStack returned = copy(cursor);
            if (returned != null && returned.getAmount() > returned.getMaxStackSize()) {
                player.sendMessage(ChatColor.RED + "A remainder must fit one item stack.");
            } else {
                draft.remainders[index] = returned;
                if (returned != null) draft.consumeSlots[index] = true;
            }
            openIngredientEditor(player, index);
        } else if (slot == 26) {
            prompt.open(player, "Item tag", draft.itemTags[index] == null
                    ? "minecraft:planks" : draft.itemTags[index].toString(), value -> {
                if (!player.hasPermission("recipeweaver.studio")) return;
                String raw = value.toLowerCase(Locale.ROOT);
                NamespacedKey key = raw.contains(":") ? NamespacedKey.fromString(raw) : null;
                Tag<Material> tag = key == null ? null
                        : Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class);
                if (tag == null || tag.getValues().isEmpty()) {
                    player.sendMessage(ChatColor.RED + "Enter an existing namespaced item tag.");
                } else {
                    draft.itemTags[index] = key;
                }
                openIngredientEditor(player, index);
            }, () -> openIngredientEditor(player, index));
        } else if (slot == 35) {
            draft.itemTags[index] = null;
            openIngredientEditor(player, index);
        } else if (slot == 43) {
            draft.optionalSlots[index] = !draft.optionalSlots[index];
            openIngredientEditor(player, index);
        } else if (slot == 44) {
            draft.matchModes[index] = nextMatchMode(draft.matchModes[index]);
            openIngredientEditor(player, index);
        }
    }

    private void openList(Player player, int page) {
        List<GridRecipeDefinition> recipes = sortedRecipes();
        int maxPage = Math.max(0, (recipes.size() - 1) / 45);
        page = Math.max(0, Math.min(page, maxPage));
        Holder holder = new Holder(player.getUniqueId(), Menu.LIST, page, null, 0);
        Inventory inventory = create(holder, 54, "RecipeWeaver Grid Recipes " + (page + 1));
        List<NamespacedKey> displayedKeys = new ArrayList<>(45);
        for (int slot = 0; slot < 45 && page * 45 + slot < recipes.size(); slot++) {
            GridRecipeDefinition recipe = recipes.get(page * 45 + slot);
            displayedKeys.add(recipe.key());
            ItemStack item = recipe.result();
            ItemMeta meta = item.getItemMeta();
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add(ChatColor.GRAY + recipe.key().toString());
            lore.add(ChatColor.GRAY + recipe.tier().name() + " " + recipe.size() + "x" + recipe.size());
            lore.add(ChatColor.YELLOW + "Click to edit");
            meta.setLore(lore);
            item.setItemMeta(meta);
            inventory.setItem(slot, item);
        }
        holder.displayedKeys = List.copyOf(displayedKeys);
        if (page > 0) inventory.setItem(45, icon(Material.ARROW, "Previous page"));
        inventory.setItem(49, icon(Material.ARROW, "Back to tables"));
        if (page < maxPage) inventory.setItem(53, icon(Material.ARROW, "Next page"));
        player.openInventory(inventory);
    }

    void openCraft(Player player, GridTier tier, int size) {
        openCraft(player, tier, size, null);
    }

    private void openCraft(Player player, GridTier tier, int size, Holder previous) {
        if ((tier == GridTier.ADVANCED && size != 3)
                || (tier == GridTier.ELITE && (size < 2 || size > 6))) {
            throw new IllegalArgumentException("Invalid table grid");
        }
        Holder holder = new Holder(player.getUniqueId(), Menu.CRAFT, 0, tier, size);
        Inventory inventory = create(holder, 54, "RecipeWeaver " + tier + " " + size + "x" + size);
        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 6; col++) {
                if (row >= size || col >= size) {
                    inventory.setItem(row * 9 + col,
                            icon(Material.GRAY_STAINED_GLASS_PANE, "Outside selected grid"));
                }
            }
        }
        if (tier == GridTier.ELITE) {
            for (int selected = 2; selected <= 6; selected++) {
                inventory.setItem((selected - 2) * 9 + 8,
                        icon(selected == size ? Material.LIME_DYE : Material.CRAFTING_TABLE,
                                selected + "x" + selected + " grid", "Click to select"));
            }
        } else {
            inventory.setItem(17, icon(Material.CRAFTING_TABLE, "Advanced 3x3 table",
                    "Accepts native 3x3 recipes too"));
        }
        inventory.setItem(53, icon(Material.BARRIER, "Close"));
        if (previous != null) {
            for (int row = 0; row < previous.size; row++) {
                for (int col = 0; col < previous.size; col++) {
                    int slot = row * 9 + col;
                    ItemStack item = previous.inventory.getItem(slot);
                    if (!GridMatcher.present(item)) continue;
                    previous.inventory.setItem(slot, null);
                    if (row < size && col < size) inventory.setItem(slot, item);
                    else returnItem(player, item);
                }
            }
        }
        player.openInventory(inventory);
        refresh(player, holder);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        int slot = event.getRawSlot();
        int topSize = holder.inventory.getSize();
        if (!holder.owner.equals(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (slot >= topSize) {
            if (event.isShiftClick() || event.getClick() == ClickType.DOUBLE_CLICK) {
                event.setCancelled(true);
            }
            return;
        }
        if (slot < 0) { event.setCancelled(true); return; }
        if (holder.menu == Menu.CRAFT) {
            if (inputSlot(slot, holder.size)) {
                if (event.getClick() == ClickType.DOUBLE_CLICK) event.setCancelled(true);
                else scheduleRefresh(player, holder);
                return;
            }
            event.setCancelled(true);
            if (slot == 7) craft(player, holder, event.getClick());
            else if (slot == 53) player.closeInventory();
            else if (holder.tier == GridTier.ELITE && slot % 9 == 8 && slot / 9 < 5) {
                openCraft(player, holder.tier, slot / 9 + 2, holder);
            }
            return;
        }
        event.setCancelled(true);
        if (holder.menu == Menu.DETAIL) {
            if (!player.hasPermission("recipeweaver.recipes")) return;
            if (slot == 52) plugin.menus().openRecipeBook(player, 0);
            else if (slot == 53 && player.hasPermission("recipeweaver.studio")) {
                plugin.gridRecipe(holder.recipe).ifPresent(recipe -> openExisting(player, recipe));
            }
            return;
        }
        if (!player.hasPermission("recipeweaver.studio")) return;
        switch (holder.menu) {
            case HOME -> clickHome(player, slot);
            case EDITOR -> clickEditor(player, slot, event.getClick(), event.getCursor());
            case INGREDIENT -> clickIngredient(player, holder.ingredientSlot, slot,
                    event.getClick(), event.getCursor());
            case RESULTS -> clickResults(player, slot, event.getClick(), event.getCursor());
            case CONDITIONS -> clickConditions(player, slot);
            case SOUND -> clickSound(player, slot);
            case COMMAND -> clickCommand(player, slot);
            case LIST -> clickList(player, holder, slot);
            case DETAIL, CRAFT -> { }
        }
    }

    private void clickHome(Player player, int slot) {
        switch (slot) {
            case 10 -> { drafts.put(player.getUniqueId(), new Draft(GridTier.ADVANCED)); openEditor(player); }
            case 12 -> { drafts.put(player.getUniqueId(), new Draft(GridTier.ELITE)); openEditor(player); }
            case 14, 16 -> {
                if (!player.hasPermission("recipeweaver.admin")) return;
                GridTier tier = slot == 14 ? GridTier.ADVANCED : GridTier.ELITE;
                returnItem(player, plugin.tableListener().item(tier));
                player.sendMessage(ChatColor.GREEN + "Received a " + tier + " crafting table.");
            }
            case 22 -> openList(player, 0);
            case 26 -> plugin.menus().openHome(player);
            default -> { }
        }
    }

    private void clickEditor(Player player, int slot, ClickType click, ItemStack cursor) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null) { openHome(player); return; }
        if (inputSlot(slot, draft.size)) {
            int index = (slot / 9) * 6 + slot % 9;
            if (click == ClickType.SHIFT_LEFT && GridMatcher.present(draft.ingredients[index])) {
                openIngredientEditor(player, index);
                return;
            }
            if (click == ClickType.SHIFT_RIGHT && GridMatcher.present(draft.ingredients[index])) {
                draft.resultCopySourceSlot = index;
                if (draft.resultMetadataMode == ResultMetadataMode.NONE) {
                    draft.resultMetadataMode = ResultMetadataMode.NAME_AND_LORE;
                }
                openEditor(player);
                return;
            }
            if (click == ClickType.RIGHT && !GridMatcher.present(cursor)
                    && GridMatcher.present(draft.ingredients[index])) {
                draft.matchModes[index] = nextMatchMode(draft.matchModes[index]);
            } else if (click == ClickType.LEFT || click == ClickType.RIGHT) {
                ItemStack sample = copy(cursor);
                if (sample == null || draft.ingredients[index] == null
                        || !draft.ingredients[index].isSimilar(sample)) {
                    draft.matchModes[index] = IngredientMatchMode.EXACT;
                    draft.alternatives.get(index).clear();
                    draft.itemTags[index] = null;
                    draft.consumeSlots[index] = true;
                    draft.optionalSlots[index] = false;
                    draft.remainders[index] = null;
                }
                draft.ingredients[index] = sample;
                if (sample == null && draft.resultCopySourceSlot == index) {
                    draft.resultCopySourceSlot = -1;
                    draft.resultMetadataMode = ResultMetadataMode.NONE;
                }
            }
            openEditor(player);
            return;
        }
        switch (slot) {
            case 7 -> {
                if (GridMatcher.present(cursor)) {
                    try {
                        int weight = draft.resultChoices.isEmpty() ? 1 : draft.resultChoices.get(0).weight();
                        WeightedResult result = new WeightedResult(cursor, weight);
                        if (draft.resultChoices.isEmpty()) draft.resultChoices.add(result);
                        else draft.resultChoices.set(0, result);
                    } catch (IllegalArgumentException ex) {
                        player.sendMessage(ChatColor.RED + ex.getMessage());
                    }
                    openEditor(player);
                } else {
                    openResultsEditor(player);
                }
            }
            case 15 -> openResultsEditor(player);
            case 8 -> {
                if (draft.tier == GridTier.ELITE) draft.size = draft.size == 6 ? 2 : draft.size + 1;
                if (draft.resultCopySourceSlot >= 0
                        && (draft.resultCopySourceSlot / 6 >= draft.size
                        || draft.resultCopySourceSlot % 6 >= draft.size)) {
                    draft.resultCopySourceSlot = -1;
                    draft.resultMetadataMode = ResultMetadataMode.NONE;
                }
                openEditor(player);
            }
            case 17 -> {
                draft.kind = draft.kind == RecipeKind.SHAPED ? RecipeKind.SHAPELESS : RecipeKind.SHAPED;
                openEditor(player);
            }
            case 26 -> prompt.open(player, "Recipe group", draft.group, value -> {
                if (!player.hasPermission("recipeweaver.studio")) return;
                draft.group = StudioMenus.groupInput(value);
                openEditor(player);
            }, () -> openEditor(player));
            case 34 -> prompt.open(player, "Grid priority", Integer.toString(draft.priority), value -> {
                if (!player.hasPermission("recipeweaver.studio")) return;
                try {
                    int priority = Integer.parseInt(value);
                    if (priority < -1000 || priority > 1000) throw new NumberFormatException();
                    draft.priority = priority;
                } catch (NumberFormatException ex) {
                    player.sendMessage(ChatColor.RED + "Priority must be -1000 through 1000.");
                }
                openEditor(player);
            }, () -> openEditor(player));
            case 35 -> { draft.enabled = !draft.enabled; openEditor(player); }
            case 42 -> openConditionsEditor(player);
            case 6 -> openCommandEditor(player);
            case 24 -> openSoundEditor(player);
            case 33 -> prompt.open(player, "Advancement to grant",
                    shown(draft.grantedAdvancement), value -> {
                if (!player.hasPermission("recipeweaver.studio")) return;
                try { draft.grantedAdvancement = conditionKey(value); }
                catch (IllegalArgumentException ex) {
                    player.sendMessage(ChatColor.RED + ex.getMessage());
                }
                openEditor(player);
            }, () -> openEditor(player));
            case 43 -> { draft.allowMirror = !draft.allowMirror; openEditor(player); }
            case 44 -> { draft.hidden = !draft.hidden; openEditor(player); }
            case 48 -> {
                ResultMetadataMode[] modes = ResultMetadataMode.values();
                draft.resultMetadataMode = modes[(draft.resultMetadataMode.ordinal() + 1) % modes.length];
                openEditor(player);
            }
            case 49 -> prompt.open(player, "Shift craft batch limit",
                    Integer.toString(draft.maxShiftBatches), value -> {
                if (!player.hasPermission("recipeweaver.studio")) return;
                try {
                    int limit = Integer.parseInt(value);
                    if (limit < 1 || limit > 64) throw new NumberFormatException();
                    draft.maxShiftBatches = limit;
                } catch (NumberFormatException ex) {
                    player.sendMessage(ChatColor.RED + "Shift craft limit must be 1 through 64.");
                }
                openEditor(player);
            }, () -> openEditor(player));
            case 50 -> {
                if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
                    prompt.open(player, "Per-player lifetime craft limit",
                            Integer.toString(draft.maxCraftsPerPlayer), value -> {
                        if (!player.hasPermission("recipeweaver.studio")) return;
                        try {
                            int limit = Integer.parseInt(value);
                            if (limit < 0 || limit > 1_000_000) throw new NumberFormatException();
                            draft.maxCraftsPerPlayer = limit;
                        } catch (NumberFormatException ex) {
                            player.sendMessage(ChatColor.RED + "Craft limit must be 0 through 1000000.");
                        }
                        openEditor(player);
                    }, () -> openEditor(player));
                } else {
                    prompt.open(player, "Craft cooldown seconds",
                            Integer.toString(draft.cooldownSeconds), value -> {
                        if (!player.hasPermission("recipeweaver.studio")) return;
                        try {
                            int seconds = Integer.parseInt(value);
                            if (seconds < 0 || seconds > 86400) throw new NumberFormatException();
                            draft.cooldownSeconds = seconds;
                        } catch (NumberFormatException ex) {
                            player.sendMessage(ChatColor.RED + "Cooldown must be 0 through 86400 seconds.");
                        }
                        openEditor(player);
                    }, () -> openEditor(player));
                }
            }
            case 51 -> {
                if (draft.existingKey == null) return;
                prompt.open(player, "Type DELETE to confirm", "type_here", value -> {
                    if (!player.hasPermission("recipeweaver.studio")) return;
                    if (value.equals("DELETE")) {
                        UUID playerId = player.getUniqueId();
                        if (!pendingWrites.add(playerId)) return;
                        NamespacedKey key = draft.existingKey;
                        try {
                            plugin.removeGridRecipeAsync(key).whenComplete((removed, failure) -> {
                                pendingWrites.remove(playerId);
                                if (!player.isOnline()) return;
                                if (failure != null) {
                                    player.sendMessage(ChatColor.RED + "Delete failed: " + failure.getMessage());
                                    openEditor(player);
                                } else if (!removed) {
                                    player.sendMessage(ChatColor.RED + "Grid recipe no longer exists.");
                                    openList(player, 0);
                                } else {
                                    drafts.remove(playerId);
                                    player.sendMessage(ChatColor.GREEN + "Deleted grid recipe " + key);
                                    openList(player, 0);
                                }
                            });
                        } catch (RuntimeException ex) {
                            pendingWrites.remove(playerId);
                            player.sendMessage(ChatColor.RED + "Delete failed: " + ex.getMessage());
                            openEditor(player);
                        }
                    } else {
                        player.sendMessage(ChatColor.RED + "Deletion cancelled.");
                        openEditor(player);
                    }
                }, () -> openEditor(player));
            }
            case 52 -> { drafts.remove(player.getUniqueId()); openHome(player); }
            case 53 -> prompt.open(player, "Grid recipe ID",
                    draft.existingKey == null ? "new_grid_recipe" : draft.existingKey.getKey(),
                    value -> save(player, draft, value), () -> openEditor(player));
            default -> { }
        }
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
        if (draft.existingKey == null && (plugin.recipe(key).isPresent()
                || plugin.stationRecipe(key).isPresent() || plugin.gridRecipe(key).isPresent())) {
            player.sendMessage(ChatColor.RED + "That recipe ID already exists.");
            openEditor(player);
            return;
        }
        try {
            GridRecipeDefinition recipe = new GridRecipeDefinition(key, draft.tier, draft.kind, draft.size,
                    draft.activeIngredients(), draft.resultChoices, draft.group, draft.enabled, draft.hidden,
                    draft.activeMatchModes(), draft.priority, draft.allowMirror,
                    draft.activeAlternatives(), draft.activeItemTags(),
                    draft.activeConsumeSlots(), draft.activeRemainders(),
                    draft.activeOptionalSlots(), draft.conditions(), draft.soundAction(),
                    draft.advancementAction(), draft.commandAction(), draft.maxShiftBatches,
                     draft.cooldownSeconds, draft.resultMetadataMode == ResultMetadataMode.NONE
                             ? -1 : draft.activeResultCopySourceSlot(), draft.resultMetadataMode,
                     draft.maxCraftsPerPlayer);
            UUID playerId = player.getUniqueId();
            if (!pendingWrites.add(playerId)) {
                player.sendMessage(ChatColor.YELLOW + "Grid recipe save is still in progress.");
                return;
            }
            try {
                plugin.saveGridRecipeAsync(recipe).whenComplete((ignored, failure) -> {
                    pendingWrites.remove(playerId);
                    if (!player.isOnline()) return;
                    if (failure == null) {
                        drafts.remove(playerId);
                        player.sendMessage(ChatColor.GREEN + "Saved grid recipe " + key);
                        openList(player, 0);
                    } else {
                        player.sendMessage(ChatColor.RED + "Grid recipe save failed: " + failure.getMessage());
                        openEditor(player);
                    }
                });
            } catch (RuntimeException ex) {
                pendingWrites.remove(playerId);
                throw ex;
            }
        } catch (IllegalArgumentException | IllegalStateException ex) {
            player.sendMessage(ChatColor.RED + "Cannot save grid recipe: " + ex.getMessage());
            openEditor(player);
        }
    }

    private void clickList(Player player, Holder holder, int slot) {
        if (slot == 49) { openHome(player); return; }
        if (slot == 45) { openList(player, holder.page - 1); return; }
        if (slot == 53) { openList(player, holder.page + 1); return; }
        if (slot < 0 || slot >= 45 || slot >= holder.displayedKeys.size()) return;
        GridRecipeDefinition recipe = displayedRecipe(holder.displayedKeys, slot,
                plugin::gridRecipe);
        if (recipe == null) {
            player.sendMessage(ChatColor.YELLOW + "Grid recipe no longer exists.");
            openList(player, holder.page);
            return;
        }
        drafts.put(player.getUniqueId(), new Draft(recipe));
        openEditor(player);
    }

    static GridRecipeDefinition displayedRecipe(List<NamespacedKey> displayedKeys, int slot,
            Function<NamespacedKey, Optional<GridRecipeDefinition>> lookup) {
        if (slot < 0 || slot >= displayedKeys.size()) return null;
        return lookup.apply(displayedKeys.get(slot)).orElse(null);
    }

    private List<GridRecipeDefinition> sortedRecipes() {
        return orderRecipes(plugin.gridRecipes());
    }

    static List<GridRecipeDefinition> orderRecipes(Collection<GridRecipeDefinition> source) {
        List<GridRecipeDefinition> recipes = new ArrayList<>(source);
        recipes.sort(Comparator.comparingInt(GridRecipeDefinition::priority).reversed()
                .thenComparing(recipe -> recipe.key().toString()));
        return recipes;
    }

    private void craft(Player player, Holder holder, ClickType click) {
        if (click != ClickType.LEFT && click != ClickType.RIGHT
                && click != ClickType.SHIFT_LEFT && click != ClickType.SHIFT_RIGHT) return;
        boolean shift = click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT;
        List<ItemStack> originalInputs = inputs(holder.inventory, holder.size);
        ItemStack[] oldStorage = player.getInventory().getStorageContents();
        ItemStack oldCursor = copy(player.getItemOnCursor());
        List<CraftSoundAction> committedSounds = new ArrayList<>();
        List<CraftAdvancementAction> committedAdvancements = new ArrayList<>();
        List<CraftCommandAction> committedCommands = new ArrayList<>();
        List<GridCraftBatch> committedCustomBatches = new ArrayList<>();
        Set<NamespacedKey> craftedKeys = new LinkedHashSet<>();
        Map<NamespacedKey, Integer> committedCooldowns = new HashMap<>();
        Map<NamespacedKey, Integer> committedLimitedBatches = new HashMap<>();
        Runnable undoCooldowns = () -> { };
        Runnable undoCraftCounts = () -> { };
        try {
            List<ItemStack> current = cloneItems(originalInputs);
            if (shift) {
                ItemStack[] storage = oldStorage;
                int batches = 0;
                Map<NamespacedKey, Integer> batchesByRecipe = new HashMap<>();
                for (int i = 0; i < 64; i++) {
                    Match match = i == 0 ? preview(player, holder, current)
                            : find(player, player.getWorld(), holder.tier, holder.size, current,
                                    committedLimitedBatches);
                    if (match == null) break;
                    if (match.definition != null && batchesByRecipe.getOrDefault(match.key(), 0)
                            >= match.definition.maxShiftBatches()) break;
                    GridMatcher.CraftEffect effect = match.after(current);
                    ItemStack[] nextStorage = insertOutputAndReturns(storage, match.result, effect.returned());
                    if (nextStorage == null) break;
                    List<ItemStack> nextInputs = effect.inputs();
                    if (sameItems(current, nextInputs)) break;
                    storage = nextStorage;
                    current = nextInputs;
                    if (match.key() != null) craftedKeys.add(match.key());
                    if (match.definition != null) {
                        committedCustomBatches.add(new GridCraftBatch(match.key(), match.result));
                        batchesByRecipe.merge(match.key(), 1, Integer::sum);
                        if (match.definition.maxCraftsPerPlayer() > 0) {
                            committedLimitedBatches.merge(match.key(), 1, Integer::sum);
                        }
                        if (match.definition.cooldownSeconds() > 0) {
                            committedCooldowns.put(match.key(), match.definition.cooldownSeconds());
                        }
                    }
                    if (match.definition != null && match.definition.craftSound() != null) {
                        committedSounds.add(match.definition.craftSound());
                    }
                    if (match.definition != null && match.definition.craftAdvancement() != null) {
                        committedAdvancements.add(match.definition.craftAdvancement());
                    }
                    if (match.definition != null && match.definition.craftCommand() != null) {
                        committedCommands.add(match.definition.craftCommand());
                    }
                    batches++;
                }
                if (batches == 0) return;
                setInputs(holder.inventory, holder.size, current);
                player.getInventory().setStorageContents(storage);
            } else {
                Match match = preview(player, holder, current);
                if (match == null) return;
                ItemStack cursor = combine(oldCursor, match.result);
                if (cursor == null) return;
                GridMatcher.CraftEffect effect = match.after(current);
                ItemStack[] nextStorage = insertOutputAndReturns(oldStorage, null, effect.returned());
                if (nextStorage == null) return;
                List<ItemStack> nextInputs = effect.inputs();
                if (sameItems(current, nextInputs)) return;
                setInputs(holder.inventory, holder.size, nextInputs);
                if (!effect.returned().isEmpty()) player.getInventory().setStorageContents(nextStorage);
                player.setItemOnCursor(cursor);
                if (match.key() != null) craftedKeys.add(match.key());
                if (match.definition != null) {
                    committedCustomBatches.add(new GridCraftBatch(match.key(), match.result));
                    if (match.definition.maxCraftsPerPlayer() > 0) {
                        committedLimitedBatches.merge(match.key(), 1, Integer::sum);
                    }
                    if (match.definition.cooldownSeconds() > 0) {
                        committedCooldowns.put(match.key(), match.definition.cooldownSeconds());
                    }
                }
                if (match.definition != null && match.definition.craftSound() != null) {
                    committedSounds.add(match.definition.craftSound());
                }
                if (match.definition != null && match.definition.craftAdvancement() != null) {
                    committedAdvancements.add(match.definition.craftAdvancement());
                }
                if (match.definition != null && match.definition.craftCommand() != null) {
                    committedCommands.add(match.definition.craftCommand());
                }
            }
            holder.previewInputs = null;
            holder.previewMatch = null;
            scheduleRefresh(player, holder);
            Bukkit.getScheduler().runTask(plugin, player::updateInventory);
            if (!committedCooldowns.isEmpty()) {
                undoCooldowns = GridCooldowns.commit(player, committedCooldowns);
            }
            if (!committedLimitedBatches.isEmpty()) {
                undoCraftCounts = GridCraftLimits.commit(player, committedLimitedBatches);
            }
        } catch (RuntimeException ex) {
            try { undoCraftCounts.run(); }
            catch (RuntimeException undoFailure) { ex.addSuppressed(undoFailure); }
            try { undoCooldowns.run(); }
            catch (RuntimeException undoFailure) { ex.addSuppressed(undoFailure); }
            setInputs(holder.inventory, holder.size, originalInputs);
            player.getInventory().setStorageContents(oldStorage);
            player.setItemOnCursor(oldCursor);
            plugin.logAsync(Level.WARNING, "Grid craft failed and was rolled back: " + ex);
            return;
        }
        for (NamespacedKey key : craftedKeys) {
            try { plugin.markDiscovered(player, key); }
            catch (RuntimeException ex) {
                plugin.logAsync(Level.WARNING, "Grid craft discovery failed after commit: " + ex);
            }
        }
        if (!committedCustomBatches.isEmpty()) {
            try {
                Bukkit.getPluginManager().callEvent(
                        new GridCraftCompleteEvent(player, committedCustomBatches));
            } catch (RuntimeException ex) {
                plugin.logAsync(Level.WARNING, "Grid craft event failed after commit: " + ex);
            }
            try { plugin.gridExtensions().runActions(player, committedCustomBatches); }
            catch (RuntimeException ex) {
                plugin.logAsync(Level.WARNING, "Grid extension actions failed after commit: " + ex);
            }
        }
        try { GridCraftSounds.play(player, committedSounds); }
        catch (RuntimeException ex) {
            plugin.logAsync(Level.WARNING, "Grid craft sound failed after commit: " + ex);
        }
        try { GridCraftAdvancements.award(player, committedAdvancements); }
        catch (RuntimeException ex) {
            plugin.logAsync(Level.WARNING, "Grid craft advancement failed after commit: " + ex);
        }
        try {
            for (String failure : GridCraftCommands.execute(player, committedCommands)) {
                plugin.logAsync(Level.WARNING, failure);
            }
        } catch (RuntimeException ex) {
            plugin.logAsync(Level.WARNING, "Grid craft command failed after commit: " + ex);
        }
    }

    private Match find(Player player, World world, GridTier tier, int size, List<ItemStack> input) {
        return find(player, world, tier, size, input, Map.of());
    }

    private Match find(Player player, World world, GridTier tier, int size, List<ItemStack> input,
                       Map<NamespacedKey, Integer> pendingBatches) {
        if (!plugin.lockedDown()) {
            for (GridRecipeDefinition recipe : sortedRecipes()) {
                if (!recipe.enabled() || recipe.tier() != tier || recipe.size() != size) continue;
                if (!GridConditionEvaluator.matches(recipe.conditions(), player, world)) continue;
                if (!GridCooldowns.ready(player, recipe)) continue;
                if (!GridCraftLimits.allows(player, recipe, pendingBatches)) continue;
                GridMatcher.MatchPlan plan = GridMatcher.plan(recipe, input);
                if (plan != null) {
                    if (!plugin.gridExtensions().allows(player, recipe, input)) continue;
                    ItemStack result = recipe.resultForTicket(
                            ThreadLocalRandom.current().nextInt(recipe.totalResultWeight()));
                    try {
                        result = GridResultMetadata.apply(recipe, plan, input, result);
                    } catch (RuntimeException ex) {
                        if (metadataWarnings.add(recipe.key())) {
                            plugin.logAsync(Level.WARNING, "Grid result metadata could not be copied for "
                                    + recipe.key() + ": " + ex);
                        }
                        return null;
                    }
                    return new Match(result, null, recipe.key(), plan, recipe);
                }
            }
        }
        if (tier == GridTier.ADVANCED && size == 3) {
            ItemStack[] matrix = new ItemStack[9];
            for (int i = 0; i < 9; i++) matrix[i] = copy(input.get(i));
            org.bukkit.inventory.Recipe nativeRecipe = Bukkit.getCraftingRecipe(matrix, world);
            ItemCraftResult nativeResult = Bukkit.craftItemResult(matrix, world);
            if (nativeResult != null && GridMatcher.present(nativeResult.getResult())
                    && NativeGridCrafting.allows(nativeRecipe,
                            nativeRecipe instanceof Keyed keyed ? plugin.recipe(keyed.getKey()).orElse(null) : null,
                            plugin.lockedDown(), plugin.disabledVanillaRecipes(), matrix,
                            nativeResult.getResult(), player, world)) {
                NamespacedKey key = nativeRecipe instanceof Keyed keyed ? keyed.getKey() : null;
                return new Match(nativeResult.getResult(), NativeGridCrafting.effect(nativeResult), key, null, null);
            }
        }
        return null;
    }

    private void scheduleRefresh(Player player, Holder holder) {
        Bukkit.getScheduler().runTask(plugin, () -> refresh(player, holder));
    }

    private Match preview(Player player, Holder holder, List<ItemStack> input) {
        Match current = find(player, player.getWorld(), holder.tier, holder.size, input, Map.of());
        if (current != null && current.definition != null && holder.previewMatch != null
                && current.definition == holder.previewMatch.definition
                && holder.previewInputs != null && sameItems(holder.previewInputs, input)) {
            return holder.previewMatch;
        }
        holder.previewInputs = cloneItems(input);
        holder.previewMatch = current;
        return current;
    }

    private void refresh(Player player, Holder holder) {
        if (player.getOpenInventory().getTopInventory() != holder.inventory) return;
        Match match = preview(player, holder, inputs(holder.inventory, holder.size));
        holder.inventory.setItem(7, match == null
                ? icon(Material.BARRIER, "No matching recipe", "Add ingredients to the grid")
                : match.result.clone());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        if (holder.menu != Menu.CRAFT) {
            if (event.getRawSlots().stream().anyMatch(slot -> slot < holder.inventory.getSize())) {
                event.setCancelled(true);
            }
            return;
        }
        if (event.getRawSlots().stream().anyMatch(slot -> slot < holder.inventory.getSize()
                && !inputSlot(slot, holder.size))) {
            event.setCancelled(true);
            return;
        }
        if (event.getWhoClicked() instanceof Player player) scheduleRefresh(player, holder);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder) || holder.menu != Menu.CRAFT) return;
        if (event.getPlayer() instanceof Player player) returnInputs(player, holder);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        drafts.remove(event.getPlayer().getUniqueId());
        if (event.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof Holder holder
                && holder.menu == Menu.CRAFT) returnInputs(event.getPlayer(), holder);
    }

    private static void returnInputs(Player player, Holder holder) {
        for (int row = 0; row < holder.size; row++) {
            for (int col = 0; col < holder.size; col++) {
                int slot = row * 9 + col;
                ItemStack item = holder.inventory.getItem(slot);
                if (!GridMatcher.present(item)) continue;
                holder.inventory.setItem(slot, null);
                returnItem(player, item);
            }
        }
    }

    private static void returnItem(Player player, ItemStack item) {
        player.getInventory().addItem(item.clone()).values().forEach(leftover ->
                player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    private static boolean inputSlot(int raw, int size) {
        return raw >= 0 && raw < 54 && raw / 9 < size && raw % 9 < size;
    }

    private static List<ItemStack> inputs(Inventory inventory, int size) {
        List<ItemStack> result = new ArrayList<>(size * size);
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) result.add(copy(inventory.getItem(row * 9 + col)));
        }
        return result;
    }

    private static void setInputs(Inventory inventory, int size, List<ItemStack> values) {
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                inventory.setItem(row * 9 + col, copy(values.get(row * size + col)));
            }
        }
    }

    private static List<ItemStack> cloneItems(List<ItemStack> items) {
        List<ItemStack> copy = new ArrayList<>(items.size());
        for (ItemStack item : items) copy.add(copy(item));
        return copy;
    }

    private static boolean sameItems(List<ItemStack> first, List<ItemStack> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            if (!java.util.Objects.equals(first.get(i), second.get(i))) return false;
        }
        return true;
    }

    private static ItemStack combine(ItemStack cursor, ItemStack result) {
        if (!GridMatcher.present(cursor)) return result.clone();
        if (!cursor.isSimilar(result) || cursor.getAmount() + result.getAmount() > cursor.getMaxStackSize()) {
            return null;
        }
        ItemStack next = cursor.clone();
        next.setAmount(cursor.getAmount() + result.getAmount());
        return next;
    }

    private static ItemStack copy(ItemStack item) {
        return GridMatcher.present(item) ? item.clone() : null;
    }

    private static IngredientMatchMode nextMatchMode(IngredientMatchMode current) {
        return switch (current) {
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
        };
    }

    /** Returns a new storage snapshot or null; no inventory changes occur on partial capacity. */
    static ItemStack[] insertOutputAndReturns(ItemStack[] storage, ItemStack output,
                                               List<ItemStack> returned) {
        ItemStack[] next = output == null ? storage : StationInventoryListener.insert(storage, output);
        if (next == null) return null;
        for (ItemStack item : returned) {
            next = StationInventoryListener.insert(next, item);
            if (next == null) return null;
        }
        return next;
    }

    private static ItemStack showIngredient(ItemStack item, IngredientMatchMode mode,
                                            int alternativeCount, NamespacedKey tag,
                                            boolean optional, boolean consume, ItemStack remainder) {
        ItemStack shown = item.clone();
        ItemMeta meta = shown.getItemMeta();
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add(ChatColor.AQUA + "Match: " + mode.name().toLowerCase(Locale.ROOT));
        if (alternativeCount > 0) lore.add(ChatColor.AQUA + "Alternatives: " + alternativeCount);
        if (tag != null) lore.add(ChatColor.AQUA + "Item tag: " + tag);
        lore.add(ChatColor.AQUA + (optional ? "Optional when empty" : "Required"));
        lore.add(ChatColor.AQUA + (consume ? "Consumed" : "Kept as catalyst"));
        if (remainder != null) lore.add(ChatColor.AQUA + "Returns: " + remainder.getType()
                + " x" + remainder.getAmount());
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

    private static Inventory create(Holder holder, int size, String title) {
        Inventory inventory = Bukkit.createInventory(holder, size, title);
        holder.inventory = inventory;
        return inventory;
    }

    private static ItemStack icon(Material material, String name, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + name);
        meta.setLore(Arrays.stream(loreLines).map(line -> ChatColor.GRAY + line).toList());
        item.setItemMeta(meta);
        return item;
    }

    private record Match(ItemStack result, GridMatcher.CraftEffect nativeEffect,
                         NamespacedKey key, GridMatcher.MatchPlan plan, GridRecipeDefinition definition) {
        GridMatcher.CraftEffect after(List<ItemStack> inputs) {
            if (nativeEffect != null) return nativeEffect;
            return GridMatcher.apply(definition, inputs, plan);
        }
    }

    private enum Menu { HOME, EDITOR, INGREDIENT, RESULTS, CONDITIONS, SOUND, COMMAND, LIST, DETAIL, CRAFT }

    private static final class Holder implements InventoryHolder {
        private final UUID owner;
        private final Menu menu;
        private final int page;
        private final GridTier tier;
        private final int size;
        private final NamespacedKey recipe;
        private final int ingredientSlot;
        private List<NamespacedKey> displayedKeys = List.of();
        private Inventory inventory;
        private List<ItemStack> previewInputs;
        private Match previewMatch;

        private Holder(UUID owner, Menu menu, int page, GridTier tier, int size) {
            this(owner, menu, page, tier, size, null);
        }

        private Holder(UUID owner, Menu menu, int page, GridTier tier, int size, NamespacedKey recipe) {
            this(owner, menu, page, tier, size, recipe, -1);
        }

        private Holder(UUID owner, Menu menu, int page, GridTier tier, int size,
                       NamespacedKey recipe, int ingredientSlot) {
            this.owner = owner;
            this.menu = menu;
            this.page = page;
            this.tier = tier;
            this.size = size;
            this.recipe = recipe;
            this.ingredientSlot = ingredientSlot;
        }

        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class Draft {
        private final GridTier tier;
        private int size = 3;
        private RecipeKind kind = RecipeKind.SHAPED;
        private NamespacedKey existingKey;
        private final ItemStack[] ingredients = new ItemStack[36];
        private final IngredientMatchMode[] matchModes = new IngredientMatchMode[36];
        private final List<List<ItemStack>> alternatives = new ArrayList<>(36);
        private final NamespacedKey[] itemTags = new NamespacedKey[36];
        private final boolean[] consumeSlots = new boolean[36];
        private final boolean[] optionalSlots = new boolean[36];
        private final ItemStack[] remainders = new ItemStack[36];
        private final List<WeightedResult> resultChoices = new ArrayList<>();
        private String group = "";
        private boolean enabled = true;
        private boolean hidden;
        private int priority;
        private boolean allowMirror = true;
        private int maxShiftBatches = 64;
        private int cooldownSeconds;
        private int maxCraftsPerPlayer;
        private int resultCopySourceSlot = -1;
        private ResultMetadataMode resultMetadataMode = ResultMetadataMode.NONE;
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

        private Draft(GridTier tier) {
            this.tier = tier;
            Arrays.fill(matchModes, IngredientMatchMode.EXACT);
            Arrays.fill(consumeSlots, true);
            for (int i = 0; i < 36; i++) alternatives.add(new ArrayList<>());
        }

        private Draft(GridRecipeDefinition recipe) {
            this(recipe.tier());
            size = recipe.size();
            kind = recipe.kind();
            existingKey = recipe.key();
            List<ItemStack> saved = recipe.ingredients();
            List<IngredientMatchMode> savedModes = recipe.matchModes();
            List<List<ItemStack>> savedAlternatives = recipe.alternatives();
            List<NamespacedKey> savedTags = recipe.itemTags();
            for (int row = 0; row < size; row++) {
                for (int col = 0; col < size; col++) {
                    ingredients[row * 6 + col] = saved.get(row * size + col);
                    matchModes[row * 6 + col] = savedModes.get(row * size + col);
                    alternatives.get(row * 6 + col).addAll(savedAlternatives.get(row * size + col));
                    itemTags[row * 6 + col] = savedTags.get(row * size + col);
                    consumeSlots[row * 6 + col] = recipe.consumes(row * size + col);
                    optionalSlots[row * 6 + col] = recipe.optional(row * size + col);
                    remainders[row * 6 + col] = recipe.remainder(row * size + col);
                }
            }
            resultChoices.addAll(recipe.resultChoices());
            group = recipe.group();
            enabled = recipe.enabled();
            hidden = recipe.hidden();
            priority = recipe.priority();
            allowMirror = recipe.allowMirror();
            maxShiftBatches = recipe.maxShiftBatches();
            cooldownSeconds = recipe.cooldownSeconds();
            maxCraftsPerPlayer = recipe.maxCraftsPerPlayer();
            resultMetadataMode = recipe.resultMetadataMode();
            if (recipe.resultCopySourceSlot() >= 0) {
                resultCopySourceSlot = recipe.resultCopySourceSlot() / size * 6
                        + recipe.resultCopySourceSlot() % size;
            }
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
            CraftAdvancementAction advancementAction = recipe.craftAdvancement();
            if (advancementAction != null) {
                grantedAdvancement = advancementAction.advancement();
            }
            CraftCommandAction command = recipe.craftCommand();
            if (command != null) {
                commandText = command.command();
                commandSender = command.sender();
                commandPerBatch = command.perBatch();
            }
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

        private List<ItemStack> activeIngredients() {
            List<ItemStack> result = new ArrayList<>(size * size);
            for (int row = 0; row < size; row++) {
                for (int col = 0; col < size; col++) result.add(copy(ingredients[row * 6 + col]));
            }
            return result;
        }

        private int activeResultCopySourceSlot() {
            if (resultCopySourceSlot < 0 || resultCopySourceSlot / 6 >= size
                    || resultCopySourceSlot % 6 >= size) return -1;
            return resultCopySourceSlot / 6 * size + resultCopySourceSlot % 6;
        }

        private List<IngredientMatchMode> activeMatchModes() {
            List<IngredientMatchMode> result = new ArrayList<>(size * size);
            for (int row = 0; row < size; row++) {
                for (int col = 0; col < size; col++) result.add(matchModes[row * 6 + col]);
            }
            return result;
        }

        private List<List<ItemStack>> activeAlternatives() {
            List<List<ItemStack>> result = new ArrayList<>(size * size);
            for (int row = 0; row < size; row++) {
                for (int col = 0; col < size; col++) {
                    result.add(new ArrayList<>(alternatives.get(row * 6 + col)));
                }
            }
            return result;
        }

        private List<NamespacedKey> activeItemTags() {
            List<NamespacedKey> result = new ArrayList<>(size * size);
            for (int row = 0; row < size; row++) {
                for (int col = 0; col < size; col++) result.add(itemTags[row * 6 + col]);
            }
            return result;
        }

        private List<Boolean> activeConsumeSlots() {
            List<Boolean> result = new ArrayList<>(size * size);
            for (int row = 0; row < size; row++) {
                for (int col = 0; col < size; col++) result.add(consumeSlots[row * 6 + col]);
            }
            return result;
        }

        private List<Boolean> activeOptionalSlots() {
            List<Boolean> result = new ArrayList<>(size * size);
            for (int row = 0; row < size; row++) {
                for (int col = 0; col < size; col++) result.add(optionalSlots[row * 6 + col]);
            }
            return result;
        }

        private List<ItemStack> activeRemainders() {
            List<ItemStack> result = new ArrayList<>(size * size);
            for (int row = 0; row < size; row++) {
                for (int col = 0; col < size; col++) result.add(copy(remainders[row * 6 + col]));
            }
            return result;
        }
    }
}
