package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import dev.recipeweaver.api.CraftCommandAction;
import dev.recipeweaver.api.CraftCommandSender;
import dev.recipeweaver.api.CraftAdvancementAction;
import dev.recipeweaver.api.CraftSoundAction;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationRecipeDefinition;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.recipe.CookingBookCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class AutomatedCookingActionsTest {
    private final CommandSender console = (CommandSender) Proxy.newProxyInstance(
            CommandSender.class.getClassLoader(), new Class<?>[] {CommandSender.class},
            (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });

    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void furnaceSoundUsesCommittedBlockCenter() {
        List<Location> heard = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        AutomatedCookingActions.run(recipe(StationKind.FURNACE, true, sound(), null),
                at("world", 4.5, 72.5, -3.5), (location, action) -> {
                    assertEquals(sound(), action);
                    heard.add(location);
                }, (sender, command) -> true, console, warnings::add);
        assertEquals(1, heard.size());
        assertEquals(4.5, heard.get(0).getX());
        assertEquals(-3.5, heard.get(0).getZ());
        assertTrue(warnings.isEmpty());
    }

    @Test void campfireSoundUsesTheSameCommittedWorldPosition() {
        AtomicInteger sounds = new AtomicInteger();
        AutomatedCookingActions.run(recipe(StationKind.CAMPFIRE, true, sound(), null),
                at("nether", 12.5, 64.5, 3.5), (location, action) -> {
                    assertEquals("nether", location.getWorld().getName());
                    sounds.incrementAndGet();
                }, (sender, command) -> true, console, ignored -> { });
        assertEquals(1, sounds.get());
    }

    @Test void blastFurnaceConsoleCommandUsesWorldAndBlockCoordinates() {
        List<String> sent = new ArrayList<>();
        AutomatedCookingActions.run(recipe(StationKind.BLAST_FURNACE, true, null,
                        command("say {world} {x} {y} {z}", CraftCommandSender.CONSOLE)),
                at("test_world", -2.5, 71.5, 4.5), (location, action) -> { },
                (sender, text) -> { assertSame(console, sender); sent.add(text); return true; },
                console, ignored -> { });
        assertEquals(List.of("say test_world -3 71 4"), sent);
    }

    @Test void smokerConsoleCommandExecutesOncePerCook() {
        AtomicInteger calls = new AtomicInteger();
        AutomatedCookingActions.run(recipe(StationKind.SMOKER, true, null,
                        command("say smoked", CraftCommandSender.CONSOLE)),
                at("world", 0.5, 64.5, 0.5), (location, action) -> { },
                (sender, text) -> { calls.incrementAndGet(); return true; },
                console, ignored -> { });
        assertEquals(1, calls.get());
    }

    @Test void playerSenderCommandIsDeniedWithoutAnActingPlayer() {
        assertThrows(IllegalArgumentException.class, () -> recipe(StationKind.FURNACE,
                true, null, command("say unsafe", CraftCommandSender.PLAYER)));
    }

    @Test void playerNameTokenIsDeniedWithoutAnActingPlayer() {
        assertRejected("say {player}");
    }

    @Test void playerUuidTokenIsDeniedWithoutAnActingPlayer() {
        assertRejected("say {uuid}");
    }

    @Test void placeholderApiTokenIsDeniedWithoutAnActingPlayer() {
        assertRejected("say %player_name%");
    }

    @Test void cookingAdvancementIsRejectedWithoutAnActingPlayer() {
        assertThrows(IllegalArgumentException.class, () -> recipe(StationKind.FURNACE,
                true, null, new CraftAdvancementAction(NamespacedKey.minecraft("story/root")), null));
    }

    @Test void cauldronRetainsPlayerAdvancementAndCommandActions() {
        assertTrue(recipe(StationKind.CAULDRON, true, sound(),
                new CraftAdvancementAction(NamespacedKey.minecraft("story/root")),
                command("say {player}", CraftCommandSender.PLAYER)).craftAdvancement() != null);
    }

    @Test void unsafeWorldExpansionCannotInjectAnotherCommand() {
        List<String> warnings = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        AutomatedCookingActions.run(recipe(StationKind.FURNACE, true, null,
                        command("say {world}", CraftCommandSender.CONSOLE)),
                at("world\nstop", 0, 64, 0), (location, action) -> { },
                (sender, text) -> { calls.incrementAndGet(); return true; }, console, warnings::add);
        assertEquals(0, calls.get());
        assertEquals(1, warnings.size());
    }

    @Test void soundFailureDoesNotSuppressConsoleCommand() {
        List<String> warnings = new ArrayList<>();
        AtomicInteger commands = new AtomicInteger();
        AutomatedCookingActions.run(recipe(StationKind.FURNACE, true, sound(),
                        command("say cooked", CraftCommandSender.CONSOLE)),
                at("world", 0, 64, 0), (location, action) -> {
                    throw new IllegalStateException("sound unavailable");
                }, (sender, text) -> { commands.incrementAndGet(); return true; },
                console, warnings::add);
        assertEquals(1, commands.get());
        assertEquals(1, warnings.size());
    }

    @Test void failingWarningSinkAfterSoundFailureStillRunsConsoleCommand() {
        AtomicInteger commands = new AtomicInteger();
        AutomatedCookingActions.run(recipe(StationKind.FURNACE, true, sound(),
                        command("say cooked", CraftCommandSender.CONSOLE)),
                at("world", 0, 64, 0), (location, action) -> {
                    throw new IllegalStateException("sound unavailable");
                }, (sender, text) -> { commands.incrementAndGet(); return true; },
                console, ignored -> { throw new IllegalStateException("logger unavailable"); });
        assertEquals(1, commands.get());
    }

    @Test void rejectedConsoleCommandReportsFailureWithoutThrowing() {
        List<String> warnings = new ArrayList<>();
        AutomatedCookingActions.run(recipe(StationKind.FURNACE, true, null,
                        command("say cooked", CraftCommandSender.CONSOLE)),
                at("world", 0, 64, 0), (location, action) -> { },
                (sender, text) -> false, console, warnings::add);
        assertEquals(1, warnings.size());
    }

    @Test void throwingConsoleCommandReportsFailureWithoutThrowing() {
        List<String> warnings = new ArrayList<>();
        AutomatedCookingActions.run(recipe(StationKind.FURNACE, true, null,
                        command("say cooked", CraftCommandSender.CONSOLE)),
                at("world", 0, 64, 0), (location, action) -> { },
                (sender, text) -> { throw new IllegalStateException("unavailable"); },
                console, warnings::add);
        assertEquals(1, warnings.size());
    }

    @Test void failingWarningSinkAfterCommandFailureDoesNotUnwindCommittedCook() {
        AtomicInteger calls = new AtomicInteger();
        AutomatedCookingActions.run(recipe(StationKind.SMOKER, true, null,
                        command("say cooked", CraftCommandSender.CONSOLE)),
                at("world", 0, 64, 0), (location, action) -> { },
                (sender, text) -> { calls.incrementAndGet(); throw new IllegalStateException("command failed"); },
                console, ignored -> { throw new IllegalStateException("logger unavailable"); });
        assertEquals(1, calls.get());
    }

    @Test void nonCookingRecipeNeverRunsAnAutomatedCookingAction() {
        AtomicInteger calls = new AtomicInteger();
        AutomatedCookingActions.run(recipe(StationKind.ANVIL, true, sound(),
                        command("say cut", CraftCommandSender.CONSOLE)),
                at("world", 0, 64, 0), (location, action) -> calls.incrementAndGet(),
                (sender, text) -> { calls.incrementAndGet(); return true; }, console, ignored -> { });
        assertEquals(0, calls.get());
    }

    @Test void disabledCookNeverRunsAnAutomatedCookingAction() {
        AtomicInteger calls = new AtomicInteger();
        AutomatedCookingActions.run(recipe(StationKind.CAMPFIRE, false, sound(),
                        command("say cooked", CraftCommandSender.CONSOLE)),
                at("world", 0, 64, 0), (location, action) -> calls.incrementAndGet(),
                (sender, text) -> { calls.incrementAndGet(); return true; }, console, ignored -> { });
        assertEquals(0, calls.get());
    }

    private void assertRejected(String text) {
        assertThrows(IllegalArgumentException.class, () -> recipe(StationKind.FURNACE,
                true, null, command(text, CraftCommandSender.CONSOLE)));
    }

    private static StationRecipeDefinition recipe(StationKind kind, boolean enabled,
                                                  CraftSoundAction sound, CraftCommandAction command) {
        return recipe(kind, enabled, sound, null, command);
    }

    private static StationRecipeDefinition recipe(StationKind kind, boolean enabled,
                                                  CraftSoundAction sound,
                                                  CraftAdvancementAction advancement,
                                                  CraftCommandAction command) {
        List<ItemStack> ingredients = new ArrayList<>(Collections.nCopies(kind.slots(), null));
        ingredients.set(0, new ItemStack(Material.STONE));
        if (kind == StationKind.CAULDRON) ingredients.set(1, new ItemStack(Material.DIRT));
        return new StationRecipeDefinition(new NamespacedKey("recipeweaver", "cooking_actions"),
                kind, ingredients, new ItemStack(Material.DIAMOND),
                "", 20, 0, 0, enabled, false,
                Collections.nCopies(kind.slots(), IngredientMatchMode.EXACT),
                Collections.nCopies(kind.slots(), List.of()),
                Collections.nCopies(kind.slots(), null), CookingBookCategory.MISC, 0, 0,
                RecipeConditions.NONE, sound, advancement, command);
    }

    private static CraftSoundAction sound() {
        return new CraftSoundAction(NamespacedKey.minecraft("block.note_block.pling"),
                1.0F, 1.0F, false);
    }

    private static CraftCommandAction command(String text, CraftCommandSender sender) {
        return new CraftCommandAction(text, sender, false);
    }

    private static Location at(String name, double x, double y, double z) {
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class<?>[] {World.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getName")) return name;
                    throw new UnsupportedOperationException(method.getName());
                });
        return new Location(world, x, y, z);
    }
}
