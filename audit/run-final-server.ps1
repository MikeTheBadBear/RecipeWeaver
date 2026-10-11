param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('paper-1.20.6', 'purpur-1.20.6', 'spigot-1.20.6', 'paper-26.2', 'purpur-26.2', 'spigot-26.2')]
    [string]$Server
)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$expected = (Get-FileHash -LiteralPath (Join-Path $workspace 'releases/RecipeWeaver-3.7.jar') -Algorithm SHA256).Hash
$candidate = (Get-FileHash -LiteralPath (Join-Path $workspace 'core/build/libs/RecipeWeaver-3.7.jar') -Algorithm SHA256).Hash
if ($candidate -ne $expected) { throw 'Candidate differs from retained artifact' }
foreach ($mode in @('development', 'candidate', 'retained')) {
    $output = Join-Path $PSScriptRoot "matrix/$mode/$Server"
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $options = @{ Version = '3.7'; Only = @($Server) }
    if ($mode -eq 'development') { $options.Development = $true }
    if ($mode -eq 'candidate') { $options.CandidateRelease = $true }
    & (Join-Path $workspace 'run-smoke-matrix.ps1') @options *> (Join-Path $output 'matrix.log')
    if (-not $?) { throw "Matrix failed: $mode $Server" }
    $serverDirectory = Join-Path $workspace "run/$Server"
    Copy-Item -LiteralPath (Join-Path $serverDirectory 'smoke-3.7-stdout.log') -Destination (Join-Path $output 'stdout.log')
    Copy-Item -LiteralPath (Join-Path $serverDirectory 'smoke-3.7-stderr.log') -Destination (Join-Path $output 'stderr.log')
    $installed = (Get-FileHash -LiteralPath (Join-Path $serverDirectory 'plugins/RecipeWeaver-3.7.jar') -Algorithm SHA256).Hash
    if ($installed -ne $expected) { throw "Installed JAR differs: $mode $Server" }
    $installed | Set-Content -LiteralPath (Join-Path $output 'installed-sha256.txt')
    if (-not (Select-String -LiteralPath (Join-Path $output 'stdout.log') -Pattern 'RW_NATIVE_GRID_FALLBACK_PASS' -Quiet)) {
        throw "Missing native fallback regression: $mode $Server"
    }
    Write-Output "PASS $mode $Server SHA256=$installed"
}
