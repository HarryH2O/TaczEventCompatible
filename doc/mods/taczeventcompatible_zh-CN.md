# TaczEventCompatible

> ℹ️ **注意：** 这是一个**非官方**社区模块，与 PVPArena 项目无关。如需帮助，请访问 [PVPArena Discord](https://discord.com/invite/a8NhSsXKVQ) 并 @**Harry_H2O**。或加入 PVPArena 中文社区 QQ 群 **1018932075**。

## 描述

本模块修复了 **Timeless and Classics Zero (TACZ)** 枪械模组与 PVPArena 在混合服务端（Forge + Bukkit）上的兼容性问题。

当玩家被 TACZ 武器射击时，伤害事件中的攻击者是子弹实体（`MohistModsEntity{TACZ_BULLET}`）而非 `Player`。PVPArena 的 EntityListener 仅处理攻击者为 `Player` 的伤害事件，因此 TACZ 枪械伤害被完全忽略——玩家会真实死亡而非触发 PVPArena 的假死机制，击杀也不会归属到射击者。

本模块拦截 TACZ 伤害事件，并以正确的玩家攻击者重新施加伤害，使 PVPArena 能够正常处理假死、重生、比分和击杀归属。

### 工作原理

1. **Forge 端**：监听 `EntityHurtByGunEvent.Pre` 和 `ExplosionEvent.Start`，记录受害者 → 攻击者 UUID 映射
2. **Bukkit 端**：以 `LOWEST` 优先级拦截 TACZ 子弹/爆炸伤害事件，取消原事件，构造新的 `EntityDamageByEntityEvent`（攻击者 = attackerPlayer，原因 = ENTITY_ATTACK），通过 Bukkit 事件总线触发
3. PVPArena 的 EntityListener（`HIGHEST` 优先级，`ignoreCancelled=true`）只看到新的 Player→Player 事件，正常处理

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
- **TACZ 模组**：服务器需安装 Timeless and Classics Zero
- **PVPArena**：版本 2.1.0+

## 安装

本模块的安装方式与普通模块相同。安装步骤请参阅文档中的[模块页面](../modules.md#installing-modules)。

1. 将模块 JAR 放入 `plugins/pvparena/mods/` 目录
2. 在竞技场配置中添加 `TaczEventCompatible`：
```yaml
modules:
  - TaczEventCompatible
```
3. 使用 `/pa <arena> reload` 重载竞技场

> ⚠️ **警告：** 请勿将此 JAR 放入 Forge 的 `mods/` 目录。它是 PVPArena 模块，不是 Forge 模组。

## 配置

本模块无需配置。在竞技场模块列表中启用后自动生效。

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
