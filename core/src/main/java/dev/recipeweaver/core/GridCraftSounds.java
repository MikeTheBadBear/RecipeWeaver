package dev.recipeweaver.core;

import dev.recipeweaver.api.CraftSoundAction;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

/** Dispatches sound actions only after the grid inventory transaction has committed. */
final class GridCraftSounds {
    private GridCraftSounds() { }

    static void play(Player player, List<CraftSoundAction> committedBatches) {
        Set<CraftSoundAction> playedOnce = new HashSet<>();
        for (CraftSoundAction action : committedBatches) {
            if (action == null || (!action.perBatch() && !playedOnce.add(action))) continue;
            player.playSound(player.getLocation(), action.sound().toString(),
                    SoundCategory.PLAYERS, action.volume(), action.pitch());
        }
    }
}
