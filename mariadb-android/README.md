# MariaDB Connector/J Android 源码适配

派生版本：`3.5.10-datasail.1`。上游：`org.mariadb.jdbc:mariadb-java-client:3.5.10`。许可证：[LGPL-2.1-or-later](../docs/licenses/mariadb-LICENSE.txt)。

保留 TCP、TLS、普通密码认证和 JDBC 整数类型 API。拒绝 JNA 本地管道、GSS、AWS 凭证、JMX 和缺少 Ed25519 提供者的 Parsec；普通池连接清理保留，XA 不提供。FunctionStatement 调用与上游 SQLType 重载相同的整数注册方法。

## 可重建来源

`upstream/` 保存完整上游源码 JAR，`upstream.lock.json` 固定源码和二进制 SHA-256、补丁后源码 SHA-256、替换与生成的 class 清单。`patches/android.patch` 是可审阅的源码差异。

```sh
source scripts/env.sh
./gradlew :mariadb-android:check
```

只编译清单中的源文件，再与原始 JAR 合并。未修改 class 和资源必须逐字节一致；失效的上游 JAR 签名移除，派生 JAR 使用自身版本和 provenance。验证失败会阻断构建。没有占位平台 API、字节码改写或 `dontwarn`。

适配范围是数舟已实现的 Android 连接方式，不声称兼容上游全部桌面 API。JVM 单测不替代 Android 混淆包连接测试；以本次发布测试报告为准。

## LGPL 修改与重新链接

完整上游源码与本项目补丁随源码包提供。可修改该模块补丁和锁定的源码校验值，重新构建派生 JAR，再通过 `:app:assembleRelease` 重新链接应用；无需原作者签名即可构建自己的安装包。自己的签名不能覆盖原签名安装，请在独立测试设备验证。保留 LGPL 许可证及上游版权声明。
