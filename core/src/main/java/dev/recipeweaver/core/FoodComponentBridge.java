package dev.recipeweaver.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.FoodComponent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Keeps the 1.20.6 food API and newer consumable APIs behind one editor path. */
final class FoodComponentBridge {
    private static final boolean LEGACY = hasMethod(FoodComponent.class, "setEatSeconds", float.class);

    private FoodComponentBridge() { }

    static boolean legacy() { return LEGACY; }

    static boolean modern() { return !LEGACY; }

    /** Copies consumption settings after the target's Bukkit ItemMeta has been applied. */
    static void copyConsumption(ItemStack source, ItemStack target) {
        if (LEGACY) return;
        if (hasMethod(ItemMeta.class, "getConsumable")) {
            ItemMeta sourceMeta = source.getItemMeta();
            ItemMeta targetMeta = target.getItemMeta();
            Class<?> componentType = type("org.bukkit.inventory.meta.components.consumable.ConsumableComponent");
            boolean present = (boolean) call(ItemMeta.class, sourceMeta, "hasConsumable");
            Object component = present ? call(ItemMeta.class, sourceMeta, "getConsumable") : null;
            call(ItemMeta.class, targetMeta, "setConsumable",
                    new Class<?>[] {componentType}, component);
            target.setItemMeta(targetMeta);
        } else {
            PaperConsumable.copy(source, target);
        }
    }

    record StoredEffect(PotionEffect effect, float probability) { }

    static List<StoredEffect> storedEffects(ItemStack item) {
        return effects(item).stream().map(entry -> new StoredEffect(
                entry.effect(), entry.probability())).toList();
    }

    static void clearStatusEffects(ItemStack item) {
        for (PotionEffectType type : storedEffects(item).stream()
                .map(entry -> entry.effect().getType()).distinct().toList()) {
            editEffect(item, type, 0, 0, 0);
        }
    }

    static void restoreEffect(ItemStack item, PotionEffect effect, float probability) {
        if (LEGACY) {
            ItemMeta meta = item.getItemMeta();
            FoodComponent food = meta.getFood();
            food.addEffect(effect, probability);
            meta.setFood(food);
            item.setItemMeta(meta);
        } else if (hasMethod(ItemMeta.class, "getConsumable")) {
            SpigotConsumable.edit(item, effect.getType(), effect, probability);
        } else {
            PaperConsumable.update(item, null, effect.getType(),
                    new EffectEdit(effect, probability));
        }
    }

    static void setConsumeSeconds(ItemStack item, float seconds) {
        if (LEGACY) throw new IllegalStateException("Legacy food time belongs to FoodComponent");
        if (hasMethod(ItemMeta.class, "getConsumable")) {
            Object meta = item.getItemMeta();
            Class<?> componentType = type("org.bukkit.inventory.meta.components.consumable.ConsumableComponent");
            Object component = call(ItemMeta.class, meta, "getConsumable");
            call(componentType, component, "setConsumeSeconds", new Class<?>[] {float.class}, seconds);
            call(ItemMeta.class, meta, "setConsumable", new Class<?>[] {componentType}, component);
            item.setItemMeta((ItemMeta) meta);
        } else {
            PaperConsumable.update(item, seconds, null, null);
        }
    }

    static void resetConsumable(ItemStack item) {
        if (LEGACY) return;
        if (hasMethod(ItemMeta.class, "getConsumable")) {
            ItemMeta meta = item.getItemMeta();
            Class<?> componentType = type("org.bukkit.inventory.meta.components.consumable.ConsumableComponent");
            call(ItemMeta.class, meta, "setConsumable", new Class<?>[] {componentType}, (Object) null);
            item.setItemMeta(meta);
        } else {
            PaperConsumable.reset(item);
        }
    }

    static void editEffect(ItemStack item, PotionEffectType type, int duration,
            int amplifier, float probability) {
        if (LEGACY) throw new IllegalStateException("Legacy food effects belong to FoodComponent");
        PotionEffect replacement = duration == 0 ? null : new PotionEffect(type, duration, amplifier);
        if (hasMethod(ItemMeta.class, "getConsumable")) {
            SpigotConsumable.edit(item, type, replacement, probability);
        } else {
            PaperConsumable.update(item, null, type, new EffectEdit(replacement, probability));
        }
    }

    static float consumeSeconds(ItemStack item) {
        if (LEGACY) return item.getItemMeta().getFood().getEatSeconds();
        if (hasMethod(ItemMeta.class, "getConsumable")) {
            Object component = call(ItemMeta.class, item.getItemMeta(), "getConsumable");
            return (float) call(type("org.bukkit.inventory.meta.components.consumable.ConsumableComponent"),
                    component, "getConsumeSeconds");
        }
        return PaperConsumable.seconds(item);
    }

    static int effectCount(ItemStack item) {
        if (!item.getItemMeta().hasFood()) return 0;
        if (LEGACY) return item.getItemMeta().getFood().getEffects().size();
        return effects(item).size();
    }

    static boolean hasEffect(ItemStack item, PotionEffectType type, int duration,
            int amplifier, float probability) {
        return effects(item).stream().anyMatch(entry -> entry.effect().getType().equals(type)
                && entry.effect().getDuration() == duration
                && entry.effect().getAmplifier() == amplifier
                && entry.probability() == probability);
    }

    private static List<EffectEdit> effects(ItemStack item) {
        List<EffectEdit> result = new ArrayList<>();
        if (!item.getItemMeta().hasFood()) return result;
        if (LEGACY) {
            for (FoodComponent.FoodEffect entry : item.getItemMeta().getFood().getEffects()) {
                result.add(new EffectEdit(entry.getEffect(), entry.getProbability()));
            }
        } else if (hasMethod(ItemMeta.class, "getConsumable")) {
            SpigotConsumable.collect(item, result);
        } else {
            PaperConsumable.collect(item, result);
        }
        return result;
    }

    private record EffectEdit(PotionEffect effect, float probability) { }

    private static final class SpigotConsumable {
        private static final Class<?> COMPONENT = type(
                "org.bukkit.inventory.meta.components.consumable.ConsumableComponent");
        private static final Class<?> APPLY = type(
                "org.bukkit.inventory.meta.components.consumable.effects.ConsumableApplyEffects");

        private static void collect(ItemStack item, List<EffectEdit> output) {
            Object component = call(ItemMeta.class, item.getItemMeta(), "getConsumable");
            for (Object entry : (List<?>) call(COMPONENT, component, "getEffects")) {
                if (!APPLY.isInstance(entry)) continue;
                float probability = (float) call(APPLY, entry, "getProbability");
                for (Object effect : (List<?>) call(APPLY, entry, "getEffects")) {
                    output.add(new EffectEdit((PotionEffect) effect, probability));
                }
            }
        }

        private static void edit(ItemStack item, PotionEffectType type,
                PotionEffect replacement, float probability) {
            ItemMeta meta = item.getItemMeta();
            Object component = call(ItemMeta.class, meta, "getConsumable");
            List<Object> remaining = new ArrayList<>();
            for (Object entry : (List<?>) call(COMPONENT, component, "getEffects")) {
                if (!APPLY.isInstance(entry)) { remaining.add(entry); continue; }
                List<PotionEffect> kept = new ArrayList<>();
                for (Object effect : (List<?>) call(APPLY, entry, "getEffects")) {
                    PotionEffect potion = (PotionEffect) effect;
                    if (!potion.getType().equals(type)) kept.add(potion);
                }
                if (!kept.isEmpty()) {
                    call(APPLY, entry, "setEffects", new Class<?>[] {List.class}, kept);
                    remaining.add(entry);
                }
            }
            if (replacement != null) {
                Object apply;
                try {
                    // The Spigot map constructor looks up its private ItemMetaKey
                    // instance, whereas its serializer writes the Bukkit string key.
                    // Persist effects separately in ItemRepository to avoid that
                    // incompatible round trip on this server version.
                    Class<?> craftApply = type("org.bukkit.craftbukkit.inventory.components."
                            + "consumable.effects.CraftConsumableApplyEffects");
                    var keyField = craftApply.getDeclaredField("POTIONS");
                    keyField.setAccessible(true);
                    Object effectKey = keyField.get(null);
                    apply = craftApply.getConstructor(Map.class).newInstance(
                            Map.of(effectKey, List.of(replacement), "probability", probability));
                } catch (ReflectiveOperationException ex) {
                    throw unsupported(ex);
                }
                Object handle = call(type("org.bukkit.craftbukkit.inventory.components."
                        + "consumable.effects.CraftConsumableEffect"), apply, "getHandle");
                if (handle == null) throw new IllegalArgumentException(
                        "The server created an empty consumable effect");
                remaining.add(apply);
            }
            for (Object entry : remaining) {
                if (entry == null || call(type("org.bukkit.craftbukkit.inventory.components."
                        + "consumable.effects.CraftConsumableEffect"), entry, "getHandle") == null) {
                    throw new IllegalArgumentException("The server returned an empty existing consumable effect");
                }
            }
            call(COMPONENT, component, "setEffects", new Class<?>[] {List.class}, remaining);
            Object nativeComponent = call(type("org.bukkit.craftbukkit.inventory.components."
                    + "consumable.CraftConsumableComponent"), component, "getHandle");
            List<?> nativeEffects = (List<?>) call(nativeComponent.getClass(), nativeComponent,
                    "onConsumeEffects");
            if (nativeEffects.contains(null)) {
                throw new IllegalArgumentException("Spigot converted a consumable effect to null"
                        + " from " + remaining.stream().map(entry -> entry.getClass().getName()).toList());
            }
            call(COMPONENT, component, "getEffects");
            call(ItemMeta.class, meta, "setConsumable", new Class<?>[] {COMPONENT}, component);
            item.setItemMeta(meta);
        }
    }

    private static final class PaperConsumable {
        private static final Class<?> DATA_TYPE = type("io.papermc.paper.datacomponent.DataComponentType");
        private static final Class<?> VALUED = type("io.papermc.paper.datacomponent.DataComponentType$Valued");
        private static final Class<?> BUILDER = type("io.papermc.paper.datacomponent.DataComponentBuilder");
        private static final Class<?> CONSUMABLE = type("io.papermc.paper.datacomponent.item.Consumable");
        private static final Class<?> APPLY = type(
                "io.papermc.paper.datacomponent.item.consumable.ConsumeEffect$ApplyStatusEffects");
        private static final Class<?> EFFECT = type(
                "io.papermc.paper.datacomponent.item.consumable.ConsumeEffect");

        private static Object key() {
            try { return type("io.papermc.paper.datacomponent.DataComponentTypes")
                    .getField("CONSUMABLE").get(null); }
            catch (ReflectiveOperationException ex) { throw unsupported(ex); }
        }

        private static Object current(ItemStack item) {
            return call(ItemStack.class, item, "getData", new Class<?>[] {VALUED}, key());
        }

        private static void copy(ItemStack source, ItemStack target) {
            Object current = current(source);
            if (current == null) {
                reset(target);
            } else {
                call(ItemStack.class, target, "setData",
                        new Class<?>[] {VALUED, BUILDER}, key(), builder(current));
            }
        }

        private static Object builder(Object current) {
            return current == null ? call(CONSUMABLE, null, "consumable")
                    : call(CONSUMABLE, current, "toBuilder");
        }

        private static void update(ItemStack item, Float seconds,
                PotionEffectType target, EffectEdit replacement) {
            Object current = current(item);
            Object builder = builder(current);
            Class<?> builderType = type("io.papermc.paper.datacomponent.item.Consumable$Builder");
            if (seconds != null) {
                call(builderType, builder, "consumeSeconds", new Class<?>[] {float.class}, seconds);
            }
            if (target != null) {
                List<Object> remaining = new ArrayList<>();
                if (current != null) {
                    for (Object entry : (List<?>) call(CONSUMABLE, current, "consumeEffects")) {
                        if (!APPLY.isInstance(entry)) { remaining.add(entry); continue; }
                        List<PotionEffect> kept = new ArrayList<>();
                        for (Object effect : (List<?>) call(APPLY, entry, "effects")) {
                            PotionEffect potion = (PotionEffect) effect;
                            if (!potion.getType().equals(target)) kept.add(potion);
                        }
                        if (!kept.isEmpty()) remaining.add(apply(kept,
                                (float) call(APPLY, entry, "probability")));
                    }
                }
                if (replacement.effect() != null) remaining.add(apply(
                        List.of(replacement.effect()), replacement.probability()));
                call(builderType, builder, "effects", new Class<?>[] {List.class}, remaining);
            }
            call(ItemStack.class, item, "setData", new Class<?>[] {VALUED, BUILDER}, key(), builder);
        }

        private static Object apply(List<PotionEffect> effects, float probability) {
            return call(EFFECT, null, "applyStatusEffects",
                    new Class<?>[] {List.class, float.class}, effects, probability);
        }

        private static float seconds(ItemStack item) {
            Object current = current(item);
            return current == null ? 0F : (float) call(CONSUMABLE, current, "consumeSeconds");
        }

        private static void collect(ItemStack item, List<EffectEdit> output) {
            Object current = current(item);
            if (current == null) return;
            for (Object entry : (List<?>) call(CONSUMABLE, current, "consumeEffects")) {
                if (!APPLY.isInstance(entry)) continue;
                float probability = (float) call(APPLY, entry, "probability");
                for (Object effect : (List<?>) call(APPLY, entry, "effects")) {
                    output.add(new EffectEdit((PotionEffect) effect, probability));
                }
            }
        }

        private static void reset(ItemStack item) {
            call(ItemStack.class, item, "resetData", new Class<?>[] {DATA_TYPE}, key());
        }
    }

    private static boolean hasMethod(Class<?> owner, String name, Class<?>... parameters) {
        if (owner == null) return false;
        try { owner.getMethod(name, parameters); return true; }
        catch (NoSuchMethodException ex) { return false; }
    }

    private static Class<?> type(String name) {
        try { return Class.forName(name); }
        catch (ClassNotFoundException ex) { throw unsupported(ex); }
    }

    private static Object call(Class<?> owner, Object target, String name) {
        return call(owner, target, name, new Class<?>[0]);
    }

    private static Object call(Class<?> owner, Object target, String name,
            Class<?>[] parameters, Object... args) {
        try {
            Method method = owner.getMethod(name, parameters);
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw unsupported(cause);
        } catch (ReflectiveOperationException ex) {
            throw unsupported(ex);
        }
    }

    private static IllegalArgumentException unsupported(Throwable cause) {
        return new IllegalArgumentException("This server cannot edit the food consumption component", cause);
    }
}
