# PolaroidDragon

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
  participation reward. Offline winners are paid on their next join.
- **Menus** — in-game GUIs for event status, the current top and the Hall of Fame.
- **Discord webhook** — optional spawn / death / timeout notifications.
- **Localization** — ships English and Spanish; add your own language file.

## Requirements

| | |
|---|---|
| Server | Paper 1.21+ |
| Java | 21+ |
| Optional | PlaceholderAPI, Vault (required for money rewards) |

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

## Building

```bash
mvn clean package
```

The jar lands in `target/PolaroidDragon.jar`. Requires JDK 21.

## License

See [LICENSE](LICENSE).
