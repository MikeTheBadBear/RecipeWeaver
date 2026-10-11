package dev.recipeweaver.smoke;

import dev.recipeweaver.api.GridRecipeDefinition;
import dev.recipeweaver.api.GridTier;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeKind;
import dev.recipeweaver.api.RecipeWeaverApi;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.FireworkEffect;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.FireworkEffectMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

/** Live, persisted checks for individual item-field ingredient selectors. */
final class FieldMatchSmoke {
    private static final NamespacedKey NAME = key("smoke_match_name_only");
    private static final NamespacedKey LORE = key("smoke_match_lore_only");
    private static final NamespacedKey MODEL = key("smoke_match_model_only");
    private static final NamespacedKey ENCHANTS = key("smoke_match_enchants_only");
    private static final NamespacedKey DAMAGE = key("smoke_match_damage_only");
    private static final NamespacedKey FLAGS = key("smoke_match_item_flags_only");
    private static final NamespacedKey ATTRIBUTES = key("smoke_match_attributes_only");
    private static final NamespacedKey UNBREAKABLE = key("smoke_match_unbreakable_only");
    private static final NamespacedKey ARMOR_TRIM = key("smoke_match_armor_trim_only");
    private static final NamespacedKey POTION = key("smoke_match_potion_only");
    private static final NamespacedKey STORED_ENCHANTMENTS =
            key("smoke_match_stored_enchantments_only");
    private static final NamespacedKey BANNER_PATTERNS =
            key("smoke_match_banner_patterns_only");
    private static final NamespacedKey CROSSBOW_PROJECTILES =
            key("smoke_match_crossbow_projectiles_only");
    private static final NamespacedKey FIREWORK_ROCKET = key("smoke_match_firework_rocket_only");
    private static final NamespacedKey FIREWORK_STAR = key("smoke_match_firework_star_only");
    private static final NamespacedKey BUNDLE_CONTENTS = key("smoke_match_bundle_contents_only");
    private static final NamespacedKey EXTRA = key("smoke_field_extra");

    private FieldMatchSmoke() { }

    static void verify(RecipeWeaverApi api, Plugin plugin) {
        ItemStack named = paper();
        ItemMeta name = named.getItemMeta();
        name.setDisplayName("Selected name");
        name.setItemName("Selected item name");
        named.setItemMeta(name);
        api.saveGridRecipe(recipe(NAME, named, IngredientMatchMode.NAME_ONLY));

        ItemStack lore = paper();
        ItemMeta loreMeta = lore.getItemMeta();
        loreMeta.setLore(List.of("First", "Second"));
        lore.setItemMeta(loreMeta);
        api.saveGridRecipe(recipe(LORE, lore, IngredientMatchMode.LORE_ONLY));

        ItemStack model = paper();
        ItemMeta modelMeta = model.getItemMeta();
        modelMeta.setCustomModelData(42);
        if (hasItemModel()) setItemModel(modelMeta, key("selected_model"));
        model.setItemMeta(modelMeta);
        api.saveGridRecipe(recipe(MODEL, model, IngredientMatchMode.MODEL_ONLY));

        ItemStack enchants = paper();
        ItemMeta enchantMeta = enchants.getItemMeta();
        enchantMeta.addEnchant(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking")),
                2, true);
        enchantMeta.setEnchantmentGlintOverride(true);
        enchants.setItemMeta(enchantMeta);
        api.saveGridRecipe(recipe(ENCHANTS, enchants, IngredientMatchMode.ENCHANTMENTS_ONLY));

        ItemStack damage = new ItemStack(Material.IRON_SWORD);
        Damageable damageMeta = (Damageable) damage.getItemMeta();
        damageMeta.setDamage(12);
        damageMeta.setMaxDamage(500);
        damage.setItemMeta(damageMeta);
        api.saveGridRecipe(recipe(DAMAGE, damage, IngredientMatchMode.DAMAGE_ONLY));

        ItemStack flags = paper();
        ItemMeta flagMeta = flags.getItemMeta();
        // Since 1.20.6 the hide-enchant flag belongs to the enchantments component.
        // An unenchanted paper item can lose that flag during Bukkit YAML restoration.
        flagMeta.addEnchant(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking")),
                1, true);
        flagMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        flags.setItemMeta(flagMeta);
        api.saveGridRecipe(recipe(FLAGS, flags, IngredientMatchMode.ITEM_FLAGS_ONLY));

        ItemStack attributes = new ItemStack(Material.DIAMOND_SWORD);
        setAttackModifier(attributes, 5D);
        api.saveGridRecipe(recipe(ATTRIBUTES, attributes,
                IngredientMatchMode.ATTRIBUTE_MODIFIERS_ONLY));

        ItemStack unbreakable = paper();
        ItemMeta unbreakableMeta = unbreakable.getItemMeta();
        unbreakableMeta.setUnbreakable(true);
        unbreakable.setItemMeta(unbreakableMeta);
        api.saveGridRecipe(recipe(UNBREAKABLE, unbreakable,
                IngredientMatchMode.UNBREAKABLE_ONLY));

        ItemStack trimmed = new ItemStack(Material.DIAMOND_CHESTPLATE);
        ArmorMeta trimmedMeta = (ArmorMeta) trimmed.getItemMeta();
        trimmedMeta.setTrim(armorTrim("iron", "sentry"));
        trimmed.setItemMeta(trimmedMeta);
        api.saveGridRecipe(recipe(ARMOR_TRIM, trimmed,
                IngredientMatchMode.ARMOR_TRIM_ONLY));

        ItemStack potion = new ItemStack(Material.POTION);
        PotionMeta potionMeta = (PotionMeta) potion.getItemMeta();
        potionMeta.setBasePotionType(PotionType.HEALING);
        potionMeta.setColor(Color.fromRGB(0x225577));
        potionMeta.addCustomEffect(new PotionEffect(PotionEffectType.SPEED, 200, 1), true);
        potion.setItemMeta(potionMeta);
        api.saveGridRecipe(recipe(POTION, potion, IngredientMatchMode.POTION_ONLY));

        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta bookMeta = (EnchantmentStorageMeta) book.getItemMeta();
        bookMeta.addStoredEnchant(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking")),
                3, true);
        book.setItemMeta(bookMeta);
        api.saveGridRecipe(recipe(STORED_ENCHANTMENTS, book,
                IngredientMatchMode.STORED_ENCHANTMENTS_ONLY));

        ItemStack banner = new ItemStack(Material.WHITE_BANNER);
        BannerMeta bannerMeta = (BannerMeta) banner.getItemMeta();
        bannerMeta.addPattern(new Pattern(DyeColor.BLUE, PatternType.CIRCLE));
        banner.setItemMeta(bannerMeta);
        api.saveGridRecipe(recipe(BANNER_PATTERNS, banner,
                IngredientMatchMode.BANNER_PATTERNS_ONLY));

        ItemStack crossbow = new ItemStack(Material.CROSSBOW);
        CrossbowMeta crossbowMeta = (CrossbowMeta) crossbow.getItemMeta();
        crossbowMeta.setChargedProjectiles(List.of(namedArrow("Selected arrow")));
        crossbow.setItemMeta(crossbowMeta);
        api.saveGridRecipe(recipe(CROSSBOW_PROJECTILES, crossbow,
                IngredientMatchMode.CROSSBOW_PROJECTILES_ONLY));

        ItemStack rocket = new ItemStack(Material.FIREWORK_ROCKET);
        FireworkMeta rocketMeta = (FireworkMeta) rocket.getItemMeta();
        rocketMeta.setPower(2);
        rocketMeta.addEffect(firework(FireworkEffect.Type.STAR, Color.BLUE));
        rocket.setItemMeta(rocketMeta);
        api.saveGridRecipe(recipe(FIREWORK_ROCKET, rocket, IngredientMatchMode.FIREWORK_ONLY));

        ItemStack star = new ItemStack(Material.FIREWORK_STAR);
        FireworkEffectMeta starMeta = (FireworkEffectMeta) star.getItemMeta();
        starMeta.setEffect(firework(FireworkEffect.Type.CREEPER, Color.BLUE));
        star.setItemMeta(starMeta);
        api.saveGridRecipe(recipe(FIREWORK_STAR, star, IngredientMatchMode.FIREWORK_ONLY));

        ItemStack bundle = new ItemStack(Material.BUNDLE);
        BundleMeta bundleMeta = (BundleMeta) bundle.getItemMeta();
        bundleMeta.setItems(List.of(namedArrow("Selected arrow"),
                new ItemStack(Material.DIAMOND, 2)));
        bundle.setItemMeta(bundleMeta);
        api.saveGridRecipe(recipe(BUNDLE_CONTENTS, bundle,
                IngredientMatchMode.BUNDLE_CONTENTS_ONLY));

        checkRecovered(api);
        plugin.getLogger().info("RW_MATCH_NAME_ONLY_PASS");
        plugin.getLogger().info("RW_MATCH_LORE_ONLY_PASS");
        plugin.getLogger().info("RW_MATCH_MODEL_ONLY_PASS");
        plugin.getLogger().info("RW_MATCH_ENCHANTMENTS_ONLY_PASS");
        plugin.getLogger().info("RW_MATCH_DAMAGE_ONLY_PASS");
        plugin.getLogger().info("RW_MATCH_ITEM_FLAGS_ONLY_PASS");
        plugin.getLogger().info("RW_MATCH_ATTRIBUTE_MODIFIERS_ONLY_PASS");
        plugin.getLogger().info("RW_MATCH_UNBREAKABLE_ONLY_PASS");
        plugin.getLogger().info("RW_MATCH_ARMOR_TRIM_ONLY_PASS");
        plugin.getLogger().info("RW_MATCH_POTION_ONLY_PASS");
        plugin.getLogger().info("RW_MATCH_STORED_ENCHANTMENTS_ONLY_PASS");
        plugin.getLogger().info("RW_MATCH_BANNER_PATTERNS_ONLY_PASS");
        plugin.getLogger().info("RW_MATCH_CROSSBOW_PROJECTILES_ONLY_PASS");
        plugin.getLogger().info("RW_MATCH_FIREWORK_ROCKET_ONLY_PASS");
        plugin.getLogger().info("RW_MATCH_FIREWORK_STAR_ONLY_PASS");
        plugin.getLogger().info("RW_MATCH_BUNDLE_CONTENTS_ONLY_PASS");
    }

    static void checkRecovered(RecipeWeaverApi api) {
        verifyName(api.gridRecipe(NAME).orElseThrow());
        verifyLore(api.gridRecipe(LORE).orElseThrow());
        verifyModel(api.gridRecipe(MODEL).orElseThrow());
        verifyEnchants(api.gridRecipe(ENCHANTS).orElseThrow());
        verifyDamage(api.gridRecipe(DAMAGE).orElseThrow());
        verifyFlags(api.gridRecipe(FLAGS).orElseThrow());
        verifyAttributes(api.gridRecipe(ATTRIBUTES).orElseThrow());
        verifyUnbreakable(api.gridRecipe(UNBREAKABLE).orElseThrow());
        verifyArmorTrim(api.gridRecipe(ARMOR_TRIM).orElseThrow());
        verifyPotion(api.gridRecipe(POTION).orElseThrow());
        verifyStoredEnchantments(api.gridRecipe(STORED_ENCHANTMENTS).orElseThrow());
        verifyBannerPatterns(api.gridRecipe(BANNER_PATTERNS).orElseThrow());
        verifyCrossbowProjectiles(api.gridRecipe(CROSSBOW_PROJECTILES).orElseThrow());
        verifyFireworkRocket(api.gridRecipe(FIREWORK_ROCKET).orElseThrow());
        verifyFireworkStar(api.gridRecipe(FIREWORK_STAR).orElseThrow());
        verifyBundleContents(api.gridRecipe(BUNDLE_CONTENTS).orElseThrow());
    }

    private static void verifyName(GridRecipeDefinition recipe) {
        check(recipe.matchModes().get(0) == IngredientMatchMode.NAME_ONLY,
                "Name-only selector was not persisted");
        ItemStack allowed = withExtra(recipe.ingredients().get(0));
        check(recipe.matchesItemSample(0, allowed), "Name-only selector rejected unrelated metadata");
        ItemMeta changed = allowed.getItemMeta();
        changed.setItemName("Different item name");
        allowed.setItemMeta(changed);
        check(!recipe.matchesItemSample(0, allowed), "Name-only selector ignored item name mismatch");
        changed = allowed.getItemMeta();
        changed.setItemName("Selected item name");
        changed.setDisplayName("Different display name");
        allowed.setItemMeta(changed);
        check(!recipe.matchesItemSample(0, allowed), "Name-only selector ignored display name mismatch");
    }

    private static void verifyLore(GridRecipeDefinition recipe) {
        check(recipe.matchModes().get(0) == IngredientMatchMode.LORE_ONLY,
                "Lore-only selector was not persisted");
        ItemStack allowed = withExtra(recipe.ingredients().get(0));
        ItemMeta named = allowed.getItemMeta();
        named.setDisplayName("Unrelated name");
        allowed.setItemMeta(named);
        check(recipe.matchesItemSample(0, allowed), "Lore-only selector rejected unrelated name");
        ItemMeta changed = allowed.getItemMeta();
        changed.setLore(List.of("First", "Different"));
        allowed.setItemMeta(changed);
        check(!recipe.matchesItemSample(0, allowed), "Lore-only selector ignored a changed line");
    }

    private static void verifyModel(GridRecipeDefinition recipe) {
        check(recipe.matchModes().get(0) == IngredientMatchMode.MODEL_ONLY,
                "Model-only selector was not persisted");
        ItemStack allowed = withExtra(recipe.ingredients().get(0));
        check(recipe.matchesItemSample(0, allowed), "Model-only selector rejected unrelated data");
        ItemMeta changed = allowed.getItemMeta();
        changed.setCustomModelData(43);
        allowed.setItemMeta(changed);
        check(!recipe.matchesItemSample(0, allowed), "Model-only selector ignored model data mismatch");
        if (hasItemModel()) {
            changed = withExtra(recipe.ingredients().get(0)).getItemMeta();
            setItemModel(changed, key("other_model"));
            ItemStack other = withExtra(recipe.ingredients().get(0));
            other.setItemMeta(changed);
            check(!recipe.matchesItemSample(0, other),
                    "Model-only selector ignored modern item-model mismatch");
        }
    }

    private static void verifyEnchants(GridRecipeDefinition recipe) {
        check(recipe.matchModes().get(0) == IngredientMatchMode.ENCHANTMENTS_ONLY,
                "Enchantments-only selector was not persisted");
        ItemStack allowed = withExtra(recipe.ingredients().get(0));
        check(recipe.matchesItemSample(0, allowed),
                "Enchantments-only selector rejected unrelated data");
        ItemMeta changed = allowed.getItemMeta();
        changed.setEnchantmentGlintOverride(false);
        allowed.setItemMeta(changed);
        check(!recipe.matchesItemSample(0, allowed),
                "Enchantments-only selector ignored glint mismatch");
        changed = allowed.getItemMeta();
        changed.setEnchantmentGlintOverride(true);
        changed.addEnchant(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("efficiency")),
                1, true);
        allowed.setItemMeta(changed);
        check(!recipe.matchesItemSample(0, allowed),
                "Enchantments-only selector ignored an additional enchantment");
    }

    private static void verifyDamage(GridRecipeDefinition recipe) {
        check(recipe.matchModes().get(0) == IngredientMatchMode.DAMAGE_ONLY,
                "Damage-only selector was not persisted");
        ItemStack allowed = withExtra(recipe.ingredients().get(0));
        ItemMeta named = allowed.getItemMeta();
        named.setDisplayName("Unrelated name");
        allowed.setItemMeta(named);
        check(recipe.matchesItemSample(0, allowed),
                "Damage-only selector rejected unrelated metadata");
        Damageable changed = (Damageable) allowed.getItemMeta();
        changed.setDamage(13);
        allowed.setItemMeta(changed);
        check(!recipe.matchesItemSample(0, allowed),
                "Damage-only selector ignored damage mismatch");
        changed = (Damageable) allowed.getItemMeta();
        changed.setDamage(12);
        changed.setMaxDamage(501);
        allowed.setItemMeta(changed);
        check(!recipe.matchesItemSample(0, allowed),
                "Damage-only selector ignored custom maximum mismatch");
    }

    private static void verifyFlags(GridRecipeDefinition recipe) {
        check(recipe.matchModes().get(0) == IngredientMatchMode.ITEM_FLAGS_ONLY,
                "Item-flags-only selector was not persisted");
        ItemStack allowed = withExtra(recipe.ingredients().get(0));
        ItemMeta named = allowed.getItemMeta();
        named.setDisplayName("Unrelated name");
        allowed.setItemMeta(named);
        check(recipe.matchesItemSample(0, allowed),
                "Item-flags-only selector rejected unrelated metadata");
        check(recipe.ingredients().get(0).getItemMeta().hasItemFlag(ItemFlag.HIDE_ENCHANTS),
                "Item-flags-only sample lost its selected flag after storage reload");
        ItemMeta changed = allowed.getItemMeta();
        changed.removeItemFlags(ItemFlag.HIDE_ENCHANTS);
        allowed.setItemMeta(changed);
        check(!allowed.getItemMeta().hasItemFlag(ItemFlag.HIDE_ENCHANTS),
                "Server retained the removed item flag on the candidate");
        check(!recipe.matchesItemSample(0, allowed),
                "Item-flags-only selector ignored a removed sample flag: sample="
                        + recipe.ingredients().get(0).getItemMeta().getItemFlags()
                        + ", candidate=" + allowed.getItemMeta().getItemFlags());
    }

    private static void verifyAttributes(GridRecipeDefinition recipe) {
        check(recipe.matchModes().get(0) == IngredientMatchMode.ATTRIBUTE_MODIFIERS_ONLY,
                "Attribute-only selector was not persisted");
        Attribute attack = attackDamage();
        ItemStack allowed = withExtra(recipe.ingredients().get(0));
        ItemMeta named = allowed.getItemMeta();
        named.setDisplayName("Unrelated name");
        allowed.setItemMeta(named);
        check(recipe.matchesItemSample(0, allowed),
                "Attribute-only selector rejected unrelated metadata");
        check(recipe.ingredients().get(0).getItemMeta().getAttributeModifiers(attack) != null,
                "Attribute-only sample lost modifiers after storage reload");
        setAttackModifier(allowed, 7D);
        check(!recipe.matchesItemSample(0, allowed),
                "Attribute-only selector ignored changed modifier amount");
    }

    private static void verifyUnbreakable(GridRecipeDefinition recipe) {
        check(recipe.matchModes().get(0) == IngredientMatchMode.UNBREAKABLE_ONLY,
                "Unbreakable-only selector was not persisted");
        ItemStack allowed = withExtra(recipe.ingredients().get(0));
        ItemMeta named = allowed.getItemMeta();
        named.setDisplayName("Unrelated name");
        allowed.setItemMeta(named);
        check(recipe.matchesItemSample(0, allowed),
                "Unbreakable-only selector rejected unrelated metadata");
        ItemMeta changed = allowed.getItemMeta();
        changed.setUnbreakable(false);
        allowed.setItemMeta(changed);
        check(!recipe.matchesItemSample(0, allowed),
                "Unbreakable-only selector ignored cleared component");
    }

    private static void verifyArmorTrim(GridRecipeDefinition recipe) {
        check(recipe.matchModes().get(0) == IngredientMatchMode.ARMOR_TRIM_ONLY,
                "Armor-trim-only selector was not persisted");
        ItemStack allowed = withExtra(recipe.ingredients().get(0));
        ArmorMeta named = (ArmorMeta) allowed.getItemMeta();
        named.setDisplayName("Unrelated name");
        allowed.setItemMeta(named);
        check(recipe.matchesItemSample(0, allowed),
                "Armor-trim-only selector rejected unrelated metadata");
        ArmorMeta changed = (ArmorMeta) allowed.getItemMeta();
        changed.setTrim(armorTrim("iron", "coast"));
        allowed.setItemMeta(changed);
        check(!recipe.matchesItemSample(0, allowed),
                "Armor-trim-only selector ignored a different trim pattern");
        changed.setTrim(null);
        allowed.setItemMeta(changed);
        check(!recipe.matchesItemSample(0, allowed),
                "Armor-trim-only selector ignored a missing trim");
    }

    private static void verifyPotion(GridRecipeDefinition recipe) {
        check(recipe.matchModes().get(0) == IngredientMatchMode.POTION_ONLY,
                "Potion-only selector was not persisted");
        ItemStack allowed = withExtra(recipe.ingredients().get(0));
        PotionMeta named = (PotionMeta) allowed.getItemMeta();
        named.setDisplayName("Unrelated name");
        allowed.setItemMeta(named);
        check(recipe.matchesItemSample(0, allowed),
                "Potion-only selector rejected unrelated metadata");
        PotionMeta changed = (PotionMeta) allowed.getItemMeta();
        changed.setBasePotionType(PotionType.WEAKNESS);
        allowed.setItemMeta(changed);
        check(!recipe.matchesItemSample(0, allowed),
                "Potion-only selector ignored changed base type");
        allowed = withExtra(recipe.ingredients().get(0));
        changed = (PotionMeta) allowed.getItemMeta();
        changed.setColor(Color.fromRGB(0x123456));
        allowed.setItemMeta(changed);
        check(!recipe.matchesItemSample(0, allowed),
                "Potion-only selector ignored changed color");
        allowed = withExtra(recipe.ingredients().get(0));
        changed = (PotionMeta) allowed.getItemMeta();
        changed.clearCustomEffects();
        allowed.setItemMeta(changed);
        check(!recipe.matchesItemSample(0, allowed),
                "Potion-only selector ignored missing custom effect");
    }

    private static void verifyStoredEnchantments(GridRecipeDefinition recipe) {
        check(recipe.matchModes().get(0) == IngredientMatchMode.STORED_ENCHANTMENTS_ONLY,
                "Stored-enchantments selector was not persisted");
        ItemStack allowed = withExtra(recipe.ingredients().get(0));
        EnchantmentStorageMeta named = (EnchantmentStorageMeta) allowed.getItemMeta();
        named.setDisplayName("Unrelated name");
        allowed.setItemMeta(named);
        check(recipe.matchesItemSample(0, allowed),
                "Stored-enchantments selector rejected unrelated metadata");
        EnchantmentStorageMeta changed = (EnchantmentStorageMeta) allowed.getItemMeta();
        changed.addStoredEnchant(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking")),
                2, true);
        allowed.setItemMeta(changed);
        check(!recipe.matchesItemSample(0, allowed),
                "Stored-enchantments selector ignored changed level");
        allowed = withExtra(recipe.ingredients().get(0));
        changed = (EnchantmentStorageMeta) allowed.getItemMeta();
        changed.addStoredEnchant(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("efficiency")),
                1, true);
        allowed.setItemMeta(changed);
        check(!recipe.matchesItemSample(0, allowed),
                "Stored-enchantments selector ignored extra stored enchantment");
    }

    private static void verifyBannerPatterns(GridRecipeDefinition recipe) {
        check(recipe.matchModes().get(0) == IngredientMatchMode.BANNER_PATTERNS_ONLY,
                "Banner-patterns selector was not persisted");
        ItemStack allowed = withExtra(recipe.ingredients().get(0));
        BannerMeta named = (BannerMeta) allowed.getItemMeta();
        named.setDisplayName("Unrelated name");
        allowed.setItemMeta(named);
        check(recipe.matchesItemSample(0, allowed),
                "Banner-patterns selector rejected unrelated metadata");
        BannerMeta changed = (BannerMeta) allowed.getItemMeta();
        changed.setPattern(0, new Pattern(DyeColor.RED, PatternType.CIRCLE));
        allowed.setItemMeta(changed);
        check(!recipe.matchesItemSample(0, allowed),
                "Banner-patterns selector ignored changed color");
        allowed = withExtra(recipe.ingredients().get(0));
        changed = (BannerMeta) allowed.getItemMeta();
        changed.addPattern(new Pattern(DyeColor.RED, PatternType.CIRCLE));
        allowed.setItemMeta(changed);
        check(!recipe.matchesItemSample(0, allowed),
                "Banner-patterns selector ignored extra pattern");
    }

    private static void verifyCrossbowProjectiles(GridRecipeDefinition recipe) {
        check(recipe.matchModes().get(0) == IngredientMatchMode.CROSSBOW_PROJECTILES_ONLY,
                "Crossbow-projectile selector was not persisted");
        ItemStack allowed = withExtra(recipe.ingredients().get(0));
        CrossbowMeta meta = (CrossbowMeta) allowed.getItemMeta();
        meta.setDisplayName("Unrelated name");
        allowed.setItemMeta(meta);
        check(recipe.matchesItemSample(0, allowed),
                "Crossbow-projectile selector rejected unrelated metadata");
        meta = (CrossbowMeta) allowed.getItemMeta();
        meta.setChargedProjectiles(List.of(namedArrow("Different arrow")));
        allowed.setItemMeta(meta);
        check(!recipe.matchesItemSample(0, allowed),
                "Crossbow-projectile selector ignored nested arrow data");
        meta = (CrossbowMeta) allowed.getItemMeta();
        meta.setChargedProjectiles(List.of());
        allowed.setItemMeta(meta);
        check(!recipe.matchesItemSample(0, allowed),
                "Crossbow-projectile selector ignored an empty charge");
    }

    private static void verifyFireworkRocket(GridRecipeDefinition recipe) {
        check(recipe.matchModes().get(0) == IngredientMatchMode.FIREWORK_ONLY,
                "Firework-rocket selector was not persisted");
        ItemStack allowed = withExtra(recipe.ingredients().get(0));
        FireworkMeta meta = (FireworkMeta) allowed.getItemMeta();
        meta.setDisplayName("Unrelated name");
        allowed.setItemMeta(meta);
        check(recipe.matchesItemSample(0, allowed),
                "Firework-rocket selector rejected unrelated metadata");
        meta = (FireworkMeta) allowed.getItemMeta();
        meta.setPower(3);
        allowed.setItemMeta(meta);
        check(!recipe.matchesItemSample(0, allowed),
                "Firework-rocket selector ignored flight power");
        allowed = withExtra(recipe.ingredients().get(0));
        meta = (FireworkMeta) allowed.getItemMeta();
        meta.clearEffects();
        meta.addEffect(firework(FireworkEffect.Type.BALL, Color.RED));
        allowed.setItemMeta(meta);
        check(!recipe.matchesItemSample(0, allowed),
                "Firework-rocket selector ignored explosion effect");
    }

    private static void verifyFireworkStar(GridRecipeDefinition recipe) {
        check(recipe.matchModes().get(0) == IngredientMatchMode.FIREWORK_ONLY,
                "Firework-star selector was not persisted");
        ItemStack allowed = withExtra(recipe.ingredients().get(0));
        FireworkEffectMeta meta = (FireworkEffectMeta) allowed.getItemMeta();
        meta.setDisplayName("Unrelated name");
        allowed.setItemMeta(meta);
        check(recipe.matchesItemSample(0, allowed),
                "Firework-star selector rejected unrelated metadata");
        meta = (FireworkEffectMeta) allowed.getItemMeta();
        meta.setEffect(firework(FireworkEffect.Type.BALL, Color.RED));
        allowed.setItemMeta(meta);
        check(!recipe.matchesItemSample(0, allowed),
                "Firework-star selector ignored changed explosion effect");
        meta = (FireworkEffectMeta) allowed.getItemMeta();
        meta.setEffect(null);
        allowed.setItemMeta(meta);
        check(!recipe.matchesItemSample(0, allowed),
                "Firework-star selector ignored missing explosion effect");
    }

    private static void verifyBundleContents(GridRecipeDefinition recipe) {
        check(recipe.matchModes().get(0) == IngredientMatchMode.BUNDLE_CONTENTS_ONLY,
                "Bundle-contents selector was not persisted");
        ItemStack allowed = withExtra(recipe.ingredients().get(0));
        BundleMeta meta = (BundleMeta) allowed.getItemMeta();
        meta.setDisplayName("Unrelated bundle name");
        allowed.setItemMeta(meta);
        check(recipe.matchesItemSample(0, allowed),
                "Bundle-contents selector rejected unrelated metadata");
        meta = (BundleMeta) allowed.getItemMeta();
        meta.setItems(List.of(namedArrow("Changed arrow"),
                new ItemStack(Material.DIAMOND, 2)));
        allowed.setItemMeta(meta);
        check(!recipe.matchesItemSample(0, allowed),
                "Bundle-contents selector ignored nested item data");
        meta = (BundleMeta) allowed.getItemMeta();
        meta.setItems(List.of(new ItemStack(Material.DIAMOND, 2),
                namedArrow("Selected arrow")));
        allowed.setItemMeta(meta);
        check(!recipe.matchesItemSample(0, allowed),
                "Bundle-contents selector ignored changed nested order");
        meta = (BundleMeta) allowed.getItemMeta();
        meta.setItems(List.of(namedArrow("Selected arrow"),
                new ItemStack(Material.DIAMOND, 1)));
        allowed.setItemMeta(meta);
        check(!recipe.matchesItemSample(0, allowed),
                "Bundle-contents selector ignored a changed nested amount");
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

    private static ArmorTrim armorTrim(String material, String pattern) {
        TrimMaterial trimMaterial = Registry.TRIM_MATERIAL.get(NamespacedKey.minecraft(material));
        TrimPattern trimPattern = Registry.TRIM_PATTERN.get(NamespacedKey.minecraft(pattern));
        check(trimMaterial != null && trimPattern != null,
                "Armor trim registry fixture is unavailable");
        return new ArmorTrim(trimMaterial, trimPattern);
    }

    private static Attribute attackDamage() {
        Attribute attack = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic.attack_damage"));
        if (attack == null) attack = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("attack_damage"));
        check(attack != null, "Attack damage attribute is unavailable");
        return attack;
    }

    private static void setAttackModifier(ItemStack item, double amount) {
        Attribute attack = attackDamage();
        ItemMeta meta = item.getItemMeta();
        meta.removeAttributeModifier(attack);
        AttributeModifier modifier = new AttributeModifier(UUID.nameUUIDFromBytes(
                "recipeweaver:smoke_field_attack".getBytes(StandardCharsets.UTF_8)),
                "RecipeWeaverSmoke", amount, AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.getByName("mainhand"));
        check(meta.addAttributeModifier(attack, modifier),
                "Could not set attack attribute for field matching");
        check(item.setItemMeta(meta), "Could not store attack attribute for field matching");
    }

    private static GridRecipeDefinition recipe(NamespacedKey key, ItemStack sample,
                                               IngredientMatchMode mode) {
        return new GridRecipeDefinition(key, GridTier.ELITE, RecipeKind.SHAPED, 2,
                Arrays.asList(sample, null, null, null), new ItemStack(Material.DIAMOND),
                "smoke", true, false,
                List.of(mode, IngredientMatchMode.MATERIAL,
                        IngredientMatchMode.MATERIAL, IngredientMatchMode.MATERIAL), 0, true);
    }

    private static ItemStack withExtra(ItemStack source) {
        ItemStack item = source.clone();
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(EXTRA, PersistentDataType.INTEGER, 7);
        item.setItemMeta(meta);
        return item;
    }

    private static boolean hasItemModel() {
        try { ItemMeta.class.getMethod("setItemModel", NamespacedKey.class); return true; }
        catch (NoSuchMethodException ignored) { return false; }
    }

    private static void setItemModel(ItemMeta meta, NamespacedKey key) {
        try {
            ItemMeta.class.getMethod("setItemModel", NamespacedKey.class).invoke(meta, key);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException ex) {
            throw new IllegalStateException("Could not set modern item model", ex);
        }
    }

    private static ItemStack paper() { return new ItemStack(Material.PAPER); }

    private static NamespacedKey key(String value) { return new NamespacedKey("recipeweaver", value); }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
