package com.minecraft.selector.utils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文件操作工具类
 */
public class FileUtils {
    
    /**
     * 检查文件是否存在
     */
    public static boolean fileExists(String filePath) {
        return Files.exists(Paths.get(filePath));
    }
    
    /**
     * 创建目录（如果不存在）
     */
    public static boolean createDirectories(String dirPath) {
        try {
            Files.createDirectories(Paths.get(dirPath));
            return true;
        } catch (IOException e) {
            System.err.println("创建目录失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 获取文件大小（字节）
     */
    public static long getFileSize(String filePath) {
        try {
            return Files.size(Paths.get(filePath));
        } catch (IOException e) {
            return -1;
        }
    }
    
    /**
     * 获取文件扩展名
     */
    public static String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex > 0 && lastDotIndex < fileName.length() - 1) {
            return fileName.substring(lastDotIndex + 1).toLowerCase();
        }
        return "";
    }
    
    /**
     * 获取不带扩展名的文件名
     */
    public static String getFileNameWithoutExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex > 0) {
            return fileName.substring(0, lastDotIndex);
        }
        return fileName;
    }
    
    /**
     * 读取文件的所有字节
     */
    public static byte[] readAllBytes(String filePath) throws IOException {
        return Files.readAllBytes(Paths.get(filePath));
    }
    
    /**
     * 写入字节到文件
     */
    public static void writeBytes(String filePath, byte[] data) throws IOException {
        Files.write(Paths.get(filePath), data);
    }
    
    /**
     * 保存BufferedImage为PNG文件
     */
    public static void saveImageAsPng(BufferedImage image, String filePath) throws IOException {
        File outputFile = new File(filePath);
        
        // 确保父目录存在
        File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        
        ImageIO.write(image, "PNG", outputFile);
    }
    
    /**
     * 保存BufferedImage为JPEG文件
     */
    public static void saveImageAsJpeg(BufferedImage image, String filePath) throws IOException {
        File outputFile = new File(filePath);
        
        // 确保父目录存在
        File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        
        // 如果原图像有透明度，需要转换为RGB
        BufferedImage rgbImage = image;
        if (image.getType() == BufferedImage.TYPE_INT_ARGB) {
            rgbImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            rgbImage.getGraphics().drawImage(image, 0, 0, null);
        }
        
        ImageIO.write(rgbImage, "JPEG", outputFile);
    }
    
    /**
     * 获取目录下所有指定扩展名的文件
     */
    public static List<String> getFilesWithExtension(String dirPath, String extension) throws IOException {
        Path dir = Paths.get(dirPath);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            throw new IOException("目录不存在或不是有效目录: " + dirPath);
        }
        
        return Files.list(dir)
                .filter(Files::isRegularFile)
                .map(Path::getFileName)
                .map(Path::toString)
                .filter(name -> name.toLowerCase().endsWith("." + extension.toLowerCase()))
                .collect(Collectors.toList());
    }
    
    /**
     * 获取目录下所有MCA文件
     */
    public static List<String> getMcaFiles(String regionDirPath) throws IOException {
        return getFilesWithExtension(regionDirPath, "mca");
    }
    
    /**
     * 解析MCA文件名获取区域坐标
     * 例如: "r.0.0.mca" -> [0, 0]
     */
    public static int[] parseRegionCoordinates(String mcaFileName) {
        if (!mcaFileName.startsWith("r.") || !mcaFileName.endsWith(".mca")) {
            throw new IllegalArgumentException("无效的MCA文件名: " + mcaFileName);
        }
        
        String coordPart = mcaFileName.substring(2, mcaFileName.length() - 4); // 移除"r."和".mca"
        String[] parts = coordPart.split("\\.");
        
        if (parts.length != 2) {
            throw new IllegalArgumentException("无效的MCA文件名格式: " + mcaFileName);
        }
        
        try {
            int x = Integer.parseInt(parts[0]);
            int z = Integer.parseInt(parts[1]);
            return new int[]{x, z};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无法解析MCA文件坐标: " + mcaFileName, e);
        }
    }
    
    /**
     * 生成MCA文件名
     */
    public static String generateMcaFileName(int regionX, int regionZ) {
        return String.format("r.%d.%d.mca", regionX, regionZ);
    }
    
    /**
     * 获取临时文件路径
     */
    public static String getTempFilePath(String fileName) {
        String tempDir = System.getProperty("java.io.tmpdir");
        return Paths.get(tempDir, "minecraft-map-selector", fileName).toString();
    }
    
    /**
     * 清理临时文件
     */
    public static void cleanupTempFiles() {
        String tempDir = getTempFilePath("");
        try {
            Path tempPath = Paths.get(tempDir);
            if (Files.exists(tempPath)) {
                Files.walk(tempPath)
                        .filter(Files::isRegularFile)
                        .forEach(file -> {
                            try {
                                Files.delete(file);
                            } catch (IOException e) {
                                System.err.println("删除临时文件失败: " + file + " - " + e.getMessage());
                            }
                        });
            }
        } catch (IOException e) {
            System.err.println("清理临时文件时出错: " + e.getMessage());
        }
    }
    
    /**
     * 格式化文件大小
     */
    public static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }
    
    /**
     * 检查是否为有效的Minecraft存档目录
     */
    public static boolean isValidMinecraftSave(String savePath) {
        Path saveDir = Paths.get(savePath);
        
        // 检查必要的文件和目录
        return Files.exists(saveDir.resolve("level.dat")) &&
               Files.exists(saveDir.resolve("region")) &&
               Files.isDirectory(saveDir.resolve("region"));
    }
    
    /**
     * 获取默认的Minecraft存档路径
     */
    public static String getDefaultMinecraftSavesPath() {
        String os = System.getProperty("os.name").toLowerCase();
        String userHome = System.getProperty("user.home");
        
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                return Paths.get(appData, ".minecraft", "saves").toString();
            }
        } else if (os.contains("mac")) {
            return Paths.get(userHome, "Library", "Application Support", "minecraft", "saves").toString();
        } else {
            return Paths.get(userHome, ".minecraft", "saves").toString();
        }
        
        return null;
    }
}
