package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class ItemCompoundArrayEditorTest {
    private static final NamespacedKey RECORDS = key("records");

    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void appendCreatesTypedCompoundArrayElement() {
        ItemStack item = new ItemStack(Material.PAPER);
        edit(item, "@compoundarray append recipeweaver:rank @int 7");
        assertEquals(7, elements(item)[0].get(key("rank"), PersistentDataType.INTEGER));
    }

    @Test void indexedEditPreservesOtherCompoundArrayElements() {
        ItemStack item = new ItemStack(Material.PAPER);
        edit(item, "@compoundarray append recipeweaver:rank @int 7");
        edit(item, "@compoundarray append recipeweaver:rank @int 11");
        edit(item, "@compoundarray 0 recipeweaver:rank @int 8");
        assertEquals(2, elements(item).length);
        assertEquals(8, elements(item)[0].get(key("rank"), PersistentDataType.INTEGER));
        assertEquals(11, elements(item)[1].get(key("rank"), PersistentDataType.INTEGER));
    }

    @Test void indexedNestedEditPreservesSiblingKeys() {
        ItemStack item = new ItemStack(Material.PAPER);
        edit(item, "@compoundarray append recipeweaver:stats>recipeweaver:rank @int 7");
        edit(item, "@compoundarray 0 recipeweaver:stats>recipeweaver:level @short 3");
        PersistentDataContainer stats = elements(item)[0]
                .get(key("stats"), PersistentDataType.TAG_CONTAINER);
        assertEquals(7, stats.get(key("rank"), PersistentDataType.INTEGER));
        assertEquals((short) 3, stats.get(key("level"), PersistentDataType.SHORT));
    }

    @Test void removingLastLeafPrunesItsEmptyArrayElement() {
        ItemStack item = new ItemStack(Material.PAPER);
        edit(item, "@compoundarray append recipeweaver:rank @int 7");
        edit(item, "@compoundarray append recipeweaver:rank @int 11");
        edit(item, "@compoundarray 0 recipeweaver:rank none");
        assertEquals(1, elements(item).length);
        assertEquals(11, elements(item)[0].get(key("rank"), PersistentDataType.INTEGER));
    }

    @Test void removeCompactsIndexesAndRemovesEmptyArrayKey() {
        ItemStack item = new ItemStack(Material.PAPER);
        edit(item, "@compoundarray append recipeweaver:rank @int 7");
        edit(item, "@compoundarray append recipeweaver:rank @int 11");
        edit(item, "@compoundarray remove 0");
        assertEquals(11, elements(item)[0].get(key("rank"), PersistentDataType.INTEGER));
        edit(item, "@compoundarray remove 0");
        assertFalse(item.getItemMeta().getPersistentDataContainer().has(RECORDS));
    }

    @Test void scalarTypeCollisionIsRejectedWithoutChangingItem() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemDraftEditor.setPersistentData(item, "recipeweaver:records\n@int 3");
        assertThrows(IllegalArgumentException.class,
                () -> edit(item, "@compoundarray append recipeweaver:rank @int 7"));
        assertEquals(3, item.getItemMeta().getPersistentDataContainer()
                .get(RECORDS, PersistentDataType.INTEGER));
    }

    @Test void invalidIndexAndOverdeepPathAreRejectedWithoutChangingArray() {
        ItemStack item = new ItemStack(Material.PAPER);
        edit(item, "@compoundarray append recipeweaver:rank @int 7");
        assertThrows(IllegalArgumentException.class,
                () -> edit(item, "@compoundarray 1 recipeweaver:rank @int 8"));
        assertThrows(IllegalArgumentException.class,
                () -> edit(item, "@compoundarray 0 recipeweaver:a>recipeweaver:b>recipeweaver:c>recipeweaver:d>recipeweaver:e @int 8"));
        assertEquals(7, elements(item)[0].get(key("rank"), PersistentDataType.INTEGER));
        assertFalse(elements(item)[0].has(key("a")));
    }

    @Test void malformedTypedEditPreservesExistingArrayElement() {
        ItemStack item = new ItemStack(Material.PAPER);
        edit(item, "@compoundarray append recipeweaver:rank @int 7");
        assertThrows(IllegalArgumentException.class,
                () -> edit(item, "@compoundarray 0 recipeweaver:rank @int invalid"));
        assertEquals(7, elements(item)[0].get(key("rank"), PersistentDataType.INTEGER));
    }

    @Test void browserReturnsIndexedEditorPathsAndRemoveControls() {
        ItemStack item = new ItemStack(Material.PAPER);
        edit(item, "@compoundarray append recipeweaver:stats>recipeweaver:rank @int 7");
        var entries = ItemDraftEditor.persistentCompoundArrayEntries(item, RECORDS);
        assertTrue(entries.stream().anyMatch(entry -> entry.editorValue()
                .equals("@compoundarray remove 0")));
        assertTrue(entries.stream().anyMatch(entry -> entry.editorValue()
                .equals("@compoundarray 0 recipeweaver:stats>recipeweaver:rank @int 7")));
        assertTrue(ItemDraftEditor.persistentEntries(item).stream().anyMatch(entry ->
                entry.key().equals(RECORDS) && entry.type().equals("compoundarray")));
    }

    private static void edit(ItemStack item, String command) {
        ItemDraftEditor.setPersistentData(item, "recipeweaver:records\n" + command);
    }

    private static PersistentDataContainer[] elements(ItemStack item) {
        return item.getItemMeta().getPersistentDataContainer()
                .get(RECORDS, PersistentDataType.TAG_CONTAINER_ARRAY);
    }

    private static NamespacedKey key(String key) {
        return new NamespacedKey("recipeweaver", key);
    }
}
