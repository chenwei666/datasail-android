# 中间诊断，不计入最终通过数

`intermediate-mobile-api30-failure.txt` 保留首次通用 Android 流程的实际失败：密码框的 Compose 语义已掩码，测试错误地等待明文，因此超时。修正为检查掩码、保存勾选状态及独立解密结果，并增加真实 SQL Server 保存密码后无需重输的登录检查。未删除生产密码掩码或降低安全策略。

最终包的完整回归结果位于父目录的 mobile-api26.txt / mobile-api30.txt 与 SQL_SERVER/。中间升级记录只作为诊断，不重复计数。

`intermediate-sqlserver-ui-failure.txt` 与 `reconnect-cleanup-reproduction.txt` 记录实连重连失败及明确的“上一操作仍在清理”状态。修复 WorkspaceRunner 的重复清理排队，新增阻塞关闭场景单测；未靠固定等待或重新输入密码绕过。

`intermediate-grid-ui-failure.txt` 记录嵌套滚动的 UI 测试定位失败：仅滚动内部网格没有把外层页面滚到网格位置。增加外层表格定位和单元格可见性断言，仍使用实际点击与输入，未用直接模型写入替代编辑交互。

`intermediate-grid-scroll-failure.txt` 进一步说明单击处理需等待双击识别：仪器测试显式推进虚拟时钟 600 ms 后再等待选择状态。最终真实点击、文本提交、NULL 提交和只读检查全部保留。

完整工作流曾在中间包通过（intermediate-grid-pass.txt）。随后全量重建改变 classes6.dex 与 APK SHA-256，因此最终交付重新执行设备与实连矩阵，不把中间包成绩直接归给新包。差异与中间 SHA 见 rebuild-difference.json / intermediate-sql-tested-apk.sha256。
