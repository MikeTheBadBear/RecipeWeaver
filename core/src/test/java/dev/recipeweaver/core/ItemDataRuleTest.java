package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import dev.recipeweaver.api.GridRecipeDefinition;
import dev.recipeweaver.api.GridTier;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeKind;
import dev.recipeweaver.api.ResultMetadataMode;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.FireworkEffect;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.FireworkEffectMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.WritableBookMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.inventory.meta.components.ToolComponent;
import org.bukkit.inventory.meta.components.FoodComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class ItemDataRuleTest {
    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void itemTextImportAcceptsOneCompleteBoundedArgumentWithoutChangingTheOldDraft() {
        ItemStack oldDraft = new ItemStack(Material.BOOK);
        ItemMeta oldMeta = oldDraft.getItemMeta();
        oldMeta.setDisplayName("Keep on failure");
        oldDraft.setItemMeta(oldMeta);
        String text = "minecraft:paper[minecraft:custom_data={recipeweaver:{label:\"a ] b\",rank:7}}]";
        ItemStack imported = ItemDraftEditor.parseItemText(text, input -> {
            assertEquals(text, input);
            return new ItemStack(Material.PAPER);
        });
        assertEquals(Material.PAPER, imported.getType());
        assertEquals(1, imported.getAmount());
        assertEquals(Material.BOOK, oldDraft.getType());
        assertEquals("Keep on failure", oldDraft.getItemMeta().getDisplayName());
    }

    @Test void itemTextImportRejectsMalformedOrMultipleArgumentsBeforeCallingBukkit() {
        List<String> malformed = List.of("", "stone", "/give minecraft:paper",
                "minecraft:paper minecraft:diamond", "minecraft:paper[minecraft:custom_data={x:1}] "
                        + "minecraft:diamond",
                "minecraft:paper[minecraft:custom_data={x:1}][minecraft:damage=1]",
                "minecraft:paper[minecraft:custom_data={x:1]",
                "minecraft:paper[minecraft:custom_data={x:'unterminated}]",
                "minecraft:paper[minecraft:custom_data={x:1})]",
                "minecraft:paper\nminecraft:diamond",
                "minecraft:paper[" + "{".repeat(33) + "}".repeat(33) + "]",
                "minecraft:paper[" + "x".repeat(4096) + "]");
        for (String text : malformed) {
            assertThrows(IllegalArgumentException.class, () ->
                    ItemDraftEditor.parseItemText(text, ignored -> {
                        throw new AssertionError("Bukkit parser saw malformed text: " + text);
                    }), text);
        }
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.parseItemText("minecraft:air", ignored -> new ItemStack(Material.AIR)));
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.parseItemText("minecraft:paper", ignored -> new ItemStack(Material.PAPER, 2)));
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.parseItemText("minecraft:paper[minecraft:custom_data={x:}]",
                        ignored -> { throw new IllegalArgumentException("raw parser detail"); }));
        assertEquals("Invalid item text for this server version", invalid.getMessage());
    }

    @Test void typedPersistentListsSurviveBrowserEditingAndEncodeForYaml() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemDraftEditor.setPersistentData(item, "recipeweaver:labels\n@strings first label\nsecond label");
        ItemDraftEditor.setPersistentData(item, "recipeweaver:counts\n@intlist -2, 0, 19");
        ItemDraftEditor.setPersistentData(item, "recipeweaver:large\n@longlist 1, 9223372036854775807");

        var data = item.getItemMeta().getPersistentDataContainer();
        assertEquals(List.of("first label", "second label"),
                data.get(key("labels"), PersistentDataType.LIST.strings()));
        assertEquals(List.of(-2, 0, 19),
                data.get(key("counts"), PersistentDataType.LIST.integers()));
        assertEquals(List.of(1L, Long.MAX_VALUE),
                data.get(key("large"), PersistentDataType.LIST.longs()));
        var entries = ItemDraftEditor.persistentEntries(item);
        assertEquals("@strings first label\nsecond label", entry(entries, "labels").editorValue());
        assertEquals("@intlist -2, 0, 19", entry(entries, "counts").editorValue());
        assertEquals("@longlist 1, 9223372036854775807", entry(entries, "large").editorValue());

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("item", item);
        String encoded = yaml.saveToString();
        assertTrue(encoded.contains("recipeweaver:labels"));
        assertTrue(encoded.contains("recipeweaver:counts"));
        assertTrue(encoded.contains("recipeweaver:large"));
        assertTrue(encoded.contains("9223372036854775807"));
    }

    @Test void malformedPersistentListEditsDoNotMutateTheDraft() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemDraftEditor.setPersistentData(item, "recipeweaver:labels\n@strings valid");
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.setPersistentData(
                item, "recipeweaver:labels\n@strings valid\n"));
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.setPersistentData(
                item, "recipeweaver:counts\n@intlist 1, invalid"));
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.setPersistentData(
                item, "recipeweaver:large\n@longlist 1, 9223372036854775808"));
        assertEquals(List.of("valid"), item.getItemMeta().getPersistentDataContainer()
                .get(key("labels"), PersistentDataType.LIST.strings()));
        assertFalse(item.getItemMeta().getPersistentDataContainer().has(key("counts")));
        assertFalse(item.getItemMeta().getPersistentDataContainer().has(key("large")));
    }

    @Test void partialIngredientMatchingComparesTypedListsAndAllowsUnrelatedData() {
        ItemStack sample = new ItemStack(Material.PAPER);
        ItemDraftEditor.setPersistentData(sample, "recipeweaver:labels\n@strings alpha\nbeta");
        GridRecipeDefinition recipe = new GridRecipeDefinition(key("typed_list_match"),
                GridTier.ELITE, RecipeKind.SHAPED, 2,
                Arrays.asList(sample, null, null, null), new ItemStack(Material.DIAMOND),
                "", true, false,
                List.of(IngredientMatchMode.PARTIAL, IngredientMatchMode.MATERIAL,
                        IngredientMatchMode.MATERIAL, IngredientMatchMode.MATERIAL), 0, true);
        ItemStack actual = sample.clone();
        ItemDraftEditor.setPersistentData(actual, "recipeweaver:other\n@int 4");
        assertTrue(recipe.matchesItemSample(0, actual));
        ItemDraftEditor.setPersistentData(actual, "recipeweaver:labels\n@strings alpha\ngamma");
        assertFalse(recipe.matchesItemSample(0, actual));
    }

    @Test void nameOnlyIngredientMatchingIgnoresOtherFieldsButRequiresTheName() {
        ItemStack sample = new ItemStack(Material.PAPER);
        ItemMeta named = sample.getItemMeta();
        named.setDisplayName("Named sample");
        sample.setItemMeta(named);
        GridRecipeDefinition recipe = fieldMatchRecipe(sample, IngredientMatchMode.NAME_ONLY);
        ItemStack actual = sample.clone();
        ItemDraftEditor.setPersistentData(actual, "recipeweaver:rank\n@int 7");
        assertTrue(recipe.matchesItemSample(0, actual));
        ItemMeta changed = actual.getItemMeta();
        changed.setDisplayName("Other name");
        actual.setItemMeta(changed);
        assertFalse(recipe.matchesItemSample(0, actual));
        assertFalse(recipe.matchesItemSample(0, new ItemStack(Material.PAPER)));
    }

    @Test void loreOnlyIngredientMatchingIgnoresNamesButRejectsDifferentLines() {
        ItemStack sample = new ItemStack(Material.PAPER);
        ItemMeta sampleMeta = sample.getItemMeta();
        sampleMeta.setLore(List.of("First", "Second"));
        sample.setItemMeta(sampleMeta);
        GridRecipeDefinition recipe = fieldMatchRecipe(sample, IngredientMatchMode.LORE_ONLY);
        ItemStack actual = sample.clone();
        ItemMeta named = actual.getItemMeta();
        named.setDisplayName("Unrelated name");
        actual.setItemMeta(named);
        assertTrue(recipe.matchesItemSample(0, actual));
        ItemMeta changed = actual.getItemMeta();
        changed.setLore(List.of("First", "Different"));
        actual.setItemMeta(changed);
        assertFalse(recipe.matchesItemSample(0, actual));
        assertFalse(recipe.matchesItemSample(0, new ItemStack(Material.PAPER)));
    }

    @Test void modelOnlyIngredientMatchingIgnoresCustomDataButRejectsDifferentModels() {
        ItemStack sample = new ItemStack(Material.PAPER);
        ItemMeta model = sample.getItemMeta();
        model.setCustomModelData(42);
        sample.setItemMeta(model);
        GridRecipeDefinition recipe = fieldMatchRecipe(sample, IngredientMatchMode.MODEL_ONLY);
        ItemStack actual = sample.clone();
        ItemDraftEditor.setPersistentData(actual, "recipeweaver:rank\n@int 7");
        assertTrue(recipe.matchesItemSample(0, actual));
        ItemMeta changed = actual.getItemMeta();
        changed.setCustomModelData(43);
        actual.setItemMeta(changed);
        assertFalse(recipe.matchesItemSample(0, actual));
        assertFalse(recipe.matchesItemSample(0, new ItemStack(Material.PAPER)));
    }

    @Test void enchantmentsOnlyIngredientMatchingRejectsExtraOrChangedEnchantments() {
        var unbreaking = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));
        var efficiency = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("efficiency"));
        ItemStack sample = new ItemStack(Material.PAPER);
        ItemMeta enchant = sample.getItemMeta();
        enchant.addEnchant(unbreaking, 2, true);
        sample.setItemMeta(enchant);
        GridRecipeDefinition recipe = fieldMatchRecipe(sample,
                IngredientMatchMode.ENCHANTMENTS_ONLY);
        ItemStack actual = sample.clone();
        ItemMeta named = actual.getItemMeta();
        named.setDisplayName("Unrelated name");
        actual.setItemMeta(named);
        assertTrue(recipe.matchesItemSample(0, actual));
        ItemMeta changed = actual.getItemMeta();
        changed.addEnchant(efficiency, 1, true);
        actual.setItemMeta(changed);
        assertFalse(recipe.matchesItemSample(0, actual));
        changed.removeEnchant(efficiency);
        changed.addEnchant(unbreaking, 3, true);
        actual.setItemMeta(changed);
        assertFalse(recipe.matchesItemSample(0, actual));
    }

    @Test void damageOnlyIngredientMatchingChecksDamageAndIgnoresName() {
        ItemStack sample = new ItemStack(Material.IRON_SWORD);
        ItemMeta meta = sample.getItemMeta();
        ((org.bukkit.inventory.meta.Damageable) meta).setDamage(12);
        sample.setItemMeta(meta);
        GridRecipeDefinition recipe = fieldMatchRecipe(sample, IngredientMatchMode.DAMAGE_ONLY);
        ItemStack actual = sample.clone();
        ItemMeta named = actual.getItemMeta();
        named.setDisplayName("Unrelated name");
        actual.setItemMeta(named);
        assertTrue(recipe.matchesItemSample(0, actual));
        ItemMeta damaged = actual.getItemMeta();
        ((org.bukkit.inventory.meta.Damageable) damaged).setDamage(13);
        actual.setItemMeta(damaged);
        assertFalse(recipe.matchesItemSample(0, actual));
    }

    @Test void itemFlagsOnlyIngredientMatchingRequiresTheExactSet() {
        ItemStack sample = new ItemStack(Material.PAPER);
        ItemMeta meta = sample.getItemMeta();
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        sample.setItemMeta(meta);
        GridRecipeDefinition recipe = fieldMatchRecipe(sample, IngredientMatchMode.ITEM_FLAGS_ONLY);
        ItemStack actual = sample.clone();
        ItemMeta named = actual.getItemMeta();
        named.setDisplayName("Unrelated name");
        actual.setItemMeta(named);
        assertTrue(recipe.matchesItemSample(0, actual));
        ItemMeta flags = actual.getItemMeta();
        flags.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        actual.setItemMeta(flags);
        assertFalse(recipe.matchesItemSample(0, actual));
        assertFalse(recipe.matchesItemSample(0, new ItemStack(Material.PAPER)));
    }

    @Test void attributeOnlyIngredientMatchingRequiresTheCompleteModifierSet() {
        String damage = "minecraft:generic.attack_damage";
        ItemStack sample = new ItemStack(Material.DIAMOND_SWORD);
        ItemDraftEditor.setAttributeModifier(sample, damage + " mainhand 5 ADD_NUMBER");
        GridRecipeDefinition recipe = fieldMatchRecipe(sample,
                IngredientMatchMode.ATTRIBUTE_MODIFIERS_ONLY);
        ItemStack actual = new ItemStack(Material.DIAMOND_SWORD);
        ItemDraftEditor.setAttributeModifier(actual, damage + " mainhand 5 ADD_NUMBER");
        ItemMeta named = actual.getItemMeta();
        named.setDisplayName("Unrelated name");
        actual.setItemMeta(named);
        assertTrue(recipe.matchesItemSample(0, actual));
        ItemDraftEditor.setAttributeModifier(actual, damage + " mainhand 7 ADD_NUMBER");
        assertFalse(recipe.matchesItemSample(0, actual));
        ItemDraftEditor.setAttributeModifier(actual, damage + " mainhand 5 ADD_NUMBER");
        ItemDraftEditor.setAttributeModifier(actual, damage + " offhand 2 ADD_NUMBER");
        assertFalse(recipe.matchesItemSample(0, actual));
        assertFalse(recipe.matchesItemSample(0, new ItemStack(Material.DIAMOND_SWORD)));
    }

    @Test void unbreakableOnlyIngredientMatchingIgnoresOtherMetadata() {
        ItemStack sample = new ItemStack(Material.PAPER);
        ItemMeta meta = sample.getItemMeta();
        meta.setUnbreakable(true);
        sample.setItemMeta(meta);
        GridRecipeDefinition recipe = fieldMatchRecipe(sample,
                IngredientMatchMode.UNBREAKABLE_ONLY);
        ItemStack actual = new ItemStack(Material.PAPER);
        ItemMeta named = actual.getItemMeta();
        named.setDisplayName("Unrelated name");
        named.setUnbreakable(true);
        actual.setItemMeta(named);
        assertTrue(recipe.matchesItemSample(0, actual));
        named.setUnbreakable(false);
        actual.setItemMeta(named);
        assertFalse(recipe.matchesItemSample(0, actual));
        assertFalse(recipe.matchesItemSample(0, new ItemStack(Material.DIAMOND)));
    }

    @Test void armorTrimOnlyIngredientMatchingChecksTheCompleteTrim() {
        ArmorTrim sampleTrim = armorTrim("iron", "sentry");
        ArmorTrimFixture sample = new ArmorTrimFixture(sampleTrim);
        ArmorTrimFixture actual = new ArmorTrimFixture(sampleTrim);
        actual.name = "Unrelated name";
        assertTrue(GridRecipeDefinition.armorTrimMatches(sample.proxy(), actual.proxy()));
        actual.trim = armorTrim("iron", "coast");
        assertFalse(GridRecipeDefinition.armorTrimMatches(sample.proxy(), actual.proxy()));
        actual.trim = null;
        assertFalse(GridRecipeDefinition.armorTrimMatches(sample.proxy(), actual.proxy()));
        assertFalse(GridRecipeDefinition.armorTrimMatches(sample.proxy(),
                new ItemStack(Material.PAPER).getItemMeta()));
    }

    @Test void potionOnlyIngredientMatchingChecksBaseColorAndCustomEffects() {
        PotionEffect speed = new PotionEffect(PotionEffectType.SPEED, 200, 1);
        PotionFixture sample = new PotionFixture(PotionType.HEALING,
                Color.fromRGB(0x225577), List.of(speed));
        PotionFixture actual = new PotionFixture(PotionType.HEALING,
                Color.fromRGB(0x225577), List.of(speed));
        actual.name = "Unrelated name";
        assertTrue(GridRecipeDefinition.potionMatches(sample.proxy(), actual.proxy()));
        actual.base = PotionType.WEAKNESS;
        assertFalse(GridRecipeDefinition.potionMatches(sample.proxy(), actual.proxy()));
        actual.base = PotionType.HEALING;
        actual.color = Color.fromRGB(0x123456);
        assertFalse(GridRecipeDefinition.potionMatches(sample.proxy(), actual.proxy()));
        actual.color = Color.fromRGB(0x225577);
        actual.effects.clear();
        assertFalse(GridRecipeDefinition.potionMatches(sample.proxy(), actual.proxy()));
        assertFalse(GridRecipeDefinition.potionMatches(sample.proxy(),
                new ItemStack(Material.PAPER).getItemMeta()));
        assertTrue(GridRecipeDefinition.potionMatches(
                new PotionFixture(null, null, List.of()).proxy(),
                new PotionFixture(PotionType.WATER, null, List.of()).proxy()));
    }

    @Test void storedEnchantmentsOnlyIngredientMatchingRequiresExactBookContents() {
        var unbreaking = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));
        var efficiency = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("efficiency"));
        ItemStack sample = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta sampleMeta = (EnchantmentStorageMeta) sample.getItemMeta();
        sampleMeta.addStoredEnchant(unbreaking, 3, true);
        sample.setItemMeta(sampleMeta);
        GridRecipeDefinition recipe = fieldMatchRecipe(sample,
                IngredientMatchMode.STORED_ENCHANTMENTS_ONLY);
        ItemStack actual = sample.clone();
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) actual.getItemMeta();
        meta.setDisplayName("Unrelated name");
        actual.setItemMeta(meta);
        assertTrue(recipe.matchesItemSample(0, actual));
        meta = (EnchantmentStorageMeta) actual.getItemMeta();
        meta.addStoredEnchant(unbreaking, 2, true);
        actual.setItemMeta(meta);
        assertFalse(recipe.matchesItemSample(0, actual));
        actual = sample.clone();
        meta = (EnchantmentStorageMeta) actual.getItemMeta();
        meta.addStoredEnchant(efficiency, 1, true);
        actual.setItemMeta(meta);
        assertFalse(recipe.matchesItemSample(0, actual));
    }

    @Test void bannerPatternsOnlyIngredientMatchingRequiresOrderedPatterns() {
        ItemStack sample = new ItemStack(Material.WHITE_BANNER);
        BannerMeta sampleMeta = (BannerMeta) sample.getItemMeta();
        sampleMeta.addPattern(new Pattern(DyeColor.BLUE, PatternType.CIRCLE));
        sample.setItemMeta(sampleMeta);
        GridRecipeDefinition recipe = fieldMatchRecipe(sample,
                IngredientMatchMode.BANNER_PATTERNS_ONLY);
        ItemStack actual = sample.clone();
        BannerMeta meta = (BannerMeta) actual.getItemMeta();
        meta.setDisplayName("Unrelated name");
        actual.setItemMeta(meta);
        assertTrue(recipe.matchesItemSample(0, actual));
        meta = (BannerMeta) actual.getItemMeta();
        meta.setPattern(0, new Pattern(DyeColor.RED, PatternType.CIRCLE));
        actual.setItemMeta(meta);
        assertFalse(recipe.matchesItemSample(0, actual));
        actual = sample.clone();
        meta = (BannerMeta) actual.getItemMeta();
        meta.addPattern(new Pattern(DyeColor.RED, PatternType.CIRCLE));
        actual.setItemMeta(meta);
        assertFalse(recipe.matchesItemSample(0, actual));
    }

    @Test void crossbowProjectilesOnlyIngredientMatchingChecksNestedItemData() {
        ItemStack sample = new ItemStack(Material.CROSSBOW);
        CrossbowMeta sampleMeta = (CrossbowMeta) sample.getItemMeta();
        sampleMeta.setChargedProjectiles(List.of(namedArrow("Selected arrow")));
        sample.setItemMeta(sampleMeta);
        GridRecipeDefinition recipe = fieldMatchRecipe(sample,
                IngredientMatchMode.CROSSBOW_PROJECTILES_ONLY);
        ItemStack actual = sample.clone();
        ItemMeta unrelated = actual.getItemMeta();
        unrelated.setDisplayName("Unrelated crossbow name");
        actual.setItemMeta(unrelated);
        assertTrue(recipe.matchesItemSample(0, actual));
        CrossbowMeta changed = (CrossbowMeta) actual.getItemMeta();
        changed.setChargedProjectiles(List.of(namedArrow("Different arrow")));
        actual.setItemMeta(changed);
        assertFalse(recipe.matchesItemSample(0, actual));
        changed = (CrossbowMeta) actual.getItemMeta();
        changed.setChargedProjectiles(List.of());
        actual.setItemMeta(changed);
        assertFalse(recipe.matchesItemSample(0, actual));
    }

    @Test void fireworkOnlyIngredientMatchingChecksRocketPowerAndOrderedEffects() {
        FireworkEffect star = firework(FireworkEffect.Type.STAR, Color.BLUE);
        FireworkRocketFixture sample = new FireworkRocketFixture(2, List.of(star));
        FireworkRocketFixture actual = new FireworkRocketFixture(2, List.of(star));
        actual.name = "Unrelated rocket name";
        assertTrue(GridRecipeDefinition.fireworkMatches(sample.proxy(), actual.proxy()));
        actual.power = 3;
        assertFalse(GridRecipeDefinition.fireworkMatches(sample.proxy(), actual.proxy()));
        actual.power = 2;
        actual.effects.clear();
        actual.effects.add(firework(FireworkEffect.Type.BALL, Color.RED));
        assertFalse(GridRecipeDefinition.fireworkMatches(sample.proxy(), actual.proxy()));
        assertFalse(GridRecipeDefinition.fireworkMatches(sample.proxy(),
                new ItemStack(Material.PAPER).getItemMeta()));
    }

    @Test void fireworkOnlyIngredientMatchingChecksStarEffect() {
        ItemStack sample = new ItemStack(Material.FIREWORK_STAR);
        FireworkEffectMeta sampleMeta = (FireworkEffectMeta) sample.getItemMeta();
        sampleMeta.setEffect(firework(FireworkEffect.Type.CREEPER, Color.BLUE));
        sample.setItemMeta(sampleMeta);
        GridRecipeDefinition recipe = fieldMatchRecipe(sample, IngredientMatchMode.FIREWORK_ONLY);
        ItemStack actual = sample.clone();
        FireworkEffectMeta changed = (FireworkEffectMeta) actual.getItemMeta();
        changed.setDisplayName("Unrelated star name");
        actual.setItemMeta(changed);
        assertTrue(recipe.matchesItemSample(0, actual));
        changed = (FireworkEffectMeta) actual.getItemMeta();
        changed.setEffect(firework(FireworkEffect.Type.BALL, Color.RED));
        actual.setItemMeta(changed);
        assertFalse(recipe.matchesItemSample(0, actual));
        changed = (FireworkEffectMeta) actual.getItemMeta();
        changed.setEffect(null);
        actual.setItemMeta(changed);
        assertFalse(recipe.matchesItemSample(0, actual));
    }

    @Test void bundleContentsOnlyIngredientMatchingChecksNestedStacksAndOrder() {
        ItemStack sample = new ItemStack(Material.BUNDLE);
        BundleMeta sampleMeta = (BundleMeta) sample.getItemMeta();
        sampleMeta.setItems(List.of(namedArrow("First arrow"), new ItemStack(Material.DIAMOND, 2)));
        sample.setItemMeta(sampleMeta);
        GridRecipeDefinition recipe = fieldMatchRecipe(sample,
                IngredientMatchMode.BUNDLE_CONTENTS_ONLY);
        ItemStack actual = sample.clone();
        BundleMeta changed = (BundleMeta) actual.getItemMeta();
        changed.setDisplayName("Unrelated bundle name");
        actual.setItemMeta(changed);
        assertTrue(recipe.matchesItemSample(0, actual));
        changed = (BundleMeta) actual.getItemMeta();
        changed.setItems(List.of(namedArrow("Different arrow"),
                new ItemStack(Material.DIAMOND, 2)));
        actual.setItemMeta(changed);
        assertFalse(recipe.matchesItemSample(0, actual));
        changed = (BundleMeta) actual.getItemMeta();
        changed.setItems(List.of(new ItemStack(Material.DIAMOND, 2),
                namedArrow("First arrow")));
        actual.setItemMeta(changed);
        assertFalse(recipe.matchesItemSample(0, actual));
        changed = (BundleMeta) actual.getItemMeta();
        changed.setItems(List.of(namedArrow("First arrow"), new ItemStack(Material.DIAMOND, 1)));
        actual.setItemMeta(changed);
        assertFalse(recipe.matchesItemSample(0, actual));
    }

    private static ItemStack namedArrow(String name) {
        ItemStack arrow = new ItemStack(Material.ARROW);
        ItemMeta meta = arrow.getItemMeta();
        meta.setDisplayName(name);
        arrow.setItemMeta(meta);
        return arrow;
    }

    private static FireworkEffect firework(FireworkEffect.Type type, Color color) {
        return FireworkEffect.builder().with(type).withColor(color).build();
    }

    private static GridRecipeDefinition fieldMatchRecipe(ItemStack sample,
                                                          IngredientMatchMode mode) {
        return new GridRecipeDefinition(key("field_match_" + mode.name().toLowerCase()),
                GridTier.ELITE, RecipeKind.SHAPED, 2,
                Arrays.asList(sample, null, null, null), new ItemStack(Material.DIAMOND),
                "", true, false,
                List.of(mode, IngredientMatchMode.MATERIAL,
                        IngredientMatchMode.MATERIAL, IngredientMatchMode.MATERIAL), 0, true);
    }

    @Test void compoundEditorPreservesSiblingsBrowsesChildrenAndRejectsTypeCollisions() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemDraftEditor.setPersistentData(item,
                "recipeweaver:profile\n@compound recipeweaver:rank @int 4");
        ItemDraftEditor.setPersistentData(item,
                "recipeweaver:profile\n@compound recipeweaver:labels @strings alpha\nbeta");
        PersistentDataContainer profile = item.getItemMeta().getPersistentDataContainer()
                .get(key("profile"), PersistentDataType.TAG_CONTAINER);
        assertEquals(4, profile.get(key("rank"), PersistentDataType.INTEGER));
        assertEquals(List.of("alpha", "beta"),
                profile.get(key("labels"), PersistentDataType.LIST.strings()));
        assertEquals("compound", entry(ItemDraftEditor.persistentEntries(item), "profile").type());
        assertEquals("@compound recipeweaver:rank @int 4",
                entry(ItemDraftEditor.persistentCompoundEntries(item, key("profile")), "rank")
                        .editorValue());
        assertEquals("@compound recipeweaver:labels @strings alpha\nbeta",
                entry(ItemDraftEditor.persistentCompoundEntries(item, key("profile")), "labels")
                        .editorValue());

        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.setPersistentData(item,
                "recipeweaver:profile\n@compound recipeweaver:rank @int invalid"));
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.setPersistentData(item,
                "recipeweaver:profile\n@compound recipeweaver:rank @compound deeper @int 1"));
        assertEquals(4, item.getItemMeta().getPersistentDataContainer()
                .get(key("profile"), PersistentDataType.TAG_CONTAINER)
                .get(key("rank"), PersistentDataType.INTEGER));
        ItemDraftEditor.setPersistentData(item,
                "recipeweaver:profile\n@compound recipeweaver:rank none");
        assertFalse(item.getItemMeta().getPersistentDataContainer()
                .get(key("profile"), PersistentDataType.TAG_CONTAINER).has(key("rank")));
        ItemDraftEditor.setPersistentData(item,
                "recipeweaver:profile\n@compound recipeweaver:labels none");
        assertFalse(item.getItemMeta().getPersistentDataContainer().has(key("profile")));
        ItemDraftEditor.setPersistentData(item, "recipeweaver:profile\nplain text");
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.setPersistentData(item,
                "recipeweaver:profile\n@compound recipeweaver:rank @int 1"));
        assertEquals("plain text", item.getItemMeta().getPersistentDataContainer()
                .get(key("profile"), PersistentDataType.STRING));
    }

    @Test void partialIngredientMatchingChecksNestedCompoundLeaves() {
        ItemStack sample = new ItemStack(Material.PAPER);
        ItemDraftEditor.setPersistentData(sample,
                "recipeweaver:profile\n@compound recipeweaver:rank @int 4");
        GridRecipeDefinition recipe = new GridRecipeDefinition(key("compound_match"),
                GridTier.ELITE, RecipeKind.SHAPED, 2,
                Arrays.asList(sample, null, null, null), new ItemStack(Material.DIAMOND),
                "", true, false,
                List.of(IngredientMatchMode.PARTIAL, IngredientMatchMode.MATERIAL,
                        IngredientMatchMode.MATERIAL, IngredientMatchMode.MATERIAL), 0, true);
        // MockBukkit shallow-copies nested PDC values when cloning an item.
        ItemStack actual = new ItemStack(Material.PAPER);
        ItemDraftEditor.setPersistentData(actual,
                "recipeweaver:profile\n@compound recipeweaver:rank @int 4");
        ItemDraftEditor.setPersistentData(actual,
                "recipeweaver:profile\n@compound recipeweaver:label @string kept");
        assertTrue(recipe.matchesItemSample(0, actual));
        ItemDraftEditor.setPersistentData(actual,
                "recipeweaver:profile\n@compound recipeweaver:rank @int 5");
        assertFalse(recipe.matchesItemSample(0, actual));
        ItemDraftEditor.setPersistentData(actual, "recipeweaver:profile\nnot a compound");
        assertFalse(recipe.matchesItemSample(0, actual));
    }

    @Test void customDataOnlyIngredientMatchesTypedLeavesWhileIgnoringOtherItemMeta() {
        ItemStack sample = new ItemStack(Material.PAPER);
        ItemMeta sampleMeta = sample.getItemMeta();
        sampleMeta.setDisplayName("Sample name");
        sampleMeta.setCustomModelData(4);
        sample.setItemMeta(sampleMeta);
        ItemDraftEditor.setPersistentData(sample,
                "recipeweaver:profile\n@compound recipeweaver:rank @int 7");
        GridRecipeDefinition recipe = new GridRecipeDefinition(key("custom_data_only_match"),
                GridTier.ELITE, RecipeKind.SHAPED, 2,
                Arrays.asList(sample, null, null, null), new ItemStack(Material.DIAMOND),
                "", true, false,
                List.of(IngredientMatchMode.CUSTOM_DATA_ONLY, IngredientMatchMode.MATERIAL,
                        IngredientMatchMode.MATERIAL, IngredientMatchMode.MATERIAL), 0, true);
        ItemStack actual = new ItemStack(Material.PAPER);
        ItemMeta actualMeta = actual.getItemMeta();
        actualMeta.setDisplayName("Different name");
        actualMeta.setLore(List.of("Additional lore"));
        actualMeta.setCustomModelData(99);
        actual.setItemMeta(actualMeta);
        ItemDraftEditor.setPersistentData(actual,
                "recipeweaver:profile\n@compound recipeweaver:rank @int 7");
        ItemDraftEditor.setPersistentData(actual,
                "recipeweaver:profile\n@compound recipeweaver:note @string extra");
        assertTrue(recipe.matchesItemSample(0, actual));
        ItemDraftEditor.setPersistentData(actual,
                "recipeweaver:profile\n@compound recipeweaver:rank @int 8");
        assertFalse(recipe.matchesItemSample(0, actual));
        ItemStack wrongType = new ItemStack(Material.PAPER);
        ItemDraftEditor.setPersistentData(wrongType,
                "recipeweaver:profile\n@compound recipeweaver:rank @string 7");
        assertFalse(recipe.matchesItemSample(0, wrongType));
        assertFalse(recipe.matchesItemSample(0, new ItemStack(Material.PAPER)));
        assertFalse(recipe.matchesItemSample(0, new ItemStack(Material.BOOK)));
    }

    @Test void nameCopyIncludesItemNameAndClearsAbsentNames() {
        ItemStack source = new ItemStack(Material.PAPER);
        ItemMeta sourceMeta = source.getItemMeta();
        sourceMeta.setDisplayName("Display from source");
        sourceMeta.setItemName("Item from source");
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.DIAMOND);
        ItemMeta templateMeta = template.getItemMeta();
        templateMeta.setDisplayName("Old display");
        templateMeta.setItemName("Old item name");
        template.setItemMeta(templateMeta);

        ItemStack copied = GridResultMetadata.copyFrom(source, template, ResultMetadataMode.NAME);
        assertEquals("Display from source", copied.getItemMeta().getDisplayName());
        assertEquals("Item from source", copied.getItemMeta().getItemName());
        assertEquals("Old item name", template.getItemMeta().getItemName());
        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER), template,
                ResultMetadataMode.NAME);
        assertFalse(cleared.getItemMeta().hasDisplayName());
        assertFalse(cleared.getItemMeta().hasItemName());
    }

    @Test void commonResultCopyKeepsLegacyModelDataAndClearsMissingSourceData() {
        ItemStack source = new ItemStack(Material.PAPER);
        ItemMeta sourceMeta = source.getItemMeta();
        sourceMeta.setCustomModelData(42);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.DIAMOND);
        ItemMeta templateMeta = template.getItemMeta();
        templateMeta.setCustomModelData(9);
        template.setItemMeta(templateMeta);

        ItemStack copied = GridResultMetadata.copyFrom(source, template,
                ResultMetadataMode.ALL_COMMON);
        assertEquals(42, copied.getItemMeta().getCustomModelData());
        assertEquals(9, template.getItemMeta().getCustomModelData());
        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER), template,
                ResultMetadataMode.ALL_COMMON);
        assertFalse(cleared.getItemMeta().hasCustomModelData());
        assertEquals(9, template.getItemMeta().getCustomModelData());
    }

    @Test void nullableComponentBridgeCopiesClearsAndIsolatesNestedRemainder() {
        NullableComponentFixture source = new NullableComponentFixture();
        NullableComponentFixture target = new NullableComponentFixture();
        source.useRemainder = new ItemStack(Material.BUCKET);
        ItemDraftEditor.setPersistentData(source.useRemainder, "recipeweaver:rank\n@int 7");
        target.useRemainder = new ItemStack(Material.STICK);
        source.useCooldown = "3.5s:recipeweaver:group";
        target.useCooldown = "old";
        source.equippable = 2;
        target.equippable = 1;

        GridResultMetadata.copyNullableComponent(source, target, NullableComponentFixture.class,
                "UseRemainder");
        GridResultMetadata.copyNullableComponent(source, target, NullableComponentFixture.class,
                "UseCooldown");
        GridResultMetadata.copyNullableComponent(source, target, NullableComponentFixture.class,
                "Equippable");
        assertEquals(Material.BUCKET, target.useRemainder.getType());
        assertEquals(7, target.useRemainder.getItemMeta().getPersistentDataContainer()
                .get(key("rank"), PersistentDataType.INTEGER));
        assertNotSame(source.useRemainder, target.useRemainder);
        target.useRemainder.setAmount(2);
        assertEquals(1, source.useRemainder.getAmount());
        assertEquals("3.5s:recipeweaver:group", target.useCooldown);
        assertEquals(2, target.equippable);

        source.useRemainder = null;
        source.useCooldown = null;
        source.equippable = null;
        GridResultMetadata.copyNullableComponent(source, target, NullableComponentFixture.class,
                "UseRemainder");
        GridResultMetadata.copyNullableComponent(source, target, NullableComponentFixture.class,
                "UseCooldown");
        GridResultMetadata.copyNullableComponent(source, target, NullableComponentFixture.class,
                "Equippable");
        assertEquals(null, target.useRemainder);
        assertEquals(null, target.useCooldown);
        assertEquals(null, target.equippable);
        GridResultMetadata.copyNullableComponent(source, target, ItemMeta.class, "UseRemainder");
    }

    @Test void richModelDataListsValidateValuesAndLegacyServerRejectsWithoutMutation() {
        assertEquals(List.of(1.5F, -2.25F), ItemDraftEditor.modelFloats("1.5, -2.25"));
        assertEquals(List.of(true, false), ItemDraftEditor.modelFlags("true, FALSE"));
        assertEquals(List.of(Color.fromRGB(0x24A0ED), Color.fromRGB(0xFFAA00)),
                ItemDraftEditor.modelColors("#24A0ED, FFAA00"));
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.modelFloats("NaN"));
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.modelFloats("Infinity"));
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.modelFloats("1,"));
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.modelFlags("true, maybe"));
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.modelColors("#12345G"));
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.modelFlags(
                "true,".repeat(32) + "true"));

        ItemStack legacy = new ItemStack(Material.PAPER);
        ItemMeta meta = legacy.getItemMeta();
        meta.setCustomModelData(17);
        meta.setDisplayName("Preserved");
        legacy.setItemMeta(meta);
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.setRichCustomModelData(
                legacy, "@floats 1.5, 2.5"));
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.setRichCustomModelData(
                legacy, "none"));
        assertEquals(17, legacy.getItemMeta().getCustomModelData());
        assertEquals("Preserved", legacy.getItemMeta().getDisplayName());
    }

    @Test void useComponentPromptsValidateValuesAndLegacyServerRejectsWithoutMutation() {
        var cooldown = ItemDraftEditor.parseUseCooldown("3.5 recipeweaver:shared");
        assertEquals(3.5F, cooldown.seconds());
        assertEquals(key("shared"), cooldown.group());
        assertTrue(cooldown.changeGroup());
        assertFalse(ItemDraftEditor.parseUseCooldown("2.0").changeGroup());
        assertEquals(null, ItemDraftEditor.parseUseCooldown("none"));
        for (String invalid : List.of("0", "-1", "3601", "NaN", "Infinity",
                "2.0 invalid_key!", "2.0 group extra", "2.0\nrecipeweaver:shared")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ItemDraftEditor.parseUseCooldown(invalid), invalid);
        }

        var equip = ItemDraftEditor.parseEquippable("HEAD recipeweaver:wearable");
        assertEquals(EquipmentSlot.HEAD, equip.slot());
        assertEquals(key("wearable"), equip.model());
        assertTrue(equip.changeModel());
        assertFalse(ItemDraftEditor.parseEquippable("FEET").changeModel());
        assertEquals(null, ItemDraftEditor.parseEquippable("none"));
        assertEquals(null, ItemDraftEditor.parseEquippable("HEAD none").model());
        for (String invalid : List.of("WRIST", "HEAD bad_model!", "HEAD model extra",
                "HEAD\nrecipeweaver:wearable")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ItemDraftEditor.parseEquippable(invalid), invalid);
        }

        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("Preserved");
        item.setItemMeta(meta);
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.setUseRemainder(item, "none"));
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.setUseCooldown(item, "3.5 recipeweaver:shared"));
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.setEquippable(item, "HEAD recipeweaver:wearable"));
        assertEquals("Preserved", item.getItemMeta().getDisplayName());
    }

    @Test void customDataCopyCarriesTypedListsAcrossMaterialsWithoutMutatingInputs() {
        ItemStack source = new ItemStack(Material.PAPER);
        ItemDraftEditor.setPersistentData(source, "recipeweaver:labels\n@strings alpha\nbeta");
        ItemDraftEditor.setPersistentData(source, "recipeweaver:counts\n@intlist 3, 5");
        ItemStack template = new ItemStack(Material.DIAMOND);
        ItemDraftEditor.setPersistentData(template, "recipeweaver:counts\n@intlist 1");
        ItemStack copied = GridResultMetadata.copyFrom(source, template,
                ResultMetadataMode.NAME_LORE_CUSTOM_DATA);
        var resultData = copied.getItemMeta().getPersistentDataContainer();
        assertEquals(List.of("alpha", "beta"),
                resultData.get(key("labels"), PersistentDataType.LIST.strings()));
        assertEquals(List.of(3, 5),
                resultData.get(key("counts"), PersistentDataType.LIST.integers()));
        assertEquals(List.of(1), template.getItemMeta().getPersistentDataContainer()
                .get(key("counts"), PersistentDataType.LIST.integers()));
        assertEquals(List.of(3, 5), source.getItemMeta().getPersistentDataContainer()
                .get(key("counts"), PersistentDataType.LIST.integers()));
    }

    @Test void customDataCopyReplacesTemplateKeysAndClearsThemForAnEmptySource() {
        ItemStack source = new ItemStack(Material.PAPER);
        ItemDraftEditor.setPersistentData(source, "recipeweaver:source_only\n@int 7");
        ItemStack template = new ItemStack(Material.DIAMOND);
        ItemDraftEditor.setPersistentData(template, "recipeweaver:template_only\n@string stale");

        ItemStack copied = GridResultMetadata.copyFrom(source, template,
                ResultMetadataMode.NAME_LORE_CUSTOM_DATA);
        var copiedData = copied.getItemMeta().getPersistentDataContainer();
        assertEquals(7, copiedData.get(key("source_only"), PersistentDataType.INTEGER));
        assertFalse(copiedData.has(key("template_only")));
        assertEquals("stale", template.getItemMeta().getPersistentDataContainer()
                .get(key("template_only"), PersistentDataType.STRING));

        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER), template,
                ResultMetadataMode.NAME_LORE_CUSTOM_DATA);
        assertFalse(cleared.getItemMeta().getPersistentDataContainer()
                .has(key("template_only")));
        assertEquals(7, source.getItemMeta().getPersistentDataContainer()
                .get(key("source_only"), PersistentDataType.INTEGER));
    }

    @Test void customDataOnlyModeReplacesKeysWithoutSelectingOtherProperties() {
        assertTrue(ResultMetadataMode.CUSTOM_DATA_ONLY.copyCustomData());
        assertFalse(ResultMetadataMode.CUSTOM_DATA_ONLY.copyName());
        assertFalse(ResultMetadataMode.CUSTOM_DATA_ONLY.copyLore());
        assertFalse(ResultMetadataMode.CUSTOM_DATA_ONLY.copyModel());
        assertFalse(ResultMetadataMode.CUSTOM_DATA_ONLY.copyEnchantments());
        PdcFixture source = new PdcFixture();
        source.values.put(key("rank"), 7);
        PdcFixture target = new PdcFixture();
        target.values.put(key("stale"), "old");
        GridResultMetadata.copyCustomData(source.proxy(), target.proxy());
        assertEquals(Map.of(key("rank"), 7), target.values);
        assertEquals(Map.of(key("rank"), 7), source.values);
        GridResultMetadata.copyCustomData(new PdcFixture().proxy(), target.proxy());
        assertTrue(target.values.isEmpty());
    }

    @Test void modelOnlyModeCopiesAndClearsModelWithoutChangingOtherOutputFields() {
        ItemStack source = new ItemStack(Material.PAPER);
        ItemMeta sourceMeta = source.getItemMeta();
        sourceMeta.setCustomModelData(42);
        sourceMeta.setDisplayName("Source name");
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.DIAMOND);
        ItemMeta templateMeta = template.getItemMeta();
        templateMeta.setCustomModelData(9);
        templateMeta.setDisplayName("Output name");
        templateMeta.setLore(List.of("Output lore"));
        template.setItemMeta(templateMeta);

        ItemStack copied = GridResultMetadata.copyFrom(source, template,
                ResultMetadataMode.MODEL_ONLY);
        assertEquals(42, copied.getItemMeta().getCustomModelData());
        assertEquals("Output name", copied.getItemMeta().getDisplayName());
        assertEquals(List.of("Output lore"), copied.getItemMeta().getLore());
        assertEquals(9, template.getItemMeta().getCustomModelData());
        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER), template,
                ResultMetadataMode.MODEL_ONLY);
        assertFalse(cleared.getItemMeta().hasCustomModelData());
        assertEquals("Output name", cleared.getItemMeta().getDisplayName());
    }

    @Test void foodOnlyResultCopyReplacesOrClearsFoodWithoutTouchingOtherComponents() {
        assertFalse(ResultMetadataMode.FOOD_ONLY.copyCommonProperties());
        ComponentMetaFixture source = new ComponentMetaFixture();
        source.food = (FoodComponent) Proxy.newProxyInstance(FoodComponent.class.getClassLoader(),
                new Class<?>[] {FoodComponent.class}, (proxy, method, args) -> null);
        ComponentMetaFixture target = new ComponentMetaFixture();
        target.food = (FoodComponent) Proxy.newProxyInstance(FoodComponent.class.getClassLoader(),
                new Class<?>[] {FoodComponent.class}, (proxy, method, args) -> null);
        ToolComponent preservedTool = new ToolFixture().proxy();
        target.tool = preservedTool;

        GridResultMetadata.copyFoodComponent(source.proxy(), target.proxy());
        assertSame(source.food, target.food);
        assertSame(preservedTool, target.tool);
        source.food = null;
        GridResultMetadata.copyFoodComponent(source.proxy(), target.proxy());
        assertNull(target.food);
        assertSame(preservedTool, target.tool);
    }

    @Test void toolOnlyResultCopyReplacesOrClearsRulesWithoutTouchingFood() {
        assertFalse(ResultMetadataMode.TOOL_ONLY.copyCommonProperties());
        ComponentMetaFixture source = new ComponentMetaFixture();
        ToolFixture sourceTool = new ToolFixture();
        sourceTool.add(List.of(Material.STONE, Material.DIRT), 8.0F, true);
        source.tool = sourceTool.proxy();
        ComponentMetaFixture target = new ComponentMetaFixture();
        target.tool = new ToolFixture().proxy();
        FoodComponent preservedFood = (FoodComponent) Proxy.newProxyInstance(
                FoodComponent.class.getClassLoader(), new Class<?>[] {FoodComponent.class},
                (proxy, method, args) -> null);
        target.food = preservedFood;

        GridResultMetadata.copyToolComponent(source.proxy(), target.proxy());
        assertSame(source.tool, target.tool);
        assertSame(preservedFood, target.food);
        source.tool = null;
        GridResultMetadata.copyToolComponent(source.proxy(), target.proxy());
        assertNull(target.tool);
        assertSame(preservedFood, target.food);
    }

    @Test void attributeOnlyResultCopyCrossesMaterialAndLeavesTemplateNameIntact() {
        String attributeKey = "minecraft:generic.attack_damage";
        Attribute damage = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic.attack_damage"));
        ItemStack source = new ItemStack(Material.DIAMOND_SWORD);
        ItemDraftEditor.setAttributeModifier(source,
                attributeKey + " mainhand 7 ADD_NUMBER");
        ItemStack template = new ItemStack(Material.IRON_SWORD);
        ItemDraftEditor.setAttributeModifier(template,
                attributeKey + " offhand 3 ADD_NUMBER");
        ItemMeta name = template.getItemMeta();
        name.setDisplayName("Output name");
        template.setItemMeta(name);

        ItemStack copied = GridResultMetadata.copyFrom(source, template,
                ResultMetadataMode.ATTRIBUTE_MODIFIERS_ONLY);
        assertEquals(Material.IRON_SWORD, copied.getType());
        assertEquals("Output name", copied.getItemMeta().getDisplayName());
        assertEquals(7D, copied.getItemMeta().getAttributeModifiers(damage)
                .iterator().next().getAmount());
        // MockBukkit clones the attribute multimap shallowly; the live server
        // probe checks that copying leaves the configured template untouched.
        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.DIAMOND_SWORD),
                template, ResultMetadataMode.ATTRIBUTE_MODIFIERS_ONLY);
        assertFalse(cleared.getItemMeta().hasAttributeModifiers());
        assertEquals("Output name", cleared.getItemMeta().getDisplayName());
    }

    @Test void damageOnlyResultCopyClampsAcrossMaterialsAndPreservesOutputName() {
        ItemStack source = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta sourceMeta = source.getItemMeta();
        ((org.bukkit.inventory.meta.Damageable) sourceMeta).setDamage(23);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.IRON_SWORD);
        ItemMeta templateMeta = template.getItemMeta();
        ((org.bukkit.inventory.meta.Damageable) templateMeta).setDamage(5);
        templateMeta.setDisplayName("Output name");
        template.setItemMeta(templateMeta);

        ItemStack copied = GridResultMetadata.copyFrom(source, template,
                ResultMetadataMode.DAMAGE_ONLY);
        assertEquals(23, ((org.bukkit.inventory.meta.Damageable) copied.getItemMeta()).getDamage());
        assertEquals("Output name", copied.getItemMeta().getDisplayName());
        assertEquals(5, ((org.bukkit.inventory.meta.Damageable) template.getItemMeta()).getDamage());

        ItemStack gold = GridResultMetadata.copyFrom(source,
                new ItemStack(Material.GOLDEN_SWORD), ResultMetadataMode.DAMAGE_ONLY);
        assertEquals(23, ((org.bukkit.inventory.meta.Damageable) gold.getItemMeta()).getDamage());
        sourceMeta = source.getItemMeta();
        ((org.bukkit.inventory.meta.Damageable) sourceMeta).setDamage(100);
        source.setItemMeta(sourceMeta);
        ItemStack clamped = GridResultMetadata.copyFrom(source,
                new ItemStack(Material.GOLDEN_SWORD), ResultMetadataMode.DAMAGE_ONLY);
        assertEquals(0, ((org.bukkit.inventory.meta.Damageable) clamped.getItemMeta()).getDamage());
    }

    @Test void itemFlagsOnlyResultCopyReplacesAndClearsFlagsWithoutChangingName() {
        ItemStack source = new ItemStack(Material.PAPER);
        ItemMeta sourceMeta = source.getItemMeta();
        sourceMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.DIAMOND);
        ItemMeta templateMeta = template.getItemMeta();
        templateMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        templateMeta.setDisplayName("Output name");
        template.setItemMeta(templateMeta);

        ItemStack copied = GridResultMetadata.copyFrom(source, template,
                ResultMetadataMode.ITEM_FLAGS_ONLY);
        assertEquals(Set.of(ItemFlag.HIDE_ENCHANTS), copied.getItemMeta().getItemFlags());
        assertEquals("Output name", copied.getItemMeta().getDisplayName());
        assertEquals(Set.of(ItemFlag.HIDE_ATTRIBUTES), template.getItemMeta().getItemFlags());
        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER), template,
                ResultMetadataMode.ITEM_FLAGS_ONLY);
        assertTrue(cleared.getItemMeta().getItemFlags().isEmpty());
        assertEquals("Output name", cleared.getItemMeta().getDisplayName());
    }

    @Test void unbreakableOnlyResultCopySetsAndClearsAcrossMaterials() {
        ItemStack source = new ItemStack(Material.PAPER);
        ItemMeta sourceMeta = source.getItemMeta();
        sourceMeta.setUnbreakable(true);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.IRON_SWORD);
        ItemMeta templateMeta = template.getItemMeta();
        templateMeta.setDisplayName("Output name");
        template.setItemMeta(templateMeta);

        ItemStack copied = GridResultMetadata.copyFrom(source, template,
                ResultMetadataMode.UNBREAKABLE_ONLY);
        assertTrue(copied.getItemMeta().isUnbreakable());
        assertEquals("Output name", copied.getItemMeta().getDisplayName());
        assertFalse(template.getItemMeta().isUnbreakable());

        ItemMeta durableTemplate = template.getItemMeta();
        durableTemplate.setUnbreakable(true);
        template.setItemMeta(durableTemplate);
        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER),
                template, ResultMetadataMode.UNBREAKABLE_ONLY);
        assertFalse(cleared.getItemMeta().isUnbreakable());
        assertEquals("Output name", cleared.getItemMeta().getDisplayName());
        assertTrue(template.getItemMeta().isUnbreakable());
    }

    @Test void armorTrimOnlyResultCopyReplacesAndClearsAcrossArmorMaterials() {
        ArmorTrim sourceTrim = armorTrim("iron", "sentry");
        ArmorTrimFixture source = new ArmorTrimFixture(sourceTrim);
        ArmorTrimFixture target = new ArmorTrimFixture(armorTrim("gold", "coast"));
        target.name = "Output name";
        GridResultMetadata.copyArmorTrim(source.proxy(), target.proxy());
        assertEquals(sourceTrim, target.trim);
        assertEquals("Output name", target.name);
        assertEquals(sourceTrim, source.trim);

        source.trim = null;
        GridResultMetadata.copyArmorTrim(source.proxy(), target.proxy());
        assertNull(target.trim);
        assertEquals("Output name", target.name);
        target.trim = sourceTrim;
        GridResultMetadata.copyArmorTrim(new ItemStack(Material.PAPER).getItemMeta(),
                target.proxy());
        assertNull(target.trim);
    }

    @Test void potionOnlyResultCopyReplacesAndClearsAllPotionFields() {
        PotionEffect speed = new PotionEffect(PotionEffectType.SPEED, 200, 1);
        PotionFixture source = new PotionFixture(PotionType.HEALING,
                Color.fromRGB(0x225577), List.of(speed));
        PotionFixture target = new PotionFixture(PotionType.WEAKNESS,
                Color.fromRGB(0x123456),
                List.of(new PotionEffect(PotionEffectType.SPEED, 50, 0)));
        target.name = "Output name";
        GridResultMetadata.copyPotionFields(source.proxy(), target.proxy());
        assertEquals(PotionType.HEALING, target.base);
        assertEquals(Color.fromRGB(0x225577), target.color);
        assertEquals(List.of(speed), target.effects);
        assertEquals("Output name", target.name);
        assertEquals(PotionType.HEALING, source.base);
        assertEquals(List.of(speed), source.effects);

        GridResultMetadata.copyPotionFields(new ItemStack(Material.PAPER).getItemMeta(),
                target.proxy());
        assertEquals(PotionType.WATER, target.base);
        assertNull(target.color);
        assertTrue(target.effects.isEmpty());
        assertEquals("Output name", target.name);
    }

    @Test void storedEnchantmentsOnlyResultCopyReplacesAndClearsBookContents() {
        var unbreaking = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));
        var efficiency = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("efficiency"));
        ItemStack source = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta sourceMeta = (EnchantmentStorageMeta) source.getItemMeta();
        sourceMeta.addStoredEnchant(unbreaking, 3, true);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta templateMeta = (EnchantmentStorageMeta) template.getItemMeta();
        templateMeta.addStoredEnchant(efficiency, 2, true);
        templateMeta.setDisplayName("Output name");
        template.setItemMeta(templateMeta);

        ItemStack copied = GridResultMetadata.copyFrom(source, template,
                ResultMetadataMode.STORED_ENCHANTMENTS_ONLY);
        assertEquals(Map.of(unbreaking, 3),
                ((EnchantmentStorageMeta) copied.getItemMeta()).getStoredEnchants());
        assertEquals("Output name", copied.getItemMeta().getDisplayName());
        assertEquals(Map.of(efficiency, 2),
                ((EnchantmentStorageMeta) template.getItemMeta()).getStoredEnchants());

        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER),
                template, ResultMetadataMode.STORED_ENCHANTMENTS_ONLY);
        assertFalse(((EnchantmentStorageMeta) cleared.getItemMeta()).hasStoredEnchants());
        assertEquals("Output name", cleared.getItemMeta().getDisplayName());
    }

    @Test void bannerPatternsOnlyResultCopyReplacesAndClearsAcrossBannerColors() {
        ItemStack source = new ItemStack(Material.WHITE_BANNER);
        BannerMeta sourceMeta = (BannerMeta) source.getItemMeta();
        Pattern blue = new Pattern(DyeColor.BLUE, PatternType.CIRCLE);
        sourceMeta.addPattern(blue);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.RED_BANNER);
        BannerMeta templateMeta = (BannerMeta) template.getItemMeta();
        Pattern red = new Pattern(DyeColor.RED, PatternType.CIRCLE);
        templateMeta.addPattern(red);
        templateMeta.setDisplayName("Output name");
        template.setItemMeta(templateMeta);

        ItemStack copied = GridResultMetadata.copyFrom(source, template,
                ResultMetadataMode.BANNER_PATTERNS_ONLY);
        assertEquals(Material.RED_BANNER, copied.getType());
        assertEquals(List.of(blue), ((BannerMeta) copied.getItemMeta()).getPatterns());
        assertEquals("Output name", copied.getItemMeta().getDisplayName());
        assertEquals(List.of(red), ((BannerMeta) template.getItemMeta()).getPatterns());

        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER),
                template, ResultMetadataMode.BANNER_PATTERNS_ONLY);
        assertTrue(((BannerMeta) cleared.getItemMeta()).getPatterns().isEmpty());
        assertEquals("Output name", cleared.getItemMeta().getDisplayName());
    }

    @Test void crossbowProjectilesOnlyResultCopyClonesNestedItemAndClearsStaleCharge() {
        ItemStack source = new ItemStack(Material.CROSSBOW);
        CrossbowMeta sourceMeta = (CrossbowMeta) source.getItemMeta();
        sourceMeta.setChargedProjectiles(List.of(namedArrow("Selected arrow")));
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.CROSSBOW);
        CrossbowMeta templateMeta = (CrossbowMeta) template.getItemMeta();
        templateMeta.setChargedProjectiles(List.of(new ItemStack(Material.FIREWORK_ROCKET)));
        templateMeta.setDisplayName("Output name");
        template.setItemMeta(templateMeta);

        ItemStack copied = GridResultMetadata.copyFrom(source, template,
                ResultMetadataMode.CROSSBOW_PROJECTILES_ONLY);
        CrossbowMeta copiedMeta = (CrossbowMeta) copied.getItemMeta();
        assertEquals(List.of(namedArrow("Selected arrow")), copiedMeta.getChargedProjectiles());
        assertEquals("Output name", copiedMeta.getDisplayName());
        assertEquals(Material.FIREWORK_ROCKET,
                ((CrossbowMeta) template.getItemMeta()).getChargedProjectiles().get(0).getType());
        sourceMeta = (CrossbowMeta) source.getItemMeta();
        sourceMeta.setChargedProjectiles(List.of(namedArrow("Later source change")));
        source.setItemMeta(sourceMeta);
        assertEquals(List.of(namedArrow("Selected arrow")),
                ((CrossbowMeta) copied.getItemMeta()).getChargedProjectiles());

        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER), template,
                ResultMetadataMode.CROSSBOW_PROJECTILES_ONLY);
        assertTrue(((CrossbowMeta) cleared.getItemMeta()).getChargedProjectiles().isEmpty());
        assertEquals("Output name", cleared.getItemMeta().getDisplayName());
    }

    @Test void fireworkOnlyResultCopyReplacesRocketPowerAndEffectsThenClears() {
        FireworkEffect selected = firework(FireworkEffect.Type.STAR, Color.BLUE);
        FireworkRocketFixture source = new FireworkRocketFixture(2, List.of(selected));
        FireworkRocketFixture target = new FireworkRocketFixture(1,
                List.of(firework(FireworkEffect.Type.BALL, Color.RED)));
        target.name = "Output name";
        GridResultMetadata.copyFireworkPayload(source.proxy(), target.proxy());
        assertEquals(2, target.power);
        assertEquals(List.of(selected), target.effects);
        assertEquals("Output name", target.name);
        assertEquals(2, source.power);
        assertEquals(List.of(selected), source.effects);

        GridResultMetadata.copyFireworkPayload(new ItemStack(Material.PAPER).getItemMeta(),
                target.proxy());
        assertEquals(0, target.power);
        assertTrue(target.effects.isEmpty());
        assertEquals("Output name", target.name);
    }

    @Test void fireworkOnlyResultCopyReplacesStarEffectThenClears() {
        ItemStack source = new ItemStack(Material.FIREWORK_STAR);
        FireworkEffectMeta sourceMeta = (FireworkEffectMeta) source.getItemMeta();
        sourceMeta.setEffect(firework(FireworkEffect.Type.CREEPER, Color.BLUE));
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.FIREWORK_STAR);
        FireworkEffectMeta templateMeta = (FireworkEffectMeta) template.getItemMeta();
        templateMeta.setEffect(firework(FireworkEffect.Type.BALL, Color.RED));
        templateMeta.setDisplayName("Output name");
        template.setItemMeta(templateMeta);

        ItemStack copied = GridResultMetadata.copyFrom(source, template,
                ResultMetadataMode.FIREWORK_ONLY);
        FireworkEffectMeta copiedMeta = (FireworkEffectMeta) copied.getItemMeta();
        assertEquals(sourceMeta.getEffect(), copiedMeta.getEffect());
        assertEquals("Output name", copiedMeta.getDisplayName());
        assertEquals(templateMeta.getEffect(),
                ((FireworkEffectMeta) template.getItemMeta()).getEffect());

        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER), template,
                ResultMetadataMode.FIREWORK_ONLY);
        assertFalse(((FireworkEffectMeta) cleared.getItemMeta()).hasEffect());
        assertEquals("Output name", cleared.getItemMeta().getDisplayName());
    }

    @Test void bundleContentsOnlyResultCopyClonesNestedStacksAndClearsStaleContents() {
        ItemStack source = new ItemStack(Material.BUNDLE);
        BundleMeta sourceMeta = (BundleMeta) source.getItemMeta();
        sourceMeta.setItems(List.of(namedArrow("Selected arrow"),
                new ItemStack(Material.DIAMOND, 2)));
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.BUNDLE);
        BundleMeta templateMeta = (BundleMeta) template.getItemMeta();
        templateMeta.setItems(List.of(new ItemStack(Material.DIRT, 3)));
        templateMeta.setDisplayName("Output name");
        template.setItemMeta(templateMeta);

        ItemStack copied = GridResultMetadata.copyFrom(source, template,
                ResultMetadataMode.BUNDLE_CONTENTS_ONLY);
        BundleMeta copiedMeta = (BundleMeta) copied.getItemMeta();
        assertEquals(List.of(namedArrow("Selected arrow"),
                new ItemStack(Material.DIAMOND, 2)), copiedMeta.getItems());
        assertEquals("Output name", copiedMeta.getDisplayName());
        assertEquals(List.of(new ItemStack(Material.DIRT, 3)),
                ((BundleMeta) template.getItemMeta()).getItems());
        sourceMeta = (BundleMeta) source.getItemMeta();
        sourceMeta.setItems(List.of(new ItemStack(Material.PAPER)));
        source.setItemMeta(sourceMeta);
        assertEquals(List.of(namedArrow("Selected arrow"),
                new ItemStack(Material.DIAMOND, 2)),
                ((BundleMeta) copied.getItemMeta()).getItems());

        ItemStack emptyBundle = GridResultMetadata.copyFrom(new ItemStack(Material.BUNDLE),
                template, ResultMetadataMode.BUNDLE_CONTENTS_ONLY);
        assertTrue(((BundleMeta) emptyBundle.getItemMeta()).getItems().isEmpty());
        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER), template,
                ResultMetadataMode.BUNDLE_CONTENTS_ONLY);
        assertTrue(((BundleMeta) cleared.getItemMeta()).getItems().isEmpty());
        assertEquals("Output name", cleared.getItemMeta().getDisplayName());
    }

    @Test void bundleEditorAppendPreservesExistingNestedStacks() {
        ItemStack bundle = bundleWithNestedArrow();
        ItemDraftEditor.setBundleContents(bundle, "+ EMERALD 3");
        assertEquals(List.of(namedArrow("Keep arrow"), new ItemStack(Material.DIAMOND, 2),
                new ItemStack(Material.EMERALD, 3)),
                ((BundleMeta) bundle.getItemMeta()).getItems());
    }

    @Test void bundleEditorIndexedReplacePreservesOtherNestedStacks() {
        ItemStack bundle = bundleWithNestedArrow();
        ItemDraftEditor.setBundleContents(bundle, "2 EMERALD 3");
        assertEquals(List.of(namedArrow("Keep arrow"), new ItemStack(Material.EMERALD, 3)),
                ((BundleMeta) bundle.getItemMeta()).getItems());
    }

    @Test void bundleEditorIndexedRemovePreservesOtherNestedStacks() {
        ItemStack bundle = bundleWithNestedArrow();
        ItemDraftEditor.setBundleContents(bundle, "- 2");
        assertEquals(List.of(namedArrow("Keep arrow")),
                ((BundleMeta) bundle.getItemMeta()).getItems());
    }

    @Test void bundleEditorRejectsInvalidIndexWithoutChangingTheDraft() {
        ItemStack bundle = bundleWithNestedArrow();
        ItemStack old = bundle.clone();
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.setBundleContents(bundle, "3 EMERALD 3"));
        assertEquals(old, bundle);
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.setBundleContents(bundle, "- 0"));
        assertEquals(old, bundle);
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.setBundleContents(bundle, "+ INVALID_MATERIAL 1"));
        assertEquals(old, bundle);
    }

    @Test void bundleEditorRetainsReplaceAllAndClearSyntax() {
        ItemStack bundle = bundleWithNestedArrow();
        ItemDraftEditor.setBundleContents(bundle, "PAPER 1");
        assertEquals(List.of(new ItemStack(Material.PAPER)),
                ((BundleMeta) bundle.getItemMeta()).getItems());
        ItemDraftEditor.setBundleContents(bundle, "none");
        assertTrue(((BundleMeta) bundle.getItemMeta()).getItems().isEmpty());
    }

    private static ItemStack bundleWithNestedArrow() {
        ItemStack bundle = new ItemStack(Material.BUNDLE);
        BundleMeta meta = (BundleMeta) bundle.getItemMeta();
        meta.setItems(List.of(namedArrow("Keep arrow"), new ItemStack(Material.DIAMOND, 2)));
        bundle.setItemMeta(meta);
        return bundle;
    }

    private static ArmorTrim armorTrim(String material, String pattern) {
        TrimMaterial trimMaterial = Registry.TRIM_MATERIAL.get(NamespacedKey.minecraft(material));
        TrimPattern trimPattern = Registry.TRIM_PATTERN.get(NamespacedKey.minecraft(pattern));
        assertTrue(trimMaterial != null && trimPattern != null);
        return new ArmorTrim(trimMaterial, trimPattern);
    }

    @Test void enchantmentsOnlyModeReplacesEnchantsAndGlint() {
        assertTrue(ResultMetadataMode.ENCHANTMENTS_ONLY.copyEnchantments());
        assertFalse(ResultMetadataMode.ENCHANTMENTS_ONLY.copyCommonProperties());
        assertFalse(ResultMetadataMode.ENCHANTMENTS_ONLY.copyModel());
        var unbreaking = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));
        var sharpness = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("sharpness"));
        EnchantmentFixture source = new EnchantmentFixture();
        source.enchants.put(unbreaking, 2);
        source.glint = true;
        EnchantmentFixture target = new EnchantmentFixture();
        target.enchants.put(sharpness, 3);
        target.glint = false;
        GridResultMetadata.copyEnchantments(source.proxy(), target.proxy());
        assertEquals(Map.of(unbreaking, 2), target.enchants);
        assertEquals(true, target.glint);
        assertEquals(Map.of(unbreaking, 2), source.enchants);
        source.enchants.clear();
        source.glint = null;
        GridResultMetadata.copyEnchantments(source.proxy(), target.proxy());
        assertTrue(target.enchants.isEmpty());
        assertNull(target.glint);
    }

    @Test void newerComponentsRejectCleanlyOnTheLegacyTestServer() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("Preserved");
        item.setItemMeta(meta);
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.setItemModel(item, "recipeweaver:paper_model"));
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.setTooltipStyle(item, "recipeweaver:blue_tooltip"));
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.setEnchantable(item, "22"));
        assertEquals("Preserved", item.getItemMeta().getDisplayName());
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.setItemModel(item, "invalid key!"));
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.setEnchantable(item, "1025"));
    }

    @Test void gliderControlAcceptsOnlyBoundedBooleansAndRejectsOnLegacyServer() {
        assertTrue(ItemDraftEditor.parseGlider("true"));
        assertFalse(ItemDraftEditor.parseGlider("FALSE"));
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.parseGlider("yes"));
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.parseGlider("true\nfalse"));
        ItemStack item = namedPaper();
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.setGlider(item, "true"));
        assertEquals("Preserved", item.getItemMeta().getDisplayName());
    }

    @Test void tradeCostControlBoundsAndClearsValuesWithoutChangingLegacyItems() {
        assertEquals(0, ItemDraftEditor.parseAdditionalTradeCost("0"));
        assertEquals(1_000_000, ItemDraftEditor.parseAdditionalTradeCost("1000000"));
        assertNull(ItemDraftEditor.parseAdditionalTradeCost("none"));
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.parseAdditionalTradeCost("-1"));
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.parseAdditionalTradeCost("1000001"));
        ItemStack item = namedPaper();
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.setAdditionalTradeCost(item, "7"));
        assertEquals("Preserved", item.getItemMeta().getDisplayName());
    }

    @Test void attackChargeControlRejectsNonfiniteAndOutOfRangeValues() {
        assertEquals(Float.valueOf(0.75F), ItemDraftEditor.parseMinimumAttackCharge("0.75"));
        assertEquals(Float.valueOf(0.0F), ItemDraftEditor.parseMinimumAttackCharge("0"));
        assertNull(ItemDraftEditor.parseMinimumAttackCharge("none"));
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.parseMinimumAttackCharge("NaN"));
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.parseMinimumAttackCharge("1.1"));
        ItemStack item = namedPaper();
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.setMinimumAttackCharge(item, "0.75"));
        assertEquals("Preserved", item.getItemMeta().getDisplayName());
    }

    @Test void breakSoundControlRequiresOneBoundedKeyAndRejectsOnLegacyServer() {
        assertEquals(NamespacedKey.minecraft("block.glass.break"),
                ItemDraftEditor.parseBreakSoundKey("minecraft:block.glass.break"));
        assertNull(ItemDraftEditor.parseBreakSoundKey("none"));
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.parseBreakSoundKey("minecraft:glass break"));
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.parseBreakSoundKey("minecraft:" + "x".repeat(160)));
        ItemStack item = namedPaper();
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.setBreakSound(item, "minecraft:block.glass.break"));
        assertEquals("Preserved", item.getItemMeta().getDisplayName());
    }

    @Test void jukeboxControlParsesOneBoundedSongKeyAndRejectsOnLegacyServer() {
        assertEquals(NamespacedKey.minecraft("cat"),
                ItemDraftEditor.parseJukeboxSongKey("minecraft:cat"));
        assertNull(ItemDraftEditor.parseJukeboxSongKey("none"));
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.parseJukeboxSongKey("minecraft:cat\nminecraft:13"));
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.parseJukeboxSongKey("minecraft:" + "x".repeat(160)));
        ItemStack item = namedPaper();
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.setJukeboxPlayable(item, "minecraft:cat"));
        assertEquals("Preserved", item.getItemMeta().getDisplayName());
    }

    @Test void damageResistanceControlParsesATagAndRejectsOnLegacyServer() {
        assertEquals(NamespacedKey.minecraft("is_fire"),
                ItemDraftEditor.parseDamageResistanceTag("minecraft:is_fire"));
        assertNull(ItemDraftEditor.parseDamageResistanceTag("none"));
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.parseDamageResistanceTag("is_fire"));
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.parseDamageResistanceTag("minecraft:is_fire\nextra"));
        ItemStack item = namedPaper();
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.setDamageResistance(item, "minecraft:is_fire"));
        assertEquals("Preserved", item.getItemMeta().getDisplayName());
    }

    @Test void writableBookPagesCanBeEditedCopiedAndCleared() {
        ItemStack source = new ItemStack(Material.WRITABLE_BOOK);
        ItemDraftEditor.setBookPages(source, "First page\n---PAGE---\nSecond page");
        assertEquals(List.of("First page", "Second page"),
                ((WritableBookMeta) source.getItemMeta()).getPages());
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.setBookPages(source, "First page\n---PAGE---\n"));

        ItemStack template = new ItemStack(Material.WRITABLE_BOOK);
        ItemDraftEditor.setBookPages(template, "Old page");
        ItemStack copied = GridResultMetadata.copyFrom(source, template,
                ResultMetadataMode.ALL_COMMON);
        assertEquals(List.of("First page", "Second page"),
                ((WritableBookMeta) copied.getItemMeta()).getPages());
        assertEquals(List.of("Old page"), ((WritableBookMeta) template.getItemMeta()).getPages());

        ItemDraftEditor.setBookPages(source, "none");
        assertTrue(((WritableBookMeta) source.getItemMeta()).getPages().isEmpty());
        ItemStack cleared = GridResultMetadata.copyFrom(source, template,
                ResultMetadataMode.ALL_COMMON);
        assertTrue(((WritableBookMeta) cleared.getItemMeta()).getPages().isEmpty());
        assertEquals(List.of("Old page"), ((WritableBookMeta) template.getItemMeta()).getPages());
    }

    @Test void headProfileAndNoteSoundInputsAreBoundedAndTypeSpecific() {
        String texture = "a".repeat(64);
        assertEquals("ExampleHead", ItemDraftEditor.parseHeadProfile("ExampleHead " + texture)
                .name());
        assertEquals(texture, ItemDraftEditor.parseHeadProfile("ExampleHead " + texture)
                .textureHash());
        assertNull(ItemDraftEditor.parseHeadProfile("none"));
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.parseHeadProfile("invalid name"));
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.parseHeadProfile("ExampleHead " + "a".repeat(63)));
        assertEquals(NamespacedKey.minecraft("block.note_block.harp"),
                ItemDraftEditor.parseHeadNoteBlockSound("minecraft:block.note_block.harp"));
        assertNull(ItemDraftEditor.parseHeadNoteBlockSound("none"));
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.parseHeadNoteBlockSound("block.note_block.harp"));
        ItemStack paper = namedPaper();
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.setHeadProfile(paper, "ExampleHead"));
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.setHeadNoteBlockSound(paper, "minecraft:block.note_block.harp"));
        assertEquals("Preserved", paper.getItemMeta().getDisplayName());
    }

    @Test void headNoteSoundResultCopyReplacesAndClearsTheTemplate() {
        ItemStack source = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sourceMeta = (SkullMeta) source.getItemMeta();
        sourceMeta.setNoteBlockSound(NamespacedKey.minecraft("entity.zombie.ambient"));
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta templateMeta = (SkullMeta) template.getItemMeta();
        templateMeta.setNoteBlockSound(NamespacedKey.minecraft("entity.skeleton.ambient"));
        template.setItemMeta(templateMeta);

        ItemStack copied = GridResultMetadata.copyFrom(source, template,
                ResultMetadataMode.ALL_COMMON);
        assertEquals(NamespacedKey.minecraft("entity.zombie.ambient"),
                ((SkullMeta) copied.getItemMeta()).getNoteBlockSound());
        assertEquals(NamespacedKey.minecraft("entity.skeleton.ambient"),
                ((SkullMeta) template.getItemMeta()).getNoteBlockSound());
        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PLAYER_HEAD),
                template, ResultMetadataMode.ALL_COMMON);
        assertNull(((SkullMeta) cleared.getItemMeta()).getNoteBlockSound());
    }

    @Test void shulkerEditorParsesBoundedSlotsAndRejectsNestedBoxes() {
        var edit = ItemDraftEditor.parseShulkerEdit("27 DIAMOND 2");
        assertEquals(26, edit.slot());
        assertEquals(Material.DIAMOND, edit.stack().getType());
        assertEquals(2, edit.stack().getAmount());
        assertEquals(0, ItemDraftEditor.parseShulkerEdit("1 none").slot());
        assertNull(ItemDraftEditor.parseShulkerEdit("1 none").stack());
        assertEquals(-1, ItemDraftEditor.parseShulkerEdit("none").slot());
        for (String invalid : List.of("0 DIAMOND 1", "28 DIAMOND 1", "1 DIAMOND 65",
                "1 ENDER_PEARL 17", "1 SHULKER_BOX 1", "1 STONE 0", "1 unknown 1",
                "1 DIAMOND", "1 DIAMOND 1 extra", "1 DIAMOND 1\n2 DIRT 1")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ItemDraftEditor.parseShulkerEdit(invalid), invalid);
        }
    }

    @Test void shulkerSlotEditPreservesOtherSlotsAndCanEmptyContents() {
        // MockBukkit does not implement BlockStateMeta for shulker items, so
        // exercise the same inventory operation used by the live editor.
        Inventory inventory = Bukkit.createInventory(null, 27);
        ItemDraftEditor.applyShulkerEdit(inventory,
                ItemDraftEditor.parseShulkerEdit("1 DIAMOND 2"));
        ItemDraftEditor.applyShulkerEdit(inventory,
                ItemDraftEditor.parseShulkerEdit("27 DIRT 3"));
        assertEquals(2, inventory.getItem(0).getAmount());
        assertEquals(3, inventory.getItem(26).getAmount());
        ItemDraftEditor.applyShulkerEdit(inventory,
                ItemDraftEditor.parseShulkerEdit("1 none"));
        assertNull(inventory.getItem(0));
        assertEquals(Material.DIRT, inventory.getItem(26).getType());
        ItemDraftEditor.applyShulkerEdit(inventory,
                ItemDraftEditor.parseShulkerEdit("none"));
        assertNull(inventory.getItem(26));
    }

    @Test void shulkerSnapshotCopyReplacesStaleSlotsAndIsolatesSource() {
        Inventory source = Bukkit.createInventory(null, 27);
        source.setItem(0, new ItemStack(Material.DIAMOND, 2));
        Inventory result = Bukkit.createInventory(null, 27);
        result.setItem(0, new ItemStack(Material.DIRT, 4));
        result.setItem(26, new ItemStack(Material.EMERALD, 3));
        GridResultMetadata.copyShulkerInventory(source, result);
        assertEquals(Material.DIAMOND, result.getItem(0).getType());
        assertEquals(2, result.getItem(0).getAmount());
        assertNull(result.getItem(26));
        result.setItem(0, new ItemStack(Material.STONE));
        assertEquals(Material.DIAMOND, source.getItem(0).getType());
        GridResultMetadata.copyShulkerInventory(Bukkit.createInventory(null, 27), result);
        assertNull(result.getItem(0));
    }

    @Test void unlockedShulkerCopyClearsTemplateLockWithoutPassingAnEmptyLockToSpigot() {
        ShulkerFixture source = new ShulkerFixture("", null);
        source.inventory.setItem(0, new ItemStack(Material.DIAMOND, 2));
        ShulkerFixture target = new ShulkerFixture("old-key", "old-name");
        target.inventory.setItem(0, new ItemStack(Material.DIRT));

        GridResultMetadata.copyShulkerState(source.proxy(), target.proxy());

        assertNull(target.lock);
        assertNull(target.name);
        assertEquals(Material.DIAMOND, target.inventory.getItem(0).getType());
        assertEquals(2, target.inventory.getItem(0).getAmount());
        target.inventory.setItem(0, new ItemStack(Material.STONE));
        assertEquals(Material.DIAMOND, source.inventory.getItem(0).getType());

        ShulkerFixture locked = new ShulkerFixture("new-key", "new-name");
        GridResultMetadata.copyShulkerState(locked.proxy(), target.proxy());
        assertEquals("new-key", target.lock);
        assertEquals("new-name", target.name);
        assertNull(target.inventory.getItem(0));
    }

    @Test void groupedToolRuleReplacementSplitsOnlyTheChosenBlock() {
        ToolFixture tool = new ToolFixture();
        tool.add(List.of(Material.STONE, Material.DIRT), 8.0F, true);
        tool.add(List.of(Material.OAK_LOG), 4.0F, false);

        ItemDraftEditor.updateToolRule(tool.proxy(), Material.STONE, false, 9.0F, false);

        assertEquals(3, tool.rules.size());
        ToolComponent.ToolRule stone = tool.ruleFor(Material.STONE);
        assertEquals(List.of(Material.STONE), List.copyOf(stone.getBlocks()));
        assertEquals(9.0F, stone.getSpeed());
        assertFalse(stone.isCorrectForDrops());
        ToolComponent.ToolRule dirt = tool.ruleFor(Material.DIRT);
        assertEquals(List.of(Material.DIRT), List.copyOf(dirt.getBlocks()));
        assertEquals(8.0F, dirt.getSpeed());
        assertTrue(dirt.isCorrectForDrops());
        assertEquals(4.0F, tool.ruleFor(Material.OAK_LOG).getSpeed());
    }

    @Test void groupedToolRuleRemovalSplitsEveryMatchingRule() {
        ToolFixture tool = new ToolFixture();
        tool.add(List.of(Material.STONE, Material.DIRT), 8.0F, true);
        tool.add(List.of(Material.STONE, Material.GRANITE), 6.0F, false);
        tool.add(List.of(Material.OAK_LOG), 4.0F, true);

        ItemDraftEditor.updateToolRule(tool.proxy(), Material.STONE, true, 0, false);

        assertEquals(3, tool.rules.size());
        assertTrue(tool.rules.stream().noneMatch(rule -> rule.getBlocks().contains(Material.STONE)));
        assertEquals(List.of(Material.DIRT), List.copyOf(tool.ruleFor(Material.DIRT).getBlocks()));
        assertEquals(8.0F, tool.ruleFor(Material.DIRT).getSpeed());
        assertTrue(tool.ruleFor(Material.DIRT).isCorrectForDrops());
        assertEquals(List.of(Material.GRANITE),
                List.copyOf(tool.ruleFor(Material.GRANITE).getBlocks()));
        assertEquals(6.0F, tool.ruleFor(Material.GRANITE).getSpeed());
        assertFalse(tool.ruleFor(Material.GRANITE).isCorrectForDrops());
        assertEquals(4.0F, tool.ruleFor(Material.OAK_LOG).getSpeed());
    }

    private static ItemStack namedPaper() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("Preserved");
        item.setItemMeta(meta);
        return item;
    }

    @Test void commonResultCopyTransfersFoodPropertiesAndClearsTemplateFood() {
        ItemStack source = new ItemStack(Material.PAPER);
        ItemDraftEditor.setFoodProperties(source, "5 1.25 true 2.5");
        ItemStack template = new ItemStack(Material.DIAMOND);
        ItemDraftEditor.setFoodProperties(template, "2 0.5 false 1.0");
        ItemStack copied = GridResultMetadata.copyFrom(source, template,
                ResultMetadataMode.ALL_COMMON);
        assertEquals(5, copied.getItemMeta().getFood().getNutrition());
        assertEquals(1.25F, copied.getItemMeta().getFood().getSaturation());
        assertTrue(copied.getItemMeta().getFood().canAlwaysEat());
        assertEquals(2.5F, copied.getItemMeta().getFood().getEatSeconds());
        assertEquals(2, template.getItemMeta().getFood().getNutrition());

        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER),
                template, ResultMetadataMode.ALL_COMMON);
        assertFalse(cleared.getItemMeta().hasFood());
    }

    @Test void attributeEditorReplacesOneSlotWithoutDiscardingOtherModifiers() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        String attributeKey = "minecraft:generic.attack_damage";
        Attribute damage = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic.attack_damage"));
        ItemDraftEditor.setAttributeModifier(sword,
                attributeKey + " mainhand 5 ADD_NUMBER");
        ItemDraftEditor.setAttributeModifier(sword,
                attributeKey + " offhand 2 ADD_SCALAR");
        ItemDraftEditor.setAttributeModifier(sword,
                attributeKey + " mainhand 7 ADD_NUMBER");
        var modifiers = sword.getItemMeta().getAttributeModifiers(damage);
        assertEquals(2, modifiers.size());
        assertTrue(modifiers.stream().anyMatch(modifier -> modifier.getAmount() == 7
                && modifier.getOperation() == AttributeModifier.Operation.ADD_NUMBER));
        assertTrue(modifiers.stream().anyMatch(modifier -> modifier.getAmount() == 2
                && modifier.getOperation() == AttributeModifier.Operation.ADD_SCALAR));
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.setAttributeModifier(
                sword, attributeKey + " mainhand NaN ADD_NUMBER"));
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.setAttributeModifier(
                sword, attributeKey + " mainhand 8 UNKNOWN"));
        assertEquals(2, sword.getItemMeta().getAttributeModifiers(damage).size());

        ItemDraftEditor.setAttributeModifier(sword, attributeKey + " mainhand none");
        assertEquals(1, sword.getItemMeta().getAttributeModifiers(damage).size());
    }

    @Test void commonResultCopyReplacesAttributeModifiersAndKeepsTheTemplate() {
        String attributeKey = "minecraft:generic.attack_damage";
        Attribute damage = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic.attack_damage"));
        ItemStack source = new ItemStack(Material.DIAMOND_SWORD);
        ItemDraftEditor.setAttributeModifier(source,
                attributeKey + " mainhand 7 ADD_NUMBER");
        ItemStack template = new ItemStack(Material.IRON_SWORD);
        ItemDraftEditor.setAttributeModifier(template,
                attributeKey + " offhand 3 ADD_NUMBER");
        ItemStack copied = GridResultMetadata.copyFrom(source, template,
                ResultMetadataMode.ALL_COMMON);
        var inherited = copied.getItemMeta().getAttributeModifiers(damage);
        assertEquals(1, inherited.size());
        assertEquals(7D, inherited.iterator().next().getAmount());
        assertEquals(3D, template.getItemMeta().getAttributeModifiers(damage)
                .iterator().next().getAmount());
        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.DIAMOND_SWORD),
                template, ResultMetadataMode.ALL_COMMON);
        assertFalse(cleared.getItemMeta().hasAttributeModifiers());
    }

    @Test void partialIngredientMatchingRequiresSampleModifiersButAllowsExtras() {
        String attributeKey = "minecraft:generic.attack_damage";
        ItemStack sample = new ItemStack(Material.DIAMOND_SWORD);
        ItemDraftEditor.setAttributeModifier(sample,
                attributeKey + " mainhand 7 ADD_NUMBER");
        GridRecipeDefinition recipe = new GridRecipeDefinition(key("attribute_partial"),
                GridTier.ELITE, RecipeKind.SHAPED, 2,
                Arrays.asList(sample, null, null, null), new ItemStack(Material.DIAMOND),
                "", true, false,
                List.of(IngredientMatchMode.PARTIAL, IngredientMatchMode.MATERIAL,
                        IngredientMatchMode.MATERIAL, IngredientMatchMode.MATERIAL), 0, true);
        ItemStack actual = sample.clone();
        ItemDraftEditor.setAttributeModifier(actual,
                attributeKey + " offhand 2 ADD_SCALAR");
        assertTrue(recipe.matchesItemSample(0, actual));
        ItemDraftEditor.setAttributeModifier(actual,
                attributeKey + " mainhand 8 ADD_NUMBER");
        assertFalse(recipe.matchesItemSample(0, actual));
        ItemDraftEditor.setAttributeModifier(actual,
                attributeKey + " mainhand none");
        assertFalse(recipe.matchesItemSample(0, actual));
    }

    @Test void partialIngredientMatchingRequiresSampleFlagsButAllowsExtras() {
        ItemStack sample = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta sampleMeta = sample.getItemMeta();
        sampleMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        sample.setItemMeta(sampleMeta);
        GridRecipeDefinition recipe = new GridRecipeDefinition(key("flag_partial"),
                GridTier.ELITE, RecipeKind.SHAPED, 2,
                Arrays.asList(sample, null, null, null), new ItemStack(Material.DIAMOND),
                "", true, false,
                List.of(IngredientMatchMode.PARTIAL, IngredientMatchMode.MATERIAL,
                        IngredientMatchMode.MATERIAL, IngredientMatchMode.MATERIAL), 0, true);
        ItemStack actual = sample.clone();
        ItemMeta actualMeta = actual.getItemMeta();
        actualMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        actual.setItemMeta(actualMeta);
        assertTrue(recipe.matchesItemSample(0, actual));
        actualMeta = actual.getItemMeta();
        actualMeta.removeItemFlags(ItemFlag.HIDE_ENCHANTS);
        actual.setItemMeta(actualMeta);
        assertFalse(recipe.matchesItemSample(0, actual));
        assertTrue(sample.getItemMeta().hasItemFlag(ItemFlag.HIDE_ENCHANTS));
    }

    private static final class ComponentMetaFixture implements InvocationHandler {
        FoodComponent food;
        ToolComponent tool;

        ItemMeta proxy() {
            return (ItemMeta) Proxy.newProxyInstance(ItemMeta.class.getClassLoader(),
                    new Class<?>[] {ItemMeta.class}, this);
        }

        @Override public Object invoke(Object proxy, java.lang.reflect.Method method,
                                       Object[] args) {
            return switch (method.getName()) {
                case "hasFood" -> food != null;
                case "getFood" -> food;
                case "setFood" -> { food = (FoodComponent) args[0]; yield null; }
                case "hasTool" -> tool != null;
                case "getTool" -> tool;
                case "setTool" -> { tool = (ToolComponent) args[0]; yield null; }
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }

    private static final class ToolFixture implements InvocationHandler {
        List<ToolComponent.ToolRule> rules = new ArrayList<>();

        ToolComponent proxy() {
            return (ToolComponent) Proxy.newProxyInstance(ToolComponent.class.getClassLoader(),
                    new Class<?>[] {ToolComponent.class}, this);
        }

        void add(Collection<Material> blocks, Float speed, Boolean drops) {
            rules.add(new ToolRuleFixture(blocks, speed, drops).proxy());
        }

        ToolComponent.ToolRule ruleFor(Material material) {
            return rules.stream().filter(rule -> rule.getBlocks().contains(material))
                    .findFirst().orElseThrow();
        }

        @Override public Object invoke(Object proxy, java.lang.reflect.Method method,
                                       Object[] args) {
            return switch (method.getName()) {
                case "getRules" -> List.copyOf(rules);
                case "setRules" -> {
                    rules = new ArrayList<>();
                    for (Object rule : (List<?>) args[0]) rules.add((ToolComponent.ToolRule) rule);
                    yield null;
                }
                case "addRule" -> {
                    Collection<Material> blocks = args[0] instanceof Material material
                            ? List.of(material) : (Collection<Material>) args[0];
                    ToolComponent.ToolRule rule = new ToolRuleFixture(blocks,
                            (Float) args[1], (Boolean) args[2]).proxy();
                    rules.add(rule);
                    yield rule;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }

    private static final class ToolRuleFixture implements InvocationHandler {
        List<Material> blocks;
        final Float speed;
        final Boolean drops;

        ToolRuleFixture(Collection<Material> blocks, Float speed, Boolean drops) {
            this.blocks = new ArrayList<>(blocks);
            this.speed = speed;
            this.drops = drops;
        }

        ToolComponent.ToolRule proxy() {
            return (ToolComponent.ToolRule) Proxy.newProxyInstance(
                    ToolComponent.ToolRule.class.getClassLoader(),
                    new Class<?>[] {ToolComponent.ToolRule.class}, this);
        }

        @Override public Object invoke(Object proxy, java.lang.reflect.Method method,
                                       Object[] args) {
            return switch (method.getName()) {
                case "getBlocks" -> List.copyOf(blocks);
                case "setBlocks" -> {
                    if (!(args[0] instanceof Collection<?> collection)) {
                        throw new UnsupportedOperationException("Expected explicit block collection");
                    }
                    blocks = new ArrayList<>();
                    for (Object block : collection) blocks.add((Material) block);
                    yield null;
                }
                case "getSpeed" -> speed;
                case "isCorrectForDrops" -> drops;
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }

    private static final class ShulkerFixture implements InvocationHandler {
        final Inventory inventory = Bukkit.createInventory(null, 27);
        String lock;
        String name;

        ShulkerFixture(String lock, String name) {
            this.lock = lock;
            this.name = name;
        }

        ShulkerBox proxy() {
            return (ShulkerBox) Proxy.newProxyInstance(ShulkerBox.class.getClassLoader(),
                    new Class<?>[] {ShulkerBox.class}, this);
        }

        @Override public Object invoke(Object proxy, java.lang.reflect.Method method,
                                       Object[] args) {
            return switch (method.getName()) {
                case "getSnapshotInventory" -> inventory;
                case "getCustomName" -> name;
                case "getLock" -> lock;
                case "setCustomName" -> { name = (String) args[0]; yield null; }
                case "setLock" -> {
                    if ("".equals(args[0])) throw new NullPointerException("Spigot rejects an empty lock");
                    lock = (String) args[0];
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }

    private static final class ArmorTrimFixture implements InvocationHandler {
        ArmorTrim trim;
        String name;

        ArmorTrimFixture(ArmorTrim trim) { this.trim = trim; }

        ArmorMeta proxy() {
            return (ArmorMeta) Proxy.newProxyInstance(ArmorMeta.class.getClassLoader(),
                    new Class<?>[] {ArmorMeta.class}, this);
        }

        @Override public Object invoke(Object proxy, java.lang.reflect.Method method,
                                       Object[] args) {
            return switch (method.getName()) {
                case "hasTrim" -> trim != null;
                case "getTrim" -> trim;
                case "setTrim" -> { trim = (ArmorTrim) args[0]; yield null; }
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }

    private static final class PotionFixture implements InvocationHandler {
        PotionType base;
        Color color;
        final List<PotionEffect> effects;
        String name;

        PotionFixture(PotionType base, Color color, List<PotionEffect> effects) {
            this.base = base;
            this.color = color;
            this.effects = new ArrayList<>(effects);
        }

        PotionMeta proxy() {
            return (PotionMeta) Proxy.newProxyInstance(PotionMeta.class.getClassLoader(),
                    new Class<?>[] {PotionMeta.class}, this);
        }

        @Override public Object invoke(Object proxy, java.lang.reflect.Method method,
                                       Object[] args) {
            return switch (method.getName()) {
                case "hasBasePotionType" -> base != null;
                case "getBasePotionType" -> base;
                case "setBasePotionType" -> { base = (PotionType) args[0]; yield null; }
                case "hasColor" -> color != null;
                case "getColor" -> color;
                case "setColor" -> { color = (Color) args[0]; yield null; }
                case "getCustomEffects" -> List.copyOf(effects);
                case "clearCustomEffects" -> { boolean had = !effects.isEmpty();
                    effects.clear(); yield had; }
                case "addCustomEffect" -> { effects.add((PotionEffect) args[0]); yield true; }
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }

    /** MockBukkit resets rocket power on ItemStack#setItemMeta, so isolate Bukkit's meta API. */
    private static final class FireworkRocketFixture implements InvocationHandler {
        int power;
        final List<FireworkEffect> effects;
        String name;

        FireworkRocketFixture(int power, List<FireworkEffect> effects) {
            this.power = power;
            this.effects = new ArrayList<>(effects);
        }

        FireworkMeta proxy() {
            return (FireworkMeta) Proxy.newProxyInstance(FireworkMeta.class.getClassLoader(),
                    new Class<?>[] {FireworkMeta.class}, this);
        }

        @Override public Object invoke(Object proxy, java.lang.reflect.Method method,
                                       Object[] args) {
            return switch (method.getName()) {
                case "getPower" -> power;
                case "setPower" -> { power = (Integer) args[0]; yield null; }
                case "getEffects" -> List.copyOf(effects);
                case "clearEffects" -> { effects.clear(); yield null; }
                case "addEffects" -> {
                    for (FireworkEffect effect : (Iterable<FireworkEffect>) args[0]) {
                        effects.add(effect);
                    }
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }

    private static final class PdcFixture implements InvocationHandler {
        final Map<NamespacedKey, Object> values = new HashMap<>();

        PersistentDataContainer proxy() {
            return (PersistentDataContainer) Proxy.newProxyInstance(
                    PersistentDataContainer.class.getClassLoader(),
                    new Class<?>[] {PersistentDataContainer.class}, this);
        }

        @Override public Object invoke(Object proxy, java.lang.reflect.Method method,
                                       Object[] args) {
            return switch (method.getName()) {
                case "getKeys" -> Set.copyOf(values.keySet());
                case "remove" -> { values.remove((NamespacedKey) args[0]); yield null; }
                case "copyTo" -> {
                    PdcFixture target = (PdcFixture) Proxy.getInvocationHandler(args[0]);
                    target.values.putAll(values);
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }

    private static final class EnchantmentFixture implements InvocationHandler {
        final Map<Enchantment, Integer> enchants = new HashMap<>();
        Boolean glint;

        ItemMeta proxy() {
            return (ItemMeta) Proxy.newProxyInstance(ItemMeta.class.getClassLoader(),
                    new Class<?>[] {ItemMeta.class}, this);
        }

        @Override public Object invoke(Object proxy, java.lang.reflect.Method method,
                                       Object[] args) {
            return switch (method.getName()) {
                case "getEnchants" -> Map.copyOf(enchants);
                case "removeEnchant" -> enchants.remove((Enchantment) args[0]) != null;
                case "addEnchant" -> { enchants.put((Enchantment) args[0], (Integer) args[1]);
                    yield true; }
                case "hasEnchantmentGlintOverride" -> glint != null;
                case "getEnchantmentGlintOverride" -> glint;
                case "setEnchantmentGlintOverride" -> { glint = (Boolean) args[0]; yield null; }
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }

    private static ItemDraftEditor.PersistentEntry entry(
            List<ItemDraftEditor.PersistentEntry> entries, String name) {
        return entries.stream().filter(entry -> entry.key().equals(key(name)))
                .findFirst().orElseThrow();
    }

    private static NamespacedKey key(String name) {
        return new NamespacedKey("recipeweaver", name);
    }
}

final class NullableComponentFixture {
    ItemStack useRemainder;
    String useCooldown;
    Integer equippable;

    public boolean hasUseRemainder() { return useRemainder != null; }
    public ItemStack getUseRemainder() { return useRemainder; }
    public void setUseRemainder(ItemStack value) { useRemainder = value; }
    public boolean hasUseCooldown() { return useCooldown != null; }
    public String getUseCooldown() { return useCooldown; }
    public void setUseCooldown(String value) { useCooldown = value; }
    public boolean hasEquippable() { return equippable != null; }
    public Integer getEquippable() { return equippable; }
    public void setEquippable(Integer value) { equippable = value; }
}
