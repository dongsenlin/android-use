#!/bin/sh
# 从 Codex 和 Claude Code 中移除 Android Use；不删除配对和截图（在 ~/.local/share/android-use）。
set -u
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if command -v codex >/dev/null; then
  codex mcp remove android_use >/dev/null 2>&1 && echo "Codex：已移除 MCP android_use"
  codex plugin remove android-use@android-use-local >/dev/null 2>&1 && echo "Codex：已卸载插件"
  codex plugin marketplace remove android-use-local >/dev/null 2>&1 && echo "Codex：已移除本地 marketplace"
fi
if command -v claude >/dev/null; then
  claude mcp remove -s user android_use >/dev/null 2>&1 && echo "Claude Code：已移除 MCP android_use"
  cfg=${CLAUDE_CONFIG_DIR:-$HOME/.claude}
  for s in android-use android-use-setup; do
    [ -L "$cfg/skills/$s" ] && rm "$cfg/skills/$s" && echo "Claude Code：已移除技能 $s"
  done
fi
echo "如需删除配对和截图：rm -rf ~/.local/share/android-use（APK 签名钥匙在 ~/.config/android-use，删了以后新版无法覆盖安装旧版）"
