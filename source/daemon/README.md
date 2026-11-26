# cnd 源码入口

以 cairn_entry.c、cairnd.c 为编译入口，其他实现被包含。build.sh 从脚本所在位置定位源码，接受可选输出路径，不安装到设备。模块命令提交已准备暂存树，不能直接解包 ZIP。详见根目录 docs。
