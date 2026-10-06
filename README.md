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

- Litematica 轻松放置：V3 精确放置协议，支持方向、轴、楼梯上下半部、红石中继器延迟等。
- Syncmatica 多人投影共享：上传/下载 `.litematic`、位置/旋转/镜像、子区域摆放、修改和删除同步，重启后保留。

## 轻松放置

在客户端 Litematica 中开启 `easyPlaceMode`，将 `easyPlaceProtocol` **明确设为 `V3`**。
本插件没有注册 Litematica 的完整任务协议，`Auto` 不保证识别到 V3。
玩家需要持有对应材料；生存模式正常消耗物品，放置距离、碰撞和 Paper 放置事件仍然生效。
领地插件取消放置后不会扣除材料；协议不会把一块台阶变成双台阶。
默认所有玩家拥有 `servux.easy_place`，服务端总开关为 `easy_place.enabled`。

## 多人投影共享

客户端需要安装与游戏版本匹配的 **Syncmatica + Litematica + MaLiLib**；本插件对接 **Syncmatica 26.3 / 0.3.20**。
使用 Syncmatica 的共享/服务器投影列表界面上传和下载投影；其他在线玩家会收到新增和摆放修改。
本插件实现 `CORE`、`FEATURE`、`MODIFY`、`CORE_EX` 协议，不要求额外安装服务端 Syncmatica 插件。
通信使用新版 `syncmatica:main` 封装；请使用 `paper.3` 或更新版本，`paper.2` 的旧频道格式会导致此客户端进服断线。

默认玩家可以查看、下载、上传，并修改/删除自己的共享；`servux.syncmatica.admin` 可管理所有共享。
权限节点为 `servux.syncmatica`、`servux.syncmatica.share`、`servux.syncmatica.modify`。
文件和摆放数据保存在 `plugins/ServuxPaper/syncmatica/`，请将此目录纳入备份。
默认单文件上限 16 MiB、总文件空间 256 MiB、最多 256 个共享，均可在 `syncmatica` 配置节调整。
断线或传输空闲两分钟后清理未完成上传；编辑锁五分钟后释放。

功能范围不包含 Litematica 服务端粘贴/填充/删除任务、Tweakeroo 频道或原版 NBT 查询权限覆盖。

种子、天气和数据记录器默认关闭，可在配置中开启。种子及玩家背包 / 末影箱数据还受独立权限节点控制；详见
[`plugin.yml`](src/main/resources/plugin.yml) 和 [`config.yml`](src/main/resources/config.yml)。
修改配置后执行 `/servux reload`；修改更新间隔需要重启服务器。
