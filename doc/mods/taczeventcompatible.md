# TaczEventCompatible

> ℹ️ **Notice:** This is an **unofficial** community module, not affiliated with the PVPArena project. If you need help, please visit the [PVPArena Discord](https://discord.gg/pvparena) and ping **@Harry_H2O**, or join the PVPArena Chinese Community QQ chat group **1018932075**.

## Description

This module fixes the compatibility issue between the **Timeless and Classics Zero (TACZ)** firearm mod and PVPArena on hybrid (Forge + Bukkit) servers.

When a player is shot by a TACZ weapon, the damage event's attacker is a bullet entity (`MohistModsEntity{TACZ_BULLET}`) rather than a `Player`. PVPArena's EntityListener only processes damage events where the attacker is a `Player`, so TACZ gun damage is completely ignored — players die for real instead of triggering PVPArena's fake-death mechanism, and kills are not credited to the shooter.

This module intercepts TACZ damage events and re-applies the damage with the correct player attacker, allowing PVPArena to handle fake death, respawn, scoring, and kill attribution as normal.

### How it works

1. **Forge side**: Listens to `EntityHurtByGunEvent.Pre` and `ExplosionEvent.Start` to record the victim → attacker UUID mapping
2. **Bukkit side**: Intercepts TACZ bullet/explosion damage events at `LOWEST` priority, cancels the original event, constructs a new `EntityDamageByEntityEvent` (damager = attackerPlayer, cause = ENTITY_ATTACK), and calls it via the Bukkit event bus
3. PVPArena's EntityListener (`HIGHEST` priority, `ignoreCancelled=true`) sees only the new Player→Player event and processes it normally

### Supported damage types

| Type | Description | Notes |
|------|-------------|-------|
| Direct hit | Bullet hits a player directly | Fully supported |
| Headshot | Bullet hits a player's head | Fully supported |
| Explosion | Grenade, RPG, or other explosive ammunition | Kill is correctly scored, but the official kill message will most likely display as "killed by block explosion" |

### Known limitations

- **Explosion kill broadcast**: When explosive ammunition (grenades, RPGs, etc.) kills a player, the kill is correctly credited and scored, but PVPArena's official kill message will most likely display as "killed by block explosion" instead of the attacker's player name. This is an inherent limitation of Mohist's event bridging — explosion damage has its `DamageCause` bridged as `BLOCK_EXPLOSION`, and PVPArena's `parseDeathCause()` generates the broadcast text accordingly. This cannot be bypassed without modifying PVPArena's core code.

## Requirements

- **Server**: Any Forge + Bukkit hybrid server (Mohist, CatServer, Arclight, etc.)
- **TACZ mod**: Timeless and Classics Zero must be installed on the server
- **PVPArena**: Version 2.1.0+

## Installation

Installation of this module can be done in a normal way. You'll find installation process in [modules page](../modules.md#installing-modules) of the doc.

1. Place the module JAR in `plugins/pvparena/mods/`
2. Add `TaczEventCompatible` to your arena's module list:
```yaml
modules:
  - TaczEventCompatible
```
3. Reload the arena with `/pa <arena> reload`

> ⚠️ **Warning:** Do NOT place this JAR in the Forge `mods/` directory. It is a PVPArena module, not a Forge mod.

## Config settings

This module has no configuration. It works automatically once enabled in the arena's module list.

## Tested environment

| Component | Version |
|-----------|---------|
| Java | 21.0.7 (65.0) |
| Minecraft | 1.20.1 |
| Server | Mohist 1.20.1-97143d12 |
| API | 1.20.1-R0.1-SNAPSHOT |
| Forge | 47.4.2 |
| NeoForge | 47.1.106 |
| TACZ | 1.1.7-hotfix |
| PVPArena | 2.1.0, 2.1.1-SNAPSHOT-b4 |

Theoretically upward compatible with all dependencies, provided that the hooked APIs and methods remain unchanged.
