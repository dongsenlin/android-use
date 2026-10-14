#!/bin/sh
# 把 Android Use 装进 Codex：本地 marketplace + 插件（带两个技能）+ 同名标准 MCP 注册。
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
command -v codex >/dev/null || { echo "找不到 codex 命令，先安装 Codex CLI" >&2; exit 1; }
command -v python3 >/dev/null || { echo "找不到 python3（需要 3.8+）" >&2; exit 1; }

stage="$root/dist/codex/android-use"
rm -rf "$stage"
mkdir -p "$stage/.codex-plugin" "$stage/server" "$stage/assets"
cp "$root/packaging/codex/plugin.json" "$stage/.codex-plugin/plugin.json"
cp "$root/packaging/codex/mcp.json" "$stage/.mcp.json"
cp "$root/server/android_use.py" "$stage/server/"
cp -R "$root/skills" "$stage/skills"
cp "$root/packaging/icon.svg" "$stage/assets/icon.svg"
cp "$root/README.md" "$root/LICENSE" "$stage/"

codex plugin marketplace add "$root" --json >/dev/null
installation=$(codex plugin add android-use@android-use-local --json)
installed=$(printf '%s' "$installation" | python3 -c 'import json,sys; print(json.load(sys.stdin)["installedPath"])')
# 与插件内的服务同名：Codex 以配置为准，只留一份工具，且不占插件共享的说明额度。
codex mcp add android_use -- python3 "$installed/server/android_use.py" >/dev/null
echo "已安装到 Codex：$installed"
echo "重开或新建 Codex 对话即可使用（先说：用 android-use-setup 配对我的安卓手机）。"
