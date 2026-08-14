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
      "advancementValues": {
        "minecraft:adventure/kill_all_mobs": 100.0,
        "minecraft:end/kill_dragon": 200.0,
        "minecraft:nether/all_effects": 150.0
      }
    }
  ],
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

    // 查询：获取总榜（所有规则积分之和）
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
| **Phase 8.3** | 刷新频率约束简化：移除 admin 刷新配额（MAX_EXTRA_REFRESHES），全量重算仅受入口权限门禁约束（OP），不再限频；`admin reload` 统一走 `refreshNow()`（服务器未就绪时才延迟到下次定时） | 0.2 天 |
| **Phase 9** | 国际化（en_us + zh_cn） | 0.5 天 |
| **Phase 10** | 惩罚榜（负积分）：放开引擎/缓存 `>0` 过滤、分榜排序语义、惩罚规则策略（受伤/死亡，独立分榜 + 计入总榜） | 1 天 |
| **测试** | 边界情况 + 性能测试 + 多玩家验证 | 2 天 |

**总计**：约 15.7 天

> **关于负积分（Phase 10）**：数据源与规则层已兼容——`damage_taken`/`deaths` 等统计项就在现有全量统计表中，规则 `multiplier` 可为负值（线性乘法不限符号）。需改动的仅在：① `ScoringEngine`/`ScoreCache` 的 `>0` 过滤（负分玩家被丢弃）；② 分榜排序方向（惩罚榜通常扣最多排最前，需支持升序）；③ `cap` 正向封顶语义（负向封顶另行定义）；④ 总榜合计天然兼容，仅过滤条件放开。

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
