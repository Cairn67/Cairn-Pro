# Cairn Pro

从精简的内核镜像出发,为 Android 提供内核级访问控制。

Cairn Pro 会修补原厂 `boot.img`,使一段自带的内核态载荷在内核中运行。该载荷监视
`execve` 对 `/system/bin/su` 的调用,查询内核内存中的 UID 白名单,并且**只对已授权
的调用方**改写凭据、重定向到守护进程。其余情况保持内核原本行为。

> **本项目天然是两用性质,可能损坏你的设备。** 它会改写 boot 镜像并装入内核代码。
> 构建错误可能导致设备无法启动,或需要整机重刷。动手前请先读
> [docs/LEGAL.md](docs/LEGAL.md);安全问题或滥用问询请看 [SECURITY.md](SECURITY.md)。

---

## 它做什么

| 能力 | 位置 |
|---|---|
| 在内核镜像中原位植入 hook | `source/kernel-builder/` |
| `execve` 拦截与内核内存 UID 白名单 | `source/kernel-builder/payload*.S` |
| 特权操作分发(`prctl` 私有 op) | `source/kernel-builder/payload*.S` |
| 按应用 UID 生效的路径可见性规则 | `source/kernel-builder/path_*`、`source/visibility/` |
| 设备端守护进程与模块生命周期 | `source/daemon/` |
| Android 管理器应用 | `source/app/` |

设计说明见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md);具体技术决策的理由(为何
帧布局由目标推导、为何白名单 fail-closed、为何某个内核系列会被直接拒绝)汇总在
[docs/DESIGN-NOTES.md](docs/DESIGN-NOTES.md)。

## 授权是分层的

本仓库分发的不止一份代码,它们**并不共用同一个许可**:

| 部分 | 许可 | 原因 |
|---|---|---|
| `source/app/`、`source/daemon/`、`source/visibility/` | GPL-3.0-only | 用户态程序 |
| `source/kernel-builder/src/`、`loader/load-core.c` | GPL-3.0-only | 用户态构建工具 |
| `source/kernel-builder/` 内核侧文件(`payload*.S`、`access_*`、`path_*`、`loader/*` 中 `load-core.c` 除外) | **GPL-2.0-only** | 该代码与 Linux 处于同一作品边界,而 Linux 为 GPL-2.0-only |

每个文件都带 `SPDX-License-Identifier` 头,以该文件自己的声明为准。许可原文收集在
[LICENSES/](LICENSES/),根目录 [LICENSE](LICENSE) 覆盖仓库整体。第三方组件及其条款见
[docs/THIRD-PARTY.md](docs/THIRD-PARTY.md)。

## 构建
见 [docs/BUILDING.md](docs/BUILDING.md)。简述:需要与机型匹配的原厂 `boot.img`、
Android SDK,以及 Java/Kotlin 工具链。提供的脚本不会在构建时下载任何东西,也不会
签名产物。
## 成品下载 (v1.0.2)
预构建产物发布在 [GitHub Releases](https://github.com/Cairn67/Cairn-Pro/releases)。
**不发布任何内核/boot 镜像成品** —— 请用你自己的原厂 `boot.img` 为自己的设备构建。
| 产物 | 说明 | SHA-256 |
|---|---|---|
| `cairn-kernel-builder-linux-arm64` | 动态修补器二进制(AArch64 Linux)。指向原厂 `boot.img`,自动识别内核版本、从镜像自身推导所有 hook 点,输出修补后镜像与配套的零密钥 `core.cld` | `4c1389002dde8b89fd8ff247a8683c2974bcc5dd971356e4a50ecbcefaac12fd` |
| `core-V1.0.1.cld` | 内核载荷分离形态(64 KiB + 48 字节头)。仅与 bootstrap 记录摘要为 `be32ec9b…` 的镜像配对;不匹配即加载失败(fail-closed,设计如此) | `d2d05a42f006a4e597770ae77394dd65c6ac60ff60d3a14577d682f6c720d8c3` |
| `Cairn-Pro-V1.0.2-adaptive-core.apk` | 管理器 APP(versionName 1.0.2)。不再绑定单一内核:自检时把它能找到的每个 `core.cld` 都交给内核加载器,保留内核接受的那一份。与上述 Turbo 6 17.0.0.100 镜像配套;换别的内核只要把该内核自己的 `core.cld` 放到 APP 旁边即可 | `bd3df859a6b3c40037f75f8acf9fbb9028848d28dd7ab4e2878889d8c527c9a5` |

### 1.0.2 改了什么 —— 不再「一个内核编一个 APP」
1.0.2 以前,换内核就得重新构建、重新签名、重新分发 APP,因为 APP 在构建期把唯一一份
`core.cld` 链进了 `libcairn.so`。内核载荷本身仍然是内核相关的(hook 指令字、prctl 帧布局、
结构体偏移都是编译期常量),但 **APP 不再是**。自检时它按顺序尝试:

```
/data/data/com.cairn.manager/files/core.cld
/sdcard/Android/data/com.cairn.manager/files/core.cld
/data/adb/cairn/core.cld
内置 blob(兜底)
```

每份候选都交给内核加载器,由内核用它自己钉住的摘要决定收或不收。属于别的内核的 core
仍然和以前一样被 `-22` 拒绝——APP 只是换下一份再试。**信任边界没有变化**:APP 只负责
递交候选,裁决权始终在内核。

换内核的用法:用修补器处理该机型的原厂 `boot.img`,把产出的 `core.cld` 放到上述任一
路径,APP 下次自检会自动选中。详见 [docs/core-adaptive-loading.md](docs/core-adaptive-loading.md)。

### 兼容性警告 —— 使用修补器前必读

动态修补器**处于实验阶段,非常不稳定**,仅支持少数机型,不是"一个二进制通吃所有
手机"的通用工具:

- 目前只在 **一加 Turbo 6 (PLU110)、ColorOS 17.0.0.100**(内核
  `6.6.118-android15-8-g9bc34d5b0c79-abogki537459655-4k`)上做过端到端验证
  (QEMU 启动 + 载荷加载)。
- 工具**没有"支持机型数据库"**。对其他手机,它可能直接拒绝该内核(这是有意的
  fail-closed 行为),也可能——如果结构碰巧能被启发式识别——产出一个从未在真机
  上测试过的镜像。**两种结果一律按"不支持"对待**;对未验证机型,即便工具接受了
  也不要刷入,除非你自担全部后果。
- 内核系列被拒绝是设计行为:当某个系列无法用稳定的结构规则描述时(例如 6.12),
  工具选择拒绝而不是猜测。
- 每个输出目录的 `verification-status.json` 都带有 `posture: experimental-qemu-only`
  与 `real_device_validated: false` 标记,直到有人在真实硬件上完成测试为止。
- 输出目录当前硬编码为 `/storage/emulated/0/OPPO/适配`(作者自己的设备路径);
  需要交互式终端,暂不支持脚本化调用。

修补器**不重签 AVB**。在启用校验的锁定 bootloader 上,修补后的镜像无法启动;
解锁 BL 是前提,降级回滚则是另一回事,与此无关。
## 本仓库不包含什么

发布树只有源码与许可,刻意排除了:

- 机型专用的 boot 镜像,以及任何内嵌认证材料的构建产物;
- 明文密钥、签名 keystore,或任何携带用户凭据的内容;
- 上游项目的源码树 —— 只保留其许可原文并给出原始链接(见
  [docs/THIRD-PARTY.md](docs/THIRD-PARTY.md))。

如果你在找一个能直接刷的镜像:这里没有。请用你自己的厂商镜像、为你的设备构建。

## 支持与贡献
- 设计问题:开 issue。
- 安全或滥用问询:见 [SECURITY.md](SECURITY.md)。
- 参与开发:见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## AI 辅助声明
本仓库部分内容在作者的交互式指导下借助 AI 工具完成,包括文档、构建工具链,以及
应用与修补器的部分代码。所有发布内容均经作者审阅与测试,责任由作者承担。

