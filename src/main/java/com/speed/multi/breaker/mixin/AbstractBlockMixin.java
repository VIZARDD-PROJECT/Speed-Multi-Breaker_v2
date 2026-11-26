package com.speed.multi.breaker.mixin;

import com.speed.multi.breaker.AreaHelper;
import com.speed.multi.breaker.PlayerMiningData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(BlockBehaviour.class)
public class AbstractBlockMixin {

    @Unique
    private static final ThreadLocal<Boolean> CHECKING_AREA = ThreadLocal.withInitial(() -> false);

    @Inject(method = "getDestroyProgress", at = @At("RETURN"), cancellable = true)
    private void onGetDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
        if (CHECKING_AREA.get()) return;

        float original = cir.getReturnValue();
        if (original <= 0.0f) return;

        PlayerMiningData.PlayerSettings settings;
        if (player.level().isClientSide()) {
            settings = PlayerMiningData.CLIENT_SETTINGS;
        } else if (player instanceof ServerPlayer serverPlayer) {
            settings = PlayerMiningData.get(serverPlayer.server, serverPlayer.getUUID());
        } else {
            return;
        }

        float minBaseProgress = original;

        if (settings.hasArea() && !player.isShiftKeyDown() && AreaHelper.isValidTool(player.getMainHandItem())) {
            Direction hitFace = player.getDirection().getOpposite();
            HitResult hit = player.pick(5.0D, 0.0F, false);
            if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(pos)) {
                hitFace = blockHit.getDirection();
            }

            CHECKING_AREA.set(true);
            try {
                List<BlockPos> area = AreaHelper.getAreaBlocks(level, player, pos, hitFace, settings, false);
                for (BlockPos targetPos : area) {
                    BlockState targetState = level.getBlockState(targetPos);
                    float targetProgress = targetState.getDestroyProgress(player, level, targetPos);
                    if (targetProgress > 0.0f && targetProgress < minBaseProgress) {
                        minBaseProgress = targetProgress;
                    }
                }
            } finally {
                CHECKING_AREA.set(false);
            }
        }

        float effectiveSpeed = settings.getEffectiveSpeed(player.isShiftKeyDown());
        cir.setReturnValue(minBaseProgress * effectiveSpeed);
    }
}
