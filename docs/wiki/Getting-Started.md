# Getting started

This page is for the person installing RecipeWeaver on a server. If your server already has it, skip to [Gameplay](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Gameplay).

## 1. Check your server

RecipeWeaver is a server plugin for **Paper, Purpur, and Spigot**. It is not a Fabric mod, and it will not load on an unmodified vanilla server. Players do not need to install RecipeWeaver on their own computers to use its menus.

The support target is Minecraft **1.20.6 through 26.2**. Version 3.7 was tested at the two ends of that range on all three server types. Use a small test server first if you are using a different version or a large collection of plugins.

The plugin is compiled for Java 21. Your Minecraft server may require a newer Java release, so follow the Java requirement for the server build you are running. Do not downgrade a newer server's Java just because the plugin targets Java 21.

## 2. Download the production JAR

Open [RecipeWeaver 3.7](https://github.com/MikeTheBadBear/RecipeWeaver/blob/main/releases/RecipeWeaver-3.7.jar) and use GitHub's download control to save the actual JAR. A page ending in `.jar` can still be a GitHub file-preview page; make sure you downloaded the file rather than saving the web page.

The file to install is:

```text
RecipeWeaver-3.7.jar
```

There are older builds in the repository because release history is retained. Install only one RecipeWeaver production JAR at a time.

**Do not install `RecipeWeaverSmoke-*.jar` on your player server.** That is a test plugin. It runs checks and then shuts the server down.

## 3. Put it in the server's plugins folder

1. Stop the server normally.
2. Open the folder containing your server installation.
3. Put the production JAR in its `plugins` folder.
4. If you are upgrading, move the previous RecipeWeaver JAR to a backup folder outside `plugins`.
5. Start the server normally and check the console for loading errors.

The layout should look roughly like this; the server JAR's name may be different:

```text
my-server/
  paper.jar
  plugins/
    RecipeWeaver-3.7.jar
```

Keep the existing `plugins/RecipeWeaver/` data folder when upgrading. It holds your recipes, items, and settings. Removing that folder is not part of a normal update.

## 4. Check that it loaded

Join the server and run:

```text
/rw info
```

The response includes the RecipeWeaver version, recipe counts, server information, and lockdown state. Zero recipes on a new installation is normal: you have not made any yet.

If Minecraft says the command is unknown, check the server console before going further. The plugin may not have loaded. The [troubleshooting page](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Troubleshooting) explains the common causes.

## 5. Give the right people access

On a fresh installation, operators can open the studio and administration menus. Ordinary players can use the recipe book.

For a first test, use an operator account. From your own server console, the usual Minecraft command is `op YourPlayerName`. Only make someone an operator if you intend to give them broad server control. For regular staff access, use your permissions plugin instead.

The RecipeWeaver permission for creating and editing recipes is `recipeweaver.studio`. Server settings and backup commands use `recipeweaver.admin`. The [command reference](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Commands-and-Permissions) lists all four permissions.

## 6. Open the studio

In game, run:

```text
/rw create
```

You should see **RecipeWeaver Studio**. Its choices include:

- **Create shaped recipe** — a normal crafting recipe where the arrangement matters.
- **Create shapeless recipe** — a normal crafting recipe where ingredient order does not matter.
- **Custom item editor** — edit a copy of the item in your main hand.
- **Recipe book** — browse saved recipes.
- **Create table recipe** — make recipes for Advanced or Elite tables.
- **Create station recipe** — make a recipe for another workstation.
- **Saved custom items** — open items you have already saved.

Administrators also see **Global settings**.

You can open the same studio with `/rw` or `/rw studio`. These menu commands need an in-game player; the server console cannot show an inventory menu.

## You can leave these things alone for now

You do not need to configure SQL storage or install ItemsAdder, Oraxen, MMOItems, MythicMobs, or PlaceholderAPI to make an ordinary recipe. Those integrations are optional.

A fresh setup uses local files, has lockdown switched off, leaves the optional book item off, and keeps backups without a retention limit. The normal `/recipes` command works without the book item.

You are ready for [your first recipe](https://github.com/MikeTheBadBear/RecipeWeaver/wiki/Gameplay).
