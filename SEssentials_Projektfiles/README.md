# SEssentials

Paper-Plugin für Minecraft **26.2 / 26.3** (Java 25).

## Bauen

```bash
mvn clean package
```
Ergebnis: `target/SEssentials-1.0.0.jar` → in den `plugins/`-Ordner.
Voraussetzung: JDK 25 und Maven 3.9+.

Kompiliert wird gegen `paper-api 26.2.build.112-stable`; das JAR läuft auch auf 26.3.
Für einen reinen 26.3-Build `paper.version` in der `pom.xml` z.B. auf `26.3.build.143-beta` setzen.

Optional: **LuckPerms** (Prefix/Suffix/Gruppen) und **PlaceholderAPI**.

## Befehle & Rechte

| Befehl | Recht | Beschreibung |
|---|---|---|
| `/softrestart <minuten>` | `sess.softrestart` | Neustart nach X Minuten planen (Alias `/srestart`) |
| `/softrestart cancel` | `sess.softrestart` | Geplanten Neustart abbrechen |
| `/softrestart status` | `sess.softrestart` | Restzeit anzeigen |
| `/sessentials reload` | `sess.reload` | Config neu laden (Alias `/sess`) |
| – | `sess.chat.color` | &-Farbcodes im Chat |
| – | `sess.chat.minimessage` | MiniMessage-Farben/Formatierung im Chat |

## Softrestart-Ablauf
Countdown mit Chat-Ankündigungen (`announce-at`), Bossbar und Titel in den letzten Sekunden →
Spieler speichern → alle Spieler mit `kick`-Nachricht kicken → Welten speichern →
`final-command` (Standard `restart`) über die Konsole.

> `restart` startet nur neu, wenn in `spigot.yml` ein `restart-script` gesetzt ist
> (bzw. dein Host einen Auto-Restart hat). Sonst fährt der Server nur herunter.

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
