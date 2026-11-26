package com.speed.multi.breaker;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
                return null;
            }

            ModConfig cfg = ModConfig.get();
            ConfigBuilder builder = ConfigBuilder.create()
                    .setParentScreen(parent)
                    .setTitle(Component.translatable("title.speed-multi-breaker.config"));

            builder.setSavingRunnable(() -> {
                cfg.validate();
                ModConfig.save();
            });

            ConfigCategory general = builder.getOrCreateCategory(Component.translatable("category.speed-multi-breaker.general"));
            ConfigEntryBuilder entryBuilder = builder.entryBuilder();

            general.addEntry(entryBuilder.startFloatField(
                            Component.translatable("option.speed-multi-breaker.max_speed"),
                            cfg.maxSpeedMultiplier)
                    .setDefaultValue(32.0f)
                    .setMin(1.0f)
                    .setMax(100.0f)
                    .setTooltip(Component.translatable("tooltip.speed-multi-breaker.max_speed"))
                    .setSaveConsumer(val -> cfg.maxSpeedMultiplier = val)
                    .build());

            general.addEntry(entryBuilder.startFloatField(
                            Component.translatable("option.speed-multi-breaker.speed_step"),
                            cfg.speedStep)
                    .setDefaultValue(0.5f)
                    .setMin(0.1f)
                    .setMax(32.0f)
                    .setTooltip(Component.translatable("tooltip.speed-multi-breaker.speed_step"))
                    .setSaveConsumer(val -> cfg.speedStep = val)
                    .build());

            general.addEntry(entryBuilder.startIntSlider(
                            Component.translatable("option.speed-multi-breaker.max_area"),
                            cfg.maxAreaSize, 1, 15)
                    .setDefaultValue(9)
                    .setTooltip(Component.translatable("tooltip.speed-multi-breaker.max_area"))
                    .setSaveConsumer(val -> cfg.maxAreaSize = val)
                    .build());

            general.addEntry(entryBuilder.startEnumSelector(
                            Component.translatable("option.speed-multi-breaker.even_anchor"),
                            ModConfig.Anchor.class,
                            cfg.evenAreaAnchor)
                    .setDefaultValue(ModConfig.Anchor.UP_RIGHT)
                    .setEnumNameProvider(e -> Component.translatable("enum.speed-multi-breaker.anchor." + e.name().toLowerCase()))
                    .setTooltip(Component.translatable("tooltip.speed-multi-breaker.even_anchor"))
                    .setSaveConsumer(val -> cfg.evenAreaAnchor = val)
                    .build());

            return builder.build();
        };
    }
}
