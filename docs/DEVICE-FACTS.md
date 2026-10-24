# 设备与分区速查（PDRM00 / OP4EA7）

## 硬件

| 项 | 值 |
|---|---|
| 型号 | PDRM00（OPPO Reno5 Pro+） |
| 项目代号 | OP4EA7 / 20135 |
| 设备树代号 | hima（Find X3 Neo 同硬件） |
| SoC | Qualcomm SM8250（kona），ARM64 + ARM32 |
| 内存 | 8 GB |
| 屏幕 | 1080 × 2400 OLED，480 dpi |
| 存储 | UFS |
| Bootloader | 已解锁（`fastboot getvar unlocked: yes`） |

> 不要用 `ro.boot.flash.locked` 或 `sys.oem_unlock_allowed` 判断锁状态，ColorOS 上这两个值和 fastboot 不一致。

## 分区布局（非 A/B，动态分区）

物理分区（`/dev/block/by-name/`）：

| 分区 | 设备节点 | 大小 |
|---|---|---|
| `super` | sda10 | 11,190,403,072 B |
| `userdata` | sda11 | — |
| `boot` | sde11 | 96 MB |
| `recovery` | sde26 | 128 MB |
| `dtbo` | sde19 | 24 MB |
| `vbmeta` | sde18 | 64 KB |
| `vbmeta_system` | sde16 | 64 KB |
| `vbmeta_vendor` | sde17 | 64 KB |

`super` 内逻辑分区：`system`、`vendor`、`product`、`odm`、`system_ext`，外加 OPPO 的 10 个 `my_*` 分区。group `qti_dynamic_partitions` 上限 11,186,208,768 B。

## 硬件对应关系

| 部件 | 值 |
|---|---|
| 触摸 | Samsung S6SY792（`sec-s6sy792`，input name `touchpanel`） |
| 音频 | `kona-mtp-snd-card` |
| 相机 | CAMX 2.4，sensor id `0x615`，eeprom `com.qti.eeprom.truly_cmb433.so` |
| 指纹 | `fingerprint@2.1` |
| 传感器 | QTI multihal + `oplusSensor` |
| 面板 | 内核 DTS `dsi-panel-*`，`display-coords = <1080 2400>` |

## 原厂系统参考

- ColorOS 13.1.0.201(CN01B60P01) / Android 13 / `TP1A.220905.001`
- 内核 `4.19.157-perf+`，Qualcomm OPPO 构建（clang 10.0.7）
- vendor security patch 2025-12-01，baseband `Q_V1_P14`
- SELinux Enforcing，`ro.debuggable=0`

OTA 获取方式见 [`OTA-DOWNLOAD.md`](OTA-DOWNLOAD.md)，走 Oplus 官方查询接口。

## 备份与恢复

刷机前用 Magisk 授权的 `su` 做原始分区备份：

```bash
adb shell "su -c 'dd if=/dev/block/by-name/super  bs=4M'" > super.img
adb shell "su -c 'dd if=/dev/block/by-name/persist bs=4M'" > persist.img
```

必备份：`super`、`boot`、`recovery`、`dtbo`、`vbmeta*`、`persist`、`modem`/`modemst1`/`modemst2`、`fsg`、`fsc`、`splash`。

恢复直接 `fastboot flash <part> <img>`。`adb sideload` 不接受裸镜像，只吃签名 OTA zip。

## Recovery 现状

**本机没有公开可用的 TWRP / OrangeFox / PitchBlack 产物。** 三个 TWRP 设备树都只发布过源码：

- `pomelohan/twrp_device_oppo_OP4EA7`（android-11）
- `youngguo18/twrp_device_oppo_OP4EA7`
- `infinixs5lite/twrp_device_oppo_reno5pro_OP4EA7`

唯一可用的第三方 recovery 是 AndyCGYan 的 Lineage Recovery 19.1（基于 ColorOS 12.1 C.16），解不了 A13/A16 userdata，但能提供 root adb shell 做 raw `dd`。

LineageOS 23.2 的 `boot.img` 内嵌 Lineage Recovery，`fastboot boot boot.img` 可进，日常够用。
