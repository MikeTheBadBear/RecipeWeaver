package dev.recipeweaver.core;

import org.bukkit.plugin.Plugin;

/** Keeps focused synchronous test helpers out of live RecipeWeaver transactions. */
final class LegacyIoGuard {
    private LegacyIoGuard() { }

    static void testOnly(Plugin plugin) {
        if (plugin instanceof RecipeWeaverPlugin) {
            throw new IllegalStateException(
                    "Synchronous storage helper is unavailable in the live plugin");
        }
    }
}
