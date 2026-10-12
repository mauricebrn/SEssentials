# SEssentials

Paper-Plugin für Minecraft **26.2 / 26.3** (Java 25).

**Funktionen:**
- Softrestart mit Countdown, Bossbar und Titel
- Chat-Format pro LuckPerms-Gruppe
- TAB-Liste (Header/Footer, Namen) und Nametags mit Sortierung
- Konfigurierbare Join- / Leave-Nachrichten
- Vanish mit Schutzregeln, Fake-Nachrichten und gefälschter Serverliste
- `/fly`, `/heal`, `/feed`, `/god`
- Report-System (`/report`) mit Admin-Übersicht
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

Alle Rechte sind standardmäßig `op`, außer `sess.report` (alle) und `sess.vanish.auto` (niemand).

| Befehl | Recht | Beschreibung |
|---|---|---|
| `/admin` | `sess.admin` | Admin-Panel öffnen |
| `/sessentials reload` | `sess.reload` | Config neu laden (Alias `/sess`) |
| `/softrestart <minuten>` | `sess.softrestart` | Neustart nach X Minuten planen (Alias `/srestart`) |
| `/softrestart cancel` | `sess.softrestart` | Geplanten Neustart abbrechen |
| `/softrestart status` | `sess.softrestart` | Restzeit anzeigen |
| `/vanish` | `sess.vanish` | Unsichtbar ein/aus (Alias `/v`), bleibt über Relogs erhalten |
| `/vanish silent` | `sess.vanish` | Wie `/vanish`, aber ohne Fake-Join/Leave-Nachricht |
| `/vanish bypass` | `sess.vanish.bypass` | Schutzregeln im Vanish aufheben/wieder aktivieren (bis zum Verlassen) |
| `/report <spieler> <grund>` | `sess.report` | Spieler melden (Alias `/melden`) |
| `/reports` | `sess.reports.admin` | Report-Übersicht öffnen (Aliase `/rlist`, `/reportadmin`) |
| `/clearreports` | `sess.reports.admin` | Alle offenen Reports löschen |
| `/fly [spieler]` | `sess.fly` / `sess.fly.others` | Flugmodus ein/aus |
| `/heal [spieler]` | `sess.heal` / `sess.heal.others` | Volle Gesundheit, Feuer aus |
| `/feed [spieler]` | `sess.feed` / `sess.feed.others` | Hunger und Sättigung auffüllen |
| `/god [spieler]` | `sess.god` / `sess.god.others` | Unverwundbar ein/aus (bis zum Verlassen) |
| – | `sess.vanish.auto` | Beim Joinen automatisch unsichtbar |
| – | `sess.vanish.see` | Sieht versteckte Spieler und bekommt Join/Leave-Hinweise |
| – | `sess.report.notify` | Benachrichtigung (mit Sound) bei neuen Reports |
| – | `sess.report.bypasscooldown` | Ignoriert den Cooldown von `/report` |
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
Spieler mit `sess.report.notify` bekommen jede Meldung sofort mit Sound.

`/reports` (oder die Kachel im Admin-Panel) zeigt alle offenen Reports, 45 pro Seite.
Ein Klick öffnet die Details mit **Teleportieren**, **Als erledigt markieren** und **Löschen**.
Reports liegen in `plugins/SEssentials/reports.yml`; erledigte bleiben dort mit `status: resolved` erhalten.
Texte unter `reports.messages`, zusätzlich mit `<id>`, `<target>`, `<reporter>` und `<reason>`.

## Spieler-Befehle
`/fly`, `/heal`, `/feed` und `/god` wirken ohne Argument auf dich selbst und mit `<spieler>` auf andere
(Recht `.others`). Von der Konsole nur mit `<spieler>`.
Texte unter `player-commands.messages`. In `other` und `player-only` steht zusätzlich `<command>` zur Verfügung.

## Admin-Panel
`/admin` öffnet ein Kisten-Menü. Jede Anwendung ist eine Kachel und erscheint nur mit ihrem eigenen Recht
(z.B. Vanish nur mit `sess.vanish`). Aktuell:
- **Vanish** – umschalten, zeigt den Status
- **Befehle** – Untermenü mit Fly, God, Heal, Feed (jeweils nur mit dem Recht des Befehls)
- **Reports** – zeigt die Zahl offener Reports und öffnet die Report-Übersicht
- **Softrestart** – Untermenü mit Zeiten und Abbrechen
- **Config neu laden**

Der Titel steht unter `admin.title`.

Neue Anwendungen in `SEssentials#registerAdminApps` mit `adminPanel.addApp(recht, icon, aktion)` einhängen.
Untermenüs nutzen `de.sessentials.admin.Menu`.

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
./mvnw clean package
```
Ergebnis: `target/SEssentials-<version>.jar`.
Voraussetzung: JDK 25+. Maven muss nicht installiert sein, `./mvnw` lädt es beim ersten Aufruf.

Kompiliert wird gegen `paper-api 26.2.build.112-stable`; das JAR läuft auch auf 26.3.
Für einen reinen 26.3-Build `paper.version` in der `pom.xml` z.B. auf `26.3.build.143-beta` setzen.

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
`./mvnw -B verify` (Temurin JDK 25). Schlägt das Kompilieren fehl, ist der Run rot.
Das fertige JAR hängt als Artifact `SEssentials` am Run (Tab *Actions* → Run → *Artifacts*).

**Release veröffentlichen:**

1. `<version>` in der `pom.xml` erhöhen, z.B. `1.0.1`.
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

> Der Tag muss exakt zur `pom.xml`-Version passen (`v1.0.1` ↔ `1.0.1`).
> Sonst bricht der Release-Job ab und es wird nichts veröffentlicht.
> Dann Tag löschen (`git tag -d v1.0.1 && git push origin :v1.0.1`), Version korrigieren, neu taggen.
