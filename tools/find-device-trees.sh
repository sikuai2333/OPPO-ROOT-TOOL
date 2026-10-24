#!/usr/bin/env bash
# 列出本机上与 PDRM00 (OP4EA7) 相关的 LineageOS / TWRP / 内核源码树。
#
# 背景：这机器的公开资源很少，几个月后很容易忘记哪个仓库对得上本机
# （尤其 hima 这个名字和 HTC One M9 撞车）。有网时跑一遍，保留输出即可。
#
# 用法：
#   ./find-device-trees.sh            # 只打印结果
#   ./find-device-trees.sh > trees.md # 存成笔记
#
# 依赖：curl（GitHub API 匿名限流 60 次/小时）

set -uo pipefail

API=https://api.github.com
SEARCH_TERMS=(
  "android_device_oppo_hima"
  "device_oppo_OP4EA7"
  "device_oppo_sm8250"
  "twrp_device_oppo_OP4EA7"
  "kernel_oppo_sm8250"
)

command -v curl >/dev/null || { echo "需要 curl" >&2; exit 1; }
command -v jq   >/dev/null || { echo "需要 jq" >&2; exit 1; }

echo "# PDRM00 / OP4EA7 相关源码仓库"
echo
echo "查询时间: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo

for term in "${SEARCH_TERMS[@]}"; do
  echo "## $term"
  echo
  curl -fsS --max-time 20 \
    "$API/search/repositories?q=$term+in:name&per_page=10" \
    | jq -r '.items[]? | "- [\(.full_name)](\(.html_url)) — \(.description // "无描述") | 更新 \(.pushed_at[:10]) | ★\(.stargazers_count)"' \
    || echo "_查询失败（限流或网络）_"
  echo
done

echo "## 参考"
echo
echo "- 设备树基线: RMX3366-Development/android_device_oppo_hima (lineage-23.2)"
echo "- 脱壳补丁: tiwe0/r0dump (v2026-09-12-fusion)"
echo
echo "> 提醒: TWRP 的 codename hima 是 HTC One M9，与本机无关。"
