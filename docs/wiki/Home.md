# Welcome to RecipeWeaver

RecipeWeaver lets you make custom recipes while you are playing Minecraft. Open the studio, choose a recipe type, set the ingredients and result, and save it. Your players can then look up those recipes in an in-game book.

You can start with an ordinary crafting-table recipe. As your server grows, you can add custom items, workstation recipes, larger crafting grids, and requirements that fit your progression system.

This wiki explains **RecipeWeaver 3.7**. The instructions were checked against the source available on **2 November 2026**. The plugin is by **TM Game Studio**.

## New here? Start with these two pages

1. **[Getting started](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Getting-Started)** explains which JAR to install, how to check the plugin loaded, and how to get access to the studio.
2. **[Gameplay: your first recipe](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Gameplay)** walks through a small recipe from start to finish, including the actual crafting test.

You do not need a database, a resource pack, or another custom-item plugin to follow that first example.

## If you just want to play

Run `/recipes`, click a recipe, and check its ingredients and workstation. Gather those items and use the station shown in the recipe. The book is a guide; clicking a result in it does not give you the item.

If you cannot find a recipe, clear the search and filters first. Staff may also have disabled or hidden it, or switched on lockdown. Ask your server staff if you are unsure. A visible recipe can still have crafting requirements you need to meet.

## If you are setting up the server

Use `/rw create` to open the studio. Ordinary players can browse recipes by default, while recipe editing and server settings default to operators. A permissions plugin can give those jobs to other staff members.

Keep your first recipe simple. Use ordinary items and default settings, save it, and make sure you can craft it. Then add one rule at a time. It is much easier to spot a mistake that way.

## What the plugin covers

| Area | What it is for |
| --- | --- |
| Workbench recipes | Shaped and shapeless crafting with a normal crafting table. |
| Station recipes | Furnaces, blast furnaces, smokers, campfires, smithing, stonecutting, anvils, grindstones, brewing, and cauldron mixing. |
| Table recipes | Custom recipes for RecipeWeaver's placeable Advanced and Elite tables. |
| Custom items | Saved item templates with names, lore, enchantments, and supported item data. |
| Recipe book | Browsing, searching, filtering, and inspecting recipes in game. |
| Rules and rewards | The conditions, ingredient rules, result choices, and actions offered by each editor. |
| Administration | Settings, vanilla recipe exclusions, backups, restore, and optional SQL storage. |

Different recipe types have different controls. For example, a furnace has no player attached to its cooking operation, so a player-permission requirement cannot work there in the same way it does at a player-operated table.

## Find the right guide

- [Getting started](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Getting-Started)
- [Gameplay and your first recipe](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Gameplay)
- [Custom items](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Custom-Items)
- [Tables and stations](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Tables-and-Stations)
- [Commands and permissions](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Commands-and-Permissions)
- [Settings, backups, and storage](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Settings-Backups-and-Storage)
- [Troubleshooting](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Troubleshooting)

## A few terms you will see

**Ingredient** means an input needed by the recipe. **Result** means the item it makes. A **sample** is the item copied into an editor to describe an ingredient or result; you are not spending it by editing.

A **recipe ID** is the recipe's saved name, such as `recipeweaver:starter_token`. It is separate from the name displayed on the resulting item. A **workstation** is the block where the recipe is used. **Lockdown** is an administrator's switch that stops RecipeWeaver's custom recipes while leaving their saved definitions in place.

## Current release and compatibility

The current installable build is [RecipeWeaver 3.7](https://github.com/MikeTheBadBear/RecipeWeaver/blob/main/releases/RecipeWeaver-3.7.jar). It remains an intermediate development release.

The compatibility target is Paper, Purpur, and Spigot from Minecraft 1.20.6 through 26.2. The 3.7 checks ran on all three server types at 1.20.6 and 26.2. That does not establish that every version between them, every optional plugin, or every real-player interaction has been tested.

For the detailed validation record, see the [3.7 release notes](https://github.com/MikeTheBadBear/RecipeWeaver/blob/main/releases/3.7-CHANGE-LEDGER.md) and [remaining work](https://github.com/MikeTheBadBear/RecipeWeaver/blob/main/NEXT_RELEASE_WORK.md).
