# V0.8.0 发布载荷

此专用 `release-assets/V0.8.0` 分支用于上传经过本地验收的既有产物；主分支不存放这些二进制文件。APK 与归档直接复制自不可变版本快照，未重新构建或签名。

`scripts/publish-v080.py --verify-only` 核对全部附件、已测试 APK 和原始 ZIP 的 SHA-256。GitHub Actions 使用仓库提供的临时 GITHUB_TOKEN 创建预发布草稿、上传附件、下载校验，再公开预发布。不同内容的既有标签或附件会被拒绝，不使用强制推送或覆盖上传。

真实发布结果写入本分支的 `publication/V0.8.0.json`。归档 manifest 中的 `published: false` 是打包时的历史状态，不能修改旧快照来补写发布状态。
