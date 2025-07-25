package com.minecraft.selector.gui;

import com.minecraft.selector.core.MapRenderer;
import com.minecraft.selector.core.MinecraftResourceExtractor;
import com.minecraft.selector.core.BlockColors;
import com.minecraft.selector.nbt.NBTReader;
import com.minecraft.selector.utils.FileUtils;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import javax.swing.SwingWorker;

/**
 * Minecraft地图GUI界面
 * 对应Python的gui.py
 */
public class MinecraftMapGUI extends JFrame {
    
    // GUI组件
    private JLabel saveInfoLabel;
    private JLabel playerPosLabel;
    private JLabel regionLabel;
    private JLabel coordDisplayLabel;
    private JLabel fileInfoLabel;

    private JTextField minYEntry;
    private JTextField maxYEntry;
    private JComboBox<String> lodDropdown;
    private JTextField xCoordEntry;
    private JTextField zCoordEntry;
    // 地图范围选择输入框已删除
    // private JTextField rangeXEntry;
    // private JTextField rangeZEntry;
    // private JTextField rangeSizeEntry;
    private JComboBox<String> mcaRangeDropdown;
    private JCheckBox autoLoadCheckbox;
    private DynamicMapManager dynamicMapManager;
    // private JButton renderButton; // 已删除

    // 多区域渲染的坐标信息
    private int lastMultiRenderMinRegionX = 0;
    private int lastMultiRenderMinRegionZ = 0;
    private JButton renderAroundPlayerButton;
    private JButton jumpButton;
    private JButton confirmSelectionButton;
    private JButton reRenderButton;
    private MapCanvas mapCanvas;
    private JProgressBar progressBar;
    private JLabel progressLabel;
    
    // 数据
    private String savePath;
    private double[] playerPos;
    private Rectangle selectedRegion;
    private BufferedImage mapImage;

    private double customScale = 1.0;
    private Point currentRegion;
    private String currentOutputFile;
    private String currentJsonFile;
    private MinecraftResourceExtractor resourceExtractor;
    private String programDir;

    // Blender集成相关
    private String outputFilePath = null;
    private String jarFilePath = null;
    private boolean isBlenderMode = false;
    private int blenderMinY = 0;
    private int blenderMaxY = 255;

    // 多线程渲染优化
    private ExecutorService renderingExecutor;
    private ExecutorService backgroundExecutor;
    private final int RENDERING_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
    private final int BACKGROUND_THREADS = Math.min(4, Runtime.getRuntime().availableProcessors());

    // UI主题管理器
    private UIThemeManager themeManager;

    // 状态指示器
    private LoadingAnimationPanel.StatusIndicator statusIndicator;
    
    public MinecraftMapGUI() {
        this(null, null, null, 0, 255);
    }

    public MinecraftMapGUI(String worldPath, String outputFile) {
        this(worldPath, outputFile, null, 0, 255);
    }

    public MinecraftMapGUI(String worldPath, String outputFile, int minY, int maxY) {
        this(worldPath, outputFile, null, minY, maxY);
    }

    public MinecraftMapGUI(String worldPath, String outputFile, String jarPath, int minY, int maxY) {
        // 初始化程序目录
        programDir = System.getProperty("user.dir");
        resourceExtractor = new MinecraftResourceExtractor();

        // 设置Blender模式
        this.outputFilePath = outputFile;
        this.jarFilePath = jarPath;
        this.isBlenderMode = (outputFile != null);
        this.blenderMinY = minY;
        this.blenderMaxY = maxY;

        // 初始化线程池
        initializeThreadPools();

        initializeGUI();

        // 尝试加载已保存的颜色文件
        loadSavedColors();

        // 如果是Blender模式，显示模式信息
        if (isBlenderMode) {
            SwingUtilities.invokeLater(() -> {
                progressLabel.setText("Blender集成模式已启动");
                if (worldPath != null) {
                    if (new File(worldPath).exists()) {
                        loadWorldFromPath(worldPath);
                    } else {
                        progressLabel.setText("Blender模式：世界路径不存在 - " + worldPath);
                        System.err.println("Blender模式：世界路径不存在: " + worldPath);
                    }
                }
            });
        }
    }

    /**
     * 加载已保存的颜色文件
     */
    private void loadSavedColors() {
        // 如果提供了JAR路径，优先从JAR文件提取颜色
        if (jarFilePath != null && new File(jarFilePath).exists()) {
            System.out.println("使用提供的JAR路径提取颜色: " + jarFilePath);
            extractColorsFromJar(jarFilePath);
            return;
        }

        // 优先尝试加载JSON格式
        String jsonColorFile = new File(programDir, "block_colors.json").getAbsolutePath();
        if (new File(jsonColorFile).exists()) {
            if (resourceExtractor.loadExtractedColorsFromJson(jsonColorFile)) {
                BlockColors.setResourceExtractor(resourceExtractor);
                System.out.println("已加载保存的方块颜色信息 (JSON格式)");
                return;
            }
        }

        // 如果JSON不存在，尝试加载Properties格式
        String propsColorFile = new File(programDir, "extracted_colors.properties").getAbsolutePath();
        if (new File(propsColorFile).exists()) {
            if (resourceExtractor.loadExtractedColors(propsColorFile)) {
                BlockColors.setResourceExtractor(resourceExtractor);
                System.out.println("已加载保存的方块颜色信息 (Properties格式)");
            }
        }
    }

    /**
     * 初始化线程池
     */
    private void initializeThreadPools() {
        // 主渲染线程池 - 用于重要的渲染任务
        renderingExecutor = Executors.newFixedThreadPool(RENDERING_THREADS, r -> {
            Thread t = new Thread(r, "RenderingThread");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY + 1); // 稍高优先级
            return t;
        });

        // 后台线程池 - 用于自动加载等后台任务
        backgroundExecutor = Executors.newFixedThreadPool(BACKGROUND_THREADS, r -> {
            Thread t = new Thread(r, "BackgroundThread");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1); // 稍低优先级
            return t;
        });

        System.out.printf("线程池初始化完成 - 渲染线程: %d, 后台线程: %d\n",
            RENDERING_THREADS, BACKGROUND_THREADS);
    }

    /**
     * 清理线程池资源
     */
    private void shutdownThreadPools() {
        if (renderingExecutor != null && !renderingExecutor.isShutdown()) {
            renderingExecutor.shutdown();
            try {
                if (!renderingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    renderingExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                renderingExecutor.shutdownNow();
            }
        }

        if (backgroundExecutor != null && !backgroundExecutor.isShutdown()) {
            backgroundExecutor.shutdown();
            try {
                if (!backgroundExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    backgroundExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                backgroundExecutor.shutdownNow();
            }
        }

        System.out.println("线程池已关闭");
    }

    private void initializeGUI() {
        setTitle("Minecraft 地图选择器");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1600, 1000);  // 增加宽度以更好利用空间
        setLocationRelativeTo(null);
        setMinimumSize(new Dimension(1200, 800));  // 优化最小尺寸

        // 添加窗口关闭监听器，确保资源清理
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                shutdownThreadPools();
                System.exit(0);
            }
        });

        // 添加组件监听器，确保布局稳定
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                // 窗口大小变化时，确保控制面板可见
                SwingUtilities.invokeLater(() -> {
                    revalidate();
                    repaint();
                });
            }
        });
        
        // 初始化主题管理器
        themeManager = UIThemeManager.getInstance();
        themeManager.initializeTheme();
        
        createComponents();
        layoutComponents();
        setupEventHandlers();
    }
    
    private void createComponents() {
        // 控制面板组件 - 使用样式化标签
        saveInfoLabel = StyledComponents.createInfoLabel("未选择存档");
        playerPosLabel = StyledComponents.createInfoLabel("玩家位置: 未知");
        regionLabel = StyledComponents.createInfoLabel("选择区域: 未选择");
        coordDisplayLabel = StyledComponents.createInfoLabel("X: 0, Z: 0");
        fileInfoLabel = StyledComponents.createInfoLabel("");


        // 输入框 - 使用样式化输入框
        minYEntry = StyledComponents.createStyledTextField("-64", 6);
        maxYEntry = StyledComponents.createStyledTextField("320", 6);
        xCoordEntry = StyledComponents.createStyledTextField("", 8);
        zCoordEntry = StyledComponents.createStyledTextField("", 8);

        // 地图范围选择输入框已删除
        
        // LOD下拉菜单 - 使用样式化下拉框
        String[] lodOptions = {
            "自动 (根据文件数量)",
            "1 (原始精度)",
            "2 (1/2精度)",
            "4 (1/4精度)",
            "8 (1/8精度)"
        };
        lodDropdown = StyledComponents.createStyledComboBox(lodOptions);
        lodDropdown.setSelectedIndex(0);

        // MCA文件范围下拉框
        mcaRangeDropdown = StyledComponents.createStyledComboBox(new String[]{"1x1 (单个文件)", "3x3 (9个文件)", "5x5 (25个文件)", "7x7 (49个文件)"});
        mcaRangeDropdown.setSelectedItem("1x1 (单个文件)");

        // 自动加载复选框 - 使用样式化复选框
        autoLoadCheckbox = StyledComponents.createStyledCheckBox("启用自动加载", true);

        // 按钮 - 使用新的图标和样式
        // renderButton 已删除
        renderAroundPlayerButton = createStyledButton("渲染玩家周围", IconManager.getPlayerIcon(IconManager.SMALL_ICON_SIZE));
        renderAroundPlayerButton.setEnabled(false);
        jumpButton = createStyledButton("跳转到坐标", IconManager.getJumpIcon(IconManager.SMALL_ICON_SIZE));
        confirmSelectionButton = createStyledButton("确认选择区域", IconManager.getConfirmIcon(IconManager.SMALL_ICON_SIZE));
        confirmSelectionButton.setEnabled(false);
        reRenderButton = createStyledButton("重新渲染", IconManager.getRefreshIcon(IconManager.SMALL_ICON_SIZE));
        reRenderButton.setEnabled(false);
        
        // 地图画布
        mapCanvas = new MapCanvas();

        // 初始化动态地图管理器
        dynamicMapManager = new DynamicMapManager();

        // 设置选择回调
        mapCanvas.setSelectionCallback(new MapCanvas.SelectionCallback() {
            @Override
            public void onSelectionComplete(int minX, int minZ, int maxX, int maxZ) {
                // 选择完成时只更新UI显示，启用确认按钮
                SwingUtilities.invokeLater(() -> {
                    confirmSelectionButton.setEnabled(true);
                    progressLabel.setText(String.format("已选择区域: (%d,%d) 到 (%d,%d) - 点击确认按钮",
                        minX, minZ, maxX, maxZ));
                });
            }

            @Override
            public void onSelectionConfirmed(int minX, int minZ, int maxX, int maxZ) {
                // 按Enter确认时返回坐标并更新输入框
                SwingUtilities.invokeLater(() -> {
                    int centerX = (minX + maxX) / 2;
                    int centerZ = (minZ + maxZ) / 2;
                    int sizeX = maxX - minX;
                    int sizeZ = maxZ - minZ;
                    int size = Math.max(sizeX, sizeZ);

                    // 地图范围选择输入框已删除，不再更新输入框

                    progressLabel.setText(String.format("确认选择区域: (%d,%d) 到 (%d,%d)",
                        minX, minZ, maxX, maxZ));

                    // 在控制台输出坐标
                    System.out.printf("=== 确认的选择区域 ===\n");
                    System.out.printf("左上角坐标: (%d, %d)\n", minX, minZ);
                    System.out.printf("右下角坐标: (%d, %d)\n", maxX, maxZ);
                    System.out.printf("中心坐标: (%d, %d)\n", centerX, centerZ);
                    System.out.printf("区域大小: %dx%d\n", sizeX, sizeZ);

                    // 如果是Blender模式，输出坐标到文件并关闭程序
                    if (isBlenderMode && outputFilePath != null) {
                        outputCoordinatesToFile(minX, minZ, maxX, maxZ);
                    }
                });
            }
        });

        // 设置视野管理回调
        mapCanvas.setViewportCallback(new MapCanvas.ViewportCallback() {
            @Override
            public void onViewportChanged(int minWorldX, int minWorldZ, int maxWorldX, int maxWorldZ) {
                SwingUtilities.invokeLater(() -> {
                    // 可以在这里更新状态栏显示当前视野范围
                    // progressLabel.setText(String.format("视野范围: (%d,%d) 到 (%d,%d)",
                    //     minWorldX, minWorldZ, maxWorldX, maxWorldZ));
                });
            }

            @Override
            public void loadRegion(int regionX, int regionZ) {
                // 在后台线程中加载区域
                loadRegionInBackground(regionX, regionZ);
            }

            @Override
            public void unloadRegion(int regionX, int regionZ) {
                // 卸载区域（可以清理内存中的数据）
                unloadRegionInBackground(regionX, regionZ);
            }
        });
        
        // 进度条 - 使用样式化进度条
        progressBar = StyledComponents.createStyledProgressBar();
        progressLabel = StyledComponents.createInfoLabel("就绪");

        // 状态指示器
        statusIndicator = new LoadingAnimationPanel.StatusIndicator();
        statusIndicator.setStatus(LoadingAnimationPanel.StatusIndicator.StatusType.IDLE);
    }

    /**
     * 创建样式化按钮
     */
    private JButton createStyledButton(String text, Icon icon) {
        JButton button = new JButton(text, icon);

        // 设置按钮样式 - 更紧凑的设计
        button.setFocusPainted(false);
        button.setFont(button.getFont().deriveFont(Font.BOLD, 11f));  // 稍微减小字体
        button.setIconTextGap(6);  // 减少图标和文字间距
        button.setMargin(new Insets(4, 8, 4, 8));  // 减少按钮内边距

        // 设置按钮颜色
        button.setBackground(UIThemeManager.ThemeColors.PRIMARY_BLUE);
        button.setForeground(Color.WHITE);

        // 设置按钮的最大尺寸，防止超出面板边界
        button.setMaximumSize(new Dimension(280, 32));  // 限制宽度和高度
        button.setPreferredSize(new Dimension(280, 32));

        // 添加悬停效果
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (button.isEnabled()) {
                    button.setBackground(UIThemeManager.ThemeColors.PRIMARY_BLUE.brighter());
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (button.isEnabled()) {
                    button.setBackground(UIThemeManager.ThemeColors.PRIMARY_BLUE);
                }
            }
        });

        return button;
    }

    /**
     * 创建分组面板
     */
    private JPanel createGroupPanel(String title, Color borderColor) {
        JPanel groupPanel = new JPanel();
        groupPanel.setLayout(new BoxLayout(groupPanel, BoxLayout.Y_AXIS));
        groupPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(borderColor, 1, true),
                title,
                TitledBorder.LEFT,
                TitledBorder.TOP,
                new Font(Font.SANS_SERIF, Font.BOLD, 11),  // 稍微减小字体
                borderColor
            ),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)  // 减少内边距
        ));
        groupPanel.setBackground(new Color(borderColor.getRed(), borderColor.getGreen(), borderColor.getBlue(), 20));
        return groupPanel;
    }

    /**
     * 显示加载状态
     */
    private void showLoadingState(String message) {
        SwingUtilities.invokeLater(() -> {
            statusIndicator.setStatus(LoadingAnimationPanel.StatusIndicator.StatusType.WORKING);
            progressLabel.setText(message);
        });
    }

    /**
     * 隐藏加载状态
     */
    private void hideLoadingState() {
        SwingUtilities.invokeLater(() -> {
            statusIndicator.setStatus(LoadingAnimationPanel.StatusIndicator.StatusType.IDLE);
        });
    }

    /**
     * 显示成功状态
     */
    private void showSuccessState(String message) {
        SwingUtilities.invokeLater(() -> {
            statusIndicator.setStatus(LoadingAnimationPanel.StatusIndicator.StatusType.SUCCESS);
            progressLabel.setText(message);
        });
    }

    /**
     * 显示错误状态
     */
    private void showErrorState(String message) {
        SwingUtilities.invokeLater(() -> {
            statusIndicator.setStatus(LoadingAnimationPanel.StatusIndicator.StatusType.ERROR);
            progressLabel.setText(message);
        });
    }
    
    private void layoutComponents() {
        setLayout(new BorderLayout());

        // 左侧控制面板 - 简化滚动设置
        JPanel controlPanel = createControlPanel();

        // 创建滚动面板，增加宽度以容纳所有内容
        JScrollPane controlScrollPane = new JScrollPane(controlPanel);
        controlScrollPane.setPreferredSize(new Dimension(320, 0));  // 增加宽度
        controlScrollPane.setMinimumSize(new Dimension(320, 0));
        controlScrollPane.setMaximumSize(new Dimension(320, Integer.MAX_VALUE));
        controlScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        controlScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        // 设置滚动速度
        controlScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        controlScrollPane.getVerticalScrollBar().setBlockIncrement(64);

        // 确保鼠标滚轮可以工作
        controlScrollPane.setWheelScrollingEnabled(true);

        add(controlScrollPane, BorderLayout.WEST);
        
        // 右侧地图显示区域 - 使用现代化边框
        JPanel mapPanel = new JPanel(new BorderLayout());
        mapPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(UIThemeManager.ThemeColors.MINECRAFT_STONE, 2, true),
                "地图预览",
                TitledBorder.CENTER,
                TitledBorder.TOP,
                new Font(Font.SANS_SERIF, Font.BOLD, 14),
                UIThemeManager.ThemeColors.MINECRAFT_STONE
            ),
            BorderFactory.createEmptyBorder(5, 5, 5, 5)
        ));

        JScrollPane mapScrollPane = new JScrollPane(mapCanvas);
        mapScrollPane.setMinimumSize(new Dimension(400, 300));
        mapScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        mapScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        // 直接将地图滚动面板添加到地图面板
        mapPanel.add(mapScrollPane, BorderLayout.CENTER);

        // 设置地图面板的最小尺寸
        mapPanel.setMinimumSize(new Dimension(400, 300));

        add(mapPanel, BorderLayout.CENTER);
        
        // 底部状态栏 - 添加状态指示器
        JPanel statusPanel = new JPanel(new BorderLayout());

        // 左侧状态信息
        JPanel leftStatusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        leftStatusPanel.add(statusIndicator);
        leftStatusPanel.add(progressLabel);
        statusPanel.add(leftStatusPanel, BorderLayout.WEST);

        statusPanel.add(coordDisplayLabel, BorderLayout.EAST);
        
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(progressBar, BorderLayout.NORTH);
        bottomPanel.add(statusPanel, BorderLayout.SOUTH);
        
        add(bottomPanel, BorderLayout.SOUTH);
    }
    
    private JPanel createControlPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UIThemeManager.ThemeColors.PRIMARY_BLUE, 1, true),
            BorderFactory.createEmptyBorder(15, 15, 15, 15)
        ));
        panel.setBackground(Color.WHITE);

        // 设置面板宽度，确保能容纳所有内容
        panel.setPreferredSize(new Dimension(300, 0));  // 增加宽度
        panel.setMinimumSize(new Dimension(300, 0));
        panel.setMaximumSize(new Dimension(300, Integer.MAX_VALUE));
        
        // 创建文件操作分组
        JPanel fileOperationsGroup = createGroupPanel("文件操作", UIThemeManager.ThemeColors.PRIMARY_BLUE);

        // 存档选择
        JButton selectSaveButton = createStyledButton("选择MC存档", IconManager.getFolderIcon(IconManager.SMALL_ICON_SIZE));
        selectSaveButton.addActionListener(e -> selectSave());
        selectSaveButton.setToolTipText("选择Minecraft存档文件夹");
        fileOperationsGroup.add(selectSaveButton);
        fileOperationsGroup.add(Box.createVerticalStrut(3));

        JButton selectMcaButton = createStyledButton("选择MCA文件", IconManager.getFileIcon(IconManager.SMALL_ICON_SIZE));
        selectMcaButton.addActionListener(e -> selectMcaFile());
        selectMcaButton.setToolTipText("手动选择MCA区域文件");
        fileOperationsGroup.add(selectMcaButton);
        fileOperationsGroup.add(Box.createVerticalStrut(3));

        // MC JAR文件选择
        JButton selectJarButton = createStyledButton("选择MC客户端", IconManager.getFileIcon(IconManager.SMALL_ICON_SIZE));
        selectJarButton.addActionListener(e -> selectMinecraftJar());
        selectJarButton.setToolTipText("选择Minecraft客户端JAR文件");
        fileOperationsGroup.add(selectJarButton);
        fileOperationsGroup.add(Box.createVerticalStrut(3));

        // 清除缓存按钮
        JButton clearCacheButton = createStyledButton("清除缓存", IconManager.getClearIcon(IconManager.SMALL_ICON_SIZE));
        clearCacheButton.addActionListener(e -> clearPngCache());
        clearCacheButton.setToolTipText("删除程序目录下的PNG地图文件和缩略图");
        clearCacheButton.setBackground(UIThemeManager.ThemeColors.WARNING_ORANGE);
        fileOperationsGroup.add(clearCacheButton);

        panel.add(fileOperationsGroup);
        panel.add(Box.createVerticalStrut(8));  // 减少间距

        // 创建存档信息分组
        JPanel saveInfoGroup = createGroupPanel("存档信息", UIThemeManager.ThemeColors.SUCCESS_GREEN);
        saveInfoGroup.add(saveInfoLabel);
        saveInfoGroup.add(Box.createVerticalStrut(3));  // 减少间距
        saveInfoGroup.add(playerPosLabel);
        saveInfoGroup.add(Box.createVerticalStrut(3));  // 减少间距
        saveInfoGroup.add(regionLabel);

        panel.add(saveInfoGroup);
        panel.add(Box.createVerticalStrut(8));  // 减少间距

        // 创建渲染设置分组
        JPanel renderSettingsGroup = createGroupPanel("渲染设置", UIThemeManager.ThemeColors.MINECRAFT_BROWN);

        // 高度设置子面板
        JPanel heightPanel = new JPanel(new BorderLayout());
        heightPanel.setBorder(BorderFactory.createTitledBorder("区域高度设置"));
        heightPanel.setOpaque(false);
        
        JPanel minYPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        minYPanel.add(StyledComponents.createStyledLabel("最小Y坐标:"));
        minYPanel.add(minYEntry);
        heightPanel.add(minYPanel, BorderLayout.NORTH);

        JPanel maxYPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        maxYPanel.add(StyledComponents.createStyledLabel("最大Y坐标:"));
        maxYPanel.add(maxYEntry);
        heightPanel.add(maxYPanel, BorderLayout.SOUTH);

        renderSettingsGroup.add(heightPanel);
        renderSettingsGroup.add(Box.createVerticalStrut(6));  // 减少间距

        // LOD设置子面板
        JPanel lodPanel = new JPanel(new BorderLayout());
        lodPanel.setBorder(BorderFactory.createTitledBorder("LOD采样精度设置"));
        lodPanel.setOpaque(false);
        
        JPanel lodSelectPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        lodSelectPanel.add(new JLabel("采样间隔:"));
        lodSelectPanel.add(lodDropdown);
        lodPanel.add(lodSelectPanel, BorderLayout.NORTH);
        
        JLabel lodDesc = new JLabel("<html>较大的采样间隔会加快渲染速度，<br>但降低图像质量</html>");
        lodDesc.setFont(lodDesc.getFont().deriveFont(10f));
        lodPanel.add(lodDesc, BorderLayout.SOUTH);

        // 隐藏LOD设置面板（保留逻辑）
        lodPanel.setVisible(false);
        renderSettingsGroup.add(lodPanel);
        // renderSettingsGroup.add(Box.createVerticalStrut(10)); // 隐藏时不需要间距

        // MCA文件范围设置子面板
        JPanel mcaRangePanel = new JPanel(new BorderLayout());
        mcaRangePanel.setBorder(BorderFactory.createTitledBorder("MCA文件范围"));
        mcaRangePanel.setOpaque(false);

        JPanel mcaSelectPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        mcaSelectPanel.add(new JLabel("渲染范围:"));
        mcaSelectPanel.add(mcaRangeDropdown);
        mcaRangePanel.add(mcaSelectPanel, BorderLayout.NORTH);

        JPanel mcaBottomPanel = new JPanel();
        mcaBottomPanel.setLayout(new BoxLayout(mcaBottomPanel, BoxLayout.Y_AXIS));

        JLabel mcaDesc = new JLabel("<html>选择渲染周围多少个MCA文件<br>更大范围需要更多时间</html>");
        mcaDesc.setFont(mcaDesc.getFont().deriveFont(10f));
        mcaBottomPanel.add(mcaDesc);

        // 添加自动加载复选框
        autoLoadCheckbox.addActionListener(e -> toggleAutoLoad());
        mcaBottomPanel.add(autoLoadCheckbox);

        mcaRangePanel.add(mcaBottomPanel, BorderLayout.SOUTH);

        // 隐藏MCA文件范围设置面板（保留逻辑）
        mcaRangePanel.setVisible(false);
        renderSettingsGroup.add(mcaRangePanel);

        panel.add(renderSettingsGroup);
        panel.add(Box.createVerticalStrut(8));  // 减少间距

        // 创建地图操作分组
        JPanel mapOperationsGroup = createGroupPanel("地图操作", UIThemeManager.ThemeColors.WARNING_ORANGE);

        // 地图范围选择子面板已删除

        // 坐标跳转子面板
        JPanel coordPanel = new JPanel(new BorderLayout());
        coordPanel.setBorder(BorderFactory.createTitledBorder("跳转到坐标"));
        coordPanel.setOpaque(false);
        
        JPanel coordInputPanel = new JPanel(new FlowLayout());
        coordInputPanel.add(new JLabel("X:"));
        coordInputPanel.add(xCoordEntry);
        coordInputPanel.add(new JLabel("Z:"));
        coordInputPanel.add(zCoordEntry);
        coordPanel.add(coordInputPanel, BorderLayout.NORTH);
        
        JButton gotoButton = createStyledButton("跳转到坐标", IconManager.getJumpIcon(IconManager.SMALL_ICON_SIZE));
        gotoButton.addActionListener(e -> gotoCoords());
        coordPanel.add(gotoButton, BorderLayout.SOUTH);

        mapOperationsGroup.add(coordPanel);

        panel.add(mapOperationsGroup);
        panel.add(Box.createVerticalStrut(8));  // 减少间距

        // 创建操作按钮分组
        JPanel actionsGroup = createGroupPanel("操作", UIThemeManager.ThemeColors.DANGER_RED);

        // 确认选择按钮
        confirmSelectionButton.addActionListener(e -> confirmSelection());
        actionsGroup.add(confirmSelectionButton);
        actionsGroup.add(Box.createVerticalStrut(4));  // 减少间距

        // 渲染选定区域按钮已删除

        renderAroundPlayerButton.addActionListener(e -> renderAroundPlayer());
        actionsGroup.add(renderAroundPlayerButton);
        actionsGroup.add(Box.createVerticalStrut(3));  // 减少间距

        reRenderButton.addActionListener(e -> reRenderCurrentView());
        actionsGroup.add(reRenderButton);

        panel.add(actionsGroup);
        panel.add(Box.createVerticalStrut(8));  // 减少间距

        // 文件信息
        fileInfoLabel.setFont(fileInfoLabel.getFont().deriveFont(10f));
        panel.add(fileInfoLabel);

        // 添加适量的底部空间
        panel.add(Box.createVerticalStrut(20));

        return panel;
    }
    
    private void setupEventHandlers() {
        // 地图画布事件处理将在MapCanvas类中实现
    }

    // renderSelectedRegion() 方法已删除，因为地图范围选择输入框已被移除

    /**
     * 渲染指定区域
     */
    private void renderRegion(int minX, int maxX, int minZ, int maxZ) {
        showLoadingState("正在渲染地图区域...");
        // renderButton 已删除
        renderAroundPlayerButton.setEnabled(false);

        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                try {
                    // 获取LOD设置
                    int lodLevel = getLodLevel();

                    // 渲染地图
                    String regionPath = new File(savePath, "region").getAbsolutePath();
                    BufferedImage image = MapRenderer.renderRegion(regionPath, minX, maxX, minZ, maxZ, lodLevel);

                    if (image != null) {
                        SwingUtilities.invokeLater(() -> {
                            mapImage = image;
                            mapCanvas.setImage(image); // 手动渲染时不重置视图

                            // 设置世界坐标映射
                            double pixelsPerBlock = (double) image.getWidth() / (maxX - minX);
                            mapCanvas.setWorldCoordinateMapping(minX, minZ, pixelsPerBlock);

                            mapCanvas.repaint();

                            // 保存图像和数据
                            saveRenderedData(image, minX, maxX, minZ, maxZ);

                            showSuccessState(String.format("渲染完成 - 区域: (%d,%d) 到 (%d,%d)", minX, minZ, maxX, maxZ));
                        });
                    } else {
                        SwingUtilities.invokeLater(() -> {
                            showErrorState("渲染失败");
                        });
                    }
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> {
                        showErrorState("渲染出错: " + e.getMessage());
                        JOptionPane.showMessageDialog(MinecraftMapGUI.this, "渲染失败: " + e.getMessage(),
                                                    "错误", JOptionPane.ERROR_MESSAGE);
                    });
                }
                return null;
            }

            @Override
            protected void done() {
                // renderButton 已删除
                renderAroundPlayerButton.setEnabled(true);
                hideLoadingState();
            }
        };

        worker.execute();
    }

    /**
     * 获取LOD级别
     */
    private int getLodLevel() {
        String selected = (String) lodDropdown.getSelectedItem();
        if (selected.contains("1 (")) return 1;
        if (selected.contains("2 (")) return 2;
        if (selected.contains("4 (")) return 4;
        if (selected.contains("8 (")) return 8;
        return 1; // 默认或自动
    }
    
    /**
     * 选择Minecraft存档
     */
    private void selectSave() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.setDialogTitle("选择Minecraft存档文件夹");
        
        // 设置默认路径
        String defaultPath = getDefaultSavesPath();
        if (defaultPath != null) {
            fileChooser.setCurrentDirectory(new File(defaultPath));
        }
        
        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedDir = fileChooser.getSelectedFile();
            savePath = selectedDir.getAbsolutePath();
            saveInfoLabel.setText("当前存档: " + selectedDir.getName());
            
            showLoadingState("正在读取存档信息...");
            
            // 在后台线程中读取存档信息
            SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    loadSaveData();
                    return null;
                }
                
                @Override
                protected void done() {
                    showSuccessState("存档加载完成");
                    // 启用渲染按钮
                    // renderButton 已删除
                    renderAroundPlayerButton.setEnabled(true);
                    reRenderButton.setEnabled(true);

                    // 默认启用自动加载
                    toggleAutoLoad();
                }
            };
            worker.execute();
        }
    }
    
    /**
     * 选择MCA文件
     */
    private void selectMcaFile() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fileChooser.setDialogTitle("选择MCA区域文件");
        
        FileNameExtensionFilter filter = new FileNameExtensionFilter("Minecraft区域文件 (*.mca)", "mca");
        fileChooser.setFileFilter(filter);
        
        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            renderSingleMcaFile(selectedFile.getAbsolutePath());
        }
    }
    
    /**
     * 获取默认的Minecraft存档路径
     */
    private String getDefaultSavesPath() {
        String os = System.getProperty("os.name").toLowerCase();
        String userHome = System.getProperty("user.home");
        
        if (os.contains("win")) {
            return System.getenv("APPDATA") + "\\.minecraft\\saves";
        } else if (os.contains("mac")) {
            return userHome + "/Library/Application Support/minecraft/saves";
        } else {
            return userHome + "/.minecraft/saves";
        }
    }
    
    /**
     * 加载存档数据
     */
    private void loadSaveData() {
        try {
            // 首先尝试从存档路径查找并提取JAR文件颜色
            autoExtractColorsFromSavePath();

            // 读取level.dat获取玩家位置
            File levelDat = new File(savePath, "level.dat");
            if (levelDat.exists()) {
                playerPos = readPlayerPos(levelDat.getAbsolutePath());
                if (playerPos != null) {
                    SwingUtilities.invokeLater(() -> {
                        playerPosLabel.setText(String.format("玩家位置: X:%.1f, Y:%.1f, Z:%.1f",
                                                           playerPos[0], playerPos[1], playerPos[2]));
                    });

                    // 渲染玩家周围的区域
                    renderAroundPlayer();
                }
            }

        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this, "加载存档时出错: " + e.getMessage(),
                                            "错误", JOptionPane.ERROR_MESSAGE);
                progressLabel.setText("加载存档失败");
            });
        }
    }

    /**
     * 从存档路径自动提取颜色（包括minecraft和mods）
     */
    private void autoExtractColorsFromSavePath() {
        SwingUtilities.invokeLater(() -> {
            progressLabel.setText("正在从存档路径查找Minecraft JAR文件和mods...");
        });

        // 在后台线程中执行
        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                // 使用新的统一初始化方法
                boolean success = BlockColors.initializeColorsFromSavePath(savePath);

                if (success) {
                    SwingUtilities.invokeLater(() -> {
                        progressLabel.setText("成功加载Minecraft和mods方块颜色");
                    });

                    // 保存颜色信息（如果有资源提取器的话）
                    if (resourceExtractor != null && resourceExtractor.isExtractionCompleted()) {
                        try {
                            String jsonFile = new File(programDir, "block_colors.json").getAbsolutePath();
                            String propsFile = new File(programDir, "extracted_colors.properties").getAbsolutePath();

                            resourceExtractor.saveExtractedColorsAsJson(jsonFile);
                            resourceExtractor.saveExtractedColors(propsFile);

                            System.out.println("已保存颜色信息到文件");
                        } catch (Exception e) {
                            System.err.println("保存颜色信息失败: " + e.getMessage());
                        }
                    }
                } else {
                    SwingUtilities.invokeLater(() -> {
                        progressLabel.setText("未找到JAR文件或mods，使用默认颜色");
                    });
                }

                return null;
            }

            @Override
            protected void done() {
                // 提取完成后的处理在doInBackground中已经完成
            }
        };

        worker.execute();
    }

    /**
     * 手动选择Minecraft JAR文件
     */
    private void selectMinecraftJar() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().toLowerCase().endsWith(".jar");
            }

            @Override
            public String getDescription() {
                return "Minecraft JAR文件 (*.jar)";
            }
        });

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            extractColorsFromJar(selectedFile.getAbsolutePath());
        }
    }

    /**
     * 从指定JAR文件提取颜色
     */
    private void extractColorsFromJar(String jarPath) {
        progressLabel.setText("正在从JAR文件提取方块颜色...");

        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                if (resourceExtractor.extractColorsFromMinecraftJar(jarPath)) {
                    BlockColors.setResourceExtractor(resourceExtractor);

                    // 保存为JSON和Properties两种格式
                    String jsonFile = new File(programDir, "block_colors.json").getAbsolutePath();
                    String propsFile = new File(programDir, "extracted_colors.properties").getAbsolutePath();

                    resourceExtractor.saveExtractedColorsAsJson(jsonFile);
                    resourceExtractor.saveExtractedColors(propsFile);

                    SwingUtilities.invokeLater(() -> {
                        progressLabel.setText("成功提取了 " + resourceExtractor.getAllExtractedColors().size() + " 种方块颜色");
                    });
                } else {
                    SwingUtilities.invokeLater(() -> {
                        progressLabel.setText("JAR文件颜色提取失败");
                    });
                }
                return null;
            }
        };

        worker.execute();
    }
    
    /**
     * 读取level.dat中的玩家位置
     */
    private double[] readPlayerPos(String levelDatPath) {
        try {
            NBTReader.NBTCompound nbtFile = NBTReader.readFromFile(levelDatPath);

            // 尝试获取玩家位置数据
            try {
                // 单人模式
                NBTReader.NBTCompound data = nbtFile.getCompound("Data");
                NBTReader.NBTCompound player = data.getCompound("Player");
                NBTReader.NBTList pos = player.getList("Pos");

                double x = ((NBTReader.NBTDouble) pos.get(0)).getValue();
                double y = ((NBTReader.NBTDouble) pos.get(1)).getValue();
                double z = ((NBTReader.NBTDouble) pos.get(2)).getValue();

                return new double[]{x, y, z};
            } catch (Exception e) {
                // 多人模式或其他格式
                try {
                    NBTReader.NBTCompound data = nbtFile.getCompound("Data");
                    int spawnX = data.getInt("SpawnX");
                    int spawnY = data.getInt("SpawnY");
                    int spawnZ = data.getInt("SpawnZ");

                    return new double[]{spawnX, spawnY, spawnZ};
                } catch (Exception e2) {
                    return null;
                }
            }
        } catch (Exception e) {
            System.err.println("读取level.dat时出错: " + e.getMessage());
            return null;
        }
    }

    /**
     * 渲染玩家周围的区域
     */
    private void renderAroundPlayer() {
        if (playerPos == null) {
            return;
        }

        // 计算玩家所在的区域文件
        int centerRegionX = (int) (playerPos[0] / 512);
        int centerRegionZ = (int) (playerPos[2] / 512);

        // 获取MCA范围设置
        int mcaRange = getMcaRange();
        int halfRange = mcaRange / 2;

        // 收集需要渲染的MCA文件
        java.util.List<String> mcaFiles = new java.util.ArrayList<>();
        for (int x = centerRegionX - halfRange; x <= centerRegionX + halfRange; x++) {
            for (int z = centerRegionZ - halfRange; z <= centerRegionZ + halfRange; z++) {
                String regionFile = String.format("r.%d.%d.mca", x, z);
                File regionPath = new File(savePath, "region/" + regionFile);
                if (regionPath.exists()) {
                    mcaFiles.add(regionPath.getAbsolutePath());
                }
            }
        }

        if (!mcaFiles.isEmpty()) {
            SwingUtilities.invokeLater(() -> {
                progressLabel.setText(String.format("正在渲染 %dx%d 区域 (%d个文件)，玩家位置: X:%.1f, Z:%.1f...",
                    mcaRange, mcaRange, mcaFiles.size(), playerPos[0], playerPos[2]));
            });

            // 保存区域坐标信息
            currentRegion = new Point(centerRegionX, centerRegionZ);

            // 渲染多个MCA文件
            if (mcaFiles.size() == 1) {
                // 单个文件，使用原有方法
                renderMap(mcaFiles.get(0));
            } else {
                // 多个文件，使用新的多文件渲染方法
                renderMultipleMcaFiles(mcaFiles, centerRegionX - halfRange, centerRegionZ - halfRange, mcaRange);
            }
        } else {
            SwingUtilities.invokeLater(() -> {
                progressLabel.setText(String.format("找不到玩家周围的区域文件 (中心: r.%d.%d.mca)", centerRegionX, centerRegionZ));
            });
        }
    }

    /**
     * 获取MCA范围设置
     */
    private int getMcaRange() {
        String selected = (String) mcaRangeDropdown.getSelectedItem();
        if (selected.contains("3x3")) return 3;
        if (selected.contains("5x5")) return 5;
        if (selected.contains("7x7")) return 7;
        return 1; // 默认1x1
    }

    /**
     * 渲染多个MCA文件
     */
    private void renderMultipleMcaFiles(java.util.List<String> mcaFiles, int startRegionX, int startRegionZ, int gridSize) {
        SwingWorker<BufferedImage, Void> worker = new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() throws Exception {
                // 创建进度回调
                MapRenderer.ProgressCallback progressCallback = new MapRenderer.ProgressCallback() {
                    @Override
                    public void onProgress(int processed, int total, double speed, Set<String> foundBlocks) {
                        if (total == 0) return;

                        int percent = Math.min(100, processed * 100 / total);
                        SwingUtilities.invokeLater(() -> {
                            progressBar.setValue(percent);
                            progressBar.setString(String.format("%d%% (%d/%d) %.1f区块/秒",
                                                               percent, processed, total, speed));
                        });
                    }
                };

                // 创建地图渲染器
                int maxWorkers = Math.min(Runtime.getRuntime().availableProcessors(), 4);
                MapRenderer renderer = new MapRenderer(maxWorkers, progressCallback);

                try {
                    // 计算区域范围
                    int minRegionX = Integer.MAX_VALUE;
                    int maxRegionX = Integer.MIN_VALUE;
                    int minRegionZ = Integer.MAX_VALUE;
                    int maxRegionZ = Integer.MIN_VALUE;

                    // 解析所有MCA文件的坐标，找到边界
                    Map<String, int[]> regionCoords = new HashMap<>();
                    for (String mcaFile : mcaFiles) {
                        String fileName = new File(mcaFile).getName();
                        String[] parts = fileName.replace("r.", "").replace(".mca", "").split("\\.");
                        if (parts.length == 2) {
                            int regionX = Integer.parseInt(parts[0]);
                            int regionZ = Integer.parseInt(parts[1]);

                            regionCoords.put(mcaFile, new int[]{regionX, regionZ});

                            minRegionX = Math.min(minRegionX, regionX);
                            maxRegionX = Math.max(maxRegionX, regionX);
                            minRegionZ = Math.min(minRegionZ, regionZ);
                            maxRegionZ = Math.max(maxRegionZ, regionZ);
                        }
                    }

                    // 计算总图像大小
                    int totalWidth = (maxRegionX - minRegionX + 1) * 512;
                    int totalHeight = (maxRegionZ - minRegionZ + 1) * 512;

                    System.out.printf("渲染区域范围: (%d,%d) 到 (%d,%d), 总尺寸: %dx%d\n",
                        minRegionX, minRegionZ, maxRegionX, maxRegionZ, totalWidth, totalHeight);

                    // 创建大图像
                    BufferedImage combinedImage = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g2d = combinedImage.createGraphics();

                    // 设置高质量渲染
                    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

                    // 填充背景色
                    g2d.setColor(new Color(32, 32, 32));
                    g2d.fillRect(0, 0, totalWidth, totalHeight);

                    // 渲染每个MCA文件
                    int processedCount = 0;
                    for (String mcaFile : mcaFiles) {
                        int[] coords = regionCoords.get(mcaFile);
                        if (coords != null) {
                            int regionX = coords[0];
                            int regionZ = coords[1];

                            System.out.printf("渲染区域 %d/%d: r.%d.%d.mca\n",
                                ++processedCount, mcaFiles.size(), regionX, regionZ);

                            // 渲染单个区域
                            MapRenderer.BlockInfo[][] topBlocks = renderer.getTopBlocks(mcaFile, 32, 1);
                            if (topBlocks != null) {
                                BufferedImage regionImage = renderer.renderToPng(topBlocks, 1);
                                if (regionImage != null) {
                                    // 计算在大图像中的正确位置
                                    int offsetX = (regionX - minRegionX) * 512;
                                    int offsetY = (regionZ - minRegionZ) * 512;

                                    System.out.printf("  -> 放置在位置: (%d, %d)\n", offsetX, offsetY);

                                    g2d.drawImage(regionImage, offsetX, offsetY, null);
                                }
                            }
                        }
                    }

                    g2d.dispose();

                    // 保存实际的起始坐标信息到类成员变量中（用于后续坐标映射）
                    lastMultiRenderMinRegionX = minRegionX;
                    lastMultiRenderMinRegionZ = minRegionZ;

                    return combinedImage;

                } finally {
                    renderer.shutdown();
                }
            }

            @Override
            protected void done() {
                try {
                    BufferedImage image = get();
                    if (image != null) {
                        mapImage = image;
                        mapCanvas.setImage(image); // 手动渲染时不重置视图

                        // 设置世界坐标映射
                        int worldMinX = lastMultiRenderMinRegionX * 512;
                        int worldMinZ = lastMultiRenderMinRegionZ * 512;
                        double pixelsPerBlock = 1.0;  // 1像素 = 1方块
                        mapCanvas.setWorldCoordinateMapping(worldMinX, worldMinZ, pixelsPerBlock);

                        // renderButton 已删除
                        renderAroundPlayerButton.setEnabled(true);

                        progressLabel.setText(String.format("多区域地图渲染完成，图片尺寸: %dx%d",
                                                          image.getWidth(), image.getHeight()));
                        progressBar.setValue(100);
                        progressBar.setString("完成");

                        // 保存图像
                        saveRenderedImage(image, String.format("multi_region_%dx%d", gridSize, gridSize));

                    } else {
                        progressLabel.setText("多区域地图渲染失败：无法获取方块数据");
                        progressBar.setValue(0);
                        progressBar.setString("失败");
                    }
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(MinecraftMapGUI.this,
                                                "渲染多区域地图时出错: " + e.getMessage(),
                                                "错误", JOptionPane.ERROR_MESSAGE);
                    progressLabel.setText("多区域地图渲染失败");
                    progressBar.setValue(0);
                    progressBar.setString("失败");
                }
            }
        };

        worker.execute();
    }

    /**
     * 渲染单个MCA文件
     */
    private void renderSingleMcaFile(String mcaFilePath) {
        SwingUtilities.invokeLater(() -> {
            progressLabel.setText("正在渲染MCA文件: " + new File(mcaFilePath).getName());
        });

        renderMap(mcaFilePath);
    }

    /**
     * 渲染地图
     */
    private void renderMap(String regionPath) {
        SwingWorker<BufferedImage, Void> worker = new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() throws Exception {
                // 创建进度回调
                MapRenderer.ProgressCallback progressCallback = new MapRenderer.ProgressCallback() {
                    @Override
                    public void onProgress(int processed, int total, double speed, Set<String> foundBlocks) {
                        if (total == 0) return;

                        int percent = Math.min(100, processed * 100 / total);
                        SwingUtilities.invokeLater(() -> {
                            progressBar.setValue(percent);
                            progressBar.setString(String.format("%d%% (%d/%d) %.1f区块/秒",
                                                               percent, processed, total, speed));
                        });
                    }
                };

                // 创建地图渲染器
                int maxWorkers = Math.min(Runtime.getRuntime().availableProcessors(), 4); // GUI模式使用较少线程
                MapRenderer renderer = new MapRenderer(maxWorkers, progressCallback);

                try {
                    // 渲染区块
                    MapRenderer.BlockInfo[][] topBlocks = renderer.getTopBlocks(regionPath, 32, 1);

                    if (topBlocks != null) {
                        // 渲染图像
                        BufferedImage image = renderer.renderToPng(topBlocks, 1);

                        // 保存图像到当前目录
                        if (image != null) {
                            saveRenderedImage(image, regionPath);
                        }

                        return image;
                    }

                    return null;
                } finally {
                    renderer.shutdown();
                }
            }

            @Override
            protected void done() {
                try {
                    BufferedImage image = get();
                    if (image != null) {
                        mapImage = image;
                        mapCanvas.setImage(image); // 手动渲染时不重置视图

                        // 设置世界坐标映射 (一个区域文件是512x512方块)
                        if (currentRegion != null) {
                            int worldMinX = currentRegion.x * 512;
                            int worldMinZ = currentRegion.y * 512;
                            double pixelsPerBlock = (double) image.getWidth() / 512.0;
                            mapCanvas.setWorldCoordinateMapping(worldMinX, worldMinZ, pixelsPerBlock);
                        }

                        // renderButton 已删除

                        progressLabel.setText(String.format("地图渲染完成，图片尺寸: %dx%d",
                                                          image.getWidth(), image.getHeight()));
                        progressBar.setValue(100);
                        progressBar.setString("完成");
                    } else {
                        progressLabel.setText("地图渲染失败：无法获取方块数据");
                        progressBar.setValue(0);
                        progressBar.setString("失败");
                    }
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(MinecraftMapGUI.this,
                                                "渲染地图时出错: " + e.getMessage(),
                                                "错误", JOptionPane.ERROR_MESSAGE);
                    progressLabel.setText("地图渲染失败");
                    progressBar.setValue(0);
                    progressBar.setString("失败");
                }
            }
        };

        worker.execute();
    }







    /**
     * 保存渲染的图像到当前目录
     */
    private void saveRenderedImage(BufferedImage image, String regionPath) {
        try {
            // 从区域文件路径提取文件名
            File regionFile = new File(regionPath);
            String regionFileName = regionFile.getName();
            String baseName = regionFileName.replaceAll("\\.mca$", "");

            // 生成时间戳
            long timestamp = System.currentTimeMillis();

            // 在当前工作目录生成输出文件名
            String currentDir = System.getProperty("user.dir");
            String outputFileName = String.format("%s_map_%d.png", baseName, timestamp);
            String outputPath = new File(currentDir, outputFileName).getAbsolutePath();

            // 保存图像
            javax.imageio.ImageIO.write(image, "PNG", new File(outputPath));

            // 更新文件信息标签
            SwingUtilities.invokeLater(() -> {
                fileInfoLabel.setText(String.format("<html>渲染图像已保存:<br>%s<br><br>图像尺寸: %dx%d</html>",
                                                   outputPath, image.getWidth(), image.getHeight()));
                progressLabel.setText("图像已保存到: " + outputFileName);
            });

            System.out.println("图像已保存到: " + outputPath);

        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this, "保存图像失败: " + e.getMessage(),
                                            "错误", JOptionPane.ERROR_MESSAGE);
            });
            System.err.println("保存图像失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 保存渲染数据（图像和JSON）
     */
    private void saveRenderedData(BufferedImage image, int minX, int maxX, int minZ, int maxZ) {
        try {
            long timestamp = System.currentTimeMillis();
            String baseName = String.format("region_%d_%d_%d_%d", minX, minZ, maxX, maxZ);

            // 保存图像
            String imageFileName = String.format("%s_map_%d.png", baseName, timestamp);
            String imagePath = new File(programDir, imageFileName).getAbsolutePath();
            javax.imageio.ImageIO.write(image, "PNG", new File(imagePath));

            // 更新文件信息
            SwingUtilities.invokeLater(() -> {
                fileInfoLabel.setText(String.format("<html>已保存:<br>%s<br>尺寸: %dx%d</html>",
                                                   imageFileName, image.getWidth(), image.getHeight()));
            });

            System.out.println("地图已保存到: " + imagePath);

        } catch (Exception e) {
            System.err.println("保存数据失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 跳转到指定坐标
     */
    private void gotoCoords() {
        try {
            int x = Integer.parseInt(xCoordEntry.getText().trim());
            int z = Integer.parseInt(zCoordEntry.getText().trim());

            // 实际跳转到地图坐标
            if (mapCanvas != null && mapCanvas.getImage() != null) {
                // 将世界坐标转换为图像坐标
                Point worldCoord = new Point(x, z);
                mapCanvas.jumpToWorldCoordinate(worldCoord);

                progressLabel.setText(String.format("已跳转到坐标 (%d, %d)", x, z));
            } else {
                progressLabel.setText("请先加载地图后再跳转坐标");
            }

        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "请输入有效的坐标数字", "错误", JOptionPane.ERROR_MESSAGE);
        }
    }



    /**
     * 确认选择区域
     */
    private void confirmSelection() {
        // 触发MapCanvas的确认回调
        if (mapCanvas != null) {
            mapCanvas.confirmCurrentSelection();
        }

        // 禁用确认按钮
        confirmSelectionButton.setEnabled(false);
    }

    /**
     * 切换自动加载模式
     */
    private void toggleAutoLoad() {
        boolean enabled = autoLoadCheckbox.isSelected();

        if (mapCanvas != null) {
            mapCanvas.enableAutoViewportManagement(enabled);
        }

        if (enabled) {
            progressLabel.setText("已启用自动加载模式 - 地图将根据视野自动加载");
        } else {
            progressLabel.setText("已禁用自动加载模式");
        }
    }

    /**
     * 在后台加载区域 - 优化版本
     */
    private void loadRegionInBackground(int regionX, int regionZ) {
        if (!autoLoadCheckbox.isSelected()) return;

        // 使用后台线程池异步加载
        CompletableFuture.supplyAsync(() -> {
            String regionFile = String.format("r.%d.%d.mca", regionX, regionZ);
            File regionPath = new File(savePath, "region/" + regionFile);

            if (!regionPath.exists()) {
                System.out.printf("区域文件不存在: %s\n", regionFile);
                return null;
            }

            System.out.printf("开始渲染区域: %s (世界坐标: %d, %d)\n",
                regionFile, regionX * 512, regionZ * 512);

            // 创建简单的进度回调
            MapRenderer.ProgressCallback progressCallback = (processed, total, speed, foundBlocks) -> {
                // 静默加载，不显示进度
            };

            // 创建地图渲染器，使用单线程避免影响主渲染
            MapRenderer renderer = new MapRenderer(1, progressCallback);

            try {
                // 渲染整个区域 (32x32区块 = 512x512方块)
                MapRenderer.BlockInfo[][] topBlocks = renderer.getTopBlocks(regionPath.getAbsolutePath(), 32, 1);
                if (topBlocks != null) {
                    BufferedImage regionImage = renderer.renderToPng(topBlocks, 1);
                    if (regionImage != null) {
                        System.out.printf("成功渲染区域: %s, 图像尺寸: %dx%d\n",
                            regionFile, regionImage.getWidth(), regionImage.getHeight());
                    }
                    return regionImage;
                }
                return null;
            } catch (Exception e) {
                System.err.printf("渲染区域 %s 时出错: %s\n", regionFile, e.getMessage());
                return null;
            } finally {
                renderer.shutdown();
            }
        }, backgroundExecutor)
        .thenAcceptAsync(regionImage -> {
            if (regionImage != null) {
                // 添加到动态地图管理器
                dynamicMapManager.addRegion(regionX, regionZ, regionImage);

                // 更新地图显示，保持用户当前视图
                SwingUtilities.invokeLater(() -> {
                    BufferedImage combinedImage = dynamicMapManager.getCombinedImage();
                    if (combinedImage != null) {
                        // 使用新方法保持世界坐标中心点不变，避免视角跳动
                        Point worldOrigin = dynamicMapManager.getWorldOrigin();
                        mapCanvas.setImageKeepWorldCenter(combinedImage, worldOrigin.x, worldOrigin.y);

                        // 确保布局稳定
                        revalidate();
                        repaint();

                        progressLabel.setText(String.format("动态地图已更新 - 已加载 %d 个区域",
                            dynamicMapManager.getRegionCount()));
                    }
                });

                System.out.printf("成功加载区域 r.%d.%d.mca\n", regionX, regionZ);
            }
        })
        .exceptionally(throwable -> {
            System.err.printf("加载区域 r.%d.%d.mca 失败: %s\n", regionX, regionZ, throwable.getMessage());
            return null;
        });
    }

    /**
     * 卸载区域
     */
    private void unloadRegionInBackground(int regionX, int regionZ) {
        // 从动态地图管理器中移除区域
        dynamicMapManager.removeRegion(regionX, regionZ);

        // 更新地图显示，保持用户当前视图
        SwingUtilities.invokeLater(() -> {
            BufferedImage combinedImage = dynamicMapManager.getCombinedImage();
            if (combinedImage != null) {
                // 使用新方法保持世界坐标中心点不变，避免视角跳动
                Point worldOrigin = dynamicMapManager.getWorldOrigin();
                mapCanvas.setImageKeepWorldCenter(combinedImage, worldOrigin.x, worldOrigin.y);

                // 确保布局稳定
                revalidate();
                repaint();

                progressLabel.setText(String.format("动态地图已更新 - 已加载 %d 个区域",
                    dynamicMapManager.getRegionCount()));
            } else {
                // 如果没有区域了，清空地图
                mapCanvas.setImage(null);
                progressLabel.setText("所有区域已卸载");
            }
        });

        System.out.printf("卸载区域 r.%d.%d.mca\n", regionX, regionZ);
    }

    /**
     * 重新渲染当前视图
     */
    private void reRenderCurrentView() {
        if (dynamicMapManager == null) return;

        // 清除当前所有区域
        dynamicMapManager.clear();
        mapCanvas.setImage(null);

        // 重新启动自动加载
        if (autoLoadCheckbox.isSelected()) {
            progressLabel.setText("正在重新渲染当前视图...");

            // 触发视野更新，重新加载区域
            SwingUtilities.invokeLater(() -> {
                if (mapCanvas != null) {
                    // 强制更新视野
                    mapCanvas.forceViewportUpdate();
                }
                progressLabel.setText("正在重新加载区域...");
            });
        } else {
            progressLabel.setText("请先启用自动加载功能");
        }
    }

    /**
     * 从指定路径加载世界（用于Blender集成）
     */
    private void loadWorldFromPath(String worldPath) {
        File worldDir = new File(worldPath);
        if (!worldDir.exists() || !worldDir.isDirectory()) {
            System.err.println("Invalid world path: " + worldPath);
            return;
        }

        System.out.println("Blender模式：正在加载世界 " + worldPath);

        // 设置存档路径
        this.savePath = worldPath;

        // 更新GUI界面显示存档路径
        SwingUtilities.invokeLater(() -> {
            if (saveInfoLabel != null) {
                saveInfoLabel.setText("当前存档: " + worldDir.getName());
            }
            progressLabel.setText("Blender模式：正在读取存档信息...");
        });

        // 在后台线程中加载存档数据，模拟用户选择存档的完整流程
        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                // 调用loadSaveData方法，这会完整地加载存档数据
                loadSaveData();
                return null;
            }

            @Override
            protected void done() {
                SwingUtilities.invokeLater(() -> {
                    progressLabel.setText("Blender模式：存档加载完成，启用自动加载");

                    // 启用自动加载
                    autoLoadCheckbox.setSelected(true);
                    toggleAutoLoad();

                    System.out.println("Blender模式：成功加载世界并启用自动加载 " + worldPath);
                });
            }
        };
        worker.execute();
    }

    /**
     * 输出坐标到文件（用于Blender集成）
     */
    private void outputCoordinatesToFile(int minX, int minZ, int maxX, int maxZ) {
        try {
            // 使用Blender传递的Y坐标范围
            int actualMinY = blenderMinY;
            int actualMaxY = blenderMaxY;

            // 写入文件
            try (java.io.FileWriter writer = new java.io.FileWriter(outputFilePath)) {
                // 简单的JSON输出
                writer.write("{\n");
                writer.write("  \"minX\": " + minX + ",\n");
                writer.write("  \"minY\": " + actualMinY + ",\n");
                writer.write("  \"minZ\": " + minZ + ",\n");
                writer.write("  \"maxX\": " + maxX + ",\n");
                writer.write("  \"maxY\": " + actualMaxY + ",\n");
                writer.write("  \"maxZ\": " + maxZ + ",\n");
                writer.write("  \"timestamp\": " + System.currentTimeMillis() + "\n");
                writer.write("}\n");
            }

            System.out.printf("Blender模式：坐标已输出到文件 %s\n", outputFilePath);
            System.out.printf("XYZ范围: (%d,%d,%d) 到 (%d,%d,%d)\n",
                minX, actualMinY, minZ, maxX, actualMaxY, maxZ);

            // 延迟关闭程序，让用户看到确认信息
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this,
                    String.format("坐标已选择并发送到Blender:\n" +
                                "起始坐标: (%d, %d, %d)\n" +
                                "结束坐标: (%d, %d, %d)",
                        minX, actualMinY, minZ, maxX, actualMaxY, maxZ),
                    "坐标已确认", JOptionPane.INFORMATION_MESSAGE);

                // 关闭程序
                System.exit(0);
            });

        } catch (Exception e) {
            System.err.println("Blender模式：输出坐标文件失败 " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 清除PNG缓存文件
     */
    private void clearPngCache() {
        // 显示确认对话框
        int result = JOptionPane.showConfirmDialog(
            this,
            "<html>确定要清除PNG缓存吗？<br><br>" +
            "这将删除程序目录下的所有地图PNG文件和缩略图，<br>" +
            "包括：<br>" +
            "• *_map_*.png (地图文件)<br>" +
            "• *_thumbnail.png (缩略图文件)<br>" +
            "• region_*.png (区域文件)<br>" +
            "• r.*.png (MCA文件)<br><br>" +
            "此操作不可撤销！</html>",
            "确认清除缓存",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );

        if (result != JOptionPane.YES_OPTION) {
            return;
        }

        // 在后台线程中执行清理
        SwingWorker<FileUtils.CacheCleanupResult, Void> worker = new SwingWorker<FileUtils.CacheCleanupResult, Void>() {
            @Override
            protected FileUtils.CacheCleanupResult doInBackground() throws Exception {
                SwingUtilities.invokeLater(() -> {
                    progressLabel.setText("正在清理PNG缓存文件...");
                });

                return FileUtils.cleanupPngCache(programDir);
            }

            @Override
            protected void done() {
                try {
                    FileUtils.CacheCleanupResult cleanupResult = get();

                    // 显示结果
                    String message = cleanupResult.getSummary();
                    progressLabel.setText(message);

                    // 显示详细结果对话框
                    int messageType = cleanupResult.isSuccess() ?
                        JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE;

                    JOptionPane.showMessageDialog(
                        MinecraftMapGUI.this,
                        message,
                        "缓存清理结果",
                        messageType
                    );

                } catch (Exception e) {
                    String errorMsg = "清理缓存时出错: " + e.getMessage();
                    progressLabel.setText(errorMsg);
                    JOptionPane.showMessageDialog(
                        MinecraftMapGUI.this,
                        errorMsg,
                        "错误",
                        JOptionPane.ERROR_MESSAGE
                    );
                }
            }
        };

        worker.execute();
    }
}
