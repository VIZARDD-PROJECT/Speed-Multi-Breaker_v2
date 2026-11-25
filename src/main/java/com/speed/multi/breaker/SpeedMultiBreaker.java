package com.speed.multi.breaker;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public class SpeedMultiBreaker implements ModInitializer {
    public static final String MOD_ID = "speed-multi-breaker";
    public static final ResourceLocation SYNC_SETTINGS_PACKET = new ResourceLocation(MOD_ID, "sync_settings");
    public static final ResourceLocation CYCLE_PRESET_PACKET = new ResourceLocation(MOD_ID, "cycle_preset");
    public static final ResourceLocation STOP_MINING_PACKET = new ResourceLocation(MOD_ID, "stop_mining");

    private static final Set<UUID> INTERNAL_BREAKING = new HashSet<>();
    private static final Map<UUID, MiningSession> ACTIVE_SESSIONS = new HashMap<>();

    private static class MiningSession {
        final ServerLevel level;
        final BlockPos centerPos;
        final Direction hitFace;
        final Map<BlockPos, Float> progressMap = new HashMap<>();
        final Map<BlockPos, Integer> lastStageMap = new HashMap<>();
        int lastTickActive;

        MiningSession(ServerLevel level, BlockPos centerPos, Direction hitFace, int tick) {
            this.level = level;
            this.centerPos = centerPos;
            this.hitFace = hitFace;
            this.lastTickActive = tick;
        }
    }

    @Override
    public void onInitialize() {
        registerCommands();
        registerNetworking();
        registerMiningEvents();

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            syncSettingsToClient(handler.player);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            clearSession(handler.player.getUUID());
        });
    }

    private void registerNetworking() {
        ServerPlayNetworking.registerGlobalReceiver(CYCLE_PRESET_PACKET, (server, player, handler, buf, responseSender) -> {
            server.execute(() -> cyclePlayerPreset(player));
        });

        ServerPlayNetworking.registerGlobalReceiver(STOP_MINING_PACKET, (server, player, handler, buf, responseSender) -> {
            server.execute(() -> clearSession(player.getUUID()));
        });
    }

    public static void cyclePlayerPreset(ServerPlayer player) {
        PlayerMiningData.PlayerSettings settings = PlayerMiningData.get(player.server, player.getUUID());
        settings.applyPreset(settings.preset.next());
        PlayerMiningData.markDirty(player.server);
        syncSettingsToClient(player);

        player.displayClientMessage(Component.literal("§7Режим: " + settings.preset.displayName), true);
        player.level().playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.5f, 1.2f);
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("minspeedx")
                .then(Commands.argument("multiplier", FloatArgumentType.floatArg(0.1f, 100.0f))
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        float mult = FloatArgumentType.getFloat(context, "multiplier");

                        PlayerMiningData.PlayerSettings settings = PlayerMiningData.get(player.server, player.getUUID());
                        settings.preset = PlayerMiningData.Preset.CUSTOM;
                        settings.speedMultiplier = mult;
                        PlayerMiningData.markDirty(player.server);
                        syncSettingsToClient(player);

                        player.displayClientMessage(Component.literal("§aСкорость добычи установлена на: §e" + mult + "x"), false);
                        return 1;
                    })
                )
            );

            dispatcher.register(Commands.literal("minsizex")
                .then(Commands.argument("pattern", StringArgumentType.word())
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        String pattern = StringArgumentType.getString(context, "pattern");

                        if (!pattern.matches("\\d{4}")) {
                            player.displayClientMessage(Component.literal("§cОшибка! Введите ровно 4 цифры (вверх, вправо, вниз, влево). Пример: /minsizex 0010"), false);
                            return 0;
                        }

                        PlayerMiningData.PlayerSettings settings = PlayerMiningData.get(player.server, player.getUUID());
                        settings.preset = PlayerMiningData.Preset.CUSTOM;
                        settings.up = Character.getNumericValue(pattern.charAt(0));
                        settings.right = Character.getNumericValue(pattern.charAt(1));
                        settings.down = Character.getNumericValue(pattern.charAt(2));
                        settings.left = Character.getNumericValue(pattern.charAt(3));
                        PlayerMiningData.markDirty(player.server);
                        syncSettingsToClient(player);

                        player.displayClientMessage(Component.literal(
                            String.format("§aОбласть добычи: §e↑%d →%d ↓%d ←%d", settings.up, settings.right, settings.down, settings.left)
                        ), false);
                        return 1;
                    })
                )
            );
        });
    }

    private void registerMiningEvents() {
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
            if (serverPlayer.isCreative() || serverPlayer.isShiftKeyDown()) return InteractionResult.PASS;

            PlayerMiningData.PlayerSettings settings = PlayerMiningData.get(serverPlayer.server, serverPlayer.getUUID());
            if (!settings.hasArea() || !AreaHelper.isValidTool(serverPlayer.getMainHandItem())) {
                clearSession(serverPlayer.getUUID());
                return InteractionResult.PASS;
            }

            UUID uuid = serverPlayer.getUUID();
            MiningSession session = ACTIVE_SESSIONS.get(uuid);
            int currentTick = serverPlayer.server.getTickCount();

            if (session == null || !session.centerPos.equals(pos) || session.hitFace != direction || session.level != world) {
                clearSession(uuid);
                session = new MiningSession((ServerLevel) world, pos, direction, currentTick);
                ACTIVE_SESSIONS.put(uuid, session);
            } else {
                session.lastTickActive = currentTick;
            }

            return InteractionResult.PASS;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int currentTick = server.getTickCount();
            Iterator<Map.Entry<UUID, MiningSession>> it = ACTIVE_SESSIONS.entrySet().iterator();

            while (it.hasNext()) {
                Map.Entry<UUID, MiningSession> entry = it.next();
                UUID uuid = entry.getKey();
                MiningSession session = entry.getValue();
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);

                if (player == null || !player.isAlive() || player.isShiftKeyDown() || (currentTick - session.lastTickActive) > 2) {
                    cleanupBreakProgress(session);
                    it.remove();
                    continue;
                }

                PlayerMiningData.PlayerSettings settings = PlayerMiningData.get(server, uuid);
                ItemStack tool = player.getMainHandItem();
                if (!settings.hasArea() || !AreaHelper.isValidTool(tool)) {
                    cleanupBreakProgress(session);
                    it.remove();
                    continue;
                }

                List<BlockPos> areaBlocks = AreaHelper.getAreaBlocks(session.level, player, session.centerPos, session.hitFace, settings, false);
                INTERNAL_BREAKING.add(uuid);
                try {
                    for (BlockPos targetPos : areaBlocks) {
                        if (tool.isEmpty()) break;

                        BlockState state = session.level.getBlockState(targetPos);
                        if (state.isAir()) continue;

                        float delta = state.getDestroyProgress(player, session.level, targetPos);
                        if (delta <= 0.0f) continue;

                        float currentProgress = session.progressMap.getOrDefault(targetPos, 0.0f) + delta;

                        if (currentProgress >= 1.0f) {
                            session.level.destroyBlockProgress(player.getId() ^ targetPos.hashCode(), targetPos, -1);
                            session.progressMap.remove(targetPos);
                            session.lastStageMap.remove(targetPos);
                            player.gameMode.destroyBlock(targetPos);
                        } else {
                            session.progressMap.put(targetPos, currentProgress);
                            int stage = (int) (currentProgress * 10.0f);
                            int prevStage = session.lastStageMap.getOrDefault(targetPos, -1);
                            if (stage != prevStage) {
                                session.lastStageMap.put(targetPos, stage);
                                session.level.destroyBlockProgress(player.getId() ^ targetPos.hashCode(), targetPos, stage);
                            }
                        }
                    }
                } finally {
                    INTERNAL_BREAKING.remove(uuid);
                }
            }
        });

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) return;
            UUID uuid = serverPlayer.getUUID();
            if (INTERNAL_BREAKING.contains(uuid)) return;

            MiningSession session = ACTIVE_SESSIONS.get(uuid);
            if (session != null && session.centerPos.equals(pos)) {
                cleanupBreakProgress(session);
                ACTIVE_SESSIONS.remove(uuid);
            }
        });
    }

    private static void clearSession(UUID uuid) {
        MiningSession session = ACTIVE_SESSIONS.remove(uuid);
        if (session != null) {
            cleanupBreakProgress(session);
        }
    }

    private static void cleanupBreakProgress(MiningSession session) {
        for (BlockPos pos : session.lastStageMap.keySet()) {
            session.level.destroyBlockProgress(pos.hashCode(), pos, -1);
        }
        session.progressMap.clear();
        session.lastStageMap.clear();
    }

    public static void syncSettingsToClient(ServerPlayer player) {
        PlayerMiningData.PlayerSettings settings = PlayerMiningData.get(player.server, player.getUUID());
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeEnum(settings.preset);
        buf.writeFloat(settings.speedMultiplier);
        buf.writeInt(settings.up);
        buf.writeInt(settings.right);
        buf.writeInt(settings.down);
        buf.writeInt(settings.left);
        ServerPlayNetworking.send(player, SYNC_SETTINGS_PACKET, buf);
    }
}
