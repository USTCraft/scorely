# Scorely 开发计划

> Minecraft Fabric 模组 — 统计玩家进度、计算积分、提供查询

## 项目定位

**核心流程**：获取统计信息 → 算分 → 提供查询

**目标场景**：社区服务器依据积分数据发放实物奖励

**与 stats-scoreboard 的关键差异**：不做游戏内侧边栏展示

---

## 展示方案（三选一，渐进实现）

| 方案 | 说明 | 优先级 |
|---|---|---|
| **命令查询** | 游戏内 `/scorely` 命令输出到聊天框 | **最小实现** |
| **自定义屏幕** | 继承 `Screen` 类，客户端 GUI 展示排行榜 | 后续迭代 |
| **Web 服务** | 模组内嵌 HTTP 服务器，外部浏览器访问 | 远期规划 |

### 方案一：命令查询（MVP）

```
/scorely score              → 查看自己的总积分
/scorely score <rule>       → 查看自己在某规则下的积分
/scorely rank               → 查看总积分排行榜（前10）
/scorely rank <rule>        → 查看某规则排行榜
/scorely rank <rule> <page> → 翻页查看
```

所有输出通过聊天消息返回，无需任何 GUI 或侧边栏。

### 方案二：自定义屏幕（后续迭代）

利用 Fabric 的 Custom Screen API（继承 `net.minecraft.client.gui.screens.Screen`）：
- 客户端渲染排行榜 GUI
- 通过 `Minecraft.getInstance().setScreen()` 打开
- 支持滚动列表、标签页切换不同规则
- 需要客户端安装模组

参考文档：https://docs.fabricmc.net/zh_cn/1.21.4/develop/rendering/gui/custom-screens

### 方案三：Web 服务（远期规划）

模组内嵌轻量 HTTP 服务器（如 Javalin / NanoHTTPD）：
- 提供 REST API（`/api/scores`、`/api/rankings`）
- 外部浏览器或 Discord Bot 访问
- 适合社区管理员后台查看和导出数据

---

## 包结构设计

> 设计原则：**版本敏感代码（Mixin、平台 API）与纯业务逻辑严格隔离**，为未来多版本适配（Stonecutter）预留空间。

```
cc.lylighte.scorely/
├── Scorely.java                     # ModInitializer 入口
│
├── compat/                          # 版本适配层（所有 MC 版本相关代码集中于此）
│   ├── mixin/                       # Mixin 注入（版本敏感，当前为空；事件日志功能预留）
│   └── CompatHelper.java            # 版本差异封装（在线玩家统计/进度的内存读取）
│
├── config/                          # 配置管理（纯逻辑，无 MC 版本依赖）
│   ├── Config.java                  # 通用 JSON 读写工具（泛型）
│   ├── ScorelyConfig.java           # 全局配置（积分规则、刷新间隔等）
│   ├── PlayerConfig.java            # 玩家配置
│   └── ConfigManager.java           # 配置管理器（init/load/save/autoSave）
│
├── scoring/                         # 积分计算引擎（纯逻辑，无 MC 版本依赖）
│   ├── ScoringRule.java             # 积分规则定义（匹配器 + 权重 + 模式）
│   ├── ScoringEngine.java           # 积分计算引擎（定时全量重算 + 查询）
│   └── ScoreCache.java              # 积分缓存（ConcurrentHashMap）
│
├── stats/                           # 统计数据获取层
│   ├── StatsReader.java             # 从磁盘 stats/*.json 解析玩家统计（纯 Java）
│   └── StatsType.java               # 统计类型映射（criteria → stat path）
│
├── advancement/                     # 进度数据获取层
│   └── AdvancementReader.java       # 从 advancements/*.json 读取玩家进度完成状态
│
├── command/                         # 命令系统（Brigadier 接口稳定，轻度版本依赖）
│   ├── ScorelyCommands.java         # 命令注册入口
│   └── handlers/
│       ├── ScoreCommand.java        # /scorely score
│       ├── RankCommand.java         # /scorely rank
│       └── AdminCommand.java        # /scorely admin（reload/refresh/rule）
│
├── event/                           # 事件处理（通过 Fabric API，版本间较稳定）
│   ├── ServerEvents.java            # 服务器生命周期 + 定时刷新循环（ServerTickEvents）
│   ├── PlayerEvents.java            # 玩家事件（加入触发刷新请求）
│   ├── RefreshScheduler.java        # 刷新调度器（定时主循环 + 合并触发，全量入口仅管理命令）
│   └── PlayerDataCache.java         # 离线玩家数据缓存（mtime+size 指纹，减少磁盘访问）
│
└── util/                            # 通用工具（无 MC 版本依赖）
    ├── Result.java                  # 操作结果（success + message）
    └── ChatHelper.java              # 聊天消息构造工具
```

### 层次依赖规则

```
util/          ← 零依赖（纯 Java）
config/        ← 仅依赖 util/ + Gson（Minecraft 内置）
scoring/       ← 仅依赖 config/ + util/（纯业务逻辑）
stats/         ← 依赖 compat/ + scoring/（通过 CompatHelper 隔离版本差异）
advancement/   ← 依赖 compat/ + scoring/（通过 CompatHelper 隔离版本差异）
command/       ← 依赖 scoring/ + util/（Brigadier 接口稳定）
event/         ← 依赖 compat/ + scoring/ + config/（Fabric API 较稳定）
compat/        ← 唯一允许直接引用 MC 内部类的层（Mixin + CompatHelper）
Scorely.java   ← 入口，注册各模块
```

**核心约束**：`scoring/`、`config/`、`util/` **禁止**直接 import `net.minecraft.*`；
所有 MC 版本敏感代码必须经过 `compat/` 层中转。

---

## 核心模块设计

### 积分体系总览

积分按**统计口径**分为多个维度，每个维度独立计榜，同时汇总为总榜：

```
┌───────────────────────────────────────────┐
│              总榜 (total)                  │
│   = 所有维度积分之和                        │
└───────────────────────────────────────────┘
         ↑          ↑          ↑
  ┌──────────┐ ┌──────────┐ ┌──────────┐
  │ 挖掘榜    │ │ 战斗榜    │ │ 进度榜    │  ...
  │ stats    │ │ stats    │ │ adv.     │
  │ 累加计分  │ │ 累加计分  │ │ 一次性计分 │
  └──────────┘ └──────────┘ └──────────┘
```

**两种计分模式**：

| 模式 | 数据源 | 计分方式 | 示例 |
|---|---|---|---|
| **stat（统计型）** | 统计累计值（内存/磁盘） | 线性：`统计值 × multiplier`；或阶段：`tiers` 阈值累加（divisor 换算单位、cap 封顶） | 挖 100 块石头 +100 分；走 100km 得 30+40+50=120 分 |
| **advancement（进度型）** | 进度完成状态（内存/磁盘） | 一次性：已完成的进度给固定分 | 完成“钻石！”+10 分 |

### 积分规则（ScoringRule）

```java
public class ScoringRule {
    String id;                       // "mining" | "combat" | "advancements"
    String displayName;              // "挖掘榜" | "战斗榜" | "进度榜"
    String type;                     // "stat" | "advancement"
    String sort;                     // "asc" | "desc"（默认 desc；惩罚榜配 "asc" 扣最多排最前）

    // stat 型专用（规则级默认值，matcher 可逐项覆盖）
    List<StatMatcher> matchers;      // 匹配哪些统计项
    boolean enabled = true;          // 默认开关
    double multiplier = 1.0;         // 线性计分倍率（tiers 非空时忽略）
    double cap = 1000.0;             // 默认封顶 1000 分（显式配 0 = 不封顶）
    double divisor = 1.0;            // 单位换算（cm→km 等）
    List<StatTier> tiers;            // 阶段奖励（非空时按阶段计分）

    // advancement 型专用
    Map<String, Double> advancementValues;  // 进度ID → 分值
    double defaultValue;             // 未单独配置的进度默认分值
}

public class StatMatcher {
    String statType;                 // "minecraft:mined"
    String statPath;                 // "minecraft:stone" 或 "*"（通配）
    // 计分配置（null = 继承规则级默认值）
    Boolean enabled;
    Double multiplier;
    Double cap;
    Double divisor;
    List<StatTier> tiers;
}

public class StatTier {
    double threshold;                // 档位阈值（统计值 ÷ divisor 后的单位）
    double value;                    // 达到该档位的奖励分（所有达到的档位累加）
}
```

配置文件示例（`config.json`）：

```json
{
  "rules": [
    {
      "id": "mining",
      "displayName": "挖掘榜",
      "type": "stat",
      "matchers": [
        { "statType": "minecraft:mined", "statPath": "*" }
      ],
      "multiplier": 1.0
    },
    {
      "id": "combat",
      "displayName": "战斗榜",
      "type": "stat",
      "matchers": [
        { "statType": "minecraft:killed", "statPath": "*" }
      ],
      "multiplier": 2.0
    },
    {
      "id": "exploration",
      "displayName": "探索榜",
      "type": "stat",
      "matchers": [
        {
          "statType": "minecraft:custom",
          "statPath": "minecraft:walked_one_cm",
          "divisor": 100000,          // cm → km
          "tiers": [                  // 阶段奖励：走 100km 得 30+40+50=120 分
            { "threshold": 10, "value": 30 },
            { "threshold": 40, "value": 40 },
            { "threshold": 100, "value": 50 }
          ],
          "cap": 200                  // 超过 200km 不再计更高档
        },
        { "statType": "minecraft:custom", "statPath": "minecraft:fly_one_cm", "enabled": false },
        { "statType": "minecraft:custom", "statPath": "minecraft:boat_one_cm", "multiplier": 0.0005 }
      ]
    },
    {
      "id": "advancements",
      "displayName": "进度榜",
      "type": "advancement",
      "defaultValue": 10.0,
      "frameValues": {            // 按难度分层（Phase 12）：task 普通 / goal 目标 / challenge 挑战；advancementValues > frameValues > defaultValue
        "task": 20,
        "goal": 30,
        "challenge": 60
      },
      "advancementValues": {
        "minecraft:adventure/kill_all_mobs": 100.0,
        "minecraft:end/kill_dragon": 200.0,
        "minecraft:nether/all_effects": 150.0
      }
    },
    {
      "id": "penalty",               // 惩罚榜（Phase 10 示例，默认不预置）
      "displayName": "scorely.rule.penalty",
      "type": "stat",
      "sort": "asc",                 // 扣最多排最前
      "matchers": [
        { "statType": "minecraft:custom", "statPath": "minecraft:deaths", "multiplier": -10 }
      ],
      "cap": 50                      // 扣分封底：|分| ≤ 500（零新增语义，复用 |积分| 上限）
    },
    {
      "id": "breadth",               // 广度榜（Phase 12 能力示例；默认不预置，用户 2026-08-13）
      "displayName": "scorely.rule.breadth",
      "type": "stat",
      "mode": "count",               // 计数模式：已解锁（累计值>0）统计项数量 × multiplier
      "matchers": [
        { "statType": "*", "statPath": "*" }
      ],
      "multiplier": 2.0               // 每解锁一项 +2 分（自建参考，见 SCORING_PLAN 3.5）
    }
  ],
  "lang": {
    "scorely.rule.boss": {          // 自定义键：displayName 填 "scorely.rule.boss" 即多语言自适应
      "zh_cn": "Boss 榜",
      "en_us": "Boss Board"
    },
    "cmd.refresh.done": {           // 覆盖内置键（话术定制）；只配 zh_cn 时 en_us 回退内置
      "zh_cn": "积分刷新成功！"
    }
  },
  "starPlayers": ["uuid1", "uuid2"],  // 打星玩家（Phase 11）：照常计分，榜单带 ★ 标记
  "starOps": true,                      // OP 自动打星（默认开）
  "refreshInterval": 5,
  "autoSaveInterval": 60
}
```

### 积分引擎（ScoringEngine）

```java
public class ScoringEngine {
    Map<UUID, Map<String, Double>> playerScores;  // 玩家 → 规则ID → 分数
    List<ScoringRule> rules;

    // 全量：重读所有玩家统计 + 进度（在线读内存、离线读磁盘）并重算
    void recalculateAll(MinecraftServer server);

    // 查询：获取某规则排行榜
    List<ScoreEntry> getLeaderboard(String ruleId, int limit);

    // 查询：获取总榜（所有规则积分加权和，weight 默认 1）
    List<ScoreEntry> getTotalLeaderboard(int limit);

    // 查询：获取玩家在某规则下的分数
    double getPlayerScore(UUID player, String ruleId);

    // 查询：获取玩家总分
    double getPlayerTotalScore(UUID player);
}
```

### 数据流

全量路径（Phase 8.1 起仅定时任务 + 管理命令触发：`admin refresh` / `admin reload`）：

```
定时全量刷新（可配置，默认 5 分钟）
    ↓
在线玩家：内存实时读取（CompatHelper 中转 StatsCounter / PlayerAdvancements）
离线玩家：磁盘解析 stats/*.json + advancements/*.json
    ↓
ScoringEngine.recalculateAll()     ← 全量重算所有维度（幂等，天然自愈）
    ↓
ScoreCache 更新积分快照
    ↓
查询命令读快照（零计算）
```

单玩家路径（Phase 8.1：进服被动触发 + `/scorely refresh` 主动触发）：

```
玩家进服 / 玩家执行 /scorely refresh
    ↓
只读对应玩家：在线走内存（CompatHelper）/ 离线走磁盘
    ↓
ScoringEngine.recalculatePlayer(uuid)   ← 单玩家全量重算，只更新该玩家缓存条目
    ↓
不触发全服重算（单玩家粒度，Phase 8.3 起无配额概念）
```

---

## 依赖项

```groovy
// build.gradle
dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    implementation "net.fabricmc:fabric-loader:${project.loader_version}"
    implementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}"
}
```

不需要 sidebar-api（不做侧边栏展示）。Gson 已内置于 Minecraft。

---

## 命令设计

| 命令 | 功能 | 权限 |
|---|---|---|
| `/scorely` | 显示帮助信息 | 所有人 |
| `/scorely score` | 查看自己的各维度积分 + 总分 | 所有人 |
| `/scorely score <rule>` | 查看自己在某维度下的积分 | 所有人 |
| `/scorely rank` | 查看**总榜**排行榜 Top 10 | 所有人 |
| `/scorely rank <rule>` | 查看某维度排行榜 | 所有人 |
| `/scorely rank <rule> <page>` | 翻页 | 所有人 |
| `/scorely admin reload` | 重载配置 | OP |
| `/scorely admin refresh` | 强制全量刷新积分 | OP |
| `/scorely admin rule list` | 列出所有积分规则/维度 | OP |
| `/scorely admin star add \| remove \| list <player>` | 管理打星名单（Phase 11） | OP |

**示例输出**：

```
/scorely score
─────────────────
  挖掘榜: 1,234
  战斗榜: 567
  探索榜: 89
  进度榜: 420
─────────────────
  总分: 2,310
```

---

## 配置文件结构

```
world/serverconfig/scorely/
├── config.json          # 全局配置（首次启动自动生成默认规则；加载校验失败回退默认 + 损坏文件保留为 .bak）
│   ├── rules[]          # 积分规则列表（校验：id 唯一、type 合法、数值非负、divisor≠0）
│   ├── refreshInterval  # 全量刷新间隔（分钟，默认5，非法回退默认）
│   └── autoSaveInterval # 自动保存兜底间隔（秒，默认60）
└── players.json         # 玩家名称缓存（Map<String,String>，UUID→名字；脏标记落盘 + 原子写）
```

---

## 实施路线图

| 阶段 | 内容 | 预估 |
|---|---|---|
| **Phase 0** | 项目重命名（template-mod → scorely），清理模板代码 | 0.5 天 |
| **Phase 1** | 搭建包骨架 + 通用工具类（Result、ChatHelper、Config） | 1 天 |
| **Phase 2** | 统计数据读取：StatsReader（磁盘解析）+ CompatHelper（在线玩家内存读取） | 1.5 天 |
| **Phase 3** | ScoringRule（stat 型）+ ScoringEngine（全量重算）+ ScoreCache | 2 天 |
| **Phase 4** | 进度数据读取：AdvancementReader（磁盘解析 + 内存） | 1 天 |
| **Phase 5** | ScoringRule（advancement 型）+ 总榜计算 | 1 天 |
| **Phase 5.1** | stat 型配置扩展：StatTier 阶段奖励 + StatMatcher 计分配置（enabled/multiplier/cap/divisor/tiers，规则默认值 + 匹配项覆盖） | 1 天 |
| **Phase 6** | 命令系统（score / rank / admin）✅ | 2 天 |
| **Phase 7** | 事件集成（ServerTick 定时刷新循环）✅ | 1 天 |
| **Phase 8** | ConfigManager + 自动保存：首次启动生成默认 config.json（序列化 DefaultRules）、Gson 加载 + 校验（id 唯一/type 合法/数值非负/divisor≠0）、损坏回退默认 + `.bak` 保留、refreshInterval 接入调度器、players.json 名称缓存（String key）+ 脏标记落盘 + 原子写 ✅ | 1 天 |
| **Phase 8.1** | 刷新策略收敛：全量重算仅定时任务 + `admin refresh` 触发；玩家进服 / `/scorely refresh` 走单玩家重算（只算对应玩家，不触发全服重算）✅ | 0.5 天 |
| **Phase 8.2** | 热重载 + 展示接入：`admin reload` 校验通过才替换引擎（失败保留旧引擎报错）+ 立即重算 + 调度器间隔更新；rank/score 用名称缓存替换 UUID 短格式 ✅ | 0.5 天 |
| **Phase 8.3** | 刷新频率约束简化：移除 admin 刷新配额（MAX_EXTRA_REFRESHES），全量重算仅受入口权限门禁约束（OP），不再限频；`admin reload` 统一走 `refreshNow()`（服务器未就绪时才延迟到下次定时）✅ | 0.2 天 |
| **Phase 9** | 国际化（en_us + zh_cn）✅：服务端语言表（`util/Lang` + `assets/scorely/lang/*.json`，回退链 目标→en_us→key）、per-player 语言（classTweaker 读 `ServerPlayer.language`，控制台用 config `language` 字段，默认 zh_cn）、Result/命令层/规则名全量 key 化、config 校验错误 19 键、modmenu 描述翻译键 | 0.5 天 |
| **Phase 9.1** | 语言覆盖表：config.json 可选 `lang` 字段（翻译键 → {语言码 → 文本}），查表顺序 覆盖(目标语言) → 内置(目标语言) → 覆盖(en_us) → 内置(en_us) → key 原文；服主可新增自定义键（displayName 填键名 → 自定义规则名多语言自适应）或覆盖内置键（命令话术定制）；volatile 引用替换（reload 失败保持旧覆盖）；校验新增 2 个错误键（lang 键非空/文本非空）✅ | 0.3 天 |
| **Phase 10** | 惩罚榜（负积分）：放开引擎/缓存 `>0` 过滤（负分玩家入榜）、规则新增 `sort` 字段（asc/desc，默认 desc，惩罚榜配 asc 扣最多排最前）、校验放开 multiplier/tier value 非负限制（threshold 仍非负）、负向封顶复用现有 cap（|积分|上限，零新增语义）、总榜仅放开过滤 ✅ | 1 天 |
| **Phase 11** | 打星机制（out-of-competition）：特殊身份玩家（config `starPlayers` UUID 名单 + `starOps` 开关默认开）照常统计计分、榜单显示但带 ★ 标记（不参与正式排名竞争）；`/scorely admin star add/remove/list` + 配置双通道维护；名单/OP 判定持久（离线也生效）；原"创造/旁观模式排除"方案弃用（模式信息仅运行时存在，无法处理离线玩家） ✅ | 0.5 天 |
| **Phase 12** | 计分模式扩展 + 进度分层 + 预置榜单重做：规则新增 `mode` 字段（linear/log/sqrt/count，默认 linear 零回归；count 供广度榜自建，**默认不预置**）、`maxScore`（显式满分）与 `weight`（总榜权重，默认 1）；进度型新增 `frameValues` 按难度分层（advancementValues > frameValues > defaultValue，默认 task 20/goal 30/challenge 60）；statType 通配支持；六榜数值与总榜口径定稿见 SCORING_PLAN ✅ | 1 天 |
| **测试** | 边界情况 + 性能测试 + 多玩家验证 | 2 天 |

**总计**：约 16.2 天

> **关于打星机制（Phase 11，原"创造/旁观排除"重定义）**：语义对齐 ACM/XCPC 打星队伍——特殊身份玩家照常游戏、统计照常采集、积分照常计算，**榜单显示但带 ★ 标记**（不参与正式排名竞争），自己 `/scorely score` 正常可见。判定源（任一命中即打星）：① config.json `starPlayers`（UUID 名单，持久，离线生效）；② `starOps` 开关（默认开，OP 经 ops.json 判定，持久离线生效）。游戏模式不再作为判定源（原"数据收集层过滤"方案弃用：模式仅运行时存在，离线玩家磁盘累计值无法回溯，收集层过滤无法根治离线污染；查询层过滤 + 持久判定源可完全规避）。实现：打星玩家仍在积分缓存中（`getPlayerScore` 正常），仅榜单渲染时对打星条目标记；`/scorely admin star add/remove/list` 命令维护名单（在线玩家 → 名称缓存解析 UUID），热生效零重算；名单/开关随 config reload 更新（`admin star` 命令直接改 config.json）。

> **关于计分模式扩展 + 进度分层 + 预置榜单重做（Phase 12）**：合并为一个 Phase（用户决策：饱和计分纳入、进度按难度分层、广度榜默认不启用；六榜数值与总榜口径定稿见 SCORING_PLAN.md）。① **计分模式**：规则新增 `mode` 字段（stat 型）——`linear`（默认，现状公式）/ `log`（`multiplier × log(1 + value÷divisor)`，cap 截断后取对数，抑制刷分收益）/ `sqrt`（`multiplier × sqrt(value÷divisor)`）/ `count`（已解锁统计项计数：匹配项累计值>0 每项 +multiplier；tiers/cap/divisor 忽略）。`mode ≠ linear` 时配置 `tiers` 报错（意图冲突）；默认规则暂不使用 log/sqrt，count 供广度榜自建。② **广度榜**（RL 灵感 A2）：count 模式全统计通配能力（每解锁一项 +2 分示例，幂等可算，无需历史状态；首次钓鱼/附魔/造访维度均计入）；**默认不预置**（用户 2026-08-13 拍板，见 SCORING_PLAN 3.5）。③ **进度分层**：advancement 型新增 `frameValues`（frame 名 → 分值），优先级 `advancementValues`（逐进度）> `frameValues`（按难度）> `defaultValue`（兜底）；帧信息来自进度元数据（26.2 验证：`AdvancementFrame` 已更名 `AdvancementType`（TASK/CHALLENGE/GOAL），经 `Advancement.display()` → `Optional<DisplayInfo>` → `DisplayInfo.getType().getSerializedName()` 取 "task"/"goal"/"challenge"）；帧映射由 CompatHelper 在服务器启动后构建静态缓存（服务端进度注册表启动后固定），引擎/调度器传参接入（纯 Java 隔离保持）。默认分层值 task 20 / goal 30 / challenge 60（估算满分 ~3420，运行时校准；root 进度无 display 走 defaultValue）。④ **statType 通配**：`StatMatcher` 的 statType 支持 `"*"`（此前仅 statPath 支持），count 模式全量统计通配所需。

> **关于总榜组成（Phase 12 决策）**：总榜 = 各分榜积分的**加权和**，默认权重均为 1（规则新增 `weight` 字段，用户 2026-08-13，见 SCORING_PLAN 1.2）；惩罚榜负分直接计入；各榜满分在设计时统一 800（`maxScore` 显式满分，进度榜天然封顶 ~3420 除外），加权和量级横向可比。

> **关于负积分（Phase 10）**：候选研究结论——核心惩罚项 `deaths`（死亡次数，语义最清晰、无法刷）与 `damage_taken`（累计受伤 HP，细粒度），均已在现有全量统计表中（`minecraft:custom` 类型，26.2 已验证常量存在）；细分项（`deaths_by_*`/`killed_by/*`）语义更好但需 StatMatcher 前缀通配支持，且与 `deaths` 同死亡事件双计（配置须二选一）；`fall_one_cm`（摔落已含于 damage_taken）/`drop`（正常整理背包会被误罚）不建议默认。实现面：① 过滤放开——`ScoringEngine.computeScores`/`ScoreCache`（getLeaderboard/getTotalLeaderboard）的 `>0` 改 `!=0`；② 排序——规则新增 `sort` 字段（asc/desc，默认 desc 零回归），惩罚榜配 asc（扣最多排最前），总榜保持降序（净分排序）；③ 校验放开——multiplier/matcher multiplier/tier value 允许负值（仅拒非有限，threshold 仍非负）；④ **负向封顶零改动**——现行 cap 语义实为"|积分|上限"（线性截断统计值再乘负 multiplier、tiers 截断 adjusted），负分场景天然给出扣分封底（如 cap=50、multiplier=-10 → 扣分 ≥ -500），无需新语义；⑤ 命令层零改动（`ChatHelper.formatNumber` 千分位负号天然支持）。惩罚榜**默认不预置**（预置榜单结构重做待规划），config 示例给出死亡榜模板（deaths × -10，cap 50）。

> **关于客户端增强（远期规划，待规划，不实施）**：当前为纯服务端模组，原版客户端零改动即可使用；远期增强必须保持**原版端兼容**。分层思路——第一层（推荐优先，零客户端改动）：原版协议级展示，scoreboard 侧边栏 Top 榜（服务端自动推送更新）、actionbar/title 积分通知、bossbar 实时积分、聊天 hover/click 富文本（组件级，纯原版客户端同样渲染）；第二层：服务器资源包携带 lang 包，使 translatable 组件在纯原版客户端显示本地化名称/描述；第三层（可选客户端 mod，需原版降级兼容）：HUD 常驻显示、自定义 Screen 排行榜（即展示方案二）。约束：所有功能以服务端为主，客户端为可选增强层，未装 mod 时自动退化为第一层。

---

## 多版本适配策略

### 当前原则：预防性隔离

不引入多版本构建工具，但在架构层面做好隔离，使未来迁移成本最小化：

**1. `compat/` 层集中管理版本差异**

所有直接引用 `net.minecraft.*` 内部类的代码必须放在 `compat/` 包内。
其余业务层（`scoring/`、`config/`、`util/`）只依赖标准 Java + Gson。

```java
// compat/CompatHelper.java — 封装版本差异
public class CompatHelper {
    // 26.2 Mojang mapping: ServerPlayer#getStats → StatsCounter
    // 将内存统计转换为 统计名 → 数值 的纯 Java Map，供积分引擎使用
    public static Map<String, Integer> readPlayerStats(ServerPlayer player) { ... }

    // 26.2 Mojang mapping: PlayerAdvancements 内存查询已完成进度
    public static Set<String> readCompletedAdvancements(ServerPlayer player) { ... }
}
```

**2. Mixin 与业务逻辑解耦（预留）**

当前积分链路不包含 Mixin（纯定时全量重算），`scorely.mixins.json` 的 mixins 列表保持为空。
未来实现“进度完成事件日志”等功能需要实时感知时，在 `compat/mixin/` 下新增拦截器，只做拦截转发，通过 `CompatHelper` 调用业务层：

```java
// compat/mixin/StatsCounterMixin.java（未来功能预留示例）
@Mixin(StatsCounter.class)
public class StatsCounterMixin {
    @Inject(method = "increment", at = @At("TAIL"))
    private void onStatIncrement(Player player, Stat<?> stat, int amount, CallbackInfo ci) {
        if (player instanceof ServerPlayer sp) {
            CompatHelper.onStatIncrement(sp.getUUID(), stat, amount);
        }
    }
}
```

**3. 避免在业务层硬编码 MC 类型**

```java
// ❌ 坏：业务层直接引用 MC 类
public class StatsReader {
    StatsCounter readPlayerStats(ServerPlayer player) { ... }
}

// ✅ 好：业务层只接收纯 Java 结构（Map / 字符串标识）
public class StatsReader {
    Map<String, Integer> readPlayerStats(UUID player) { ... }
}
```

### 未来迁移路线

| 阶段 | 触发条件 | 方案 |
|---|---|---|
| 保持现状 | 仅支持 26.2 | 当前架构已足够 |
| 引入 Stonecutter | 需支持 2+ MC 版本 | `compat/` 内添加注释宏，`versions/` 目录管理各版本 `gradle.properties` |
| 引入 Architectury | 需同时支持 Fabric + NeoForge | 将 `compat/` 拆分为 `common/` + `fabric/` + `neoforge/` 子项目 |

> **推荐工具**：[Stonecutter](https://github.com/kikugurdev/stonecutter)（注释宏预处理器），
> 一条命令 `./gradlew chiseledBuild` 产出所有版本 jar。

---

## 关键设计决策

### D1: 不做侧边栏 → 不需要 sidebar-api

减少依赖、简化架构。展示层通过命令/屏幕/Web 实现。

### D2: 积分计算采用纯定时全量重算（无 Mixin 拦截）

**决策**：榜单查询接受延迟（默认 5 分钟，可配置），因此取消实时拦截：
- 定时任务每 `refreshInterval` 分钟全量重算：在线玩家读内存（StatsCounter / PlayerAdvancements），离线玩家读磁盘 JSON
- 全量重算天然幂等（统计累计值 × 权重），无增量状态、无累积误差、故障自愈
- 避免 Mixin 注入风险与版本敏感代码；未来“进度完成事件日志”功能需要实时感知时，再单独引入拦截器，与积分链路解耦
- 查询命令永远读缓存快照，零计算开销
- 触发源收敛（Phase 8.1/8.3）：全量重算仅由定时任务与管理命令（`/scorely admin refresh` / `admin reload`）触发，无频率配额（8.3 移除）；玩家进服与 `/scorely refresh` 走单玩家粒度重算（只更新对应玩家缓存条目），不触发全量重算

### D3: 积分规则 JSON 配置

- 服务器管理员可直接编辑
- 支持热重载（`/scorely admin reload`）
- 后期可扩展为更复杂的公式

### D4: 命令框架

直接使用 Brigadier API（Fabric 内置），无需额外抽象层。
相比 stats-scoreboard 的自定义 CommandNode DSL，Brigadier 更原生、文档更全。

### D5: 版本隔离架构（`compat/` 层）

- 将 Mixin（未来）和所有 `net.minecraft.*` 引用集中到 `compat/` 包
- 业务层（`scoring/`、`config/`、`util/`）禁止直接 import MC 内部类
- `CompatHelper` 承担在线玩家数据的内存读取（版本敏感），向业务层输出纯 Java 数据结构
- 为未来引入 Stonecutter 多版本构建预留最小迁移路径

---

## Phase 0 文件变更清单

- [ ] `settings.gradle` → `rootProject.name = 'scorely'`
- [ ] `gradle.properties` → 添加 `mod_id=scorely`
- [ ] `src/main/resources/fabric.mod.json` → 修改 id/name/description/entrypoints
- [ ] `src/main/resources/template-mod.mixins.json` → 重命名为 `scorely.mixins.json`，`package` 改为 `cc.lylighte.scorely.compat.mixin`
- [ ] `src/main/java/cc/lylighte/TemplateMod.java` → 重命名为 `Scorely.java`
- [ ] `src/main/java/cc/lylighte/mixin/ExampleMixin.java` → 删除（积分链路无 Mixin，纯定时全量重算）
- [ ] `src/main/resources/assets/template-mod/` → 重命名为 `assets/scorely/`
- [ ] `README.md` → 更新为 Scorely 项目说明
