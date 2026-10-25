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

## 8.5 产物组织：让导出包能直接用

第一版导出只把捕获块原样搬进 ZIP，对整壳样本（如农业银行 `com.android.bankabc`）产出的是一堆 `process-*/artifacts/000002.dex.raw`：文件名带 `.raw`、嵌在四层目录里，报告还显示 `usable_outputs: 0`，看上去像「什么都没抓到」。实际那 14 个 DEX 全部有效（magic `dex\n`、声明大小与实际一致、adler32 通过），JADX 能解出 3.9 万个 Java 文件。

四个问题一起修：

| 问题 | 根因 | 修法 |
|---|---|---|
| 没有可直接用的 DEX | 产物按「记录→块」组织，整壳直捕没有逐方法记录 | 新增 `dex/` 目录，把通过头部校验的完整 DEX 以 `01-000002.dex` 输出 |
| `record_count: 0` | ART 写 `process/records/records.jsonl`，导出复制时拍平成 `process/records.jsonl`，加载器仍找原路径 | 复制不再拍平；加载器兼容旧的拍平格式 |
| `usable_outputs: 0` | 只统计 codeitem 回填，整壳样本天然为 0 | 同上的 dex/ 输出与 index 一起给出真实数量 |
| 没有入口 Activity | 从来没提取过 | AMS 用 `PackageManagerInternal.queryIntentActivities(MAIN+LAUNCHER, filterCallingUid=targetUid)` 解析，经导出请求带给 helper |

现在每个 run 旁边会多两个文件：

```text
<run-id>.README.md       版本、入口 Activity、DEX 清单、jadx 命令
<run-id>.dex-index.json  同一份信息的机器可读版
```

实测（农业银行 11.3.0）：

```text
- 版本: 11.3.0
- 入口 Activity: com.android.bankabc.LauncherActivity
- DEX 数量: 14
```

```text
$ sha256sum -c <run>.zip.sha256   →  OK
$ unzip -l <run>.zip | wc           →  69 files（含 14 个 dex/）
$ jadx -d out dex/                  →  29,072 个类，39,098 个 Java 文件
```

### 三个连环坑

1. **请求缓冲区按最大结构体开。** 接收路径原本用 `sizeof(Request)`（285 字节）做缓冲区，而导出请求扩到 628 字节后，尾部字段被截断——`version`/`label` 恰好落在 285 内所以正常，`launcher` 在偏移 372，永远读回空。改成取两者最大值。
2. **可选字段不能用严格读取器。** `ReadFixedString` 遇到空字段返回 false，会让整个导出失败；新增宽容的 `OptionalField`。
3. **`MATCH_DEFAULT_ONLY` 会漏掉 launcher。** launcher 的 intent-filter 只有 `CATEGORY_LAUNCHER` 没有 `CATEGORY_DEFAULT`，而且 `system_server` 调用 `queryIntentActivities` 时默认按自身的可见性过滤，看不到用户应用。改用内部 API 并显式传 `filterCallingUid = targetUid`。

## 9. 进度条：从假的 10% 到可观测

第一版只在进入复制阶段时写一次 `{"stage":"copying","percent":10}`。打包目标的复制要走过近十万个文件、跑上几十分钟，这个值全程不变——观感和卡死完全一样。

修复分两层。

### 9.1 进度必须带分母

```cpp
void r0dump_WriteExportProgress(const char *stage, int percent, uint64_t done,
                                uint64_t total);
```

- `copying`：先扫一遍源目录算出 `expected_files`，之后每 256 个文件上报一次
- `repairing`：`done` = 已完成进程数，`total` = 总进程数
- `archiving`：`done` = 已打包文件数，`total` = 总文件数

### 9.2 跨进程传递：复用导出 socket

helper 只接受 system uid 的连接（`peer.uid != AID_SYSTEM` 拒绝），Manager 既连不上它的 socket，也读不到 `/sdcard/Downloads` 下的进度文件。所以进度只能由 helper 沿导出连接送回来。

导出本来就是 SEQPACKET，天然分帧，加一个进度帧即可：

```cpp
struct ExportProgressFrame {
  uint32_t magic;   // 'R0EP'
  uint16_t version;
  uint16_t size;    // 60
  int32_t  percent;
  uint64_t done;
  uint64_t total;
  char     stage[32];
};
```

callback 通过 `ExportLatest(..., sink, context)` 一路传到 `CopyTree` / `CreateZip`，context 就是 client fd。

Manager 侧改为逐数据报读取、按 magic 分发，而不是假设「一次读满固定长度」。UI 显示「阶段 · 百分比 · done / total」+ 确定性进度条。

### 9.3 必须一起改的超时

`r0dump_FilePreparationBridge` 的 `EXPORT_TIMEOUT_MS` 原本是 5 分钟。它此前形同虚设——`setSoTimeout` 只对 `read()` 生效，而旧实现写完之后不读。改成流式读进度帧后它会**真的触发**，把 30 分钟的正常导出掐断。已改为 30 分钟。

### 9.4 验证

```text
{"stage":"copying","percent":10,"done":256,"total":98179,"pid":6027}
{"stage":"copying","percent":10,"done":2816,"total":98179,"pid":6027}   ← 24 秒后
...
{"stage":"archiving","percent":84,"done":23680,"total":98201}
```

界面上数字同步递增，阶段切换正确。

### 9.5 打包阶段的两个坑

**坑一：ZIP 条目数上限 65,535。** 打包目标有 98,201 个文件，两次导出都精确停在 `done=65472`（65472 + 63 对齐 = 65535）然后开始删 staging，观感上像死锁。根因在 `system/libziparchive`：

```cpp
// zip_writer.cc:483
// We haven't supported zip64 format yet.
```

修复是分卷：每卷最多 65,000 条目，超出时输出 `run-id.zip` + `run-id-part2.zip`，`.sha256` 文件列出所有分卷，`sha256sum -c` 一次校验全部。

**坑二：`fs::remove_all(staging)` 在 FUSE 上极慢。** 十万个小文件逐个 `unlink`，每个都要触发一次 `fuse_flush_times` 元数据回写。导出失败后的清理本身能跑十几分钟，把「失败」伪装成「卡住」。判断方法：

```bash
# 文件数在减少 = 在清理；完全不动 = 真死锁
find <staging> -type f | wc -l
cat /proc/<helper-pid>/task/*/stack | grep fuse_
```

看到 `fuse_unlink` 就是清理阶段，不是打包。

**坑三（顺带）：`export_busy` 清理。** 导出异常中断后 `g_export_in_progress` 留成 true，后续调用一直返回 `status=11`。需 root 重启 helper：

```bash
adb root && adb shell "stop r0dump_file_helper; start r0dump_file_helper"
```

## 10. 用 MCP 桥驱动（不用点屏幕）

Manager 内置一个 MCP（JSON-RPC 2.0）服务，走抽象命名空间 unix socket `@r0dump_mcp`，设备上有对应的桥接二进制：

```bash
# 等同打开 UI 里的 MCP 开关
adb shell am start-foreground-service -n com.android.r0dumpmanager/.mcp.McpBridgeService

adb push req.jsonl /data/local/tmp/
adb shell "r0dump_mcp_bridge < /data/local/tmp/req.jsonl"
```

可用工具：`read_status`、`list_apps`、`set_config`、`enable`、`disable`、`launch_app`、`stop_app`、`recover`、`repair_export`。

两个注意点：

- `launch_app` 会被 **BAL**（后台启动限制）拦住，必须先把 Manager 拉到前台再调用。
- 导出异常中断后 `g_export_in_progress` 会留成 true，之后一直返回 `status=11 export_busy`。用 root 重启 helper 清除：`adb root && adb shell "stop r0dump_file_helper; start r0dump_file_helper"`。

## 11. 能力边界

| 壳类型 | 能否自动还原 |
|---|---|
| 整壳（DEX 加密） | 能 |
| 抽取壳（codeitem 抽取） | 能，需主动调用触发回填 |
| VMP | **不能**，只能识别与统计缺口 |
| DEX2C | **不能**，只能识别与统计缺口 |

## 12. 刷入 update 时的两个注意点

- `m systemimage` **不会**重建 `super.img`。改了 system 内容后必须再跑 `m superimage`，否则刷进去的还是旧镜像。
- ART 是 **preload apex**（首阶段从 `/dev/block/dm-*` 挂载），`adb remount` 改 `/system/apex/com.android.art.capex` 不生效，必须整包刷 `super`。

参考：[tiwe0/r0dump](https://github.com/tiwe0/r0dump)

## 13. 2026-10-25 追加：360（libjiagu）实测结论

### 13.1 DEX 是完整的，缺的是方法体

对 `com.mubu.app`（360 加固，`assets/libjiagu_a64.so`）的捕获结果：

```text
DEX files (deduped): 18
methods: 332707   coded: 306299 (92.06%)   native: 3885 (1.17%)   empty: 0 (0.0%)
structural: OK
```

`empty: 0` 说明没有 NOP 空桩，所有 header 与 Adler32 校验通过。**问题不在 DEX 完整性**。

### 13.2 入口类是真的被改成了 native

```text
MubuSplashActivity: 51 methods
  onCreate  kind=virtual  access=0x0104  code_off=0  native=True
```

`0x0104` = `ACC_PROTECTED | ACC_NATIVE`。方法声明在，但没有 `code_off`。

> **解析陷阱**：`class_data_item` 里 direct_methods 和 virtual_methods 是**两个独立列表，各自从 0 开始做增量编码**。当成连续序列累加会得到完全错误的方法名，进而误判成「方法被剥离」。

### 13.3 360 不走 RegisterNatives

在 ART 的 `RegisterNatives` 里加钩子（`jni_internal.cc`），用 `dladdr` 反查模块和 RVA。放开日志限流（10 → 4096/秒）后单次冷启动共 **3,969 条**注册记录：

```text
2131  /system/lib64/libandroid_runtime.so
 862  /system/lib64/libhwui.so
 427  /apex/com.android.art/lib64/libart.so
 273  libopenjdk.so
  12  /data/data/com.mubu.app/.jiagu/libjiagu_64.so   ← 全部是 com.stub.StubApp.interfaceN
```

**没有任何业务类。** `MubuSplashActivity.onCreate` 一次都没出现。结论：360 绕过 JNI 层，直接写 `ArtMethod.data_`。

### 13.4 遍历 ArtMethod 的方案会崩

尝试遍历类表、对每个 native 方法 `dladdr(GetEntryPointFromJni())`，结果：

```text
Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x8
```

`ClassLinker::VisitClasses` 与 `Class::GetMethods` 之间存在竞态，会撞到无效的 ArtMethod 槽位。**该方案已回退，未包含在补丁中。**

### 13.5 能做到与做不到

| 能力 | 状态 |
|---|---|
| DEX 完整性审计 | ✅ `tools/unpackfix/audit.py` |
| 抽取方法识别（含置信度） | ✅ 46 高置信 + 5 低置信 |
| 壳特征识别与清理 | ✅ 71 个类 + 2 个 so |
| 入口 Application 还原 | ✅ `tools/unpackfix/repack.py` |
| native → so+RVA（走 RegisterNatives 的壳） | ✅ ART 钩子可用 |
| native → so+RVA（直接写 ArtMethod 的壳，如 360） | ❌ 需要换落点 |
| 方法体还原成 Java | ❌ 无通用解 |

**「自动回填成可运行 APK」的瓶颈不在回填，在过签。** 现代壳的签名校验几乎全在 native，只能逐样本手工定位；而壳清理 + 入口还原 + DEX 审计这三项是可以全自动的。
