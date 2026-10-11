package dev.recipeweaver.api;

import java.util.Objects;
import org.bukkit.NamespacedKey;

/** Sound played for a committed custom-grid craft. Custom resource-pack sound keys are allowed. */
public record CraftSoundAction(NamespacedKey sound, float volume, float pitch, boolean perBatch) {
    public CraftSoundAction {
        Objects.requireNonNull(sound, "sound");
        if (!Float.isFinite(volume) || volume <= 0.0F || volume > 4.0F) {
            throw new IllegalArgumentException("Craft sound volume must be above 0 and at most 4");
        }
        if (!Float.isFinite(pitch) || pitch < 0.5F || pitch > 2.0F) {
            throw new IllegalArgumentException("Craft sound pitch must be 0.5 through 2");
        }
    }
}
