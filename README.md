# TaczEventCompatible

A **PVPArena module** that fixes compatibility between the [Timeless and Classics Zero (TACZ)](https://github.com/MCModderAnchor/TACZ) firearm mod and [PVPArena](https://github.com/Eredrim/pvparena) on hybrid servers (Forge + Bukkit).

[中文文档](README_zh-CN.md)

> **Unofficial community module** — not affiliated with the PVPArena project. For help, visit the [PVPArena Discord](https://discord.gg/pvparena) and ping **@Harry\_H2O**, or join the PVPArena Chinese Community QQ chat group **1018932075**.

***

## The Problem

On hybrid servers (Mohist, CatServer, Arclight, etc.), when a player is shot by a TACZ weapon, the Bukkit damage event's attacker is a bullet entity (`MohistModsEntity{TACZ_BULLET}`) rather than a `Player`. PVPArena's EntityListener only processes damage events where the attacker is a `Player`, so:

- Players **die for real** instead of triggering PVPArena's fake-death mechanism
- Kills are **not credited** to the shooter
- Explosion kills (grenades, RPGs) are attributed to **block explosion** instead of the player who fired

## How It Works

```
TACZ Bullet Hits Player
        │
        ▼
[Forge] EntityHurtByGunEvent.Pre / ExplosionEvent.Start
        │  TACZ_listener records: victim UUID → attacker UUID
        │
        ▼
[Bukkit] EntityDamageByEntityEvent (damager = TACZ_BULLET)
         or EntityDamageEvent (cause = BLOCK_EXPLOSION)
        │  PA_listener (LOWEST priority) intercepts:
        │  1. Looks up attacker UUID from KillInfoManager
        │  2. Cancels the original event
        │  3. Constructs a new EntityDamageByEntityEvent
        │     (damager = attackerPlayer, cause = ENTITY_ATTACK)
        │  4. Calls the new event via Bukkit event bus
        │
        ▼
[Bukkit] New EntityDamageByEntityEvent (damager = Player)
        │  PVPArena EntityListener (HIGHEST priority) processes normally:
        │  → Fake death ✓  → Respawn ✓  → Scoring ✓  → Kill attribution ✓
```

### Supported Damage Types

| Type       | Description                                 | Notes |
| ---------- | ------------------------------------------- | ----- |
| Direct hit | Bullet hits a player directly               | Fully supported |
| Headshot   | Bullet hits a player's head                 | Fully supported |
| Explosion  | Grenade, RPG, or other explosive ammunition | Kill is correctly scored, but the official kill message will most likely display as "killed by block explosion" |

### Known Limitations

- **Explosion kill broadcast**: When explosive ammunition (grenades, RPGs, etc.) kills a player, the kill is correctly credited and scored, but PVPArena's official kill message will most likely display as "killed by block explosion" instead of the attacker's player name. This is an inherent limitation of Mohist's event bridging — explosion damage has its `DamageCause` bridged as `BLOCK_EXPLOSION`, and PVPArena's `parseDeathCause()` generates the broadcast text accordingly. This cannot be bypassed without modifying PVPArena's core code.

## Requirements

- **Server**: Any Forge + Bukkit hybrid server (Mohist, CatServer, Arclight, etc.)
- **TACZ**: [Timeless and Classics Zero](https://github.com/MCModdingAnchor/TACZ) must be installed on the server
- **PVPArena**: Version 2.1.0+

## Installation

1. Download the latest release from [Releases](../../releases)
2. Place the JAR in `plugins/pvparena/mods/`
3. Add `TaczEventCompatible` to your arena's module list:

```yaml
modules:
  - TaczEventCompatible
```

1. Reload the arena with `/pa <arena> reload`

> **Do NOT** place this JAR in the Forge `mods/` directory. It is a PVPArena module, not a Forge mod.

## Configuration

No configuration needed. Works automatically once enabled.

## Tested Environment

| Component | Version                  |
| --------- | ------------------------ |
| Java      | 21.0.7 (65.0)            |
| Minecraft | 1.20.1                   |
| Server    | Mohist 1.20.1-97143d12   |
| API       | 1.20.1-R0.1-SNAPSHOT     |
| Forge     | 47.4.2                   |
| NeoForge  | 47.1.106                 |
| TACZ      | 1.1.7-hotfix             |
| PVPArena  | 2.1.0, 2.1.1-SNAPSHOT-b4 |

Theoretically upward compatible with all dependencies, provided that the hooked APIs and methods remain unchanged.

## Project Structure

```
src/main/java/com/Harry_H2O/pa_module/
├── Main.java              # PVPArena module entry point
├── KillInfoManager.java   # Thread-safe victim→attacker UUID mapping
└── event/
    ├── TACZ_listener.java  # Forge event listener (gun hit & explosion)
    └── PA_listener.java    # Bukkit event listener (damage redirect)
```

