# ATLauncher-Enhanced Roadmap

A working handoff for this fork of [ATLauncher](https://github.com/ATLauncher/ATLauncher).
It separates what upstream already provides from what this fork adds, so future
work extends existing code instead of rebuilding it.

- Fork remote: `origin` → `TooCoolCello/ATLauncher-Enhanced`
- Upstream remote: `upstream` → `ATLauncher/ATLauncher`
- Base branch: `master` (tracks upstream releases; fork work is **not** merged into it)
- Fork branch: `consolidated` holds every fork feature below in one place. Start new work from it.

Paths below are relative to `src/main/java/com/atlauncher/` unless they start with `src/`.

## Before implementing a feature

1. **Inspect the checked-out base and fork first.** Read the relevant upstream code
   and the fork branches before designing anything. A keyword search that finds
   nothing does **not** prove a feature is absent. Trace the actual UI and data flow.
2. **Extend existing code.** Add Mods, Edit Mods, the file/version selectors,
   dependency handling, server creation and import/export already exist. Build on
   them rather than writing parallel implementations.
3. **Keep project-level loader metadata separate from actual file compatibility.**
   A project listing "Fabric" or "NeoForge" says nothing about whether a specific
   file works with a specific Minecraft version, loader or Connector setup. Filter
   and label from file/version data wherever possible, and say clearly when the
   launcher is guessing.
4. Never stage `.idea/gradle.xml` or other IDE files with feature work.

---

## 1. Already in base ATLauncher

Do not rebuild these. Extend them.

| Feature | Main source paths |
|---|---|
| Add Mods (CurseForge/Modrinth search) | `gui/dialogs/AddModsDialog.java`, `utils/CurseForgeApi.java`, `utils/ModrinthApi.java` |
| Edit Mods (enable/disable, update checks) | `gui/dialogs/EditModsDialog.java`, `data/DisableableMod.java`, `gui/components/ModsJCheckBox.java` |
| CurseForge file selector | `gui/dialogs/CurseForgeProjectFileSelectorDialog.java` |
| Modrinth version selector | `gui/dialogs/ModrinthVersionSelectorDialog.java` |
| Dependency handling (install prompts in selectors) | the two selector dialogs above; `gui/dialogs/ModsChooser.java` |
| Local server creation | `gui/dialogs/InstanceInstallerDialog.java` (`isServer`), `data/Server.java`, `gui/tabs/ServersTab.java` |
| Instance import / export | `gui/dialogs/ImportInstanceDialog.java`, `gui/dialogs/InstanceExportDialog.java`, `gui/dialogs/ModrinthExportOverridesDialog.java` |
| Maximum Java memory | `data/Settings.java` (`maximumMemory`), per-instance overrides in `data/Instance.java` |
| Some Sinytra-aware Fabric browsing | `data/Settings.java` (`showFabricModsWhenSinytraInstalled`), `constants/Constants.java` (Connector IDs), `AddModsDialog.java`, `isForgeLikeAndHasInstalledSinytraConnector()` in `data/Instance.java` / `data/Server.java` / `data/ModManagement.java`, `gui/tabs/settings/ModsSettingsTab.java` |

Note: hiding Fabric results when Connector is absent is **existing base behavior**.
Seeing it does not verify any fork change.

## 2. Completed and pushed in this fork

Each item is on its own branch on `origin`. The branches are stacked in the order
below, and none is merged into `master` yet.

| Feature | Branch | Key paths |
|---|---|---|
| Dialog lifecycle monitoring | `feature/dialog-recovery` | `utils/DialogDebugMonitor.java`, `App.java` |
| Safe dialog recovery | `feature/recover-ui` | `managers/DialogManager.java`, `gui/TrayMenu.java` |
| Edit Mods live search | `feature/edit-mods-search` | `gui/dialogs/EditModsDialog.java` |
| Add Mods page size 25/50/100/200 | *(commit `a38cedd3`; no dedicated branch, pushed as part of `feature/add-mods-list-view` and later)* | `gui/dialogs/AddModsPagination.java`, `data/Settings.java`, `CurseForgeApi.java`, `ModrinthApi.java` |
| Add Mods Cards / List results | `feature/add-mods-list-view` | `data/AddModsViewMode.java`, `gui/card/CompactResultRow.java`, `gui/card/*Card.java` |
| Invalid remote-image-cache handling | `fix/remote-image-cache-empty-url` | `workers/BackgroundImageWorker.java` |
| Loader indicators (official NeoForge/Quilt art, with fallbacks) | `feature/add-mods-loader-icons` | `data/LoaderIndicators.java`, `gui/card/LoaderIndicatorStrip.java`, `src/main/resources/assets/image/loader/` (see `ATTRIBUTION.md`) |

Tests for these are under `src/test/java/com/atlauncher/` (same package as each class).

### On `consolidated` (merged 2026-11-10, none tested by hand yet)

| Feature | Where in the UI | Key paths |
|---|---|---|
| Collections (Favorites, your own collections, saved Modrinth collections) and a Cards / Compact List / List picker | Add Mods > Collections... | `gui/dialogs/CollectionsDialog.java`, `utils/CollectionsLibrary.java`, `utils/ModrinthCollections.java`, `data/ResultsView.java`, `gui/card/ResultRow.java` |
| Loader icons in every results view | Add Mods, Collections | `gui/card/LoaderIndicatorStrip.java` (restored after the view rework dropped it) |
| Evidence-based mod compatibility, contextual Sinytra Connector mark, Hide incompatible | Add Mods results and bottom bar; selector Connector note | `data/ModCompatibility.java`, `data/ModCompatibilityChecker.java` (Modrinth checks spaced 250 ms apart), `gui/card/CompatibilityBadge.java` |
| Flag / hide incompatible modpacks | Packs tab, "Hide incompatible packs" | `data/PackCompatibility.java`, `gui/card/packbrowser/IncompatiblePackFlag.java`, `gui/tabs/PacksBrowserTab.java` |
| Default game options (FOV, sensitivity, render distance, GUI scale, auto-jump, master volume) for new instances | Settings > Java/Minecraft; Edit Instance > Apply Default Game Options | `utils/OptionsTxt.java`, `data/DefaultGameOptions.java`, `workers/InstanceInstaller.java` |
| Client/server side per mod (manual, else Modrinth, else CurseForge) | Edit Mods right-click > Side | `data/ModSide.java`, `DisableableMod.getEffectiveSide()`; used by Modrinth export env and Sync |
| Remote servers over SFTP (trust-on-first-use host keys, password per session only), link an instance, upload with backups | Servers tab > Remote Servers...; Edit Instance > Link Remote Server / Upload | `data/RemoteServer.java`, `network/sftp/*`, `gui/dialogs/RemoteServer*.java`; library `com.github.mwiede:jsch` |
| Sync with Server (preview; stops on Minecraft/loader mismatch; skips client-only mods; removals go to `atlauncher-backups/`) | Edit Instance > Sync with ... | `network/sftp/ServerDetection.java`, `ServerSync.java`, `gui/dialogs/ServerSyncDialog.java` |
| Make an instance from a server (SFTP, folder or server pack zip) | Import Instance > From a Server...; Remote Servers > Make Instance... | `network/sftp/ServerImport.java`, `gui/dialogs/ServerImportDialog.java` |
| Add mods from someone else's modpack or another instance | Add Mods > From Modpack... | `data/PackMods.java`, `gui/dialogs/PackModsDialog.java` |
| Related mods (same author, addons search, dependencies) | Right-click a Mods result > Related... | `data/RelatedMods.java`, `gui/dialogs/RelatedModsDialog.java` |
| Dependency choices (install / keep / replace / skip / enable; incompatible mods; removal checks) | Selectors' Dependencies panel; Edit Mods removal | `data/DependencyChoice.java`, `data/InstalledDependencies.java`, `gui/components/DependencyChoicesPanel.java`, `gui/dialogs/ModRemovalDialog.java` |
| Zip extraction refuses entries that leave the target folder | every zip import | `utils/ArchiveUtils.java` |

## 2b. Committed on a fork branch

For whether a branch is on `origin`, check `git branch -r --contains <commit>`.
This file doesn't record push status because it goes stale.

### Sinytra Connector selection fix

**Branch:** `fix/sinytra-add-mods-selection` (based on `feature/add-mods-loader-icons`).
**Commit:** `dbe6a1c1` "Fix Sinytra Connector mod selection".

Sinytra selector and dependency corrections:
- enabled-Connector detection (a disabled Connector jar no longer counts);
- version-scoped native preference (prefer a native Forge/NeoForge file for *that* version before a Fabric fallback);
- Modrinth `NONE` loader safety;
- dependency-ID fixes;
- null-loader handling;
- honest selector notes ("Uses Sinytra Connector; compatibility is not guaranteed.").

Files:
- New: `data/SinytraConnector.java`
- Modified: `data/Instance.java`, `data/Server.java`, `gui/dialogs/CurseForgeProjectFileSelectorDialog.java`, `gui/dialogs/ModrinthVersionSelectorDialog.java`
- New tests: `src/test/java/com/atlauncher/data/SinytraConnectorTest.java`, `src/test/java/com/atlauncher/gui/dialogs/ModrinthDependencyCheckTest.java`, `src/test/java/com/atlauncher/gui/dialogs/SelectorLoaderFilterTest.java`, `src/test/java/com/atlauncher/gui/dialogs/ConnectorNoteLayoutTest.java`

Verification:
- Automated (at commit): **148 tests passing, `shadowJar` built.** The native-versus-Fabric
  selection cases, enabled/disabled Connector detection, dependency IDs, null loaders
  and the note's layout are covered by the tests above.
- Manual: the yellow Connector note was visually confirmed in the compact Fabric
  version selector (NeoForge instance, Connector enabled). The layout that used
  to hide it is fixed in this commit.
- Manual, **not** new-fix evidence: Fabric results were absent without Connector.
  That is existing base behavior (see section 1).
- **Not manually tested:** the full native-versus-Fabric matrix, such as a project with
  both native and Fabric files for the same MC version, or Connector disabled vs absent.
  These rely on the automated coverage.
- `.idea/gradle.xml` is an unrelated local modification. **Never stage it with this work.**

### Edit Mods update icons and sorting

**Branch:** `feature/edit-mods-update-sort` (based on `feature/defaultconfigs-manage`).

- Opening Edit Mods checks every CurseForge/Modrinth mod for updates in the background and shows a
  green arrow beside the check box of each mod with one. `data/BulkUpdateCheck.java` settles most mods
  in a few requests (CurseForge `POST /mods` `latestFilesIndexes`; Modrinth `POST /version_files/update`
  per loader group, using the stored SHA-1). It only settles what it can be sure of; the rest get
  `DisableableMod.fetchUpdateStatus`, the same platform, restriction and Sinytra loader-path rules as
  Check For Updates, 4 at a time. Results last for the open dialog only; Refresh keeps them.
- Sort By: Name, Date Added (newest first, from the file's creation time, so updating a mod counts as
  adding it again), Update Available. Re-sorting keeps the rows,
  so selections survive. The choice is remembered until the launcher closes.
- Layout: opens at 1000x700 (capped to the screen), each list has its header above it, and one
  draggable divider splits Enabled and Disabled (both grow equally with the window). Refresh rescans
  the mod folders (`scanMissingMods`) and only checks mods that are new.
- Speed: `Utils.moveFile` / `FileUtils.moveFile` rename first (instant on the same drive, keeps the
  creation time) and only fall back to copy + delete. Row right-click menus are built on demand
  instead of for every row on every reload.
- Startup not touched yet: run `run-test-build.bat --debug --debug-level=5` and read the
  `PerformanceManager` "took N ms" lines in `logs/` to find what is actually slow first.
- `gradlew runShared` runs the fork on the installed ATLauncher's data folder
  (`%APPDATA%\ATLauncher`, or `-PatlauncherDir=...`) with launcher self-update off. Close the real
  ATLauncher first. Fork-only settings and instance fields are dropped whenever the real launcher saves.

Files: `gui/dialogs/EditModsDialog.java`, `data/DisableableMod.java`, `gui/components/ModsJCheckBox.java`,
`src/main/resources/assets/icon/update.svg`, `build.gradle`. Test: `EditModsDialogSortTest`.

Verification: the Test Build workflow compiled it and passed all unit tests (Java 17), including the
new sort test. **Not tested by hand yet:** open Edit Mods on a big instance and check the icons,
the progress label and each sort order.

### Test builds without IntelliJ

`.github/workflows/test-build.yml` tests and builds every push to `feature/**` or `fix/**`, then
replaces the `test-build` pre-release with `ATLauncher-Enhanced.jar`. `scripts/run-test-build.bat`
downloads that jar and runs it on `%APPDATA%\ATLauncher` with ATLauncher's bundled Java.

## 3. Implemented locally but not yet committed

Nothing at present.

Build note: Gradle 8.12.1 can't run on Java 25. Use JDK 17, for example
`JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot" ./gradlew test`.

## 4. Planned

Phases A–F are built (section 2, "On `consolidated`"). Their user-facing defaults still await the
user's answers. Known follow-ups:

- Hand-test every feature in section 2's `consolidated` table; none has been clicked through yet.
- Paper/Purpur servers: Sync and Import report their loader as unknown.
- Disable Selected in Edit Mods doesn't warn about mods that need what's being disabled (removal does).
- "Often used together in modpacks" for Related was skipped: the only data would be packs the user opened.
- Remembering SFTP passwords needs a safe store (Windows DPAPI via jna-platform is one option).
- Sync/Import pair jars by name, size and hash, not by the mod IDs inside jars (`mods.toml`, `fabric.mod.json`).

## 5. Needs source verification

Check these against upstream code before designing anything. A search that
finds nothing is not proof that a feature is missing.

- **Update checks + Sinytra:** how `EditModsDialog` / `DisableableMod` choose update candidates on Forge-like instances with Connector.
- **Client/server side data:** whether base already reads side info from Modrinth/CurseForge (for server installs or exports) that manual metadata should extend.
- **Minimum memory:** whether any `-Xms` or initial-memory handling exists in `data/Settings.java`, `data/Instance.java` or launch code (`data/InstanceLauncher.java`, `mclauncher/`).
- **Favorites / collections:** whether any existing pack or mod favoriting exists (for example in the packs browser) that should be reused.
- **Incompatible pack hiding:** what filtering `gui/tabs/PacksBrowserTab.java` and the platform APIs already do.
- **Game options (FOV/sensitivity):** whether any existing code writes `options.txt` defaults.
- **Server export/import formats:** what `InstanceExportDialog` and `ImportInstanceDialog` support today, and whether a server-exporter format fits there.
