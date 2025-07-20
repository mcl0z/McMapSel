package com.minecraft.selector.gui;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 动态地图管理器 - 管理多个MCA区域的动态拼接
 */
public class DynamicMapManager {
    
    // 存储已加载的区域图像
    private final Map<RegionCoord, BufferedImage> regionImages = new ConcurrentHashMap<>();
    
    // 当前合成的大图
    private BufferedImage combinedImage;
    private int combinedMinRegionX = Integer.MAX_VALUE;
    private int combinedMinRegionZ = Integer.MAX_VALUE;
    private int combinedMaxRegionX = Integer.MIN_VALUE;
    private int combinedMaxRegionZ = Integer.MIN_VALUE;
    
    // 每个区域的像素大小
    private static final int REGION_SIZE_PIXELS = 512;
    
    /**
     * 添加区域图像
     */
    public synchronized void addRegion(int regionX, int regionZ, BufferedImage regionImage) {
        if (regionImage == null) return;
        
        RegionCoord coord = new RegionCoord(regionX, regionZ);
        regionImages.put(coord, regionImage);
        
        // 更新边界
        combinedMinRegionX = Math.min(combinedMinRegionX, regionX);
        combinedMinRegionZ = Math.min(combinedMinRegionZ, regionZ);
        combinedMaxRegionX = Math.max(combinedMaxRegionX, regionX);
        combinedMaxRegionZ = Math.max(combinedMaxRegionZ, regionZ);
        
        // 重新生成合成图像
        rebuildCombinedImage();
        
        System.out.printf("添加区域 r.%d.%d.mca 到动态地图\n", regionX, regionZ);
    }
    
    /**
     * 移除区域图像
     */
    public synchronized void removeRegion(int regionX, int regionZ) {
        RegionCoord coord = new RegionCoord(regionX, regionZ);
        BufferedImage removed = regionImages.remove(coord);
        
        if (removed != null) {
            // 重新计算边界
            recalculateBounds();
            
            // 重新生成合成图像
            rebuildCombinedImage();
            
            System.out.printf("从动态地图移除区域 r.%d.%d.mca\n", regionX, regionZ);
        }
    }
    
    /**
     * 获取当前合成的地图图像
     */
    public synchronized BufferedImage getCombinedImage() {
        return combinedImage;
    }
    
    /**
     * 获取合成地图的世界坐标起点
     */
    public synchronized Point getWorldOrigin() {
        if (regionImages.isEmpty()) {
            return new Point(0, 0);
        }
        return new Point(combinedMinRegionX * 512, combinedMinRegionZ * 512);
    }
    
    /**
     * 重新计算边界
     */
    private void recalculateBounds() {
        if (regionImages.isEmpty()) {
            combinedMinRegionX = Integer.MAX_VALUE;
            combinedMinRegionZ = Integer.MAX_VALUE;
            combinedMaxRegionX = Integer.MIN_VALUE;
            combinedMaxRegionZ = Integer.MIN_VALUE;
            return;
        }
        
        combinedMinRegionX = Integer.MAX_VALUE;
        combinedMinRegionZ = Integer.MAX_VALUE;
        combinedMaxRegionX = Integer.MIN_VALUE;
        combinedMaxRegionZ = Integer.MIN_VALUE;
        
        for (RegionCoord coord : regionImages.keySet()) {
            combinedMinRegionX = Math.min(combinedMinRegionX, coord.x);
            combinedMinRegionZ = Math.min(combinedMinRegionZ, coord.z);
            combinedMaxRegionX = Math.max(combinedMaxRegionX, coord.x);
            combinedMaxRegionZ = Math.max(combinedMaxRegionZ, coord.z);
        }
    }
    
    /**
     * 重新构建合成图像
     */
    private void rebuildCombinedImage() {
        if (regionImages.isEmpty()) {
            combinedImage = null;
            return;
        }

        // 计算合成图像的大小
        int width = (combinedMaxRegionX - combinedMinRegionX + 1) * REGION_SIZE_PIXELS;
        int height = (combinedMaxRegionZ - combinedMinRegionZ + 1) * REGION_SIZE_PIXELS;

        // 创建新的合成图像
        combinedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = combinedImage.createGraphics();

        // 设置高质量渲染
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // 填充背景色（深灰色，表示未加载区域）
        g2d.setColor(new Color(32, 32, 32));
        g2d.fillRect(0, 0, width, height);

        // 绘制每个区域，根据MCA文件的坐标系统正确定位
        for (Map.Entry<RegionCoord, BufferedImage> entry : regionImages.entrySet()) {
            RegionCoord coord = entry.getKey();
            BufferedImage regionImage = entry.getValue();

            // 计算在合成图像中的位置
            // MCA文件坐标系：r.x.z.mca 对应世界坐标 (x*512, z*512)
            int pixelX = (coord.x - combinedMinRegionX) * REGION_SIZE_PIXELS;
            int pixelY = (coord.z - combinedMinRegionZ) * REGION_SIZE_PIXELS;

            // 确保区域图像是512x512
            if (regionImage.getWidth() != REGION_SIZE_PIXELS || regionImage.getHeight() != REGION_SIZE_PIXELS) {
                // 如果尺寸不对，进行缩放
                g2d.drawImage(regionImage, pixelX, pixelY, REGION_SIZE_PIXELS, REGION_SIZE_PIXELS, null);
            } else {
                // 直接绘制
                g2d.drawImage(regionImage, pixelX, pixelY, null);
            }

            // 可选：绘制区域边界（调试用）
            if (false) { // 设置为true可以看到区域边界
                g2d.setColor(Color.RED);
                g2d.setStroke(new BasicStroke(1));
                g2d.drawRect(pixelX, pixelY, REGION_SIZE_PIXELS, REGION_SIZE_PIXELS);

                // 绘制区域标签
                g2d.setColor(Color.WHITE);
                g2d.drawString(String.format("r.%d.%d", coord.x, coord.z), pixelX + 5, pixelY + 15);
            }
        }

        g2d.dispose();

        System.out.printf("重建合成地图: %dx%d像素, 包含%d个区域\n",
            width, height, regionImages.size());
    }
    
    /**
     * 清除所有区域
     */
    public synchronized void clear() {
        regionImages.clear();
        combinedImage = null;
        combinedMinRegionX = Integer.MAX_VALUE;
        combinedMinRegionZ = Integer.MAX_VALUE;
        combinedMaxRegionX = Integer.MIN_VALUE;
        combinedMaxRegionZ = Integer.MIN_VALUE;
    }
    
    /**
     * 获取已加载的区域数量
     */
    public synchronized int getRegionCount() {
        return regionImages.size();
    }
    
    /**
     * 区域坐标类
     */
    private static class RegionCoord {
        final int x, z;
        
        RegionCoord(int x, int z) {
            this.x = x;
            this.z = z;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            RegionCoord that = (RegionCoord) obj;
            return x == that.x && z == that.z;
        }
        
        @Override
        public int hashCode() {
            return x * 31 + z;
        }
        
        @Override
        public String toString() {
            return String.format("Region(%d, %d)", x, z);
        }
    }
}
