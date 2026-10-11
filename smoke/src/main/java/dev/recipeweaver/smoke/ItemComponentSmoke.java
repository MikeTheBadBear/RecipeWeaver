package dev.recipeweaver.smoke;

import dev.recipeweaver.api.RecipeWeaverApi;
import dev.recipeweaver.api.GridRecipeDefinition;
import dev.recipeweaver.api.GridTier;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.MapLocationLabel;
import dev.recipeweaver.api.RecipeKind;
import dev.recipeweaver.api.ResultMetadataMode;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.DyeColor;
import org.bukkit.block.banner.PatternType;
import org.bukkit.entity.TropicalFish;
import org.bukkit.plugin.Plugin;

/** Exercises item-specific editor helpers and saved-item round trips on real servers. */
final class ItemComponentSmoke {
    static void verifyCompoundArrayEditor() {
        ItemStack draft = new ItemStack(Material.PAPER);
        NamespacedKey records = new NamespacedKey("recipeweaver", "smoke_compound_array");
        NamespacedKey rank = new NamespacedKey("recipeweaver", "rank");
        NamespacedKey stats = new NamespacedKey("recipeweaver", "stats");
        edit("setPersistentData", draft,
                "recipeweaver:smoke_compound_array\n@compoundarray append recipeweaver:rank @int 7");
        edit("setPersistentData", draft,
                "recipeweaver:smoke_compound_array\n@compoundarray append recipeweaver:rank @int 11");
        edit("setPersistentData", draft,
                "recipeweaver:smoke_compound_array\n@compoundarray 0 recipeweaver:stats>recipeweaver:rank @int 3");
        var data = draft.getItemMeta().getPersistentDataContainer();
        var array = data.get(records, PersistentDataType.TAG_CONTAINER_ARRAY);
        check(array != null && array.length == 2
                        && Integer.valueOf(7).equals(array[0].get(rank, PersistentDataType.INTEGER))
                        && Integer.valueOf(11).equals(array[1].get(rank, PersistentDataType.INTEGER))
                        && Integer.valueOf(3).equals(array[0]
                                .get(stats, PersistentDataType.TAG_CONTAINER)
                                .get(rank, PersistentDataType.INTEGER)),
                "Live compound-array editor lost typed or indexed values");
        edit("setPersistentData", draft,
                "recipeweaver:smoke_compound_array\n@compoundarray remove 0");
        array = draft.getItemMeta().getPersistentDataContainer()
                .get(records, PersistentDataType.TAG_CONTAINER_ARRAY);
        check(array != null && array.length == 1
                        && Integer.valueOf(11).equals(array[0].get(rank, PersistentDataType.INTEGER)),
                "Live compound-array editor failed to compact after element removal");
    }

    private static final NamespacedKey ROCKET = new NamespacedKey("recipeweaver", "smoke_component_rocket");
    private static final NamespacedKey LEATHER = new NamespacedKey("recipeweaver", "smoke_component_leather");
    private static final NamespacedKey POTION = new NamespacedKey("recipeweaver", "smoke_component_potion");
    private static final NamespacedKey BOOK = new NamespacedKey("recipeweaver", "smoke_component_book");
    private static final NamespacedKey FOOD = new NamespacedKey("recipeweaver", "smoke_component_food");
    private static final NamespacedKey TOOL = new NamespacedKey("recipeweaver", "smoke_component_tool");
    private static final NamespacedKey MAP = new NamespacedKey("recipeweaver", "smoke_component_map");
    private static final NamespacedKey COMPASS = new NamespacedKey("recipeweaver", "smoke_component_compass");
    private static final NamespacedKey CROSSBOW = new NamespacedKey("recipeweaver", "smoke_component_crossbow");
    private static final NamespacedKey BANNER = new NamespacedKey("recipeweaver", "smoke_component_banner");
    private static final NamespacedKey STEW = new NamespacedKey("recipeweaver", "smoke_component_stew");
    private static final NamespacedKey FISH = new NamespacedKey("recipeweaver", "smoke_component_fish");
    private static final NamespacedKey ENCHANTED_BOOK = new NamespacedKey("recipeweaver", "smoke_component_enchanted_book");
    private static final NamespacedKey DURABILITY = new NamespacedKey("recipeweaver", "smoke_component_durability");
    private static final NamespacedKey FIREWORK_STAR = new NamespacedKey("recipeweaver", "smoke_component_firework_star");
    private static final NamespacedKey DATA = new NamespacedKey("recipeweaver", "component_marker");
    private static final NamespacedKey LEVEL = new NamespacedKey("recipeweaver", "component_level");
    private static final NamespacedKey RATIO = new NamespacedKey("recipeweaver", "component_ratio");
    private static final NamespacedKey BYTES = new NamespacedKey("recipeweaver", "component_bytes");
    private static final NamespacedKey LONGS = new NamespacedKey("recipeweaver", "component_longs");
    private static final NamespacedKey BYTE_LIST = new NamespacedKey("recipeweaver", "component_byte_list");
    private static final NamespacedKey SHORT_LIST = new NamespacedKey("recipeweaver", "component_short_list");
    private static final NamespacedKey FLOAT_LIST = new NamespacedKey("recipeweaver", "component_float_list");
    private static final NamespacedKey DOUBLE_LIST = new NamespacedKey("recipeweaver", "component_double_list");
    private static final NamespacedKey BYTE_ARRAY_LIST = new NamespacedKey("recipeweaver", "component_byte_array_list");
    private static final NamespacedKey INT_ARRAY_LIST = new NamespacedKey("recipeweaver", "component_int_array_list");
    private static final NamespacedKey LONG_ARRAY_LIST = new NamespacedKey("recipeweaver", "component_long_array_list");
    private static final NamespacedKey NESTED = new NamespacedKey("recipeweaver", "component_nested");
    private static final NamespacedKey STATS = new NamespacedKey("recipeweaver", "stats");
    private static final NamespacedKey RANK = new NamespacedKey("recipeweaver", "rank");
    private static final String LONG_LORE = ("Long lore " + "x".repeat(170) + "\n")
            .repeat(8).stripTrailing();
    private static final byte[] LARGE_BYTES = new byte[512];

    static { Arrays.fill(LARGE_BYTES, (byte) 0xA5); }

    private ItemComponentSmoke() { }

    static void verify(RecipeWeaverApi api, Plugin harness) {
        ItemStack rocket = new ItemStack(Material.FIREWORK_ROCKET, 2);
        edit("setPersistentString", rocket, "recipeweaver:component_marker\nrocket-value");
        edit("setPersistentData", rocket, "recipeweaver:component_level\n@int 42");
        edit("setPersistentData", rocket, "recipeweaver:component_ratio\n@double 1.25");
        edit("setPersistentData", rocket, "recipeweaver:component_bytes\n@bytes " + "a5".repeat(512));
        edit("setPersistentData", rocket, "recipeweaver:component_longs\n@longs 1, 9223372036854775807");
        edit("setPersistentData", rocket, "recipeweaver:component_byte_list\n@bytelist -128, 127");
        edit("setPersistentData", rocket, "recipeweaver:component_short_list\n@shortlist -32768, 32767");
        edit("setPersistentData", rocket, "recipeweaver:component_float_list\n@floatlist -1.25, 0.5");
        edit("setPersistentData", rocket, "recipeweaver:component_double_list\n@doublelist -1.25, 0.5");
        edit("setPersistentData", rocket, "recipeweaver:component_byte_array_list\n@bytearraylist 00ff\n80a5");
        edit("setPersistentData", rocket, "recipeweaver:component_int_array_list\n@intarraylist -2, 0\n3, 5");
        edit("setPersistentData", rocket, "recipeweaver:component_long_array_list\n@longarraylist -2, 0\n3, 9223372036854775807");
        edit("setPersistentData", rocket,
                "recipeweaver:component_nested\n@compound recipeweaver:stats>recipeweaver:rank @int 7");
        edit("replaceLore", rocket, LONG_LORE);
        edit("setVisualComponent", rocket, "2 #AA44FF STAR");
        edit("editFireworkEffect", rocket, "BURST #FF0000");
        edit("cycleEnchantmentGlint", rocket);
        edit("toggleFireResistance", rocket);
        edit("toggleHideTooltip", rocket);
        edit("setMaxStackSize", rocket, "16");
        edit("setRarity", rocket, "rare");
        if (!rocketSaved(rocket)) {
            FireworkMeta detail = (FireworkMeta) rocket.getItemMeta();
            throw new IllegalStateException("Live firework editor mismatch: power=" + detail.getPower()
                    + " effects=" + detail.getEffectsSize() + " glint="
                    + (detail.hasEnchantmentGlintOverride()
                            ? detail.getEnchantmentGlintOverride() : "automatic")
                    + " fire=" + detail.isFireResistant() + " hidden=" + detail.isHideTooltip()
                    + " stack=" + (detail.hasMaxStackSize() ? detail.getMaxStackSize() : "default")
                    + " rarity=" + (detail.hasRarity() ? detail.getRarity() : "default")
                    + " lore=" + (detail.getLore() == null ? 0 : detail.getLore().size())
                    + " pdc=" + detail.getPersistentDataContainer().getKeys());
        }
        ItemStack leather = new ItemStack(Material.LEATHER_CHESTPLATE);
        edit("setVisualComponent", leather, "#113355");
        check(leatherSaved(leather), "Live leather color edit was not applied");
        ItemStack potion = new ItemStack(Material.POTION);
        edit("setVisualComponent", potion, "#225577");
        edit("setPotionBase", potion, "HEALING");
        edit("setPotionEffect", potion, "minecraft:speed 200 1");
        check(potionSaved(potion), "Live potion color edit was not applied");
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        edit("setBookTitle", book, "Recipe Guide");
        edit("setBookAuthor", book, "TM Game Studio");
        edit("setBookPages", book, "First page " + "a".repeat(600)
                + "\n---PAGE---\nSecond page " + "b".repeat(600));
        check(bookSaved(book), "Live book title, author, or pages were not applied");
        ItemStack food = new ItemStack(Material.PAPER);
        edit("setFoodProperties", food, "4 0.6 true 1.6");
        edit("setFoodEffect", food, "minecraft:speed 200 1 0.5");
        check(foodSaved(food), "Live food properties or effect were not applied");
        ItemStack tool = new ItemStack(Material.STICK);
        edit("setToolProperties", tool, "4.0 1");
        edit("setToolRule", tool, "STONE 8.0 true");
        check(toolSaved(tool), "Live tool properties or block rule were not applied");
        verifyFieldRules(rocket, food, tool);
        harness.getLogger().info("RW_ITEM_FIELD_RULES_PASS");
        ItemStack map = new ItemStack(Material.FILLED_MAP);
        org.bukkit.inventory.meta.MapMeta mapMeta =
                (org.bukkit.inventory.meta.MapMeta) map.getItemMeta();
        mapMeta.setMapView(Bukkit.createMap(Bukkit.getWorlds().get(0)));
        map.setItemMeta(mapMeta);
        edit("setMapColor", map, "#AA44FF");
        int secondMapId = Bukkit.createMap(Bukkit.getWorlds().get(0)).getId();
        edit("setMapId", map, Integer.toString(secondMapId));
        check(((org.bukkit.inventory.meta.MapMeta) map.getItemMeta()).getMapId() == secondMapId,
                "Map ID editor did not select the second existing map");
        if (!((org.bukkit.inventory.meta.MapMeta) map.getItemMeta()).isScaling()) {
            edit("toggleMapScaling", map);
        } else {
            edit("toggleMapScaling", map);
            check(!((org.bukkit.inventory.meta.MapMeta) map.getItemMeta()).isScaling(),
                    "Map scaling did not turn off");
            edit("toggleMapScaling", map);
        }
        check(mapSaved(map), "Live map display components were not applied: "
                + map.getItemMeta());
        verifyMapLocationNameSelector();
        harness.getLogger().info("RW_ITEM_MAP_LOCATION_MATCH_PASS");
        verifyMapLocationNameCopy();
        harness.getLogger().info("RW_ITEM_MAP_LOCATION_COPY_PASS");
        verifyHeadSoundSelector();
        harness.getLogger().info("RW_ITEM_HEAD_SOUND_MATCH_PASS");
        verifyHeadSoundCopy();
        harness.getLogger().info("RW_ITEM_HEAD_SOUND_COPY_PASS");
        ItemStack compass = new ItemStack(Material.COMPASS);
        String world = Bukkit.getWorlds().get(0).getName();
        edit("setCompassLodestone", compass, world + " 8 65 -3");
        boolean tracking = ((org.bukkit.inventory.meta.CompassMeta) compass.getItemMeta())
                .isLodestoneTracked();
        edit("toggleCompassTracking", compass);
        check(((org.bukkit.inventory.meta.CompassMeta) compass.getItemMeta())
                        .isLodestoneTracked() != tracking,
                "Compass tracking did not toggle");
        if (!((org.bukkit.inventory.meta.CompassMeta) compass.getItemMeta()).isLodestoneTracked()) {
            edit("toggleCompassTracking", compass);
        }
        check(compassSaved(compass), "Live compass target or tracking was not applied");
        ItemStack crossbow = new ItemStack(Material.CROSSBOW);
        edit("setCrossbowProjectile", crossbow, "FIREWORK_ROCKET");
        check(crossbowSaved(crossbow), "Live crossbow charge was not applied");
        ItemStack banner = new ItemStack(Material.WHITE_BANNER);
        edit("editBannerPattern", banner, "RED CIRCLE");
        check(bannerSaved(banner), "Live banner pattern was not applied");
        ItemStack stew = new ItemStack(Material.SUSPICIOUS_STEW);
        edit("setStewEffect", stew, "minecraft:speed 200");
        check(stewSaved(stew), "Live suspicious stew effect was not applied: "
                + ((org.bukkit.inventory.meta.SuspiciousStewMeta) stew.getItemMeta()).getCustomEffects());
        ItemStack fish = new ItemStack(Material.TROPICAL_FISH_BUCKET);
        edit("setTropicalFishVariant", fish, "RED BLUE KOB");
        org.bukkit.inventory.meta.TropicalFishBucketMeta fishMeta =
                (org.bukkit.inventory.meta.TropicalFishBucketMeta) fish.getItemMeta();
        if (!fishSaved(fish)) {
            throw new IllegalStateException("Live tropical fish variant was not applied: variant="
                    + fishMeta.hasVariant() + (fishMeta.hasVariant()
                    ? " body=" + fishMeta.getBodyColor() + " patternColor="
                            + fishMeta.getPatternColor() + " pattern=" + fishMeta.getPattern() : ""));
        }
        ItemStack enchantedBook = new ItemStack(Material.ENCHANTED_BOOK);
        edit("setStoredEnchantment", enchantedBook, "minecraft:unbreaking 3");
        check(enchantedBookSaved(enchantedBook), "Live stored enchantment was not applied");
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        edit("setMaxDamage", sword, "500");
        edit("setDamage", sword, "300");
        check(durabilitySaved(sword), "Live custom durability was not applied");
        ItemStack star = new ItemStack(Material.FIREWORK_STAR);
        edit("setVisualComponent", star, "BURST #AA44FF");
        check(fireworkStarSaved(star), "Live firework star effect was not applied");

        check(api.registerItemProvider(harness, "smoke", key -> rocket),
                "Could not register the item import smoke provider");
        try {
            ItemStack imported = importDraft("smoke:component_item", api::resolveItem);
            check(rocketSaved(imported), "Provider item import lost its server-held components");
            imported.setAmount(1);
            check(rocket.getAmount() == 2, "Provider import did not clone its source");
        } finally {
            check(api.unregisterItemProvider(harness, "smoke"),
                    "Could not unregister the item import smoke provider");
        }
        api.saveCustomItem(ROCKET, rocket);
        api.saveCustomItem(LEATHER, leather);
        api.saveCustomItem(POTION, potion);
        api.saveCustomItem(BOOK, book);
        api.saveCustomItem(FOOD, food);
        api.saveCustomItem(TOOL, tool);
        api.saveCustomItem(MAP, map);
        api.saveCustomItem(COMPASS, compass);
        api.saveCustomItem(CROSSBOW, crossbow);
        api.saveCustomItem(BANNER, banner);
        api.saveCustomItem(STEW, stew);
        api.saveCustomItem(FISH, fish);
        api.saveCustomItem(ENCHANTED_BOOK, enchantedBook);
        api.saveCustomItem(DURABILITY, sword);
        api.saveCustomItem(FIREWORK_STAR, star);
        SmokePlugin.reloadForLegacyAssertions(api);
        checkRecovered(api);
    }

    static boolean saved(RecipeWeaverApi api) {
        return api.customItem(ROCKET).map(ItemComponentSmoke::rocketSaved).orElse(false)
                && api.customItem(LEATHER).map(ItemComponentSmoke::leatherSaved).orElse(false)
                && api.customItem(POTION).map(ItemComponentSmoke::potionSaved).orElse(false)
                && api.customItem(BOOK).map(ItemComponentSmoke::bookSaved).orElse(false)
                && api.customItem(FOOD).map(ItemComponentSmoke::foodSaved).orElse(false)
                && api.customItem(TOOL).map(ItemComponentSmoke::toolSaved).orElse(false)
                && api.customItem(MAP).map(ItemComponentSmoke::mapSaved).orElse(false)
                && api.customItem(COMPASS).map(ItemComponentSmoke::compassSaved).orElse(false)
                && api.customItem(CROSSBOW).map(ItemComponentSmoke::crossbowSaved).orElse(false)
                && api.customItem(BANNER).map(ItemComponentSmoke::bannerSaved).orElse(false)
                && api.customItem(STEW).map(ItemComponentSmoke::stewSaved).orElse(false)
                && api.customItem(FISH).map(ItemComponentSmoke::fishSaved).orElse(false)
                && api.customItem(ENCHANTED_BOOK).map(ItemComponentSmoke::enchantedBookSaved).orElse(false)
                && api.customItem(DURABILITY).map(ItemComponentSmoke::durabilitySaved).orElse(false)
                && api.customItem(FIREWORK_STAR).map(ItemComponentSmoke::fireworkStarSaved).orElse(false);
    }

    static void checkRecovered(RecipeWeaverApi api) {
        if (saved(api)) return;
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (!api.customItem(ROCKET).map(ItemComponentSmoke::rocketSaved).orElse(false)) missing.add("rocket");
        if (!api.customItem(LEATHER).map(ItemComponentSmoke::leatherSaved).orElse(false)) missing.add("leather");
        if (!api.customItem(POTION).map(ItemComponentSmoke::potionSaved).orElse(false)) missing.add("potion");
        if (!api.customItem(BOOK).map(ItemComponentSmoke::bookSaved).orElse(false)) missing.add("book");
        if (!api.customItem(FOOD).map(ItemComponentSmoke::foodSaved).orElse(false)) missing.add("food");
        if (!api.customItem(TOOL).map(ItemComponentSmoke::toolSaved).orElse(false)) missing.add("tool");
        if (!api.customItem(MAP).map(ItemComponentSmoke::mapSaved).orElse(false)) {
            String detail = api.customItem(MAP).map(item -> {
                org.bukkit.inventory.meta.MapMeta meta =
                        (org.bukkit.inventory.meta.MapMeta) item.getItemMeta();
                return "id=" + (meta.hasMapId() ? meta.getMapId() : "none")
                        + ", color=" + (meta.hasColor() ? meta.getColor() : "none")
                        + ", scaling=" + meta.isScaling()
                        + ", view=" + (meta.hasMapId() ? Bukkit.getMap(meta.getMapId()) : "none");
            }).orElse("missing");
            missing.add("map(" + detail + ")");
        }
        if (!api.customItem(COMPASS).map(ItemComponentSmoke::compassSaved).orElse(false)) {
            String detail = api.customItem(COMPASS).map(item -> {
                org.bukkit.inventory.meta.CompassMeta meta =
                        (org.bukkit.inventory.meta.CompassMeta) item.getItemMeta();
                return "target=" + meta.getLodestone() + ", tracked=" + meta.isLodestoneTracked();
            }).orElse("missing");
            missing.add("compass(" + detail + ")");
        }
        if (!api.customItem(CROSSBOW).map(ItemComponentSmoke::crossbowSaved).orElse(false)) missing.add("crossbow");
        if (!api.customItem(BANNER).map(ItemComponentSmoke::bannerSaved).orElse(false)) missing.add("banner");
        if (!api.customItem(STEW).map(ItemComponentSmoke::stewSaved).orElse(false)) missing.add("stew");
        if (!api.customItem(FISH).map(ItemComponentSmoke::fishSaved).orElse(false)) missing.add("fish");
        if (!api.customItem(ENCHANTED_BOOK).map(ItemComponentSmoke::enchantedBookSaved).orElse(false)) missing.add("enchanted book");
        if (!api.customItem(DURABILITY).map(ItemComponentSmoke::durabilitySaved).orElse(false)) missing.add("durability");
        if (!api.customItem(FIREWORK_STAR).map(ItemComponentSmoke::fireworkStarSaved).orElse(false)) missing.add("firework star");
        throw new IllegalStateException("Item components were not recovered: " + missing);
    }

    static void mutateForRestore(RecipeWeaverApi api) {
        api.saveCustomItem(ROCKET, new ItemStack(Material.FIREWORK_ROCKET));
        api.saveCustomItem(LEATHER, new ItemStack(Material.LEATHER_CHESTPLATE));
        api.saveCustomItem(POTION, new ItemStack(Material.POTION));
        api.saveCustomItem(BOOK, new ItemStack(Material.WRITTEN_BOOK));
        api.saveCustomItem(FOOD, new ItemStack(Material.PAPER));
        api.saveCustomItem(TOOL, new ItemStack(Material.STICK));
        api.saveCustomItem(MAP, new ItemStack(Material.FILLED_MAP));
        api.saveCustomItem(COMPASS, new ItemStack(Material.COMPASS));
        api.saveCustomItem(CROSSBOW, new ItemStack(Material.CROSSBOW));
        api.saveCustomItem(BANNER, new ItemStack(Material.WHITE_BANNER));
        api.saveCustomItem(STEW, new ItemStack(Material.SUSPICIOUS_STEW));
        api.saveCustomItem(FISH, new ItemStack(Material.TROPICAL_FISH_BUCKET));
        api.saveCustomItem(ENCHANTED_BOOK, new ItemStack(Material.ENCHANTED_BOOK));
        api.saveCustomItem(DURABILITY, new ItemStack(Material.DIAMOND_SWORD));
        api.saveCustomItem(FIREWORK_STAR, new ItemStack(Material.FIREWORK_STAR));
        SmokePlugin.reloadForLegacyAssertions(api);
        check(!saved(api), "Item component backup mutation did not save");
    }

    private static boolean rocketSaved(ItemStack item) {
        if (item.getType() != Material.FIREWORK_ROCKET || item.getAmount() != 2
                || !(item.getItemMeta() instanceof FireworkMeta meta)) return false;
        return meta.getPower() == 2 && meta.getEffectsSize() == 2
                && meta.hasEnchantmentGlintOverride()
                && Boolean.TRUE.equals(meta.getEnchantmentGlintOverride())
                && meta.isFireResistant() && meta.isHideTooltip()
                && meta.hasMaxStackSize() && meta.getMaxStackSize() == 16
                && meta.hasRarity() && meta.getRarity() == ItemRarity.RARE
                && Arrays.asList(LONG_LORE.split("\n")).equals(meta.getLore())
                && meta.getEffects().get(0).getType() == org.bukkit.FireworkEffect.Type.STAR
                && meta.getEffects().get(0).getColors().contains(Color.fromRGB(0xAA44FF))
                && meta.getEffects().get(1).getType() == org.bukkit.FireworkEffect.Type.BURST
                && meta.getEffects().get(1).getColors().contains(Color.fromRGB(0xFF0000))
                && "rocket-value".equals(meta.getPersistentDataContainer()
                        .get(DATA, PersistentDataType.STRING))
                && Integer.valueOf(42).equals(meta.getPersistentDataContainer()
                        .get(LEVEL, PersistentDataType.INTEGER))
                && Double.valueOf(1.25D).equals(meta.getPersistentDataContainer()
                        .get(RATIO, PersistentDataType.DOUBLE))
                && Arrays.equals(LARGE_BYTES,
                        meta.getPersistentDataContainer().get(BYTES, PersistentDataType.BYTE_ARRAY))
                && Arrays.equals(new long[] {1L, Long.MAX_VALUE},
                        meta.getPersistentDataContainer().get(LONGS, PersistentDataType.LONG_ARRAY))
                && List.of((byte) -128, (byte) 127).equals(meta.getPersistentDataContainer()
                        .get(BYTE_LIST, PersistentDataType.LIST.bytes()))
                && List.of((short) -32768, (short) 32767).equals(meta.getPersistentDataContainer()
                        .get(SHORT_LIST, PersistentDataType.LIST.shorts()))
                && List.of(-1.25F, 0.5F).equals(meta.getPersistentDataContainer()
                        .get(FLOAT_LIST, PersistentDataType.LIST.floats()))
                && List.of(-1.25D, 0.5D).equals(meta.getPersistentDataContainer()
                        .get(DOUBLE_LIST, PersistentDataType.LIST.doubles()))
                && byteArraysEqual(meta.getPersistentDataContainer().get(
                        BYTE_ARRAY_LIST, PersistentDataType.LIST.byteArrays()),
                        new byte[] {0, (byte) 255}, new byte[] {(byte) 128, (byte) 165})
                && intArraysEqual(meta.getPersistentDataContainer().get(
                        INT_ARRAY_LIST, PersistentDataType.LIST.integerArrays()),
                        new int[] {-2, 0}, new int[] {3, 5})
                && longArraysEqual(meta.getPersistentDataContainer().get(
                        LONG_ARRAY_LIST, PersistentDataType.LIST.longArrays()),
                        new long[] {-2, 0}, new long[] {3, Long.MAX_VALUE})
                && nestedRankSaved(meta.getPersistentDataContainer());
    }

    private static boolean nestedRankSaved(org.bukkit.persistence.PersistentDataContainer data) {
        var root = data.get(NESTED, PersistentDataType.TAG_CONTAINER);
        if (root == null) return false;
        var stats = root.get(STATS, PersistentDataType.TAG_CONTAINER);
        return stats != null && Integer.valueOf(7).equals(
                stats.get(RANK, PersistentDataType.INTEGER));
    }

    private static boolean byteArraysEqual(List<byte[]> actual, byte[] first, byte[] second) {
        return actual != null && actual.size() == 2
                && Arrays.equals(first, actual.get(0)) && Arrays.equals(second, actual.get(1));
    }

    private static boolean intArraysEqual(List<int[]> actual, int[] first, int[] second) {
        return actual != null && actual.size() == 2
                && Arrays.equals(first, actual.get(0)) && Arrays.equals(second, actual.get(1));
    }

    private static boolean longArraysEqual(List<long[]> actual, long[] first, long[] second) {
        return actual != null && actual.size() == 2
                && Arrays.equals(first, actual.get(0)) && Arrays.equals(second, actual.get(1));
    }

    private static void verifyFieldRules(ItemStack rich, ItemStack food, ItemStack tool) {
        check(matchRule(rich, IngredientMatchMode.FIRE_RESISTANT_ONLY).matchesItemSample(0, rich),
                "Live fire-resistance selector rejected its sample");
        ItemStack changed = rich.clone();
        edit("toggleFireResistance", changed);
        var meta = changed.getItemMeta();
        check(!meta.isFireResistant(), "Live fire-resistance editor did not clear the flag");
        check(!matchRule(rich, IngredientMatchMode.FIRE_RESISTANT_ONLY).matchesItemSample(0, changed),
                "Live fire-resistance selector accepted the wrong flag");

        check(matchRule(rich, IngredientMatchMode.HIDE_TOOLTIP_ONLY).matchesItemSample(0, rich),
                "Live hide-tooltip selector rejected its sample");
        changed = rich.clone();
        meta = changed.getItemMeta();
        meta.setHideTooltip(false);
        changed.setItemMeta(meta);
        check(!matchRule(rich, IngredientMatchMode.HIDE_TOOLTIP_ONLY).matchesItemSample(0, changed),
                "Live hide-tooltip selector accepted the wrong flag");

        check(matchRule(rich, IngredientMatchMode.RARITY_ONLY).matchesItemSample(0, rich),
                "Live rarity selector rejected its sample");
        changed = rich.clone();
        meta = changed.getItemMeta();
        meta.setRarity(null);
        changed.setItemMeta(meta);
        check(!matchRule(rich, IngredientMatchMode.RARITY_ONLY).matchesItemSample(0, changed),
                "Live rarity selector accepted the wrong rarity");

        check(matchRule(food, IngredientMatchMode.FOOD_ONLY).matchesItemSample(0, food),
                "Live food selector rejected its sample");
        changed = food.clone();
        edit("setFoodProperties", changed, "5 0.6 true 1.6");
        check(!matchRule(food, IngredientMatchMode.FOOD_ONLY).matchesItemSample(0, changed),
                "Live food selector accepted different nutrition");
        check(matchRule(tool, IngredientMatchMode.TOOL_ONLY).matchesItemSample(0, tool),
                "Live tool selector rejected its sample");
        changed = tool.clone();
        edit("setToolProperties", changed, "5.0 1");
        check(!matchRule(tool, IngredientMatchMode.TOOL_ONLY).matchesItemSample(0, changed),
                "Live tool selector accepted different mining speed");

        ItemStack template = new ItemStack(Material.PAPER);
        meta = template.getItemMeta();
        meta.setDisplayName("Configured output");
        template.setItemMeta(meta);
        ItemStack copied = copyRule(rich, template, ResultMetadataMode.FIRE_RESISTANT_ONLY);
        check(copied.getItemMeta().isFireResistant()
                        && "Configured output".equals(copied.getItemMeta().getDisplayName())
                        && !template.getItemMeta().isFireResistant(),
                "Live fire-resistance copy changed output identity or template");
        ItemStack cleared = copyRule(changed, rich, ResultMetadataMode.FIRE_RESISTANT_ONLY);
        check(!cleared.getItemMeta().isFireResistant(),
                "Live fire-resistance copy could not clear an existing flag");
        copied = copyRule(rich, template, ResultMetadataMode.HIDE_TOOLTIP_ONLY);
        check(copied.getItemMeta().isHideTooltip()
                        && "Configured output".equals(copied.getItemMeta().getDisplayName())
                        && !template.getItemMeta().isHideTooltip(),
                "Live hide-tooltip copy changed output identity or template");
        copied = copyRule(rich, template, ResultMetadataMode.RARITY_ONLY);
        check(copied.getItemMeta().hasRarity()
                        && copied.getItemMeta().getRarity() == ItemRarity.RARE
                        && "Configured output".equals(copied.getItemMeta().getDisplayName())
                        && !template.getItemMeta().hasRarity(),
                "Live rarity copy changed output identity or template");
    }

    private static void verifyMapLocationNameSelector() {
        ItemStack sample = mapWithLocationName("Starting point");
        check("Starting point".equals(sample.getItemMeta().getPersistentDataContainer()
                        .get(MapLocationLabel.STORAGE_KEY, PersistentDataType.STRING)),
                "Live map-location editor did not store the sample label");
        GridRecipeDefinition rule = matchRule(sample, IngredientMatchMode.MAP_LOCATION_NAME_ONLY);
        ItemStack renamed = namedItem(sample.clone(), "Different ingredient name");
        check(rule.matchesItemSample(0, renamed),
                "Live map-location selector rejected the same label after an unrelated name change");
        ItemStack changed = mapWithLocationName("Other point");
        check("Other point".equals(changed.getItemMeta().getPersistentDataContainer()
                        .get(MapLocationLabel.STORAGE_KEY, PersistentDataType.STRING)),
                "Live map-location editor did not store the changed label");
        check(!rule.matchesItemSample(0, changed),
                "Live map-location selector accepted a different label");
    }

    private static void verifyMapLocationNameCopy() {
        ItemStack source = mapWithLocationName("Starting point");
        ItemStack template = namedItem(mapWithLocationName("Old point"), "Configured map output");
        ItemStack copied = copyRule(source, template, ResultMetadataMode.MAP_LOCATION_NAME_ONLY);
        org.bukkit.inventory.meta.MapMeta copiedMeta =
                (org.bukkit.inventory.meta.MapMeta) copied.getItemMeta();
        check("Starting point".equals(MapLocationLabel.read(copiedMeta))
                        && "Configured map output".equals(copiedMeta.getDisplayName())
                        && "Old point".equals(MapLocationLabel.read(template.getItemMeta())),
                "Live map-location copy failed to replace only the label");
        ItemStack cleared = copyRule(new ItemStack(Material.PAPER), copied,
                ResultMetadataMode.MAP_LOCATION_NAME_ONLY);
        org.bukkit.inventory.meta.MapMeta clearedMeta =
                (org.bukkit.inventory.meta.MapMeta) cleared.getItemMeta();
        check(MapLocationLabel.read(clearedMeta) == null
                        && "".equals(clearedMeta.getPersistentDataContainer().get(
                                MapLocationLabel.STORAGE_KEY, PersistentDataType.STRING))
                        && "Configured map output".equals(clearedMeta.getDisplayName()),
                "Live map-location copy failed to clear the label while retaining the output name");
    }

    private static void verifyHeadSoundSelector() {
        ItemStack sample = headWithSound("minecraft:block.note_block.harp");
        GridRecipeDefinition rule = matchRule(sample, IngredientMatchMode.HEAD_SOUND_ONLY);
        ItemStack renamed = namedItem(sample.clone(), "Different ingredient name");
        check(rule.matchesItemSample(0, renamed),
                "Live head-sound selector rejected the same sound after an unrelated name change");
        ItemStack changed = headWithSound("minecraft:block.note_block.bell");
        check(!rule.matchesItemSample(0, changed),
                "Live head-sound selector accepted a different sound key");
    }

    private static void verifyHeadSoundCopy() {
        ItemStack source = headWithSound("minecraft:block.note_block.harp");
        ItemStack template = namedItem(headWithSound("minecraft:block.note_block.bell"),
                "Configured head output");
        ItemStack copied = copyRule(source, template, ResultMetadataMode.HEAD_SOUND_ONLY);
        org.bukkit.inventory.meta.SkullMeta copiedMeta =
                (org.bukkit.inventory.meta.SkullMeta) copied.getItemMeta();
        check(NamespacedKey.minecraft("block.note_block.harp")
                        .equals(copiedMeta.getNoteBlockSound())
                        && "Configured head output".equals(copiedMeta.getDisplayName())
                        && NamespacedKey.minecraft("block.note_block.bell")
                                .equals(((org.bukkit.inventory.meta.SkullMeta)
                                        template.getItemMeta()).getNoteBlockSound()),
                "Live head-sound copy failed to replace only the sound key");
        ItemStack cleared = copyRule(new ItemStack(Material.PAPER), copied,
                ResultMetadataMode.HEAD_SOUND_ONLY);
        org.bukkit.inventory.meta.SkullMeta clearedMeta =
                (org.bukkit.inventory.meta.SkullMeta) cleared.getItemMeta();
        check(clearedMeta.getNoteBlockSound() == null
                        && "Configured head output".equals(clearedMeta.getDisplayName()),
                "Live head-sound copy failed to clear the sound while retaining the output name");
    }

    private static ItemStack mapWithLocationName(String label) {
        ItemStack map = new ItemStack(Material.FILLED_MAP);
        edit("setMapLocationName", map, label);
        return map;
    }

    private static ItemStack headWithSound(String key) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        edit("setHeadNoteBlockSound", head, key);
        return head;
    }

    private static ItemStack namedItem(ItemStack item, String name) {
        var meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    private static GridRecipeDefinition matchRule(ItemStack sample, IngredientMatchMode mode) {
        return new GridRecipeDefinition(new NamespacedKey("recipeweaver",
                "smoke_field_" + mode.name().toLowerCase(Locale.ROOT)),
                GridTier.ELITE, RecipeKind.SHAPED, 2,
                Arrays.asList(sample, null, null, null), new ItemStack(Material.DIAMOND),
                "", true, false,
                List.of(mode, IngredientMatchMode.MATERIAL,
                        IngredientMatchMode.MATERIAL, IngredientMatchMode.MATERIAL), 0, true);
    }

    private static ItemStack copyRule(ItemStack source, ItemStack target, ResultMetadataMode mode) {
        try {
            ClassLoader loader = Bukkit.getPluginManager().getPlugin("RecipeWeaver")
                    .getClass().getClassLoader();
            Class<?> rules = Class.forName("dev.recipeweaver.core.GridResultMetadata", true, loader);
            Method method = rules.getDeclaredMethod("copyFrom", ItemStack.class, ItemStack.class,
                    ResultMetadataMode.class);
            method.setAccessible(true);
            return (ItemStack) method.invoke(null, source, target, mode);
        } catch (InvocationTargetException ex) {
            throw new IllegalStateException("Live selected result copy failed: " + mode, ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not call selected result copy: " + mode, ex);
        }
    }

    private static boolean leatherSaved(ItemStack item) {
        return item.getType() == Material.LEATHER_CHESTPLATE
                && item.getItemMeta() instanceof LeatherArmorMeta meta
                && meta.getColor().equals(Color.fromRGB(0x113355));
    }

    private static boolean potionSaved(ItemStack item) {
        return item.getType() == Material.POTION
                && item.getItemMeta() instanceof PotionMeta meta
                && Color.fromRGB(0x225577).equals(meta.getColor())
                && meta.hasBasePotionType() && meta.getBasePotionType() == PotionType.HEALING
                && meta.getCustomEffects().size() == 1
                && meta.getCustomEffects().get(0).getType() == PotionEffectType.SPEED
                && meta.getCustomEffects().get(0).getDuration() == 200
                && meta.getCustomEffects().get(0).getAmplifier() == 1;
    }

    private static boolean bookSaved(ItemStack item) {
        return item.getType() == Material.WRITTEN_BOOK
                && item.getItemMeta() instanceof org.bukkit.inventory.meta.BookMeta meta
                && "Recipe Guide".equals(meta.getTitle())
                && "TM Game Studio".equals(meta.getAuthor())
                && meta.getPageCount() == 2
                && meta.getPage(1).equals("First page " + "a".repeat(600))
                && meta.getPage(2).equals("Second page " + "b".repeat(600));
    }

    private static boolean foodSaved(ItemStack item) {
        if (item.getType() != Material.PAPER || !item.getItemMeta().hasFood()) return false;
        var food = item.getItemMeta().getFood();
        return food.getNutrition() == 4 && food.getSaturation() == 0.6F
                && food.canAlwaysEat()
                && (float) readFood("foodConsumeSeconds", new Class<?>[] {ItemStack.class}, item) == 1.6F
                && (int) readFood("foodEffectCount", new Class<?>[] {ItemStack.class}, item) == 1
                && (boolean) readFood("foodEffectMatches",
                        new Class<?>[] {ItemStack.class, PotionEffectType.class,
                                int.class, int.class, float.class},
                        item, PotionEffectType.SPEED, 200, 1, 0.5F);
    }

    private static boolean toolSaved(ItemStack item) {
        if (item.getType() != Material.STICK || !item.getItemMeta().hasTool()) return false;
        var tool = item.getItemMeta().getTool();
        return tool.getDefaultMiningSpeed() == 4.0F && tool.getDamagePerBlock() == 1
                && tool.getRules().size() == 1
                && tool.getRules().get(0).getBlocks().contains(Material.STONE)
                && tool.getRules().get(0).getSpeed() == 8.0F
                && Boolean.TRUE.equals(tool.getRules().get(0).isCorrectForDrops());
    }

    private static boolean mapSaved(ItemStack item) {
        return item.getType() == Material.FILLED_MAP
                && item.getItemMeta() instanceof org.bukkit.inventory.meta.MapMeta meta
                && meta.hasColor() && Color.fromRGB(0xAA44FF).equals(meta.getColor())
                && meta.hasMapId() && Bukkit.getMap(meta.getMapId()) != null
                && meta.isScaling();
    }

    private static boolean compassSaved(ItemStack item) {
        return item.getType() == Material.COMPASS
                && item.getItemMeta() instanceof org.bukkit.inventory.meta.CompassMeta meta
                && meta.hasLodestone() && meta.isLodestoneTracked()
                && meta.getLodestone().getWorld() != null
                && meta.getLodestone().getBlockX() == 8
                && meta.getLodestone().getBlockY() == 65
                && meta.getLodestone().getBlockZ() == -3;
    }

    private static boolean crossbowSaved(ItemStack item) {
        return item.getType() == Material.CROSSBOW
                && item.getItemMeta() instanceof org.bukkit.inventory.meta.CrossbowMeta meta
                && meta.hasChargedProjectiles() && meta.getChargedProjectiles().size() == 1
                && meta.getChargedProjectiles().get(0).getType() == Material.FIREWORK_ROCKET;
    }

    private static boolean bannerSaved(ItemStack item) {
        return item.getType() == Material.WHITE_BANNER
                && item.getItemMeta() instanceof org.bukkit.inventory.meta.BannerMeta meta
                && meta.numberOfPatterns() == 1
                && meta.getPattern(0).getColor() == DyeColor.RED
                && meta.getPattern(0).getPattern() == PatternType.CIRCLE;
    }

    private static boolean stewSaved(ItemStack item) {
        return item.getType() == Material.SUSPICIOUS_STEW
                && item.getItemMeta() instanceof org.bukkit.inventory.meta.SuspiciousStewMeta meta
                && meta.getCustomEffects().size() == 1
                && meta.getCustomEffects().get(0).getType() == PotionEffectType.SPEED
                && meta.getCustomEffects().get(0).getDuration() == 200
                && meta.getCustomEffects().get(0).getAmplifier() == 0;
    }

    private static boolean fishSaved(ItemStack item) {
        return item.getType() == Material.TROPICAL_FISH_BUCKET
                && item.getItemMeta() instanceof org.bukkit.inventory.meta.TropicalFishBucketMeta meta
                && meta.hasVariant() && meta.getBodyColor() == DyeColor.RED
                && meta.getPatternColor() == DyeColor.BLUE
                && meta.getPattern() == TropicalFish.Pattern.KOB;
    }

    private static boolean enchantedBookSaved(ItemStack item) {
        return item.getType() == Material.ENCHANTED_BOOK
                && item.getItemMeta() instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta meta
                && meta.getStoredEnchantLevel(org.bukkit.Registry.ENCHANTMENT.get(
                        NamespacedKey.minecraft("unbreaking"))) == 3;
    }

    private static boolean durabilitySaved(ItemStack item) {
        return item.getType() == Material.DIAMOND_SWORD
                && item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable meta
                && meta.hasMaxDamage() && meta.getMaxDamage() == 500
                && meta.getDamage() == 300;
    }

    private static boolean fireworkStarSaved(ItemStack item) {
        return item.getType() == Material.FIREWORK_STAR
                && item.getItemMeta() instanceof org.bukkit.inventory.meta.FireworkEffectMeta meta
                && meta.hasEffect() && meta.getEffect().getType() == org.bukkit.FireworkEffect.Type.BURST
                && meta.getEffect().getColors().contains(Color.fromRGB(0xAA44FF));
    }

    private static void edit(String methodName, ItemStack item, String value) {
        try {
            Method method = editor().getDeclaredMethod(methodName, ItemStack.class, String.class);
            method.setAccessible(true);
            method.invoke(null, item, value);
        } catch (InvocationTargetException ex) {
            throw new IllegalStateException("Live item editor rejected " + methodName, ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not invoke live item editor " + methodName, ex);
        }
    }

    private static void edit(String methodName, ItemStack item) {
        try {
            Method method = editor().getDeclaredMethod(methodName, ItemStack.class);
            method.setAccessible(true);
            method.invoke(null, item);
        } catch (InvocationTargetException ex) {
            throw new IllegalStateException("Live item editor rejected " + methodName, ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not invoke live item editor " + methodName, ex);
        }
    }

    private static ItemStack importDraft(String key,
            Function<NamespacedKey, Optional<ItemStack>> resolver) {
        try {
            Method method = editor().getDeclaredMethod("resolveImport", String.class, Function.class);
            method.setAccessible(true);
            return (ItemStack) method.invoke(null, key, resolver);
        } catch (InvocationTargetException ex) {
            throw new IllegalStateException("Live item provider import failed", ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not invoke live item provider import", ex);
        }
    }

    private static Class<?> editor() throws ClassNotFoundException {
        ClassLoader loader = Bukkit.getPluginManager().getPlugin("RecipeWeaver")
                .getClass().getClassLoader();
        return Class.forName("dev.recipeweaver.core.ItemDraftEditor", true, loader);
    }

    private static Object readFood(String methodName, Class<?>[] parameters, Object... args) {
        try {
            Method method = editor().getDeclaredMethod(methodName, parameters);
            method.setAccessible(true);
            return method.invoke(null, args);
        } catch (InvocationTargetException ex) {
            throw new IllegalStateException("Live food component read failed: " + methodName,
                    ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not read live food component: " + methodName, ex);
        }
    }

    private static void check(boolean passed, String detail) {
        if (!passed) throw new IllegalStateException(detail);
    }
}
