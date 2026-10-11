package dev.recipeweaver.smoke;

import dev.recipeweaver.api.RecipeWeaverApi;
import dev.recipeweaver.api.GridRecipeDefinition;
import dev.recipeweaver.api.GridTier;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeKind;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.MusicInstrument;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.BlockFace;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Axolotl;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.AxolotlBucketMeta;
import org.bukkit.inventory.meta.BlockDataMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.KnowledgeBookMeta;
import org.bukkit.inventory.meta.MusicInstrumentMeta;
import org.bukkit.inventory.meta.OminousBottleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.WritableBookMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.persistence.PersistentDataContainer;

/** Exercises each advanced in-game item edit through durable item save and reload. */
final class AdvancedItemSmoke {
    private static final NamespacedKey AXOLOTL = key("smoke_advanced_axolotl");
    private static final NamespacedKey KNOWLEDGE = key("smoke_advanced_knowledge");
    private static final NamespacedKey HORN = key("smoke_advanced_horn");
    private static final NamespacedKey BOTTLE = key("smoke_advanced_bottle");
    private static final NamespacedKey BUNDLE = key("smoke_advanced_bundle");
    private static final NamespacedKey BUNDLE_EDITED = key("smoke_advanced_bundle_slots");
    private static final NamespacedKey REPAIR = key("smoke_advanced_repair");
    private static final NamespacedKey BOOK = key("smoke_advanced_book");
    private static final NamespacedKey BLOCK = key("smoke_advanced_block");
    private static final NamespacedKey NAME = key("smoke_advanced_name");
    private static final NamespacedKey LISTS = key("smoke_advanced_lists");
    private static final NamespacedKey MODEL = key("smoke_advanced_item_model");
    private static final NamespacedKey STYLE = key("smoke_advanced_tooltip_style");
    private static final NamespacedKey ENCHANTABLE = key("smoke_advanced_enchantable");
    private static final NamespacedKey ATTRIBUTE = key("smoke_advanced_attribute");
    private static final NamespacedKey COMPOUND = key("smoke_advanced_compound");
    private static final NamespacedKey TEXT_IMPORT = key("smoke_advanced_text_import");
    private static final NamespacedKey RICH_MODEL = key("smoke_advanced_rich_model");
    private static final NamespacedKey USE_REMAINDER = key("smoke_advanced_use_remainder");
    private static final NamespacedKey USE_COOLDOWN = key("smoke_advanced_use_cooldown");
    private static final NamespacedKey EQUIPPABLE = key("smoke_advanced_equippable");
    private static final NamespacedKey GLIDER = key("smoke_advanced_glider");
    private static final NamespacedKey TRADE_COST = key("smoke_advanced_trade_cost");
    private static final NamespacedKey ATTACK_CHARGE = key("smoke_advanced_attack_charge");
    private static final NamespacedKey BREAK_SOUND = key("smoke_advanced_break_sound");
    private static final NamespacedKey JUKEBOX = key("smoke_advanced_jukebox");
    private static final NamespacedKey DAMAGE_RESISTANCE = key("smoke_advanced_damage_resistance");
    private static final NamespacedKey WRITABLE_PAGES = key("smoke_advanced_writable_pages");
    private static final NamespacedKey HEAD_PROFILE = key("smoke_advanced_head_profile");
    private static final NamespacedKey HEAD_NOTE_SOUND = key("smoke_advanced_head_note_sound");
    private static final NamespacedKey SHULKER_CONTENTS = key("smoke_advanced_shulker_contents");
    private static final NamespacedKey GROUPED_TOOL_RULE = key("smoke_advanced_grouped_tool_rule");
    private static final String HEAD_SKIN_HASH = "a".repeat(64);
    private static final String TEXT_IMPORT_VALUE =
            "minecraft:paper[minecraft:custom_data={recipeweaver:{level:7}}]";
    private static final String REMAINDER_VALUE =
            "minecraft:bucket[minecraft:custom_data={recipeweaver:{rank:7}}]";
    private static final NamespacedKey MODEL_VALUE = key("smoke_model");
    private static final NamespacedKey STYLE_VALUE = key("smoke_style");
    private static final NamespacedKey PICKAXE = NamespacedKey.minecraft("stone_pickaxe");

    private AdvancedItemSmoke() { }

    static void verify(RecipeWeaverApi api, Plugin harness) {
        ItemStack axolotl = new ItemStack(Material.AXOLOTL_BUCKET);
        edit("setAxolotlVariant", axolotl, "BLUE");
        require(axolotlSaved(axolotl), "Axolotl variant edit failed");
        reject("setAxolotlVariant", axolotl, "PURPLE");
        var namedBucket = axolotl.getItemMeta();
        namedBucket.setDisplayName("Tagged bucket");
        axolotl.setItemMeta(namedBucket);
        ItemStack clearedAxolotl = axolotl.clone();
        edit("setAxolotlVariant", clearedAxolotl, "none");
        require(!((AxolotlBucketMeta) clearedAxolotl.getItemMeta()).hasVariant(),
                "Axolotl variant could not be cleared");
        require("Tagged bucket".equals(clearedAxolotl.getItemMeta().getDisplayName()),
                "Clearing axolotl variant discarded other item metadata");

        ItemStack knowledge = new ItemStack(Material.KNOWLEDGE_BOOK);
        edit("editKnowledgeRecipes", knowledge, PICKAXE.toString());
        require(knowledgeSaved(knowledge), "Knowledge book recipe edit failed");
        reject("editKnowledgeRecipes", knowledge, "invalid key!");
        ItemStack clearedKnowledge = knowledge.clone();
        edit("editKnowledgeRecipes", clearedKnowledge, "remove " + PICKAXE);
        require(((KnowledgeBookMeta) clearedKnowledge.getItemMeta()).getRecipes().isEmpty(),
                "Knowledge book recipe could not be removed");

        ItemStack horn = new ItemStack(Material.GOAT_HORN);
        edit("setGoatHornInstrument", horn, "DREAM");
        require(hornSaved(horn), "Goat horn instrument edit failed");
        reject("setGoatHornInstrument", horn, "INVALID");

        ItemStack bottle = new ItemStack(Material.OMINOUS_BOTTLE);
        edit("setOminousBottleLevel", bottle, "4");
        require(bottleSaved(bottle), "Ominous bottle level edit failed");
        reject("setOminousBottleLevel", bottle, "5");

        ItemStack bundle = new ItemStack(Material.BUNDLE);
        edit("setBundleContents", bundle, "DIAMOND 2");
        require(bundleSaved(bundle), "Bundle contents edit failed");
        reject("setBundleContents", bundle, "DIAMOND 65");
        ItemStack clearedBundle = bundle.clone();
        edit("setBundleContents", clearedBundle, "none");
        require(((BundleMeta) clearedBundle.getItemMeta()).getItems().isEmpty(),
                "Bundle contents could not be cleared");

        ItemStack repair = new ItemStack(Material.DIAMOND_SWORD);
        edit("setRepairCost", repair, "7");
        require(repairSaved(repair), "Repair cost edit failed");
        reject("setRepairCost", repair, "-1");
        ItemStack clearedRepair = repair.clone();
        edit("setRepairCost", clearedRepair, "none");
        require(((Repairable) clearedRepair.getItemMeta()).getRepairCost() == 0,
                "Repair cost could not be cleared");

        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta bookMeta = (BookMeta) book.getItemMeta();
        bookMeta.setTitle("RecipeWeaver");
        bookMeta.setAuthor("TM Game Studio");
        bookMeta.setPages("Advanced item smoke");
        book.setItemMeta(bookMeta);
        edit("setBookGeneration", book, "COPY_OF_ORIGINAL");
        require(bookSaved(book), "Written book generation edit failed");
        reject("setBookGeneration", book, "UNKNOWN");

        ItemStack block = new ItemStack(Material.OAK_STAIRS);
        edit("setBlockItemState", block, "facing=east,half=top");
        require(blockSaved(block), "Block-item state edit failed");
        reject("setBlockItemState", block, "facing=up");
        ItemStack clearedBlock = block.clone();
        edit("setBlockItemState", clearedBlock, "none");
        require(!((BlockDataMeta) clearedBlock.getItemMeta()).hasBlockData(),
                "Block-item state could not be cleared");

        ItemStack name = new ItemStack(Material.PAPER);
        edit("setItemName", name, "Permanent Name");
        require(nameSaved(name), "Non-italic item name edit failed");
        reject("setItemName", name, "x".repeat(81));
        ItemStack clearedName = name.clone();
        edit("setItemName", clearedName, "none");
        require(!clearedName.getItemMeta().hasItemName(), "Item name could not be cleared");

        ItemStack lists = new ItemStack(Material.PAPER);
        edit("setPersistentData", lists, "recipeweaver:labels\n@strings alpha\nbeta");
        edit("setPersistentData", lists, "recipeweaver:counts\n@intlist -2, 0, 19");
        edit("setPersistentData", lists, "recipeweaver:large\n@longlist 1, 9223372036854775807");
        require(listsSaved(lists), "Typed persistent-data list edits failed");
        reject("setPersistentData", lists, "recipeweaver:counts\n@intlist 1, invalid");
        require(listsSaved(lists), "Rejected list edit changed the item");

        api.saveCustomItem(AXOLOTL, axolotl);
        api.saveCustomItem(KNOWLEDGE, knowledge);
        api.saveCustomItem(HORN, horn);
        api.saveCustomItem(BOTTLE, bottle);
        api.saveCustomItem(BUNDLE, bundle);
        api.saveCustomItem(REPAIR, repair);
        api.saveCustomItem(BOOK, book);
        api.saveCustomItem(BLOCK, block);
        api.saveCustomItem(NAME, name);
        api.saveCustomItem(LISTS, lists);
        SmokePlugin.reloadForLegacyAssertions(api);
        checkRecovered(api);
        for (String marker : List.of("AXOLOTL", "KNOWLEDGE_BOOK", "GOAT_HORN",
                "OMINOUS_BOTTLE", "BUNDLE", "REPAIR_COST", "BOOK_GENERATION",
                "BLOCK_ITEM_STATE", "ITEM_NAME", "PERSISTENT_LISTS")) {
            harness.getLogger().info("RW_EDIT_" + marker + "_PASS");
        }
    }

    /** Runs after the baseline item smoke on both legacy and newer servers. */
    static void verifyModernComponents(RecipeWeaverApi api, Plugin harness) {
        ItemStack model = new ItemStack(Material.PAPER);
        ItemStack style = new ItemStack(Material.PAPER);
        ItemStack enchantable = new ItemStack(Material.PAPER);
        boolean modelSupported = supports("setItemModel", NamespacedKey.class);
        boolean styleSupported = supports("setTooltipStyle", NamespacedKey.class);
        boolean enchantableSupported = supports("setEnchantable", Integer.class);

        if (modelSupported) {
            edit("setItemModel", model, "recipeweaver:smoke_model");
            require(MODEL_VALUE.equals(component(model, "ItemModel")), "Item model edit failed");
            api.saveCustomItem(MODEL, model);
        } else reject("setItemModel", model, "recipeweaver:smoke_model");
        if (styleSupported) {
            edit("setTooltipStyle", style, "recipeweaver:smoke_style");
            require(STYLE_VALUE.equals(component(style, "TooltipStyle")), "Tooltip style edit failed");
            api.saveCustomItem(STYLE, style);
        } else reject("setTooltipStyle", style, "recipeweaver:smoke_style");
        if (enchantableSupported) {
            edit("setEnchantable", enchantable, "27");
            require(Integer.valueOf(27).equals(component(enchantable, "Enchantable")),
                    "Enchantability edit failed");
            api.saveCustomItem(ENCHANTABLE, enchantable);
        } else reject("setEnchantable", enchantable, "27");

        if (modelSupported || styleSupported || enchantableSupported) {
            SmokePlugin.reloadForLegacyAssertions(api);
        }
        if (modelSupported) {
            require(MODEL_VALUE.equals(api.customItem(MODEL)
                    .map(item -> component(item, "ItemModel")).orElse(null)),
                    "Item model did not survive application reload");
            edit("setItemModel", model, "none");
            require(component(model, "ItemModel") == null, "Item model could not be cleared");
        }
        if (styleSupported) {
            require(STYLE_VALUE.equals(api.customItem(STYLE)
                    .map(item -> component(item, "TooltipStyle")).orElse(null)),
                    "Tooltip style did not survive application reload");
            edit("setTooltipStyle", style, "none");
            require(component(style, "TooltipStyle") == null,
                    "Tooltip style could not be cleared");
        }
        if (enchantableSupported) {
            require(Integer.valueOf(27).equals(api.customItem(ENCHANTABLE)
                    .map(item -> component(item, "Enchantable")).orElse(null)),
                    "Enchantability did not survive application reload");
            reject("setEnchantable", enchantable, "1025");
            edit("setEnchantable", enchantable, "none");
            require(component(enchantable, "Enchantable") == null,
                    "Enchantability could not be cleared");
        }
        harness.getLogger().info("RW_EDIT_ITEM_MODEL_"
                + (modelSupported ? "PASS" : "UNSUPPORTED_PASS"));
        harness.getLogger().info("RW_EDIT_TOOLTIP_STYLE_"
                + (styleSupported ? "PASS" : "UNSUPPORTED_PASS"));
        harness.getLogger().info("RW_EDIT_ENCHANTABLE_"
                + (enchantableSupported ? "PASS" : "UNSUPPORTED_PASS"));
    }

    static void verifyAttributeModifiers(RecipeWeaverApi api, Plugin harness) {
        Attribute damage = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic.attack_damage"));
        if (damage == null) damage = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("attack_damage"));
        require(damage != null, "Attack damage attribute is unavailable");
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        String attributeKey = damage.getKey().toString();
        edit("setAttributeModifier", sword, attributeKey + " mainhand 5 ADD_NUMBER");
        edit("setAttributeModifier", sword, attributeKey + " offhand 2 ADD_SCALAR");
        edit("setAttributeModifier", sword, attributeKey + " mainhand 7 ADD_NUMBER");
        require(sword.getItemMeta().getAttributeModifiers(damage).size() == 2,
                "Attribute replacement discarded another slot or kept a duplicate");
        reject("setAttributeModifier", sword, attributeKey + " mainhand NaN ADD_NUMBER");
        reject("setAttributeModifier", sword, attributeKey + " mainhand 8 UNKNOWN");
        api.saveCustomItem(ATTRIBUTE, sword);
        SmokePlugin.reloadForLegacyAssertions(api);
        Attribute selected = damage;
        require(api.customItem(ATTRIBUTE).map(item -> item.getItemMeta()
                        .getAttributeModifiers(selected)).map(modifiers -> modifiers.size() == 2
                            && modifiers.stream().anyMatch(modifier -> modifier.getAmount() == 7)
                            && modifiers.stream().anyMatch(modifier -> modifier.getAmount() == 2))
                        .orElse(false),
                "Attribute modifiers did not survive application reload");
        ItemStack sample = new ItemStack(Material.DIAMOND_SWORD);
        edit("setAttributeModifier", sample, attributeKey + " mainhand 7 ADD_NUMBER");
        GridRecipeDefinition partial = new GridRecipeDefinition(key("smoke_attribute_match"),
                GridTier.ELITE, RecipeKind.SHAPED, 2,
                Arrays.asList(sample, null, null, null), new ItemStack(Material.DIAMOND),
                "", true, false,
                List.of(IngredientMatchMode.PARTIAL, IngredientMatchMode.MATERIAL,
                        IngredientMatchMode.MATERIAL, IngredientMatchMode.MATERIAL), 0, true);
        require(partial.matchesItemSample(0, sword),
                "Partial matching rejected a sword with an unrelated attribute modifier");
        ItemStack changed = sword.clone();
        edit("setAttributeModifier", changed, attributeKey + " mainhand 8 ADD_NUMBER");
        require(!partial.matchesItemSample(0, changed),
                "Partial matching accepted a changed required attribute modifier");
        harness.getLogger().info("RW_MATCH_ATTRIBUTE_MODIFIER_PASS");
        edit("setAttributeModifier", sword, attributeKey + " mainhand none");
        require(sword.getItemMeta().getAttributeModifiers(damage).size() == 1,
                "Removing one attribute slot removed another modifier");
        harness.getLogger().info("RW_EDIT_ATTRIBUTE_MODIFIER_PASS");
    }

    static void verifyItemFlagPartialMatching(Plugin harness) {
        ItemStack sample = new ItemStack(Material.DIAMOND_SWORD);
        var sampleMeta = sample.getItemMeta();
        sampleMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        sample.setItemMeta(sampleMeta);
        GridRecipeDefinition partial = new GridRecipeDefinition(key("smoke_flag_match"),
                GridTier.ELITE, RecipeKind.SHAPED, 2,
                Arrays.asList(sample, null, null, null), new ItemStack(Material.DIAMOND),
                "", true, false,
                List.of(IngredientMatchMode.PARTIAL, IngredientMatchMode.MATERIAL,
                        IngredientMatchMode.MATERIAL, IngredientMatchMode.MATERIAL), 0, true);
        ItemStack actual = sample.clone();
        var actualMeta = actual.getItemMeta();
        actualMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        actual.setItemMeta(actualMeta);
        require(partial.matchesItemSample(0, actual),
                "Partial matching rejected an item with an extra tooltip flag");
        actualMeta = actual.getItemMeta();
        actualMeta.removeItemFlags(ItemFlag.HIDE_ENCHANTS);
        actual.setItemMeta(actualMeta);
        require(!partial.matchesItemSample(0, actual),
                "Partial matching accepted an item missing the required tooltip flag");
        harness.getLogger().info("RW_MATCH_ITEM_FLAGS_PASS");
    }

    static void verifyPersistentCompound(RecipeWeaverApi api, Plugin harness) {
        ItemStack sample = new ItemStack(Material.PAPER);
        edit("setPersistentData", sample,
                "recipeweaver:profile\n@compound recipeweaver:rank @int 4");
        edit("setPersistentData", sample,
                "recipeweaver:profile\n@compound recipeweaver:labels @strings alpha\nbeta");
        require(compoundSaved(sample), "Nested compound editor did not retain both typed leaves");
        reject("setPersistentData", sample,
                "recipeweaver:profile\n@compound recipeweaver:rank @int invalid");
        require(compoundSaved(sample), "Rejected compound edit changed the item");

        GridRecipeDefinition partial = new GridRecipeDefinition(key("smoke_compound_match"),
                GridTier.ELITE, RecipeKind.SHAPED, 2,
                Arrays.asList(sample, null, null, null), new ItemStack(Material.DIAMOND),
                "", true, false,
                List.of(IngredientMatchMode.PARTIAL, IngredientMatchMode.MATERIAL,
                        IngredientMatchMode.MATERIAL, IngredientMatchMode.MATERIAL), 0, true);
        ItemStack actual = sample.clone();
        edit("setPersistentData", actual,
                "recipeweaver:profile\n@compound recipeweaver:extra @string allowed");
        require(partial.matchesItemSample(0, actual),
                "Partial matching rejected a compound with extra child data");
        edit("setPersistentData", actual,
                "recipeweaver:profile\n@compound recipeweaver:rank @int 5");
        require(!partial.matchesItemSample(0, actual),
                "Partial matching accepted a different nested rank");

        api.saveCustomItem(COMPOUND, sample);
        SmokePlugin.reloadForLegacyAssertions(api);
        require(api.customItem(COMPOUND).map(AdvancedItemSmoke::compoundSaved).orElse(false),
                "Nested compound did not survive application reload");
        edit("setPersistentData", sample,
                "recipeweaver:profile\n@compound recipeweaver:rank none");
        PersistentDataContainer remaining = sample.getItemMeta().getPersistentDataContainer()
                .get(key("profile"), PersistentDataType.TAG_CONTAINER);
        require(remaining != null && !remaining.has(key("rank")) && remaining.has(key("labels")),
                "Removing one compound leaf discarded its sibling");
        edit("setPersistentData", sample,
                "recipeweaver:profile\n@compound recipeweaver:labels none");
        require(!sample.getItemMeta().getPersistentDataContainer().has(key("profile")),
                "Removing the last compound leaf left stale custom data");
        harness.getLogger().info("RW_EDIT_PERSISTENT_COMPOUND_PASS");
        harness.getLogger().info("RW_MATCH_PERSISTENT_COMPOUND_PASS");
    }

    static void verifyItemTextImport(RecipeWeaverApi api, Plugin harness) {
        ItemStack imported = parseText(TEXT_IMPORT_VALUE);
        require(imported.getType() == Material.PAPER && imported.getAmount() == 1
                        && !imported.isSimilar(new ItemStack(Material.PAPER)),
                "Bukkit item text parser did not apply custom SNBT data");
        rejectText("minecraft:paper[minecraft:custom_data={recipeweaver:{level:7}}] minecraft:diamond");
        rejectText("minecraft:paper[minecraft:custom_data={recipeweaver:{level:7}]");
        rejectText("minecraft:paper[minecraft:unknown_component=7]");
        api.saveCustomItem(TEXT_IMPORT, imported);
        harness.getLogger().info("RW_EDIT_ITEM_TEXT_PASS");
    }

    static boolean verifyRichModelDataEditor(RecipeWeaverApi api, Plugin harness) {
        ItemStack item = new ItemStack(Material.PAPER);
        if (!richModelSupported()) {
            edit("setCustomModelData", item, "17");
            reject("setRichCustomModelData", item, "@floats 1.5, 2.5");
            require(item.getItemMeta().getCustomModelData() == 17,
                    "Unsupported rich model edit changed legacy custom model data");
            harness.getLogger().info("RW_EDIT_RICH_MODEL_DATA_UNSUPPORTED_PASS");
            return false;
        }
        edit("setRichCustomModelData", item, "@floats 1.5, 2.5");
        edit("setRichCustomModelData", item, "@flags true, false");
        edit("setRichCustomModelData", item, "@strings first\nsecond");
        edit("setRichCustomModelData", item, "@colors #24A0ED, #FFAA00");
        require(richModelSaved(item), "Rich model editor did not retain all four lists");
        reject("setRichCustomModelData", item, "@floats NaN");
        reject("setRichCustomModelData", item, "@flags true, maybe");
        reject("setRichCustomModelData", item, "@colors #12ZZ00");
        require(richModelSaved(item), "Rejected rich model edit changed the item");
        ItemStack clearedList = item.clone();
        edit("setRichCustomModelData", clearedList, "@colors none");
        require(modelValues(clearedList, "Colors").isEmpty()
                        && java.util.List.of("first", "second")
                                .equals(modelValues(clearedList, "Strings")),
                "Clearing one model-data list discarded another list");
        ItemStack clearedAll = item.clone();
        edit("setRichCustomModelData", clearedAll, "none");
        require(!richModelPresent(clearedAll), "Rich model component could not be cleared");
        api.saveCustomItem(RICH_MODEL, item);
        harness.getLogger().info("RW_EDIT_RICH_MODEL_DATA_PASS");
        return true;
    }

    static boolean richModelDataSaved(RecipeWeaverApi api) {
        return api.customItem(RICH_MODEL).map(AdvancedItemSmoke::richModelSaved).orElse(false);
    }

    static boolean verifyUseRemainderEditor(RecipeWeaverApi api, Plugin harness) {
        ItemStack item = new ItemStack(Material.PAPER);
        if (!supports("setUseRemainder", ItemStack.class)) {
            reject("setUseRemainder", item, "none");
            harness.getLogger().info("RW_EDIT_USE_REMAINDER_UNSUPPORTED_PASS");
            return false;
        }
        edit("setUseRemainder", item, REMAINDER_VALUE);
        require(useRemainderSaved(item), "Use remainder editor lost the nested item data");
        reject("setUseRemainder", item, "minecraft:bucket]minecraft:stone");
        require(useRemainderSaved(item), "Rejected use remainder edit changed the item");
        ItemStack cleared = item.clone();
        edit("setUseRemainder", cleared, "none");
        require(component(cleared, "UseRemainder") == null,
                "Use remainder component could not be cleared");
        api.saveCustomItem(USE_REMAINDER, item);
        harness.getLogger().info("RW_EDIT_USE_REMAINDER_PASS");
        return true;
    }

    static boolean verifyUseCooldownEditor(RecipeWeaverApi api, Plugin harness) {
        ItemStack item = new ItemStack(Material.PAPER);
        if (!supportsModernComponent("UseCooldown")) {
            reject("setUseCooldown", item, "3.5 recipeweaver:shared");
            harness.getLogger().info("RW_EDIT_USE_COOLDOWN_UNSUPPORTED_PASS");
            return false;
        }
        edit("setUseCooldown", item, "3.5 recipeweaver:shared");
        require(useCooldownSaved(item), "Use cooldown editor lost seconds or group");
        reject("setUseCooldown", item, "NaN recipeweaver:shared");
        require(useCooldownSaved(item), "Rejected use cooldown edit changed the item");
        ItemStack changed = item.clone();
        edit("setUseCooldown", changed, "5.0");
        require(Float.valueOf(5.0F).equals(componentValue(changed, "UseCooldown",
                        "getCooldownSeconds"))
                        && key("shared").equals(componentValue(changed, "UseCooldown",
                                "getCooldownGroup")),
                "Changing cooldown seconds discarded the group");
        edit("setUseCooldown", changed, "5.0 none");
        require(componentValue(changed, "UseCooldown", "getCooldownGroup") == null,
                "Cooldown group could not be cleared");
        edit("setUseCooldown", changed, "none");
        require(component(changed, "UseCooldown") == null,
                "Use cooldown component could not be cleared");
        api.saveCustomItem(USE_COOLDOWN, item);
        harness.getLogger().info("RW_EDIT_USE_COOLDOWN_PASS");
        return true;
    }

    static boolean verifyEquippableEditor(RecipeWeaverApi api, Plugin harness) {
        ItemStack item = new ItemStack(Material.PAPER);
        if (!supportsModernComponent("Equippable")) {
            reject("setEquippable", item, "HEAD recipeweaver:hat");
            harness.getLogger().info("RW_EDIT_EQUIPPABLE_UNSUPPORTED_PASS");
            return false;
        }
        edit("setEquippable", item, "HEAD recipeweaver:hat");
        require(equippableSaved(item), "Equippable editor lost slot or model");
        reject("setEquippable", item, "WRIST recipeweaver:hat");
        require(equippableSaved(item), "Rejected equippable edit changed the item");
        ItemStack changed = item.clone();
        edit("setEquippable", changed, "CHEST");
        require(componentValue(changed, "Equippable", "getSlot") == EquipmentSlot.CHEST
                        && key("hat").equals(componentValue(changed, "Equippable", "getModel")),
                "Changing equippable slot discarded its model");
        edit("setEquippable", changed, "HEAD none");
        require(componentValue(changed, "Equippable", "getModel") == null,
                "Equippable model could not be cleared");
        edit("setEquippable", changed, "none");
        require(component(changed, "Equippable") == null,
                "Equippable component could not be cleared");
        api.saveCustomItem(EQUIPPABLE, item);
        harness.getLogger().info("RW_EDIT_EQUIPPABLE_PASS");
        return true;
    }

    static boolean useRemainderSaved(RecipeWeaverApi api) {
        return api.customItem(USE_REMAINDER).map(AdvancedItemSmoke::useRemainderSaved)
                .orElse(false);
    }

    static boolean useCooldownSaved(RecipeWeaverApi api) {
        return api.customItem(USE_COOLDOWN).map(AdvancedItemSmoke::useCooldownSaved)
                .orElse(false);
    }

    static boolean equippableSaved(RecipeWeaverApi api) {
        return api.customItem(EQUIPPABLE).map(AdvancedItemSmoke::equippableSaved)
                .orElse(false);
    }

    static boolean verifyGliderEditor(RecipeWeaverApi api, Plugin harness) {
        ItemStack item = new ItemStack(Material.PAPER);
        if (!supports("setGlider", boolean.class)) {
            reject("setGlider", item, "true");
            harness.getLogger().info("RW_EDIT_GLIDER_UNSUPPORTED_PASS");
            return false;
        }
        edit("setGlider", item, "true");
        require(gliderSaved(item), "Glider edit was not applied");
        reject("setGlider", item, "maybe");
        require(gliderSaved(item), "Rejected glider edit changed the item");
        ItemStack changed = item.clone();
        edit("setGlider", changed, "false");
        require(!gliderSaved(changed), "Glider could not be disabled");
        api.saveCustomItem(GLIDER, item);
        harness.getLogger().info("RW_EDIT_GLIDER_PASS");
        return true;
    }

    static boolean verifyAdditionalTradeCostEditor(RecipeWeaverApi api, Plugin harness) {
        ItemStack item = new ItemStack(Material.PAPER);
        if (!supports("setAdditionalTradeCost", Integer.class)) {
            reject("setAdditionalTradeCost", item, "7");
            harness.getLogger().info("RW_EDIT_TRADE_COST_UNSUPPORTED_PASS");
            return false;
        }
        edit("setAdditionalTradeCost", item, "7");
        require(tradeCostSaved(item), "Additional trade cost edit was not applied");
        reject("setAdditionalTradeCost", item, "-1");
        reject("setAdditionalTradeCost", item, "1000001");
        require(tradeCostSaved(item), "Rejected trade cost edit changed the item");
        ItemStack cleared = item.clone();
        edit("setAdditionalTradeCost", cleared, "none");
        require(tradeCost(cleared) == null, "Additional trade cost could not be cleared");
        api.saveCustomItem(TRADE_COST, item);
        harness.getLogger().info("RW_EDIT_TRADE_COST_PASS");
        return true;
    }

    static boolean verifyMinimumAttackChargeEditor(RecipeWeaverApi api, Plugin harness) {
        ItemStack item = new ItemStack(Material.PAPER);
        if (!supports("setMinimumAttackCharge", Float.class)) {
            reject("setMinimumAttackCharge", item, "0.75");
            harness.getLogger().info("RW_EDIT_ATTACK_CHARGE_UNSUPPORTED_PASS");
            return false;
        }
        edit("setMinimumAttackCharge", item, "0.75");
        require(attackChargeSaved(item), "Minimum attack charge edit was not applied");
        reject("setMinimumAttackCharge", item, "NaN");
        reject("setMinimumAttackCharge", item, "1.1");
        require(attackChargeSaved(item), "Rejected attack charge edit changed the item");
        ItemStack cleared = item.clone();
        edit("setMinimumAttackCharge", cleared, "none");
        require(component(cleared, "MinimumAttackCharge") == null,
                "Minimum attack charge could not be cleared");
        api.saveCustomItem(ATTACK_CHARGE, item);
        harness.getLogger().info("RW_EDIT_ATTACK_CHARGE_PASS");
        return true;
    }

    static boolean verifyBreakSoundEditor(RecipeWeaverApi api, Plugin harness) {
        ItemStack item = new ItemStack(Material.PAPER);
        if (!supports("setBreakSound", Sound.class)) {
            reject("setBreakSound", item, "minecraft:block.glass.break");
            harness.getLogger().info("RW_EDIT_BREAK_SOUND_UNSUPPORTED_PASS");
            return false;
        }
        edit("setBreakSound", item, "minecraft:block.glass.break");
        require(breakSoundSaved(item), "Break sound edit was not applied");
        reject("setBreakSound", item, "minecraft:not_a_sound");
        require(breakSoundSaved(item), "Rejected break sound edit changed the item");
        ItemStack cleared = item.clone();
        edit("setBreakSound", cleared, "none");
        require(component(cleared, "BreakSound") == null,
                "Break sound could not be cleared");
        api.saveCustomItem(BREAK_SOUND, item);
        harness.getLogger().info("RW_EDIT_BREAK_SOUND_PASS");
        return true;
    }

    static boolean gliderSaved(RecipeWeaverApi api) {
        return api.customItem(GLIDER).map(AdvancedItemSmoke::gliderSaved).orElse(false);
    }

    static boolean tradeCostSaved(RecipeWeaverApi api) {
        return api.customItem(TRADE_COST).map(AdvancedItemSmoke::tradeCostSaved).orElse(false);
    }

    static boolean attackChargeSaved(RecipeWeaverApi api) {
        return api.customItem(ATTACK_CHARGE).map(AdvancedItemSmoke::attackChargeSaved).orElse(false);
    }

    static boolean breakSoundSaved(RecipeWeaverApi api) {
        return api.customItem(BREAK_SOUND).map(AdvancedItemSmoke::breakSoundSaved).orElse(false);
    }

    static boolean verifyJukeboxPlayableEditor(RecipeWeaverApi api, Plugin harness) {
        ItemStack item = new ItemStack(Material.PAPER);
        if (!supportsModernComponent("JukeboxPlayable")) {
            reject("setJukeboxPlayable", item, "minecraft:cat");
            harness.getLogger().info("RW_EDIT_JUKEBOX_UNSUPPORTED_PASS");
            return false;
        }
        edit("setJukeboxPlayable", item, "minecraft:cat");
        require(jukeboxSaved(item), "Jukebox song edit was not applied");
        reject("setJukeboxPlayable", item, "minecraft:not_a_song");
        require(jukeboxSaved(item), "Rejected jukebox song edit changed the item");
        ItemStack cleared = item.clone();
        edit("setJukeboxPlayable", cleared, "none");
        require(component(cleared, "JukeboxPlayable") == null,
                "Jukebox song could not be cleared");
        api.saveCustomItem(JUKEBOX, item);
        harness.getLogger().info("RW_EDIT_JUKEBOX_PASS");
        return true;
    }

    static boolean verifyDamageResistanceEditor(RecipeWeaverApi api, Plugin harness) {
        ItemStack item = new ItemStack(Material.PAPER);
        if (!supports("setDamageResistant", org.bukkit.Tag.class)) {
            reject("setDamageResistance", item, "minecraft:is_fire");
            harness.getLogger().info("RW_EDIT_DAMAGE_RESISTANCE_UNSUPPORTED_PASS");
            return false;
        }
        edit("setDamageResistance", item, "minecraft:is_fire");
        require(damageResistanceSaved(item), "Damage resistance edit was not applied");
        reject("setDamageResistance", item, "minecraft:not_a_damage_tag");
        require(damageResistanceSaved(item), "Rejected damage resistance edit changed the item");
        ItemStack cleared = item.clone();
        edit("setDamageResistance", cleared, "none");
        require(component(cleared, "DamageResistant") == null,
                "Damage resistance could not be cleared");
        api.saveCustomItem(DAMAGE_RESISTANCE, item);
        harness.getLogger().info("RW_EDIT_DAMAGE_RESISTANCE_PASS");
        return true;
    }

    static boolean jukeboxSaved(RecipeWeaverApi api) {
        return api.customItem(JUKEBOX).map(AdvancedItemSmoke::jukeboxSaved).orElse(false);
    }

    static boolean damageResistanceSaved(RecipeWeaverApi api) {
        return api.customItem(DAMAGE_RESISTANCE).map(AdvancedItemSmoke::damageResistanceSaved)
                .orElse(false);
    }

    static void verifyWritableBookPagesEditor(RecipeWeaverApi api, Plugin harness) {
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        edit("setBookPages", item, "First page\n---PAGE---\nSecond page");
        require(writablePagesSaved(item), "Writable book pages were not edited");
        reject("setBookPages", item, "First page\n---PAGE---\n");
        require(writablePagesSaved(item), "Rejected writable page edit changed the item");
        ItemStack cleared = item.clone();
        edit("setBookPages", cleared, "none");
        require(((WritableBookMeta) cleared.getItemMeta()).getPages().isEmpty(),
                "Writable book pages could not be cleared");
        api.saveCustomItem(WRITABLE_PAGES, item);
        harness.getLogger().info("RW_EDIT_WRITABLE_PAGES_PASS");
    }

    static void verifyHeadProfileEditor(RecipeWeaverApi api, Plugin harness) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        edit("setHeadProfile", item, "SmokeHead " + HEAD_SKIN_HASH);
        require(headProfileSaved(item), "Head name or skin texture was not edited");
        reject("setHeadProfile", item, "SmokeHead " + "a".repeat(63));
        require(headProfileSaved(item), "Rejected head profile edit changed the item");
        ItemStack cleared = item.clone();
        edit("setHeadProfile", cleared, "none");
        require(((SkullMeta) cleared.getItemMeta()).getOwnerProfile() == null,
                "Player-head profile could not be cleared");
        api.saveCustomItem(HEAD_PROFILE, item);
        harness.getLogger().info("RW_EDIT_HEAD_PROFILE_PASS");
    }

    static void verifyHeadNoteSoundEditor(RecipeWeaverApi api, Plugin harness) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        edit("setHeadNoteBlockSound", item, "minecraft:block.note_block.harp");
        require(headNoteSoundSaved(item), "Head note-block sound was not edited");
        reject("setHeadNoteBlockSound", item, "block.note_block.harp");
        require(headNoteSoundSaved(item), "Rejected head note sound edit changed the item");
        ItemStack cleared = item.clone();
        edit("setHeadNoteBlockSound", cleared, "none");
        require(((SkullMeta) cleared.getItemMeta()).getNoteBlockSound() == null,
                "Head note-block sound could not be cleared");
        api.saveCustomItem(HEAD_NOTE_SOUND, item);
        harness.getLogger().info("RW_EDIT_HEAD_NOTE_SOUND_PASS");
    }

    static boolean writablePagesSaved(RecipeWeaverApi api) {
        return api.customItem(WRITABLE_PAGES).map(AdvancedItemSmoke::writablePagesSaved)
                .orElse(false);
    }

    static boolean headProfileSaved(RecipeWeaverApi api) {
        return api.customItem(HEAD_PROFILE).map(AdvancedItemSmoke::headProfileSaved)
                .orElse(false);
    }

    static boolean headNoteSoundSaved(RecipeWeaverApi api) {
        return api.customItem(HEAD_NOTE_SOUND).map(AdvancedItemSmoke::headNoteSoundSaved)
                .orElse(false);
    }

    static void verifyShulkerContentsEditor(RecipeWeaverApi api, Plugin harness) {
        ItemStack item = new ItemStack(Material.RED_SHULKER_BOX);
        ItemMeta name = item.getItemMeta();
        name.setDisplayName("Keep shulker name");
        item.setItemMeta(name);
        edit("setShulkerContents", item, "1 DIAMOND 2");
        edit("setShulkerContents", item, "27 DIRT 3");
        require(shulkerContentsSaved(item), "Shulker slot edits did not persist on the item");
        reject("setShulkerContents", item, "1 SHULKER_BOX 1");
        require(shulkerContentsSaved(item), "Rejected nested shulker edit changed the item");
        ItemStack oneCleared = item.clone();
        edit("setShulkerContents", oneCleared, "1 none");
        require(shulkerSlot(oneCleared, 0) == null
                        && shulkerSlot(oneCleared, 26).getType() == Material.DIRT,
                "Clearing one shulker slot discarded another");
        ItemStack emptied = item.clone();
        edit("setShulkerContents", emptied, "none");
        require(shulkerSlot(emptied, 0) == null && shulkerSlot(emptied, 26) == null
                        && "Keep shulker name".equals(emptied.getItemMeta().getDisplayName()),
                "Emptying a shulker discarded other item metadata or kept contents");
        api.saveCustomItem(SHULKER_CONTENTS, item);
        harness.getLogger().info("RW_EDIT_SHULKER_CONTENTS_PASS");
    }

    static boolean shulkerContentsSaved(RecipeWeaverApi api) {
        return api.customItem(SHULKER_CONTENTS).map(AdvancedItemSmoke::shulkerContentsSaved)
                .orElse(false);
    }

    static void verifyGroupedToolRules(RecipeWeaverApi api, Plugin harness) {
        ItemStack item = new ItemStack(Material.STICK);
        edit("setToolProperties", item, "4.0 1");
        ItemMeta meta = item.getItemMeta();
        var tool = meta.getTool();
        tool.addRule(List.of(Material.STONE, Material.DIRT), 8.0F, true);
        tool.addRule(Material.OAK_LOG, 4.0F, false);
        meta.setTool(tool);
        item.setItemMeta(meta);
        ItemStack original = item.clone();

        edit("setToolRule", item, "STONE 9.0 false");
        var replaced = item.getItemMeta().getTool().getRules();
        require(replaced.size() == 3
                        && replaced.stream().anyMatch(rule -> rule.getBlocks().contains(Material.STONE)
                                && rule.getBlocks().size() == 1 && rule.getSpeed() == 9.0F
                                && !rule.isCorrectForDrops())
                        && replaced.stream().anyMatch(rule -> rule.getBlocks().contains(Material.DIRT)
                                && rule.getBlocks().size() == 1 && rule.getSpeed() == 8.0F
                                && rule.isCorrectForDrops())
                        && replaced.stream().anyMatch(rule -> rule.getBlocks().contains(Material.OAK_LOG)
                                && rule.getSpeed() == 4.0F),
                "Replacing a grouped tool rule changed or retained the wrong blocks");
        harness.getLogger().info("RW_EDIT_GROUPED_TOOL_REPLACE_PASS");

        edit("setToolRule", original, "STONE none");
        require(groupedToolRuleSaved(original),
                "Removing one grouped tool block discarded another rule or retained the block");
        api.saveCustomItem(GROUPED_TOOL_RULE, original);
        harness.getLogger().info("RW_EDIT_GROUPED_TOOL_REMOVE_PASS");
    }

    static void verifyBundleSlotEditor(RecipeWeaverApi api, Plugin harness) {
        ItemStack arrow = new ItemStack(Material.ARROW);
        ItemMeta arrowMeta = arrow.getItemMeta();
        arrowMeta.setDisplayName("Keep arrow");
        arrow.setItemMeta(arrowMeta);
        ItemStack bundle = new ItemStack(Material.BUNDLE);
        BundleMeta meta = (BundleMeta) bundle.getItemMeta();
        meta.setItems(List.of(arrow, new ItemStack(Material.DIAMOND, 2)));
        bundle.setItemMeta(meta);

        edit("setBundleContents", bundle, "+ EMERALD 3");
        require(((BundleMeta) bundle.getItemMeta()).getItems()
                        .equals(List.of(arrow, new ItemStack(Material.DIAMOND, 2),
                                new ItemStack(Material.EMERALD, 3))),
                "Appending a bundle stack discarded existing nested items");
        harness.getLogger().info("RW_EDIT_BUNDLE_APPEND_PASS");

        edit("setBundleContents", bundle, "2 DIRT 1");
        require(((BundleMeta) bundle.getItemMeta()).getItems()
                        .equals(List.of(arrow, new ItemStack(Material.DIRT),
                                new ItemStack(Material.EMERALD, 3))),
                "Replacing a bundle slot changed its sibling stacks");
        harness.getLogger().info("RW_EDIT_BUNDLE_REPLACE_PASS");

        edit("setBundleContents", bundle, "- 2");
        require(((BundleMeta) bundle.getItemMeta()).getItems()
                        .equals(List.of(arrow, new ItemStack(Material.EMERALD, 3))),
                "Removing a bundle slot changed its sibling stacks");
        harness.getLogger().info("RW_EDIT_BUNDLE_REMOVE_PASS");

        reject("setBundleContents", bundle, "3 DIAMOND 1");
        reject("setBundleContents", bundle, "- 0");
        harness.getLogger().info("RW_EDIT_BUNDLE_INVALID_INDEX_PASS");
        api.saveCustomItem(BUNDLE_EDITED, bundle);
    }

    static boolean bundleSlotEditorSaved(RecipeWeaverApi api) {
        return api.customItem(BUNDLE_EDITED).map(AdvancedItemSmoke::bundleSlotEditorSaved)
                .orElse(false);
    }

    private static boolean bundleSlotEditorSaved(ItemStack item) {
        if (item.getType() != Material.BUNDLE || !(item.getItemMeta() instanceof BundleMeta meta)) {
            return false;
        }
        List<ItemStack> items = meta.getItems();
        return items.size() == 2 && items.get(0).getType() == Material.ARROW
                && "Keep arrow".equals(items.get(0).getItemMeta().getDisplayName())
                && items.get(1).getType() == Material.EMERALD
                && items.get(1).getAmount() == 3;
    }

    static boolean groupedToolRuleSaved(RecipeWeaverApi api) {
        return api.customItem(GROUPED_TOOL_RULE)
                .map(AdvancedItemSmoke::groupedToolRuleSaved).orElse(false);
    }

    private static boolean groupedToolRuleSaved(ItemStack item) {
        if (item.getType() != Material.STICK || !item.getItemMeta().hasTool()) return false;
        var rules = item.getItemMeta().getTool().getRules();
        return rules.size() == 2
                && rules.stream().noneMatch(rule -> rule.getBlocks().contains(Material.STONE))
                && rules.stream().anyMatch(rule -> rule.getBlocks().contains(Material.DIRT)
                    && rule.getBlocks().size() == 1 && rule.getSpeed() == 8.0F
                    && rule.isCorrectForDrops())
                && rules.stream().anyMatch(rule -> rule.getBlocks().contains(Material.OAK_LOG)
                    && rule.getSpeed() == 4.0F && !rule.isCorrectForDrops());
    }

    static boolean itemTextSaved(RecipeWeaverApi api) {
        ItemStack expected = parseText(TEXT_IMPORT_VALUE);
        return api.customItem(TEXT_IMPORT).map(expected::isSimilar).orElse(false);
    }

    static boolean saved(RecipeWeaverApi api) {
        return matches(api, AXOLOTL, AdvancedItemSmoke::axolotlSaved)
                && matches(api, KNOWLEDGE, AdvancedItemSmoke::knowledgeSaved)
                && matches(api, HORN, AdvancedItemSmoke::hornSaved)
                && matches(api, BOTTLE, AdvancedItemSmoke::bottleSaved)
                && matches(api, BUNDLE, AdvancedItemSmoke::bundleSaved)
                && matches(api, REPAIR, AdvancedItemSmoke::repairSaved)
                && matches(api, BOOK, AdvancedItemSmoke::bookSaved)
                && matches(api, BLOCK, AdvancedItemSmoke::blockSaved)
                && matches(api, NAME, AdvancedItemSmoke::nameSaved)
                && matches(api, LISTS, AdvancedItemSmoke::listsSaved);
    }

    static void checkRecovered(RecipeWeaverApi api) {
        List<String> missing = new ArrayList<>();
        if (!matches(api, AXOLOTL, AdvancedItemSmoke::axolotlSaved)) missing.add("axolotl");
        if (!matches(api, KNOWLEDGE, AdvancedItemSmoke::knowledgeSaved)) missing.add("knowledge book");
        if (!matches(api, HORN, AdvancedItemSmoke::hornSaved)) missing.add("goat horn");
        if (!matches(api, BOTTLE, AdvancedItemSmoke::bottleSaved)) missing.add("ominous bottle");
        if (!matches(api, BUNDLE, AdvancedItemSmoke::bundleSaved)) missing.add("bundle");
        if (!matches(api, REPAIR, AdvancedItemSmoke::repairSaved)) missing.add("repair cost");
        if (!matches(api, BOOK, AdvancedItemSmoke::bookSaved)) {
            missing.add("book generation " + api.customItem(BOOK).map(item ->
                    item.getType() + "/" + item.getItemMeta().getClass().getSimpleName() + "/"
                            + ((BookMeta) item.getItemMeta()).getGeneration()).orElse("missing"));
        }
        if (!matches(api, BLOCK, AdvancedItemSmoke::blockSaved)) missing.add("block state");
        if (!matches(api, NAME, AdvancedItemSmoke::nameSaved)) missing.add("item name");
        if (!matches(api, LISTS, AdvancedItemSmoke::listsSaved)) missing.add("persistent lists");
        if (!missing.isEmpty()) throw new IllegalStateException("Advanced item components were not recovered: " + missing);
    }

    static void mutateForRestore(RecipeWeaverApi api) {
        api.saveCustomItem(AXOLOTL, new ItemStack(Material.AXOLOTL_BUCKET));
        api.saveCustomItem(KNOWLEDGE, new ItemStack(Material.KNOWLEDGE_BOOK));
        api.saveCustomItem(HORN, new ItemStack(Material.GOAT_HORN));
        api.saveCustomItem(BOTTLE, new ItemStack(Material.OMINOUS_BOTTLE));
        api.saveCustomItem(BUNDLE, new ItemStack(Material.BUNDLE));
        api.saveCustomItem(REPAIR, new ItemStack(Material.DIAMOND_SWORD));
        api.saveCustomItem(BOOK, new ItemStack(Material.WRITTEN_BOOK));
        api.saveCustomItem(BLOCK, new ItemStack(Material.OAK_STAIRS));
        api.saveCustomItem(NAME, new ItemStack(Material.PAPER));
        api.saveCustomItem(LISTS, new ItemStack(Material.PAPER));
        SmokePlugin.reloadForLegacyAssertions(api);
        require(!saved(api), "Advanced item mutation did not save");
    }

    private static boolean axolotlSaved(ItemStack item) {
        return item.getType() == Material.AXOLOTL_BUCKET
                && item.getItemMeta() instanceof AxolotlBucketMeta meta
                && meta.hasVariant() && meta.getVariant() == Axolotl.Variant.BLUE;
    }

    private static boolean knowledgeSaved(ItemStack item) {
        return item.getType() == Material.KNOWLEDGE_BOOK
                && item.getItemMeta() instanceof KnowledgeBookMeta meta
                && meta.getRecipes().equals(List.of(PICKAXE));
    }

    private static boolean hornSaved(ItemStack item) {
        return item.getType() == Material.GOAT_HORN
                && item.getItemMeta() instanceof MusicInstrumentMeta meta
                && meta.getInstrument() != null
                && meta.getInstrument().getKey().equals(MusicInstrument.DREAM_GOAT_HORN.getKey());
    }

    private static boolean bottleSaved(ItemStack item) {
        return item.getType() == Material.OMINOUS_BOTTLE
                && item.getItemMeta() instanceof OminousBottleMeta meta
                && meta.getAmplifier() == 4;
    }

    private static boolean bundleSaved(ItemStack item) {
        return item.getType() == Material.BUNDLE
                && item.getItemMeta() instanceof BundleMeta meta
                && meta.getItems().size() == 1
                && meta.getItems().get(0).getType() == Material.DIAMOND
                && meta.getItems().get(0).getAmount() == 2;
    }

    private static boolean repairSaved(ItemStack item) {
        return item.getType() == Material.DIAMOND_SWORD
                && item.getItemMeta() instanceof Repairable meta
                && meta.getRepairCost() == 7;
    }

    private static boolean bookSaved(ItemStack item) {
        return item.getType() == Material.WRITTEN_BOOK
                && item.getItemMeta() instanceof BookMeta meta
                && meta.getGeneration() == BookMeta.Generation.COPY_OF_ORIGINAL;
    }

    private static boolean blockSaved(ItemStack item) {
        if (item.getType() != Material.OAK_STAIRS
                || !(item.getItemMeta() instanceof BlockDataMeta meta)
                || !meta.hasBlockData()) return false;
        Stairs stairs = (Stairs) meta.getBlockData(Material.OAK_STAIRS);
        return stairs.getFacing() == BlockFace.EAST && stairs.getHalf() == Stairs.Half.TOP;
    }

    private static boolean nameSaved(ItemStack item) {
        return item.getType() == Material.PAPER && item.getItemMeta().hasItemName()
                && "Permanent Name".equals(item.getItemMeta().getItemName());
    }

    private static boolean listsSaved(ItemStack item) {
        if (item.getType() != Material.PAPER) return false;
        var data = item.getItemMeta().getPersistentDataContainer();
        return List.of("alpha", "beta").equals(data.get(key("labels"),
                    PersistentDataType.LIST.strings()))
                && List.of(-2, 0, 19).equals(data.get(key("counts"),
                    PersistentDataType.LIST.integers()))
                && List.of(1L, Long.MAX_VALUE).equals(data.get(key("large"),
                    PersistentDataType.LIST.longs()));
    }

    private static boolean richModelSupported() {
        try {
            ItemMeta.class.getMethod("getCustomModelDataComponent");
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private static boolean richModelPresent(ItemStack item) {
        try {
            return Boolean.TRUE.equals(ItemMeta.class.getMethod("hasCustomModelDataComponent")
                    .invoke(item.getItemMeta()));
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not inspect rich model data", ex);
        }
    }

    private static List<?> modelValues(ItemStack item, String field) {
        try {
            Method get = ItemMeta.class.getMethod("getCustomModelDataComponent");
            Object component = get.invoke(item.getItemMeta());
            return (List<?>) get.getReturnType().getMethod("get" + field).invoke(component);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not inspect model-data " + field, ex);
        }
    }

    private static boolean richModelSaved(ItemStack item) {
        return item.getType() == Material.PAPER && richModelPresent(item)
                && List.of(1.5F, 2.5F).equals(modelValues(item, "Floats"))
                && List.of(true, false).equals(modelValues(item, "Flags"))
                && List.of("first", "second").equals(modelValues(item, "Strings"))
                && List.of(Color.fromRGB(0x24A0ED), Color.fromRGB(0xFFAA00))
                        .equals(modelValues(item, "Colors"));
    }

    private static boolean useRemainderSaved(ItemStack item) {
        ItemStack remainder = (ItemStack) component(item, "UseRemainder");
        return item.getType() == Material.PAPER && remainder != null
                && remainder.isSimilar(parseText(REMAINDER_VALUE));
    }

    private static boolean useCooldownSaved(ItemStack item) {
        return item.getType() == Material.PAPER
                && Float.valueOf(3.5F).equals(componentValue(item, "UseCooldown",
                        "getCooldownSeconds"))
                && key("shared").equals(componentValue(item, "UseCooldown",
                        "getCooldownGroup"));
    }

    private static boolean equippableSaved(ItemStack item) {
        return item.getType() == Material.PAPER
                && componentValue(item, "Equippable", "getSlot") == EquipmentSlot.HEAD
                && key("hat").equals(componentValue(item, "Equippable", "getModel"));
    }

    private static boolean gliderSaved(ItemStack item) {
        try {
            return item.getType() == Material.PAPER && Boolean.TRUE.equals(
                    ItemMeta.class.getMethod("isGlider").invoke(item.getItemMeta()));
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not inspect glider property", ex);
        }
    }

    private static Integer tradeCost(ItemStack item) {
        Object value = item.getItemMeta().serialize().get("additional-trade-cost");
        return value instanceof Integer cost ? cost : null;
    }

    private static boolean tradeCostSaved(ItemStack item) {
        return item.getType() == Material.PAPER && Integer.valueOf(7).equals(tradeCost(item));
    }

    private static boolean attackChargeSaved(ItemStack item) {
        return item.getType() == Material.PAPER
                && Float.valueOf(0.75F).equals(component(item, "MinimumAttackCharge"));
    }

    private static boolean breakSoundSaved(ItemStack item) {
        return item.getType() == Material.PAPER
                && Sound.BLOCK_GLASS_BREAK.equals(component(item, "BreakSound"));
    }

    private static boolean jukeboxSaved(ItemStack item) {
        return item.getType() == Material.PAPER
                && NamespacedKey.minecraft("cat").equals(
                        componentValue(item, "JukeboxPlayable", "getSongKey"));
    }

    private static boolean damageResistanceSaved(ItemStack item) {
        return item.getType() == Material.PAPER
                && component(item, "DamageResistant") instanceof org.bukkit.Tag<?> tag
                && tag.getKey().equals(NamespacedKey.minecraft("is_fire"));
    }

    private static boolean writablePagesSaved(ItemStack item) {
        return item.getType() == Material.WRITABLE_BOOK
                && item.getItemMeta() instanceof WritableBookMeta book
                && book.getPages().equals(List.of("First page", "Second page"));
    }

    private static boolean headProfileSaved(ItemStack item) {
        if (item.getType() != Material.PLAYER_HEAD
                || !(item.getItemMeta() instanceof SkullMeta skull)) return false;
        var profile = skull.getOwnerProfile();
        return profile != null && "SmokeHead".equals(profile.getName())
                && profile.getTextures().getSkin() != null
                && profile.getTextures().getSkin().toString().equals(
                        "https://textures.minecraft.net/texture/" + HEAD_SKIN_HASH);
    }

    private static boolean headNoteSoundSaved(ItemStack item) {
        return item.getType() == Material.PLAYER_HEAD
                && item.getItemMeta() instanceof SkullMeta skull
                && NamespacedKey.minecraft("block.note_block.harp")
                        .equals(skull.getNoteBlockSound());
    }

    private static boolean shulkerContentsSaved(ItemStack item) {
        if (item.getType() != Material.RED_SHULKER_BOX
                || !(item.getItemMeta() instanceof BlockStateMeta meta)
                || !(meta.getBlockState() instanceof ShulkerBox box)) return false;
        ItemStack first = box.getSnapshotInventory().getItem(0);
        ItemStack last = box.getSnapshotInventory().getItem(26);
        return first != null && first.getType() == Material.DIAMOND && first.getAmount() == 2
                && last != null && last.getType() == Material.DIRT && last.getAmount() == 3
                && "Keep shulker name".equals(item.getItemMeta().getDisplayName());
    }

    private static ItemStack shulkerSlot(ItemStack item, int slot) {
        BlockStateMeta meta = (BlockStateMeta) item.getItemMeta();
        ShulkerBox box = (ShulkerBox) meta.getBlockState();
        return box.getSnapshotInventory().getItem(slot);
    }

    private static boolean compoundSaved(ItemStack item) {
        if (item.getType() != Material.PAPER) return false;
        PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
        if (!data.has(key("profile"), PersistentDataType.TAG_CONTAINER)) return false;
        PersistentDataContainer profile = data.get(key("profile"),
                PersistentDataType.TAG_CONTAINER);
        return Integer.valueOf(4).equals(profile.get(key("rank"), PersistentDataType.INTEGER))
                && List.of("alpha", "beta").equals(profile.get(key("labels"),
                    PersistentDataType.LIST.strings()));
    }

    private static boolean matches(RecipeWeaverApi api, NamespacedKey key, Predicate<ItemStack> predicate) {
        return api.customItem(key).map(predicate::test).orElse(false);
    }

    private static boolean supports(String name, Class<?> argument) {
        try { org.bukkit.inventory.meta.ItemMeta.class.getMethod(name, argument); return true; }
        catch (NoSuchMethodException ex) { return false; }
    }

    private static boolean supportsModernComponent(String suffix) {
        try {
            Method get = ItemMeta.class.getMethod("get" + suffix);
            ItemMeta.class.getMethod("set" + suffix, get.getReturnType());
            return true;
        } catch (NoSuchMethodException ex) {
            return false;
        }
    }

    private static Object componentValue(ItemStack item, String suffix, String getter) {
        Object value = component(item, suffix);
        if (value == null) return null;
        try {
            return ItemMeta.class.getMethod("get" + suffix).getReturnType()
                    .getMethod(getter).invoke(value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not read " + suffix + " component", ex);
        }
    }

    private static Object component(ItemStack item, String name) {
        try {
            Object meta = item.getItemMeta();
            boolean present = (boolean) org.bukkit.inventory.meta.ItemMeta.class
                    .getMethod("has" + name).invoke(meta);
            return present ? org.bukkit.inventory.meta.ItemMeta.class
                    .getMethod("get" + name).invoke(meta) : null;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not read " + name + " component", ex);
        }
    }

    private static void edit(String name, ItemStack item, String value) {
        try {
            Method method = editor().getDeclaredMethod(name, ItemStack.class, String.class);
            method.setAccessible(true);
            method.invoke(null, item, value);
        } catch (InvocationTargetException ex) {
            throw new IllegalStateException("Live advanced item editor rejected " + name, ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not invoke advanced item editor " + name, ex);
        }
    }

    private static void reject(String name, ItemStack item, String value) {
        ItemStack before = item.clone();
        try {
            Method method = editor().getDeclaredMethod(name, ItemStack.class, String.class);
            method.setAccessible(true);
            method.invoke(null, item, value);
            throw new IllegalStateException("Invalid " + name + " edit was accepted");
        } catch (InvocationTargetException ex) {
            require(ex.getCause() instanceof IllegalArgumentException,
                    "Invalid " + name + " edit failed for the wrong reason: " + ex.getCause());
            require(before.isSimilar(item), "Invalid " + name + " edit changed the item");
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not invoke advanced item editor " + name, ex);
        }
    }

    private static ItemStack parseText(String text) {
        try {
            Method method = editor().getDeclaredMethod("parseItemText", String.class);
            method.setAccessible(true);
            return (ItemStack) method.invoke(null, text);
        } catch (InvocationTargetException ex) {
            throw new IllegalStateException("Live item text import failed", ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not invoke item text import", ex);
        }
    }

    private static void rejectText(String text) {
        try {
            Method method = editor().getDeclaredMethod("parseItemText", String.class);
            method.setAccessible(true);
            method.invoke(null, text);
            throw new IllegalStateException("Invalid item text was accepted");
        } catch (InvocationTargetException ex) {
            require(ex.getCause() instanceof IllegalArgumentException,
                    "Invalid item text failed for the wrong reason: " + ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not invoke item text import", ex);
        }
    }

    private static Class<?> editor() throws ClassNotFoundException {
        ClassLoader loader = Bukkit.getPluginManager().getPlugin("RecipeWeaver")
                .getClass().getClassLoader();
        return Class.forName("dev.recipeweaver.core.ItemDraftEditor", true, loader);
    }

    private static NamespacedKey key(String value) {
        return new NamespacedKey("recipeweaver", value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
