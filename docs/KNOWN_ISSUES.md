# V0.2.2 已知问题与未验证项

1. **Release 阻塞：** R8 缺失 Android 的 SQLType、management、JNDI、XA，以及驱动的 Azure/Entra、Kerberos、AWS 等可选依赖。SQL Server 普通 TLS 路径的 JNDI 依赖已通过严格 TrustManager 扩展绕开，但没有修复所有可选能力，未批量 dontwarn。没有 Release APK，不能正式发布。
2. **验证范围有限：** SQL Server JVM、Android 8/API26 和 Android 11/API30 x86_64 软件模拟器已经实连；真机/arm64、MySQL Android、MariaDB 服务器未验证。UI smoke 不等于全部手工交互/性能/无障碍验收。
3. **写入约束：** 仅测试会话中安全类型单行图形编辑；无主键、视图、启用触发器、不支持的复杂列组合只读。提交短事务使用表锁，可能阻塞其它写入；应用不适用于大规模生产管理。
4. **分页/查询：** 100 行/64 列/字段读取预算；额外 8 MB 驱动保留缓冲预算不代表总响应或总堆限制；无主键只有首批。分页不是一致性快照。只支持受限 SELECT，不支持 CTE、多语句、任意写 SQL、DDL、批量事务或大字段按需加载。本地过滤不是数据库权限控制。
5. **TLS：** 支持系统 CA 和连接专用公共 CA，SQL Server 要求匹配 SAN；不支持 CN-only 回退、IPv6、mTLS、Windows/Kerberos/Entra。错误证书不降级。MySQL 自定义 CA 路径仍需 Android 实验。
6. **未知结果：** 超时/取消/断网/丢失提交确认不证明服务端已停止或回滚。连接失效，提示核对，无自动重放。实际硬断网和服务端请求清理尚需更多设备测试。
7. **凭据/生命周期：** 不保存密码、不提供 Keystore 记忆密码；Compose/JDBC String 不能保证内存擦除。后台与旋转断开会话，不能恢复未确认编辑。连接元数据可保存，不包含秘密。
8. **发行身份：** com.example.datasail 占位；同一本地 Debug 签名支持本轮覆盖安装，不保证跨云任务长期密钥。没有公开 Release、远端推送或环境发布。
9. **后续产品范围：** MySQL/MariaDB 工作台、其它引擎、AI、收藏、导入导出尚未实现。

下一项最小任务：继续处理 SQL Server Android profile 的 GSS/集成认证和普通关闭路径，构建混淆 Release 后，在 Android 11 真机验证相同读写、冲突、取消与覆盖升级流程。

V0.2.1 已修复切库部分失败的目标显示，并通过 API26/API30 超时/取消及配置保留测试。真实断网、服务端最终取消状态和完整生命周期仍不是已验证能力。

V0.2.2 R8 缺失类从 147 降到 132，仍未通过；SQL Server 原生源码适配范围有限，不能宣传全部 JDBC 能力兼容 Android。驱动的 CN-only、PKCS#1 客户端私钥和 StAX 路径明确不支持。
