# r0dump 脱壳移植记录（PDRM00 / LineageOS 23.2）

把 [tiwe0/r0dump](https://github.com/tiwe0/r0dump) 的 `v2026-09-12-fusion` 补丁集移植到本机，并修掉上游没有覆盖的部分。

## 1. 为什么选 r0dump

FART / Youpk 都绑死在 ART 7.1–8.0，依赖三个在 Android 13 之后失效的机制：

- `ArtMethod::Invoke` 里用「同线程 + 假调用标志」强制进 switch 解释器 → ART 9 之后 nterp 成为默认，这条路没了；
- switch 解释器 `PREAMBLE` 逐指令插桩 → 解释器实现已换；
- `dex2oat` 用 `kVerifyAtRuntime` 过滤 → A13 起拒绝给不可调试包用非 speed 过滤。

r0dump 是为 A16 重做的：43 个捕获点、class loader 遍历 + 主动调用、设备端 DEX 重建（重算 Adler-32 / SHA-1）、带 UI 的 Manager，且**补丁按可移植方式发布**。

## 2. 上游缺失、需要自己补的两块

补丁集在 `android-16.0.0_r4` 基线上 `git am --3way` 零冲突，但 fusion 版本**不含任何 `.java` 文件**。

### 2.1 `dalvik/system/r0dump_Runtime.java`

ART 侧 `art/runtime/native/dalvik_system_r0dump_Runtime.cc` 有：

```cpp
REGISTER_NATIVE_METHODS("dalvik/system/r0dump_Runtime")
```

但对应 Java 类不存在。方法签名要从文件末尾的 `gNatives[]`（JNINativeMethod 列表）和 `frameworks/base` 的调用点反推。注意 `r0dump_configure` 是 **public Java 方法**，内部包 `r0dump_configureNative`（37 个参数），且只在 native 成功后发布 `r0dump_capturePointMask` —— 补丁自带的 python 测试会断言这个顺序。

### 2.2 libcore 的捕获点钩子

`BaseDexClassLoader.r0dump_GetDexCookies` / `r0dump_GetDexElementCount`、`DexPathList.Object[] r0dump_GetDexCookies(int firstElement)`、`DexFile.r0dump_GetCookie()` 返回 `mCookie`、`DexPathList.Element.r0dump_GetDexCookie()`。要求写在补丁的 python 测试里（`r0dump_check_base_dex_class_loader`、`r0dump_check_dex_path_list`）。

## 3. 捕获点序数是 C++ 枚举值

不是 1/2/3：

```cpp
kBdclPathConstructor       = 32
kBdclMemoryConstructor     = 33
kBdclFindClassSuccess      = 34
kBdclAddDexPathReturn      = 35
kDexFileOpenFileReturn     = 36
kDexFileOpenMemoryReturn   = 37
kDexFileBeforeDefine       = 38
```

定义在 `art/runtime/r0dump/r0dump_hook_point.h`。

## 4. API stub 陷阱（最容易卡住的地方）

`frameworks/base` 编译时报 `cannot find symbol: dalvik.system.r0dump_Runtime`。

原因：framework 用 `sdk_version: "core_platform"` 编译，stub 来自**预编译快照** `prebuilts/module_sdk/art/current`，不会从源码重新生成。所以给新类加 `@SystemApi(client = MODULE_LIBRARIES)` 无效；给 framework 加 `core-libart` 依赖又造成循环依赖（core-libart 依赖 framework）。

解法是反射桥 `frameworks/base/core/java/android/app/r0dump_Bridge.java`，把三个 `r0dump_*.java` 的二十个静态调用点全部改走它。反射会被 hiddenapi 拦，所以还要在 ART 开豁免：

```cpp
// art/runtime/hidden_api.cc
static const std::vector<std::string> kCorePlatformApiExemptions = {
    "Ldalvik/system/VMDebug;->setWaitingForDebugger",
    // r0dump-fusion: the capture bridge lives in dalvik.system
    "Ldalvik/system/r0dump_Runtime;",
};
```

另外 `libcore/non_openjdk_java_files.bp` 里新文件必须列进 **`non_openjdk_java_files` 组**，只加 javadoc 组不参与编译。

## 5. 补丁清单

| 文件 | 仓库 | 内容 |
|---|---|---|
| `patches/01-art.patch` | `art` | hiddenapi 豁免、codeitem 范围旁路、去重键内容寻址、三层预算上限、records.jsonl |
| `patches/02-frameworks-base.patch` | `frameworks/base` | 反射桥、三个 r0dump_*.java、AMS 文件准备校验 |
| `patches/03-system-sepolicy.patch` | `system/sepolicy` | sdcardfs 访问规则 |
| `patches/04-packages-apps-R0dumpManager.patch` | `packages/apps/R0dumpManager` | 导出 sdcardfs 兼容、records 合并、进度上报、预算对齐 |
| `patches/05-libcore.patch` | `libcore` | `r0dump_Runtime.java` + 三个捕获点钩子 |

应用方式（在各自仓库根目录）：

```bash
git apply --3way /path/to/patches/01-art.patch
```

## 6. 函数抽取：从 0 到 50,572 个 codeitem

换到抽取壳样本后，类加载一万七千多个但 `codeitems=0`。三个叠加因素：

1. `force_max_calls` 默认 1，主动调用次数太少；
2. `capture_point_mask` 没包含 codeitem 点位（掩码是位图，且序数是枚举值）；
3. **ART 侧 `kR0dumpMaxRecordsHardLimit = 4096`** —— 真正的根因。

日志线索是：

```text
W R0dumpFusion: event=config_rejected status=invalid
  site=AT.bind.configure object_kind=run field=budgets
```

`site=AT.bind.configure` 说明拒绝发生在 ART 层，Java/Manager 层的校验改了也没用。

```cpp
// art/runtime/r0dump/r0dump_config.h
inline constexpr uint32_t kR0dumpMaxRecordsHardLimit      = 262144u;   // 原 4096
inline constexpr uint32_t kR0dumpMaxArtifactsHardLimit    = 16384u;    // 原 512
inline constexpr uint64_t kR0dumpMaxTotalBytesHardLimit   = 2048ull * 1024 * 1024;
inline constexpr uint64_t kR0dumpBufferPoolBytesHardLimit = 1024ull * 1024 * 1024;
```

改完结果：

```text
codeitem: 50728
dex:      89
records:  265782
methods:  3
total:    316609
```

抽检 32 个 `.codeitem.raw`，CodeItem 头部与操作码全部合法（`registers_size=12`、`ins_size=5`、`tries_size=3`、`insns_size=66`，操作码 `0x6e`/`0x52`/`0x34`），零空壳零损坏。

### codeitem 被搬出 DEX 数据段的情况

部分壳把方法体搬到 dex 映射之外，指针可读但相对 `DexFile` 数据区的偏移无效。`r0dump_GetCodeItemSpan()` 加了可读性旁路，此时 `code_offset` 置 0（相对偏移无意义）。

## 7. sdcardfs / FUSE 兼容

r0dump 按 FUSE 语义编写，而本机 `Android/data` 这一层是 sdcardfs：

```text
/dev/fuse    on /mnt/user/0/emulated     # FUSE
/dev/fuse    on /storage/emulated        # FUSE
/data/media  on .../Android/data         # sdcardfs（只有这一层）
```

FUSE 由守护进程代理、属主统一；sdcardfs 由内核按调用者 uid 判定。共七个问题：

| # | 现象 | 修法 |
|---|---|---|
| 1 | SELinux 拒绝读 sdcardfs | sepolicy 加 `sdcardfs:dir/file` 规则 |
| 2 | `fchown`/`fchmod` EPERM | `IsSdcardfsFd()` 后跳过 |
| 3 | root 打不开 app 私有目录 | `setfsuid()` 切到 app uid |
| 4 | root 建目录归属错误 | `MkdiratAsUid()`，4 处全改 |
| 5 | `Message too long` | `LocalSocket.setReceiveBufferSize(64KB)` |
| 6 | `copy_source_failed` | 读用 app uid、写用 helper uid，RAII 作用域切换 |
| 7 | `prepared_directory_fd` 校验失败 | ART 侧加 sdcardfs 旁路 |

第 7 条需要说明：ART 要求目录 `gid=1078`、`mode=02770`，而 sdcardfs 只能给 `gid=3452`、`mode=02771`（mode 由挂载选项合成，永远对不上）。但这个 fd 是 helper 建好、经 socket 传给 system_server 再传给目标进程的，来源已过 `r0dump_prepareOutput` 的完整校验（uid/pid/包名/generation）。ART 侧复检是冗余的，因此放行——**不是删校验，是加旁路**。

## 8. 导出：26 万文件 → 3 万

每条观测一个 JSON 文件，实测打包出 265,782 个 1.2 KB 小文件，在 sdcardfs 上逐个 `create+write+fsync` 要十几分钟。

改为 ART 侧每次 flush 追加到一个 `records.jsonl`，导出侧把 `records/` 目录折叠成单个文件（`disable_recursion_pending()` 阻止下钻），读取时先试新格式再回退旧格式。

```text
codeitem: 30884
records.jsonl: 1
total:    30990        # 原 316,609，减少 90%
```

> 踩过的坑：`r0dump_SerializeRecord()` 结尾已经是 `json.append("}\n")`，拼接后再 `push_back('\n')` 会让每行多一个空行，行数正好是唯一 `seq` 数的两倍。判断方法：`lines` 与 `sort -u` 后的 `unique` 是否相等。

导出四阶段进度写在 `/storage/emulated/0/Downloads/r0dump-export-progress.json`。

## 9. 用 MCP 桥驱动（不用点屏幕）

Manager 内置一个 MCP（JSON-RPC 2.0）服务，走抽象命名空间 unix socket `@r0dump_mcp`，设备上有对应的桥接二进制：

```bash
# 等同打开 UI 里的 MCP 开关
adb shell am start-foreground-service -n com.android.r0dumpmanager/.mcp.McpBridgeService

adb push req.jsonl /data/local/tmp/
adb shell "r0dump_mcp_bridge < /data/local/tmp/req.jsonl"
```

可用工具：`read_status`、`list_apps`、`set_config`、`enable`、`disable`、`launch_app`、`stop_app`、`recover`、`repair_export`。

## 10. 能力边界

| 壳类型 | 能否自动还原 |
|---|---|
| 整壳（DEX 加密） | 能 |
| 抽取壳（codeitem 抽取） | 能，需主动调用触发回填 |
| VMP | **不能**，只能识别与统计缺口 |
| DEX2C | **不能**，只能识别与统计缺口 |

## 11. 刷入 update 时的两个注意点

- `m systemimage` **不会**重建 `super.img`。改了 system 内容后必须再跑 `m superimage`，否则刷进去的还是旧镜像。
- ART 是 **preload apex**（首阶段从 `/dev/block/dm-*` 挂载），`adb remount` 改 `/system/apex/com.android.art.capex` 不生效，必须整包刷 `super`。

参考：[tiwe0/r0dump](https://github.com/tiwe0/r0dump)
