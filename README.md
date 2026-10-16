# SEssentials

Paper-Plugin für Minecraft **26.2 / 26.3** (Java 25).

**Funktionen:**
- Softrestart mit Countdown, Bossbar und Titel
- Chat-Format pro LuckPerms-Gruppe
- TAB-Liste (Header/Footer, Namen) und Nametags mit Sortierung
- Konfigurierbare Join- / Leave-Nachrichten
- Vanish mit Schutzregeln, Fake-Nachrichten und gefälschter Serverliste
- `/fly`, `/heal`, `/feed`, `/god`
- `/nether` und `/end` zum Wechseln zwischen den Dimensionen
- Spawn-System: `/setspawn`, `/spawn` (kostenlos mit Recht, sonst vom Konto oder gegen Items)
- Konfigurierbare `/help`-Seiten mit klickbaren Befehlen
- Teleport-Anfragen (`/tpa`) mit einstellbaren Kosten
- `/enderchest` (`/ec`) mit einstellbaren Kosten
- Private Nachrichten: `/msg` und `/r` zum schnellen Antworten
- Digitale Bank: Items einzahlen, mit dem Konto bezahlen, `/pay`, optional als Vault-Economy
- Chestshops: Items aus Kisten für Taler kaufen und verkaufen
- Clans: gründen mit Farbe, Einladungen, Home per Abstimmung, gemeinsame Truhe und Clan-Konto
- Kopfgeld: Taler auf Spieler aussetzen – wer das Ziel tötet, bekommt Kopfgeld und Inventar
- Item-Tracker: wertvolle Items in Inventar und Enderchest mit Verlauf
- News-System mit Verwaltung im Admin-Panel, Planung und Laufzeit
- Report-System (`/report`) mit Admin-Übersicht
- Fairplay: Punkte-Rangliste, Combat-Log, Spawn-Kill und Kill-Farming, Strikes mit automatischer Strafe
- Strafen: Bans, Mutes, IP-Bans – frei oder per Vorlage-ID, zeitlich oder permanent
- ItemGuard: entfernt verbotene Items und Spawn-Eier, meldet automatisch
- CreativeLog: protokolliert Aktionen im Kreativmodus
- Client-Detector: erkennt Vanilla-, Mod- und bekannte Hack-Clients beim Join
- Webpanel: Statistiken aus dem Admin-Panel im Browser
- Admin-Panel (`/admin`) als zentrales Menü für alle Anwendungen

Alle Texte unterstützen MiniMessage und &-Farbcodes.
Optional: **LuckPerms** (Prefix/Suffix/Gruppen) und **PlaceholderAPI**.

## Installation

`SEssentials-<version>.jar` aus den [Releases](../../releases) in den `plugins/`-Ordner legen und den Server starten.
Die Konfiguration liegt danach in `plugins/SEssentials/config.yml`. Änderungen mit `/sess reload` übernehmen.

Neue Optionen aus Updates werden nicht automatisch in eine bestehende `config.yml` geschrieben.
Fehlt ein Abschnitt, gelten die Standardwerte. Zum Anpassen den Abschnitt aus der
[Standard-Config](src/main/resources/config.yml) übernehmen.

## Befehle & Rechte

Alle Rechte sind standardmäßig `op`, außer `sess.creativelog.bypass` (niemand), `sess.msg`, `sess.report`, `sess.tpa`, `sess.enderchest`, `sess.bank`, `sess.bank.pay`, `sess.bounty`, `sess.clan`, `sess.fairplay` und `sess.chestshop.create` (alle) sowie `sess.vanish.auto` (niemand).

| Befehl | Recht | Beschreibung |
|---|---|---|
| `/news` | – | Aktuelle News (Alias `/neuigkeiten`) |
| `/news read <id>` | – | Ganze Nachricht lesen |
| `/newsadmin` | `sess.news.admin` | News-Verwaltung öffnen |
| `/help [seite]` | – | Hilfeseiten aus der Config (Alias `/?`) |
| `/admin` | `sess.admin` | Admin-Panel öffnen |
| `/webpanel [logout]` | `sess.webpanel` | Login-Link fürs Webpanel bzw. alle eigenen Sitzungen beenden |
| `/sessentials reload` | `sess.reload` | Config neu laden (Alias `/sess`) |
| `/softrestart <minuten>` | `sess.softrestart` | Neustart nach X Minuten planen (Alias `/srestart`) |
| `/softrestart cancel` | `sess.softrestart` | Geplanten Neustart abbrechen |
| `/softrestart status` | `sess.softrestart` | Restzeit anzeigen |
| `/vanish` | `sess.vanish` | Unsichtbar ein/aus (Alias `/v`), bleibt über Relogs erhalten |
| `/vanish silent` | `sess.vanish` | Wie `/vanish`, aber ohne Fake-Join/Leave-Nachricht |
| `/vanish bypass` | `sess.vanish.bypass` | Schutzregeln im Vanish aufheben/wieder aktivieren (bis zum Verlassen) |
| `/report <spieler> <grund>` | `sess.report` | Spieler melden (Alias `/melden`) |
| `/reports` | `sess.reports.admin` | Report-Übersicht öffnen (Aliase `/rlist`, `/reportadmin`) |
| `/punish <spieler> <id>` | Recht des Typs | Strafe nach Vorlage aus `punishments.templates` |
| `/ban <spieler> [dauer\|perm] [grund]` | `sess.punish.ban` | Bannen (ersetzt Vanilla-`/ban`); ohne Dauer permanent |
| `/mute <spieler> [dauer\|perm] [grund]` | `sess.punish.mute` | Muten: blockiert Chat und `mute-blocked-commands` |
| `/ipban <spieler\|ip> [dauer\|perm] [grund]` | `sess.punish.ipban` | IP-Bann (aktuelle oder letzte bekannte IP) |
| `/unban`, `/unmute`, `/unbanip <spieler>` | Recht des Typs | Strafe aufheben (`/unbanip` auch mit IP) |
| `/fairplay [spieler]`, `/fairplay top` | `sess.fairplay` | Fairplay-Punkte, Strikes und Rang bzw. Rangliste; mit `sess.fairplay.admin` auch der Verstoß-Verlauf |
| `/fairplay reset <spieler>` | `sess.fairplay.admin` | Punkte und Strikes zurücksetzen |
| `/history <spieler>` | `sess.punish.history` | Strafen-Verlauf eines Spielers |
| `/itemscan [spieler]` | `sess.itemguard.scan` | Inventare sofort nach verbotenen Items scannen (Alias `/iscan`) |
| `/creativelog <spieler> [seite]` | `sess.creativelog.view` | Kreativ-Aktionen eines Spielers (Alias `/clog`) |
| `/clearreports` | `sess.reports.admin` | Alle offenen Reports löschen |
| `/fly [spieler]` | `sess.fly` / `sess.fly.others` | Flugmodus ein/aus |
| `/heal [spieler]` | `sess.heal` / `sess.heal.others` | Volle Gesundheit, Feuer aus |
| `/feed [spieler]` | `sess.feed` / `sess.feed.others` | Hunger und Sättigung auffüllen |
| `/god [spieler]` | `sess.god` / `sess.god.others` | Unverwundbar ein/aus (bis zum Verlassen) |
| `/spawn` | – / `sess.spawn` | Zum Spawn. Ohne `sess.spawn` kostenpflichtig (Standard 500 Taler vom Konto) |
| `/tpa <spieler>` | `sess.tpa` / `sess.tpa.free` | Teleport-Anfrage. Ohne `sess.tpa.free` kostenpflichtig (Standard 500 Taler) |
| `/msg <spieler> <text>` | `sess.msg` | Private Nachricht (auch `/tell`, `/w`, `/whisper`); ersetzt Vanilla-`/msg` |
| `/r <text>` | `sess.msg` | Antwort an den letzten Gesprächspartner (Alias `/reply`) |
| `/shop` | `sess.chestshop.create` | Chestshop per Menü erstellen (auf eine Kiste schauen) |
| `/enderchest` | `sess.enderchest` / `sess.enderchest.free` | Eigene Enderchest öffnen (Alias `/ec`). Ohne `.free` kostenpflichtig (Standard 50 Taler) |
| `/bank` | `sess.bank` | Bank-Menü: Kontostand, Einzahlen, Auszahlen, Kurse |
| `/bank einzahlen [hand]` | `sess.bank` | Alle passenden Items bzw. den Stack in der Hand einzahlen |
| `/bank auszahlen <betrag>` | `sess.bank` | Betrag in Items auszahlen (falls erlaubt) |
| `/balance [spieler]` | – | Kontostand (Aliase `/bal`, `/money`, `/geld`, `/kontostand`) |
| `/pay <spieler> <betrag>` | `sess.bank.pay` | Geld überweisen (Alias `/zahlen`) |
| `/kopfgeld [<spieler> <betrag>]` | `sess.bounty` | Kopfgeld vom Konto aussetzen; ohne Argumente die höchsten anzeigen (Alias `/bounty`). Der Töter (nicht der Aussetzende) bekommt Kopfgeld und Inventar. Laufzeit über `bounty.max-duration` (0 = unbegrenzt) |
| `/clan erstellen <name> <farbe>` | `sess.clan` | Clan gründen (kostet `clans.create-cost`); `/clan farbe` ändert die Farbe. Der Clan-Tag wird als LuckPerms-Prefix gesetzt (`clans.luckperms-prefix`; für Rang + Tag LuckPerms-Meta-Stacking `["highest_own", "highest_inherited"]`) |
| `/clan einladen\|kicken\|leiter <spieler>` | `sess.clan` | Mitglieder verwalten; `/clan annehmen\|ablehnen [clan]`, `/clan verlassen`, `/clan aufloesen` |
| `/clan info [clan]`, `/clan liste` | `sess.clan` | Clan-Infos bzw. alle Clans |
| `/clan home [vorschlagen]` | `sess.clan` | Zum Clan-Home (kostet `clans.home.cost`) bzw. aktuellen Ort zur Abstimmung stellen; `/clan abstimmen ja\|nein` |
| `/clan truhe [kaufen]` | `sess.clan` | Truhe aus der Ferne öffnen (`clans.chest.remote-cost`) bzw. vom Clan-Konto kaufen und nahe dem Home aufstellen |
| `/clan bank`, `/clan einzahlen\|auszahlen <betrag>` | `sess.clan` | Gemeinsames Clan-Konto |
| `/clan friendlyfire [an\|aus]` | `sess.clan` | Schaden unter Clan-Mitgliedern erlauben/verhindern (Standard `clans.friendly-fire-default`, wer umschalten darf: `clans.leader-only.friendly-fire`) |
| `/clan admin aufloesen <clan>` | `sess.clan.admin` | Clan zwangsweise auflösen (Truheninhalt geht an den Admin). Alles Weitere im Admin-Panel unter „Clans“: Home/Truhe besuchen, Truhe öffnen, Clan-Konto ändern, Mitglieder rauswerfen, Leiter bestimmen |
| `/kopfgeld entfernen <spieler>` | `sess.bounty.admin` | Alle Kopfgelder auf einen Spieler entfernen und erstatten; einzelne Gebote im Admin-Panel |
| `/baltop` | – | Die 10 reichsten Spieler |
| `/eco give\|take\|set <spieler> <betrag>` | `sess.bank.admin` | Konten verwalten |
| `/tpaccept [spieler]` | – | Anfrage annehmen (ohne Namen: neueste) |
| `/tpdeny [spieler]` | – | Anfrage ablehnen (ohne Namen: neueste) |
| `/setspawn` | `sess.setspawn` | Server-Spawn auf die eigene Position setzen |
| `/nether` | `sess.nether` | In den Nether; im Nether zurück in die Oberwelt |
| `/end` | `sess.end` | Ins End; im End zurück in die Oberwelt |
| – | `sess.vanish.auto` | Beim Joinen automatisch unsichtbar |
| – | `sess.vanish.see` | Sieht versteckte Spieler und bekommt Join/Leave-Hinweise |
| – | `sess.report.notify` | Benachrichtigung (mit Sound) bei neuen Reports |
| – | `sess.detector.notify` | Meldungen vom Client-Detector |
| – | `sess.report.bypasscooldown` | Ignoriert den Cooldown von `/report` |
| – | `sess.chestshop.create` | Chestshops erstellen |
| – | `sess.chestshop.admin` | Admin-Shops erstellen, alle Shops öffnen und abbauen |
| – | `sess.itemguard.bypass` | Wird vom ItemGuard nicht gescannt |
| – | `sess.itemguard.admin` | ItemGuard im Admin-Panel einstellen |
| – | `sess.creativelog.bypass` | Wird im Kreativmodus nicht protokolliert (Standard: niemand) |
| – | `sess.tracker` | Item-Tracker im Admin-Panel |
| – | `sess.chat.color` | &-Farbcodes im Chat |
| – | `sess.chat.minimessage` | MiniMessage-Farben/Formatierung im Chat |

## Softrestart
Countdown mit Chat-Ankündigungen (`announce-at`), Bossbar und Titel in den letzten Sekunden →
Spieler speichern → alle Spieler mit `kick`-Nachricht kicken → Welten speichern →
`final-command` (Standard `restart`) über die Konsole.

> `restart` startet nur neu, wenn in `spigot.yml` ein `restart-script` gesetzt ist
> (bzw. dein Host einen Auto-Restart hat). Sonst fährt der Server nur herunter.

## Chat, TAB & Nametags
- `chat.default-format` gilt für alle, `chat.group-formats` überschreibt es pro LuckPerms-Primärgruppe.
- `tab.header`, `tab.footer` und `tab.player-format` werden alle `tab.update-interval-ticks` aktualisiert.
- `nametag` setzt Prefix, Suffix und Namensfarbe über dem Kopf, pro Gruppe mit `priority` für die TAB-Sortierung.

## Join- / Leave-Nachrichten
Unter `join-quit` in der `config.yml` (`join`, `quit`). Alle [Platzhalter](#platzhalter) sind nutzbar.
Leerer Text blendet die Nachricht aus, `enabled: false` lässt die Vanilla-Nachrichten unverändert.

## Vanish
Versteckte Spieler sind für alle ohne `sess.vanish.see` unsichtbar:
- in der Welt und im TAB
- in Tab-Completion und `/list`
- in Spielerzahl und Spielerliste der Serverliste und in `<online>`
- in Commands: Ist ein versteckter Spieler das erste Argument (z.B. `/msg Name`), kommt „Spieler nicht gefunden“.

**Schutzregeln:** Im Vanish sind Chat, Bauen, Abbauen, Schlagen (auch mit Projektilen) und Einsammeln blockiert.
Einzeln abschaltbar unter `vanish.protections`, pro Spieler aufhebbar mit `/vanish bypass`.

**Fake-Nachrichten:** Beim Ein-/Ausblenden kommt eine Fake-Leave/Join-Nachricht (Texte aus `join-quit`).
- Global abschaltbar mit `vanish.fake-messages: false`, einzeln mit `/vanish silent`.
- `vanish.fake-message-cooldown` (Sekunden) begrenzt Fake-Nachrichten pro Spieler. Innerhalb des Cooldowns wird nichts gesendet.
- Gesendet wird nur, wenn sich der für andere sichtbare Status wirklich ändert.
- Echtes Joinen/Verlassen im Vanish ist immer ohne Nachricht.

**Grenzen:** Selektoren wie `@a` erfassen versteckte Spieler weiterhin. Mobs greifen sie weiter an.
PAPI-Platzhalter anderer Plugins (z.B. `%server_online%`) zählen sie mit.

## Reports
`/report <spieler> <grund>` meldet einen Spieler, der schon einmal auf dem Server war.
Selbst melden geht nicht. Zwischen zwei Meldungen liegt `reports.cooldown` (Sekunden).
Spieler mit `sess.report.notify` bekommen jede Meldung sofort mit Sound (abschaltbar unter **Benachrichtigungen** im Admin-Panel).

`/reports` (oder die Kachel im Admin-Panel) zeigt alle offenen Reports, 45 pro Seite.
Ein Klick öffnet die Details mit **Teleportieren**, **Als erledigt markieren** und **Löschen**.
Reports liegen in `plugins/SEssentials/reports.yml`; erledigte bleiben dort mit `status: resolved` erhalten.
Texte unter `reports.messages`, zusätzlich mit `<id>`, `<target>`, `<reporter>` und `<reason>`.

## Fairplay
Jeder Spieler hat Fairplay-Punkte (`fairplay.start-score`, Standard 100); `/fairplay top` zeigt die Rangliste.
Verstöße kosten Punkte (`fairplay.violations`, `points: 0` schaltet einen Verstoß ab):

- **Combat-Log** – Server verlassen bis `combat-tag-seconds` nach einem PvP-Treffer. Mit `combat-log-kill` stirbt der Spieler dabei (Items fallen). Kicks zählen nie, Timeouts nur mit `count-timeouts`.
- **Spawn-Kill** – einen Spieler bis `spawn-kill.seconds` nach seinem Respawn töten.
- **Kill-Farming** – denselben Spieler `kill-farming.kills`-mal in `kill-farming.minutes` töten.

Bei `strike-threshold` Punkten oder weniger gibt es einen Strike, danach beginnt der Stand wieder beim Startwert.
Ab `strikes-for-punishment` aktiven Strikes (sie verfallen nach `strike-expire-days`) wird automatisch die Strafvorlage
`punishment-template` vergeben (Standard: Vorlage 8, 1 Tag Ban) – im Verlauf mit „Fairplay“ als Urheber.
Fair spielen bringt alle `recovery.interval-minutes` `recovery.points` Punkte zurück.

Geldstrafen: jeder Verstoß kann zusätzlich `fine` kosten, jeder Strike `strike-fine`. Abgezogen wird vom Bankkonto
in den Server-Topf, höchstens bis 0 (nie ins Minus); ohne Bank-Modul gibt es keine Geldstrafen.

Im Admin-Panel (Kachel „Fairplay“, Recht `sess.fairplay.admin`) gibt es die Rangliste mit Verlauf pro Spieler,
Strike entfernen und Zurücksetzen. Das Webpanel zeigt unter „Fairplay“ Kennzahlen, auffällige Spieler, die Rangliste
und die letzten Verstöße.

## Strafen
Bans, Mutes und IP-Bans, zeitlich begrenzt oder permanent:
- **Vorlagen:** `/punish <spieler> <id>` – IDs mit Typ, Dauer und Grund unter `punishments.templates`
  (Tab-Completion zeigt die Vorlagen). Erlaubt, wenn man das Recht des Typs hat.
- **Frei:** `/ban`, `/mute`, `/ipban <spieler> [dauer|perm] [grund]`. Dauer z.B. `30m`, `2h`, `7d`, `1d12h`, `1mo`, `1y`;
  ohne Dauer permanent. `/ipban` nimmt die aktuelle bzw. letzte bekannte IP des Spielers oder direkt eine IP.
- **Aufheben/Verlauf:** `/unban`, `/unmute`, `/unbanip`, `/history <spieler>`.

Bans und IP-Bans greifen schon beim Login (mit Grund, Restdauer und ID auf dem Kick-Bildschirm); Online-Spieler werden
sofort gekickt. Gemutete können nicht chatten und die `mute-blocked-commands` nicht nutzen. Eine neue Strafe ersetzt
eine aktive gleichen Typs. Staff mit `sess.punish.notify` wird informiert, `broadcast: true` sagt Bans/Mutes allen an.
Spieler mit `sess.punish.exempt` (Standard: op) können nur von der Konsole bestraft werden.

Admin-Panel (Moderation → **Strafen**): aktive Strafen mit Grund, Restzeit, Urheber und ID – Rechtsklick hebt auf;
dazu die Vorlagen. Gespeichert in `plugins/SEssentials/punishments.yml` (inkl. letzter IP je Spieler für IP-Bans).
`/ban` ersetzt den Vanilla-Befehl; die Vanilla-Bannliste (`banned-players.json`) bleibt davon getrennt.

## ItemGuard
Entfernt nicht survival-taugliche Items (Befehlsblöcke, Barrieren, Spawner, Grundgestein, Debug-Stick, … – Liste
`itemguard.banned`) und auf Wunsch alle Spawn-Eier aus Inventar, Enderchest und Shulkerkisten darin.
Gescannt wird beim Join und alle `interval-seconds`; Spawn-Eier werden schon beim Benutzen blockiert.

Jeder Fund wird als Report angelegt (Absender „ItemGuard“, Details mit Mengen) und – wie Reports – an Admins mit
`sess.report.notify` gemeldet (abschaltbar unter **Benachrichtigungen**). Der Spieler bekommt eine Nachricht,
die Konsole ein Protokoll. Spieler mit `sess.itemguard.bypass` (Standard: op) werden nicht gescannt.

Im Admin-Panel (Moderation → **ItemGuard**): Schalter für ItemGuard, Spawn-Eier, Enderchest und Shulker,
verbotene Items ansehen und per Rechtsklick entfernen, „Item aus der Hand verbieten“, „Alle jetzt scannen“.
Änderungen werden in der `config.yml` gespeichert.

## CreativeLog
Protokolliert alle Aktionen von Spielern im Kreativmodus – außer mit `sess.creativelog.bypass` (Standard: niemand,
auch Ops werden protokolliert): Moduswechsel mit Position, Join/Quit im Kreativmodus, Items aus dem Kreativ-Inventar,
geöffnete Container, Droppen/Aufheben, Blöcke setzen/abbauen, Rechtsklicks auf Blöcke und Entities, Schaden, Befehle.

Abruf mit `/creativelog <spieler> [seite]` (neueste zuerst, klickbar blättern) oder im Admin-Panel
(Moderation → **CreativeLog**: Spieler mit Log, wer gerade im Kreativmodus ist, Ein/Aus-Schalter).
Pro Spieler bleiben `max-entries` Einträge; gespeichert in `plugins/SEssentials/creativelog/` (alle 30 s, beim Verlassen und beim Stoppen).

## Client-Detector
Prüft jeden Spieler kurz nach dem Join (`check-delay-ticks`) anhand von Client-Brand und Plugin-Channels:
- **Vanilla:** Brand `vanilla` und keine Plugin-Channels
- **Mod-Client:** alles andere (Fabric, Forge, Lunar, Feather, …, auch „vanilla“ mit Channels)
- **Hack-Client:** Brand oder ein Channel enthält einen Eintrag aus `hack-signatures`

Bei einem Hack-Client entsteht eine Meldung in der Report-Übersicht (Absender „Client-Detector“, Fernrohr-Symbol)
mit Brand, Sprache, UUID, Treffer und allen Channels. Pro Spieler und Treffer gibt es höchstens eine offene Meldung.
Mit `report-modded: true` auch für Mod-Clients.

Wer im Chat benachrichtigt wird, stellt jeder Admin selbst im Admin-Panel unter **Benachrichtigungen** ein:
Aus, nur Hack-Clients (Standard), alle Mod-Clients oder alle Joins. Voraussetzung ist `sess.detector.notify`.

> Die Erkennung ist bewusst leichtgewichtig (eine Prüfung pro Join, keine Tick-Last).
> Sie erwischt nur Clients, die sich nicht tarnen. Gute Hack-Clients geben sich als Vanilla oder Fabric aus.
> Für Verhaltens-Erkennung (Fly, KillAura, Speed, …) ist ein Anti-Cheat wie Grim nötig.

## Spieler-Befehle
`/fly`, `/heal`, `/feed` und `/god` wirken ohne Argument auf dich selbst und mit `<spieler>` auf andere
(Recht `.others`). Von der Konsole nur mit `<spieler>`.
Texte unter `player-commands.messages`. In `other` und `player-only` steht zusätzlich `<command>` zur Verfügung.

## Admin-Panel
`/admin` öffnet ein 6-reihiges Menü mit einer Zeile pro Kategorie. Jede Anwendung ist eine Kachel und erscheint
nur mit ihrem eigenen Recht (z.B. Vanish nur mit `sess.vanish`); leere Kategorien werden ausgeblendet.

| Kategorie | Anwendungen |
|---|---|
| Moderation | **Strafen** (aktive Strafen, Rechtsklick hebt auf, Vorlagen), **Vanish** (umschalten, zeigt Status), **Reports** (Zahl offener Reports, Übersicht), **ItemGuard** (Schalter, verbotene Items, Sofort-Scan), **CreativeLog** (Spieler mit Log, Klick zeigt den Log) |
| Spieler & Teleport | **Befehle** – Untermenü „Spieler“ (Fly, God, Heal, Feed, Enderchest) und „Teleport“ (Spawn, Spawn setzen, Nether, End) |
| Wirtschaft | **Bank** (Geld im Umlauf, Konten ansehen und per Chat-Eingabe `+100` / `-50` / `=200` ändern), **Item-Tracker** |
| Server | **News** (Verwaltung), **Softrestart** (Zeiten, Abbrechen), **Config neu laden** |
| Einstellungen | **Benachrichtigungen** (Report-Meldungen an/aus, Client-Detector-Stufe) |

Der Titel steht unter `admin.title`.

Neue Anwendungen in `SEssentials#registerAdminApps` mit `adminPanel.addApp(kategorie, recht, icon, aktion)` einhängen.
Untermenüs nutzen `de.sessentials.admin.Menu` (`Menu.sectioned` für Kategorie-Layouts),
Texteingaben im Chat `ChatPrompt`.

## Hilfe
`/help [seite]` zeigt die Seiten aus `help.pages` im Chat und ersetzt die Vanilla-Hilfe.
Jede Zeile ist Text oder `{text: "...", permission: "..."}` – Letztere sieht nur, wer das Recht hat.
Seiten ohne sichtbare Zeile werden übersprungen, sodass Spieler keine leeren Staff-Seiten sehen.
Kopf-, Fußzeile und Blätter-Buttons sind unter `help` einstellbar (Platzhalter `<page>`, `<pages>`).

## Spawn
`/setspawn` speichert die eigene Position (inkl. Blickrichtung) in `plugins/SEssentials/spawn.yml`
und setzt sie zugleich als Welt-Spawn. Ohne gesetzten Spawn nutzt `/spawn` den Spawn der Hauptwelt.

`/spawn` kann jeder nutzen. Mit `sess.spawn` ist es kostenlos, sonst kostet es `spawn.cost` – siehe [Kosten](#kosten)
(Standard 500 Taler vom Konto). Die Kosten werden nach der [Wartezeit](#teleport-wartezeit) direkt vor dem Teleport abgezogen und bei einem fehlgeschlagenen Teleport erstattet.

Neue Spieler landen beim ersten Join am Spawn, Respawns ohne Bett/Anker exakt dort (ohne Vanilla-Streuradius).

## Teleport-Wartezeit
`/spawn`, `/tpa` (nach der Annahme), `/nether`, `/end` und `/clan home` teleportieren erst nach `teleport-warmup.seconds`
(pro Teleport änderbar unter `teleport-warmup.per-teleport`). Wer in der Zeit den Block verlässt (`cancel-on-move`,
Spielraum `move-tolerance-blocks`) oder Schaden nimmt (`cancel-on-damage`), bricht ab; Kosten fallen erst beim Teleport an.
Mit `sess.teleport.nowarmup` (Standard: op) gibt es keine Wartezeit. Admin-Teleports aus Admin-Panel und Reports sind sofort.

## News
`/news` zeigt alle aktiven News (höchste Priorität zuerst) mit Kurzfassung; ungelesene sind als **NEU** markiert.
Ein Klick oder `/news read <id>` zeigt die ganze Nachricht und markiert sie als gelesen.
Mit `news.show-on-join` erscheint die Übersicht beim Join (nach `join-delay-seconds`, nur wenn es News gibt).

**Verwaltung** über `/newsadmin` oder das Admin-Panel (Kategorie Server), Recht `sess.news.admin`:
- Übersicht aller News mit Status (Entwurf, Geplant, Aktiv, Abgelaufen); Rechtsklick schaltet die Sichtbarkeit um.
- Editor: Titel, Inhalt ersetzen/ergänzen, Priorität (Info, Normal, Wichtig, DRINGEND), Startzeit, Laufzeit,
  Veröffentlichen/Entwurf, Vorschau im Chat, Löschen (Shift + Klick).
- Texte und Zeiten werden im Chat eingegeben (`abbrechen` bricht ab). Zeiten deutsch oder englisch: `2h`, `7 Tage`, `1d 12h`.

Inhalt: MiniMessage oder &-Farbcodes, `[Text](/befehl)` für klickbare Befehle, `\n` für Zeilenumbrüche.
News liegen in `plugins/SEssentials/news.yml`; Design und Prioritäten unter `news` in der Config.

## Bank
Spieler zahlen Items auf ein digitales Konto ein und bezahlen damit Befehle, andere Spieler (`/pay`) und –
wenn [Vault](https://www.spigotmc.org/resources/vault.34315/) installiert ist – andere Plugins wie Shops.
Beträge sind ganze Einheiten (`bank.currency`), Konten liegen in `plugins/SEssentials/bank.yml`.

| Einstellung (`bank.`) | Wirkung |
|---|---|
| `materials` | Einzahlbare Materialien mit Wert pro Item. Das erste ist das Basis-Material. |
| `multiple-materials` | `false` = nur das Basis-Material zählt (eine Währung, z.B. nur Diamanten) |
| `allow-blocks` | `true` = auch Blöcke (Diamantblock = 9 Diamanten) ein- und auszahlen |
| `withdraw.enabled` | Ob man wieder auszahlen kann |
| `withdraw.mode` | `original` = in den eingestellten Materialien, größte Werte zuerst; `base` = nur im Basis-Material |
| `withdraw.presets` | Schnell-Buttons zum Auszahlen im `/bank`-Menü |
| `start-balance` | Startguthaben neuer Konten |

Nur unveränderte Items zählen (keine umbenannten oder verzauberten). Auszahlungen, die nicht genau in Items aufgehen,
zahlen den größtmöglichen Teil aus; der Rest bleibt auf dem Konto. Volles Inventar: Der Rest fällt vor die Füße.

## Chestshops
**Per Menü:** `/shop` eingeben, während man auf eine Kiste/ein Fass schaut (für einen Gebühren-Shop auf einen
beliebigen Block). Im Menü Typ (Item-Shop/Gebühr, AdminShop mit Recht), Item (Standard: erstes Item in der Kiste,
Button übernimmt das Item aus der Hand), Menge, Kauf- und Verkaufspreis per −/+ einstellen; Klick auf den Wert = Chat-Eingabe,
Rechtsklick auf einen Preis = aus. **Erstellen** setzt das Schild an die angeschaute Seite (verbraucht ein Schild aus dem
Inventar, nicht im Kreativmodus) und wachst es. Schutz-Plugins können das Platzieren wie gewohnt verhindern.

**Per Schild:** Schild an eine Kiste, Truhe oder ein Fass setzen (Wandschild oder obendrauf):

| Zeile | Inhalt |
|---|---|
| 1 | `[Shop]` – oder `[AdminShop]` (unbegrenzter Bestand, Taler vom/an den Server; Recht `sess.chestshop.admin`) |
| 2 | Menge pro Handel, z.B. `16` |
| 3 | Preise in Talern: `K 100` (Spieler kauft), `V 80` (Spieler verkauft) oder beides |
| 4 | leer = erstes Item in der Kiste (sonst Item in der Hand), oder ein Item-Name wie `DIAMOND` |

**Gebühren-Shop** (bezahlen ohne Item, z.B. für die Nutzung einer Farm): Zeile 2 = Beschreibung (z.B. `Farm`),
Zeile 3 = `K 50`, Zeile 4 = `Gebühr` (oder `Eintritt`, `Nutzung`, `Fee`, `-`). Braucht keine Kiste; Rechtsklick zahlt.
Gegen versehentliches Doppelzahlen gilt eine Sperre von 2 Sekunden pro Spieler und Schild.

**Rechtsklick** aufs Schild kauft, **Linksklick** verkauft. Bezahlt wird über die Bank direkt zwischen Kunde und Besitzer;
der Besitzer wird benachrichtigt, wenn er online ist. Es zählen nur Items, die exakt dem Shop-Item entsprechen
(gleiche Verzauberungen, Namen usw.).

Schutz: Kiste und Schild – auch der Block, an dem das Schild hängt – können nur der Besitzer und Admins öffnen bzw. abbauen (Besitzer: Sneak + Linksklick aufs Schild),
Trichter ziehen nichts heraus (Auffüllen per Trichter geht), Explosionen zerstören keine Shops.
Shops liegen in `plugins/SEssentials/shops.yml`, Texte unter `chestshop.messages`.

## Item-Tracker
Zählt die Items aus `tracker.items` im Inventar und in der Enderchest jedes Spielers, auf Wunsch inkl. Shulkerkisten
(`include-shulkers`). Erfasst wird beim Join, beim Verlassen und alle `interval-minutes` für alle Online-Spieler;
ein neuer Eintrag entsteht nur, wenn sich etwas geändert hat. Offline-Spieler behalten ihren letzten Stand.

Im Admin-Panel (Wirtschaft → **Item-Tracker**, Recht `sess.tracker`):
- **Server-Bestand** pro Item mit Veränderung seit 24 h und 7 Tagen und Wert nach den Bank-Kursen; Klick zeigt den Verlauf im Chat.
- **Spieler** nach Wert sortiert, mit Veränderung seit 24 h und Kontostand.
- **Spieler-Details:** Inventar, Enderchest und Gesamt pro Item mit Trends sowie Wert-Verlauf.
- **Server-Verlauf** des Gesamtwerts und **Jetzt erfassen**.

Verlauf: letzte 24 h vollständig, bis 7 Tage die letzte Erfassung pro Stunde, danach pro Tag, gelöscht nach `history-days`.
Gespeichert in `plugins/SEssentials/tracker/`. Werte richten sich nach `bank.materials` (Blöcke = 9 Items);
Items ohne Kurs werden nur gezählt.

## Kosten
`spawn.cost`, `tpa.cost` und `enderchest.cost` haben dasselbe Format:
```yaml
cost:
  type: money   # money = vom Bankkonto, item = in Items
  amount: 500   # Betrag bzw. Anzahl Items; 0 = kostenlos
  item: DIAMOND # nur bei type: item
```
In den Texten steht `<price>` für den Preis („500 Taler“ bzw. „5x Diamant“).
Ältere Configs ohne `type`, aber mit `item`, bleiben bei Item-Kosten.

## Teleport-Anfragen
`/tpa <spieler>` schickt eine Anfrage mit klickbarem **[Annehmen]** / **[Ablehnen]**. Sie verfällt nach `tpa.expire-seconds`.
Der Anfragende zahlt `tpa.cost` (siehe [Kosten](#kosten), Standard 500 Taler) erst bei Annahme; mit `sess.tpa.free` kostenlos.
Kann er bei Annahme nicht mehr bezahlen, wird der Teleport abgebrochen. Bei einem fehlgeschlagenen Teleport wird erstattet.
Versteckte Spieler (Vanish) können nicht angefragt werden. Eine neue Anfrage an denselben Spieler ersetzt die alte.

## Dimensionen
`/nether` und `/end` teleportieren wie ein Portal; im Nether bzw. End bringt derselbe Befehl zurück in die Oberwelt.
- **Oberwelt → Nether:** Koordinaten ÷ 8, sicherer Platz im selben Chunk unterhalb der Decke.
  Ohne freien Platz entsteht eine kleine Obsidian-Plattform.
- **Nether → Oberwelt:** Koordinaten × 8, auf der Oberfläche.
- **→ End:** Obsidian-Plattform bei 100 / 50 / 0, wie beim Betreten über ein Portal.
- **End → Oberwelt:** Respawn-Punkt (Bett/Anker), sonst Welt-Spawn.

Genutzt wird jeweils die erste geladene Welt der Dimension (Standard: `world`, `world_nether`, `world_the_end`).
Texte unter `dimensions.messages`.

## Item-Upgrader
Jedes Item hat einen Grundwert (`upgrader-values.yml`, sonst aus den Rezepten berechnet, ändern mit `upgrader.value-overrides`).
Davon getrennt gibt es einen **Einsatz-Wert** (beim Setzen) und einen **Gewinn-Wert** (als Ziel) – standardmäßig der
Grundwert, einzeln überschreibbar mit `upgrader.input-overrides` / `upgrader.output-overrides`. Am bequemsten im
Admin-Panel unter „Upgrader-Werte“ (`sess.upgrader.admin`): Linksklick = Einsatz-Wert, Rechtsklick = Gewinn-Wert,
Shift-Klick = beide zurücksetzen. Die Chance ist Hausfaktor × Einsatz-Wert / Gewinn-Wert. `/upgrader value` zeigt beide Werte.

## Webpanel
Zeigt Statistiken aus dem Admin-Panel im Browser – lesend bis auf die Glücksrad-Chancen (siehe unten): Online-Spieler (mit Vanish-Markierung, Ping, Kontostand),
TPS/MSPT/Laufzeit, offene Reports, aktive Strafen, Taler im Umlauf, reichste Spieler, Item-Tracker-Bestände mit
7-Tage-Trend und Wert-Verlauf als Diagramm sowie aktive News. Aktualisiert sich alle 30 Sekunden; IP-Adressen werden nie gezeigt.

**CreativeLog-Viewer** (`/creativelog` im Webpanel, nur mit `sess.creativelog.view`): alle Kreativ-Einträge durchsuchen und
filtern – nach Spieler (mit Hinweis, wer gerade im Kreativmodus ist), Art (Betreten, Verlassen, Join/Quit, Items, Blöcke,
Interaktion, Kampf, Befehle; mit Trefferzahl je Art), Volltext (z.B. `DIAMOND`, `/gamemode`, Koordinaten) und Zeitraum.
Neueste zuerst, 100 pro Seite mit „Mehr laden“, Suchtreffer hervorgehoben, Klick auf einen Namen filtert nach diesem Spieler.
Die Filter stehen in der Adresse (als Link teilbar); **CSV-Export** der gefilterten Einträge (bis 50.000 Zeilen).
Das Recht wird beim Erzeugen des Login-Links festgehalten.

**Glücksrad-Chancen pro Spieler** (Seite „Glücksrad“, nur mit `sess.gamble.admin`): Spieler auswählen, Gewicht je Feld
ändern (die Chance wird live angezeigt), speichern oder auf den Standard aus `gambling.segments` zurücksetzen. Gespeichert in
`gambling-odds.yml`, jede Änderung steht im Server-Log. Spieler sehen im `/gluecksrad`-Menü keine Chancen mehr.
Zusätzlich dämpft `gambling.warmup` hohe Gewinne (ab `high-multiplier` und den Jackpot) zu Beginn einer Sitzung und bei
wenig Einsatz: anfangs `min-factor` ihrer Gewichtung, voll erst nach `full-after-spins` Drehungen und `full-after-staked` Einsatz.

**Login:** Im Spiel `/webpanel` (Recht `sess.webpanel`) – es kommt ein persönlicher Link, 5 Minuten gültig und nur einmal
verwendbar. Auch in der Server-Konsole möglich; der Link erscheint dann dort. Danach bleibt man `session-hours` angemeldet (HttpOnly-Cookie). `/webpanel logout` beendet alle eigenen Sitzungen;
Sitzungen enden auch bei einem Server-Neustart.

**Erreichbarkeit:** Standard `0.0.0.0:8085` (Port beim Hoster freigeben). Das Panel spricht nur HTTP – für Zugriff über das
Internet einen Reverse-Proxy mit HTTPS davorsetzen (z.B. Caddy, nginx) und `webpanel.public-url` auf dessen Adresse setzen;
dann wird das Cookie zusätzlich als `Secure` markiert. `bind: 127.0.0.1` macht das Panel nur lokal erreichbar.
Spielerköpfe werden von `mc-heads.net` geladen.

## Platzhalter
`<player>`, `<displayname>`, `<world>`, `<online>`, `<max_players>`, `<prefix>`,
`<lp_prefix>`, `<lp_suffix>`, `<lp_group>`, `<lp_meta:key>`, `<papi:name>`, `%name%`.
Im Chat-Format zusätzlich `<message>`, in Softrestart-Texten `<time>`.

LuckPerms-Prefixe dürfen MiniMessage **oder** &-Codes (inkl. `&#RRGGBB`) enthalten.

## Hinweise
- Nametags & TAB-Sortierung laufen über Teams auf dem Haupt-Scoreboard (`sess_<priority>_<name>`).
  Plugins, die Spielern ein eigenes Scoreboard setzen, überschreiben die Nametags.
- Ist `tab.player-format` gesetzt, bestimmt es den TAB-Namen; leer lassen, um Team-Prefix/Suffix
  auch im TAB zu zeigen.

---

## Entwicklung

### Bauen

```bash
./gradlew build
```
Ergebnis: `build/libs/SEssentials-<version>.jar`.
Voraussetzung: JDK 25+. Gradle muss nicht installiert sein, `./gradlew` lädt es beim ersten Aufruf.
Der erste Build dauert einige Minuten: paperweight baut einmalig den Paper-Server zum Kompilieren.

Kompiliert wird gegen das Paper-Dev-Bundle `26.2.build.112-stable` (in `build.gradle.kts`); getestet auf 26.2 und 26.3.
Die Fake-Spieler nutzen Server-Interna (NMS). Nach einem Minecraft-Update deshalb das Dev-Bundle auf die
neue Version setzen, bauen und die Fake-Spieler auf dem Testserver prüfen.

### Lokaler Testserver

```bash
./test-server.sh          # Paper 26.3
./test-server.sh 26.2     # andere Paper-Version
```

Das Skript baut das Plugin, lädt den neuesten Paper-Build der Version nach `run/`
(SHA-256 geprüft), kopiert das JAR nach `run/plugins/` und startet den Server.
Verbinden mit `localhost`, beenden mit `stop` in der Konsole.

- Das Skript setzt `eula=true` – damit akzeptierst du die [Minecraft-EULA](https://aka.ms/MinecraftEULA).
- `run/` ist in `.gitignore`. Welt und Configs bleiben zwischen Starts erhalten;
  für einen frischen Server `run/` löschen.
- Es darf nur ein Server gleichzeitig in `run/` laufen. Vor dem Neustart den alten mit `stop` beenden.
- LuckPerms und PlaceholderAPI sind nicht dabei. Zum Testen die JARs selbst nach `run/plugins/` legen.
- `/softrestart` endet lokal mit einem Stopp, da kein `restart-script` gesetzt ist.

### CI & Releases

Die Pipeline liegt in `.github/workflows/build.yml` (GitHub Actions).

**Build-Check:** Jeder Push auf `main` und jeder Pull Request baut das Plugin mit
`./gradlew build` (Temurin JDK 25). Schlägt das Kompilieren fehl, ist der Run rot.
Das fertige JAR hängt als Artifact `SEssentials` am Run (Tab *Actions* → Run → *Artifacts*).

**Release veröffentlichen:**

1. `version` in der `build.gradle.kts` erhöhen, z.B. `1.0.1`.
2. Änderung committen und pushen:
   ```bash
   git commit -am "Release 1.0.1"
   git push
   ```
3. Passenden Tag mit `v`-Präfix setzen und pushen:
   ```bash
   git tag v1.0.1
   git push origin v1.0.1
   ```

Der Tag startet die Pipeline erneut. Nach erfolgreichem Build erstellt sie ein GitHub Release
mit `SEssentials-1.0.1.jar` und automatisch generierten Release Notes (Tab *Releases*).

> Der Tag muss exakt zur Version in der `build.gradle.kts` passen (`v1.0.1` ↔ `1.0.1`).
> Sonst bricht der Release-Job ab und es wird nichts veröffentlicht.
> Dann Tag löschen (`git tag -d v1.0.1 && git push origin :v1.0.1`), Version korrigieren, neu taggen.
