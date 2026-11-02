# Tables and stations

The first decision is where a player should use the recipe. A normal workbench recipe, an Advanced-table recipe, and a furnace recipe are different saved recipe types. They are not interchangeable just because they use the same ingredients.

## Choose the right editor

| What you want players to do | Start here in `/rw create` |
| --- | --- |
| Craft in an ordinary crafting table | **Create shaped recipe** or **Create shapeless recipe** |
| Craft in a RecipeWeaver Advanced or Elite table | **Create table recipe** |
| Smelt, cook, smith, cut stone, use an anvil or grindstone, brew, or mix in a cauldron | **Create station recipe** |

The recipe book shows the saved type so players can check which block they need.

## Advanced tables

An Advanced table is a RecipeWeaver crafting-table item with a fixed **3×3** grid. It accepts matching custom Advanced-table recipes and eligible native 3×3 recipes.

### Give yourself the table

With `recipeweaver.admin`, run:

```text
/rw table advanced
```

To give one to another online player:

```text
/rw table advanced YourPlayerName
```

Place the supplied table item and right-click the placed block. You can also get the item from **Create table recipe → Get advanced table**.

An ordinary crafting table renamed in an anvil is not the same item. Use the table supplied by RecipeWeaver so the plugin can register its location.

### Create a recipe for it

1. Open `/rw create` and choose **Create table recipe**.
2. Choose **New advanced recipe**.
3. Set ingredient samples in the active grid.
4. Set the result in the result slot on the right.
5. Check the type, enabled state, and any ingredient rules.
6. Click **Save recipe**, choose an ID, and wait for the success message.
7. Test it in the placed Advanced table.

Custom Advanced recipes are considered before the native fallback. If multiple custom table recipes overlap, higher priority wins. Avoid ambiguous recipes while learning the editor.

## Elite tables

Elite tables use custom grids from **2×2 through 6×6**. They are useful when a recipe needs more room or belongs in a separate crafting system.

Get a table with:

```text
/rw table elite
```

Choose **Create table recipe → New elite recipe** to make its recipes. The **Grid** control changes the recipe's size. When crafting, use the size buttons in the Elite table to select the matching grid.

A saved 4×4 Elite recipe needs the Elite table set to 4×4. It will not become a workbench recipe or an Advanced recipe.

## Table ingredient controls

The table editor copies the **whole stack amount** into an ingredient sample. Setting a sample with eight cobblestones means that ingredient slot requires eight cobblestones. This differs from an ordinary workbench sample, which represents one item per occupied slot.

The main controls are:

- **Left-click with a cursor item:** set or replace an ingredient sample.
- **Left-click with an empty cursor:** clear it.
- **Right-click a sample with an empty cursor:** cycle the matching modes available in this editor.
- **Shift-left-click a sample:** open its alternatives, tags, and ingredient rules.
- **Shift-right-click a sample:** choose it as the source for result metadata copying.

Read the ingredient's hover text after editing it. It shows the matching mode, amount, and any special rules.

### What do the special rules mean?

**Required amount** is the number needed in that slot. **Optional** means the recipe may match without that ingredient. A **kept catalyst** must be present but is not consumed. A **remainder** is an item returned when a consumed ingredient is used, such as a container.

These rules can change the cost of a recipe a lot. Test both the cheapest valid input and the expected normal input. Check that kept or returned items do not let players make valuable output for less than you intended.

### Shaped, shapeless, and mirrored patterns

Use the **Type** control for shaped or shapeless matching. Shaped recipes care about arrangement. Shapeless recipes do not. **Allow mirror** is a separate control for accepting the reflected version of a shaped pattern.

Do not assume rotating a pattern is the same as mirroring it.

## Results, requirements, and actions

You can leave these settings at their defaults for a simple recipe.

| Control | What it changes |
| --- | --- |
| Weighted results | Several possible outputs, chosen according to their relative weights. |
| Priority | Which custom table recipe wins when more than one matches. |
| Craft conditions | Player or world requirements offered by the editor. |
| Shift craft limit | Maximum batches allowed for one shift-click, from 1 through 64. |
| Craft cooldown | Delay for that player and recipe after a committed craft; `0` turns it off. |
| Lifetime craft limit | A per-player recipe limit; `0` means unlimited. |
| Result metadata | Which supported data is copied from the selected ingredient to the output. |
| Craft sound, command, advancement | Additional actions after a successful craft. |

Weights are relative. If one result has weight 3 and another has weight 1, their chances are 3 out of 4 and 1 out of 4. The numbers are not percentages unless your total happens to be 100.

For metadata copying, choose the source ingredient and the copying mode. Copying all item data requires the source and result to have the same material. A book's data cannot simply be copied wholesale onto a sword.

Command actions can run with console authority. Only trusted staff should configure them. Choose the intended sender and whether an action runs once for the click or for each crafted batch. Test shift-crafting so repeated rewards behave as expected.

## Crafting in a placed table

The table's crafting grid contains real items, not editor samples. Put the ingredients in the selected grid, check the preview, and take the result.

Leave room in your inventory for the result and returned items. A valid ingredient match is not enough if the transaction cannot place everything it needs to return.

A preview may stop being valid if the recipe is disabled, lockdown changes, or a player requirement is no longer met. Check the current settings rather than assuming an earlier preview guarantees a craft.

## Other workstations

Open **Create station recipe** and select the type you need. The editor labels each input so you can put the right sample in the right place.

| Station type | How players use it | Setting to pay attention to |
| --- | --- | --- |
| Furnace, blast furnace, smoker, campfire | Cook the configured input in the appropriate block. | Cooking ticks, experience, and supported world conditions. |
| Smithing transform | Use the labeled template, base, and addition inputs. | The correct smithing type and ingredient order. |
| Smithing trim | Combine the configured trim inputs. | The result is dynamic armor trim rather than a fixed output sample. |
| Stonecutting | Put the input in a stonecutter and select the matching output. | Ingredient matching and the saved result. |
| Anvil | Use the configured inputs and take the result. | Level cost, ingredient rules, and any player requirements. |
| Grindstone | Use the configured inputs and take the result. | Ingredient rules and any player requirements. |
| Brewing | Use a brewing stand with the configured ingredient and bottle inputs. | Exact bottle contents, fuel, and supported conditions. |
| Cauldron | Add two configured ingredients by right-clicking a water cauldron. | Ingredient order, water, and the pending first ingredient. |

Click **Save station recipe** and wait for confirmation after editing. Different stations offer different controls; a feature shown for one station is not automatically available on another.

### Cooking time

Minecraft normally runs at 20 ticks per second. A cooking time of 200 ticks is about 10 seconds when the server is keeping up. Lag can make the real elapsed time longer.

Furnaces, campfires, and brewing stands do not have a player attached to the cooking operation. Player-only requirements, such as a player permission or advancement, block those operations rather than being silently ignored. Use the world rules supported by that editor for automated stations.

### Cauldron mixing

1. Set up a **CAULDRON** station recipe with its first and second ingredients.
2. Put water in a cauldron.
3. Hold the first ingredient in your main hand and right-click the cauldron.
4. Wait for the message confirming the first ingredient was added.
5. Hold the second ingredient and right-click the same cauldron to complete the mix.

Right-click with an **empty hand** if you need to retrieve a pending first ingredient. Keep enough inventory space for it. If the cauldron lacks water before the mix completes, follow the message asking you to add water.

This is a right-click interaction. Throwing item entities into the water is not the documented input method.

### The redstone crafter

Eligible native shaped and shapeless recipes can be used by the Minecraft crafter. A crafter is not an Elite crafting grid, and it cannot supply a player's identity for player-only rules.

Some recipes with exact-item or player-only checks are deliberately withheld from native automation on older supported APIs. Do not assume every saved player recipe is an automation recipe. Test the exact inputs and server version before designing a production line around it.
