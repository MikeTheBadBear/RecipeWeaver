package dev.recipeweaver.core;

import dev.recipeweaver.api.GridRecipeDefinition;
import dev.recipeweaver.api.MapLocationLabel;
import dev.recipeweaver.api.ResultMetadataMode;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.AxolotlBucketMeta;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.BlockDataMeta;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.FireworkEffectMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.inventory.meta.SuspiciousStewMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.TropicalFishBucketMeta;
import org.bukkit.inventory.meta.WritableBookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.KnowledgeBookMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.inventory.meta.MusicInstrumentMeta;
import org.bukkit.inventory.meta.OminousBottleMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** Copies selected metadata from the actual matched ingredient into a fresh result stack. */
final class GridResultMetadata {
    private static final List<PersistentDataType<?, ?>> KNOWN_TYPES = List.of(
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

    private GridResultMetadata() { }

    static ItemStack apply(GridRecipeDefinition recipe, GridMatcher.MatchPlan plan,
                           List<ItemStack> actual, ItemStack template) {
        ItemStack result = template.clone();
        ResultMetadataMode mode = recipe.resultMetadataMode();
        if (mode == ResultMetadataMode.NONE) return result;
        int[] sources = plan.sourceSlots();
        if (sources.length != actual.size()) throw new IllegalArgumentException("Wrong match plan size");
        for (int slot = 0; slot < sources.length; slot++) {
            if (sources[slot] != recipe.resultCopySourceSlot()) continue;
            ItemStack input = actual.get(slot);
            if (!GridMatcher.present(input)) break;
            return copyFrom(input, result, mode);
        }
        return result;
    }

    /** Copies metadata from one actual ingredient without changing either input stack. */
    static ItemStack copyFrom(ItemStack input, ItemStack template, ResultMetadataMode mode) {
        ItemStack result = template.clone();
        if (mode == ResultMetadataMode.NONE) return result;
        if (!GridMatcher.present(input)) throw new IllegalArgumentException("Metadata source is empty");
        if (mode == ResultMetadataMode.ALL_ITEM_DATA) {
            if (input.getType() != result.getType()) {
                throw new IllegalArgumentException("Full item data copy requires the same source and result material");
            }
            ItemStack copied = input.clone();
            copied.setAmount(result.getAmount());
            return copied;
        }
        ItemMeta source = input.getItemMeta();
        ItemMeta target = result.getItemMeta();
        if (source == null || target == null) {
            throw new IllegalStateException("Metadata source or result does not support item data");
        }
        if (mode.copyName()) {
            target.setDisplayName(source.hasDisplayName() ? source.getDisplayName() : null);
            target.setItemName(source.hasItemName() ? source.getItemName() : null);
        }
        if (mode.copyLore()) {
            target.setLore(source.hasLore() ? source.getLore() : null);
        }
        if (mode.copyCustomData()) {
            copyCustomData(source.getPersistentDataContainer(),
                    target.getPersistentDataContainer());
        }
        if (mode.copyEnchantments()) copyEnchantments(source, target);
        if (mode.copyModel()) {
            copyCustomModelData(source, target);
            copyModernComponent(source, target, "ItemModel", NamespacedKey.class);
        }
        if (mode == ResultMetadataMode.FOOD_ONLY) copyFoodComponent(source, target);
        if (mode == ResultMetadataMode.TOOL_ONLY) copyToolComponent(source, target);
        if (mode == ResultMetadataMode.ATTRIBUTE_MODIFIERS_ONLY) {
            copyAttributeModifiers(source, target);
        }
        if (mode == ResultMetadataMode.DAMAGE_ONLY) {
            copyDamage(source, target, result.getType());
        }
        if (mode == ResultMetadataMode.ITEM_FLAGS_ONLY) copyItemFlags(source, target);
        if (mode == ResultMetadataMode.UNBREAKABLE_ONLY) {
            target.setUnbreakable(source.isUnbreakable());
        }
        if (mode == ResultMetadataMode.ARMOR_TRIM_ONLY) copyArmorTrim(source, target);
        if (mode == ResultMetadataMode.POTION_ONLY) copyPotionFields(source, target);
        if (mode == ResultMetadataMode.POTION_BASE_ONLY) copyPotionBase(source, target);
        if (mode == ResultMetadataMode.POTION_COLOR_ONLY && target instanceof PotionMeta potion) {
            PotionMeta from = sourceOrDefault(source, Material.POTION, PotionMeta.class);
            potion.setColor(from.hasColor() ? from.getColor() : null);
        }
        if (mode == ResultMetadataMode.STORED_ENCHANTMENTS_ONLY) {
            copyStoredEnchantments(source, target);
        }
        if (mode == ResultMetadataMode.BANNER_PATTERNS_ONLY) {
            copyBannerPatterns(source, target);
        }
        if (mode == ResultMetadataMode.CROSSBOW_PROJECTILES_ONLY) {
            copyCrossbowProjectiles(source, target);
        }
        if (mode == ResultMetadataMode.FIREWORK_ONLY) copyFireworkPayload(source, target);
        if (mode == ResultMetadataMode.BUNDLE_CONTENTS_ONLY) copyBundleContents(source, target);
        if (mode == ResultMetadataMode.FIRE_RESISTANT_ONLY) {
            copyFireResistance(source, target);
        }
        if (mode == ResultMetadataMode.HIDE_TOOLTIP_ONLY) {
            copyHideTooltip(source, target);
        }
        if (mode == ResultMetadataMode.MAX_STACK_SIZE_ONLY) {
            Integer maximum = source.hasMaxStackSize() ? source.getMaxStackSize() : null;
            if (maximum != null && result.getAmount() > maximum) {
                throw new IllegalArgumentException("Result amount exceeds copied maximum stack size");
            }
            target.setMaxStackSize(maximum);
        }
        if (mode == ResultMetadataMode.RARITY_ONLY) {
            copyRarity(source, target);
        }
        if (mode == ResultMetadataMode.REPAIR_COST_ONLY) {
            if (target instanceof Repairable repair) {
                repair.setRepairCost(source instanceof Repairable sourceRepair
                        ? sourceRepair.getRepairCost() : 0);
            }
        }
        if (mode == ResultMetadataMode.LEATHER_COLOR_ONLY) {
            if (target instanceof LeatherArmorMeta leather) {
                leather.setColor(source instanceof LeatherArmorMeta sourceLeather
                        ? sourceLeather.getColor() : null);
            }
        }
        if (mode == ResultMetadataMode.SUSPICIOUS_STEW_ONLY) {
            if (target instanceof SuspiciousStewMeta stew) {
                stew.clearCustomEffects();
                if (source instanceof SuspiciousStewMeta sourceStew) {
                    for (var effect : sourceStew.getCustomEffects()) {
                        stew.addCustomEffect(effect, true);
                    }
                }
            }
        }
        if (mode == ResultMetadataMode.WRITTEN_BOOK_ONLY && target instanceof BookMeta book) {
            BookMeta from = sourceOrDefault(source, Material.WRITTEN_BOOK, BookMeta.class);
            book.setTitle(from.hasTitle() ? from.getTitle() : null);
            book.setAuthor(from.hasAuthor() ? from.getAuthor() : null);
            book.setPages(from.getPages());
            book.setGeneration(from.getGeneration());
        }
        if (mode == ResultMetadataMode.WRITTEN_BOOK_TITLE_ONLY
                && target instanceof BookMeta book) {
            BookMeta from = sourceOrDefault(source, Material.WRITTEN_BOOK, BookMeta.class);
            book.setTitle(from.hasTitle() ? from.getTitle() : null);
        }
        if (mode == ResultMetadataMode.WRITTEN_BOOK_AUTHOR_ONLY
                && target instanceof BookMeta book) {
            BookMeta from = sourceOrDefault(source, Material.WRITTEN_BOOK, BookMeta.class);
            book.setAuthor(from.hasAuthor() ? from.getAuthor() : null);
        }
        if (mode == ResultMetadataMode.WRITABLE_BOOK_ONLY
                && target instanceof WritableBookMeta book) {
            WritableBookMeta from = sourceOrDefault(source, Material.WRITABLE_BOOK,
                    WritableBookMeta.class);
            book.setPages(from.getPages());
        }
        if (mode == ResultMetadataMode.COMPASS_ONLY && target instanceof CompassMeta compass) {
            CompassMeta from = sourceOrDefault(source, Material.COMPASS, CompassMeta.class);
            compass.setLodestone(from.hasLodestone() ? from.getLodestone() : null);
            compass.setLodestoneTracked(from.isLodestoneTracked());
        }
        if (mode == ResultMetadataMode.MAP_COLOR_ONLY && target instanceof MapMeta map) {
            MapMeta from = sourceOrDefault(source, Material.FILLED_MAP, MapMeta.class);
            map.setColor(from.hasColor() ? from.getColor() : null);
        }
        if (mode == ResultMetadataMode.MAP_SCALING_ONLY && target instanceof MapMeta map) {
            MapMeta from = sourceOrDefault(source, Material.FILLED_MAP, MapMeta.class);
            map.setScaling(from.isScaling());
        }
        if (mode == ResultMetadataMode.MAP_LOCATION_NAME_ONLY && target instanceof MapMeta map) {
            MapMeta from = sourceOrDefault(source, Material.FILLED_MAP, MapMeta.class);
            MapLocationLabel.write(map, MapLocationLabel.read(from));
        }
        if (mode == ResultMetadataMode.TROPICAL_FISH_ONLY
                && target instanceof TropicalFishBucketMeta fish) {
            TropicalFishBucketMeta from = sourceOrDefault(source,
                    Material.TROPICAL_FISH_BUCKET, TropicalFishBucketMeta.class);
            fish.setPattern(from.getPattern());
            fish.setPatternColor(from.getBodyColor());
            fish.setBodyColor(from.getBodyColor());
            fish.setPatternColor(from.getPatternColor());
        }
        if (mode == ResultMetadataMode.KNOWLEDGE_BOOK_ONLY
                && target instanceof KnowledgeBookMeta book) {
            KnowledgeBookMeta from = sourceOrDefault(source, Material.KNOWLEDGE_BOOK,
                    KnowledgeBookMeta.class);
            book.setRecipes(from.getRecipes());
        }
        if (mode == ResultMetadataMode.HEAD_SOUND_ONLY && target instanceof SkullMeta head) {
            SkullMeta from = sourceOrDefault(source, Material.PLAYER_HEAD, SkullMeta.class);
            head.setNoteBlockSound(from.getNoteBlockSound());
        }
        if (mode.copyCommonProperties()) {
            copyItemFlags(source, target);
            target.setUnbreakable(source.isUnbreakable());
            setFireResistance(target, source.isFireResistant());
            copyDamageResistance(source, target);
            target.setHideTooltip(source.isHideTooltip());
            target.setMaxStackSize(source.hasMaxStackSize() ? source.getMaxStackSize() : null);
            target.setRarity(source.hasRarity() ? source.getRarity() : null);
            copyFoodComponent(source, target);
            copyToolComponent(source, target);
            copyAttributeModifiers(source, target);
            copyModernComponent(source, target, "TooltipStyle", NamespacedKey.class);
            copyModernComponent(source, target, "Enchantable", Integer.class);
            copyModernBoolean(source, target, "Glider");
            copyAdditionalTradeCost(source, target);
            copyModernComponent(source, target, "MinimumAttackCharge", Float.class);
            copyNullableComponent(source, target, ItemMeta.class, "BreakSound");
            copyNullableComponent(source, target, ItemMeta.class, "UseRemainder");
            copyNullableComponent(source, target, ItemMeta.class, "UseCooldown");
            copyNullableComponent(source, target, ItemMeta.class, "Equippable");
            copyNullableComponent(source, target, ItemMeta.class, "JukeboxPlayable");
            if (source instanceof ArmorMeta sourceArmor && target instanceof ArmorMeta targetArmor) {
                targetArmor.setTrim(sourceArmor.hasTrim() ? sourceArmor.getTrim() : null);
            }
            if (source instanceof LeatherArmorMeta sourceLeather
                    && target instanceof LeatherArmorMeta targetLeather) {
                targetLeather.setColor(sourceLeather.getColor());
            }
            if (source instanceof BannerMeta sourceBanner && target instanceof BannerMeta targetBanner) {
                copyBannerPatterns(sourceBanner, targetBanner);
            }
            if (source instanceof PotionMeta sourcePotion && target instanceof PotionMeta targetPotion) {
                copyPotionFields(sourcePotion, targetPotion);
            }
            if (source instanceof EnchantmentStorageMeta sourceBook
                    && target instanceof EnchantmentStorageMeta targetBook) {
                copyStoredEnchantments(sourceBook, targetBook);
            }
            if (source instanceof CrossbowMeta sourceCrossbow
                    && target instanceof CrossbowMeta targetCrossbow) {
                copyCrossbowProjectiles(sourceCrossbow, targetCrossbow);
            }
            if (source instanceof SuspiciousStewMeta sourceStew
                    && target instanceof SuspiciousStewMeta targetStew) {
                targetStew.clearCustomEffects();
                for (var effect : sourceStew.getCustomEffects()) {
                    targetStew.addCustomEffect(effect, true);
                }
            }
            if (source instanceof FireworkMeta sourceFirework
                    && target instanceof FireworkMeta targetFirework) {
                copyFireworkPayload(sourceFirework, targetFirework);
            }
            if (source instanceof FireworkEffectMeta sourceStar
                    && target instanceof FireworkEffectMeta targetStar) {
                copyFireworkPayload(sourceStar, targetStar);
            }
            if (source instanceof WritableBookMeta sourceBook
                    && target instanceof WritableBookMeta targetBook) {
                targetBook.setPages(sourceBook.getPages());
            }
            if (source instanceof BookMeta sourceBook && target instanceof BookMeta targetBook) {
                targetBook.setTitle(sourceBook.hasTitle() ? sourceBook.getTitle() : null);
                targetBook.setAuthor(sourceBook.hasAuthor() ? sourceBook.getAuthor() : null);
                targetBook.setGeneration(sourceBook.getGeneration());
            }
            if (input.getType() == Material.PLAYER_HEAD && result.getType() == Material.PLAYER_HEAD
                    && source instanceof SkullMeta sourceSkull && target instanceof SkullMeta targetSkull) {
                var profile = sourceSkull.getOwnerProfile();
                targetSkull.setOwnerProfile(profile == null ? null : profile.clone());
                targetSkull.setNoteBlockSound(sourceSkull.getNoteBlockSound());
            }
            if (source instanceof CompassMeta sourceCompass
                    && target instanceof CompassMeta targetCompass) {
                targetCompass.setLodestone(sourceCompass.hasLodestone()
                        ? sourceCompass.getLodestone() : null);
                targetCompass.setLodestoneTracked(sourceCompass.isLodestoneTracked());
            }
            if (source instanceof BundleMeta sourceBundle
                    && target instanceof BundleMeta targetBundle) {
                copyBundleContents(sourceBundle, targetBundle);
            }
            if (source instanceof AxolotlBucketMeta sourceAxolotl
                    && target instanceof AxolotlBucketMeta targetAxolotl) {
                if (sourceAxolotl.hasVariant()) {
                    targetAxolotl.setVariant(sourceAxolotl.getVariant());
                } else if (targetAxolotl.hasVariant()) {
                    ItemMeta common = Bukkit.getItemFactory().asMetaFor(target, Material.STONE);
                    target = Bukkit.getItemFactory().asMetaFor(common, result.getType());
                    if (!(target instanceof AxolotlBucketMeta cleared) || cleared.hasVariant()) {
                        throw new IllegalStateException("Could not clear result axolotl variant");
                    }
                }
            }
            if (source instanceof KnowledgeBookMeta sourceKnowledge
                    && target instanceof KnowledgeBookMeta targetKnowledge) {
                targetKnowledge.setRecipes(sourceKnowledge.getRecipes());
            }
            if (source instanceof MusicInstrumentMeta sourceHorn
                    && target instanceof MusicInstrumentMeta targetHorn) {
                targetHorn.setInstrument(sourceHorn.getInstrument());
            }
            if (source instanceof OminousBottleMeta sourceBottle
                    && target instanceof OminousBottleMeta targetBottle) {
                targetBottle.setAmplifier(sourceBottle.hasAmplifier()
                        ? sourceBottle.getAmplifier() : 0);
            }
            if (source instanceof MapMeta sourceMap && target instanceof MapMeta targetMap) {
                targetMap.setScaling(sourceMap.isScaling());
                MapLocationLabel.write(targetMap, MapLocationLabel.read(sourceMap));
                targetMap.setColor(sourceMap.hasColor() ? sourceMap.getColor() : null);
                targetMap.setMapView(sourceMap.hasMapView() ? sourceMap.getMapView() : null);
            }
            if (source instanceof BlockDataMeta sourceBlock
                    && target instanceof BlockDataMeta targetBlock) {
                targetBlock.setBlockData(sourceBlock.hasBlockData()
                        ? sourceBlock.getBlockData(input.getType()) : null);
            }
            if (source instanceof BlockStateMeta sourceBlock
                    && target instanceof BlockStateMeta targetBlock) {
                copyBlockEntityState(input, result, sourceBlock, targetBlock);
            }
            if (source instanceof TropicalFishBucketMeta sourceFish
                    && target instanceof TropicalFishBucketMeta targetFish) {
                targetFish.setPattern(sourceFish.getPattern());
                // The setter order matters on Spigot 1.20.6: setting the pattern
                // color can otherwise replace the chosen body color.
                targetFish.setPatternColor(sourceFish.getBodyColor());
                targetFish.setBodyColor(sourceFish.getBodyColor());
                targetFish.setPatternColor(sourceFish.getPatternColor());
            }
            copyDamage(source, target, result.getType());
            if (source instanceof Repairable sourceRepair && target instanceof Repairable targetRepair) {
                targetRepair.setRepairCost(sourceRepair.getRepairCost());
            }
        }
        if (!result.setItemMeta(target)) {
            throw new IllegalStateException("Result metadata is incompatible with the output item");
        }
        if (mode.copyCommonProperties()) FoodComponentBridge.copyConsumption(input, result);
        return result;
    }

    private static <T extends ItemMeta> T sourceOrDefault(ItemMeta source, Material material,
                                                            Class<T> type) {
        if (type.isInstance(source)) return type.cast(source);
        ItemMeta empty = new ItemStack(material).getItemMeta();
        if (!type.isInstance(empty)) {
            throw new IllegalStateException("No default item data for " + material);
        }
        return type.cast(empty);
    }

    static void copyFireResistance(ItemMeta source, ItemMeta target) {
        setFireResistance(target, source.isFireResistant());
    }

    /** Spigot 26.2's deprecated boolean setter ignores false; clear its public tag instead. */
    static void setFireResistance(ItemMeta target, boolean resistant) {
        target.setFireResistant(resistant);
        if (resistant || !target.isFireResistant()) return;
        try {
            var setter = ItemMeta.class.getMethod("setDamageResistant", org.bukkit.Tag.class);
            setter.invoke(target, (Object) null);
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("Server cannot clear item fire resistance", ex);
        } catch (IllegalAccessException | InvocationTargetException ex) {
            throw new IllegalStateException("Could not clear item fire resistance", ex);
        }
        if (target.isFireResistant()) {
            throw new IllegalStateException("Server retained item fire resistance after clearing");
        }
    }

    static void copyHideTooltip(ItemMeta source, ItemMeta target) {
        target.setHideTooltip(source.isHideTooltip());
    }

    static void copyRarity(ItemMeta source, ItemMeta target) {
        target.setRarity(source.hasRarity() ? source.getRarity() : null);
    }

    static void copyEnchantments(ItemMeta source, ItemMeta target) {
        for (var enchantment : target.getEnchants().keySet()) target.removeEnchant(enchantment);
        for (var enchantment : source.getEnchants().entrySet()) {
            if (!target.addEnchant(enchantment.getKey(), enchantment.getValue(), true)) {
                throw new IllegalStateException("Result cannot inherit source enchantment");
            }
        }
        target.setEnchantmentGlintOverride(source.hasEnchantmentGlintOverride()
                ? source.getEnchantmentGlintOverride() : null);
    }

    static void copyFoodComponent(ItemMeta source, ItemMeta target) {
        target.setFood(source.hasFood() ? source.getFood() : null);
    }

    static void copyToolComponent(ItemMeta source, ItemMeta target) {
        target.setTool(source.hasTool() ? source.getTool() : null);
    }

    static void copyAttributeModifiers(ItemMeta source, ItemMeta target) {
        target.setAttributeModifiers(source.hasAttributeModifiers()
                ? source.getAttributeModifiers() : null);
    }

    static void copyItemFlags(ItemMeta source, ItemMeta target) {
        target.removeItemFlags(target.getItemFlags().toArray(ItemFlag[]::new));
        target.addItemFlags(source.getItemFlags().toArray(ItemFlag[]::new));
    }

    static void copyArmorTrim(ItemMeta source, ItemMeta target) {
        if (target instanceof ArmorMeta targetArmor) {
            targetArmor.setTrim(source instanceof ArmorMeta sourceArmor && sourceArmor.hasTrim()
                    ? sourceArmor.getTrim() : null);
        }
    }

    static void copyPotionFields(ItemMeta source, ItemMeta target) {
        if (!(target instanceof PotionMeta targetPotion)) return;
        PotionMeta sourcePotion = source instanceof PotionMeta potion ? potion : null;
        targetPotion.setBasePotionType(sourcePotion != null && sourcePotion.hasBasePotionType()
                ? sourcePotion.getBasePotionType() : org.bukkit.potion.PotionType.WATER);
        targetPotion.setColor(sourcePotion != null && sourcePotion.hasColor()
                ? sourcePotion.getColor() : null);
        targetPotion.clearCustomEffects();
        if (sourcePotion != null) {
            for (var effect : sourcePotion.getCustomEffects()) {
                targetPotion.addCustomEffect(effect, true);
            }
        }
    }

    static void copyPotionBase(ItemMeta source, ItemMeta target) {
        if (!(target instanceof PotionMeta targetPotion)) return;
        PotionMeta sourcePotion = source instanceof PotionMeta potion ? potion : null;
        targetPotion.setBasePotionType(sourcePotion != null && sourcePotion.hasBasePotionType()
                ? sourcePotion.getBasePotionType() : org.bukkit.potion.PotionType.WATER);
    }

    static void copyStoredEnchantments(ItemMeta source, ItemMeta target) {
        if (!(target instanceof EnchantmentStorageMeta targetBook)) return;
        for (var enchantment : List.copyOf(targetBook.getStoredEnchants().keySet())) {
            targetBook.removeStoredEnchant(enchantment);
        }
        if (source instanceof EnchantmentStorageMeta sourceBook) {
            for (var enchantment : sourceBook.getStoredEnchants().entrySet()) {
                if (!targetBook.addStoredEnchant(enchantment.getKey(), enchantment.getValue(), true)) {
                    throw new IllegalStateException("Result cannot inherit stored enchantment");
                }
            }
        }
    }

    static void copyBannerPatterns(ItemMeta source, ItemMeta target) {
        if (target instanceof BannerMeta targetBanner) {
            targetBanner.setPatterns(source instanceof BannerMeta sourceBanner
                    ? sourceBanner.getPatterns() : List.of());
        }
    }

    static void copyCrossbowProjectiles(ItemMeta source, ItemMeta target) {
        if (target instanceof CrossbowMeta targetCrossbow) {
            targetCrossbow.setChargedProjectiles(source instanceof CrossbowMeta sourceCrossbow
                    ? sourceCrossbow.getChargedProjectiles().stream().map(ItemStack::clone).toList()
                    : List.of());
        }
    }

    static void copyFireworkPayload(ItemMeta source, ItemMeta target) {
        if (target instanceof FireworkMeta targetRocket) {
            FireworkMeta sourceRocket = source instanceof FireworkMeta rocket ? rocket : null;
            targetRocket.setPower(sourceRocket == null ? 0 : sourceRocket.getPower());
            targetRocket.clearEffects();
            if (sourceRocket != null) targetRocket.addEffects(sourceRocket.getEffects());
        } else if (target instanceof FireworkEffectMeta targetStar) {
            FireworkEffectMeta sourceStar = source instanceof FireworkEffectMeta star ? star : null;
            targetStar.setEffect(sourceStar != null && sourceStar.hasEffect()
                    ? sourceStar.getEffect() : null);
        }
    }

    static void copyBundleContents(ItemMeta source, ItemMeta target) {
        if (target instanceof BundleMeta targetBundle) {
            targetBundle.setItems(source instanceof BundleMeta sourceBundle
                    ? sourceBundle.getItems().stream().map(ItemStack::clone).toList()
                    : List.of());
        }
    }

    static void copyDamage(ItemMeta source, ItemMeta target, Material resultMaterial) {
        if (!(target instanceof Damageable targetDamage)) return;
        if (!(source instanceof Damageable sourceDamage)) {
            targetDamage.setMaxDamage(null);
            targetDamage.setDamage(0);
            return;
        }
        targetDamage.setMaxDamage(sourceDamage.hasMaxDamage()
                ? sourceDamage.getMaxDamage() : null);
        int maximum = targetDamage.hasMaxDamage() ? targetDamage.getMaxDamage()
                : resultMaterial.getMaxDurability();
        targetDamage.setDamage(sourceDamage.getDamage() < maximum
                ? sourceDamage.getDamage() : 0);
    }

    private static void copyBlockEntityState(ItemStack input, ItemStack result,
                                             BlockStateMeta source, BlockStateMeta target) {
        BlockState sourceState = source.getBlockState();
        if (input.getType() == result.getType()) {
            // getBlockState returns a snapshot, including a fresh empty state when
            // the source has none. Replacing the target snapshot clears old data.
            target.setBlockState(sourceState.copy());
            return;
        }
        BlockState targetState = target.getBlockState();
        if (sourceState instanceof ShulkerBox sourceBox
                && targetState instanceof ShulkerBox targetBox) {
            // A color change must retain the result's shulker material. Populate
            // its own snapshot rather than embedding the source color's state.
            copyShulkerState(sourceBox, targetBox);
            target.setBlockState(targetBox);
        }
    }

    static void copyShulkerState(ShulkerBox source, ShulkerBox target) {
        copyShulkerInventory(source.getSnapshotInventory(), target.getSnapshotInventory());
        target.setCustomName(source.getCustomName());
        String lock = source.getLock();
        // Spigot 26.2 reports an unlocked container as "", but setLock("")
        // attempts to construct a lock with a null custom-name component.
        target.setLock(lock == null || lock.isEmpty() ? null : lock);
    }

    static void copyShulkerInventory(Inventory source, Inventory target) {
        if (source.getSize() != 27 || target.getSize() != 27) {
            throw new IllegalArgumentException("Shulker inventories must have 27 slots");
        }
        ItemStack[] contents = source.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            target.setItem(slot, contents[slot] == null ? null : contents[slot].clone());
        }
    }

    static void copyCustomData(PersistentDataContainer source,
                                       PersistentDataContainer target) {
        // Result-copy modes replace the source's custom-data collection as a whole.
        // Otherwise a template-only key survives even when the source omits it.
        for (NamespacedKey key : List.copyOf(target.getKeys())) target.remove(key);
        try {
            var copyTo = PersistentDataContainer.class.getMethod(
                    "copyTo", PersistentDataContainer.class, boolean.class);
            copyTo.invoke(source, target, true);
            return;
        } catch (NoSuchMethodException ignored) {
            // Older supported Bukkit APIs need the primitive-type fallback below.
        } catch (IllegalAccessException | InvocationTargetException ex) {
            throw new IllegalStateException("Could not copy custom item data", ex);
        }
        for (NamespacedKey key : source.getKeys()) {
            boolean copied = false;
            for (PersistentDataType<?, ?> type : KNOWN_TYPES) {
                if (copyValue(source, target, key, type)) {
                    copied = true;
                    break;
                }
            }
            if (!copied) throw new IllegalStateException("Unsupported custom item data type: " + key);
        }
    }

    /** Newer Bukkit servers store model data as four lists, not one legacy integer. */
    private static void copyCustomModelData(ItemMeta source, ItemMeta target) {
        try {
            var has = ItemMeta.class.getMethod("hasCustomModelDataComponent");
            var get = ItemMeta.class.getMethod("getCustomModelDataComponent");
            var set = ItemMeta.class.getMethod("setCustomModelDataComponent", get.getReturnType());
            Object component = Boolean.TRUE.equals(has.invoke(source)) ? get.invoke(source) : null;
            set.invoke(target, component);
        } catch (NoSuchMethodException ignored) {
            // 1.20.6 only publishes the integer form.
            target.setCustomModelData(source.hasCustomModelData()
                    ? source.getCustomModelData() : null);
        } catch (IllegalAccessException | InvocationTargetException ex) {
            throw new IllegalStateException("Could not copy custom model data component", ex);
        }
    }

    /** Copies a nullable public component when the running Bukkit API publishes it. */
    static void copyNullableComponent(Object source, Object target, Class<?> api, String suffix) {
        java.lang.reflect.Method has;
        try {
            has = api.getMethod("has" + suffix);
        } catch (NoSuchMethodException ignored) {
            return;
        }
        try {
            var get = api.getMethod("get" + suffix);
            var set = api.getMethod("set" + suffix, get.getReturnType());
            Object value = Boolean.TRUE.equals(has.invoke(source)) ? get.invoke(source) : null;
            if (value instanceof ItemStack item) value = item.clone();
            set.invoke(target, value);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ex) {
            throw new IllegalStateException("Could not copy result " + suffix + " component", ex);
        }
    }

    /** Copies newer nullable Bukkit components only when the running server publishes them. */
    private static void copyModernComponent(ItemMeta source, ItemMeta target,
                                            String suffix, Class<?> setterType) {
        try {
            var has = ItemMeta.class.getMethod("has" + suffix);
            var get = ItemMeta.class.getMethod("get" + suffix);
            var set = ItemMeta.class.getMethod("set" + suffix, setterType);
            Object value = Boolean.TRUE.equals(has.invoke(source)) ? get.invoke(source) : null;
            set.invoke(target, (Object) value);
        } catch (NoSuchMethodException ignored) {
            // The 1.20.6 API does not expose these later data components.
        } catch (IllegalAccessException | InvocationTargetException ex) {
            throw new IllegalStateException("Could not copy result " + suffix + " component", ex);
        }
    }

    /** Some newer boolean properties have no has/get pair or nullable state. */
    private static void copyModernBoolean(ItemMeta source, ItemMeta target, String suffix) {
        try {
            var get = ItemMeta.class.getMethod("is" + suffix);
            var set = ItemMeta.class.getMethod("set" + suffix, boolean.class);
            set.invoke(target, get.invoke(source));
        } catch (NoSuchMethodException ignored) {
            // The 1.20.6 API does not expose this later property.
        } catch (IllegalAccessException | InvocationTargetException ex) {
            throw new IllegalStateException("Could not copy result " + suffix + " property", ex);
        }
    }

    /** Spigot 26.2's public trade-cost getter reads max-stack-size by mistake. */
    private static void copyAdditionalTradeCost(ItemMeta source, ItemMeta target) {
        try {
            var has = ItemMeta.class.getMethod("hasAdditionalTradeCost");
            var set = ItemMeta.class.getMethod("setAdditionalTradeCost", Integer.class);
            Integer cost = null;
            if (Boolean.TRUE.equals(has.invoke(source))) {
                Object encoded = source.serialize().get("additional-trade-cost");
                if (!(encoded instanceof Integer value)) {
                    throw new IllegalStateException("Could not read source additional trade cost");
                }
                cost = value;
            }
            set.invoke(target, cost);
        } catch (NoSuchMethodException ignored) {
            // This property is absent on 1.20.6.
        } catch (IllegalAccessException | InvocationTargetException ex) {
            throw new IllegalStateException("Could not copy result additional trade cost", ex);
        }
    }

    /** Copy tag-backed resistance on all modern servers and explicit sets through their public APIs. */
    private static void copyDamageResistance(ItemMeta source, ItemMeta target) {
        try {
            var has = ItemMeta.class.getMethod("hasDamageResistant");
            var getTag = ItemMeta.class.getMethod("getDamageResistant");
            var setTag = ItemMeta.class.getMethod("setDamageResistant", org.bukkit.Tag.class);
            if (!Boolean.TRUE.equals(has.invoke(source))) {
                setTag.invoke(target, (Object) null);
                return;
            }
            Object tag = getTag.invoke(source);
            if (tag != null) {
                setTag.invoke(target, tag);
                return;
            }
            // Paper exposes explicit damage sets as RegistryKeySet; Spigot exposes a Collection.
            try {
                var get = ItemMeta.class.getMethod("getDamageResistantTypes");
                var set = ItemMeta.class.getMethod("setDamageResistantTypes", get.getReturnType());
                set.invoke(target, get.invoke(source));
                return;
            } catch (NoSuchMethodException ignored) {
                // Use Spigot's public collection API below.
            }
            var get = ItemMeta.class.getMethod("getDamageResistances");
            var set = ItemMeta.class.getMethod("setDamageResistant", java.util.Collection.class);
            Object values = get.invoke(source);
            if (!(values instanceof java.util.Collection<?> collection)) {
                throw new IllegalStateException("Could not read source damage resistances");
            }
            set.invoke(target, List.copyOf(collection));
        } catch (NoSuchMethodException ignored) {
            // 1.20.6 predates the damage-resistance component.
        } catch (IllegalAccessException | InvocationTargetException ex) {
            throw new IllegalStateException("Could not copy result damage resistance", ex);
        }
    }


    private static <P, C> boolean copyValue(PersistentDataContainer source,
                                            PersistentDataContainer target,
                                            NamespacedKey key, PersistentDataType<P, C> type) {
        if (!source.has(key, type)) return false;
        C value = source.get(key, type);
        if (value == null) return false;
        target.set(key, type, value);
        return true;
    }
}
