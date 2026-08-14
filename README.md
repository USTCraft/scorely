# Scorely

> Minecraft Fabric 服务端模组 — 统计玩家进度、计算积分、提供查询

## 简介

Scorely 为社区服务器提供玩家积分体系：自动统计玩家挖掘、合成、战斗、探索、进度等行为并按规则计算积分，生成多个分榜与总榜，配合打星机制，便于社区依据积分发放奖励。

积分通过**定时全量重算**（默认每 5 分钟，可配置）计算：在线玩家读内存统计，离线玩家解析磁盘存档，天然幂等、故障自愈；查询命令读取缓存快照，零实时计算开销。

## 功能特性

- **五张预置榜单**：工艺榜（挖掘/使用/合成）、战斗榜（击杀/玩家击杀）、探索榜（移动距离/开箱/钓鱼）、惩罚榜（死亡/受伤/PVP 扣分，升序展示）、进度榜（按 task/goal/challenge 难度分层）
- **总榜**：各分榜积分的加权和（默认权重 1，可配置）
- **积分上限控制**：各榜显式满分（默认 800）、惩罚榜封底（-800），抑制刷分
- **高度可配置**：全部计分规则（统计项、档位、倍率、封顶、满分、权重）在 config.json 中定义，热重载生效
- **打星机制**：特殊玩家（手动名单 / OP 自动识别）照常计分，榜单带 ★ 标记，不参与正式排名竞争
- **国际化**：内置 zh_cn / en_us 语言表，服主可经 config.json `lang` 字段自定义翻译
- **纯服务端**：客户端零改动，原版客户端直接使用

## 环境要求

- Minecraft: 26.2
- Fabric Loader: >= 0.19.3
- Fabric API
- Java: >= 25

## 安装

1. 将构建产物 `scorely-<version>.jar` 放入服务器 `mods/` 目录
2. 启动服务器，首次启动自动生成 `config/scorely/config.json`（预置五榜）

## 命令

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/scorely` | 帮助信息 | 所有人 |
| `/scorely score [rule]` | 查看自己的积分（可指定分榜） | 所有人 |
| `/scorely refresh` | 刷新自己的积分 | 所有人 |
| `/scorely rank [rule] [page]` | 排行榜（总榜 / 分榜，可翻页） | 所有人 |
| `/scorely admin reload` | 热重载配置（校验通过才生效，失败保留旧配置） | OP |
| `/scorely admin refresh` | 强制全量刷新积分 | OP |
| `/scorely admin rule list` | 查看全部积分规则及计分配置 | OP |
| `/scorely admin star add/remove/list` | 管理打星名单 | OP |

## 配置

配置文件位于 `config/scorely/config.json`，首次启动自动生成，可用 `/scorely admin reload` 热重载（校验失败时保留旧配置并备份 `.bak`）。

- `language`：控制台默认语言（zh_cn / en_us）
- `refreshIntervalMinutes`：全量重算周期（分钟，默认 5）
- `starPlayers`：打星玩家 UUID 名单
- `starOps`：OP 自动打星开关（默认开，经 ops.json 持久判定）
- `lang`：翻译覆盖表（翻译键 → 语言 → 文本），可覆盖内置翻译或自定义榜单名
- `rules`：积分规则数组，每条的配置字段包括：
  - `id` / `displayName` / `type`（`stat` / `advancement`）/ `sort`（`asc` / `desc`）
  - `matchers`：统计项匹配（statType + statPath，支持通配）与逐项计分覆盖
  - `multiplier` / `cap` / `divisor` / `tiers`：线性或档位计分
  - `advancementValues` / `defaultValue` / `frameValues`：进度型计分
  - `maxScore`：整榜满分（正=上限，负=封底，0=不限）
  - `weight`：总榜加权和权重（0 = 1）

## 构建

```bash
./gradlew build
```

构建产物位于 `build/libs/`。

## 许可证

[CC0-1.0](LICENSE)
