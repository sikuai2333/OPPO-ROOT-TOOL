# unpackfix — 脱壳产物审计与重打包

纯 Python，无第三方依赖（只需标准库）。

## 工具

| 文件 | 作用 |
|---|---|
| `dexlib.py` | DEX 解析器，逐方法分类 code / native / abstract / empty |
| `heuristics.py` | 区分「壳抽取」与「正常 JNI」的判据 + 15 家壳的特征库 |
| `audit.py` | 输出 JSON 报告 + 人读摘要 |
| `axml.py` | Android 二进制 XML（AXML）解析与重写 |
| `repack.py` | 入口 Application 修复 + 壳文件清理 + 重打包 |

## 用法

### 审计一次脱壳产物

```bash
python audit.py <run目录或dex目录> --apk <原始APK> --json audit.json
```

输出：

```text
DEX files (deduped): 18
methods: 332707   coded: 306299 (92.06%)   native: 3885 (1.17%)   empty: 0 (0.0%)
structural: OK

extracted methods (high confidence): 46 classes
  me.minetsh.imaging.IMGGalleryActivity      2 native  onCreate,onRequestPermissionsResult
  ...

shell residue classes:
  360: 71
shell files in APK: 2
  assets/libjiagu.so (360)
```

### 修复入口并重打包

```bash
python repack.py \
  --dex-dir <解包后的run目录> \
  --manifest <captured AndroidManifest.xml> \
  --base-apk <原始APK> \
  --out fixed.apk \
  --dry-run          # 先看计划
```

会做三件事：

1. 从 DEX 里找真实的 Application 子类（按覆盖的生命周期钩子数排序，排除壳类）
2. 重写 AndroidManifest 里的 `android:name`，把壳代理换掉
3. 丢弃壳的 so / assets

`--keep-shell-libs` 保留壳 so——**当被抽取的方法仍需要 native 运行时必须加这个**。

## 判据说明

`classify_native()` 返回 `(kind, confidence, reason)`：

| kind | 含义 | 判据 |
|---|---|---|
| `shell` | 壳自己的类 | 命名空间命中 `SHELL_CLASS_PREFIXES` |
| `extracted` / high | 极可能被壳抽走 | 生命周期方法（onCreate 等）被 native 化 |
| `extracted` / low | 可能被抽走 | 混在 Java 类里的 native，且不符合 `nXxx`/`jni_Xxx` 命名 |
| `wrapper` | 正常 JNI | 全部方法 native，或属于框架/SDK 命名空间 |
| `normal` | 无可报告 | — |

## 已知边界

- **`empty` 与 `native` 是两件事**：`empty` 是 `code_off == 0` 且非 native 且非 abstract，才是真正的空桩。
- **native 方法的方法体不在 DEX 里**，本工具只能定位到「哪个方法被搬走了」，不能还原。
- **壳可能绕过 `RegisterNatives`**：实测 360 直接写 `ArtMethod.data_`，JNI 层注册钩子看不到业务方法。
- **入口选择是启发式的**：多个类继承 `Application` 时按生命周期覆盖度排序，可能需要在 `--real-app` 手动指定。
