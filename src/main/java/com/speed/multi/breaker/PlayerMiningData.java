package com.speed.multi.breaker;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerMiningData extends SavedData {
    private static final String DATA_NAME = SpeedMultiBreaker.MOD_ID + "_player_data";
    private final Map<UUID, PlayerSettings> players = new HashMap<>();

    public enum Preset {
        DEFAULT("§fОбычный §7(1×1 | 1x)", 1.0f, 0, 0, 0, 0),
        FAST("§bБыстрый §7(1×1 | 3x)", 3.0f, 0, 0, 0, 0),
        MINER_3X3("§6Бур 3×3 §7(3×3 | 6x)", 6.0f, 1, 1, 1, 1),
        DESTROYER_5X5("§c§lУНИЧТОЖИТЕЛЬ §7(5×5 | 10x)", 10.0f, 2, 2, 2, 2),
        CUSTOM("§dКастомный", 1.0f, 0, 0, 0, 0);

        public final String displayName;
        public final float speed;
        public final int up, right, down, left;

        Preset(String displayName, float speed, int up, int right, int down, int left) {
            this.displayName = displayName;
            this.speed = speed;
            this.up = up;
            this.right = right;
            this.down = down;
            this.left = left;
        }

        public Preset next() {
            return switch (this) {
                case DEFAULT -> FAST;
                case FAST -> MINER_3X3;
                case MINER_3X3 -> DESTROYER_5X5;
                case DESTROYER_5X5, CUSTOM -> DEFAULT;
            };
        }
    }

    public static class PlayerSettings {
        public Preset preset = Preset.DEFAULT;
        public float speedMultiplier = 1.0f;
        public int up = 0;
        public int right = 0;
        public int down = 0;
        public int left = 0;

        public void applyPreset(Preset newPreset) {
            this.preset = newPreset;
            if (newPreset != Preset.CUSTOM) {
                this.speedMultiplier = newPreset.speed;
                this.up = newPreset.up;
                this.right = newPreset.right;
                this.down = newPreset.down;
                this.left = newPreset.left;
            }
        }

        public boolean hasArea() {
            return up > 0 || right > 0 || down > 0 || left > 0;
        }
    }

    // Клиентский кэш настроек локального игрока (для рамки и скорости)
    public static final PlayerSettings CLIENT_SETTINGS = new PlayerSettings();

    public static PlayerMiningData getServerState(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(
                PlayerMiningData::load,
                PlayerMiningData::new,
                DATA_NAME
        );
    }

    public static PlayerSettings get(MinecraftServer server, UUID uuid) {
        PlayerMiningData data = getServerState(server);
        return data.players.computeIfAbsent(uuid, k -> {
            data.setDirty();
            return new PlayerSettings();
        });
    }

    public static void markDirty(MinecraftServer server) {
        getServerState(server).setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag playersTag = new CompoundTag();
        for (Map.Entry<UUID, PlayerSettings> entry : players.entrySet()) {
            CompoundTag pTag = new CompoundTag();
            PlayerSettings s = entry.getValue();
            pTag.putString("preset", s.preset.name());
            pTag.putFloat("speed", s.speedMultiplier);
            pTag.putInt("up", s.up);
            pTag.putInt("right", s.right);
            pTag.putInt("down", s.down);
            pTag.putInt("left", s.left);
            playersTag.put(entry.getKey().toString(), pTag);
        }
        tag.put("players", playersTag);
        return tag;
    }

    public static PlayerMiningData load(CompoundTag tag) {
        PlayerMiningData data = new PlayerMiningData();
        if (tag.contains("players")) {
            CompoundTag playersTag = tag.getCompound("players");
            for (String key : playersTag.getAllKeys()) {
                try {
                    UUID uuid = UUID.fromString(key);
                    CompoundTag pTag = playersTag.getCompound(key);
                    PlayerSettings s = new PlayerSettings();
                    if (pTag.contains("preset")) {
                        try {
                            s.preset = Preset.valueOf(pTag.getString("preset"));
                        } catch (IllegalArgumentException ignored) {
                            s.preset = Preset.DEFAULT;
                        }
                    }
                    s.speedMultiplier = pTag.getFloat("speed");
                    s.up = pTag.getInt("up");
                    s.right = pTag.getInt("right");
                    s.down = pTag.getInt("down");
                    s.left = pTag.getInt("left");
                    data.players.put(uuid, s);
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return data;
    }
}