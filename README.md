# OPPO Reno5 Pro+ (PDRM00 / OP4EA7) 工具仓库

围绕 `OPPO Reno5 Pro+`（型号 `PDRM00`，内部代号 `OP4EA7`，设备树代号 `hima`）的一站式资料与工具仓库。

最初只用来做官方 OTA 下载与 Magisk Root，现在扩展到自定义 ROM 构建：LineageOS 23.2（Android 16）定制、recovery 移植、以及 ART 层脱壳工具的 ROM 级集成。

> 设备矩阵：SM8250（kona）/ 8 GB RAM / 1080×2400 / UFS / **非 A/B** / 动态分区 `super` 11,190,403,072 B

## 目录

```text
docs/
  DEVICE-FACTS.md         设备、分区布局、硬件对应关系、Recovery 现状
  ROOT-WORKFLOW.md        Magisk Root 完整流程
  OTA-DOWNLOAD.md         官方 OTA 查询与多线程下载
  ROLLBACK.md             恢复原厂 boot
  TROUBLESHOOTING.md      常见问题
  SESSION-2026-02-14.md   首次 Root 实操记录
  LINEAGEOS-HIMA.md       LineageOS 23.2 定制构建指南（清单/构建/刷机/救砖）
  R0DUMP-PORT.md          r0dump 脱壳移植记录与踩坑清单
patches/                  r0dump 移植补丁，按仓库一文件
firmware/flash-scripts/   一键刷机脚本
tools/                    OTA 查询 / 下载 / 源码仓库检索
adb.exe                   随仓库分发的 adb
```

## 三条主线

### 1. Root 与官方 OTA

不改系统分区，只在原厂 `boot.img` 上打 Magisk 补丁。OTA 包走 Oplus 官方查询接口（`component-ota-cn.allawntech.com`）拿官方 CDN 链接，不用论坛镜像。

```powershell
powershell -ExecutionPolicy Bypass -File tools/query_ota.ps1 `
  -OtaVersion PDRM00_11.F.24_2240_202506291009 -Model PDRM00 -Carrier 10011000 -Region CN
```

细节：[`docs/ROOT-WORKFLOW.md`](docs/ROOT-WORKFLOW.md) · [`docs/OTA-DOWNLOAD.md`](docs/OTA-DOWNLOAD.md)

### 2. LineageOS 23.2 定制构建

设备树来自 `RMX3366-Development` 组织的 `lineage-23.2` 分支（`hima` = Find X3 Neo / Reno5 Pro+ 海外项目名）。这份树对本机的匹配是逐字节的，判定依据写在文档里。

```bash
source build/envsetup.sh
lunch lineage_hima-trunk_staging-userdebug
m systemimage -j8 && m superimage -j8
```

完整清单、构建坑位、刷机与回滚：[`docs/LINEAGEOS-HIMA.md`](docs/LINEAGEOS-HIMA.md)

刷机脚本：

```powershell
.\firmware\flash-scripts\flash-hima.ps1 -Images C:\path\to\out\target\product\hima
```

### 3. ART 层脱壳（r0dump 移植）

把 [r0dump](https://github.com/tiwe0/r0dump) 的 A16 补丁集移植到本机，并修掉上游没覆盖的部分（缺失的 libcore 桥接类、`core_platform` stub 陷阱、sdcardfs 兼容、ART 预算上限）。

实测结果：对抽取壳样本捕获 **50,572 个 codeitem**，抽检 32 个全部为合法 Dalvik 字节码。导出文件数从 316,609 压到 30,990；导出过程带 `done/total` 实时进度条，超过 65,000 条目自动分卷打包。

能力边界要说清楚：整壳与抽取壳可自动还原；**VMP 与 DEX2C 只能识别缺口，无法自动还原**。

完整记录：[`docs/R0DUMP-PORT.md`](docs/R0DUMP-PORT.md)

## 关键提醒

- **Recovery**：本机**没有公开可用的 TWRP**。三个 TWRP 设备树都没有发布产物。TWRP 的 codename `hima` 是 HTC One M9，刷了会变砖。可用的是 LineageOS 内嵌 recovery。
- **首次刷 LOS 要格式化 /data**：ColorOS 的 data 是加密的，LOS 的 init 读不了，会卡 `init_user0_failed`。
- **`m systemimage` 不重建 `super.img`**：改了 system 内容后记得再跑 `m superimage`。
- **备份**：刷任何东西之前先备份 `super`/`boot`/`recovery`/`persist`/`modem*`，方法见 [`docs/DEVICE-FACTS.md`](docs/DEVICE-FACTS.md)。

## 快速检索

找源码树不确定的时候跑：

```bash
./tools/find-device-trees.sh
```

它会按仓库名搜索 `android_device_oppo_hima` / `device_oppo_OP4EA7` / `twrp_device_oppo_OP4EA7` / `kernel_oppo_sm8250` 等关键词，输出仓库、更新时间与 star 数。

## 仓库状态

- 2026-02-14：官方 OTA 下载 + Magisk Root 流程整理完成。
- 2026-10-22：LineageOS 23.2 首次构建成功并刷入真机（`23.2-20261021-UNOFFICIAL-hima`）。
- 2026-10-24：r0dump 移植完成，抽取壳 codeitem 捕获验证通过；补丁与文档归档到本仓库。
