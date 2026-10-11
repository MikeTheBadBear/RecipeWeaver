package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import java.util.List;
import java.util.Optional;
import org.bukkit.Color;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.SuspiciousStewMeta;
import org.bukkit.inventory.meta.TropicalFishBucketMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.FireworkEffectMeta;
import org.bukkit.DyeColor;
import org.bukkit.block.banner.PatternType;
import org.bukkit.entity.TropicalFish;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class ItemDraftEditorTest {
    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void bookPageReplacesOrClearsLoreWithoutChangingOtherMeta() {
        ItemStack item = new ItemStack(Material.DIAMOND_CHESTPLATE);
        var original = item.getItemMeta();
        original.setDisplayName("Kept name");
        item.setItemMeta(original);
        ItemDraftEditor.replaceLore(item, "&aFirst line\n&bSecond line");
        assertEquals(List.of(ChatColor.GREEN + "First line", ChatColor.AQUA + "Second line"),
                item.getItemMeta().getLore());
        assertEquals("Kept name", item.getItemMeta().getDisplayName());
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.replaceLore(item, "x\n".repeat(17)));
        ItemDraftEditor.replaceLore(item, "none");
        assertFalse(item.getItemMeta().hasLore());
    }

    @Test void damageChangesOnlyDamageableItemsAndChecksBounds() {
        ItemStack armor = new ItemStack(Material.DIAMOND_CHESTPLATE);
        ItemDraftEditor.setDamage(armor, "27");
        assertEquals(27, ((Damageable) armor.getItemMeta()).getDamage());
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.setDamage(armor, "99999"));
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.setDamage(new ItemStack(Material.STONE), "1"));
    }

    @Test void enchantmentsCanBeSetAndRemovedByNamespacedKey() {
        ItemStack armor = new ItemStack(Material.DIAMOND_CHESTPLATE);
        Enchantment protection = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("protection"));
        ItemDraftEditor.setEnchantment(armor, "minecraft:protection 4");
        assertEquals(4, armor.getItemMeta().getEnchantLevel(protection));
        ItemDraftEditor.setEnchantment(armor, "minecraft:protection 0");
        assertFalse(armor.getItemMeta().hasEnchant(protection));
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.setEnchantment(armor, "minecraft:protection 256"));
    }

    @Test void modelDataUnbreakableAndTooltipFlagsRoundTrip() {
        ItemStack armor = new ItemStack(Material.DIAMOND_CHESTPLATE);
        ItemDraftEditor.setCustomModelData(armor, "1234");
        assertEquals(1234, armor.getItemMeta().getCustomModelData());
        ItemDraftEditor.toggleUnbreakable(armor);
        assertTrue(armor.getItemMeta().isUnbreakable());
        ItemDraftEditor.toggleFlag(armor, ItemFlag.HIDE_ENCHANTS);
        assertTrue(armor.getItemMeta().hasItemFlag(ItemFlag.HIDE_ENCHANTS));
        ItemDraftEditor.toggleFlag(armor, ItemFlag.HIDE_ENCHANTS);
        assertFalse(armor.getItemMeta().hasItemFlag(ItemFlag.HIDE_ENCHANTS));
        ItemDraftEditor.setCustomModelData(armor, "none");
        assertFalse(armor.getItemMeta().hasCustomModelData());
        ItemDraftEditor.toggleUnbreakable(armor);
        assertFalse(armor.getItemMeta().isUnbreakable());
    }

    @Test void trimEditorRejectsNonArmorAndMalformedKeys() {
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.setTrim(new ItemStack(Material.STONE), "none"));
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.setTrim(new ItemStack(Material.DIAMOND_CHESTPLATE), "bad"));
    }

    @Test void visualComponentAndPersistentStringEditsPreserveEachOther() {
        ItemStack leather = new ItemStack(Material.LEATHER_CHESTPLATE);
        ItemDraftEditor.setPersistentString(leather, "recipeweaver:marker\nkept");
        ItemDraftEditor.setVisualComponent(leather, "#AA44FF");
        assertEquals(Color.fromRGB(0xAA44FF),
                ((LeatherArmorMeta) leather.getItemMeta()).getColor());
        assertEquals("kept", leather.getItemMeta().getPersistentDataContainer().get(
                new NamespacedKey("recipeweaver", "marker"), PersistentDataType.STRING));
        ItemStack rocket = new ItemStack(Material.FIREWORK_ROCKET);
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.setVisualComponent(rocket, "128 #AA44FF STAR"));
        ItemDraftEditor.setPersistentString(leather, "recipeweaver:marker\nnone");
        assertFalse(leather.getItemMeta().getPersistentDataContainer().has(
                new NamespacedKey("recipeweaver", "marker"), PersistentDataType.STRING));
    }

    @Test void leatherAndPotionColorsAreValidated() {
        ItemStack leather = new ItemStack(Material.LEATHER_CHESTPLATE);
        ItemDraftEditor.setVisualComponent(leather, "#113355");
        assertEquals(Color.fromRGB(0x113355),
                ((LeatherArmorMeta) leather.getItemMeta()).getColor());
        ItemStack potion = new ItemStack(Material.POTION);
        ItemDraftEditor.setVisualComponent(potion, "#225577");
        assertEquals(Color.fromRGB(0x225577), ((PotionMeta) potion.getItemMeta()).getColor());
        ItemDraftEditor.setVisualComponent(potion, "none");
        assertEquals(null, ((PotionMeta) potion.getItemMeta()).getColor());
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.setVisualComponent(new ItemStack(Material.STONE), "#123456"));
    }

    @Test void importRequiresAResolvableNamespacedItemAndReturnsACopy() {
        ItemStack provided = new ItemStack(Material.DIAMOND, 2);
        ItemStack imported = ItemDraftEditor.resolveImport("oraxen:sample",
                key -> key.toString().equals("oraxen:sample")
                        ? Optional.of(provided) : Optional.empty());
        imported.setAmount(1);
        assertEquals(2, provided.getAmount());
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.resolveImport("missing", key -> Optional.of(provided)));
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.resolveImport("oraxen:missing", key -> Optional.empty()));
    }

    @Test void persistentEditorWritesEachSupportedScalarTypeWithoutLosingOtherMetadata() {
        ItemStack item = new ItemStack(Material.DIAMOND);
        ItemDraftEditor.setPersistentData(item, "recipeweaver:text\nplain value");
        ItemDraftEditor.setPersistentData(item, "recipeweaver:literal\n@string none");
        ItemDraftEditor.setPersistentData(item, "recipeweaver:byte\n@byte -12");
        ItemDraftEditor.setPersistentData(item, "recipeweaver:short\n@short 1200");
        ItemDraftEditor.setPersistentData(item, "recipeweaver:int\n@int 123456");
        ItemDraftEditor.setPersistentData(item, "recipeweaver:long\n@long 12345678901");
        ItemDraftEditor.setPersistentData(item, "recipeweaver:float\n@float 1.25");
        ItemDraftEditor.setPersistentData(item, "recipeweaver:double\n@double 2.5");
        var data = item.getItemMeta().getPersistentDataContainer();
        assertEquals("plain value", data.get(key("text"), PersistentDataType.STRING));
        assertEquals("none", data.get(key("literal"), PersistentDataType.STRING));
        assertEquals((byte) -12, data.get(key("byte"), PersistentDataType.BYTE));
        assertEquals((short) 1200, data.get(key("short"), PersistentDataType.SHORT));
        assertEquals(123456, data.get(key("int"), PersistentDataType.INTEGER));
        assertEquals(12345678901L, data.get(key("long"), PersistentDataType.LONG));
        assertEquals(1.25F, data.get(key("float"), PersistentDataType.FLOAT));
        assertEquals(2.5D, data.get(key("double"), PersistentDataType.DOUBLE));
        ItemDraftEditor.setPersistentData(item, "recipeweaver:int\nnone");
        assertFalse(item.getItemMeta().getPersistentDataContainer().has(key("int")));
        assertEquals("plain value", item.getItemMeta().getPersistentDataContainer()
                .get(key("text"), PersistentDataType.STRING));
    }

    @Test void persistentEditorWritesArraysAndRejectsMalformedValuesAtomically() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemDraftEditor.setPersistentData(item, "recipeweaver:bytes\n@bytes 00A5ff");
        ItemDraftEditor.setPersistentData(item, "recipeweaver:ints\n@ints -1, 0, 42");
        ItemDraftEditor.setPersistentData(item, "recipeweaver:longs\n@longs 1, 9223372036854775807");
        var data = item.getItemMeta().getPersistentDataContainer();
        assertArrayEquals(new byte[] {0, (byte) 0xA5, (byte) 0xFF},
                data.get(key("bytes"), PersistentDataType.BYTE_ARRAY));
        assertArrayEquals(new int[] {-1, 0, 42},
                data.get(key("ints"), PersistentDataType.INTEGER_ARRAY));
        assertArrayEquals(new long[] {1L, Long.MAX_VALUE},
                data.get(key("longs"), PersistentDataType.LONG_ARRAY));
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.setPersistentData(item, "recipeweaver:ints\n@ints 1, invalid"));
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.setPersistentData(item, "recipeweaver:bytes\n@bytes A5F"));
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.setPersistentData(item, "recipeweaver:double\n@double NaN"));
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.setPersistentData(item, "recipeweaver:byte\n@byte 128"));
        assertThrows(IllegalArgumentException.class, () ->
                ItemDraftEditor.setPersistentData(item, "recipeweaver:missing\n@unknown 1"));
        assertArrayEquals(new int[] {-1, 0, 42}, item.getItemMeta()
                .getPersistentDataContainer().get(key("ints"), PersistentDataType.INTEGER_ARRAY));
        assertFalse(item.getItemMeta().getPersistentDataContainer().has(key("missing")));
    }

    @Test void persistentBrowserListsTypedValuesInStableOrderAndPrefillsEditableSyntax() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemDraftEditor.setPersistentData(item, "recipeweaver:zeta\n@bytes 00ff");
        ItemDraftEditor.setPersistentData(item, "recipeweaver:alpha\n@string none");
        var entries = ItemDraftEditor.persistentEntries(item);
        assertEquals(List.of(key("alpha"), key("zeta")),
                entries.stream().map(ItemDraftEditor.PersistentEntry::key).toList());
        assertEquals("@string none", entries.get(0).editorValue());
        assertEquals("@bytes 00ff", entries.get(1).editorValue());
        assertEquals("bytes", entries.get(1).type());
    }

    @Test void persistentEditorAcceptsAFullByteArrayAcrossBookPages() {
        ItemStack item = new ItemStack(Material.PAPER);
        String hex = "a5".repeat(512);
        ItemDraftEditor.setPersistentData(item, "recipeweaver:large\n@bytes " + hex);
        assertEquals(512, item.getItemMeta().getPersistentDataContainer()
                .get(key("large"), PersistentDataType.BYTE_ARRAY).length);
        assertEquals("@bytes " + hex, ItemDraftEditor.persistentEntries(item).get(0).editorValue());
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.setPersistentData(
                item, "recipeweaver:large\n@bytes " + hex + "a5"));
    }

    @Test void itemComponentControlsCycleAndValidateIndependently() {
        ItemStack item = new ItemStack(Material.PAPER, 2);
        ItemDraftEditor.cycleEnchantmentGlint(item);
        assertEquals(Boolean.TRUE, item.getItemMeta().getEnchantmentGlintOverride());
        ItemDraftEditor.cycleEnchantmentGlint(item);
        assertEquals(Boolean.FALSE, item.getItemMeta().getEnchantmentGlintOverride());
        ItemDraftEditor.cycleEnchantmentGlint(item);
        assertFalse(item.getItemMeta().hasEnchantmentGlintOverride());

        ItemDraftEditor.toggleFireResistance(item);
        assertTrue(item.getItemMeta().isFireResistant());
        ItemDraftEditor.toggleFireResistance(item);
        assertFalse(item.getItemMeta().isFireResistant());
        ItemDraftEditor.toggleHideTooltip(item);
        assertTrue(item.getItemMeta().isHideTooltip());
        ItemDraftEditor.toggleHideTooltip(item);
        assertFalse(item.getItemMeta().isHideTooltip());

        ItemDraftEditor.setMaxStackSize(item, "16");
        assertEquals(16, item.getItemMeta().getMaxStackSize());
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.setMaxStackSize(item, "1"));
        assertEquals(16, item.getItemMeta().getMaxStackSize());
        ItemDraftEditor.setMaxStackSize(item, "none");
        assertFalse(item.getItemMeta().hasMaxStackSize());

        ItemDraftEditor.setRarity(item, "rare");
        assertEquals(ItemRarity.RARE, item.getItemMeta().getRarity());
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.setRarity(item, "legendary"));
        assertEquals(ItemRarity.RARE, item.getItemMeta().getRarity());
        ItemDraftEditor.setRarity(item, "epic");
        assertEquals(ItemRarity.EPIC, item.getItemMeta().getRarity());
        ItemDraftEditor.setRarity(item, "default");
        assertFalse(item.getItemMeta().hasRarity());
    }

    @Test void potionBaseAndCustomEffectsAreEditableWithoutLosingColor() {
        ItemStack potion = new ItemStack(Material.POTION);
        ItemDraftEditor.setVisualComponent(potion, "#225577");
        ItemDraftEditor.setPotionBase(potion, "HEALING");
        ItemDraftEditor.setPotionEffect(potion, "minecraft:speed 200 1");
        PotionMeta meta = (PotionMeta) potion.getItemMeta();
        assertEquals(PotionType.HEALING, meta.getBasePotionType());
        assertEquals(200, meta.getCustomEffects().get(0).getDuration());
        assertEquals(1, meta.getCustomEffects().get(0).getAmplifier());
        assertEquals(PotionEffectType.SPEED, meta.getCustomEffects().get(0).getType());
        assertEquals(Color.fromRGB(0x225577), meta.getColor());
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.setPotionEffect(potion, "minecraft:speed 200 999"));
        assertEquals(1, ((PotionMeta) potion.getItemMeta()).getCustomEffects().size());
        ItemDraftEditor.setPotionEffect(potion, "minecraft:speed 0");
        assertTrue(((PotionMeta) potion.getItemMeta()).getCustomEffects().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.setPotionBase(new ItemStack(Material.PAPER), "HEALING"));
    }

    @Test void writtenBookTitleAuthorAndPagesAreEditableIndependently() {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        ItemDraftEditor.setBookTitle(book, "Recipe Guide");
        ItemDraftEditor.setBookAuthor(book, "TM Game Studio");
        ItemDraftEditor.setBookPages(book, "First page\n---PAGE---\nSecond page");
        BookMeta meta = (BookMeta) book.getItemMeta();
        assertEquals("Recipe Guide", meta.getTitle());
        assertEquals("TM Game Studio", meta.getAuthor());
        assertEquals(List.of("First page", "Second page"), meta.getPages());
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.setBookTitle(book, "x".repeat(33)));
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.setBookPages(book, "\n---PAGE---\nempty first page"));
        assertEquals(List.of("First page", "Second page"),
                ((BookMeta) book.getItemMeta()).getPages());
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.setBookAuthor(new ItemStack(Material.PAPER), "Author"));
    }

    @Test void foodPropertiesAndEffectsCanBeAddedReplacedAndRemoved() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemDraftEditor.setFoodProperties(item, "4 0.6 true 1.6");
        var food = item.getItemMeta().getFood();
        assertEquals(4, food.getNutrition());
        assertEquals(0.6F, food.getSaturation());
        assertTrue(food.canAlwaysEat());
        assertEquals(1.6F, food.getEatSeconds());
        ItemDraftEditor.setFoodEffect(item, "minecraft:speed 200 1 0.5");
        food = item.getItemMeta().getFood();
        assertEquals(1, food.getEffects().size());
        assertEquals(0.5F, food.getEffects().get(0).getProbability());
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.setFoodEffect(item, "minecraft:speed 200 1 1.5"));
        assertEquals(1, item.getItemMeta().getFood().getEffects().size());
        ItemDraftEditor.setFoodEffect(item, "minecraft:speed 0");
        assertTrue(item.getItemMeta().getFood().getEffects().isEmpty());
        ItemDraftEditor.setFoodProperties(item, "none");
        assertFalse(item.getItemMeta().hasFood());
    }

    @Test void toolPropertiesAndSingleBlockRuleCanBeEditedWithoutDamagingOtherRules() {
        ItemStack item = new ItemStack(Material.STICK);
        ItemDraftEditor.setToolProperties(item, "4.0 1");
        assertEquals(4.0F, item.getItemMeta().getTool().getDefaultMiningSpeed());
        assertEquals(1, item.getItemMeta().getTool().getDamagePerBlock());
        ItemDraftEditor.setToolRule(item, "STONE 8.0 true");
        assertEquals(1, item.getItemMeta().getTool().getRules().size());
        assertEquals(8.0F, item.getItemMeta().getTool().getRules().get(0).getSpeed());
        assertTrue(item.getItemMeta().getTool().getRules().get(0).isCorrectForDrops());
        ItemDraftEditor.setToolRule(item, "STONE 9.0 false");
        assertEquals(1, item.getItemMeta().getTool().getRules().size());
        assertEquals(9.0F, item.getItemMeta().getTool().getRules().get(0).getSpeed());
        ItemDraftEditor.setToolRule(item, "STONE none");
        assertTrue(item.getItemMeta().getTool().getRules().isEmpty());
        ItemDraftEditor.setToolProperties(item, "none");
        assertFalse(item.getItemMeta().hasTool());
    }

    @Test void mapColorIdAndScalingCanBeChangedSeparately() {
        var world = MockBukkit.getMock().addSimpleWorld("map-test");
        int mapId = org.bukkit.Bukkit.createMap(world).getId();
        ItemStack map = new ItemStack(Material.FILLED_MAP);
        ItemDraftEditor.setMapColor(map, "#AA44FF");
        ItemDraftEditor.setMapId(map, Integer.toString(mapId));
        ItemDraftEditor.toggleMapScaling(map);
        MapMeta meta = (MapMeta) map.getItemMeta();
        assertEquals(Color.fromRGB(0xAA44FF), meta.getColor());
        assertEquals(mapId, meta.getMapId());
        assertTrue(meta.isScaling());
        ItemDraftEditor.setMapColor(map, "none");
        assertFalse(((MapMeta) map.getItemMeta()).hasColor());
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.setMapId(map, "-1"));
    }

    @Test void compassLodestoneAndTrackingAreValidatedIndependently() {
        MockBukkit.getMock().addSimpleWorld("compass-test");
        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemDraftEditor.setCompassLodestone(compass, "compass-test 8 65 -3");
        CompassMeta meta = (CompassMeta) compass.getItemMeta();
        assertEquals(8, meta.getLodestone().getBlockX());
        assertEquals(65, meta.getLodestone().getBlockY());
        assertEquals(-3, meta.getLodestone().getBlockZ());
        boolean before = meta.isLodestoneTracked();
        ItemDraftEditor.toggleCompassTracking(compass);
        assertEquals(!before, ((CompassMeta) compass.getItemMeta()).isLodestoneTracked());
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.setCompassLodestone(compass, "missing-world 8 65 -3"));
        ItemDraftEditor.setCompassLodestone(compass, "none");
        assertFalse(((CompassMeta) compass.getItemMeta()).hasLodestone());
    }

    @Test void crossbowProjectileCanBeChangedOrCleared() {
        ItemStack crossbow = new ItemStack(Material.CROSSBOW);
        ItemDraftEditor.setCrossbowProjectile(crossbow, "FIREWORK_ROCKET");
        assertEquals(Material.FIREWORK_ROCKET, ((CrossbowMeta) crossbow.getItemMeta())
                .getChargedProjectiles().get(0).getType());
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.setCrossbowProjectile(crossbow, "STONE"));
        ItemDraftEditor.setCrossbowProjectile(crossbow, "none");
        assertFalse(((CrossbowMeta) crossbow.getItemMeta()).hasChargedProjectiles());
    }

    @Test void bannerPatternsCanBeAppendedAndRemoved() {
        ItemStack banner = new ItemStack(Material.WHITE_BANNER);
        ItemDraftEditor.editBannerPattern(banner, "RED CIRCLE");
        BannerMeta meta = (BannerMeta) banner.getItemMeta();
        assertEquals(1, meta.numberOfPatterns());
        assertEquals(DyeColor.RED, meta.getPattern(0).getColor());
        assertEquals(PatternType.CIRCLE, meta.getPattern(0).getPattern());
        ItemDraftEditor.editBannerPattern(banner, "remove-last");
        assertEquals(0, ((BannerMeta) banner.getItemMeta()).numberOfPatterns());
    }

    @Test void suspiciousStewEffectCanBeAddedAndRemoved() {
        ItemStack stew = new ItemStack(Material.SUSPICIOUS_STEW);
        ItemDraftEditor.setStewEffect(stew, "minecraft:speed 200");
        SuspiciousStewMeta meta = (SuspiciousStewMeta) stew.getItemMeta();
        assertEquals(1, meta.getCustomEffects().size());
        assertEquals(PotionEffectType.SPEED, meta.getCustomEffects().get(0).getType());
        assertEquals(200, meta.getCustomEffects().get(0).getDuration());
        assertEquals(0, meta.getCustomEffects().get(0).getAmplifier());
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.setStewEffect(stew, "minecraft:speed 200 1"));
        ItemDraftEditor.setStewEffect(stew, "minecraft:speed 0");
        assertFalse(((SuspiciousStewMeta) stew.getItemMeta()).hasCustomEffects());
    }

    @Test void tropicalFishVariantStoresBothColorsAndPattern() {
        ItemStack bucket = new ItemStack(Material.TROPICAL_FISH_BUCKET);
        ItemDraftEditor.setTropicalFishVariant(bucket, "RED BLUE KOB");
        TropicalFishBucketMeta meta = (TropicalFishBucketMeta) bucket.getItemMeta();
        assertEquals(DyeColor.RED, meta.getBodyColor());
        assertEquals(DyeColor.BLUE, meta.getPatternColor());
        assertEquals(TropicalFish.Pattern.KOB, meta.getPattern());
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.setTropicalFishVariant(bucket, "RED BLUE UNKNOWN"));
    }

    @Test void fireworkEffectsCanBeAppendedWithoutReplacingPreviousEffects() {
        ItemStack rocket = new ItemStack(Material.FIREWORK_ROCKET);
        ItemDraftEditor.setVisualComponent(rocket, "2 #AA44FF STAR");
        ItemDraftEditor.editFireworkEffect(rocket, "BURST #FF0000");
        FireworkMeta meta = (FireworkMeta) rocket.getItemMeta();
        assertEquals(2, meta.getEffectsSize());
        assertEquals(org.bukkit.FireworkEffect.Type.STAR, meta.getEffects().get(0).getType());
        assertEquals(org.bukkit.FireworkEffect.Type.BURST, meta.getEffects().get(1).getType());
        ItemDraftEditor.editFireworkEffect(rocket, "remove-last");
        assertEquals(1, ((FireworkMeta) rocket.getItemMeta()).getEffectsSize());
    }

    @Test void enchantedBookStoredEnchantmentsCanBeAddedAndRemoved() {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        Enchantment unbreaking = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));
        ItemDraftEditor.setStoredEnchantment(book, "minecraft:unbreaking 3");
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        assertEquals(3, meta.getStoredEnchantLevel(unbreaking));
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.setStoredEnchantment(book, "minecraft:unbreaking 256"));
        assertEquals(3, ((EnchantmentStorageMeta) book.getItemMeta()).getStoredEnchantLevel(unbreaking));
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.setStoredEnchantment(new ItemStack(Material.BOOK),
                        "minecraft:unbreaking 3"));
        ItemDraftEditor.setStoredEnchantment(book, "minecraft:unbreaking 0");
        assertFalse(((EnchantmentStorageMeta) book.getItemMeta()).hasStoredEnchants());
    }

    @Test void customMaxDurabilityBoundsDamageAndCanBeCleared() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        ItemDraftEditor.setMaxDamage(sword, "500");
        assertEquals(500, ((Damageable) sword.getItemMeta()).getMaxDamage());
        ItemDraftEditor.setDamage(sword, "499");
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.setDamage(sword, "500"));
        assertThrows(IllegalArgumentException.class, () -> ItemDraftEditor.setMaxDamage(sword, "300"));
        assertEquals(500, ((Damageable) sword.getItemMeta()).getMaxDamage());
        ItemDraftEditor.setDamage(sword, "0");
        ItemDraftEditor.setMaxDamage(sword, "none");
        assertFalse(((Damageable) sword.getItemMeta()).hasMaxDamage());
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.setMaxDamage(new ItemStack(Material.PAPER), "500"));
    }

    @Test void fireworkStarEffectCanBeSetAndCleared() {
        ItemStack star = new ItemStack(Material.FIREWORK_STAR);
        ItemDraftEditor.setVisualComponent(star, "BURST #AA44FF");
        FireworkEffectMeta meta = (FireworkEffectMeta) star.getItemMeta();
        assertTrue(meta.hasEffect());
        assertEquals(org.bukkit.FireworkEffect.Type.BURST, meta.getEffect().getType());
        assertEquals(Color.fromRGB(0xAA44FF), meta.getEffect().getColors().get(0));
        assertThrows(IllegalArgumentException.class,
                () -> ItemDraftEditor.setVisualComponent(star, "BAD #AA44FF"));
        assertTrue(((FireworkEffectMeta) star.getItemMeta()).hasEffect());
        ItemDraftEditor.setVisualComponent(star, "none");
        assertFalse(((FireworkEffectMeta) star.getItemMeta()).hasEffect());
    }

    private static NamespacedKey key(String value) {
        return new NamespacedKey("recipeweaver", value);
    }
}
