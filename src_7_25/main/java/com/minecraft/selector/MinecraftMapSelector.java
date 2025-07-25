package com.minecraft.selector;

import com.minecraft.selector.core.MapRenderer;
import com.minecraft.selector.gui.MinecraftMapGUI;
import com.minecraft.selector.gui.UIThemeManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

/**
 * Minecraft地图选择器主程序
 * 对应Python的a.py主程序
 */
public class MinecraftMapSelector {
    
    public static void main(String[] args) {
        // 初始化UI主题系统
        UIThemeManager.getInstance().initializeTheme();

        System.out.println("Minecraft地图选择器 - Java版本");
        System.out.println("接收到的参数数量: " + args.length);
        for (int i = 0; i < args.length; i++) {
            System.out.println("参数[" + i + "]: " + args[i]);
        }

        // 检查是否是Blender集成模式
        final String worldPath;
        final String outputFile;
        final String jarPath;
        final int minY;
        final int maxY;

        String tempWorldPath = null;
        String tempOutputFile = null;
        String tempJarPath = null;
        int tempMinY = 0;    // 默认最低Y坐标
        int tempMaxY = 255;  // 默认最高Y坐标

        for (int i = 0; i < args.length - 1; i++) {
            if ("--world-path".equals(args[i])) {
                tempWorldPath = args[i + 1];
            } else if ("--output-file".equals(args[i])) {
                tempOutputFile = args[i + 1];
            } else if ("--jar-path".equals(args[i])) {
                tempJarPath = args[i + 1];
            } else if ("--min-y".equals(args[i])) {
                try {
                    tempMinY = Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid min-y value: " + args[i + 1]);
                }
            } else if ("--max-y".equals(args[i])) {
                try {
                    tempMaxY = Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid max-y value: " + args[i + 1]);
                }
            }
        }

        worldPath = tempWorldPath;
        outputFile = tempOutputFile;
        jarPath = tempJarPath;
        minY = tempMinY;
        maxY = tempMaxY;

        // 检查命令行参数
        if (args.length == 0) {
            // 没有参数，启动GUI
            System.out.println("启动图形界面...");
            javax.swing.SwingUtilities.invokeLater(() -> {
                try {
                    new MinecraftMapGUI().setVisible(true);
                } catch (Exception e) {
                    System.err.println("启动GUI失败: " + e.getMessage());
                    e.printStackTrace();
                    showUsage();
                }
            });
        } else if (worldPath != null && outputFile != null) {
            // Blender集成模式，启动GUI但自动加载世界
            System.out.println("启动Blender集成模式...");
            System.out.printf("世界路径: %s\n", worldPath);
            System.out.printf("输出文件: %s\n", outputFile);
            if (jarPath != null) {
                System.out.printf("JAR路径: %s\n", jarPath);
            }
            System.out.printf("Y坐标范围: %d 到 %d\n", minY, maxY);
            javax.swing.SwingUtilities.invokeLater(() -> {
                try {
                    MinecraftMapGUI gui = new MinecraftMapGUI(worldPath, outputFile, jarPath, minY, maxY);
                    gui.setVisible(true);
                } catch (Exception e) {
                    System.err.println("启动Blender集成模式失败: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        } else if (args.length >= 1 && !args[0].startsWith("--")) {
            // 有参数且不是选项，运行命令行模式
            runCommandLine(args);
        } else {
            showUsage();
        }
    }
    
    /**
     * 运行命令行模式
     */
    private static void runCommandLine(String[] args) {
        long overallStartTime = System.currentTimeMillis();

        String mcaFilePath = args[0];

        // 生成默认输出文件名（基于输入文件名和时间戳）
        String defaultBaseName = generateDefaultOutputName(mcaFilePath);
        String jsonOutput = args.length > 1 ? args[1] : defaultBaseName + ".json";
        String imageOutput = args.length > 2 ? args[2] : defaultBaseName + ".png";

        int maxWorkers = args.length > 3 ? Integer.parseInt(args[3]) : Math.min(Runtime.getRuntime().availableProcessors(), 8);
        int regionSize = args.length > 4 ? Integer.parseInt(args[4]) : 32;
        int sampleInterval = args.length > 5 ? Integer.parseInt(args[5]) : 1;
        
        System.out.println("正在处理区域文件: " + mcaFilePath);
        System.out.println("JSON输出路径: " + jsonOutput);
        System.out.println("图像输出路径: " + imageOutput);
        System.out.println("使用线程数: " + maxWorkers);
        System.out.println("处理区域大小: " + regionSize + "x" + regionSize + " 区块");
        System.out.println("采样间隔: " + sampleInterval);
        
        // 创建进度回调
        MapRenderer.ProgressCallback progressCallback = new MapRenderer.ProgressCallback() {
            @Override
            public void onProgress(int processed, int total, double speed, Set<String> foundBlocks) {
                if (total == 0) return;
                
                int percent = Math.min(100, processed * 100 / total);
                double remaining = (total - processed) / Math.max(0.1, speed);
                
                // 计算进度条长度 (50个字符)
                int barLength = 50;
                int filledLength = barLength * percent / 100;
                StringBuilder bar = new StringBuilder();
                for (int i = 0; i < filledLength; i++) {
                    bar.append("█");
                }
                for (int i = filledLength; i < barLength; i++) {
                    bar.append("░");
                }
                
                System.out.print("\r处理进度: [" + bar + "] " + percent + "% (" + processed + "/" + total + ") " +
                               "速度: " + String.format("%.1f", speed) + "区块/秒 " +
                               "剩余: " + String.format("%.1f", remaining) + "秒 " +
                               "发现方块: " + foundBlocks.size() + "种");
                System.out.flush();
            }
        };
        
        // 创建地图渲染器
        MapRenderer renderer = new MapRenderer(maxWorkers, progressCallback);
        
        try {
            // 读取区域文件
            MapRenderer.BlockInfo[][] topBlocks = renderer.getTopBlocks(mcaFilePath, regionSize, sampleInterval);

            if (topBlocks != null) {
                System.out.println("\n\n区域文件读取成功!");

                // 保存为JSON
                saveBlocksToJson(topBlocks, jsonOutput);

                // 渲染PNG
                BufferedImage image = renderer.renderToPng(topBlocks, sampleInterval);
                if (image != null) {
                    saveImageToPng(image, imageOutput);
                }
                
                // 输出总体统计信息
                long totalTime = System.currentTimeMillis() - overallStartTime;
                System.out.println("\n所有处理完成!");
                System.out.println("总耗时: " + (totalTime / 1000.0) + "秒");
            } else {
                System.err.println("区域文件读取失败");
            }
            
        } catch (Exception e) {
            System.err.println("处理过程中出错: " + e.getMessage());
            e.printStackTrace();
        } finally {
            renderer.shutdown();
        }
    }
    
    /**
     * 保存方块数据为JSON文件
     */
    private static void saveBlocksToJson(MapRenderer.BlockInfo[][] topBlocks, String outputFile) {
        System.out.println("正在将数据保存为JSON: " + outputFile);
        long startTime = System.currentTimeMillis();

        try {
            // 转换BlockInfo数组为可序列化的格式
            Map<String, Object> data = new HashMap<>();
            String[][] blockIds = new String[topBlocks.length][];
            int[][] heights = new int[topBlocks.length][];

            for (int i = 0; i < topBlocks.length; i++) {
                blockIds[i] = new String[topBlocks[i].length];
                heights[i] = new int[topBlocks[i].length];
                for (int j = 0; j < topBlocks[i].length; j++) {
                    MapRenderer.BlockInfo blockInfo = topBlocks[i][j];
                    if (blockInfo != null) {
                        blockIds[i][j] = blockInfo.getBlockId();
                        heights[i][j] = blockInfo.getHeight();
                    } else {
                        blockIds[i][j] = "air";
                        heights[i][j] = -64;
                    }
                }
            }

            data.put("blocks", blockIds);
            data.put("heights", heights);
            data.put("width", topBlocks[0].length);
            data.put("height", topBlocks.length);

            ObjectMapper mapper = new ObjectMapper();
            mapper.writeValue(new File(outputFile), data);

            long fileSize = new File(outputFile).length();
            long totalTime = System.currentTimeMillis() - startTime;
            System.out.println("JSON数据已保存，文件大小: " + (fileSize / 1024.0) + " KB，耗时: " + (totalTime / 1000.0) + "秒");

        } catch (IOException e) {
            System.err.println("保存JSON文件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 保存图像为PNG文件
     */
    private static void saveImageToPng(BufferedImage image, String outputFile) {
        System.out.println("正在保存图像: " + outputFile);
        long startTime = System.currentTimeMillis();
        
        try {
            // 保存原始尺寸图像
            ImageIO.write(image, "PNG", new File(outputFile));
            
            // 如果图像太大，创建缩略图版本
            int maxSize = 2048;
            if (image.getWidth() > maxSize || image.getHeight() > maxSize) {
                // 保持宽高比
                int newWidth, newHeight;
                if (image.getWidth() > image.getHeight()) {
                    newWidth = maxSize;
                    newHeight = maxSize * image.getHeight() / image.getWidth();
                } else {
                    newHeight = maxSize;
                    newWidth = maxSize * image.getWidth() / image.getHeight();
                }
                
                System.out.println("调整图像大小至 " + newWidth + "x" + newHeight);
                BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g2d = resized.createGraphics();
                g2d.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, 
                                   java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2d.drawImage(image, 0, 0, newWidth, newHeight, null);
                g2d.dispose();
                
                // 保存缩略图
                String thumbnailFile = outputFile.replaceAll("\\.(png|jpg|jpeg)$", "_thumbnail.$1");
                ImageIO.write(resized, "PNG", new File(thumbnailFile));
                System.out.println("缩略图已保存到: " + thumbnailFile);
            }
            
            long totalTime = System.currentTimeMillis() - startTime;
            System.out.println("图像已保存，耗时: " + (totalTime / 1000.0) + "秒");
            
        } catch (IOException e) {
            System.err.println("保存图像文件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 生成默认输出文件名
     */
    private static String generateDefaultOutputName(String mcaFilePath) {
        File mcaFile = new File(mcaFilePath);
        String baseName = mcaFile.getName().replaceAll("\\.mca$", "");
        long timestamp = System.currentTimeMillis();
        return String.format("%s_map_%d", baseName, timestamp);
    }

    /**
     * 显示使用说明
     */
    private static void showUsage() {
        System.out.println("用法:");
        System.out.println("  java -jar minecraft-map-selector.jar                                    # 启动GUI");
        System.out.println("  java -jar minecraft-map-selector.jar <mca文件路径> [选项...]              # 命令行模式");
        System.out.println();
        System.out.println("命令行选项:");
        System.out.println("  <mca文件路径>        必需，.mca区域文件路径");
        System.out.println("  [输出JSON文件路径]   可选，默认为<区域名>_map_<时间戳>.json");
        System.out.println("  [输出图像文件路径]   可选，默认为<区域名>_map_<时间戳>.png");
        System.out.println("  [线程数]            可选，默认为CPU核心数或8（取较小值）");
        System.out.println("  [区域大小]          可选，以区块为单位，默认32（即32x32区块）");
        System.out.println("  [采样间隔]          可选，每隔多少个方块采样一次，默认1（全采样）");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  java -jar minecraft-map-selector.jar /path/to/r.0.0.mca");
        System.out.println("  java -jar minecraft-map-selector.jar /path/to/r.0.0.mca blocks.json map.png 8 32 1");
        System.out.println();
        System.out.println("注意: 输出文件将保存到当前工作目录");
    }
}
