# V0.1.0 已知问题与未验证项

1. **Release 阻塞：** `:app:assembleRelease` 的 R8 报告缺失类，包含 Android 不提供的 `java.sql.SQLType`、`javax.naming.*`、`java.lang.management.*`、部分 XML/JDBC XA，以及驱动的 Azure/Entra、Kerberos、AWS 等可选依赖。当前不批量忽略。不能将 Debug D8 打包成功解释为这些路径在 ART 可运行。正式发布前需完成驱动裁剪/适配设计和 Release 实机验证。
2. **SQL Server 尚未实连：** 官方 2022-CU16 镜像配置请求重定向至 `centralus.data.mcr.microsoft.com`，代理 HTTP 403；已保存域名需求草稿，当前运行策略未更新。SQL Server 2022 CU16 是待测目标，不是本次实测服务端。
3. **Android 尚未执行：** 本机无 `/dev/kvm`，没有连接 ADB 设备；两个仪器测试只构建，未运行。Android 8/11、ABI、真实安装、前后台、输入法、弱网、TalkBack、性能和升级均未执行。
4. **TLS 范围：** 默认严格验证系统信任链与主机名；没有自定义 CA 导入 UI、mTLS、IPv6 或信任例外开关。私有 CA 的真机端点需要先建立受信任链，不能跳过校验。
5. **取消真实性：** cancel/close 请求与客户端资源清理不证明服务端已经停止。UI 保留 UNKNOWN；未实现事务/写操作，也不自动重试。
6. **凭据范围：** 内存 char[] 结束后清零，但 Compose/JDBC String 无法保证擦除；不持久化密码，不提供保存连接/Keystore 功能。没有宣称可抵御被攻陷设备上的内存窃取。
7. **发行身份：** com.example.datasail 是占位；本地 Debug 签名不是正式发行或长期升级签名。没有正式 Release APK、公开 Release、远端推送或环境发布。
8. **产品范围：** 工作台、任意 SQL、元数据浏览、编辑、AI 实现、收藏、持久化、导入导出、其他数据库均未实现。

下一项最小任务：在 Android 11 上运行仪器测试并验证 SQL Server 严格 TLS 合成探针；若 ART 暴露缺失类，先形成最小驱动适配修复与回归，再推进对象浏览。R8 门禁必须在正式分发前解决。
