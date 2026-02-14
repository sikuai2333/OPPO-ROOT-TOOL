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
