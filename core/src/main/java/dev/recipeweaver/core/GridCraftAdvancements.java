package dev.recipeweaver.core;

import dev.recipeweaver.api.CraftAdvancementAction;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;

/** Awards advancement criteria only after the grid inventory transaction commits. */
final class GridCraftAdvancements {
    private GridCraftAdvancements() { }

    static void award(Player player, List<CraftAdvancementAction> committedBatches) {
        award(player, committedBatches, Bukkit::getAdvancement);
    }

    static void award(Player player, List<CraftAdvancementAction> committedBatches,
                      Function<NamespacedKey, Advancement> lookup) {
        Set<NamespacedKey> awardedThisClick = new HashSet<>();
        for (CraftAdvancementAction action : committedBatches) {
            if (action == null || !awardedThisClick.add(action.advancement())) continue;
            Advancement advancement = lookup.apply(action.advancement());
            if (advancement == null) continue;
            AdvancementProgress progress = player.getAdvancementProgress(advancement);
            for (String criterion : List.copyOf(progress.getRemainingCriteria())) {
                progress.awardCriteria(criterion);
            }
        }
    }
}
