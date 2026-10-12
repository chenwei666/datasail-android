# Release 驱动适配审查（V0.2.2）

已完成 SQL Server Android 源码适配第一阶段，见 [构建与行为边界](../sqlserver-android/README.md)。Release/R8 仍失败；没有把缺失类清单作为 dontwarn 配置，没有占位的成功实现。

## 本轮证据

```bash
./gradlew :sqlserver-android:check
./gradlew :app:assembleRelease --console=plain
./gradlew :core:driverCompatibilityReport --console=plain
```

profile 构建锁定微软 13.6.0.jre8 原始二进制和完整源码，精确应用补丁，编译 3 个源码文件中的 5 个类，其余 class/资源字节不变。派生 JAR 自行标识版本，不沿用失效的微软 JAR 签名。

- MaxResultBufferParser 使用 Runtime.maxMemory，修复容量乘法溢出，移除其 JMX 依赖。
- SQLServerCertificateUtils 不接受 CN 身份回退，PKCS#1 客户端密钥明确拒绝；移除其 JNDI 和 BouncyCastle PEM 依赖。
- SQLServerSQLXML 的 StAX 请求明确不支持；移除 StAX 实现，保留字符串/流/DOM/SAX 及其安全配置。
- R8 实际缺失类从 V0.2.1 的 147 个减少到 132 个（减少 15 个，没有新增）。这是局部进展，不是 Release 通过。

引用审查使用固定 V0.2.1 缺失类型基线，ASM 9.8 对每个方法建立独立常量池。报告同时列原始 SQL Server 143、派生 SQL Server 136、未改动 MariaDB 72 个方法，共 351 行；实际应用两驱动对应 208 个相关方法。报告仅定位引用，不证明可达或可以删除。ASM 仅为构建工具，不进入 APK。

## 下一步最小适配范围

| 位置 | 未解决问题与验收 |
|---|---|
| SQLServerConnection.connectInternal / logon | 直接引用 GSSCredential。需分离并明确拒绝集成认证分支，保留 SQL 账号流程，不能删除整个方法 |
| SQLServerConnection.poolCloseEventNotify | XAConnection 引用。需审查非池化普通连接关闭路径，不能用不关闭连接的空实现 |
| SQLType 重载、JNDI 数据源、Kerberos/Entra/AWS、可选加密插件 | 逐项区分 API 签名、可达功能和可选依赖；不能从 UI 没入口推出 R8 后无引用 |
| MariaDB 驱动 | 尚未修改；LGPL 源码及重链接义务仍需单独核对 |

后续源码修改保持锁定版本、可重放 patch、明确拒绝能力和完整回归；不能静默换驱动或提高 minSdk。

进入可交付 Release 的门槛：R8 无批量忽略即可构建，最终 DEX 没有不受支持的可达平台引用；SQL 账号 TLS 正负例、参数化读写/冲突/未知提交、关闭/超时/取消、升级在混淆 APK 上重跑。API26、API30、真机各自记录，当前 Debug/JVM 结果不替代这些门槛。
