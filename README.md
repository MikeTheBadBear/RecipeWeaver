# RecipeWeaver

Project backup containing the plugin source, Gradle wrapper, tests, release history, and code-audit evidence.

Latest installable development build: [RecipeWeaver 3.7](releases/RecipeWeaver-3.7.jar).
SHA-256: `3BB76E0EC30CEF39D16AA29B272394978EED130054B7DF14C05DDA10F00B72C8`.
All 89 versioned release JARs are retained in [releases](releases).

The latest audit fixed native advanced-table recipe restriction bypasses, missing returned containers, anvil prompt control leakage, and book prompt races. Validation: 580 tests passed, 22 skipped, and 18 server runs passed across Paper, Purpur, and Spigot at Minecraft 1.20.6 and 26.2. See the [audit report](audit/CODE-AUDIT-2026-10-11.txt), [release record](releases/3.7-CHANGE-LEDGER.md), and [remaining work](NEXT_RELEASE_WORK.md) for details and testing boundaries. This remains an intermediate development build.

Build with JDK 25; application classes target Java 21:

```powershell
.\gradlew.bat check verifyBuildJar :smoke:jar
```

On Linux or macOS, use `./gradlew` with the same tasks. The modules are `api`, `adapters`, `core`, and `smoke`. Local test-server installations/worlds and generated build caches are excluded from this backup. Running `run-smoke-matrix.ps1` requires the separately provisioned local servers.

Install only the production RecipeWeaver JAR. `RecipeWeaverSmoke` is a test-only plugin that shuts down its test server after verification.
