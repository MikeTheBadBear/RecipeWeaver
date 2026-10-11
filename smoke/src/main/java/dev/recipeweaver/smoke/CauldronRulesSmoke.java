package dev.recipeweaver.smoke;

import dev.recipeweaver.api.CraftCommandAction;
import dev.recipeweaver.api.CraftCommandSender;
import dev.recipeweaver.api.CraftSoundAction;
import dev.recipeweaver.api.IngredientMatchMode;
import dev.recipeweaver.api.RecipeConditions;
import dev.recipeweaver.api.RecipeWeaverApi;
import dev.recipeweaver.api.ResultMetadataMode;
import dev.recipeweaver.api.StationCraftCompleteEvent;
import dev.recipeweaver.api.StationKind;
import dev.recipeweaver.api.StationRecipeDefinition;
import dev.recipeweaver.api.WeightedResult;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.recipe.CookingBookCategory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/** Real water-cauldron block and interaction probes with a synthetic player's inventory. */
final class CauldronRulesSmoke {
    private static final NamespacedKey AMOUNTS = new NamespacedKey(
            "recipeweaver", "smoke_cauldron_amounts_082");
    private static final NamespacedKey KEPT = new NamespacedKey(
            "recipeweaver", "smoke_cauldron_kept_082");
    private static final String FIRST_NAME = "Cauldron first source";
    private static final String SECOND_NAME = "Cauldron kept source";
    private static final String PLAYER_NAME = "RecipeWeaverCauldronSmoke";
    private static final UUID PLAYER_ID = UUID.fromString(
            "12345678-1234-1234-1234-123456789ca0");
    private static final CraftSoundAction SOUND = new CraftSoundAction(
            NamespacedKey.minecraft("block.note_block.bell"), 1.0F, 0.8F, false);
    private static final CraftCommandAction COMMAND = new CraftCommandAction(
            "recipeweaversmokeaction cauldron {player} {uuid}",
            CraftCommandSender.CONSOLE, false);
    private static final AtomicInteger COMMANDS = new AtomicInteger();
    private static final AtomicInteger ACCEPTED_COMMANDS = new AtomicInteger();
    private static volatile BooleanSupplier commandAfterCommit;

    private CauldronRulesSmoke() { }

    /** Called by SmokePlugin's existing recipeweaversmokeaction command for the cauldron subcommand. */
    static boolean onCommand(CommandSender sender, String[] args) {
        COMMANDS.incrementAndGet();
        check(Bukkit.isPrimaryThread() && sender == Bukkit.getConsoleSender()
                        && args.length == 3 && args[0].equals("cauldron")
                        && args[1].equals(PLAYER_NAME)
                        && args[2].equals(PLAYER_ID.toString()),
                "Cauldron action command used the wrong sender or placeholders");
        BooleanSupplier committed = commandAfterCommit;
        check(committed != null && committed.getAsBoolean(),
                "Cauldron command ran before the result and water committed");
        ACCEPTED_COMMANDS.incrementAndGet();
        return true;
    }

    static CompletionStage<Void> verifyAsync(RecipeWeaverApi api, World world,
                                              JavaPlugin harness) {
        COMMANDS.set(0);
        ACCEPTED_COMMANDS.set(0);
        return consumedAmounts(api, world, harness)
                .thenCompose(ignored -> keptWeighted(api, world, harness))
                .whenComplete((ignored, failure) -> commandAfterCommit = null);
    }

    private static CompletionStage<Void> consumedAmounts(RecipeWeaverApi api, World world,
                                                          JavaPlugin harness) {
        try {
            StationRecipeDefinition recipe = recipe(AMOUNTS,
                    List.of(new ItemStack(Material.BLAZE_POWDER, 3),
                            new ItemStack(Material.INK_SAC, 2)),
                    new ItemStack(Material.PAPER), 0, ResultMetadataMode.NAME,
                    List.of(true, true),
                    Arrays.asList(new ItemStack(Material.BUCKET),
                            new ItemStack(Material.GLASS_BOTTLE)),
                    List.of(new WeightedResult(new ItemStack(Material.PAPER), 1)),
                    null, null);
            api.saveStationRecipe(recipe);
            Block block = testBlock(world, 30);
            FakePlayer fake = new FakePlayer(world, block.getLocation());
            return prepare(api, block, fake, harness).thenCompose(ignored -> {
                int water = waterLevel(block);
                fake.storage[0] = named(Material.BLAZE_POWDER, 2, FIRST_NAME);
                fake.storage[1] = new ItemStack(Material.INK_SAC, 4);
                check(!interaction(fake.player, block, fake.storage[0])
                                && count(fake.storage, Material.BLAZE_POWDER) == 2
                                && pending(api, block.getLocation()) == null
                                && waterLevel(block) == water,
                        "A short first cauldron stack started a mix");
                fake.storage[0] = named(Material.BLAZE_POWDER, 5, FIRST_NAME);
                interact(fake.player, block, fake.storage[0]);
                check(count(fake.storage, Material.BLAZE_POWDER) == 2 && busy(api),
                        "Cauldron did not take exactly three first items");
                return awaitIdle(api, harness, 200)
                        .thenRun(() -> check(pendingAmount(api, block.getLocation(),
                                        Material.BLAZE_POWDER) == 3,
                                "Cauldron did not retain the exact first amount"))
                        .thenCompose(done -> api.reloadDataAsync().toCompletableFuture())
                        .thenRun(() -> {
                            check(pendingAmount(api, block.getLocation(),
                                            Material.BLAZE_POWDER) == 3,
                                    "Cauldron first amount did not survive data reload");
                            harness.getLogger().info("RW_CAULDRON_FIRST_AMOUNT_PERSISTED_PASS");
                            fake.held = 1;
                            fake.storage[1] = new ItemStack(Material.INK_SAC);
                            interact(fake.player, block, fake.storage[1]);
                            check(count(fake.storage, Material.INK_SAC) == 1
                                            && pendingAmount(api, block.getLocation(),
                                                    Material.BLAZE_POWDER) == 3
                                            && waterLevel(block) == water,
                                    "A short second cauldron stack changed the pending mix");
                            harness.getLogger().info("RW_CAULDRON_SECOND_AMOUNT_GUARD_PASS");
                            fake.storage[1] = new ItemStack(Material.INK_SAC, 4);
                            interact(fake.player, block, fake.storage[1]);
                            check(count(fake.storage, Material.INK_SAC) == 2 && busy(api),
                                    "Cauldron did not take exactly two second items");
                        }).thenCompose(done -> awaitIdle(api, harness, 200))
                        .thenRun(() -> {
                            check(pending(api, block.getLocation()) == null
                                            && waterLevel(block) == water - 1
                                            && count(fake.storage, Material.BLAZE_POWDER) == 2
                                            && count(fake.storage, Material.INK_SAC) == 2
                                            && count(fake.storage, Material.PAPER) == 1,
                                    "Cauldron amounts, output, or water duplicated at commit");
                            harness.getLogger().info("RW_CAULDRON_MULTI_AMOUNT_PASS");
                            check(count(fake.storage, Material.BUCKET) == 1
                                            && count(fake.storage, Material.GLASS_BOTTLE) == 1,
                                    "Consumed cauldron slots did not each return one item");
                            harness.getLogger().info("RW_CAULDRON_REMAINDERS_PASS");
                            check(namedCount(fake.storage, Material.PAPER, FIRST_NAME) == 1,
                                    "Cauldron output did not copy the actual first input name");
                            harness.getLogger().info("RW_CAULDRON_COPY_FIRST_PASS");
                        });
            });
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static CompletionStage<Void> keptWeighted(RecipeWeaverApi api, World world,
                                                       JavaPlugin harness) {
        Listener listener = new Listener() { };
        try {
            StationRecipeDefinition recipe = recipe(KEPT,
                    List.of(new ItemStack(Material.PRISMARINE_SHARD, 2),
                            new ItemStack(Material.REDSTONE, 3)),
                    new ItemStack(Material.EMERALD), 1, ResultMetadataMode.NAME,
                    List.of(true, false), Arrays.asList(new ItemStack(Material.STICK), null),
                    List.of(new WeightedResult(new ItemStack(Material.EMERALD), 3),
                            new WeightedResult(new ItemStack(Material.DIAMOND), 1)),
                    SOUND, COMMAND);
            api.saveStationRecipe(recipe);
            Block block = testBlock(world, 31);
            FakePlayer fake = new FakePlayer(world, block.getLocation());
            AtomicInteger events = new AtomicInteger();
            AtomicBoolean validEvent = new AtomicBoolean();
            AtomicInteger startingWater = new AtomicInteger();
            Bukkit.getPluginManager().registerEvent(StationCraftCompleteEvent.class, listener,
                    EventPriority.NORMAL, (registered, fired) -> {
                        StationCraftCompleteEvent event = (StationCraftCompleteEvent) fired;
                        if (!event.batch().recipe().equals(KEPT)) return;
                        events.incrementAndGet();
                        ItemStack output = selected(fake.storage);
                        validEvent.set(event.player() == fake.player
                                && event.batch().kind() == StationKind.CAULDRON
                                && output != null && event.batch().result().isSimilar(output)
                                && count(fake.storage, Material.REDSTONE) == 5
                                && waterLevel(block) == startingWater.get() - 1);
                    }, harness);
            return prepare(api, block, fake, harness).thenCompose(ignored -> {
                int water = waterLevel(block);
                startingWater.set(water);
                fake.onSound = () -> check(selected(fake.storage) != null
                                && count(fake.storage, Material.REDSTONE) == 5
                                && waterLevel(block) == water - 1,
                        "Cauldron sound ran before result and water committed");
                commandAfterCommit = () -> selected(fake.storage) != null
                        && count(fake.storage, Material.REDSTONE) == 5
                        && waterLevel(block) == water - 1;
                return keptAttempt(api, harness, block, fake, Material.OBSIDIAN)
                        .thenRun(() -> {
                            deniedKeptState(api, block, fake, water, events.get());
                            check(count(fake.storage, Material.OBSIDIAN) == 5,
                                    "Changing the kept second item was overwritten");
                            harness.getLogger().info(
                                    "RW_CAULDRON_KEPT_SECOND_CHANGED_GUARD_PASS");
                        }).thenCompose(done -> keptAttempt(api, harness, block, fake, null))
                        .thenRun(() -> {
                            deniedKeptState(api, block, fake, water, events.get());
                            check(fake.storage[1] == null,
                                    "Removing the kept second item was overwritten");
                            harness.getLogger().info(
                                    "RW_CAULDRON_KEPT_SECOND_REMOVED_GUARD_PASS");
                        }).thenCompose(done -> keptAttempt(api, harness, block, fake,
                                Material.REDSTONE))
                        .thenRun(() -> {
                            ItemStack output = selected(fake.storage);
                            check(pending(api, block.getLocation()) == null
                                            && waterLevel(block) == water - 1
                                            && count(fake.storage, Material.PRISMARINE_SHARD) == 2
                                            && count(fake.storage, Material.REDSTONE) == 5
                                            && count(fake.storage, Material.STICK) == 1,
                                    "Kept second cauldron input or first remainder was lost");
                            harness.getLogger().info("RW_CAULDRON_KEPT_SECOND_PASS");
                            check(output != null && output.getAmount() == 1
                                            && weightedCount(fake.storage) == 1
                                            && SECOND_NAME.equals(output.getItemMeta().getDisplayName()),
                                    "Weighted cauldron result was duplicated or lost copied metadata");
                            harness.getLogger().info("RW_CAULDRON_WEIGHTED_COPY_SECOND_PASS");
                            check(fake.sounds.get() == 1 && COMMANDS.get() == 1
                                            && ACCEPTED_COMMANDS.get() == 1
                                            && events.get() == 1 && validEvent.get(),
                                    "Cauldron actions or completion event missed the committed result");
                            harness.getLogger().info("RW_CAULDRON_ACTIONS_POSTCOMMIT_PASS");
                        });
            }).whenComplete((ignored, failure) -> HandlerList.unregisterAll(listener));
        } catch (Throwable failure) {
            HandlerList.unregisterAll(listener);
            return CompletableFuture.failedFuture(failure);
        }
    }

    /** Runs one first/second interaction pair. A null or different replacement denies commit. */
    private static CompletionStage<Void> keptAttempt(RecipeWeaverApi api, JavaPlugin harness,
                                                      Block block, FakePlayer fake,
                                                      Material replacement) {
        fake.storage[0] = new ItemStack(Material.PRISMARINE_SHARD, 4);
        fake.storage[1] = named(Material.REDSTONE, 5, SECOND_NAME);
        fake.held = 0;
        interact(fake.player, block, fake.storage[0]);
        check(count(fake.storage, Material.PRISMARINE_SHARD) == 2 && busy(api),
                "Kept cauldron first input was not consumed");
        return awaitIdle(api, harness, 200).thenRun(() -> {
            check(pendingAmount(api, block.getLocation(), Material.PRISMARINE_SHARD) == 2,
                    "Kept cauldron first input was not persisted");
            fake.held = 1;
            interact(fake.player, block, fake.storage[1]);
            check(count(fake.storage, Material.REDSTONE) == 5 && busy(api),
                    "Kept cauldron second item was consumed before commit");
            if (replacement == null) fake.storage[1] = null;
            else if (replacement != Material.REDSTONE) {
                fake.storage[1] = new ItemStack(replacement, 5);
            }
        }).thenCompose(ignored -> awaitIdle(api, harness, 200));
    }

    private static void deniedKeptState(RecipeWeaverApi api, Block block,
                                        FakePlayer fake, int water, int events) {
        check(pending(api, block.getLocation()) == null && waterLevel(block) == water
                        && count(fake.storage, Material.PRISMARINE_SHARD) == 4
                        && weightedCount(fake.storage) == 0
                        && count(fake.storage, Material.STICK) == 0
                        && fake.sounds.get() == 0 && COMMANDS.get() == 0 && events == 0,
                "Changed or removed kept cauldron input still committed or lost its first item");
    }

    private static StationRecipeDefinition recipe(NamespacedKey key,
            List<ItemStack> ingredients, ItemStack result, int copySlot,
            ResultMetadataMode copyMode, List<Boolean> consumes,
            List<ItemStack> remainders, List<WeightedResult> choices,
            CraftSoundAction sound, CraftCommandAction command) {
        return new StationRecipeDefinition(key, StationKind.CAULDRON,
                ingredients, result, "smoke", 20, 0, 0, true, false,
                List.of(IngredientMatchMode.MATERIAL, IngredientMatchMode.MATERIAL),
                List.of(List.of(), List.of()), Arrays.asList(null, null),
                CookingBookCategory.MISC, 0, 0, RecipeConditions.NONE,
                sound, null, command, copySlot, copyMode,
                consumes, remainders, List.of(false, false), choices);
    }

    private static CompletionStage<Void> prepare(RecipeWeaverApi api, Block block,
                                                  FakePlayer fake, JavaPlugin harness) {
        block.setType(Material.WATER_CAULDRON);
        Levelled full = (Levelled) block.getBlockData();
        full.setLevel(Math.min(3, full.getMaximumLevel()));
        block.setBlockData(full);
        if (pending(api, block.getLocation()) == null) return CompletableFuture.completedFuture(null);
        fake.held = 0;
        interact(fake.player, block, null);
        return awaitIdle(api, harness, 200).thenRun(() -> {
            check(pending(api, block.getLocation()) == null,
                    "Prior cauldron fixture could not be retrieved");
            Arrays.fill(fake.storage, null);
        });
    }

    private static CompletableFuture<Void> awaitIdle(RecipeWeaverApi api,
                                                      JavaPlugin harness, int ticksLeft) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskLater(harness, () -> {
            try {
                if (!busy(api)) {
                    Bukkit.getScheduler().runTaskLater(harness,
                            () -> result.complete(null), 1L);
                } else if (ticksLeft <= 1) {
                    result.completeExceptionally(new IllegalStateException(
                            "Cauldron I/O did not finish within 200 ticks"));
                } else {
                    awaitIdle(api, harness, ticksLeft - 1)
                            .whenComplete((ignored, failure) -> {
                                if (failure == null) result.complete(null);
                                else result.completeExceptionally(failure);
                            });
                }
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }, 1L);
        return result;
    }

    private static boolean busy(RecipeWeaverApi api) {
        try {
            Object repository = repository(api);
            var method = repository.getClass().getDeclaredMethod("pendingWrite");
            method.setAccessible(true);
            return (boolean) method.invoke(repository);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not inspect cauldron I/O state", ex);
        }
    }

    private static ItemStack pending(RecipeWeaverApi api, Location location) {
        try {
            Object repository = repository(api);
            var method = repository.getClass().getDeclaredMethod("get", Location.class);
            method.setAccessible(true);
            return (ItemStack) method.invoke(repository, location);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not inspect pending cauldron item", ex);
        }
    }

    private static Object repository(RecipeWeaverApi api) throws ReflectiveOperationException {
        var field = api.getClass().getDeclaredField("cauldronRepository");
        field.setAccessible(true);
        return field.get(api);
    }

    private static int pendingAmount(RecipeWeaverApi api, Location location, Material material) {
        ItemStack item = pending(api, location);
        return item != null && item.getType() == material ? item.getAmount() : 0;
    }

    private static ItemStack selected(ItemStack[] storage) {
        for (ItemStack item : storage) {
            if (item != null && (item.getType() == Material.EMERALD
                    || item.getType() == Material.DIAMOND)) return item;
        }
        return null;
    }

    private static int weightedCount(ItemStack[] storage) {
        return count(storage, Material.EMERALD) + count(storage, Material.DIAMOND);
    }

    private static int namedCount(ItemStack[] storage, Material material, String name) {
        int amount = 0;
        for (ItemStack item : storage) {
            if (item != null && item.getType() == material
                    && name.equals(item.getItemMeta().getDisplayName())) amount += item.getAmount();
        }
        return amount;
    }

    private static int count(ItemStack[] storage, Material material) {
        int amount = 0;
        for (ItemStack item : storage) {
            if (item != null && item.getType() == material) amount += item.getAmount();
        }
        return amount;
    }

    private static int waterLevel(Block block) {
        return block.getType() == Material.WATER_CAULDRON
                && block.getBlockData() instanceof Levelled level ? level.getLevel() : 0;
    }

    private static Block testBlock(World world, int offset) {
        Location spawn = world.getSpawnLocation();
        return world.getBlockAt(spawn.getBlockX() + offset, spawn.getBlockY() + 10,
                spawn.getBlockZ() + offset);
    }

    private static ItemStack named(Material material, int amount, String name) {
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    private static void interact(Player player, Block block, ItemStack held) {
        check(interaction(player, block, held), "Cauldron interaction was not handled");
    }

    private static boolean interaction(Player player, Block block, ItemStack held) {
        PlayerInteractEvent event = new PlayerInteractEvent(player,
                Action.RIGHT_CLICK_BLOCK, held, block, BlockFace.UP, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class FakePlayer {
        private final ItemStack[] storage = new ItemStack[36];
        private final AtomicInteger sounds = new AtomicInteger();
        private final Player player;
        private int held;
        private Runnable onSound;

        private FakePlayer(World world, Location location) {
            PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(
                    PlayerInventory.class.getClassLoader(),
                    new Class<?>[] {PlayerInventory.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getStorageContents" -> Arrays.copyOf(storage, storage.length);
                        case "setStorageContents" -> {
                            ItemStack[] supplied = (ItemStack[]) args[0];
                            System.arraycopy(supplied, 0, storage, 0, storage.length);
                            yield null;
                        }
                        case "getHeldItemSlot" -> held;
                        case "getItemInMainHand" -> storage[held];
                        default -> throw new UnsupportedOperationException(
                                "PlayerInventory." + method.getName());
                    });
            player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                    new Class<?>[] {Player.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getInventory" -> inventory;
                        case "getWorld" -> world;
                        case "getLocation" -> location;
                        case "getUniqueId" -> PLAYER_ID;
                        case "getName" -> PLAYER_NAME;
                        case "isOnline" -> true;
                        case "hasPermission" -> false;
                        case "getLevel" -> 0;
                        case "playSound" -> {
                            check(args.length == 5
                                            && SOUND.sound().toString().equals(args[1])
                                            && args[2] == SoundCategory.PLAYERS,
                                    "Cauldron used the wrong sound action");
                            check(onSound != null, "Unexpected cauldron sound action");
                            onSound.run();
                            sounds.incrementAndGet();
                            yield null;
                        }
                        case "updateInventory", "sendMessage" -> null;
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        default -> throw new UnsupportedOperationException(
                                "Player." + method.getName());
                    });
        }
    }
}
