package com.speed.multi.breaker;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public class SpeedMultiBreakerClient implements ClientModInitializer {

    public static KeyMapping keyActivatorA;
    public static KeyMapping keyActivatorB;
    public static KeyMapping keyActivatorMod;
    public static KeyMapping keyIncrease;
    public static KeyMapping keyDecrease;

    @Override
    public void onInitializeClient() {
        registerKeybindings();

        // 1. Приём настроек с сервера (включая depth)
        ClientPlayNetworking.registerGlobalReceiver(SpeedMultiBreaker.SYNC_SETTINGS_PACKET, (client, handler, buf, responseSender) -> {
            float speed = buf.readFloat();
            int areaSize = buf.readInt();
            boolean customPattern = buf.readBoolean();
            int up = buf.readInt();
            int right = buf.readInt();
            int down = buf.readInt();
            int left = buf.readInt();
            int depth = buf.readInt();
            ModConfig.Anchor anchor = buf.readEnum(ModConfig.Anchor.class);

            client.execute(() -> {
                PlayerMiningData.CLIENT_SETTINGS.speedMultiplier = speed;
                PlayerMiningData.CLIENT_SETTINGS.areaSize = areaSize;
                PlayerMiningData.CLIENT_SETTINGS.customPattern = customPattern;
                PlayerMiningData.CLIENT_SETTINGS.up = up;
                PlayerMiningData.CLIENT_SETTINGS.right = right;
                PlayerMiningData.CLIENT_SETTINGS.down = down;
                PlayerMiningData.CLIENT_SETTINGS.left = left;
                PlayerMiningData.CLIENT_SETTINGS.depth = depth;
                ModConfig.get().evenAreaAnchor = anchor;
            });
        });

        // 2. Обработка кнопок + и - из Keybinds (удобно для PojavLauncher на телефоне)
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.screen != null) return;

            while (keyIncrease.consumeClick()) {
                if (areBaseModifiersDown(client)) {
                    sendAdjustPacket(isAreaModifierDown(client), 1);
                }
            }
            while (keyDecrease.consumeClick()) {
                if (areBaseModifiersDown(client)) {
                    sendAdjustPacket(isAreaModifierDown(client), -1);
                }
            }
        });

        // 3. Отрисовка цветной 3D-рамки вокруг всех блоков области
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

            int rgb = PlayerMiningData.CLIENT_SETTINGS.getAreaOutlineRGB();
            float r = ((rgb >> 16) & 0xFF) / 255.0f;
            float g = ((rgb >> 8) & 0xFF) / 255.0f;
            float b = (rgb & 0xFF) / 255.0f;
            float a = 0.8f;

            for (BlockPos pos : blocks) {
                BlockState state = level.getBlockState(pos);
                if (state.isAir()) continue;

                VoxelShape shape = state.getShape(level, pos, CollisionContext.of(player));
                renderCustomShape(
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

    private void registerKeybindings() {
        String category = "category.speed-multi-breaker.main";
        keyActivatorA = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.speed-multi-breaker.activator_a", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_SHIFT, category));
        keyActivatorB = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.speed-multi-breaker.activator_b", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_CONTROL, category));
        keyActivatorMod = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.speed-multi-breaker.activator_mod", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, category));
        keyIncrease = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.speed-multi-breaker.increase", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), category));
        keyDecrease = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.speed-multi-breaker.decrease", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), category));
    }

    public static boolean handleScroll(double verticalDelta) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.screen != null) return false;
        if (!areBaseModifiersDown(client)) return false;

        int delta = verticalDelta > 0 ? 1 : -1;
        sendAdjustPacket(isAreaModifierDown(client), delta);
        return true;
    }

    private static boolean isKeyDown(Minecraft client, KeyMapping mapping) {
        if (mapping.isUnbound()) return true;
        InputConstants.Key key = KeyBindingHelper.getBoundKeyOf(mapping);
        if (key.getType() == InputConstants.Type.KEYSYM) {
            return InputConstants.isKeyDown(client.getWindow().getWindow(), key.getValue());
        } else if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(client.getWindow().getWindow(), key.getValue()) == GLFW.GLFW_PRESS;
        }
        return mapping.isDown();
    }

    private static boolean areBaseModifiersDown(Minecraft client) {
        if (keyActivatorA.isUnbound() && keyActivatorB.isUnbound()) return false;
        return isKeyDown(client, keyActivatorA) && isKeyDown(client, keyActivatorB);
    }

    private static boolean isAreaModifierDown(Minecraft client) {
        if (keyActivatorMod.isUnbound()) return false;
        return isKeyDown(client, keyActivatorMod);
    }

    private static void sendAdjustPacket(boolean isAreaMode, int delta) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(isAreaMode);
        buf.writeInt(delta);
        ClientPlayNetworking.send(SpeedMultiBreaker.ADJUST_SETTING_PACKET, buf);
    }

    private static void renderCustomShape(PoseStack poseStack, VertexConsumer consumer, VoxelShape shape, double x, double y, double z, float r, float g, float b, float a) {
        PoseStack.Pose pose = poseStack.last();
        shape.forAllEdges((minX, minY, minZ, maxX, maxY, maxZ) -> {
            float dx = (float) (maxX - minX);
            float dy = (float) (maxY - minY);
            float dz = (float) (maxZ - minZ);
            float len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
            if (len > 0.0f) {
                dx /= len;
                dy /= len;
                dz /= len;
            }

            consumer.vertex(pose.pose(), (float) (minX + x), (float) (minY + y), (float) (minZ + z))
                    .color(r, g, b, a)
                    .normal(pose.normal(), dx, dy, dz)
                    .endVertex();
            consumer.vertex(pose.pose(), (float) (maxX + x), (float) (maxY + y), (float) (maxZ + z))
                    .color(r, g, b, a)
                    .normal(pose.normal(), dx, dy, dz)
                    .endVertex();
        });
    }
}
