# Release 驱动适配审查（V0.2.1）

本轮不把 R8 缺失类清单直接作为 dontwarn 配置，也不生成返回成功的空实现。Release 仍是失败门禁。

可复现检查：

```bash
./gradlew :app:assembleRelease --console=plain
./gradlew :core:driverCompatibilityReport --console=plain
```

第二项是只读构建工具，用已校验的 ASM 9.8 读取原始驱动 JAR，按方法建立独立常量池，再与真实 R8 缺失类型清单对照；输出 `core/build/reports/driver-audit.txt`。它不修改驱动、不生成忽略规则、也不证明某方法不可达。ASM 仅用于 driverAudit source set，不打包进 APK。新 checkout 没有生成 R8 清单时，使用归档的 V0.2.0 清单作诊断输入，输出会标明输入文件。

本轮检测到 215 个方法引用缺失类型（SQL Server 143，MariaDB 72）。关键证据：

| 引用位置 | 问题与下一步 |
|---|---|
| SQLServerConnection.connectInternal / logon | 直接引用 GSSCredential，不能删除整个方法；需在可审计 Android 源码 profile 中分离并明确拒绝集成认证分支，保留 SQL 账号流程 |
| SQLServerConnection.poolCloseEventNotify | XAConnection 引用；需验证非池化普通连接关闭路径，不得用不关闭连接的空实现 |
| MaxResultBufferParser.getMaxMemory | 依赖 JMX 内存接口；可单独评审 Runtime.maxMemory 等价实现与边界，不应整类屏蔽 |
| SQLServerCertificateUtils.parseCommonNameSecure | JNDI CN 解析；当前应用通过严格链+SAN TrustManager 避开，仍需证明优化后调用路径及证书负例 |
| SQLType 重载、XA/JMX、StAX、Kerberos/Entra/AWS、可选加密插件 | 需逐项区分 API 签名、可达功能和可选依赖；不能从“UI 没入口”推出 R8 后无引用 |

建议下一次只推进 SQL Server 的 Android 源码 profile：固定上游版本/源码校验和、保留 MIT 许可、以可重放 patch 管理改动；对禁用功能使用明确的不支持错误。MySQL/MariaDB 的 LGPL 修改与重链接义务必须另行核对。不能静默换驱动或提高 minSdk。

进入可交付 Release 的门槛：R8 不依赖批量忽略即可构建；最终 DEX 无不受支持的平台引用；SQL 账号 TLS 正负例、参数化增改删/冲突/未知提交、关闭/超时/取消均在混淆后的 APK 上重跑；API26 和 API30 与真机分别记录。当前 Debug 验证不可代替这些门槛。
