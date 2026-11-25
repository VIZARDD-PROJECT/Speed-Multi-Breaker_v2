package com.speed.multi.breaker;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

public class SpeedMultiBreakerClient implements ClientModInitializer {

    private static boolean wasAttackPressed = false;
    private static int switchCooldown = 0;

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(SpeedMultiBreaker.SYNC_SETTINGS_PACKET, (client, handler, buf, responseSender) -> {
            PlayerMiningData.Preset preset = buf.readEnum(PlayerMiningData.Preset.class);
            float speed = buf.readFloat();
            int up = buf.readInt();
            int right = buf.readInt();
            int down = buf.readInt();
            int left = buf.readInt();

            client.execute(() -> {
                PlayerMiningData.CLIENT_SETTINGS.preset = preset;
                PlayerMiningData.CLIENT_SETTINGS.speedMultiplier = speed;
                PlayerMiningData.CLIENT_SETTINGS.up = up;
                PlayerMiningData.CLIENT_SETTINGS.right = right;
                PlayerMiningData.CLIENT_SETTINGS.down = down;
                PlayerMiningData.CLIENT_SETTINGS.left = left;
            });
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClientSide() && hand == InteractionHand.MAIN_HAND && tryTriggerPresetSwitch(player)) {
                return InteractionResultHolder.success(player.getItemInHand(hand));
            }
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide() && hand == InteractionHand.MAIN_HAND && tryTriggerPresetSwitch(player)) {
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (switchCooldown > 0) switchCooldown--;

            if (client.player == null || client.level == null) {
                wasAttackPressed = false;
                return;
            }

            boolean isAttackPressed = client.options.keyAttack.isDown();
            if (wasAttackPressed && !isAttackPressed) {
                ClientPlayNetworking.send(SpeedMultiBreaker.STOP_MINING_PACKET, PacketByteBufs.empty());
            }
            wasAttackPressed = isAttackPressed;
        });

        WorldRenderEvents.BLOCK_OUTLINE.register((worldRenderContext, blockOutlineContext) -> {
            Minecraft client = Minecraft.getInstance();
            Player player = client.player;
            Level level = client.level;
            if (player == null || level == null) return true;

            if (player.isShiftKeyDown() || !PlayerMiningData.CLIENT_SETTINGS.hasArea()) return true;
            if (!AreaHelper.isValidTool(player.getMainHandItem())) return true;

            if (!(client.hitResult instanceof BlockHitResult hitResult) || hitResult.getType() != HitResult.Type.BLOCK) {
                return true;
            }

            BlockPos centerPos = hitResult.getBlockPos();
            List<BlockPos> blocks = AreaHelper.getAreaBlocks(level, player, centerPos, hitResult.getDirection(), PlayerMiningData.CLIENT_SETTINGS, true);
            if (blocks.size() <= 1) return true;

            PoseStack poseStack = worldRenderContext.matrixStack();
            VertexConsumer consumer = worldRenderContext.consumers().getBuffer(RenderType.lines());
            Vec3 cameraPos = worldRenderContext.camera().getPosition();

            float r = 0.0f, g = 0.0f, b = 0.0f, a = 0.55f;
            switch (PlayerMiningData.CLIENT_SETTINGS.preset) {
                case MINER_3X3 -> { r = 1.0f; g = 0.8f; b = 0.1f; a = 0.7f; }
                case DESTROYER_5X5 -> { r = 1.0f; g = 0.2f; b = 0.2f; a = 0.75f; }
                case CUSTOM -> { r = 0.8f; g = 0.3f; b = 1.0f; a = 0.7f; }
                default -> { r = 1.0f; g = 1.0f; b = 1.0f; a = 0.5f; }
            }

            for (BlockPos pos : blocks) {
                BlockState state = level.getBlockState(pos);
                if (state.isAir()) continue;

                VoxelShape shape = state.getShape(level, pos, CollisionContext.of(player));
                LevelRenderer.renderShape(
                        poseStack,
                        consumer,
                        shape,
                        pos.getX() - cameraPos.x,
                        pos.getY() - cameraPos.y,
                        pos.getZ() - cameraPos.z,
                        r, g, b, a
                );
            }

            return false;
        });
    }

    private static boolean tryTriggerPresetSwitch(Player player) {
        Minecraft client = Minecraft.getInstance();
        if (player.isShiftKeyDown() && client.options.keySprint.isDown() && AreaHelper.isValidTool(player.getMainHandItem())) {
            if (switchCooldown == 0) {
                switchCooldown = 5;
                ClientPlayNetworking.send(SpeedMultiBreaker.CYCLE_PRESET_PACKET, PacketByteBufs.empty());
            }
            return true;
        }
        return false;
    }
}
