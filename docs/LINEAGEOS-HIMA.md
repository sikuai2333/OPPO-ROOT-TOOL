# LineageOS 23.2 定制构建指南（hima / PDRM00）

本仓库同时托管 PDRM00 的 **LineageOS 23.2（Android 16）定制构建** 过程资料：清单、设备树来源、构建步骤、r0dump 脱壳移植补丁、刷机与回滚。

> 目标设备：OPPO Reno5 Pro+ `PDRM00` / `OP4EA7`，SM8250（kona），8 GB RAM，非 A/B，动态分区。

## 1. 清单从哪里来

设备树来自 [RMX3366-Development](https://github.com/RMX3366-Development) 组织的 `lineage-23.2` 分支，`hima` 是 Find X3 Neo / Reno5 Pro+ 的海外项目名。

判定这棵树对得上本机（不是同名机型）的三条硬证据：

```makefile
# device/oppo/hima/BoardConfigCommon.mk
TARGET_OTA_ASSERT_DEVICE   := OP4F0BL1,OP4EA7,hima
BOARD_SUPER_PARTITION_SIZE := 11190403072      # 与实机 super 分区逐字节相同
```

```properties
# recovery/root/vendor/odm/etc/20135/build.default.prop
ro.product.odm.model=PDRM00
```

内核 DTS 里的触摸节点是 S6SY792，`display-coords = <1080 2400>`，与实机一致。

> **命名冲突警告**：TWRP 的 codename `hima` 是 **HTC One M9（2015）**，与本机的 hima 项目无关。刷 `dl.twrp.me/hima/` 会变砖。

需要同步的仓库：

| 仓库 | 作用 |
|---|---|
| `RMX3366-Development/android_device_oppo_hima` | 设备树主体（`lineage-23.2`） |
| `RMX3366-Development/android_device_oppo_sm8250-common` | 平台公共部分 |
| `RMX3366-Development/android_kernel_oppo_sm8250` | 内核 4.19.325 |
| `RMX3366-Development/android_hardware_oplus` | OPPO 硬件抽象 |
| `RMX3366-Development/android_hardware_dolby` | 杜比支持 |
| GitLab 上的 `proprietary_vendor_oppo_{hima,sm8250-common}` | 闭源 blobs |

## 2. 网络

`android.googlesource.com` 在国内多数网络下不可达（表现为 `repo sync` 长时间 0 MB/min）。可用组合：

- AOSP：`https://mirrors.ustc.edu.cn/aosp`
- LineageOS / GitHub 仓库：`https://gh-proxy.com/https://github.com/...`（实测 ~74 MB/s）

浅克隆收益极大，例如 `prebuilts/r8` 完整历史 4.9 GB，`--depth 1` 后只有 87 MB。

```bash
repo init -u https://github.com/LineageOS/android.git -b lineage-23.2 --depth 1
```

改完 `.repo/manifests/default.xml` 后，`.repo/manifests` 这个 git 仓库自己的 `origin` 仍指向真 GitHub，`repo sync` 会继续从那里拉 manifest。必须一并 `git remote set-url origin <mirror>`。

## 3. 构建

```bash
source build/envsetup.sh
lunch lineage_hima-trunk_staging-userdebug
m systemimage -j8     # 只出 system
m superimage -j8      # 动态分区镜像（改了 system 之后必须重跑）
m bacon               # 完整 OTA 包
```

### 宿主要求

| 项 | 值 |
|---|---|
| 内存 | **≥ 28 GB**（`soong_build` 单进程峰值 24.4 GB） |
| swap | ≥ 20 GB |
| 并行度 | 12 线程主机用 `-j8`，不要 `-j16` |
| 磁盘 | 源码树 60–80 GB，出包再 +100 GB |
| 全量耗时 | 12 线程 / 28 GB 下约 **11 小时 42 分**（215,056 个 ninja target） |

WSL2 配置参考：

```ini
[wsl2]
memory=28GB
swap=20GB
swapFile=D:\\WSL\\swap.vhdx
```

### 已知构建坑

| 现象 | 原因 | 处理 |
|---|---|---|
| `unrecognized module type "csuite_test"` / `cts_defaults` 未定义 | 删除了 `cts`/`test`/`trusty`/`cuttlefish` | 这些必须恢复，Soong 全局解析 module type |
| `missing variant ... image:vendor,link:static` | host-only 模块被 `vendor_available` 模块依赖 | 给 `libskia_skcms`、`libhwy`、`xmp_toolkit_sdk`、`zuid_md5` 加 `vendor_available: true` |
| `cc_defaults` 同名冲突 | `hardware/oplus/interfaces` 与 vendor 里 prebuilt 重名 | shim defaults 改 `vendor: true` |
| `neverallow dumpstate vold` | `device/qcom/sepolicy/generic/private/dumpstate.te` | 删掉违规的 `binder_call(dumpstate, vold)` |
| `genfscon /class/typec` 冲突 | legacy-um 旧规则重复 | 删除旧条目 |
| 内核编译 `openssl/bio.h` not found | sysroot 里没有 openssl | `TARGET_KERNEL_LIBC_SYSROOT_USE := host`（不要在 sysroot 里塞 glibc 2.39 的 libcrypto） |
| boot jar allowlist 报错 | `net.oneplus.odm`、`oplus.content.res` | 追加到 allowlist 正则 |

**Soong 缓存陷阱**：改了 ART 源码但只跑 `m systemimage` 时，产物可能不重编。确认方法：

```bash
rm -rf out/soong/.intermediates/art/runtime/libart-runtime
rm -rf out/soong/.intermediates/packages/apps/R0dumpManager
```

刷机前检查 `out/target/product/hima/system/apex/com.android.art.capex` 的时间戳。

## 4. 刷机

```bash
fastboot flash boot    boot.img
fastboot flash dtbo    dtbo.img
fastboot flash vbmeta  vbmeta.img
fastboot flash super   super.img
```

**第一次刷必须格式化 /data**：ColorOS 的 /data 是加密的，LineageOS 的 init 读不了，会卡在 `init_user0_failed`。进 Lineage Recovery（内嵌在 `boot.img` 里）执行格式化。

本机**没有公开的 TWRP**（三个 TWRP 设备树都没有发布产物），可用的第三方 recovery 只有 AndyCGYan 的 Lineage Recovery 19.1，但它解不了 A13/A16 的 userdata，只适合做 raw `dd` 恢复。

### 救砖 / 恢复原厂

刷机前必须备份分区。user 版本的 adbd 拒绝 `adb root`，fastboot 又读不了分区，所以走 Magisk 授权的 `su`：

```bash
adb shell "su -c 'dd if=/dev/block/by-name/super'" > super.img
```

恢复时直接 `fastboot flash super`，或进 recovery 用 dd 写回。`adb sideload` 不能用于裸镜像。

需要备份的分区：`super`（11.19 GB）、`boot`、`recovery`、`dtbo`、`vbmeta*`、`persist`、`modem`/`modemst*`、`fsg`、`fsc`、`splash`。

## 5. 硬件对应关系（定制 recovery 时要用）

| 部件 | 值 |
|---|---|
| 触摸 | Samsung S6SY792（`sec-s6sy792`，input name `touchpanel`） |
| 音频 | `kona-mtp-snd-card` |
| 相机 | CAMX 2.4，sensor id `0x615`，OPPO 自定义 eeprom `com.qti.eeprom.truly_cmb433.so` |
| 指纹 | `fingerprint@2.1` |
| 传感器 | QTI multihal + `oplusSensor` |
| 分区 | 非 A/B，`super` 11,190,403,072 B，group `qti_dynamic_partitions` |

## 6. r0dump 脱壳移植

见 [`docs/R0DUMP-PORT.md`](R0DUMP-PORT.md)。补丁在 `patches/`，按仓库一个文件。
