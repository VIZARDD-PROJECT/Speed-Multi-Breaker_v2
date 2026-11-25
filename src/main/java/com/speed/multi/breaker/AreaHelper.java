package com.speed.multi.breaker;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.Container;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AreaHelper {

    public static boolean isValidTool(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof DiggerItem;
    }

    /**
     * Вычисляет все блоки области с учётом грани блока (hitFace) и направления взгляда игрока.
     */
    public static List<BlockPos> getAreaBlocks(Level level, Player player, BlockPos centerPos, Direction hitFace, PlayerMiningData.PlayerSettings settings, boolean includeCenter) {
        if (!settings.hasArea() || player.isShiftKeyDown()) {
            return includeCenter ? Collections.singletonList(centerPos) : Collections.emptyList();
        }

        ItemStack tool = player.getMainHandItem();
        if (!isValidTool(tool)) {
            return includeCenter ? Collections.singletonList(centerPos) : Collections.emptyList();
        }

        BlockState centerState = level.getBlockState(centerPos);
        Direction upDir;
        Direction rightDir;

        if (hitFace.getAxis().isVertical()) {
            // Бьём в пол (UP) или потолок (DOWN): ориентируем сетку по горизонтальному взгляду игрока
            Direction playerFacing = player.getDirection();
            upDir = playerFacing;
            rightDir = playerFacing.getClockWise();
        } else {
            // Бьём в вертикальную стену (NORTH, SOUTH, EAST, WEST)
            upDir = Direction.UP;
            // Если смотрим на стену со стороны hitFace, право для игрока — это поворот от взгляда (hitFace.getOpposite()) по часовой стрелке
            rightDir = hitFace.getOpposite().getClockWise();
        }

        List<BlockPos> result = new ArrayList<>();
        for (int u = -settings.down; u <= settings.up; u++) {
            for (int h = -settings.left; h <= settings.right; h++) {
                if (u == 0 && h == 0) {
                    if (includeCenter) result.add(centerPos);
                    continue;
                }

                BlockPos targetPos = centerPos.relative(upDir, u).relative(rightDir, h);
                if (canBreakInArea(level, targetPos, centerState, tool)) {
                    result.add(targetPos);
                }
            }
        }
        return result;
    }

    public static boolean canBreakInArea(Level level, BlockPos targetPos, BlockState centerState, ItemStack tool) {
        BlockState targetState = level.getBlockState(targetPos);
        if (targetState.isAir() || targetState.getDestroySpeed(level, targetPos) < 0) return false;

        // Инструмент должен подходить для блока (кирка для камня, лопата для земли, топор для дерева)
        if (!tool.isCorrectToolForDrops(targetState) && targetState.getBlock() != centerState.getBlock()) {
            return false;
        }

        // Не трогаем сундуки, печки и другие контейнеры, чтобы случайно не рассыпать лут
        BlockEntity be = level.getBlockEntity(targetPos);
        return !(be instanceof Container);
    }
}
