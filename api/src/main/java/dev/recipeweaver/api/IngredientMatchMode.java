package dev.recipeweaver.api;

/** How an ingredient sample is compared with a stack. Stack amount is checked separately. */
public enum IngredientMatchMode {
    /** Material and all item metadata must match. Stack amount is checked separately. */
    EXACT,
    /** Material and metadata recorded on the sample must match; additional metadata is allowed. */
    PARTIAL,
    /** Material and the sample's typed persistent-data keys must match; other item data is ignored. */
    CUSTOM_DATA_ONLY,
    /** Material and both display/item name fields must match exactly; other item data is ignored. */
    NAME_ONLY,
    /** Material and lore must match exactly; other item data is ignored. */
    LORE_ONLY,
    /** Material and model-related components must match exactly; other item data is ignored. */
    MODEL_ONLY,
    /** Material, enchantments, and glint override must match exactly; other item data is ignored. */
    ENCHANTMENTS_ONLY,
    /** Material, damage, and custom maximum durability must match; other item data is ignored. */
    DAMAGE_ONLY,
    /** Material and the complete item-flag set must match; other item data is ignored. */
    ITEM_FLAGS_ONLY,
    /** Material and the complete explicit attribute-modifier set must match. */
    ATTRIBUTE_MODIFIERS_ONLY,
    /** Material and the unbreakable component must match; other item data is ignored. */
    UNBREAKABLE_ONLY,
    /** Material and armor trim must match; only armor items can satisfy this selector. */
    ARMOR_TRIM_ONLY,
    /** Material and effective potion base, color, and custom effects must match. */
    POTION_ONLY,
    /** Material and the effective potion base type must match; color and effects are ignored. */
    POTION_BASE_ONLY,
    /** Material and the explicit potion color must match; base type and effects are ignored. */
    POTION_COLOR_ONLY,
    /** Material and stored book enchantments must match. */
    STORED_ENCHANTMENTS_ONLY,
    /** Material and the ordered banner pattern list must match. */
    BANNER_PATTERNS_ONLY,
    /** Material and the complete charged crossbow projectile list must match. */
    CROSSBOW_PROJECTILES_ONLY,
    /** Material and the rocket or star firework payload must match. */
    FIREWORK_ONLY,
    /** Material and the ordered nested contents of a bundle must match. */
    BUNDLE_CONTENTS_ONLY,
    /** Material and the fire-resistant item property must match. */
    FIRE_RESISTANT_ONLY,
    /** Material and the hide-tooltip item property must match. */
    HIDE_TOOLTIP_ONLY,
    /** Material and the explicit maximum stack size must match. */
    MAX_STACK_SIZE_ONLY,
    /** Material and the explicit rarity must match. */
    RARITY_ONLY,
    /** Material and the repair-work penalty must match. */
    REPAIR_COST_ONLY,
    /** Material and the leather dye color must match. */
    LEATHER_COLOR_ONLY,
    /** Material and the ordered suspicious-stew effects must match. */
    SUSPICIOUS_STEW_ONLY,
    /** Material and the food component must match. */
    FOOD_ONLY,
    /** Material and the tool component must match. */
    TOOL_ONLY,
    /** Material and signed-book title, author, pages, and generation must match. */
    WRITTEN_BOOK_ONLY,
    /** Material and signed-book title must match; author and pages are ignored. */
    WRITTEN_BOOK_TITLE_ONLY,
    /** Material and signed-book author must match; title and pages are ignored. */
    WRITTEN_BOOK_AUTHOR_ONLY,
    /** Material and writable-book pages must match. */
    WRITABLE_BOOK_ONLY,
    /** Material, compass lodestone, and tracking state must match. */
    COMPASS_ONLY,
    /** Material and the explicit map color must match. */
    MAP_COLOR_ONLY,
    /** Material and map scaling state must match. */
    MAP_SCALING_ONLY,
    /** Material and the map location label must match. */
    MAP_LOCATION_NAME_ONLY,
    /** Material and all tropical-fish bucket variant fields must match. */
    TROPICAL_FISH_ONLY,
    /** Material and the ordered knowledge-book recipe keys must match. */
    KNOWLEDGE_BOOK_ONLY,
    /** Material and the head's note-block sound must match. */
    HEAD_SOUND_ONLY,
    /** Only the material must match. Stack amount is checked separately. */
    MATERIAL
}
