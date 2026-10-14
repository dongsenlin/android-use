# 参与贡献

欢迎提 Issue、真机兼容性报告和 Pull Request。这个项目刻意保持"零依赖"：手机端是纯 Java，不用 Gradle；电脑端只用 Python 标准库。贡献时请尽量保持这一点。

*English: Issues, real-device compatibility reports and pull requests are welcome. Please keep the project dependency-free: plain Java on the phone, no Gradle, Python standard library only on the computer side. Chinese or English are both fine in issues and PRs.*

## 最有价值的贡献

- **真机兼容性报告**：在 [Discussions](https://github.com/dongsenlin/android-use/discussions) 的 *Show and tell* 里贴出你的机型、系统版本、厂商系统（MIUI / HyperOS / ColorOS / OriginOS / One UI…），并说明哪些功能正常、哪些不行。国产系统杀后台和无障碍限制的情况尤其需要。
- **Bug**：用 Issue 模板提交，附上 `android_doctor` 的输出。**记得删掉配对码**。
- **改进 Agent 的操作体验**：更稳的等待策略、更好的报错提示、更省 token 的观察方式。

## 开发环境

- **电脑端**：Python 3.8+，不需要安装任何包。
- **手机端**：JDK 17+，Android SDK 的 `platforms;android-35` 和 `build-tools;35.0.0`。

```sh
python3 -m unittest discover -s tests -v   # 电脑端离线测试（用假手机，几秒跑完）
sh app/build.sh                            # 打包 APK 到 dist/
```

签名钥匙第一次构建时会自动生成在 `~/.config/android-use/`。它是你本机自己的钥匙，不要提交。

### 用模拟器做端到端测试

```sh
adb install -r dist/android-use-*.apk
adb shell settings put secure enabled_accessibility_services \
  com.dongsen.androiduse/com.dongsen.androiduse.AgentService
adb forward tcp:8787 tcp:8787
# 在模拟器里打开 App，打开「允许 Agent 远程控制」，然后：
ANDROID_USE_STATE_DIR=/tmp/au-test python3 server/android_use.py call setup \
  '{"action":"pair","url":"127.0.0.1:8787","token":"<App 里显示的配对码>"}'
ANDROID_USE_STATE_DIR=/tmp/au-test python3 server/android_use.py call ready '{}'
```

- **刘海屏**：可以用 `adb shell cmd overlay enable com.android.internal.display.cutout.emulation.tall` 模拟。
- **配对隔离**：`ANDROID_USE_STATE_DIR` 指向临时目录，就不会覆盖你真实手机的配对。

## 提交 Pull Request 前

1. `python3 -m unittest discover -s tests` 全部通过；改了电脑端行为的，请补测试（`tests/test_server.py` 里有假手机可以用）。
2. 改了手机端的，请在模拟器或真机上实际跑过，并在 PR 里写明机型和系统版本。
3. 改了对外行为（工具参数、错误码、提示条位置等），同步更新 `README.md`、`skills/` 下的技能说明和 `CHANGELOG.md`。
4. 版本号写在四个地方：`app/build.sh`、`AgentService.VERSION`、`server/android_use.py` 的 `VERSION`、`packaging/codex/plugin.json`。由维护者在发版时统一修改，PR 里不用改。

## 安全问题

请不要在公开 Issue 里报告安全漏洞，见 [SECURITY.md](SECURITY.md)。
