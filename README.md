# OPPO PDRM00 Root 参考仓库

- 日期: 2026-02-14
- 执行者: Codex
- 目标设备: `OPPO Reno5 Pro+ (PDRM00 / OP4EA7)`
- 目标系统: `ColorOS 13.1.0.201(CN01)`

## 1. 仓库用途

本仓库用于沉淀一次可复现的 Root 过程，重点记录:

- 版本一致性校验方法
- OTA 包提取 `boot.img` 的步骤
- Magisk 修补与 fastboot 刷写流程
- Root 成功验证与回滚路径

适合后续自己复用，也方便直接上传到 GitHub 做留档。

## 2. 目录说明

- `README.md`: 总览与入口
- `docs/OTA-DOWNLOAD.md`: OTA 下载工具与命令说明
- `docs/ROOT-WORKFLOW.md`: 完整操作流程
- `docs/TROUBLESHOOTING.md`: 常见问题排查
- `docs/ROLLBACK.md`: 回滚到原厂 boot 的步骤
- `docs/SESSION-2026-02-14.md`: 本次实操记录
- `_root_work/`: 本地工作区（含脚本与本地保留镜像）

## 3. 快速开始

1. 先读 `docs/ROOT-WORKFLOW.md`，按“版本一致性检查”确认目标包是否匹配。
2. 下载 OTA 前先读 `docs/OTA-DOWNLOAD.md`，确认下载工具与校验方式。
2. 修补前，必须确认当前系统版本与 `boot.img` 来源版本完全一致。
3. 修补后只刷正确分区:
   - 本机是非 A/B 机型 -> 刷 `boot`
4. 刷写后用 `su -c id` 验证是否获取 root。

## 4. 本次结论

- 本机已成功 Root。
- 当前验证结果:
  - `su -v` -> `30.6:MAGISKSU`
  - `su -c id` -> `uid=0(root)`

## 5. 提交到 GitHub 建议

如果你只想同步文档，不要把大文件一起推送:

- 已通过 `.gitignore` 忽略 `_root_work/*.img`、OTA 包和分片文件。
- 推送前建议再执行一次:

```powershell
git status
```

确认只包含文档文件后再推送远端。
