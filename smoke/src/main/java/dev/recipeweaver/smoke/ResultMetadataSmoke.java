package dev.recipeweaver.smoke;

import dev.recipeweaver.api.MapLocationLabel;
import dev.recipeweaver.api.ResultMetadataMode;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.FireworkEffect;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.MusicInstrument;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.block.BlockFace;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Axolotl;
import org.bukkit.entity.TropicalFish;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.AxolotlBucketMeta;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.BlockDataMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.FireworkEffectMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.KnowledgeBookMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.inventory.meta.MusicInstrumentMeta;
import org.bukkit.inventory.meta.OminousBottleMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.SuspiciousStewMeta;
import org.bukkit.inventory.meta.TropicalFishBucketMeta;
import org.bukkit.inventory.meta.WritableBookMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.persistence.PersistentDataContainer;

/** Checks cross-material result metadata that can be inherited by compatible items. */
final class ResultMetadataSmoke {
    private ResultMetadataSmoke() { }

    static void verifyArmorTrim() {
        TrimMaterial material = Registry.TRIM_MATERIAL.get(NamespacedKey.minecraft("iron"));
        TrimPattern pattern = Registry.TRIM_PATTERN.get(NamespacedKey.minecraft("sentry"));
        check(material != null && pattern != null, "Armor trim registry fixture is unavailable");
        ItemStack source = new ItemStack(Material.DIAMOND_CHESTPLATE);
        ArmorMeta sourceMeta = (ArmorMeta) source.getItemMeta();
        sourceMeta.setTrim(new ArmorTrim(material, pattern));
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.NETHERITE_CHESTPLATE, 2);

        ItemStack copied = copy(source, template);
        check(copied.getType() == Material.NETHERITE_CHESTPLATE && copied.getAmount() == 2,
                "Armor trim copy replaced the configured result");
        ArmorMeta copiedMeta = (ArmorMeta) copied.getItemMeta();
        check(copiedMeta.hasTrim() && copiedMeta.getTrim().getMaterial() == material
                        && copiedMeta.getTrim().getPattern() == pattern,
                "Armor trim was not inherited from the matched ingredient");
        check(!((ArmorMeta) template.getItemMeta()).hasTrim(),
                "Armor trim copy mutated the result template");

        ArmorMeta templateMeta = (ArmorMeta) template.getItemMeta();
        templateMeta.setTrim(new ArmorTrim(material, pattern));
        template.setItemMeta(templateMeta);
        ItemStack cleared = copy(new ItemStack(Material.DIAMOND_CHESTPLATE), template);
        check(!((ArmorMeta) cleared.getItemMeta()).hasTrim(),
                "An untrimmed source retained an unrelated template trim");
        check(((ArmorMeta) template.getItemMeta()).hasTrim(),
                "Clearing the copied trim changed the result template");
    }

    static void verifyArmorTrimOnlyCopy() {
        TrimMaterial iron = Registry.TRIM_MATERIAL.get(NamespacedKey.minecraft("iron"));
        TrimMaterial gold = Registry.TRIM_MATERIAL.get(NamespacedKey.minecraft("gold"));
        TrimPattern sentry = Registry.TRIM_PATTERN.get(NamespacedKey.minecraft("sentry"));
        TrimPattern coast = Registry.TRIM_PATTERN.get(NamespacedKey.minecraft("coast"));
        check(iron != null && gold != null && sentry != null && coast != null,
                "Armor trim registry fixture is unavailable");
        ItemStack source = new ItemStack(Material.DIAMOND_CHESTPLATE);
        ArmorMeta sourceMeta = (ArmorMeta) source.getItemMeta();
        sourceMeta.setTrim(new ArmorTrim(iron, sentry));
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.NETHERITE_CHESTPLATE);
        ArmorMeta templateMeta = (ArmorMeta) template.getItemMeta();
        templateMeta.setTrim(new ArmorTrim(gold, coast));
        templateMeta.setDisplayName("Output name");
        template.setItemMeta(templateMeta);

        ItemStack copied = copy(source, template, ResultMetadataMode.ARMOR_TRIM_ONLY);
        ArmorMeta copiedMeta = (ArmorMeta) copied.getItemMeta();
        check(copiedMeta.hasTrim() && copiedMeta.getTrim().getMaterial() == iron
                        && copiedMeta.getTrim().getPattern() == sentry
                        && "Output name".equals(copiedMeta.getDisplayName())
                        && ((ArmorMeta) template.getItemMeta()).getTrim().getMaterial() == gold,
                "Armor-trim-only copy changed unrelated output data or mutated the template");

        ItemStack cleared = copy(new ItemStack(Material.DIAMOND_CHESTPLATE), template,
                ResultMetadataMode.ARMOR_TRIM_ONLY);
        check(!((ArmorMeta) cleared.getItemMeta()).hasTrim()
                        && "Output name".equals(cleared.getItemMeta().getDisplayName()),
                "Armor-trim-only copy retained a stale trim");
        ItemStack nonArmor = copy(new ItemStack(Material.PAPER), template,
                ResultMetadataMode.ARMOR_TRIM_ONLY);
        check(!((ArmorMeta) nonArmor.getItemMeta()).hasTrim(),
                "Armor-trim-only copy retained a trim from a non-armor source");
    }

    static void verifyPotionOnlyCopy() {
        ItemStack source = new ItemStack(Material.POTION);
        PotionMeta sourceMeta = (PotionMeta) source.getItemMeta();
        sourceMeta.setBasePotionType(PotionType.HEALING);
        sourceMeta.setColor(Color.fromRGB(0x225577));
        sourceMeta.addCustomEffect(new PotionEffect(PotionEffectType.SPEED, 200, 1), true);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.SPLASH_POTION);
        PotionMeta templateMeta = (PotionMeta) template.getItemMeta();
        templateMeta.setBasePotionType(PotionType.WEAKNESS);
        templateMeta.setColor(Color.fromRGB(0x123456));
        templateMeta.addCustomEffect(new PotionEffect(PotionEffectType.SPEED, 50, 0), true);
        templateMeta.setDisplayName("Output name");
        template.setItemMeta(templateMeta);

        ItemStack copied = copy(source, template, ResultMetadataMode.POTION_ONLY);
        PotionMeta copiedMeta = (PotionMeta) copied.getItemMeta();
        check(copied.getType() == Material.SPLASH_POTION
                        && copiedMeta.getBasePotionType() == PotionType.HEALING
                        && Color.fromRGB(0x225577).equals(copiedMeta.getColor())
                        && copiedMeta.getCustomEffects().equals(sourceMeta.getCustomEffects())
                        && "Output name".equals(copiedMeta.getDisplayName())
                        && ((PotionMeta) template.getItemMeta()).getBasePotionType()
                                == PotionType.WEAKNESS,
                "Potion-only copy lost selected fields, changed output material/name, or mutated template");

        ItemStack cleared = copy(new ItemStack(Material.PAPER), template,
                ResultMetadataMode.POTION_ONLY);
        PotionMeta clearedMeta = (PotionMeta) cleared.getItemMeta();
        check(clearedMeta.getBasePotionType() == PotionType.WATER
                        && !clearedMeta.hasColor() && clearedMeta.getCustomEffects().isEmpty()
                        && "Output name".equals(clearedMeta.getDisplayName()),
                "Potion-only copy retained stale fields from an incompatible source");
    }

    static void verifyStoredEnchantmentsOnlyCopy() {
        var unbreaking = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));
        var efficiency = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("efficiency"));
        check(unbreaking != null && efficiency != null, "Stored enchantment fixture is unavailable");
        ItemStack source = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta sourceMeta = (EnchantmentStorageMeta) source.getItemMeta();
        sourceMeta.addStoredEnchant(unbreaking, 3, true);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta templateMeta = (EnchantmentStorageMeta) template.getItemMeta();
        templateMeta.addStoredEnchant(efficiency, 2, true);
        templateMeta.setDisplayName("Output name");
        template.setItemMeta(templateMeta);

        ItemStack copied = copy(source, template, ResultMetadataMode.STORED_ENCHANTMENTS_ONLY);
        EnchantmentStorageMeta copiedMeta = (EnchantmentStorageMeta) copied.getItemMeta();
        check(copiedMeta.getStoredEnchants().equals(java.util.Map.of(unbreaking, 3))
                        && "Output name".equals(copiedMeta.getDisplayName())
                        && ((EnchantmentStorageMeta) template.getItemMeta()).getStoredEnchants()
                                .equals(java.util.Map.of(efficiency, 2)),
                "Stored-enchantments-only copy kept stale enchants or changed output/template");

        ItemStack cleared = copy(new ItemStack(Material.PAPER), template,
                ResultMetadataMode.STORED_ENCHANTMENTS_ONLY);
        check(!((EnchantmentStorageMeta) cleared.getItemMeta()).hasStoredEnchants()
                        && "Output name".equals(cleared.getItemMeta().getDisplayName()),
                "Stored-enchantments-only copy retained stale book enchants");
    }

    static void verifyBannerPatternsOnlyCopy() {
        ItemStack source = new ItemStack(Material.WHITE_BANNER);
        BannerMeta sourceMeta = (BannerMeta) source.getItemMeta();
        sourceMeta.addPattern(new Pattern(DyeColor.BLUE, PatternType.CIRCLE));
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.RED_BANNER);
        BannerMeta templateMeta = (BannerMeta) template.getItemMeta();
        templateMeta.addPattern(new Pattern(DyeColor.RED, PatternType.CIRCLE));
        templateMeta.setDisplayName("Output name");
        template.setItemMeta(templateMeta);

        ItemStack copied = copy(source, template, ResultMetadataMode.BANNER_PATTERNS_ONLY);
        BannerMeta copiedMeta = (BannerMeta) copied.getItemMeta();
        check(copied.getType() == Material.RED_BANNER && copiedMeta.numberOfPatterns() == 1
                        && copiedMeta.getPattern(0).getColor() == DyeColor.BLUE
                        && copiedMeta.getPattern(0).getPattern() == PatternType.CIRCLE
                        && "Output name".equals(copiedMeta.getDisplayName())
                        && ((BannerMeta) template.getItemMeta()).getPattern(0).getColor()
                                == DyeColor.RED,
                "Banner-patterns-only copy lost patterns or changed output/template");

        ItemStack cleared = copy(new ItemStack(Material.PAPER), template,
                ResultMetadataMode.BANNER_PATTERNS_ONLY);
        check(((BannerMeta) cleared.getItemMeta()).getPatterns().isEmpty()
                        && "Output name".equals(cleared.getItemMeta().getDisplayName()),
                "Banner-patterns-only copy retained stale patterns");
    }

    static void verifyCrossbowProjectilesOnlyCopy() {
        ItemStack arrow = new ItemStack(Material.ARROW);
        ItemMeta arrowMeta = arrow.getItemMeta();
        arrowMeta.setDisplayName("Selected arrow");
        arrow.setItemMeta(arrowMeta);
        ItemStack source = new ItemStack(Material.CROSSBOW);
        CrossbowMeta sourceMeta = (CrossbowMeta) source.getItemMeta();
        sourceMeta.setChargedProjectiles(java.util.List.of(arrow));
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.CROSSBOW);
        CrossbowMeta templateMeta = (CrossbowMeta) template.getItemMeta();
        templateMeta.setChargedProjectiles(java.util.List.of(new ItemStack(Material.FIREWORK_ROCKET)));
        templateMeta.setDisplayName("Output name");
        template.setItemMeta(templateMeta);

        ItemStack copied = copy(source, template, ResultMetadataMode.CROSSBOW_PROJECTILES_ONLY);
        CrossbowMeta copiedMeta = (CrossbowMeta) copied.getItemMeta();
        check(copiedMeta.getChargedProjectiles().equals(java.util.List.of(arrow))
                        && "Output name".equals(copiedMeta.getDisplayName())
                        && ((CrossbowMeta) template.getItemMeta()).getChargedProjectiles().get(0)
                                .getType() == Material.FIREWORK_ROCKET,
                "Crossbow-only copy lost nested projectile data or changed output/template");
        sourceMeta = (CrossbowMeta) source.getItemMeta();
        sourceMeta.setChargedProjectiles(java.util.List.of(new ItemStack(Material.ARROW)));
        source.setItemMeta(sourceMeta);
        check(((CrossbowMeta) copied.getItemMeta()).getChargedProjectiles()
                        .equals(java.util.List.of(arrow)),
                "Crossbow-only result changed after its ingredient was modified");

        ItemStack cleared = copy(new ItemStack(Material.PAPER), template,
                ResultMetadataMode.CROSSBOW_PROJECTILES_ONLY);
        check(((CrossbowMeta) cleared.getItemMeta()).getChargedProjectiles().isEmpty()
                        && "Output name".equals(cleared.getItemMeta().getDisplayName()),
                "Crossbow-only copy retained a stale charged projectile");
    }

    static void verifyFireworkRocketOnlyCopy() {
        FireworkEffect selected = FireworkEffect.builder().with(FireworkEffect.Type.STAR)
                .withColor(Color.BLUE).build();
        ItemStack source = new ItemStack(Material.FIREWORK_ROCKET);
        FireworkMeta sourceMeta = (FireworkMeta) source.getItemMeta();
        sourceMeta.setPower(2);
        sourceMeta.addEffect(selected);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.FIREWORK_ROCKET);
        FireworkMeta templateMeta = (FireworkMeta) template.getItemMeta();
        templateMeta.setPower(1);
        templateMeta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL)
                .withColor(Color.RED).build());
        templateMeta.setDisplayName("Output name");
        template.setItemMeta(templateMeta);

        ItemStack copied = copy(source, template, ResultMetadataMode.FIREWORK_ONLY);
        FireworkMeta copiedMeta = (FireworkMeta) copied.getItemMeta();
        check(copiedMeta.getPower() == 2 && copiedMeta.getEffects().equals(java.util.List.of(selected))
                        && "Output name".equals(copiedMeta.getDisplayName())
                        && ((FireworkMeta) template.getItemMeta()).getPower() == 1,
                "Firework-only rocket copy lost power/effects or changed output/template");

        ItemStack cleared = copy(new ItemStack(Material.PAPER), template,
                ResultMetadataMode.FIREWORK_ONLY);
        FireworkMeta clearedMeta = (FireworkMeta) cleared.getItemMeta();
        check(clearedMeta.getPower() == 0 && clearedMeta.getEffects().isEmpty()
                        && "Output name".equals(clearedMeta.getDisplayName()),
                "Firework-only rocket copy retained stale power or effects");
    }

    static void verifyFireworkStarOnlyCopy() {
        FireworkEffect selected = FireworkEffect.builder().with(FireworkEffect.Type.CREEPER)
                .withColor(Color.BLUE).build();
        ItemStack source = new ItemStack(Material.FIREWORK_STAR);
        FireworkEffectMeta sourceMeta = (FireworkEffectMeta) source.getItemMeta();
        sourceMeta.setEffect(selected);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.FIREWORK_STAR);
        FireworkEffectMeta templateMeta = (FireworkEffectMeta) template.getItemMeta();
        templateMeta.setEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL)
                .withColor(Color.RED).build());
        templateMeta.setDisplayName("Output name");
        template.setItemMeta(templateMeta);

        ItemStack copied = copy(source, template, ResultMetadataMode.FIREWORK_ONLY);
        FireworkEffectMeta copiedMeta = (FireworkEffectMeta) copied.getItemMeta();
        check(selected.equals(copiedMeta.getEffect())
                        && "Output name".equals(copiedMeta.getDisplayName())
                        && ((FireworkEffectMeta) template.getItemMeta()).getEffect()
                                .equals(templateMeta.getEffect()),
                "Firework-only star copy lost effect or changed output/template");

        ItemStack cleared = copy(new ItemStack(Material.PAPER), template,
                ResultMetadataMode.FIREWORK_ONLY);
        check(!((FireworkEffectMeta) cleared.getItemMeta()).hasEffect()
                        && "Output name".equals(cleared.getItemMeta().getDisplayName()),
                "Firework-only star copy retained a stale effect");
    }

    static void verifyBundleContentsOnlyCopy() {
        ItemStack arrow = new ItemStack(Material.ARROW);
        ItemMeta arrowMeta = arrow.getItemMeta();
        arrowMeta.setDisplayName("Selected arrow");
        arrow.setItemMeta(arrowMeta);
        ItemStack source = new ItemStack(Material.BUNDLE);
        BundleMeta sourceMeta = (BundleMeta) source.getItemMeta();
        sourceMeta.setItems(java.util.List.of(arrow, new ItemStack(Material.DIAMOND, 2)));
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.BUNDLE);
        BundleMeta templateMeta = (BundleMeta) template.getItemMeta();
        templateMeta.setItems(java.util.List.of(new ItemStack(Material.DIRT, 3)));
        templateMeta.setDisplayName("Output name");
        template.setItemMeta(templateMeta);

        ItemStack copied = copy(source, template, ResultMetadataMode.BUNDLE_CONTENTS_ONLY);
        BundleMeta copiedMeta = (BundleMeta) copied.getItemMeta();
        check(copiedMeta.getItems().equals(java.util.List.of(arrow,
                            new ItemStack(Material.DIAMOND, 2)))
                        && "Output name".equals(copiedMeta.getDisplayName())
                        && ((BundleMeta) template.getItemMeta()).getItems()
                                .equals(java.util.List.of(new ItemStack(Material.DIRT, 3))),
                "Bundle-only copy lost nested contents or changed output/template");
        sourceMeta = (BundleMeta) source.getItemMeta();
        sourceMeta.setItems(java.util.List.of(new ItemStack(Material.PAPER)));
        source.setItemMeta(sourceMeta);
        check(((BundleMeta) copied.getItemMeta()).getItems()
                        .equals(java.util.List.of(arrow, new ItemStack(Material.DIAMOND, 2))),
                "Bundle-only result changed after its ingredient was modified");

        ItemStack emptyBundle = copy(new ItemStack(Material.BUNDLE), template,
                ResultMetadataMode.BUNDLE_CONTENTS_ONLY);
        check(((BundleMeta) emptyBundle.getItemMeta()).getItems().isEmpty(),
                "Bundle-only copy retained stale contents from an empty bundle source");
        ItemStack cleared = copy(new ItemStack(Material.PAPER), template,
                ResultMetadataMode.BUNDLE_CONTENTS_ONLY);
        check(((BundleMeta) cleared.getItemMeta()).getItems().isEmpty()
                        && "Output name".equals(cleared.getItemMeta().getDisplayName()),
                "Bundle-only copy retained stale nested contents");
    }

    static void verifyMaxDurability() {
        ItemStack source = new ItemStack(Material.DIAMOND_SWORD);
        Damageable sourceMeta = (Damageable) source.getItemMeta();
        sourceMeta.setMaxDamage(500);
        sourceMeta.setDamage(300);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.IRON_SWORD);
        ItemStack copied = copy(source, template);
        Damageable result = (Damageable) copied.getItemMeta();
        check(result.hasMaxDamage() && result.getMaxDamage() == 500
                        && result.getDamage() == 300,
                "Custom maximum durability and damage were not inherited together");
        check(!((Damageable) template.getItemMeta()).hasMaxDamage(),
                "Durability copy mutated the result template");

        sourceMeta.setMaxDamage(null);
        sourceMeta.setDamage(300);
        source.setItemMeta(sourceMeta);
        ItemStack incompatible = copy(source, template);
        Damageable safe = (Damageable) incompatible.getItemMeta();
        check(!safe.hasMaxDamage() && safe.getDamage() == 0,
                "Cross-material damage exceeded the result's default durability");
    }

    static void verifyCompatibleItemComponents() {
        ItemStack potion = new ItemStack(Material.POTION);
        PotionMeta potionMeta = (PotionMeta) potion.getItemMeta();
        potionMeta.setBasePotionType(PotionType.HEALING);
        potionMeta.setColor(Color.fromRGB(0x225577));
        potionMeta.addCustomEffect(new PotionEffect(PotionEffectType.SPEED, 200, 1), true);
        potion.setItemMeta(potionMeta);
        ItemStack splash = copy(potion, new ItemStack(Material.SPLASH_POTION));
        PotionMeta splashMeta = (PotionMeta) splash.getItemMeta();
        check(splash.getType() == Material.SPLASH_POTION
                        && splashMeta.getBasePotionType() == PotionType.HEALING
                        && Color.fromRGB(0x225577).equals(splashMeta.getColor())
                        && splashMeta.getCustomEffects().size() == 1
                        && splashMeta.getCustomEffects().get(0).getType() == PotionEffectType.SPEED
                        && splashMeta.getCustomEffects().get(0).getDuration() == 200
                        && splashMeta.getCustomEffects().get(0).getAmplifier() == 1,
                "Potion result lost base, color, or custom effect");

        ItemStack leather = new ItemStack(Material.LEATHER_CHESTPLATE);
        LeatherArmorMeta leatherMeta = (LeatherArmorMeta) leather.getItemMeta();
        leatherMeta.setColor(Color.fromRGB(0x113355));
        leather.setItemMeta(leatherMeta);
        ItemStack helmet = copy(leather, new ItemStack(Material.LEATHER_HELMET));
        check(helmet.getType() == Material.LEATHER_HELMET
                        && ((LeatherArmorMeta) helmet.getItemMeta()).getColor()
                                .equals(Color.fromRGB(0x113355)),
                "Leather armor result lost its color");

        ItemStack banner = new ItemStack(Material.WHITE_BANNER);
        BannerMeta bannerMeta = (BannerMeta) banner.getItemMeta();
        bannerMeta.addPattern(new Pattern(DyeColor.BLUE, PatternType.CIRCLE));
        banner.setItemMeta(bannerMeta);
        ItemStack coloredBanner = copy(banner, new ItemStack(Material.RED_BANNER));
        BannerMeta coloredMeta = (BannerMeta) coloredBanner.getItemMeta();
        check(coloredBanner.getType() == Material.RED_BANNER
                        && coloredMeta.numberOfPatterns() == 1
                        && coloredMeta.getPattern(0).getColor() == DyeColor.BLUE
                        && coloredMeta.getPattern(0).getPattern() == PatternType.CIRCLE,
                "Banner result lost its pattern");

        ItemStack tool = new ItemStack(Material.STICK);
        ItemMeta toolMeta = tool.getItemMeta();
        var component = toolMeta.getTool();
        component.setDefaultMiningSpeed(4.0F);
        component.setDamagePerBlock(1);
        component.addRule(Material.STONE, 8.0F, true);
        toolMeta.setTool(component);
        tool.setItemMeta(toolMeta);
        ItemStack pickaxe = copy(tool, new ItemStack(Material.WOODEN_PICKAXE));
        var copiedTool = pickaxe.getItemMeta().getTool();
        check(pickaxe.getItemMeta().hasTool()
                        && copiedTool.getDefaultMiningSpeed() == 4.0F
                        && copiedTool.getDamagePerBlock() == 1
                        && copiedTool.getRules().size() == 1
                        && copiedTool.getRules().get(0).getBlocks().contains(Material.STONE)
                        && copiedTool.getRules().get(0).getSpeed() == 8.0F,
                "Tool result lost its mining component");
    }

    static boolean verifyModernItemComponents() {
        ItemStack named = new ItemStack(Material.PAPER);
        ItemMeta namedMeta = named.getItemMeta();
        namedMeta.setDisplayName("Source display");
        namedMeta.setItemName("Source item name");
        named.setItemMeta(namedMeta);
        ItemStack namedTemplate = new ItemStack(Material.DIAMOND);
        ItemMeta templateMeta = namedTemplate.getItemMeta();
        templateMeta.setItemName("Template item name");
        namedTemplate.setItemMeta(templateMeta);
        ItemStack nameCopied = copy(named, namedTemplate);
        check("Source display".equals(nameCopied.getItemMeta().getDisplayName())
                        && "Source item name".equals(nameCopied.getItemMeta().getItemName()),
                "Display and non-italic item names were not inherited together");
        check(!copy(new ItemStack(Material.PAPER), namedTemplate).getItemMeta().hasItemName(),
                "Missing source item name retained the result template's name");
        check("Template item name".equals(namedTemplate.getItemMeta().getItemName()),
                "Name copy mutated the result template");

        ItemStack typedSource = new ItemStack(Material.PAPER);
        ItemMeta typedMeta = typedSource.getItemMeta();
        NamespacedKey listKey = NamespacedKey.fromString("recipeweaver:copied_list");
        typedMeta.getPersistentDataContainer().set(listKey,
                PersistentDataType.LIST.strings(), java.util.List.of("alpha", "beta"));
        typedSource.setItemMeta(typedMeta);
        ItemStack typedTemplate = new ItemStack(Material.DIAMOND);
        ItemMeta typedTemplateMeta = typedTemplate.getItemMeta();
        typedTemplateMeta.getPersistentDataContainer().set(listKey,
                PersistentDataType.LIST.strings(), java.util.List.of("old"));
        typedTemplate.setItemMeta(typedTemplateMeta);
        ItemStack typedCopy = copy(typedSource, typedTemplate);
        check(java.util.List.of("alpha", "beta").equals(typedCopy.getItemMeta()
                        .getPersistentDataContainer().get(listKey, PersistentDataType.LIST.strings())),
                "Typed custom-data list was not inherited");
        check(java.util.List.of("old").equals(typedTemplate.getItemMeta()
                        .getPersistentDataContainer().get(listKey, PersistentDataType.LIST.strings())),
                "Typed custom-data copy mutated the result template");

        if (!supports("setItemModel", NamespacedKey.class)
                || !supports("setTooltipStyle", NamespacedKey.class)
                || !supports("setEnchantable", Integer.class)) return false;
        ItemStack source = new ItemStack(Material.PAPER);
        ItemStack template = new ItemStack(Material.DIAMOND);
        NamespacedKey model = NamespacedKey.fromString("recipeweaver:copied_model");
        NamespacedKey style = NamespacedKey.fromString("recipeweaver:copied_style");
        setComponent(source, "ItemModel", NamespacedKey.class, model);
        setComponent(source, "TooltipStyle", NamespacedKey.class, style);
        setComponent(source, "Enchantable", Integer.class, 27);
        setComponent(template, "ItemModel", NamespacedKey.class,
                NamespacedKey.fromString("recipeweaver:template_model"));
        setComponent(template, "TooltipStyle", NamespacedKey.class,
                NamespacedKey.fromString("recipeweaver:template_style"));
        setComponent(template, "Enchantable", Integer.class, 4);

        ItemStack copied = copy(source, template);
        check(model.equals(component(copied, "ItemModel")), "Item model was not inherited");
        check(style.equals(component(copied, "TooltipStyle")), "Tooltip style was not inherited");
        check(Integer.valueOf(27).equals(component(copied, "Enchantable")),
                "Enchantability was not inherited");
        check(Integer.valueOf(4).equals(component(template, "Enchantable")),
                "Modern component copy mutated the result template");

        ItemStack cleared = copy(new ItemStack(Material.PAPER), template);
        check(component(cleared, "ItemModel") == null,
                "Missing source item model retained the result template's value");
        check(component(cleared, "TooltipStyle") == null,
                "Missing source tooltip style retained the result template's value");
        check(component(cleared, "Enchantable") == null,
                "Missing source enchantability retained the result template's value");
        return true;
    }

    static boolean verifyRichCustomModelDataCopy() {
        ItemStack legacySource = new ItemStack(Material.PAPER);
        ItemMeta legacySourceMeta = legacySource.getItemMeta();
        legacySourceMeta.setCustomModelData(42);
        legacySource.setItemMeta(legacySourceMeta);
        ItemStack legacyTemplate = new ItemStack(Material.DIAMOND);
        ItemMeta legacyTemplateMeta = legacyTemplate.getItemMeta();
        legacyTemplateMeta.setCustomModelData(9);
        legacyTemplate.setItemMeta(legacyTemplateMeta);
        check(copy(legacySource, legacyTemplate).getItemMeta().getCustomModelData() == 42,
                "Legacy custom model data was not copied");
        check(!copy(new ItemStack(Material.PAPER), legacyTemplate).getItemMeta()
                        .hasCustomModelData(),
                "Missing source model data retained the result template's value");
        check(legacyTemplate.getItemMeta().getCustomModelData() == 9,
                "Custom model data copy mutated the result template");

        Method get;
        try {
            get = ItemMeta.class.getMethod("getCustomModelDataComponent");
        } catch (NoSuchMethodException ignored) {
            return false;
        }
        try {
            Class<?> type = get.getReturnType();
            var set = ItemMeta.class.getMethod("setCustomModelDataComponent", type);
            var has = ItemMeta.class.getMethod("hasCustomModelDataComponent");
            ItemStack source = new ItemStack(Material.PAPER);
            ItemMeta sourceMeta = source.getItemMeta();
            Object model = get.invoke(sourceMeta);
            type.getMethod("setFloats", java.util.List.class).invoke(model,
                    java.util.List.of(1.5F, 2.5F));
            type.getMethod("setFlags", java.util.List.class).invoke(model,
                    java.util.List.of(true, false));
            type.getMethod("setStrings", java.util.List.class).invoke(model,
                    java.util.List.of("first", "second"));
            type.getMethod("setColors", java.util.List.class).invoke(model,
                    java.util.List.of(Color.fromRGB(0x24A0ED)));
            set.invoke(sourceMeta, model);
            source.setItemMeta(sourceMeta);

            ItemStack template = new ItemStack(Material.DIAMOND);
            ItemMeta templateMeta = template.getItemMeta();
            Object old = get.invoke(templateMeta);
            type.getMethod("setStrings", java.util.List.class).invoke(old,
                    java.util.List.of("old"));
            set.invoke(templateMeta, old);
            template.setItemMeta(templateMeta);

            ItemStack copied = copy(source, template);
            Object inherited = get.invoke(copied.getItemMeta());
            check(java.util.List.of(1.5F, 2.5F).equals(type.getMethod("getFloats").invoke(inherited))
                            && java.util.List.of(true, false).equals(type.getMethod("getFlags")
                                    .invoke(inherited))
                            && java.util.List.of("first", "second").equals(type.getMethod("getStrings")
                                    .invoke(inherited))
                            && java.util.List.of(Color.fromRGB(0x24A0ED)).equals(
                                    type.getMethod("getColors").invoke(inherited)),
                    "Rich custom model data lost floats, flags, strings, or colors");
            check(java.util.List.of("old").equals(type.getMethod("getStrings")
                            .invoke(get.invoke(template.getItemMeta()))),
                    "Rich model data copy mutated the result template");
            check(!Boolean.TRUE.equals(has.invoke(copy(new ItemStack(Material.PAPER), template)
                            .getItemMeta())),
                    "Missing source rich model data retained template values");
            return true;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not verify rich model data result copy", ex);
        }
    }

    static boolean verifyUseRemainderCopy() {
        if (!supports("setUseRemainder", ItemStack.class)) return false;
        ItemStack remainder = new ItemStack(Material.BUCKET);
        ItemMeta remainderMeta = remainder.getItemMeta();
        NamespacedKey rank = NamespacedKey.fromString("recipeweaver:remainder_rank");
        remainderMeta.getPersistentDataContainer().set(rank, PersistentDataType.INTEGER, 7);
        remainder.setItemMeta(remainderMeta);
        ItemStack source = new ItemStack(Material.PAPER);
        setComponent(source, "UseRemainder", ItemStack.class, remainder);
        ItemStack template = new ItemStack(Material.DIAMOND);
        setComponent(template, "UseRemainder", ItemStack.class, new ItemStack(Material.STICK));
        ItemStack copied = copy(source, template);
        ItemStack inherited = (ItemStack) component(copied, "UseRemainder");
        check(inherited != null && inherited.getType() == Material.BUCKET
                        && Integer.valueOf(7).equals(inherited.getItemMeta()
                                .getPersistentDataContainer().get(rank, PersistentDataType.INTEGER)),
                "Use remainder or its nested custom data was not copied across materials");
        inherited.setAmount(2);
        check(((ItemStack) component(source, "UseRemainder")).getAmount() == 1,
                "Copied use remainder shares a mutable item with the source");
        check(((ItemStack) component(template, "UseRemainder")).getType() == Material.STICK,
                "Use remainder copy changed the configured result template");
        check(component(copy(new ItemStack(Material.PAPER), template), "UseRemainder") == null,
                "Missing source use remainder retained the template's remainder");
        return true;
    }

    static boolean verifyUseCooldownCopy() {
        if (componentApiType("UseCooldown") == null) return false;
        ItemStack source = new ItemStack(Material.PAPER);
        setMutableComponent(source, "UseCooldown", "setCooldownSeconds", float.class, 3.5F);
        NamespacedKey group = NamespacedKey.fromString("recipeweaver:shared_cooldown");
        setMutableComponent(source, "UseCooldown", "setCooldownGroup", NamespacedKey.class, group);
        ItemStack template = new ItemStack(Material.DIAMOND);
        setMutableComponent(template, "UseCooldown", "setCooldownSeconds", float.class, 1.0F);
        ItemStack copied = copy(source, template);
        check(Float.valueOf(3.5F).equals(componentValue(copied, "UseCooldown", "getCooldownSeconds"))
                        && group.equals(componentValue(copied, "UseCooldown", "getCooldownGroup")),
                "Use cooldown seconds or group was not copied across materials");
        check(Float.valueOf(1.0F).equals(componentValue(template, "UseCooldown",
                        "getCooldownSeconds")),
                "Use cooldown copy changed the configured result template");
        check(component(copy(new ItemStack(Material.PAPER), template), "UseCooldown") == null,
                "Missing source use cooldown retained the template's cooldown");
        return true;
    }

    static boolean verifyEquippableCopy() {
        if (componentApiType("Equippable") == null) return false;
        ItemStack source = new ItemStack(Material.PAPER);
        setMutableComponent(source, "Equippable", "setSlot", EquipmentSlot.class,
                EquipmentSlot.HEAD);
        NamespacedKey model = NamespacedKey.fromString("recipeweaver:wearable_model");
        setMutableComponent(source, "Equippable", "setModel", NamespacedKey.class, model);
        ItemStack template = new ItemStack(Material.DIAMOND);
        setMutableComponent(template, "Equippable", "setSlot", EquipmentSlot.class,
                EquipmentSlot.CHEST);
        ItemStack copied = copy(source, template);
        check(componentValue(copied, "Equippable", "getSlot") == EquipmentSlot.HEAD
                        && model.equals(componentValue(copied, "Equippable", "getModel")),
                "Equippable slot or model was not copied across materials");
        check(componentValue(template, "Equippable", "getSlot") == EquipmentSlot.CHEST,
                "Equippable copy changed the configured result template");
        check(component(copy(new ItemStack(Material.PAPER), template), "Equippable") == null,
                "Missing source equippable retained the template's component");
        return true;
    }

    static void verifyCustomDataReplacement() {
        NamespacedKey inheritedKey = NamespacedKey.fromString("recipeweaver:inherited_data");
        NamespacedKey staleKey = NamespacedKey.fromString("recipeweaver:template_only_data");
        ItemStack source = new ItemStack(Material.PAPER);
        ItemMeta sourceMeta = source.getItemMeta();
        sourceMeta.getPersistentDataContainer().set(inheritedKey, PersistentDataType.INTEGER, 7);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.DIAMOND);
        ItemMeta templateMeta = template.getItemMeta();
        templateMeta.getPersistentDataContainer().set(staleKey, PersistentDataType.STRING, "stale");
        template.setItemMeta(templateMeta);

        ItemStack copied = copy(source, template);
        PersistentDataContainer copiedData = copied.getItemMeta().getPersistentDataContainer();
        check(Integer.valueOf(7).equals(copiedData.get(inheritedKey, PersistentDataType.INTEGER))
                        && !copiedData.has(staleKey),
                "Cross-material custom data kept a template-only key or lost the source key");
        check(!copy(new ItemStack(Material.PAPER), template).getItemMeta()
                        .getPersistentDataContainer().has(staleKey),
                "An empty source retained the result template's custom data");
        check("stale".equals(template.getItemMeta().getPersistentDataContainer()
                        .get(staleKey, PersistentDataType.STRING)),
                "Custom-data replacement changed the configured result template");
    }

    static boolean verifyGliderCopy() {
        if (!supports("setGlider", boolean.class)) return false;
        ItemStack source = new ItemStack(Material.PAPER);
        setBooleanProperty(source, "Glider", true);
        ItemStack template = new ItemStack(Material.DIAMOND);
        ItemStack copied = copy(source, template);
        check(booleanProperty(copied, "Glider"), "Glider was not copied across materials");
        setBooleanProperty(template, "Glider", true);
        check(!booleanProperty(copy(new ItemStack(Material.PAPER), template), "Glider"),
                "A non-glider source retained the template's glider property");
        check(booleanProperty(template, "Glider"), "Glider copy changed the result template");
        return true;
    }

    static boolean verifyAdditionalTradeCostCopy() {
        if (!supports("setAdditionalTradeCost", Integer.class)) return false;
        ItemStack source = new ItemStack(Material.PAPER);
        setComponent(source, "AdditionalTradeCost", Integer.class, 7);
        ItemStack template = new ItemStack(Material.DIAMOND);
        setComponent(template, "AdditionalTradeCost", Integer.class, 2);
        check(Integer.valueOf(7).equals(serializedTradeCost(copy(source, template))),
                "Additional trade cost was not copied across materials");
        check(serializedTradeCost(copy(new ItemStack(Material.PAPER), template)) == null,
                "Missing source trade cost retained the template's cost");
        check(Integer.valueOf(2).equals(serializedTradeCost(template)),
                "Trade cost copy changed the result template");
        return true;
    }

    static boolean verifyMinimumAttackChargeCopy() {
        if (!supports("setMinimumAttackCharge", Float.class)) return false;
        ItemStack source = new ItemStack(Material.PAPER);
        setComponent(source, "MinimumAttackCharge", Float.class, 0.75F);
        ItemStack template = new ItemStack(Material.DIAMOND);
        setComponent(template, "MinimumAttackCharge", Float.class, 0.25F);
        check(Float.valueOf(0.75F).equals(component(copy(source, template),
                        "MinimumAttackCharge")),
                "Minimum attack charge was not copied across materials");
        check(component(copy(new ItemStack(Material.PAPER), template),
                        "MinimumAttackCharge") == null,
                "Missing source attack charge retained the template's charge");
        check(Float.valueOf(0.25F).equals(component(template, "MinimumAttackCharge")),
                "Attack charge copy changed the result template");
        return true;
    }

    static boolean verifyBreakSoundCopy() {
        if (!supports("setBreakSound", Sound.class)) return false;
        ItemStack source = new ItemStack(Material.PAPER);
        setComponent(source, "BreakSound", Sound.class, Sound.BLOCK_GLASS_BREAK);
        ItemStack template = new ItemStack(Material.DIAMOND);
        setComponent(template, "BreakSound", Sound.class, Sound.ENTITY_ITEM_BREAK);
        check(Sound.BLOCK_GLASS_BREAK.equals(component(copy(source, template), "BreakSound")),
                "Break sound was not copied across materials");
        check(component(copy(new ItemStack(Material.PAPER), template), "BreakSound") == null,
                "Missing source break sound retained the template's sound");
        check(Sound.ENTITY_ITEM_BREAK.equals(component(template, "BreakSound")),
                "Break sound copy changed the result template");
        return true;
    }

    static boolean verifyJukeboxPlayableCopy() {
        if (componentApiType("JukeboxPlayable") == null) return false;
        ItemStack source = new ItemStack(Material.PAPER);
        NamespacedKey song = NamespacedKey.minecraft("cat");
        setMutableComponent(source, "JukeboxPlayable", "setSongKey", NamespacedKey.class, song);
        ItemStack template = new ItemStack(Material.DIAMOND);
        setMutableComponent(template, "JukeboxPlayable", "setSongKey", NamespacedKey.class,
                NamespacedKey.minecraft("13"));
        ItemStack copied = copy(source, template);
        check(song.equals(componentValue(copied, "JukeboxPlayable", "getSongKey")),
                "Jukebox song was not copied across materials");
        check(NamespacedKey.minecraft("13").equals(componentValue(template,
                        "JukeboxPlayable", "getSongKey")),
                "Jukebox copy changed the result template");
        check(component(copy(new ItemStack(Material.PAPER), template), "JukeboxPlayable") == null,
                "Missing source jukebox song retained the template song");
        return true;
    }

    static boolean verifyDamageResistanceCopy() {
        if (!supports("setDamageResistant", org.bukkit.Tag.class)) return false;
        Object fire = damageTag("is_fire");
        Object explosion = damageTag("is_explosion");
        check(fire != null && explosion != null, "Damage resistance tag fixtures are unavailable");
        ItemStack source = new ItemStack(Material.PAPER);
        setComponent(source, "DamageResistant", org.bukkit.Tag.class, fire);
        ItemStack template = new ItemStack(Material.DIAMOND);
        setComponent(template, "DamageResistant", org.bukkit.Tag.class, explosion);
        ItemStack copied = copy(source, template);
        check(component(copied, "DamageResistant") instanceof org.bukkit.Tag<?> inherited
                        && inherited.getKey().equals(NamespacedKey.minecraft("is_fire")),
                "Damage resistance tag was not copied across materials");
        check(component(template, "DamageResistant") instanceof org.bukkit.Tag<?> retained
                        && retained.getKey().equals(NamespacedKey.minecraft("is_explosion")),
                "Damage resistance copy changed the result template");
        check(component(copy(new ItemStack(Material.PAPER), template), "DamageResistant") == null,
                "Missing source damage resistance retained the template tag");
        return true;
    }

    static void verifyWritableBookPagesCopy() {
        ItemStack source = new ItemStack(Material.WRITABLE_BOOK);
        WritableBookMeta sourceMeta = (WritableBookMeta) source.getItemMeta();
        sourceMeta.setPages(java.util.List.of("First page", "Second page"));
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.WRITABLE_BOOK);
        WritableBookMeta templateMeta = (WritableBookMeta) template.getItemMeta();
        templateMeta.setPages(java.util.List.of("Old page"));
        template.setItemMeta(templateMeta);
        check(((WritableBookMeta) copy(source, template).getItemMeta()).getPages()
                        .equals(java.util.List.of("First page", "Second page")),
                "Writable book pages were not inherited");
        check(((WritableBookMeta) template.getItemMeta()).getPages()
                        .equals(java.util.List.of("Old page")),
                "Writable book copy changed the result template");
        check(((WritableBookMeta) copy(new ItemStack(Material.WRITABLE_BOOK), template)
                        .getItemMeta()).getPages().isEmpty(),
                "Empty source retained the template's writable book pages");
        ItemStack written = new ItemStack(Material.WRITTEN_BOOK);
        check(((WritableBookMeta) copy(source, written).getItemMeta()).getPages()
                        .equals(java.util.List.of("First page", "Second page")),
                "Writable book pages were not copied to a written-book result");
    }

    static void verifyHeadProfileCopy() {
        ItemStack source = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sourceMeta = (SkullMeta) source.getItemMeta();
        sourceMeta.setOwnerProfile(headProfile("RWSource", "a".repeat(64)));
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta templateMeta = (SkullMeta) template.getItemMeta();
        templateMeta.setOwnerProfile(headProfile("RWTemplate", "b".repeat(64)));
        template.setItemMeta(templateMeta);

        SkullMeta copied = (SkullMeta) copy(source, template).getItemMeta();
        check(profileMatches(copied.getOwnerProfile(), "RWSource", "a".repeat(64)),
                "Player-head profile name or skin was not copied");
        check(profileMatches(((SkullMeta) template.getItemMeta()).getOwnerProfile(),
                        "RWTemplate", "b".repeat(64)),
                "Head profile copy changed the result template");
        check(((SkullMeta) copy(new ItemStack(Material.PLAYER_HEAD), template)
                        .getItemMeta()).getOwnerProfile() == null,
                "Empty source retained the template's head profile");
    }

    static void verifyHeadNoteSoundCopy() {
        ItemStack source = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sourceMeta = (SkullMeta) source.getItemMeta();
        sourceMeta.setNoteBlockSound(NamespacedKey.minecraft("block.note_block.harp"));
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta templateMeta = (SkullMeta) template.getItemMeta();
        templateMeta.setNoteBlockSound(NamespacedKey.minecraft("entity.zombie.ambient"));
        template.setItemMeta(templateMeta);
        check(NamespacedKey.minecraft("block.note_block.harp").equals(
                        ((SkullMeta) copy(source, template).getItemMeta()).getNoteBlockSound()),
                "Player-head note-block sound was not copied");
        check(NamespacedKey.minecraft("entity.zombie.ambient").equals(
                        ((SkullMeta) template.getItemMeta()).getNoteBlockSound()),
                "Head sound copy changed the result template");
        check(((SkullMeta) copy(new ItemStack(Material.PLAYER_HEAD), template)
                        .getItemMeta()).getNoteBlockSound() == null,
                "Empty source retained the template's head note sound");
    }

    static void verifyShulkerBlockStateCopy() {
        ItemStack source = new ItemStack(Material.RED_SHULKER_BOX);
        ItemStack nested = new ItemStack(Material.DIAMOND, 2);
        var nestedMeta = nested.getItemMeta();
        nestedMeta.getPersistentDataContainer().set(new NamespacedKey("recipeweaver", "nested_rank"),
                org.bukkit.persistence.PersistentDataType.INTEGER, 7);
        nested.setItemMeta(nestedMeta);
        setShulkerSlot(source, 0, nested);
        setShulkerSlot(source, 26, new ItemStack(Material.GLASS));
        ItemStack template = new ItemStack(Material.BLUE_SHULKER_BOX);
        setShulkerSlot(template, 0, new ItemStack(Material.DIRT, 4));
        setShulkerSlot(template, 26, new ItemStack(Material.EMERALD, 3));
        BlockStateMeta templateMeta = (BlockStateMeta) template.getItemMeta();
        ShulkerBox templateBox = (ShulkerBox) templateMeta.getBlockState();
        templateBox.setLock("old-key");
        templateBox.setCustomName("old-name");
        templateMeta.setBlockState(templateBox);
        template.setItemMeta(templateMeta);

        ItemStack copied = copy(source, template);
        check(copied.getType() == Material.BLUE_SHULKER_BOX,
                "Shulker result changed the output color");
        ShulkerBox copiedBox = (ShulkerBox) ((BlockStateMeta) copied.getItemMeta()).getBlockState();
        check(!copiedBox.isLocked() && copiedBox.getCustomName() == null,
                "Unlocked source shulker retained the template lock or block name");
        check(shulkerSlot(copied, 0).getType() == Material.DIAMOND
                        && shulkerSlot(copied, 0).getAmount() == 2
                        && Integer.valueOf(7).equals(shulkerSlot(copied, 0).getItemMeta()
                            .getPersistentDataContainer().get(
                                    new NamespacedKey("recipeweaver", "nested_rank"),
                                    org.bukkit.persistence.PersistentDataType.INTEGER)),
                "Nested shulker item, count, or custom data was not copied");
        check(shulkerSlot(copied, 26).getType() == Material.GLASS,
                "Cross-color shulker copy retained a stale template slot");
        check(shulkerSlot(source, 0).getAmount() == 2
                        && shulkerSlot(template, 0).getType() == Material.DIRT,
                "Shulker result copy mutated its ingredient or template");

        setShulkerSlot(copied, 0, new ItemStack(Material.COAL));
        check(shulkerSlot(source, 0).getType() == Material.DIAMOND
                        && shulkerSlot(template, 0).getType() == Material.DIRT,
                "Nested result edits leaked into source or template");
        ItemStack cleared = copy(new ItemStack(Material.RED_SHULKER_BOX), template);
        check(shulkerSlot(cleared, 0) == null && shulkerSlot(cleared, 26) == null,
                "Empty source retained nested template items");
        check(shulkerSlot(template, 0).getType() == Material.DIRT,
                "Clearing shulker result changed the template");

        ItemStack sameColor = copy(source, new ItemStack(Material.RED_SHULKER_BOX));
        check(shulkerSlot(sameColor, 0).getType() == Material.DIAMOND
                        && shulkerSlot(sameColor, 0).getAmount() == 2,
                "Same-color shulker block-state snapshot was not copied");
    }

    static void verifyCustomDataOnlyCopy() {
        NamespacedKey rank = new NamespacedKey("recipeweaver", "selective_rank");
        NamespacedKey stale = new NamespacedKey("recipeweaver", "selective_stale");
        ItemStack source = new ItemStack(Material.PAPER);
        ItemMeta sourceMeta = source.getItemMeta();
        sourceMeta.setDisplayName("Source name");
        sourceMeta.getPersistentDataContainer().set(rank, org.bukkit.persistence.PersistentDataType.INTEGER, 7);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.DIAMOND);
        ItemMeta templateMeta = template.getItemMeta();
        templateMeta.setDisplayName("Output name");
        templateMeta.setCustomModelData(9);
        templateMeta.getPersistentDataContainer().set(stale,
                org.bukkit.persistence.PersistentDataType.STRING, "old");
        template.setItemMeta(templateMeta);
        ItemStack copied = copy(source, template, ResultMetadataMode.CUSTOM_DATA_ONLY);
        check(Integer.valueOf(7).equals(copied.getItemMeta().getPersistentDataContainer()
                        .get(rank, org.bukkit.persistence.PersistentDataType.INTEGER))
                        && !copied.getItemMeta().getPersistentDataContainer().has(stale)
                        && "Output name".equals(copied.getItemMeta().getDisplayName())
                        && copied.getItemMeta().getCustomModelData() == 9,
                "Custom-data-only mode changed another output component or retained stale keys");
        check("old".equals(template.getItemMeta().getPersistentDataContainer()
                        .get(stale, org.bukkit.persistence.PersistentDataType.STRING)),
                "Custom-data-only copy mutated the template");
        ItemStack cleared = copy(new ItemStack(Material.PAPER), template,
                ResultMetadataMode.CUSTOM_DATA_ONLY);
        check(!cleared.getItemMeta().getPersistentDataContainer().has(stale)
                        && "Output name".equals(cleared.getItemMeta().getDisplayName()),
                "Empty custom-data source did not clear stale output data");
    }

    static void verifyModelOnlyCopy() {
        ItemStack source = new ItemStack(Material.PAPER);
        ItemMeta sourceMeta = source.getItemMeta();
        sourceMeta.setDisplayName("Source name");
        sourceMeta.setCustomModelData(42);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.DIAMOND);
        ItemMeta templateMeta = template.getItemMeta();
        templateMeta.setDisplayName("Output name");
        templateMeta.setLore(java.util.List.of("Output lore"));
        templateMeta.setCustomModelData(9);
        template.setItemMeta(templateMeta);
        ItemStack copied = copy(source, template, ResultMetadataMode.MODEL_ONLY);
        check(copied.getItemMeta().getCustomModelData() == 42
                        && "Output name".equals(copied.getItemMeta().getDisplayName())
                        && java.util.List.of("Output lore").equals(copied.getItemMeta().getLore())
                        && template.getItemMeta().getCustomModelData() == 9,
                "Model-only mode changed unrelated output data or mutated the template");
        ItemStack cleared = copy(new ItemStack(Material.PAPER), template,
                ResultMetadataMode.MODEL_ONLY);
        check(!cleared.getItemMeta().hasCustomModelData()
                        && "Output name".equals(cleared.getItemMeta().getDisplayName()),
                "Empty model source retained the template model");
        if (supports("setItemModel", NamespacedKey.class)) {
            NamespacedKey sourceModel = new NamespacedKey("recipeweaver", "source_model");
            NamespacedKey oldModel = new NamespacedKey("recipeweaver", "old_model");
            setComponent(source, "ItemModel", NamespacedKey.class, sourceModel);
            setComponent(template, "ItemModel", NamespacedKey.class, oldModel);
            check(sourceModel.equals(component(copy(source, template,
                            ResultMetadataMode.MODEL_ONLY), "ItemModel"))
                            && oldModel.equals(component(template, "ItemModel")),
                    "Model-only mode did not replace the modern item-model key");
            check(component(copy(new ItemStack(Material.PAPER), template,
                            ResultMetadataMode.MODEL_ONLY), "ItemModel") == null,
                    "Empty source retained the output item-model key");
        }
    }

    static void verifyEnchantmentsOnlyCopy() {
        var unbreaking = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));
        var sharpness = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("sharpness"));
        ItemStack source = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta sourceMeta = source.getItemMeta();
        sourceMeta.addEnchant(unbreaking, 2, true);
        sourceMeta.setEnchantmentGlintOverride(true);
        sourceMeta.setDisplayName("Source name");
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.IRON_SWORD);
        ItemMeta templateMeta = template.getItemMeta();
        templateMeta.addEnchant(sharpness, 3, true);
        templateMeta.setEnchantmentGlintOverride(false);
        templateMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        templateMeta.setDisplayName("Output name");
        template.setItemMeta(templateMeta);
        ItemStack copied = copy(source, template, ResultMetadataMode.ENCHANTMENTS_ONLY);
        check(copied.getItemMeta().getEnchantLevel(unbreaking) == 2
                        && !copied.getItemMeta().hasEnchant(sharpness)
                        && Boolean.TRUE.equals(copied.getItemMeta().getEnchantmentGlintOverride())
                        && copied.getItemMeta().hasItemFlag(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS)
                        && "Output name".equals(copied.getItemMeta().getDisplayName())
                        && template.getItemMeta().getEnchantLevel(sharpness) == 3,
                "Enchantments-only mode changed unrelated output fields or retained stale enchantments");
        ItemStack cleared = copy(new ItemStack(Material.DIAMOND_SWORD), template,
                ResultMetadataMode.ENCHANTMENTS_ONLY);
        check(cleared.getItemMeta().getEnchants().isEmpty()
                        && !cleared.getItemMeta().hasEnchantmentGlintOverride()
                        && cleared.getItemMeta().hasItemFlag(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS),
                "Empty enchantment source retained enchantments or glint override");
    }

    static void verifyFoodOnlyCopy() {
        ItemStack source = new ItemStack(Material.PAPER);
        editFood("setFoodProperties", source, "5 1.25 true 2.5");
        ItemStack template = new ItemStack(Material.DIAMOND);
        editFood("setFoodProperties", template, "2 0.5 false 1.0");
        ItemMeta templateMeta = template.getItemMeta();
        templateMeta.setDisplayName("Output name");
        templateMeta.setCustomModelData(9);
        template.setItemMeta(templateMeta);

        ItemStack copied = copy(source, template, ResultMetadataMode.FOOD_ONLY);
        check(copied.getItemMeta().getFood().getNutrition() == 5
                        && copied.getItemMeta().getFood().getSaturation() == 1.25F
                        && "Output name".equals(copied.getItemMeta().getDisplayName())
                        && copied.getItemMeta().getCustomModelData() == 9
                        && template.getItemMeta().getFood().getNutrition() == 2,
                "Food-only copy changed unrelated output data or its template");
        ItemStack cleared = copy(new ItemStack(Material.PAPER), template,
                ResultMetadataMode.FOOD_ONLY);
        check(!cleared.getItemMeta().hasFood()
                        && "Output name".equals(cleared.getItemMeta().getDisplayName()),
                "Food-only copy retained stale food or cleared the output name");
    }

    static void verifyToolOnlyCopy() {
        ItemStack source = new ItemStack(Material.STICK);
        ItemMeta sourceMeta = source.getItemMeta();
        var sourceTool = sourceMeta.getTool();
        sourceTool.setDefaultMiningSpeed(5.0F);
        sourceTool.addRule(java.util.List.of(Material.STONE, Material.DIRT), 8.0F, true);
        sourceMeta.setTool(sourceTool);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.PAPER);
        ItemMeta templateMeta = template.getItemMeta();
        var oldTool = templateMeta.getTool();
        oldTool.addRule(Material.OAK_LOG, 2.0F, false);
        templateMeta.setTool(oldTool);
        templateMeta.setDisplayName("Output name");
        template.setItemMeta(templateMeta);

        ItemStack copied = copy(source, template, ResultMetadataMode.TOOL_ONLY);
        var copiedTool = copied.getItemMeta().getTool();
        check(copied.getItemMeta().hasTool()
                        && copiedTool.getDefaultMiningSpeed() == 5.0F
                        && copiedTool.getRules().size() == 1
                        && copiedTool.getRules().get(0).getBlocks().containsAll(
                                java.util.List.of(Material.STONE, Material.DIRT))
                        && !copiedTool.getRules().get(0).getBlocks().contains(Material.OAK_LOG)
                        && "Output name".equals(copied.getItemMeta().getDisplayName())
                        && template.getItemMeta().getTool().getRules().get(0)
                                .getBlocks().contains(Material.OAK_LOG),
                "Tool-only copy lost grouped rules, changed other output data, or mutated the template");
        ItemStack cleared = copy(new ItemStack(Material.STICK), template,
                ResultMetadataMode.TOOL_ONLY);
        check(!cleared.getItemMeta().hasTool()
                        && "Output name".equals(cleared.getItemMeta().getDisplayName()),
                "Tool-only copy retained stale rules or cleared the output name");
    }

    static void verifyAttributeModifiersOnlyCopy() {
        Attribute damage = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic.attack_damage"));
        if (damage == null) damage = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("attack_damage"));
        check(damage != null, "Attack damage attribute is unavailable");
        String attributeKey = damage.getKey().toString();
        ItemStack source = new ItemStack(Material.DIAMOND_SWORD);
        editAttribute(source, attributeKey + " mainhand 7 ADD_NUMBER");
        ItemStack template = new ItemStack(Material.IRON_SWORD);
        editAttribute(template, attributeKey + " offhand 3 ADD_NUMBER");
        ItemMeta templateMeta = template.getItemMeta();
        templateMeta.setDisplayName("Output name");
        template.setItemMeta(templateMeta);

        ItemStack copied = copy(source, template, ResultMetadataMode.ATTRIBUTE_MODIFIERS_ONLY);
        Attribute selected = damage;
        check(copied.getItemMeta().getAttributeModifiers(selected).size() == 1
                        && copied.getItemMeta().getAttributeModifiers(selected)
                                .iterator().next().getAmount() == 7D
                        && "Output name".equals(copied.getItemMeta().getDisplayName())
                        && template.getItemMeta().getAttributeModifiers(selected)
                                .iterator().next().getAmount() == 3D,
                "Attribute-only copy changed unrelated output data or mutated the template");
        ItemStack cleared = copy(new ItemStack(Material.DIAMOND_SWORD), template,
                ResultMetadataMode.ATTRIBUTE_MODIFIERS_ONLY);
        check(!cleared.getItemMeta().hasAttributeModifiers()
                        && "Output name".equals(cleared.getItemMeta().getDisplayName()),
                "Attribute-only copy retained stale modifiers or cleared the output name");
    }

    static void verifyDamageOnlyCopy() {
        ItemStack source = new ItemStack(Material.DIAMOND_SWORD);
        org.bukkit.inventory.meta.Damageable sourceMeta =
                (org.bukkit.inventory.meta.Damageable) source.getItemMeta();
        sourceMeta.setMaxDamage(500);
        sourceMeta.setDamage(300);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.IRON_SWORD);
        org.bukkit.inventory.meta.Damageable templateMeta =
                (org.bukkit.inventory.meta.Damageable) template.getItemMeta();
        templateMeta.setMaxDamage(400);
        templateMeta.setDamage(50);
        templateMeta.setDisplayName("Output name");
        template.setItemMeta(templateMeta);

        org.bukkit.inventory.meta.Damageable copied =
                (org.bukkit.inventory.meta.Damageable) copy(source, template,
                        ResultMetadataMode.DAMAGE_ONLY).getItemMeta();
        check(copied.hasMaxDamage() && copied.getMaxDamage() == 500
                        && copied.getDamage() == 300
                        && "Output name".equals(copied.getDisplayName())
                        && ((org.bukkit.inventory.meta.Damageable) template.getItemMeta())
                                .getDamage() == 50,
                "Damage-only copy changed other output data or mutated the template");
        sourceMeta = (org.bukkit.inventory.meta.Damageable) source.getItemMeta();
        sourceMeta.setMaxDamage(null);
        sourceMeta.setDamage(300);
        source.setItemMeta(sourceMeta);
        org.bukkit.inventory.meta.Damageable clamped =
                (org.bukkit.inventory.meta.Damageable) copy(source, template,
                        ResultMetadataMode.DAMAGE_ONLY).getItemMeta();
        check(!clamped.hasMaxDamage() && clamped.getDamage() == 0
                        && "Output name".equals(clamped.getDisplayName()),
                "Damage-only copy did not clear maximum or clamp against output durability");
    }

    static void verifyItemFlagsOnlyCopy() {
        ItemStack source = new ItemStack(Material.PAPER);
        ItemMeta sourceMeta = source.getItemMeta();
        sourceMeta.addEnchant(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking")),
                1, true);
        sourceMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.DIAMOND);
        Attribute damage = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic.attack_damage"));
        if (damage == null) damage = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("attack_damage"));
        check(damage != null, "Attack damage attribute is unavailable");
        editAttribute(template, damage.getKey() + " mainhand 3 ADD_NUMBER");
        ItemMeta templateMeta = template.getItemMeta();
        templateMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
        templateMeta.setDisplayName("Output name");
        template.setItemMeta(templateMeta);

        check(source.getItemMeta().hasItemFlag(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS)
                        && template.getItemMeta().hasItemFlag(
                                org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES),
                "Server discarded a component-backed item flag before selective copy: source="
                        + source.getItemMeta().getItemFlags() + ", template="
                        + template.getItemMeta().getItemFlags());
        var sourceFlags = source.getItemMeta().getItemFlags();
        var templateFlags = template.getItemMeta().getItemFlags();
        check(!sourceFlags.equals(templateFlags),
                "Item-flags-only copy fixture needs distinct source and template flags");

        ItemStack copied = copy(source, template, ResultMetadataMode.ITEM_FLAGS_ONLY);
        check(copied.getItemMeta().getItemFlags().equals(sourceFlags)
                        && "Output name".equals(copied.getItemMeta().getDisplayName())
                        && template.getItemMeta().getItemFlags().equals(templateFlags),
                "Item-flags-only copy changed unrelated output data or mutated the template: "
                        + "source=" + source.getItemMeta().getItemFlags() + ", copied="
                        + copied.getItemMeta().getItemFlags() + ", template="
                        + template.getItemMeta().getItemFlags());
        ItemStack cleared = copy(new ItemStack(Material.PAPER), template,
                ResultMetadataMode.ITEM_FLAGS_ONLY);
        check(cleared.getItemMeta().getItemFlags().isEmpty()
                        && "Output name".equals(cleared.getItemMeta().getDisplayName()),
                "Item-flags-only copy retained stale flags or cleared the output name");
    }

    static void verifyUnbreakableOnlyCopy() {
        ItemStack source = new ItemStack(Material.PAPER);
        ItemMeta sourceMeta = source.getItemMeta();
        sourceMeta.setUnbreakable(true);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.IRON_SWORD);
        ItemMeta templateMeta = template.getItemMeta();
        templateMeta.setDisplayName("Output name");
        template.setItemMeta(templateMeta);

        ItemStack copied = copy(source, template, ResultMetadataMode.UNBREAKABLE_ONLY);
        check(copied.getItemMeta().isUnbreakable()
                        && "Output name".equals(copied.getItemMeta().getDisplayName())
                        && !template.getItemMeta().isUnbreakable(),
                "Unbreakable-only copy changed other output data or mutated the template");

        templateMeta = template.getItemMeta();
        templateMeta.setUnbreakable(true);
        template.setItemMeta(templateMeta);
        ItemStack cleared = copy(new ItemStack(Material.PAPER), template,
                ResultMetadataMode.UNBREAKABLE_ONLY);
        check(!cleared.getItemMeta().isUnbreakable()
                        && "Output name".equals(cleared.getItemMeta().getDisplayName())
                        && template.getItemMeta().isUnbreakable(),
                "Unbreakable-only copy retained stale state or changed the template");
    }

    static void setShulkerSlot(ItemStack item, int slot, ItemStack contents) {
        BlockStateMeta meta = (BlockStateMeta) item.getItemMeta();
        ShulkerBox box = (ShulkerBox) meta.getBlockState();
        box.getSnapshotInventory().setItem(slot, contents == null ? null : contents.clone());
        meta.setBlockState(box);
        check(item.setItemMeta(meta), "Could not store shulker block state");
    }

    static ItemStack shulkerSlot(ItemStack item, int slot) {
        BlockStateMeta meta = (BlockStateMeta) item.getItemMeta();
        ShulkerBox box = (ShulkerBox) meta.getBlockState();
        return box.getSnapshotInventory().getItem(slot);
    }

    private static PlayerProfile headProfile(String name, String skinHash) {
        PlayerProfile profile = Bukkit.createPlayerProfile(
                UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name);
        try {
            var textures = profile.getTextures();
            textures.setSkin(URI.create("https://textures.minecraft.net/texture/"
                    + skinHash).toURL());
            profile.setTextures(textures);
        } catch (MalformedURLException ex) {
            throw new IllegalStateException("Invalid head profile fixture", ex);
        }
        return profile;
    }

    private static boolean profileMatches(PlayerProfile profile, String name, String skinHash) {
        return profile != null && name.equals(profile.getName())
                && UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8))
                        .equals(profile.getUniqueId())
                && profile.getTextures().getSkin() != null
                && profile.getTextures().getSkin().toString().equals(
                        "https://textures.minecraft.net/texture/" + skinHash);
    }

    static void verifyFoodConsumptionCopy() {
        ItemStack source = new ItemStack(Material.PAPER);
        editFood("setFoodProperties", source, "5 1.25 true 2.5");
        editFood("setFoodEffect", source, "minecraft:speed 120 1 0.5");
        ItemStack template = new ItemStack(Material.DIAMOND);
        editFood("setFoodProperties", template, "2 0.5 false 1.0");
        ItemStack copied = copy(source, template);
        check(copied.getItemMeta().hasFood()
                        && copied.getItemMeta().getFood().getNutrition() == 5
                        && copied.getItemMeta().getFood().getSaturation() == 1.25F
                        && copied.getItemMeta().getFood().canAlwaysEat(),
                "Food properties were not inherited");
        check((float) readFood("foodConsumeSeconds", new Class<?>[] {ItemStack.class},
                        copied) == 2.5F,
                "Food consumption time was not inherited");
        check((boolean) readFood("foodEffectMatches",
                        new Class<?>[] {ItemStack.class, PotionEffectType.class,
                                int.class, int.class, float.class},
                        copied, PotionEffectType.SPEED, 120, 1, 0.5F),
                "Food consumption effect was not inherited");
        check(template.getItemMeta().getFood().getNutrition() == 2,
                "Food copy mutated the result template");
        ItemStack cleared = copy(new ItemStack(Material.PAPER), template);
        check(!cleared.getItemMeta().hasFood(),
                "Missing source food retained the result template's food component");
    }

    static void verifyAttributeModifierCopy() {
        Attribute damage = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic.attack_damage"));
        if (damage == null) damage = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("attack_damage"));
        check(damage != null, "Attack damage attribute is unavailable");
        String attributeKey = damage.getKey().toString();
        ItemStack source = new ItemStack(Material.DIAMOND_SWORD);
        editAttribute(source, attributeKey + " mainhand 7 ADD_NUMBER");
        ItemStack template = new ItemStack(Material.IRON_SWORD);
        editAttribute(template, attributeKey + " offhand 3 ADD_NUMBER");
        Attribute selected = damage;
        ItemStack copied = copy(source, template);
        check(copied.getItemMeta().getAttributeModifiers(selected).size() == 1
                        && copied.getItemMeta().getAttributeModifiers(selected)
                                .iterator().next().getAmount() == 7D,
                "Attribute modifiers were not inherited from the actual source");
        check(template.getItemMeta().getAttributeModifiers(selected)
                        .iterator().next().getAmount() == 3D,
                "Attribute copy mutated the result template");
        check(!copy(new ItemStack(Material.DIAMOND_SWORD), template)
                        .getItemMeta().hasAttributeModifiers(),
                "Missing source modifiers retained template modifiers");
    }

    static void verifyPersistentCompoundCopy() {
        NamespacedKey outer = NamespacedKey.fromString("recipeweaver:copied_compound");
        NamespacedKey rank = NamespacedKey.fromString("recipeweaver:rank");
        NamespacedKey extra = NamespacedKey.fromString("recipeweaver:template_only");
        ItemStack source = new ItemStack(Material.PAPER);
        ItemMeta sourceMeta = source.getItemMeta();
        PersistentDataContainer sourceData = sourceMeta.getPersistentDataContainer();
        PersistentDataContainer sourceCompound = sourceData.getAdapterContext()
                .newPersistentDataContainer();
        sourceCompound.set(rank, PersistentDataType.INTEGER, 4);
        sourceData.set(outer, PersistentDataType.TAG_CONTAINER, sourceCompound);
        source.setItemMeta(sourceMeta);

        ItemStack template = new ItemStack(Material.DIAMOND);
        ItemMeta templateMeta = template.getItemMeta();
        PersistentDataContainer templateData = templateMeta.getPersistentDataContainer();
        PersistentDataContainer templateCompound = templateData.getAdapterContext()
                .newPersistentDataContainer();
        templateCompound.set(rank, PersistentDataType.INTEGER, 1);
        templateCompound.set(extra, PersistentDataType.STRING, "old");
        templateData.set(outer, PersistentDataType.TAG_CONTAINER, templateCompound);
        template.setItemMeta(templateMeta);

        ItemStack result = copy(source, template);
        PersistentDataContainer copied = result.getItemMeta().getPersistentDataContainer()
                .get(outer, PersistentDataType.TAG_CONTAINER);
        check(copied != null && Integer.valueOf(4).equals(copied.get(rank,
                        PersistentDataType.INTEGER)) && !copied.has(extra),
                "Nested compound result copy did not replace the template's matching key");
        check(Integer.valueOf(1).equals(template.getItemMeta().getPersistentDataContainer()
                        .get(outer, PersistentDataType.TAG_CONTAINER)
                        .get(rank, PersistentDataType.INTEGER)),
                "Compound result copy mutated the configured template");
        check(Integer.valueOf(4).equals(source.getItemMeta().getPersistentDataContainer()
                        .get(outer, PersistentDataType.TAG_CONTAINER)
                        .get(rank, PersistentDataType.INTEGER)),
                "Compound result copy mutated the input item");
    }

    private static void editAttribute(ItemStack item, String value) {
        try {
            Class<?> editor = Class.forName("dev.recipeweaver.core.ItemDraftEditor", true,
                    Bukkit.getPluginManager().getPlugin("RecipeWeaver")
                            .getClass().getClassLoader());
            var method = editor.getDeclaredMethod("setAttributeModifier", ItemStack.class,
                    String.class);
            method.setAccessible(true);
            method.invoke(null, item, value);
        } catch (InvocationTargetException ex) {
            throw new IllegalStateException("Attribute copy smoke edit failed", ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not invoke attribute copy smoke edit", ex);
        }
    }

    private static void editFood(String name, ItemStack item, String value) {
        try {
            Class<?> editor = Class.forName("dev.recipeweaver.core.ItemDraftEditor", true,
                    Bukkit.getPluginManager().getPlugin("RecipeWeaver")
                            .getClass().getClassLoader());
            var method = editor.getDeclaredMethod(name, ItemStack.class, String.class);
            method.setAccessible(true);
            method.invoke(null, item, value);
        } catch (InvocationTargetException ex) {
            throw new IllegalStateException("Food copy smoke edit failed", ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not invoke food copy smoke edit", ex);
        }
    }

    private static Object readFood(String name, Class<?>[] parameters, Object... arguments) {
        try {
            Class<?> editor = Class.forName("dev.recipeweaver.core.ItemDraftEditor", true,
                    Bukkit.getPluginManager().getPlugin("RecipeWeaver")
                            .getClass().getClassLoader());
            var method = editor.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method.invoke(null, arguments);
        } catch (InvocationTargetException ex) {
            throw new IllegalStateException("Food copy smoke read failed", ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not invoke food copy smoke read", ex);
        }
    }

    private static boolean supports(String name, Class<?> argument) {
        try { ItemMeta.class.getMethod(name, argument); return true; }
        catch (NoSuchMethodException ex) { return false; }
    }

    private static void setBooleanProperty(ItemStack item, String suffix, boolean value) {
        try {
            ItemMeta meta = item.getItemMeta();
            ItemMeta.class.getMethod("set" + suffix, boolean.class).invoke(meta, value);
            item.setItemMeta(meta);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not set " + suffix + " smoke property", ex);
        }
    }

    private static boolean booleanProperty(ItemStack item, String suffix) {
        try {
            return (boolean) ItemMeta.class.getMethod("is" + suffix).invoke(item.getItemMeta());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not read " + suffix + " smoke property", ex);
        }
    }

    private static Object damageTag(String name) {
        try {
            Class<?> type = Class.forName("org.bukkit.damage.DamageType");
            return Bukkit.class.getMethod("getTag", String.class, NamespacedKey.class, Class.class)
                    .invoke(null, "damage_types", NamespacedKey.minecraft(name), type);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not find damage type tag " + name, ex);
        }
    }

    private static Integer serializedTradeCost(ItemStack item) {
        Object value = item.getItemMeta().serialize().get("additional-trade-cost");
        return value instanceof Integer cost ? cost : null;
    }

    private static Class<?> componentApiType(String name) {
        try { return ItemMeta.class.getMethod("get" + name).getReturnType(); }
        catch (NoSuchMethodException ex) { return null; }
    }

    private static void setMutableComponent(ItemStack item, String name, String mutator,
                                            Class<?> argument, Object value) {
        try {
            ItemMeta meta = item.getItemMeta();
            var get = ItemMeta.class.getMethod("get" + name);
            Object component = get.invoke(meta);
            get.getReturnType().getMethod(mutator, argument).invoke(component, value);
            ItemMeta.class.getMethod("set" + name, get.getReturnType()).invoke(meta, component);
            item.setItemMeta(meta);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not set " + name + " smoke component", ex);
        }
    }

    private static Object componentValue(ItemStack item, String name, String getter) {
        try {
            Object component = component(item, name);
            return component == null ? null : componentApiType(name).getMethod(getter)
                    .invoke(component);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not read " + name + " smoke component", ex);
        }
    }

    private static void setComponent(ItemStack item, String name, Class<?> argument, Object value) {
        try {
            ItemMeta meta = item.getItemMeta();
            ItemMeta.class.getMethod("set" + name, argument).invoke(meta, (Object) value);
            item.setItemMeta(meta);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not set " + name + " smoke component", ex);
        }
    }

    private static Object component(ItemStack item, String name) {
        try {
            Object meta = item.getItemMeta();
            boolean present = (boolean) ItemMeta.class.getMethod("has" + name).invoke(meta);
            return present ? ItemMeta.class.getMethod("get" + name).invoke(meta) : null;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not read " + name + " smoke component", ex);
        }
    }

    static void verifyPotionBaseClearing() {
        ItemStack source = new ItemStack(Material.POTION);
        ItemStack template = new ItemStack(Material.SPLASH_POTION);
        PotionMeta templateMeta = (PotionMeta) template.getItemMeta();
        templateMeta.setBasePotionType(PotionType.HEALING);
        template.setItemMeta(templateMeta);
        ItemStack copied = copy(source, template);
        check(((PotionMeta) copied.getItemMeta()).getBasePotionType() == PotionType.WATER,
                "A source without a base potion retained the result template's effect");
        check(((PotionMeta) template.getItemMeta()).getBasePotionType() == PotionType.HEALING,
                "Clearing a potion base mutated the result template");
    }

    static void verifyStoredEnchantments() {
        var enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));
        var other = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("efficiency"));
        check(enchantment != null && other != null, "Stored enchantment fixtures are unavailable");
        ItemStack source = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta sourceMeta = (EnchantmentStorageMeta) source.getItemMeta();
        sourceMeta.addStoredEnchant(enchantment, 3, true);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta templateMeta = (EnchantmentStorageMeta) template.getItemMeta();
        templateMeta.addStoredEnchant(other, 4, true);
        template.setItemMeta(templateMeta);
        EnchantmentStorageMeta copied = (EnchantmentStorageMeta) copy(source, template).getItemMeta();
        check(copied.getStoredEnchants().size() == 1
                        && copied.getStoredEnchantLevel(enchantment) == 3
                        && !copied.hasStoredEnchant(other),
                "Stored enchantments did not replace the result template's enchantments");
        check(((EnchantmentStorageMeta) template.getItemMeta()).hasStoredEnchant(other),
                "Stored enchantment copy mutated the result template");
    }

    static void verifyCrossbowProjectiles() {
        ItemStack source = new ItemStack(Material.CROSSBOW);
        CrossbowMeta sourceMeta = (CrossbowMeta) source.getItemMeta();
        sourceMeta.setChargedProjectiles(java.util.List.of(new ItemStack(Material.ARROW)));
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.CROSSBOW);
        CrossbowMeta templateMeta = (CrossbowMeta) template.getItemMeta();
        templateMeta.setChargedProjectiles(java.util.List.of(new ItemStack(Material.FIREWORK_ROCKET)));
        template.setItemMeta(templateMeta);
        CrossbowMeta copied = (CrossbowMeta) copy(source, template).getItemMeta();
        check(copied.getChargedProjectiles().size() == 1
                        && copied.getChargedProjectiles().get(0).getType() == Material.ARROW,
                "Charged crossbow projectile was not inherited");
        check(((CrossbowMeta) template.getItemMeta()).getChargedProjectiles().get(0)
                        .getType() == Material.FIREWORK_ROCKET,
                "Crossbow copy mutated the result template");
        CrossbowMeta cleared = (CrossbowMeta) copy(new ItemStack(Material.CROSSBOW), template)
                .getItemMeta();
        check(cleared.getChargedProjectiles().isEmpty(),
                "Uncharged source retained a charged result template");
    }

    static void verifyStewEffects() {
        ItemStack source = new ItemStack(Material.SUSPICIOUS_STEW);
        SuspiciousStewMeta sourceMeta = (SuspiciousStewMeta) source.getItemMeta();
        sourceMeta.addCustomEffect(new PotionEffect(PotionEffectType.SPEED, 120, 0), true);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.SUSPICIOUS_STEW);
        SuspiciousStewMeta templateMeta = (SuspiciousStewMeta) template.getItemMeta();
        templateMeta.addCustomEffect(new PotionEffect(PotionEffectType.POISON, 80, 1), true);
        template.setItemMeta(templateMeta);
        SuspiciousStewMeta copied = (SuspiciousStewMeta) copy(source, template).getItemMeta();
        check(copied.getCustomEffects().size() == 1
                        && copied.getCustomEffects().get(0).getType() == PotionEffectType.SPEED
                        && copied.getCustomEffects().get(0).getDuration() == 120,
                "Suspicious stew effects were not inherited");
        check(((SuspiciousStewMeta) template.getItemMeta()).hasCustomEffect(PotionEffectType.POISON),
                "Stew effect copy mutated the result template");
    }

    static void verifyFireworkEffects() {
        ItemStack source = new ItemStack(Material.FIREWORK_ROCKET);
        FireworkMeta sourceMeta = (FireworkMeta) source.getItemMeta();
        sourceMeta.setPower(3);
        sourceMeta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.STAR)
                .withColor(Color.BLUE).build());
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.FIREWORK_ROCKET);
        FireworkMeta templateMeta = (FireworkMeta) template.getItemMeta();
        templateMeta.setPower(1);
        templateMeta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL)
                .withColor(Color.RED).build());
        template.setItemMeta(templateMeta);
        FireworkMeta copied = (FireworkMeta) copy(source, template).getItemMeta();
        check(copied.getPower() == 3 && copied.getEffectsSize() == 1
                        && copied.getEffects().get(0).getType() == FireworkEffect.Type.STAR
                        && copied.getEffects().get(0).getColors().contains(Color.BLUE),
                "Firework flight duration or explosion effects were not inherited");
        check(((FireworkMeta) template.getItemMeta()).getPower() == 1,
                "Firework copy mutated the result template");
    }

    static void verifyTropicalFishVariant() {
        ItemStack source = new ItemStack(Material.TROPICAL_FISH_BUCKET);
        TropicalFishBucketMeta sourceMeta = (TropicalFishBucketMeta) source.getItemMeta();
        sourceMeta.setPattern(TropicalFish.Pattern.KOB);
        sourceMeta.setPatternColor(DyeColor.YELLOW);
        sourceMeta.setBodyColor(DyeColor.YELLOW);
        sourceMeta.setPatternColor(DyeColor.BLUE);
        check(sourceMeta.getBodyColor() == DyeColor.YELLOW
                        && sourceMeta.getPatternColor() == DyeColor.BLUE,
                "Tropical fish source fixture could not retain separate colors");
        source.setItemMeta(sourceMeta);
        ItemStack copied = copy(source, new ItemStack(Material.TROPICAL_FISH_BUCKET));
        TropicalFishBucketMeta copiedMeta = (TropicalFishBucketMeta) copied.getItemMeta();
        check(copiedMeta.getPattern() == TropicalFish.Pattern.KOB
                        && copiedMeta.getBodyColor() == DyeColor.YELLOW
                        && copiedMeta.getPatternColor() == DyeColor.BLUE,
                "Tropical fish variant was not inherited: " + copiedMeta.getPattern()
                        + ", " + copiedMeta.getBodyColor() + ", " + copiedMeta.getPatternColor());
    }

    static void verifyFireworkStarEffect() {
        ItemStack source = new ItemStack(Material.FIREWORK_STAR);
        FireworkEffectMeta sourceMeta = (FireworkEffectMeta) source.getItemMeta();
        sourceMeta.setEffect(FireworkEffect.builder().with(FireworkEffect.Type.CREEPER)
                .withColor(Color.GREEN).build());
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.FIREWORK_STAR);
        FireworkEffectMeta templateMeta = (FireworkEffectMeta) template.getItemMeta();
        templateMeta.setEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL)
                .withColor(Color.RED).build());
        template.setItemMeta(templateMeta);
        FireworkEffectMeta copied = (FireworkEffectMeta) copy(source, template).getItemMeta();
        check(copied.hasEffect() && copied.getEffect().getType() == FireworkEffect.Type.CREEPER
                        && copied.getEffect().getColors().contains(Color.GREEN),
                "Firework star explosion effect was not inherited");
        check(((FireworkEffectMeta) template.getItemMeta()).getEffect().getType()
                        == FireworkEffect.Type.BALL,
                "Firework star copy mutated the result template");
        check(!((FireworkEffectMeta) copy(new ItemStack(Material.FIREWORK_STAR), template)
                        .getItemMeta()).hasEffect(),
                "A source without a star effect retained the template effect");
    }

    static void verifyWrittenBook() {
        ItemStack source = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta sourceMeta = (BookMeta) source.getItemMeta();
        sourceMeta.setTitle("Source title");
        sourceMeta.setAuthor("RecipeWeaver");
        sourceMeta.setPages("First page", "Second page");
        sourceMeta.setGeneration(BookMeta.Generation.COPY_OF_ORIGINAL);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta templateMeta = (BookMeta) template.getItemMeta();
        templateMeta.setTitle("Template title");
        templateMeta.setAuthor("Template");
        templateMeta.setPages("Wrong page");
        template.setItemMeta(templateMeta);
        BookMeta copied = (BookMeta) copy(source, template).getItemMeta();
        check("Source title".equals(copied.getTitle())
                        && "RecipeWeaver".equals(copied.getAuthor())
                        && copied.getPages().equals(java.util.List.of("First page", "Second page"))
                        && copied.getGeneration() == BookMeta.Generation.COPY_OF_ORIGINAL,
                "Written book title, author, pages, or generation was not inherited");
        check("Template title".equals(((BookMeta) template.getItemMeta()).getTitle()),
                "Written book copy mutated the result template");
    }

    static void verifyCompassLodestone() {
        Location destination = new Location(Bukkit.getWorlds().get(0), 12, 75, 34);
        ItemStack source = new ItemStack(Material.COMPASS);
        CompassMeta sourceMeta = (CompassMeta) source.getItemMeta();
        sourceMeta.setLodestone(destination);
        sourceMeta.setLodestoneTracked(false);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.COMPASS);
        CompassMeta templateMeta = (CompassMeta) template.getItemMeta();
        templateMeta.setLodestone(new Location(destination.getWorld(), 40, 80, 42));
        template.setItemMeta(templateMeta);
        CompassMeta copied = (CompassMeta) copy(source, template).getItemMeta();
        check(copied.hasLodestone() && destination.equals(copied.getLodestone())
                        && !copied.isLodestoneTracked(),
                "Compass lodestone or tracking setting was not inherited");
        check(((CompassMeta) template.getItemMeta()).getLodestone().getBlockX() == 40,
                "Compass copy mutated the result template");
        check(!((CompassMeta) copy(new ItemStack(Material.COMPASS), template)
                        .getItemMeta()).hasLodestone(),
                "A source without a lodestone retained the template location");
    }

    static void verifyBundleContents() {
        ItemStack source = new ItemStack(Material.BUNDLE);
        BundleMeta sourceMeta = (BundleMeta) source.getItemMeta();
        sourceMeta.setItems(java.util.List.of(new ItemStack(Material.DIAMOND, 2)));
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.BUNDLE);
        BundleMeta templateMeta = (BundleMeta) template.getItemMeta();
        templateMeta.setItems(java.util.List.of(new ItemStack(Material.DIRT, 3)));
        template.setItemMeta(templateMeta);
        BundleMeta copied = (BundleMeta) copy(source, template).getItemMeta();
        check(copied.getItems().size() == 1 && copied.getItems().get(0).getType() == Material.DIAMOND
                        && copied.getItems().get(0).getAmount() == 2,
                "Bundle contents were not inherited");
        check(((BundleMeta) template.getItemMeta()).getItems().get(0).getType() == Material.DIRT,
                "Bundle copy mutated the result template");
    }

    static void verifyRepairCost() {
        ItemStack source = new ItemStack(Material.DIAMOND_SWORD);
        Repairable sourceMeta = (Repairable) source.getItemMeta();
        sourceMeta.setRepairCost(7);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.IRON_SWORD);
        Repairable templateMeta = (Repairable) template.getItemMeta();
        templateMeta.setRepairCost(2);
        template.setItemMeta(templateMeta);
        Repairable copied = (Repairable) copy(source, template).getItemMeta();
        check(copied.getRepairCost() == 7, "Anvil repair cost was not inherited");
        check(((Repairable) template.getItemMeta()).getRepairCost() == 2,
                "Repair cost copy mutated the result template");
        check(((Repairable) copy(new ItemStack(Material.DIAMOND_SWORD), template)
                        .getItemMeta()).getRepairCost() == 0,
                "A source without repair cost retained the template cost");
    }

    static void verifyAxolotlVariant() {
        ItemStack source = new ItemStack(Material.AXOLOTL_BUCKET);
        AxolotlBucketMeta sourceMeta = (AxolotlBucketMeta) source.getItemMeta();
        sourceMeta.setVariant(Axolotl.Variant.BLUE);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.AXOLOTL_BUCKET);
        AxolotlBucketMeta templateMeta = (AxolotlBucketMeta) template.getItemMeta();
        templateMeta.setVariant(Axolotl.Variant.GOLD);
        template.setItemMeta(templateMeta);
        AxolotlBucketMeta copied = (AxolotlBucketMeta) copy(source, template).getItemMeta();
        check(copied.hasVariant() && copied.getVariant() == Axolotl.Variant.BLUE,
                "Axolotl bucket variant was not inherited");
        check(((AxolotlBucketMeta) template.getItemMeta()).getVariant() == Axolotl.Variant.GOLD,
                "Axolotl variant copy mutated the result template");
        AxolotlBucketMeta cleared = (AxolotlBucketMeta) copy(
                new ItemStack(Material.AXOLOTL_BUCKET), template).getItemMeta();
        check(!cleared.hasVariant(), "An unset source retained the template axolotl variant");
    }

    static void verifyKnowledgeBookRecipes() {
        ItemStack source = new ItemStack(Material.KNOWLEDGE_BOOK);
        KnowledgeBookMeta sourceMeta = (KnowledgeBookMeta) source.getItemMeta();
        NamespacedKey selected = NamespacedKey.minecraft("stone_pickaxe");
        sourceMeta.setRecipes(java.util.List.of(selected));
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.KNOWLEDGE_BOOK);
        KnowledgeBookMeta templateMeta = (KnowledgeBookMeta) template.getItemMeta();
        templateMeta.setRecipes(java.util.List.of(NamespacedKey.minecraft("stick")));
        template.setItemMeta(templateMeta);
        KnowledgeBookMeta copied = (KnowledgeBookMeta) copy(source, template).getItemMeta();
        check(copied.getRecipes().equals(java.util.List.of(selected)),
                "Knowledge book recipe list was not inherited");
        check(((KnowledgeBookMeta) template.getItemMeta()).getRecipes().get(0)
                        .equals(NamespacedKey.minecraft("stick")),
                "Knowledge book copy mutated the result template");
    }

    static void verifyGoatHornInstrument() {
        ItemStack source = new ItemStack(Material.GOAT_HORN);
        MusicInstrumentMeta sourceMeta = (MusicInstrumentMeta) source.getItemMeta();
        sourceMeta.setInstrument(MusicInstrument.DREAM_GOAT_HORN);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.GOAT_HORN);
        MusicInstrumentMeta templateMeta = (MusicInstrumentMeta) template.getItemMeta();
        templateMeta.setInstrument(MusicInstrument.SING_GOAT_HORN);
        template.setItemMeta(templateMeta);
        MusicInstrumentMeta copied = (MusicInstrumentMeta) copy(source, template).getItemMeta();
        check(copied.getInstrument().getKey().equals(MusicInstrument.DREAM_GOAT_HORN.getKey()),
                "Goat horn instrument was not inherited");
        check(((MusicInstrumentMeta) template.getItemMeta()).getInstrument().getKey()
                        .equals(MusicInstrument.SING_GOAT_HORN.getKey()),
                "Goat horn copy mutated the result template");
    }

    static void verifyOminousBottleLevel() {
        ItemStack source = new ItemStack(Material.OMINOUS_BOTTLE);
        OminousBottleMeta sourceMeta = (OminousBottleMeta) source.getItemMeta();
        sourceMeta.setAmplifier(4);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.OMINOUS_BOTTLE);
        OminousBottleMeta templateMeta = (OminousBottleMeta) template.getItemMeta();
        templateMeta.setAmplifier(1);
        template.setItemMeta(templateMeta);
        OminousBottleMeta copied = (OminousBottleMeta) copy(source, template).getItemMeta();
        check(copied.getAmplifier() == 4, "Ominous bottle level was not inherited");
        check(((OminousBottleMeta) template.getItemMeta()).getAmplifier() == 1,
                "Ominous bottle copy mutated the result template");
    }

    static void verifyMapAppearance() {
        ItemStack source = new ItemStack(Material.FILLED_MAP);
        MapMeta sourceMeta = (MapMeta) source.getItemMeta();
        sourceMeta.setMapView(Bukkit.createMap(Bukkit.getWorlds().get(0)));
        sourceMeta.setScaling(true);
        MapLocationLabel.write(sourceMeta, "RecipeWeaver base");
        sourceMeta.setColor(Color.fromRGB(0x226688));
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.FILLED_MAP);
        MapMeta templateMeta = (MapMeta) template.getItemMeta();
        templateMeta.setMapView(Bukkit.createMap(Bukkit.getWorlds().get(0)));
        MapLocationLabel.write(templateMeta, "Wrong location");
        templateMeta.setColor(Color.RED);
        template.setItemMeta(templateMeta);
        MapMeta copied = (MapMeta) copy(source, template).getItemMeta();
        MapMeta persistedSource = (MapMeta) source.getItemMeta();
        check("RecipeWeaver base".equals(persistedSource.getPersistentDataContainer().get(
                        MapLocationLabel.STORAGE_KEY, PersistentDataType.STRING))
                        && "Wrong location".equals(template.getItemMeta()
                                .getPersistentDataContainer().get(
                                        MapLocationLabel.STORAGE_KEY, PersistentDataType.STRING)),
                "Map appearance fixtures lost their typed location labels");
        check(copied.isScaling()
                        && "RecipeWeaver base".equals(MapLocationLabel.read(copied))
                        && "RecipeWeaver base".equals(copied.getPersistentDataContainer().get(
                                MapLocationLabel.STORAGE_KEY, PersistentDataType.STRING))
                        && Color.fromRGB(0x226688).equals(copied.getColor())
                        && copied.hasMapView() && copied.getMapView().getId()
                            == persistedSource.getMapView().getId(),
                "Map scaling, location name, or color was not inherited: source="
                        + persistedSource.isScaling() + "/"
                        + MapLocationLabel.read(persistedSource) + "/"
                        + persistedSource.getColor() + ", result="
                        + copied.isScaling() + "/" + MapLocationLabel.read(copied) + "/"
                        + copied.getColor());
        check(Color.RED.equals(((MapMeta) template.getItemMeta()).getColor()),
                "Map copy mutated the result template");
        MapMeta withoutView = (MapMeta) copy(new ItemStack(Material.FILLED_MAP),
                template).getItemMeta();
        check(!withoutView.hasMapView(),
                "A source without a map view retained the template map view");
        check(MapLocationLabel.read(withoutView) == null,
                "A source without a map label retained the template label");
        check(((MapMeta) template.getItemMeta()).hasMapView(),
                "Clearing copied map view mutated the result template");
    }

    static void verifyBlockItemState() {
        ItemStack source = new ItemStack(Material.OAK_STAIRS);
        BlockDataMeta sourceMeta = (BlockDataMeta) source.getItemMeta();
        Stairs sourceData = (Stairs) Bukkit.createBlockData(Material.OAK_STAIRS);
        sourceData.setFacing(BlockFace.EAST);
        sourceData.setHalf(Stairs.Half.TOP);
        sourceMeta.setBlockData(sourceData);
        source.setItemMeta(sourceMeta);
        ItemStack template = new ItemStack(Material.OAK_STAIRS);
        BlockDataMeta templateMeta = (BlockDataMeta) template.getItemMeta();
        Stairs templateData = (Stairs) Bukkit.createBlockData(Material.OAK_STAIRS);
        templateData.setFacing(BlockFace.NORTH);
        templateMeta.setBlockData(templateData);
        template.setItemMeta(templateMeta);
        BlockDataMeta copied = (BlockDataMeta) copy(source, template).getItemMeta();
        Stairs inherited = (Stairs) copied.getBlockData(Material.OAK_STAIRS);
        check(inherited.getFacing() == BlockFace.EAST && inherited.getHalf() == Stairs.Half.TOP,
                "Placeable block-item state was not inherited");
        check(((Stairs) ((BlockDataMeta) template.getItemMeta())
                        .getBlockData(Material.OAK_STAIRS)).getFacing() == BlockFace.NORTH,
                "Block-item state copy mutated the result template");
        BlockDataMeta withoutState = (BlockDataMeta) copy(new ItemStack(Material.OAK_STAIRS),
                template).getItemMeta();
        check(!withoutState.hasBlockData(),
                "A source without block-item state retained the template block data");
        check(((BlockDataMeta) template.getItemMeta()).hasBlockData(),
                "Clearing copied block data mutated the result template");
    }

    private static ItemStack copy(ItemStack source, ItemStack template) {
        return copy(source, template, ResultMetadataMode.ALL_COMMON);
    }

    private static ItemStack copy(ItemStack source, ItemStack template, ResultMetadataMode mode) {
        try {
            Class<?> metadata = Class.forName("dev.recipeweaver.core.GridResultMetadata");
            var method = metadata.getDeclaredMethod("copyFrom", ItemStack.class,
                    ItemStack.class, ResultMetadataMode.class);
            method.setAccessible(true);
            return (ItemStack) method.invoke(null, source, template, mode);
        } catch (InvocationTargetException ex) {
            throw new IllegalStateException("Live armor trim result copy failed", ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not invoke result metadata copy", ex);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
