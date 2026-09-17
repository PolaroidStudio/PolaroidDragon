# PolaroidDragon

[![Build](https://github.com/PolaroidStudio/PolaroidDragon/actions/workflows/build.yml/badge.svg)](https://github.com/PolaroidStudio/PolaroidDragon/actions/workflows/build.yml)
[![Paper](https://img.shields.io/badge/Paper-1.21-0288D1)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-21-E76F00)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-Apache%202.0-D22128)](LICENSE)

Scheduled Ancestral Dragon boss events for Paper servers, with live damage
leaderboards, a persistent Hall of Fame and configurable rewards.

## What it does

An Ender Dragon spawns on a schedule you define in your own timezone. Every
player who damages it is tracked, a boss bar follows the fight, and when the
dragon dies the top damagers are paid out. Scores persist to SQLite or MySQL,
so the Hall of Fame survives restarts.

- **Scheduled events** — cron-like entries in your server's timezone, with a
  countdown and phase notifications (chat, action bar, title, sound).
- **Damage leaderboard** — live ranking during the fight, exposed through
  PlaceholderAPI.
- **Hall of Fame** — all-time stats backed by SQLite (default) or MySQL.
- **Rewards** — per-position money via Vault, plus a kill bonus and a
  participation reward. Money reaches offline winners immediately through
  Vault; any reward commands are queued and run on their next join.
- **Menus** — in-game GUIs for event status, the current top and the Hall of Fame.
- **Hunter NPCs** — optional FancyNpcs integration: an NPC takes the skin and
  name of the last player to kill the dragon, or of the last top damager.
- **Discord webhook** — optional spawn / death / timeout notifications.
- **Localization** — ships English and Spanish; add your own language file.

## Requirements

| | |
|---|---|
| Server | Paper 1.21 or newer |
| Java | 21 or newer |
| Optional | PlaceholderAPI, Vault (required for money rewards), FancyNpcs (hunter NPCs) |

Both are verified on startup. If either is below the minimum the plugin logs what
it found and disables itself, instead of failing later with an unrelated error.
When the version cannot be determined it starts anyway and logs a warning.

## Installation

1. Drop the jar in `plugins/`.
2. Start the server once to generate the configuration.
3. Edit `config.yml` (schedule, dragon, rewards) and `data.yml` (storage).
4. Run `/polaroiddragon reload`.

Changing the storage backend in `data.yml` requires a full restart, not a reload.

## Commands

All commands are also available under the `/pdragon` alias.

| Command | Permission |
|---|---|
| `/polaroiddragon info` | — |
| `/polaroiddragon top` | — |
| `/polaroiddragon placeholders` | — |
| `/polaroiddragon start` | `polaroiddragon.admin.start` |
| `/polaroiddragon spawn` | `polaroiddragon.admin.spawn` |
| `/polaroiddragon stop` | `polaroiddragon.admin.stop` |
| `/polaroiddragon reload` | `polaroiddragon.admin.reload` |

`polaroiddragon.admin` grants all four admin permissions and defaults to op.

## Configuration

| File | Holds |
|---|---|
| `config.yml` | Event schedule, dragon attributes, rewards, boss bar, phases |
| `data.yml` | Storage backend and connection settings |
| `menus.yml` | Every GUI layout, item, name and lore |
| `webhook.yml` | Discord webhook toggle, URL and embeds |
| `lang/` | Player-facing messages, one file per language |

Every player-facing string lives in a configuration file. Nothing is hardcoded.

> **Do not commit your `webhook.yml`** once you have filled in the URL — a
> Discord webhook URL is a credential.

## Hunter NPCs

With [FancyNpcs](https://modrinth.com/plugin/fancynpcs) installed, an NPC can
wear the skin and name of the last player to hunt the dragon. Two hunters are
tracked separately: the **last killer** (whoever landed the final blow) and the
**top damager** (whoever finished 1st in the ranking). They are often different
people, so each drives its own NPC.

The plugin never creates, moves or deletes an NPC — you own it. Setup:

1. Build and place the NPC yourself: `/npc create dragon_killer`
2. Put that exact name in `config.yml` under `npc.last-killer.name` (or
   `npc.top-damager.name`). An empty name disables that NPC.
3. Run `/polaroiddragon reload`.

The NPC updates when an event ends, and once about ten seconds after the server
starts. Leaving a name empty disables that NPC; removing FancyNpcs disables the
whole feature silently.

Both records are **sticky**: they keep the last valid value until a new one
replaces it. An event that times out has no killer, so the NPC keeps showing the
previous hunter rather than going blank.

If the skin does not visibly change, set `npc.respawn-on-skin-change: true` —
some clients only re-read a skin when the entity is re-added.

### Placeholders

| Placeholder | Value |
|---|---|
| `%polaroiddragon_last_killer_name%` | Who landed the final blow last time |
| `%polaroiddragon_last_killer_date%` | When that happened |
| `%polaroiddragon_top_damager_name%` | Who finished 1st last time |
| `%polaroiddragon_top_damager_date%` | When that happened |

These work without FancyNpcs — they only need PlaceholderAPI. Run
`/polaroiddragon placeholders` in-game for the full list.

## Building

```bash
mvn clean package
```

The jar lands in `target/PolaroidDragon.jar`. Requires JDK 21.

## License

See [LICENSE](LICENSE).
