# 安全策略

Android Use 能远程操作一台真实手机，安全问题我们会优先处理。

*English: Android Use can remotely operate a real phone, so security reports get priority. Please report privately through [GitHub private vulnerability reporting](https://github.com/dongsenlin/android-use/security/advisories/new). Do not open a public issue.*

## 如何报告

请通过 GitHub 的 **[私密漏洞报告](https://github.com/dongsenlin/android-use/security/advisories/new)** 提交，不要发公开 Issue。报告里请写清：

- 受影响的版本（手机端 App 和电脑端各是哪个版本）。
- 复现步骤，以及需要的前提（是否需要同一局域网、是否需要配对码等）。
- 可能造成的影响。

我们会尽快确认，修复后发布新版本，并在更新记录里致谢（除非你希望匿名）。

## 支持的版本

只有最新版本会收到安全修复，请保持手机端和电脑端都是最新。

| 版本 | 是否支持 |
| --- | --- |
| 最新的 0.1.x | ✅ |
| 更早的版本 | ❌ |

## 设计上的安全边界

报告前可以先对照一下，下面这些是已知的、有意为之的边界：

- **明文 HTTP**：手机和电脑之间走明文 HTTP。同一局域网里能抓包的人可以看到截图和配对码，所以只应在信任的 Wi‑Fi 下开启远程控制。如果你找到了在**不知道配对码**的情况下操作手机的办法，那就是漏洞，请务必报告。
- **配对码**：配对码是 120 位随机数，所有操作接口都要求带上它；不带配对码时只有 `/v1/ping` 可访问，而且只返回版本号。手机端只接受局域网和本机地址的连接。
- **两道开关**：无障碍服务要用户在系统设置里亲手开启；「允许 Agent 远程控制」默认关闭，随时可以通过状态栏的紫色提示条暂停。
- **数据留在本机**：截图只保存在电脑本地（`~/.local/share/android-use/artifacts/`，仅本人可读）。项目不收集任何统计数据。

## 使用者的安全建议

- 不用时关掉「允许 Agent 远程控制」。
- 怀疑配对码泄露时，在 App 里点「换一个配对码」，旧码立即失效。
- 支付、转账、发送消息等操作，Agent 会先请你确认，请认真看清再同意。
