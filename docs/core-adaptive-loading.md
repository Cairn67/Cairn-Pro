# 自适应 core 装载（1.0.2）

## 问题

1.0.1 及以前，一个 APP 只能配一个内核镜像。原因是 `jni/core_blob.S` 用 `.incbin "detached_core.cld"`
把**唯一一份** core 静态链进 `libcairn.so`，而 core 的字节内容随内核变化（hook 点的被覆盖指令字、
prctl 帧布局、结构体偏移都是编译期常量）。内核侧 bootstrap 又用烧死的 `cl_digest` 校验 core，
所以换内核必须重新构建 APP、重新签名、重新分发。

## 1.0.2 的改动

APP 不再把 core 当作唯一来源，改为**运行时候选 + 内核裁决**：

```
selfTestAuto()
  ├─ <filesDir>/core.cld              ← 优先
  ├─ <getExternalFilesDir()>/core.cld
  ├─ /data/adb/cairn/core.cld
  └─ 内置 blob（spawnRoot，无 core 路径时）
```

每个候选走一次 `spawnRootCore`，内核 loader 用自己烧死的 `cl_digest` 校验：

- 校验通过 → 记录该路径为已接受 core，后续调用直接复用；
- `-22` / `-74` → 该 core 不属于本内核，试下一个；
- `-13` → 密钥不对，与 core 无关，立即停止（换 core 也没用）。

**信任边界没有降低**：APP 只是多递几份候选，最终是否接受仍由内核的 digest 校验决定。
APP 侧 `cairn_ensure_core` 依然只检查容器头（magic/abi/profile/bytes），从不校验 digest。

## 实现位置

| 层 | 文件 | 改动 |
|---|---|---|
| JNI | `jni/jni_bridge.c` | 新增 `Java_..._spawnRootCore`，多传一个 core 路径 |
| native | `jni/cairn.c` | `cairn_spawn_root` 增加 `core_path`；子进程优先读外部 core，失败才回退内置 blob；输出 `CAIRN_CORE_SOURCE=external\|bundled` |
| Java | `Native.java` | 声明 `spawnRootCore` |
| Java | `CairnCore.java` | `runRoot(...)` 增加 corePath 重载；新增 `coreCandidates()` / `selfTestAuto()` / `acceptedCorePath()` / `resetCoreSelection()` |
| Java | `MainActivity.java` | 自检改用 `selfTestAuto`；日志记录选中的 core；密钥变更时重置选择；版本号不再强制等于内核版本 |

## 使用方式

换内核后，不再需要重编 APP。把修补器为该内核产出的 `core.cld` 放到：

```
/data/data/com.cairn.manager/files/core.cld
```

或（需要 MANAGE_EXTERNAL_STORAGE 权限，APP 已声明）：

```
/sdcard/Android/data/com.cairn.manager/files/core.cld
```

或全局：

```
/data/adb/cairn/core.cld
```

APP 下次自检会自动识别并选中匹配的那一份。

## 仍然存在的限制

- **核心本体仍未通用化**。core 的指令字、帧布局、结构偏移仍是编译期常量，所以不同内核仍需各自的
  `core.cld`。本次改动解决的是「不用重编 APP」，不是「一份 core 通吃所有内核」。
- 内置 blob 仍然存在，用于没有外部 core 时的首次引导与恢复；它只匹配构建时对应的那一个内核。
- 多候选探测会增加一次 fork 开销，仅在首次识别时发生。

## 后续（未实现）

真正的「一份 core 通吃」需要把以下内容从编译期 `-D` 改为 cld 容器内的参数表，由 loader 在
内存可写阶段回填：

- `CAIRN_REPLAY_*`（17 处被覆盖指令字）
- `CAIRN_PRCTL_REPLAY` / `_LOCALS` / `_FRAME_DEPTH` / `_FP_OFFSET`
- `CA_*` 结构偏移（12 个宏，22 处引用）

这是中等规模重构，未在 1.0.2 中实施。