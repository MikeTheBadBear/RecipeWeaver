package dev.recipeweaver.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.recipe.CookingBookCategory;

/** Immutable recipe for a standard Minecraft workstation. Smithing trims derive their result from the base item. */
public final class StationRecipeDefinition {
    public static final int MAX_ALTERNATIVES_PER_SLOT = 16;
    public static final int MAX_RESULTS = 16;
    private final NamespacedKey key;
    private final StationKind kind;
    private final List<ItemStack> ingredients;
    private final List<IngredientMatchMode> matchModes;
    private final List<List<ItemStack>> alternatives;
    private final List<NamespacedKey> itemTags;
    private final List<Boolean> consumeSlots;
    private final List<ItemStack> remainders;
    private final List<Boolean> optionalSlots;
    private final ItemStack result;
    private final List<WeightedResult> resultChoices;
    private final int totalResultWeight;
    private final String group;
    private final CookingBookCategory bookCategory;
    private final int cookingTicks;
    private final float experience;
    private final int anvilLevels;
    private final boolean enabled;
    private final boolean hidden;
    private final int maxCraftsPerPlayer;
    private final int cooldownSeconds;
    private final RecipeConditions conditions;
    private final CraftSoundAction craftSound;
    private final CraftAdvancementAction craftAdvancement;
    private final CraftCommandAction craftCommand;
    private final int resultCopySourceSlot;
    private final ResultMetadataMode resultMetadataMode;

    public StationRecipeDefinition(NamespacedKey key, StationKind kind, List<ItemStack> ingredients,
                                   ItemStack result, String group, int cookingTicks, float experience,
                                   int anvilLevels, boolean enabled, boolean hidden) {
        this(key, kind, ingredients, result, group, cookingTicks, experience, anvilLevels,
                enabled, hidden, Collections.nCopies(kind.slots(), IngredientMatchMode.EXACT),
                Collections.nCopies(kind.slots(), List.of()), Collections.nCopies(kind.slots(), null));
    }

    /** Weighted outcomes are selected once per owned preview, cauldron mix, or brewed bottle. */
    public static StationRecipeDefinition withResults(
            NamespacedKey key, StationKind kind, List<ItemStack> ingredients,
            List<WeightedResult> resultChoices, String group, int cookingTicks,
            float experience, int anvilLevels, boolean enabled, boolean hidden) {
        return new StationRecipeDefinition(key, kind, ingredients, firstResult(resultChoices),
                group, cookingTicks, experience, anvilLevels, enabled, hidden,
                Collections.nCopies(kind.slots(), IngredientMatchMode.EXACT),
                Collections.nCopies(kind.slots(), List.of()),
                Collections.nCopies(kind.slots(), null), CookingBookCategory.MISC,
                0, 0, RecipeConditions.NONE, null, null, null, -1,
                ResultMetadataMode.NONE, Collections.nCopies(kind.slots(), true),
                Collections.nCopies(kind.slots(), null),
                Collections.nCopies(kind.slots(), false), resultChoices);
    }

    /** Native and custom station choices; a tag requires material matching for consistent behavior. */
    public StationRecipeDefinition(NamespacedKey key, StationKind kind, List<ItemStack> ingredients,
                                   ItemStack result, String group, int cookingTicks, float experience,
                                   int anvilLevels, boolean enabled, boolean hidden,
                                   List<IngredientMatchMode> matchModes, List<List<ItemStack>> alternatives,
                                   List<NamespacedKey> itemTags) {
        this(key, kind, ingredients, result, group, cookingTicks, experience, anvilLevels,
                enabled, hidden, matchModes, alternatives, itemTags, CookingBookCategory.MISC);
    }

    public StationRecipeDefinition(NamespacedKey key, StationKind kind, List<ItemStack> ingredients,
                                   ItemStack result, String group, int cookingTicks, float experience,
                                   int anvilLevels, boolean enabled, boolean hidden,
                                   List<IngredientMatchMode> matchModes, List<List<ItemStack>> alternatives,
                                   List<NamespacedKey> itemTags, CookingBookCategory bookCategory) {
        this(key, kind, ingredients, result, group, cookingTicks, experience, anvilLevels,
                enabled, hidden, matchModes, alternatives, itemTags, bookCategory, 0, 0);
    }

    /** Player rules are supported by RecipeWeaver's owned anvil and grindstone transactions. */
    public StationRecipeDefinition(NamespacedKey key, StationKind kind, List<ItemStack> ingredients,
                                   ItemStack result, String group, int cookingTicks, float experience,
                                   int anvilLevels, boolean enabled, boolean hidden,
                                   List<IngredientMatchMode> matchModes, List<List<ItemStack>> alternatives,
                                   List<NamespacedKey> itemTags, CookingBookCategory bookCategory,
                                   int maxCraftsPerPlayer, int cooldownSeconds) {
        this(key, kind, ingredients, result, group, cookingTicks, experience, anvilLevels,
                enabled, hidden, matchModes, alternatives, itemTags, bookCategory,
                maxCraftsPerPlayer, cooldownSeconds, RecipeConditions.NONE);
    }

    /** Cooking checks world conditions at commit; owned stations also check player conditions. */
    public StationRecipeDefinition(NamespacedKey key, StationKind kind, List<ItemStack> ingredients,
                                   ItemStack result, String group, int cookingTicks, float experience,
                                   int anvilLevels, boolean enabled, boolean hidden,
                                   List<IngredientMatchMode> matchModes, List<List<ItemStack>> alternatives,
                                   List<NamespacedKey> itemTags, CookingBookCategory bookCategory,
                                   int maxCraftsPerPlayer, int cooldownSeconds,
                                   RecipeConditions conditions) {
        this(key, kind, ingredients, result, group, cookingTicks, experience, anvilLevels,
                enabled, hidden, matchModes, alternatives, itemTags, bookCategory,
                maxCraftsPerPlayer, cooldownSeconds, conditions, null, null, null);
    }

    /** Post-commit actions are supported by RecipeWeaver's owned anvil and grindstone transactions. */
    public StationRecipeDefinition(NamespacedKey key, StationKind kind, List<ItemStack> ingredients,
                                   ItemStack result, String group, int cookingTicks, float experience,
                                   int anvilLevels, boolean enabled, boolean hidden,
                                   List<IngredientMatchMode> matchModes, List<List<ItemStack>> alternatives,
                                   List<NamespacedKey> itemTags, CookingBookCategory bookCategory,
                                   int maxCraftsPerPlayer, int cooldownSeconds,
                                   RecipeConditions conditions, CraftSoundAction craftSound,
                                   CraftAdvancementAction craftAdvancement,
                                   CraftCommandAction craftCommand) {
        this(key, kind, ingredients, result, group, cookingTicks, experience, anvilLevels,
                enabled, hidden, matchModes, alternatives, itemTags, bookCategory,
                maxCraftsPerPlayer, cooldownSeconds, conditions, craftSound,
                craftAdvancement, craftCommand, -1, ResultMetadataMode.NONE);
    }

    /** Copies selected result metadata from one actual anvil or grindstone ingredient. */
    public StationRecipeDefinition(NamespacedKey key, StationKind kind, List<ItemStack> ingredients,
                                   ItemStack result, String group, int cookingTicks, float experience,
                                   int anvilLevels, boolean enabled, boolean hidden,
                                   List<IngredientMatchMode> matchModes, List<List<ItemStack>> alternatives,
                                   List<NamespacedKey> itemTags, CookingBookCategory bookCategory,
                                   int maxCraftsPerPlayer, int cooldownSeconds,
                                   RecipeConditions conditions, CraftSoundAction craftSound,
                                   CraftAdvancementAction craftAdvancement,
                                   CraftCommandAction craftCommand, int resultCopySourceSlot,
                                   ResultMetadataMode resultMetadataMode) {
        this(key, kind, ingredients, result, group, cookingTicks, experience, anvilLevels,
                enabled, hidden, matchModes, alternatives, itemTags, bookCategory,
                maxCraftsPerPlayer, cooldownSeconds, conditions, craftSound,
                craftAdvancement, craftCommand, resultCopySourceSlot, resultMetadataMode,
                Collections.nCopies(kind.slots(), true), Collections.nCopies(kind.slots(), null));
    }

    /** Amounts, kept inputs, and returned items are enforced by owned station transactions. */
    public StationRecipeDefinition(NamespacedKey key, StationKind kind, List<ItemStack> ingredients,
                                   ItemStack result, String group, int cookingTicks, float experience,
                                   int anvilLevels, boolean enabled, boolean hidden,
                                   List<IngredientMatchMode> matchModes, List<List<ItemStack>> alternatives,
                                   List<NamespacedKey> itemTags, CookingBookCategory bookCategory,
                                   int maxCraftsPerPlayer, int cooldownSeconds,
                                   RecipeConditions conditions, CraftSoundAction craftSound,
                                   CraftAdvancementAction craftAdvancement,
                                   CraftCommandAction craftCommand, int resultCopySourceSlot,
                                   ResultMetadataMode resultMetadataMode,
                                   List<Boolean> consumeSlots, List<ItemStack> remainders) {
        this(key, kind, ingredients, result, group, cookingTicks, experience, anvilLevels,
                enabled, hidden, matchModes, alternatives, itemTags, bookCategory,
                maxCraftsPerPlayer, cooldownSeconds, conditions, craftSound,
                craftAdvancement, craftCommand, resultCopySourceSlot, resultMetadataMode,
                consumeSlots, remainders, Collections.nCopies(kind.slots(), false));
    }

    /** Optional second inputs and partial metadata matching run in owned station craft paths. */
    public StationRecipeDefinition(NamespacedKey key, StationKind kind, List<ItemStack> ingredients,
                                   ItemStack result, String group, int cookingTicks, float experience,
                                   int anvilLevels, boolean enabled, boolean hidden,
                                   List<IngredientMatchMode> matchModes, List<List<ItemStack>> alternatives,
                                   List<NamespacedKey> itemTags, CookingBookCategory bookCategory,
                                   int maxCraftsPerPlayer, int cooldownSeconds,
                                   RecipeConditions conditions, CraftSoundAction craftSound,
                                   CraftAdvancementAction craftAdvancement,
                                   CraftCommandAction craftCommand, int resultCopySourceSlot,
                                   ResultMetadataMode resultMetadataMode,
                                   List<Boolean> consumeSlots, List<ItemStack> remainders,
                                   List<Boolean> optionalSlots) {
        this(key, kind, ingredients, result, group, cookingTicks, experience, anvilLevels,
                enabled, hidden, matchModes, alternatives, itemTags, bookCategory,
                maxCraftsPerPlayer, cooldownSeconds, conditions, craftSound,
                craftAdvancement, craftCommand, resultCopySourceSlot, resultMetadataMode,
                consumeSlots, remainders, optionalSlots,
                result == null ? List.of() : List.of(new WeightedResult(result, 1)));
    }

    public StationRecipeDefinition(NamespacedKey key, StationKind kind, List<ItemStack> ingredients,
                                    ItemStack result, String group, int cookingTicks,
                                    float experience, int anvilLevels, boolean enabled,
                                    boolean hidden, List<IngredientMatchMode> matchModes,
                                    List<List<ItemStack>> alternatives,
                                    List<NamespacedKey> itemTags, CookingBookCategory bookCategory,
                                    int maxCraftsPerPlayer, int cooldownSeconds,
                                    RecipeConditions conditions, CraftSoundAction craftSound,
                                    CraftAdvancementAction craftAdvancement,
                                    CraftCommandAction craftCommand, int resultCopySourceSlot,
                                    ResultMetadataMode resultMetadataMode,
                                    List<Boolean> consumeSlots, List<ItemStack> remainders,
                                    List<Boolean> optionalSlots,
                                    List<WeightedResult> resultChoices) {
        this.key = Objects.requireNonNull(key, "key");
        this.kind = Objects.requireNonNull(kind, "kind");
        if (!key.getNamespace().equals("recipeweaver")) throw new IllegalArgumentException("Recipe IDs must use recipeweaver namespace");
        if (ingredients == null || ingredients.size() != kind.slots()) {
            throw new IllegalArgumentException(kind + " needs " + kind.slots() + " input slots");
        }
        if (matchModes == null || matchModes.size() != kind.slots()
                || matchModes.stream().anyMatch(Objects::isNull)
                || alternatives == null || alternatives.size() != kind.slots()
                || itemTags == null || itemTags.size() != kind.slots()) {
            throw new IllegalArgumentException("Each station slot needs a match mode, alternatives, and tag entry");
        }
        if (consumeSlots == null || consumeSlots.size() != kind.slots()
                || consumeSlots.stream().anyMatch(Objects::isNull)
                || remainders == null || remainders.size() != kind.slots()) {
            throw new IllegalArgumentException("Each station slot needs consumption and remainder settings");
        }
        if (optionalSlots == null || optionalSlots.size() != kind.slots()
                || optionalSlots.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Each station slot needs an optional setting");
        }
        if (matchModes.stream().anyMatch(mode -> mode != IngredientMatchMode.EXACT
                    && mode != IngredientMatchMode.MATERIAL)
                && kind != StationKind.ANVIL && kind != StationKind.GRINDSTONE) {
            throw new IllegalArgumentException("Component ingredient matching requires an anvil or grindstone");
        }
        List<ItemStack> copy = new ArrayList<>(kind.slots());
        List<List<ItemStack>> alternativeCopy = new ArrayList<>(kind.slots());
        List<ItemStack> remainderCopy = new ArrayList<>(kind.slots());
        boolean consumesRequiredInput = false;
        for (int slot = 0; slot < kind.slots(); slot++) {
            ItemStack ingredient = ingredients.get(slot);
            List<ItemStack> choices = alternatives.get(slot);
            NamespacedKey tag = itemTags.get(slot);
            boolean consume = consumeSlots.get(slot);
            ItemStack remainder = remainders.get(slot);
            boolean optional = optionalSlots.get(slot);
            if (choices == null || choices.size() > MAX_ALTERNATIVES_PER_SLOT) {
                throw new IllegalArgumentException("A station slot may have at most "
                        + MAX_ALTERNATIVES_PER_SLOT + " alternatives");
            }
            if (ingredient == null || ingredient.getType().isAir()) {
                if (!choices.isEmpty() || tag != null || !consume || remainder != null || optional) {
                    throw new IllegalArgumentException("An empty station slot cannot have ingredient rules");
                }
                copy.add(null);
            } else {
                if (optional && (slot == 0
                        || kind != StationKind.ANVIL && kind != StationKind.GRINDSTONE)) {
                    throw new IllegalArgumentException("Only a second anvil or grindstone input can be optional");
                }
                if (ingredient.getAmount() < 1 || ingredient.getAmount() > ingredient.getMaxStackSize()
                        || (ingredient.getAmount() != 1 && kind != StationKind.ANVIL
                        && kind != StationKind.GRINDSTONE && kind != StationKind.CAULDRON
                        && !(kind == StationKind.BREWING && slot == 0))) {
                    throw new IllegalArgumentException("Input amount is unsupported for this station");
                }
                if (!consume && kind != StationKind.ANVIL && kind != StationKind.GRINDSTONE
                        && !(kind == StationKind.CAULDRON && slot == 1)
                        && !(kind == StationKind.BREWING && slot == 0)) {
                    throw new IllegalArgumentException("This station cannot keep the selected input");
                }
                if (remainder != null && kind != StationKind.ANVIL && kind != StationKind.GRINDSTONE
                        && kind != StationKind.CAULDRON && kind != StationKind.BREWING) {
                    throw new IllegalArgumentException("This station cannot return an item");
                }
                if (!consume && remainder != null) {
                    throw new IllegalArgumentException("A kept input cannot return an item");
                }
                if (consume && !optional) consumesRequiredInput = true;
                copy.add(ingredient.clone());
                if (tag != null && matchModes.get(slot) != IngredientMatchMode.MATERIAL) {
                    throw new IllegalArgumentException("An item tag requires material matching in slot " + slot);
                }
            }
            if (remainder != null && (remainder.getType().isAir() || remainder.getAmount() < 1
                    || remainder.getAmount() > remainder.getMaxStackSize())) {
                throw new IllegalArgumentException("A returned item must fit one stack");
            }
            remainderCopy.add(remainder == null ? null : remainder.clone());
            List<ItemStack> samples = new ArrayList<>(choices.size());
            for (ItemStack choice : choices) {
                if (choice == null || choice.getType().isAir()) {
                    throw new IllegalArgumentException("Station alternatives must be items");
                }
                if (kind == StationKind.BREWING && slot == 1 && !bottleMaterial(choice.getType())) {
                    throw new IllegalArgumentException("Brewing bottle alternatives must fit a bottle slot");
                }
                ItemStack sample = choice.clone();
                sample.setAmount(1);
                samples.add(sample);
            }
            alternativeCopy.add(Collections.unmodifiableList(samples));
        }
        if (copy.get(0) == null) throw new IllegalArgumentException("The first input is required");
        if (!consumesRequiredInput) throw new IllegalArgumentException("At least one input must be consumed");
        if ((kind == StationKind.SMITHING_TRANSFORM || kind == StationKind.SMITHING_TRIM)
                && (copy.get(1) == null || copy.get(2) == null)) {
            throw new IllegalArgumentException("Smithing requires template, base, and addition");
        }
        if ((kind == StationKind.BREWING || kind == StationKind.CAULDRON) && copy.get(1) == null) {
            throw new IllegalArgumentException(kind + " requires two ingredients");
        }
        Objects.requireNonNull(resultMetadataMode, "resultMetadataMode");
        if (resultMetadataMode == ResultMetadataMode.NONE) {
            if (resultCopySourceSlot != -1) {
                throw new IllegalArgumentException("Disabled result metadata copying needs no source slot");
            }
        } else if (kind != StationKind.ANVIL && kind != StationKind.GRINDSTONE
                && kind != StationKind.CAULDRON && kind != StationKind.BREWING) {
            throw new IllegalArgumentException("Result metadata copying is unsupported for this station");
        } else if (resultCopySourceSlot < 0 || resultCopySourceSlot >= kind.slots()
                || copy.get(resultCopySourceSlot) == null
                || optionalSlots.get(resultCopySourceSlot)) {
            throw new IllegalArgumentException("Result metadata source must be a required ingredient slot");
        }
        if (kind != StationKind.SMITHING_TRIM && (result == null || result.getType().isAir() || result.getAmount() < 1)) {
            throw new IllegalArgumentException("Result is required");
        }
        if (resultChoices == null || resultChoices.size() > MAX_RESULTS
                || resultChoices.stream().anyMatch(Objects::isNull)
                || (kind == StationKind.SMITHING_TRIM ? !resultChoices.isEmpty()
                        : resultChoices.isEmpty())) {
            throw new IllegalArgumentException("A station recipe needs 1 through "
                    + MAX_RESULTS + " results, except a derived smithing trim");
        }
        if (resultChoices.size() > 1
                && kind != StationKind.ANVIL && kind != StationKind.GRINDSTONE
                && kind != StationKind.CAULDRON && kind != StationKind.BREWING) {
            throw new IllegalArgumentException("Weighted results require an anvil, grindstone, cauldron, or brewing stand");
        }
        List<WeightedResult> outputCopy = new ArrayList<>(resultChoices.size());
        int totalWeight = 0;
        for (WeightedResult choice : resultChoices) {
            outputCopy.add(new WeightedResult(choice.item(), choice.weight()));
            totalWeight += choice.weight();
        }
        if (result != null && !outputCopy.isEmpty()
                && (result.getAmount() != outputCopy.get(0).item().getAmount()
                        || !result.isSimilar(outputCopy.get(0).item()))) {
            throw new IllegalArgumentException("Primary result must match the first weighted result");
        }
        if (resultMetadataMode == ResultMetadataMode.ALL_ITEM_DATA) {
            var material = copy.get(resultCopySourceSlot).getType();
            if (result.getType() != material || itemTags.get(resultCopySourceSlot) != null
                    || alternatives.get(resultCopySourceSlot).stream()
                            .anyMatch(choice -> choice.getType() != material)
                    || outputCopy.stream().anyMatch(choice -> choice.item().getType() != material)) {
                throw new IllegalArgumentException("Full item data copy needs one source material "
                        + "and a result of that same material");
            }
        }
        if (kind == StationKind.BREWING) {
            org.bukkit.Material bottle = copy.get(1).getType();
            if (!bottleMaterial(bottle)) {
                throw new IllegalArgumentException("Brewing input must fit a bottle slot");
            }
            if (outputCopy.stream().anyMatch(choice -> !bottleMaterial(choice.item().getType()))) {
                throw new IllegalArgumentException("Every brewing result must fit a bottle slot");
            }
            if (outputCopy.stream().anyMatch(choice -> choice.item().getAmount() != 1)) {
                throw new IllegalArgumentException("Every brewing result amount must be 1");
            }
        }
        if (cookingTicks < 1 || cookingTicks > 72000) throw new IllegalArgumentException("Invalid cooking time");
        if (!Float.isFinite(experience) || experience < 0) throw new IllegalArgumentException("Invalid XP");
        if (anvilLevels < 0 || anvilLevels > 40) throw new IllegalArgumentException("Invalid anvil levels");
        if (maxCraftsPerPlayer < 0 || maxCraftsPerPlayer > 1_000_000) {
            throw new IllegalArgumentException("Per-player craft limit must be 0 through 1000000");
        }
        if (cooldownSeconds < 0 || cooldownSeconds > 86400) {
            throw new IllegalArgumentException("Craft delay must be 0 through 86400 seconds");
        }
        Objects.requireNonNull(conditions, "conditions");
        if ((maxCraftsPerPlayer > 0 || cooldownSeconds > 0)
                && kind != StationKind.ANVIL && kind != StationKind.GRINDSTONE) {
            throw new IllegalArgumentException("Craft limits and delays require an anvil or grindstone");
        }
        if ((craftSound != null || craftAdvancement != null || craftCommand != null)
                && kind != StationKind.ANVIL && kind != StationKind.GRINDSTONE
                && kind != StationKind.CAULDRON && !kind.cooking()) {
            throw new IllegalArgumentException("Actions require an anvil, grindstone, cauldron, or cooking station");
        }
        if (kind.cooking() && craftAdvancement != null) {
            throw new IllegalArgumentException("Automated cooking cannot grant a player advancement");
        }
        if (kind.cooking() && craftCommand != null) {
            if (craftCommand.sender() != CraftCommandSender.CONSOLE) {
                throw new IllegalArgumentException("Automated cooking requires a console command sender");
            }
            String command = craftCommand.command();
            if (command.contains("{player}") || command.contains("{uuid}")
                    || command.matches("(?s).*%[^%]+%.*")) {
                throw new IllegalArgumentException("Automated cooking cannot resolve player placeholders");
            }
        }
        if (!conditions.unrestricted() && !kind.cooking()
                && kind != StationKind.ANVIL && kind != StationKind.GRINDSTONE
                && kind != StationKind.SMITHING_TRANSFORM
                && kind != StationKind.SMITHING_TRIM
                && kind != StationKind.STONECUTTER
                && kind != StationKind.CAULDRON
                && kind != StationKind.BREWING) {
            throw new IllegalArgumentException("Conditions require cooking, anvil, grindstone,"
                    + " smithing, stonecutter, cauldron, or brewing");
        }
        if (!kind.cooking() && bookCategory != CookingBookCategory.MISC) {
            throw new IllegalArgumentException("Only cooking stations have a native recipe-book category");
        }
        this.ingredients = Collections.unmodifiableList(copy);
        this.matchModes = List.copyOf(matchModes);
        this.alternatives = Collections.unmodifiableList(alternativeCopy);
        this.itemTags = Collections.unmodifiableList(new ArrayList<>(itemTags));
        this.consumeSlots = List.copyOf(consumeSlots);
        this.remainders = Collections.unmodifiableList(remainderCopy);
        this.optionalSlots = List.copyOf(optionalSlots);
        this.result = result == null ? null : result.clone();
        this.resultChoices = List.copyOf(outputCopy);
        this.totalResultWeight = totalWeight;
        this.group = group == null ? "" : group;
        this.bookCategory = Objects.requireNonNull(bookCategory, "bookCategory");
        this.cookingTicks = cookingTicks;
        this.experience = experience;
        this.anvilLevels = anvilLevels;
        this.enabled = enabled;
        this.hidden = hidden;
        this.maxCraftsPerPlayer = maxCraftsPerPlayer;
        this.cooldownSeconds = cooldownSeconds;
        this.conditions = conditions;
        this.craftSound = craftSound;
        this.craftAdvancement = craftAdvancement;
        this.craftCommand = craftCommand;
        this.resultCopySourceSlot = resultCopySourceSlot;
        this.resultMetadataMode = resultMetadataMode;
    }

    public NamespacedKey key() { return key; }
    public StationKind kind() { return kind; }
    public List<ItemStack> ingredients() {
        List<ItemStack> copy = new ArrayList<>(ingredients.size());
        for (ItemStack item : ingredients) copy.add(item == null ? null : item.clone());
        return Collections.unmodifiableList(copy);
    }
    public List<IngredientMatchMode> matchModes() { return matchModes; }
    public List<List<ItemStack>> alternatives() {
        List<List<ItemStack>> copy = new ArrayList<>(alternatives.size());
        for (List<ItemStack> choices : alternatives) {
            List<ItemStack> samples = new ArrayList<>(choices.size());
            for (ItemStack item : choices) samples.add(item.clone());
            copy.add(Collections.unmodifiableList(samples));
        }
        return Collections.unmodifiableList(copy);
    }
    public List<NamespacedKey> itemTags() { return itemTags; }
    public List<Boolean> consumeSlots() { return consumeSlots; }
    public boolean consumes(int slot) { return consumeSlots.get(slot); }
    public List<Boolean> optionalSlots() { return optionalSlots; }
    public boolean optional(int slot) { return optionalSlots.get(slot); }
    public ItemStack remainder(int slot) {
        ItemStack item = remainders.get(slot);
        return item == null ? null : item.clone();
    }
    public List<ItemStack> remainders() {
        List<ItemStack> copy = new ArrayList<>(remainders.size());
        for (ItemStack item : remainders) copy.add(item == null ? null : item.clone());
        return Collections.unmodifiableList(copy);
    }
    public boolean hasIngredient(int slot) { return ingredients.get(slot) != null; }
    public boolean matchesItemSample(int slot, ItemStack actual) {
        if (!hasIngredient(slot) || actual == null || actual.getType().isAir()) return false;
        IngredientMatchMode mode = matchModes.get(slot);
        if (sampleMatches(ingredients.get(slot), mode, actual)) return true;
        for (ItemStack sample : alternatives.get(slot)) {
            if (sampleMatches(sample, mode, actual)) return true;
        }
        return false;
    }
    private static boolean sampleMatches(ItemStack sample, IngredientMatchMode mode, ItemStack actual) {
        return GridRecipeDefinition.sampleMatches(sample, mode, actual);
    }
    public static boolean bottleMaterial(org.bukkit.Material material) {
        return material == org.bukkit.Material.POTION || material == org.bukkit.Material.SPLASH_POTION
                || material == org.bukkit.Material.LINGERING_POTION || material == org.bukkit.Material.GLASS_BOTTLE;
    }
    public ItemStack result() { return result == null ? null : result.clone(); }
    public List<WeightedResult> resultChoices() { return resultChoices; }
    public int totalResultWeight() { return totalResultWeight; }
    /** Ticket is zero based in the range [0, totalResultWeight). */
    public ItemStack resultForTicket(int ticket) {
        if (ticket < 0 || ticket >= totalResultWeight) {
            throw new IllegalArgumentException("Invalid result ticket");
        }
        int offset = ticket;
        for (WeightedResult choice : resultChoices) {
            if (offset < choice.weight()) return choice.item();
            offset -= choice.weight();
        }
        throw new IllegalStateException("Result weights are inconsistent");
    }
    private static ItemStack firstResult(List<WeightedResult> choices) {
        if (choices == null || choices.isEmpty() || choices.get(0) == null) {
            throw new IllegalArgumentException("A weighted station recipe needs a result");
        }
        return choices.get(0).item();
    }
    public String group() { return group; }
    public CookingBookCategory bookCategory() { return bookCategory; }
    public int cookingTicks() { return cookingTicks; }
    public float experience() { return experience; }
    public int anvilLevels() { return anvilLevels; }
    public boolean enabled() { return enabled; }
    public boolean hidden() { return hidden; }
    public int maxCraftsPerPlayer() { return maxCraftsPerPlayer; }
    public int cooldownSeconds() { return cooldownSeconds; }
    public RecipeConditions conditions() { return conditions; }
    public CraftSoundAction craftSound() { return craftSound; }
    public CraftAdvancementAction craftAdvancement() { return craftAdvancement; }
    public CraftCommandAction craftCommand() { return craftCommand; }
    public int resultCopySourceSlot() { return resultCopySourceSlot; }
    public ResultMetadataMode resultMetadataMode() { return resultMetadataMode; }
}
