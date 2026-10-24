# Tools

## 1) `query_ota.ps1`

用途: 查询 Oplus 官方 OTA 接口，输出下载链接、MD5、版本号。

示例:

```powershell
powershell -ExecutionPolicy Bypass -File tools/query_ota.ps1 -OtaVersion PDRM00_11.F.24_2240_202506291009 -Model PDRM00 -Carrier 10011000 -Region CN
```

依赖:

- `git`
- `go`

说明:

- 脚本会自动在 `tools/bin/updater.exe` 构建查询器（源码来自 `SakuraKyuo/OplusUpdater`）。
- 构建产物目录 `tools/bin/` 与缓存目录 `tools/.cache/` 已在 `.gitignore` 中忽略。

## 2) `download_ota_multithread.py`

用途: 对 OTA 大文件进行多连接分片下载，支持断点续传和 MD5 校验。

示例:

```powershell
python tools/download_ota_multithread.py --url "<OTA_URL>" --out "_root_work/PDRM00_ota.zip" --threads 16 --md5 "<OTA_MD5>"
```

## 3) `find-device-trees.sh`

用途: 按仓库名搜索与 PDRM00 / OP4EA7 相关的 LineageOS、TWRP、内核源码树，用来确认资源还在不在、最近有没有更新。

示例:

```bash
./tools/find-device-trees.sh            # 打印
./tools/find-device-trees.sh > trees.md # 存成笔记
```

依赖: `curl`、`jq`（GitHub API 匿名限流 60 次/小时）。

## 4) `../firmware/flash-scripts/flash-hima.ps1`

用途: 一键刷入 boot / dtbo / vbmeta / super。

示例:

```powershell
.\firmware\flash-scripts\flash-hima.ps1 -Images C:\path\to\out\target\product\hima
.\firmware\flash-scripts\flash-hima.ps1 -Images . -SkipSuper    # 只刷 boot/dtbo/vbmeta
```

`-SkipSuper` 适合只改内核或 boot 参数的情况；改了 system 内容（含 ART）必须连 super 一起刷。

