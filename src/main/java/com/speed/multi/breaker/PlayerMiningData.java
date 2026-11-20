package com.speed.multi.breaker;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerMiningData {
    private static final Map<UUID, PlayerSettings> DATA = new HashMap<>();

    public static PlayerSettings get(UUID uuid) {
        return DATA.computeIfAbsent(uuid, k -> new PlayerSettings());
    }

    public static class PlayerSettings {
        public float speedMultiplier = 1.0f;
        public int up = 0;
        public int right = 0;
        public int down = 0;
        public int left = 0;

        public boolean hasArea() {
            return up > 0 || right > 0 || down > 0 || left > 0;
        }
    }
}
