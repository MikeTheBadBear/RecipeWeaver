package dev.recipeweaver.core;

import dev.recipeweaver.api.GridCraftAction;
import dev.recipeweaver.api.GridCraftBatch;
import dev.recipeweaver.api.GridCraftCondition;
import dev.recipeweaver.api.GridRecipeDefinition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Runtime grid rule and action callbacks, ordered by registration on the server thread. */
final class GridExtensions implements Listener {
    private static final class ConditionEntry {
        final Plugin owner;
        final GridCraftCondition callback;
        boolean warned;
        ConditionEntry(Plugin owner, GridCraftCondition callback) {
            this.owner = owner;
            this.callback = callback;
        }
    }

    private static final class ActionEntry {
        final Plugin owner;
        final boolean perBatch;
        final GridCraftAction callback;
        boolean warned;
        ActionEntry(Plugin owner, boolean perBatch, GridCraftAction callback) {
            this.owner = owner;
            this.perBatch = perBatch;
            this.callback = callback;
        }
    }

    private final Map<NamespacedKey, List<ConditionEntry>> conditions = new LinkedHashMap<>();
    private final Map<NamespacedKey, List<ActionEntry>> actions = new LinkedHashMap<>();
    private final Consumer<String> warning;

    GridExtensions(Consumer<String> warning) { this.warning = Objects.requireNonNull(warning); }

    void registerCondition(Plugin owner, NamespacedKey recipe, GridCraftCondition condition) {
        requireOwner(owner);
        Objects.requireNonNull(recipe, "recipe");
        Objects.requireNonNull(condition, "condition");
        List<ConditionEntry> entries = conditions.computeIfAbsent(recipe, ignored -> new ArrayList<>());
        entries.removeIf(entry -> entry.owner == owner);
        entries.add(new ConditionEntry(owner, condition));
    }

    boolean unregisterCondition(Plugin owner, NamespacedKey recipe) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(recipe, "recipe");
        List<ConditionEntry> entries = conditions.get(recipe);
        if (entries == null) return false;
        boolean removed = entries.removeIf(entry -> entry.owner == owner);
        if (entries.isEmpty()) conditions.remove(recipe);
        return removed;
    }

    void registerAction(Plugin owner, NamespacedKey recipe, boolean perBatch, GridCraftAction action) {
        requireOwner(owner);
        Objects.requireNonNull(recipe, "recipe");
        Objects.requireNonNull(action, "action");
        List<ActionEntry> entries = actions.computeIfAbsent(recipe, ignored -> new ArrayList<>());
        entries.removeIf(entry -> entry.owner == owner);
        entries.add(new ActionEntry(owner, perBatch, action));
    }

    boolean unregisterAction(Plugin owner, NamespacedKey recipe) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(recipe, "recipe");
        List<ActionEntry> entries = actions.get(recipe);
        if (entries == null) return false;
        boolean removed = entries.removeIf(entry -> entry.owner == owner);
        if (entries.isEmpty()) actions.remove(recipe);
        return removed;
    }

    boolean allows(Player player, GridRecipeDefinition recipe, List<ItemStack> inputs) {
        List<ConditionEntry> entries = conditions.get(recipe.key());
        if (entries == null) return true;
        for (ConditionEntry entry : List.copyOf(entries)) {
            if (!registered(conditions, recipe.key(), entry) || !entry.owner.isEnabled()) continue;
            try {
                if (!entry.callback.allows(player, recipe, copiedInputs(inputs))) return false;
            } catch (RuntimeException | LinkageError ex) {
                if (!entry.warned) {
                    entry.warned = true;
                    warning.accept("Grid condition from " + entry.owner.getName() + " failed for "
                            + recipe.key() + ": " + ex);
                }
                return false;
            }
        }
        return true;
    }

    void runActions(Player player, List<GridCraftBatch> batches) {
        Set<ActionEntry> playedOnce = Collections.newSetFromMap(new IdentityHashMap<>());
        for (GridCraftBatch batch : batches) {
            List<ActionEntry> entries = actions.get(batch.recipe());
            if (entries == null) continue;
            for (ActionEntry entry : List.copyOf(entries)) {
                if (!registered(actions, batch.recipe(), entry) || !entry.owner.isEnabled()
                        || (!entry.perBatch && !playedOnce.add(entry))) continue;
                try { entry.callback.execute(player, batch); }
                catch (RuntimeException | LinkageError ex) {
                    if (!entry.warned) {
                        entry.warned = true;
                        warning.accept("Grid action from " + entry.owner.getName() + " failed for "
                                + batch.recipe() + ": " + ex);
                    }
                }
            }
        }
    }

    @EventHandler void onPluginDisable(PluginDisableEvent event) { removeOwner(event.getPlugin()); }

    void removeOwner(Plugin owner) {
        conditions.entrySet().removeIf(entry -> {
            entry.getValue().removeIf(registered -> registered.owner == owner);
            return entry.getValue().isEmpty();
        });
        actions.entrySet().removeIf(entry -> {
            entry.getValue().removeIf(registered -> registered.owner == owner);
            return entry.getValue().isEmpty();
        });
    }

    void clear() { conditions.clear(); actions.clear(); }

    private static <T> boolean registered(Map<NamespacedKey, List<T>> entries,
                                          NamespacedKey recipe, T entry) {
        List<T> current = entries.get(recipe);
        return current != null && current.contains(entry);
    }

    private static List<ItemStack> copiedInputs(List<ItemStack> inputs) {
        List<ItemStack> snapshot = new ArrayList<>(inputs.size());
        for (ItemStack item : inputs) snapshot.add(item == null ? null : item.clone());
        return Collections.unmodifiableList(snapshot);
    }

    private static void requireOwner(Plugin owner) {
        Objects.requireNonNull(owner, "owner");
        if (!owner.isEnabled()) throw new IllegalStateException("Grid extension owner is disabled");
    }
}
