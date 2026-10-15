# DataSail · 数舟

**An AI-assisted, multi-database management tool for Android. Query data, edit records and ask questions on your phone.**

[简体中文](README.md) · **English** · [Download](https://github.com/chenwei666/datasail-android/releases/tag/V0.14.0) · [All versions](docs/VERSION_HISTORY.md) · [Workbench guide (Chinese)](docs/WORKSPACE.md)

DataSail is a native Android database client built with Kotlin, Jetpack Compose and ViewModel. It connects directly to databases without requiring Root, Termux, a web wrapper or a mandatory relay service. Database features remain available with AI disabled. The application interface is currently primarily Chinese; this English overview does not imply a fully localized English UI.

> Current version: **V0.14.0 / versionCode 22**. The APK is an **independently production-signed, non-debuggable, minified Release**. Actual test scope and limitations are documented separately; physical devices, arm64 and other server versions still require independent validation.

## Features

- **Connect and discover:** connect to a server, discover databases or indices visible to your account, then search and select a target. Recent connections, table favorites and optional encrypted password storage are included.
- **Spreadsheet-style browsing:** column filters and sorting, server-side pagination, fullscreen mode, pinch zoom, column width/visibility and full row details. Double-tap an editable field to open a draft; preview and confirm before submission.
- **SQL editing:** a dedicated editor with keywords, loaded field names, hardware Tab completion, saved queries and query history. Optional AI suggestions require manual acceptance.
- **Engine-specific workspaces:** browse and perform supported edits on SQL rows, Redis/Valkey keys, MongoDB documents and Elasticsearch/OpenSearch documents.
- **Conversational AI:** separate “Q&A / Write SQL” and “Operate current table” modes, both supporting follow-up questions. Configure services in Settings and retrieve upstream models after entering a key.
- **Local context memory:** AES-GCM encryption and isolation by service, model, connection, database, table and mode. Store up to 16 complete exchanges locally; send up to the most recent 8 per request. Clear individual contexts. No cross-device cloud synchronization.
- **Result export:** export a fully loaded current page as CSV through Android's file picker. A page export is not a full database backup.

## Database support

| Engine | Browsing and queries | Current editing scope |
|---|---|---|
| SQL Server | Databases, schemas, tables/views, pagination, SELECT | Single-row CRUD with a stable primary key and complete comparable data; AI deletion of the current table requires separate full-name confirmation |
| MySQL / MariaDB | Databases, tables/views, pagination, SELECT | InnoDB, stable primary key, inspectable trigger state and complete original-row checks |
| PostgreSQL | Schemas, tables/views, pagination, SELECT | Single-row changes with stable primary keys and complete comparable original rows |
| SQLite | A private copy of a user-selected consistent database file | Edits affect the app's private copy; no automatic source-file writeback or modified-copy export yet |
| Redis / Valkey | SCAN, six key types, TTL, bounded previews | Complete UTF-8 strings, TTL preservation and WATCH conflict detection |
| MongoDB | Collections, bounded JSON filters, cursor pagination | Bounded complete documents, immutable `_id`, original-document and BSON-type checks |
| Elasticsearch / OpenSearch | Indices, mappings, bounded DSL, pagination | Single-document CRUD with `_seq_no` / `_primary_term` conflict detection |

**Ten engines are implemented.** Views, missing primary keys, complex types, triggers and incomplete or oversized data still have restrictions. Oracle, Db2, ClickHouse and other engines remain planned. DataSail does not claim support for every database/version or full DBeaver feature parity. See the [capability matrix (Chinese)](docs/DRIVER_MATRIX.md) and [known limitations (Chinese)](docs/KNOWN_ISSUES.md).

## AI services

Supports OpenAI-compatible Chat Completions, Anthropic Messages and Gemini generateContent text APIs, with custom HTTPS endpoints. Presets include OpenAI, Claude, Gemini, DeepSeek, Qwen, Doubao, Zhipu, Kimi, MiniMax, SiliconFlow, Baidu Qianfan, Tencent Hunyuan, Grok, OpenRouter, Groq, Mistral, Together, NVIDIA NIM and Perplexity.

Presets are not individual provider certifications. Available models, regions and URLs depend on your account. Cloud IAM, Azure-specific authentication, Responses-only APIs, image/audio and streaming output are not currently supported. Provider charges are separate; the application includes no shared API key.

Open **Settings → AI services and models**, select a service, enter its endpoint and key, fetch models, select a model and enable AI. Result rows are not automatically uploaded. Preview and redact selected schema/SQL context before sending. Model-proposed operations are validated locally; writes and table deletion require confirmation. Previous conversations never become replayable database commands. See the [AI and memory guide (Chinese)](docs/AI_ASSISTANT.md).

## Install and get started

1. Download `DataSail-V0.14.0-release.apk` from the [V0.14.0 release](https://github.com/chenwei666/datasail-android/releases/tag/V0.14.0). Check its SHA-256 against the attached checksum file if needed.
2. Install the APK. The configured minimum is Android 8/API26; this release is validated separately on API26 and API30 x86_64 emulators, with exact coverage in the test report. Physical devices and arm64 remain unverified.
3. Add an authorized connection, enter the server, account and TLS settings, then discover and select a database. Your phone needs an existing trusted network route to the server.
4. Open a table to filter, query or edit. Review the target and proposed changes before confirming a write.

**Signing transition: V0.13.0 and earlier used a Debug certificate and cannot be overwritten by this production-signed release.** Preserve connection details, favorites, SQL and settings before deciding to uninstall. Uninstalling removes local data and encryption keys; no complete migration tool is available. Future official releases must reuse this production certificate. See [signing and migration](docs/RELEASE_BUILD.md) and [all versions](docs/VERSION_HISTORY.md).

For IP addresses or self-signed certificates, retrieve the certificate in connection settings and independently verify its SHA-256 with your administrator before trusting that specific connection. Certificate errors never silently disable verification or fall back to plaintext. [Certificate guide (Chinese)](docs/CERTIFICATE_TRUST.md)

## Data and privacy

Credentials and AI history use Android Keystore/AES-GCM and local storage excluded from system backup. AI is optional and can be prohibited per connection. Default TLS identity verification, bound parameters, original-value/version checks, single-use confirmations and no automatic replay of uncertain writes help prevent unintended overwrites. They do not replace server permissions or business backups. Uninstalling the application or losing system keys may make stored credentials/history unrecoverable.

## Verification and release status

This release adds rebuildable Android source profiles for SQL Server, PostgreSQL, MariaDB/MySQL and MongoDB. Normal R8 builds pass without suppressing missing-class warnings. Final DEX auditing checks the original 193 missing types, and device evidence is bound to the final APK SHA-256.

JVM, Android, positive/negative TLS cases, database operations, UI and AI results are recorded separately. Building or passing JVM tests does not establish Android compatibility. Tests use isolated synthetic data, not user business databases or paid AI accounts. See the [release test report](docs/TEST_REPORT.md), [known limitations](docs/KNOWN_ISSUES.md), [driver adaptation notes](docs/DRIVER_RELEASE_PLAN.md) and [historical publication verification](docs/PUBLICATION_REPORT.md).

## Development and licensing

```bash
bash scripts/setup-cloud.sh
source scripts/env.sh
./gradlew :sqlserver-android:check :postgresql-android:check :mariadb-android:check :mongodb-core-android:check :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

In this cloud workspace, first set `export DATASAIL_TOOLS_DIR=/workspace/toolchains`. The [testing guide (Chinese)](docs/TESTING.md) documents pinned tools, isolated database fixtures and Android checks.

[Version history](docs/VERSION_HISTORY.md) · [Architecture](docs/ARCHITECTURE.md) · [Roadmap](docs/ROADMAP.md) · [Report an issue](https://github.com/chenwei666/datasail-android/issues)

First-party code is licensed under [Apache-2.0](LICENSE). See [third-party notices](docs/THIRD_PARTY.md) for driver/source obligations. The application ID remains `com.example.datasail`. This release introduces an independent production certificate, which future official releases must reuse. Historical Debug installation migration is described above.
