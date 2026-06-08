# TaczEventCompatible

一个 **PVPArena 模块**，修复 [Timeless and Classics Zero (TACZ)](https://github.com/MCModderAnchor/TACZ) 枪械模组与 [PVPArena](https://github.com/Eredrim/pvparena) 在混合服务端（Forge + Bukkit）上的兼容性问题。

[English](README.md)

> **非官方社区模块** — 与 PVPArena 项目无关。如需帮助，请访问 [PVPArena Discord](https://discord.gg/pvparena) 并 @**Harry_H2O**。或加入 PVPArena 中文社区 QQ 群 **1018932075**。


---

## 问题描述

在混合服务端（Mohist、CatServer、Arclight 等）上，当玩家被 TACZ 武器射击时，Bukkit 伤害事件中的攻击者是子弹实体（`MohistModsEntity{TACZ_BULLET}`）而非 `Player`。PVPArena 的 EntityListener 仅处理攻击者为 `Player` 的伤害事件，因此：

- 玩家**真实死亡**而非触发 PVPArena 的假死机制
- 击杀**不会归属**到射击者
- 爆炸击杀（榴弹、火箭筒）被归类为**方块爆炸**而非玩家击杀

## 工作原理

```
TACZ 子弹命中玩家
        │
        ▼
[Forge] EntityHurtByGunEvent.Pre / ExplosionEvent.Start
        │  TACZ_listener 记录：受害者 UUID → 攻击者 UUID
        │
        ▼
[Bukkit] EntityDamageByEntityEvent（攻击者 = TACZ_BULLET）
         或 EntityDamageEvent（原因 = BLOCK_EXPLOSION）
        │  PA_listener（LOWEST 优先级）拦截：
        │  1. 从 KillInfoManager 查找攻击者 UUID
        │  2. 取消原始事件
        │  3. 构造新的 EntityDamageByEntityEvent
        │    （攻击者 = attackerPlayer，原因 = ENTITY_ATTACK）
        │  4. 通过 Bukkit 事件总线触发新事件
        │
        ▼
[Bukkit] 新的 EntityDamageByEntityEvent（攻击者 = Player）
        │  PVPArena EntityListener（HIGHEST 优先级）正常处理：
        │  → 假死 ✓  → 重生 ✓  → 比分 ✓  → 击杀归属 ✓
```

### 支持的伤害类型

| 类型 | 描述 | 备注 |
|------|------|------|
| 直接命中 | 子弹直接命中玩家 | 完全支持 |
| 爆头 | 子弹命中玩家头部 | 完全支持 |
| 爆炸 | 榴弹、火箭筒等爆炸性弹药 | 击杀正常计分，但官方击杀消息大概率显示为因方块爆炸而死 |

### 已知限制

- **爆炸击杀播报**：榴弹等爆炸性弹药击杀玩家时，击杀会正常计入分数和归属，但 PVPArena 的官方击杀消息大概率显示为"因方块爆炸而死"而非攻击者玩家名。这是 Mohist 混合端事件桥接的固有限制——爆炸伤害的 `DamageCause` 会被桥接为 `BLOCK_EXPLOSION`，PVPArena 的 `parseDeathCause()` 据此生成播报文本，目前无法在不修改 PVPArena 核心代码的情况下绕过。

## 依赖

- **服务端**：任意 Forge + Bukkit 混合端（Mohist、CatServer、Arclight 等）
- **TACZ**：服务器需安装 [Timeless and Classics Zero](https://github.com/MCModdingAnchor/TACZ)
- **PVPArena**：版本 2.1.0+

## 安装

1. 从 [Releases](../../releases) 下载最新版本
2. 将 JAR 放入 `plugins/pvparena/mods/` 目录
3. 在竞技场配置中添加 `TaczEventCompatible`：
```yaml
modules:
  - TaczEventCompatible
```
4. 使用 `/pa <arena> reload` 重载竞技场

> **请勿**将此 JAR 放入 Forge 的 `mods/` 目录。它是 PVPArena 模块，不是 Forge 模组。

## 配置

无需配置。在竞技场模块列表中启用后自动生效。

## 测试环境

| 组件 | 版本 |
|------|------|
| Java | 21.0.7 (65.0) |
| Minecraft | 1.20.1 |
| 服务端 | Mohist 1.20.1-97143d12 |
| API | 1.20.1-R0.1-SNAPSHOT |
| Forge | 47.4.2 |
| NeoForge | 47.1.106 |
| TACZ | 1.1.7-hotfix |
| PVPArena | 2.1.0, 2.1.1-SNAPSHOT-b4 |

理论上向上兼容所有依赖，前提是所钩子的 API 和方法保持不变。

## 项目结构

```
src/main/java/com/Harry_H2O/pa_module/
├── Main.java              # PVPArena 模块入口
├── KillInfoManager.java   # 线程安全的受害者→攻击者 UUID 映射
└── event/
    ├── TACZ_listener.java  # Forge 事件监听器（枪击 & 爆炸）
    └── PA_listener.java    # Bukkit 事件监听器（伤害重定向）
```
