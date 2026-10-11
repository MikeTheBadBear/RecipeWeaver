package dev.recipeweaver.core;

import dev.recipeweaver.adapters.PlatformAdapter;
import dev.recipeweaver.api.RecipeDefinition;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.bukkit.NamespacedKey;

/** Clears 1.20.6's static crafter lookup cache after a native recipe changes. */
final class CrafterRecipeCache implements PlatformAdapter {
    private final PlatformAdapter delegate;
    private final Object[] entries;

    private CrafterRecipeCache(PlatformAdapter delegate, Object[] entries) {
        this.delegate = delegate;
        this.entries = entries;
    }

    static CrafterRecipeCache open(PlatformAdapter delegate) throws ReflectiveOperationException {
        Class<?> block = Class.forName("net.minecraft.world.level.block.CrafterBlock");
        Field cacheField = null;
        for (Field field : block.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && field.getType().getSimpleName().equals("RecipeCache")) {
                if (cacheField != null) throw new NoSuchFieldException("Multiple crafter recipe caches");
                cacheField = field;
            }
        }
        if (cacheField == null) throw new NoSuchFieldException("Crafter recipe cache");
        cacheField.setAccessible(true);
        Object cache = cacheField.get(null);
        Field entriesField = null;
        for (Field field : cache.getClass().getDeclaredFields()) {
            if (field.getType().isArray()
                    && field.getType().getComponentType().getEnclosingClass() == cache.getClass()) {
                if (entriesField != null) throw new NoSuchFieldException("Multiple cache entry arrays");
                entriesField = field;
            }
        }
        if (entriesField == null) throw new NoSuchFieldException("Crafter recipe cache entries");
        entriesField.setAccessible(true);
        Object value = entriesField.get(cache);
        if (!(value instanceof Object[] entries) || entries.length == 0) {
            throw new NoSuchFieldException("Crafter recipe cache entries unavailable");
        }
        CrafterRecipeCache result = new CrafterRecipeCache(delegate, entries);
        result.clear();
        return result;
    }

    void clear() { Arrays.fill(entries, null); }

    @Override public void register(RecipeDefinition recipe) {
        try { delegate.register(recipe); }
        finally { clear(); }
    }

    @Override public void unregister(NamespacedKey key) {
        try { delegate.unregister(key); }
        finally { clear(); }
    }

    @Override public String platformName() { return delegate.platformName(); }
    @Override public String minecraftVersion() { return delegate.minecraftVersion(); }
}
