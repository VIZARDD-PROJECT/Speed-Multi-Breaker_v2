package com.speed.multi.breaker;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class SpeedMultiBreakerClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(SpeedMultiBreaker.SYNC_SPEED_PACKET, (client, handler, buf, responseSender) -> {
            float speed = buf.readFloat();
            client.execute(() -> SpeedMultiBreaker.clientSpeedMultiplier = speed);
        });
    }
}
