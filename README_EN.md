# KachanovBans

**English** | [Русский](README.md)

Punishment plugin for Paper / Folia 1.21: bans, temp-bans, mutes, IP-bans, warns with auto-escalation, history, Discord/Telegram bots, staff protection and the "Black Nick" punishment.

## Features

- `ban / tempban / unban`, `mute / tempmute / unmute`, `ipban / tempipban / ipunban`, `kick`
- Warns (`warn / unwarn`) with categories and punishment ladder from `config.yml`
- `history`, `iphistory`, `staffhistory`, `dupeip`, `banlist`, `mutelist`
- `checkban`, `checkmute`, `checkvmute`, Plasmo Voice voice-mute audit (`vmutelist`)
- Punishment replacement confirmation (`punishconfirm`), `-s` (silent) and `-a` (no evidence) flags
- MySQL or JSON storage, automatic expiry of punishments, Essentials bans/mutes migration
- Discord bot (JDA) and Telegram bot: notifications, command audit, staff linking
- Staff protection: Discord linking, access keys, 2FA, IP-based sessions
- "Black Nick" (`blacknick / unblacknick`): quest with progress, reduced HP, blocked commands and drops

## Requirements

- Java 21
- Paper / Folia 1.21.1
- LuckPerms (required, `depend`)
- Plasmo Voice (optional, `softdepend`)
- MySQL (optional, JSON otherwise)

## Installation

1. Build the jar: `mvn clean package` → `target/kachanovbans.jar`
2. Drop the jar into `plugins/`, start the server, then stop it
3. Configure `plugins/KachanovBans/config.yml`, `messages.yml`, `discord.yml`
4. Start the server again

## Build

```bash
mvn clean package
```

Paper/LuckPerms dependencies are `provided`, MySQL/Gson/JDA are shaded into the jar.

## Configuration

- `src/main/resources/config.yml` — storage, warns, bots, security, black nick
- `src/main/resources/messages.yml` — all plugin messages
- `src/main/resources/discord.yml` — Discord webhook with embed notifications
- `src/main/resources/plugin.yml` — commands and permissions

Bot tokens in the repository are replaced with placeholders. Put your real tokens only locally:

```yaml
bots:
  discord:
    token: "PUT-YOUR-DISCORD-BOT-TOKEN-HERE"
  telegram:
    token: "PUT-YOUR-TELEGRAM-BOT-TOKEN-HERE"
```

## Commands

| Command | Description |
|---|---|
| `/ban <nick> [reason] [-s]` | Permanent ban |
| `/tempban <nick> <time> [reason] [-s]` | Temp ban (`10s`, `30m`, `2h`, `1d`) |
| `/unban <nick>` | Unban |
| `/mute <nick> [reason] [-s]` | Permanent mute |
| `/tempmute <nick> <time> [reason] [-s]` | Temp mute |
| `/unmute <nick>` | Unmute |
| `/ipban <ip/nick> [reason]` | Permanent IP ban |
| `/tempipban <ip/nick> <time> [reason]` | Temporary IP ban |
| `/ipunban <ip>` | Remove IP ban |
| `/kick <nick> [reason]` | Kick |
| `/warn <nick> <point> [reason]` | Warn under a config point |
| `/unwarn <nick> <point>` | Remove a warn |
| `/history`, `/iphistory`, `/staffhistory`, `/dupeip` | History and IP lookup |
| `/banlist`, `/mutelist`, `/vmutelist` | Punishment lists |
| `/checkban`, `/checkmute`, `/checkvmute` | Status checks |
| `/blacknick <nick> <quest>`, `/unblacknick <nick>` | Black nick |
| `/punishconfirm` | Confirm punishment replacement |
| `/kachanovbans reload` | Reload configs |
| `/kachanovbans migrate essentials` | Migrate from Essentials |
| `/access`, `/ds`, `/tg`, `/2fa` | Staff keys and linking |

## Permissions

- `kachanovbans.*` — everything
- `kachanovbans.ban`, `.tempban`, `.unban`, `.mute`, `.tempmute`, `.unmute`
- `kachanovbans.ipban`, `.tempipban`, `.ipunban`, `.kick`
- `kachanovbans.warn`, `.unwarn`, `.history`, `.iphistory`, `.staffhistory`, `.dupeip`
- `kachanovbans.banlist`, `.mutelist`, `.vmutelist`
- `kachanovbans.checkban`, `.checkmute`, `.checkvmute`
- `kachanovbans.blacknick`, `.unblacknick`, `.confirm`, `.reload`, `.staff`
- `kachbans.blacknick.exempt` — black nick immunity
- `kachbans.evidence.bypass` — `-a` flag
- `kachbans.punishment.protected` — protection from punishments
- `kachbans.silent` — `-s` flag
- `kachbans.webhook.bypass` — skip bot posting

## Storage

```yaml
storage-type: "mysql" # or "json"
mysql:
  host: "localhost"
  port: 3306
  database: "kachanovbans"
  user: "root"
  password: ""
```

## License

MIT — see [LICENSE](LICENSE).
