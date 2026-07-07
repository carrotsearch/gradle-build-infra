---
name: push-release
description: >-
  Push a release of this Gradle plugin — drop -SNAPSHOT from projectVersion in
  gradle/libs.versions.toml, run ./gradlew check, commit "Version bump.", run
  ./gradlew publishPlugins, then open the next dev cycle (bump projectVersion to the
  next -SNAPSHOT and set projectVersionSelfApply to the just-released version) as a
  second commit. Use when the user wants to cut/publish a release.
---

# Push a release

Drive a release of the `com.carrotsearch.gradle.buildinfra` plugin. All version state
lives in `gradle/libs.versions.toml`:

- `projectVersion` — the plugin's own version (`build.gradle` reads it as `project.version`).
- `projectVersionSelfApply` — the *previously released* buildinfra version the build applies
  to itself (avoids a chicken-and-egg self-apply). After a release it must point at the
  version just published.

Execute the steps below in order. **Stop immediately and report if any step fails** —
do not continue a partial release.

## Preconditions (check first; abort if unmet)

1. Working tree is clean: `git status --porcelain` must be empty. If not, stop and tell the
   user to commit or stash first — release commits must contain only the version edit.
2. `projectVersion` must end with `-SNAPSHOT`. If it does not, the repo is not in a
   releasable state — stop.
3. Prerequisite for the human: `publishPlugins` needs Gradle Plugin Portal credentials
   (`gradle.publish.key` / `gradle.publish.secret`, typically in `~/.gradle/gradle.properties`
   or the environment). If step 5 fails, missing credentials are the most likely cause.

## Steps

1. **Read** `gradle/libs.versions.toml`. Compute `releaseVersion` = `projectVersion` with the
   `-SNAPSHOT` suffix removed (e.g. `0.0.22-SNAPSHOT` → `0.0.22`).

2. **Edit** `projectVersion` to `releaseVersion` (drop `-SNAPSHOT`). Leave
   `projectVersionSelfApply` unchanged for now.

3. **Verify the build:** `./gradlew check`. If it fails, stop and report; do not commit.

4. **Commit the release version**, without Claude attribution (do NOT add a
   `Co-Authored-By: Claude ...` footer). Commit only the version file by explicit path so no
   stray changes (e.g. the untracked `.claude/` directory) are swept in:

   ```
   git commit gradle/libs.versions.toml -m "Version bump."
   ```

5. **Publish:** `./gradlew publishPlugins`. If it fails, stop and report (see the credentials
   note in the preconditions).

6. **Ask the user for the next development version** using the AskUserQuestion tool. Offer the
   default first: a **bugfix bump** of `releaseVersion` with `-SNAPSHOT` appended — for `x.y.z`
   that is `x.y.(z+1)-SNAPSHOT` (e.g. `0.0.22` → `0.0.23-SNAPSHOT`). Call the chosen value
   `nextDevVersion`.

7. **Open the next dev cycle** — edit `gradle/libs.versions.toml`:
   - `projectVersion` → `nextDevVersion`
   - `projectVersionSelfApply` → `releaseVersion` (the version just published)

8. **Commit the dev-cycle bump**, again without Claude attribution and by explicit path:

   ```
   git commit gradle/libs.versions.toml -m "Bump version to <nextDevVersion>."
   ```

9. **Do not push.** Print a summary: the released version, the next dev version, the two local
   commits created, and a reminder that the user must run `git push origin main` when ready.
