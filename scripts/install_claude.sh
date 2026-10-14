#!/bin/sh
# 把 Android Use 装进 Claude Code：用户级 MCP 服务 + 两个技能（软链接到源码，更新源码即生效）。
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
command -v claude >/dev/null || { echo "找不到 claude 命令，先安装 Claude Code" >&2; exit 1; }
command -v python3 >/dev/null || { echo "找不到 python3（需要 3.8+）" >&2; exit 1; }

cfg=${CLAUDE_CONFIG_DIR:-$HOME/.claude}
claude mcp remove -s user android_use >/dev/null 2>&1 || true
claude mcp add -s user android_use -- python3 "$root/server/android_use.py" >/dev/null
mkdir -p "$cfg/skills"
for s in android-use android-use-setup; do
  dest="$cfg/skills/$s"
  if [ -L "$dest" ] || [ ! -e "$dest" ]; then
    ln -sfn "$root/skills/$s" "$dest"
  else
    echo "跳过 $dest：已存在且不是软链接，请手动处理" >&2
  fi
done
echo "已安装到 Claude Code（MCP: android_use，技能: android-use / android-use-setup）。"
echo "重开 Claude Code 会话即可使用（先说：用 android-use-setup 配对我的安卓手机）。"
