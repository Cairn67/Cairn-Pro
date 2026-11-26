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

