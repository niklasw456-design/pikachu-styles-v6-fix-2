# Pikachu Styles (Fabric, Minecraft 1.21.11)

Client-Mod: erkennt Texturepacks im `resourcepacks`-Ordner und lässt dich pro Kategorie
(Schwerter, Äxte, Spitzhacken, Schaufeln, Rüstungen, Obsidian, End Crystal (3D), Schild, Elytra, Bogen, Armbrust, Totem, Tränke, XP-Flasche, Raketen, ...) wählen, aus welchem Pack die Textur kommt.

## Bauen
1. Ordner in IntelliJ IDEA öffnen (JDK 21 einstellen), Gradle importieren lassen.
2. Zum Testen: Gradle-Task `Tasks > fabric > runClient`.
3. Fertige Mod: Task `build`, die Datei liegt in `build/libs/texturemixer-1.0.0.jar`.
   Zusammen mit Fabric API und Mod Menu in den `mods`-Ordner legen.

## Benutzen
- Menü öffnen: Mod Menu > "Pikachu Styles" > Konfigurieren-Symbol.
- Oben Reiter pro Kategorie, darunter ein Raster mit Vorschaubild pro Pack (Animationen laufen live). Klick = auswählen, "Standard" = nichts überschreiben.
- Unten: Taste zum Öffnen festlegen (standardmäßig nicht belegt). ESC = Abbruch, Entf = Taste löschen.
- Beim Schließen ("Fertig" oder ESC) wird `resourcepacks/texturemixer_output` erzeugt, ganz oben aktiviert und die Ressourcen neu geladen.
- Auswahl und Taste werden in `config/texturemixer.json` gespeichert.

## Neue Kategorie hinzufügen
In `Category.java` in `build()` eine Zeile ergänzen, z. B.
`l.add(simple("gapple", "Goldener Apfel", "item/golden_apple.png", "item/enchanted_golden_apple.png"));`

## Hinweise
- Es werden nur Texturen kopiert (assets/minecraft/textures). Packs mit eigenen 3D-Modellen
  für Items brauchen zusätzlich deren Modelle, das ist hier nicht enthalten.
- Alte Rüstungstexturen (`models/armor/*_layer_1/2.png`) werden automatisch ins neue Format umgeleitet.
- Falls Gradle eine Version nicht findet: aktuelle Werte auf https://fabricmc.net/develop eintragen
  (`gradle.properties`).

## Tränke
Feuerresistenz, Stärke und Schnelligkeit, jeweils Normal und Splash (Umschalter oben rechts).
Die Mod liest dafür `items/potion.json` und `items/splash_potion.json` der Packs (ab 1.21.4) und baut daraus
eine neue Weiche. Alle anderen Tränke bleiben unverändert. Lange und starke Stufen nutzen denselben Look.

## Rüstung: Item / Body
Diamant- und Netherite-Rüstung haben oben rechts einen Umschalter **Item / Body** (Auswahl wird getrennt gespeichert):
- **Item**: die Icons (Helm, Brustplatte, Hose, Stiefel), Vorschau als 2x2-Bild.
- **Body**: die getragene Rüstung. Vorschau ist dein eigener Skin (mit Hut/Jacke-Ebene) mit der Rüstung des Packs, **drehend** (eine Umdrehung in 16 Sekunden, mit Bodenschatten, auch Rücken und Seiten).
  Schmale Skins (Alex) werden erkannt. Ohne Welt (z. B. im Titelbildschirm) wird Steve gezeigt. Die Karte "Standard" zeigt die Vanilla-Rüstung.
  Technik: jedes Körperteil ist ein Quader, pro Bild werden nur die zugewandten Seiten gezeichnet und waagerecht gestaucht (Matrix-Skalierung).
- Alte Config-Einträge (`diamond_armor`) gelten nach dem Update automatisch für Item und Body.
- Skin-Zugriff: `BodyView.skin()` (ein einziger Aufruf, `getSkin().body().texturePath()`).


## Schild (3D)
Die Vorschau des Schilds ist ein 3D-Modell (Platte + Griff) aus der Entity-Textur `shield_base_nopattern.png` des Packs.
Es dreht sich langsam (eine Umdrehung in etwa 10 Sekunden), Vorderseite, Seiten und Rückseite mit Griff sind zu sehen.
Technik: `render/ShieldRenderer.java` (Software-Renderer wie beim End Crystal).

## End Crystal: In der Hand (3D-Item-Modell)
Hat ein Pack für das Crystal-Item ein eigenes 3D-Modell (`items/end_crystal.json` und/oder `models/item/end_crystal.json`
mit `elements`), zeigt die Vorschau es so, wie es im Inventar aussieht (`display.gui`, Eltern-Modelle, Drehungen, Texturen).
Beim Anwenden werden Item-Definition, Modelle und die benutzten Texturen des Modells ins Ausgabe-Pack kopiert.
Packs mit nur einem flachen Icon (`item/end_crystal.png`) werden wie bisher als Bild gezeigt.
Technik: `ItemModelSupport.java` (Modelle lesen/kopieren) und `render/ModelRenderer.java`.

## Oberfläche (GUI / HUD)
Neue Reiter: **Inventar**, **Herzen**, **Hungerkeulen**, **Hotbar**, **XP-Leiste**, **Fadenkreuz**.
- Inventar: `gui/container/inventory.png`
- Herzen: alle Sprites in `gui/sprites/hud/heart/` (voll, halb, Hardcore, vergiftet, gefroren, ...)
- Hungerkeulen: `gui/sprites/hud/food_*`
- Hotbar: `hotbar.png`, `hotbar_selection.png`, `hotbar_offhand_*`
- XP-Leiste: `experience_bar_*`
- Fadenkreuz: `crosshair*` und die Angriffsanzeige (`hotbar_attack_indicator_*`)
- Nur Packs im neuen Format (ab 1.20.2, Sprites unter `gui/sprites/hud/`). Alte Packs mit `gui/icons.png` werden nicht erkannt.

## Custom Sky (OptiFine-Format)
Reiter **Custom Sky**: erkennt Packs mit `assets/minecraft/optifine/sky/` (auch `mcpatcher/sky/`) und kopiert den kompletten
Sky-Ordner (`.properties` + Würfel-PNGs wie `cloud1.png`, 3 Spalten x 2 Reihen) ins Ausgabe-Pack.
Die Vorschau zeigt die Würfel-Textur verkleinert. Zum Anzeigen im Spiel braucht man OptiFine oder eine Mod, die OptiFine-Skies liest.

## Feuer
Reiter **Feuer**: Vorschau ist ein Netherrack-Block mit Flamme darüber (gekreuzte Ebenen aus `fire_0` und `fire_1`).
Die Flamme läuft mit der Animation aus der `.mcmeta` des Packs. Netherrack stammt aus dem Pack, sonst aus dem Spiel.
Kopiert werden `block/fire_0.png` und `block/fire_1.png` samt Animationsdateien.

## Blöcke
Reiter **Blöcke**: links eine alphabetische Liste aller Block-Texturen (`block/<name>.png`), die in deinen Packs vorkommen
(ohne Obsidian, Crying Obsidian, Spinnennetz und Feuer, die haben eigene Reiter). Block anklicken, rechts die Packs wählen.
Die Auswahl wird pro Block gespeichert (`block:<name>`). Die Liste nutzt die Namen der Textur-Dateien, nicht die Block-IDs.
