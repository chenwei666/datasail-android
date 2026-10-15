# PostgreSQL JDBC Android 源码适配

派生版本：`42.7.14-datasail.1`。上游：`org.postgresql:postgresql:42.7.14`。许可证：[BSD-2-Clause](../docs/licenses/pgjdbc-42.7.14-LICENSE)。

保留 TCP、TLS、密码/SCRAM、JDBC 整数类型 API。堆预算使用 Runtime。移除 Android 不存在的 JDBC SQLType 和 StAX 重载；GSS/SSPI 明确拒绝，主机名验证要求 SAN，不回退 CN。

## 可重建来源

`upstream/` 保存完整上游源码 JAR，`upstream.lock.json` 固定源码和二进制 SHA-256、补丁后源码 SHA-256、替换与生成的 class 清单。`patches/android.patch` 是可审阅的源码差异。

```sh
source scripts/env.sh
./gradlew :postgresql-android:check
```

只编译清单中的源文件，再与原始 JAR 合并。未修改 class 和资源必须逐字节一致；失效的上游 JAR 签名移除，派生 JAR 使用自身版本和 provenance。验证失败会阻断构建。没有占位平台 API、字节码改写或 `dontwarn`。

适配范围是数舟已实现的 Android 连接方式，不声称兼容上游全部桌面 API。JVM 单测不替代 Android 混淆包连接测试；以本次发布测试报告为准。
