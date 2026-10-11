package dev.recipeweaver.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.FireworkEffectMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.inventory.meta.SuspiciousStewMeta;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.WritableBookMeta;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.inventory.meta.TropicalFishBucketMeta;
import org.bukkit.inventory.meta.KnowledgeBookMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;

/** Immutable recipe for a RecipeWeaver table. Inputs are row-major for a square grid. */
public final class GridRecipeDefinition {
    public static final int MAX_ALTERNATIVES_PER_SLOT = 16;
    public static final int MAX_RESULTS = 16;
    private static final List<PersistentDataType<?, ?>> PERSISTENT_TYPES = List.of(
            PersistentDataType.BYTE, PersistentDataType.SHORT, PersistentDataType.INTEGER,
            PersistentDataType.LONG, PersistentDataType.FLOAT, PersistentDataType.DOUBLE,
            PersistentDataType.STRING, PersistentDataType.BYTE_ARRAY,
            PersistentDataType.INTEGER_ARRAY, PersistentDataType.LONG_ARRAY,
            PersistentDataType.TAG_CONTAINER, PersistentDataType.TAG_CONTAINER_ARRAY,
            PersistentDataType.LIST.bytes(), PersistentDataType.LIST.shorts(),
            PersistentDataType.LIST.integers(), PersistentDataType.LIST.longs(),
            PersistentDataType.LIST.floats(), PersistentDataType.LIST.doubles(),
            PersistentDataType.LIST.strings(), PersistentDataType.LIST.byteArrays(),
            PersistentDataType.LIST.integerArrays(), PersistentDataType.LIST.longArrays(),
            PersistentDataType.LIST.dataContainers());
    private final NamespacedKey key;
    private final GridTier tier;
    private final RecipeKind kind;
    private final int size;
    private final List<ItemStack> ingredients;
    private final List<IngredientMatchMode> matchModes;
    private final List<List<ItemStack>> alternatives;
    private final List<NamespacedKey> itemTags;
    private final List<Boolean> consumeSlots;
    private final List<Boolean> optionalSlots;
    private final List<ItemStack> remainders;
    private final ItemStack result;
    private final List<WeightedResult> resultChoices;
    private final int totalResultWeight;
    private final String group;
    private final boolean enabled;
    private final boolean hidden;
    private final int priority;
    private final boolean allowMirror;
    private final RecipeConditions conditions;
    private final CraftSoundAction craftSound;
    private final CraftAdvancementAction craftAdvancement;
    private final CraftCommandAction craftCommand;
    private final int maxShiftBatches;
    private final int cooldownSeconds;
    private final int maxCraftsPerPlayer;
    private final int resultCopySourceSlot;
    private final ResultMetadataMode resultMetadataMode;

    public GridRecipeDefinition(NamespacedKey key, GridTier tier, RecipeKind kind, int size,
                                List<ItemStack> ingredients, ItemStack result, String group,
                                boolean enabled, boolean hidden) {
        this(key, tier, kind, size, ingredients, result, group, enabled, hidden,
                Collections.nCopies(size * size, IngredientMatchMode.EXACT), 0, true);
    }

    public GridRecipeDefinition(NamespacedKey key, GridTier tier, RecipeKind kind, int size,
                                List<ItemStack> ingredients, ItemStack result, String group,
                                boolean enabled, boolean hidden,
                                List<IngredientMatchMode> matchModes, int priority,
                                boolean allowMirror) {
        this(key, tier, kind, size, ingredients, result, group, enabled, hidden,
                matchModes, priority, allowMirror,
                Collections.nCopies(size * size, List.of()),
                Collections.nCopies(size * size, null));
    }

    /** Alternatives and the optional item tag augment the sample item in each occupied slot. */
    public GridRecipeDefinition(NamespacedKey key, GridTier tier, RecipeKind kind, int size,
                                List<ItemStack> ingredients, ItemStack result, String group,
                                boolean enabled, boolean hidden,
                                List<IngredientMatchMode> matchModes, int priority,
                                boolean allowMirror, List<List<ItemStack>> alternatives,
                                List<NamespacedKey> itemTags) {
        this(key, tier, kind, size, ingredients,
                List.of(new WeightedResult(result, 1)), group, enabled, hidden,
                matchModes, priority, allowMirror, alternatives, itemTags);
    }

    /** Result choices are rolled once per craft; the first choice is the recipe-book icon. */
    public GridRecipeDefinition(NamespacedKey key, GridTier tier, RecipeKind kind, int size,
                                List<ItemStack> ingredients, List<WeightedResult> resultChoices,
                                String group, boolean enabled, boolean hidden,
                                List<IngredientMatchMode> matchModes, int priority,
                                boolean allowMirror, List<List<ItemStack>> alternatives,
                                List<NamespacedKey> itemTags) {
        this(key, tier, kind, size, ingredients, resultChoices, group, enabled, hidden,
                matchModes, priority, allowMirror, alternatives, itemTags,
                Collections.nCopies(size * size, true), Collections.nCopies(size * size, null));
    }

    /** Non-consuming slots act as catalysts. A consumed slot may return one configured stack. */
    public GridRecipeDefinition(NamespacedKey key, GridTier tier, RecipeKind kind, int size,
                                List<ItemStack> ingredients, List<WeightedResult> resultChoices,
                                String group, boolean enabled, boolean hidden,
                                List<IngredientMatchMode> matchModes, int priority,
                                boolean allowMirror, List<List<ItemStack>> alternatives,
                                List<NamespacedKey> itemTags, List<Boolean> consumeSlots,
                                List<ItemStack> remainders) {
        this(key, tier, kind, size, ingredients, resultChoices, group, enabled, hidden,
                matchModes, priority, allowMirror, alternatives, itemTags, consumeSlots,
                remainders, RecipeConditions.NONE);
    }

    public GridRecipeDefinition(NamespacedKey key, GridTier tier, RecipeKind kind, int size,
                                List<ItemStack> ingredients, List<WeightedResult> resultChoices,
                                String group, boolean enabled, boolean hidden,
                                List<IngredientMatchMode> matchModes, int priority,
                                boolean allowMirror, List<List<ItemStack>> alternatives,
                                List<NamespacedKey> itemTags, List<Boolean> consumeSlots,
                                List<ItemStack> remainders, RecipeConditions conditions) {
        this(key, tier, kind, size, ingredients, resultChoices, group, enabled, hidden,
                matchModes, priority, allowMirror, alternatives, itemTags, consumeSlots,
                remainders, Collections.nCopies(size * size, false), conditions);
    }

    /** Optional slots may be absent, but every recipe still needs a consumed required input. */
    public GridRecipeDefinition(NamespacedKey key, GridTier tier, RecipeKind kind, int size,
                                List<ItemStack> ingredients, List<WeightedResult> resultChoices,
                                String group, boolean enabled, boolean hidden,
                                List<IngredientMatchMode> matchModes, int priority,
                                boolean allowMirror, List<List<ItemStack>> alternatives,
                                List<NamespacedKey> itemTags, List<Boolean> consumeSlots,
                                List<ItemStack> remainders, List<Boolean> optionalSlots,
                                RecipeConditions conditions) {
        this(key, tier, kind, size, ingredients, resultChoices, group, enabled, hidden,
                matchModes, priority, allowMirror, alternatives, itemTags, consumeSlots,
                remainders, optionalSlots, conditions, null);
    }

    public GridRecipeDefinition(NamespacedKey key, GridTier tier, RecipeKind kind, int size,
                                List<ItemStack> ingredients, List<WeightedResult> resultChoices,
                                String group, boolean enabled, boolean hidden,
                                List<IngredientMatchMode> matchModes, int priority,
                                boolean allowMirror, List<List<ItemStack>> alternatives,
                                List<NamespacedKey> itemTags, List<Boolean> consumeSlots,
                                List<ItemStack> remainders, List<Boolean> optionalSlots,
                                RecipeConditions conditions, CraftSoundAction craftSound) {
        this(key, tier, kind, size, ingredients, resultChoices, group, enabled, hidden,
                matchModes, priority, allowMirror, alternatives, itemTags, consumeSlots,
                remainders, optionalSlots, conditions, craftSound, null);
    }

    public GridRecipeDefinition(NamespacedKey key, GridTier tier, RecipeKind kind, int size,
                                List<ItemStack> ingredients, List<WeightedResult> resultChoices,
                                String group, boolean enabled, boolean hidden,
                                List<IngredientMatchMode> matchModes, int priority,
                                boolean allowMirror, List<List<ItemStack>> alternatives,
                                List<NamespacedKey> itemTags, List<Boolean> consumeSlots,
                                List<ItemStack> remainders, List<Boolean> optionalSlots,
                                RecipeConditions conditions, CraftSoundAction craftSound,
                                CraftAdvancementAction craftAdvancement) {
        this(key, tier, kind, size, ingredients, resultChoices, group, enabled, hidden,
                matchModes, priority, allowMirror, alternatives, itemTags, consumeSlots,
                remainders, optionalSlots, conditions, craftSound, craftAdvancement, null);
    }

    public GridRecipeDefinition(NamespacedKey key, GridTier tier, RecipeKind kind, int size,
                                List<ItemStack> ingredients, List<WeightedResult> resultChoices,
                                String group, boolean enabled, boolean hidden,
                                List<IngredientMatchMode> matchModes, int priority,
                                boolean allowMirror, List<List<ItemStack>> alternatives,
                                List<NamespacedKey> itemTags, List<Boolean> consumeSlots,
                                List<ItemStack> remainders, List<Boolean> optionalSlots,
                                RecipeConditions conditions, CraftSoundAction craftSound,
                                CraftAdvancementAction craftAdvancement,
                                CraftCommandAction craftCommand) {
        this(key, tier, kind, size, ingredients, resultChoices, group, enabled, hidden,
                matchModes, priority, allowMirror, alternatives, itemTags, consumeSlots,
                remainders, optionalSlots, conditions, craftSound, craftAdvancement,
                craftCommand, 64);
    }

    /** Bounds the number of batches committed by one shift-click. */
    public GridRecipeDefinition(NamespacedKey key, GridTier tier, RecipeKind kind, int size,
                                List<ItemStack> ingredients, List<WeightedResult> resultChoices,
                                String group, boolean enabled, boolean hidden,
                                List<IngredientMatchMode> matchModes, int priority,
                                boolean allowMirror, List<List<ItemStack>> alternatives,
                                List<NamespacedKey> itemTags, List<Boolean> consumeSlots,
                                List<ItemStack> remainders, List<Boolean> optionalSlots,
                                RecipeConditions conditions, CraftSoundAction craftSound,
                                CraftAdvancementAction craftAdvancement,
                                CraftCommandAction craftCommand, int maxShiftBatches) {
        this(key, tier, kind, size, ingredients, resultChoices, group, enabled, hidden,
                matchModes, priority, allowMirror, alternatives, itemTags, consumeSlots,
                remainders, optionalSlots, conditions, craftSound, craftAdvancement,
                craftCommand, maxShiftBatches, 0);
    }

    /** A per-player pause after a committed click; zero disables it. */
    public GridRecipeDefinition(NamespacedKey key, GridTier tier, RecipeKind kind, int size,
                                List<ItemStack> ingredients, List<WeightedResult> resultChoices,
                                String group, boolean enabled, boolean hidden,
                                List<IngredientMatchMode> matchModes, int priority,
                                boolean allowMirror, List<List<ItemStack>> alternatives,
                                List<NamespacedKey> itemTags, List<Boolean> consumeSlots,
                                List<ItemStack> remainders, List<Boolean> optionalSlots,
                                RecipeConditions conditions, CraftSoundAction craftSound,
                                CraftAdvancementAction craftAdvancement,
                                CraftCommandAction craftCommand, int maxShiftBatches,
                                int cooldownSeconds) {
        this(key, tier, kind, size, ingredients, resultChoices, group, enabled, hidden,
                matchModes, priority, allowMirror, alternatives, itemTags, consumeSlots,
                remainders, optionalSlots, conditions, craftSound, craftAdvancement,
                craftCommand, maxShiftBatches, cooldownSeconds, -1, ResultMetadataMode.NONE);
    }

    /** Result metadata may come from one occupied recipe slot, including a translated or mirrored match. */
    public GridRecipeDefinition(NamespacedKey key, GridTier tier, RecipeKind kind, int size,
                                List<ItemStack> ingredients, List<WeightedResult> resultChoices,
                                String group, boolean enabled, boolean hidden,
                                List<IngredientMatchMode> matchModes, int priority,
                                boolean allowMirror, List<List<ItemStack>> alternatives,
                                List<NamespacedKey> itemTags, List<Boolean> consumeSlots,
                                List<ItemStack> remainders, List<Boolean> optionalSlots,
                                RecipeConditions conditions, CraftSoundAction craftSound,
                                CraftAdvancementAction craftAdvancement,
                                 CraftCommandAction craftCommand, int maxShiftBatches,
                                 int cooldownSeconds, int resultCopySourceSlot,
                                 ResultMetadataMode resultMetadataMode) {
        this(key, tier, kind, size, ingredients, resultChoices, group, enabled, hidden,
                matchModes, priority, allowMirror, alternatives, itemTags, consumeSlots,
                remainders, optionalSlots, conditions, craftSound, craftAdvancement,
                craftCommand, maxShiftBatches, cooldownSeconds, resultCopySourceSlot,
                resultMetadataMode, 0);
    }

    /** A persistent per-player lifetime craft limit; zero disables it. */
    public GridRecipeDefinition(NamespacedKey key, GridTier tier, RecipeKind kind, int size,
                                List<ItemStack> ingredients, List<WeightedResult> resultChoices,
                                String group, boolean enabled, boolean hidden,
                                List<IngredientMatchMode> matchModes, int priority,
                                boolean allowMirror, List<List<ItemStack>> alternatives,
                                List<NamespacedKey> itemTags, List<Boolean> consumeSlots,
                                List<ItemStack> remainders, List<Boolean> optionalSlots,
                                RecipeConditions conditions, CraftSoundAction craftSound,
                                CraftAdvancementAction craftAdvancement,
                                CraftCommandAction craftCommand, int maxShiftBatches,
                                int cooldownSeconds, int resultCopySourceSlot,
                                ResultMetadataMode resultMetadataMode, int maxCraftsPerPlayer) {
        this.key = Objects.requireNonNull(key, "key");
        if (!key.getNamespace().equals("recipeweaver")) {
            throw new IllegalArgumentException("Grid recipe IDs must use recipeweaver namespace");
        }
        this.tier = Objects.requireNonNull(tier, "tier");
        this.kind = Objects.requireNonNull(kind, "kind");
        if ((tier == GridTier.ADVANCED && size != 3)
                || (tier == GridTier.ELITE && (size < 2 || size > 6))) {
            throw new IllegalArgumentException("Advanced grids are 3x3; elite grids are 2x2 through 6x6");
        }
        this.size = size;
        if (ingredients == null || ingredients.size() != size * size) {
            throw new IllegalArgumentException("A " + size + "x" + size + " grid needs " + (size * size) + " slots");
        }
        if (matchModes == null || matchModes.size() != size * size
                || matchModes.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Each grid slot needs a match mode");
        }
        if (alternatives == null || alternatives.size() != size * size
                || itemTags == null || itemTags.size() != size * size) {
            throw new IllegalArgumentException("Each grid slot needs an alternative list and tag entry");
        }
        if (consumeSlots == null || consumeSlots.size() != size * size
                || consumeSlots.stream().anyMatch(Objects::isNull)
                || remainders == null || remainders.size() != size * size) {
            throw new IllegalArgumentException("Each grid slot needs a consume flag and remainder entry");
        }
        if (optionalSlots == null || optionalSlots.size() != size * size
                || optionalSlots.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Each grid slot needs an optional flag");
        }
        if (priority < -1000 || priority > 1000) {
            throw new IllegalArgumentException("Grid priority must be -1000 through 1000");
        }
        if (maxShiftBatches < 1 || maxShiftBatches > 64) {
            throw new IllegalArgumentException("Shift craft limit must be 1 through 64 batches");
        }
        if (cooldownSeconds < 0 || cooldownSeconds > 86400) {
            throw new IllegalArgumentException("Craft cooldown must be 0 through 86400 seconds");
        }
        if (maxCraftsPerPlayer < 0 || maxCraftsPerPlayer > 1_000_000) {
            throw new IllegalArgumentException("Per-player craft limit must be 0 through 1000000");
        }
        Objects.requireNonNull(resultMetadataMode, "resultMetadataMode");
        if (resultMetadataMode == ResultMetadataMode.NONE) {
            if (resultCopySourceSlot != -1) {
                throw new IllegalArgumentException("Disabled result metadata copying needs no source slot");
            }
        } else if (resultCopySourceSlot < 0 || resultCopySourceSlot >= ingredients.size()
                || ingredients.get(resultCopySourceSlot) == null
                || ingredients.get(resultCopySourceSlot).getType().isAir()) {
            throw new IllegalArgumentException("Result metadata source must be an occupied ingredient slot");
        }
        List<ItemStack> copy = new ArrayList<>(ingredients.size());
        List<List<ItemStack>> alternativeCopy = new ArrayList<>(ingredients.size());
        List<NamespacedKey> tagCopy = new ArrayList<>(ingredients.size());
        List<ItemStack> remainderCopy = new ArrayList<>(ingredients.size());
        int requiredCount = 0;
        int requiredConsumedCount = 0;
        for (int slot = 0; slot < ingredients.size(); slot++) {
            ItemStack item = ingredients.get(slot);
            List<ItemStack> choices = alternatives.get(slot);
            NamespacedKey tag = itemTags.get(slot);
            boolean consume = consumeSlots.get(slot);
            boolean optional = optionalSlots.get(slot);
            ItemStack remainder = remainders.get(slot);
            if (choices == null || choices.size() > MAX_ALTERNATIVES_PER_SLOT) {
                throw new IllegalArgumentException("A grid slot may have at most "
                        + MAX_ALTERNATIVES_PER_SLOT + " alternatives");
            }
            if (item == null || item.getType().isAir()) {
                if (!choices.isEmpty() || tag != null || !consume || remainder != null || optional) {
                    throw new IllegalArgumentException("An empty grid slot cannot have alternatives, a tag, or use options");
                }
                copy.add(null);
            } else {
                if (item.getAmount() < 1 || item.getAmount() > item.getMaxStackSize()) {
                    throw new IllegalArgumentException("Grid ingredient amount must fit one stack");
                }
                copy.add(item.clone());
                if (!optional) {
                    requiredCount++;
                    if (consume) requiredConsumedCount++;
                }
            }
            if (!consume && remainder != null) {
                throw new IllegalArgumentException("A kept ingredient cannot produce a remainder");
            }
            if (remainder != null && (remainder.getType().isAir() || remainder.getAmount() < 1
                    || remainder.getAmount() > remainder.getMaxStackSize())) {
                throw new IllegalArgumentException("A grid remainder must fit one item stack");
            }
            remainderCopy.add(remainder == null ? null : remainder.clone());
            List<ItemStack> samples = new ArrayList<>(choices.size());
            for (ItemStack choice : choices) {
                if (choice == null || choice.getType().isAir()) {
                    throw new IllegalArgumentException("Grid alternatives must be items");
                }
                ItemStack sample = choice.clone();
                sample.setAmount(1);
                samples.add(sample);
            }
            alternativeCopy.add(Collections.unmodifiableList(samples));
            tagCopy.add(tag);
        }
        if (requiredCount == 0) throw new IllegalArgumentException("A grid recipe needs a required ingredient");
        if (requiredConsumedCount == 0) {
            throw new IllegalArgumentException("A grid recipe needs a consumed required ingredient");
        }
        if (resultChoices == null || resultChoices.isEmpty() || resultChoices.size() > MAX_RESULTS
                || resultChoices.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("A grid recipe needs 1 through " + MAX_RESULTS + " results");
        }
        List<WeightedResult> outputCopy = new ArrayList<>(resultChoices.size());
        int totalWeight = 0;
        for (WeightedResult choice : resultChoices) {
            outputCopy.add(new WeightedResult(choice.item(), choice.weight()));
            totalWeight += choice.weight();
        }
        if (resultMetadataMode == ResultMetadataMode.ALL_ITEM_DATA) {
            var material = ingredients.get(resultCopySourceSlot).getType();
            if (optionalSlots.get(resultCopySourceSlot)
                    || itemTags.get(resultCopySourceSlot) != null
                    || alternatives.get(resultCopySourceSlot).stream()
                            .anyMatch(choice -> choice.getType() != material)
                    || outputCopy.stream().anyMatch(choice -> choice.item().getType() != material)) {
                throw new IllegalArgumentException("Full item data copy needs a required source material "
                        + "and results of that same material");
            }
        }
        this.ingredients = Collections.unmodifiableList(copy);
        this.matchModes = List.copyOf(matchModes);
        this.alternatives = Collections.unmodifiableList(alternativeCopy);
        this.itemTags = Collections.unmodifiableList(tagCopy);
        this.consumeSlots = List.copyOf(consumeSlots);
        this.optionalSlots = List.copyOf(optionalSlots);
        this.remainders = Collections.unmodifiableList(remainderCopy);
        this.resultChoices = List.copyOf(outputCopy);
        this.totalResultWeight = totalWeight;
        this.result = outputCopy.get(0).item();
        this.group = group == null ? "" : group;
        this.enabled = enabled;
        this.hidden = hidden;
        this.priority = priority;
        this.allowMirror = allowMirror;
        this.conditions = Objects.requireNonNull(conditions, "conditions");
        this.craftSound = craftSound;
        this.craftAdvancement = craftAdvancement;
        this.craftCommand = craftCommand;
        this.maxShiftBatches = maxShiftBatches;
        this.cooldownSeconds = cooldownSeconds;
        this.maxCraftsPerPlayer = maxCraftsPerPlayer;
        this.resultCopySourceSlot = resultCopySourceSlot;
        this.resultMetadataMode = resultMetadataMode;
    }

    public NamespacedKey key() { return key; }
    public GridTier tier() { return tier; }
    public RecipeKind kind() { return kind; }
    public int size() { return size; }
    public List<ItemStack> ingredients() {
        List<ItemStack> copy = new ArrayList<>(ingredients.size());
        for (ItemStack item : ingredients) copy.add(item == null ? null : item.clone());
        return Collections.unmodifiableList(copy);
    }
    public List<IngredientMatchMode> matchModes() { return matchModes; }
    public boolean hasIngredient(int slot) { return ingredients.get(slot) != null; }
    /** Required stack amount for an occupied slot, or zero for an empty slot. */
    public int ingredientAmount(int slot) {
        ItemStack sample = ingredients.get(slot);
        return sample == null ? 0 : sample.getAmount();
    }
    /** Checks the primary sample and explicit alternatives, without checking amount or item tag. */
    public boolean matchesItemSample(int slot, ItemStack actual) {
        ItemStack sample = ingredients.get(slot);
        if (sample == null || actual == null || actual.getType().isAir()) return false;
        IngredientMatchMode mode = matchModes.get(slot);
        if (sampleMatches(sample, mode, actual)) return true;
        for (ItemStack alternative : alternatives.get(slot)) {
            if (sampleMatches(alternative, mode, actual)) return true;
        }
        return false;
    }
    static boolean sampleMatches(ItemStack sample, IngredientMatchMode mode, ItemStack actual) {
        if (sample.getType() != actual.getType()) return false;
        return switch (mode) {
            case MATERIAL -> true;
            case EXACT -> sample.isSimilar(actual);
            case PARTIAL -> !sample.hasItemMeta() || actual.hasItemMeta()
                    && partialMetadataMatches(sample.getItemMeta(), actual.getItemMeta());
            case CUSTOM_DATA_ONLY -> customDataMatches(sample, actual);
            case NAME_ONLY -> nameFields(sample.getItemMeta())
                    .equals(nameFields(actual.getItemMeta()));
            case LORE_ONLY -> loreMatches(sample.getItemMeta(), actual.getItemMeta());
            case MODEL_ONLY -> modelFields(sample.getItemMeta())
                    .equals(modelFields(actual.getItemMeta()));
            case ENCHANTMENTS_ONLY -> enchantmentsMatch(sample.getItemMeta(), actual.getItemMeta());
            case DAMAGE_ONLY -> damageMatches(sample.getItemMeta(), actual.getItemMeta());
            case ITEM_FLAGS_ONLY -> sample.getItemMeta().getItemFlags()
                    .equals(actual.getItemMeta().getItemFlags());
            case ATTRIBUTE_MODIFIERS_ONLY -> attributeModifiersMatch(
                    sample.getItemMeta(), actual.getItemMeta());
            case UNBREAKABLE_ONLY -> sample.getItemMeta().isUnbreakable()
                    == actual.getItemMeta().isUnbreakable();
            case ARMOR_TRIM_ONLY -> armorTrimMatches(sample.getItemMeta(), actual.getItemMeta());
            case POTION_ONLY -> potionMatches(sample.getItemMeta(), actual.getItemMeta());
            case POTION_BASE_ONLY -> potionBaseMatches(sample.getItemMeta(), actual.getItemMeta());
            case POTION_COLOR_ONLY -> potionColorMatches(sample.getItemMeta(), actual.getItemMeta());
            case STORED_ENCHANTMENTS_ONLY -> storedEnchantmentsMatch(
                    sample.getItemMeta(), actual.getItemMeta());
            case BANNER_PATTERNS_ONLY -> bannerPatternsMatch(
                    sample.getItemMeta(), actual.getItemMeta());
            case CROSSBOW_PROJECTILES_ONLY -> crossbowProjectilesMatch(
                    sample.getItemMeta(), actual.getItemMeta());
            case FIREWORK_ONLY -> fireworkMatches(sample.getItemMeta(), actual.getItemMeta());
            case BUNDLE_CONTENTS_ONLY -> bundleContentsMatch(
                    sample.getItemMeta(), actual.getItemMeta());
            case FIRE_RESISTANT_ONLY -> fireResistantMatches(
                    sample.getItemMeta(), actual.getItemMeta());
            case HIDE_TOOLTIP_ONLY -> hideTooltipMatches(
                    sample.getItemMeta(), actual.getItemMeta());
            case MAX_STACK_SIZE_ONLY -> Objects.equals(
                    sample.getItemMeta().hasMaxStackSize()
                            ? sample.getItemMeta().getMaxStackSize() : null,
                    actual.getItemMeta().hasMaxStackSize()
                            ? actual.getItemMeta().getMaxStackSize() : null);
            case RARITY_ONLY -> rarityMatches(sample.getItemMeta(), actual.getItemMeta());
            case REPAIR_COST_ONLY -> repairCostMatches(
                    sample.getItemMeta(), actual.getItemMeta());
            case LEATHER_COLOR_ONLY -> leatherColorMatches(
                    sample.getItemMeta(), actual.getItemMeta());
            case SUSPICIOUS_STEW_ONLY -> stewEffectsMatch(
                    sample.getItemMeta(), actual.getItemMeta());
            case FOOD_ONLY -> componentFieldsMatch(sample.getItemMeta(), actual.getItemMeta(), "food");
            case TOOL_ONLY -> componentFieldsMatch(sample.getItemMeta(), actual.getItemMeta(), "tool");
            case WRITTEN_BOOK_ONLY -> writtenBookMatches(sample.getItemMeta(), actual.getItemMeta());
            case WRITTEN_BOOK_TITLE_ONLY -> writtenBookTitleMatches(
                    sample.getItemMeta(), actual.getItemMeta());
            case WRITTEN_BOOK_AUTHOR_ONLY -> writtenBookAuthorMatches(
                    sample.getItemMeta(), actual.getItemMeta());
            case WRITABLE_BOOK_ONLY -> writableBookMatches(sample.getItemMeta(), actual.getItemMeta());
            case COMPASS_ONLY -> compassMatches(sample.getItemMeta(), actual.getItemMeta());
            case MAP_COLOR_ONLY -> mapColorMatches(sample.getItemMeta(), actual.getItemMeta());
            case MAP_SCALING_ONLY -> mapScalingMatches(sample.getItemMeta(), actual.getItemMeta());
            case MAP_LOCATION_NAME_ONLY -> mapLocationNameMatches(sample.getItemMeta(), actual.getItemMeta());
            case TROPICAL_FISH_ONLY -> tropicalFishMatches(sample.getItemMeta(), actual.getItemMeta());
            case KNOWLEDGE_BOOK_ONLY -> knowledgeBookMatches(sample.getItemMeta(), actual.getItemMeta());
            case HEAD_SOUND_ONLY -> headSoundMatches(sample.getItemMeta(), actual.getItemMeta());
        };
    }

    private static boolean writtenBookMatches(ItemMeta sample, ItemMeta actual) {
        return sample instanceof BookMeta expected && actual instanceof BookMeta found
                && Objects.equals(expected.hasTitle() ? expected.getTitle() : null,
                        found.hasTitle() ? found.getTitle() : null)
                && Objects.equals(expected.hasAuthor() ? expected.getAuthor() : null,
                        found.hasAuthor() ? found.getAuthor() : null)
                && expected.getGeneration() == found.getGeneration()
                && expected.getPages().equals(found.getPages());
    }

    private static boolean writtenBookTitleMatches(ItemMeta sample, ItemMeta actual) {
        return sample instanceof BookMeta expected && actual instanceof BookMeta found
                && Objects.equals(expected.hasTitle() ? expected.getTitle() : null,
                        found.hasTitle() ? found.getTitle() : null);
    }

    private static boolean writtenBookAuthorMatches(ItemMeta sample, ItemMeta actual) {
        return sample instanceof BookMeta expected && actual instanceof BookMeta found
                && Objects.equals(expected.hasAuthor() ? expected.getAuthor() : null,
                        found.hasAuthor() ? found.getAuthor() : null);
    }

    private static boolean writableBookMatches(ItemMeta sample, ItemMeta actual) {
        return sample instanceof WritableBookMeta expected && actual instanceof WritableBookMeta found
                && expected.getPages().equals(found.getPages());
    }

    private static boolean compassMatches(ItemMeta sample, ItemMeta actual) {
        return sample instanceof CompassMeta expected && actual instanceof CompassMeta found
                && Objects.equals(expected.hasLodestone() ? expected.getLodestone() : null,
                        found.hasLodestone() ? found.getLodestone() : null)
                && expected.isLodestoneTracked() == found.isLodestoneTracked();
    }

    private static boolean mapColorMatches(ItemMeta sample, ItemMeta actual) {
        return sample instanceof MapMeta expected && actual instanceof MapMeta found
                && Objects.equals(expected.hasColor() ? expected.getColor() : null,
                        found.hasColor() ? found.getColor() : null);
    }

    private static boolean mapScalingMatches(ItemMeta sample, ItemMeta actual) {
        return sample instanceof MapMeta expected && actual instanceof MapMeta found
                && expected.isScaling() == found.isScaling();
    }

    private static boolean mapLocationNameMatches(ItemMeta sample, ItemMeta actual) {
        return sample instanceof MapMeta && actual instanceof MapMeta
                && Objects.equals(MapLocationLabel.read(sample), MapLocationLabel.read(actual));
    }

    private static boolean tropicalFishMatches(ItemMeta sample, ItemMeta actual) {
        return sample instanceof TropicalFishBucketMeta expected
                && actual instanceof TropicalFishBucketMeta found
                && expected.getBodyColor() == found.getBodyColor()
                && expected.getPatternColor() == found.getPatternColor()
                && expected.getPattern() == found.getPattern();
    }

    private static boolean knowledgeBookMatches(ItemMeta sample, ItemMeta actual) {
        return sample instanceof KnowledgeBookMeta expected
                && actual instanceof KnowledgeBookMeta found
                && expected.getRecipes().equals(found.getRecipes());
    }

    private static boolean headSoundMatches(ItemMeta sample, ItemMeta actual) {
        return sample instanceof SkullMeta expected && actual instanceof SkullMeta found
                && Objects.equals(expected.getNoteBlockSound(), found.getNoteBlockSound());
    }

    private static boolean repairCostMatches(ItemMeta sample, ItemMeta actual) {
        return sample instanceof Repairable expected && actual instanceof Repairable found
                && expected.getRepairCost() == found.getRepairCost();
    }

    public static boolean fireResistantMatches(ItemMeta sample, ItemMeta actual) {
        return sample.isFireResistant() == actual.isFireResistant();
    }

    public static boolean hideTooltipMatches(ItemMeta sample, ItemMeta actual) {
        return sample.isHideTooltip() == actual.isHideTooltip();
    }

    public static boolean rarityMatches(ItemMeta sample, ItemMeta actual) {
        return Objects.equals(sample.hasRarity() ? sample.getRarity() : null,
                actual.hasRarity() ? actual.getRarity() : null);
    }

    private static boolean leatherColorMatches(ItemMeta sample, ItemMeta actual) {
        return sample instanceof LeatherArmorMeta expected
                && actual instanceof LeatherArmorMeta found
                && expected.getColor().equals(found.getColor());
    }

    private static boolean stewEffectsMatch(ItemMeta sample, ItemMeta actual) {
        return sample instanceof SuspiciousStewMeta expected
                && actual instanceof SuspiciousStewMeta found
                && expected.getCustomEffects().equals(found.getCustomEffects());
    }

    /** Exact comparison of one serialized public component, excluding unrelated item fields. */
    public static boolean componentFieldsMatch(ItemMeta sample, ItemMeta actual, String field) {
        Map<String, Object> expected = selectedFields(sample, field);
        Map<String, Object> found = selectedFields(actual, field);
        return metadataContains(expected, found) && metadataContains(found, expected);
    }

    private static Map<String, Object> nameFields(ItemMeta meta) {
        return selectedFields(meta, "display-name", "item-name");
    }

    private static boolean loreMatches(ItemMeta sample, ItemMeta actual) {
        return Objects.equals(sample.hasLore() ? sample.getLore() : null,
                actual.hasLore() ? actual.getLore() : null);
    }

    private static Map<String, Object> modelFields(ItemMeta meta) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (var entry : meta.serialize().entrySet()) {
            String key = normalizedField(entry.getKey());
            if (key.startsWith("custom-model-data") || key.equals("item-model")) {
                fields.put(key, entry.getValue());
            }
        }
        return fields;
    }

    private static boolean enchantmentsMatch(ItemMeta sample, ItemMeta actual) {
        return sample.getEnchants().equals(actual.getEnchants())
                && selectedFields(sample, "enchantment-glint-override")
                        .equals(selectedFields(actual, "enchantment-glint-override"));
    }

    private static boolean damageMatches(ItemMeta sample, ItemMeta actual) {
        if (!(sample instanceof Damageable expected)) return !(actual instanceof Damageable);
        if (!(actual instanceof Damageable found)) return false;
        return expected.getDamage() == found.getDamage()
                && Objects.equals(expected.hasMaxDamage() ? expected.getMaxDamage() : null,
                        found.hasMaxDamage() ? found.getMaxDamage() : null);
    }

    private static boolean attributeModifiersMatch(ItemMeta sample, ItemMeta actual) {
        return Objects.equals(sample.hasAttributeModifiers()
                        ? sample.getAttributeModifiers() : null,
                actual.hasAttributeModifiers() ? actual.getAttributeModifiers() : null);
    }

    /** Compares the trim field of two armor metadata values, excluding other item fields. */
    public static boolean armorTrimMatches(ItemMeta sample, ItemMeta actual) {
        if (!(sample instanceof org.bukkit.inventory.meta.ArmorMeta expected)
                || !(actual instanceof org.bukkit.inventory.meta.ArmorMeta found)) return false;
        return Objects.equals(expected.hasTrim() ? expected.getTrim() : null,
                found.hasTrim() ? found.getTrim() : null);
    }

    /** Compares the effective potion base, optional color, and ordered custom effects. */
    public static boolean potionMatches(ItemMeta sample, ItemMeta actual) {
        if (!(sample instanceof PotionMeta expected) || !(actual instanceof PotionMeta found)) {
            return false;
        }
        PotionType expectedBase = expected.hasBasePotionType()
                ? expected.getBasePotionType() : PotionType.WATER;
        PotionType foundBase = found.hasBasePotionType()
                ? found.getBasePotionType() : PotionType.WATER;
        return expectedBase == foundBase
                && Objects.equals(expected.hasColor() ? expected.getColor() : null,
                        found.hasColor() ? found.getColor() : null)
                && expected.getCustomEffects().equals(found.getCustomEffects());
    }

    public static boolean potionBaseMatches(ItemMeta sample, ItemMeta actual) {
        return sample instanceof PotionMeta expected && actual instanceof PotionMeta found
                && (expected.hasBasePotionType() ? expected.getBasePotionType() : PotionType.WATER)
                        == (found.hasBasePotionType() ? found.getBasePotionType() : PotionType.WATER);
    }

    private static boolean potionColorMatches(ItemMeta sample, ItemMeta actual) {
        return sample instanceof PotionMeta expected && actual instanceof PotionMeta found
                && Objects.equals(expected.hasColor() ? expected.getColor() : null,
                        found.hasColor() ? found.getColor() : null);
    }

    private static boolean storedEnchantmentsMatch(ItemMeta sample, ItemMeta actual) {
        return sample instanceof EnchantmentStorageMeta expected
                && actual instanceof EnchantmentStorageMeta found
                && expected.getStoredEnchants().equals(found.getStoredEnchants());
    }

    private static boolean bannerPatternsMatch(ItemMeta sample, ItemMeta actual) {
        return sample instanceof BannerMeta expected && actual instanceof BannerMeta found
                && expected.getPatterns().equals(found.getPatterns());
    }

    private static boolean crossbowProjectilesMatch(ItemMeta sample, ItemMeta actual) {
        return sample instanceof CrossbowMeta expected && actual instanceof CrossbowMeta found
                && expected.getChargedProjectiles().equals(found.getChargedProjectiles());
    }

    /** Compares rocket flight/effects or a star's explosion effect, excluding other item fields. */
    public static boolean fireworkMatches(ItemMeta sample, ItemMeta actual) {
        if (sample instanceof FireworkMeta expected && actual instanceof FireworkMeta found) {
            return expected.getPower() == found.getPower()
                    && expected.getEffects().equals(found.getEffects());
        }
        return sample instanceof FireworkEffectMeta expected
                && actual instanceof FireworkEffectMeta found
                && Objects.equals(expected.hasEffect() ? expected.getEffect() : null,
                        found.hasEffect() ? found.getEffect() : null);
    }

    private static boolean bundleContentsMatch(ItemMeta sample, ItemMeta actual) {
        return sample instanceof BundleMeta expected && actual instanceof BundleMeta found
                && expected.getItems().equals(found.getItems());
    }

    private static Map<String, Object> selectedFields(ItemMeta meta, String... names) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (var entry : meta.serialize().entrySet()) {
            String key = normalizedField(entry.getKey());
            for (String name : names) {
                if (name.equals(key)) fields.put(key, entry.getValue());
            }
        }
        return fields;
    }

    private static String normalizedField(String key) {
        return key.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static boolean customDataMatches(ItemStack sample, ItemStack actual) {
        if (!sample.hasItemMeta()) return true;
        PersistentDataContainer expected = sample.getItemMeta().getPersistentDataContainer();
        if (expected.getKeys().isEmpty()) return true;
        return actual.hasItemMeta()
                && persistentDataContains(expected,
                        actual.getItemMeta().getPersistentDataContainer());
    }

    private static boolean partialMetadataMatches(ItemMeta sample, ItemMeta actual) {
        Map<String, Object> sampleFields = new LinkedHashMap<>(sample.serialize());
        Map<String, Object> actualFields = new LinkedHashMap<>(actual.serialize());
        // Bukkit writes every persistent-data key into one serialized value. Compare its keys separately.
        sampleFields.remove("PublicBukkitValues");
        actualFields.remove("PublicBukkitValues");
        // Its serialized attribute multimap is all-or-nothing. Match the sample's
        // entries through ItemMeta so unrelated modifiers may be present.
        sampleFields.remove("attribute-modifiers");
        actualFields.remove("attribute-modifiers");
        // ItemFlags is serialized as a whole list; PARTIAL requires only the sample's flags.
        sampleFields.remove("ItemFlags");
        actualFields.remove("ItemFlags");
        return metadataContains(sampleFields, actualFields)
                && persistentDataContains(sample.getPersistentDataContainer(),
                        actual.getPersistentDataContainer())
                && attributeModifiersContain(sample, actual)
                && actual.getItemFlags().containsAll(sample.getItemFlags());
    }

    private static boolean attributeModifiersContain(ItemMeta sample, ItemMeta actual) {
        if (!sample.hasAttributeModifiers()) return true;
        if (!actual.hasAttributeModifiers()) return false;
        for (var entry : sample.getAttributeModifiers().entries()) {
            var matching = actual.getAttributeModifiers(entry.getKey());
            if (matching == null || !matching.contains(entry.getValue())) return false;
        }
        return true;
    }

    /** A sample constrains only its serialized metadata keys; nested components keep their own keys. */
    private static boolean metadataContains(Object expected, Object actual) {
        if (expected instanceof PersistentDataContainer expectedData) {
            return actual instanceof PersistentDataContainer actualData
                    && persistentDataContains(expectedData, actualData);
        }
        if (expected instanceof Map<?, ?> expectedMap) {
            if (!(actual instanceof Map<?, ?> actualMap)) return false;
            for (var entry : expectedMap.entrySet()) {
                if (!actualMap.containsKey(entry.getKey())
                        || !metadataContains(entry.getValue(), actualMap.get(entry.getKey()))) return false;
            }
            return true;
        }
        if (expected instanceof java.util.List<?> expectedList) {
            if (!(actual instanceof java.util.List<?> actualList)
                    || expectedList.size() != actualList.size()) return false;
            for (int index = 0; index < expectedList.size(); index++) {
                if (!metadataContains(expectedList.get(index), actualList.get(index))) return false;
            }
            return true;
        }
        if (expected instanceof Object[] expectedArray) {
            if (!(actual instanceof Object[] actualArray)
                    || expectedArray.length != actualArray.length) return false;
            for (int index = 0; index < expectedArray.length; index++) {
                if (!metadataContains(expectedArray[index], actualArray[index])) return false;
            }
            return true;
        }
        return java.util.Objects.deepEquals(expected, actual);
    }

    private static boolean persistentDataContains(PersistentDataContainer sample,
                                                  PersistentDataContainer actual) {
        for (NamespacedKey key : sample.getKeys()) {
            boolean matchedType = false;
            for (PersistentDataType<?, ?> type : PERSISTENT_TYPES) {
                if (!sample.has(key, type)) continue;
                Object expected;
                try { expected = sample.get(key, type); }
                catch (IllegalArgumentException | ClassCastException ex) { continue; }
                matchedType = true;
                if (!actual.has(key, type)) return false;
                Object found;
                try { found = actual.get(key, type); }
                catch (IllegalArgumentException | ClassCastException ex) { return false; }
                if (!metadataContains(expected, found)) return false;
                break;
            }
            if (!matchedType) return false;
        }
        return true;
    }
    /** Each list contains item samples; the primary ingredient controls the required amount. */
    public List<List<ItemStack>> alternatives() {
        List<List<ItemStack>> copy = new ArrayList<>(alternatives.size());
        for (List<ItemStack> choices : alternatives) {
            List<ItemStack> samples = new ArrayList<>(choices.size());
            for (ItemStack item : choices) samples.add(item.clone());
            copy.add(Collections.unmodifiableList(samples));
        }
        return Collections.unmodifiableList(copy);
    }
    /** A null entry means the slot has no Bukkit item tag alternative. */
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
    public ItemStack result() { return result.clone(); }
    public List<WeightedResult> resultChoices() { return resultChoices; }
    public int totalResultWeight() { return totalResultWeight; }
    /** Ticket is zero based, in the range [0, totalResultWeight). */
    public ItemStack resultForTicket(int ticket) {
        if (ticket < 0 || ticket >= totalResultWeight) throw new IllegalArgumentException("Invalid result ticket");
        int offset = ticket;
        for (WeightedResult choice : resultChoices) {
            if (offset < choice.weight()) return choice.item();
            offset -= choice.weight();
        }
        throw new IllegalStateException("Result weights are inconsistent");
    }
    public String group() { return group; }
    public boolean enabled() { return enabled; }
    public boolean hidden() { return hidden; }
    /** Higher priority wins when multiple enabled custom grid recipes match. */
    public int priority() { return priority; }
    /** Whether a shaped pattern may also match horizontally mirrored. */
    public boolean allowMirror() { return allowMirror; }
    public RecipeConditions conditions() { return conditions; }
    public CraftSoundAction craftSound() { return craftSound; }
    public CraftAdvancementAction craftAdvancement() { return craftAdvancement; }
    public CraftCommandAction craftCommand() { return craftCommand; }
    public int maxShiftBatches() { return maxShiftBatches; }
    public int cooldownSeconds() { return cooldownSeconds; }
    public int maxCraftsPerPlayer() { return maxCraftsPerPlayer; }
    public int resultCopySourceSlot() { return resultCopySourceSlot; }
    public ResultMetadataMode resultMetadataMode() { return resultMetadataMode; }
}
