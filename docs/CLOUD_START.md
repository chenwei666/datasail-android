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

Live emulators/processes do not survive environment publication reliably. Inspect `adb devices`; if absent, start the existing AVDs in the background with output redirected outside tracked source:

```bash
emulator -avd datasail-api30 -port 5554 -no-window -no-audio -no-boot-anim -no-snapshot -accel off -gpu swiftshader_indirect -cores 2 -memory 2048 > /tmp/datasail-api30.log 2>&1 &
emulator -avd datasail-api26 -port 5556 -no-window -no-audio -no-boot-anim -no-snapshot -accel off -gpu swiftshader_indirect -cores 2 -memory 1536 > /tmp/datasail-api26.log 2>&1 &
```

Wait for each device's `sys.boot_completed=1`, then check API/fingerprint. No KVM is available; TCG can take minutes. Use short readiness polls, report progress, and do not compile heavily during deadline-sensitive ART tests. Run instrumentation/upgrade/Gradle device operations serially. Enable real fixture Android tests with `DATASAIL_ANDROID_TESTS=1 ANDROID_SERIAL=emulator-5556` (or 5554). MongoDB `DATASAIL_SKIP_JVM=1` skips only the already-verified JVM repeat; it is never evidence of JVM success.

`bash scripts/test-android-upgrade.sh --reset-synthetic-emulator` intentionally resets this app on a disposable emulator, then checks in-place upgrade from the previous immutable APK. Never use it on a user/physical device. Generic mobile suites and real database suites are separate; record exact test counts and installed APK hashes.

`:app:assembleRelease` is a deliberately unresolved R8 gate. Preserve the actual failure report, do not add blanket dontwarn or claim a release APK. Debug emulator tests do not establish physical/arm64 or complete mainstream database support.

For a version delivery follow AGENTS.md: update actual validation/evidence/version, preserve prior archives, create a clean local commit, then run `python3 scripts/archive-version.py`. No push or public release is implied. The archive binds evidence to the installed APK SHA-256 and committed source. Local-only commits and files have not been independently validated after a fresh-task restore. Saving this startup draft does not execute, publish or verify environment restoration.

V0.6.0 adds search fixtures. They use official, digest-pinned Elasticsearch 8.19.5 / OpenSearch 2.19.4 images, not vendor tarballs (those URLs returned HTTP 403 here). Search images have many vfs layers: do not pull both while keeping all large SQL images. Check free space before and after pulling and leave enough for the writable container copy. Remove only identified unused reproducible image caches after their tests; keep all unknown volumes and source/archives. This iteration evicted unused SQL Server/MySQL/MariaDB/PostgreSQL image caches; original scripts can restore their pinned digests. Python 3.11's crypt/BLOWFISH is required for the synthetic OpenSearch password hash. No host sysctl change: fixtures disable mmap in the server configuration.

Search readiness is an authenticated TLS request followed by synthetic index setup and proof the reader cannot PUT a document. `DATASAIL_ANDROID_TESTS=1 DATASAIL_ANDROID_SERIALS='emulator-5556 emulator-5554' bash scripts/test-search-local.sh ELASTICSEARCH` (and separately OPENSEARCH) runs JVM contracts then the two ART/UI suites serially. `DATASAIL_SKIP_JVM=1` skips only a prior completed JVM check. Each engine needs its own server-version.json, JVM XML and device OK(5) logs. The adversarial HTTP peer is explicitly separate from real engine acceptance. These are not physical-device, all-version, cloud-auth or Release tests.


V0.7.0 adds server database discovery using the same pinned drivers and fixture services; no new installation dependency. JDBC Android scripts now run JdbcWorkspaceDeviceTest plus DiscoveryUiTest (4 checks) including blank-target scan/select/connect and invalidation after endpoint edits. Redis/Valkey fixtures grant the reader only INFO keyspace in addition to existing reads, seed DB 5 separately, and include a noinfo account to check permission failures. MongoDB scans authorized databases only. Historical V0.7.0 evidence is in docs/evidence/v070; do not overwrite it. The V0.7.0 upgrade baseline was immutable V0.6.0/code8, retaining SQL Server/MongoDB/OpenSearch connection metadata and SQL favorites, followed by install -r of code9; use the current baseline below. Read the current TEST_REPORT for the exact API/engine combinations actually executed; do not infer every combination from existing script capability.

V0.7.1/code10 adds explicit per-connection leaf-certificate pinning across the nine network engines. No new toolchain or runtime dependency. V0.7.1 evidence is historical; do not overwrite it. SQL Server scripts now write docs/evidence/v072; other fixture scripts still target v071 and must use a new evidence directory before any future rerun. Use `DATASAIL_SQLSERVER_AUTOCERT=1 bash scripts/test-sqlserver-local.sh` for a SQL Server with no configured certificate (same pinned image; no TLS config/certificate mount). Add `DATASAIL_ANDROID_TESTS=1 ANDROID_SERIAL=emulator-5556` to run the two CertificateTrustUiTest checks. Default script mode retains strict-CA regression. All network fixtures now exercise inspection (always abort before login), pinned IP connection, wrong-fingerprint rejection and cancelled inspection. These additional negative handshakes take time, especially MongoDB server selection. Run fixtures and Android operations serially. Current upgrade script baseline is immutable V0.7.1/code10 followed by install -r of V0.7.2/code11, including saved certificate trust. V0.7.0 evidence and archives remain immutable. Refer to current TEST_REPORT for actual completed checks, not just script capability.

V0.7.2 updates only the connection interface and app theme; no new setup dependencies. CertificateTrustUiTest now scans first, asserts the visible TLS recovery card, then confirms/persists/scans/connects. ConnectionUiTest covers category/search/SQLite form and saves a synthetic engine-picker screenshot in app-private files. It clears FLAG_SECURE only inside that synthetic test; production keeps it enabled. Run the generic ConnectionUiTest, MobileWorkflowTest, QueryStoreTest and SqliteDeviceTest suites with explicit instrumentation class arguments, separately from real SQL Server tests.
