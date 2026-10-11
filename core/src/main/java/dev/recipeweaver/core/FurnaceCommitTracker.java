package dev.recipeweaver.core;

import dev.recipeweaver.api.StationCraftBatch;
import dev.recipeweaver.api.StationKind;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

/** Conservative, one-use proof that an accepted furnace cook changed both inventory slots. */
final class FurnaceCommitTracker {
    record Position(UUID world, int x, int y, int z) { }

    static final class Pending {
        private final Position position;
        private final NamespacedKey recipe;
        private final StationKind kind;
        private final ItemStack sourceBefore;
        private final ItemStack outputBefore;
        private final ItemStack expectedResult;
        private boolean invalidated;
        private boolean consumed;

        private Pending(Position position, NamespacedKey recipe, StationKind kind,
                ItemStack sourceBefore, ItemStack outputBefore, ItemStack expectedResult) {
            this.position = position;
            this.recipe = recipe;
            this.kind = kind;
            this.sourceBefore = sourceBefore.clone();
            this.outputBefore = present(outputBefore) ? outputBefore.clone() : null;
            this.expectedResult = expectedResult.clone();
        }

        ItemStack expectedResult() { return expectedResult.clone(); }
    }

    private final Map<Position, Pending> active = new HashMap<>();

    Pending begin(Position position, NamespacedKey recipe, StationKind kind,
            ItemStack sourceBefore, ItemStack outputBefore, ItemStack eventSource,
            ItemStack expectedResult) {
        Objects.requireNonNull(position, "position");
        Pending earlier = active.get(position);
        if (earlier != null) {
            // Two events before either post-state is observable cannot be
            // attributed separately. Publish neither completion.
            earlier.invalidated = true;
            return null;
        }
        if (kind != StationKind.FURNACE && kind != StationKind.BLAST_FURNACE
                && kind != StationKind.SMOKER) return null;
        if (!present(sourceBefore) || !present(eventSource)
                || !sourceBefore.isSimilar(eventSource) || !present(expectedResult)) return null;
        if (present(outputBefore) && !outputBefore.isSimilar(expectedResult)) return null;
        Pending pending = new Pending(position, Objects.requireNonNull(recipe, "recipe"),
                kind, sourceBefore, outputBefore, expectedResult);
        active.put(position, pending);
        return pending;
    }

    StationCraftBatch complete(Pending pending, boolean eventAccepted,
            ItemStack sourceAfter, ItemStack outputAfter) {
        if (pending == null || pending.consumed) return null;
        pending.consumed = true;
        if (!active.remove(pending.position, pending) || pending.invalidated
                || !eventAccepted || !consumedOne(pending.sourceBefore, sourceAfter)
                || !insertedResult(pending.outputBefore, pending.expectedResult, outputAfter)) {
            return null;
        }
        return new StationCraftBatch(pending.recipe, pending.kind, pending.expectedResult);
    }

    void discard(Pending pending) { complete(pending, false, null, null); }

    void invalidate(Position position) {
        Pending pending = active.remove(position);
        if (pending != null) pending.invalidated = true;
    }

    private static boolean consumedOne(ItemStack before, ItemStack after) {
        if (before.getAmount() == 1) return !present(after);
        return present(after) && before.isSimilar(after)
                && after.getAmount() == before.getAmount() - 1;
    }

    private static boolean insertedResult(ItemStack before, ItemStack result,
            ItemStack after) {
        if (!present(after) || !after.isSimilar(result)) return false;
        int oldAmount = present(before) ? before.getAmount() : 0;
        return after.getAmount() == oldAmount + result.getAmount();
    }

    private static boolean present(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getAmount() > 0;
    }
}
