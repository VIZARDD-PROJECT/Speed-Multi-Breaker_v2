package com.speed.multi.breaker.mixin;

import com.speed.multi.breaker.PlayerMiningData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockBehaviour.class)
public class AbstractBlockMixin {

    @Inject(method = "getDestroyProgress", at = @At("RETURN"), cancellable = true)
    private void onGetDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
        float original = cir.getReturnValue();
        if (original <= 0.0f) return;

        float multiplier;
        if (player.level().isClientSide()) {
            multiplier = PlayerMiningData.CLIENT_SETTINGS.speedMultiplier;
        } else if (player instanceof ServerPlayer serverPlayer) {
            multiplier = PlayerMiningData.get(serverPlayer.server, serverPlayer.getUUID()).speedMultiplier;
        } else {
            multiplier = 1.0f;
        }

        cir.setReturnValue(original * multiplier);
    }
}
