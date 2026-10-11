param([string]$Version = "3.7", [switch]$RequireGridPrior, [switch]$RequireGridAmountPrior, [switch]$RequireGridOptionsPrior, [switch]$RequireGridChoicesPrior, [switch]$RequireGridResultsPrior, [switch]$RequireGridUsesPrior, [switch]$RequireGridConditionsPrior, [switch]$RequireGridOptionalPrior, [switch]$RequireGridSoundPrior, [switch]$RequireGridAdvancementPrior, [switch]$RequireGridPartialPrior, [switch]$RequireGridCommandPrior, [switch]$RequireGridShiftPrior, [switch]$RequireGridCooldownPrior, [switch]$RequireGridMetadataPrior, [switch]$RequireGridCraftLimitPrior, [switch]$RequireItemMetadataPrior, [switch]$RequireSqlPrior, [switch]$RequireSqlMirrorPrior, [switch]$RequireRemoteSqlPrior, [switch]$RequireMultiSqlPrior, [switch]$RequireSqlRecoveryPrior, [switch]$RequireSqlPreferencePrior, [switch]$InjectSqlRecovery, [switch]$InjectSqlPreference, [string]$ExpectedRecoverySource = "SQLITE", [switch]$RequireVanillaPrior, [switch]$RequireWorkbenchChoicesPrior, [switch]$RequireStationChoicesPrior, [switch]$RequireCauldronPrior, [switch]$RequireStationRulePrior, [switch]$RequireStationIngredientPrior, [switch]$RequireStationOptionalPrior, [string[]]$Only = @(), [switch]$Development, [switch]$CandidateRelease)

$ErrorActionPreference = "Stop"
if ($Development -and $CandidateRelease) { throw "Choose development or candidate release mode" }
if ($ExpectedRecoverySource -notin @("SQLITE", "H2")) { throw "Unsupported expected recovery source" }
if ($InjectSqlRecovery -and $InjectSqlPreference) { throw "Choose one SQL startup fault probe" }
$workspace = [System.IO.Path]::GetFullPath($PSScriptRoot)
$production = if ($Development -or $CandidateRelease) {
    Join-Path $workspace "core/build/libs/RecipeWeaver-$Version.jar"
} else {
    Join-Path $workspace "releases/RecipeWeaver-$Version.jar"
}
$smoke = Join-Path $workspace "smoke/build/libs/RecipeWeaverSmoke-$Version.jar"
$requireShutdown = $Development -or [version]$Version -ge [version]"0.2.27"
$requireGridOptions = $Development -or [version]$Version -ge [version]"0.2.28"
$requireGridChoices = $Development -or [version]$Version -ge [version]"0.2.29"
$requireGridResults = $Development -or [version]$Version -ge [version]"0.2.34"
$requireGridUses = $Development -or [version]$Version -ge [version]"0.2.35"
$requireGridConditions = $Development -or [version]$Version -ge [version]"0.2.36"
$requireGridOptional = $Development -or [version]$Version -ge [version]"0.2.37"
$requireGridSound = $Development -or [version]$Version -ge [version]"0.2.39"
$requireGridAdvancement = $Development -or [version]$Version -ge [version]"0.2.40"
$requireGridPartial = $Development -or [version]$Version -ge [version]"0.2.43"
$requireGridCommand = $Development -or [version]$Version -ge [version]"0.2.44"
$requireItemMetadata = $Development -or [version]$Version -ge [version]"0.2.45"
$requireSqlStore = $Development -or [version]$Version -ge [version]"0.2.41"
$requireSqlMirror = $Development -or [version]$Version -ge [version]"0.2.42"
$requireRemoteSql = $Development -or [version]$Version -ge [version]"0.2.46"
$requireMultiSql = $Development -or [version]$Version -ge [version]"0.2.47"
$requireSqlAudit = $Development -or [version]$Version -ge [version]"0.2.48"
$requireSqlRecovery = $Development -or [version]$Version -ge [version]"0.2.49"
$requireSqlPreference = $Development -or [version]$Version -ge [version]"0.2.50"
$requireSqlRuntime = $Development -or [version]$Version -ge [version]"0.2.51"
$requireRawLocalReloadRecovery = [version]$Version -ge [version]"0.2.75"
$requireGridCooldown = $Development -or [version]$Version -ge [version]"0.2.52"
$requireGridCraftLimit = $Development -or [version]$Version -ge [version]"0.2.53"
$requireNativeBook = $Development -or [version]$Version -ge [version]"0.2.54"
$requireNativeCategory = $Development -or [version]$Version -ge [version]"0.2.55"
$requireItemProvider = $Development -or [version]$Version -ge [version]"0.2.56"
$requireGridExtensions = $Development -or [version]$Version -ge [version]"0.2.57"
$requireSqlExactSource = $Development -or [version]$Version -ge [version]"0.2.58"
$requireVanilla = $Development -or [version]$Version -ge [version]"0.2.38"
$requireWorkbenchChoices = $Development -or [version]$Version -ge [version]"0.2.30"
$requireStationChoices = $Development -or [version]$Version -ge [version]"0.2.31"
$requireStationDiscovery = $Development -or [version]$Version -ge [version]"0.2.32"
$requireStationRules = $Development -or [version]$Version -ge [version]"0.2.61"
$requireStationConditions = $Development -or [version]$Version -ge [version]"0.2.62"
$requireStationActions = $Development -or [version]$Version -ge [version]"0.2.63"
$requireStationMetadata = $Development -or [version]$Version -ge [version]"0.2.64"
$requireStationIngredients = $Development -or [version]$Version -ge [version]"0.2.65"
$requireStationOptional = $Development -or [version]$Version -ge [version]"0.2.66"
$requireSqlGlobalGuard = $Development -or [version]$Version -ge [version]"0.2.67"
$requireSqlConcurrentGuard = $Development -or [version]$Version -ge [version]"0.2.68"
$requireStationExtensions = $Development -or [version]$Version -ge [version]"0.2.69"
$requireFullItemData = $Development -or [version]$Version -ge [version]"0.2.70"
$requireItemComponent = $Development -or [version]$Version -ge [version]"0.2.71"
$requireNativeCraftGuard = $Development -or [version]$Version -ge [version]"0.2.72"
$requireNativeCrafterGuard = $Development -or [version]$Version -ge [version]"0.2.75"
$requireNativeSmithingGuard = $Development -or [version]$Version -ge [version]"0.2.73"
$requireStationStaleResult = $Development -or [version]$Version -ge [version]"0.2.74"
$requireStonecutterGuard = $Development -or [version]$Version -ge [version]"0.2.74"
$requireVanillaStonecutterGuard = $Development -or [version]$Version -ge [version]"0.2.75"
$requireNativeCookingGuard = $Development -or [version]$Version -ge [version]"0.2.75"
$requireNativeCampfirePulse = $Development -or [version]$Version -ge [version]"0.2.75"
$requireVanillaCookingGuard = $Development -or [version]$Version -ge [version]"0.2.75"
$requireCommonComponents = $Development -or [version]$Version -ge [version]"0.2.75"
$requireArmorTrimResultCopy = $Development -or [version]$Version -ge [version]"0.2.75"
$requireMaxDurabilityResultCopy = $Development -or [version]$Version -ge [version]"0.2.75"
$requireCompatibleComponentResultCopy = $Development -or [version]$Version -ge [version]"0.2.75"
$requirePotionBaseResultCopy = $Development -or [version]$Version -ge [version]"0.2.75"
$requireStoredEnchantResultCopy = $Development -or [version]$Version -ge [version]"0.2.75"
$requireCrossbowResultCopy = $Development -or [version]$Version -ge [version]"0.2.75"
$requireStewResultCopy = $Development -or [version]$Version -ge [version]"0.2.75"
$requireFireworkResultCopy = $Development -or [version]$Version -ge [version]"0.2.75"
$requireTropicalFishResultCopy = $Development -or [version]$Version -ge [version]"0.2.75"
$requireFireworkStarResultCopy = $Development -or [version]$Version -ge [version]"0.2.75"
$requireWrittenBookResultCopy = $Development -or [version]$Version -ge [version]"0.2.75"
$requireCompassResultCopy = $Development -or [version]$Version -ge [version]"0.2.75"
$requireBundleResultCopy = $Development -or [version]$Version -ge [version]"0.2.75"
$requireRepairCostResultCopy = $Development -or [version]$Version -ge [version]"0.2.75"
$requireAxolotlResultCopy = $Development -or [version]$Version -ge [version]"0.2.75"
$requireKnowledgeBookResultCopy = $Development -or [version]$Version -ge [version]"0.2.75"
$requireGoatHornResultCopy = $Development -or [version]$Version -ge [version]"0.2.75"
$requireOminousBottleResultCopy = $Development -or [version]$Version -ge [version]"0.2.75"
$requireMapResultCopy = $Development -or [version]$Version -ge [version]"0.2.75"
$requireBlockItemResultCopy = $Development -or [version]$Version -ge [version]"0.2.75"
$requireAdvancedItems = $Development -or [version]$Version -ge [version]"0.2.75"
$requireTypedItemData = $Development -or [version]$Version -ge [version]"0.2.74"
$requireNextNativeAndItemRules = $Development -or [version]$Version -ge [version]"0.2.76"
$requireNextRelease = $Development -or [version]$Version -ge [version]"0.2.77"
$requireFollowingRelease = $Development -or [version]$Version -ge [version]"0.2.78"
$requireUpcomingRelease = $Development -or [version]$Version -ge [version]"0.2.79"
$requirePostRelease = $Development -or [version]$Version -ge [version]"0.2.80"
$requireNextDevelopment = $Development -or [version]$Version -ge [version]"0.2.81"
$requireScopedRelease = $Development -or [version]$Version -ge [version]"0.2.82"
$requireNextScopedRelease = $Development -or [version]$Version -ge [version]"0.2.83"
$requireDirectoryBackup = $Development -or [version]$Version -ge [version]"0.2.33"
if (-not (Test-Path -LiteralPath $production -PathType Leaf)) { throw "Missing production JAR: $production" }
if (-not (Test-Path -LiteralPath $smoke -PathType Leaf)) { throw "Missing test-only smoke JAR: $smoke" }
foreach ($jar in @($production, $smoke)) {
    $archiveCheck = [IO.Compression.ZipFile]::OpenRead($jar)
    try {
        $seenEntries = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($entry in $archiveCheck.Entries) {
            if (-not $seenEntries.Add($entry.FullName)) { throw "Duplicate JAR entry in ${jar}: $($entry.FullName)" }
            if ($entry.FullName.EndsWith('.class') -and $entry.FullName.Contains(' ')) {
                throw "Invalid class entry in ${jar}: $($entry.FullName)"
            }
        }
    } finally { $archiveCheck.Dispose() }
}
$java21 = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe"
$java25 = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\java.exe"
$servers = @(
    @{ Name = "paper-1.20.6"; Jar = "paper.jar"; Java = $java21 },
    @{ Name = "purpur-1.20.6"; Jar = "purpur.jar"; Java = $java21 },
    @{ Name = "spigot-1.20.6"; Jar = "spigot.jar"; Java = $java21 },
    @{ Name = "paper-26.2"; Jar = "paper.jar"; Java = $java25 },
    @{ Name = "purpur-26.2"; Jar = "purpur.jar"; Java = $java25 },
    @{ Name = "spigot-26.2"; Jar = "spigot.jar"; Java = $java25 }
)
foreach ($entry in $servers) {
    if ($Only.Count -gt 0 -and $entry.Name -notin $Only) { continue }
    $server = [System.IO.Path]::GetFullPath((Join-Path $workspace ("run/" + $entry.Name)))
    if (-not $server.StartsWith((Join-Path $workspace "run") + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase)) { throw "Unsafe server path: $server" }
    $plugins = Join-Path $server "plugins"
    $archive = Join-Path $server "disabled-plugin-jars"
    if (-not (Test-Path -LiteralPath $plugins -PathType Container)) { throw "Missing plugins directory: $plugins" }
    $requirePlaceholderLive = @(Get-ChildItem -LiteralPath $plugins -File -Filter 'PlaceholderAPI*.jar').Count -gt 0
    $requireMythicLive = @(Get-ChildItem -LiteralPath $plugins -File -Filter 'MythicMobs*.jar').Count -gt 0
    if (-not (Test-Path -LiteralPath (Join-Path $server $entry.Jar) -PathType Leaf)) {
        throw "Missing server JAR: $($entry.Name)"
    }
    New-Item -ItemType Directory -Path $archive -Force | Out-Null
    foreach ($old in @(Get-ChildItem -LiteralPath $plugins -File | Where-Object {
        $_.Name -match '^RecipeWeaver(?:Smoke)?-.*\.jar$' -and
        $_.Name -notin @("RecipeWeaver-$Version.jar", "RecipeWeaverSmoke-$Version.jar")
    })) {
        $target = Join-Path $archive $old.Name
        if (Test-Path -LiteralPath $target) {
            $target = Join-Path $archive ($old.BaseName + "-" + [guid]::NewGuid().ToString("N").Substring(0, 8) + $old.Extension)
        }
        Move-Item -LiteralPath $old.FullName -Destination $target
    }
    Copy-Item -LiteralPath $production -Destination (Join-Path $plugins "RecipeWeaver-$Version.jar") -Force
    Copy-Item -LiteralPath $smoke -Destination (Join-Path $plugins "RecipeWeaverSmoke-$Version.jar") -Force
    if ($InjectSqlRecovery) {
        $items = Join-Path $plugins "RecipeWeaver/items.yml"
        if (-not (Test-Path -LiteralPath $items -PathType Leaf)) {
            throw "Cannot inject SQL recovery probe without prior item data: $items"
        }
        [System.IO.File]::WriteAllText($items, "schema: 999`nitems: {}`n",
                [System.Text.UTF8Encoding]::new($false))
    }
    if ($InjectSqlPreference) {
        $items = Join-Path $plugins "RecipeWeaver/items.yml"
        if (-not (Test-Path -LiteralPath $items -PathType Leaf)) {
            throw "Cannot inject SQL preference probe without prior item data: $items"
        }
        $itemText = Get-Content -LiteralPath $items -Raw
        $localOnlyKey = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("recipeweaver:smoke_local_only")).TrimEnd('=').Replace('+', '-').Replace('/', '_')
        if ($itemText.Contains($localOnlyKey)) { throw "Local-only preference probe item already exists" }
        $versionMatch = [regex]::Match($itemText, '(?m)^    v: (\d+)\r?$')
        if ($versionMatch.Success) {
            $added = "`n  ${localOnlyKey}:`n    ==: org.bukkit.inventory.ItemStack`n    v: $($versionMatch.Groups[1].Value)`n    type: STONE`n"
        } else {
            $versionMatch = [regex]::Match($itemText, '(?m)^    DataVersion: (\d+)\r?$')
            if (-not $versionMatch.Success) { throw "Cannot find item data version for SQL preference probe" }
            $added = "`n  ${localOnlyKey}:`n    ==: org.bukkit.inventory.ItemStack`n    DataVersion: $($versionMatch.Groups[1].Value)`n    id: minecraft:stone`n    count: 1`n    schema_version: 1`n"
        }
        [System.IO.File]::AppendAllText($items, $added, [System.Text.UTF8Encoding]::new($false))
    }
    $stdout = Join-Path $server "smoke-$Version-stdout.log"
    $stderr = Join-Path $server "smoke-$Version-stderr.log"
    Write-Output "$(if ($Development) { 'DEVELOPMENT RUN' } else { 'RUN' }) $($entry.Name)"
    $process = Start-Process -FilePath $entry.Java -ArgumentList @("-Xms512m", "-Xmx2g", "-jar", $entry.Jar, "nogui") -WorkingDirectory $server -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    if (-not $process.WaitForExit(240000)) {
        Stop-Process -Id $process.Id -Force
        throw "Server timed out: $($entry.Name). See $stdout"
    }
    $log = Get-Content -LiteralPath (Join-Path $server "logs/latest.log") -Raw
    if ($process.ExitCode -ne 0 -or $log -notmatch 'RW_SMOKE_PASS' -or
        $log -notmatch 'RW_SMOKE_BACKUP_RESTORE_PASS' -or
        $log -notmatch 'RW_BACKUP_ASYNC_PASS' -or
        $log -notmatch 'RW_BACKUP_COMMAND_PASS' -or
        $log -notmatch 'RecipeWeaver backups:' -or
        $log -notmatch 'RW_RELOAD_ASYNC_PASS' -or
        $log -notmatch 'RW_RELOAD_COMMAND_PASS' -or
        $log -notmatch 'RW_RESTORE_ASYNC_PASS' -or
        $log -notmatch 'RW_RESTORE_COMMAND_PASS' -or
        $log -notmatch 'RW_TABLE_ASYNC_PASS' -or
        $log -notmatch 'RW_SHAPE_VARIANTS_PASS' -or
        $log -notmatch 'RW_GRID_API_PASS' -or
        $log -notmatch 'RW_GRID_AMOUNT_PASS' -or
        ($requireGridOptions -and $log -notmatch 'RW_GRID_OPTIONS_PASS') -or
        ($requireGridChoices -and $log -notmatch 'RW_GRID_CHOICES_PASS') -or
        ($requireGridResults -and $log -notmatch 'RW_GRID_RESULTS_PASS') -or
        ($requireGridUses -and $log -notmatch 'RW_GRID_USES_PASS') -or
        ($requireGridConditions -and $log -notmatch 'RW_GRID_CONDITIONS_PASS') -or
        ($requireGridOptional -and $log -notmatch 'RW_GRID_OPTIONAL_PASS') -or
        ($requireGridSound -and $log -notmatch 'RW_GRID_SOUND_PASS') -or
        ($requireGridAdvancement -and $log -notmatch 'RW_GRID_ADVANCEMENT_PASS') -or
        ($requireGridPartial -and $log -notmatch 'RW_GRID_PARTIAL_PASS') -or
        ($requireGridCommand -and $log -notmatch 'RW_GRID_COMMAND_PASS') -or
        ($requireGridCommand -and $log -notmatch 'RW_GRID_EVENT_API_PASS') -or
        ($requireItemMetadata -and $log -notmatch 'RW_ITEM_METADATA_PASS') -or
        ($requireSqlStore -and $log -notmatch 'RW_SQL_STORE_PASS') -or
        ($requireSqlGlobalGuard -and $log -notmatch 'RW_SQL_GLOBAL_GUARD_PASS') -or
        ($requireSqlConcurrentGuard -and $log -notmatch 'RW_SQL_CONCURRENT_GUARD_PASS') -or
        ($requireSqlStore -and $log -notmatch 'RW_SQL_SNAPSHOT_PASS') -or
        ($requireSqlMirror -and $log -notmatch 'RW_SQL_MIRROR_PASS') -or
        ($requireRemoteSql -and $log -notmatch 'RW_REMOTE_SQL_PROFILE_PASS') -or
        ($requireMultiSql -and $log -notmatch 'RW_MULTI_SQL_PASS') -or
        ($requireSqlAudit -and $log -notmatch 'RW_SQL_SOURCE_AUDIT_PASS') -or
        ($requireSqlExactSource -and $log -notmatch 'RW_SQL_EXACT_SOURCE_PASS') -or
        ($requireSqlRecovery -and $log -notmatch 'RW_SQL_RECOVERY_POLICY_PASS') -or
        ($requireSqlPreference -and $log -notmatch 'RW_SQL_PREFERENCE_POLICY_PASS') -or
        ($requireSqlRuntime -and $log -notmatch 'RW_SQL_RUNTIME_POLICY_PASS') -or
        ($requireSqlRuntime -and $log -notmatch 'RW_SQL_RUNTIME_RELOAD_PASS') -or
        ($requireSqlRuntime -and $log -notmatch 'RW_SQL_RUNTIME_NOOP_PASS') -or
        ($requireRawLocalReloadRecovery -and $log -notmatch 'RW_SQL_RAW_LOCAL_RELOAD_RECOVERY_PASS') -or
        ($requireSqlRuntime -and $log -notmatch 'RW_SQL_RUNTIME_INVALID_FALLBACK_PASS') -or
        ($requireSqlRuntime -and $log -notmatch 'Skipped invalid SQLITE SQL reload source') -or
        ($requireSqlRuntime -and $log -notmatch 'RW_GRID_SHIFT_LIMIT_PASS') -or
        ($requireSqlRuntime -and $log -notmatch 'RW_GRID_TRANSACTION_PASS') -or
        (([version]$Version -ge [version]"3.7") -and $log -notmatch 'RW_NATIVE_GRID_FALLBACK_PASS') -or
        ($requireGridCooldown -and $log -notmatch 'RW_GRID_COOLDOWN_PASS') -or
        ($requireGridCooldown -and $log -notmatch 'RW_GRID_METADATA_PASS') -or
        ($requireGridCraftLimit -and $log -notmatch 'RW_GRID_CRAFT_LIMIT_PASS') -or
        ($requireNativeBook -and $log -notmatch 'RW_NATIVE_BOOK_PASS') -or
        ($requireNativeCategory -and $log -notmatch 'RW_NATIVE_CATEGORY_PASS') -or
        ($requireItemProvider -and $log -notmatch 'RW_ITEM_PROVIDER_PASS') -or
        ($requireGridExtensions -and $log -notmatch 'RW_GRID_EXTENSIONS_PASS') -or
        ($InjectSqlRecovery -and $log -notmatch "Recovered invalid local data from $ExpectedRecoverySource") -or
        ($InjectSqlPreference -and $log -notmatch 'Loaded preferred SQL startup source SQLITE') -or
        ($requireVanilla -and $log -notmatch 'RW_VANILLA_OVERRIDE_PASS') -or
        ($requireWorkbenchChoices -and $log -notmatch 'RW_WORKBENCH_CHOICES_PASS') -or
        ($requireStationChoices -and $log -notmatch 'RW_STATION_CHOICES_PASS') -or
        ($requireStationRules -and $log -notmatch 'RW_STATION_RULES_PASS') -or
        ($requireStationConditions -and $log -notmatch 'RW_STATION_CONDITIONS_PASS') -or
        ($requireStationActions -and $log -notmatch 'RW_STATION_ACTIONS_PASS') -or
        ($requireStationMetadata -and $log -notmatch 'RW_STATION_METADATA_PASS') -or
        ($requireStationIngredients -and $log -notmatch 'RW_STATION_INGREDIENTS_PASS') -or
        ($requireStationOptional -and $log -notmatch 'RW_STATION_OPTIONAL_PASS') -or
        ($requireStationExtensions -and $log -notmatch 'RW_STATION_EXTENSIONS_PASS') -or
        ($requireFullItemData -and $log -notmatch 'RW_FULL_ITEM_DATA_GRID_PASS') -or
        ($requireFullItemData -and $log -notmatch 'RW_FULL_ITEM_DATA_STATION_PASS') -or
        ($requireItemComponent -and $log -notmatch 'RW_ITEM_COMPONENT_PASS') -or
        ($requireNativeCraftGuard -and $log -notmatch 'RW_NATIVE_CRAFT_GUARD_PASS') -or
        ($requireNextNativeAndItemRules -and $log -notmatch 'RW_WORKBENCH_CONDITIONS_PASS') -or
        ($requireNativeCrafterGuard -and $entry.Name -like '*26.2' -and $log -notmatch 'RW_NATIVE_CRAFTER_GUARD_PASS') -or
        ($requireNativeSmithingGuard -and $log -notmatch 'RW_NATIVE_SMITHING_GUARD_PASS') -or
        ($requireNextRelease -and $log -notmatch 'RW_NATIVE_SMITHING_CONDITIONS_PASS') -or
        ($requireNextRelease -and $log -notmatch 'RW_NATIVE_STONECUTTER_CONDITIONS_PASS') -or
        ($requireNextRelease -and $log -notmatch 'RW_STATION_WEIGHTED_PASS') -or
        ($requireStationStaleResult -and $log -notmatch 'RW_STATION_STALE_RESULT_PASS') -or
        ($requireStonecutterGuard -and $log -notmatch 'RW_STONECUTTER_GUARD_PASS') -or
        ($requireVanillaStonecutterGuard -and $log -notmatch 'RW_VANILLA_STONECUTTER_GUARD_PASS') -or
        ($requireNativeCookingGuard -and $log -notmatch 'RW_NATIVE_COOKING_GUARD_PASS') -or
        ($requireNativeCampfirePulse -and $log -notmatch 'RW_NATIVE_CAMPFIRE_PULSE_PASS') -or
        ($requirePostRelease -and $log -notmatch 'RW_CAMPFIRE_PICKUP_DISCOVERY_PASS') -or
        ($requirePostRelease -and $log -notmatch 'RW_NATIVE_COOKING_NO_START_PASS') -or
        ($requirePostRelease -and $log -notmatch 'RW_CAMPFIRE_DROP_MERGE_PASS') -or
        ($requireNextDevelopment -and $log -notmatch 'RW_CAMPFIRE_POSTCOMMIT_EVENT_PASS') -or
        ($requireNextNativeAndItemRules -and $log -notmatch 'RW_NATIVE_FURNACE_PHYSICAL_PASS') -or
        ($requireNextNativeAndItemRules -and $log -notmatch 'RW_NATIVE_BLAST_PHYSICAL_PASS') -or
        ($requireNextNativeAndItemRules -and $log -notmatch 'RW_NATIVE_SMOKER_PHYSICAL_PASS') -or
        ($requireNextNativeAndItemRules -and $log -notmatch 'RW_NATIVE_CRAFTER_PHYSICAL_PASS') -or
        ($requireNextNativeAndItemRules -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_NATIVE_CRAFTER_REMAINDER_GUARD_PASS' -and
                $log -notmatch 'RW_NATIVE_CRAFTER_REMAINDER_UNSUPPORTED_API') -or
        ($requireNextNativeAndItemRules -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_NATIVE_CRAFTER_ENVIRONMENT_PASS') -or
        ($requireNextNativeAndItemRules -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_NATIVE_CRAFTER_WEATHER_GUARD_PASS') -or
        ($requireNextNativeAndItemRules -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_NATIVE_CRAFTER_PLAYER_RULE_GUARD_PASS') -or
        ($requireNextNativeAndItemRules -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_NATIVE_CRAFTER_PHYSICAL_UNSUPPORTED_API') -or
        ($requireVanillaCookingGuard -and $log -notmatch 'RW_VANILLA_COOKING_GUARD_PASS') -or
        ($requireCommonComponents -and $log -notmatch 'RW_GRID_COMMON_COMPONENTS_PASS') -or
        ($requireArmorTrimResultCopy -and $log -notmatch 'RW_ARMOR_TRIM_RESULT_COPY_PASS') -or
        ($requireMaxDurabilityResultCopy -and $log -notmatch 'RW_MAX_DURABILITY_RESULT_COPY_PASS') -or
        ($requireCompatibleComponentResultCopy -and $log -notmatch 'RW_COMPATIBLE_COMPONENT_RESULT_COPY_PASS') -or
        ($requirePotionBaseResultCopy -and $log -notmatch 'RW_POTION_BASE_RESULT_COPY_PASS') -or
        ($requireStoredEnchantResultCopy -and $log -notmatch 'RW_STORED_ENCHANT_RESULT_COPY_PASS') -or
        ($requireCrossbowResultCopy -and $log -notmatch 'RW_CROSSBOW_RESULT_COPY_PASS') -or
        ($requireStewResultCopy -and $log -notmatch 'RW_STEW_RESULT_COPY_PASS') -or
        ($requireFireworkResultCopy -and $log -notmatch 'RW_FIREWORK_RESULT_COPY_PASS') -or
        ($requireTropicalFishResultCopy -and $log -notmatch 'RW_TROPICAL_FISH_RESULT_COPY_PASS') -or
        ($requireFireworkStarResultCopy -and $log -notmatch 'RW_FIREWORK_STAR_RESULT_COPY_PASS') -or
        ($requireWrittenBookResultCopy -and $log -notmatch 'RW_WRITTEN_BOOK_RESULT_COPY_PASS') -or
        ($requireCompassResultCopy -and $log -notmatch 'RW_COMPASS_RESULT_COPY_PASS') -or
        ($requireBundleResultCopy -and $log -notmatch 'RW_BUNDLE_RESULT_COPY_PASS') -or
        ($requireRepairCostResultCopy -and $log -notmatch 'RW_REPAIR_COST_RESULT_COPY_PASS') -or
        ($requireAxolotlResultCopy -and $log -notmatch 'RW_AXOLOTL_RESULT_COPY_PASS') -or
        ($requireKnowledgeBookResultCopy -and $log -notmatch 'RW_KNOWLEDGE_BOOK_RESULT_COPY_PASS') -or
        ($requireGoatHornResultCopy -and $log -notmatch 'RW_GOAT_HORN_RESULT_COPY_PASS') -or
        ($requireOminousBottleResultCopy -and $log -notmatch 'RW_OMINOUS_BOTTLE_RESULT_COPY_PASS') -or
        ($requireMapResultCopy -and $log -notmatch 'RW_MAP_RESULT_COPY_PASS') -or
        ($requireBlockItemResultCopy -and $log -notmatch 'RW_BLOCK_ITEM_RESULT_COPY_PASS') -or
        ($requireNextNativeAndItemRules -and $log -notmatch 'RW_RESULT_NAME_TYPED_PASS') -or
        ($requireNextNativeAndItemRules -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_RESULT_MODERN_COMPONENTS_PASS') -or
        ($requireNextNativeAndItemRules -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_RESULT_MODERN_COMPONENTS_UNSUPPORTED_API') -or
        ($requireNextNativeAndItemRules -and $log -notmatch 'RW_RESULT_FOOD_CONSUMPTION_PASS') -or
        ($requireNextNativeAndItemRules -and $log -notmatch 'RW_RESULT_ATTRIBUTE_MODIFIER_PASS') -or
        ($requireNextRelease -and $log -notmatch 'RW_RESULT_PERSISTENT_COMPOUND_PASS') -or
        ($requireNextRelease -and $log -notmatch 'RW_COPY_MODEL_DATA_PASS') -or
        ($requireNextRelease -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_COPY_RICH_MODEL_DATA_PASS') -or
        ($requireNextRelease -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_COPY_RICH_MODEL_DATA_UNSUPPORTED_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_COPY_USE_REMAINDER_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_COPY_USE_COOLDOWN_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_COPY_EQUIPPABLE_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_COPY_USE_REMAINDER_UNSUPPORTED_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_COPY_USE_COOLDOWN_UNSUPPORTED_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_COPY_EQUIPPABLE_UNSUPPORTED_PASS') -or
        ($requireFollowingRelease -and
                $log -notmatch 'RW_COPY_CUSTOM_DATA_REPLACEMENT_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_COPY_GLIDER_PASS') -or
        ($requireFollowingRelease -and $entry.Name -eq 'spigot-26.2' -and
                $log -notmatch 'RW_COPY_ADDITIONAL_TRADE_COST_PASS') -or
        ($requireFollowingRelease -and $entry.Name -eq 'spigot-26.2' -and
                $log -notmatch 'RW_COPY_MINIMUM_ATTACK_CHARGE_PASS') -or
        ($requireFollowingRelease -and $entry.Name -eq 'spigot-26.2' -and
                $log -notmatch 'RW_COPY_BREAK_SOUND_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*26.2' -and
                $entry.Name -ne 'spigot-26.2' -and
                $log -notmatch 'RW_COPY_ADDITIONAL_TRADE_COST_UNSUPPORTED_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*26.2' -and
                $entry.Name -ne 'spigot-26.2' -and
                $log -notmatch 'RW_COPY_MINIMUM_ATTACK_CHARGE_UNSUPPORTED_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*26.2' -and
                $entry.Name -ne 'spigot-26.2' -and
                $log -notmatch 'RW_COPY_BREAK_SOUND_UNSUPPORTED_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_COPY_GLIDER_UNSUPPORTED_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_COPY_ADDITIONAL_TRADE_COST_UNSUPPORTED_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_COPY_MINIMUM_ATTACK_CHARGE_UNSUPPORTED_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_COPY_BREAK_SOUND_UNSUPPORTED_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_COPY_JUKEBOX_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_COPY_DAMAGE_RESISTANCE_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_COPY_JUKEBOX_UNSUPPORTED_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_COPY_DAMAGE_RESISTANCE_UNSUPPORTED_PASS') -or
        ($requireAdvancedItems -and $log -notmatch 'RW_ADVANCED_ITEM_PASS') -or
        ($requireAdvancedItems -and $log -notmatch 'RW_EDIT_AXOLOTL_PASS') -or
        ($requireAdvancedItems -and $log -notmatch 'RW_EDIT_KNOWLEDGE_BOOK_PASS') -or
        ($requireAdvancedItems -and $log -notmatch 'RW_EDIT_GOAT_HORN_PASS') -or
        ($requireAdvancedItems -and $log -notmatch 'RW_EDIT_OMINOUS_BOTTLE_PASS') -or
        ($requireAdvancedItems -and $log -notmatch 'RW_EDIT_BUNDLE_PASS') -or
        ($requireAdvancedItems -and $log -notmatch 'RW_EDIT_REPAIR_COST_PASS') -or
        ($requireAdvancedItems -and $log -notmatch 'RW_EDIT_BOOK_GENERATION_PASS') -or
        ($requireAdvancedItems -and $log -notmatch 'RW_EDIT_BLOCK_ITEM_STATE_PASS') -or
        ($requireAdvancedItems -and $log -notmatch 'RW_EDIT_ITEM_NAME_PASS') -or
        ($requireNextNativeAndItemRules -and $log -notmatch 'RW_EDIT_PERSISTENT_LISTS_PASS') -or
        ($requireNextNativeAndItemRules -and $log -notmatch 'RW_EDIT_ATTRIBUTE_MODIFIER_PASS') -or
        ($requireNextRelease -and $log -notmatch 'RW_EDIT_PERSISTENT_COMPOUND_PASS') -or
        ($requireNextRelease -and $log -notmatch 'RW_MATCH_ITEM_FLAGS_PASS') -or
        ($requireNextRelease -and $log -notmatch 'RW_EDIT_ITEM_TEXT_PASS') -or
        ($requireNextRelease -and $log -notmatch 'RW_ITEM_TEXT_PERSISTED_PASS') -or
        ($requireNextRelease -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_EDIT_RICH_MODEL_DATA_PASS') -or
        ($requireNextRelease -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_PERSIST_RICH_MODEL_DATA_PASS') -or
        ($requireNextRelease -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_EDIT_RICH_MODEL_DATA_UNSUPPORTED_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_EDIT_USE_REMAINDER_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_EDIT_USE_COOLDOWN_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_EDIT_EQUIPPABLE_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_PERSIST_USE_REMAINDER_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_PERSIST_USE_COOLDOWN_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_PERSIST_EQUIPPABLE_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_EDIT_USE_REMAINDER_UNSUPPORTED_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_EDIT_USE_COOLDOWN_UNSUPPORTED_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_EDIT_EQUIPPABLE_UNSUPPORTED_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_EDIT_GLIDER_PASS') -or
        ($requireFollowingRelease -and $entry.Name -eq 'spigot-26.2' -and
                $log -notmatch 'RW_EDIT_TRADE_COST_PASS') -or
        ($requireFollowingRelease -and $entry.Name -eq 'spigot-26.2' -and
                $log -notmatch 'RW_EDIT_ATTACK_CHARGE_PASS') -or
        ($requireFollowingRelease -and $entry.Name -eq 'spigot-26.2' -and
                $log -notmatch 'RW_EDIT_BREAK_SOUND_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_PERSIST_GLIDER_PASS') -or
        ($requireFollowingRelease -and $entry.Name -eq 'spigot-26.2' -and
                $log -notmatch 'RW_PERSIST_TRADE_COST_PASS') -or
        ($requireFollowingRelease -and $entry.Name -eq 'spigot-26.2' -and
                $log -notmatch 'RW_PERSIST_ATTACK_CHARGE_PASS') -or
        ($requireFollowingRelease -and $entry.Name -eq 'spigot-26.2' -and
                $log -notmatch 'RW_PERSIST_BREAK_SOUND_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*26.2' -and
                $entry.Name -ne 'spigot-26.2' -and
                $log -notmatch 'RW_EDIT_TRADE_COST_UNSUPPORTED_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*26.2' -and
                $entry.Name -ne 'spigot-26.2' -and
                $log -notmatch 'RW_EDIT_ATTACK_CHARGE_UNSUPPORTED_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*26.2' -and
                $entry.Name -ne 'spigot-26.2' -and
                $log -notmatch 'RW_EDIT_BREAK_SOUND_UNSUPPORTED_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_EDIT_GLIDER_UNSUPPORTED_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_EDIT_TRADE_COST_UNSUPPORTED_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_EDIT_ATTACK_CHARGE_UNSUPPORTED_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_EDIT_BREAK_SOUND_UNSUPPORTED_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_EDIT_JUKEBOX_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_EDIT_DAMAGE_RESISTANCE_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_PERSIST_JUKEBOX_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_PERSIST_DAMAGE_RESISTANCE_PASS') -or
        ($requireUpcomingRelease -and $log -notmatch 'RW_COPY_WRITABLE_BOOK_PAGES_PASS') -or
        ($requireUpcomingRelease -and $log -notmatch 'RW_COPY_HEAD_PROFILE_PASS') -or
        ($requireUpcomingRelease -and $log -notmatch 'RW_COPY_HEAD_NOTE_SOUND_PASS') -or
        ($requireUpcomingRelease -and $log -notmatch 'RW_EDIT_WRITABLE_PAGES_PASS') -or
        ($requireUpcomingRelease -and $log -notmatch 'RW_EDIT_HEAD_PROFILE_PASS') -or
        ($requireUpcomingRelease -and $log -notmatch 'RW_EDIT_HEAD_NOTE_SOUND_PASS') -or
        ($requireUpcomingRelease -and $log -notmatch 'RW_PERSIST_WRITABLE_PAGES_PASS') -or
        ($requireUpcomingRelease -and $log -notmatch 'RW_PERSIST_HEAD_PROFILE_PASS') -or
        ($requireUpcomingRelease -and $log -notmatch 'RW_PERSIST_HEAD_NOTE_SOUND_PASS') -or
        ($requireUpcomingRelease -and $log -notmatch 'RW_COPY_SHULKER_BLOCK_STATE_PASS') -or
        ($requireUpcomingRelease -and $log -notmatch 'RW_EDIT_SHULKER_CONTENTS_PASS') -or
        ($requireUpcomingRelease -and $log -notmatch 'RW_PERSIST_SHULKER_CONTENTS_PASS') -or
        ($requireUpcomingRelease -and $log -notmatch 'RW_GRID_SHULKER_NO_DUP_PASS') -or
        ($requireUpcomingRelease -and $log -notmatch 'RW_COPY_CUSTOM_DATA_ONLY_PASS') -or
        ($requireUpcomingRelease -and $log -notmatch 'RW_COPY_MODEL_ONLY_PASS') -or
        ($requireUpcomingRelease -and $log -notmatch 'RW_COPY_ENCHANTMENTS_ONLY_PASS') -or
        ($requireUpcomingRelease -and $log -notmatch 'RW_GRID_CUSTOM_DATA_ONLY_MATCH_PASS') -or
        ($requireUpcomingRelease -and $log -notmatch 'RW_EDIT_GROUPED_TOOL_REPLACE_PASS') -or
        ($requireUpcomingRelease -and $log -notmatch 'RW_EDIT_GROUPED_TOOL_REMOVE_PASS') -or
        ($requireUpcomingRelease -and $log -notmatch 'RW_PERSIST_GROUPED_TOOL_RULE_PASS') -or
        ($requirePostRelease -and $log -notmatch 'RW_MATCH_NAME_ONLY_PASS') -or
        ($requirePostRelease -and $log -notmatch 'RW_MATCH_LORE_ONLY_PASS') -or
        ($requirePostRelease -and $log -notmatch 'RW_MATCH_MODEL_ONLY_PASS') -or
        ($requirePostRelease -and $log -notmatch 'RW_MATCH_ENCHANTMENTS_ONLY_PASS') -or
        ($requirePostRelease -and $log -notmatch 'RW_MATCH_DAMAGE_ONLY_PASS') -or
        ($requirePostRelease -and $log -notmatch 'RW_MATCH_ITEM_FLAGS_ONLY_PASS') -or
        ($requirePostRelease -and $log -notmatch 'RW_MATCH_ATTRIBUTE_MODIFIERS_ONLY_PASS') -or
        ($requirePostRelease -and $log -notmatch 'RW_MATCH_UNBREAKABLE_ONLY_PASS') -or
        ($requirePostRelease -and $log -notmatch 'RW_MATCH_ARMOR_TRIM_ONLY_PASS') -or
        ($requirePostRelease -and $log -notmatch 'RW_PERSIST_FIELD_MATCH_MODES_PASS') -or
        ($requirePostRelease -and $log -notmatch 'RW_COPY_FOOD_ONLY_PASS') -or
        ($requirePostRelease -and $log -notmatch 'RW_COPY_TOOL_ONLY_PASS') -or
        ($requirePostRelease -and $log -notmatch 'RW_COPY_ATTRIBUTE_MODIFIERS_ONLY_PASS') -or
        ($requirePostRelease -and $log -notmatch 'RW_COPY_DAMAGE_ONLY_PASS') -or
        ($requirePostRelease -and $log -notmatch 'RW_COPY_ITEM_FLAGS_ONLY_PASS') -or
        ($requirePostRelease -and $log -notmatch 'RW_COPY_UNBREAKABLE_ONLY_PASS') -or
        ($requirePostRelease -and $log -notmatch 'RW_COPY_ARMOR_TRIM_ONLY_PASS') -or
        ($requireNextDevelopment -and $log -notmatch 'RW_MATCH_POTION_ONLY_PASS') -or
        ($requireNextDevelopment -and $log -notmatch 'RW_MATCH_STORED_ENCHANTMENTS_ONLY_PASS') -or
        ($requireNextDevelopment -and $log -notmatch 'RW_MATCH_BANNER_PATTERNS_ONLY_PASS') -or
        ($requireNextDevelopment -and $log -notmatch 'RW_COPY_POTION_ONLY_PASS') -or
        ($requireNextDevelopment -and $log -notmatch 'RW_COPY_STORED_ENCHANTMENTS_ONLY_PASS') -or
        ($requireNextDevelopment -and $log -notmatch 'RW_COPY_BANNER_PATTERNS_ONLY_PASS') -or
        ($requireNextDevelopment -and $log -notmatch 'RW_MATCH_CROSSBOW_PROJECTILES_ONLY_PASS') -or
        ($requireNextDevelopment -and $log -notmatch 'RW_MATCH_FIREWORK_ROCKET_ONLY_PASS') -or
        ($requireNextDevelopment -and $log -notmatch 'RW_MATCH_FIREWORK_STAR_ONLY_PASS') -or
        ($requireNextDevelopment -and $log -notmatch 'RW_COPY_CROSSBOW_PROJECTILES_ONLY_PASS') -or
        ($requireNextDevelopment -and $log -notmatch 'RW_COPY_FIREWORK_ROCKET_ONLY_PASS') -or
        ($requireNextDevelopment -and $log -notmatch 'RW_COPY_FIREWORK_STAR_ONLY_PASS') -or
        ($requireNextDevelopment -and $log -notmatch 'RW_MATCH_BUNDLE_CONTENTS_ONLY_PASS') -or
        ($requireNextDevelopment -and $log -notmatch 'RW_COPY_BUNDLE_CONTENTS_ONLY_PASS') -or
        ($requireNextDevelopment -and $log -notmatch 'RW_EDIT_BUNDLE_APPEND_PASS') -or
        ($requireNextDevelopment -and $log -notmatch 'RW_EDIT_BUNDLE_REPLACE_PASS') -or
        ($requireNextDevelopment -and $log -notmatch 'RW_EDIT_BUNDLE_REMOVE_PASS') -or
        ($requireNextDevelopment -and $log -notmatch 'RW_EDIT_BUNDLE_INVALID_INDEX_PASS') -or
        ($requireNextDevelopment -and $log -notmatch 'RW_PERSIST_BUNDLE_SLOT_EDITOR_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_EDIT_JUKEBOX_UNSUPPORTED_PASS') -or
        ($requireFollowingRelease -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_EDIT_DAMAGE_RESISTANCE_UNSUPPORTED_PASS') -or
        ($requireNextNativeAndItemRules -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_EDIT_ITEM_MODEL_PASS') -or
        ($requireNextNativeAndItemRules -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_EDIT_TOOLTIP_STYLE_PASS') -or
        ($requireNextNativeAndItemRules -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_EDIT_ENCHANTABLE_PASS') -or
        ($requireNextNativeAndItemRules -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_EDIT_ITEM_MODEL_UNSUPPORTED_PASS') -or
        ($requireNextNativeAndItemRules -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_EDIT_TOOLTIP_STYLE_UNSUPPORTED_PASS') -or
        ($requireNextNativeAndItemRules -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_EDIT_ENCHANTABLE_UNSUPPORTED_PASS') -or
        ($requireTypedItemData -and $log -notmatch 'RW_TYPED_ITEM_DATA_PASS') -or
        ($requireStationDiscovery -and $log -notmatch 'RW_STATION_DISCOVERY_PASS') -or
        ($requireSqlRuntime -and $log -notmatch 'RW_FURNACE_CLICK_DISCOVERY_PASS') -or
        ($requireUpcomingRelease -and $log -notmatch 'RW_CUSTOM_ONLY_NATIVE_BOOK_GUARD_PASS') -or
        ($requireUpcomingRelease -and $log -notmatch 'RW_FURNACE_AMBIGUOUS_DISCOVERY_PASS') -or
        ($requireUpcomingRelease -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_STONECUTTER_SELECTED_API_PASS') -or
        ($requireUpcomingRelease -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_STONECUTTER_SELECTED_API_UNSUPPORTED_PASS') -or
        ($requireNextDevelopment -and $entry.Name -like '*26.2' -and
                $log -notmatch 'RW_STONECUTTER_SELECTED_GUARD_SYNTHETIC_PASS') -or
        ($requireNextDevelopment -and $entry.Name -like '*1.20.6' -and
                $log -notmatch 'RW_STONECUTTER_SELECTED_GUARD_UNSUPPORTED_PASS') -or
        ($requireNextDevelopment -and $log -notmatch 'RW_NATIVE_COOKING_EXTENSION_VETO_SYNTHETIC_PASS') -or
        ($requireNextDevelopment -and $log -notmatch 'RW_NATIVE_COOKING_POSTCOMMIT_EVENT_PASS') -or
        $log -notmatch 'RW_DISCOVERY_ASYNC_PASS' -or
        $log -notmatch 'RW_DISCOVERY_API_PASS' -or
        $log -notmatch 'RW_ITEM_ASYNC_PASS' -or
        $log -notmatch 'RW_SETTINGS_ASYNC_PASS' -or
        ($requireNextRelease -and $log -notmatch 'RW_GLOBAL_SETTINGS_API_PASS') -or
        ($requireFollowingRelease -and $log -notmatch 'RW_SQL_SETTINGS_MAIN_THREAD_PASS') -or
        $log -notmatch 'RW_RECIPE_ASYNC_PASS' -or
        $log -notmatch 'RW_STATION_GRID_ASYNC_PASS' -or
        $log -notmatch 'RW_CAULDRON_RUNTIME_PASS' -or
        ($requireNextRelease -and $log -notmatch 'RW_CAULDRON_CONDITIONS_PASS') -or
        ($requireNextRelease -and $log -notmatch 'RW_CAULDRON_WEIGHTED_PASS') -or
        ($requireFollowingRelease -and $log -notmatch 'RW_CAULDRON_EXTENSIONS_PASS') -or
        $log -notmatch 'RW_BREW_RUNTIME_PASS' -or
        ($requireNextRelease -and $log -notmatch 'RW_BREWING_RECIPE_CHANGE_PASS') -or
        ($requireNextRelease -and $log -notmatch 'RW_BREWING_CONDITIONS_PASS') -or
        ($requireFollowingRelease -and $log -notmatch 'RW_BREWING_WEIGHTED_PASS') -or
        ($requireFollowingRelease -and $log -notmatch 'RW_BREWING_EXTENSIONS_PASS') -or
        ($requireUpcomingRelease -and $log -notmatch 'RW_NATIVE_BREW_CONDITION_VETO_PASS') -or
        ($requireUpcomingRelease -and $log -notmatch 'RW_NATIVE_BREW_LOCKDOWN_VETO_PASS') -or
        ($requirePlaceholderLive -and $log -notmatch 'RW_PLACEHOLDERAPI_LIVE_PASS') -or
        ($requireMythicLive -and $log -notmatch 'RW_MYTHICMOBS_ITEM_PASS') -or
        ($requirePostRelease -and $requireMythicLive -and
                $log -notmatch 'RW_MYTHICMOBS_VANILLA_FALLBACK_PASS') -or
        ($requireStationDiscovery -and $log -notmatch 'RW_BREW_DISCOVERY_PASS') -or
        ($requireUpcomingRelease -and $log -notmatch 'RW_BREW_DISCOVERY_SLOT_RETAIN_PASS') -or
        ($requireDirectoryBackup -and $log -notmatch 'RW_DIRECTORY_BACKUP_PASS') -or
        $log -notmatch 'RW_AUTOCRAFTER_RUNTIME_PASS' -or
        ($RequireGridPrior -and $log -notmatch 'RW_GRID_PRIOR_SAVED=true') -or
        ($RequireGridAmountPrior -and $log -notmatch 'RW_GRID_AMOUNT_PRIOR_SAVED=true') -or
        ($RequireGridOptionsPrior -and $log -notmatch 'RW_GRID_OPTIONS_PRIOR_SAVED=true') -or
        ($RequireGridChoicesPrior -and $log -notmatch 'RW_GRID_CHOICES_PRIOR_SAVED=true') -or
        ($RequireGridResultsPrior -and $log -notmatch 'RW_GRID_RESULTS_PRIOR_SAVED=true') -or
        ($RequireGridUsesPrior -and $log -notmatch 'RW_GRID_USES_PRIOR_SAVED=true') -or
        ($RequireGridConditionsPrior -and $log -notmatch 'RW_GRID_CONDITIONS_PRIOR_SAVED=true') -or
        ($RequireGridOptionalPrior -and $log -notmatch 'RW_GRID_OPTIONAL_PRIOR_SAVED=true') -or
        ($RequireGridSoundPrior -and $log -notmatch 'RW_GRID_SOUND_PRIOR_SAVED=true') -or
        ($RequireGridAdvancementPrior -and $log -notmatch 'RW_GRID_ADVANCEMENT_PRIOR_SAVED=true') -or
        ($RequireGridPartialPrior -and $log -notmatch 'RW_GRID_PARTIAL_PRIOR_SAVED=true') -or
        ($RequireGridCommandPrior -and $log -notmatch 'RW_GRID_COMMAND_PRIOR_SAVED=true') -or
        ($RequireGridShiftPrior -and $log -notmatch 'RW_GRID_SHIFT_PRIOR_SAVED=true') -or
        ($RequireGridCooldownPrior -and $log -notmatch 'RW_GRID_COOLDOWN_PRIOR_SAVED=true') -or
        ($RequireGridMetadataPrior -and $log -notmatch 'RW_GRID_METADATA_PRIOR_SAVED=true') -or
        ($requireFullItemData -and -not $Development -and
                $log -notmatch 'RW_FULL_ITEM_DATA_GRID_PRIOR_SAVED=true') -or
        ($requireFullItemData -and -not $Development -and
                $log -notmatch 'RW_FULL_ITEM_DATA_STATION_PRIOR_SAVED=true') -or
        ($RequireGridCraftLimitPrior -and $log -notmatch 'RW_GRID_CRAFT_LIMIT_PRIOR_SAVED=true') -or
        ($requireNativeBook -and -not $Development -and $log -notmatch 'RW_NATIVE_BOOK_PRIOR_SAVED=true') -or
        ($requireNativeCategory -and -not $Development -and $log -notmatch 'RW_NATIVE_CATEGORY_PRIOR_SAVED=true') -or
        ($RequireItemMetadataPrior -and $log -notmatch 'RW_ITEM_METADATA_PRIOR_SAVED=true') -or
        ($requireItemComponent -and -not $Development -and
                $log -notmatch 'RW_ITEM_COMPONENT_PRIOR_SAVED=true') -or
        ($requireAdvancedItems -and -not $Development -and
                $log -notmatch 'RW_ADVANCED_ITEM_PRIOR_SAVED=true') -or
        ($RequireSqlPrior -and $log -notmatch 'RW_SQL_PRIOR_SAVED=true') -or
        ($RequireSqlMirrorPrior -and $log -notmatch 'RW_SQL_MIRROR_PRIOR_SAVED=true') -or
        ($RequireRemoteSqlPrior -and $log -notmatch 'RW_REMOTE_SQL_PRIOR_SAVED=true') -or
        ($RequireMultiSqlPrior -and $log -notmatch 'RW_MULTI_SQL_PRIOR_SAVED=true') -or
        ($RequireSqlRecoveryPrior -and $log -notmatch 'RW_SQL_RECOVERY_PRIOR_SAVED=true') -or
        ($RequireSqlPreferencePrior -and $log -notmatch 'RW_SQL_PREFERENCE_PRIOR_SAVED=true') -or
        ($RequireVanillaPrior -and $log -notmatch 'RW_VANILLA_PRIOR_SAVED=true') -or
        ($requireNativeCraftGuard -and -not $Development -and
                $log -notmatch 'RW_NATIVE_CRAFT_GUARD_PRIOR_SAVED=true') -or
        ($RequireWorkbenchChoicesPrior -and $log -notmatch 'RW_WORKBENCH_CHOICES_PRIOR_SAVED=true') -or
        ($RequireStationChoicesPrior -and $log -notmatch 'RW_STATION_CHOICES_PRIOR_SAVED=true') -or
        ($RequireStationRulePrior -and $log -notmatch 'RW_STATION_RULES_PRIOR_SAVED=true') -or
        ($requireNativeSmithingGuard -and -not $Development -and
                $log -notmatch 'RW_NATIVE_SMITHING_GUARD_PRIOR_SAVED=true') -or
        ($RequireStationRulePrior -and $requireStationMetadata -and $log -notmatch 'RW_STATION_METADATA_PRIOR_SAVED=true') -or
        ($RequireStationIngredientPrior -and $log -notmatch 'RW_STATION_INGREDIENTS_PRIOR_SAVED=true') -or
        ($RequireStationOptionalPrior -and $log -notmatch 'RW_STATION_OPTIONAL_PRIOR_SAVED=true') -or
        ($RequireCauldronPrior -and $log -notmatch 'RW_CAULDRON_PRIOR_SAVED=true') -or
        $log -notmatch 'RW_SMOKE_PRIOR_SAVED=true' -or
        ($requireShutdown -and $log -notmatch 'RW_SHUTDOWN_SAVE_QUEUED') -or
        $log -match 'RW_SMOKE_FAIL') {
        throw "Smoke failed: $($entry.Name), exit $($process.ExitCode). See logs/latest.log"
    }
    if ($requireScopedRelease) {
        $requiredScopedMarkers = @(
            'RW_ITEM_FIELD_RULES_PASS',
            'RW_NATIVE_COOKING_ACTION_PASS',
            'RW_NATIVE_COOKING_ACTION_DENIAL_PASS',
            'RW_AUTOCRAFTER_SHAPELESS_EXACT_DENIAL_PASS',
            'RW_AUTOCRAFTER_SHAPED_EXACT_DENIAL_PASS',
            'RW_CAULDRON_FIRST_AMOUNT_PERSISTED_PASS',
            'RW_CAULDRON_SECOND_AMOUNT_GUARD_PASS',
            'RW_CAULDRON_MULTI_AMOUNT_PASS',
            'RW_CAULDRON_REMAINDERS_PASS',
            'RW_CAULDRON_COPY_FIRST_PASS',
            'RW_CAULDRON_KEPT_SECOND_CHANGED_GUARD_PASS',
            'RW_CAULDRON_KEPT_SECOND_REMOVED_GUARD_PASS',
            'RW_CAULDRON_KEPT_SECOND_PASS',
            'RW_CAULDRON_WEIGHTED_COPY_SECOND_PASS',
            'RW_CAULDRON_ACTIONS_POSTCOMMIT_PASS'
        )
        if ($entry.Name -like '*1.20.6') {
            $requiredScopedMarkers += 'RW_NATIVE_CRAFTER_1206_EXACT_PASS'
            $requiredScopedMarkers += 'RW_NATIVE_CRAFTER_1206_DENIAL_PASS'
            $requiredScopedMarkers += 'RW_NATIVE_CRAFTER_1206_VANILLA_CACHE_DENIAL_PASS'
        }
        if ($requireNextScopedRelease) {
            $requiredScopedMarkers += 'RW_ITEM_COMPOUND_ARRAY_EDITOR_PASS'
            $requiredScopedMarkers += 'RW_BREWING_ADVANCED_RULES_PHYSICAL_PASS'
            $requiredScopedMarkers += 'RW_ITEM_MAP_LOCATION_MATCH_PASS'
            $requiredScopedMarkers += 'RW_ITEM_MAP_LOCATION_COPY_PASS'
            $requiredScopedMarkers += 'RW_ITEM_HEAD_SOUND_MATCH_PASS'
            $requiredScopedMarkers += 'RW_ITEM_HEAD_SOUND_COPY_PASS'
        }
        foreach ($marker in $requiredScopedMarkers) {
            if ($log -notmatch [regex]::Escape($marker)) {
                throw "Smoke failed: $($entry.Name) missing $marker. See logs/latest.log"
            }
        }
    }
    if ($InjectSqlPreference) {
        $saved = [regex]::Match($log, 'Loaded preferred SQL startup source SQLITE; previous local files saved as (RecipeWeaver-backup-[A-Za-z0-9._-]+\.(?:zip|dir))')
        if (-not $saved.Success) { throw "Preferred SQL startup did not report its local-data backup" }
        $backup = Join-Path $plugins ("RecipeWeaver/backups/" + $saved.Groups[1].Value)
        if ($saved.Groups[1].Value.EndsWith('.dir')) {
            $savedItems = Get-Content -LiteralPath (Join-Path $backup 'items.yml') -Raw
        } else {
            $archiveFile = [System.IO.Compression.ZipFile]::OpenRead($backup)
            try {
                $entryFile = $archiveFile.GetEntry('items.yml')
                if ($null -eq $entryFile) { throw "Local-data ZIP backup lacks items.yml" }
                $reader = [System.IO.StreamReader]::new($entryFile.Open())
                try { $savedItems = $reader.ReadToEnd() }
                finally { $reader.Dispose() }
            } finally { $archiveFile.Dispose() }
        }
        if (-not $savedItems.Contains($localOnlyKey)) {
            throw "Local-only item was not preserved in the SQL preference backup"
        }
    }
    if ($requireSqlRuntime) {
        $runtimeSaved = [regex]::Match($log, 'Reloaded from SQLITE; previous local files saved as (RecipeWeaver-backup-[A-Za-z0-9._-]+\.(?:zip|dir))')
        if (-not $runtimeSaved.Success) { throw "Runtime SQL reload did not report its local-data backup" }
        $runtimeBackup = Join-Path $plugins ("RecipeWeaver/backups/" + $runtimeSaved.Groups[1].Value)
        if ($runtimeSaved.Groups[1].Value.EndsWith('.dir')) {
            $runtimeItems = Get-Content -LiteralPath (Join-Path $runtimeBackup 'items.yml') -Raw
        } else {
            $runtimeArchive = [System.IO.Compression.ZipFile]::OpenRead($runtimeBackup)
            try {
                $runtimeEntry = $runtimeArchive.GetEntry('items.yml')
                if ($null -eq $runtimeEntry) { throw "Runtime SQL backup lacks items.yml" }
                $runtimeReader = [System.IO.StreamReader]::new($runtimeEntry.Open())
                try { $runtimeItems = $runtimeReader.ReadToEnd() }
                finally { $runtimeReader.Dispose() }
            } finally { $runtimeArchive.Dispose() }
        }
        $runtimeKey = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("recipeweaver:smoke_runtime_local_only")).TrimEnd('=').Replace('+', '-').Replace('/', '_')
        if (-not $runtimeItems.Contains($runtimeKey)) {
            throw "Runtime SQL reload did not preserve the local-only item in its backup"
        }
    }
    if ($requireShutdown) {
        $shutdownItem = Join-Path $plugins "RecipeWeaver/items.yml"
        $shutdownKey = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("recipeweaver:smoke_shutdown")).TrimEnd('=').Replace('+', '-').Replace('/', '_')
        if (-not (Test-Path -LiteralPath $shutdownItem -PathType Leaf) -or
                -not (Get-Content -LiteralPath $shutdownItem -Raw).Contains($shutdownKey)) {
            throw "Queued shutdown item was not durable: $($entry.Name). See $shutdownItem"
        }
    }
    Write-Output "PASS $($entry.Name): API, grids, cauldron, brewing, auto-crafter, reload, lockdown, backup/restore$(if ($requireShutdown) { ', shutdown save' })"
}
