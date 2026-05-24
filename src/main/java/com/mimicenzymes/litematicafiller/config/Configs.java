package com.mimicenzymes.litematicafiller.config;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mimicenzymes.litematicafiller.Reference;
import com.mimicenzymes.litematicafiller.input.InputHandler;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import fi.dy.masa.malilib.config.options.ConfigColor;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.config.options.ConfigStringList;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.util.JsonUtils;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.util.List;

public class Configs implements IConfigHandler {

    private static final Configs INSTANCE = new Configs();

    private static final String CONFIG_FILE_NAME = "litematica_container_filler.json";

    //核心运行设置
    public static final ConfigBoolean ENABLE_MOD                    = new ConfigBoolean("litematica_container_filler.config.name.enableMod", true, "litematica_container_filler.config.comment.enableMod");
    public static final ConfigBooleanHotkeyed WORKING_STATE         = new ConfigBooleanHotkeyed("litematica_container_filler.config.name.workingState", false, "", "litematica_container_filler.config.comment.workingState");
    private static final ConfigBoolean CONTINUOUS_FILL              = new ConfigBoolean("litematica_container_filler.config.name.continuousFill", false, "litematica_container_filler.config.comment.continuousFill");
    private static final ConfigBoolean AREA_MODE                    = new ConfigBoolean("litematica_container_filler.config.name.areaMode", false, "litematica_container_filler.config.comment.areaMode");
    public static final ConfigBoolean ENABLE_CARPET_LARGE_BARRELS   = new ConfigBoolean("litematica_container_filler.config.name.enableCarpetLargeBarrels", false, "litematica_container_filler.config.comment.enableCarpetLargeBarrels");
    public static final ConfigInteger FILL_RADIUS                   = new ConfigInteger("litematica_container_filler.config.name.fillRadius", 5, 0, 1024, "litematica_container_filler.config.comment.fillRadius");
    public static final ConfigInteger FILL_DELAY                    = new ConfigInteger("litematica_container_filler.config.name.fillDelay", 0, 0, 100, "litematica_container_filler.config.comment.fillDelay");
    public static final ConfigStringList MATERIAL_REPLACEMENTS      = new ConfigStringList("litematica_container_filler.config.name.materialReplacements", ImmutableList.of(), "litematica_container_filler.config.comment.materialReplacements");

    //数据同步设置
    public static final ConfigBoolean ENABLE_DATA_SYNC              = new ConfigBoolean("litematica_container_filler.config.name.enableDataSync", true, "litematica_container_filler.config.comment.enableDataSync");
    public static final ConfigBoolean ENABLE_OP_NBT_QUERY           = new ConfigBoolean("litematica_container_filler.config.name.enableOpNbtQuery", true, "litematica_container_filler.config.comment.enableOpNbtQuery");

    //自动物流设置
    public static final ConfigBoolean ENABLE_CREATIVE_FILL          = new ConfigBoolean("litematica_container_filler.config.name.creativeFill", true, "litematica_container_filler.config.comment.creativeFill");
    public static final ConfigBoolean ENABLE_QS_EXTRACTION          = new ConfigBoolean("litematica_container_filler.config.name.enableQsExtraction", true, "litematica_container_filler.config.comment.enableQsExtraction");
    public static final ConfigOptionList QUICK_SHULKER_OPEN_MODE    = new ConfigOptionList("litematica_container_filler.config.name.quickShulkerOpenMode", QuickShulkerOpenMode.INVOKE, "litematica_container_filler.config.comment.quickShulkerOpenMode");
    public static final ConfigBoolean AUTO_STASH_ITEMS              = new ConfigBoolean("litematica_container_filler.config.name.autoStashItems", true, "litematica_container_filler.config.comment.autoStashItems");
    public static final ConfigBoolean DROP_EXTRACTED_ITEMS          = new ConfigBoolean("litematica_container_filler.config.name.dropExtractedItems", false, "litematica_container_filler.config.comment.dropExtractedItems");
    public static final ConfigBoolean ENABLE_SAFETY_DELAY           = new ConfigBoolean("litematica_container_filler.config.name.enableSafetyDelay", true, "litematica_container_filler.config.comment.enableSafetyDelay");
    public static final ConfigBoolean HIDE_FILLER_GUI               = new ConfigBoolean("litematica_container_filler.config.name.hideFillerGui", true, "litematica_container_filler.config.comment.hideFillerGui");

    //高亮渲染基础设置
    public static final ConfigBoolean HIGHLIGHT_CONTAINERS          = new ConfigBoolean("litematica_container_filler.config.name.highlightContainers", true, "litematica_container_filler.config.comment.highlightContainers");
    public static final ConfigBoolean HIGHLIGHT_XRAY                = new ConfigBoolean("litematica_container_filler.config.name.highlightXray", true, "litematica_container_filler.config.comment.highlightXray");
    public static final ConfigInteger RENDER_RADIUS                 = new ConfigInteger("litematica_container_filler.config.name.renderRadius", 15, 0, 1024, "litematica_container_filler.config.comment.renderRadius");
    public static final ConfigBoolean SYNC_LITE_LAYER               = new ConfigBoolean("litematica_container_filler.config.name.syncLiteLayer", true, "litematica_container_filler.config.comment.syncLiteLayer");
    public static final ConfigBoolean HIDE_COMPLETED_CONTAINERS     = new ConfigBoolean("litematica_container_filler.config.name.hideCompletedContainers", true, "litematica_container_filler.config.comment.hideCompletedContainers");
    public static final ConfigBoolean HIGHLIGHT_EMPTY_SCHEMATIC_CONTAINERS = new ConfigBoolean("litematica_container_filler.config.name.highlightEmptySchematicContainers", true, "litematica_container_filler.config.comment.highlightEmptySchematicContainers");

    //高亮颜色配置
    public static final ConfigColor HIGHLIGHT_COLOR_UNFILLED        = new ConfigColor("litematica_container_filler.config.name.highlightColorUnfilled", "0x8033CCFF", "litematica_container_filler.config.comment.highlightColorUnfilled");
    public static final ConfigColor HIGHLIGHT_COLOR_PARTIAL         = new ConfigColor("litematica_container_filler.config.name.highlightColorPartial", "0x80FFFF00", "litematica_container_filler.config.comment.highlightColorPartial");
    public static final ConfigColor HIGHLIGHT_COLOR_OVERFILLED      = new ConfigColor("litematica_container_filler.config.name.highlightColorOverfilled", "0x80FF00FF", "litematica_container_filler.config.comment.highlightColorOverfilled");
    public static final ConfigColor HIGHLIGHT_COLOR_WRONG           = new ConfigColor("litematica_container_filler.config.name.highlightColorWrong", "0x80FF0000", "litematica_container_filler.config.comment.highlightColorWrong");
    public static final ConfigColor HIGHLIGHT_COLOR_SATISFIED       = new ConfigColor("litematica_container_filler.config.name.highlightColorSatisfied", "0x8033FF33", "litematica_container_filler.config.comment.highlightColorSatisfied");
    public static final ConfigColor HIGHLIGHT_COLOR_UNKNOWN         = new ConfigColor("litematica_container_filler.config.name.highlightColorUnknown", "0x80FFA500", "litematica_container_filler.config.comment.highlightColorUnknown");

    public static final List<IConfigBase> OPTIONS;
    public static final List<IConfigBase> CORE_OPTIONS;
    public static final List<IConfigBase> DATA_OPTIONS;
    public static final List<IConfigBase> LOGISTICS_OPTIONS;
    public static final List<IConfigBase> RENDER_OPTIONS;
    private static final List<IConfigBase> LEGACY_OPTIONS;

    static {
        CORE_OPTIONS = ImmutableList.of(
                ENABLE_MOD,
                WORKING_STATE,
                FILL_RADIUS,
                FILL_DELAY,
                ENABLE_CARPET_LARGE_BARRELS,
                MATERIAL_REPLACEMENTS
        );

        DATA_OPTIONS = ImmutableList.of(
                ENABLE_DATA_SYNC,
                ENABLE_OP_NBT_QUERY
        );

        LOGISTICS_OPTIONS = ImmutableList.of(
                ENABLE_CREATIVE_FILL,
                ENABLE_QS_EXTRACTION,
                QUICK_SHULKER_OPEN_MODE,
                AUTO_STASH_ITEMS,
                DROP_EXTRACTED_ITEMS,
                ENABLE_SAFETY_DELAY,
                HIDE_FILLER_GUI
        );

        RENDER_OPTIONS = ImmutableList.of(
                HIGHLIGHT_CONTAINERS,
                HIGHLIGHT_XRAY,
                RENDER_RADIUS,
                SYNC_LITE_LAYER,
                HIDE_COMPLETED_CONTAINERS,
                HIGHLIGHT_EMPTY_SCHEMATIC_CONTAINERS,
                HIGHLIGHT_COLOR_UNFILLED,
                HIGHLIGHT_COLOR_PARTIAL,
                HIGHLIGHT_COLOR_OVERFILLED,
                HIGHLIGHT_COLOR_WRONG,
                HIGHLIGHT_COLOR_SATISFIED,
                HIGHLIGHT_COLOR_UNKNOWN
        );

        LEGACY_OPTIONS = ImmutableList.of(CONTINUOUS_FILL, AREA_MODE);

        ImmutableList.Builder<IConfigBase> builder = ImmutableList.builder();
        builder.addAll(CORE_OPTIONS);
        builder.addAll(DATA_OPTIONS);
        builder.addAll(LOGISTICS_OPTIONS);
        builder.addAll(RENDER_OPTIONS);
        OPTIONS = builder.build();
    }

    @Override
    public void load() {
        File file = new File(FabricLoader.getInstance().getConfigDir().toFile(), CONFIG_FILE_NAME);
        if (file.exists() && file.canRead()) {
            JsonElement element = JsonUtils.parseJsonFile(file);
            if (element != null && element.isJsonObject()) {
                JsonObject root = element.getAsJsonObject();
                ConfigUtils.readConfigBase(root, "Features", Configs.LEGACY_OPTIONS);
                ConfigUtils.readConfigBase(root, "Features", Configs.OPTIONS);
                ConfigUtils.readConfigBase(root, "Hotkeys", Hotkeys.HOTKEY_LIST);
                if (CONTINUOUS_FILL.getBooleanValue()) {
                    WORKING_STATE.setBooleanValue(true);
                }
            }
        }
    }

    @Override
    public void save() {
        File dir = FabricLoader.getInstance().getConfigDir().toFile();
        if ((dir.exists() && dir.isDirectory()) || dir.mkdirs()) {
            JsonObject root = new JsonObject();
            ConfigUtils.writeConfigBase(root, "Features", Configs.OPTIONS);
            ConfigUtils.writeConfigBase(root, "Hotkeys", Hotkeys.HOTKEY_LIST);
            JsonUtils.writeJsonToFile(root, new File(dir, CONFIG_FILE_NAME));
        }

        InputHandler.getInstance().addKeysToMap(InputEventHandler.getKeybindManager());
    }
    public static void init() {
        Configs.INSTANCE.load();
        ConfigManager.getInstance().registerConfigHandler(Reference.MOD_ID, Configs.INSTANCE);
        InputEventHandler.getKeybindManager().registerKeybindProvider(InputHandler.getInstance());
        InputEventHandler.getInputManager().registerKeyboardInputHandler(InputHandler.getInstance());
        fi.dy.masa.malilib.registry.Registry.CONFIG_SCREEN.registerConfigScreenFactory(
                new fi.dy.masa.malilib.util.data.ModInfo(Reference.MOD_ID, Reference.MOD_SHORT_NAME, GuiConfigs::new)
        );
    }
}
