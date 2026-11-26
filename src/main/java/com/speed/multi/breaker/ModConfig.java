package com.speed.multi.breaker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("speed-multi-breaker.json");

    public float maxSpeedMultiplier = 32.0f;
    public float speedStep = 0.5f;
    public int maxAreaSize = 9;
    public Anchor evenAreaAnchor = Anchor.UP_RIGHT;

    public enum Anchor {
        UP_RIGHT,
        UP_LEFT,
        DOWN_RIGHT,
        DOWN_LEFT
    }

    private static ModConfig INSTANCE;

    public static ModConfig get() {
        if (INSTANCE == null) {
            load();
        }
        return INSTANCE;
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH);
                INSTANCE = GSON.fromJson(json, ModConfig.class);
            } catch (Exception e) {
                INSTANCE = new ModConfig();
            }
        } else {
            INSTANCE = new ModConfig();
        }

        if (INSTANCE == null) INSTANCE = new ModConfig();
        INSTANCE.validate();
        save();
    }

    public void validate() {
        if (maxSpeedMultiplier < 1.0f) maxSpeedMultiplier = 1.0f;
        if (maxSpeedMultiplier > 100.0f) maxSpeedMultiplier = 100.0f;

        if (speedStep < 0.1f) speedStep = 0.1f;
        if (speedStep > 32.0f) speedStep = 32.0f;
        speedStep = Math.round(speedStep * 10.0f) / 10.0f;

        if (maxAreaSize < 1) maxAreaSize = 1;
        if (maxAreaSize > 15) maxAreaSize = 15;

        if (evenAreaAnchor == null) evenAreaAnchor = Anchor.UP_RIGHT;
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(INSTANCE));
        } catch (IOException ignored) {}
    }
}
