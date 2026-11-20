package com.speed.multi.breaker.mixin;

import com.speed.multi.breaker.PlayerMiningData;
import com.speed.multi.breaker.SpeedMultiBreakerClient;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractBlock.class)
public class AbstractBlockMixin {

    @Inject(method = "calcBlockBreakingDelta", at = @At("RETURN"), cancellable = true)
    private void onCalcBlockBreakingDelta(BlockState state, PlayerEntity player, BlockView world, BlockPos pos, CallbackInfoReturnable<Float> cir) {
        float original = cir.getReturnValue();
        if (original <= 0.0f) return; // Не трогаем неразрушимые блоки вроде бедрока

        float multiplier;
        if (player.getWorld().isClient) {
            multiplier = SpeedMultiBreakerClient.clientSpeedMultiplier;
        } else {
            multiplier = PlayerMiningData.get(player.getUuid()).speedMultiplier;
        }

        cir.setReturnValue(original * multiplier);
    }
}
