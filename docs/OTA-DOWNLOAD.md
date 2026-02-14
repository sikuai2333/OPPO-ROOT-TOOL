# OTA 下载工具说明

- 日期: 2026-02-14
- 执行者: Codex
- 适用机型: `PDRM00`（本次实操）

## 1. 本次使用了哪些工具

1. OTA 查询工具: `updater`（OplusUpdater CLI）
2. 文件下载工具:
   - `curl`（单连接/断点续传）
   - 本地分片脚本 `download_ota_multithread.py`（多连接断点续传）

说明:

- 你当前的 aria2 RPC（`127.0.0.1:16800`）在该 CDN 链路上多次返回 `403`，所以本次没有采用 aria2 作为最终下载链路。

## 2. OTA 链接查询（updater）

示例:

```powershell
updater PDRM00_11.F.24_2240_202506291009 --region CN --model PDRM00 --carrier 10011000 --json
```

从输出里提取:

- `manualUrl` 或 `url`
- `md5`
- `size`
- `versionName / otaVersion`

## 3. 下载方式 A（curl，推荐保底）

断点续传:

```powershell
curl.exe -L -C - -o PDRM00_ota.zip "<OTA_URL>"
```

## 4. 下载方式 B（多连接分片脚本）

脚本（本地工作目录）:

- `_root_work/download_ota_multithread.py`

示例:

```powershell
python _root_work/download_ota_multithread.py --url "<OTA_URL>" --out "_root_work/PDRM00_ota.zip" --threads 16 --md5 "<OTA_MD5>"
```

## 5. 完整性校验（必须）

```powershell
certutil -hashfile .\PDRM00_ota.zip MD5
```

结果必须与 OTA 元数据中的 `md5` 一致。

## 6. 风险与注意事项

1. 只使用与当前系统完全一致的 OTA 包提取 `boot.img`。
2. 下载完成后先校验哈希，再进入修补/刷写。
3. 下载工具可替换，但“版本一致性+哈希一致性”不可省略。

