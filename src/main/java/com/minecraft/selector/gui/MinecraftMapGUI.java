package com.minecraft.selector.gui;

import com.minecraft.selector.core.MapRenderer;
import com.minecraft.selector.core.MinecraftResourceExtractor;
import com.minecraft.selector.core.BlockColors;
import com.minecraft.selector.nbt.NBTReader;

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
import java.util.Set;
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
    private JLabel zoomLabel;
    private JTextField minYEntry;
    private JTextField maxYEntry;
    private JComboBox<String> lodDropdown;
    private JTextField xCoordEntry;
    private JTextField zCoordEntry;
    private JTextField rangeXEntry;
    private JTextField rangeZEntry;
    private JTextField rangeSizeEntry;
    private JComboBox<String> mcaRangeDropdown;
    private JCheckBox autoLoadCheckbox;
    private DynamicMapManager dynamicMapManager;
    private JButton renderButton;
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
    private double mapScale = 1.0;
    private double customScale = 1.0;
    private Point currentRegion;
    private String currentOutputFile;
    private String currentJsonFile;
    private MinecraftResourceExtractor resourceExtractor;
    private String programDir;
    
    public MinecraftMapGUI() {
        // 初始化程序目录
        programDir = System.getProperty("user.dir");
        resourceExtractor = new MinecraftResourceExtractor();

        initializeGUI();

        // 尝试加载已保存的颜色文件
        loadSavedColors();
    }

    /**
     * 加载已保存的颜色文件
     */
    private void loadSavedColors() {
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
    
    private void initializeGUI() {
        setTitle("Minecraft 地图导出工具 - Java版");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1400, 900);
        setLocationRelativeTo(null);
        setMinimumSize(new Dimension(1200, 700));
        
        // 设置Look and Feel
        try {
            // UIManager.setLookAndFeel(UIManager.getSystemLookAndFeel());
        } catch (Exception e) {
            // 使用默认Look and Feel
        }
        
        createComponents();
        layoutComponents();
        setupEventHandlers();
    }
    
    private void createComponents() {
        // 控制面板组件
        saveInfoLabel = new JLabel("未选择存档");
        playerPosLabel = new JLabel("玩家位置: 未知");
        regionLabel = new JLabel("选择区域: 未选择");
        coordDisplayLabel = new JLabel("X: 0, Z: 0");
        fileInfoLabel = new JLabel("");
        zoomLabel = new JLabel("100%");
        
        // 输入框
        minYEntry = new JTextField("-64", 6);
        maxYEntry = new JTextField("320", 6);
        xCoordEntry = new JTextField(8);
        zCoordEntry = new JTextField(8);

        // 地图范围选择输入框
        rangeXEntry = new JTextField("0", 8);
        rangeZEntry = new JTextField("0", 8);
        rangeSizeEntry = new JTextField("512", 8);
        
        // LOD下拉菜单
        String[] lodOptions = {
            "自动 (根据文件数量)",
            "1 (原始精度)",
            "2 (1/2精度)",
            "4 (1/4精度)",
            "8 (1/8精度)"
        };
        lodDropdown = new JComboBox<>(lodOptions);
        lodDropdown.setSelectedIndex(0);

        // MCA文件范围下拉框
        mcaRangeDropdown = new JComboBox<>(new String[]{"1x1 (单个文件)", "3x3 (9个文件)", "5x5 (25个文件)", "7x7 (49个文件)"});
        mcaRangeDropdown.setSelectedItem("1x1 (单个文件)");

        // 自动加载复选框
        autoLoadCheckbox = new JCheckBox("启用自动加载", true);

        // 按钮
        renderButton = new JButton("渲染选定区域");
        renderButton.setEnabled(false);
        renderAroundPlayerButton = new JButton("渲染玩家周围");
        renderAroundPlayerButton.setEnabled(false);
        jumpButton = new JButton("跳转到坐标");
        confirmSelectionButton = new JButton("确认选择区域");
        confirmSelectionButton.setEnabled(false);
        reRenderButton = new JButton("重新渲染");
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

                    rangeXEntry.setText(String.valueOf(centerX));
                    rangeZEntry.setText(String.valueOf(centerZ));
                    rangeSizeEntry.setText(String.valueOf(size));

                    progressLabel.setText(String.format("确认选择区域: (%d,%d) 到 (%d,%d)",
                        minX, minZ, maxX, maxZ));

                    // 在控制台输出坐标
                    System.out.printf("=== 确认的选择区域 ===\n");
                    System.out.printf("左上角坐标: (%d, %d)\n", minX, minZ);
                    System.out.printf("右下角坐标: (%d, %d)\n", maxX, maxZ);
                    System.out.printf("中心坐标: (%d, %d)\n", centerX, centerZ);
                    System.out.printf("区域大小: %dx%d\n", sizeX, sizeZ);
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
        
        // 进度条
        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressLabel = new JLabel("就绪");
    }
    
    private void layoutComponents() {
        setLayout(new BorderLayout());
        
        // 左侧控制面板
        JPanel controlPanel = createControlPanel();
        JScrollPane controlScrollPane = new JScrollPane(controlPanel);
        controlScrollPane.setPreferredSize(new Dimension(320, 0));
        controlScrollPane.setMinimumSize(new Dimension(300, 0));
        controlScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        controlScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        
        add(controlScrollPane, BorderLayout.WEST);
        
        // 右侧地图显示区域
        JPanel mapPanel = new JPanel(new BorderLayout());
        mapPanel.setBorder(new TitledBorder("地图预览"));
        
        JScrollPane mapScrollPane = new JScrollPane(mapCanvas);
        mapScrollPane.setPreferredSize(new Dimension(800, 600));
        mapPanel.add(mapScrollPane, BorderLayout.CENTER);
        
        add(mapPanel, BorderLayout.CENTER);
        
        // 底部状态栏
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.add(progressLabel, BorderLayout.WEST);
        statusPanel.add(coordDisplayLabel, BorderLayout.EAST);
        
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(progressBar, BorderLayout.NORTH);
        bottomPanel.add(statusPanel, BorderLayout.SOUTH);
        
        add(bottomPanel, BorderLayout.SOUTH);
    }
    
    private JPanel createControlPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        // 存档选择
        JButton selectSaveButton = new JButton("选择Minecraft存档");
        selectSaveButton.addActionListener(e -> selectSave());
        panel.add(selectSaveButton);
        panel.add(Box.createVerticalStrut(5));

        JButton selectMcaButton = new JButton("手动选择MCA文件");
        selectMcaButton.addActionListener(e -> selectMcaFile());
        panel.add(selectMcaButton);
        panel.add(Box.createVerticalStrut(5));

        // MC JAR文件选择
        JButton selectJarButton = new JButton("选择MC客户端JAR");
        selectJarButton.addActionListener(e -> selectMinecraftJar());
        panel.add(selectJarButton);
        panel.add(Box.createVerticalStrut(10));
        
        // 存档信息
        panel.add(saveInfoLabel);
        panel.add(Box.createVerticalStrut(5));
        panel.add(playerPosLabel);
        panel.add(Box.createVerticalStrut(5));
        panel.add(regionLabel);
        panel.add(Box.createVerticalStrut(10));
        
        // 高度设置
        JPanel heightPanel = new JPanel(new BorderLayout());
        heightPanel.setBorder(new TitledBorder("区域高度设置"));
        
        JPanel minYPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        minYPanel.add(new JLabel("最小Y坐标:"));
        minYPanel.add(minYEntry);
        heightPanel.add(minYPanel, BorderLayout.NORTH);
        
        JPanel maxYPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        maxYPanel.add(new JLabel("最大Y坐标:"));
        maxYPanel.add(maxYEntry);
        heightPanel.add(maxYPanel, BorderLayout.SOUTH);
        
        panel.add(heightPanel);
        panel.add(Box.createVerticalStrut(10));
        
        // LOD设置
        JPanel lodPanel = new JPanel(new BorderLayout());
        lodPanel.setBorder(new TitledBorder("LOD采样精度设置"));
        
        JPanel lodSelectPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        lodSelectPanel.add(new JLabel("采样间隔:"));
        lodSelectPanel.add(lodDropdown);
        lodPanel.add(lodSelectPanel, BorderLayout.NORTH);
        
        JLabel lodDesc = new JLabel("<html>较大的采样间隔会加快渲染速度，<br>但降低图像质量</html>");
        lodDesc.setFont(lodDesc.getFont().deriveFont(10f));
        lodPanel.add(lodDesc, BorderLayout.SOUTH);
        
        panel.add(lodPanel);
        panel.add(Box.createVerticalStrut(10));

        // MCA文件范围设置
        JPanel mcaRangePanel = new JPanel(new BorderLayout());
        mcaRangePanel.setBorder(new TitledBorder("MCA文件范围"));

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

        panel.add(mcaRangePanel);
        panel.add(Box.createVerticalStrut(10));

        // 地图范围选择
        JPanel rangePanel = new JPanel(new BorderLayout());
        rangePanel.setBorder(new TitledBorder("地图范围选择"));

        JPanel rangeInputPanel = new JPanel();
        rangeInputPanel.setLayout(new BoxLayout(rangeInputPanel, BoxLayout.Y_AXIS));

        JPanel centerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        centerPanel.add(new JLabel("中心X:"));
        centerPanel.add(rangeXEntry);
        centerPanel.add(new JLabel("Z:"));
        centerPanel.add(rangeZEntry);
        rangeInputPanel.add(centerPanel);

        JPanel sizePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        sizePanel.add(new JLabel("范围大小:"));
        sizePanel.add(rangeSizeEntry);
        sizePanel.add(new JLabel("方块"));
        rangeInputPanel.add(sizePanel);

        rangePanel.add(rangeInputPanel, BorderLayout.CENTER);

        panel.add(rangePanel);
        panel.add(Box.createVerticalStrut(10));

        // 坐标跳转
        JPanel coordPanel = new JPanel(new BorderLayout());
        coordPanel.setBorder(new TitledBorder("跳转到坐标"));
        
        JPanel coordInputPanel = new JPanel(new FlowLayout());
        coordInputPanel.add(new JLabel("X:"));
        coordInputPanel.add(xCoordEntry);
        coordInputPanel.add(new JLabel("Z:"));
        coordInputPanel.add(zCoordEntry);
        coordPanel.add(coordInputPanel, BorderLayout.NORTH);
        
        JButton gotoButton = new JButton("跳转到坐标");
        gotoButton.addActionListener(e -> gotoCoords());
        coordPanel.add(gotoButton, BorderLayout.SOUTH);
        
        panel.add(coordPanel);
        panel.add(Box.createVerticalStrut(10));
        
        // 缩放控制
        JPanel zoomPanel = new JPanel(new BorderLayout());
        zoomPanel.setBorder(new TitledBorder("缩放控制"));
        
        JPanel zoomButtonPanel = new JPanel(new FlowLayout());
        JButton zoomOutButton = new JButton("-");
        zoomOutButton.addActionListener(e -> zoomOut());
        JButton zoomInButton = new JButton("+");
        zoomInButton.addActionListener(e -> zoomIn());
        
        zoomButtonPanel.add(zoomOutButton);
        zoomButtonPanel.add(zoomLabel);
        zoomButtonPanel.add(zoomInButton);
        zoomPanel.add(zoomButtonPanel, BorderLayout.NORTH);
        
        JButton resetViewButton = new JButton("重置视图");
        resetViewButton.addActionListener(e -> resetZoom());
        zoomPanel.add(resetViewButton, BorderLayout.SOUTH);
        
        panel.add(zoomPanel);
        panel.add(Box.createVerticalStrut(10));
        
        // 渲染按钮
        renderButton.addActionListener(e -> renderSelectedRegion());
        panel.add(renderButton);
        panel.add(Box.createVerticalStrut(5));

        renderAroundPlayerButton.addActionListener(e -> renderAroundPlayer());
        panel.add(renderAroundPlayerButton);
        panel.add(Box.createVerticalStrut(5));

        // 确认选择按钮
        confirmSelectionButton.addActionListener(e -> confirmSelection());
        panel.add(confirmSelectionButton);
        panel.add(Box.createVerticalStrut(5));

        // 重新渲染按钮
        reRenderButton.addActionListener(e -> reRenderCurrentView());
        panel.add(reRenderButton);
        panel.add(Box.createVerticalStrut(10));
        
        // 文件信息
        fileInfoLabel.setFont(fileInfoLabel.getFont().deriveFont(10f));
        panel.add(fileInfoLabel);
        
        return panel;
    }
    
    private void setupEventHandlers() {
        // 地图画布事件处理将在MapCanvas类中实现
    }

    /**
     * 渲染选定的地图区域
     */
    private void renderSelectedRegion() {
        if (savePath == null) {
            JOptionPane.showMessageDialog(this, "请先选择存档文件夹", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            // 获取用户输入的参数
            int centerX = Integer.parseInt(rangeXEntry.getText().trim());
            int centerZ = Integer.parseInt(rangeZEntry.getText().trim());
            int size = Integer.parseInt(rangeSizeEntry.getText().trim());

            if (size <= 0 || size > 4096) {
                JOptionPane.showMessageDialog(this, "范围大小必须在1-4096之间", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // 计算区域边界
            int halfSize = size / 2;
            int minX = centerX - halfSize;
            int maxX = centerX + halfSize;
            int minZ = centerZ - halfSize;
            int maxZ = centerZ + halfSize;

            // 开始渲染
            renderRegion(minX, maxX, minZ, maxZ);

        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "请输入有效的数字", "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * 渲染指定区域
     */
    private void renderRegion(int minX, int maxX, int minZ, int maxZ) {
        progressLabel.setText("正在渲染地图区域...");
        renderButton.setEnabled(false);
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
                            mapCanvas.setImage(image);

                            // 设置世界坐标映射
                            double pixelsPerBlock = (double) image.getWidth() / (maxX - minX);
                            mapCanvas.setWorldCoordinateMapping(minX, minZ, pixelsPerBlock);

                            mapCanvas.repaint();

                            // 保存图像和数据
                            saveRenderedData(image, minX, maxX, minZ, maxZ);

                            progressLabel.setText(String.format("渲染完成 - 区域: (%d,%d) 到 (%d,%d)", minX, minZ, maxX, maxZ));
                        });
                    } else {
                        SwingUtilities.invokeLater(() -> {
                            progressLabel.setText("渲染失败");
                        });
                    }
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> {
                        progressLabel.setText("渲染出错: " + e.getMessage());
                        JOptionPane.showMessageDialog(MinecraftMapGUI.this, "渲染失败: " + e.getMessage(),
                                                    "错误", JOptionPane.ERROR_MESSAGE);
                    });
                }
                return null;
            }

            @Override
            protected void done() {
                renderButton.setEnabled(true);
                renderAroundPlayerButton.setEnabled(true);
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
            
            progressLabel.setText("正在读取存档信息...");
            
            // 在后台线程中读取存档信息
            SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    loadSaveData();
                    return null;
                }
                
                @Override
                protected void done() {
                    progressLabel.setText("存档加载完成");
                    // 启用渲染按钮
                    renderButton.setEnabled(true);
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
     * 从存档路径自动提取颜色
     */
    private void autoExtractColorsFromSavePath() {
        SwingUtilities.invokeLater(() -> {
            progressLabel.setText("正在从存档路径查找Minecraft JAR文件...");
        });

        // 在后台线程中执行
        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                java.util.List<String> foundJars = MinecraftResourceExtractor.findMinecraftJarsFromSavePath(savePath);

                if (!foundJars.isEmpty()) {
                    System.out.println("从存档路径找到 " + foundJars.size() + " 个JAR文件");

                    // 选择最新的JAR文件（通常版本号最高）
                    String selectedJar = foundJars.get(foundJars.size() - 1);
                    System.out.println("选择JAR文件: " + selectedJar);

                    SwingUtilities.invokeLater(() -> {
                        progressLabel.setText("正在从JAR文件提取方块颜色...");
                    });

                    // 提取颜色
                    if (resourceExtractor.extractColorsFromMinecraftJar(selectedJar)) {
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
                } else {
                    System.out.println("从存档路径未找到JAR文件");
                    SwingUtilities.invokeLater(() -> {
                        progressLabel.setText("未找到JAR文件，使用默认颜色");
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
                    // 计算总图像大小
                    int totalWidth = gridSize * 512;  // 每个MCA文件512x512方块
                    int totalHeight = gridSize * 512;

                    // 创建大图像
                    BufferedImage combinedImage = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g2d = combinedImage.createGraphics();

                    // 渲染每个MCA文件
                    for (String mcaFile : mcaFiles) {
                        // 从文件名解析区域坐标
                        String fileName = new File(mcaFile).getName();
                        String[] parts = fileName.replace("r.", "").replace(".mca", "").split("\\.");
                        if (parts.length == 2) {
                            int regionX = Integer.parseInt(parts[0]);
                            int regionZ = Integer.parseInt(parts[1]);

                            // 渲染单个区域
                            String[][] topBlocks = renderer.getTopBlocks(mcaFile, 32, 1);
                            if (topBlocks != null) {
                                BufferedImage regionImage = renderer.renderToPng(topBlocks, 1);
                                if (regionImage != null) {
                                    // 计算在大图像中的位置
                                    int offsetX = (regionX - startRegionX) * 512;
                                    int offsetY = (regionZ - startRegionZ) * 512;

                                    g2d.drawImage(regionImage, offsetX, offsetY, null);
                                }
                            }
                        }
                    }

                    g2d.dispose();
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
                        mapCanvas.setImage(image);

                        // 设置世界坐标映射
                        int worldMinX = startRegionX * 512;
                        int worldMinZ = startRegionZ * 512;
                        double pixelsPerBlock = 1.0;  // 1像素 = 1方块
                        mapCanvas.setWorldCoordinateMapping(worldMinX, worldMinZ, pixelsPerBlock);

                        renderButton.setEnabled(true);
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
                    String[][] topBlocks = renderer.getTopBlocks(regionPath, 32, 1);

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
                        mapCanvas.setImage(image);

                        // 设置世界坐标映射 (一个区域文件是512x512方块)
                        if (currentRegion != null) {
                            int worldMinX = currentRegion.x * 512;
                            int worldMinZ = currentRegion.y * 512;
                            double pixelsPerBlock = (double) image.getWidth() / 512.0;
                            mapCanvas.setWorldCoordinateMapping(worldMinX, worldMinZ, pixelsPerBlock);
                        }

                        renderButton.setEnabled(true);

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

            // 更新地图范围输入框
            rangeXEntry.setText(String.valueOf(x));
            rangeZEntry.setText(String.valueOf(z));

            progressLabel.setText(String.format("已跳转到坐标 (%d, %d)", x, z));

        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "请输入有效的坐标数字", "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * 缩放控制方法
     */
    private void zoomIn() {
        mapScale *= 1.2;
        updateZoomLabel();
        mapCanvas.setScale(mapScale);
        mapCanvas.repaint();
    }

    private void zoomOut() {
        mapScale /= 1.2;
        updateZoomLabel();
        mapCanvas.setScale(mapScale);
        mapCanvas.repaint();
    }

    private void resetZoom() {
        mapScale = 1.0;
        updateZoomLabel();
        mapCanvas.setScale(mapScale);
        mapCanvas.repaint();
    }

    private void updateZoomLabel() {
        zoomLabel.setText(String.format("%.0f%%", mapScale * 100));
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
     * 在后台加载区域
     */
    private void loadRegionInBackground(int regionX, int regionZ) {
        if (!autoLoadCheckbox.isSelected()) return;

        SwingWorker<BufferedImage, Void> worker = new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() throws Exception {
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

                // 创建地图渲染器
                MapRenderer renderer = new MapRenderer(2, progressCallback); // 使用较少线程避免影响主渲染

                try {
                    // 渲染整个区域 (32x32区块 = 512x512方块)
                    String[][] topBlocks = renderer.getTopBlocks(regionPath.getAbsolutePath(), 32, 1);
                    if (topBlocks != null) {
                        BufferedImage regionImage = renderer.renderToPng(topBlocks, 1);
                        if (regionImage != null) {
                            System.out.printf("成功渲染区域: %s, 图像尺寸: %dx%d\n",
                                regionFile, regionImage.getWidth(), regionImage.getHeight());
                        }
                        return regionImage;
                    }
                    return null;
                } finally {
                    renderer.shutdown();
                }
            }

            @Override
            protected void done() {
                try {
                    BufferedImage regionImage = get();
                    if (regionImage != null) {
                        // 添加到动态地图管理器
                        dynamicMapManager.addRegion(regionX, regionZ, regionImage);

                        // 更新地图显示
                        SwingUtilities.invokeLater(() -> {
                            BufferedImage combinedImage = dynamicMapManager.getCombinedImage();
                            if (combinedImage != null) {
                                mapCanvas.setImage(combinedImage);

                                // 更新世界坐标映射
                                Point worldOrigin = dynamicMapManager.getWorldOrigin();
                                mapCanvas.setWorldCoordinateMapping(worldOrigin.x, worldOrigin.y, 1.0);

                                progressLabel.setText(String.format("动态地图已更新 - 已加载 %d 个区域",
                                    dynamicMapManager.getRegionCount()));
                            }
                        });

                        System.out.printf("成功加载区域 r.%d.%d.mca\n", regionX, regionZ);
                    }
                } catch (Exception e) {
                    System.err.printf("加载区域 r.%d.%d.mca 失败: %s\n", regionX, regionZ, e.getMessage());
                }
            }
        };

        worker.execute();
    }

    /**
     * 卸载区域
     */
    private void unloadRegionInBackground(int regionX, int regionZ) {
        // 从动态地图管理器中移除区域
        dynamicMapManager.removeRegion(regionX, regionZ);

        // 更新地图显示
        SwingUtilities.invokeLater(() -> {
            BufferedImage combinedImage = dynamicMapManager.getCombinedImage();
            if (combinedImage != null) {
                mapCanvas.setImage(combinedImage);

                // 更新世界坐标映射
                Point worldOrigin = dynamicMapManager.getWorldOrigin();
                mapCanvas.setWorldCoordinateMapping(worldOrigin.x, worldOrigin.y, 1.0);

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
}
