# V0.11.0 审查验证

`checks.json` 记录本轮重新执行的单元测试与增量构建检查。测试摘要取自 JUnit XML，不包含历史实连或 Android 测试。

`FilterBoundaryProbe.java` 只使用合成元数据调用真实核心编译器，不联网、不修改数据库。它用于复现 V0.11.0 基线限制，断言问题仍存在。V0.12.0 已增加正式单元/实连回归，本文件保留为历史诊断，不应在新版本运行或算作通过测试。

复现（在独立检出基线 `88cb0bbb4fd9030acf7a6035d586f4221db760c0` 的工作树内运行，并从本报告复制诊断文件；JDK 21，先按 scripts/env.sh 激活工具链）：

```bash
./gradlew :core:classes --console=plain
```

找到 Gradle 缓存中与项目锁定版本一致的 `kotlin-stdlib-2.2.10.jar`，将其完整路径填写到下列占位处：

```bash
java --class-path 'core/build/classes/kotlin/main:/path/to/kotlin-stdlib-2.2.10.jar' docs/evidence/audit-v0110/FilterBoundaryProbe.java
```

实际输出见 `filter-boundaries.txt`。第二项只证明客户端接受排序，未在本轮连接 SQL Server 验证 text 类型排序的服务端错误。
