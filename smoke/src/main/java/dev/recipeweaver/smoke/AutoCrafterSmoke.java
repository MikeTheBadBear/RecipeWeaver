package dev.recipeweaver.smoke;

import dev.recipeweaver.api.RecipeDefinition;
import dev.recipeweaver.api.RecipeKind;
import dev.recipeweaver.api.RecipeWeaverApi;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Crafter;
import org.bukkit.block.data.type.Crafter.Orientation;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/** Tests native redstone-powered crafters against both RecipeWeaver workbench kinds. */
final class AutoCrafterSmoke {
    private AutoCrafterSmoke() { }

    static Runnable prepare(RecipeWeaverApi api, World world, JavaPlugin smoke) {
        boolean observable = hasCrafterCraftEvent();
        List<ItemStack> shapelessInputs = emptyGrid();
        shapelessInputs.set(0, new ItemStack(Material.NETHERRACK));
        ItemStack shapelessResult = named(Material.DIAMOND, "RecipeWeaver shapeless auto craft");
        save(api, "smoke_auto_shapeless", RecipeKind.SHAPELESS, shapelessInputs,
                shapelessResult, observable);

        List<ItemStack> shapedInputs = emptyGrid();
        shapedInputs.set(0, new ItemStack(Material.COBBLESTONE));
        shapedInputs.set(1, new ItemStack(Material.NETHERRACK));
        ItemStack shapedResult = named(Material.EMERALD, "RecipeWeaver shaped auto craft");
        save(api, "smoke_auto_shaped", RecipeKind.SHAPED, shapedInputs,
                shapedResult, observable);

        Probe shapeless = place(world, 8, List.of(new ItemStack(Material.NETHERRACK, 2)),
                shapelessResult);
        Probe shaped = place(world, 12, List.of(new ItemStack(Material.COBBLESTONE, 2),
                new ItemStack(Material.NETHERRACK, 2)), shapedResult);
        Bukkit.getScheduler().runTaskLater(smoke, () -> {
            shapeless.power().setType(Material.REDSTONE_BLOCK);
            shaped.power().setType(Material.REDSTONE_BLOCK);
        }, 20L);
        AtomicReference<Throwable> mismatchFailure = new AtomicReference<>();
        AtomicBoolean mismatchArmed = new AtomicBoolean();
        Bukkit.getScheduler().runTaskLater(smoke, () -> {
            try {
                if (observable) {
                    verify(world, shapeless);
                    verify(world, shaped);
                } else {
                    verifyWithheld(world, shapeless);
                    verifyWithheld(world, shaped);
                }
                armExactMismatch(shapeless);
                armExactMismatch(shaped);
                mismatchArmed.set(true);
                Bukkit.getScheduler().runTaskLater(smoke, () -> {
                    shapeless.power().setType(Material.REDSTONE_BLOCK);
                    shaped.power().setType(Material.REDSTONE_BLOCK);
                }, 2L);
            } catch (Throwable failure) {
                mismatchFailure.set(failure);
            }
        }, 80L);
        return () -> {
            if (mismatchFailure.get() != null) {
                throw new IllegalStateException("Could not arm exact-choice crafter mismatch",
                        mismatchFailure.get());
            }
            check(mismatchArmed.get(), "Exact-choice crafter mismatch pulse was not armed");
            verifyExactMismatch(world, shapeless, observable);
            smoke.getLogger().info("RW_AUTOCRAFTER_SHAPELESS_EXACT_DENIAL_PASS");
            verifyExactMismatch(world, shaped, observable);
            smoke.getLogger().info("RW_AUTOCRAFTER_SHAPED_EXACT_DENIAL_PASS");
        };
    }

    private static List<ItemStack> emptyGrid() {
        return new ArrayList<>(Arrays.asList(new ItemStack[9]));
    }

    private static ItemStack named(Material material, String name) {
        ItemStack result = new ItemStack(material);
        ItemMeta meta = result.getItemMeta();
        meta.setDisplayName(name);
        result.setItemMeta(meta);
        return result;
    }

    private static void save(RecipeWeaverApi api, String id, RecipeKind kind,
                             List<ItemStack> inputs, ItemStack result, boolean observable) {
        NamespacedKey key = new NamespacedKey("recipeweaver", id);
        api.saveRecipe(new RecipeDefinition(key, kind, inputs,
                result, "smoke", true, false));
        check(api.recipe(key).isPresent(), "Auto-crafter recipe was not saved: " + key);
        check((Bukkit.getRecipe(key) != null) == observable,
                "Auto-crafter exact recipe registration was unsafe: " + key);
    }

    private static Probe place(World world, int offset, List<ItemStack> inputs,
                               ItemStack result) {
        Location spawn = world.getSpawnLocation();
        Block block = world.getBlockAt(spawn.getBlockX() + offset,
                spawn.getBlockY() + 10, spawn.getBlockZ() + offset);
        block.getChunk().setForceLoaded(true);
        Block power = block.getRelative(BlockFace.WEST);
        power.setType(Material.AIR);
        Block output = block.getRelative(BlockFace.EAST);
        output.setType(Material.CHEST);
        ((Chest) output.getState()).getBlockInventory().clear();
        block.setType(Material.CRAFTER);
        org.bukkit.block.data.type.Crafter data =
                (org.bukkit.block.data.type.Crafter) block.getBlockData();
        data.setOrientation(Orientation.EAST_UP);
        block.setBlockData(data);
        Crafter state = (Crafter) block.getState();
        for (int slot = 0; slot < 9; slot++) state.setSlotDisabled(slot, false);
        check(state.update(true, false), "Could not configure auto-crafter slots");
        Crafter live = (Crafter) block.getState();
        for (int slot = 0; slot < 9; slot++) live.getInventory().setItem(slot, null);
        for (int slot = 0; slot < inputs.size(); slot++) {
            live.getInventory().setItem(slot, inputs.get(slot).clone());
        }
        for (Entity entity : world.getNearbyEntities(block.getLocation(), 10, 20, 10)) {
            if (entity instanceof Item item && item.getItemStack().isSimilar(result)) {
                item.remove();
            }
        }
        return new Probe(block, power, output, inputs, result);
    }

    private static void verify(World world, Probe probe) {
        Crafter current = (Crafter) probe.block().getState();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack remaining = current.getInventory().getItem(slot);
            if (slot < probe.inputs().size()) {
                ItemStack expected = probe.inputs().get(slot);
                check(remaining != null && remaining.isSimilar(expected)
                                && remaining.getAmount() == expected.getAmount() - 1,
                        "Auto-crafter consumed the wrong input in slot " + slot + ": " + remaining);
            } else check(remaining == null || remaining.getType().isAir(),
                    "Auto-crafter changed an empty slot " + slot);
        }
        check(outputCount(world, probe) == probe.result().getAmount(),
                "Auto-crafter emitted the wrong number of custom results");
    }

    private static void verifyWithheld(World world, Probe probe) {
        Crafter current = (Crafter) probe.block().getState();
        for (int slot = 0; slot < probe.inputs().size(); slot++) {
            ItemStack remaining = current.getInventory().getItem(slot);
            check(remaining != null && remaining.isSimilar(probe.inputs().get(slot))
                            && remaining.getAmount() == 2,
                    "Withheld exact recipe consumed an input in slot " + slot);
        }
        check(outputCount(world, probe) == 0,
                "Withheld exact recipe produced an output");
    }

    private static void armExactMismatch(Probe probe) {
        probe.power().setType(Material.AIR);
        Crafter live = (Crafter) probe.block().getState();
        live.getInventory().clear();
        ItemStack changed = named(probe.inputs().get(0).getType(),
                "RecipeWeaver nonmatching exact ingredient");
        live.getInventory().setItem(0, changed);
        for (int slot = 1; slot < probe.inputs().size(); slot++) {
            ItemStack next = probe.inputs().get(slot).clone();
            next.setAmount(1);
            live.getInventory().setItem(slot, next);
        }
    }

    private static void verifyExactMismatch(World world, Probe probe, boolean observable) {
        Crafter live = (Crafter) probe.block().getState();
        ItemStack first = live.getInventory().getItem(0);
        check(first != null && first.isSimilar(named(probe.inputs().get(0).getType(),
                        "RecipeWeaver nonmatching exact ingredient"))
                        && first.getAmount() == 1,
                "Auto-crafter consumed a nonmatching exact ingredient");
        for (int slot = 1; slot < probe.inputs().size(); slot++) {
            ItemStack remaining = live.getInventory().getItem(slot);
            check(remaining != null && remaining.isSimilar(probe.inputs().get(slot))
                            && remaining.getAmount() == 1,
                    "Auto-crafter consumed a sibling of a nonmatching exact ingredient");
        }
        check(outputCount(world, probe) == (observable ? probe.result().getAmount() : 0),
                "Auto-crafter produced an extra result from a nonmatching exact ingredient");
    }

    private static boolean hasCrafterCraftEvent() {
        try {
            return org.bukkit.event.Cancellable.class.isAssignableFrom(
                    Class.forName("org.bukkit.event.block.CrafterCraftEvent"));
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private static int outputCount(World world, Probe probe) {
        int output = 0;
        for (ItemStack item : ((Chest) probe.output().getState()).getBlockInventory().getContents()) {
            if (item != null && item.isSimilar(probe.result())) output += item.getAmount();
        }
        for (Entity entity : world.getNearbyEntities(probe.block().getLocation(), 10, 20, 10)) {
            if (entity instanceof Item item && item.getItemStack().isSimilar(probe.result())) {
                output += item.getItemStack().getAmount();
            }
        }
        return output;
    }

    private record Probe(Block block, Block power, Block output, List<ItemStack> inputs,
                         ItemStack result) { }

    private static void check(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
