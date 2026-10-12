# SEssentials

Paper-Plugin für Minecraft **26.2 / 26.3** (Java 25).

## Bauen

```bash
./mvnw clean package
```
Ergebnis: `target/SEssentials-1.0.0.jar` → in den `plugins/`-Ordner.
Voraussetzung: JDK 25+. Maven muss nicht installiert sein, `./mvnw` lädt es beim ersten Aufruf.

Kompiliert wird gegen `paper-api 26.2.build.112-stable`; das JAR läuft auch auf 26.3.
Für einen reinen 26.3-Build `paper.version` in der `pom.xml` z.B. auf `26.3.build.143-beta` setzen.

Optional: **LuckPerms** (Prefix/Suffix/Gruppen) und **PlaceholderAPI**.

## Lokaler Testserver

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
- LuckPerms und PlaceholderAPI sind nicht dabei. Zum Testen die JARs selbst nach `run/plugins/` legen.
- `/softrestart` endet lokal mit einem Stopp, da kein `restart-script` gesetzt ist.

## CI & Releases

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

## Befehle & Rechte

| Befehl | Recht | Beschreibung |
|---|---|---|
| `/softrestart <minuten>` | `sess.softrestart` | Neustart nach X Minuten planen (Alias `/srestart`) |
| `/softrestart cancel` | `sess.softrestart` | Geplanten Neustart abbrechen |
| `/softrestart status` | `sess.softrestart` | Restzeit anzeigen |
| `/sessentials reload` | `sess.reload` | Config neu laden (Alias `/sess`) |
| `/vanish` | `sess.vanish` | Unsichtbar ein/aus (Alias `/v`), bleibt über Relogs erhalten |
| `/vanish silent` | `sess.vanish` | Wie `/vanish`, aber ohne Fake-Join/Leave-Nachricht |
| `/vanish bypass` | `sess.vanish.bypass` | Schutzregeln im Vanish aufheben/wieder aktivieren (bis zum Verlassen) |
| – | `sess.vanish.auto` | Beim Joinen automatisch unsichtbar (Standard: niemand) |
| – | `sess.vanish.see` | Sieht versteckte Spieler und bekommt Join/Leave-Hinweise |
| – | `sess.chat.color` | &-Farbcodes im Chat |
| – | `sess.chat.minimessage` | MiniMessage-Farben/Formatierung im Chat |

## Softrestart-Ablauf
Countdown mit Chat-Ankündigungen (`announce-at`), Bossbar und Titel in den letzten Sekunden →
Spieler speichern → alle Spieler mit `kick`-Nachricht kicken → Welten speichern →
`final-command` (Standard `restart`) über die Konsole.

> `restart` startet nur neu, wenn in `spigot.yml` ein `restart-script` gesetzt ist
> (bzw. dein Host einen Auto-Restart hat). Sonst fährt der Server nur herunter.

## Join- / Leave-Nachrichten
Unter `join-quit` in der `config.yml` (`join`, `quit`). Alle Platzhalter unten sind nutzbar.
Leerer Text blendet die Nachricht aus, `enabled: false` lässt die Vanilla-Nachrichten unverändert.

## Vanish
Versteckte Spieler sind für alle ohne `sess.vanish.see` unsichtbar: in der Welt, im TAB,
in Tab-Completion und `/list`, in Spielerzahl und Spielerliste der Serverliste und in `<online>`.
Commands mit einem versteckten Spieler als erstem Argument (z.B. `/msg Name`) melden „Spieler nicht gefunden“.
Im Vanish sind Chat, Bauen, Abbauen, Schlagen (auch mit Projektilen) und Einsammeln blockiert.
Einzeln abschaltbar unter `vanish.protections`, pro Spieler aufhebbar mit `/vanish bypass`.

Beim Ein-/Ausblenden kommt eine Fake-Leave/Join-Nachricht (Texte aus `join-quit`).
Global abschaltbar mit `vanish.fake-messages: false`, einzeln mit `/vanish silent`.
Echtes Joinen/Verlassen im Vanish ist immer ohne Nachricht.
`vanish.fake-message-cooldown` begrenzt diese pro Spieler. Innerhalb des Cooldowns wird nichts gesendet.
Später wird nur gesendet, wenn sich der für andere sichtbare Status wirklich ändert.

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
