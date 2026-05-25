package com.mimicenzymes.litematicafiller.config;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mimicenzymes.litematicafiller.Reference;
import com.mimicenzymes.litematicafiller.filter.ContainerFilterMode;
import com.mimicenzymes.litematicafiller.filter.ContainerFilterScope;
import com.mimicenzymes.litematicafiller.gui.GuiConfigs;
import com.mimicenzymes.litematicafiller.input.InputHandler;
import com.mimicenzymes.litematicafiller.tool.ContainerClearOutputMode;
import com.mimicenzymes.litematicafiller.tool.ContainerToolMode;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import fi.dy.masa.malilib.config.options.ConfigColor;
import fi.dy.masa.malilib.config.options.ConfigDouble;
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

    //鏍稿績杩愯璁剧疆
    public static final ConfigBooleanHotkeyed ENABLE_MOD            = new ConfigBooleanHotkeyed("litematica_container_filler.config.name.enableMod", true, "", "litematica_container_filler.config.comment.enableMod");
    public static final ConfigBooleanHotkeyed WORKING_STATE         = new ConfigBooleanHotkeyed("litematica_container_filler.config.name.workingState", false, "", "litematica_container_filler.config.comment.workingState");
    private static final ConfigBoolean CONTINUOUS_FILL              = new ConfigBoolean("litematica_container_filler.config.name.continuousFill", false, "litematica_container_filler.config.comment.continuousFill");
    private static final ConfigBoolean AREA_MODE                    = new ConfigBoolean("litematica_container_filler.config.name.areaMode", false, "litematica_container_filler.config.comment.areaMode");
    private static final ConfigBoolean LEGACY_ENABLE_CARPET_LARGE_BARRELS = new ConfigBoolean("litematica_container_filler.config.name.enableCarpetLargeBarrels", false, "litematica_container_filler.config.comment.enableCarpetLargeBarrels");
    public static final ConfigOptionList CARPET_LARGE_BARREL_MODE   = new ConfigOptionList("litematica_container_filler.config.name.carpetLargeBarrelMode", CarpetLargeBarrelMode.AUTO, "litematica_container_filler.config.comment.carpetLargeBarrelMode");
    public static final ConfigInteger FILL_RADIUS                   = new ConfigInteger("litematica_container_filler.config.name.fillRadius", 5, 0, 1024, "litematica_container_filler.config.comment.fillRadius");
    public static final ConfigInteger FILL_DELAY                    = new ConfigInteger("litematica_container_filler.config.name.fillDelay", 0, 0, 100, "litematica_container_filler.config.comment.fillDelay");
    public static final ConfigBoolean ENABLE_SAFETY_DELAY           = new ConfigBoolean("litematica_container_filler.config.name.enableSafetyDelay", true, "litematica_container_filler.config.comment.enableSafetyDelay");
    public static final ConfigBoolean ENABLE_FILL_STATE_PROTECTION  = new ConfigBoolean("litematica_container_filler.config.name.enableFillStateProtection", true, "litematica_container_filler.config.comment.enableFillStateProtection");
    public static final ConfigBoolean RATE_LIMIT_CLICK_PACKETS      = new ConfigBoolean("litematica_container_filler.config.name.rateLimitClickPackets", false, "litematica_container_filler.config.comment.rateLimitClickPackets");
    public static final ConfigInteger CLICK_PACKET_RATE_LIMIT        = new ConfigInteger("litematica_container_filler.config.name.clickPacketRateLimit", 4, 1, 1024, "litematica_container_filler.config.comment.clickPacketRateLimit");
    public static final ConfigOptionList CONTAINER_FILTER_MODE      = new ConfigOptionList("litematica_container_filler.config.name.containerFilterMode", ContainerFilterMode.DISABLED, "litematica_container_filler.config.comment.containerFilterMode");
    public static final ConfigOptionList CONTAINER_FILTER_SCOPE     = new ConfigOptionList("litematica_container_filler.config.name.containerFilterScope", ContainerFilterScope.BOTH, "litematica_container_filler.config.comment.containerFilterScope");
    public static final ConfigStringList CONTAINER_FILTER_LIST      = new ConfigStringList("litematica_container_filler.config.name.containerFilterList", ImmutableList.of("minecraft:chest", "minecraft:barrel", "minecraft:trapped_chest", "minecraft:shulker_box", "minecraft:*_shulker_box", "minecraft:crafter", "minecraft:hopper", "minecraft:dispenser", "minecraft:dropper", "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker", "minecraft:brewing_stand"), "litematica_container_filler.config.comment.containerFilterList");
    public static final ConfigStringList MATERIAL_REPLACEMENTS      = new ConfigStringList("litematica_container_filler.config.name.materialReplacements", ImmutableList.of(), "litematica_container_filler.config.comment.materialReplacements");

    //鏁版嵁鍚屾璁剧疆
    public static final ConfigBooleanHotkeyed ENABLE_DATA_SYNC      = new ConfigBooleanHotkeyed("litematica_container_filler.config.name.enableDataSync", true, "", "litematica_container_filler.config.comment.enableDataSync");
    public static final ConfigBoolean ENABLE_OP_NBT_QUERY           = new ConfigBoolean("litematica_container_filler.config.name.enableOpNbtQuery", true, "litematica_container_filler.config.comment.enableOpNbtQuery");

    //鑷姩鐗╂祦璁剧疆
    public static final ConfigBoolean ENABLE_CREATIVE_FILL          = new ConfigBoolean("litematica_container_filler.config.name.creativeFill", true, "litematica_container_filler.config.comment.creativeFill");
    public static final ConfigBoolean ENABLE_QS_EXTRACTION          = new ConfigBoolean("litematica_container_filler.config.name.enableQsExtraction", true, "litematica_container_filler.config.comment.enableQsExtraction");
    public static final ConfigOptionList QUICK_SHULKER_OPEN_MODE    = new ConfigOptionList("litematica_container_filler.config.name.quickShulkerOpenMode", QuickShulkerOpenMode.INVOKE, "litematica_container_filler.config.comment.quickShulkerOpenMode");
    public static final ConfigBoolean MATCH_SHULKER_BOXES_BY_CONTENT = new ConfigBoolean("litematica_container_filler.config.name.matchShulkerBoxesByContent", false, "litematica_container_filler.config.comment.matchShulkerBoxesByContent");
    private static final ConfigBoolean AUTO_STASH_ITEMS             = new ConfigBoolean("litematica_container_filler.config.name.autoStashItems", true, "litematica_container_filler.config.comment.autoStashItems");
    public static final ConfigBoolean STORE_ORDERLY                 = new ConfigBoolean("litematica_container_filler.config.name.storeOrderly", true, "litematica_container_filler.config.comment.storeOrderly");
    public static final ConfigBoolean DROP_EXTRACTED_ITEMS          = new ConfigBoolean("litematica_container_filler.config.name.dropExtractedItems", false, "litematica_container_filler.config.comment.dropExtractedItems");
    public static final ConfigBoolean DROP_ITEMS_FROM_EMPTY_SCHEMATIC_CONTAINERS = new ConfigBoolean("litematica_container_filler.config.name.dropItemsFromEmptySchematicContainers", false, "litematica_container_filler.config.comment.dropItemsFromEmptySchematicContainers");
    private static final ConfigBoolean HIDE_FILLER_GUI              = new ConfigBoolean("litematica_container_filler.config.name.hideFillerGui", true, "litematica_container_filler.config.comment.hideFillerGui");
    public static final ConfigBoolean HIDE_PROJECTION_FILL_GUI      = new ConfigBoolean("litematica_container_filler.config.name.hideProjectionFillGui", true, "litematica_container_filler.config.comment.hideProjectionFillGui");
    public static final ConfigBoolean TOOL_ENABLED                  = new ConfigBoolean("litematica_container_filler.config.name.toolEnabled", false, "litematica_container_filler.config.comment.toolEnabled");
    public static final ConfigOptionList CONTAINER_TOOL_MODE        = new ConfigOptionList("litematica_container_filler.config.name.containerToolMode", ContainerToolMode.CLEAR, "litematica_container_filler.config.comment.containerToolMode");
    public static final ConfigBoolean ENABLE_SYNC_TOOL_QS_EXTRACTION = new ConfigBoolean("litematica_container_filler.config.name.enableSyncToolQsExtraction", true, "litematica_container_filler.config.comment.enableSyncToolQsExtraction");
    public static final ConfigOptionList CONTAINER_CLEAR_OUTPUT_MODE = new ConfigOptionList("litematica_container_filler.config.name.containerClearOutputMode", ContainerClearOutputMode.DROP, "litematica_container_filler.config.comment.containerClearOutputMode");
    public static final ConfigBoolean HIDE_TOOL_GUI                 = new ConfigBoolean("litematica_container_filler.config.name.hideToolGui", true, "litematica_container_filler.config.comment.hideToolGui");
    public static final ConfigBooleanHotkeyed ENABLE_TOOL_HUD       = new ConfigBooleanHotkeyed("litematica_container_filler.config.name.enableToolHud", false, "", "litematica_container_filler.config.comment.enableToolHud");
    public static final ConfigOptionList TOOL_HUD_STYLE             = new ConfigOptionList("litematica_container_filler.config.name.toolHudStyle", ToolHudStyle.FIXED_CARD, "litematica_container_filler.config.comment.toolHudStyle");
    public static final ConfigDouble TOOL_HUD_OPACITY               = new ConfigDouble("litematica_container_filler.config.name.toolHudOpacity", 0.52D, 0.1D, 1.0D, "litematica_container_filler.config.comment.toolHudOpacity");
    public static final ConfigDouble TOOL_HUD_SMOOTHING             = new ConfigDouble("litematica_container_filler.config.name.toolHudSmoothing", 0.22D, 0.05D, 0.8D, "litematica_container_filler.config.comment.toolHudSmoothing");
    public static final ConfigInteger TOOL_HUD_OFFSET               = new ConfigInteger("litematica_container_filler.config.name.toolHudOffset", 34, 12, 120, "litematica_container_filler.config.comment.toolHudOffset");
    public static final ConfigInteger TOOL_HUD_SCALE                = new ConfigInteger("litematica_container_filler.config.name.toolHudScale", 100, 70, 150, "litematica_container_filler.config.comment.toolHudScale");
    public static final ConfigInteger TOOL_HUD_ICON_SCALE           = new ConfigInteger("litematica_container_filler.config.name.toolHudIconScale", 82, 50, 150, "litematica_container_filler.config.comment.toolHudIconScale");
    public static final ConfigInteger TOOL_HUD_CUSTOM_X             = new ConfigInteger("litematica_container_filler.config.name.toolHudCustomX", 112, -1000, 1000, "litematica_container_filler.config.comment.toolHudCustomX");
    public static final ConfigInteger TOOL_HUD_CUSTOM_Y             = new ConfigInteger("litematica_container_filler.config.name.toolHudCustomY", -86, -1000, 1000, "litematica_container_filler.config.comment.toolHudCustomY");
    public static final ConfigInteger TOOL_HUD_FRAME_RATE           = new ConfigInteger("litematica_container_filler.config.name.toolHudFrameRate", 30, 0, 240, "litematica_container_filler.config.comment.toolHudFrameRate");

    // Container tool hotkeys
    public static final ConfigBooleanHotkeyed TOOL_CLEAR_MODE        = new ConfigBooleanHotkeyed("litematica_container_filler.config.name.toolClearMode", false, "", "litematica_container_filler.config.comment.toolClearMode");
    public static final ConfigBooleanHotkeyed TOOL_FILL_FULL_MODE    = new ConfigBooleanHotkeyed("litematica_container_filler.config.name.toolFillFullMode", false, "", "litematica_container_filler.config.comment.toolFillFullMode");
    public static final ConfigBooleanHotkeyed TOOL_COPY_MODE         = new ConfigBooleanHotkeyed("litematica_container_filler.config.name.toolCopyMode", false, "", "litematica_container_filler.config.comment.toolCopyMode");

    //楂樹寒娓叉煋鍩虹璁剧疆
    public static final ConfigBooleanHotkeyed HIGHLIGHT_CONTAINERS  = new ConfigBooleanHotkeyed("litematica_container_filler.config.name.highlightContainers", true, "", "litematica_container_filler.config.comment.highlightContainers");
    public static final ConfigBooleanHotkeyed HIGHLIGHT_XRAY        = new ConfigBooleanHotkeyed("litematica_container_filler.config.name.highlightXray", true, "", "litematica_container_filler.config.comment.highlightXray");
    public static final ConfigInteger RENDER_RADIUS                 = new ConfigInteger("litematica_container_filler.config.name.renderRadius", 15, 0, 1024, "litematica_container_filler.config.comment.renderRadius");
    public static final ConfigBooleanHotkeyed SYNC_LITE_LAYER       = new ConfigBooleanHotkeyed("litematica_container_filler.config.name.syncLiteLayer", true, "", "litematica_container_filler.config.comment.syncLiteLayer");
    public static final ConfigBooleanHotkeyed HIDE_COMPLETED_CONTAINERS = new ConfigBooleanHotkeyed("litematica_container_filler.config.name.hideCompletedContainers", true, "", "litematica_container_filler.config.comment.hideCompletedContainers");
    public static final ConfigBooleanHotkeyed HIGHLIGHT_EMPTY_SCHEMATIC_CONTAINERS = new ConfigBooleanHotkeyed("litematica_container_filler.config.name.highlightEmptySchematicContainers", true, "", "litematica_container_filler.config.comment.highlightEmptySchematicContainers");
    public static final ConfigBooleanHotkeyed RENDER_STATE_UNFILLED = new ConfigBooleanHotkeyed("litematica_container_filler.config.name.renderStateUnfilled", true, "", "litematica_container_filler.config.comment.renderStateUnfilled");
    public static final ConfigBooleanHotkeyed RENDER_STATE_PARTIAL  = new ConfigBooleanHotkeyed("litematica_container_filler.config.name.renderStatePartial", true, "", "litematica_container_filler.config.comment.renderStatePartial");
    public static final ConfigBooleanHotkeyed RENDER_STATE_OVERFILLED = new ConfigBooleanHotkeyed("litematica_container_filler.config.name.renderStateOverfilled", true, "", "litematica_container_filler.config.comment.renderStateOverfilled");
    public static final ConfigBooleanHotkeyed RENDER_STATE_WRONG    = new ConfigBooleanHotkeyed("litematica_container_filler.config.name.renderStateWrong", true, "", "litematica_container_filler.config.comment.renderStateWrong");
    public static final ConfigBooleanHotkeyed RENDER_STATE_SATISFIED = new ConfigBooleanHotkeyed("litematica_container_filler.config.name.renderStateSatisfied", true, "", "litematica_container_filler.config.comment.renderStateSatisfied");
    public static final ConfigBooleanHotkeyed RENDER_STATE_UNKNOWN  = new ConfigBooleanHotkeyed("litematica_container_filler.config.name.renderStateUnknown", true, "", "litematica_container_filler.config.comment.renderStateUnknown");
    public static final ConfigBooleanHotkeyed HIGHLIGHT_UNPLACED_CONTAINERS = new ConfigBooleanHotkeyed("litematica_container_filler.config.name.highlightUnplacedContainers", true, "", "litematica_container_filler.config.comment.highlightUnplacedContainers");
    public static final ConfigBooleanHotkeyed RENDER_STATE_GLASS    = new ConfigBooleanHotkeyed("litematica_container_filler.config.name.renderStateGlass", true, "", "litematica_container_filler.config.comment.renderStateGlass");
    public static final ConfigBooleanHotkeyed RENDER_STATE_TOP_PLATE = new ConfigBooleanHotkeyed("litematica_container_filler.config.name.renderStateTopPlate", true, "", "litematica_container_filler.config.comment.renderStateTopPlate");
    public static final ConfigBooleanHotkeyed RENDER_FILLING_ARROW  = new ConfigBooleanHotkeyed("litematica_container_filler.config.name.renderFillingArrow", true, "", "litematica_container_filler.config.comment.renderFillingArrow");
    public static final ConfigBooleanHotkeyed RENDER_QUEUED_SPINNER = new ConfigBooleanHotkeyed("litematica_container_filler.config.name.renderQueuedSpinner", true, "", "litematica_container_filler.config.comment.renderQueuedSpinner");
    public static final ConfigBooleanHotkeyed RENDER_MISSING_MATERIAL_MARKER = new ConfigBooleanHotkeyed("litematica_container_filler.config.name.renderMissingMaterialMarker", true, "", "litematica_container_filler.config.comment.renderMissingMaterialMarker");
    public static final ConfigInteger MAX_QUEUED_RENDER_OVERLAYS    = new ConfigInteger("litematica_container_filler.config.name.maxQueuedRenderOverlays", 20, 0, 256, "litematica_container_filler.config.comment.maxQueuedRenderOverlays");
    public static final ConfigInteger TASK_OVERLAY_LINGER_TICKS     = new ConfigInteger("litematica_container_filler.config.name.taskOverlayLingerTicks", 40, 0, 200, "litematica_container_filler.config.comment.taskOverlayLingerTicks");
    public static final ConfigDouble HIGHLIGHT_GLASS_ALPHA_MULTIPLIER = new ConfigDouble("litematica_container_filler.config.name.highlightGlassAlphaMultiplier", 0.24D, 0.0D, 1.0D, "litematica_container_filler.config.comment.highlightGlassAlphaMultiplier");
    public static final ConfigDouble HIGHLIGHT_TOP_PLATE_SIZE       = new ConfigDouble("litematica_container_filler.config.name.highlightTopPlateSize", 0.64D, 0.1D, 1.2D, "litematica_container_filler.config.comment.highlightTopPlateSize");
    public static final ConfigDouble TASK_OVERLAY_SCALE             = new ConfigDouble("litematica_container_filler.config.name.taskOverlayScale", 1.0D, 0.25D, 3.0D, "litematica_container_filler.config.comment.taskOverlayScale");
    public static final ConfigInteger TASK_MARKER_ANIMATION_FPS     = new ConfigInteger("litematica_container_filler.config.name.taskMarkerAnimationFps", 30, 0, 240, "litematica_container_filler.config.comment.taskMarkerAnimationFps");

    //楂樹寒棰滆壊閰嶇疆
    public static final ConfigColor HIGHLIGHT_COLOR_UNFILLED        = new ConfigColor("litematica_container_filler.config.name.highlightColorUnfilled", "0x806E5CFF", "litematica_container_filler.config.comment.highlightColorUnfilled");
    public static final ConfigColor HIGHLIGHT_COLOR_PARTIAL         = new ConfigColor("litematica_container_filler.config.name.highlightColorPartial", "0x80FFB02E", "litematica_container_filler.config.comment.highlightColorPartial");
    public static final ConfigColor HIGHLIGHT_COLOR_OVERFILLED      = new ConfigColor("litematica_container_filler.config.name.highlightColorOverfilled", "0x80FF3EA5", "litematica_container_filler.config.comment.highlightColorOverfilled");
    public static final ConfigColor HIGHLIGHT_COLOR_WRONG           = new ConfigColor("litematica_container_filler.config.name.highlightColorWrong", "0x80FF5A45", "litematica_container_filler.config.comment.highlightColorWrong");
    public static final ConfigColor HIGHLIGHT_COLOR_SATISFIED       = new ConfigColor("litematica_container_filler.config.name.highlightColorSatisfied", "0x8044FFB2", "litematica_container_filler.config.comment.highlightColorSatisfied");
    public static final ConfigColor HIGHLIGHT_COLOR_UNKNOWN         = new ConfigColor("litematica_container_filler.config.name.highlightColorUnknown", "0x80B66DFF", "litematica_container_filler.config.comment.highlightColorUnknown");
    public static final ConfigColor HIGHLIGHT_COLOR_UNPLACED        = new ConfigColor("litematica_container_filler.config.name.highlightColorUnplaced", "0x8097A6C7", "litematica_container_filler.config.comment.highlightColorUnplaced");
    public static final ConfigColor HIGHLIGHT_COLOR_FILLING         = new ConfigColor("litematica_container_filler.config.name.highlightColorFilling", "0xD00DFFF2", "litematica_container_filler.config.comment.highlightColorFilling");
    public static final ConfigColor HIGHLIGHT_COLOR_QUEUED          = new ConfigColor("litematica_container_filler.config.name.highlightColorQueued", "0xC0FFB02E", "litematica_container_filler.config.comment.highlightColorQueued");
    public static final ConfigColor HIGHLIGHT_COLOR_MISSING_MATERIAL = new ConfigColor("litematica_container_filler.config.name.highlightColorMissingMaterial", "0xD0FF385C", "litematica_container_filler.config.comment.highlightColorMissingMaterial");

    public static final List<IConfigBase> OPTIONS;
    public static final List<IConfigBase> CORE_OPTIONS;
    public static final List<IConfigBase> DATA_OPTIONS;
    public static final List<IConfigBase> LOGISTICS_OPTIONS;
    public static final List<IConfigBase> FILTER_OPTIONS;
    public static final List<IConfigBase> TOOL_OPTIONS;
    public static final List<IConfigBase> RENDER_OPTIONS;
    public static final List<ConfigBooleanHotkeyed> BOOLEAN_HOTKEY_OPTIONS;
    private static final List<IConfigBase> LEGACY_OPTIONS;

    static {
        CORE_OPTIONS = ImmutableList.of(
                ENABLE_MOD,
                WORKING_STATE,
                FILL_RADIUS,
                FILL_DELAY,
                ENABLE_SAFETY_DELAY,
                ENABLE_FILL_STATE_PROTECTION,
                CARPET_LARGE_BARREL_MODE,
                MATERIAL_REPLACEMENTS
        );

        DATA_OPTIONS = ImmutableList.of(
                ENABLE_DATA_SYNC,
                ENABLE_OP_NBT_QUERY,
                RATE_LIMIT_CLICK_PACKETS,
                CLICK_PACKET_RATE_LIMIT
        );

        FILTER_OPTIONS = ImmutableList.of(
                CONTAINER_FILTER_MODE,
                CONTAINER_FILTER_SCOPE,
                CONTAINER_FILTER_LIST
        );

        LOGISTICS_OPTIONS = ImmutableList.of(
                ENABLE_CREATIVE_FILL,
                ENABLE_QS_EXTRACTION,
                QUICK_SHULKER_OPEN_MODE,
                MATCH_SHULKER_BOXES_BY_CONTENT,
                STORE_ORDERLY,
                DROP_EXTRACTED_ITEMS,
                DROP_ITEMS_FROM_EMPTY_SCHEMATIC_CONTAINERS,
                HIDE_PROJECTION_FILL_GUI
        );

        TOOL_OPTIONS = ImmutableList.of(
                TOOL_ENABLED,
                CONTAINER_TOOL_MODE,
                ENABLE_SYNC_TOOL_QS_EXTRACTION,
                CONTAINER_CLEAR_OUTPUT_MODE,
                HIDE_TOOL_GUI,
                ENABLE_TOOL_HUD,
                TOOL_HUD_STYLE,
                TOOL_HUD_OPACITY,
                TOOL_HUD_SMOOTHING,
                TOOL_HUD_OFFSET,
                TOOL_HUD_SCALE,
                TOOL_HUD_ICON_SCALE,
                TOOL_HUD_CUSTOM_X,
                TOOL_HUD_CUSTOM_Y,
                TOOL_HUD_FRAME_RATE
        );

        RENDER_OPTIONS = ImmutableList.of(
                HIGHLIGHT_CONTAINERS,
                HIGHLIGHT_XRAY,
                RENDER_RADIUS,
                SYNC_LITE_LAYER,
                HIDE_COMPLETED_CONTAINERS,
                HIGHLIGHT_EMPTY_SCHEMATIC_CONTAINERS,
                RENDER_STATE_UNFILLED,
                RENDER_STATE_PARTIAL,
                RENDER_STATE_OVERFILLED,
                RENDER_STATE_WRONG,
                RENDER_STATE_SATISFIED,
                RENDER_STATE_UNKNOWN,
                HIGHLIGHT_UNPLACED_CONTAINERS,
                RENDER_STATE_GLASS,
                RENDER_STATE_TOP_PLATE,
                RENDER_FILLING_ARROW,
                RENDER_QUEUED_SPINNER,
                RENDER_MISSING_MATERIAL_MARKER,
                MAX_QUEUED_RENDER_OVERLAYS,
                TASK_OVERLAY_LINGER_TICKS,
                HIGHLIGHT_GLASS_ALPHA_MULTIPLIER,
                HIGHLIGHT_TOP_PLATE_SIZE,
                TASK_OVERLAY_SCALE,
                TASK_MARKER_ANIMATION_FPS,
                HIGHLIGHT_COLOR_UNFILLED,
                HIGHLIGHT_COLOR_PARTIAL,
                HIGHLIGHT_COLOR_OVERFILLED,
                HIGHLIGHT_COLOR_WRONG,
                HIGHLIGHT_COLOR_SATISFIED,
                HIGHLIGHT_COLOR_UNKNOWN,
                HIGHLIGHT_COLOR_UNPLACED,
                HIGHLIGHT_COLOR_FILLING,
                HIGHLIGHT_COLOR_QUEUED,
                HIGHLIGHT_COLOR_MISSING_MATERIAL
        );

        BOOLEAN_HOTKEY_OPTIONS = ImmutableList.of(
                ENABLE_MOD,
                ENABLE_DATA_SYNC,
                ENABLE_TOOL_HUD,
                HIGHLIGHT_CONTAINERS,
                HIGHLIGHT_XRAY,
                SYNC_LITE_LAYER,
                HIDE_COMPLETED_CONTAINERS,
                HIGHLIGHT_EMPTY_SCHEMATIC_CONTAINERS,
                RENDER_STATE_UNFILLED,
                RENDER_STATE_PARTIAL,
                RENDER_STATE_OVERFILLED,
                RENDER_STATE_WRONG,
                RENDER_STATE_SATISFIED,
                RENDER_STATE_UNKNOWN,
                HIGHLIGHT_UNPLACED_CONTAINERS,
                RENDER_STATE_GLASS,
                RENDER_STATE_TOP_PLATE,
                RENDER_FILLING_ARROW,
                RENDER_QUEUED_SPINNER,
                RENDER_MISSING_MATERIAL_MARKER
        );

        LEGACY_OPTIONS = ImmutableList.of(CONTINUOUS_FILL, AREA_MODE, LEGACY_ENABLE_CARPET_LARGE_BARRELS, AUTO_STASH_ITEMS, HIDE_FILLER_GUI);

        ImmutableList.Builder<IConfigBase> builder = ImmutableList.builder();
        builder.addAll(CORE_OPTIONS);
        builder.addAll(DATA_OPTIONS);
        builder.addAll(LOGISTICS_OPTIONS);
        builder.addAll(FILTER_OPTIONS);
        builder.addAll(TOOL_OPTIONS);
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
                migrateLegacyLargeBarrelConfig(root);
                migrateLegacyAutoStashConfig(root);
                migrateLegacyHiddenGuiConfig(root);
                validateConditionalOptions();
            }
        }
    }

    private static void validateConditionalOptions() {
        if (CONTAINER_TOOL_MODE.getOptionListValue() == ContainerToolMode.PACK && !ContainerToolMode.isPackingAvailable()) {
            CONTAINER_TOOL_MODE.setOptionListValue(ContainerToolMode.CLEAR);
            TOOL_ENABLED.setBooleanValue(false);
        }
    }

    private static void migrateLegacyLargeBarrelConfig(JsonObject root) {
        if (hasConfigEntry(root, CARPET_LARGE_BARREL_MODE.getName())) return;
        if (!hasConfigEntry(root, LEGACY_ENABLE_CARPET_LARGE_BARRELS.getName())) return;

        CARPET_LARGE_BARREL_MODE.setValueFromString(
                LEGACY_ENABLE_CARPET_LARGE_BARRELS.getBooleanValue() ? CarpetLargeBarrelMode.ON.getStringValue() : CarpetLargeBarrelMode.OFF.getStringValue()
        );
    }

    private static void migrateLegacyAutoStashConfig(JsonObject root) {
        if (hasConfigEntry(root, STORE_ORDERLY.getName())) return;
        if (!hasConfigEntry(root, AUTO_STASH_ITEMS.getName())) return;

        STORE_ORDERLY.setBooleanValue(AUTO_STASH_ITEMS.getBooleanValue());
    }

    private static void migrateLegacyHiddenGuiConfig(JsonObject root) {
        if (!hasConfigEntry(root, HIDE_FILLER_GUI.getName())) return;

        boolean legacyValue = HIDE_FILLER_GUI.getBooleanValue();
        if (!hasConfigEntry(root, HIDE_PROJECTION_FILL_GUI.getName())) {
            HIDE_PROJECTION_FILL_GUI.setBooleanValue(legacyValue);
        }
        if (!hasConfigEntry(root, HIDE_TOOL_GUI.getName())) {
            HIDE_TOOL_GUI.setBooleanValue(legacyValue);
        }
    }

    private static boolean hasConfigEntry(JsonObject root, String key) {
        if (root == null || key == null) return false;
        JsonObject features = JsonUtils.getNestedObject(root, "Features", false);
        return features != null && features.has(key);
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

    public static void saveToFile() {
        INSTANCE.save();
        ConfigManager.getInstance().onConfigsChanged(Reference.MOD_ID);
    }

    public static CarpetLargeBarrelMode getCarpetLargeBarrelMode() {
        if (CARPET_LARGE_BARREL_MODE.getOptionListValue() instanceof CarpetLargeBarrelMode mode) {
            return mode;
        }
        return CarpetLargeBarrelMode.AUTO;
    }
}
