# v2rayNG Speed Test Patch Maintenance Guide

This repository is a working fork used to maintain a local Speed Test patch for
v2rayNG. The upstream maintainer declined the pull request, so the patch must be
re-applied when a new upstream v2rayNG version is released.

This document is written as a handoff note for a future human or AI agent. It
contains the project context, the known build-library issue, the patch workflow,
and the debugging/install steps needed to produce and test an APK on a phone.

## Current Branches And Commits

The original successful reusable patch was created on:

```bash
speed-test-2.0.18
```

Important commits:

```text
42e7f95e  tag: 2.0.18, upstream release base
5adf2282  fix: add build configuration and documentation for APK assembly
79d3f8ab  feat: add reusable speed test patch
```

The patch was later ported to upstream tag `2.1.5` on:

```bash
speed-test-2.1.5
```

Important commits on the 2.1.5 maintenance branch:

```text
4c86e7c2  tag: 2.1.5, upstream release base
e8f26bc9  fix: add build configuration and documentation for APK assembly
```

The 2.1.5 port adapts the patch from the old `V2RayTestService.kt` flow to the
new upstream test architecture:

```text
CoreTestService.kt
RealPingWorkerService.kt
SpeedtestManager.kt
TestServiceMessage.kt
```

The clean upstream PR branch was:

```bash
feature/speed-test
```

PR:

```text
https://github.com/2dust/v2rayNG/pull/5526
```

That PR was intentionally clean and did not include the local build workaround
or binary AAR files. The maintenance branches such as `speed-test-2.0.18` and
`speed-test-2.1.5` are the practical branches to use for future local builds.

## What The Patch Adds

The patch adds a new menu item:

```text
Speed test
```

Behavior:

1. Start with the existing real delay test.
2. Keep only profiles with successful real delay results.
3. Start a temporary core instance per profile for download testing.
4. Download a test file through the temporary local proxy.
5. Display download speed next to the real delay value in the server list.
6. Show speed values in blue.

User settings added:

```text
Speed test URL
Speed test timeout, seconds
Speed test concurrency (1-8)
```

Default URLs:

```text
https://speed.cloudflare.com/__down?bytes=10000000
https://cachefly.cachefly.net/50mb.test
```

Implementation files touched by the patch:

```text
V2rayNG/app/src/main/java/com/v2ray/ang/AppConfig.kt
V2rayNG/app/src/main/java/com/v2ray/ang/dto/ServerAffiliationInfo.kt
V2rayNG/app/src/main/java/com/v2ray/ang/handler/MmkvManager.kt
V2rayNG/app/src/main/java/com/v2ray/ang/handler/SettingsManager.kt
V2rayNG/app/src/main/java/com/v2ray/ang/service/CoreTestService.kt
V2rayNG/app/src/main/java/com/v2ray/ang/service/SpeedTestWorkerService.kt
V2rayNG/app/src/main/java/com/v2ray/ang/ui/MainActivity.kt
V2rayNG/app/src/main/java/com/v2ray/ang/ui/MainRecyclerAdapter.kt
V2rayNG/app/src/main/java/com/v2ray/ang/util/MessageUtil.kt
V2rayNG/app/src/main/java/com/v2ray/ang/viewmodel/MainViewModel.kt
V2rayNG/app/src/main/res/layout/item_recycler_main.xml
V2rayNG/app/src/main/res/menu/menu_main.xml
V2rayNG/app/src/main/res/values-ru/strings.xml
V2rayNG/app/src/main/res/values/colors.xml
V2rayNG/app/src/main/res/values/strings.xml
V2rayNG/app/src/main/res/xml/pref_settings.xml
```

The reusable patch and helper script are:

```text
patches/speed-test.patch
apply_speed_test_patch.sh
```

Example result screenshot:

```text
screen_2026-04-21_22-44-38.jpg
```

## Known Build-Library Context

Upstream v2rayNG source may not build directly from a release tag in this local
environment because generated/native Android library artifacts are missing.

Typical compile errors:

```text
Unresolved reference 'go'
Unresolved reference 'libv2ray'
Unresolved reference 'Libv2ray'
Unresolved reference 'CoreController'
Unresolved reference 'CoreCallbackHandler'
```

The local build workaround is documented in:

```text
V2rayNG/BUILD_INSTRUCTIONS.md
```

In short, a working APK build needs AAR files under:

```text
V2rayNG/app/libs/
```

Known required libraries:

```text
libv2ray.aar
libhev-socks5-tunnel.aar
```

Important: do not keep unpacked native libraries under:

```text
V2rayNG/app/src/main/jniLibs/
```

This path is ignored by git, but Gradle still packages files from it. During the
2.1.5 port an old local `app/src/main/jniLibs/arm64-v8a/libgojni.so` overrode
the correct `libgojni.so` from `app/libs/libv2ray.aar`. The APK built
successfully, but VPN startup crashed with:

```text
java.lang.UnsatisfiedLinkError: No implementation found for
void libv2ray.CoreController.registerProcessFinder(libv2ray.ProcessFinder)
```

Fix:

```bash
rm -rf V2rayNG/app/src/main/jniLibs
cd V2rayNG
./gradlew clean assembleRelease
```

After the clean rebuild, the warning about duplicate `lib/arm64-v8a/libgojni.so`
must disappear.

The original build-fix branch/commit supplied the missing local build setup:

```text
origin/fix/build-with-hev-library
a7093199999913a9d12598254640d86fa89ee1e8
```

In this repository that fix is represented on the maintenance branch as:

```text
5adf2282 fix: add build configuration and documentation for APK assembly
```

When updating to a newer upstream release, keep this distinction clear:

- The Speed Test patch is feature code.
- The build-library fix is only for local APK building.
- Do not include large binary build artifacts in a public upstream PR unless the
  upstream maintainer explicitly asks for them.

## Updating To A New Upstream Version

Use this when upstream releases a new version and the local patched APK must be
rebuilt.

Start from the repository root:

```bash
cd /home/hohla/git/v2rayNG
```

Fetch upstream tags and branches:

```bash
git fetch upstream --tags
```

Inspect recent tags:

```bash
git tag --sort=-version:refname | head -20
```

Create a fresh maintenance branch from the new upstream tag:

```bash
git switch -c speed-test-X.Y.Z tags/X.Y.Z
```

Replace `X.Y.Z` with the new upstream version.

Apply the local build fix. Prefer cherry-picking the known commit if it still
applies:

```bash
git cherry-pick 5adf2282
```

If it conflicts, resolve only build-related files. The relevant context is in
`V2rayNG/BUILD_INSTRUCTIONS.md`.

Then apply the Speed Test patch:

```bash
./apply_speed_test_patch.sh
```

The script runs `git apply --3way patches/speed-test.patch`. If conflicts occur,
resolve them in the touched files listed above.

After resolving conflicts:

```bash
git status --short
git diff --check
git add <resolved-files>
git commit -m "feat: add reusable speed test patch"
```

Regenerate the reusable patch for the next version after the code is final:

```bash
git diff HEAD~1..HEAD -- V2rayNG/app --output=patches/speed-test.patch
git add patches/speed-test.patch
git commit -m "chore: refresh speed test patch for X.Y.Z"
```

If the previous commit already included the patch file, amend instead:

```bash
git add patches/speed-test.patch
git commit --amend --no-edit
```

## Building APK

Go into the Android project directory:

```bash
cd /home/hohla/git/v2rayNG/V2rayNG
```

Make sure the required libraries exist:

```bash
ls -lh app/libs/
```

Expected:

```text
libv2ray.aar
libhev-socks5-tunnel.aar
```

Build release APKs:

```bash
./gradlew assembleRelease
```

APK output directory:

```text
V2rayNG/app/build/outputs/apk/playstore/release/
```

For a modern Android phone, the usual APK is:

```text
v2rayNG_X.Y.Z_arm64-v8a.apk
```

There may also be a universal APK:

```text
v2rayNG_X.Y.Z_universal.apk
```

## Installing On A Phone

ADB path used in this environment:

```bash
/home/hohla/Android/Sdk/platform-tools/adb
```

Check connected devices:

```bash
/home/hohla/Android/Sdk/platform-tools/adb devices
```

Install:

```bash
/home/hohla/Android/Sdk/platform-tools/adb install -r V2rayNG/app/build/outputs/apk/playstore/release/v2rayNG_X.Y.Z_arm64-v8a.apk
```

If Android says the app conflicts with another package, this is usually a signing
key mismatch. The patched APK is signed locally, while the original app may have
been signed by upstream, GitHub release, F-Droid, or Play Store.

Fix:

```bash
/home/hohla/Android/Sdk/platform-tools/adb uninstall com.v2ray.ang
/home/hohla/Android/Sdk/platform-tools/adb install V2rayNG/app/build/outputs/apk/playstore/release/v2rayNG_X.Y.Z_arm64-v8a.apk
```

Warning: uninstalling removes app data unless the device or Android version keeps
backups. Export subscriptions/configs before uninstalling if needed.

Google Play Protect may ask to scan the manually installed APK. That is normal
for sideloaded APKs and is not specific to this patch.

## Manual Test Plan

1. Open v2rayNG on the phone.
2. Ensure there are several profiles in the list.
3. Open the three-dot menu.
4. Run the existing real delay action and confirm it still completes quickly.
5. Open the three-dot menu again.
6. Run `Speed test`.
7. Confirm real delay runs first.
8. Confirm only successful real-delay profiles proceed to speed testing.
9. Confirm speed values appear to the right of delay values.
10. Confirm speed values are blue.
11. Open Settings and verify speed test URL, timeout, and concurrency fields.

Expected behavior:

- Real delay phase should run in parallel, not one profile at a time.
- Speed test phase should use configurable concurrency from 1 to 8.
- Invalid or tiny URL responses should be skipped and fallback URLs tried.
- Speeds below `0.05 Mbps` are not displayed.

## Debugging

Clear logs:

```bash
/home/hohla/Android/Sdk/platform-tools/adb logcat -c
```

Run the app and collect logs:

```bash
/home/hohla/Android/Sdk/platform-tools/adb logcat | tee v2rayng.log
```

Useful filters:

```bash
/home/hohla/Android/Sdk/platform-tools/adb logcat | grep -i "speed"
/home/hohla/Android/Sdk/platform-tools/adb logcat | grep -i "v2ray"
```

Stop the app:

```bash
/home/hohla/Android/Sdk/platform-tools/adb shell am force-stop com.v2ray.ang
```

If the Speed Test menu item exists but no speed appears:

1. Check that real delay results are positive.
2. Check that speed test URLs are reachable.
3. Try Cloudflare first:

   ```text
   https://speed.cloudflare.com/__down?bytes=10000000
   ```

4. Avoid URLs that return tiny placeholder responses. Earlier testing showed
   some proxies returned only a few bytes from `cachefly`, so the implementation
   treats very small downloads as invalid and tries the next URL.

If the real delay phase inside Speed Test is very slow:

- Ensure the delay phase is parallelized similarly to `RealPingWorkerService`.
- A previous failed implementation checked profiles sequentially, causing one
  server to take 5-10 seconds and making large lists unusable.

## Public PR Notes

The upstream PR was declined, but the clean branch is still useful as reference:

```text
feature/speed-test
ae623ffb feat: add profile download speed test
https://github.com/2dust/v2rayNG/pull/5526
```

For future upstream attempts, keep the PR focused on feature code only. Do not
include:

```text
libv2ray.aar
libhev-socks5-tunnel.aar
BUILD_INSTRUCTIONS.md
patches/speed-test.patch
apply_speed_test_patch.sh
```

For local maintenance builds, use `speed-test-2.0.18` and this document.
