package dev.recipeweaver.core;

import dev.recipeweaver.api.ItemProvider;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Optional plugin adapters. Lookup is fresh on each request so item reloads are visible. */
final class ExternalItemHooks implements Listener {
    private static final Map<String, String> NAMESPACES = Map.of(
            "ItemsAdder", "itemsadder", "Oraxen", "oraxen",
            "MMOItems", "mmoitems", "MythicMobs", "mythicmobs");

    private final ItemProviders providers;
    private final Consumer<String> warning;

    ExternalItemHooks(ItemProviders providers, Consumer<String> warning) {
        this.providers = providers;
        this.warning = warning;
    }

    void installPresent() {
        for (String name : NAMESPACES.keySet()) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
            if (plugin != null && plugin.isEnabled()) install(plugin);
        }
    }

    @EventHandler void onEnable(PluginEnableEvent event) { install(event.getPlugin()); }

    @EventHandler void onDisable(PluginDisableEvent event) { providers.removeOwner(event.getPlugin()); }

    private void install(Plugin owner) {
        String namespace = NAMESPACES.get(owner.getName());
        if (namespace == null || !owner.isEnabled()) return;
        try {
            if (!providers.register(owner, namespace, providerFor(owner))) {
                warning.accept("Item provider namespace " + namespace + " is already registered");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            warning.accept("Could not connect " + owner.getName() + " item API: " + ex);
        }
    }

    static ItemProvider providerFor(Plugin owner) throws ReflectiveOperationException {
        ClassLoader loader = owner.getClass().getClassLoader();
        return switch (owner.getName()) {
            case "ItemsAdder" -> {
                Class<?> stack = loader.loadClass("dev.lone.itemsadder.api.CustomStack");
                Method get = stack.getMethod("getInstance", String.class);
                Method build = stack.getMethod("getItemStack");
                yield key -> {
                    String path = key.getKey();
                    int slash = path.indexOf('/');
                    if (slash < 1 || slash == path.length() - 1) return null;
                    Object custom = invoke(get, null,
                            path.substring(0, slash) + ":" + path.substring(slash + 1));
                    return custom == null ? null : item(invoke(build, custom));
                };
            }
            case "Oraxen" -> {
                Class<?> items = loader.loadClass("io.th0rgal.oraxen.api.OraxenItems");
                Method get = items.getMethod("getItemById", String.class);
                Method build = get.getReturnType().getMethod("build");
                Method names = optionalMethod(items, "getNames");
                yield key -> {
                    String id = key.getKey();
                    Object builder = invoke(get, null, id);
                    if (builder == null && names != null) {
                        // NamespacedKey folds IDs to lowercase, while Oraxen keeps configured IDs.
                        Object loaded = invoke(names, null);
                        if (loaded instanceof Collection<?> values) {
                            String match = null;
                            for (Object value : values) {
                                if (!(value instanceof String candidate) || !candidate.equalsIgnoreCase(id)) continue;
                                if (match != null && !match.equals(candidate)) return null;
                                match = candidate;
                            }
                            if (match != null) builder = invoke(get, null, match);
                        }
                    }
                    return builder == null ? null : item(invoke(build, builder));
                };
            }
            case "MMOItems" -> {
                Method get = owner.getClass().getMethod("getItem", String.class, String.class);
                yield key -> {
                    String path = key.getKey();
                    int slash = path.indexOf('/');
                    if (slash < 1 || slash == path.length() - 1) return null;
                    String type = path.substring(0, slash);
                    String id = path.substring(slash + 1);
                    String canonicalType = type.toUpperCase(Locale.ROOT);
                    // MMOItems types are canonical uppercase, but configured item IDs can be mixed case.
                    ItemStack stack = item(invoke(get, owner, canonicalType, id));
                    if (stack != null) return stack;
                    String canonicalId = id.toUpperCase(Locale.ROOT);
                    if (!canonicalId.equals(id)) {
                        stack = item(invoke(get, owner, canonicalType, canonicalId));
                        if (stack != null) return stack;
                    }
                    if (!canonicalType.equals(type)) {
                        stack = item(invoke(get, owner, type, id));
                        if (stack != null) return stack;
                    }
                    // NamespacedKey is lowercase, while MMOItems permits mixed-case template IDs.
                    String registeredId = mmoTemplateName(owner, canonicalType, id);
                    return registeredId == null ? null : item(invoke(get, owner, canonicalType, registeredId));
                };
            }
            case "MythicMobs" -> {
                Method managerMethod = owner.getClass().getMethod("getItemManager");
                yield key -> {
                    Object activeManager = invoke(managerMethod, owner);
                    if (activeManager == null) return null;
                    String id = key.getKey();
                    // MythicMobs may replace its manager on reload. Resolve methods on the current manager.
                    Object known = invoke(method(activeManager.getClass(), "getItemNames"), activeManager);
                    if (!(known instanceof Collection<?> values)) return null;
                    String match = null;
                    for (Object value : values) {
                        if (!(value instanceof String candidate) || !candidate.equalsIgnoreCase(id)) continue;
                        if (match != null && !match.equals(candidate)) return null;
                        match = candidate;
                    }
                    // getItemStack(String) falls back to vanilla Material names for unknown Mythic IDs.
                    if (match == null) return null;
                    id = match;
                    Method direct = optionalMethod(activeManager.getClass(), "getItemStack", String.class);
                    if (direct != null) return item(invoke(direct, activeManager, id));
                    // getItem(String) belongs to MythicMobs' published ItemManager interface;
                    // getItemStack(String) only exists on some concrete managers.
                    Object found = invoke(method(activeManager.getClass(), "getItem", String.class), activeManager, id);
                    if (found instanceof Optional<?> optional) found = optional.orElse(null);
                    if (found == null) return null;
                    Object abstractStack = invoke(method(found.getClass(), "generateItemStack", int.class), found, 1);
                    if (abstractStack == null) return null;
                    try {
                        Class<?> adapter = loader.loadClass("io.lumine.mythic.bukkit.BukkitAdapter");
                        Class<?> abstractType = loader.loadClass("io.lumine.mythic.api.adapters.AbstractItemStack");
                        return item(invoke(adapter.getMethod("adapt", abstractType), null, abstractStack));
                    } catch (ReflectiveOperationException ex) {
                        throw new IllegalStateException("Mythic item conversion unavailable", ex);
                    }
                };
            }
            default -> throw new IllegalArgumentException("Unsupported item plugin: " + owner.getName());
        };
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) {
        Method method = optionalMethod(type, name, parameters);
        if (method == null) throw new IllegalStateException("External item API missing " + name);
        return method;
    }

    private static Method optionalMethod(Class<?> type, String name, Class<?>... parameters) {
        try { return type.getMethod(name, parameters); }
        catch (NoSuchMethodException ex) { return null; }
    }

    private static String mmoTemplateName(Plugin owner, String typeName, String requestedId) {
        Method typesMethod = optionalMethod(owner.getClass(), "getTypes");
        Method templatesMethod = optionalMethod(owner.getClass(), "getTemplates");
        if (typesMethod == null || templatesMethod == null) return null;
        Object types = invoke(typesMethod, owner);
        Object templates = invoke(templatesMethod, owner);
        if (types == null || templates == null) return null;
        Object type = invoke(method(types.getClass(), "get", String.class), types, typeName);
        if (type == null) return null;
        Method namesMethod = Arrays.stream(templates.getClass().getMethods())
                .filter(candidate -> candidate.getName().equals("getTemplateNames")
                        && candidate.getParameterCount() == 1
                        && candidate.getParameterTypes()[0].isInstance(type))
                .findFirst().orElse(null);
        if (namesMethod == null) return null;
        Object names = invoke(namesMethod, templates, type);
        if (!(names instanceof Collection<?> values)) return null;
        String match = null;
        for (Object value : values) {
            if (!(value instanceof String candidate) || !candidate.equalsIgnoreCase(requestedId)) continue;
            if (match != null) return null; // An ID with ambiguous casing cannot be represented by NamespacedKey.
            match = candidate;
        }
        return match;
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try { return method.invoke(target, args); }
        catch (IllegalAccessException | InvocationTargetException ex) {
            throw new IllegalStateException("External item lookup failed", ex);
        }
    }

    private static ItemStack item(Object value) {
        return value instanceof ItemStack stack ? stack : null;
    }
}
