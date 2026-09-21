# KachanovBans

[English](README_EN.md) | **Русский**

Плагин наказаний для Paper / Folia 1.21: баны, временные баны, муты, IP-баны, варны с автолестницей, история, Discord/Telegram-боты, защита сотрудников и наказание «Чёрный ник».

## Возможности

- `ban / tempban / unban`, `mute / tempmute / unmute`, `ipban / tempipban / ipunban`, `kick`
- Варны (`warn / unwarn`) с категориями и лестницей наказаний из `config.yml`
- `history`, `iphistory`, `staffhistory`, `dupeip`, `banlist`, `mutelist`
- `checkban`, `checkmute`, `checkvmute`, аудит voice-мутов Plasmo Voice (`vmutelist`)
- Подтверждение замены наказания (`punishconfirm`), флаги `-s` (тихо) и `-a` (без доказательств)
- Хранилище MySQL или JSON, автоснятие истёкших наказаний, миграция банов/мутов из Essentials
- Discord-бот (JDA) и Telegram-бот: уведомления, аудит команд, привязка сотрудников
- Защита сотрудников: Discord-привязка, access-ключи, 2FA, сессии по IP
- «Чёрный ник» (`blacknick / unblacknick`): задание с прогрессом, урезание HP, блок команд и дропа

## Требования

- Java 21
- Paper / Folia 1.21.1
- LuckPerms (обязателен, `depend`)
- Plasmo Voice (опционально, `softdepend`)
- MySQL (опционально, иначе JSON)

## Установка

1. Собери jar: `mvn clean package` → `target/kachanovbans.jar`
2. Положи jar в `plugins/`, запусти сервер, останови его
3. Настрой `plugins/KachanovBans/config.yml`, `messages.yml`, `discord.yml`
4. Запусти сервер снова

## Сборка

```bash
mvn clean package
```

Зависимости Paper/LuckPerms — `provided`, MySQL/Gson/JDA шейдятся в jar.

## Конфигурация

- `src/main/resources/config.yml` — хранилище, варны, боты, безопасность, чёрный ник
- `src/main/resources/messages.yml` — все сообщения плагина
- `src/main/resources/discord.yml` — Discord-webhook с embed-уведомлениями
- `src/main/resources/plugin.yml` — команды и права

Токены ботов в репозитории заменены плейсхолдерами. Свои токены вписывай только локально:

```yaml
bots:
  discord:
    token: "PUT-YOUR-DISCORD-BOT-TOKEN-HERE"
  telegram:
    token: "PUT-YOUR-TELEGRAM-BOT-TOKEN-HERE"
```

## Команды

| Команда | Описание |
|---|---|
| `/ban <ник> [причина] [-s]` | Перманентный бан |
| `/tempban <ник> <время> [причина] [-s]` | Временный бан (`10s`, `30m`, `2h`, `1d`) |
| `/unban <ник>` | Разбан |
| `/mute <ник> [причина] [-s]` | Перманентный мут |
| `/tempmute <ник> <время> [причина] [-s]` | Временный мут |
| `/unmute <ник>` | Размут |
| `/ipban <ip/ник> [причина]` | Бан IP навсегда |
| `/tempipban <ip/ник> <время> [причина]` | Бан IP на время |
| `/ipunban <ip>` | Снять IP-бан |
| `/kick <ник> [причина]` | Кик |
| `/warn <ник> <пункт> [причина]` | Варн по пункту из конфига |
| `/unwarn <ник> <пункт>` | Снять варн |
| `/history`, `/iphistory`, `/staffhistory`, `/dupeip` | История и поиск по IP |
| `/banlist`, `/mutelist`, `/vmutelist` | Списки наказаний |
| `/checkban`, `/checkmute`, `/checkvmute` | Проверка статусов |
| `/blacknick <ник> <задание>`, `/unblacknick <ник>` | Чёрный ник |
| `/punishconfirm` | Подтвердить замену наказания |
| `/kachanovbans reload` | Перезагрузка конфигов |
| `/kachanovbans migrate essentials` | Миграция из Essentials |
| `/access`, `/ds`, `/tg`, `/2fa` | Ключи и привязки сотрудников |

## Права

- `kachanovbans.*` — все права
- `kachanovbans.ban`, `.tempban`, `.unban`, `.mute`, `.tempmute`, `.unmute`
- `kachanovbans.ipban`, `.tempipban`, `.ipunban`, `.kick`
- `kachanovbans.warn`, `.unwarn`, `.history`, `.iphistory`, `.staffhistory`, `.dupeip`
- `kachanovbans.banlist`, `.mutelist`, `.vmutelist`
- `kachanovbans.checkban`, `.checkmute`, `.checkvmute`
- `kachanovbans.blacknick`, `.unblacknick`, `.confirm`, `.reload`, `.staff`
- `kachbans.blacknick.exempt` — иммунитет к чёрному нику
- `kachbans.evidence.bypass` — флаг `-a`
- `kachbans.punishment.protected` — защита от наказаний
- `kachbans.silent` — флаг `-s`
- `kachbans.webhook.bypass` — не слать в боты

## Хранилище

```yaml
storage-type: "mysql" # или "json"
mysql:
  host: "localhost"
  port: 3306
  database: "kachanovbans"
  user: "root"
  password: ""
```

## Лицензия

MIT — см. [LICENSE](LICENSE).
