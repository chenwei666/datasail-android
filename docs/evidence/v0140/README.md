# V0.14.0 evidence

The delivered application APK is `2decdb05b4d0b6382095ed96b6f2c220d3e56b5a813626a4444c17fbb0a6b5f1`.
Only the per-suite reports referenced by `summary.json` count toward the release result.
Every device receipt records the APK bytes actually installed, the test APK, Android version and ABI.

`test-runners.json` records test-only synchronization/visible-toolbar fixes after some network suites had completed. Prior test runners are preserved in the release archive with their hashes, report lists and exact source patches; all application bytes remain identical. The full UI suites on both systems use the final test runner.

`attempts/` contains failed instrumentation, earlier application candidates, fixture/ABI diagnostics and software-emulator System UI ANRs. These are historical diagnostics, not passing release evidence. Earlier candidates have different APK hashes and are excluded from final coverage.

JVM reports represent isolated real synthetic servers. An engine's host checks are counted once even when Android tests run on both systems; their actual source directories are listed in `jvmEngineReportDirectories`. AI tests use synthetic HTTPS services, not commercial vendor accounts. No physical/arm64 devices or user business databases were accessed.
