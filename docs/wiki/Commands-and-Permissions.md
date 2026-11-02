# Commands and permissions

Commands below are written as you would enter them in Minecraft chat. In the server console, omit the opening `/`. A command that opens a menu needs an in-game player.

`/recipeweaver` is an alias for `/rw`.

## Command reference

| Command | What it does | Permission |
| --- | --- | --- |
| `/rw` | Opens the studio for an in-game player. | `recipeweaver.studio` |
| `/rw studio` | Opens the same studio. | `recipeweaver.studio` |
| `/rw create` | Opens the studio to choose a recipe type. | `recipeweaver.studio` |
| `/recipes` | Opens the player recipe book. | `recipeweaver.recipes` |
| `/rw recipes` | Opens the same recipe book. | `recipeweaver.recipes` |
| `/rw settings` | Opens global settings in game. | `recipeweaver.admin` |
| `/rw table advanced [player]` | Gives a RecipeWeaver Advanced table. | `recipeweaver.admin` |
| `/rw table elite [player]` | Gives a RecipeWeaver Elite table. | `recipeweaver.admin` |
| `/rw give <player> <item-id>` | Gives a saved or resolved item template to an online player. | `recipeweaver.give` |
| `/rw info` | Shows version, recipe counts, platform, and lockdown status. Administrators also see storage-source information. | No additional permission check for this subcommand. |
| `/rw reload` | Reloads RecipeWeaver data and reports success or failure. | `recipeweaver.admin` |
| `/rw lockdown` | Begins a lockdown toggle; repeat within 30 seconds to confirm. | `recipeweaver.admin` |
| `/rw backup` | Creates a RecipeWeaver backup and reports its name. | `recipeweaver.admin` |
| `/rw backups` | Lists saved backups, showing up to the latest ten names. | `recipeweaver.admin` |
| `/rw restore <backup-name> confirm` | Restores a named backup after creating a recovery snapshot of current data. | `recipeweaver.admin` |

Angle brackets mean a value is required. Square brackets mean it is optional. Do not type the brackets themselves.

For a table command used in game, leaving out `[player]` gives the table to you. From the console, provide an online player's name.

For example:

```text
/rw table advanced Alex
/rw give Alex recipeweaver:travel_token
```

The named item must exist. `travel_token` is the example item from [Custom items](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Custom-Items), not a template supplied on every fresh installation.

## Permission defaults

| Permission | Default | Intended use |
| --- | --- | --- |
| `recipeweaver.recipes` | Everyone | Browse the recipe book. |
| `recipeweaver.studio` | Operators | Create, edit, and delete recipes and custom-item templates. |
| `recipeweaver.admin` | Operators | Global settings, tables, reload, lockdown, and backups. |
| `recipeweaver.give` | Operators | Give saved or resolved items through `/rw give`. |

Give staff only the jobs they need. Someone who should design recipes may need `recipeweaver.studio` without needing backup restore or global database settings. A normal player usually needs only `recipeweaver.recipes`.

Use your installed permissions plugin to assign these nodes. RecipeWeaver does not require a particular permissions plugin, and `/rw give` is not a permission-management command.

An editor can configure post-craft commands for supported recipe types, including commands run as the console. Treat recipe-design access as a trusted staff role.

## Recipe permissions are a different thing

The four nodes above control access to RecipeWeaver's commands and menus. An individual recipe can also have a **permission condition**.

For example, a table recipe might require `myserver.blacksmith`. A player could have permission to browse the book while still lacking that recipe's crafting condition. Giving them `recipeweaver.recipes` does not grant every recipe condition.

## Using the recipe book

Open `/recipes` and click a recipe to see its inputs, result, type, and available requirements. The bottom row provides navigation and filters:

- **Category** cycles between workbench, station, Advanced, Elite, and all recipes.
- **Search recipes** searches IDs, groups, kinds, and result names. Right-click the search control to clear it.
- **Sort** changes the ordering.
- **Group** cycles through the available recipe groups.
- **Discovery** shows all, discovered, or undiscovered recipes.
- Page arrows move through longer lists.

Search and filters combine. If a recipe seems to have disappeared, clear the search, choose all categories/groups/discovery states, and look again.

Studio users can see disabled or hidden entries that regular players do not see. Lockdown also hides custom recipe entries from the normal-player view. Seeing a recipe as an administrator is not proof that an ordinary player can see or craft it. A visible recipe can still have conditions that prevent crafting.

The **Create custom recipe** button takes authorized staff into the studio. It does not let ordinary players bypass `recipeweaver.studio`.

## Optional recipe-book item

An administrator can enable **Right-click book item** in `/rw settings`. Then a player can open `/recipes` and use **Get recipe book item** to receive the tagged book. Right-clicking that item opens the browser.

A normal book renamed to look like it is not the same shortcut item. Players still need `recipeweaver.recipes` to open the browser with it. The `/recipes` command remains available when the optional item is off.

Discovery labels are browsing information. Filtering the book to discovered recipes is not the same as adding a permission condition or disabling a recipe. Test any progression rules with a non-operator player.
