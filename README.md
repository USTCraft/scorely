# Scorely

> Minecraft Fabric 服务端模组 — 统计玩家进度、计算积分、提供查询

## 简介

Scorely 通过拦截游戏内统计事件，实时计算玩家积分，并通过 `/scorely` 命令提供查询功能。
适用于社区服务器依据积分数据发放奖励等场景。

## 环境要求

- Minecraft: 26.2
- Fabric Loader: >= 0.19.3
- Fabric API
- Java: >= 25

## 构建

```bash
./gradlew build
```

构建产物位于 `build/libs/`。

## 许可证

[CC0-1.0](LICENSE)
