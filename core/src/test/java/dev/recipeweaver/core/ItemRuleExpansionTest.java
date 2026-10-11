package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import dev.recipeweaver.api.GridRecipeDefinition;
import dev.recipeweaver.api.GridTier;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.MapLocationLabel;
import dev.recipeweaver.api.RecipeKind;
import dev.recipeweaver.api.ResultMetadataMode;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.TropicalFish;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.KnowledgeBookMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.SuspiciousStewMeta;
import org.bukkit.inventory.meta.TropicalFishBucketMeta;
import org.bukkit.inventory.meta.WritableBookMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class ItemRuleExpansionTest {
    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void fireResistanceSelectorIgnoresNameButRejectsWrongFlag() {
        ItemMetaFixture sample = new ItemMetaFixture();
        sample.fireResistant = true;
        ItemMetaFixture actual = new ItemMetaFixture();
        actual.fireResistant = true;
        actual.name = "Unrelated";
        assertTrue(GridRecipeDefinition.fireResistantMatches(sample.proxy(), actual.proxy()));
        actual.fireResistant = false;
        assertFalse(GridRecipeDefinition.fireResistantMatches(sample.proxy(), actual.proxy()));
    }

    @Test void fireResistanceCopyReplacesAndClearsFlagAcrossMaterials() {
        ItemMetaFixture source = new ItemMetaFixture();
        source.fireResistant = true;
        ItemMetaFixture target = new ItemMetaFixture();
        target.name = "Output";
        GridResultMetadata.copyFireResistance(source.proxy(), target.proxy());
        assertTrue(target.fireResistant);
        assertEquals("Output", target.name);
        source.fireResistant = false;
        GridResultMetadata.copyFireResistance(source.proxy(), target.proxy());
        assertFalse(target.fireResistant);
    }

    @Test void hideTooltipSelectorIgnoresNameButRejectsWrongFlag() {
        ItemMetaFixture sample = new ItemMetaFixture();
        sample.hideTooltip = true;
        ItemMetaFixture actual = new ItemMetaFixture();
        actual.hideTooltip = true;
        actual.name = "Unrelated";
        assertTrue(GridRecipeDefinition.hideTooltipMatches(sample.proxy(), actual.proxy()));
        actual.hideTooltip = false;
        assertFalse(GridRecipeDefinition.hideTooltipMatches(sample.proxy(), actual.proxy()));
    }

    @Test void hideTooltipCopyReplacesAndClearsFlagAcrossMaterials() {
        ItemMetaFixture source = new ItemMetaFixture();
        source.hideTooltip = true;
        ItemMetaFixture target = new ItemMetaFixture();
        target.name = "Output";
        GridResultMetadata.copyHideTooltip(source.proxy(), target.proxy());
        assertTrue(target.hideTooltip);
        assertEquals("Output", target.name);
        source.hideTooltip = false;
        GridResultMetadata.copyHideTooltip(source.proxy(), target.proxy());
        assertFalse(target.hideTooltip);
    }

    @Test void maxStackSizeSelectorDistinguishesExplicitOverrideFromDefault() {
        ItemStack sample = new ItemStack(Material.PAPER);
        ItemMeta meta = sample.getItemMeta();
        meta.setMaxStackSize(12);
        sample.setItemMeta(meta);
        GridRecipeDefinition recipe = matchRecipe(sample, IngredientMatchMode.MAX_STACK_SIZE_ONLY);
        ItemStack actual = renamed(sample);
        assertTrue(recipe.matchesItemSample(0, actual));
        meta = actual.getItemMeta();
        meta.setMaxStackSize(null);
        actual.setItemMeta(meta);
        assertFalse(recipe.matchesItemSample(0, actual));
    }

    @Test void maxStackSizeCopyReplacesAndClearsOverride() {
        ItemStack source = new ItemStack(Material.PAPER);
        ItemMeta meta = source.getItemMeta();
        meta.setMaxStackSize(12);
        source.setItemMeta(meta);
        ItemStack template = named(new ItemStack(Material.DIAMOND));
        ItemStack copied = GridResultMetadata.copyFrom(source, template,
                ResultMetadataMode.MAX_STACK_SIZE_ONLY);
        assertEquals(12, copied.getItemMeta().getMaxStackSize());
        assertEquals("Output", copied.getItemMeta().getDisplayName());
        assertFalse(template.getItemMeta().hasMaxStackSize());
        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER), copied,
                ResultMetadataMode.MAX_STACK_SIZE_ONLY);
        assertFalse(cleared.getItemMeta().hasMaxStackSize());
    }

    @Test void raritySelectorDistinguishesExplicitRarityFromDefault() {
        ItemMetaFixture sample = new ItemMetaFixture();
        sample.rarity = ItemRarity.RARE;
        ItemMetaFixture actual = new ItemMetaFixture();
        actual.rarity = ItemRarity.RARE;
        actual.name = "Unrelated";
        assertTrue(GridRecipeDefinition.rarityMatches(sample.proxy(), actual.proxy()));
        actual.rarity = null;
        assertFalse(GridRecipeDefinition.rarityMatches(sample.proxy(), actual.proxy()));
    }

    @Test void rarityCopyReplacesAndClearsExplicitRarity() {
        ItemMetaFixture source = new ItemMetaFixture();
        source.rarity = ItemRarity.RARE;
        ItemMetaFixture target = new ItemMetaFixture();
        target.name = "Output";
        target.rarity = ItemRarity.UNCOMMON;
        GridResultMetadata.copyRarity(source.proxy(), target.proxy());
        assertEquals(ItemRarity.RARE, target.rarity);
        assertEquals("Output", target.name);
        source.rarity = null;
        GridResultMetadata.copyRarity(source.proxy(), target.proxy());
        assertEquals(null, target.rarity);
    }

    @Test void repairCostSelectorIgnoresNameButRejectsDifferentPenalty() {
        ItemStack sample = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = sample.getItemMeta();
        ((Repairable) meta).setRepairCost(7);
        sample.setItemMeta(meta);
        GridRecipeDefinition recipe = matchRecipe(sample, IngredientMatchMode.REPAIR_COST_ONLY);
        ItemStack actual = renamed(sample);
        assertTrue(recipe.matchesItemSample(0, actual));
        meta = actual.getItemMeta();
        ((Repairable) meta).setRepairCost(8);
        actual.setItemMeta(meta);
        assertFalse(recipe.matchesItemSample(0, actual));
    }

    @Test void repairCostCopyReplacesAndClearsPenaltyAcrossTools() {
        ItemStack source = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = source.getItemMeta();
        ((Repairable) meta).setRepairCost(7);
        source.setItemMeta(meta);
        ItemStack template = named(new ItemStack(Material.IRON_SWORD));
        ItemStack copied = GridResultMetadata.copyFrom(source, template,
                ResultMetadataMode.REPAIR_COST_ONLY);
        assertEquals(7, ((Repairable) copied.getItemMeta()).getRepairCost());
        assertEquals("Output", copied.getItemMeta().getDisplayName());
        assertEquals(0, ((Repairable) template.getItemMeta()).getRepairCost());
        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER), copied,
                ResultMetadataMode.REPAIR_COST_ONLY);
        assertEquals(0, ((Repairable) cleared.getItemMeta()).getRepairCost());
    }

    @Test void leatherColorSelectorIgnoresNameButRejectsDifferentDye() {
        ItemStack sample = new ItemStack(Material.LEATHER_CHESTPLATE);
        ItemMeta meta = sample.getItemMeta();
        ((LeatherArmorMeta) meta).setColor(Color.BLUE);
        sample.setItemMeta(meta);
        GridRecipeDefinition recipe = matchRecipe(sample, IngredientMatchMode.LEATHER_COLOR_ONLY);
        ItemStack actual = renamed(sample);
        assertTrue(recipe.matchesItemSample(0, actual));
        meta = actual.getItemMeta();
        ((LeatherArmorMeta) meta).setColor(Color.RED);
        actual.setItemMeta(meta);
        assertFalse(recipe.matchesItemSample(0, actual));
    }

    @Test void leatherColorCopyReplacesAndResetsDyeAcrossArmorPieces() {
        ItemStack source = new ItemStack(Material.LEATHER_CHESTPLATE);
        ItemMeta meta = source.getItemMeta();
        ((LeatherArmorMeta) meta).setColor(Color.BLUE);
        source.setItemMeta(meta);
        ItemStack template = named(new ItemStack(Material.LEATHER_BOOTS));
        ItemStack copied = GridResultMetadata.copyFrom(source, template,
                ResultMetadataMode.LEATHER_COLOR_ONLY);
        assertEquals(Color.BLUE, ((LeatherArmorMeta) copied.getItemMeta()).getColor());
        assertEquals("Output", copied.getItemMeta().getDisplayName());
        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER), copied,
                ResultMetadataMode.LEATHER_COLOR_ONLY);
        assertEquals(((LeatherArmorMeta) template.getItemMeta()).getColor(),
                ((LeatherArmorMeta) cleared.getItemMeta()).getColor());
    }

    @Test void stewEffectSelectorIgnoresNameButRejectsDifferentEffect() {
        ItemStack sample = stew(200);
        GridRecipeDefinition recipe = matchRecipe(sample, IngredientMatchMode.SUSPICIOUS_STEW_ONLY);
        ItemStack actual = renamed(sample);
        assertTrue(recipe.matchesItemSample(0, actual));
        ItemMeta meta = actual.getItemMeta();
        ((SuspiciousStewMeta) meta).clearCustomEffects();
        ((SuspiciousStewMeta) meta).addCustomEffect(effect(100), true);
        actual.setItemMeta(meta);
        assertFalse(recipe.matchesItemSample(0, actual));
    }

    @Test void stewEffectCopyReplacesAndClearsStaleEffects() {
        ItemStack template = named(stew(100));
        ItemStack copied = GridResultMetadata.copyFrom(stew(200), template,
                ResultMetadataMode.SUSPICIOUS_STEW_ONLY);
        assertEquals(List.of(effect(200)),
                ((SuspiciousStewMeta) copied.getItemMeta()).getCustomEffects());
        assertEquals("Output", copied.getItemMeta().getDisplayName());
        assertEquals(List.of(effect(100)),
                ((SuspiciousStewMeta) template.getItemMeta()).getCustomEffects());
        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER), copied,
                ResultMetadataMode.SUSPICIOUS_STEW_ONLY);
        assertTrue(((SuspiciousStewMeta) cleared.getItemMeta()).getCustomEffects().isEmpty());
    }

    @Test void foodSelectorComparesOnlyTheCompleteFoodComponent() {
        ItemMeta sample = serialized(Map.of("food", Map.of("nutrition", 4, "saturation", 0.5)));
        ItemMeta actual = serialized(Map.of("food", Map.of("nutrition", 4, "saturation", 0.5),
                "display-name", "Unrelated"));
        assertTrue(GridRecipeDefinition.componentFieldsMatch(sample, actual, "food"));
        assertFalse(GridRecipeDefinition.componentFieldsMatch(sample,
                serialized(Map.of("food", Map.of("nutrition", 5, "saturation", 0.5))), "food"));
        assertFalse(GridRecipeDefinition.componentFieldsMatch(sample,
                serialized(Map.of("food", Map.of("nutrition", 4, "saturation", 0.5,
                        "can-always-eat", true))), "food"));
    }

    @Test void toolSelectorComparesOnlyTheCompleteToolComponent() {
        ItemMeta sample = serialized(Map.of("tool", Map.of("default-mining-speed", 4.0,
                "damage-per-block", 1)));
        ItemMeta actual = serialized(Map.of("tool", Map.of("default-mining-speed", 4.0,
                "damage-per-block", 1), "display-name", "Unrelated"));
        assertTrue(GridRecipeDefinition.componentFieldsMatch(sample, actual, "tool"));
        assertFalse(GridRecipeDefinition.componentFieldsMatch(sample,
                serialized(Map.of("tool", Map.of("default-mining-speed", 5.0,
                        "damage-per-block", 1))), "tool"));
    }

    @Test void byteListEditorPersistsAndReopensSignedValues() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemDraftEditor.setPersistentData(item, "recipeweaver:values\n@bytelist -128, 0, 127");
        assertEquals(List.of((byte) -128, (byte) 0, (byte) 127),
                item.getItemMeta().getPersistentDataContainer().get(key("values"),
                        PersistentDataType.LIST.bytes()));
        assertEquals("@bytelist -128, 0, 127", ItemDraftEditor.persistentEntries(item).get(0).editorValue());
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.setPersistentData(
                item, "recipeweaver:values\n@bytelist 128"));
    }

    @Test void shortListEditorPersistsAndReopensSignedValues() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemDraftEditor.setPersistentData(item, "recipeweaver:values\n@shortlist -32768, 0, 32767");
        assertEquals(List.of((short) -32768, (short) 0, (short) 32767),
                item.getItemMeta().getPersistentDataContainer().get(key("values"),
                        PersistentDataType.LIST.shorts()));
        assertEquals("@shortlist -32768, 0, 32767",
                ItemDraftEditor.persistentEntries(item).get(0).editorValue());
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.setPersistentData(
                item, "recipeweaver:values\n@shortlist 32768"));
    }

    @Test void floatListEditorPersistsAndRejectsNonFiniteValues() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemDraftEditor.setPersistentData(item, "recipeweaver:values\n@floatlist -1.25, 0.5");
        assertEquals(List.of(-1.25F, 0.5F),
                item.getItemMeta().getPersistentDataContainer().get(key("values"),
                        PersistentDataType.LIST.floats()));
        assertEquals("@floatlist -1.25, 0.5",
                ItemDraftEditor.persistentEntries(item).get(0).editorValue());
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.setPersistentData(
                item, "recipeweaver:values\n@floatlist NaN"));
    }

    @Test void doubleListEditorPersistsAndRejectsNonFiniteValues() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemDraftEditor.setPersistentData(item, "recipeweaver:values\n@doublelist -1.25, 0.5");
        assertEquals(List.of(-1.25D, 0.5D),
                item.getItemMeta().getPersistentDataContainer().get(key("values"),
                        PersistentDataType.LIST.doubles()));
        assertEquals("@doublelist -1.25, 0.5",
                ItemDraftEditor.persistentEntries(item).get(0).editorValue());
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.setPersistentData(
                item, "recipeweaver:values\n@doublelist Infinity"));
    }

    @Test void byteArrayListEditorPersistsAndReopensEachHexArray() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemDraftEditor.setPersistentData(item,
                "recipeweaver:values\n@bytearraylist 00ff\n80a5");
        List<byte[]> values = item.getItemMeta().getPersistentDataContainer().get(
                key("values"), PersistentDataType.LIST.byteArrays());
        assertEquals(2, values.size());
        assertTrue(Arrays.equals(new byte[] {0, (byte) 255}, values.get(0)));
        assertTrue(Arrays.equals(new byte[] {(byte) 128, (byte) 165}, values.get(1)));
        assertEquals("@bytearraylist 00ff\n80a5",
                ItemDraftEditor.persistentEntries(item).get(0).editorValue());
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.setPersistentData(
                item, "recipeweaver:values\n@bytearraylist 00ff\ninvalid"));
    }

    @Test void integerArrayListEditorPersistsAndReopensEachNumberArray() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemDraftEditor.setPersistentData(item,
                "recipeweaver:values\n@intarraylist -2, 0\n3, 5");
        List<int[]> values = item.getItemMeta().getPersistentDataContainer().get(
                key("values"), PersistentDataType.LIST.integerArrays());
        assertEquals(2, values.size());
        assertTrue(Arrays.equals(new int[] {-2, 0}, values.get(0)));
        assertTrue(Arrays.equals(new int[] {3, 5}, values.get(1)));
        assertEquals("@intarraylist -2, 0\n3, 5",
                ItemDraftEditor.persistentEntries(item).get(0).editorValue());
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.setPersistentData(
                item, "recipeweaver:values\n@intarraylist 1\n"));
    }

    @Test void longArrayListEditorPersistsAndReopensEachNumberArray() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemDraftEditor.setPersistentData(item,
                "recipeweaver:values\n@longarraylist -2, 0\n3, 9223372036854775807");
        List<long[]> values = item.getItemMeta().getPersistentDataContainer().get(
                key("values"), PersistentDataType.LIST.longArrays());
        assertEquals(2, values.size());
        assertTrue(Arrays.equals(new long[] {-2, 0}, values.get(0)));
        assertTrue(Arrays.equals(new long[] {3, Long.MAX_VALUE}, values.get(1)));
        assertEquals("@longarraylist -2, 0\n3, 9223372036854775807",
                ItemDraftEditor.persistentEntries(item).get(0).editorValue());
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.setPersistentData(
                item, "recipeweaver:values\n@longarraylist 1\n9223372036854775808"));
    }

    @Test void nestedCompoundPathEditorPreservesSiblingsAndBrowsesFullPaths() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemDraftEditor.setPersistentData(item,
                "recipeweaver:profile\n@compound recipeweaver:stats>recipeweaver:rank @int 7");
        ItemDraftEditor.setPersistentData(item,
                "recipeweaver:profile\n@compound recipeweaver:stats>recipeweaver:level @short 3");
        var root = item.getItemMeta().getPersistentDataContainer()
                .get(key("profile"), PersistentDataType.TAG_CONTAINER);
        var stats = root.get(key("stats"), PersistentDataType.TAG_CONTAINER);
        assertEquals(7, stats.get(key("rank"), PersistentDataType.INTEGER));
        assertEquals((short) 3, stats.get(key("level"), PersistentDataType.SHORT));
        assertTrue(ItemDraftEditor.persistentCompoundEntries(item, key("profile")).stream()
                .anyMatch(entry -> entry.editorValue().equals(
                        "@compound recipeweaver:stats>recipeweaver:rank @int 7")));
    }

    @Test void removingLastNestedLeafPrunesOnlyEmptyAncestorCompounds() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemDraftEditor.setPersistentData(item,
                "recipeweaver:profile\n@compound recipeweaver:stats>recipeweaver:rank @int 7");
        ItemDraftEditor.setPersistentData(item,
                "recipeweaver:profile\n@compound recipeweaver:label @string kept");
        ItemDraftEditor.setPersistentData(item,
                "recipeweaver:profile\n@compound recipeweaver:stats>recipeweaver:rank none");
        var root = item.getItemMeta().getPersistentDataContainer()
                .get(key("profile"), PersistentDataType.TAG_CONTAINER);
        assertFalse(root.has(key("stats")));
        assertEquals("kept", root.get(key("label"), PersistentDataType.STRING));
        ItemDraftEditor.setPersistentData(item,
                "recipeweaver:profile\n@compound recipeweaver:label none");
        assertFalse(item.getItemMeta().getPersistentDataContainer().has(key("profile")));
    }

    @Test void nestedCompoundPathRejectsScalarCollisionAndOverdeepPathAtomically() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemDraftEditor.setPersistentData(item,
                "recipeweaver:profile\n@compound recipeweaver:stats @int 7");
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.setPersistentData(item,
                "recipeweaver:profile\n@compound recipeweaver:stats>recipeweaver:rank @int 8"));
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.setPersistentData(item,
                "recipeweaver:profile\n@compound recipeweaver:a>recipeweaver:b>recipeweaver:c>recipeweaver:d @int 8"));
        var root = item.getItemMeta().getPersistentDataContainer()
                .get(key("profile"), PersistentDataType.TAG_CONTAINER);
        assertEquals(7, root.get(key("stats"), PersistentDataType.INTEGER));
        assertFalse(root.has(key("a")));
    }

    @Test void importedDeepCompoundRowTargetsItsFullPathForSafeRemoval() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        var root = meta.getPersistentDataContainer();
        var outer = root.getAdapterContext().newPersistentDataContainer();
        var first = root.getAdapterContext().newPersistentDataContainer();
        var second = root.getAdapterContext().newPersistentDataContainer();
        var deep = root.getAdapterContext().newPersistentDataContainer();
        deep.set(key("leaf"), PersistentDataType.STRING, "keep");
        second.set(key("deep"), PersistentDataType.TAG_CONTAINER, deep);
        first.set(key("second"), PersistentDataType.TAG_CONTAINER, second);
        outer.set(key("first"), PersistentDataType.TAG_CONTAINER, first);
        root.set(key("profile"), PersistentDataType.TAG_CONTAINER, outer);
        item.setItemMeta(meta);
        var entries = ItemDraftEditor.persistentCompoundEntries(item, key("profile"));
        assertEquals(1, entries.size());
        String editor = entries.get(0).editorValue();
        assertEquals("@compound recipeweaver:first>recipeweaver:second>recipeweaver:deep none", editor);
        ItemDraftEditor.setPersistentData(item, "recipeweaver:profile\n" + editor);
        assertFalse(item.getItemMeta().getPersistentDataContainer().has(key("profile")));
    }

    @Test void writtenBookSelectorIgnoresNameButChecksTitleAndPages() {
        ItemStack sample = new ItemStack(Material.WRITTEN_BOOK);
        ItemDraftEditor.setBookTitle(sample, "Guide");
        ItemDraftEditor.setBookAuthor(sample, "Weaver");
        ItemDraftEditor.setBookPages(sample, "First page");
        GridRecipeDefinition recipe = matchRecipe(sample, IngredientMatchMode.WRITTEN_BOOK_ONLY);
        ItemStack actual = renamed(sample);
        assertTrue(recipe.matchesItemSample(0, actual));
        ItemDraftEditor.setBookTitle(actual, "Other guide");
        assertFalse(recipe.matchesItemSample(0, actual));
        ItemDraftEditor.setBookTitle(actual, "Guide");
        ItemDraftEditor.setBookPages(actual, "Other page");
        assertFalse(recipe.matchesItemSample(0, actual));
    }

    @Test void potionBaseSelectorIgnoresColorAndNameButChecksBase() {
        PotionBaseFixture sample = new PotionBaseFixture(PotionType.HEALING, Color.RED,
                "Sample name");
        PotionBaseFixture actual = new PotionBaseFixture(PotionType.HEALING, Color.BLUE,
                "Different name");
        assertTrue(GridRecipeDefinition.potionBaseMatches(sample.proxy(), actual.proxy()));
        actual.base = PotionType.WATER;
        assertFalse(GridRecipeDefinition.potionBaseMatches(sample.proxy(), actual.proxy()));
    }

    @Test void potionBaseCopyReplacesOnlyBaseAndResetsToWater() {
        PotionBaseFixture source = new PotionBaseFixture(PotionType.HEALING, Color.RED,
                "Source name");
        PotionBaseFixture target = new PotionBaseFixture(PotionType.WATER, Color.BLUE,
                "Output");
        GridResultMetadata.copyPotionBase(source.proxy(), target.proxy());
        assertEquals(PotionType.HEALING, target.base);
        assertEquals(Color.BLUE, target.color);
        assertEquals("Output", target.name);
        GridResultMetadata.copyPotionBase(new ItemStack(Material.PAPER).getItemMeta(),
                target.proxy());
        assertEquals(PotionType.WATER, target.base);
        assertEquals(Color.BLUE, target.color);
    }

    @Test void potionColorSelectorIgnoresEffectsAndNameButChecksExplicitColor() {
        ItemStack sample = coloredPotion(Color.RED);
        GridRecipeDefinition recipe = matchRecipe(sample, IngredientMatchMode.POTION_COLOR_ONLY);
        ItemStack actual = renamed(sample);
        PotionMeta meta = (PotionMeta) actual.getItemMeta();
        meta.addCustomEffect(new PotionEffect(PotionEffectType.SPEED, 100, 1), true);
        actual.setItemMeta(meta);
        assertTrue(recipe.matchesItemSample(0, actual));
        meta = (PotionMeta) actual.getItemMeta();
        meta.setColor(Color.BLUE);
        actual.setItemMeta(meta);
        assertFalse(recipe.matchesItemSample(0, actual));
    }

    @Test void potionColorCopyReplacesAndClearsOnlyExplicitColor() {
        ItemStack source = coloredPotion(Color.RED);
        ItemStack target = named(coloredPotion(Color.BLUE));
        PotionMeta targetMeta = (PotionMeta) target.getItemMeta();
        targetMeta.addCustomEffect(new PotionEffect(PotionEffectType.SPEED, 100, 1), true);
        target.setItemMeta(targetMeta);
        ItemStack copied = GridResultMetadata.copyFrom(source, target,
                ResultMetadataMode.POTION_COLOR_ONLY);
        PotionMeta meta = (PotionMeta) copied.getItemMeta();
        assertEquals(Color.RED, meta.getColor());
        assertEquals(1, meta.getCustomEffects().size());
        assertEquals("Output", meta.getDisplayName());
        assertEquals(Color.BLUE, ((PotionMeta) target.getItemMeta()).getColor());
        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER), copied,
                ResultMetadataMode.POTION_COLOR_ONLY);
        meta = (PotionMeta) cleared.getItemMeta();
        assertFalse(meta.hasColor());
        assertEquals(1, meta.getCustomEffects().size());
    }

    @Test void writtenBookTitleSelectorIgnoresAuthorPagesAndName() {
        ItemStack sample = signedBook("Guide", "Weaver", "First page");
        GridRecipeDefinition recipe = matchRecipe(sample,
                IngredientMatchMode.WRITTEN_BOOK_TITLE_ONLY);
        ItemStack actual = renamed(sample);
        ItemDraftEditor.setBookAuthor(actual, "Other author");
        ItemDraftEditor.setBookPages(actual, "Other page");
        assertTrue(recipe.matchesItemSample(0, actual));
        ItemDraftEditor.setBookTitle(actual, "Other guide");
        assertFalse(recipe.matchesItemSample(0, actual));
    }

    @Test void writtenBookTitleCopyReplacesAndClearsOnlyTitle() {
        ItemStack source = signedBook("Guide", "Weaver", "Source page");
        ItemStack target = named(signedBook("Old guide", "Target author", "Keep page"));
        ItemStack copied = GridResultMetadata.copyFrom(source, target,
                ResultMetadataMode.WRITTEN_BOOK_TITLE_ONLY);
        BookMeta meta = (BookMeta) copied.getItemMeta();
        assertEquals("Guide", meta.getTitle());
        assertEquals("Target author", meta.getAuthor());
        assertEquals(List.of("Keep page"), meta.getPages());
        assertEquals("Output", meta.getDisplayName());
        assertEquals("Old guide", ((BookMeta) target.getItemMeta()).getTitle());
        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER), copied,
                ResultMetadataMode.WRITTEN_BOOK_TITLE_ONLY);
        meta = (BookMeta) cleared.getItemMeta();
        assertFalse(meta.hasTitle());
        assertEquals("Target author", meta.getAuthor());
        assertEquals(List.of("Keep page"), meta.getPages());
    }

    @Test void writtenBookAuthorSelectorIgnoresTitlePagesAndName() {
        ItemStack sample = signedBook("Guide", "Weaver", "First page");
        GridRecipeDefinition recipe = matchRecipe(sample,
                IngredientMatchMode.WRITTEN_BOOK_AUTHOR_ONLY);
        ItemStack actual = renamed(sample);
        ItemDraftEditor.setBookTitle(actual, "Other guide");
        ItemDraftEditor.setBookPages(actual, "Other page");
        assertTrue(recipe.matchesItemSample(0, actual));
        ItemDraftEditor.setBookAuthor(actual, "Other author");
        assertFalse(recipe.matchesItemSample(0, actual));
    }

    @Test void writtenBookAuthorCopyReplacesAndClearsOnlyAuthor() {
        ItemStack source = signedBook("Source guide", "Weaver", "Source page");
        ItemStack target = named(signedBook("Keep guide", "Old author", "Keep page"));
        ItemStack copied = GridResultMetadata.copyFrom(source, target,
                ResultMetadataMode.WRITTEN_BOOK_AUTHOR_ONLY);
        BookMeta meta = (BookMeta) copied.getItemMeta();
        assertEquals("Weaver", meta.getAuthor());
        assertEquals("Keep guide", meta.getTitle());
        assertEquals(List.of("Keep page"), meta.getPages());
        assertEquals("Output", meta.getDisplayName());
        assertEquals("Old author", ((BookMeta) target.getItemMeta()).getAuthor());
        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER), copied,
                ResultMetadataMode.WRITTEN_BOOK_AUTHOR_ONLY);
        meta = (BookMeta) cleared.getItemMeta();
        assertFalse(meta.hasAuthor());
        assertEquals("Keep guide", meta.getTitle());
        assertEquals(List.of("Keep page"), meta.getPages());
    }

    @Test void writtenBookCopyReplacesAndClearsContentWithoutChangingName() {
        ItemStack source = new ItemStack(Material.WRITTEN_BOOK);
        ItemDraftEditor.setBookTitle(source, "Guide");
        ItemDraftEditor.setBookAuthor(source, "Weaver");
        ItemDraftEditor.setBookPages(source, "First page");
        ItemStack target = named(new ItemStack(Material.WRITTEN_BOOK));
        ItemStack copied = GridResultMetadata.copyFrom(source, target,
                ResultMetadataMode.WRITTEN_BOOK_ONLY);
        BookMeta book = (BookMeta) copied.getItemMeta();
        assertEquals("Guide", book.getTitle());
        assertEquals("Weaver", book.getAuthor());
        assertEquals(List.of("First page"), book.getPages());
        assertEquals("Output", book.getDisplayName());
        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER), copied,
                ResultMetadataMode.WRITTEN_BOOK_ONLY);
        book = (BookMeta) cleared.getItemMeta();
        assertFalse(book.hasTitle());
        assertFalse(book.hasAuthor());
        assertTrue(book.getPages().isEmpty());
        assertEquals("Output", book.getDisplayName());
    }

    @Test void writableBookSelectorIgnoresNameButChecksPages() {
        ItemStack sample = new ItemStack(Material.WRITABLE_BOOK);
        ItemDraftEditor.setBookPages(sample, "First page");
        GridRecipeDefinition recipe = matchRecipe(sample, IngredientMatchMode.WRITABLE_BOOK_ONLY);
        ItemStack actual = renamed(sample);
        assertTrue(recipe.matchesItemSample(0, actual));
        ItemDraftEditor.setBookPages(actual, "Changed page");
        assertFalse(recipe.matchesItemSample(0, actual));
    }

    @Test void writableBookCopyReplacesAndClearsPagesWithoutChangingName() {
        ItemStack source = new ItemStack(Material.WRITABLE_BOOK);
        ItemDraftEditor.setBookPages(source, "First page");
        ItemStack target = named(new ItemStack(Material.WRITABLE_BOOK));
        ItemStack copied = GridResultMetadata.copyFrom(source, target,
                ResultMetadataMode.WRITABLE_BOOK_ONLY);
        assertEquals(List.of("First page"),
                ((WritableBookMeta) copied.getItemMeta()).getPages());
        assertEquals("Output", copied.getItemMeta().getDisplayName());
        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER), copied,
                ResultMetadataMode.WRITABLE_BOOK_ONLY);
        assertTrue(((WritableBookMeta) cleared.getItemMeta()).getPages().isEmpty());
        assertEquals("Output", cleared.getItemMeta().getDisplayName());
    }

    @Test void compassSelectorIgnoresNameButChecksLodestoneAndTracking() {
        MockBukkit.getMock().addSimpleWorld("compass-rules");
        ItemStack sample = new ItemStack(Material.COMPASS);
        ItemDraftEditor.setCompassLodestone(sample, "compass-rules 8 65 -3");
        GridRecipeDefinition recipe = matchRecipe(sample, IngredientMatchMode.COMPASS_ONLY);
        ItemStack actual = renamed(sample);
        assertTrue(recipe.matchesItemSample(0, actual));
        CompassMeta compass = (CompassMeta) actual.getItemMeta();
        compass.setLodestoneTracked(!compass.isLodestoneTracked());
        actual.setItemMeta(compass);
        assertFalse(recipe.matchesItemSample(0, actual));
        compass = (CompassMeta) actual.getItemMeta();
        compass.setLodestoneTracked(!compass.isLodestoneTracked());
        compass.setLodestone(null);
        actual.setItemMeta(compass);
        assertFalse(recipe.matchesItemSample(0, actual));
    }

    @Test void compassCopyReplacesAndClearsLodestoneWithoutChangingName() {
        MockBukkit.getMock().addSimpleWorld("compass-copy");
        ItemStack source = new ItemStack(Material.COMPASS);
        ItemDraftEditor.setCompassLodestone(source, "compass-copy 8 65 -3");
        ItemStack copied = GridResultMetadata.copyFrom(source,
                named(new ItemStack(Material.COMPASS)), ResultMetadataMode.COMPASS_ONLY);
        assertEquals(8, ((CompassMeta) copied.getItemMeta()).getLodestone().getBlockX());
        assertEquals("Output", copied.getItemMeta().getDisplayName());
        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER), copied,
                ResultMetadataMode.COMPASS_ONLY);
        assertFalse(((CompassMeta) cleared.getItemMeta()).hasLodestone());
        assertEquals("Output", cleared.getItemMeta().getDisplayName());
    }

    @Test void mapColorSelectorIgnoresNameButChecksExplicitColor() {
        ItemStack sample = new ItemStack(Material.FILLED_MAP);
        ItemDraftEditor.setMapColor(sample, "#AA44FF");
        GridRecipeDefinition recipe = matchRecipe(sample, IngredientMatchMode.MAP_COLOR_ONLY);
        ItemStack actual = renamed(sample);
        assertTrue(recipe.matchesItemSample(0, actual));
        ItemDraftEditor.setMapColor(actual, "none");
        assertFalse(recipe.matchesItemSample(0, actual));
    }

    @Test void mapColorCopyReplacesAndClearsOnlyColor() {
        ItemStack source = new ItemStack(Material.FILLED_MAP);
        ItemDraftEditor.setMapColor(source, "#AA44FF");
        ItemStack target = named(new ItemStack(Material.FILLED_MAP));
        ItemStack copied = GridResultMetadata.copyFrom(source, target,
                ResultMetadataMode.MAP_COLOR_ONLY);
        assertEquals(Color.fromRGB(0xAA44FF), ((MapMeta) copied.getItemMeta()).getColor());
        assertEquals("Output", copied.getItemMeta().getDisplayName());
        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER), copied,
                ResultMetadataMode.MAP_COLOR_ONLY);
        assertFalse(((MapMeta) cleared.getItemMeta()).hasColor());
        assertEquals("Output", cleared.getItemMeta().getDisplayName());
    }

    @Test void mapScalingSelectorIgnoresNameButChecksScaling() {
        ItemStack sample = new ItemStack(Material.FILLED_MAP);
        ItemDraftEditor.toggleMapScaling(sample);
        GridRecipeDefinition recipe = matchRecipe(sample, IngredientMatchMode.MAP_SCALING_ONLY);
        ItemStack actual = renamed(sample);
        assertTrue(recipe.matchesItemSample(0, actual));
        ItemDraftEditor.toggleMapScaling(actual);
        assertFalse(recipe.matchesItemSample(0, actual));
    }

    @Test void mapScalingCopyReplacesOnlyScaling() {
        ItemStack source = new ItemStack(Material.FILLED_MAP);
        ItemDraftEditor.toggleMapScaling(source);
        ItemStack copied = GridResultMetadata.copyFrom(source,
                named(new ItemStack(Material.FILLED_MAP)), ResultMetadataMode.MAP_SCALING_ONLY);
        assertEquals(((MapMeta) source.getItemMeta()).isScaling(),
                ((MapMeta) copied.getItemMeta()).isScaling());
        assertEquals("Output", copied.getItemMeta().getDisplayName());
        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER), copied,
                ResultMetadataMode.MAP_SCALING_ONLY);
        assertEquals(((MapMeta) new ItemStack(Material.FILLED_MAP).getItemMeta()).isScaling(),
                ((MapMeta) cleared.getItemMeta()).isScaling());
    }

    @Test void mapLocationNameSelectorIgnoresNameButChecksLabel() {
        ItemStack sample = mapWithLocationName("Start");
        GridRecipeDefinition recipe = matchRecipe(sample, IngredientMatchMode.MAP_LOCATION_NAME_ONLY);
        ItemStack actual = renamed(sample);
        assertTrue(recipe.matchesItemSample(0, actual));
        ItemDraftEditor.setMapLocationName(actual, "Changed");
        assertFalse(recipe.matchesItemSample(0, actual));
    }

    @Test void mapLocationNameCopyReplacesAndClearsOnlyLabel() {
        ItemStack copied = GridResultMetadata.copyFrom(mapWithLocationName("Start"),
                named(mapWithLocationName("Old")), ResultMetadataMode.MAP_LOCATION_NAME_ONLY);
        assertEquals("Start", MapLocationLabel.read(copied.getItemMeta()));
        assertEquals("Output", copied.getItemMeta().getDisplayName());
        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER), copied,
                ResultMetadataMode.MAP_LOCATION_NAME_ONLY);
        assertEquals(null, MapLocationLabel.read(cleared.getItemMeta()));
        assertEquals("Output", cleared.getItemMeta().getDisplayName());
    }

    @Test void mapLocationEditorStoresTypedLabelAndRejectsOversizeEditsAtomically() {
        ItemStack map = new ItemStack(Material.FILLED_MAP);
        ItemDraftEditor.setMapLocationName(map, "  Search marker  ");
        assertEquals("Search marker", MapLocationLabel.read(map.getItemMeta()));
        assertEquals("Search marker", map.getItemMeta().getPersistentDataContainer().get(
                MapLocationLabel.STORAGE_KEY, PersistentDataType.STRING));
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.setMapLocationName(map, "x".repeat(129)));
        assertEquals("Search marker", MapLocationLabel.read(map.getItemMeta()));
        ItemDraftEditor.setMapLocationName(map, "none");
        assertEquals(null, MapLocationLabel.read(map.getItemMeta()));
        assertEquals("", map.getItemMeta().getPersistentDataContainer().get(
                MapLocationLabel.STORAGE_KEY, PersistentDataType.STRING));
    }

    @Test void tropicalFishSelectorIgnoresNameButChecksVariant() {
        ItemStack sample = tropicalFish(DyeColor.RED, DyeColor.BLUE);
        GridRecipeDefinition recipe = matchRecipe(sample, IngredientMatchMode.TROPICAL_FISH_ONLY);
        ItemStack actual = renamed(sample);
        assertTrue(recipe.matchesItemSample(0, actual));
        ItemStack changed = tropicalFish(DyeColor.BLUE, DyeColor.RED);
        assertFalse(recipe.matchesItemSample(0, changed));
    }

    @Test void tropicalFishCopyReplacesVariantWithoutChangingName() {
        ItemStack source = tropicalFish(DyeColor.RED, DyeColor.BLUE);
        ItemStack target = named(tropicalFish(DyeColor.BLUE, DyeColor.RED));
        ItemStack copied = GridResultMetadata.copyFrom(source, target,
                ResultMetadataMode.TROPICAL_FISH_ONLY);
        TropicalFishBucketMeta fish = (TropicalFishBucketMeta) copied.getItemMeta();
        assertEquals(DyeColor.RED, fish.getBodyColor());
        assertEquals(DyeColor.BLUE, fish.getPatternColor());
        assertEquals(TropicalFish.Pattern.KOB, fish.getPattern());
        assertEquals("Output", fish.getDisplayName());
        assertEquals(DyeColor.BLUE,
                ((TropicalFishBucketMeta) target.getItemMeta()).getBodyColor());
    }

    @Test void knowledgeBookSelectorIgnoresNameButChecksRecipeKeys() {
        ItemStack sample = knowledgeBook(key("alpha"));
        GridRecipeDefinition recipe = matchRecipe(sample, IngredientMatchMode.KNOWLEDGE_BOOK_ONLY);
        ItemStack actual = renamed(sample);
        assertTrue(recipe.matchesItemSample(0, actual));
        KnowledgeBookMeta meta = (KnowledgeBookMeta) actual.getItemMeta();
        meta.setRecipes(List.of(key("beta")));
        actual.setItemMeta(meta);
        assertFalse(recipe.matchesItemSample(0, actual));
    }

    @Test void knowledgeBookCopyReplacesAndClearsKeysWithoutChangingName() {
        ItemStack copied = GridResultMetadata.copyFrom(knowledgeBook(key("alpha")),
                named(knowledgeBook(key("beta"))), ResultMetadataMode.KNOWLEDGE_BOOK_ONLY);
        // MockBukkit's KnowledgeBookMetaMock clone duplicates its list, so assert
        // the selected key replaces the template key without relying on list size.
        assertFalse(((KnowledgeBookMeta) copied.getItemMeta()).getRecipes().isEmpty());
        assertTrue(((KnowledgeBookMeta) copied.getItemMeta()).getRecipes().stream()
                .allMatch(key("alpha")::equals));
        assertFalse(((KnowledgeBookMeta) copied.getItemMeta()).getRecipes()
                .contains(key("beta")));
        assertEquals("Output", copied.getItemMeta().getDisplayName());
        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER), copied,
                ResultMetadataMode.KNOWLEDGE_BOOK_ONLY);
        assertTrue(((KnowledgeBookMeta) cleared.getItemMeta()).getRecipes().isEmpty());
        assertEquals("Output", cleared.getItemMeta().getDisplayName());
    }

    @Test void headSoundSelectorIgnoresNameButChecksSoundKey() {
        ItemStack sample = headWithSound("minecraft:block.note_block.harp");
        GridRecipeDefinition recipe = matchRecipe(sample, IngredientMatchMode.HEAD_SOUND_ONLY);
        ItemStack actual = renamed(sample);
        assertTrue(recipe.matchesItemSample(0, actual));
        ItemDraftEditor.setHeadNoteBlockSound(actual, "minecraft:block.note_block.bell");
        assertFalse(recipe.matchesItemSample(0, actual));
    }

    @Test void headSoundCopyReplacesAndClearsOnlySoundKey() {
        ItemStack copied = GridResultMetadata.copyFrom(
                headWithSound("minecraft:block.note_block.harp"),
                named(headWithSound("minecraft:block.note_block.bell")),
                ResultMetadataMode.HEAD_SOUND_ONLY);
        assertEquals(NamespacedKey.minecraft("block.note_block.harp"),
                ((SkullMeta) copied.getItemMeta()).getNoteBlockSound());
        assertEquals("Output", copied.getItemMeta().getDisplayName());
        ItemStack cleared = GridResultMetadata.copyFrom(new ItemStack(Material.PAPER), copied,
                ResultMetadataMode.HEAD_SOUND_ONLY);
        assertEquals(null, ((SkullMeta) cleared.getItemMeta()).getNoteBlockSound());
        assertEquals("Output", cleared.getItemMeta().getDisplayName());
    }

    private static ItemStack mapWithLocationName(String name) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        ItemDraftEditor.setMapLocationName(item, name);
        return item;
    }

    private static ItemStack coloredPotion(Color color) {
        ItemStack item = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) item.getItemMeta();
        meta.setColor(color);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack signedBook(String title, String author, String page) {
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        ItemDraftEditor.setBookTitle(item, title);
        ItemDraftEditor.setBookAuthor(item, author);
        ItemDraftEditor.setBookPages(item, page);
        return item;
    }

    private static ItemStack tropicalFish(DyeColor body, DyeColor pattern) {
        ItemStack item = new ItemStack(Material.TROPICAL_FISH_BUCKET);
        TropicalFishBucketMeta meta = (TropicalFishBucketMeta) item.getItemMeta();
        meta.setPattern(TropicalFish.Pattern.KOB);
        meta.setPatternColor(body);
        meta.setBodyColor(body);
        meta.setPatternColor(pattern);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack knowledgeBook(NamespacedKey recipe) {
        ItemStack item = new ItemStack(Material.KNOWLEDGE_BOOK);
        KnowledgeBookMeta meta = (KnowledgeBookMeta) item.getItemMeta();
        meta.setRecipes(List.of(recipe));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack headWithSound(String key) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemDraftEditor.setHeadNoteBlockSound(item, key);
        return item;
    }

    private static GridRecipeDefinition matchRecipe(ItemStack sample, IngredientMatchMode mode) {
        return new GridRecipeDefinition(key("item_rule_" + mode.name().toLowerCase()),
                GridTier.ELITE, RecipeKind.SHAPED, 2,
                Arrays.asList(sample, null, null, null), new ItemStack(Material.DIAMOND),
                "", true, false, List.of(mode, IngredientMatchMode.MATERIAL,
                        IngredientMatchMode.MATERIAL, IngredientMatchMode.MATERIAL), 0, true);
    }

    private static ItemStack named(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("Output");
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack renamed(ItemStack item) {
        ItemStack copy = item.clone();
        return named(copy);
    }

    private static ItemStack stew(int duration) {
        ItemStack item = new ItemStack(Material.SUSPICIOUS_STEW);
        SuspiciousStewMeta meta = (SuspiciousStewMeta) item.getItemMeta();
        meta.addCustomEffect(effect(duration), true);
        item.setItemMeta(meta);
        return item;
    }

    private static PotionEffect effect(int duration) {
        return new PotionEffect(PotionEffectType.SPEED, duration, 0);
    }

    private static NamespacedKey key(String key) {
        return new NamespacedKey("recipeweaver", key);
    }

    private static ItemMeta serialized(Map<String, Object> values) {
        return (ItemMeta) Proxy.newProxyInstance(ItemMeta.class.getClassLoader(),
                new Class<?>[] {ItemMeta.class}, (proxy, method, args) -> {
                    if (method.getName().equals("serialize")) return values;
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class PotionBaseFixture implements InvocationHandler {
        private PotionType base;
        private Color color;
        private String name;

        private PotionBaseFixture(PotionType base, Color color, String name) {
            this.base = base;
            this.color = color;
            this.name = name;
        }

        private PotionMeta proxy() {
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
                case "hasDisplayName" -> name != null;
                case "getDisplayName" -> name;
                case "setDisplayName" -> { name = (String) args[0]; yield null; }
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }

    private static final class ItemMetaFixture {
        private boolean fireResistant;
        private boolean hideTooltip;
        private ItemRarity rarity;
        private String name;

        private ItemMeta proxy() {
            return (ItemMeta) Proxy.newProxyInstance(ItemMeta.class.getClassLoader(),
                    new Class<?>[] {ItemMeta.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "isFireResistant" -> fireResistant;
                        case "setFireResistant" -> { fireResistant = (boolean) args[0]; yield null; }
                        case "isHideTooltip" -> hideTooltip;
                        case "setHideTooltip" -> { hideTooltip = (boolean) args[0]; yield null; }
                        case "hasRarity" -> rarity != null;
                        case "getRarity" -> rarity;
                        case "setRarity" -> { rarity = (ItemRarity) args[0]; yield null; }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
