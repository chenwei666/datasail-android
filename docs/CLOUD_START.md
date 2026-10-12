# Cloud startup instructions

Use the existing `/workspace/datasail-android` checkout. Do not create another worktree unless the user requests it. Read AGENTS.md and current roadmap, driver matrix and test report; inspect Git status and preserve changes/local commits. Setup alone does not authorize application changes; ongoing user development requests do.

Activate tools in every fresh shell:

```bash
cd /workspace/datasail-android
export DATASAIL_TOOLS_DIR=/workspace/toolchains
source scripts/env.sh
java -version
./gradlew --version
adb devices
```

Pinned JDK21/Gradle8.13/SDK36 and emulator API26/API30 images are installed under `/workspace/toolchains`. If installation must be restored, use `bash scripts/setup-cloud.sh` with this tools directory; keep dependency verification enabled. Do not replace HOME/CODEX_HOME, disable TLS verification, or dump credentials/environment values.

Build and verify:

```bash
./gradlew :sqlserver-android:check :sasl-android:check :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

The environment supplies Docker. Check `docker info`; a successful process start or open port is not service readiness. Fixture scripts start isolated TLS servers on loopback, generate ephemeral synthetic credentials and remove their own containers/volumes/files on exit. Do not start a long-lived database, reuse production credentials, expose database ports publicly, or prune unrelated Docker data. Check disk space first; Docker uses vfs, and the MongoDB/SQL Server fixtures need substantial headroom. On this 32 GiB vfs environment keep about 5 GiB free before starting SQL Server. Run large fixtures sequentially; if caches fill the disk, remove only identified, unused, reproducible image caches after their tests complete (the scripts can pull their pinned digests again). Never prune user/database volumes indiscriminately.

```bash
bash scripts/test-mysql-local.sh
bash scripts/test-sqlserver-local.sh
bash scripts/test-jdbc-workspace-local.sh MYSQL
bash scripts/test-jdbc-workspace-local.sh MARIADB
bash scripts/test-jdbc-workspace-local.sh POSTGRESQL
bash scripts/test-key-value-local.sh REDIS
bash scripts/test-key-value-local.sh VALKEY
bash scripts/test-mongo-local.sh
```

Live emulators/processes do not survive environment publication reliably. Inspect `adb devices`; if absent, start the existing AVDs in the background with output redirected outside tracked source:

```bash
emulator -avd datasail-api30 -port 5554 -no-window -no-audio -no-boot-anim -no-snapshot -accel off -gpu swiftshader_indirect -cores 2 -memory 2048 > /tmp/datasail-api30.log 2>&1 &
emulator -avd datasail-api26 -port 5556 -no-window -no-audio -no-boot-anim -no-snapshot -accel off -gpu swiftshader_indirect -cores 2 -memory 1536 > /tmp/datasail-api26.log 2>&1 &
```

Wait for each device's `sys.boot_completed=1`, then check API/fingerprint. No KVM is available; TCG can take minutes. Use short readiness polls, report progress, and do not compile heavily during deadline-sensitive ART tests. Run instrumentation/upgrade/Gradle device operations serially. Enable real fixture Android tests with `DATASAIL_ANDROID_TESTS=1 ANDROID_SERIAL=emulator-5556` (or 5554). MongoDB `DATASAIL_SKIP_JVM=1` skips only the already-verified JVM repeat; it is never evidence of JVM success.

`bash scripts/test-android-upgrade.sh --reset-synthetic-emulator` intentionally resets this app on a disposable emulator, then checks in-place upgrade from the previous immutable APK. Never use it on a user/physical device. Generic mobile suites and real database suites are separate; record exact test counts and installed APK hashes.

`:app:assembleRelease` is a deliberately unresolved R8 gate. Preserve the actual failure report, do not add blanket dontwarn or claim a release APK. Debug emulator tests do not establish physical/arm64 or complete mainstream database support.

For a version delivery follow AGENTS.md: update actual validation/evidence/version, preserve prior archives, create a clean local commit, then run `python3 scripts/archive-version.py`. No push or public release is implied. The archive binds evidence to the installed APK SHA-256 and committed source. Local-only commits and files have not been independently validated after a fresh-task restore. Saving this startup draft does not execute, publish or verify environment restoration.
