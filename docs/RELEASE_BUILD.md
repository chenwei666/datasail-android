# 正式签名与混淆构建 / Signed, minified Release

从 V0.14.0 起使用独立的 DataSail 正式签名。应用标识保持 `com.example.datasail`。
公开证书指纹见 [signing-certificate.json](releases/signing-certificate.json)。私钥、密码和签名目录不得进入 Git、源码归档、日志或公开 Release。

## 签名保管

已经生成过正式签名时必须复用，不要再次运行生成命令。
在受控的发布机器首次生成身份：

```sh
source scripts/env.sh
python3 scripts/prepare-release-signing.py /absolute/private/datasail-signing
```

目录包含 `datasail-release.p12` 和 `store-password.txt`，仅所有者可读。
将这两个文件通过私有渠道备份到持久、安全的位置。丢失签名后无法更新同一应用身份；公开证书指纹不能恢复私钥。
普通 PR/CI 只构建未签名 Release，不接触正式私钥。

## 构建与验证

```sh
source scripts/env.sh
export DATASAIL_SIGNING_DIR=/absolute/private/datasail-signing
./gradlew :sqlserver-android:check :postgresql-android:check :mariadb-android:check \
  :mongodb-core-android:check :core:test :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleRelease
python3 scripts/verify-release-dex.py app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

Release 始终开启 R8 与资源收缩，关闭 debuggable；没有签名目录时只输出 unsigned APK。
不允许用 `-ignorewarnings`、批量 `-dontwarn` 或关闭混淆规避驱动问题。
新增驱动适配的源码、锁文件和许可证见各 `*-android/README.md`。

`app/release-test-api.pro` 固定独立测试 APK 实际调用的共享类、成员签名和可继承性，仍允许名称混淆；Kotlin 的 `$DefaultImpls` 与协程探针适配器保留必要名称，以便独立测试 APK 正确链接。其余实现继续正常优化。
更改测试源码或依赖后重新生成并审阅差异，不用整库 keep 规则替代：

```sh
./gradlew -Pdatasail.testBuildType=release -I scripts/release-test-classpaths.gradle \
  :app:recordReleaseTestClasspaths
python3 scripts/generate-release-test-api.py build/release-test-classpaths.json app/release-test-api.pro
```

在隔离的 SDK 模拟器上验证实际 Release：

```sh
export DATASAIL_TEST_VARIANT=release DATASAIL_ANDROID_TESTS=1
export DATASAIL_EVIDENCE_DIR=docs/evidence/v0140/release-validation
./gradlew -Pdatasail.testBuildType=release :app:assembleRelease :app:assembleReleaseAndroidTest
bash scripts/install-android-test-apks.sh
bash scripts/test-sqlserver-local.sh
for engine in MYSQL MARIADB POSTGRESQL; do bash scripts/test-jdbc-workspace-local.sh "$engine"; done
```

非调试包不能 `run-as`。测试脚本仅在已有 `su` 的可丢弃 SDK 模拟器中传送合成夹具，不改变设备 Root 状态，不支持连接用户真机执行此夹具操作。传送后恢复应用文件应有的 SELinux 标签，不关闭 enforcing。普通使用不需要 Root。
其他引擎、AI、TLS 反例、冲突和升级必须分别记录；真实结果见测试报告。
当前发布矩阵的全部套件完成后，运行 `python3 scripts/summarize-release-verification.py` 汇总真实结果；缺失、失败或 APK 不一致会拒绝汇总。早期候选包与失败诊断在 `attempts/` 中单独记录；不得用这些结果替代最终安装包的回归。本版测试流程补充了弹窗等待、工具栏可见定位和输入法状态隔离：`test-runners.json` 明确列出已完成网络套件使用的测试包及逐次差异；全部测试包均归档，所有套件始终绑定同一个最终应用 APK。受影响的界面套件在两个系统上使用最终测试包完整重跑。最终归档使用 `python3 scripts/archive-version.py --variant release`，要求干净提交、签名匹配、真实 R8 mapping 和逐套绑定最终 APK 哈希的设备证据。每套网络脚本成功后自动运行 `record-device-evidence.py`；本地界面测试也必须单独记录。

## 历史安装迁移

V0.13.0 及以前的 APK 使用 Android Debug 证书，新正式签名通常会被系统拒绝覆盖安装。
**先记录需要保留的连接参数、收藏、SQL 和设置，再决定是否卸载旧版。**
卸载会删除本地数据及加密密钥；当前没有完整配置/凭据/AI 记忆迁移工具，CSV 只导出当前页数据。
以后正式版本必须沿用这次签名，并验证覆盖升级。不要将同版本重装或 Debug 升级结果称作正式签名迁移通过。

## English

Release builds use R8, resource shrinking and `debuggable=false`. The application ID stays
`com.example.datasail`. Supply `DATASAIL_SIGNING_DIR` only on a controlled release machine;
without it Gradle produces an unsigned Release. Keep the keystore and password outside the
repository, back up both privately, and reuse this identity for future versions.

Historical APKs used the Android Debug certificate and cannot normally be overwritten by
the new production certificate. Preserve connection details, saved SQL and settings before
removing an old installation. Uninstalling deletes local data and encryption keys. There is
currently no complete migration tool; CSV export is not a configuration or database backup.

Build, checksum, signature and device-test evidence are separate requirements. Passing JVM
tests or building successfully does not prove device/database compatibility. Consult the
actual release test report for the verified scope and remaining limitations.
