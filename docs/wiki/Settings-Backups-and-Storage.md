# Settings, backups, and storage

Open `/rw settings` with `recipeweaver.admin` to see global controls. For a first installation, ordinary local storage is enough. Make a few recipes and a backup before changing database settings.

## Everyday settings

| Control | What it does |
| --- | --- |
| Right-click book item | Enables the optional book shortcut players can collect from `/recipes`. |
| Recipe lockdown | Temporarily stops RecipeWeaver custom recipes without deleting their saved definitions. |
| Create backup | Saves a snapshot of RecipeWeaver data. |
| Browse backups | Opens the saved backups and restore review flow. |
| Backups to keep | Sets normal retention; `0` means no retention limit. |
| Backup format | Chooses a ZIP archive or directory backup. |
| Disabled vanilla recipes | Manages Minecraft recipe IDs excluded from native registration. |
| Local SQL snapshots | Exports/restores snapshots through SQLite or H2 and manages local mirrors. |
| Remote SQL connection | Configures an optional external database connection. |
| SQL mirror order | Orders selected SQL targets and controls when SQL sources are read. |

Wait for the success message after an operation. If RecipeWeaver says a backup, save, reload, or source change is still running, let it finish before trying another related action.

## Make a backup

Run:

```text
/rw backup
```

Wait for **Backup created** and the saved name. You can also use **Create backup** in settings.

Use `/rw backups` to list names again. For more than ten backups, the GUI is useful for browsing the list.

A RecipeWeaver backup contains its recipe definitions, custom items, settings, table locations, pending cauldron data, and recipe-discovery data. It does **not** back up the entire Minecraft world, other plugins, player inventories, or your server JAR.

Keep a separate server backup for those things. In particular, restoring RecipeWeaver table-location data does not rebuild blocks in the world.

## Restore carefully

The command format is:

```text
/rw restore <backup-name> confirm
```

Replace `<backup-name>` with the exact name shown by `/rw backups`. The final word `confirm` is required. You can also select a backup through **Browse backups** and review the restore there.

Restoring replaces the current RecipeWeaver data with the chosen snapshot. The plugin first creates a recovery snapshot of the current data and reports its name after a successful restore. Keep that name in case you selected the wrong backup.

Before restoring on a player server, tell staff what is changing and make sure you understand which recipes and settings the snapshot contains. Do not treat restore as a way to recover unrelated world or inventory changes.

## Lockdown

For the command version, run `/rw lockdown` and repeat it within 30 seconds to confirm the toggle. Use `/rw info` to check the resulting state.

Lockdown is useful while investigating custom-recipe problems. It leaves saved recipes in place. It is not a server-wide shutdown or a blanket switch for every vanilla recipe. Advanced tables may still use allowed native vanilla recipes.

Toggle it back when you are ready for the custom recipes to be used again.

## Disabling a vanilla recipe

Open **Disabled vanilla recipes** in settings. Use a full Minecraft recipe key, such as:

```text
minecraft:oak_planks
```

A recipe ID identifies a recipe, not necessarily an item with the same name. Different recipes can produce the same material. Check the exact key before excluding it.

This is separate from setting one of your custom recipes to inactive. A custom replacement also needs to be saved and tested at the intended workstation. Changes to native registration may affect recipe-book discoveries, so test with a regular player as well as an operator.

## Where local data lives

RecipeWeaver uses the server's `plugins/RecipeWeaver/` folder. The main data locations are:

| Path | Contents |
| --- | --- |
| `recipes/` | Workbench recipes. |
| `stations/` | Station recipes. |
| `grid-recipes/` | Advanced and Elite table recipes. |
| `items.yml` | Saved custom-item templates. |
| `settings.yml` | Global settings, including configured database details. |
| `tables.yml` | Registered special-table locations. |
| `cauldrons.yml` | Pending cauldron-mixing state. |
| `discoveries/` | Saved recipe-discovery data. |
| `backups/` | RecipeWeaver backup archives or directories. |

Use the menus for normal editing. File names inside recipe directories may be encoded rather than matching the visible ID. Do not rename those files to make them look friendlier.

If you must edit files by hand, stop the server first and keep a copy of the original data. After restarting, check the console and confirm the recipes loaded. `/rw reload` reloads RecipeWeaver data; it does not install a different plugin JAR or replace a full server restart for upgrades.

## Optional SQL storage

SQL is an additional storage feature, not a requirement for crafting. Drivers are bundled for SQLite, H2, MySQL, MariaDB, PostgreSQL, SQL Server, and Oracle.

Local files remain the normal write source. Selected SQL mirrors receive copies of plugin data. Which source is read at startup or reload depends on the read policy and source order. A SQL mirror is not the same thing as an independent historical backup.

### SQLite and H2

Open **Local SQL snapshots** to export the current data, prepare a stored snapshot for restore, or manage the selected local mirrors. Read the restore review before applying data back to the server.

### Remote databases

Open **Remote SQL connection** and set the backend, JDBC URL, username, and password provided by your database administrator or host. Then use **Test save/read/delete** before enabling live mirroring.

That test performs a real small database round trip. A successful connection alone would not prove the account can write and read data. The test also does not establish every recovery or concurrent-write scenario.

Connection details are saved locally in RecipeWeaver settings. Backups containing those settings should be kept private. Redact passwords and connection details before posting diagnostic files publicly.

### Read policies

| Policy | Meaning |
| --- | --- |
| `LOCAL_ONLY` | Reads local files. This is the default. |
| `RECOVER_ON_STARTUP` | Allows selected SQL sources to help recover startup data when the local source cannot be used. |
| `PREFER_SQL_AT_STARTUP` | Prefers the first usable selected SQL source at startup. |
| `PREFER_SQL_AT_STARTUP_AND_RELOAD` | Extends that SQL preference to runtime reloads. |

Source selection and validation can cause fallback. `/rw info` shows administrators which source was actually used at startup and at the last reload. Check that report instead of assuming the selected preference guarantees a particular source was loaded.

Make a local backup before changing the policy or restoring a SQL snapshot. Stay with `LOCAL_ONLY` until you have tested your chosen database and know why you want it to become a read source.

SQLite and H2 have current automated coverage. Earlier local-server checks covered MySQL and PostgreSQL, but those external tests were not rerun in the 3.7 audit. Live MariaDB, SQL Server, and Oracle checks remain pending. Bundling a driver is not proof that every backend has been fully tested.
