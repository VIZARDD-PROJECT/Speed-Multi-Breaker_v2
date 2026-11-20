package com.speed.multi.breaker;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.PickaxeItem;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class SpeedMultiBreaker implements ModInitializer {
    public static final String MOD_ID = "speed-multi-breaker";
    public static final Identifier SYNC_SPEED_PACKET = new Identifier(MOD_ID, "sync_speed");

    // Защита от бесконечной рекурсии, когда мод сам ломает соседние блоки
    private static final Set<UUID> ACTIVE_MINERS = new HashSet<>();

    @Override
    public void onInitialize() {
        registerCommands();
        registerBlockBreakLogic();

        // Синхронизируем скорость при входе игрока на сервер/в мир
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            syncSpeedToClient(handler.player);
        });
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // Команда /minspeedx <множитель>
            dispatcher.register(CommandManager.literal("minspeedx")
                .then(CommandManager.argument("multiplier", FloatArgumentType.floatArg(0.1f, 100.0f))
                    .executes(context -> {
                        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                        float mult = FloatArgumentType.getFloat(context, "multiplier");

                        PlayerMiningData.get(player.getUuid()).speedMultiplier = mult;
                        syncSpeedToClient(player);

                        context.getSource().sendFeedback(() -> Text.literal("§aСкорость добычи установлена на: §e" + mult + "x"), false);
                        return 1;
                    })
                )
            );

            // Команда /minsizex <4 цифры, например 0010>
            dispatcher.register(CommandManager.literal("minsizex")
                .then(CommandManager.argument("pattern", StringArgumentType.word())
                    .executes(context -> {
                        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                        String pattern = StringArgumentType.getString(context, "pattern");

                        if (!pattern.matches("\\d{4}")) {
                            context.getSource().sendError(Text.literal("§cОшибка! Введите ровно 4 цифры (вверх, вправо, вниз, влево). Пример: /minsizex 0010"));
                            return 0;
                        }

                        PlayerMiningData.PlayerSettings settings = PlayerMiningData.get(player.getUuid());
                        settings.up = Character.getNumericValue(pattern.charAt(0));
                        settings.right = Character.getNumericValue(pattern.charAt(1));
                        settings.down = Character.getNumericValue(pattern.charAt(2));
                        settings.left = Character.getNumericValue(pattern.charAt(3));

                        context.getSource().sendFeedback(() -> Text.literal(
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
            if (world.isClient || !(player instanceof ServerPlayerEntity serverPlayer)) return;
            if (serverPlayer.isCreative()) return;

            UUID uuid = serverPlayer.getUuid();
            if (ACTIVE_MINERS.contains(uuid)) return;

            PlayerMiningData.PlayerSettings settings = PlayerMiningData.get(uuid);
            if (!settings.hasArea()) return;

            // Ограничение 1: работает только с киркой
            ItemStack tool = serverPlayer.getMainHandStack();
            if (!(tool.getItem() instanceof PickaxeItem)) return;

            // Определяем направления относительно взгляда игрока
            Direction facing = serverPlayer.getHorizontalFacing();
            Direction rightDir = facing.rotateYClockwise();
            Direction leftDir = facing.rotateYCounterclockwise();

            Direction upDir = Direction.UP;
            Direction downDir = Direction.DOWN;

            // Если игрок смотрит резко вверх или вниз, "вверх/вниз" считаем по плоскости пола/потолка
            if (serverPlayer.getPitch() < -55.0f || serverPlayer.getPitch() > 55.0f) {
                upDir = facing;
                downDir = facing.getOpposite();
            }

            ACTIVE_MINERS.add(uuid);
            try {
                for (int u = -settings.down; u <= settings.up; u++) {
                    for (int h = -settings.left; h <= settings.right; h++) {
                        if (u == 0 && h == 0) continue; // Основной блок уже сломан
                        if (tool.isEmpty()) break; // Кирка сломалась в процессе

                        BlockPos targetPos = pos.offset(upDir, u).offset(rightDir, h);
                        tryBreakExtraBlock((ServerWorld) world, serverPlayer, targetPos, state, tool);
                    }
                }
            } finally {
                ACTIVE_MINERS.remove(uuid);
            }
        });
    }

    private void tryBreakExtraBlock(ServerWorld world, ServerPlayerEntity player, BlockPos targetPos, BlockState originalState, ItemStack tool) {
        BlockState targetState = world.getBlockState(targetPos);

        // Пропускаем воздух и неразрушимые блоки (бедрок, рамки портала и т.д.)
        if (targetState.isAir() || targetState.getHardness(world, targetPos) < 0) return;

        // Ограничение 2: ломаем только блоки, подходящие под кирку (или того же типа)
        if (!tool.isSuitableFor(targetState) && targetState.getBlock() != originalState.getBlock()) return;

        // Ограничение 3: не трогаем блоки с инвентарём (сундуки, печки, бочки и т.п.)
        BlockEntity be = world.getBlockEntity(targetPos);
        if (be instanceof Inventory) return;

        // Ломаем блок от лица игрока (выпадает лут с учётом Шёлкового касания/Удачи, тратится 1 прочность кирки)
        player.interactionManager.tryBreakBlock(targetPos);
    }

    public static void syncSpeedToClient(ServerPlayerEntity player) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeFloat(PlayerMiningData.get(player.getUuid()).speedMultiplier);
        ServerPlayNetworking.send(player, SYNC_SPEED_PACKET, buf);
    }
}
