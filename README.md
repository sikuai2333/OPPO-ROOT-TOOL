# OPPO PDRM00 Root 参考仓库

- 日期: 2026-02-14
- 执行者: Codex
- 目标设备: `OPPO Reno5 Pro+ (PDRM00 / OP4EA7)`
- 目标系统: `ColorOS 13.1.0.201(CN01)`

## 1. OTA 包来源（重点）

本仓库使用的 OTA 包不是论坛镜像，而是通过 Oplus 官方 OTA 查询接口拿到的官方 CDN 链接。

- 查询接口主机（CN）: `component-ota-cn.allawntech.com`（接口路径 `/update/v5`）
- 包下载域名（返回结果中的 CDN）:
  - `gauss-compotacostauto-cn.allawnfs.com`
  - `gauss-componentotacostmanual-cn.allawnfs.com`

本次实操查询参数与结果（示例）:

```powershell
updater PDRM00_11.F.24_2240_202506291009 --region CN --model PDRM00 --carrier 10011000 --json
```

返回中确认:

- `versionName = PDRM00_13.1.0.201(CN01)`
- `otaVersion = PDRM00_11.F.26_2260_202512111236`
- `md5 = bd073f00045571696359e470366b9351`
- `package_url`（示例）:
  - `https://gauss-compotacostauto-cn.allawnfs.com/remove-ca6670f0baba3d136956b38dc7186104/component-ota/25/12/15/99ee88345ecb43b1bfd8ab282d714d91.zip`

结论: 该 OTA 包与目标系统版本一致，可用于提取 `boot.img` 修补。

## 2. 仅凭本仓库继续 Root（工具代码已内置）

仓库已内置工具代码，无需再手抄命令逻辑:

- OTA 查询脚本: `tools/query_ota.ps1`
- OTA 多线程下载脚本: `tools/download_ota_multithread.py`

最小依赖:

- `adb` / `fastboot`
- Python 3（运行下载脚本）
- Git（用于拉取查询器源码）
- Go（用于构建仓库内查询器）

说明:

- `tools/query_ota.ps1` 会自动在 `tools/bin/updater.exe` 构建兼容版查询器，避免系统内旧版 `updater` 参数不兼容问题。

## 3. 快速流程

1. 查询 OTA 链接:
   ```powershell
   powershell -ExecutionPolicy Bypass -File tools/query_ota.ps1 -OtaVersion PDRM00_11.F.24_2240_202506291009 -Model PDRM00 -Carrier 10011000 -Region CN
   ```
2. 使用返回的 `package_url` 下载并校验:
   ```powershell
   python tools/download_ota_multithread.py --url "<package_url>" --out "_root_work/PDRM00_ota.zip" --threads 16 --md5 "<package_md5>"
   ```
3. 从 OTA 提取 `boot.img`，推送手机修补，刷回 `boot` 分区。
4. `adb shell "su -c id"` 验证 root。

详细步骤看:

- `docs/ROOT-WORKFLOW.md`
- `docs/OTA-DOWNLOAD.md`
- `docs/TROUBLESHOOTING.md`
- `docs/ROLLBACK.md`
- `docs/SESSION-2026-02-14.md`

## 4. GitHub 提交建议

- `.gitignore` 已忽略 OTA 大包、分片、镜像等临时文件。
- 推送前先看:

```powershell
git status
```

确认没有大文件误提交后再 push。
