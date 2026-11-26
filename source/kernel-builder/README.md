# 内核补丁构建器 1.0.1

当前入口为 Rust detached 构建器：4232 字节 bootstrap、65536 字节 core、19 个功能 gateway 和一个启动 Hook。只支持已校验的精确原厂 profile，非完整厂商内核树。使用 loader/rebuild.py --check 核对生成模板；运行 Rust 构建器须提供自己的原厂 boot。构建器日志包含认证密钥，须私有保存。AVB 未重新签名。详见根目录 docs/构建说明.md 与 docs/验证与交付状态.md。
