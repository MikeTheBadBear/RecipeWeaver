# Custom items

A custom item is a saved item template. It can be a named paper token, a tool with lore, an enchanted reward, or another item with supported metadata. You can give copies to players and use those copies as recipe ingredients or results.

It is still based on a Minecraft item. Giving paper a custom name does not add a new material or create a new texture by itself. Custom textures need a suitable resource pack or item plugin.

## Example: make a Travel Token

### Start with an item in your hand

Hold paper in your **main hand**, run `/rw`, and click **Custom item editor**. The editor starts from a copy of your held item. If your hand is empty, it asks you to hold an item first.

### Give the item a name

Click **Edit name**, enter `Travel Token` in the anvil prompt, and click the anvil result to accept it.

The preview in the middle of the editor shows the draft you are working on. The name is what players see; you will choose a separate saved ID later.

### Add a description

Click **Edit lore**. Long text uses a temporary book and quill:

1. Leave at least one hotbar slot empty before opening the book editor.
2. Right-click the temporary book.
3. Write a short description, such as `Bring this to the station master.`
4. Click **Done** to submit it. You do not need to sign the book.

Each line becomes a lore line. The editor accepts up to eight pages, with up to 1,024 characters per page. Page boundaries do not add newlines, so press Enter where you actually want a new line. Enter `none` when using the lore editor if you want to clear the lore.

If you want to abandon the temporary book prompt, use `/rw` to return to the studio.

### Save the template

Click **Save custom item**, enter `travel_token`, accept the anvil result, and wait for the success message.

The saved ID is `recipeweaver:travel_token`. The display name is `Travel Token`. Those are different things: the ID is how commands and menus find the template; the display name is part of the item itself.

### Give yourself a saved copy

With `recipeweaver.give`, run:

```text
/rw give YourPlayerName recipeweaver:travel_token
```

Replace `YourPlayerName` with the exact name of an online player. This command gives a copy of the saved template. It does not open a quantity prompt; use the amount saved on the template.

Now you can put that copy into a recipe editor as an ingredient or result. For an ingredient that should specifically require the Travel Token, keep **exact matching**. Material matching would also accept ordinary paper.

## Editing a saved custom item

Open `/rw` and choose **Saved custom items**. Click the item to open its saved template, change what you need, and save with its existing ID.

Recipes store item samples. After changing a saved custom item, revisit recipes that use the old sample and replace their ingredient or result samples if you want the new version there. Changing a template also does not rewrite copies that players already own.

## Other controls you may see

The editor adapts to the item type. A potion has different controls from a sword or a written book. Available controls include names and lore, enchantments, unbreakable state, damage, armor trims, model data, tooltip flags, rarity, and supported item components.

Some controls accept a short value through an anvil; longer values use the book prompt. Hover over the control before entering a value. It tells you the expected format, range, and whether `none` clears the setting.

You do not need to understand persistent data or Minecraft item-text syntax to make a useful custom item. Leave those controls alone until you have a reason to use them.

## Using items from another plugin

**Import saved or provider item** can start a draft from a supported item provider. That provider must be installed, enabled, and able to resolve the ID.

For ItemsAdder, the format shown by the editor is:

```text
itemsadder:namespace/item
```

For example, an ItemsAdder ID written as `my_pack:ruby` would be entered here as `itemsadder:my_pack/ruby`.

Optional hooks also exist for Oraxen, MMOItems, and MythicMobs. IDs and support depend on the provider. An unknown item should be treated as an import problem, not replaced with an unrelated vanilla sample.

ItemsAdder, Oraxen, and MMOItems have not all been verified with installed live plugins in this release. Test the provider and the resulting recipe on your own server before relying on it for progression.

## One important limit

The supported component and persistent-data controls are not an unrestricted editor for every possible Minecraft SNBT value. The import feature uses syntax supported by the running server. An item or component available on a newer Minecraft version may not exist on an older one.
