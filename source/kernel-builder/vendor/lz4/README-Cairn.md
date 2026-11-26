# lz4/lz4 — 第三方源码说明

## 引入原因

部分厂商 boot 镜像以 LZ4 legacy 流(`lz4 -l`)压缩存放内核。修补器必须先解出 ARM64
Image 才能分析结构、定位站点;打补丁后又必须按原格式回压,否则产出的不是设备期望的
boot 镜像。

**本目录不实现 LZ4 算法。** 压缩与解压全部由上游参考实现完成:`cairn_lz4.c` 只是
一层薄封装,负责 legacy 容器格式(魔数 `0x184C2102`、每块 4 字节小端长度前缀、
块大小)的分帧。

这样做的理由是可靠性:设备加载器用的是参考解码器,因此产出的流必须能被参考解码器
接受。项目早期曾尝试手写压缩器,自解码通过但被 `lz4` 命令行工具拒绝
(`Error 54 : Decoding Failed`),此后改为直接使用上游实现,并保留一条交叉验证测试
(`reference_lz4_accepts_what_we_emit`),要求宿主机 `lz4` 必须接受我们写出的流。

## 来源

| 项目 | 上游地址 | 版本 |
|---|---|---|
| lz4/lz4 | <https://github.com/lz4/lz4> | `v1.9.4` |

引入的文件:

- `lz4.c` — 上游 `lib/lz4.c`,**原样复制,未修改**
- `lz4.h` — 上游 `lib/lz4.h`,**原样复制,未修改**

获取地址:

- <https://raw.githubusercontent.com/lz4/lz4/v1.9.4/lib/lz4.c>
- <https://raw.githubusercontent.com/lz4/lz4/v1.9.4/lib/lz4.h>

## 许可

上游为 **BSD 2-Clause** 许可,版权 `Copyright (C) 2011-2020, Yann Collet`。
许可证原文保留在 `lz4.c` 文件头:

```
LZ4 - Fast LZ compression algorithm
Copyright (C) 2011-2020, Yann Collet.
BSD 2-Clause License (http://www.opensource.org/licenses/bsd-license.php)
```

BSD 2-Clause 与 GPL-3.0 兼容,可以静态链接进 GPL-3.0-only 的二进制。

## 本项目新增的部分

`cairn_lz4.c` 不属于上游,由本项目编写,许可 **GPL-3.0-only**。它只做三件事:

1. 写 legacy 魔数与每块长度前缀
2. 把输入切成 8 MiB 块交给 `LZ4_compress_fast`
3. 读取时按长度前缀逐块交给 `LZ4_decompress_safe`

块大小取 8 MiB 与参考工具的 legacy 模式一致。这一项不是装饰性的:每块载荷以字面量
数据结尾,而镜像尾部通常是零填充,块末若正好是 4 个零字节,就与 legacy 流用「零长度」
表示结束的约定冲突,参考解码器会提前停止并报告流损坏。用与参考工具相同的块大小,
产出的分帧形状与它一致。

## 构建

`source/kernel-builder/build.rs` 在构建时用宿主机 C 编译器(`CC`,默认 `cc`)把
`lz4.c` 与 `cairn_lz4.c` 编成 `libcairn_lz4.a` 并静态链接。

因此构建修补器需要 C 编译器。这不是新增类型的依赖:载荷汇编本来就需要 C 预处理器
(`aarch64-linux-gnu-gcc -E`),工具链要求同属一类。

## 修改记录

| 日期 | 内容 |
|---|---|
| 引入 | 复制 `v1.9.4` 的 `lz4.c` / `lz4.h`,未做任何修改 |
| 引入 | 新增 `cairn_lz4.c`(legacy 容器封装,GPL-3.0-only) |
| 引入 | 新增 `build.rs` 静态库构建步骤 |
| 修正 | 未写入结束零长度:参考 `lz4 -l` 在最后一块后直接结束,写入零长度会让参考解码器读到一个空块并报错 |
| 修正 | 块大小由 4 MiB 改为 8 MiB,与参考工具一致,避免块末零字节与结束标记混淆 |