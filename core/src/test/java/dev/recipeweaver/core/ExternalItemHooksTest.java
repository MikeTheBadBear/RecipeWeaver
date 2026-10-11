package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.*;

import be.seeseemelk.mockbukkit.MockBukkit;
import dev.recipeweaver.api.ItemProvider;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class ExternalItemHooksTest {
    interface MmoLookup { ItemStack getItem(String type, String id); }
    interface MmoCanonicalLookup extends MmoLookup {
        MmoTypes getTypes();
        MmoTemplates getTemplates();
    }
    public static final class MmoType { }
    public static final class MmoTypes {
        public MmoType get(String name) { return name.equals("SWORD") ? new MmoType() : null; }
    }
    public static final class MmoTemplates {
        public List<String> getTemplateNames(MmoType type) { return List.of("CrystalBlade"); }
    }
    interface MythicLookup { MythicManager getItemManager(); }
    interface ReloadingMythicLookup { Object getItemManager(); }
    public static final class MythicManager {
        public List<String> getItemNames() { return List.of("MythicSword"); }
        public ItemStack getItemStack(String name) {
            if (name.equals("MythicSword")) return new ItemStack(Material.IRON_SWORD);
            // ItemExecutor 5.13.0 treats unknown IDs that match Material as vanilla items.
            return name.equals("diamond") ? new ItemStack(Material.DIAMOND) : null;
        }
    }
    public static final class PublicMythicManager {
        public List<String> getItemNames() { return List.of("ReloadedBlade"); }
        public Optional<PublicMythicItem> getItem(String name) {
            return name.equals("ReloadedBlade") ? Optional.of(new PublicMythicItem()) : Optional.empty();
        }
    }
    public static final class PublicMythicItem {
        public io.lumine.mythic.api.adapters.AbstractItemStack generateItemStack(int amount) {
            return () -> new ItemStack(Material.GOLDEN_SWORD, amount);
        }
    }

    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void itemsAdderUsesQualifiedId() throws Exception {
        ItemProvider provider = ExternalItemHooks.providerFor(owner("ItemsAdder"));
        assertEquals(2, provider.resolve(new NamespacedKey("itemsadder", "demo/ruby")).getAmount());
        assertNull(provider.resolve(new NamespacedKey("itemsadder", "demo/missing")));
        assertNull(provider.resolve(new NamespacedKey("itemsadder", "ruby")));
    }

    @Test void oraxenBuildsItem() throws Exception {
        ItemProvider provider = ExternalItemHooks.providerFor(owner("Oraxen"));
        assertEquals(Material.EMERALD,
                provider.resolve(new NamespacedKey("oraxen", "ruby")).getType());
        assertNull(provider.resolve(new NamespacedKey("oraxen", "missing")));
    }

    @Test void oraxenRestoresUnambiguousConfiguredIdCase() throws Exception {
        ItemProvider provider = ExternalItemHooks.providerFor(owner("Oraxen"));
        assertEquals(Material.DIAMOND_SWORD,
                provider.resolve(new NamespacedKey("oraxen", "crystalblade")).getType());
        assertNull(provider.resolve(new NamespacedKey("oraxen", "dawnblade")));
    }

    @Test void mmoItemsUsesTypeAndItemId() throws Exception {
        MmoLookup plugin = (type, id) -> type.equals("SWORD") && id.equals("CUTLASS")
                ? new ItemStack(Material.DIAMOND_SWORD) : null;
        ItemProvider provider = ExternalItemHooks.providerFor(owner("MMOItems", MmoLookup.class,
                (proxy, method, args) -> method.getName().equals("getItem")
                        ? plugin.getItem((String) args[0], (String) args[1]) : null));
        assertEquals(Material.DIAMOND_SWORD,
                provider.resolve(new NamespacedKey("mmoitems", "sword/cutlass")).getType());
        assertNull(provider.resolve(new NamespacedKey("mmoitems", "cutlass")));
    }

    @Test void mmoItemsFindsMixedCaseTemplateThroughPublishedManagers() throws Exception {
        MmoLookup plugin = (type, id) -> type.equals("SWORD") && id.equals("CrystalBlade")
                ? new ItemStack(Material.DIAMOND_SWORD) : null;
        ItemProvider provider = ExternalItemHooks.providerFor(owner("MMOItems", MmoCanonicalLookup.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "getItem" -> plugin.getItem((String) args[0], (String) args[1]);
                    case "getTypes" -> new MmoTypes();
                    case "getTemplates" -> new MmoTemplates();
                    default -> null;
                }));
        assertEquals(Material.DIAMOND_SWORD,
                provider.resolve(new NamespacedKey("mmoitems", "sword/crystalblade")).getType());
    }

    @Test void mythicMobsRestoresCanonicalCase() throws Exception {
        ItemProvider provider = ExternalItemHooks.providerFor(owner("MythicMobs", MythicLookup.class,
                (proxy, method, args) -> new MythicManager()));
        assertEquals(Material.IRON_SWORD,
                provider.resolve(new NamespacedKey("mythicmobs", "mythicsword")).getType());
        assertNull(provider.resolve(new NamespacedKey("mythicmobs", "missing")));
        assertNull(provider.resolve(new NamespacedKey("mythicmobs", "diamond")));
    }

    @Test void mythicMobsUsesPublishedItemManagerContractAfterReload() throws Exception {
        AtomicReference<Object> manager = new AtomicReference<>(new MythicManager());
        ItemProvider provider = ExternalItemHooks.providerFor(owner("MythicMobs", ReloadingMythicLookup.class,
                (proxy, method, args) -> manager.get()));
        assertEquals(Material.IRON_SWORD,
                provider.resolve(new NamespacedKey("mythicmobs", "mythicsword")).getType());
        manager.set(new PublicMythicManager());
        ItemStack reloaded = provider.resolve(new NamespacedKey("mythicmobs", "reloadedblade"));
        assertEquals(Material.GOLDEN_SWORD, reloaded.getType());
        assertEquals(1, reloaded.getAmount());
        assertNull(provider.resolve(new NamespacedKey("mythicmobs", "missing")));
    }

    @Test void mythicMobsManagerCanBecomeAvailableAfterHookRegistration() throws Exception {
        AtomicReference<Object> manager = new AtomicReference<>();
        ItemProvider provider = ExternalItemHooks.providerFor(owner("MythicMobs", ReloadingMythicLookup.class,
                (proxy, method, args) -> manager.get()));
        NamespacedKey key = new NamespacedKey("mythicmobs", "reloadedblade");
        assertNull(provider.resolve(key));
        manager.set(new PublicMythicManager());
        assertEquals(Material.GOLDEN_SWORD, provider.resolve(key).getType());
    }

    private interface ExtraCall { Object call(Object proxy, java.lang.reflect.Method method, Object[] args); }

    private static Plugin owner(String name) { return owner(name, null, null); }

    private static Plugin owner(String name, Class<?> extra, ExtraCall extraCall) {
        Class<?>[] interfaces = extra == null ? new Class<?>[] {Plugin.class}
                : new Class<?>[] {Plugin.class, extra};
        return (Plugin) Proxy.newProxyInstance(ExternalItemHooksTest.class.getClassLoader(),
                interfaces, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "isEnabled" -> true;
                    default -> extraCall == null ? null : extraCall.call(proxy, method, args);
                });
    }
}
