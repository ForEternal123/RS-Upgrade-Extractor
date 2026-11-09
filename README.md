# RS Upgrade Extractor

[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-green)](https://www.minecraft.net/)
[![Forge](https://img.shields.io/badge/Forge-47.x-orange)](https://files.minecraftforge.net/)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

把藏在**合成升级**（Crafting Upgrade）里的物品取出来，直接放进 **Refined Storage** 网络。

> A tiny utility mod that extracts items hidden inside Sophisticated Backpacks' **Crafting Upgrade**
> and pushes them straight into your Refined Storage network.

---

## 它解决什么问题

精妙背包的合成升级把材料存在**物品自己的 NBT** 里：

```nbt
sophisticatedbackpacks:crafting_upgrade
 └─ tag.craftingInventory
      └─ Items: [ {钻石 x5}, {另一种升级（里面还有绿宝石）} ]
```

RS 只认识最外层那个升级物品，里面藏的东西**看不见、搜不到、还占体积**。
这个 mod 把它们掏出来放进网络。

---

## 支持的五种来源

| # | 场景 | 入口 |
| --- | --- | --- |
| ① | 手上 / RS 里的裸升级 | `/rsupgrade bag` · `/rsupgrade rs` |
| ② | 背包**升级槽**里的升级 | 同上 |
| ③ | 背包**主存储**里的升级（当普通物品塞进去的） | 同上 |
| ④ | 背包里套背包（2 层） | 同上 |
| ⑤ | **箱子里的背包** | `/rsupgrade box <x> <y> <z>` |

> 普通物品**永远不会**被搬走 —— 只有 ID 含 `upgrade` 的条目会被处理。

---

## 安装

| 环境 | 装在哪 |
| --- | --- |
| 多人服务器 | **服务端**（客户端可不装，但快捷键需要客户端也装）|
| 单机 / 局域网 | **客户端**（自带服务端）|
| 想两边都能用 | 两边都装（同一个 jar）|

- Minecraft **1.20.1**
- Forge **47.x**
- 必需前置：**Refined Storage**
- 可选前置：**Curios**（装了才会扫描背饰槽）、**Sophisticated Backpacks**（装了才能读背包升级槽）

`displayTest = IGNORE_ALL_VERSION`，两端版本不一致不会报红。

---

## 指令

> `/rsupgrade` 所有玩家可见。只有「动 RS 网络 / 动容器」的子指令需要 OP。

| 指令 | 权限 | 作用 |
| --- | --- | --- |
| `/rsupgrade` | 所有人 | 帮助 |
| `/rsupgrade list bag [关键字]` | 所有人 | 查看背包里的可提取内容 |
| `/rsupgrade bag [关键字] [deep]` | 所有人 | 从背包取出 |
| `/rsupgrade list rs [关键字]` | OP | 查看网络里的 |
| `/rsupgrade rs [关键字] [deep]` | OP | 从网络取出 |
| `/rsupgrade box <x> <y> <z> [关键字]` | OP | 处理箱子里的背包 |
| `/rsupgrade import <x> <y> <z>` | OP | 容器内容整体搬进 RS |
| `/rsupgrade debug` | 所有人 | 打印背包里每个物品的 NBT 结构（排错用）|

### 关键字与 deep

```
/rsupgrade bag crafting_upgrade      # 只处理合成升级
/rsupgrade bag                       # 处理所有带内容的升级
/rsupgrade bag crafting_upgrade deep # 递归：嵌套的同类也一起处理
```

---

## 快捷键

默认 **K 键**（可在「选项 → 控制 → 按键绑定」里改），按一下 = 背包 + 网络各取一次。

- 不需要 OP
- 服务端没装会提示「服务端没有装这个 mod」

## 自动模式

```
/rsupgrade auto                      查看状态
/rsupgrade auto bag on|off [玩家]     按玩家
/rsupgrade auto rs  on|off           按网络（站到网络旁边执行）
/rsupgrade auto rs  list             看已开启的网络
/rsupgrade auto all on|off           全局
```

每 **10 秒**检查一次，只处理被单独打开的目标。状态存在
`world/data/rsupgrade_auto.dat`，指令改动立即生效、重启不丢。

---

## 构建

```bash
# 需要 JDK 17
# 1. 把下面这些 jar 放进 libs/（用你整合包里的版本）
#    refinedstorage.jar
#    curios.jar                  (可选)
#    sophisticatedbackpacks.jar  (可选)
#    sophisticatedcore.jar       (可选)
# 2. 构建
./gradlew build
# 产物：build/libs/rsupgrade-1.0.0.jar
```

`libs/` 里的第三方 jar 不随仓库提供（许可证原因），需要自己放。

---

## 已知限制

| 限制 | 说明 |
| --- | --- |
| RS 里存的背包 | 精妙背包 3.x 可能把内容存世界存档，RS 里的副本读不到内容 |
| 升级 ID 必须含 `upgrade` | 判断依据是物品 ID 里的关键词 |
| 只处理所在维度 | 指令只操作执行者当前维度的网络 |
| Curios 为可选 | 没装时自动跳过，不报错 |

---

## 测试环境

- Minecraft 1.20.1 + Forge 47.4.20
- 300+ mod 的大型整合包实测（RS 1.12.4 / 精妙背包 3.24.65）
- 服务端启动 `Done (4s)`，无致命错误

---

## 许可

[MIT](LICENSE)
