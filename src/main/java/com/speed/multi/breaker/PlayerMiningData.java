package com.speed.multi.breaker;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class PlayerMiningData extends SavedData {
    private static final String DATA_NAME = SpeedMultiBreaker.MOD_ID + "_player_data";
    private final Map<UUID, PlayerSettings> players = new HashMap<>();

    public static class PlayerSettings {
        public float speedMultiplier = 1.0f;
        public int areaSize = 1;

        public boolean customPattern = false;
        public int up = 0;
        public int right = 0;
        public int down = 0;
        public int left = 0;
        public int depth = 0;

        public void setSquareArea(int n, ModConfig.Anchor anchor) {
            this.customPattern = false;
            this.depth = 0;
            this.areaSize = Math.max(1, n);
            if (areaSize == 1) {
                up = right = down = left = 0;
                return;
            }

            if (areaSize % 2 == 1) {
                int radius = areaSize / 2;
                up = radius;
                right = radius;
                down = radius;
                left = radius;
            } else {
                int major = areaSize / 2;
                int minor = major - 1;
                switch (anchor) {
                    case UP_RIGHT -> { up = major; right = major; down = minor; left = minor; }
                    case UP_LEFT -> { up = major; left = major; down = minor; right = minor; }
                    case DOWN_RIGHT -> { down = major; right = major; up = minor; left = minor; }
                    case DOWN_LEFT -> { down = major; left = major; up = minor; right = minor; }
                }
            }
        }

        public boolean hasArea() {
            return up > 0 || right > 0 || down > 0 || left > 0 || depth > 0;
        }

        public float getEffectiveSpeed(boolean isSneaking) {
            if (!isSneaking) {
                return speedMultiplier;
            }
            if (speedMultiplier < 3.0f) return 1.0f;
            if (speedMultiplier < 6.0f) return 2.0f;
            if (speedMultiplier < 10.0f) return 3.0f;
            if (speedMultiplier < 16.0f) return 4.0f;
            return 5.0f;
        }

        public ChatFormatting getSpeedColor() {
            if (speedMultiplier <= 1.0f) return ChatFormatting.GRAY;
            if (speedMultiplier < 3.0f) return ChatFormatting.WHITE;
            if (speedMultiplier < 6.0f) return ChatFormatting.GREEN;
            if (speedMultiplier < 10.0f) return ChatFormatting.BLUE;
            if (speedMultiplier < 16.0f) return ChatFormatting.LIGHT_PURPLE;
            return ChatFormatting.RED;
        }

        public ChatFormatting getAreaNumberColor() {
            return switch (areaSize) {
                case 1 -> ChatFormatting.WHITE;
                case 2 -> ChatFormatting.DARK_GREEN;
                case 3 -> ChatFormatting.GREEN;
                case 4 -> ChatFormatting.DARK_BLUE;
                case 5 -> ChatFormatting.BLUE;
                case 6 -> ChatFormatting.DARK_PURPLE;
                case 7 -> ChatFormatting.LIGHT_PURPLE;
                case 8 -> ChatFormatting.DARK_RED;
                default -> ChatFormatting.RED;
            };
        }

        public int getAreaOutlineRGB() {
            if (customPattern && depth > 0 && areaSize == 1) {
                return 0x55FFFF;
            }
            Integer color = getAreaNumberColor().getColor();
            return color != null ? color : 0xFFFFFF;
        }

        public Component buildSpeedActionbar() {
            String formattedSpeed = (speedMultiplier % 1.0f == 0)
                    ? String.format(Locale.US, "%.0fx", speedMultiplier)
                    : String.format(Locale.US, "%.1fx", speedMultiplier);

            MutableComponent value = Component.literal(formattedSpeed).withStyle(getSpeedColor());
            return Component.translatable("message.speed-multi-breaker.speed", value).withStyle(ChatFormatting.WHITE);
        }

        public Component buildAreaActionbar() {
            ChatFormatting labelColor = (areaSize % 2 == 0) ? ChatFormatting.GRAY : ChatFormatting.WHITE;
            String sizeText = areaSize + "×" + areaSize;
            MutableComponent value = Component.literal(sizeText).withStyle(getAreaNumberColor());
            return Component.translatable("message.speed-multi-breaker.area", value).withStyle(labelColor);
        }
    }

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
            pTag.putFloat("speed", s.speedMultiplier);
            pTag.putInt("areaSize", s.areaSize);
            pTag.putBoolean("customPattern", s.customPattern);
            pTag.putInt("up", s.up);
            pTag.putInt("right", s.right);
            pTag.putInt("down", s.down);
            pTag.putInt("left", s.left);
            pTag.putInt("depth", s.depth);
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
                    s.speedMultiplier = pTag.contains("speed") ? pTag.getFloat("speed") : 1.0f;
                    s.areaSize = pTag.contains("areaSize") ? pTag.getInt("areaSize") : 1;
                    s.customPattern = pTag.getBoolean("customPattern");
                    s.up = pTag.getInt("up");
                    s.right = pTag.getInt("right");
                    s.down = pTag.getInt("down");
                    s.left = pTag.getInt("left");
                    s.depth = pTag.getInt("depth");
                    data.players.put(uuid, s);
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return data;
    }
}
