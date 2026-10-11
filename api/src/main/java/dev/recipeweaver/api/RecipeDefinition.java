package dev.recipeweaver.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.recipe.CraftingBookCategory;

/** An immutable recipe snapshot. Slots are stored in row-major order for a 3x3 grid. */
public final class RecipeDefinition {
    private final NamespacedKey key;
    private final RecipeKind kind;
    private final List<ItemStack> ingredients;
    private final List<IngredientMatchMode> matchModes;
    private final List<List<ItemStack>> alternatives;
    private final List<NamespacedKey> itemTags;
    private final ItemStack result;
    private final String group;
    private final CraftingBookCategory bookCategory;
    private final boolean enabled;
    private final boolean hidden;
    private final RecipeConditions conditions;

    public RecipeDefinition(NamespacedKey key, RecipeKind kind, List<ItemStack> ingredients,
                            ItemStack result, String group, boolean enabled, boolean hidden) {
        this(key, kind, ingredients, result, group, enabled, hidden,
                Collections.nCopies(9, IngredientMatchMode.EXACT),
                Collections.nCopies(9, List.of()), Collections.nCopies(9, null));
    }

    /** Native workbench choices. Tags require material matching because Bukkit cannot mix exact and material choices. */
    public RecipeDefinition(NamespacedKey key, RecipeKind kind, List<ItemStack> ingredients,
                            ItemStack result, String group, boolean enabled, boolean hidden,
                            List<IngredientMatchMode> matchModes, List<List<ItemStack>> alternatives,
                            List<NamespacedKey> itemTags) {
        this(key, kind, ingredients, result, group, enabled, hidden,
                matchModes, alternatives, itemTags, CraftingBookCategory.MISC);
    }

    public RecipeDefinition(NamespacedKey key, RecipeKind kind, List<ItemStack> ingredients,
                            ItemStack result, String group, boolean enabled, boolean hidden,
                            List<IngredientMatchMode> matchModes, List<List<ItemStack>> alternatives,
                            List<NamespacedKey> itemTags, CraftingBookCategory bookCategory) {
        this(key, kind, ingredients, result, group, enabled, hidden,
                matchModes, alternatives, itemTags, bookCategory, RecipeConditions.NONE);
    }

    public RecipeDefinition(NamespacedKey key, RecipeKind kind, List<ItemStack> ingredients,
                            ItemStack result, String group, boolean enabled, boolean hidden,
                            List<IngredientMatchMode> matchModes, List<List<ItemStack>> alternatives,
                            List<NamespacedKey> itemTags, CraftingBookCategory bookCategory,
                            RecipeConditions conditions) {
        this.key = Objects.requireNonNull(key, "key");
        if (!key.getNamespace().equals("recipeweaver")) throw new IllegalArgumentException("Recipe IDs must use recipeweaver namespace");
        this.kind = Objects.requireNonNull(kind, "kind");
        if (ingredients == null || ingredients.size() != 9) {
            throw new IllegalArgumentException("A workbench recipe must have exactly 9 slots");
        }
        if (matchModes == null || matchModes.size() != 9 || matchModes.stream().anyMatch(Objects::isNull)
                || alternatives == null || alternatives.size() != 9
                || itemTags == null || itemTags.size() != 9) {
            throw new IllegalArgumentException("Each workbench slot needs a match mode, alternatives, and tag entry");
        }
        if (matchModes.stream().anyMatch(mode -> mode != IngredientMatchMode.EXACT
                && mode != IngredientMatchMode.MATERIAL)) {
            throw new IllegalArgumentException("Component ingredient matching is unavailable on native workbenches");
        }
        List<ItemStack> copy = new ArrayList<>(9);
        List<List<ItemStack>> alternativesCopy = new ArrayList<>(9);
        int count = 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack item = ingredients.get(slot);
            List<ItemStack> choices = alternatives.get(slot);
            NamespacedKey tag = itemTags.get(slot);
            if (choices == null || choices.size() > GridRecipeDefinition.MAX_ALTERNATIVES_PER_SLOT) {
                throw new IllegalArgumentException("A workbench slot may have at most "
                        + GridRecipeDefinition.MAX_ALTERNATIVES_PER_SLOT + " alternatives");
            }
            if (item != null && !item.getType().isAir()) {
                if (item.getAmount() != 1) {
                    throw new IllegalArgumentException("Workbench ingredients currently require amount 1");
                }
                copy.add(item.clone());
                count++;
                if (tag != null && matchModes.get(slot) != IngredientMatchMode.MATERIAL) {
                    throw new IllegalArgumentException("A workbench item tag requires material matching in slot " + slot);
                }
            } else {
                if (!choices.isEmpty() || tag != null) {
                    throw new IllegalArgumentException("An empty workbench slot cannot have alternatives or a tag");
                }
                copy.add(null);
            }
            List<ItemStack> samples = new ArrayList<>(choices.size());
            for (ItemStack choice : choices) {
                if (choice == null || choice.getType().isAir()) {
                    throw new IllegalArgumentException("Workbench alternatives must be items");
                }
                ItemStack sample = choice.clone();
                sample.setAmount(1);
                samples.add(sample);
            }
            alternativesCopy.add(Collections.unmodifiableList(samples));
        }
        if (count == 0) {
            throw new IllegalArgumentException("A recipe needs at least one ingredient");
        }
        if (result == null || result.getType().isAir() || result.getAmount() < 1) {
            throw new IllegalArgumentException("A recipe needs a result");
        }
        this.ingredients = Collections.unmodifiableList(copy);
        this.matchModes = List.copyOf(matchModes);
        this.alternatives = Collections.unmodifiableList(alternativesCopy);
        this.itemTags = Collections.unmodifiableList(new ArrayList<>(itemTags));
        this.result = result.clone();
        this.group = group == null ? "" : group;
        this.bookCategory = Objects.requireNonNull(bookCategory, "bookCategory");
        this.enabled = enabled;
        this.hidden = hidden;
        this.conditions = Objects.requireNonNull(conditions, "conditions");
    }

    public NamespacedKey key() { return key; }
    public RecipeKind kind() { return kind; }
    public List<ItemStack> ingredients() {
        List<ItemStack> copy = new ArrayList<>(9);
        for (ItemStack item : ingredients) copy.add(item == null ? null : item.clone());
        return Collections.unmodifiableList(copy);
    }
    public List<IngredientMatchMode> matchModes() { return matchModes; }
    public List<List<ItemStack>> alternatives() {
        List<List<ItemStack>> copy = new ArrayList<>(9);
        for (List<ItemStack> choices : alternatives) {
            List<ItemStack> samples = new ArrayList<>(choices.size());
            for (ItemStack item : choices) samples.add(item.clone());
            copy.add(Collections.unmodifiableList(samples));
        }
        return Collections.unmodifiableList(copy);
    }
    public List<NamespacedKey> itemTags() { return itemTags; }
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
        return mode == IngredientMatchMode.MATERIAL
                ? sample.getType() == actual.getType() : sample.isSimilar(actual);
    }
    public ItemStack result() { return result.clone(); }
    public String group() { return group; }
    public CraftingBookCategory bookCategory() { return bookCategory; }
    public boolean enabled() { return enabled; }
    public boolean hidden() { return hidden; }
    public RecipeConditions conditions() { return conditions; }
}
