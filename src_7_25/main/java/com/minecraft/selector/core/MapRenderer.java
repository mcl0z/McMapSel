package com.minecraft.selector.core;

import com.minecraft.selector.region.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 地图渲染器
 * 对应Python代码中的地图渲染功能
 */
public class MapRenderer {

    /**
     * 存储方块信息的内部类，包含方块ID和高度
     */
    public static class BlockInfo {
        private final String blockId;
        private final int height;

        public BlockInfo(String blockId, int height) {
            this.blockId = blockId;
            this.height = height;
        }

        public String getBlockId() {
            return blockId;
        }

        public int getHeight() {
            return height;
        }

        @Override
        public String toString() {
            return blockId + "@" + height;
        }
    }
    
    private final ExecutorService executorService;
    private final int maxWorkers;
    private final ProgressCallback progressCallback;
    
    // 进度跟踪
    private final AtomicInteger processedChunks = new AtomicInteger(0);
    private final AtomicInteger totalChunks = new AtomicInteger(0);
    private final AtomicLong startTime = new AtomicLong(0);
    private final Set<String> foundBlocks = ConcurrentHashMap.newKeySet();
    
    /**
     * 进度回调接口
     */
    public interface ProgressCallback {
        void onProgress(int processed, int total, double speed, Set<String> foundBlocks);
    }
    
    public MapRenderer(int maxWorkers, ProgressCallback progressCallback) {
        this.maxWorkers = maxWorkers;
        this.progressCallback = progressCallback;
        this.executorService = Executors.newFixedThreadPool(maxWorkers);
    }

    /**
     * 静态方法：渲染指定区域
     */
    public static BufferedImage renderRegion(String regionPath, int minX, int maxX, int minZ, int maxZ, int lodLevel) {
        try {
            // 创建简单的进度回调
            ProgressCallback callback = (processed, total, speed, foundBlocks) -> {
                System.out.printf("渲染进度: %d/%d (%.1f%%) - 速度: %.1f区块/秒\n",
                    processed, total, (processed * 100.0 / total), speed);
            };

            // 创建渲染器
            MapRenderer renderer = new MapRenderer(4, callback);

            // 计算区域大小
            int width = maxX - minX;
            int height = maxZ - minZ;

            // 渲染区域 - 这里需要处理多个MCA文件
            // 简化版本：假设只有一个MCA文件
            File regionDir = new File(regionPath);
            File[] mcaFiles = regionDir.listFiles((dir, name) -> name.endsWith(".mca"));

            if (mcaFiles != null && mcaFiles.length > 0) {
                // 使用第一个MCA文件进行渲染
                String mcaPath = mcaFiles[0].getAbsolutePath();
                BlockInfo[][] topBlocks = renderer.getTopBlocks(mcaPath, Math.max(width/16, height/16), lodLevel);
                if (topBlocks != null) {
                    return renderer.renderToPng(topBlocks, lodLevel);
                }
            }

        } catch (Exception e) {
            System.err.println("渲染区域失败: " + e.getMessage());
            e.printStackTrace();
        }

        return null;
    }
    
    /**
     * 渲染区域文件为顶部方块数据
     */
    public BlockInfo[][] getTopBlocks(String mcaFilePath, int regionSize, int sampleInterval) throws IOException {
        System.out.println("正在处理区域文件: " + mcaFilePath);
        System.out.println("区域大小: " + regionSize + "x" + regionSize + " 区块");
        System.out.println("采样间隔: " + sampleInterval);
        
        // 重置进度
        processedChunks.set(0);
        foundBlocks.clear();
        startTime.set(System.currentTimeMillis());
        
        // 加载区域文件
        Region region = Region.fromFile(mcaFilePath);
        
        // 获取存在的区块坐标
        List<int[]> populatedChunks = new ArrayList<>();
        for (int x = 0; x < regionSize; x++) {
            for (int z = 0; z < regionSize; z++) {
                if (region.chunkExists(x, z)) {
                    populatedChunks.add(new int[]{x, z});
                }
            }
        }
        
        totalChunks.set(populatedChunks.size());
        System.out.println("发现 " + populatedChunks.size() + " 个有效区块");
        
        if (populatedChunks.isEmpty()) {
            System.out.println("没有找到有效区块");
            return new BlockInfo[regionSize * 16][regionSize * 16];
        }

        // 创建结果数组
        int arraySize = regionSize * 16;
        BlockInfo[][] topBlocks = new BlockInfo[arraySize][arraySize];

        // 初始化为"none"
        for (int i = 0; i < arraySize; i++) {
            for (int j = 0; j < arraySize; j++) {
                topBlocks[i][j] = new BlockInfo("none", -64);
            }
        }
        
        // 分批处理区块
        int batchSize = Math.max(1, populatedChunks.size() / maxWorkers);
        List<List<int[]>> batches = new ArrayList<>();
        
        for (int i = 0; i < populatedChunks.size(); i += batchSize) {
            int end = Math.min(i + batchSize, populatedChunks.size());
            batches.add(populatedChunks.subList(i, end));
        }
        
        System.out.println("使用 " + batches.size() + " 个批次处理");
        
        // 并行处理批次
        List<Future<Map<String, BlockInfo[][]>>> futures = new ArrayList<>();

        for (int i = 0; i < batches.size(); i++) {
            final int batchIndex = i;
            final List<int[]> batch = batches.get(i);

            Future<Map<String, BlockInfo[][]>> future = executorService.submit(() ->
                processChunkBatch(region, batch, batchIndex, sampleInterval)
            );
            futures.add(future);
        }

        // 收集结果
        for (Future<Map<String, BlockInfo[][]>> future : futures) {
            try {
                Map<String, BlockInfo[][]> batchResults = future.get();

                // 将批次结果合并到主数组
                for (Map.Entry<String, BlockInfo[][]> entry : batchResults.entrySet()) {
                    String[] coords = entry.getKey().split(",");
                    int chunkX = Integer.parseInt(coords[0]);
                    int chunkZ = Integer.parseInt(coords[1]);
                    BlockInfo[][] chunkBlocks = entry.getValue();

                    if (chunkBlocks != null) {
                        // 将区块数据复制到结果数组
                        int startX = chunkX * 16;
                        int startZ = chunkZ * 16;

                        for (int localZ = 0; localZ < 16; localZ++) {
                            for (int localX = 0; localX < 16; localX++) {
                                int globalX = startX + localX;
                                int globalZ = startZ + localZ;

                                if (globalX < arraySize && globalZ < arraySize) {
                                    topBlocks[globalZ][globalX] = chunkBlocks[localZ][localX];
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("处理批次结果时出错: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        // 输出统计信息
        long totalTime = System.currentTimeMillis() - startTime.get();
        System.out.println("\n处理完成!");
        System.out.println("总耗时: " + (totalTime / 1000.0) + "秒");
        System.out.println("处理区块: " + populatedChunks.size());
        System.out.println("平均速度: " + (populatedChunks.size() / (totalTime / 1000.0)) + " 区块/秒");
        System.out.println("发现的方块类型数量: " + foundBlocks.size());
        
        return topBlocks;
    }
    
    /**
     * 处理一批区块
     */
    private Map<String, BlockInfo[][]> processChunkBatch(Region region, List<int[]> chunkCoords,
                                                     int threadId, int sampleInterval) {
        Map<String, BlockInfo[][]> results = new HashMap<>();
        Set<String> localFoundBlocks = new HashSet<>();

        for (int[] coord : chunkCoords) {
            int chunkX = coord[0];
            int chunkZ = coord[1];

            try {
                Chunk chunk = region.getChunk(chunkX, chunkZ);
                if (chunk != null) {
                    BlockInfo[][] chunkBlocks = processChunk(chunk, localFoundBlocks, sampleInterval);
                    results.put(chunkX + "," + chunkZ, chunkBlocks);
                } else {
                    results.put(chunkX + "," + chunkZ, null);
                }

                // 更新进度
                int processed = processedChunks.incrementAndGet();
                if (processed % 8 == 0) { // 每处理8个区块更新一次进度
                    updateProgress();
                }

            } catch (Exception e) {
                System.err.println("处理区块 (" + chunkX + ", " + chunkZ + ") 时出错: " + e.getMessage());
                results.put(chunkX + "," + chunkZ, null);
            }
        }

        // 将本地发现的方块添加到全局集合
        foundBlocks.addAll(localFoundBlocks);

        return results;
    }
    
    /**
     * 处理单个区块，提取顶部方块
     */
    private BlockInfo[][] processChunk(Chunk chunk, Set<String> localFoundBlocks, int sampleInterval) {
        BlockInfo[][] chunkBlocks = new BlockInfo[16][16];

        // 初始化为空气
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                chunkBlocks[i][j] = new BlockInfo("air", -64);
            }
        }


        
        // 根据采样间隔创建要处理的坐标列表
        List<int[]> sampleCoords = new ArrayList<>();
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                if (sampleInterval == 1 || (localX % sampleInterval == 0 && localZ % sampleInterval == 0)) {
                    sampleCoords.add(new int[]{localX, localZ});
                }
            }
        }
        
        boolean[][] foundBlocks = new boolean[16][16];
        
        // 检查区块是否有区段信息
        boolean hasSections = chunk.hasSections();
        
        if (hasSections) {
            // 对每个采样点从世界顶部向下查找第一个非空气方块
            for (int[] coord : sampleCoords) {
                int localX = coord[0];
                int localZ = coord[1];

                String resultBlockId = "air";
                int topY = -1;

                // 从世界最高点向下扫描，找到第一个非空气方块
                for (int y = 319; y >= -64; y--) {
                    try {
                        Block block = chunk.getBlock(localX, y, localZ);
                        if (block != null && !BlockColors.isAirBlock(block.getId())) {
                            resultBlockId = block.getId();
                            topY = y;


                            break; // 找到第一个非空气方块就停止
                        }
                    } catch (Exception e) {
                        // 如果出错，继续向下查找
                        continue;
                    }
                }
                
                // 处理找到的顶层方块
                if (!"air".equals(resultBlockId) && topY >= -64) {
                    String blockId = resultBlockId;
                    // 移除minecraft:前缀
                    if (blockId.startsWith("minecraft:")) {
                        blockId = blockId.substring("minecraft:".length());
                    }
                    chunkBlocks[localZ][localX] = new BlockInfo(blockId, topY);
                    localFoundBlocks.add(blockId);
                    foundBlocks[localZ][localX] = true;
                } else {
                    // 如果没有找到非空气方块，标记为空气
                    chunkBlocks[localZ][localX] = new BlockInfo("air", -64);
                }
            }
            
            // 如果使用了采样间隔 > 1，填充未采样的方块
            if (sampleInterval > 1) {
                fillUnsampledBlocks(chunkBlocks, foundBlocks, sampleInterval);
            }
        }
        
        return chunkBlocks;
    }
    
    /**
     * 填充未采样的方块
     */
    private void fillUnsampledBlocks(BlockInfo[][] chunkBlocks, boolean[][] foundBlocks, int sampleInterval) {
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                if (!foundBlocks[localZ][localX]) {
                    // 找到最近的采样点
                    int sampleX = (localX / sampleInterval) * sampleInterval;
                    int sampleZ = (localZ / sampleInterval) * sampleInterval;

                    // 确保采样点在范围内
                    if (sampleX >= 16) {
                        sampleX = 16 - sampleInterval;
                    }
                    if (sampleZ >= 16) {
                        sampleZ = 16 - sampleInterval;
                    }

                    // 如果该采样点有方块数据，使用它
                    if (foundBlocks[sampleZ][sampleX]) {
                        chunkBlocks[localZ][localX] = chunkBlocks[sampleZ][sampleX];
                    } else {
                        // 如果没有找到最近的采样点，使用默认值
                        chunkBlocks[localZ][localX] = new BlockInfo("air", -64);
                    }
                }
            }
        }
    }
    
    /**
     * 更新进度
     */
    private void updateProgress() {
        if (progressCallback != null) {
            int processed = processedChunks.get();
            int total = totalChunks.get();
            long elapsed = System.currentTimeMillis() - startTime.get();
            double speed = processed / Math.max(0.1, elapsed / 1000.0);
            
            progressCallback.onProgress(processed, total, speed, new HashSet<>(foundBlocks));
        }
    }
    
    /**
     * 渲染顶部方块数据为PNG图像
     */
    public BufferedImage renderToPng(BlockInfo[][] topBlocks, int sampleInterval) {
        if (topBlocks == null) {
            System.err.println("无法渲染：顶部方块数据为空");
            return null;
        }

        System.out.println("正在渲染PNG图像...");
        long startTime = System.currentTimeMillis();

        int height = topBlocks.length;
        int width = topBlocks[0].length;

        if (height <= 0 || width <= 0) {
            System.err.println("无效的数组大小: " + width + "x" + height);
            return null;
        }

        if (sampleInterval > 1) {
            System.out.println("注意：使用了优化采样（每" + sampleInterval + "个方块采样1次），图像质量可能略有降低");
        }

        System.out.println("图像大小: " + width + "x" + height + "像素");

        // 创建RGBA图像
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        // 预处理所有方块ID及其颜色
        System.out.println("正在预处理方块颜色...");
        Set<String> uniqueBlocks = new HashSet<>();
        for (BlockInfo[] row : topBlocks) {
            for (BlockInfo blockInfo : row) {
                if (blockInfo != null && blockInfo.getBlockId() != null) {
                    uniqueBlocks.add(blockInfo.getBlockId());
                }
            }
        }

        Map<String, Color> blockColorMap = new HashMap<>();
        for (String blockId : uniqueBlocks) {
            if (blockId != null) {
                blockColorMap.put(blockId, BlockColors.getBlockColor(blockId));
            }
        }

        // 填充像素数组并添加高度差边缘效果
        System.out.println("正在填充像素数组并处理高度差效果...");
        int errorCount = 0;
        int updateInterval = Math.max(1, height / 10);

        // 高度差阈值，超过这个值就绘制黑色线条
        final int HEIGHT_DIFF_THRESHOLD = 3;
        final int MAX_HEIGHT_DIFF = 50; // 用于计算黑色强度的最大高度差

        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                try {
                    BlockInfo currentBlock = topBlocks[i][j];
                    String blockId = currentBlock != null ? currentBlock.getBlockId() : null;
                    int currentHeight = currentBlock != null ? currentBlock.getHeight() : -64;

                    Color baseColor;
                    if (blockId == null || !blockColorMap.containsKey(blockId)) {
                        // 如果方块ID无效，使用粉色表示错误
                        baseColor = new Color(255, 0, 255, 255);
                        errorCount++;
                    } else {
                        baseColor = blockColorMap.get(blockId);
                    }

                    // 检查相邻像素的高度差
                    boolean shouldDrawEdge = false;
                    int maxHeightDiff = 0;

                    // 检查四个方向的相邻像素
                    int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
                    for (int[] dir : directions) {
                        int ni = i + dir[0];
                        int nj = j + dir[1];

                        if (ni >= 0 && ni < height && nj >= 0 && nj < width) {
                            BlockInfo neighborBlock = topBlocks[ni][nj];
                            int neighborHeight = neighborBlock != null ? neighborBlock.getHeight() : -64;

                            int heightDiff = Math.abs(currentHeight - neighborHeight);
                            if (heightDiff > HEIGHT_DIFF_THRESHOLD) {
                                shouldDrawEdge = true;
                                maxHeightDiff = Math.max(maxHeightDiff, heightDiff);
                            }
                        }
                    }

                    Color finalColor = baseColor;
                    if (shouldDrawEdge) {
                        // 根据高度差计算黑色强度
                        float intensity = Math.min(1.0f, (float) maxHeightDiff / MAX_HEIGHT_DIFF);
                        // 将基础颜色与黑色混合
                        int r = (int) (baseColor.getRed() * (1 - intensity * 0.7f));
                        int g = (int) (baseColor.getGreen() * (1 - intensity * 0.7f));
                        int b = (int) (baseColor.getBlue() * (1 - intensity * 0.7f));
                        finalColor = new Color(r, g, b, baseColor.getAlpha());
                    }

                    // 设置像素颜色
                    image.setRGB(j, i, finalColor.getRGB());

                } catch (Exception e) {
                    // 记录错误并使用紫色表示处理失败的像素
                    image.setRGB(j, i, new Color(128, 0, 128, 255).getRGB());
                    errorCount++;
                }
            }

            // 更新进度
            if (i % updateInterval == 0) {
                int percent = i * 100 / height;
                System.out.print("\r图像渲染: " + percent + "% 完成");
                System.out.flush();
            }
        }

        if (errorCount > 0) {
            System.out.println("\n注意：处理过程中有 " + errorCount + " 个像素出现错误 (" +
                             String.format("%.2f", errorCount * 100.0 / (width * height)) + "%)");
        }

        long totalTime = System.currentTimeMillis() - startTime;
        System.out.println("\n渲染完成，耗时: " + (totalTime / 1000.0) + "秒");

        return image;
    }

    /**
     * 关闭线程池
     */
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
