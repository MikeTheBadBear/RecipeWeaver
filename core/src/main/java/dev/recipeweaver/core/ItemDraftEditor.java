package dev.recipeweaver.core;

import dev.recipeweaver.api.MapLocationLabel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HexFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Function;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.MalformedURLException;
import java.util.UUID;
import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.MusicInstrument;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.entity.TropicalFish;
import org.bukkit.entity.Axolotl;
import org.bukkit.FireworkEffect;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.AxolotlBucketMeta;
import org.bukkit.inventory.meta.BlockDataMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.KnowledgeBookMeta;
import org.bukkit.inventory.meta.MusicInstrumentMeta;
import org.bukkit.inventory.meta.OminousBottleMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.SuspiciousStewMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.TropicalFishBucketMeta;
import org.bukkit.inventory.meta.WritableBookMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.FireworkEffectMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.inventory.meta.components.FoodComponent;
import org.bukkit.inventory.meta.components.ToolComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

/** Validated edits shared by the custom-item GUI and server probes. */
final class ItemDraftEditor {
    private ItemDraftEditor() { }

    static void replaceLore(ItemStack item, String page) {
        if (page == null) throw new IllegalArgumentException("Enter lore text or none");
        ItemMeta meta = item.getItemMeta();
        if (page.strip().equalsIgnoreCase("none")) {
            meta.setLore(null);
        } else {
            String[] rawLines = page.replace("\r", "").split("\n", -1);
            List<String> lines = new ArrayList<>();
            for (String raw : rawLines) {
                if (raw.length() > 256) throw new IllegalArgumentException("Lore lines may have at most 256 characters");
                if (!raw.isBlank()) lines.add(ChatColor.translateAlternateColorCodes('&', raw));
            }
            if (lines.isEmpty() || lines.size() > 16) {
                throw new IllegalArgumentException("Lore needs 1 through 16 nonempty lines, or none");
            }
            meta.setLore(lines);
        }
        item.setItemMeta(meta);
    }

    static void setDamage(ItemStack item, String raw) {
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable damageable) || item.getType().getMaxDurability() <= 0) {
            throw new IllegalArgumentException("This item has no durability");
        }
        int maximum = damageable.hasMaxDamage() ? damageable.getMaxDamage()
                : item.getType().getMaxDurability();
        int value = parseInt(raw, "Damage must be 0 through " + (maximum - 1));
        if (value < 0 || value >= maximum) {
            throw new IllegalArgumentException("Damage must be 0 through "
                    + (maximum - 1));
        }
        damageable.setDamage(value);
        item.setItemMeta(meta);
    }

    static void setMaxDamage(ItemStack item, String raw) {
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable damageable) || item.getType().getMaxDurability() <= 0) {
            throw new IllegalArgumentException("This item has no durability");
        }
        int maximum;
        if (raw != null && raw.strip().equalsIgnoreCase("none")) {
            maximum = item.getType().getMaxDurability();
            if (damageable.getDamage() >= maximum) {
                throw new IllegalArgumentException("Reduce item damage before restoring default durability");
            }
            damageable.setMaxDamage(null);
        } else {
            maximum = parseInt(raw, "Maximum durability must be 1 through 1000000, or none");
            if (maximum < 1 || maximum > 1_000_000 || damageable.getDamage() >= maximum) {
                throw new IllegalArgumentException("Maximum durability must exceed current damage and be at most 1000000");
            }
            damageable.setMaxDamage(maximum);
        }
        item.setItemMeta(meta);
    }

    static void setEnchantment(ItemStack item, String raw) {
        String[] words = raw == null ? new String[0] : raw.strip().split("\\s+");
        if (words.length != 2) {
            throw new IllegalArgumentException("Enter namespace:enchantment level; use level 0 to remove");
        }
        NamespacedKey key = key(words[0]);
        Enchantment enchantment = Registry.ENCHANTMENT.get(key);
        if (enchantment == null) throw new IllegalArgumentException("Unknown enchantment: " + key);
        int level = parseInt(words[1], "Enchantment level must be 0 through 255");
        if (level < 0 || level > 255) {
            throw new IllegalArgumentException("Enchantment level must be 0 through 255");
        }
        ItemMeta meta = item.getItemMeta();
        if (level == 0) meta.removeEnchant(enchantment);
        else meta.addEnchant(enchantment, level, true);
        item.setItemMeta(meta);
    }

    static void setStoredEnchantment(ItemStack item, String raw) {
        if (!(item.getItemMeta() instanceof EnchantmentStorageMeta book)) {
            throw new IllegalArgumentException("Stored enchantments require an enchanted book");
        }
        String[] words = raw == null ? new String[0] : raw.strip().split("\\s+");
        if (words.length != 2) {
            throw new IllegalArgumentException("Enter namespace:enchantment level; use level 0 to remove");
        }
        NamespacedKey key = key(words[0]);
        Enchantment enchantment = Registry.ENCHANTMENT.get(key);
        if (enchantment == null) throw new IllegalArgumentException("Unknown enchantment: " + key);
        int level = parseInt(words[1], "Stored enchantment level must be 0 through 255");
        if (level < 0 || level > 255) {
            throw new IllegalArgumentException("Stored enchantment level must be 0 through 255");
        }
        if (level == 0) book.removeStoredEnchant(enchantment);
        else book.addStoredEnchant(enchantment, level, true);
        item.setItemMeta(book);
    }

    static void setTrim(ItemStack item, String raw) {
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof ArmorMeta armor)) {
            throw new IllegalArgumentException("This item cannot have an armor trim");
        }
        if (raw != null && raw.strip().equalsIgnoreCase("none")) {
            armor.setTrim(null);
        } else {
            String[] words = raw == null ? new String[0] : raw.strip().split("\\s+");
            if (words.length != 2) {
                throw new IllegalArgumentException("Enter material and pattern keys, or none");
            }
            TrimMaterial material = Registry.TRIM_MATERIAL.get(key(words[0]));
            TrimPattern pattern = Registry.TRIM_PATTERN.get(key(words[1]));
            if (material == null || pattern == null) {
                throw new IllegalArgumentException("Unknown trim material or pattern");
            }
            armor.setTrim(new ArmorTrim(material, pattern));
        }
        item.setItemMeta(meta);
    }

    static void setCustomModelData(ItemStack item, String raw) {
        ItemMeta meta = item.getItemMeta();
        if (raw != null && raw.strip().equalsIgnoreCase("none")) {
            meta.setCustomModelData(null);
        } else {
            int value = parseInt(raw, "Custom model data must be 0 through 16777215, or none");
            if (value < 0 || value > 16_777_215) {
                throw new IllegalArgumentException("Custom model data must be 0 through 16777215, or none");
            }
            meta.setCustomModelData(value);
        }
        item.setItemMeta(meta);
    }

    /** Edits one list of the newer custom-model-data component, preserving its other lists. */
    static void setRichCustomModelData(ItemStack item, String page) {
        if (page == null || page.isBlank() || page.length() > 4096) {
            throw new IllegalArgumentException("Enter a model-data list or none on one book page");
        }
        ItemMeta meta = item.getItemMeta();
        try {
            var get = ItemMeta.class.getMethod("getCustomModelDataComponent");
            Class<?> componentType = get.getReturnType();
            var set = ItemMeta.class.getMethod("setCustomModelDataComponent", componentType);
            if (page.strip().equalsIgnoreCase("none")) {
                set.invoke(meta, (Object) null);
            } else {
                String normalized = page.replace("\r", "");
                int separator = normalized.indexOf(' ');
                if (separator < 0 || separator == normalized.length() - 1) {
                    throw new IllegalArgumentException("Use @floats, @flags, @strings, or @colors and values");
                }
                String kind = normalized.substring(0, separator).toLowerCase(Locale.ROOT);
                String raw = normalized.substring(separator + 1);
                String field = switch (kind) {
                    case "@floats" -> "Floats";
                    case "@flags" -> "Flags";
                    case "@strings" -> "Strings";
                    case "@colors" -> "Colors";
                    default -> throw new IllegalArgumentException("Unknown model-data list: " + kind);
                };
                List<?> values = raw.strip().equalsIgnoreCase("none") ? List.of()
                        : switch (kind) {
                            case "@floats" -> modelFloats(raw);
                            case "@flags" -> modelFlags(raw);
                            case "@strings" -> strings(raw);
                            case "@colors" -> modelColors(raw);
                            default -> throw new IllegalStateException("Unknown model-data list");
                        };
                Object component = get.invoke(meta);
                componentType.getMethod("set" + field, List.class).invoke(component, values);
                set.invoke(meta, component);
            }
        } catch (NoSuchMethodException ex) {
            throw new IllegalArgumentException("This server does not support rich custom model data", ex);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Could not edit rich custom model data", ex);
        } catch (InvocationTargetException ex) {
            throw new IllegalArgumentException("The server rejected rich custom model data", ex.getCause());
        }
        if (!item.setItemMeta(meta)) {
            throw new IllegalArgumentException("This item cannot store rich custom model data");
        }
    }

    private static List<String> modelParts(String raw) {
        String[] parts = raw.split(",", -1);
        if (parts.length < 1 || parts.length > 32) {
            throw new IllegalArgumentException("Model-data lists need 1 through 32 values");
        }
        List<String> values = new ArrayList<>(parts.length);
        for (String part : parts) {
            String value = part.strip();
            if (value.isEmpty()) throw new IllegalArgumentException("Model-data lists cannot contain blanks");
            values.add(value);
        }
        return List.copyOf(values);
    }

    static List<Float> modelFloats(String raw) {
        List<Float> values = new ArrayList<>();
        for (String part : modelParts(raw)) {
            try { values.add(finiteFloat(part)); }
            catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Model-data floats must be finite numbers", ex);
            }
        }
        return List.copyOf(values);
    }

    static List<Boolean> modelFlags(String raw) {
        List<Boolean> values = new ArrayList<>();
        for (String part : modelParts(raw)) {
            if (part.equalsIgnoreCase("true")) values.add(true);
            else if (part.equalsIgnoreCase("false")) values.add(false);
            else throw new IllegalArgumentException("Model-data flags must be true or false");
        }
        return List.copyOf(values);
    }

    static List<Color> modelColors(String raw) {
        List<Color> values = new ArrayList<>();
        for (String part : modelParts(raw)) values.add(color(part));
        return List.copyOf(values);
    }

    /** Edits common item-specific components without replacing unrelated server-held metadata. */
    static void setVisualComponent(ItemStack item, String raw) {
        ItemMeta meta = item.getItemMeta();
        String value = raw == null ? "" : raw.strip();
        if (meta instanceof FireworkMeta firework) {
            if (value.equalsIgnoreCase("none")) {
                firework.clearEffects();
                firework.setPower(0);
            } else {
                String[] words = value.split("\\s+");
                if (words.length != 3) {
                    throw new IllegalArgumentException("Enter power #RRGGBB effect-type, or none");
                }
                int power = parseInt(words[0], "Firework power must be 0 through 127");
                if (power < 0 || power > 127) {
                    throw new IllegalArgumentException("Firework power must be 0 through 127");
                }
                Color color = color(words[1]);
                FireworkEffect.Type type;
                try { type = FireworkEffect.Type.valueOf(words[2].toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("Unknown firework effect type", ex);
                }
                FireworkEffect effect = FireworkEffect.builder().with(type).withColor(color).build();
                firework.clearEffects();
                firework.addEffect(effect);
                firework.setPower(power);
            }
        } else if (meta instanceof FireworkEffectMeta star) {
            if (value.equalsIgnoreCase("none")) star.setEffect(null);
            else {
                String[] words = value.split("\\s+");
                if (words.length != 2) {
                    throw new IllegalArgumentException("Enter effect-type #RRGGBB, or none");
                }
                FireworkEffect.Type type;
                try { type = FireworkEffect.Type.valueOf(words[0].toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("Unknown firework effect type", ex);
                }
                star.setEffect(FireworkEffect.builder().with(type)
                        .withColor(color(words[1])).build());
            }
        } else if (meta instanceof LeatherArmorMeta leather) {
            leather.setColor(color(value));
        } else if (meta instanceof PotionMeta potion) {
            potion.setColor(value.equalsIgnoreCase("none") ? null : color(value));
        } else {
            throw new IllegalArgumentException("This item has no supported color or firework effect editor");
        }
        item.setItemMeta(meta);
    }

    static void setPotionBase(ItemStack item, String raw) {
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof PotionMeta potion)) {
            throw new IllegalArgumentException("This item is not a potion");
        }
        PotionType type;
        try { type = PotionType.valueOf(raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Enter a valid potion type such as HEALING", ex);
        }
        potion.setBasePotionType(type);
        item.setItemMeta(potion);
    }

    static void setPotionEffect(ItemStack item, String raw) {
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof PotionMeta potion)) {
            throw new IllegalArgumentException("This item is not a potion");
        }
        String[] words = raw == null ? new String[0] : raw.strip().split("\\s+");
        if (words.length < 2 || words.length > 3) {
            throw new IllegalArgumentException("Enter effect key, duration ticks, and amplifier; duration 0 removes it");
        }
        PotionEffectType type = Registry.EFFECT.get(key(words[0]));
        if (type == null) throw new IllegalArgumentException("Unknown potion effect");
        int duration = parseInt(words[1], "Effect duration must be 0 through 1000000 ticks");
        if (duration < 0 || duration > 1_000_000) {
            throw new IllegalArgumentException("Effect duration must be 0 through 1000000 ticks");
        }
        if (duration == 0) {
            if (words.length != 2) throw new IllegalArgumentException("Use effect key and 0 to remove it");
            potion.removeCustomEffect(type);
        } else {
            if (words.length != 3) throw new IllegalArgumentException("Enter an amplifier from 0 through 255");
            int amplifier = parseInt(words[2], "Effect amplifier must be 0 through 255");
            if (amplifier < 0 || amplifier > 255) {
                throw new IllegalArgumentException("Effect amplifier must be 0 through 255");
            }
            potion.addCustomEffect(new PotionEffect(type, duration, amplifier), true);
        }
        item.setItemMeta(potion);
    }

    static void setBookTitle(ItemStack item, String raw) {
        if (item.getType() != org.bukkit.Material.WRITTEN_BOOK
                || !(item.getItemMeta() instanceof BookMeta book)) {
            throw new IllegalArgumentException("A title requires a written book");
        }
        String title = raw == null ? "" : raw.strip();
        if (title.isEmpty() || title.length() > 32 || !book.setTitle(title)) {
            throw new IllegalArgumentException("Book title must be 1 through 32 characters");
        }
        item.setItemMeta(book);
    }

    static void setBookAuthor(ItemStack item, String raw) {
        if (item.getType() != org.bukkit.Material.WRITTEN_BOOK
                || !(item.getItemMeta() instanceof BookMeta book)) {
            throw new IllegalArgumentException("An author requires a written book");
        }
        String author = raw == null ? "" : raw.strip();
        if (author.isEmpty() || author.length() > 64) {
            throw new IllegalArgumentException("Book author must be 1 through 64 characters");
        }
        book.setAuthor(author);
        item.setItemMeta(book);
    }

    static void setBookPages(ItemStack item, String raw) {
        if (!(item.getItemMeta() instanceof WritableBookMeta book)) {
            throw new IllegalArgumentException("This item is not a book");
        }
        if (raw != null && raw.strip().equalsIgnoreCase("none")) {
            book.setPages(List.of());
            item.setItemMeta(book);
            return;
        }
        String[] pages = raw == null ? new String[0] : raw.split("\\n---PAGE---\\n", -1);
        if (pages.length < 1 || pages.length > 8) {
            throw new IllegalArgumentException("A book draft needs 1 through 8 pages");
        }
        for (String page : pages) {
            if (page.isBlank() || page.length() > 1024) {
                throw new IllegalArgumentException("Each book page needs 1 through 1024 characters");
            }
        }
        book.setPages(pages);
        item.setItemMeta(book);
    }

    record HeadProfileSpec(String name, String textureHash) { }

    static HeadProfileSpec parseHeadProfile(String raw) {
        if (raw == null || raw.length() > 96 || raw.indexOf('\n') >= 0
                || raw.indexOf('\r') >= 0 || raw.isBlank()) {
            throw new IllegalArgumentException("Enter a player name and optional 64-character skin hash, or none");
        }
        if (raw.strip().equalsIgnoreCase("none")) return null;
        String[] words = raw.strip().split("\\s+");
        if (words.length < 1 || words.length > 2 || !words[0].matches("[A-Za-z0-9_]{1,16}")
                || words.length == 2 && !words[1].matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Enter a player name and optional 64-character skin hash, or none");
        }
        return new HeadProfileSpec(words[0], words.length == 2 ? words[1] : null);
    }

    static void setHeadProfile(ItemStack item, String raw) {
        if (item.getType() != Material.PLAYER_HEAD
                || !(item.getItemMeta() instanceof SkullMeta skull)) {
            throw new IllegalArgumentException("This item is not a player head");
        }
        HeadProfileSpec spec = parseHeadProfile(raw);
        PlayerProfile profile = null;
        if (spec != null) {
            profile = Bukkit.createPlayerProfile(spec.name());
            if (spec.textureHash() != null) {
                try {
                    var textures = profile.getTextures();
                    textures.setSkin(URI.create("https://textures.minecraft.net/texture/"
                            + spec.textureHash()).toURL());
                    profile.setTextures(textures);
                } catch (MalformedURLException ex) {
                    throw new IllegalArgumentException("Invalid Minecraft skin hash", ex);
                }
            }
        }
        skull.setOwnerProfile(profile);
        if (!item.setItemMeta(skull)) {
            throw new IllegalArgumentException("This player head cannot store a profile");
        }
    }

    static NamespacedKey parseHeadNoteBlockSound(String raw) {
        String value = boundedComponentInput(raw, 160,
                "Enter a namespaced note-block sound key, or none");
        return value.equalsIgnoreCase("none") ? null : key(value);
    }

    static void setHeadNoteBlockSound(ItemStack item, String raw) {
        if (item.getType() != Material.PLAYER_HEAD
                || !(item.getItemMeta() instanceof SkullMeta skull)) {
            throw new IllegalArgumentException("This item is not a player head");
        }
        skull.setNoteBlockSound(parseHeadNoteBlockSound(raw));
        if (!item.setItemMeta(skull)) {
            throw new IllegalArgumentException("This player head cannot store a note-block sound");
        }
    }

    static void setFoodProperties(ItemStack item, String raw) {
        ItemMeta meta = item.getItemMeta();
        if (raw != null && raw.strip().equalsIgnoreCase("none")) {
            meta.setFood(null);
            item.setItemMeta(meta);
            FoodComponentBridge.resetConsumable(item);
            return;
        } else {
            String[] words = raw == null ? new String[0] : raw.strip().split("\\s+");
            if (words.length != 4) {
                throw new IllegalArgumentException("Enter nutrition saturation always-eat eat-seconds, or none");
            }
            int nutrition = parseInt(words[0], "Nutrition must be 0 through 20");
            float saturation = finiteFloat(words[1]);
            if (nutrition < 0 || nutrition > 20 || saturation < 0 || saturation > 20) {
                throw new IllegalArgumentException("Nutrition and saturation must be 0 through 20");
            }
            if (!words[2].equalsIgnoreCase("true") && !words[2].equalsIgnoreCase("false")) {
                throw new IllegalArgumentException("Always-eat must be true or false");
            }
            float eatSeconds = finiteFloat(words[3]);
            if (eatSeconds <= 0 || eatSeconds > 60) {
                throw new IllegalArgumentException("Eat seconds must be above 0 through 60");
            }
            FoodComponent food = meta.getFood();
            food.setNutrition(nutrition);
            food.setSaturation(saturation);
            food.setCanAlwaysEat(Boolean.parseBoolean(words[2]));
            if (FoodComponentBridge.legacy()) food.setEatSeconds(eatSeconds);
            meta.setFood(food);
            item.setItemMeta(meta);
            if (!FoodComponentBridge.legacy()) FoodComponentBridge.setConsumeSeconds(item, eatSeconds);
            return;
        }
    }

    static void setFoodEffect(ItemStack item, String raw) {
        ItemMeta meta = item.getItemMeta();
        if (!meta.hasFood()) throw new IllegalArgumentException("Configure food properties first");
        String[] words = raw == null ? new String[0] : raw.strip().split("\\s+");
        if (words.length != 2 && words.length != 4) {
            throw new IllegalArgumentException("Enter effect key duration amplifier probability; duration 0 removes it");
        }
        PotionEffectType type = Registry.EFFECT.get(key(words[0]));
        if (type == null) throw new IllegalArgumentException("Unknown food effect");
        int duration = parseInt(words[1], "Food effect duration must be 0 through 1000000 ticks");
        if (duration < 0 || duration > 1_000_000 || (duration == 0 && words.length != 2)
                || (duration > 0 && words.length != 4)) {
            throw new IllegalArgumentException("Enter key and 0 to remove, or duration amplifier probability");
        }
        int amplifier = 0;
        float probability = 0;
        if (duration > 0) {
            amplifier = parseInt(words[2], "Food effect amplifier must be 0 through 255");
            probability = finiteFloat(words[3]);
            if (amplifier < 0 || amplifier > 255 || probability < 0 || probability > 1) {
                throw new IllegalArgumentException("Amplifier must be 0 through 255 and probability 0 through 1");
            }
        }
        if (!FoodComponentBridge.legacy()) {
            FoodComponentBridge.editEffect(item, type, duration, amplifier, probability);
            return;
        }
        FoodComponent food = meta.getFood();
        List<FoodComponent.FoodEffect> remaining = new ArrayList<>();
        for (FoodComponent.FoodEffect effect : food.getEffects()) {
            if (!effect.getEffect().getType().equals(type)) remaining.add(effect);
        }
        food.setEffects(remaining);
        if (duration > 0) food.addEffect(new PotionEffect(type, duration, amplifier), probability);
        meta.setFood(food);
        item.setItemMeta(meta);
    }

    static int foodEffectCount(ItemStack item) {
        return FoodComponentBridge.effectCount(item);
    }

    static float foodConsumeSeconds(ItemStack item) {
        return FoodComponentBridge.consumeSeconds(item);
    }

    static boolean foodEffectMatches(ItemStack item, PotionEffectType type,
            int duration, int amplifier, float probability) {
        return FoodComponentBridge.hasEffect(item, type, duration, amplifier, probability);
    }

    static void setToolProperties(ItemStack item, String raw) {
        ItemMeta meta = item.getItemMeta();
        if (raw != null && raw.strip().equalsIgnoreCase("none")) {
            meta.setTool(null);
        } else {
            String[] words = raw == null ? new String[0] : raw.strip().split("\\s+");
            if (words.length != 2) {
                throw new IllegalArgumentException("Enter default mining speed and damage per block, or none");
            }
            float speed = finiteFloat(words[0]);
            int damage = parseInt(words[1], "Damage per block must be 0 through 100");
            if (speed < 0 || speed > 1024 || damage < 0 || damage > 100) {
                throw new IllegalArgumentException("Mining speed must be 0 through 1024; damage 0 through 100");
            }
            ToolComponent tool = meta.getTool();
            tool.setDefaultMiningSpeed(speed);
            tool.setDamagePerBlock(damage);
            meta.setTool(tool);
        }
        item.setItemMeta(meta);
    }

    static void setToolRule(ItemStack item, String raw) {
        ItemMeta meta = item.getItemMeta();
        if (!meta.hasTool()) throw new IllegalArgumentException("Configure tool properties first");
        String[] words = raw == null ? new String[0] : raw.strip().split("\\s+");
        if (words.length != 2 && words.length != 3) {
            throw new IllegalArgumentException("Enter block speed correct-drops, or block none to remove");
        }
        org.bukkit.Material material = org.bukkit.Material.matchMaterial(words[0]);
        if (material == null || !material.isBlock()) {
            throw new IllegalArgumentException("Enter a known block material");
        }
        boolean remove = words.length == 2 && words[1].equalsIgnoreCase("none");
        if (!remove && words.length != 3) {
            throw new IllegalArgumentException("Enter block speed correct-drops, or block none");
        }
        float speed = 0;
        boolean drops = false;
        if (!remove) {
            speed = finiteFloat(words[1]);
            if (speed < 0 || speed > 1024
                    || !words[2].equalsIgnoreCase("true") && !words[2].equalsIgnoreCase("false")) {
                throw new IllegalArgumentException("Speed must be 0 through 1024 and correct-drops true or false");
            }
            drops = Boolean.parseBoolean(words[2]);
        }
        ToolComponent tool = meta.getTool();
        updateToolRule(tool, material, remove, speed, drops);
        meta.setTool(tool);
        item.setItemMeta(meta);
    }

    static void updateToolRule(ToolComponent tool, org.bukkit.Material material,
                               boolean remove, float speed, boolean drops) {
        List<ToolComponent.ToolRule> remaining = new ArrayList<>();
        for (ToolComponent.ToolRule rule : tool.getRules()) {
            if (!rule.getBlocks().contains(material)) {
                remaining.add(rule);
                continue;
            }
            List<org.bukkit.Material> otherBlocks = new ArrayList<>(rule.getBlocks());
            otherBlocks.removeIf(material::equals);
            if (!otherBlocks.isEmpty()) {
                rule.setBlocks(otherBlocks);
                remaining.add(rule);
            }
        }
        tool.setRules(remaining);
        if (!remove) tool.addRule(material, speed, drops);
    }

    static void setMapColor(ItemStack item, String raw) {
        if (!(item.getItemMeta() instanceof MapMeta map)) {
            throw new IllegalArgumentException("This item is not a filled map");
        }
        map.setColor(raw != null && raw.strip().equalsIgnoreCase("none")
                ? null : color(raw == null ? "" : raw.strip()));
        item.setItemMeta(map);
    }

    static void setMapId(ItemStack item, String raw) {
        if (!(item.getItemMeta() instanceof MapMeta map)) {
            throw new IllegalArgumentException("This item is not a filled map");
        }
        int id = parseInt(raw, "Enter an existing nonnegative map ID");
        if (id < 0 || Bukkit.getMap(id) == null) {
            throw new IllegalArgumentException("Enter an existing nonnegative map ID");
        }
        map.setMapId(id);
        item.setItemMeta(map);
    }

    static void toggleMapScaling(ItemStack item) {
        if (!(item.getItemMeta() instanceof MapMeta map)) {
            throw new IllegalArgumentException("This item is not a filled map");
        }
        map.setScaling(!map.isScaling());
        item.setItemMeta(map);
    }

    static void setMapLocationName(ItemStack item, String raw) {
        if (!(item.getItemMeta() instanceof MapMeta map)) {
            throw new IllegalArgumentException("This item is not a filled map");
        }
        String label = raw == null ? "" : raw.strip();
        if (label.equalsIgnoreCase("none")) label = "";
        if (label.length() > 128) {
            throw new IllegalArgumentException("Enter a map location label of at most 128 characters, or none");
        }
        MapLocationLabel.write(map, label.isEmpty() ? null : label);
        if (!item.setItemMeta(map)) {
            throw new IllegalArgumentException("This map cannot store a location label");
        }
    }

    static void setCompassLodestone(ItemStack item, String raw) {
        if (!(item.getItemMeta() instanceof CompassMeta compass)) {
            throw new IllegalArgumentException("This item is not a compass");
        }
        String value = raw == null ? "" : raw.strip();
        if (value.equalsIgnoreCase("none")) {
            compass.setLodestone(null);
        } else {
            String[] words = value.split("\\s+");
            if (words.length != 4) {
                throw new IllegalArgumentException("Enter world x y z, or none");
            }
            World world = Bukkit.getWorld(words[0]);
            if (world == null) throw new IllegalArgumentException("Unknown loaded world");
            int x = parseInt(words[1], "Invalid X coordinate");
            int y = parseInt(words[2], "Invalid Y coordinate");
            int z = parseInt(words[3], "Invalid Z coordinate");
            if (Math.abs((long) x) > 30_000_000L || Math.abs((long) z) > 30_000_000L
                    || y < world.getMinHeight() || y >= world.getMaxHeight()) {
                throw new IllegalArgumentException("Lodestone location is outside the loaded world");
            }
            compass.setLodestone(new Location(world, x, y, z));
        }
        item.setItemMeta(compass);
    }

    static void toggleCompassTracking(ItemStack item) {
        if (!(item.getItemMeta() instanceof CompassMeta compass) || !compass.hasLodestone()) {
            throw new IllegalArgumentException("Set a compass lodestone first");
        }
        compass.setLodestoneTracked(!compass.isLodestoneTracked());
        item.setItemMeta(compass);
    }

    static void setCrossbowProjectile(ItemStack item, String raw) {
        if (!(item.getItemMeta() instanceof CrossbowMeta crossbow)) {
            throw new IllegalArgumentException("This item is not a crossbow");
        }
        String value = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (value.equals("NONE")) {
            crossbow.setChargedProjectiles(List.of());
        } else {
            Material projectile;
            try { projectile = Material.valueOf(value); }
            catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Use ARROW, SPECTRAL_ARROW, TIPPED_ARROW, FIREWORK_ROCKET, or none", ex);
            }
            if (projectile != Material.ARROW && projectile != Material.SPECTRAL_ARROW
                    && projectile != Material.TIPPED_ARROW && projectile != Material.FIREWORK_ROCKET) {
                throw new IllegalArgumentException("Use an arrow or firework rocket projectile");
            }
            crossbow.setChargedProjectiles(List.of(new ItemStack(projectile)));
        }
        item.setItemMeta(crossbow);
    }

    static void editBannerPattern(ItemStack item, String raw) {
        if (!(item.getItemMeta() instanceof BannerMeta banner)) {
            throw new IllegalArgumentException("This item is not a banner");
        }
        String value = raw == null ? "" : raw.strip();
        if (value.equalsIgnoreCase("remove-last")) {
            if (banner.numberOfPatterns() == 0) throw new IllegalArgumentException("Banner has no pattern to remove");
            banner.removePattern(banner.numberOfPatterns() - 1);
        } else {
            String[] words = value.split("\\s+");
            if (words.length != 2) throw new IllegalArgumentException("Enter dye color and pattern type");
            if (banner.numberOfPatterns() >= 6) {
                throw new IllegalArgumentException("A banner may have at most six edited patterns");
            }
            DyeColor dye;
            PatternType type;
            try {
                dye = DyeColor.valueOf(words[0].toUpperCase(Locale.ROOT));
                type = PatternType.valueOf(words[1].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Unknown dye color or banner pattern", ex);
            }
            banner.addPattern(new Pattern(dye, type));
        }
        item.setItemMeta(banner);
    }

    static void setStewEffect(ItemStack item, String raw) {
        if (!(item.getItemMeta() instanceof SuspiciousStewMeta stew)) {
            throw new IllegalArgumentException("This item is not suspicious stew");
        }
        String[] words = raw == null ? new String[0] : raw.strip().split("\\s+");
        if (words.length != 2) {
            throw new IllegalArgumentException("Enter effect key and duration ticks; duration 0 removes it");
        }
        PotionEffectType type = Registry.EFFECT.get(key(words[0]));
        if (type == null) throw new IllegalArgumentException("Unknown stew effect");
        int duration = parseInt(words[1], "Stew effect duration must be 0 through 1000000 ticks");
        if (duration < 0 || duration > 1_000_000) {
            throw new IllegalArgumentException("Stew effect duration must be 0 through 1000000 ticks");
        }
        if (duration == 0) stew.removeCustomEffect(type);
        else stew.addCustomEffect(new PotionEffect(type, duration, 0), true);
        item.setItemMeta(stew);
    }

    static void setTropicalFishVariant(ItemStack item, String raw) {
        if (!(item.getItemMeta() instanceof TropicalFishBucketMeta fish)) {
            throw new IllegalArgumentException("This item is not a tropical fish bucket");
        }
        String[] words = raw == null ? new String[0] : raw.strip().split("\\s+");
        if (words.length != 3) {
            throw new IllegalArgumentException("Enter body color, pattern color, and fish pattern");
        }
        DyeColor body;
        DyeColor patternColor;
        TropicalFish.Pattern pattern;
        try {
            body = DyeColor.valueOf(words[0].toUpperCase(Locale.ROOT));
            patternColor = DyeColor.valueOf(words[1].toUpperCase(Locale.ROOT));
            pattern = TropicalFish.Pattern.valueOf(words[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown tropical fish color or pattern", ex);
        }
        fish.setPattern(pattern);
        // Spigot 1.20.6 derives the body color from the previous pattern color
        // when setPatternColor is called, so seed that value first.
        fish.setPatternColor(body);
        fish.setBodyColor(body);
        fish.setPatternColor(patternColor);
        if (fish.getBodyColor() != body || fish.getPatternColor() != patternColor
                || fish.getPattern() != pattern) {
            throw new IllegalArgumentException("This server could not retain the selected fish variant");
        }
        item.setItemMeta(fish);
    }

    static void setAxolotlVariant(ItemStack item, String raw) {
        if (!(item.getItemMeta() instanceof AxolotlBucketMeta bucket)) {
            throw new IllegalArgumentException("This item is not an axolotl bucket");
        }
        String value = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (value.equals("NONE")) {
            // Bukkit's setVariant(null) throws on 1.20.6 and selects LUCY on
            // newer CraftBukkit. Convert through ordinary item meta to remove
            // only the bucket-specific variant while retaining common metadata.
            ItemMeta common = Bukkit.getItemFactory().asMetaFor(bucket, Material.STONE);
            ItemMeta cleared = Bukkit.getItemFactory().asMetaFor(common, Material.AXOLOTL_BUCKET);
            if (!(cleared instanceof AxolotlBucketMeta empty) || empty.hasVariant()
                    || !item.setItemMeta(empty)) {
                throw new IllegalArgumentException("This server could not clear the axolotl variant");
            }
            return;
        }
        try {
            bucket.setVariant(Axolotl.Variant.valueOf(value));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Use LUCY, WILD, GOLD, CYAN, BLUE, or none", ex);
        }
        item.setItemMeta(bucket);
    }

    static void editKnowledgeRecipes(ItemStack item, String raw) {
        if (!(item.getItemMeta() instanceof KnowledgeBookMeta book)) {
            throw new IllegalArgumentException("This item is not a knowledge book");
        }
        String value = raw == null ? "" : raw.strip();
        if (value.equalsIgnoreCase("clear")) {
            book.setRecipes(List.of());
        } else {
            boolean remove = value.toLowerCase(Locale.ROOT).startsWith("remove ");
            String name = remove ? value.substring(7).strip() : value;
            NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
            if (key == null || (!remove && Bukkit.getRecipe(key) == null)) {
                throw new IllegalArgumentException("Enter an existing recipe key, remove key, or clear");
            }
            List<NamespacedKey> recipes = new ArrayList<>(book.getRecipes());
            if (remove) {
                if (!recipes.remove(key)) throw new IllegalArgumentException("Recipe is not in this book");
            } else if (!recipes.contains(key)) {
                if (recipes.size() >= 16) throw new IllegalArgumentException("Knowledge book is limited to 16 recipes");
                recipes.add(key);
            }
            book.setRecipes(recipes);
        }
        item.setItemMeta(book);
    }

    static void setGoatHornInstrument(ItemStack item, String raw) {
        if (!(item.getItemMeta() instanceof MusicInstrumentMeta horn)) {
            throw new IllegalArgumentException("This item is not a goat horn");
        }
        String value = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        MusicInstrument instrument = switch (value) {
            case "PONDER" -> MusicInstrument.PONDER_GOAT_HORN;
            case "SING" -> MusicInstrument.SING_GOAT_HORN;
            case "SEEK" -> MusicInstrument.SEEK_GOAT_HORN;
            case "FEEL" -> MusicInstrument.FEEL_GOAT_HORN;
            case "ADMIRE" -> MusicInstrument.ADMIRE_GOAT_HORN;
            case "CALL" -> MusicInstrument.CALL_GOAT_HORN;
            case "YEARN" -> MusicInstrument.YEARN_GOAT_HORN;
            case "DREAM" -> MusicInstrument.DREAM_GOAT_HORN;
            default -> throw new IllegalArgumentException("Use a vanilla goat horn instrument name");
        };
        horn.setInstrument(instrument);
        item.setItemMeta(horn);
    }

    static void setOminousBottleLevel(ItemStack item, String raw) {
        if (!(item.getItemMeta() instanceof OminousBottleMeta bottle)) {
            throw new IllegalArgumentException("This item is not an ominous bottle");
        }
        int level = parseInt(raw, "Ominous bottle level must be 0 through 4");
        if (level < 0 || level > 4) {
            throw new IllegalArgumentException("Ominous bottle level must be 0 through 4");
        }
        bottle.setAmplifier(level);
        item.setItemMeta(bottle);
    }

    static void setBundleContents(ItemStack item, String raw) {
        if (!(item.getItemMeta() instanceof BundleMeta bundle)) {
            throw new IllegalArgumentException("This item is not a bundle");
        }
        String value = raw == null ? "" : raw.strip();
        List<ItemStack> contents = bundle.getItems().stream().map(ItemStack::clone)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (value.equalsIgnoreCase("none")) {
            contents.clear();
        } else {
            String[] parts = value.split("\\s+");
            if (parts.length == 3 && parts[0].equals("+")) {
                if (contents.size() >= 64) {
                    throw new IllegalArgumentException("A bundle may have at most 64 edited stacks");
                }
                contents.add(bundleStack(parts[1], parts[2]));
            } else if (parts.length == 3) {
                int index = bundleIndex(parts[0], contents.size());
                contents.set(index, bundleStack(parts[1], parts[2]));
            } else if (parts.length == 2 && parts[0].equals("-")) {
                contents.remove(bundleIndex(parts[1], contents.size()));
            } else if (parts.length == 2) {
                // Preserve the original shorthand for replacing the complete bundle.
                contents.clear();
                contents.add(bundleStack(parts[0], parts[1]));
            } else {
                throw new IllegalArgumentException(
                        "Use MATERIAL amount, + MATERIAL amount, slot MATERIAL amount, - slot, or none");
            }
        }
        try { bundle.setItems(contents); }
        catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Bundle cannot hold those contents", ex);
        }
        if (!item.setItemMeta(bundle)) {
            throw new IllegalArgumentException("Could not save bundle contents");
        }
    }

    private static int bundleIndex(String raw, int size) {
        int index = parseInt(raw, "Bundle slot must be 1 through " + size);
        if (index < 1 || index > size) {
            throw new IllegalArgumentException("Bundle slot must be 1 through " + size);
        }
        return index - 1;
    }

    private static ItemStack bundleStack(String materialName, String amountValue) {
        Material material;
        try { material = Material.valueOf(materialName.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown bundle material", ex);
        }
        int amount = parseInt(amountValue, "Bundle amount must be 1 through 64");
        if (!material.isItem() || material.isAir() || amount < 1 || amount > 64) {
            throw new IllegalArgumentException("Bundle item must be an item with amount 1 through 64");
        }
        return new ItemStack(material, amount);
    }

    static void setShulkerContents(ItemStack item, String raw) {
        if (!(item.getItemMeta() instanceof BlockStateMeta block)
                || !(block.getBlockState() instanceof ShulkerBox box)) {
            throw new IllegalArgumentException("This item is not a shulker box");
        }
        ShulkerEdit edit = parseShulkerEdit(raw);
        var inventory = box.getSnapshotInventory();
        applyShulkerEdit(inventory, edit);
        block.setBlockState(box);
        if (!item.setItemMeta(block)) {
            throw new IllegalArgumentException("Could not save shulker contents");
        }
    }

    static int shulkerFilledSlots(ItemStack item) {
        if (!(item.getItemMeta() instanceof BlockStateMeta block)
                || !(block.getBlockState() instanceof ShulkerBox box)) return -1;
        int filled = 0;
        for (ItemStack stack : box.getSnapshotInventory().getContents()) {
            if (stack != null && !stack.getType().isAir()) filled++;
        }
        return filled;
    }

    static ShulkerEdit parseShulkerEdit(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.length() > 100 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Enter slot MATERIAL amount, slot none, or none");
        }
        if (value.equalsIgnoreCase("none")) return new ShulkerEdit(-1, null);
        String[] parts = value.split("\\s+");
        if (parts.length != 2 && parts.length != 3) {
            throw new IllegalArgumentException("Enter slot MATERIAL amount, slot none, or none");
        }
        int slot = parseInt(parts[0], "Shulker slot must be 1 through 27");
        if (slot < 1 || slot > 27) {
            throw new IllegalArgumentException("Shulker slot must be 1 through 27");
        }
        if (parts.length == 2 && parts[1].equalsIgnoreCase("none")) {
            return new ShulkerEdit(slot - 1, null);
        }
        if (parts.length != 3) {
            throw new IllegalArgumentException("Enter slot MATERIAL amount, slot none, or none");
        }
        Material material;
        try { material = Material.valueOf(parts[1].toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown shulker item material", ex);
        }
        int amount = parseInt(parts[2], "Shulker amount must be a valid stack size");
        if (!material.isItem() || material.isAir()
                || material.name().endsWith("SHULKER_BOX")
                || amount < 1 || amount > material.getMaxStackSize()) {
            throw new IllegalArgumentException("Shulker slot needs a non-shulker item and valid stack size");
        }
        return new ShulkerEdit(slot - 1, new ItemStack(material, amount));
    }

    record ShulkerEdit(int slot, ItemStack stack) { }

    static void applyShulkerEdit(Inventory inventory, ShulkerEdit edit) {
        if (edit.slot() == -1) inventory.clear();
        else inventory.setItem(edit.slot(), edit.stack() == null ? null : edit.stack().clone());
    }

    static void setRepairCost(ItemStack item, String raw) {
        if (!(item.getItemMeta() instanceof Repairable repairable)) {
            throw new IllegalArgumentException("This item has no anvil repair cost");
        }
        int cost = raw != null && raw.strip().equalsIgnoreCase("none") ? 0
                : parseInt(raw, "Repair cost must be 0 through 1000000, or none");
        if (cost < 0 || cost > 1_000_000) {
            throw new IllegalArgumentException("Repair cost must be 0 through 1000000, or none");
        }
        repairable.setRepairCost(cost);
        item.setItemMeta(repairable);
    }

    static void setBookGeneration(ItemStack item, String raw) {
        if (item.getType() != Material.WRITTEN_BOOK
                || !(item.getItemMeta() instanceof BookMeta book)) {
            throw new IllegalArgumentException("This item is not a written book");
        }
        if (!book.hasTitle() || !book.hasAuthor() || book.getPageCount() == 0) {
            throw new IllegalArgumentException("Set a title, author, and at least one page first");
        }
        BookMeta.Generation generation;
        try { generation = BookMeta.Generation.valueOf(raw.strip().toUpperCase(Locale.ROOT)); }
        catch (RuntimeException ex) {
            throw new IllegalArgumentException("Use ORIGINAL, COPY_OF_ORIGINAL, COPY_OF_COPY, or TATTERED", ex);
        }
        book.setGeneration(generation);
        item.setItemMeta(book);
    }

    static void setBlockItemState(ItemStack item, String raw) {
        if (!(item.getItemMeta() instanceof BlockDataMeta block)) {
            throw new IllegalArgumentException("This item has no placeable block state");
        }
        String value = raw == null ? "" : raw.strip();
        if (value.equalsIgnoreCase("none")) {
            block.setBlockData(null);
        } else {
            if (value.length() > 160 || !value.matches("[a-z0-9_=,]+")) {
                throw new IllegalArgumentException("Enter block properties such as facing=east,half=top");
            }
            BlockData data;
            try { data = Bukkit.createBlockData(item.getType().getKey() + "[" + value + "]"); }
            catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Invalid block properties for " + item.getType(), ex);
            }
            if (data.getMaterial() != item.getType()) {
                throw new IllegalArgumentException("Block state material does not match the item");
            }
            block.setBlockData(data);
        }
        item.setItemMeta(block);
    }

    static void setItemName(ItemStack item, String raw) {
        ItemMeta meta = item.getItemMeta();
        String value = raw == null ? "" : raw.strip();
        if (value.equalsIgnoreCase("none")) meta.setItemName(null);
        else {
            if (value.isEmpty() || value.length() > 80 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Item name must be 1 through 80 characters, or none");
            }
            meta.setItemName(ChatColor.translateAlternateColorCodes('&', value));
        }
        item.setItemMeta(meta);
    }

    /** Newer server item-model component; a namespaced resource key or none. */
    static void setItemModel(ItemStack item, String raw) {
        setNamespacedComponent(item, raw, "ItemModel", "item model");
    }

    /** Newer server tooltip-style component; a namespaced resource key or none. */
    static void setTooltipStyle(ItemStack item, String raw) {
        setNamespacedComponent(item, raw, "TooltipStyle", "tooltip style");
    }

    /** Newer server enchantability component; its configured value is independent of enchants. */
    static void setEnchantable(ItemStack item, String raw) {
        Integer value = null;
        if (raw == null || !raw.strip().equalsIgnoreCase("none")) {
            value = parseInt(raw, "Enchantability must be 0 through 1024, or none");
            if (value < 0 || value > 1024) {
                throw new IllegalArgumentException("Enchantability must be 0 through 1024, or none");
            }
        }
        ItemMeta meta = item.getItemMeta();
        setModernComponent(meta, "Enchantable", Integer.class, value, "enchantability");
        item.setItemMeta(meta);
    }

    static boolean parseGlider(String raw) {
        String value = boundedComponentInput(raw, 16, "Enter true or false for glider");
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("Enter true or false for glider");
        };
    }

    static void setGlider(ItemStack item, String raw) {
        boolean glider = parseGlider(raw);
        ItemMeta meta = item.getItemMeta();
        setModernComponent(meta, "Glider", boolean.class, glider, "glider");
        if (!item.setItemMeta(meta)) throw new IllegalArgumentException("This item cannot store glider");
    }

    static Integer parseAdditionalTradeCost(String raw) {
        String value = boundedComponentInput(raw, 16,
                "Additional trade cost must be 0 through 1000000, or none");
        Integer cost = null;
        if (!value.equalsIgnoreCase("none")) {
            cost = parseInt(value, "Additional trade cost must be 0 through 1000000, or none");
            if (cost < 0 || cost > 1_000_000) {
                throw new IllegalArgumentException(
                        "Additional trade cost must be 0 through 1000000, or none");
            }
        }
        return cost;
    }

    static void setAdditionalTradeCost(ItemStack item, String raw) {
        Integer cost = parseAdditionalTradeCost(raw);
        ItemMeta meta = item.getItemMeta();
        setModernComponent(meta, "AdditionalTradeCost", Integer.class, cost,
                "additional trade cost");
        if (!item.setItemMeta(meta)) {
            throw new IllegalArgumentException("This item cannot store additional trade cost");
        }
    }

    static Float parseMinimumAttackCharge(String raw) {
        String value = boundedComponentInput(raw, 32,
                "Minimum attack charge must be 0 through 1, or none");
        Float charge = null;
        if (!value.equalsIgnoreCase("none")) {
            try { charge = finiteFloat(value); }
            catch (NumberFormatException ex) {
                throw new IllegalArgumentException(
                        "Minimum attack charge must be 0 through 1, or none", ex);
            }
            if (charge < 0 || charge > 1) {
                throw new IllegalArgumentException("Minimum attack charge must be 0 through 1, or none");
            }
        }
        return charge;
    }

    static void setMinimumAttackCharge(ItemStack item, String raw) {
        Float charge = parseMinimumAttackCharge(raw);
        ItemMeta meta = item.getItemMeta();
        setModernComponent(meta, "MinimumAttackCharge", Float.class, charge,
                "minimum attack charge");
        if (!item.setItemMeta(meta)) {
            throw new IllegalArgumentException("This item cannot store minimum attack charge");
        }
    }

    static NamespacedKey parseBreakSoundKey(String raw) {
        String value = boundedComponentInput(raw, 160,
                "Enter a sound key such as minecraft:block.glass.break, or none");
        return value.equalsIgnoreCase("none") ? null : key(value);
    }

    static void setBreakSound(ItemStack item, String raw) {
        try { ItemMeta.class.getMethod("setBreakSound", Sound.class); }
        catch (NoSuchMethodException ex) {
            throw new IllegalArgumentException("This server does not support break sound", ex);
        }
        NamespacedKey soundKey = parseBreakSoundKey(raw);
        Sound sound = null;
        if (soundKey != null) {
            sound = Registry.SOUNDS.get(soundKey);
            if (sound == null) throw new IllegalArgumentException("Unknown break sound");
        }
        ItemMeta meta = item.getItemMeta();
        setModernComponent(meta, "BreakSound", Sound.class, sound, "break sound");
        if (!item.setItemMeta(meta)) throw new IllegalArgumentException("This item cannot store break sound");
    }

    static NamespacedKey parseJukeboxSongKey(String raw) {
        String value = boundedComponentInput(raw, 160,
                "Enter a jukebox song key such as minecraft:cat, or none");
        return value.equalsIgnoreCase("none") ? null : key(value);
    }

    static void setJukeboxPlayable(ItemStack item, String raw) {
        ItemMeta meta = item.getItemMeta();
        try {
            var get = ItemMeta.class.getMethod("getJukeboxPlayable");
            Class<?> componentType = get.getReturnType();
            var set = ItemMeta.class.getMethod("setJukeboxPlayable", componentType);
            NamespacedKey songKey = parseJukeboxSongKey(raw);
            if (songKey == null) {
                set.invoke(meta, (Object) null);
            } else {
                Object registry = Registry.class.getField("JUKEBOX_SONG").get(null);
                Object song = Registry.class.getMethod("get", NamespacedKey.class)
                        .invoke(registry, songKey);
                if (song == null) throw new IllegalArgumentException("Unknown jukebox song");
                Object component = get.invoke(meta);
                componentType.getMethod("setSongKey", NamespacedKey.class)
                        .invoke(component, songKey);
                set.invoke(meta, component);
            }
        } catch (NoSuchMethodException | NoSuchFieldException ex) {
            throw new IllegalArgumentException("This server does not support jukebox playable", ex);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Could not edit jukebox playable", ex);
        } catch (InvocationTargetException ex) {
            throw new IllegalArgumentException("The server rejected jukebox playable", ex.getCause());
        }
        if (!item.setItemMeta(meta)) {
            throw new IllegalArgumentException("This item cannot store jukebox playable");
        }
    }

    static NamespacedKey parseDamageResistanceTag(String raw) {
        String value = boundedComponentInput(raw, 160,
                "Enter a damage type tag such as minecraft:is_fire, or none");
        return value.equalsIgnoreCase("none") ? null : key(value);
    }

    static void setDamageResistance(ItemStack item, String raw) {
        try { ItemMeta.class.getMethod("setDamageResistant", org.bukkit.Tag.class); }
        catch (NoSuchMethodException ex) {
            throw new IllegalArgumentException("This server does not support damage resistance", ex);
        }
        NamespacedKey tagKey = parseDamageResistanceTag(raw);
        Object tag = null;
        if (tagKey != null) {
            try {
                Class<?> damageType = Class.forName("org.bukkit.damage.DamageType");
                tag = Bukkit.class.getMethod("getTag", String.class, NamespacedKey.class, Class.class)
                        .invoke(null, "damage_types", tagKey, damageType);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Could not look up damage type tag", ex);
            }
            if (tag == null) throw new IllegalArgumentException("Unknown damage type tag");
        }
        ItemMeta meta = item.getItemMeta();
        setModernComponent(meta, "DamageResistant", org.bukkit.Tag.class, tag,
                "damage resistance");
        if (!item.setItemMeta(meta)) {
            throw new IllegalArgumentException("This item cannot store damage resistance");
        }
    }

    private static String boundedComponentInput(String raw, int maximum, String message) {
        if (raw == null || raw.length() > maximum || raw.indexOf('\n') >= 0
                || raw.indexOf('\r') >= 0 || raw.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return raw.strip();
    }

    /** A single nested item argument, or none to clear the use remainder. */
    static void setUseRemainder(ItemStack item, String raw) {
        ItemMeta meta = item.getItemMeta();
        try {
            var set = ItemMeta.class.getMethod("setUseRemainder", ItemStack.class);
            ItemStack remainder = raw != null && raw.strip().equalsIgnoreCase("none")
                    ? null : parseItemText(raw);
            set.invoke(meta, remainder == null ? null : remainder.clone());
        } catch (NoSuchMethodException ex) {
            throw new IllegalArgumentException("This server does not support use remainder", ex);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Could not edit use remainder", ex);
        } catch (InvocationTargetException ex) {
            throw new IllegalArgumentException("The server rejected use remainder", ex.getCause());
        }
        if (!item.setItemMeta(meta)) {
            throw new IllegalArgumentException("This item cannot store use remainder");
        }
    }

    record CooldownSpec(float seconds, NamespacedKey group, boolean changeGroup) { }

    static CooldownSpec parseUseCooldown(String raw) {
        if (raw == null || raw.length() > 160 || raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Enter cooldown seconds and optional namespace:group, or none");
        }
        String value = raw.strip();
        if (value.equalsIgnoreCase("none")) return null;
        String[] words = value.split("\\s+");
        if (words.length < 1 || words.length > 2 || words[0].isBlank()) {
            throw new IllegalArgumentException("Enter cooldown seconds and optional namespace:group, or none");
        }
        float seconds;
        try { seconds = finiteFloat(words[0]); }
        catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Cooldown must be a finite number of seconds", ex);
        }
        if (seconds <= 0 || seconds > 3600) {
            throw new IllegalArgumentException("Cooldown must be greater than 0 and at most 3600 seconds");
        }
        NamespacedKey group = words.length == 2 && !words[1].equalsIgnoreCase("none")
                ? key(words[1]) : null;
        return new CooldownSpec(seconds, group, words.length == 2);
    }

    static void setUseCooldown(ItemStack item, String raw) {
        ItemMeta meta = item.getItemMeta();
        try {
            var get = ItemMeta.class.getMethod("getUseCooldown");
            Class<?> componentType = get.getReturnType();
            var set = ItemMeta.class.getMethod("setUseCooldown", componentType);
            CooldownSpec spec = parseUseCooldown(raw);
            if (spec == null) {
                set.invoke(meta, (Object) null);
            } else {
                Object component = get.invoke(meta);
                componentType.getMethod("setCooldownSeconds", float.class)
                        .invoke(component, spec.seconds());
                if (spec.changeGroup()) {
                    componentType.getMethod("setCooldownGroup", NamespacedKey.class)
                            .invoke(component, spec.group());
                }
                set.invoke(meta, component);
            }
        } catch (NoSuchMethodException ex) {
            throw new IllegalArgumentException("This server does not support use cooldown", ex);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Could not edit use cooldown", ex);
        } catch (InvocationTargetException ex) {
            throw new IllegalArgumentException("The server rejected use cooldown", ex.getCause());
        }
        if (!item.setItemMeta(meta)) {
            throw new IllegalArgumentException("This item cannot store use cooldown");
        }
    }

    record EquippableSpec(EquipmentSlot slot, NamespacedKey model, boolean changeModel) { }

    static EquippableSpec parseEquippable(String raw) {
        if (raw == null || raw.length() > 160 || raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Enter equipment slot and optional namespace:model, or none");
        }
        String value = raw.strip();
        if (value.equalsIgnoreCase("none")) return null;
        String[] words = value.split("\\s+");
        if (words.length < 1 || words.length > 2 || words[0].isBlank()) {
            throw new IllegalArgumentException("Enter equipment slot and optional namespace:model, or none");
        }
        EquipmentSlot slot;
        try { slot = EquipmentSlot.valueOf(words[0].toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown equipment slot", ex);
        }
        NamespacedKey model = words.length == 2 && !words[1].equalsIgnoreCase("none")
                ? key(words[1]) : null;
        return new EquippableSpec(slot, model, words.length == 2);
    }

    static void setEquippable(ItemStack item, String raw) {
        ItemMeta meta = item.getItemMeta();
        try {
            var get = ItemMeta.class.getMethod("getEquippable");
            Class<?> componentType = get.getReturnType();
            var set = ItemMeta.class.getMethod("setEquippable", componentType);
            EquippableSpec spec = parseEquippable(raw);
            if (spec == null) {
                set.invoke(meta, (Object) null);
            } else {
                Object component = get.invoke(meta);
                componentType.getMethod("setSlot", EquipmentSlot.class)
                        .invoke(component, spec.slot());
                if (spec.changeModel()) {
                    componentType.getMethod("setModel", NamespacedKey.class)
                            .invoke(component, spec.model());
                }
                set.invoke(meta, component);
            }
        } catch (NoSuchMethodException ex) {
            throw new IllegalArgumentException("This server does not support equippable items", ex);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Could not edit equippable item", ex);
        } catch (InvocationTargetException ex) {
            throw new IllegalArgumentException("The server rejected equippable item", ex.getCause());
        }
        if (!item.setItemMeta(meta)) {
            throw new IllegalArgumentException("This item cannot store equippable data");
        }
    }

    /** Edits one RecipeWeaver-owned modifier for an attribute and equipment group. */
    static void setAttributeModifier(ItemStack item, String raw) {
        String[] words = raw == null ? new String[0] : raw.strip().split("\\s+");
        if (words.length != 3 && words.length != 4) {
            throw new IllegalArgumentException("Enter attribute-key slot-group amount operation, "
                    + "or attribute-key slot-group none");
        }
        NamespacedKey attributeKey = key(words[0]);
        Attribute attribute = Registry.ATTRIBUTE.get(attributeKey);
        if (attribute == null && attributeKey.getNamespace().equals("minecraft")) {
            String name = attributeKey.getKey();
            String other = name.startsWith("generic.")
                    ? name.substring("generic.".length()) : "generic." + name;
            attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(other));
        }
        if (attribute == null) throw new IllegalArgumentException("Unknown attribute: " + attributeKey);
        EquipmentSlotGroup group = EquipmentSlotGroup.getByName(words[1]);
        if (group == null) throw new IllegalArgumentException("Unknown equipment slot group");
        boolean remove = words.length == 3 && words[2].equalsIgnoreCase("none");
        if (!remove && words.length != 4) {
            throw new IllegalArgumentException("Enter a modifier amount and operation, or none");
        }
        double amount = 0;
        AttributeModifier.Operation operation = null;
        if (!remove) {
            amount = finiteDouble(words[2]);
            if (amount < -1024 || amount > 1024) {
                throw new IllegalArgumentException("Modifier amount must be -1024 through 1024");
            }
            try { operation = AttributeModifier.Operation.valueOf(words[3].toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Use ADD_NUMBER, ADD_SCALAR, or MULTIPLY_SCALAR_1", ex);
            }
        }
        String stableAttribute = attribute.getKey().toString()
                .replace("minecraft:generic.", "minecraft:");
        UUID id = UUID.nameUUIDFromBytes(("recipeweaver:attribute:" + stableAttribute
                + "@" + group).getBytes(StandardCharsets.UTF_8));
        ItemMeta meta = item.getItemMeta();
        var existing = meta.hasAttributeModifiers()
                ? meta.getAttributeModifiers(attribute) : null;
        if (existing != null) {
            for (AttributeModifier modifier : List.copyOf(existing)) {
                if (modifier.getUniqueId().equals(id)) {
                    meta.removeAttributeModifier(attribute, modifier);
                }
            }
        }
        if (!remove) {
            AttributeModifier modifier = new AttributeModifier(id, "RecipeWeaver",
                    amount, operation, group);
            if (!meta.addAttributeModifier(attribute, modifier)) {
                throw new IllegalArgumentException("Could not add the attribute modifier");
            }
        }
        if (!item.setItemMeta(meta)) {
            throw new IllegalArgumentException("This item cannot store the attribute modifier");
        }
    }

    private static void setNamespacedComponent(ItemStack item, String raw,
                                                String methodSuffix, String label) {
        NamespacedKey value = raw != null && raw.strip().equalsIgnoreCase("none")
                ? null : key(raw == null ? null : raw.strip());
        ItemMeta meta = item.getItemMeta();
        setModernComponent(meta, methodSuffix, NamespacedKey.class, value, label);
        item.setItemMeta(meta);
    }

    private static void setModernComponent(ItemMeta meta, String methodSuffix,
                                           Class<?> valueType, Object value, String label) {
        try {
            ItemMeta.class.getMethod("set" + methodSuffix, valueType).invoke(meta, (Object) value);
        } catch (NoSuchMethodException ex) {
            throw new IllegalArgumentException("This server does not support the " + label
                    + " item component", ex);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Could not edit the " + label + " item component", ex);
        } catch (InvocationTargetException ex) {
            throw new IllegalArgumentException("The server rejected the " + label
                    + " item component", ex.getCause());
        }
    }

    static void editFireworkEffect(ItemStack item, String raw) {
        if (!(item.getItemMeta() instanceof FireworkMeta firework)) {
            throw new IllegalArgumentException("This item is not a firework rocket");
        }
        String value = raw == null ? "" : raw.strip();
        if (value.equalsIgnoreCase("remove-last")) {
            if (!firework.hasEffects()) throw new IllegalArgumentException("Firework has no effect to remove");
            firework.removeEffect(firework.getEffectsSize() - 1);
        } else {
            String[] words = value.split("\\s+");
            if (words.length != 2) throw new IllegalArgumentException("Enter effect type and #RRGGBB color");
            if (firework.getEffectsSize() >= 8) {
                throw new IllegalArgumentException("A firework may have at most eight edited effects");
            }
            FireworkEffect.Type type;
            try { type = FireworkEffect.Type.valueOf(words[0].toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Unknown firework effect type", ex);
            }
            firework.addEffect(FireworkEffect.builder().with(type).withColor(color(words[1])).build());
        }
        item.setItemMeta(firework);
    }

    static void setPersistentString(ItemStack item, String page) {
        setPersistentData(item, page);
    }

    /** Parses one complete /give item argument without executing a command or changing the draft on failure. */
    static ItemStack parseItemText(String text) {
        return parseItemText(text, Bukkit.getItemFactory()::createItemStack);
    }

    /** Separate parser seam because MockBukkit does not implement ItemFactory#createItemStack. */
    static ItemStack parseItemText(String text, Function<String, ItemStack> parser) {
        String input = validateItemText(text);
        ItemStack parsed;
        try {
            parsed = parser.apply(input);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid item text for this server version", ex);
        }
        if (parsed == null || parsed.getType().isAir() || parsed.getAmount() != 1) {
            throw new IllegalArgumentException("Item text must create exactly one non-air item");
        }
        return parsed;
    }

    /** The Bukkit parser accepts a valid prefix, so require one bounded, balanced full expression first. */
    private static String validateItemText(String text) {
        if (text == null || text.isBlank() || text.length() > 4096) {
            throw new IllegalArgumentException("Enter one item argument of at most 4096 characters");
        }
        String input = text.strip();
        int componentStart = input.indexOf('[');
        String itemId = componentStart < 0 ? input : input.substring(0, componentStart);
        if (!itemId.matches("[a-z0-9._-]+:[a-z0-9._/-]+")) {
            throw new IllegalArgumentException("Start with one explicit namespace:item key");
        }
        if (componentStart < 0) return input;
        if (!input.endsWith("]")) {
            throw new IllegalArgumentException("Item components must end with ]");
        }
        char[] delimiters = new char[32];
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = componentStart; index < input.length(); index++) {
            char current = input.charAt(index);
            if (Character.isISOControl(current)) {
                throw new IllegalArgumentException("Item text must stay on one line");
            }
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == quote) quote = 0;
                continue;
            }
            if (current == '\'' || current == '"') {
                quote = current;
            } else if (current == '[' || current == '{' || current == '(') {
                if (depth == delimiters.length) {
                    throw new IllegalArgumentException("Item text nests too deeply");
                }
                delimiters[depth++] = current;
            } else if (current == ']' || current == '}' || current == ')') {
                if (depth == 0 || !matchingDelimiter(delimiters[--depth], current)) {
                    throw new IllegalArgumentException("Item text has mismatched delimiters");
                }
                if (depth == 0 && index != input.length() - 1) {
                    throw new IllegalArgumentException("Enter only one item argument");
                }
            }
        }
        if (quote != 0 || depth != 0) {
            throw new IllegalArgumentException("Item text has an unfinished value");
        }
        return input;
    }

    private static boolean matchingDelimiter(char opening, char closing) {
        return opening == '[' && closing == ']' || opening == '{' && closing == '}'
                || opening == '(' && closing == ')';
    }

    /** Edits one Bukkit persistent-data value while retaining all other item metadata. */
    static void setPersistentData(ItemStack item, String page) {
        if (page == null || page.length() > 8192) {
            throw new IllegalArgumentException("Enter a key and value on one book page");
        }
        String[] lines = page.replace("\r", "").split("\n", 2);
        if (lines.length != 2 || lines[1].length() > 4096) {
            throw new IllegalArgumentException("Use namespace:key on line one and a typed value or none on line two");
        }
        NamespacedKey dataKey = key(lines[0].strip());
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        String value = lines[1];
        if (value.strip().equalsIgnoreCase("none")) {
            data.remove(dataKey);
        } else if (!value.startsWith("@")) {
            // Existing two-line pages remain plain strings.
            data.set(dataKey, PersistentDataType.STRING, value);
        } else {
            int separator = value.indexOf(' ');
            String type = separator < 0 ? value : value.substring(0, separator);
            String raw = separator < 0 ? "" : value.substring(separator + 1);
            try {
                if (type.equalsIgnoreCase("@compound")) editPersistentCompound(data, dataKey, raw);
                else if (type.equalsIgnoreCase("@compoundarray")) {
                    editPersistentCompoundArray(data, dataKey, raw);
                }
                else writePersistentValue(data, dataKey, type, raw);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid " + type + " persistent-data value", ex);
            }
        }
        item.setItemMeta(meta);
    }

    /** Edits a bounded path of nested PDC compounds without replacing sibling keys. */
    private static void editPersistentCompound(PersistentDataContainer data,
                                               NamespacedKey outerKey, String raw) {
        int separator = raw.indexOf(' ');
        if (separator <= 0 || separator == raw.length() - 1) {
            throw new IllegalArgumentException("Use @compound namespace:child[>namespace:grandchild] @type value, or none");
        }
        String[] parts = raw.substring(0, separator).split(">", -1);
        if (parts.length < 1 || parts.length > 3) {
            throw new IllegalArgumentException("A compound path may have 1 through 3 child keys");
        }
        NamespacedKey[] path = new NamespacedKey[parts.length];
        for (int index = 0; index < parts.length; index++) path[index] = key(parts[index]);
        String value = raw.substring(separator + 1);
        if (value.length() > 4096 || value.isBlank()) {
            throw new IllegalArgumentException("Compound value must fit one editor page");
        }
        editCompoundPath(data, outerKey, path, 0, value);
    }

    /** Edits an indexed list of nested compounds without replacing other elements. */
    private static void editPersistentCompoundArray(PersistentDataContainer data,
                                                    NamespacedKey arrayKey, String raw) {
        if (data.has(arrayKey) && !data.has(arrayKey, PersistentDataType.TAG_CONTAINER_ARRAY)) {
            throw new IllegalArgumentException("The key already stores a different persistent-data type");
        }
        PersistentDataContainer[] stored = data.get(arrayKey, PersistentDataType.TAG_CONTAINER_ARRAY);
        List<PersistentDataContainer> entries = new ArrayList<>();
        if (stored != null) {
            entries.addAll(Arrays.asList(stored));
        }
        int space = raw.indexOf(' ');
        String operation = (space < 0 ? raw : raw.substring(0, space)).strip();
        String argument = space < 0 ? "" : raw.substring(space + 1);
        if (operation.equalsIgnoreCase("remove")) {
            int index = parseCompoundArrayIndex(argument, entries.size());
            entries.remove(index);
        } else {
            boolean append = operation.equalsIgnoreCase("append");
            int index = append ? entries.size() : parseCompoundArrayIndex(operation, entries.size());
            if (append && entries.size() >= 32) {
                throw new IllegalArgumentException("A compound array may have at most 32 elements");
            }
            if (argument.isBlank() || argument.length() > 4096) {
                throw new IllegalArgumentException("Enter a compound path and typed value");
            }
            PersistentDataContainer element = append
                    ? data.getAdapterContext().newPersistentDataContainer() : entries.get(index);
            editPersistentArrayElement(element, argument);
            if (append && element.isEmpty()) {
                throw new IllegalArgumentException("An appended compound array element needs a value");
            }
            if (append) entries.add(element);
            else if (element.isEmpty()) entries.remove(index);
        }
        if (entries.isEmpty()) data.remove(arrayKey);
        else data.set(arrayKey, PersistentDataType.TAG_CONTAINER_ARRAY,
                entries.toArray(PersistentDataContainer[]::new));
    }

    private static int parseCompoundArrayIndex(String raw, int size) {
        String token = raw.strip();
        if (token.isEmpty() || !token.matches("[0-9]+")) {
            throw new IllegalArgumentException("Compound array index must be a nonnegative number");
        }
        int index = Integer.parseInt(token);
        if (index >= size) throw new IllegalArgumentException("Compound array index does not exist");
        return index;
    }

    private static void editPersistentArrayElement(PersistentDataContainer element, String raw) {
        int space = raw.indexOf(' ');
        if (space <= 0 || space == raw.length() - 1) {
            throw new IllegalArgumentException("Use namespace:child[>namespace:grandchild] @type value, or none");
        }
        String[] parts = raw.substring(0, space).split(">", -1);
        if (parts.length < 1 || parts.length > 4) {
            throw new IllegalArgumentException("An array element path may have 1 through 4 keys");
        }
        NamespacedKey[] path = new NamespacedKey[parts.length];
        for (int i = 0; i < parts.length; i++) path[i] = key(parts[i]);
        String value = raw.substring(space + 1);
        if (value.isBlank()) throw new IllegalArgumentException("Enter a typed value or none");
        if (path.length > 1) {
            editCompoundPath(element, path[0], Arrays.copyOfRange(path, 1, path.length), 0, value);
            return;
        }
        NamespacedKey leaf = path[0];
        if (!element.has(leaf) && !value.strip().equalsIgnoreCase("none")
                && element.getKeys().size() >= 64) {
            throw new IllegalArgumentException("A compound may have at most 64 edited keys");
        }
        if (value.strip().equalsIgnoreCase("none")) {
            element.remove(leaf);
        } else if (!value.startsWith("@")) {
            element.set(leaf, PersistentDataType.STRING, value);
        } else {
            int typeEnd = value.indexOf(' ');
            String type = typeEnd < 0 ? value : value.substring(0, typeEnd);
            if (type.equalsIgnoreCase("@compound") || type.equalsIgnoreCase("@compoundarray")) {
                throw new IllegalArgumentException("Enter nested keys with > before the typed value");
            }
            writePersistentValue(element, leaf, type,
                    typeEnd < 0 ? "" : value.substring(typeEnd + 1));
        }
    }

    private static void editCompoundPath(PersistentDataContainer data, NamespacedKey outerKey,
                                         NamespacedKey[] path, int depth, String value) {
        if (data.has(outerKey) && !data.has(outerKey, PersistentDataType.TAG_CONTAINER)) {
            throw new IllegalArgumentException("The outer key already stores a non-compound value");
        }
        PersistentDataContainer compound = data.has(outerKey, PersistentDataType.TAG_CONTAINER)
                ? data.get(outerKey, PersistentDataType.TAG_CONTAINER)
                : data.getAdapterContext().newPersistentDataContainer();
        if (compound == null) throw new IllegalStateException("Could not read nested item data");
        NamespacedKey childKey = path[depth];
        if (!compound.has(childKey) && !value.strip().equalsIgnoreCase("none")
                && compound.getKeys().size() >= 64) {
            throw new IllegalArgumentException("A compound may have at most 64 edited keys");
        }
        if (depth + 1 < path.length) {
            editCompoundPath(compound, childKey, path, depth + 1, value);
        } else if (value.strip().equalsIgnoreCase("none")) {
            compound.remove(childKey);
        } else if (!value.startsWith("@")) {
            compound.set(childKey, PersistentDataType.STRING, value);
        } else {
            int typeEnd = value.indexOf(' ');
            String type = typeEnd < 0 ? value : value.substring(0, typeEnd);
            if (type.equalsIgnoreCase("@compound")) {
                throw new IllegalArgumentException("Enter nested keys with > before the typed value");
            }
            String typedValue = typeEnd < 0 ? "" : value.substring(typeEnd + 1);
            writePersistentValue(compound, childKey, type, typedValue);
        }
        if (compound.isEmpty()) data.remove(outerKey);
        else data.set(outerKey, PersistentDataType.TAG_CONTAINER, compound);
    }

    private static void writePersistentValue(PersistentDataContainer data, NamespacedKey key,
                                             String type, String raw) {
        switch (type.toLowerCase(Locale.ROOT)) {
            case "@string" -> data.set(key, PersistentDataType.STRING, raw);
            case "@byte" -> data.set(key, PersistentDataType.BYTE, Byte.parseByte(raw.strip()));
            case "@short" -> data.set(key, PersistentDataType.SHORT, Short.parseShort(raw.strip()));
            case "@int" -> data.set(key, PersistentDataType.INTEGER, Integer.parseInt(raw.strip()));
            case "@long" -> data.set(key, PersistentDataType.LONG, Long.parseLong(raw.strip()));
            case "@float" -> data.set(key, PersistentDataType.FLOAT, finiteFloat(raw));
            case "@double" -> data.set(key, PersistentDataType.DOUBLE, finiteDouble(raw));
            case "@bytes" -> data.set(key, PersistentDataType.BYTE_ARRAY, bytes(raw));
            case "@ints" -> data.set(key, PersistentDataType.INTEGER_ARRAY, integers(raw));
            case "@longs" -> data.set(key, PersistentDataType.LONG_ARRAY, longs(raw));
            case "@strings" -> data.set(key, PersistentDataType.LIST.strings(), strings(raw));
            case "@bytelist" -> data.set(key, PersistentDataType.LIST.bytes(),
                    numericList(raw, Byte::parseByte));
            case "@shortlist" -> data.set(key, PersistentDataType.LIST.shorts(),
                    numericList(raw, Short::parseShort));
            case "@intlist" -> data.set(key, PersistentDataType.LIST.integers(),
                    Arrays.stream(integers(raw)).boxed().toList());
            case "@longlist" -> data.set(key, PersistentDataType.LIST.longs(),
                    Arrays.stream(longs(raw)).boxed().toList());
            case "@floatlist" -> data.set(key, PersistentDataType.LIST.floats(),
                    numericList(raw, ItemDraftEditor::finiteFloat));
            case "@doublelist" -> data.set(key, PersistentDataType.LIST.doubles(),
                    numericList(raw, ItemDraftEditor::finiteDouble));
            case "@bytearraylist" -> data.set(key, PersistentDataType.LIST.byteArrays(),
                    arrayList(raw, ItemDraftEditor::bytes));
            case "@intarraylist" -> data.set(key, PersistentDataType.LIST.integerArrays(),
                    arrayList(raw, ItemDraftEditor::integers));
            case "@longarraylist" -> data.set(key, PersistentDataType.LIST.longArrays(),
                    arrayList(raw, ItemDraftEditor::longs));
            default -> throw new IllegalArgumentException("Unknown persistent-data type: " + type);
        }
    }

    private static float finiteFloat(String raw) {
        float value = Float.parseFloat(raw.strip());
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Float must be finite");
        return value;
    }

    private static double finiteDouble(String raw) {
        double value = Double.parseDouble(raw.strip());
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Double must be finite");
        return value;
    }

    private static byte[] bytes(String raw) {
        String hex = raw.strip();
        if (hex.isEmpty() || hex.length() > 1024 || (hex.length() & 1) != 0
                || !hex.matches("[0-9a-fA-F]+")) {
            throw new IllegalArgumentException("Byte array needs 1 through 512 bytes of even-length hex");
        }
        return HexFormat.of().parseHex(hex);
    }

    private static int[] integers(String raw) {
        String[] parts = arrayParts(raw);
        int[] values = new int[parts.length];
        for (int index = 0; index < parts.length; index++) {
            values[index] = Integer.parseInt(parts[index].strip());
        }
        return values;
    }

    private static long[] longs(String raw) {
        String[] parts = arrayParts(raw);
        long[] values = new long[parts.length];
        for (int index = 0; index < parts.length; index++) {
            values[index] = Long.parseLong(parts[index].strip());
        }
        return values;
    }

    private static <T> List<T> numericList(String raw, Function<String, T> parser) {
        String[] parts = arrayParts(raw);
        List<T> values = new ArrayList<>(parts.length);
        for (String part : parts) values.add(parser.apply(part.strip()));
        return List.copyOf(values);
    }

    private static <T> List<T> arrayList(String raw, Function<String, T> parser) {
        String[] lines = raw.replace("\r", "").split("\n", -1);
        if (lines.length < 1 || lines.length > 16) {
            throw new IllegalArgumentException("Array lists need 1 through 16 lines");
        }
        List<T> values = new ArrayList<>(lines.length);
        for (String line : lines) {
            if (line.isBlank()) throw new IllegalArgumentException("Array-list entries cannot be blank");
            values.add(parser.apply(line));
        }
        return List.copyOf(values);
    }

    private static List<String> strings(String raw) {
        String[] values = raw.replace("\r", "").split("\n", -1);
        if (values.length < 1 || values.length > 32) {
            throw new IllegalArgumentException("String lists need 1 through 32 lines");
        }
        for (String value : values) {
            if (value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException("String list entries need 1 through 256 characters");
            }
        }
        return List.of(values);
    }

    private static String[] arrayParts(String raw) {
        String[] parts = raw.split(",", -1);
        if (parts.length < 1 || parts.length > 64) {
            throw new IllegalArgumentException("Persistent-data arrays need 1 through 64 numbers");
        }
        return parts;
    }

    record PersistentEntry(NamespacedKey key, String type, String preview, String editorValue) { }

    /** Lists existing values, including unknown types, so they can be found and replaced in game. */
    static List<PersistentEntry> persistentEntries(ItemStack item) {
        return persistentEntries(item.getItemMeta().getPersistentDataContainer(), false);
    }

    /** Lists nested leaves with editor-ready paths under one top-level compound. */
    static List<PersistentEntry> persistentCompoundEntries(ItemStack item, NamespacedKey outerKey) {
        PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
        if (!data.has(outerKey, PersistentDataType.TAG_CONTAINER)) return List.of();
        List<PersistentEntry> entries = new ArrayList<>();
        collectCompoundEntries(data.get(outerKey, PersistentDataType.TAG_CONTAINER), "", 0, entries);
        entries.sort(Comparator.comparing(PersistentEntry::editorValue));
        return List.copyOf(entries);
    }

    /** Lists each compound-array element and its typed leaves with editable indexes. */
    static List<PersistentEntry> persistentCompoundArrayEntries(ItemStack item,
                                                               NamespacedKey arrayKey) {
        PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
        PersistentDataContainer[] array = data.get(arrayKey, PersistentDataType.TAG_CONTAINER_ARRAY);
        if (array == null) return List.of();
        List<PersistentEntry> entries = new ArrayList<>();
        for (int index = 0; index < array.length; index++) {
            String prefix = "Element " + index;
            entries.add(new PersistentEntry(arrayKey, "element", prefix + ": "
                    + array[index].getKeys().size() + " keys", "@compoundarray remove " + index));
            List<PersistentEntry> children = new ArrayList<>();
            collectCompoundEntries(array[index], "", 0, children);
            children.sort(Comparator.comparing(PersistentEntry::preview));
            for (PersistentEntry child : children) {
                String editor = child.editorValue();
                if (editor.startsWith("@compound ")) {
                    editor = "@compoundarray " + index + " " + editor.substring(10);
                } else editor = "";
                entries.add(new PersistentEntry(child.key(), child.type(),
                        prefix + " / " + child.preview(), editor));
            }
        }
        return List.copyOf(entries);
    }

    private static void collectCompoundEntries(PersistentDataContainer data, String prefix,
                                               int depth, List<PersistentEntry> entries) {
        if (data == null) return;
        for (PersistentEntry entry : persistentEntries(data, false)) {
            String path = prefix.isEmpty() ? entry.key().toString() : prefix + ">" + entry.key();
            if (entry.type().equals("compound") && depth < 2) {
                collectCompoundEntries(data.get(entry.key(), PersistentDataType.TAG_CONTAINER),
                        path, depth + 1, entries);
            } else {
                String editor = entry.type().equals("compound")
                        ? "@compound " + path + " none"
                        : entry.editorValue().isBlank() ? ""
                        : "@compound " + path + " " + entry.editorValue();
                entries.add(new PersistentEntry(entry.key(), entry.type(),
                        prefix.isEmpty() ? entry.preview() : path + " = " + entry.preview(),
                        editor));
            }
        }
    }

    private static List<PersistentEntry> persistentEntries(PersistentDataContainer data,
                                                           boolean nested) {
        List<PersistentEntry> entries = new ArrayList<>();
        for (NamespacedKey key : data.getKeys()) {
            String type;
            String value;
            if (data.has(key, PersistentDataType.STRING)) {
                type = "string";
                value = "@string " + data.get(key, PersistentDataType.STRING);
            } else if (data.has(key, PersistentDataType.BYTE)) {
                type = "byte";
                value = "@byte " + data.get(key, PersistentDataType.BYTE);
            } else if (data.has(key, PersistentDataType.SHORT)) {
                type = "short";
                value = "@short " + data.get(key, PersistentDataType.SHORT);
            } else if (data.has(key, PersistentDataType.INTEGER)) {
                type = "int";
                value = "@int " + data.get(key, PersistentDataType.INTEGER);
            } else if (data.has(key, PersistentDataType.LONG)) {
                type = "long";
                value = "@long " + data.get(key, PersistentDataType.LONG);
            } else if (data.has(key, PersistentDataType.FLOAT)) {
                type = "float";
                value = "@float " + data.get(key, PersistentDataType.FLOAT);
            } else if (data.has(key, PersistentDataType.DOUBLE)) {
                type = "double";
                value = "@double " + data.get(key, PersistentDataType.DOUBLE);
            } else if (data.has(key, PersistentDataType.BYTE_ARRAY)) {
                type = "bytes";
                value = "@bytes " + HexFormat.of().formatHex(
                        data.get(key, PersistentDataType.BYTE_ARRAY));
            } else if (data.has(key, PersistentDataType.INTEGER_ARRAY)) {
                type = "ints";
                value = "@ints " + join(data.get(key, PersistentDataType.INTEGER_ARRAY));
            } else if (data.has(key, PersistentDataType.LONG_ARRAY)) {
                type = "longs";
                value = "@longs " + join(data.get(key, PersistentDataType.LONG_ARRAY));
            } else if (isListOf(data, key, PersistentDataType.LIST.strings(), String.class)) {
                type = "strings";
                value = "@strings " + String.join("\n",
                        data.get(key, PersistentDataType.LIST.strings()));
            } else if (isListOf(data, key, PersistentDataType.LIST.bytes(), Byte.class)) {
                type = "bytelist";
                value = "@bytelist " + joinNumbers(data.get(key, PersistentDataType.LIST.bytes()));
            } else if (isListOf(data, key, PersistentDataType.LIST.shorts(), Short.class)) {
                type = "shortlist";
                value = "@shortlist " + joinNumbers(data.get(key, PersistentDataType.LIST.shorts()));
            } else if (isListOf(data, key, PersistentDataType.LIST.integers(), Integer.class)) {
                type = "intlist";
                value = "@intlist " + data.get(key, PersistentDataType.LIST.integers())
                        .stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
            } else if (isListOf(data, key, PersistentDataType.LIST.longs(), Long.class)) {
                type = "longlist";
                value = "@longlist " + data.get(key, PersistentDataType.LIST.longs())
                        .stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
            } else if (isListOf(data, key, PersistentDataType.LIST.floats(), Float.class)) {
                type = "floatlist";
                value = "@floatlist " + joinNumbers(data.get(key, PersistentDataType.LIST.floats()));
            } else if (isListOf(data, key, PersistentDataType.LIST.doubles(), Double.class)) {
                type = "doublelist";
                value = "@doublelist " + joinNumbers(data.get(key, PersistentDataType.LIST.doubles()));
            } else if (isListOf(data, key, PersistentDataType.LIST.byteArrays(), byte[].class)) {
                type = "bytearraylist";
                value = "@bytearraylist " + data.get(key, PersistentDataType.LIST.byteArrays())
                        .stream().map(HexFormat.of()::formatHex)
                        .collect(java.util.stream.Collectors.joining("\n"));
            } else if (isListOf(data, key, PersistentDataType.LIST.integerArrays(), int[].class)) {
                type = "intarraylist";
                value = "@intarraylist " + data.get(key, PersistentDataType.LIST.integerArrays())
                        .stream().map(ItemDraftEditor::join)
                        .collect(java.util.stream.Collectors.joining("\n"));
            } else if (isListOf(data, key, PersistentDataType.LIST.longArrays(), long[].class)) {
                type = "longarraylist";
                value = "@longarraylist " + data.get(key, PersistentDataType.LIST.longArrays())
                        .stream().map(ItemDraftEditor::join)
                        .collect(java.util.stream.Collectors.joining("\n"));
            } else if (data.has(key, PersistentDataType.TAG_CONTAINER)) {
                type = "compound";
                value = "@compound ";
            } else if (data.has(key, PersistentDataType.TAG_CONTAINER_ARRAY)) {
                type = "compoundarray";
                value = "@compoundarray append recipeweaver:child @string value";
            } else {
                type = "other";
                value = "";
            }
            String shown = type.equals("compound")
                    ? "compound: " + data.get(key, PersistentDataType.TAG_CONTAINER).getKeys().size() + " keys"
                    : type.equals("compoundarray")
                            ? "compound array: "
                                    + data.get(key, PersistentDataType.TAG_CONTAINER_ARRAY).length
                                    + " elements"
                            : value.replace("\n", "\\n");
            if (shown.length() > 80) shown = shown.substring(0, 77) + "...";
            entries.add(new PersistentEntry(key, type, shown,
                    nested && type.equals("compound") ? "" : value.length() <= 4096
                            ? nested ? "@compound " + key + " " + value : value : ""));
        }
        entries.sort(Comparator.comparing(entry -> entry.key().toString()));
        return List.copyOf(entries);
    }

    private static boolean isListOf(PersistentDataContainer data, NamespacedKey key,
                                    PersistentDataType<?, ?> type, Class<?> elementType) {
        try {
            Object value = data.get(key, type);
            return value instanceof List<?> list && !list.isEmpty()
                    && list.stream().allMatch(elementType::isInstance);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static String join(int[] values) {
        return Arrays.stream(values).mapToObj(Integer::toString)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String join(long[] values) {
        return Arrays.stream(values).mapToObj(Long::toString)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String joinNumbers(List<? extends Number> values) {
        return values.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    static ItemStack resolveImport(String raw,
                                   Function<NamespacedKey, Optional<ItemStack>> lookup) {
        NamespacedKey itemKey = key(raw == null ? null : raw.strip());
        ItemStack resolved = lookup.apply(itemKey).orElseThrow(() ->
                new IllegalArgumentException("No saved or enabled-provider item found: " + itemKey));
        if (resolved.getType().isAir() || resolved.getAmount() < 1) {
            throw new IllegalArgumentException("Item provider returned an empty item");
        }
        return resolved.clone();
    }

    private static Color color(String raw) {
        String hex = raw.startsWith("#") ? raw.substring(1) : raw;
        if (!hex.matches("[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("Enter a six-digit color such as #AA44FF");
        }
        return Color.fromRGB(Integer.parseInt(hex, 16));
    }

    static void toggleUnbreakable(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        meta.setUnbreakable(!meta.isUnbreakable());
        item.setItemMeta(meta);
    }

    static void cycleEnchantmentGlint(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        Boolean current = meta.hasEnchantmentGlintOverride()
                ? meta.getEnchantmentGlintOverride() : null;
        meta.setEnchantmentGlintOverride(current == null ? Boolean.TRUE
                : current ? Boolean.FALSE : null);
        item.setItemMeta(meta);
    }

    static void toggleFireResistance(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        GridResultMetadata.setFireResistance(meta, !meta.isFireResistant());
        item.setItemMeta(meta);
    }

    static void toggleHideTooltip(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        meta.setHideTooltip(!meta.isHideTooltip());
        item.setItemMeta(meta);
    }

    static void setMaxStackSize(ItemStack item, String raw) {
        ItemMeta meta = item.getItemMeta();
        if (raw != null && raw.strip().equalsIgnoreCase("none")) {
            meta.setMaxStackSize(null);
        } else {
            int size = parseInt(raw, "Max stack size must be 1 through 99, or none");
            if (size < 1 || size > 99) {
                throw new IllegalArgumentException("Max stack size must be 1 through 99, or none");
            }
            if (item.getAmount() > size) {
                throw new IllegalArgumentException("Set the draft amount to " + size + " or less first");
            }
            meta.setMaxStackSize(size);
        }
        item.setItemMeta(meta);
    }

    static void setRarity(ItemStack item, String raw) {
        ItemMeta meta = item.getItemMeta();
        String value = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (value.equals("DEFAULT") || value.equals("NONE")) meta.setRarity(null);
        else {
            ItemRarity rarity;
            try { rarity = ItemRarity.valueOf(value); }
            catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Enter default, common, uncommon, rare, or epic", ex);
            }
            meta.setRarity(rarity);
        }
        item.setItemMeta(meta);
    }

    static void toggleFlag(ItemStack item, ItemFlag flag) {
        ItemMeta meta = item.getItemMeta();
        if (meta.hasItemFlag(flag)) meta.removeItemFlags(flag);
        else meta.addItemFlags(flag);
        item.setItemMeta(meta);
    }

    private static NamespacedKey key(String raw) {
        if (raw == null || !raw.contains(":")) {
            throw new IllegalArgumentException("Use a namespaced key such as minecraft:sharpness");
        }
        NamespacedKey key = NamespacedKey.fromString(raw.toLowerCase(Locale.ROOT));
        if (key == null || !raw.equalsIgnoreCase(key.toString())) {
            throw new IllegalArgumentException("Invalid namespaced key");
        }
        return key;
    }

    private static int parseInt(String raw, String message) {
        try { return Integer.parseInt(raw == null ? "" : raw.strip()); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(message, ex); }
    }
}
