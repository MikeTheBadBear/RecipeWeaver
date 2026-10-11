# RecipeWeaver build plan

RecipeWeaver is an independent private Minecraft plugin by TM Game Studio. It uses its own code, interface, and assets. Keep each prior `RecipeWeaver-<version>.jar` in `releases`; the smoke JAR is test-only.

## Required support

- One installable JAR for stable Minecraft 1.20.6 through 26.2 on Paper, Purpur, and Spigot.
- Java 21 bytecode; use the Java runtime required by each server version.
- All RecipeWeaver settings editable in game.
- Shared Bukkit core and version-specific adapters where needed. Public APIs first.
- Plugin-owned file and database reads/writes, backup ZIP work, and RecipeWeaver log emission must run on dedicated I/O workers without blocking normal server ticks. Keep Bukkit world, inventory, recipe registration, and item serialization on the server thread where the API requires it; use immutable snapshots and ordered commits across the boundary.
- Make persistence and reload APIs asynchronous or lifecycle-safe while preserving write order, failure reporting, backup consistency, and shutdown durability. A worker thread plus a main-thread wait is not sufficient to claim nonblocking behavior.

## Recipes and stations

- Shaped and shapeless crafting, auto-crafter, furnace, blast furnace, smoker, campfire, smithing and armor trims, anvil, grindstone, stonecutter, brewing stand, and cauldron mixing.
- Placeable advanced table and elite crafting grids from 2x2 through 6x6. Advanced table accepts vanilla recipes.
- Vanilla recipe disabling and overrides; priority, groups, symmetry, discovery, and recipe-book visibility.

## Ingredients, rules, and results

- Custom item editor, item tags and alternatives, optional/empty ingredients, exact/partial/component matching, ingredient amounts, consumption, replacement, and remains.
- Weighted result choices with stable previews; item component and SNBT copying; durability, enchantments, lore, armor trims, commands, sounds, advancements, and actions once or per craft batch.
- Conditions for permissions, world, biome, time, weather, experience, scoreboard, advancements, workstation, craft limits, and delays.

## In-game interface

- Edit every recipe and global setting, including data sources and backups, with previews and validation.
- Short text uses anvil input; long text uses an in-game book editor.
- Player recipe book has categories, filters, sorting, discovery, and an optional right-click book item.

## Storage and extensions

- Versioned recipe and item data upgrades.
- Ordered local-file and SQL sources with overwrite and propagation rules.
- SQLite, H2, MySQL, MariaDB, PostgreSQL, SQL Server, and Oracle, with required drivers bundled.
- ZIP or directory backups, retention, and GUI restore.
- Optional ItemsAdder, Oraxen, MMOItems, MythicMobs, and PlaceholderAPI hooks.
- Bukkit service API for lookup, registration, item providers, conditions, actions, and craft events.

## Commands

`/rw` opens the studio. `/rw recipes`, `give`, `reload`, `lockdown`, `backup`, `restore`, and `info` provide those functions. `/recipes` opens the book where available. Admin permissions default to operators; player book access is available by default.

## Milestones

1. Compatibility probe and 0.1.0: prove the adapter and one-JAR approach on oldest and newest versions; shaped/shapeless, custom item editor, core GUI, basic book, and local saving.
2. 0.2.0: every native workstation, auto-crafter, advanced table, elite grids, brewing, and cauldron mixing.
3. 0.3.0: complete ingredient, condition, result, action, book, and optional integration systems.
4. 1.0.0: every selected database, ordered sources, backups, restore, global settings GUI, and release hardening.

A numbered development JAR is not a completed milestone until its acceptance checks pass.

## Verification gates

- For every milestone, automated recipe tests and real-server tests on available Paper, Purpur, and Spigot builds across the target range.
- Cover native previews, normal and shift crafting, automation, ingredient consumption, permissions, lockdown, restart persistence, and duplication risks.
- Before 1.0.0, real save/read round trips on every storage backend, GUI editing of every setting, packaged JAR and metadata inspection, and coexistence of old/new JARs.
- Do not assume an item exists in an older Minecraft version.
- Optional item hooks get published-API and contract tests; label live behavior unverified until real plugins are tested.
- Thread-affinity tests and server checks must prove plugin-owned storage and log calls execute off the main thread; include rapid writes, reload/backup races, write failures, and shutdown flushing before claiming complete asynchronous I/O.
- No CustomCrafting data importer or Fabric support. English initially, with editable, translation-ready text.