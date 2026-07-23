# 仓库设置

以下 GitHub 设置不会存储在 Git 中。工作流文件合并到 `master` 后，请在仓库设置页面应用这些选项。

## 常规设置

- 默认分支：`master`
- 启用 Discussions。
- Pull Request 合并后自动删除源分支。
- 启用私密漏洞报告。

## `master` 分支保护

- 合并前必须通过 Pull Request。
- 至少需要一名审查者批准。
- 推送新提交后，自动取消过期的批准。
- 合并前必须解决所有审查对话。
- 必须通过 `Build and verify` 状态检查。
- 要求分支已包含目标分支最新提交。
- 禁止强制推送和删除分支。
- 规则默认对管理员生效，除非另有书面记录的紧急绕过流程。

## Discussions 分类

- 公告
- 提问与帮助
- 功能建议
- 功能投票
- 作品展示
- 开发讨论

## 首次远程验证

1. 创建一个目标为 `master` 的测试 Pull Request，确认 CI 运行并命中 Gradle 缓存。
2. 手动运行 Nightly 工作流，确认预发布 JAR 和校验文件生成。
3. 推送类似 `v2.4.0-beta.1` 的测试预发布标签，确认 GitHub Release 创建成功。
4. 下载 Release 资产后运行 `sha256sum -c checksums-sha256.txt` 验证文件完整性。
