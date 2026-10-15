# MongoDB Java synchronous driver Android 源码适配

派生版本：`5.6.5-datasail.1`。上游：`org.mongodb:mongodb-driver-sync:5.6.5`。许可证：[Apache-2.0](../docs/licenses/MongoDB-Java-5.6.5-LICENSE.txt)。

在创建连接前拒绝自动客户端字段加密；普通同步数据库和集合操作保留。

## 可重建来源

`upstream/` 保存完整上游源码 JAR，`upstream.lock.json` 固定源码和二进制 SHA-256、补丁后源码 SHA-256、替换与生成的 class 清单。`patches/android.patch` 是可审阅的源码差异。

```sh
source scripts/env.sh
./gradlew :mongodb-sync-android:check
```

只编译清单中的源文件，再与原始 JAR 合并。未修改 class 和资源必须逐字节一致；失效的上游 JAR 签名移除，派生 JAR 使用自身版本和 provenance。验证失败会阻断构建。没有占位平台 API、字节码改写或 `dontwarn`。

适配范围是数舟已实现的 Android 连接方式，不声称兼容上游全部桌面 API。JVM 单测不替代 Android 混淆包连接测试；以本次发布测试报告为准。
