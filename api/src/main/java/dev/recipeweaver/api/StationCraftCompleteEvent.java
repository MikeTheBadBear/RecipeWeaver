package dev.recipeweaver.api;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after an owned station result commits, once per brewed bottle or verified cooking result.
 * This event cannot cancel a craft. Cooking completion is verified on the next tick; an output
 * removed before then can suppress this conservative event. {@link #player()} is null for
 * automated brewing and cooking. */
public final class StationCraftCompleteEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final StationCraftBatch batch;

    public StationCraftCompleteEvent(Player player, StationCraftBatch batch) {
        this.batch = Objects.requireNonNull(batch, "batch");
        this.player = batch.kind() == StationKind.BREWING || batch.kind() == StationKind.FURNACE
                || batch.kind() == StationKind.BLAST_FURNACE || batch.kind() == StationKind.SMOKER
                || batch.kind() == StationKind.CAMPFIRE
                ? player : Objects.requireNonNull(player, "player");
    }

    public Player player() { return player; }
    public StationCraftBatch batch() { return batch; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
