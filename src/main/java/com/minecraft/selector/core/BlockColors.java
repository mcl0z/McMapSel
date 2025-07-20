package com.minecraft.selector.core;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 方块颜色映射类
 * 对应Python代码中的BLOCK_COLORS和get_block_color函数
 */
public class BlockColors {
    
    // 方块类型到颜色的映射 (RGB格式)
    private static final Map<String, Color> BLOCK_COLORS = new HashMap<>();
    
    // 线程安全的颜色缓存
    private static final Map<String, Color> COLOR_CACHE = new ConcurrentHashMap<>();

    // 从Minecraft JAR提取的颜色
    private static MinecraftResourceExtractor resourceExtractor = null;
    
    static {
        // 石头类
        BLOCK_COLORS.put("stone", new Color(127, 127, 127));
        BLOCK_COLORS.put("cobblestone", new Color(110, 110, 110));
        BLOCK_COLORS.put("granite", new Color(154, 123, 100));
        BLOCK_COLORS.put("diorite", new Color(207, 207, 207));
        BLOCK_COLORS.put("andesite", new Color(138, 138, 138));
        
        // 矿石类
        BLOCK_COLORS.put("coal_ore", new Color(46, 46, 46));
        BLOCK_COLORS.put("iron_ore", new Color(197, 145, 106));
        BLOCK_COLORS.put("gold_ore", new Color(252, 222, 112));
        BLOCK_COLORS.put("diamond_ore", new Color(93, 236, 245));
        BLOCK_COLORS.put("emerald_ore", new Color(23, 221, 98));
        BLOCK_COLORS.put("lapis_ore", new Color(22, 64, 201));
        BLOCK_COLORS.put("redstone_ore", new Color(255, 0, 0));
        
        // 泥土和草方块
        BLOCK_COLORS.put("dirt", new Color(139, 111, 63));
        BLOCK_COLORS.put("grass_block", new Color(85, 174, 58));
        BLOCK_COLORS.put("podzol", new Color(106, 67, 27));
        BLOCK_COLORS.put("mycelium", new Color(126, 108, 140));
        
        // 沙子和沙砾
        BLOCK_COLORS.put("sand", new Color(219, 207, 142));
        BLOCK_COLORS.put("red_sand", new Color(189, 106, 55));
        BLOCK_COLORS.put("gravel", new Color(150, 141, 125));
        
        // 木头类
        BLOCK_COLORS.put("oak_log", new Color(188, 152, 98));
        BLOCK_COLORS.put("spruce_log", new Color(109, 84, 59));
        BLOCK_COLORS.put("birch_log", new Color(215, 203, 143));
        BLOCK_COLORS.put("jungle_log", new Color(151, 114, 80));
        BLOCK_COLORS.put("acacia_log", new Color(169, 88, 33));
        BLOCK_COLORS.put("dark_oak_log", new Color(76, 51, 25));
        
        // 树叶类
        BLOCK_COLORS.put("oak_leaves", new Color(42, 132, 39));
        BLOCK_COLORS.put("spruce_leaves", new Color(23, 89, 44));
        BLOCK_COLORS.put("birch_leaves", new Color(115, 197, 72));
        BLOCK_COLORS.put("jungle_leaves", new Color(34, 130, 31));
        BLOCK_COLORS.put("acacia_leaves", new Color(95, 175, 53));
        BLOCK_COLORS.put("dark_oak_leaves", new Color(32, 92, 22));
        BLOCK_COLORS.put("pale_oak_leaves", new Color(85, 140, 75)); // 苍白橡木叶 - 浅绿色
        
        // 水和岩浆
        BLOCK_COLORS.put("water", new Color(60, 68, 170));
        BLOCK_COLORS.put("lava", new Color(234, 92, 15));
        
        // 冰和雪
        BLOCK_COLORS.put("ice", new Color(160, 233, 255));
        BLOCK_COLORS.put("snow", new Color(255, 255, 255));
        BLOCK_COLORS.put("snow_block", new Color(243, 244, 251));
        
        // 植物
        BLOCK_COLORS.put("grass", new Color(88, 169, 47));
        BLOCK_COLORS.put("tall_grass", new Color(94, 174, 49));
        BLOCK_COLORS.put("fern", new Color(82, 139, 62));
        BLOCK_COLORS.put("large_fern", new Color(82, 139, 62));
        BLOCK_COLORS.put("dandelion", new Color(255, 236, 79));
        BLOCK_COLORS.put("poppy", new Color(237, 48, 44));
        
        // 其他常见方块
        BLOCK_COLORS.put("bedrock", new Color(10, 10, 10));
        BLOCK_COLORS.put("obsidian", new Color(21, 18, 30));
        BLOCK_COLORS.put("netherrack", new Color(114, 58, 57));
        BLOCK_COLORS.put("soul_sand", new Color(85, 67, 54));
        BLOCK_COLORS.put("glowstone", new Color(254, 217, 63));
        BLOCK_COLORS.put("end_stone", new Color(219, 222, 158));
        
        // 特殊方块 - 使用透明度
        BLOCK_COLORS.put("air", new Color(255, 255, 255, 0));
        BLOCK_COLORS.put("cave_air", new Color(255, 255, 255, 0));
        BLOCK_COLORS.put("void_air", new Color(255, 255, 255, 0));
        BLOCK_COLORS.put("none", new Color(255, 0, 255, 170)); // 粉色半透明，表示无效区块
    }
    
    /**
     * 根据方块ID获取对应的颜色
     */
    public static Color getBlockColor(String blockId) {
        // 检查缓存
        if (COLOR_CACHE.containsKey(blockId)) {
            return COLOR_CACHE.get(blockId);
        }
        
        Color color = calculateBlockColor(blockId);
        COLOR_CACHE.put(blockId, color);
        return color;
    }
    
    /**
     * 设置资源提取器
     */
    public static void setResourceExtractor(MinecraftResourceExtractor extractor) {
        resourceExtractor = extractor;
        // 清除缓存以使用新的颜色数据
        COLOR_CACHE.clear();
    }

    /**
     * 计算方块颜色
     */
    private static Color calculateBlockColor(String blockId) {
        // 如果blockId为null或空字符串，返回紫色标记错误
        if (blockId == null || blockId.isEmpty()) {
            return new Color(255, 0, 255, 128); // 半透明紫色
        }

        // 从完整方块ID中提取基本名称
        String processedId = blockId;
        if (blockId.contains(":")) {
            String[] parts = blockId.split(":", 2);
            // 如果是minecraft命名空间，只使用名称部分
            if ("minecraft".equals(parts[0])) {
                processedId = parts[1];
            }
        }

        // 首先尝试从提取的颜色中获取
        if (resourceExtractor != null && resourceExtractor.isExtractionCompleted()) {
            Color extractedColor = resourceExtractor.getExtractedColor(processedId);
            if (extractedColor != null) {
                return extractedColor;
            }
        }

        // 尝试匹配完整ID
        if (BLOCK_COLORS.containsKey(processedId)) {
            return BLOCK_COLORS.get(processedId);
        }

        // 尝试匹配部分ID（例如，如果blockId是"spruce_planks"，可以匹配"spruce"）
        for (String key : BLOCK_COLORS.keySet()) {
            if (processedId.contains(key)) {
                return BLOCK_COLORS.get(key);
            }
        }

        // 生成随机颜色但保持一致性
        // 使用方块ID的哈希值作为随机数种子
        int hash = blockId.hashCode();
        java.util.Random random = new java.util.Random(hash);

        int r = random.nextInt(200) + 55; // 避免太暗的颜色
        int g = random.nextInt(200) + 55;
        int b = random.nextInt(200) + 55;

        return new Color(r, g, b, 255);
    }
    
    /**
     * 清除颜色缓存
     */
    public static void clearCache() {
        COLOR_CACHE.clear();
    }
    
    /**
     * 获取缓存大小
     */
    public static int getCacheSize() {
        return COLOR_CACHE.size();
    }
    
    /**
     * 检查是否为空气方块
     */
    public static boolean isAirBlock(String blockId) {
        return "air".equals(blockId) || "cave_air".equals(blockId) || "void_air".equals(blockId);
    }
}
