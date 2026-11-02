# RecipeWeaver

Make custom Minecraft recipes from inside the game, then give your players a recipe book they can actually use.

RecipeWeaver gives server owners an in-game studio for crafting, workstation recipes, custom items, and larger crafting grids. You choose the ingredients and the result, save the recipe, and try it at the appropriate table or station. Players can browse the recipes with `/recipes` instead of having to remember every combination.

Built by **TM Game Studio** for **Paper, Purpur, and Spigot**.

**[Download RecipeWeaver 3.7](releases/RecipeWeaver-3.7.jar)** · **[Read the beginner guide](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Gameplay)** · **[Open the wiki](https://github.com/MikeTheBadBear/RecipeWeaver/wiki)**

## What can you make?

Start with a simple crafting recipe, or build a more involved progression system for your server:

- **Crafting recipes:** use a fixed pattern or let players arrange the ingredients however they like. Eligible native recipes can also work with Minecraft's crafter.
- **Workstation recipes:** create furnace, blast furnace, smoker, campfire, smithing, stonecutter, anvil, and grindstone recipes.
- **Brewing and mixing:** add brewing-stand recipes and two-ingredient mixes in water cauldrons.
- **Bigger crafting tables:** use an Advanced table for 3×3 crafting, or an Elite table with grids from 2×2 to 6×6. Advanced tables also accept eligible native 3×3 recipes.
- **Custom items:** edit names, lore, enchantments, durability, and supported item components, then save items for use in your recipes.
- **More control over ingredients:** use exact items, material matching, alternatives, or item tags. Table recipes also support required stack amounts, optional ingredients, kept catalysts, and returned items.
- **Recipe rules and rewards:** use the controls available for each recipe type to add requirements, weighted results, cooldowns, craft limits, sounds, commands, or advancements.

The studio shows the controls that apply to the recipe you are editing. You can leave the advanced options alone and still make a useful recipe.

## Your first recipe

1. Install the production JAR and start your server.
2. Join as an operator, or use an account with `recipeweaver.studio`.
3. Run `/rw create` and choose **Create shaped recipe** or **Create shapeless recipe**.
4. Copy ingredient samples into the blue grid and put your output sample in the green result slot.
5. Click **3. SAVE RECIPE**, enter an ID, and accept it in the anvil prompt.
6. Wait for the save message, then try the recipe in a real crafting table. Use `/recipes` to find it again.

The editor copies samples from your inventory. Crafting the saved recipe is the part that consumes real ingredients.

If you have never used a recipe editor before, the [Gameplay guide](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Gameplay) walks through an example one click at a time.

## For your players

Players use `/recipes` to browse the recipes available to them, search by name or ID, filter the list, and inspect ingredients and results. Server staff can enable an optional right-click recipe-book item as well.

Browsing a recipe does not give its result for free. Players still need the ingredients, the correct workstation, and any requirements set on that recipe. Studio and administration controls are reserved for staff by default.

## Installing on a server

Put **one** production `RecipeWeaver-<version>.jar` in your server's `plugins` folder and start the server normally. Run `/rw info` to check that it loaded.

The compatibility target is Minecraft **1.20.6 through 26.2** on Paper, Purpur, and Spigot. Version 3.7 was tested on all three at **1.20.6 and 26.2**; every intermediate version has not been individually verified. Use the Java runtime required by your server version. The plugin targets Java 21 bytecode.

The current build is an **intermediate development release**. Try your recipes on a test server before adding them to a live server. Optional item-plugin integrations and some player interactions still need broader live testing.

**Do not install `RecipeWeaverSmoke` on a player server.** It is a test plugin that shuts its server down after its checks.

## Guides

| I want to… | Read this |
| --- | --- |
| Install the plugin and get into the menus | [Getting started](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Getting-Started) |
| Make and test my first recipe | [Gameplay](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Gameplay) |
| Make a named item or custom reward | [Custom items](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Custom-Items) |
| Use larger tables, brewing, or other workstations | [Tables and stations](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Tables-and-Stations) |
| Set up staff access or look up a command | [Commands and permissions](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Commands-and-Permissions) |
| Back up recipes or configure storage | [Settings, backups, and storage](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Settings-Backups-and-Storage) |
| Find out why something is not working | [Troubleshooting](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Troubleshooting) |

<details>
<summary>Development and release information</summary>

Build with JDK 25; application classes target Java 21:

```powershell
.\gradlew.bat check verifyBuildJar :smoke:jar
```

On Linux or macOS, use `./gradlew` with the same tasks. The modules are `api`, `adapters`, `core`, and the test-only `smoke` module. The server matrix script requires separately provisioned local servers.

The [release index](releases/README.md) contains retained versions. The [3.7 release record](releases/3.7-CHANGE-LEDGER.md), [audit report](audit/CODE-AUDIT-2026-10-11.txt), and [remaining work](NEXT_RELEASE_WORK.md) explain what was tested and what is still pending.

</details>
