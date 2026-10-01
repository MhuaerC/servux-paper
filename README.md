# Servux Paper 26.3

这是服务端插件，客户端无需安装 Servux。

## 安装

1. 使用 **Java 25** 启动 **Paper 26.3**（构建及运行测试固定为 build 140）。
2. 从 [Releases](https://github.com/MhuaerC/servux-paper/releases) 下载普通插件 JAR，放入服务器的 `plugins/`。
3. 重启服务器。配置文件位于 `plugins/ServuxPaper/config.yml`。
4. 客户端安装与 Minecraft 26.3 匹配的 MiniHUD 及其依赖。

不要安装 `-sources.jar` 或 `-integration-tests.jar`。本插件使用 Minecraft 内部 API，不能保证其他 Minecraft 版本兼容。
从旧实现迁移时，请先移走旧 Servux JAR，避免两个插件同时处理相同频道；旧配置不会自动迁移。

## 功能

- MiniHUD 结构边界同步：`servux:structures`（协议 3）。
- 出生点、可选种子和天气、TPS / mob caps、配方数据：`servux:hud_metadata`（协议 3）。
- 实体和方块实体 NBT 查询：`servux:entity_data`（协议 2）。
- `/servux reload`、`/servux save`、`/servux list` 管理命令。

功能范围不包含 Litematica 服务端粘贴、Easy Place、Tweakeroo 频道或原版 NBT 查询权限覆盖。

种子、天气和数据记录器默认关闭，可在配置中开启。种子及玩家背包 / 末影箱数据还受独立权限节点控制；详见
[`plugin.yml`](src/main/resources/plugin.yml) 和 [`config.yml`](src/main/resources/config.yml)。
修改配置后执行 `/servux reload`；修改更新间隔需要重启服务器。
