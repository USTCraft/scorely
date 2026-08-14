# Agent 辅助模组开发经验（Scorely 实践沉淀）

> 本文档基于 Scorely（Fabric + Minecraft 26.2 + Java 25）实际开发工作流提炼，
> 总结「Agent 辅助模组开发」的一般经验。内容以本项目真实发生的事件为锚点，
> 结论可直接迁移到其他 MC 版本 / 其他模组加载器（Forge、NeoForge、Quilt）项目。

---

## 1. 核心方法论：Spec 驱动的 Phase 工作流

本项目本质上采用 **spec 编程**（规范驱动开发）：每个功能 Phase 以文档规格为
先导，Agent 与用户围绕同一份「活文档」协作。

### 1.1 标准流程

```
用户提出设想
   ↓
① 研究阶段：读现有实现 + 验证版本 API + 输出研究报告（候选、语义映射、技术结论）
   ↓
② 决策阶段：AskUserQuestion 收集决策点（可多选判定源、默认值、管理模式等）
   ↓
③ 落档阶段：PLAN.md 写入（路线图行 ✅ / 详设注记 / 配置示例 / 命令表）
   ↓
④ 实施阶段：按 spec 逐文件修改（配置 → 逻辑 → 渲染 → 资源 → 文档）
   ↓
⑤ 验证阶段：IDE 问题检查 → gradle build → 本地 config 实测路径说明
   ↓
⑥ 实测阶段：用户起服务器实机验证 → 反馈 → 微调
   ↓
⑦ 提交阶段：一个 Phase 对应一个 commit（提交信息列改动点）
```

### 1.2 为什么有效

- **决策先于编码**：关键设计（排序方向、判定源、过滤位置）在写代码前定死，避免返工；
- **研究结论落档**：验证过的 API、弃用方案、边界语义写进 PLAN.md 注记，
  后续不再重复研究（如负积分候选研究、打星机制重定义依据）；
- **文档即规格**：配置示例、命令表、错误键清单本身就是可执行规格，
  实施时逐条对照，漏项可被检查出来；
- **失败可回退**：每个 Phase 独立 commit，回滚粒度清晰。

### 1.3 需求演进处理

用户中途重定义需求时（如 Phase 11 从「创造/旁观模式排除」重定义为
「打星机制 out-of-competition」）：

1. **重新研究**，不沿用原方案的技术前提；
2. **废弃原方案要落档弃用原因**（PLAN.md 注记写明：
   「模式信息仅运行时存在，无法处理离线玩家，收集层过滤无法根治离线污染」）；
3. 决策点重新收集（判定源、可见性、管理模式），不假设用户意图。

---

## 2. 版本 API 验证方法论（Fabric + 特定 MC 版本）

模组开发最大的风险源是「凭旧版知识写新版 API」。本项目固定动作：
**任何不熟悉的 MC 类/方法，先验证签名再写代码**。

### 2.1 javap 反编译验证（首选）

```powershell
& "C:\Program Files\Java\jdk-25.0.3\bin\javap.exe" -p -classpath `
  "C:\Users\<user>\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.2\minecraft-merged-deobf-26.2.jar" `
  net.minecraft.server.players.PlayerList | Select-String -Pattern "isOp|getOps"
```

要点：

- jar 路径在 Loom 缓存的 `minecraftMaven` 下，按版本号定位；
- Mojang official mappings 下 **26.1+ 未混淆**，javap 看到的类名/方法名即开发名，
  验证结果直接可用（本项目已验证：`ServerPlayer.language`、`NameAndId`、`PermissionSet` 等）；
- javap 不在 PATH 时用完整路径（Windows）；
- 输出落盘再读（`Out-File` + `Get-Content`），规避 PowerShell 多语句输出被吞。

### 2.2 大版本改名的典型案例（26.x）

| 旧版知识 | 26.2 实际 | 影响 |
|---|---|---|
| `PlayerList.isOp(GameProfile)` | `isOp(NameAndId)`（record，`new NameAndId(UUID, name)`） | OP 判定签名变化 |
| `ServerPlayer.getGameProfile()` | 不存在 | 在线玩家名用 `getName().getString()` |
| `ServerPlayer.language`（private 无 getter） | 同左 | 需 classTweaker 开放字段 |
| `StatType` 遍历 | 直接 `for (Stat<?> stat : type)` | 无需 raw 强转 |
| `ServerPlayer.getServer()` | 不存在 | 经 `((ServerLevel) player.level()).getServer()` |
| `ServerTickEvents.END_SERVER_TICK` 回调 | 回调签名带 `MinecraftServer` 参数 | 事件层签名 |

### 2.3 classTweaker / Access Widener

- header `classTweaker v1 official` **必须为文件第一行**；
- 用于开放私有字段（如 `ServerPlayer.language`）；
- **IDE lint 会误报**（不认识 classTweaker 的访问开放），
  以 `gradle build` 编译结果为准，不要被 IDE 报错带偏。

### 2.4 构建即验证

- 改完所有文件后先跑 IDE 问题检查，再 `gradlew.bat build -x test`；
- 编译通过 ≠ 逻辑正确，命令路径、边界语义靠服务器实测闭环。

---

## 3. 架构经验：版本隔离（预防性多版本适配）

### 3.1 compat/ 层集中版本差异

```
compat/        ← 唯一允许直接 import net.minecraft.* 的层（CompatHelper + mixin）
scoring/       ← 纯业务逻辑（只收 Map/List/record 等纯 Java 结构）
config/        ← 纯 Java + Gson（无 MC 类型）
util/          ← 零依赖
command/       ← Brigadier 接口稳定，轻度版本依赖
event/         ← Fabric API 事件（版本间较稳定）
```

- 业务层禁止 import `net.minecraft.*`：为未来引入 Stonecutter
  （注释宏预处理器，`./gradlew chiseledBuild` 多版本产出）预留最小迁移路径；
- `CompatHelper` 承担全部版本敏感读取（在线统计、进度、语言、OP 判定），
  向业务层输出纯 Java 结构（`Map<String,Integer>`、`Set<String>`、`boolean`）；
- 本项目的 ops.json 持久判定、per-player 语言读取均为该层的成果。

### 3.2 设计决策落档

重大决策（D1-D5：不做侧边栏、纯定时全量重算、JSON 配置、Brigadier、
版本隔离）全部记录在 PLAN.md「关键设计决策」章节，作为后续阶段的约束来源。

---

## 4. 实施与验证循环

### 4.1 单 Phase 实施顺序

1. **配置层先行**：`ScorelyConfig` 字段（Gson 缺省即默认，旧配置零回归）；
2. **逻辑层**：`ConfigManager`（加载/校验/写盘，先写盘成功再改内存）、
   `CompatHelper`（版本 API 封装）；
3. **展示层**：命令（子命令树 → 处理器 → 渲染）；
4. **资源层**：lang 键（zh_cn/en_us 同步，符号类文本也走语言表以便覆盖）；
5. **文档层**：PLAN.md 路线图 ✅ + 注记更新；
6. 构建 + 实测路径说明（本地 `run/config/scorely/config.json` 加示例，
   运行时数据 gitignore 不进仓库）。

### 4.2 数据一致性原则

- 配置写盘用**原子写**（`Config.saveAtomic`），先写盘成功再更新内存，
  持久化失败不产生内存/磁盘不一致；
- 校验失败保留旧配置生效（热重载语义），损坏文件改名 `.bak` 保留；
- 运行时数据（`run/`）与代码仓库严格分离。

### 4.3 验证清单

- [ ] IDE 问题检查无 error（classTweaker 误报除外）
- [ ] `gradle build` 通过
- [ ] 旧配置兼容（新字段缺省即默认，零回归）
- [ ] 离线玩家场景（持久判定源，如 OP 经 ops.json）
- [ ] 服务器实测（reload 热生效 + 命令路径 + 语言显示）

---

## 5. 工具链与平台坑（Windows PowerShell 实测）

| 坑 | 规避 |
|---|---|
| PowerShell 不支持 `&&` | 用 `;` 连接 |
| 多语句输出被吞 | 单行管道，或 `Out-File` 落盘再 `Get-Content` |
| `javap` 不在 PATH | 完整路径调用 |
| **git commit 消息含半角括号 `(Phase 11)`** | PowerShell 会把 `(…)` 当子表达式解析 → 命令失败；**消息用单引号包裹** |
| `\"` 转义无效 | PowerShell 转义是反引号；commit 消息内嵌双引号直接去掉 |
| classTweaker header 不在第一行 | 文件首行必须是 `classTweaker v1 official` |
| UTF-8 中文乱码（Bash 读文件） | 用专用读取工具而非 shell cat |

---

## 6. 可迁移的一般经验（其他加载器 / 其他 MC 版本）

1. **版本敏感 API 一律先验证再编码**：javap / 反编译 / source jar 三选一，
   不凭旧版记忆写新版代码；
2. **spec 先行**：功能设计落档（路线图 + 注记 + 示例），实施逐条对照；
3. **版本隔离层**：所有 `net.minecraft.*` 引用集中在适配层，
   业务层纯 Java——这是多版本（Stonecutter）与跨加载器（Architectury）迁移的前提；
4. **配置分层**：`config/` 纯 Java + Gson，字段缺省即默认，热重载校验通过才生效；
5. **i18n 分层**：语言表（目标语言 → en_us → key 回退链）+ per-player 语言 +
   配置覆盖表（服主可新增/覆盖键，符号类文本也走语言表）；
6. **日志与玩家消息分离**：后台日志英文（面向服主/开发者），
   玩家可见消息走语言表；
7. **数据一致性**：写盘原子化、先持久化后改内存、失败保留旧状态；
8. **提交纪律**：一个功能阶段一个 commit，消息列改动点，
   文档（PLAN）与代码同仓同步更新。

---

## 7. 本项目的经验载体索引

- `doc/PLAN.md` —— 路线图（Phase 0-11 ✅ + 测试阶段）+ 详设注记 + 配置示例 + 命令表 + 设计决策
- `src/main/java/cc/lylighte/scorely/compat/` —— 版本差异封装（API 验证成果）
- `src/main/resources/scorely.classtweaker` —— classTweaker 访问开放
- `src/main/resources/assets/scorely/lang/` —— 语言表（zh_cn/en_us）
- `run/config/scorely/config.json` —— 本地实测配置（不进 git）
