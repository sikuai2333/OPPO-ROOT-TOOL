# 故障排查

- 日期: 2026-02-14
- 执行者: Codex

## 1. `adb` 看到的是错误设备

现象:

- `adb devices -l` 显示了其他手机

处理:

```powershell
adb disconnect <ip:port>
adb kill-server
adb start-server
adb devices -l
```

只在显示 `PDRM00` 后继续操作。

## 2. fastboot 下 `boot_a/boot_b` 不存在

现象:

- `fastboot flash boot_a ...` 报 `No such partition`

原因:

- 当前机型是非 A/B 分区

处理:

```powershell
fastboot flash boot <patched.img>
```

## 3. Magisk 修补后找不到 `magisk_patched*.img`

建议检查路径:

- `/sdcard/Download/`

快速命令:

```powershell
adb shell "ls -l /sdcard/Download | grep -i magisk"
```

## 4. 刷完无法拿到 Root

检查顺序:

1. 镜像来源版本是否与当前系统完全一致
2. 是否刷到了正确分区（本机为 `boot`）
3. 是否成功开机并安装了正确 Magisk 版本

验证命令:

```powershell
adb shell su -v
adb shell "su -c id"
```

## 5. OTA 下载失败或速度很慢

可选方案:

- 使用分片下载脚本并支持断点续传
- 下载后必须校验 MD5 再继续

