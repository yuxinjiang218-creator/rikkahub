---
name: rikkahub-upstream-port
description: Standardize upstream tracking for this RikkaHub fork. Use when syncing upstream updates into this repo, creating port-* migration branches, preserving every mod feature while absorbing upstream optimizations, resolving obvious conflicts with explicit user confirmation, verifying web/static/runtime integrity, building release APKs, and publishing GitHub releases.
---

# RikkaHub Upstream Port

Use this skill to execute a repeatable, low-risk upstream merge for this repository.

## Branch Roles

- Keep `main` as upstream clean mirror only.
- Keep `master` as the only public mod mainline.
- Use `port-*` as temporary migration branches.
- Keep archive/experiment branches read-only unless explicitly requested.

## Safety Rules

- Stop and ask if unrelated unexpected changes appear.
- Stop and ask before resolving any obvious product-behavior conflict in a mod-owned file.
- Never force-push `main` or `master`.
- Never resolve conflicts by deleting logic only to pass compilation.
- Keep upstream structure whenever mod behavior does not require divergence.
- Preserve mod behavior completely. Treat small UX details as release-blocking, not optional polish.
- Do not silently drop a mod feature just because upstream touched the same area.
- If both upstream and mod behavior can coexist, keep both.

## Mod Preservation Contract

During every upstream port, explicitly preserve all current `master`-only behavior unless the user approves removal.

Minimum preserved feature surface:

- Sandbox file tools and assistant-level file management UI
- PRoot container runtime, background process support, container Python, and background keep-alive
- Workflow TODO, Workflow Control, workflow side panel/handle, and conversation-bound workflow state
- SubAgent tool integration
- Local Skills directory and built-in skill runtime
- Dual-track compaction: primary dialogue summary plus independent memory ledger
- Auto compression, manual compression, and separate regeneration of latest dialogue summary or memory ledger
- Shared progress dialogs and cancellable regeneration/compression flows
- Compression completion auto-scroll back to the generated compaction card
- Memory index rebuild, Indexed History Recall, and source-readback flow
- Assistant-level knowledge base, document indexing, and the list/search/read tool chain
- Conversation branch sandbox copy behavior
- Multi-key provider editors and persistent key rotation cursor
- Fork-owned release/update channel behavior

Treat the following interaction details as must-keep unless the user explicitly says otherwise:

- Regenerating summary after second confirmation still shows the shared progress dialog
- Finishing compression still scrolls back to the generated summary card
- Index completion / rebuild completion system feedback still appears
- Cancel buttons in progress dialogs still cancel the real running job
- Manual compression options that the user toggles must stay persisted if they were persisted before

If any preserved behavior becomes uncertain during conflict resolution, stop and ask the user instead of guessing.

## Step 1: Sync Upstream Mirror

Run:

```bash
git fetch upstream --prune --tags
git switch main
git merge --ff-only upstream/master
git push origin main
```

Record upstream version from tag or commit:

```bash
git describe --tags --abbrev=0 upstream/master
git rev-parse --short upstream/master
```

## Step 2: Create Port Branch from Master

Run:

```bash
git switch master
git switch -c port-upstream-<yyyymmdd>-<version>
git merge main
```

Use a clear branch name, e.g. `port-upstream-20260305-2.1.0`.

## Step 3: Resolve Conflicts with Fixed Policy

Prefer upstream for:

- General UI refactors and icons
- Navigation/page structure updates
- Non-mod provider updates
- Web API/static framework changes from upstream
- Neutral refactors that do not change mod behavior

Backfill mod features for:

- App coexistence strategy (`applicationId`, signing behavior, target sdk policy)
- Firebase-disabled behavior
- Container/proot runtime and sandbox integration
- Workflow entry/toggle/overlay behavior
- SubAgent and local Skills runtime behavior
- Dual-track compaction, memory ledger, Indexed History Recall, and source readback flow
- Knowledge base pages, services, indexing, and tool integration
- Conversation branch sandbox copy behavior
- Tavily key-rotation and custom search strategy
- Fork-owned update/release channel behavior

Ask the user before deciding a conflict when:

- Upstream and mod both changed the same user-visible flow and one side would have to lose behavior
- A conflict touches compaction, memory retrieval, knowledge base, workflow, container runtime, or update channel logic
- The only apparent resolution is to simplify or remove existing mod behavior

Inspect high-risk files every merge:

- `app/build.gradle.kts`
- `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/ai/CompressContextDialog.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/ai/LedgerGenerationDialog.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt`
- `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
- `app/src/main/java/me/rerere/rikkahub/service/KnowledgeBaseService.kt`
- `app/src/main/java/me/rerere/rikkahub/service/KnowledgeBaseIndexForegroundService.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProviderPage.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingSearchPage.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantKnowledgeBasePage.kt`
- `search/src/main/java/me/rerere/search/SearchService.kt`
- `search/src/main/java/me/rerere/search/TavilySearchService.kt`
- `app/src/main/java/me/rerere/rikkahub/service/WebServerService.kt`
- `app/src/main/java/me/rerere/rikkahub/web/WebServerManager.kt`

Confirm no conflict markers remain:

```bash
rg -n "^(<<<<<<<|=======|>>>>>>>)" -S .
```

## Step 4: Validate Before Merge Back

Before release validation, ensure the release signing inputs are loaded from the local private signing config, not the debug keystore.
Use this local-only flow:

1. Read `%USERPROFILE%\\.codex\\secrets\\rikkahub-release.properties`
2. Copy or sync its `storeFile/storePassword/keyAlias/keyPassword` values into ignored `local.properties`
3. Confirm `app/build.gradle.kts` release build type uses `signingConfigs.getByName("release")`

Never commit signing credentials, keystore files, or `local.properties`.

Compile with constrained memory:

```bash
GRADLE_OPTS="-Xmx2g -XX:MaxMetaspaceSize=768m -Dkotlin.daemon.jvm.options=-Xmx1536m" ./gradlew :app:compileDebugKotlin --no-daemon --console=plain
```

Build release with current mod policy (targetSdk 28):

```bash
GRADLE_OPTS="-Xmx2g -XX:MaxMetaspaceSize=768m -Dkotlin.daemon.jvm.options=-Xmx1536m" ./gradlew :app:assembleRelease -x lintVitalRelease --no-daemon --console=plain
```

Verify web static assets are packaged:

- Ensure `:app:buildWebUiClient` and `:app:syncWebUiStatic` are executed.
- Confirm `web/src/main/resources/static/index.html` exists.

Run a mod-preservation review before merge back:

- Compare the finished `port-*` branch against pre-merge `master`, not just against upstream.
- Read the current `README.md` and use it as a feature checklist for public mod functionality.
- Explicitly review compaction, memory recall, knowledge base, workflow, sandbox/container, provider settings, and update-channel behavior.
- Search for conflict leftovers and accidental deletions with:

```bash
git diff --stat master...HEAD
git diff --name-only master...HEAD
rg -n "TODO|FIXME|TEMP|兼容|回退|fallback" app search web ai
```

- If a feature moved files during upstream refactor, port the behavior to the new structure instead of restoring old structure blindly.
- If validation passes but a user-visible mod behavior is no longer obviously present, stop and inspect before merge.

## Step 5: Merge Port Branch Back to Master

Commit on `port-*`:

```bash
git commit -m "merge: sync upstream <version> into master line"
```

Fast-forward master:

```bash
git switch master
git merge --ff-only port-upstream-<yyyymmdd>-<version>
git push origin master
```

Delete the finished port branch after master is updated:

```bash
git branch -d port-upstream-<yyyymmdd>-<version>
```

## Step 6: Publish Release

Use upstream version tag format directly (no custom prefix/suffix).
Only build and publish the `arm64-v8a` APK. Do not keep or upload `x86_64` or universal APKs.

Never hardcode GitHub tokens in this skill, the repository, or any committed file.
For release publishing, first resolve a GitHub token from one of these local-only sources:

1. `GH_TOKEN` environment variable
2. `GITHUB_TOKEN` environment variable
3. `%USERPROFILE%\\.codex\\secrets\\github_token.txt`

If a token file is used, read it locally at runtime and never copy its contents into tracked files, skill files, commit messages, or release notes.
Prefer GitHub API / upload API with the resolved token when `gh` CLI is unavailable.

Example:

```bash
gh release create 2.1.0 app/build/outputs/apk/release/app-arm64-v8a-release.apk#rikkahub-2.1.0-arm64.apk --repo yuxinjiang218-creator/rikkahub --target master --title "2.1.0" --notes "RikkaHub Mod 2.1.0 release (arm64)."
```

Verify:

- Release is not draft.
- Release is not prerelease.
- Asset exists and is downloadable.

## Non-Blocking Gradle Monitor Pattern

Use background process and tail logs instead of blocking:

```powershell
$p = Start-Process cmd.exe -ArgumentList '/c','gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain' -RedirectStandardOutput tmp-gradle.log -RedirectStandardError tmp-gradle.err -PassThru
```

Poll every few seconds, print log tail, and stop on timeout if needed.

## Done Criteria

- `main` == `upstream/master`
- `master` contains merged upstream + preserved mod features
- `:app:compileDebugKotlin` succeeds
- `:app:assembleRelease -x lintVitalRelease` succeeds
- Web static is present and not regressed
- Public mod features described in `README.md` still exist without known regressions
- High-impact UX details around compaction, recall, knowledge base, workflow, and update flow are explicitly checked
- `origin/master` pushed
- Finished `port-*` branch deleted locally
- Release published with correct tag and asset
