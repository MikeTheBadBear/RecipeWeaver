package dev.recipeweaver.api;

import java.util.List;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after a player's custom-grid inventory transaction commits. This event cannot cancel a craft. */
public final class GridCraftCompleteEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final List<GridCraftBatch> batches;

    public GridCraftCompleteEvent(Player player, List<GridCraftBatch> batches) {
        this.player = Objects.requireNonNull(player, "player");
        if (batches == null || batches.isEmpty() || batches.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("A completed grid craft needs committed batches");
        }
        this.batches = List.copyOf(batches);
    }

    public Player player() { return player; }
    public List<GridCraftBatch> batches() { return batches; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
