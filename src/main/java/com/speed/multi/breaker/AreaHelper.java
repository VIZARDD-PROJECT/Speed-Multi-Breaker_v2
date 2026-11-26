package com.speed.multi.breaker;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AreaHelper {

    public static boolean isValidTool(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof DiggerItem;
    }

    public static List<BlockPos> getAreaBlocks(BlockGetter level, Player player, BlockPos centerPos, Direction hitFace, PlayerMiningData.PlayerSettings settings, boolean includeCenter) {
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
        Direction depthDir = hitFace.getOpposite();

        if (hitFace.getAxis().isVertical()) {
            Direction playerFacing = player.getDirection();
            upDir = playerFacing;
            rightDir = playerFacing.getClockWise();
        } else {
            upDir = Direction.UP;
            rightDir = hitFace.getOpposite().getClockWise();
        }

        List<BlockPos> result = new ArrayList<>();
        for (int d = 0; d <= settings.depth; d++) {
            for (int u = -settings.down; u <= settings.up; u++) {
                for (int h = -settings.left; h <= settings.right; h++) {
                    if (d == 0 && u == 0 && h == 0) {
                        if (includeCenter) result.add(centerPos);
                        continue;
                    }

                    BlockPos targetPos = centerPos.relative(depthDir, d).relative(upDir, u).relative(rightDir, h);
                    if (canBreakInArea(level, targetPos, centerState, tool)) {
                        result.add(targetPos);
                    }
                }
            }
        }
        return result;
    }

    public static boolean canBreakInArea(BlockGetter level, BlockPos targetPos, BlockState centerState, ItemStack tool) {
        BlockState targetState = level.getBlockState(targetPos);
        if (targetState.isAir() || targetState.getDestroySpeed(level, targetPos) < 0) return false;

        if (!tool.isCorrectToolForDrops(targetState) && targetState.getBlock() != centerState.getBlock()) {
            return false;
        }

        if (level instanceof Level realLevel) {
            BlockEntity be = realLevel.getBlockEntity(targetPos);
            if (be instanceof Container) return false;
        }

        return true;
    }
}
