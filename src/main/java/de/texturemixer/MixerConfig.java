package de.texturemixer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MixerConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static MixerConfig instance = new MixerConfig();

    /** Kategorie-ID -> Dateiname des gewählten Packs. Fehlt ein Eintrag, wird nichts überschrieben. */
    public Map<String, String> selections = new LinkedHashMap<>();

    /** GLFW-Keycode zum Öffnen des Menüs, -1 = nicht belegt (Standard). */
    public int openKey = -1;

    public static MixerConfig get() {
        return instance;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("texturemixer.json");
    }

    public static void load() {
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        try {
            MixerConfig loaded = GSON.fromJson(Files.readString(f), MixerConfig.class);
            if (loaded != null) {
                if (loaded.selections == null) loaded.selections = new LinkedHashMap<>();
                loaded.migrate();
                instance = loaded;
            }
        } catch (Exception e) {
            TextureMixerClient.LOGGER.warn("Konnte Config nicht lesen, nutze Standardwerte", e);
        }
    }

    /**
     * Alte Configs: Die Rüstung hatte früher nur einen Eintrag (z. B. "diamond_armor"). Jetzt gibt es
     * "diamond_armor#item" und "diamond_armor#body" - der alte Eintrag gilt dann für beide.
     */
    private void migrate() {
        for (Category c : Category.ALL) {
            if (!c.dual() || !selections.containsKey(c.id())) continue;
            String pack = selections.remove(c.id());
            for (Category.Variant v : c.variants()) {
                selections.putIfAbsent(c.fullId(v), pack);
            }
        }
    }

    public void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(this));
        } catch (IOException e) {
            TextureMixerClient.LOGGER.warn("Konnte Config nicht speichern", e);
        }
    }
}
