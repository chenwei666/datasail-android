# 早期失败与修复

这些文件是诊断证据，不是最终验收报告。

- PostgreSQL 初始 5 秒登录预算不足，随后在 15 秒预算下遇到过超时；最终新增引擎 TCP/TLS 15 秒、PostgreSQL 登录整体 30 秒。查询/取消仍单独限时；严格证书校验不变。软件模拟器上避免重型构建与计时测试同时运行。
- pgjdbc 元数据方法调用 Android 缺少的 PreparedStatement.closeOnCompletion；客户端改为参数化 PostgreSQL information_schema/pg_catalog 查询，显式关闭语句/结果。没有修改或屏蔽驱动 API。
- MySQL 主机名错误反例进入上游 CN 回退，触发缺少 javax.naming.ldap.LdapName；StrictTlsPlugin 改用共享 SAN 匹配器，链验证不变，拒绝 CN-only。
- SQLite 第一轮测试误以为文件只有一张表，未考虑 Android 自动创建 android_metadata；改为严格按目标表 records 选择，原文件/副本逐字节不变和写入拒绝断言保留。
- 数值预算初版对所有 DECIMAL 使用 getCharacterStream，MariaDB 不支持此转换。最终对受限精度使用 BigDecimal 并扣除预算，对 PostgreSQL 无界 NUMERIC 流式限制，另验收 3000 位数字未加载及 NaN 保真。

最终状态按各引擎目录、API 日志以及 TEST_REPORT 判断。JVM、Android、R8、真机结果不互相替代。

MySQL 超时回归还暴露清理阶段 rollback 失败覆盖原查询异常的风险；JdbcBrowseSession 现在保留原异常，把清理异常附为 suppressed，并关闭连接。对应单元回归模拟查询超时与回滚失联同时发生，要求最终仍报告 TIMEOUT。
