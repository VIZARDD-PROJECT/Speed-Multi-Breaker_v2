package com.speed.multi.breaker;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class SpeedMultiBreaker implements ModInitializer {
    public static final String MOD_ID = "speed-multi-breaker";
    public static final ResourceLocation SYNC_SPEED_PACKET = new ResourceLocation(MOD_ID, "sync_speed");

    public static float clientSpeedMultiplier = 1.0f;
    private static final Set<UUID> ACTIVE_MINERS = new HashSet<>();

    @Override
    public void onInitialize() {
        registerCommands();
        registerBlockBreakLogic();

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            syncSpeedToClient(handler.player);
        });
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // /minspeedx <множитель>
            dispatcher.register(Commands.literal("minspeedx")
                .then(Commands.argument("multiplier", FloatArgumentType.floatArg(0.1f, 100.0f))
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrThrow();
                        float mult = FloatArgumentType.getFloat(context, "multiplier");

                        PlayerMiningData.get(player.getUUID()).speedMultiplier = mult;
                        syncSpeedToClient(player);

                        player.displayClientMessage(Component.literal("§aСкорость добычи установлена на: §e" + mult + "x"), false);
                        return 1;
                    })
                )
            );

            // /minsizex <4 цифры, например 0010>
            dispatcher.register(Commands.literal("minsizex")
                .then(Commands.argument("pattern", StringArgumentType.word())
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrThrow();
                        String pattern = StringArgumentType.getString(context, "pattern");

                        if (!pattern.matches("\\d{4}")) {
                            player.displayClientMessage(Component.literal("§cОшибка! Введите ровно 4 цифры (вверх, вправо, вниз, влево). Пример: /minsizex 0010"), false);
                            return 0;
                        }

                        PlayerMiningData.PlayerSettings settings = PlayerMiningData.get(player.getUUID());
                        settings.up = Character.getNumericValue(pattern.charAt(0));
                        settings.right = Character.getNumericValue(pattern.charAt(1));
                        settings.down = Character.getNumericValue(pattern.charAt(2));
                        settings.left = Character.getNumericValue(pattern.charAt(3));

                        player.displayClientMessage(Component.literal(
                            String.format("§aОбласть добычи: §e↑%d →%d ↓%d ←%d", settings.up, settings.right, settings.down, settings.left)
                        ), false);
                        return 1;
                    })
                )
            );
        });
    }

    private void registerBlockBreakLogic() {
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) return;
            if (serverPlayer.isCreative()) return;

            UUID uuid = serverPlayer.getUUID();
            if (ACTIVE_MINERS.contains(uuid)) return;

            PlayerMiningData.PlayerSettings settings = PlayerMiningData.get(uuid);
            if (!settings.hasArea()) return;

            ItemStack tool = serverPlayer.getMainHandItem();
            if (!(tool.getItem() instanceof PickaxeItem)) return;

            Direction facing = serverPlayer.getDirection();
            Direction rightDir = facing.getClockWise();

            Direction upDir = Direction.UP;
            if (serverPlayer.getXRot() < -55.0f || serverPlayer.getXRot() > 55.0f) {
                upDir = facing;
            }

            ACTIVE_MINERS.add(uuid);
            try {
                for (int u = -settings.down; u <= settings.up; u++) {
                    for (int h = -settings.left; h <= settings.right; h++) {
                        if (u == 0 && h == 0) continue;
                        if (tool.isEmpty()) break;

                        BlockPos targetPos = pos.relative(upDir, u).relative(rightDir, h);
                        tryBreakExtraBlock((ServerLevel) world, serverPlayer, targetPos, state, tool);
                    }
                }
            } finally {
                ACTIVE_MINERS.remove(uuid);
            }
        });
    }

    private void tryBreakExtraBlock(ServerLevel level, ServerPlayer player, BlockPos targetPos, BlockState originalState, ItemStack tool) {
        BlockState targetState = level.getBlockState(targetPos);

        if (targetState.isAir() || targetState.getDestroySpeed(level, targetPos) < 0) return;
        if (!tool.isCorrectToolForDrops(targetState) && targetState.getBlock() != originalState.getBlock()) return;

        BlockEntity be = level.getBlockEntity(targetPos);
        if (be instanceof Container) return;

        player.gameMode.destroyBlock(targetPos);
    }

    public static void syncSpeedToClient(ServerPlayer player) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeFloat(PlayerMiningData.get(player.getUUID()).speedMultiplier);
        ServerPlayNetworking.send(player, SYNC_SPEED_PACKET, buf);
    }
}
