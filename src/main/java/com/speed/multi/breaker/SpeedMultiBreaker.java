package com.speed.multi.breaker;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public class SpeedMultiBreaker implements ModInitializer {
    public static final String MOD_ID = "speed-multi-breaker";
    public static final ResourceLocation SYNC_SETTINGS_PACKET = new ResourceLocation(MOD_ID, "sync_settings");
    public static final ResourceLocation ADJUST_SETTING_PACKET = new ResourceLocation(MOD_ID, "adjust_setting");

    private static final Set<UUID> ACTIVE_MINERS = new HashSet<>();
    private static final Map<UUID, Direction> LAST_HIT_FACE = new HashMap<>();

    @Override
    public void onInitialize() {
        ModConfig.load();
        registerCommands();
        registerNetworking();
        registerBlockBreakLogic();

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            syncSettingsToClient(handler.player);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            LAST_HIT_FACE.remove(handler.player.getUUID());
        });
    }

    private void registerNetworking() {
        ServerPlayNetworking.registerGlobalReceiver(ADJUST_SETTING_PACKET, (server, player, handler, buf, responseSender) -> {
            boolean isAreaMode = buf.readBoolean();
            int delta = buf.readInt();

            server.execute(() -> {
                ModConfig cfg = ModConfig.get();
                PlayerMiningData.PlayerSettings settings = PlayerMiningData.get(server, player.getUUID());

                if (isAreaMode) {
                    int oldSize = settings.areaSize;
                    int newSize = Math.max(1, Math.min(cfg.maxAreaSize, oldSize + delta));
                    settings.setSquareArea(newSize, cfg.evenAreaAnchor);
                    PlayerMiningData.markDirty(server);
                    syncSettingsToClient(player);

                    player.displayClientMessage(settings.buildAreaActionbar(), true);
                    float pitch = (newSize == oldSize) ? 0.5f : (0.8f + (newSize / (float) cfg.maxAreaSize) * 0.8f);
                    player.level().playSound(null, player.blockPosition(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.4f, pitch);
                } else {
                    float oldSpeed = settings.speedMultiplier;
                    float rawSpeed = oldSpeed + (delta * cfg.speedStep);
                    float newSpeed = Math.round(rawSpeed * 10.0f) / 10.0f;
                    newSpeed = Math.max(1.0f, Math.min(cfg.maxSpeedMultiplier, newSpeed));

                    settings.speedMultiplier = newSpeed;
                    PlayerMiningData.markDirty(server);
                    syncSettingsToClient(player);

                    player.displayClientMessage(settings.buildSpeedActionbar(), true);
                    float pitch = (newSpeed == oldSpeed) ? 0.5f : (0.7f + (newSpeed / cfg.maxSpeedMultiplier) * 1.1f);
                    player.level().playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.35f, pitch);
                }
            });
        });
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("minspeedx")
                .then(Commands.argument("multiplier", FloatArgumentType.floatArg(0.1f, 100.0f))
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        float mult = Math.round(FloatArgumentType.getFloat(context, "multiplier") * 10.0f) / 10.0f;

                        PlayerMiningData.PlayerSettings settings = PlayerMiningData.get(player.server, player.getUUID());
                        settings.speedMultiplier = mult;
                        PlayerMiningData.markDirty(player.server);
                        syncSettingsToClient(player);

                        player.displayClientMessage(
                            Component.translatable("message.speed-multi-breaker.command.speed_set",
                                Component.literal(mult + "x").withStyle(settings.getSpeedColor())),
                            false
                        );
                        return 1;
                    })
                )
            );

            dispatcher.register(Commands.literal("minsizex")
                .then(Commands.argument("pattern", StringArgumentType.word())
                    .executes(context -> applyCustomSize(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "pattern"), 0))
                    .then(Commands.argument("depth", IntegerArgumentType.integer(0, 32))
                        .executes(context -> applyCustomSize(
                            context.getSource().getPlayerOrException(),
                            StringArgumentType.getString(context, "pattern"),
                            IntegerArgumentType.getInteger(context, "depth")
                        ))
                    )
                )
            );
        });
    }

    private int applyCustomSize(ServerPlayer player, String pattern, int depth) {
        if (!pattern.matches("\\d{4}")) {
            player.displayClientMessage(Component.translatable("message.speed-multi-breaker.command.size_error"), false);
            return 0;
        }

        PlayerMiningData.PlayerSettings settings = PlayerMiningData.get(player.server, player.getUUID());
        settings.customPattern = true;
        settings.areaSize = 1;
        settings.up = Character.getNumericValue(pattern.charAt(0));
        settings.right = Character.getNumericValue(pattern.charAt(1));
        settings.down = Character.getNumericValue(pattern.charAt(2));
        settings.left = Character.getNumericValue(pattern.charAt(3));
        settings.depth = depth;
        PlayerMiningData.markDirty(player.server);
        syncSettingsToClient(player);

        player.displayClientMessage(
            Component.translatable("message.speed-multi-breaker.command.size_set_depth",
                settings.up, settings.right, settings.down, settings.left, settings.depth),
            false
        );
        return 1;
    }

    private void registerBlockBreakLogic() {
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!world.isClientSide()) {
                LAST_HIT_FACE.put(player.getUUID(), direction);
            }
            return InteractionResult.PASS;
        });

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) return;
            if (serverPlayer.isCreative()) return;

            UUID uuid = serverPlayer.getUUID();
            if (ACTIVE_MINERS.contains(uuid)) return;

            PlayerMiningData.PlayerSettings settings = PlayerMiningData.get(serverPlayer.server, uuid);
            if (!settings.hasArea() || serverPlayer.isShiftKeyDown()) return;

            ItemStack tool = serverPlayer.getMainHandItem();
            if (!AreaHelper.isValidTool(tool)) return;

            Direction hitFace = LAST_HIT_FACE.getOrDefault(uuid, serverPlayer.getDirection().getOpposite());
            List<BlockPos> targets = AreaHelper.getAreaBlocks((ServerLevel) world, serverPlayer, pos, hitFace, settings, false);

            ACTIVE_MINERS.add(uuid);
            try {
                for (BlockPos targetPos : targets) {
                    if (tool.isEmpty()) break;
                    BlockState targetState = world.getBlockState(targetPos);
                    if (!targetState.isAir()) {
                        serverPlayer.gameMode.destroyBlock(targetPos);
                    }
                }
            } finally {
                ACTIVE_MINERS.remove(uuid);
            }
        });
    }

    public static void syncSettingsToClient(ServerPlayer player) {
        ModConfig cfg = ModConfig.get();
        PlayerMiningData.PlayerSettings settings = PlayerMiningData.get(player.server, player.getUUID());
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeFloat(settings.speedMultiplier);
        buf.writeInt(settings.areaSize);
        buf.writeBoolean(settings.customPattern);
        buf.writeInt(settings.up);
        buf.writeInt(settings.right);
        buf.writeInt(settings.down);
        buf.writeInt(settings.left);
        buf.writeInt(settings.depth);
        buf.writeEnum(cfg.evenAreaAnchor);
        ServerPlayNetworking.send(player, SYNC_SETTINGS_PACKET, buf);
    }
}
