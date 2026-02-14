# 回滚指南（恢复原厂 boot）

- 日期: 2026-02-14
- 执行者: Codex

## 1. 适用场景

- Root 后需要恢复原厂 boot
- Magisk 导致异常，需排除 root 影响

## 2. 准备文件

- 原厂 `boot.img`（必须与当前系统版本一致）
- 示例文件:
  - `_root_work/boot.img`

## 3. 刷写步骤（本机非 A/B）

```powershell
adb reboot bootloader
fastboot flash boot _root_work\boot.img
fastboot reboot
```

## 4. 验证回滚

```powershell
adb shell su -v
adb shell "su -c id"
```

预期:

- `su` 不再可用或无法获取 `uid=0`

## 5. 注意事项

- 回滚同样要求版本严格匹配。
- 不匹配的 boot 可能导致卡开机或异常重启。

