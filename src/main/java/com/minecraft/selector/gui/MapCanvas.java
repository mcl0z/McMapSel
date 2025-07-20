package com.minecraft.selector.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import javax.swing.Timer;

/**
 * 地图画布组件
 * 用于显示和交互Minecraft地图
 */
public class MapCanvas extends JPanel {
    
    private BufferedImage image;
    private double scale = 1.0;
    private Point imageOffset = new Point(0, 0);
    private Rectangle selectionRect;
    private Point selectionStart;
    private Point selectionEnd;
    private boolean dragging = false;
    private boolean selecting = false;
    private Point lastMousePos;

    // 选择回调接口
    public interface SelectionCallback {
        void onSelectionComplete(int minX, int minZ, int maxX, int maxZ);
        void onSelectionConfirmed(int minX, int minZ, int maxX, int maxZ);
    }

    // 当前选择的坐标
    private int currentMinX, currentMinZ, currentMaxX, currentMaxZ;
    private boolean hasValidSelection = false;

    // 选择的世界坐标（用于绘制时转换回屏幕坐标）
    private Point selectionWorldStart, selectionWorldEnd;

    // 世界坐标映射信息
    private int worldMinX = 0, worldMinZ = 0;  // 地图对应的世界坐标起点
    private double pixelsPerBlock = 1.0;       // 每个方块对应的像素数

    // 视野管理
    private ViewportManager viewportManager;
    private Timer viewportUpdateTimer;
    private Rectangle lastViewport = new Rectangle();

    // 视野管理回调接口
    public interface ViewportCallback {
        void onViewportChanged(int minWorldX, int minWorldZ, int maxWorldX, int maxWorldZ);
        void loadRegion(int regionX, int regionZ);
        void unloadRegion(int regionX, int regionZ);
    }

    private ViewportCallback viewportCallback;

    private SelectionCallback selectionCallback;
    
    public MapCanvas() {
        setPreferredSize(new Dimension(800, 600));
        setBackground(Color.WHITE);
        
        setupMouseListeners();
    }
    
    private void setupMouseListeners() {
        MouseAdapter mouseHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    // 右键开始选择区域
                    startSelection(e.getPoint());
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    // 左键开始拖拽地图
                    startDrag(e.getPoint());
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e) && selecting) {
                    // 右键拖拽更新选择区域
                    updateSelection(e.getPoint());
                } else if (SwingUtilities.isLeftMouseButton(e) && dragging) {
                    // 左键拖拽移动地图
                    updateDrag(e.getPoint());
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e) && selecting) {
                    // 完成右键选择
                    finishSelection(e.getPoint());
                } else if (SwingUtilities.isLeftMouseButton(e) && dragging) {
                    // 完成左键拖拽
                    finishDrag();
                }
            }
            
            @Override
            public void mouseMoved(MouseEvent e) {
                // 更新鼠标坐标显示
                updateMouseCoordinates(e.getPoint());
            }
            
            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                // 鼠标滚轮缩放
                handleMouseWheel(e);
            }
        };
        
        addMouseListener(mouseHandler);
        addMouseMotionListener(mouseHandler);
        addMouseWheelListener(mouseHandler);

        // 添加键盘监听器
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && hasValidSelection) {
                    // 按下Enter键确认选择
                    if (selectionCallback != null) {
                        selectionCallback.onSelectionConfirmed(currentMinX, currentMinZ, currentMaxX, currentMaxZ);
                    }
                } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    // 按下Escape键清除选择
                    clearSelection();
                    hasValidSelection = false;
                }
            }
        });

        // 设置焦点以接收键盘事件
        setFocusable(true);

        // 鼠标点击时获得焦点
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                requestFocusInWindow();
            }
        });

        // 初始化视野更新定时器（每500ms检查一次视野变化）
        viewportUpdateTimer = new Timer(500, e -> updateViewportIfNeeded());
        viewportUpdateTimer.start();
    }
    
    /**
     * 设置要显示的图像
     */
    public void setImage(BufferedImage image) {
        this.image = image;
        if (image != null) {
            // 重置视图
            resetView();
        }
        repaint();
    }
    
    /**
     * 设置缩放比例
     */
    public void setScale(double scale) {
        this.scale = Math.max(0.1, Math.min(10.0, scale));
        repaint();
    }
    
    /**
     * 获取当前缩放比例
     */
    public double getScale() {
        return scale;
    }
    
    /**
     * 重置视图
     */
    public void resetView() {
        scale = 1.0;
        imageOffset.setLocation(0, 0);
        selectionRect = null;
        repaint();
    }
    

    
    /**
     * 拖拽地图
     */
    private void dragMap(Point point) {
        if (dragging && lastMousePos != null) {
            int dx = point.x - lastMousePos.x;
            int dy = point.y - lastMousePos.y;
            
            imageOffset.translate(dx, dy);
            lastMousePos = new Point(point);
            repaint();
        }
    }
    

    
    /**
     * 更新鼠标坐标显示
     */
    private void updateMouseCoordinates(Point point) {
        if (image != null) {
            // 将屏幕坐标转换为图像坐标
            Point imagePoint = screenToImageCoordinates(point);
            
            // 这里可以触发坐标更新事件
            // 暂时打印到控制台
            if (imagePoint.x >= 0 && imagePoint.x < image.getWidth() && 
                imagePoint.y >= 0 && imagePoint.y < image.getHeight()) {
                // System.out.println("图像坐标: " + imagePoint);
            }
        }
    }
    
    /**
     * 处理鼠标滚轮事件
     */
    private void handleMouseWheel(MouseWheelEvent e) {
        if (image != null) {
            double oldScale = scale;
            
            if (e.getWheelRotation() < 0) {
                // 向上滚动 - 放大
                scale = Math.min(10.0, scale * 1.2);
            } else {
                // 向下滚动 - 缩小
                scale = Math.max(0.1, scale / 1.2);
            }
            
            if (scale != oldScale) {
                // 以鼠标位置为中心缩放
                Point mousePos = e.getPoint();
                double scaleRatio = scale / oldScale;
                
                imageOffset.x = (int) (mousePos.x - (mousePos.x - imageOffset.x) * scaleRatio);
                imageOffset.y = (int) (mousePos.y - (mousePos.y - imageOffset.y) * scaleRatio);
                
                repaint();
            }
        }
    }
    
    /**
     * 将屏幕坐标转换为图像坐标
     */
    private Point screenToImageCoordinates(Point screenPoint) {
        if (image == null) {
            return new Point(0, 0);
        }
        
        int imageX = (int) ((screenPoint.x - imageOffset.x) / scale);
        int imageY = (int) ((screenPoint.y - imageOffset.y) / scale);
        
        return new Point(imageX, imageY);
    }
    
    /**
     * 将图像坐标转换为屏幕坐标
     */
    private Point imageToScreenCoordinates(Point imagePoint) {
        if (image == null) {
            return new Point(0, 0);
        }
        
        int screenX = (int) (imagePoint.x * scale + imageOffset.x);
        int screenY = (int) (imagePoint.y * scale + imageOffset.y);
        
        return new Point(screenX, screenY);
    }
    
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        
        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        
        if (image != null) {
            // 绘制图像
            int scaledWidth = (int) (image.getWidth() * scale);
            int scaledHeight = (int) (image.getHeight() * scale);
            
            g2d.drawImage(image, imageOffset.x, imageOffset.y, scaledWidth, scaledHeight, null);
            
            // 绘制选择框 - 根据当前缩放和偏移调整
            if (selecting && selectionStart != null && selectionEnd != null) {
                // 正在选择时，直接使用屏幕坐标
                int x = Math.min(selectionStart.x, selectionEnd.x);
                int y = Math.min(selectionStart.y, selectionEnd.y);
                int width = Math.abs(selectionEnd.x - selectionStart.x);
                int height = Math.abs(selectionEnd.y - selectionStart.y);

                g2d.setColor(Color.RED);
                g2d.setStroke(new BasicStroke(2));
                g2d.drawRect(x, y, width, height);
            } else if (selectionWorldStart != null && selectionWorldEnd != null) {
                // 选择完成后，使用世界坐标转换为当前屏幕坐标
                Point scaledStart = worldToScreenCoordinates(selectionWorldStart);
                Point scaledEnd = worldToScreenCoordinates(selectionWorldEnd);

                if (scaledStart != null && scaledEnd != null) {
                    int x = Math.min(scaledStart.x, scaledEnd.x);
                    int y = Math.min(scaledStart.y, scaledEnd.y);
                    int width = Math.abs(scaledEnd.x - scaledStart.x);
                    int height = Math.abs(scaledEnd.y - scaledStart.y);

                    g2d.setColor(Color.RED);
                    g2d.setStroke(new BasicStroke(2));
                    g2d.drawRect(x, y, width, height);
                }
            }
        } else {
            // 没有图像时显示提示
            g2d.setColor(Color.GRAY);
            String message = "没有加载地图图像";
            FontMetrics fm = g2d.getFontMetrics();
            int x = (getWidth() - fm.stringWidth(message)) / 2;
            int y = getHeight() / 2;
            g2d.drawString(message, x, y);
        }
        
        g2d.dispose();
    }
    
    /**
     * 设置选择回调
     */
    public void setSelectionCallback(SelectionCallback callback) {
        this.selectionCallback = callback;
    }

    /**
     * 设置世界坐标映射信息
     * @param worldMinX 地图左上角对应的世界X坐标
     * @param worldMinZ 地图左上角对应的世界Z坐标
     * @param pixelsPerBlock 每个方块对应的像素数
     */
    public void setWorldCoordinateMapping(int worldMinX, int worldMinZ, double pixelsPerBlock) {
        this.worldMinX = worldMinX;
        this.worldMinZ = worldMinZ;
        this.pixelsPerBlock = pixelsPerBlock;
        System.out.printf("设置世界坐标映射: 起点(%d, %d), 像素/方块=%.2f\n",
            worldMinX, worldMinZ, pixelsPerBlock);
    }

    /**
     * 设置视野回调
     */
    public void setViewportCallback(ViewportCallback callback) {
        this.viewportCallback = callback;
        // 初始化视野管理器（缓冲区大小为1，即周围1个区域的范围）
        this.viewportManager = new ViewportManager(callback, 1);
    }

    /**
     * 启用自动视野管理
     */
    public void enableAutoViewportManagement(boolean enable) {
        if (enable) {
            if (!viewportUpdateTimer.isRunning()) {
                viewportUpdateTimer.start();
            }
        } else {
            viewportUpdateTimer.stop();
        }
    }

    /**
     * 检查视野是否需要更新
     */
    private void updateViewportIfNeeded() {
        if (viewportManager == null || image == null) return;

        // 计算当前可见的世界坐标范围
        Rectangle currentViewport = getCurrentViewportInWorldCoords();

        // 如果视野发生了显著变化，更新视野管理
        if (!currentViewport.equals(lastViewport)) {
            lastViewport = new Rectangle(currentViewport);

            viewportManager.updateViewport(
                currentViewport.x,
                currentViewport.y,
                currentViewport.x + currentViewport.width,
                currentViewport.y + currentViewport.height
            );
        }
    }

    /**
     * 获取当前可见区域的世界坐标范围
     */
    private Rectangle getCurrentViewportInWorldCoords() {
        if (image == null) return new Rectangle();

        // 获取屏幕可见区域
        Rectangle visibleRect = getVisibleRect();

        // 转换为世界坐标
        Point topLeft = screenToWorldCoordinates(new Point(visibleRect.x, visibleRect.y));
        Point bottomRight = screenToWorldCoordinates(new Point(
            visibleRect.x + visibleRect.width,
            visibleRect.y + visibleRect.height
        ));

        if (topLeft != null && bottomRight != null) {
            return new Rectangle(
                topLeft.x,
                topLeft.y,
                bottomRight.x - topLeft.x,
                bottomRight.y - topLeft.y
            );
        }

        return new Rectangle();
    }

    /**
     * 开始选择区域
     */
    private void startSelection(Point point) {
        selectionStart = new Point(point);
        selectionEnd = new Point(point);
        selecting = true;
        selectionRect = new Rectangle();
        repaint();
    }

    /**
     * 更新选择区域
     */
    private void updateSelection(Point point) {
        if (selecting && selectionStart != null) {
            selectionEnd = new Point(point);

            // 计算选择矩形
            int x = Math.min(selectionStart.x, selectionEnd.x);
            int y = Math.min(selectionStart.y, selectionEnd.y);
            int width = Math.abs(selectionEnd.x - selectionStart.x);
            int height = Math.abs(selectionEnd.y - selectionStart.y);

            selectionRect = new Rectangle(x, y, width, height);
            repaint();
        }
    }

    /**
     * 完成选择区域
     */
    private void finishSelection(Point point) {
        if (selecting && selectionStart != null && image != null) {
            selectionEnd = new Point(point);

            // 将屏幕坐标转换为世界坐标
            Point worldStart = screenToWorldCoordinates(selectionStart);
            Point worldEnd = screenToWorldCoordinates(selectionEnd);

            if (worldStart != null && worldEnd != null) {
                // 保存世界坐标用于后续绘制
                selectionWorldStart = new Point(worldStart);
                selectionWorldEnd = new Point(worldEnd);

                // 计算世界坐标范围
                currentMinX = Math.min(worldStart.x, worldEnd.x);
                currentMaxX = Math.max(worldStart.x, worldEnd.x);
                currentMinZ = Math.min(worldStart.y, worldEnd.y);
                currentMaxZ = Math.max(worldStart.y, worldEnd.y);
                hasValidSelection = true;

                // 调用回调（仅用于UI更新，不触发渲染）
                if (selectionCallback != null) {
                    selectionCallback.onSelectionComplete(currentMinX, currentMinZ, currentMaxX, currentMaxZ);
                }

                System.out.printf("选择区域: 世界坐标 (%d, %d) 到 (%d, %d) - 按Enter确认\n",
                    currentMinX, currentMinZ, currentMaxX, currentMaxZ);
            }
        }

        // 清除选择状态，但保持世界坐标用于绘制
        selecting = false;
        selectionStart = null;
        selectionEnd = null;
        repaint();
    }

    /**
     * 确认当前选择
     */
    public void confirmCurrentSelection() {
        if (hasValidSelection && selectionCallback != null) {
            selectionCallback.onSelectionConfirmed(currentMinX, currentMinZ, currentMaxX, currentMaxZ);
        }
    }

    /**
     * 清除选择矩形
     */
    public void clearSelection() {
        selectionStart = null;
        selectionEnd = null;
        selectionRect = null;
        selectionWorldStart = null;
        selectionWorldEnd = null;
        selecting = false;
        hasValidSelection = false;
        repaint();
    }

    /**
     * 将屏幕坐标转换为世界坐标
     */
    private Point screenToWorldCoordinates(Point screenPoint) {
        if (image == null) return null;

        // 1. 屏幕坐标 -> 图像像素坐标
        double imageX = (screenPoint.x - imageOffset.x) / scale;
        double imageY = (screenPoint.y - imageOffset.y) / scale;

        // 确保坐标在图像范围内
        imageX = Math.max(0, Math.min(image.getWidth() - 1, imageX));
        imageY = Math.max(0, Math.min(image.getHeight() - 1, imageY));

        // 2. 图像像素坐标 -> 世界坐标
        int worldX = worldMinX + (int) (imageX / pixelsPerBlock);
        int worldZ = worldMinZ + (int) (imageY / pixelsPerBlock);

        return new Point(worldX, worldZ);
    }

    /**
     * 将世界坐标转换为当前屏幕坐标（考虑缩放和偏移）
     */
    private Point worldToScreenCoordinates(Point worldPoint) {
        if (image == null) return null;

        // 1. 世界坐标 -> 图像像素坐标
        double imageX = (worldPoint.x - worldMinX) * pixelsPerBlock;
        double imageY = (worldPoint.y - worldMinZ) * pixelsPerBlock;

        // 2. 图像像素坐标 -> 屏幕坐标（考虑缩放和偏移）
        int screenX = (int) (imageX * scale + imageOffset.x);
        int screenY = (int) (imageY * scale + imageOffset.y);

        return new Point(screenX, screenY);
    }

    /**
     * 开始拖拽地图
     */
    private void startDrag(Point point) {
        lastMousePos = new Point(point);
        dragging = true;
    }

    /**
     * 更新拖拽地图
     */
    private void updateDrag(Point point) {
        if (dragging && lastMousePos != null) {
            int dx = point.x - lastMousePos.x;
            int dy = point.y - lastMousePos.y;

            imageOffset.x += dx;
            imageOffset.y += dy;

            lastMousePos = new Point(point);
            repaint();
        }
    }

    /**
     * 完成拖拽地图
     */
    private void finishDrag() {
        dragging = false;
        lastMousePos = null;
    }

    @Override
    public Dimension getPreferredSize() {
        if (image != null) {
            int width = (int) (image.getWidth() * scale) + Math.abs(imageOffset.x);
            int height = (int) (image.getHeight() * scale) + Math.abs(imageOffset.y);
            return new Dimension(Math.max(800, width), Math.max(600, height));
        }
        return new Dimension(800, 600);
    }
}
