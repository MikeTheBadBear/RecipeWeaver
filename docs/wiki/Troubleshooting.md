# Troubleshooting

Start with the smallest test that can show the problem. A plain recipe with no conditions is easier to diagnose than one with custom metadata, several possible results, and a command reward.

## `/rw` or `/recipes` is unknown

Check the server console from startup. Confirm that you installed the actual production JAR in the correct server's `plugins` folder and restarted that server.

Check the Java version and server type as well. RecipeWeaver needs a compatible Paper, Purpur, or Spigot server; putting it in a vanilla or Fabric server does not turn it into a Bukkit plugin server.

Use one production RecipeWeaver JAR at a time. Keep older versions outside the active `plugins` folder.

## The server runs tests and then shuts down

Check for a `RecipeWeaverSmoke` JAR in `plugins`. It is the automated test companion and intentionally shuts its test server down. Stop the server, move that JAR outside `plugins`, and leave the production RecipeWeaver JAR installed.

## I get “Missing permission”

Read the permission node in the message. Studio access, administration, item giving, and recipe browsing use separate nodes.

For an initial test on your own server, use an operator account. For ongoing access, assign the appropriate node with your permissions plugin. The full list is in [Commands and permissions](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Commands-and-Permissions).

An individual recipe may have its own permission condition as well. Access to `/recipes` does not grant that condition.

## I cannot find the Save button

In the ordinary crafting editor, Save is at the bottom center and reads **3. SAVE RECIPE**. It uses an emerald block when ready and a redstone block when an ingredient or result is missing.

Put at least one sample in the blue grid and an output sample in the green result slot. Hover over Save to see what is missing.

Other editors use different labels: **Save recipe** for table recipes, **Save station recipe** for stations, and **Save custom item** for item templates.

## I entered the recipe ID, but it did not save

The short-text prompt is an anvil. Enter the ID in its name field and click the **result paper on the right** to accept it. Closing the anvil is not the same as accepting it.

Use a simple ID such as `starter_token`. Spaces, another plugin's namespace, or an ID already used by a different saved recipe can be rejected. When editing an existing recipe, keep its original ID.

Watch the chat response. **Saved recipe** means success. If a save is still in progress, wait. If it fails, keep the error message and check the server log.

## The preview matches, but I cannot craft the result

Check these in order:

1. **Was the recipe saved?** The draft preview does not save it for you.
2. **Is it active?** Open its editor and check the enabled setting.
3. **Is lockdown off?** Use `/rw info` to check.
4. **Are you using the right block?** Workbench, station, Advanced, and Elite recipes are different types.
5. **For Elite, is the selected grid size correct?** A 4×4 recipe needs the 4×4 grid.
6. **Do the real ingredients match the samples?** Names, lore, enchantments, damage, potion data, and other metadata matter under exact matching.
7. **Do you have enough items?** Custom table recipes can require a stack amount in a slot.
8. **Are there extra ingredients in otherwise empty slots?** Remove them for a simple shaped recipe test.
9. **Do you meet the requirements?** Check permission, location, time, weather, level, scoreboard, advancement, cooldown, and craft limit where configured.
10. **Is there room for everything?** Results and returned containers need space.

Try the plain example from [Gameplay](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Gameplay). If that works, add your extra settings one at a time until the difference becomes clear.

## It works for staff but not normal players

Test with a non-operator account. Operators can have permissions that ordinary players lack, and studio users can see hidden or disabled entries in the book.

Check the player's browsing permission, the recipe's active/hidden state, its own conditions, and any protections supplied by other plugins. A successful operator test does not prove the intended player progression works.

## A recipe is missing from the book

Clear the search by right-clicking its control. Set category, group, and discovery filters to show everything.

Then check whether the recipe is saved, enabled, or hidden, and whether lockdown is on. Staff views can differ from normal-player views. Crafting conditions can still prevent using a recipe that is visible in the book.

The RecipeWeaver browser and Minecraft's native recipe book are not the same menu. Some custom-only recipes do not have an equivalent native unlock path.

## My Advanced or Elite table behaves like an ordinary table

Use the item supplied by `/rw table advanced` or `/rw table elite`, then place it. Renaming an ordinary crafting table does not create a registered special table.

If placement was cancelled by a protection plugin or the plugin reported a save failure, resolve that message before testing. Wait for a pending table save, reload, or backup operation to finish.

## I cannot edit lore or another long value

Leave an empty slot in your hotbar. RecipeWeaver uses a protected temporary book for long input.

Right-click the book, write your text, and choose **Done**. Use no more than eight pages or 1,024 characters per page. Enter actual line breaks where needed; page boundaries do not add them.

Use `/rw` if you need to cancel the temporary prompt.

## A furnace, brewing stand, or crafter refuses a conditional recipe

Automated operations do not have a player identity. A player permission, level, scoreboard, or advancement requirement cannot be evaluated as though a particular player were crafting by hand.

Use supported world-only rules for those operations, and check the editor's guidance. Older native crafter APIs also cannot enforce every exact-item rule, so some recipes are intentionally withheld rather than allowed through without their checks.

## A cauldron mix is stuck after the first ingredient

Use the configured second ingredient in your main hand and right-click the same water cauldron. Check its water and the chat message.

To cancel the pending mix, right-click with an empty hand and leave room for the returned first ingredient. Dropping items into the cauldron is not the documented mixing interaction.

## An optional-plugin item cannot be imported

Check that the provider plugin is installed, enabled, and has the item ID you are using. For ItemsAdder, use RecipeWeaver's `itemsadder:namespace/item` import format.

Look for provider errors in the log. Optional integration coverage differs by plugin, so reproduce the problem with the exact provider and server versions rather than assuming a successful vanilla recipe proves the hook works.

## Data appears to change after a restart or reload

Do not delete the data folder as a first response. Check `/rw info` as an administrator and note which source was loaded. A configured SQL read preference can make a selected SQL snapshot the source at startup or reload.

Keep a copy of the current data and logs before changing source policies or restoring anything. Use the [storage guide](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Settings-Backups-and-Storage) to understand the current policy.

## What to include in a bug report

Provide the RecipeWeaver version from `/rw info`, Minecraft version, server software, relevant other plugins, and the exact steps that reproduce the issue. Say whether the test used an operator, a normal player, or automation, and whether it was a normal or shift-click craft.

Include what you expected, what actually happened, the recipe ID and relevant settings, and the full error stack trace if there is one. Redact passwords, JDBC connection details, and other secrets before sharing files or logs publicly.

Version 3.7 passed 580 tests with 22 skipped and 18 automated endpoint server runs. Those checks do not replace real-player testing of every menu, optional integration, and Minecraft version. Reporting the exact interaction makes the next investigation much more useful.
