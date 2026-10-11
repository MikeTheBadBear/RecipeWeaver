package dev.recipeweaver.smoke;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Optional live check against PlaceholderAPI and its Server expansion. */
final class PlaceholderIntegrationSmoke {
    private PlaceholderIntegrationSmoke() { }

    static void verifyIfInstalled(Plugin harness) throws Exception {
        Plugin placeholder = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (placeholder == null || !placeholder.isEnabled()) return;
        Plugin recipeWeaver = Bukkit.getPluginManager().getPlugin("RecipeWeaver");
        if (recipeWeaver == null || !recipeWeaver.isEnabled()) {
            throw new IllegalStateException("RecipeWeaver is unavailable for PlaceholderAPI check");
        }
        Class<?> hook = recipeWeaver.getClass().getClassLoader()
                .loadClass("dev.recipeweaver.core.PlaceholderHook");
        Method expand = hook.getDeclaredMethod("expand", Player.class, String.class);
        expand.setAccessible(true);
        String actual;
        try {
            actual = (String) expand.invoke(null, null, "say %server_online%");
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            throw new IllegalStateException("RecipeWeaver PlaceholderAPI hook failed", cause);
        }
        String expected = "say " + Bukkit.getOnlinePlayers().size();
        if (!expected.equals(actual)) {
            throw new IllegalStateException("PlaceholderAPI Server expansion was not applied: "
                    + actual + " expected " + expected);
        }
        harness.getLogger().info("RW_PLACEHOLDERAPI_LIVE_PASS");
    }
}
