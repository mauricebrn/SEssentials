# SEssentials

**An all-in-one server toolkit for Paper 26.2 / 26.3** — chat, TAB, moderation, economy, clans, an admin panel and much more in a single plugin. Every module can be turned on or off, and every message supports MiniMessage and `&` color codes.

> **Beta:** 1.3.0-beta.1 is a beta release. Please back up your server and report bugs before using it in production.

## Features

**Chat & display**
- Chat format per LuckPerms group
- TAB list (header/footer, names) and sorted nametags
- Configurable join and leave messages
- Clickable, configurable `/help` pages and a rules system

**Players**
- `/fly`, `/heal`, `/feed`, `/god`, `/enderchest`
- `/msg` and `/r` private messages
- `/tpa` teleport requests, `/spawn` and `/setspawn`, `/nether` and `/end` — with optional costs (money or items) and teleport delays
- Per-player time and weather: `/ptime`, `/pweather`
- Custom prefixes and suffixes: `/prefix <text> [player] [time]`, `/suffix …` — shown in chat, TAB and nametags, optionally temporary, **works without LuckPerms**
- Disposable elytras: jump off a cliff inside a launch area and get a temporary elytra that disappears on landing — locked in the chest slot, with a **boost on F**
- Separate inventories for Creative and Survival: `/gminv`

**Economy**
- Digital bank: deposit items, pay with your account, `/pay` — optionally provided as a **Vault** economy
- Chest shops: buy and sell items from chests
- Bounties: put money on a player; whoever kills the target gets the bounty and the inventory
- Item upgrader
- Wheel of fortune (in-game currency)

**Clans**
- Create clans with a color, invitations, clan home by vote, shared chest and clan bank

**Moderation**
- Vanish with protection rules, fake join/leave messages and an adjusted server list
- Punishments: bans, mutes, IP bans — free-form or from templates, temporary or permanent
- Report system (`/report`) with an admin overview
- Fairplay: score leaderboard, combat-log, spawn-kill and kill-farming detection, strikes with automatic punishment
- ItemGuard: removes forbidden items and spawn eggs and reports them
- CreativeLog: logs actions in Creative mode
- Item tracker: tracks valuable items in inventories and ender chests, with history
- Client detector: recognizes vanilla, modded and known hacked clients on join

**Administration**
- `/admin` panel as a central menu for all modules
- Web panel with statistics in the browser (login via one-time link from `/webpanel`)
- News system with scheduling and duration
- Soft restart with countdown, boss bar and title
- Fake players (`/fakeplayer`): players without a client that load chunks, can wander, fight or mine

## Requirements
- Paper **26.2 or 26.3**, Java **25**
- Optional: **LuckPerms** (prefixes, suffixes, groups — custom prefixes/suffixes also work without it), **PlaceholderAPI**, **Vault**

## Installation
1. Put `SEssentials-<version>.jar` into your `plugins/` folder and start the server.
2. Configure it in `plugins/SEssentials/`: `config.yml` (language, prefix, modules on/off), `config/<module>.yml` and `lang/<language>.yml`.
3. Languages: English (`en_US`) and German (`de_DE`) are included — set `language: en_US` and run `/sess reload`. Admin menu labels are currently German only.

New options from updates are added to your files automatically; existing values are kept.

## Permissions
All permissions default to OP, except these, which everyone has: `sess.msg`, `sess.report`, `sess.tpa`, `sess.enderchest`, `sess.bank`, `sess.bank.pay`, `sess.bounty`, `sess.clan`, `sess.fairplay`, `sess.chestshop.create`, `sess.ptime`, `sess.pweather`, `sess.durability` and `sess.elytra.use`. The full list of commands and permissions is in the [README](https://github.com/mauricebrn/SEssentials#befehle--rechte) (German).

New in 1.3.0:

| Permission | Default | Allows |
|---|---|---|
| `sess.prefix` / `sess.suffix` | OP | Set your own prefix / suffix |
| `sess.prefix.others` / `sess.suffix.others` | OP | Set them for other players, also offline |
| `sess.elytra.admin` | OP | Manage launch areas (`/elytra pos1`, `pos2`, `create`, `delete`, `list`) |
| `sess.elytra.use` | everyone | Receive a disposable elytra in launch areas |

## Good to know
- **Web panel:** disabled by default and bound to `127.0.0.1`. It speaks plain HTTP — put a reverse proxy with HTTPS (e.g. Caddy, nginx) in front of it before exposing it to the internet. It loads player avatars from mc-heads.net.
- **Auto-update:** disabled by default. If you enable it (`/autoupdate`), the plugin checks the project's GitHub releases once a day, verifies the jar (SHA-256) and installs it on the next restart. Beta releases are never installed automatically.
- **Fake players** appear in TAB and `/list` like real players. While an admin controls one, it temporarily gets that admin's permissions (including OP).
- **Wheel of fortune** is meant for in-game currency only. Selling in-game currency for real money to use on it may violate the [Minecraft Usage Guidelines](https://www.minecraft.net/usage-guidelines).
- No telemetry, no metrics, no license checks.

## Support
Report bugs and suggestions on [GitHub Issues](https://github.com/mauricebrn/SEssentials/issues).

## AI disclaimer
SEssentials is developed with the help of AI tools (Anthropic's Claude). Large parts of the code, as well as this description, were written with AI assistance. All changes are reviewed by the author before release, and the full source code is public on [GitHub](https://github.com/mauricebrn/SEssentials).

## License
MIT
