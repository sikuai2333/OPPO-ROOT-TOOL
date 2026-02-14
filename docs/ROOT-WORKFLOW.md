# Root 操作流程（PDRM00 / ColorOS 13.1.0.201）

- 日期: 2026-02-14
- 执行者: Codex

## 1. 前置条件

- 手机已开启开发者模式与 USB 调试
- 已完成深度测试并解锁引导
- PC 已有 `adb` 和 `fastboot`
- Magisk App 已安装

## 2. 基础连通性检查

```powershell
adb devices
adb reboot bootloader
fastboot devices
fastboot getvar unlocked
```

预期:

- `adb devices` 能看到设备
- `fastboot devices` 有序列号
- `unlocked: yes`

## 3. 版本一致性检查（必须）

手机侧:

```powershell
adb shell getprop ro.build.display.id
adb shell getprop ro.build.version.ota
adb shell getprop ro.build.fingerprint
```

OTA 包侧:

```powershell
tar -xOf <ota.zip> META-INF/com/android/metadata
```

必须同时匹配:

- `version_name`
- `ota_version`
- `post-build`

## 4. 提取并准备 boot 镜像

从 OTA 直接提取:

```powershell
tar -xf <ota.zip> -C _root_work boot.img
```

推送到手机:

```powershell
adb push _root_work\boot.img /sdcard/Download/boot_PDRM00_13.1.0.201_stock.img
```

## 5. Magisk 修补

手机操作:

1. 打开 Magisk
2. 安装 -> 选择并修补一个文件
3. 选择 `Download/boot_PDRM00_13.1.0.201_stock.img`
4. 生成 `magisk_patched-*.img`

拉回电脑:

```powershell
adb pull /sdcard/Download/magisk_patched-*.img _root_work\
```

## 6. 刷写补丁镜像

先确认分区类型:

- 非 A/B 机型: 刷 `boot`
- A/B 机型: 刷 `boot_a` 与 `boot_b`

本机是非 A/B，命令如下:

```powershell
adb reboot bootloader
fastboot flash boot _root_work\magisk_patched-xxxx.img
fastboot reboot
```

## 7. Root 验证

```powershell
adb shell su -v
adb shell su -V
adb shell "su -c id"
```

预期:

- `su -v` 返回 `MAGISKSU`
- `su -c id` 返回 `uid=0(root)`

