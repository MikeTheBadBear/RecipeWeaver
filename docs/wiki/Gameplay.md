# Gameplay: make your first recipe

This walkthrough starts with an ordinary crafting-table recipe. You will make **one cobblestone plus one stick produce one paper**, save it as `starter_token`, and craft it for real.

The ingredients and result are only a teaching example. Once you understand the process, use the items and rewards that suit your server.

## Before you open the menu

Make sure RecipeWeaver is loaded and you can use `recipeweaver.studio`. On a fresh installation, an operator account has that permission.

Put some **ordinary cobblestone**, **ordinary sticks**, and **paper** in your inventory. Use plain items for this example. Renamed or enchanted samples can create stricter ingredient requirements than you intended.

You will also need access to a normal crafting table for the final test.

## 1. Choose the recipe type

Run:

```text
/rw create
```

Click **Create shaped recipe**. A shaped recipe checks the arrangement of its ingredients. A shapeless recipe checks the ingredients without requiring that arrangement.

The crafting editor has a blue ingredient grid on the left and a green result slot on the right. The buttons along the bottom are settings, a preview, and Save.

## 2. Set the ingredients

1. Left-click cobblestone in your own inventory so it is attached to your cursor.
2. Left-click the **top-left blue ingredient slot** in the editor.
3. Put the cobblestone from your cursor back in your inventory.
4. Pick up a stick and click the **blue slot immediately to the right** of the cobblestone sample.
5. Put the stick from your cursor back in your inventory.

The pattern now looks like this:

| | Column 1 | Column 2 | Column 3 |
| --- | --- | --- | --- |
| Row 1 | Cobblestone | Stick | Empty |
| Row 2 | Empty | Empty | Empty |
| Row 3 | Empty | Empty | Empty |

The editor **copies samples**. It does not take those items out of your inventory as the cost of creating a recipe.

For an ordinary workbench recipe, each occupied ingredient slot represents **one item**. Holding a stack of 64 while setting the slot does not make that slot require 64 items. Table recipes have separate stack-amount behavior, explained in [Tables and stations](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Tables-and-Stations).

## 3. Set the result

Pick up **one paper** on your cursor and click the green result slot. Then put the paper from your cursor back in your inventory.

The result sample includes its stack amount. For this example, use one paper so each successful craft produces one paper.

If you need to correct a sample, click its slot with the replacement item on your cursor. To clear an ingredient or result, left-click that slot with an empty cursor.

## 4. Check the basic settings

For this first recipe, keep these settings:

- **Crafting style:** `SHAPED`.
- **Show in recipe book:** `Yes`.
- **Recipe active:** `Yes`.
- **Extra crafting requirements:** none.

The group and book category can stay at their defaults. They help organize recipes; you do not need to fill them in before saving.

New ingredient samples use exact matching. With the plain cobblestone and stick used here, that is a sensible starting point. If a recipe later needs to accept renamed versions of the same material, read the matching section below.

## 5. Use the preview

Click **Preview recipe match**. This opens a separate test grid with samples copied from your draft.

You should see **Ingredients match** and the paper result. You can replace the test inputs to see which combinations match. **Reset test inputs** copies the draft ingredients into the preview again. **Back to editor** returns you to the recipe.

This preview is useful, but it is only a matching check. It does not consume ingredients or replace a real crafting test with a normal player.

## 6. Save it

Once you have at least one ingredient and a result, the bottom-center button reads **3. SAVE RECIPE** and uses an emerald block.

1. Click **3. SAVE RECIPE**.
2. An anvil text prompt opens. Its starting text is `new_recipe` for a new recipe.
3. Replace that text with `starter_token`.
4. Click the result paper on the right of the anvil to accept the text.
5. Wait for the chat message **Saved recipe recipeweaver:starter_token**.

RecipeWeaver adds the `recipeweaver:` part when you use a short ID. IDs identify saved recipes; they do not rename the resulting item. This example still makes ordinary paper.

Use a short, descriptive ID with lowercase letters, numbers, and underscores. `starter_token`, `copper_upgrade`, and `tools/repair_kit` are reasonable examples. Spaces are not allowed. A new recipe cannot use an ID that is already saved.

Changing a saved recipe's ID is not supported by this editor. Keep its original ID when editing it, or create a separate recipe under a new ID.

Leaving the editor without a successful Save does not create or update the saved recipe.

## 7. Craft it for real

Close the studio and open a normal crafting table. Put one plain cobblestone in the top-left slot and one plain stick immediately to its right. Leave the other slots empty.

One paper should appear as the result. Take it and check that one cobblestone and one stick were consumed.

Start with a normal single craft. Once that works, try other player interactions you plan to allow, including shift-clicking and using non-operator accounts. Operator status can hide permission mistakes during setup.

You do not need to run `/rw reload` after saving through the editor. Wait for the save confirmation before testing.

## 8. Find it in the recipe book

Run:

```text
/recipes
```

Find `recipeweaver:starter_token` and click it to view the ingredients and result. You can search for `starter_token`, or clear the search and filters if it does not appear.

For a regular player, browsing the recipe does not give the result or remove its requirements. They still need to craft it in the correct place.

## Changing or removing your recipe

With `recipeweaver.studio`, open `/recipes`, select the recipe, and choose **Edit recipe**. Make your changes and save again with the same ID.

To remove a workbench or station recipe, choose **Delete recipe** in its detail view and type **DELETE** exactly when asked. For a table recipe, open its editor and use its delete control. Make a backup before removing recipes you may want later.

## What changes for a shapeless recipe?

Choose **Create shapeless recipe** instead. You still set samples and save an ID, but players can arrange those ingredients in different slots when crafting.

Each occupied sample slot still represents one ingredient. A recipe with cobblestone in two sample slots requires two cobblestones, even though it does not require their original positions.

## Exact matching, material matching, and alternatives

These controls answer a simple question: **what counts as this ingredient?**

| Choice | What it means | When to use it |
| --- | --- | --- |
| Exact | The material and item metadata must match the sample. | A particular named token, custom item, or reward should be required. |
| Material | The material matters; the sample's other metadata does not. | Ordinary sticks, cobblestone, or another material should be accepted regardless of names or lore. |
| Alternatives | A slot can accept one of several configured choices. | Several specific items should work in the same slot. |
| Item tag | A named Minecraft group supplies acceptable materials. | A recipe should accept a group such as `minecraft:planks`. |

In the workbench editor, right-click a filled ingredient slot with an **empty cursor** to switch exact/material matching. Shift-left-click a filled sample to open its alternatives and tag controls. An item tag uses material matching; clear the tag before switching that ingredient back to exact.

Table and some other recipe editors offer additional matching modes. Use the label shown by that editor rather than assuming every workstation supports every option.

## Playing with Advanced and Elite tables

These are special, placeable RecipeWeaver items. An ordinary crafting-table block is not automatically an Advanced or Elite table.

Ask staff for the appropriate table, place it, and right-click it. An Advanced table opens a 3×3 grid. An Elite table lets you select a size from 2×2 to 6×6. The table tier and selected size must match the custom table recipe.

Unlike the recipe editor, the crafting grid holds **real ingredients**. Taking the result is a real crafting transaction. Leave room in your inventory for both the result and any returned containers.

See [Tables and stations](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Tables-and-Stations) for setup and other workstations. If the result does not appear, work through [Troubleshooting](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Troubleshooting) before rebuilding the recipe from scratch.
