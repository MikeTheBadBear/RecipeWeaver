package dev.recipeweaver.api;

/** Which data a custom grid or owned station result inherits from one matched ingredient. */
public enum ResultMetadataMode {
    NONE(false, false, false),
    NAME(true, false, false),
    LORE(false, true, false),
    NAME_AND_LORE(true, true, false),
    NAME_LORE_CUSTOM_DATA(true, true, true),
    ALL_COMMON(true, true, true),
    /** Clones all source item data, including components, when source and output have the same material. */
    ALL_ITEM_DATA(true, true, true),
    /** Replaces only the output's persistent custom-data keys. */
    CUSTOM_DATA_ONLY(false, false, true),
    /** Replaces custom model data and the modern item-model key, preserving other output data. */
    MODEL_ONLY(false, false, false),
    /** Replaces enchantments and the glint override, preserving other output data. */
    ENCHANTMENTS_ONLY(false, false, false),
    /** Replaces only the food component, preserving other output data. */
    FOOD_ONLY(false, false, false),
    /** Replaces only the tool component and its rules, preserving other output data. */
    TOOL_ONLY(false, false, false),
    /** Replaces only attribute modifiers, preserving other output data. */
    ATTRIBUTE_MODIFIERS_ONLY(false, false, false),
    /** Replaces damage and custom maximum durability, bounded by the output material. */
    DAMAGE_ONLY(false, false, false),
    /** Replaces only item flags, preserving other output data. */
    ITEM_FLAGS_ONLY(false, false, false),
    /** Replaces only the unbreakable component, preserving other output data. */
    UNBREAKABLE_ONLY(false, false, false),
    /** Replaces the armor trim on compatible armor outputs, preserving other output data. */
    ARMOR_TRIM_ONLY(false, false, false),
    /** Replaces potion base, color, and custom effects on potion outputs. */
    POTION_ONLY(false, false, false),
    /** Replaces only the effective potion base type on potion outputs. */
    POTION_BASE_ONLY(false, false, false),
    /** Replaces or clears only the explicit potion color. */
    POTION_COLOR_ONLY(false, false, false),
    /** Replaces stored enchantments on enchanted-book outputs. */
    STORED_ENCHANTMENTS_ONLY(false, false, false),
    /** Replaces ordered banner patterns on banner outputs. */
    BANNER_PATTERNS_ONLY(false, false, false),
    /** Replaces charged projectiles on crossbow outputs without changing their other data. */
    CROSSBOW_PROJECTILES_ONLY(false, false, false),
    /** Replaces a rocket's flight/effects or a star's effect on compatible outputs. */
    FIREWORK_ONLY(false, false, false),
    /** Replaces nested bundle items on bundle outputs, preserving other output data. */
    BUNDLE_CONTENTS_ONLY(false, false, false),
    /** Replaces only the fire-resistant item property. */
    FIRE_RESISTANT_ONLY(false, false, false),
    /** Replaces only the hide-tooltip item property. */
    HIDE_TOOLTIP_ONLY(false, false, false),
    /** Replaces or clears the explicit maximum stack size. */
    MAX_STACK_SIZE_ONLY(false, false, false),
    /** Replaces or clears the explicit rarity. */
    RARITY_ONLY(false, false, false),
    /** Replaces the repair-work penalty on repairable results. */
    REPAIR_COST_ONLY(false, false, false),
    /** Replaces the dye color on leather armor results. */
    LEATHER_COLOR_ONLY(false, false, false),
    /** Replaces the ordered effects on suspicious-stew results. */
    SUSPICIOUS_STEW_ONLY(false, false, false),
    /** Replaces signed-book title, author, pages, and generation on a compatible result. */
    WRITTEN_BOOK_ONLY(false, false, false),
    /** Replaces or clears only the title of a signed-book result. */
    WRITTEN_BOOK_TITLE_ONLY(false, false, false),
    /** Replaces or clears only the author of a signed-book result. */
    WRITTEN_BOOK_AUTHOR_ONLY(false, false, false),
    /** Replaces writable-book pages on a compatible result. */
    WRITABLE_BOOK_ONLY(false, false, false),
    /** Replaces compass lodestone and tracking state on a compatible result. */
    COMPASS_ONLY(false, false, false),
    /** Replaces or clears only the explicit map color. */
    MAP_COLOR_ONLY(false, false, false),
    /** Replaces only the map scaling state. */
    MAP_SCALING_ONLY(false, false, false),
    /** Replaces or clears only the map location label. */
    MAP_LOCATION_NAME_ONLY(false, false, false),
    /** Replaces the tropical-fish bucket's colors and pattern. */
    TROPICAL_FISH_ONLY(false, false, false),
    /** Replaces or clears only knowledge-book recipe keys. */
    KNOWLEDGE_BOOK_ONLY(false, false, false),
    /** Replaces or clears only the head's note-block sound. */
    HEAD_SOUND_ONLY(false, false, false);

    private final boolean name;
    private final boolean lore;
    private final boolean customData;

    ResultMetadataMode(boolean name, boolean lore, boolean customData) {
        this.name = name;
        this.lore = lore;
        this.customData = customData;
    }

    public boolean copyName() { return name; }
    public boolean copyLore() { return lore; }
    public boolean copyCustomData() { return customData; }
    public boolean copyModel() { return this == ALL_COMMON || this == MODEL_ONLY; }
    public boolean copyEnchantments() { return this == ALL_COMMON || this == ENCHANTMENTS_ONLY; }
    /** Also copies enchants, flags, unbreakable state, model data, generic item components, and compatible damage. */
    public boolean copyCommonProperties() { return this == ALL_COMMON; }
}
