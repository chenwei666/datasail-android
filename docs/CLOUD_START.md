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
bash scripts/test-search-local.sh ELASTICSEARCH
bash scripts/test-search-local.sh OPENSEARCH
```

Live emulators/processes do not survive environment publication reliably. Inspect `adb devices`; if absent, start one existing AVD with output redirected outside tracked source. Use API26 first; do not run both TCG emulators concurrently:

```bash
"$ANDROID_HOME/emulator/emulator" -avd datasail-api26 -port 5556 -no-window -no-audio -no-boot-anim -no-snapshot -accel off -gpu swiftshader_indirect -cores 2 -memory 1536 > /tmp/datasail-api26.log 2>&1 &
```

After API26 tests finish, stop that emulator with `adb -s emulator-5556 emu kill` and wait for its process to exit. Then start the same command with AVD `datasail-api30`, port `5554`, memory `2048` and a separate `/tmp/datasail-api30.log`. Never stop an emulator owned by another task.

Wait for each device's `sys.boot_completed=1`, then check API/fingerprint. No KVM is available; TCG can take minutes. Use short readiness polls, report progress, and do not compile heavily during deadline-sensitive ART tests. Run instrumentation/upgrade/Gradle device operations serially. Enable real fixture Android tests with `DATASAIL_ANDROID_TESTS=1 ANDROID_SERIAL=emulator-5556` (or 5554). MongoDB `DATASAIL_SKIP_JVM=1` skips only the already-verified JVM repeat; it is never evidence of JVM success.

`bash scripts/test-android-upgrade.sh --reset-synthetic-emulator` intentionally resets this app on a disposable emulator, then checks in-place upgrade from the previous immutable APK. Never use it on a user/physical device. Generic mobile suites and real database suites are separate; record exact test counts and installed APK hashes.

`:app:assembleRelease` is a deliberately unresolved R8 gate. Preserve the actual failure report, do not add blanket dontwarn or claim a release APK. Debug emulator tests do not establish physical/arm64 or complete mainstream database support.

For a version delivery follow AGENTS.md: update actual validation/evidence/version, preserve prior archives, create a clean local commit, then run `python3 scripts/archive-version.py`. No push or public release is implied. The archive binds evidence to the installed APK SHA-256 and committed source. Local-only commits and files have not been independently validated after a fresh-task restore. Saving this startup draft does not execute, publish or verify environment restoration.

V0.6.0 adds search fixtures. They use official, digest-pinned Elasticsearch 8.19.5 / OpenSearch 2.19.4 images, not vendor tarballs (those URLs returned HTTP 403 here). Search images have many vfs layers: do not pull both while keeping all large SQL images. Check free space before and after pulling and leave enough for the writable container copy. Remove only identified unused reproducible image caches after their tests; keep all unknown volumes and source/archives. This iteration evicted unused SQL Server/MySQL/MariaDB/PostgreSQL image caches; original scripts can restore their pinned digests. Python 3.11's crypt/BLOWFISH is required for the synthetic OpenSearch password hash. No host sysctl change: fixtures disable mmap in the server configuration.

Search readiness is an authenticated TLS request followed by synthetic index setup and proof the reader cannot PUT a document. `DATASAIL_ANDROID_TESTS=1 DATASAIL_ANDROID_SERIALS='emulator-5556 emulator-5554' bash scripts/test-search-local.sh ELASTICSEARCH` (and separately OPENSEARCH) runs JVM contracts then the two ART/UI suites serially. `DATASAIL_SKIP_JVM=1` skips only a prior completed JVM check. Each engine needs its own server-version.json, JVM XML and device OK(5) logs. The adversarial HTTP peer is explicitly separate from real engine acceptance. These are not physical-device, all-version, cloud-auth or Release tests.


V0.7.0 adds server database discovery using the same pinned drivers and fixture services; no new installation dependency. JDBC Android scripts now run JdbcWorkspaceDeviceTest plus DiscoveryUiTest (4 checks) including blank-target scan/select/connect and invalidation after endpoint edits. Redis/Valkey fixtures grant the reader only INFO keyspace in addition to existing reads, seed DB 5 separately, and include a noinfo account to check permission failures. MongoDB scans authorized databases only. Historical V0.7.0 evidence is in docs/evidence/v070; do not overwrite it. The V0.7.0 upgrade baseline was immutable V0.6.0/code8, retaining SQL Server/MongoDB/OpenSearch connection metadata and SQL favorites, followed by install -r of code9; use the current baseline below. Read the current TEST_REPORT for the exact API/engine combinations actually executed; do not infer every combination from existing script capability.

V0.7.1/code10 adds explicit per-connection leaf-certificate pinning across the nine network engines. No new toolchain or runtime dependency. V0.7.1 evidence is historical; do not overwrite it. SQL Server scripts now write docs/evidence/v090; other fixture scripts still target v071 and must use a new evidence directory before any future rerun. Use `DATASAIL_SQLSERVER_AUTOCERT=1 bash scripts/test-sqlserver-local.sh` for a SQL Server with no configured certificate (same pinned image; no TLS config/certificate mount). Add `DATASAIL_ANDROID_TESTS=1 ANDROID_SERIAL=emulator-5556` to run the two CertificateTrustUiTest checks. Default script mode retains strict-CA regression. All network fixtures now exercise inspection (always abort before login), pinned IP connection, wrong-fingerprint rejection and cancelled inspection. These additional negative handshakes take time, especially MongoDB server selection. Run fixtures and Android operations serially. Current upgrade script baseline is immutable V0.8.1/code13 followed by install -r of V0.9.0/code14, including saved certificate trust. V0.7.0 evidence and archives remain immutable. Refer to current TEST_REPORT for actual completed checks, not just script capability.

V0.7.2 updates only the connection interface and app theme; no new setup dependencies. CertificateTrustUiTest now scans first, asserts the visible TLS recovery card, then confirms/persists/scans/connects. ConnectionUiTest covers category/search/SQLite form and saves a synthetic engine-picker screenshot in app-private files. It clears FLAG_SECURE only inside that synthetic test; production keeps it enabled. Run the generic ConnectionUiTest, MobileWorkflowTest, QueryStoreTest and SqliteDeviceTest suites with explicit instrumentation class arguments, separately from real SQL Server tests.

V0.8.0 uses platform AndroidKeyStore AES-GCM for opt-in password storage and platform preferences for settings; no new build or runtime installation dependency. Never inspect, export or archive real app-private password ciphertext/keys. Generic Android classes now include PasswordVaultTest, PasswordWorkflowTest and SettingsUiTest (13 checks together with the existing 8 generic mobile checks). Password form semantics are deliberately masked: test the masked state and vault binding, then validate actual authentication with the saved password in the SQL Server UI fixture. Production retains FLAG_SECURE; only synthetic screenshot tests clear it.

Default SQL Server fixture Android mode runs SqlServerDeviceTest plus SpreadsheetSqlServerUiTest (3 checks), including encrypted password reuse, physical-key Tab completion, grid preview/commit/NULL, and known-result read refresh. Automatic-certificate mode still runs CertificateTrustUiTest (2 checks). These scripts generate credentials only for isolated local services and clean their own fixtures. Run final device operations serially after builds; record the installed APK SHA. Profile/unit reports and full nine-engine historical evidence remain distinct from current SQL Server reruns. No claim of physical device, biometric/app-lock, all-engine editing, or Release support follows from these tests.

V0.8.1 adds automatic SQL metadata navigation, table favorites and saved-connection quick login; no new dependency. SQL Server evidence now goes to v081; preserve v080. Generic Android classes add TableFavoriteStoreTest (14 total); MobileWorkflowTest now actually clicks the automatically loaded SQLite table, saves/reopens a table favorite and queries. SQL Server SpreadsheetSqlServerUiTest now clicks catalog/schema/table controls and tests quick login and cross-catalog favorites before editing. Upgrade script explicitly seeds V0.8.0 encrypted synthetic password/settings using UpgradeStateSeedTest before install-r, then SmokeTest/UpgradeTest/UpgradeStateVerifyTest verify 5 cases. Never run UpgradeStateSeedTest on current-version app or real devices. Test class selection remains explicit.

Emulator binary is not in env.sh PATH; use the absolute path above. If a shell tool cleans background jobs, start the emulator as a foreground long-running exec session and poll adb from another command. A restored AVD may retain a stale hardware-qemu.ini.lock; only remove it after verifying its recorded process no longer runs (a defunct process cannot own the AVD). Do not remove locks of live emulators or reset user devices.

V0.8.1 also serializes ProbeRunner cancellation/cleanup close attempts. The deterministic blocked-close regression must fail on the original code and pass on the fix; real SQL Server ART timeout/cancel tests must still require a passed disconnect check. Keep failure evidence and rerun the final APK after core changes.


V0.9.0 adds a text AI assistant with OpenAI-compatible / Anthropic / Gemini protocols, no new dependency. The app is disabled by default and per-database external-AI policy defaults to deny (connection schema 5). AI key ciphertext uses a separate Android Keystore namespace; never provide real API keys to this cloud fixture. `scripts/test-ai-android.sh` starts a loopback Python HTTPS synthetic upstream, creates an ephemeral two-day certificate, injects that CA only into instrumentation-created client instances, runs AiServiceUiTest on the selected disposable emulator, and removes temporary material. It never changes emulator system trust or production trust rules. The two cases exercise all three ART protocols and actual UI model discovery / preview / SQL draft handoff; they are not live vendor acceptance. JVM AiHttpsTest creates its own temporary keytool certificate and covers redirects, certificate rejection, cancellation and response bounds.

V0.9.0 generic Android classes also include AiStoreTest and TablePresentationTest (17 total with the previous 14 cases); the latter browses 130 actual SQLite columns through visible controls and captures a synthetic fullscreen screenshot. SQL Server / MySQL / MariaDB / PostgreSQL JVM fixtures add wide-column regressions; `test-jdbc-workspace-local.sh` now writes v090 evidence. Earlier directories remain immutable. UpgradeStateSeedTest must run against V0.8.1 before install-r of V0.9.0; it seeds old password/settings/table-favorite formats without calling new-only constructors. Run all device operations serially after the final build and preserve actual failures before reruns.


V0.9.0 validation observed an API30 AOSP system_server watchdog during an install immediately after cold boot (PackageManager / StorageManager lock); this occurred before app tests. Retain system crash evidence and wait for the package/activity services to recover, not only a stale sys.boot_completed property. Fixture installation now uses `adb install --no-streaming` (transfer first, then install); `scripts/install-android-test-apks.sh` skips an install only after hashing the installed APK bytes and rejects physical devices. Its `test` mode installs only instrumentation, so the upgrade seed still runs against the old APK. Do not disable watchdogs, change system trust or reset user devices to pass tests. Compose dialog capture is unsupported below API28; run all fullscreen assertions on API26 and capture the dialog on API30.

During final V0.9.0 verification, two concurrently cold-started TCG emulators both showed a SystemUI ANR dialog over the app; sys.boot_completed and passing individual nonvisibility assertions did not reveal the obstruction. Start only one AVD at a time on this host, inspect `adb shell dumpsys window` for an `Application Not Responding` focused window before UI verification, preserve system diagnostics, and restart only the disposable emulator when needed. Stop that AVD before starting the next. Do not dismiss this as an application TLS failure, disable ANR handling, or remove UI visibility assertions. A certificate test retains a bounded wait for automatic visibility because StateFlow completion precedes Compose scroll completion.

A single cold start can still produce the SystemUI dialog after installation begins. Reboot alone did not prevent it in this run. Preserve a screenshot and window diagnostics; selecting the system-provided Wait action on the disposable emulator allowed the UI to recover. Confirm the app regains focus before continuing UI checks. Do not count a hidden visibility assertion as passed or disable system ANR handling.


V0.9.1 moves AI endpoint/key/model configuration into Settings and adds searchable table-column metadata. The upgrade baseline is now immutable V0.9.0/code14; UpgradeStateSeedTest also seeds the previous AI profile and separately scoped synthetic key. Generic selected regression classes are SettingsUiTest, TablePresentationTest, AiNavigationTest and MobileWorkflowTest (4 checks), plus upgrade 5 and AI HTTPS 2 on each API. AI and SQL Server scripts write v091 evidence; preserve all older evidence.

The SQL Server Android profile is now 13.6.0.jre8-datasail.2, built from the same checksum-locked upstream. DDC used desktop-only Timestamp.valueOf(LocalDateTime) and corresponding Date/Time methods; an actual API26 date read reproduced NoSuchMethodError before the fix. The source overlay uses Android-compatible constructors and Formatter input, with 14 profile JVM checks. SqlServerTemporalContract verifies six temporal types, NULL and nanosecond text precision; SpreadsheetSqlServerUiTest opens an actual date table through catalog/table controls and then continues the original edit workflow. Run scripts/test-sqlserver-local.sh with DATASAIL_ANDROID_TESTS=1 for each selected API after the final build. No business server or real model credential is needed. Current pass/fail scope belongs in TEST_REPORT, not inferred from these instructions.
