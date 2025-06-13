import os
import sys
import tkinter as tk
from tkinter import ttk, filedialog, messagebox, simpledialog
import threading
import json
import numpy as np
from PIL import Image, ImageTk
import nbt
import a  #导入现有的mca处理模块
import time
import argparse  #导入命令行参数解析模块

class MinecraftMapGUI:
    def __init__(self, root):
        self.root = root
        self.root.title("MC Map Selector")
        self.root.geometry("1100x700")
        
        #是否从Blender启动
        self.from_blender = False
        
        #设置更小的字体和样式
        self.small_font = ("", 8)
        self.normal_font = ("", 9)
        
        #创建自定义样式
        self.style = ttk.Style()
        self.style.configure("Small.TButton", font=self.small_font, padding=(2, 1))
        self.style.configure("Small.TCheckbutton", font=self.small_font)
        self.style.configure("Small.TLabel", font=self.small_font)
        self.style.configure("Small.TLabelframe", font=self.small_font)
        self.style.configure("Small.TLabelframe.Label", font=self.small_font)
        self.style.configure("Small.TFrame", padding=(2, 2))
        
        #玩家位置和选择的区域
        self.player_pos = None
        self.selected_region = {"start": None, "end": None}
        self.selected_game_coords = {"start": None, "end": None}
        
        #当前维度（主世界、下界、末地）
        self.current_dimension = "overworld"
        
        #存储所有可用维度
        self.available_dimensions = [
            "overworld (主世界)",
            "the_nether (下界)",
            "the_end (末地)"
        ]
        
        #维度路径映射
        self.dimension_paths = {
            "overworld": "region",
            "the_nether": "DIM-1/region",
            "the_end": "DIM1/region"
        }
        
        #地图图像
        self.map_image = None
        self.tk_image = None
        self.map_scale = 1.0  #缩放比例
        
        #存档信息
        self.save_path = None
        self.region_files = []
        
        #解析命令行参数
        self.parse_command_line_args()
        
        #尝试自动设置JAR文件路径
        self.auto_detect_jar_file()
        
        #创建界面
        self.create_widgets()
        
        #如果从Blender启动并指定了存档路径，自动加载存档
        if self.from_blender and self.save_path:
            #设置延时加载，确保界面已完全初始化
            self.root.after(100, self.auto_load_save)
    
    def parse_command_line_args(self):
        """解析命令行参数，用于从Blender接收存档路径"""
        parser = argparse.ArgumentParser(description="Minecraft Map Selector")
        parser.add_argument('--save-path', type=str, help='Minecraft存档路径')
        parser.add_argument('--blender', action='store_true', help='从Blender启动')
        parser.add_argument('--direct-output', action='store_true', help='直接输出结果到标准输出，而不是写入临时文件')
        parser.add_argument('--auto-return', action='store_true', help='选择完成后自动返回结果，不显示确认对话框')
        parser.add_argument('--disable-auto-render', action='store_true', help='禁用自动渲染功能')
        
        #解析命令行参数
        args, unknown = parser.parse_known_args()
        
        #如果提供了存档路径，则设置
        if args.save_path and os.path.exists(args.save_path):
            self.save_path = args.save_path
            self.blender_mode = True
        
        #如果指定了从Blender启动标志
        if args.blender:
            self.blender_mode = True
            
        #是否直接输出结果
        self.direct_output = args.direct_output
        
        #是否自动返回结果
        self.auto_return = args.auto_return
        
        #是否禁用自动渲染
        self.disable_auto_render = args.disable_auto_render
        
        #打印调试信息
        if self.blender_mode:
            print(f"从Blender启动，存档路径: {self.save_path}")
            if self.direct_output:
                print("使用直接输出模式")
            if self.auto_return:
                print("使用自动返回模式")
            if self.disable_auto_render:
                print("已禁用自动渲染功能")
    
    def auto_load_save(self):
        """自动加载存档（从Blender启动时使用）"""
        if not self.save_path or not os.path.exists(self.save_path):
            messagebox.showerror("错误", f"指定的存档路径不存在: {self.save_path}")
            return
        
        #更新存档信息
        self.save_info.config(text=f"当前存档: {os.path.basename(self.save_path)}")
        
        #更新状态
        self.status_bar.config(text="正在读取存档信息...")
        print(f"正在加载存档: {self.save_path}")
        
        #扫描模组维度
        try:
            mod_dims = self.scan_mod_dimensions()
            if mod_dims:
                print(f"找到 {len(mod_dims)} 个模组维度: {', '.join(mod_dims)}")
                self.status_bar.config(text=f"找到 {len(mod_dims)} 个模组维度")
            else:
                print("未找到模组维度")
        except Exception as e:
            print(f"扫描模组维度时出错: {str(e)}")
            self.status_bar.config(text=f"扫描模组维度时出错: {str(e)}")
        
        # 如果禁用了自动渲染，只加载存档数据
        if hasattr(self, 'disable_auto_render') and self.disable_auto_render:
            # 在后台线程中只加载存档信息，不渲染
            loading_thread = threading.Thread(target=self.load_save_data, daemon=True)
            loading_thread.start()
        else:
            # 在后台线程中读取存档信息并渲染
            loading_thread = threading.Thread(target=self.load_save_data_and_render, daemon=True)
            loading_thread.start()
        
        #绑定回车键事件以获取选择结果
        self.root.bind("<Return>", self.return_selection_to_blender)
        
        #添加退出按钮，用于返回结果
        return_button = ttk.Button(
            self.root, 
            text="确认选择并返回", 
            command=self.return_selection_to_blender, 
            style="Small.TButton"
        )
        return_button.pack(side=tk.BOTTOM, padx=5, pady=5)
        
        #显示指导消息
        message = "请在地图上拖动选择一个区域，然后按回车键或点击'确认选择并返回'按钮将选择结果返回给Blender"
        self.status_bar.config(text=message)
        print(message)
    
    def load_save_data_and_render(self):
        """加载存档数据并自动渲染主世界地图"""
        try:
            #加载存档数据
            self.load_save_data()
            
            #等待数据加载完成
            time.sleep(1)
            
            #确保选择了主世界
            self.current_dimension = "overworld"
            if hasattr(self, 'dimension_dropdown'):
                #在主线程中更新UI
                self.root.after(0, lambda: self.dimension_dropdown.current(0))
            
            #加载主世界数据
            self.load_dimension_data("overworld")
            
            #自动渲染玩家周围区域（除非禁用）
            if not hasattr(self, 'disable_auto_render') or not self.disable_auto_render:
                self.root.after(500, self.render_around_player)
                print("已自动加载存档并渲染主世界地图")
            else:
                print("已加载存档数据，自动渲染已禁用")
        except Exception as e:
            print(f"加载存档数据并渲染地图时出错: {str(e)}")
            #在主线程中更新UI
            self.root.after(0, lambda: self.status_bar.config(text=f"加载存档时出错: {str(e)}"))
            
            #显示错误消息
            self.root.after(0, lambda: messagebox.showerror("错误", f"加载存档时出错: {str(e)}"))
    
    def return_selection_to_blender(self, event=None):
        """获取选区坐标并返回给Blender"""
        #检查是否已选择区域
        if not hasattr(self, 'selected_game_coords') or not self.selected_game_coords["start"] or not self.selected_game_coords["end"]:
            messagebox.showinfo("提示", "请先在地图上选择一个区域")
            return
        
        #获取Y坐标范围
        try:
            min_y = int(self.min_y_entry.get())
            max_y = int(self.max_y_entry.get())
        except ValueError:
            messagebox.showerror("错误", "无效的Y坐标值，请输入整数")
            return
        
        #获取选择的游戏坐标
        start_x, start_z = self.selected_game_coords["start"]
        end_x, end_z = self.selected_game_coords["end"]
        
        #确保坐标有序 (起点坐标总是较小的那个)
        min_x = min(start_x, end_x)
        min_z = min(start_z, end_z)
        max_x = max(start_x, end_x)
        max_z = max(start_z, end_z)
        
        #创建对角坐标对
        corner1 = (min_x, min_y, min_z)  #第一个角落坐标
        corner2 = (max_x, max_y, max_z)  #对角坐标
        
        #获取维度名称 (不包含显示部分)
        dimension = self.current_dimension
        
        #准备返回给Blender的数据
        result = {
            "dimension": dimension,
            "corner1": corner1,
            "corner2": corner2
        }
        
        #是否需要显示确认对话框
        should_return = True
        if not hasattr(self, 'auto_return') or not self.auto_return:
            #显示确认对话框
            should_return = messagebox.askyesno("确认", f"已选择区域:\n维度: {dimension}\n角落1: {corner1}\n角落2: {corner2}\n\n确认将这些坐标返回给Blender吗?")
        
        if should_return:
            #新方法: 总是写入唯一的临时文件。
            #如果是为Blender输出，则将文件路径打印到stdout。
            try:
                temp_dir = os.environ.get('TEMP') or os.environ.get('TMP') or '.'
                timestamp = int(time.time() * 1000)
                result_file = os.path.join(temp_dir, f"blender_selection_result_{timestamp}.json")

                #将选择结果写入JSON文件
                with open(result_file, 'w', encoding='utf-8') as f:
                    json.dump(result, f, indent=2)
                
                #如果是为Blender输出模式，将文件路径打印到 stdout
                if hasattr(self, 'direct_output') and self.direct_output:
                    print(f"SELECTION_RESULT_FILE:{result_file}")
                    # 添加固定格式的输出，方便插件识别
                    print(f"SELECTION_RESULT:{json.dumps(result)}")
                
                print(f"选择结果已保存到: {result_file}")
                print(f"选择结果: 维度={dimension}, 角落1={corner1}, 角落2={corner2}")

            except Exception as e:
                error_msg = f"保存选择结果文件时出错: {str(e)}\n请检查临时文件夹权限: {temp_dir}"
                print(error_msg)
                messagebox.showerror("错误", error_msg)
                return

            #关闭程序
            self.root.destroy()
    
    def copy_to_clipboard(self, text):
        """复制文本到剪贴板"""
        self.root.clipboard_clear()
        self.root.clipboard_append(text)
        messagebox.showinfo("已复制", "结果已复制到剪贴板")
        self.root.destroy()
    
    def create_widgets(self):
        #创建主框架
        main_frame = ttk.Frame(self.root)
        main_frame.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)
        
        #左侧控制面板
        control_frame = ttk.LabelFrame(main_frame, text="控制面板", style="Small.TLabelframe")
        control_frame.pack(side=tk.LEFT, fill=tk.Y, padx=2, pady=2)
        
        #选择存档按钮 (如果不是从Blender启动则显示)
        if not self.from_blender:
            ttk.Button(control_frame, text="选择Minecraft存档", command=self.select_save, style="Small.TButton").pack(fill=tk.X, padx=3, pady=2)
        
        #添加选择多个MCA文件按钮
        ttk.Button(control_frame, text="从存档中选择MCA文件", command=self.select_mca_from_save, style="Small.TButton").pack(fill=tk.X, padx=3, pady=2)
        ttk.Button(control_frame, text="手动选择MCA文件", command=self.select_multiple_mca, style="Small.TButton").pack(fill=tk.X, padx=3, pady=2)
        
        #存档信息显示
        if self.save_path:
            save_info_text = f"当前存档: {os.path.basename(self.save_path)}"
        else:
            save_info_text = "未选择存档"
        self.save_info = ttk.Label(control_frame, text=save_info_text, style="Small.TLabel", wraplength=180)
        self.save_info.pack(fill=tk.X, padx=3, pady=2)
        
        #添加维度选择框
        dimension_frame = ttk.LabelFrame(control_frame, text="维度选择", style="Small.TLabelframe")
        dimension_frame.pack(fill=tk.X, padx=3, pady=2)
        
        #创建维度下拉菜单
        self.dimension_var = tk.StringVar(value="overworld")
        dimension_options = [
            "overworld (主世界)",
            "the_nether (下界)",
            "the_end (末地)"
        ]
        self.dimension_dropdown = ttk.Combobox(
            dimension_frame, 
            textvariable=self.dimension_var, 
            values=dimension_options, 
            width=20, 
            state="readonly", 
            font=self.small_font
        )
        self.dimension_dropdown.pack(fill=tk.X, padx=3, pady=2)
        self.dimension_dropdown.current(0)  #默认选择主世界
        
        #绑定选择事件
        self.dimension_dropdown.bind("<<ComboboxSelected>>", self.on_dimension_change)
        
        #玩家位置信息
        self.player_pos_label = ttk.Label(control_frame, text="玩家位置:None", style="Small.TLabel")
        self.player_pos_label.pack(fill=tk.X, padx=3, pady=2)
        
        #选择区域标签
        self.region_label = ttk.Label(control_frame, text="选择区域:None", style="Small.TLabel", wraplength=180)
        self.region_label.pack(fill=tk.X, padx=3, pady=2)
        
        #高度设置区域
        height_frame = ttk.LabelFrame(control_frame, text="导出区域高度设置", style="Small.TLabelframe")
        height_frame.pack(fill=tk.X, padx=3, pady=2)
        
        #最小Y坐标
        min_y_frame = ttk.Frame(height_frame, style="Small.TFrame")
        min_y_frame.pack(fill=tk.X, padx=3, pady=1)
        ttk.Label(min_y_frame, text="最小Y坐标:", style="Small.TLabel").pack(side=tk.LEFT)
        self.min_y_entry = ttk.Entry(min_y_frame, width=5, font=self.small_font)
        self.min_y_entry.pack(side=tk.RIGHT, padx=3)
        self.min_y_entry.insert(0, "-64")  #默认值
        
        #最大Y坐标
        max_y_frame = ttk.Frame(height_frame, style="Small.TFrame")
        max_y_frame.pack(fill=tk.X, padx=3, pady=1)
        ttk.Label(max_y_frame, text="最大Y坐标:", style="Small.TLabel").pack(side=tk.LEFT)
        self.max_y_entry = ttk.Entry(max_y_frame, width=5, font=self.small_font)
        self.max_y_entry.pack(side=tk.RIGHT, padx=3)
        self.max_y_entry.insert(0, "320")  #默认值
        
        #添加LOD等级设置区域
        lod_frame = ttk.LabelFrame(control_frame, text="LOD采样设置", style="Small.TLabelframe")
        lod_frame.pack(fill=tk.X, padx=3, pady=2)
        
        #LOD等级选择
        lod_select_frame = ttk.Frame(lod_frame, style="Small.TFrame")
        lod_select_frame.pack(fill=tk.X, padx=3, pady=1)
        ttk.Label(lod_select_frame, text="采样间隔:", style="Small.TLabel").pack(side=tk.LEFT)
        
        #创建LOD下拉菜单
        self.lod_var = tk.StringVar()
        lod_options = [
            "Auto",
            "1 (原始)",
            "2 (1/2)",
            "4 (1/4)",
            "8 (1/8)"
        ]
        self.lod_dropdown = ttk.Combobox(lod_select_frame, textvariable=self.lod_var, values=lod_options, width=15, state="readonly", font=self.small_font)
        self.lod_dropdown.pack(side=tk.RIGHT, padx=3)
        self.lod_dropdown.current(0)  #默认选择自动
        
        #添加采样间隔说明
        lod_desc = ttk.Label(lod_frame, text="较大的LOD会加快渲染Map速度，但降低渲染的质量\nAutoMode会根据区域数量自动调整", justify=tk.LEFT, style="Small.TLabel")
        lod_desc.pack(fill=tk.X, padx=3, pady=1)
        
        #添加从JAR文件提取方块颜色的选项
        color_frame = ttk.LabelFrame(control_frame, text="方块颜色设置", style="Small.TLabelframe")
        color_frame.pack(fill=tk.X, padx=3, pady=2)
        
        #颜色提取选项
        self.extract_colors_var = tk.BooleanVar(value=True)
        extract_colors_cb = ttk.Checkbutton(
            color_frame, 
            text="从Minecraft JAR文件提取方块材质", 
            variable=self.extract_colors_var,
            style="Small.TCheckbutton"
        )
        extract_colors_cb.pack(fill=tk.X, padx=3, pady=1)
        
        #添加模组方块颜色提取选项
        self.extract_mods_var = tk.BooleanVar(value=True)
        extract_mods_cb = ttk.Checkbutton(
            color_frame, 
            text="从Mods文件中提取模组方块材质", 
            variable=self.extract_mods_var,
            style="Small.TCheckbutton"
        )
        extract_mods_cb.pack(fill=tk.X, padx=3, pady=1)
        
        #添加使用颜色缓存的选项
        self.use_color_cache_var = tk.BooleanVar(value=True)
        use_cache_cb = ttk.Checkbutton(
            color_frame, 
            text="使用颜色缓存,如果你的mod更新了,请清除缓存", 
            variable=self.use_color_cache_var,
            style="Small.TCheckbutton"
        )
        use_cache_cb.pack(fill=tk.X, padx=3, pady=1)
        
        #添加清除缓存按钮
        ttk.Button(color_frame, text="清除颜色缓存", command=self.clear_color_cache, style="Small.TButton").pack(fill=tk.X, padx=3, pady=2)
        
        #添加手动设置mods文件夹按钮
        ttk.Button(color_frame, text="手动选择Mods文件夹", command=self.select_mods_folder, style="Small.TButton").pack(fill=tk.X, padx=3, pady=2)
        
        #添加mods文件夹路径显示
        self.mods_path_label = ttk.Label(color_frame, text="未设置Mods文件夹路径", style="Small.TLabel", wraplength=180)
        self.mods_path_label.pack(fill=tk.X, padx=3, pady=1)
        
        #添加提取颜色说明
        color_desc = ttk.Label(
            color_frame, 
            text="启用这些选项,程序会从Minecraft安装和模组中提取方块颜色,这可以支持更多方块的渲染", 
            justify=tk.LEFT, 
            style="Small.TLabel",
            wraplength=180
        )
        color_desc.pack(fill=tk.X, padx=3, pady=1)
        
        #添加手动设置JAR文件路径按钮
        ttk.Button(color_frame, text="手动选择JAR文件", command=self.select_jar_file, style="Small.TButton").pack(fill=tk.X, padx=3, pady=2)
        
        #添加JAR文件路径显示
        self.jar_path_label = ttk.Label(color_frame, text="未设置JAR文件路径", style="Small.TLabel", wraplength=180)
        self.jar_path_label.pack(fill=tk.X, padx=3, pady=1)
        
        #获取选区坐标按钮
        ttk.Button(control_frame, text="获取选区对角坐标", command=self.get_selection_corners, style="Small.TButton").pack(fill=tk.X, padx=3, pady=2)
        
        #分隔线
        ttk.Separator(control_frame, orient='horizontal').pack(fill='x', padx=3, pady=5)
        
        #跳转到坐标区域
        ttk.Label(control_frame, text="跳转到坐标:", style="Small.TLabel").pack(anchor=tk.W, padx=3, pady=1)
        coord_frame = ttk.Frame(control_frame, style="Small.TFrame")
        coord_frame.pack(fill=tk.X, padx=3, pady=2)
        
        #X坐标输入
        ttk.Label(coord_frame, text="X:", style="Small.TLabel").pack(side=tk.LEFT)
        self.x_coord = ttk.Entry(coord_frame, width=7, font=self.small_font)
        self.x_coord.pack(side=tk.LEFT, padx=(2, 5))
        
        #Z坐标输入
        ttk.Label(coord_frame, text="Z:", style="Small.TLabel").pack(side=tk.LEFT)
        self.z_coord = ttk.Entry(coord_frame, width=7, font=self.small_font)
        self.z_coord.pack(side=tk.LEFT, padx=2)
        
        #跳转按钮
        ttk.Button(control_frame, text="跳转到坐标", command=self.goto_coords, style="Small.TButton").pack(fill=tk.X, padx=3, pady=2)
        
        #分隔线
        ttk.Separator(control_frame, orient='horizontal').pack(fill='x', padx=3, pady=5)
        
        #缩放控制
        ttk.Label(control_frame, text="缩放控制:", style="Small.TLabel").pack(anchor=tk.W, padx=3, pady=1)
        zoom_frame = ttk.Frame(control_frame, style="Small.TFrame")
        zoom_frame.pack(fill=tk.X, padx=3, pady=2)
        
        #缩放按钮
        ttk.Button(zoom_frame, text="-", width=2, command=self.zoom_out, style="Small.TButton").pack(side=tk.LEFT, padx=2)
        
        #显示当前缩放比例
        self.zoom_label = ttk.Label(zoom_frame, text="100%", style="Small.TLabel")
        self.zoom_label.pack(side=tk.LEFT, expand=True)
        
        ttk.Button(zoom_frame, text="+", width=2, command=self.zoom_in, style="Small.TButton").pack(side=tk.LEFT, padx=2)
        
        #重置缩放
        ttk.Button(control_frame, text="重置视图", command=self.reset_zoom, style="Small.TButton").pack(fill=tk.X, padx=3, pady=2)
        
        #分隔线
        ttk.Separator(control_frame, orient='horizontal').pack(fill='x', padx=3, pady=5)
        
        #渲染按钮
        self.render_btn = ttk.Button(control_frame, text="渲染选择区域", command=self.render_selected_region, style="Small.TButton")
        self.render_btn.pack(fill=tk.X, padx=3, pady=2)
        self.render_btn["state"] = "disabled"
        
        #添加文件位置信息标签
        self.file_info_label = ttk.Label(control_frame, text="", style="Small.TLabel", wraplength=180)
        self.file_info_label.pack(fill=tk.X, padx=3, pady=2)
        
        #右侧地图显示区域
        map_frame = ttk.LabelFrame(main_frame, text="地图预览", style="Small.TLabelframe")
        map_frame.pack(side=tk.RIGHT, fill=tk.BOTH, expand=True, padx=2, pady=2)
        
        #创建Canvas用于显示地图和选择区域
        self.canvas = tk.Canvas(map_frame, bg="white")
        self.canvas.pack(fill=tk.BOTH, expand=True)
        
        #创建滚动条
        h_scrollbar = ttk.Scrollbar(map_frame, orient=tk.HORIZONTAL, command=self.canvas.xview)
        h_scrollbar.pack(side=tk.BOTTOM, fill=tk.X)
        
        v_scrollbar = ttk.Scrollbar(map_frame, orient=tk.VERTICAL, command=self.canvas.yview)
        v_scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        
        #配置Canvas的滚动区域
        self.canvas.configure(xscrollcommand=h_scrollbar.set, yscrollcommand=v_scrollbar.set)
        
        #绑定鼠标事件
        self.canvas.bind("<Button-1>", self.on_canvas_click)
        self.canvas.bind("<B1-Motion>", self.on_canvas_drag)
        self.canvas.bind("<ButtonRelease-1>", self.on_canvas_release)
        self.canvas.bind("<MouseWheel>", self.on_mouse_wheel)  #Windows滚轮
        self.canvas.bind("<Button-4>", self.on_mouse_wheel)    #Linux上滚
        self.canvas.bind("<Button-5>", self.on_mouse_wheel)    #Linux下滚
        
        #初始化缩放比例
        self.custom_scale = 1.0  #用户自定义缩放
        
        #状态栏
        self.status_bar = ttk.Label(self.root, text="就绪", relief=tk.SUNKEN, anchor=tk.W, font=self.small_font)
        self.status_bar.pack(side=tk.BOTTOM, fill=tk.X)
        
        #鼠标位置坐标显示
        self.coord_display = ttk.Label(self.root, text="", relief=tk.SUNKEN, anchor=tk.E, font=self.small_font)
        self.coord_display.pack(side=tk.BOTTOM, fill=tk.X)
        
        #绑定鼠标移动事件显示坐标
        self.canvas.bind("<Motion>", self.show_mouse_coords)
        
        #如果从Blender启动，添加返回结果按钮
        if self.from_blender:
            #分隔线
            ttk.Separator(control_frame, orient='horizontal').pack(fill='x', padx=3, pady=5)
            
            #添加返回结果按钮
            return_btn = ttk.Button(
                control_frame, 
                text="确认选择并返回到Blender (Enter)", 
                command=self.return_selection_to_blender, 
                style="Small.TButton"
            )
            return_btn.pack(fill=tk.X, padx=3, pady=2)
            
            #添加说明标签
            ttk.Label(
                control_frame, 
                text="在地图上选择区域后，按Enter或点击上方按钮将选择结果返回到Blender", 
                style="Small.TLabel", 
                wraplength=180
            ).pack(fill=tk.X, padx=3, pady=2)
    
    def select_save(self):
        """选择Minecraft存档文件夹"""
        #获取默认的Minecraft存档路径
        default_path = self.get_default_saves_path()
        
        #打开文件对话框选择存档文件夹
        save_dir = filedialog.askdirectory(title="选择Minecraft存档文件夹", initialdir=default_path)
        if not save_dir:
            return
        
        self.save_path = save_dir
        self.save_info.config(text=f"当前存档: {os.path.basename(save_dir)}")
        
        #更新状态
        self.status_bar.config(text="正在读取存档信息...")
        
        #重置维度列表为默认值
        self.available_dimensions = [
            "overworld (主世界)",
            "the_nether (下界)",
            "the_end (末地)"
        ]
        
        print(f"\n======== 选择的存档路径 ========")
        print(f"存档路径: {save_dir}")
        
        #在选择存档后再次尝试自动检测JAR文件（打开调试信息）
        print(f"\n======== 尝试自动检测JAR文件 ========")
        result = self.auto_detect_jar_file(debug=True)
        if result:
            print(f"成功：已找到匹配的JAR文件")
            self.status_bar.config(text="已自动检测JAR文件")
        else:
            print(f"失败：未能自动检测到对应的JAR文件，请考虑手动设置")
        
        #扫描模组维度
        print(f"\n======== 扫描模组维度 ========")
        try:
            mod_dims = self.scan_mod_dimensions()
            if mod_dims:
                print(f"找到 {len(mod_dims)} 个模组维度: {', '.join(mod_dims)}")
                self.status_bar.config(text=f"找到 {len(mod_dims)} 个模组维度")
            else:
                print("未找到模组维度")
        except Exception as e:
            print(f"扫描模组维度时出错: {str(e)}")
            self.status_bar.config(text=f"扫描模组维度时出错: {str(e)}")
        
        #在后台线程中读取存档信息
        threading.Thread(target=self.load_save_data, daemon=True).start()
    
    def load_save_data(self):
        """在后台线程中加载存档数据"""
        try:
            player_pos_found = False
            
            #读取level.dat获取玩家位置
            level_dat_path = os.path.join(self.save_path, "level.dat")
            if os.path.exists(level_dat_path):
                try:
                    player_pos = self.read_player_pos(level_dat_path)
                    if player_pos:
                        self.player_pos = player_pos
                        player_pos_found = True
                        self.root.after(0, lambda: self.player_pos_label.config(
                            text=f"玩家位置: X:{player_pos[0]:.1f}, Y:{player_pos[1]:.1f}, Z:{player_pos[2]:.1f}"))
                except Exception as e:
                    print(f"读取玩家位置时出错: {e}")
                    self.root.after(0, lambda: self.player_pos_label.config(
                        text="玩家位置: 无法读取"))
            
            #加载当前选择的维度
            self.load_dimension_data(self.current_dimension)
            
            #如果无法从level.dat获取玩家位置，尝试从区域文件名推断一个可能的位置
            if not player_pos_found and self.region_files:
                try:
                    estimated_pos = self.estimate_position_from_regions()
                    if estimated_pos:
                        self.player_pos = estimated_pos
                        self.root.after(0, lambda: self.player_pos_label.config(
                            text=f"玩家位置(估计): X:{estimated_pos[0]:.1f}, Y:64.0, Z:{estimated_pos[2]:.1f}"))
                        print(f"使用估计的玩家位置: {estimated_pos}")
                except Exception as e:
                    print(f"估计玩家位置时出错: {e}")
            
            self.root.after(0, lambda: self.status_bar.config(text="存档加载完成"))
            
        except Exception as e:
            self.root.after(0, lambda: messagebox.showerror("错误", f"加载存档时出错: {str(e)}"))
            self.root.after(0, lambda: self.status_bar.config(text="加载存档失败"))
    
    def estimate_position_from_regions(self):
        """从区域文件名估计一个可能的玩家位置"""
        if not self.region_files:
            return None
        
        #计算所有区域的中心点
        region_x_sum = 0
        region_z_sum = 0
        count = 0
        
        for region_file in self.region_files:
            #解析文件名，格式通常是 r.X.Z.mca
            parts = region_file.split('.')
            if len(parts) >= 4 and parts[0] == 'r' and parts[3] == 'mca':
                try:
                    region_x = int(parts[1])
                    region_z = int(parts[2])
                    
                    #计算该区域的中心坐标（一个区域是512×512方块）
                    region_center_x = region_x * 512 + 256
                    region_center_z = region_z * 512 + 256
                    
                    region_x_sum += region_center_x
                    region_z_sum += region_center_z
                    count += 1
                except (ValueError, IndexError) as e:
                    print(f"解析区域文件名 {region_file} 时出错: {e}")
                    continue
        
        if count > 0:
            #计算平均中心点
            avg_x = region_x_sum / count
            avg_z = region_z_sum / count
            #Y坐标默认设置为64（大致在地面层）
            return [avg_x, 64.0, avg_z]
        
        return None
    
    def read_player_pos(self, level_dat_path):
        """读取level.dat中的玩家位置"""
        try:
            #确保使用二进制模式打开文件
            nbt_file = None
            try:
                #使用nbt库解析level.dat文件
                nbt_file = nbt.nbt.NBTFile(level_dat_path, 'rb')
            except Exception as parse_error:
                print(f"解析level.dat文件失败: {parse_error}")
                return None
            
            if not nbt_file:
                return None
            
            #尝试获取玩家位置数据
            #单人模式和多人模式的数据结构可能不同
            try:
                #单人模式
                if "Data" in nbt_file and "Player" in nbt_file["Data"] and "Pos" in nbt_file["Data"]["Player"]:
                    pos = nbt_file["Data"]["Player"]["Pos"]
                return [pos[0].value, pos[1].value, pos[2].value]
            except Exception as e:
                print(f"读取单人模式玩家位置出错: {e}")
            
            try:
                #多人模式 - 尝试获取出生点
                if "Data" in nbt_file:
                    data = nbt_file["Data"]
                    if "SpawnX" in data and "SpawnY" in data and "SpawnZ" in data:
                        pos = (data["SpawnX"].value, data["SpawnY"].value, data["SpawnZ"].value)
                    return pos
            except Exception as e:
                print(f"读取多人模式玩家位置出错: {e}")
            
            #如果上述方法都失败，则返回None
                return None
        except Exception as e:
            print(f"读取level.dat时出错: {e}")
            return None
    
    def get_default_saves_path(self):
        """获取默认的Minecraft存档路径"""
        if sys.platform == "win32":
            return os.path.join(os.environ["APPDATA"], ".minecraft", "saves")
        elif sys.platform == "darwin":
            return os.path.expanduser("~/Library/Application Support/minecraft/saves")
        else:
            return os.path.expanduser("~/.minecraft/saves")
    
    def load_dimension_data(self, dimension):
        """加载指定维度的区域文件"""
        try:
            #根据维度获取区域文件夹路径
            if dimension in self.dimension_paths:
                region_dir = os.path.join(self.save_path, self.dimension_paths[dimension])
            else:
                #对于未知维度，默认使用主世界路径
                region_dir = os.path.join(self.save_path, "region")
            
            if os.path.exists(region_dir):
                self.region_files = [f for f in os.listdir(region_dir) if f.startswith("r.") and f.endswith(".mca")]
                
                #获取维度显示名称
                dimension_display = next((d for d in self.available_dimensions if d.startswith(dimension)), dimension)
                
                self.status_bar.config(text=f"已加载 {len(self.region_files)} 个区域文件 (维度: {dimension_display})")
                
                #渲染玩家周围的区域（除非禁用自动渲染）
                if self.player_pos and self.region_files and (not hasattr(self, 'disable_auto_render') or not self.disable_auto_render):
                    self.render_around_player()
            else:
                self.region_files = []
                self.root.after(0, lambda: self.status_bar.config(text=f"找不到维度 {dimension} 的区域文件夹: {region_dir}"))
        except Exception as e:
            self.root.after(0, lambda: messagebox.showerror("错误", f"加载维度 {dimension} 时出错: {str(e)}"))
            self.root.after(0, lambda: self.status_bar.config(text=f"加载维度 {dimension} 失败"))
    
    def render_around_player(self):
        """渲染玩家周围的区域"""
        if not self.player_pos:
            return
            
        # 如果禁用了自动渲染，直接返回
        if hasattr(self, 'disable_auto_render') and self.disable_auto_render:
            print("自动渲染已禁用，跳过渲染玩家周围区域")
            return
        
        #计算玩家所在的区域文件
        #区域坐标计算 - 将玩家坐标转换为区域坐标
        #Minecraft区域文件: r.x.z.mca，其中x和z是区域坐标
        #每个区域是32×32个区块，每个区块是16×16个方块
        #因此区域坐标 = 方块坐标 ÷ (32*16)
        region_x = int(self.player_pos[0] // 512)
        region_z = int(self.player_pos[2] // 512)
        
        #根据维度获取区域文件夹路径
        if self.current_dimension in self.dimension_paths:
            region_dir = os.path.join(self.save_path, self.dimension_paths[self.current_dimension])
        else:
            #对于未知维度，默认使用主世界路径
            region_dir = os.path.join(self.save_path, "region")
        
        #查找对应的区域文件
        region_file = f"r.{region_x}.{region_z}.mca"
        region_path = os.path.join(region_dir, region_file)
        
        if os.path.exists(region_path):
            self.status_bar.config(text=f"正在渲染区域 ({region_file})，玩家位置: X:{self.player_pos[0]:.1f}, Z:{self.player_pos[2]:.1f}...")
            
            #保存区域坐标信息，用于后续坐标转换
            self.current_region = {"x": region_x, "z": region_z}
            
            #在后台线程中渲染地图
            threading.Thread(target=self.render_map, args=(region_path,), daemon=True).start()
        else:
            #如果找不到玩家所在区域，则尝试查找附近的区域
            nearby_regions = [
                (region_x+1, region_z), (region_x-1, region_z),
                (region_x, region_z+1), (region_x, region_z-1)
            ]
            
            for rx, rz in nearby_regions:
                nearby_file = f"r.{rx}.{rz}.mca"
                nearby_path = os.path.join(region_dir, nearby_file)
                
                if os.path.exists(nearby_path):
                    self.status_bar.config(text=f"找不到玩家所在的区域，渲染附近区域 ({nearby_file})...")
                    
                    #保存区域坐标信息
                    self.current_region = {"x": rx, "z": rz}
                    
                    #渲染找到的第一个区域
                    threading.Thread(target=self.render_map, args=(nearby_path,), daemon=True).start()
                    return
            
            #如果附近也没有区域，随机渲染一个存在的区域文件
            if self.region_files:
                random_region = self.region_files[0]
                random_path = os.path.join(region_dir, random_region)
                self.status_bar.config(text=f"找不到玩家附近的区域，渲染随机区域 ({random_region})...")
                
                #提取区域坐标
                parts = random_region.split(".")
                if len(parts) >= 3:
                    try:
                        rx, rz = int(parts[1]), int(parts[2])
                        self.current_region = {"x": rx, "z": rz}
                    except:
                        pass
                
                #渲染随机区域
                threading.Thread(target=self.render_map, args=(random_path,), daemon=True).start()
            else:
                #获取维度显示名称
                dimension_display = next((d for d in self.available_dimensions if d.startswith(self.current_dimension)), self.current_dimension)
                self.status_bar.config(text=f"当前维度 {dimension_display} 中找不到任何区域文件")
    
    def render_map(self, region_path):
        """渲染地图"""
        # 如果禁用了自动渲染，直接返回
        if hasattr(self, 'disable_auto_render') and self.disable_auto_render:
            print("自动渲染已禁用，跳过渲染地图")
            return
            
        try:
            #使用现有的处理函数渲染地图
            timestamp = int(time.time())
            temp_output = self.get_temp_file_path(f"temp_map_{timestamp}.png")
            
            #确保之前的临时文件已删除
            if os.path.exists(temp_output):
                try:
                    os.remove(temp_output)
                except:
                    pass
            
            #渲染区块
            self.root.after(0, lambda: self.status_bar.config(text=f"正在处理区域文件..."))
            top_blocks = a.get_top_blocks(region_path, max_workers=os.cpu_count())
            
            if top_blocks is not None:
                self.root.after(0, lambda: self.status_bar.config(text=f"正在渲染图片..."))
                a.direct_render_to_png(top_blocks, temp_output)
                
                #检查生成的图片是否存在且有效
                if not os.path.exists(temp_output):
                    self.root.after(0, lambda: messagebox.showerror("错误", "渲染失败：未生成图片文件"))
                    self.root.after(0, lambda: self.status_bar.config(text="渲染失败：未生成图片文件"))
                    return
                
                try:
                    #加载渲染好的图片并检查其有效性
                    self.map_image = Image.open(temp_output)
                    #检查图像大小是否合理
                    width, height = self.map_image.size
                    if width <= 0 or height <= 0:
                        self.root.after(0, lambda: messagebox.showerror("错误", f"渲染图片无效：尺寸异常 ({width}x{height})"))
                        self.root.after(0, lambda: self.status_bar.config(text="渲染图片无效"))
                        return
                    
                    #保存临时图片原始路径
                    self.temp_image_path = temp_output
                    
                    #更新Canvas显示
                    self.update_map_display()
                    
                    #启用渲染按钮
                    self.root.after(0, lambda: self.render_btn.config(state="normal"))
                    
                    #更新文件信息标签
                    self.root.after(0, lambda: self.file_info_label.config(
                        text=f"渲染文件位置:\n{temp_output}"
                    ))
                    
                    self.root.after(0, lambda: self.status_bar.config(text=f"地图渲染完成，图片尺寸: {width}x{height}"))
                except Exception as img_error:
                    self.root.after(0, lambda: messagebox.showerror("错误", f"图片加载失败: {str(img_error)}"))
                    self.root.after(0, lambda: self.status_bar.config(text="图片加载失败"))
            else:
                self.root.after(0, lambda: self.status_bar.config(text="地图渲染失败：无法获取方块数据"))
        
        except Exception as e:
            self.root.after(0, lambda: messagebox.showerror("错误", f"渲染地图时出错: {str(e)}"))
            self.root.after(0, lambda: self.status_bar.config(text="地图渲染失败"))
    
    def update_map_display(self):
        """更新Canvas中的地图显示"""
        if self.map_image:
            #调整图像大小以适应Canvas
            canvas_width = self.canvas.winfo_width()
            canvas_height = self.canvas.winfo_height()
            
            #如果Canvas尚未布局完成，使用默认大小
            if canvas_width <= 1:
                canvas_width = 800
            if canvas_height <= 1:
                canvas_height = 600
            
            #计算基础缩放比例
            img_width, img_height = self.map_image.size
            width_ratio = canvas_width / img_width
            height_ratio = canvas_height / img_height
            base_scale = min(width_ratio, height_ratio)
            
            #应用用户自定义缩放
            self.map_scale = base_scale * self.custom_scale
            
            #更新缩放标签
            self.zoom_label.config(text=f"{int(self.custom_scale * 100)}%")
            
            #缩放图像
            new_width = int(img_width * self.map_scale)
            new_height = int(img_height * self.map_scale)
            
            #对于大图像，使用LANCZOS算法以获得更好的质量
            resized_img = self.map_image.resize((new_width, new_height), Image.LANCZOS)
            
            #转换为Tkinter可用的图像
            self.tk_image = ImageTk.PhotoImage(resized_img)
            
            #清除Canvas并显示新图像
            self.canvas.delete("all")
            
            #设置滚动区域
            self.canvas.config(scrollregion=(0, 0, new_width, new_height))
            
            #创建图像
            self.canvas.create_image(new_width/2, new_height/2, image=self.tk_image, anchor=tk.CENTER, tags="map")
    
    def on_canvas_click(self, event):
        """处理Canvas点击事件，开始选择区域"""
        if not self.map_image:
            return
        
        #记录起始点
        self.selected_region["start"] = (event.x, event.y)
        self.selected_region["end"] = None
        
        #清除之前的选择框
        self.canvas.delete("selection")
    
    def on_canvas_drag(self, event):
        """处理拖动事件，更新选择区域"""
        if not self.map_image or not self.selected_region["start"]:
            return
        #更新终点
        self.selected_region["end"] = (event.x, event.y)
        #绘制选择框
        self.canvas.delete("selection")
        start_x, start_y = self.selected_region["start"]
        self.canvas.create_rectangle(
            start_x, start_y, event.x, event.y,
            outline="red", width=2, tags="selection"
        )
    def on_canvas_release(self, event):
        """处理鼠标释放事件，完成选择"""
        if not self.map_image or not self.selected_region["start"]:
            return
        
        #更新终点
        self.selected_region["end"] = (event.x, event.y)
        
        #计算选择区域对应的游戏内坐标
        self.calculate_selected_coords()
        
        #按下Enter键获取坐标
        self.root.bind("<Return>", lambda e: self.get_selection_corners())
    
    def calculate_selected_coords(self):
        """计算选择区域对应的游戏内坐标"""
        if not self.map_image or not self.selected_region["start"] or not self.selected_region["end"]:
            return
        
        #获取Canvas上选择的像素坐标
        start_x, start_y = self.selected_region["start"]
        end_x, end_y = self.selected_region["end"]
        
        #计算中心点偏移
        canvas_width = self.canvas.winfo_width()
        canvas_height = self.canvas.winfo_height()
        img_width, img_height = self.map_image.size
        scaled_width = img_width * self.map_scale
        scaled_height = img_height * self.map_scale
        
        #计算图像左上角在Canvas中的位置
        img_left = (canvas_width - scaled_width) / 2
        img_top = (canvas_height - scaled_height) / 2
        
        #将Canvas坐标转换为图像坐标
        img_start_x = (start_x - img_left) / self.map_scale
        img_start_y = (start_y - img_top) / self.map_scale
        img_end_x = (end_x - img_left) / self.map_scale
        img_end_y = (end_y - img_top) / self.map_scale
        
        #确保坐标在图像范围内
        img_start_x = max(0, min(img_width, img_start_x))
        img_start_y = max(0, min(img_height, img_start_y))
        img_end_x = max(0, min(img_width, img_end_x))
        img_end_y = max(0, min(img_height, img_end_y))
        
        #获取当前区域的基础坐标（区域左上角的世界坐标）
        if hasattr(self, 'current_region'):
            region_min_x = self.current_region["x"] * 512
            region_min_z = self.current_region["z"] * 512
        else:
            #如果没有区域信息，使用默认值
            region_min_x = 0
            region_min_z = 0
        
        #图像坐标到游戏坐标的转换
        game_start_x = region_min_x + int(img_start_x)
        game_start_z = region_min_z + int(img_start_y)
        game_end_x = region_min_x + int(img_end_x)
        game_end_z = region_min_z + int(img_end_y)
        
        #更新选择区域标签
        self.region_label.config(
            text=f"选择区域: ({game_start_x}, {game_start_z}) 到 ({game_end_x}, {game_end_z})"
        )
        
        #保存选择的游戏坐标
        self.selected_game_coords = {
            "start": (game_start_x, game_start_z),
            "end": (game_end_x, game_end_z)
        }
    
    def render_selected_region(self):
        """渲染选择的区域"""
        #检查是否已选择区域
        if not hasattr(self, 'selected_game_coords') or not self.selected_game_coords["start"] or not self.selected_game_coords["end"]:
            messagebox.showinfo("提示", "请先在地图上选择一个区域")
            return
        
        #获取选择的游戏坐标
        start_x, start_z = self.selected_game_coords["start"]
        end_x, end_z = self.selected_game_coords["end"]
        
        #确保坐标有序 (起点坐标总是较小的那个)
        min_x = min(start_x, end_x)
        min_z = min(start_z, end_z)
        max_x = max(start_x, end_x)
        max_z = max(start_z, end_z)
        
        #计算区域大小
        width = max_x - min_x + 1
        height = max_z - min_z + 1
        
        #检查区域大小是否合理
        if width * height > 10000 * 10000:
            messagebox.showerror("错误", f"选择的区域过大: {width}x{height}方块，请选择更小的区域")
            return
        
        #计算区域范围内的区域文件坐标
        min_region_x = min_x // 512
        min_region_z = min_z // 512
        max_region_x = max_x // 512
        max_region_z = max_z // 512
        
        #计算区域文件数量
        region_count = (max_region_x - min_region_x + 1) * (max_region_z - min_region_z + 1)
        
        #如果区域文件数量过多，询问是否继续
        if region_count > 9:
            if not messagebox.askyesno("确认", f"将渲染 {region_count} 个区域文件，这可能需要较长时间。是否继续？"):
                return
        
        #根据区域文件数量确定采样间隔
        sample_interval = self.get_lod_sample_interval(region_count)
        
        #准备要渲染的区域文件列表
        region_coords = []
        for rx in range(min_region_x, max_region_x + 1):
            for rz in range(min_region_z, max_region_z + 1):
                #构建区域文件名
                region_file = f"r.{rx}.{rz}.mca"
                
                #根据维度获取区域文件夹路径
                if self.current_dimension in self.dimension_paths:
                    region_dir = os.path.join(self.save_path, self.dimension_paths[self.current_dimension])
                else:
                    #对于未知维度，默认使用主世界路径
                    region_dir = os.path.join(self.save_path, "region")
                
                region_path = os.path.join(region_dir, region_file)
                
                #检查文件是否存在
                if os.path.exists(region_path):
                    region_coords.append((rx, rz, region_path))
        
        #如果没有找到区域文件，显示错误
        if not region_coords:
            messagebox.showerror("错误", "在选择的区域内未找到任何区域文件")
            return
        
        #计算方块坐标范围
        min_x = min_region_x * 512
        max_x = (max_region_x + 1) * 512 - 1
        min_z = min_region_z * 512
        max_z = (max_region_z + 1) * 512 - 1
        
        #显示进度条窗口
        progress_window = tk.Toplevel(self.root)
        progress_window.title("渲染进度")
        progress_window.geometry("400x150")
        progress_window.transient(self.root)
        progress_window.grab_set()
        
        #设置进度显示
        ttk.Label(progress_window, text="正在渲染选择的MCA文件...").pack(pady=10)
        progress_var = tk.DoubleVar()
        progress_bar = ttk.Progressbar(progress_window, variable=progress_var, maximum=len(region_coords))
        progress_bar.pack(fill=tk.X, padx=20, pady=10)
        progress_label = ttk.Label(progress_window, text="准备中...")
        progress_label.pack(pady=10)
        
        #启动渲染线程
        def render_thread():
            try:
                #准备渲染结果
                total_width = (max_region_x - min_region_x + 1) * 512
                total_height = (max_region_z - min_region_z + 1) * 512
                
                #检查合并图像尺寸是否合理
                if total_width <= 0 or total_height <= 0 or total_width > 10000 or total_height > 10000:
                    progress_window.after(0, lambda: messagebox.showerror(
                        "错误", 
                        f"无效的图像尺寸: {total_width}x{total_height}，请选择更小区域的MCA文件"
                    ))
                    progress_window.after(0, progress_window.destroy)
                    return
                
                #创建合并图像（使用RGBA模式以支持透明度）
                combined_image = Image.new('RGBA', (total_width, total_height), (255, 255, 255, 0))
                
                #记录渲染区域的基准坐标
                self.render_base_coords = {
                    "x": min_region_x * 512,
                    "z": min_region_z * 512
                }
                
                #记录采样间隔，用于后续处理
                self.sample_interval = sample_interval
                
                #处理每个MCA文件
                for i, (rx, rz, mca_file) in enumerate(region_coords):
                    progress_window.after(0, lambda i=i: progress_var.set(i))
                    progress_window.after(0, lambda rx=rx, rz=rz, i=i: progress_label.config(
                        text=f"渲染区域 {i+1}/{len(region_coords)}: r.{rx}.{rz}.mca"
                    ))
                    
                    #渲染当前区域 - 使用临时文件路径
                    temp_output = self.get_temp_file_path(f"temp_region_{rx}_{rz}.png")
                    
                    #清理之前的临时文件
                    if os.path.exists(temp_output):
                        try:
                            os.remove(temp_output)
                        except:
                            pass
                    
                    #控制颜色提取功能
                    if not self.extract_colors_var.get():
                        os.environ['DISABLE_COLOR_EXTRACTION'] = '1'
                    else:
                        os.environ.pop('DISABLE_COLOR_EXTRACTION', None)
                    
                    #控制模组颜色提取功能
                    if not self.extract_mods_var.get():
                        os.environ['DISABLE_MODS_EXTRACTION'] = '1'
                    else:
                        os.environ.pop('DISABLE_MODS_EXTRACTION', None)
                    
                    #控制颜色缓存功能
                    if not self.use_color_cache_var.get():
                        os.environ['DISABLE_COLOR_CACHE'] = '1'
                    else:
                        os.environ.pop('DISABLE_COLOR_CACHE', None)
                    
                    # 如果是从Blender启动的，确保使用不透明颜色
                    if hasattr(self, 'blender_mode') and self.blender_mode:
                        os.environ['USE_OPAQUE_COLORS'] = '1'
                    
                    #渲染区块，传递采样间隔参数
                    top_blocks = a.get_top_blocks(mca_file, max_workers=os.cpu_count(), sample_interval=sample_interval)
                    
                    if top_blocks is not None:
                        #使用direct_render_to_png函数渲染图片，传递采样间隔参数
                        render_success = a.direct_render_to_png(top_blocks, temp_output, sample_interval=sample_interval)
                        
                        if render_success and os.path.exists(temp_output):
                            try:
                                #加载渲染好的区域图像
                                region_img = Image.open(temp_output)
                                
                                #检查区域图像尺寸
                                if region_img.size[0] <= 0 or region_img.size[1] <= 0:
                                    progress_window.after(0, lambda rx=rx, rz=rz: messagebox.showwarning(
                                        "警告", 
                                        f"区域 r.{rx}.{rz}.mca 渲染图像尺寸无效: {region_img.size[0]}x{region_img.size[1]}"
                                    ))
                                    continue
                                
                                #计算此区域图像在合并图像中的位置
                                pos_x = (rx - min_region_x) * 512
                                pos_z = (rz - min_region_z) * 512
                                
                                #粘贴到合并图像上
                                combined_image.paste(region_img, (pos_x, pos_z))
                            except Exception as img_error:
                                progress_window.after(0, lambda rx=rx, rz=rz, e=str(img_error): messagebox.showwarning(
                                    "警告", 
                                    f"无法处理区域 r.{rx}.{rz}.mca 的图像: {e}"
                                ))
                        else:
                            progress_window.after(0, lambda rx=rx, rz=rz: messagebox.showwarning(
                                "警告", 
                                f"无法渲染区域 r.{rx}.{rz}.mca"
                            ))
                    else:
                        progress_window.after(0, lambda rx=rx, rz=rz: messagebox.showwarning(
                            "警告", 
                            f"无法读取区域 r.{rx}.{rz}.mca 的方块数据"
                        ))
                    
                    #清理临时文件
                    try:
                        if os.path.exists(temp_output):
                            os.remove(temp_output)
                    except:
                        pass
                
                #检查合并图像是否包含有效数据
                if combined_image.getbbox() is None:
                    progress_window.after(0, lambda: messagebox.showerror(
                        "错误", 
                        "渲染失败：合并后的图像为空"
                    ))
                    progress_window.after(0, progress_window.destroy)
                    return
                
                #保存合并后的图像到临时文件夹
                timestamp = int(time.time())
                output_file = self.get_temp_file_path(f"combined_map_{timestamp}.png")
                combined_image.save(output_file)
                
                #保存坐标信息到临时文件夹
                json_output = self.get_temp_file_path(f"map_coords_{timestamp}.json")
                coords_data = {
                    "timestamp": timestamp,
                    "image_size": combined_image.size,
                    "base_coords": self.render_base_coords,
                    "region_bounds": {
                        "min_rx": min_region_x,
                        "max_rx": max_region_x,
                        "min_rz": min_region_z,
                        "max_rz": max_region_z
                    },
                    "block_bounds": {
                        "min_x": self.render_base_coords["x"],
                        "min_z": self.render_base_coords["z"],
                        "max_x": self.render_base_coords["x"] + total_width,
                        "max_z": self.render_base_coords["z"] + total_height
                    },
                    "sample_interval": sample_interval,
                    "selection": self.selected_game_coords
                }
                
                with open(json_output, 'w', encoding='utf-8') as f:
                    json.dump(coords_data, f, indent=2)
                
                #检查保存的图像是否存在
                if not os.path.exists(output_file):
                    progress_window.after(0, lambda: messagebox.showerror(
                        "错误", 
                        "保存图像失败"
                    ))
                    progress_window.after(0, progress_window.destroy)
                    return
                
                #更新GUI显示
                self.map_image = combined_image
                self.current_output_file = output_file
                self.current_json_file = json_output
                progress_window.after(0, self.update_map_display)
                progress_window.after(0, lambda: self.status_bar.config(text=f"已渲染选定区域，保存为: {output_file}"))
                
                #更新选择区域标签 - 使用实际方块坐标范围
                progress_window.after(0, lambda: self.region_label.config(
                    text=f"渲染区域: ({self.render_base_coords['x']}, {self.render_base_coords['z']}) 到 "
                         f"({self.render_base_coords['x'] + total_width}, {self.render_base_coords['z'] + total_height})"
                ))
                
                #更新文件信息标签
                progress_window.after(0, lambda: self.file_info_label.config(
                    text=f"图像文件:\n{output_file}\n\n坐标文件:\n{json_output}"
                ))
                
                #关闭进度窗口
                progress_window.after(500, progress_window.destroy)
                
            except Exception as e:
                progress_window.after(0, lambda: messagebox.showerror("错误", f"渲染区域时出错: {str(e)}"))
                progress_window.after(0, progress_window.destroy)
        
        #启动渲染线程
        threading.Thread(target=render_thread, daemon=True).start()
    
    def zoom_out(self):
        """缩小地图"""
        if not self.map_image:
            return
        
        #限制最小缩放比例
        if self.custom_scale > 0.2:
            self.custom_scale /= 1.2
            self.update_map_display()
    
    def zoom_in(self):
        """放大地图"""
        if not self.map_image:
            return
        
        #限制最大缩放比例
        if self.custom_scale < 5.0:
            self.custom_scale *= 1.2
            self.update_map_display()
    
    def reset_zoom(self):
        """重置缩放和视图位置"""
        if not self.map_image:
            return
        
        #重置缩放比例
        self.custom_scale = 1.0
        
        #重置滚动位置
        self.canvas.xview_moveto(0)
        self.canvas.yview_moveto(0)
        
        #更新显示
        self.update_map_display()
        
        #清除任何标记
        self.canvas.delete("marker")
        
        self.status_bar.config(text="视图已重置")
    
    def on_mouse_wheel(self, event):
        """处理鼠标滚轮事件"""
        if not self.map_image:
            return
        
        #根据不同平台处理滚轮事件
        if event.num == 4 or (hasattr(event, 'delta') and event.delta > 0):
            #向上滚动 - 放大
            self.zoom_in()
        elif event.num == 5 or (hasattr(event, 'delta') and event.delta < 0):
            #向下滚动 - 缩小
            self.zoom_out()
    
    def show_mouse_coords(self, event):
        """显示鼠标所在位置的游戏坐标"""
        if not self.map_image:
            return
        
        #获取Canvas中的位置
        canvas_x = self.canvas.canvasx(event.x)
        canvas_z = self.canvas.canvasy(event.y)
        
        #转换为图像坐标
        img_x = canvas_x / self.map_scale
        img_z = canvas_z / self.map_scale
        
        #计算游戏坐标
        if hasattr(self, 'render_base_coords'):
            base_x = self.render_base_coords["x"]
            base_z = self.render_base_coords["z"]
        elif hasattr(self, 'current_region'):
            base_x = self.current_region["x"] * 512
            base_z = self.current_region["z"] * 512
        else:
            base_x = 0
            base_z = 0
        
        #计算实际游戏坐标
        game_x = base_x + int(img_x)
        game_z = base_z + int(img_z)
        
        #更新坐标显示
        self.coord_display.config(text=f"X: {game_x}, Z: {game_z}")

    def goto_coords(self):
        """跳转到指定坐标"""
        try:
            #获取用户输入的坐标
            x = int(self.x_coord.get())
            z = int(self.z_coord.get())
            
            #如果已经加载了地图，尝试在当前地图上跳转
            if self.map_image:
                #检查是否有当前区域信息
                if hasattr(self, 'current_region') or hasattr(self, 'render_base_coords'):
                    #计算相对于地图的坐标
                    if hasattr(self, 'render_base_coords'):
                        base_x = self.render_base_coords["x"]
                        base_z = self.render_base_coords["z"]
                    else:
                        base_x = self.current_region["x"] * 512
                        base_z = self.current_region["z"] * 512
                    
                    #计算图像上的像素坐标
                    img_width, img_height = self.map_image.size
                    rel_x = x - base_x
                    rel_z = z - base_z
                    
                    #检查坐标是否在地图范围内
                    if 0 <= rel_x < img_width and 0 <= rel_z < img_height:
                        #在地图范围内，直接跳转
                        #计算缩放后的坐标
                        scaled_x = rel_x * self.map_scale
                        scaled_z = rel_z * self.map_scale
                        
                        #设置Canvas的滚动位置，使目标坐标居中
                        canvas_width = self.canvas.winfo_width()
                        canvas_height = self.canvas.winfo_height()
                        
                        #计算滚动位置
                        scroll_x = (scaled_x - canvas_width/2) / (img_width * self.map_scale)
                        scroll_z = (scaled_z - canvas_height/2) / (img_height * self.map_scale)
                        
                        #确保滚动位置在有效范围内
                        scroll_x = max(0, min(1, scroll_x))
                        scroll_z = max(0, min(1, scroll_z))
                        
                        #设置滚动位置
                        self.canvas.xview_moveto(scroll_x)
                        self.canvas.yview_moveto(scroll_z)
                        
                        #在目标位置绘制一个标记
                        mark_size = 10
                        self.canvas.delete("marker")
                        self.canvas.create_oval(
                            scaled_x - mark_size, scaled_z - mark_size,
                            scaled_x + mark_size, scaled_z + mark_size,
                            outline="red", width=2, tags="marker"
                        )
                        self.canvas.create_text(
                            scaled_x, scaled_z - mark_size - 5,
                            text=f"({x}, {z})", fill="red", tags="marker"
                        )
                        
                        self.status_bar.config(text=f"已跳转到坐标: ({x}, {z})")
                        return
            
            #坐标不在当前地图范围内或未加载地图，需要加载对应区域
            if not self.save_path:
                messagebox.showinfo("提示", "请先选择Minecraft存档")
                return
                
            #计算对应的区域坐标
            region_x = int(x // 512)
            region_z = int(z // 512)
            
            #查找对应的区域文件
            region_file = f"r.{region_x}.{region_z}.mca"
            region_path = os.path.join(self.save_path, "region", region_file)
            
            if os.path.exists(region_path):
                #询问用户是否要加载新区域
                if messagebox.askyesno("确认加载", 
                    f"坐标 ({x}, {z}) 不在当前渲染范围内。\n"
                    f"是否加载新区域 {region_file}？"):
                    
                    self.status_bar.config(text=f"正在加载区域 ({region_file})，目标坐标: X:{x}, Z:{z}...")
                    
                    #保存区域坐标信息，用于后续坐标转换
                    self.current_region = {"x": region_x, "z": region_z}
                    
                    #在后台线程中渲染地图
                    loading_thread = threading.Thread(
                        target=self.render_map_and_goto,
                        args=(region_path, x, z),
                        daemon=True
                    )
                    loading_thread.start()
            else:
                #如果找不到对应区域，则查找附近的区域
                nearby_regions = [
                    (region_x, region_z),  #目标区域（可能已经检查过）
                    (region_x+1, region_z), (region_x-1, region_z),
                    (region_x, region_z+1), (region_x, region_z-1),
                    (region_x+1, region_z+1), (region_x-1, region_z-1),
                    (region_x+1, region_z-1), (region_x-1, region_z+1)
                ]
                
                available_regions = []
                
                for rx, rz in nearby_regions:
                    nearby_file = f"r.{rx}.{rz}.mca"
                    nearby_path = os.path.join(self.save_path, "region", nearby_file)
                    
                    if os.path.exists(nearby_path):
                        #计算与目标的距离
                        distance = abs(rx - region_x) + abs(rz - region_z)
                        available_regions.append((rx, rz, nearby_path, distance))
                
                if available_regions:
                    #按照距离排序
                    available_regions.sort(key=lambda r: r[3])
                    
                    #提示用户选择区域
                    if len(available_regions) == 1:
                        rx, rz, region_path, _ = available_regions[0]
                        if messagebox.askyesno("确认加载", 
                            f"找不到坐标 ({x}, {z}) 所在的区域文件。\n"
                            f"是否加载附近区域 r.{rx}.{rz}.mca？"):
                            
                            self.status_bar.config(text=f"正在加载附近区域 (r.{rx}.{rz}.mca)...")
                            
                            #保存区域坐标信息
                            self.current_region = {"x": rx, "z": rz}
                            
                            #在后台线程中渲染地图
                            loading_thread = threading.Thread(
                                target=self.render_map_and_goto,
                                args=(region_path, x, z),
                                daemon=True
                            )
                            loading_thread.start()
                    else:
                        #有多个可用区域，创建选择对话框
                        region_window = tk.Toplevel(self.root)
                        region_window.title("选择区域")
                        region_window.geometry("300x250")
                        region_window.transient(self.root)
                        region_window.grab_set()
                        
                        ttk.Label(region_window, text=f"找不到坐标 ({x}, {z}) 所在的区域文件。\n请选择要加载的附近区域:").pack(pady=10)
                        
                        #创建列表框
                        region_list = tk.Listbox(region_window, height=8)
                        region_list.pack(fill=tk.BOTH, expand=True, padx=10, pady=5)
                        
                        #添加区域到列表
                        for i, (rx, rz, _, distance) in enumerate(available_regions):
                            region_list.insert(tk.END, f"r.{rx}.{rz}.mca (距离: {distance})")
                        
                        #选择第一个
                        region_list.selection_set(0)
                        
                        #按钮框架
                        btn_frame = ttk.Frame(region_window)
                        btn_frame.pack(fill=tk.X, padx=10, pady=10)
                        
                        def on_region_select():
                            #获取选择的索引
                            selection = region_list.curselection()
                            if selection:
                                idx = selection[0]
                                rx, rz, region_path, _ = available_regions[idx]
                                
                                self.status_bar.config(text=f"正在加载附近区域 (r.{rx}.{rz}.mca)...")
                                
                                #保存区域坐标信息
                                self.current_region = {"x": rx, "z": rz}
                                
                                #在后台线程中渲染地图
                                loading_thread = threading.Thread(
                                    target=self.render_map_and_goto,
                                    args=(region_path, x, z),
                                    daemon=True
                                )
                                loading_thread.start()
                                
                                region_window.destroy()
                        
                        ttk.Button(btn_frame, text="加载选择的区域", command=on_region_select).pack(side=tk.LEFT, padx=5)
                        ttk.Button(btn_frame, text="取消", command=region_window.destroy).pack(side=tk.RIGHT, padx=5)
                else:
                    messagebox.showinfo("提示", f"找不到坐标 ({x}, {z}) 附近的任何区域文件")
                    
        except ValueError:
            messagebox.showerror("错误", "请输入有效的整数坐标")
    
    def render_map_and_goto(self, region_path, target_x, target_z):
        """渲染地图并跳转到指定坐标"""
        try:
            #使用现有的处理函数渲染地图
            timestamp = int(time.time())
            temp_output = self.get_temp_file_path(f"temp_map_{timestamp}.png")
            
            #确保之前的临时文件已删除
            if os.path.exists(temp_output):
                try:
                    os.remove(temp_output)
                except:
                    pass
            
            #渲染区块
            self.root.after(0, lambda: self.status_bar.config(text=f"正在处理区域文件..."))
            top_blocks = a.get_top_blocks(region_path, max_workers=os.cpu_count())
            
            if top_blocks is not None:
                self.root.after(0, lambda: self.status_bar.config(text=f"正在渲染图片..."))
                a.direct_render_to_png(top_blocks, temp_output)
                
                #检查生成的图片是否存在且有效
                if not os.path.exists(temp_output):
                    self.root.after(0, lambda: messagebox.showerror("错误", "渲染失败：未生成图片文件"))
                    self.root.after(0, lambda: self.status_bar.config(text="渲染失败：未生成图片文件"))
                    return
                
                try:
                    #加载渲染好的图片并检查其有效性
                    self.map_image = Image.open(temp_output)
                    #检查图像大小是否合理
                    width, height = self.map_image.size
                    if width <= 0 or height <= 0:
                        self.root.after(0, lambda: messagebox.showerror("错误", f"渲染图片无效：尺寸异常 ({width}x{height})"))
                        self.root.after(0, lambda: self.status_bar.config(text="渲染图片无效"))
                        return
                    
                    #保存临时图片原始路径
                    self.temp_image_path = temp_output
                    
                    #更新Canvas显示
                    self.root.after(0, self.update_map_display)
                    
                    #启用渲染按钮
                    self.root.after(0, lambda: self.render_btn.config(state="normal"))
                    
                    #更新文件信息标签
                    self.root.after(0, lambda: self.file_info_label.config(
                        text=f"渲染文件位置:\n{temp_output}"
                    ))
                    
                    self.root.after(0, lambda: self.status_bar.config(text=f"地图渲染完成，图片尺寸: {width}x{height}"))
                    
                    #渲染完成后，跳转到目标坐标
                    self.root.after(100, lambda: self.goto_coords_on_current_map(target_x, target_z))
                    
                except Exception as img_error:
                    self.root.after(0, lambda: messagebox.showerror("错误", f"图片加载失败: {str(img_error)}"))
                    self.root.after(0, lambda: self.status_bar.config(text="图片加载失败"))
            else:
                self.root.after(0, lambda: self.status_bar.config(text="地图渲染失败：无法获取方块数据"))
        
        except Exception as e:
            self.root.after(0, lambda: messagebox.showerror("错误", f"渲染地图时出错: {str(e)}"))
            self.root.after(0, lambda: self.status_bar.config(text="地图渲染失败"))
    
    def goto_coords_on_current_map(self, x, z):
        """在当前加载的地图上跳转到指定坐标"""
        if not self.map_image:
            return
            
        try:
            #计算相对于地图的坐标
            if hasattr(self, 'render_base_coords'):
                base_x = self.render_base_coords["x"]
                base_z = self.render_base_coords["z"]
            else:
                base_x = self.current_region["x"] * 512
                base_z = self.current_region["z"] * 512
            
            #计算图像上的像素坐标
            img_width, img_height = self.map_image.size
            rel_x = x - base_x
            rel_z = z - base_z
            
            #如果坐标超出范围，不做标记
            if rel_x < 0 or rel_x >= img_width or rel_z < 0 or rel_z >= img_height:
                return
                
            #计算缩放后的坐标
            scaled_x = rel_x * self.map_scale
            scaled_z = rel_z * self.map_scale
            
            #设置Canvas的滚动位置，使目标坐标居中
            canvas_width = self.canvas.winfo_width()
            canvas_height = self.canvas.winfo_height()
            
            #计算滚动位置
            scroll_x = (scaled_x - canvas_width/2) / (img_width * self.map_scale)
            scroll_z = (scaled_z - canvas_height/2) / (img_height * self.map_scale)
            
            #确保滚动位置在有效范围内
            scroll_x = max(0, min(1, scroll_x))
            scroll_z = max(0, min(1, scroll_z))
            
            #设置滚动位置
            self.canvas.xview_moveto(scroll_x)
            self.canvas.yview_moveto(scroll_z)
            
            #在目标位置绘制一个标记
            mark_size = 10
            self.canvas.delete("marker")
            self.canvas.create_oval(
                scaled_x - mark_size, scaled_z - mark_size,
                scaled_x + mark_size, scaled_z + mark_size,
                outline="red", width=2, tags="marker"
            )
            self.canvas.create_text(
                scaled_x, scaled_z - mark_size - 5,
                text=f"({x}, {z})", fill="red", tags="marker"
            )
            
            self.status_bar.config(text=f"已跳转到坐标: ({x}, {z})")
        except Exception as e:
            print(f"跳转到坐标时出错: {e}")

    def get_selection_corners(self):
        """获取选择区域的对角坐标，包括高度信息"""
        if not hasattr(self, 'selected_game_coords') or not self.selected_game_coords["start"] or not self.selected_game_coords["end"]:
            messagebox.showinfo("提示", "请先在地图上选择一个区域")
            return
        
        try:
            #获取Y坐标范围
            min_y = int(self.min_y_entry.get())
            max_y = int(self.max_y_entry.get())
            
            #获取选择的游戏坐标
            start_x, start_z = self.selected_game_coords["start"]
            end_x, end_z = self.selected_game_coords["end"]
            
            #确保坐标有序 (起点坐标总是较小的那个)
            min_x = min(start_x, end_x)
            min_z = min(start_z, end_z)
            max_x = max(start_x, end_x)
            max_z = max(start_z, end_z)
            
            #创建对角坐标对
            corner1 = (min_x, min_y, min_z)  #第一个角落坐标
            corner2 = (max_x, max_y, max_z)  #对角坐标
            
            #显示结果对话框
            result_text = (
                f"选择区域的对角坐标:\n\n"
                f"角落1: X:{corner1[0]}, Y:{corner1[1]}, Z:{corner1[2]}\n"
                f"角落2: X:{corner2[0]}, Y:{corner2[1]}, Z:{corner2[2]}\n\n"
                f"区域大小: {max_x - min_x + 1} × {max_y - min_y + 1} × {max_z - min_z + 1} 方块"
            )
            
            #创建一个可复制文本的对话框
            result_window = tk.Toplevel(self.root)
            result_window.title("选区对角坐标")
            result_window.geometry("400x300")
            result_window.transient(self.root)
            result_window.grab_set()
            
            #显示结果文本
            result_label = ttk.Label(result_window, text=result_text, justify=tk.LEFT)
            result_label.pack(padx=20, pady=20)
            
            #创建可复制的文本框
            copy_frame = ttk.LabelFrame(result_window, text="复制坐标")
            copy_frame.pack(fill=tk.X, padx=20, pady=10)
            
            #坐标格式1 - 简洁格式
            ttk.Label(copy_frame, text="简洁格式:").pack(anchor=tk.W, padx=5, pady=2)
            simple_text = f"{corner1[0]} {corner1[1]} {corner1[2]} {corner2[0]} {corner2[1]} {corner2[2]}"
            simple_entry = ttk.Entry(copy_frame, width=50)
            simple_entry.pack(fill=tk.X, padx=5, pady=2)
            simple_entry.insert(0, simple_text)
            simple_entry.configure(state="readonly")
            
            #坐标格式2 - JSON格式
            ttk.Label(copy_frame, text="JSON格式:").pack(anchor=tk.W, padx=5, pady=2)
            json_text = json.dumps({"corner1": corner1, "corner2": corner2})
            json_entry = ttk.Entry(copy_frame, width=50)
            json_entry.pack(fill=tk.X, padx=5, pady=2)
            json_entry.insert(0, json_text)
            json_entry.configure(state="readonly")
            
            #复制按钮
            def copy_simple():
                result_window.clipboard_clear()
                result_window.clipboard_append(simple_text)
                self.status_bar.config(text="简洁格式坐标已复制到剪贴板")
            
            def copy_json():
                result_window.clipboard_clear()
                result_window.clipboard_append(json_text)
                self.status_bar.config(text="JSON格式坐标已复制到剪贴板")
            
            button_frame = ttk.Frame(copy_frame)
            button_frame.pack(fill=tk.X, padx=5, pady=5)
            ttk.Button(button_frame, text="复制简洁格式", command=copy_simple).pack(side=tk.LEFT, padx=5)
            ttk.Button(button_frame, text="复制JSON格式", command=copy_json).pack(side=tk.LEFT, padx=5)
            
            #关闭按钮
            ttk.Button(result_window, text="关闭", command=result_window.destroy).pack(pady=10)
            
            #更新状态栏
            self.status_bar.config(text=f"已获取选区对角坐标: ({corner1[0]},{corner1[1]},{corner1[2]}) 到 ({corner2[0]},{corner2[1]},{corner2[2]})")
            
            #选区中显示坐标
            self.show_corner_markers(corner1, corner2)
            
            return corner1, corner2
            
        except ValueError:
            messagebox.showerror("错误", "无效的Y坐标值，请输入整数")
            return None
    
    def show_corner_markers(self, corner1, corner2):
        """在地图上显示角落标记"""
        if not self.map_image:
            return
        
        #计算相对于地图的坐标
        if hasattr(self, 'render_base_coords'):
            base_x = self.render_base_coords["x"]
            base_z = self.render_base_coords["z"]
        else:
            base_x = self.current_region["x"] * 512
            base_z = self.current_region["z"] * 512
        
        #计算图像上的像素坐标
        corner1_x = corner1[0] - base_x
        corner1_z = corner1[2] - base_z
        corner2_x = corner2[0] - base_x
        corner2_z = corner2[2] - base_z
        
        #缩放坐标
        scaled_x1 = corner1_x * self.map_scale
        scaled_z1 = corner1_z * self.map_scale
        scaled_x2 = corner2_x * self.map_scale
        scaled_z2 = corner2_z * self.map_scale
        
        #删除之前的标记
        self.canvas.delete("corner_markers")
        
        #标记角落1
        mark_size = 8
        self.canvas.create_oval(
            scaled_x1 - mark_size, scaled_z1 - mark_size,
            scaled_x1 + mark_size, scaled_z1 + mark_size,
            outline="blue", width=2, tags="corner_markers"
        )
        self.canvas.create_text(
            scaled_x1, scaled_z1 - mark_size - 5,
            text=f"角落1: ({corner1[0]},{corner1[1]},{corner1[2]})", 
            fill="blue", tags="corner_markers"
        )
        
        #标记角落2
        self.canvas.create_oval(
            scaled_x2 - mark_size, scaled_z2 - mark_size,
            scaled_x2 + mark_size, scaled_z2 + mark_size,
            outline="green", width=2, tags="corner_markers"
        )
        self.canvas.create_text(
            scaled_x2, scaled_z2 - mark_size - 5,
            text=f"角落2: ({corner2[0]},{corner2[1]},{corner2[2]})", 
            fill="green", tags="corner_markers"
        )
        
        #绘制选择框
        self.canvas.create_rectangle(
            scaled_x1, scaled_z1, scaled_x2, scaled_z2,
            outline="purple", width=2, dash=(5, 3), tags="corner_markers"
        )

    def select_multiple_mca(self):
        """选择多个MCA文件进行渲染"""
        #打开文件对话框选择多个MCA文件
        files = filedialog.askopenfilenames(
            title="选择MCA文件",
            filetypes=[("MCA文件", "*.mca"), ("所有文件", "*.*")]
        )
        
        if not files:
            return
            
        #保存选择的文件
        self.selected_mca_files = list(files)
        
        #更新状态
        self.save_info.config(text=f"已选择 {len(self.selected_mca_files)} 个MCA文件")
        
        #如果选择了文件，启用渲染按钮
        self.render_btn.config(state="normal")
        
        #更新状态栏
        self.status_bar.config(text=f"已选择 {len(self.selected_mca_files)} 个MCA文件，可以开始渲染")
        
        #设置渲染按钮文本
        self.render_btn.config(text=f"渲染选择的 {len(self.selected_mca_files)} 个区域文件")
        
    def render_selected_mca_files(self):
        """渲染用户选择的多个MCA文件"""
        if not hasattr(self, 'selected_mca_files') or not self.selected_mca_files:
            messagebox.showinfo("提示", "请先选择MCA文件")
            return
            
        mca_files = self.selected_mca_files
        
        #计算采样间隔
        file_count = len(mca_files)
        sample_interval = self.get_lod_sample_interval(file_count)
        
        #显示确认对话框，附带采样精度信息
        confirm_message = f"将渲染选择的 {file_count} 个MCA文件\n"
        
        #如果有已知的边界信息，显示方块范围
        if hasattr(self, 'selected_region_bounds'):
            bounds = self.selected_region_bounds
            confirm_message += f"渲染区域: ({bounds['min_x']},{bounds['min_z']}) 至 ({bounds['max_x']},{bounds['max_z']})\n"
            
        if sample_interval > 1:
            confirm_message += f"采样间隔设置为 {sample_interval}（每{sample_interval}个方块采样1次）\n"
        
        #添加颜色提取信息
        if self.extract_colors_var.get():
            confirm_message += "将从Minecraft JAR文件中提取方块颜色\n"
        else:
            confirm_message += "将使用内置方块颜色\n"
            
        #添加模组颜色提取信息
        if self.extract_mods_var.get():
            confirm_message += "将从Mods文件夹提取模组方块颜色\n"
        
        confirm_message += "这可能需要一些时间，是否继续？"
        
        confirm = messagebox.askyesno("确认渲染", confirm_message)
        
        if not confirm:
            return
        
        #解析MCA文件名以获取区域坐标，用于图像合并
        region_coords = []
        for mca_file in mca_files:
            filename = os.path.basename(mca_file)
            if filename.startswith('r.') and filename.endswith('.mca'):
                try:
                    #提取坐标，格式为 r.x.z.mca
                    parts = filename.split('.')
                    if len(parts) == 4:
                        rx = int(parts[1])
                        rz = int(parts[2])
                        region_coords.append((rx, rz, mca_file))
                except ValueError:
                    messagebox.showwarning(
                        "警告", 
                        f"无法解析文件名坐标: {filename}"
                    )
            else:
                messagebox.showwarning(
                    "警告", 
                    f"文件名格式不符合 r.x.z.mca: {filename}"
                )
        
        if not region_coords:
            messagebox.showinfo("提示", "没有找到有效的MCA文件")
            return
        
        #计算渲染区域的边界
        min_region_x = min(rx for rx, rz, _ in region_coords)
        max_region_x = max(rx for rx, rz, _ in region_coords)
        min_region_z = min(rz for rx, rz, _ in region_coords)
        max_region_z = max(rz for rx, rz, _ in region_coords)
        
        #计算方块坐标范围
        min_x = min_region_x * 512
        max_x = (max_region_x + 1) * 512 - 1
        min_z = min_region_z * 512
        max_z = (max_region_z + 1) * 512 - 1
        
        #显示进度条窗口
        progress_window = tk.Toplevel(self.root)
        progress_window.title("渲染进度")
        progress_window.geometry("400x150")
        progress_window.transient(self.root)
        progress_window.grab_set()
        
        #设置进度显示
        ttk.Label(progress_window, text="正在渲染选择的MCA文件...").pack(pady=10)
        progress_var = tk.DoubleVar()
        progress_bar = ttk.Progressbar(progress_window, variable=progress_var, maximum=len(region_coords))
        progress_bar.pack(fill=tk.X, padx=20, pady=10)
        progress_label = ttk.Label(progress_window, text="准备中...")
        progress_label.pack(pady=10)
        
        #启动渲染线程
        def render_thread():
            try:
                #准备渲染结果
                total_width = (max_region_x - min_region_x + 1) * 512
                total_height = (max_region_z - min_region_z + 1) * 512
                
                #检查合并图像尺寸是否合理
                if total_width <= 0 or total_height <= 0 or total_width > 10000 or total_height > 10000:
                    progress_window.after(0, lambda: messagebox.showerror(
                        "错误", 
                        f"无效的图像尺寸: {total_width}x{total_height}，请选择更小区域的MCA文件"
                    ))
                    progress_window.after(0, progress_window.destroy)
                    return
                
                #创建合并图像（使用RGBA模式以支持透明度）
                combined_image = Image.new('RGBA', (total_width, total_height), (255, 255, 255, 0))
                
                #记录渲染区域的基准坐标
                self.render_base_coords = {
                    "x": min_region_x * 512,
                    "z": min_region_z * 512
                }
                
                #记录采样间隔，用于后续处理
                self.sample_interval = sample_interval
                
                #处理每个MCA文件
                for i, (rx, rz, mca_file) in enumerate(region_coords):
                    progress_window.after(0, lambda i=i: progress_var.set(i))
                    progress_window.after(0, lambda rx=rx, rz=rz, i=i: progress_label.config(
                        text=f"渲染区域 {i+1}/{len(region_coords)}: r.{rx}.{rz}.mca"
                    ))
                    
                    #渲染当前区域 - 使用临时文件路径
                    temp_output = self.get_temp_file_path(f"temp_region_{rx}_{rz}.png")
                    
                    #清理之前的临时文件
                    if os.path.exists(temp_output):
                        try:
                            os.remove(temp_output)
                        except:
                            pass
                    
                    #控制颜色提取功能
                    if not self.extract_colors_var.get():
                        os.environ['DISABLE_COLOR_EXTRACTION'] = '1'
                    else:
                        os.environ.pop('DISABLE_COLOR_EXTRACTION', None)
                    
                    #控制模组颜色提取功能
                    if not self.extract_mods_var.get():
                        os.environ['DISABLE_MODS_EXTRACTION'] = '1'
                    else:
                        os.environ.pop('DISABLE_MODS_EXTRACTION', None)
                    
                    #控制颜色缓存功能
                    if not self.use_color_cache_var.get():
                        os.environ['DISABLE_COLOR_CACHE'] = '1'
                    else:
                        os.environ.pop('DISABLE_COLOR_CACHE', None)
                    
                    #渲染区块，传递采样间隔参数
                    top_blocks = a.get_top_blocks(mca_file, max_workers=os.cpu_count(), sample_interval=sample_interval)
                    
                    if top_blocks is not None:
                        #使用direct_render_to_png函数渲染图片，传递采样间隔参数
                        render_success = a.direct_render_to_png(top_blocks, temp_output, sample_interval=sample_interval)
                        
                        if render_success and os.path.exists(temp_output):
                            try:
                                #加载渲染好的区域图像
                                region_img = Image.open(temp_output)
                                
                                #检查区域图像尺寸
                                if region_img.size[0] <= 0 or region_img.size[1] <= 0:
                                    progress_window.after(0, lambda rx=rx, rz=rz: messagebox.showwarning(
                                        "警告", 
                                        f"区域 r.{rx}.{rz}.mca 渲染图像尺寸无效: {region_img.size[0]}x{region_img.size[1]}"
                                    ))
                                    continue
                                
                                #计算此区域图像在合并图像中的位置
                                pos_x = (rx - min_region_x) * 512
                                pos_z = (rz - min_region_z) * 512
                                
                                #粘贴到合并图像上
                                combined_image.paste(region_img, (pos_x, pos_z))
                            except Exception as img_error:
                                progress_window.after(0, lambda rx=rx, rz=rz, e=str(img_error): messagebox.showwarning(
                                    "警告", 
                                    f"无法处理区域 r.{rx}.{rz}.mca 的图像: {e}"
                                ))
                        else:
                            progress_window.after(0, lambda rx=rx, rz=rz: messagebox.showwarning(
                                "警告", 
                                f"无法渲染区域 r.{rx}.{rz}.mca"
                            ))
                    else:
                        progress_window.after(0, lambda rx=rx, rz=rz: messagebox.showwarning(
                            "警告", 
                            f"无法读取区域 r.{rx}.{rz}.mca 的方块数据"
                        ))
                    
                    #清理临时文件
                    try:
                        if os.path.exists(temp_output):
                            os.remove(temp_output)
                    except:
                        pass
                
                #检查合并图像是否包含有效数据
                if combined_image.getbbox() is None:
                    progress_window.after(0, lambda: messagebox.showerror(
                        "错误", 
                        "渲染失败：合并后的图像为空"
                    ))
                    progress_window.after(0, progress_window.destroy)
                    return
                
                #保存合并后的图像到临时文件夹
                timestamp = int(time.time())
                output_file = self.get_temp_file_path(f"combined_map_{timestamp}.png")
                combined_image.save(output_file)
                
                #保存坐标信息到临时文件夹
                json_output = self.get_temp_file_path(f"map_coords_{timestamp}.json")
                coords_data = {
                    "timestamp": timestamp,
                    "image_size": combined_image.size,
                    "base_coords": self.render_base_coords,
                    "region_bounds": {
                        "min_rx": min_region_x,
                        "max_rx": max_region_x,
                        "min_rz": min_region_z,
                        "max_rz": max_region_z
                    },
                    "block_bounds": {
                        "min_x": self.render_base_coords["x"],
                        "min_z": self.render_base_coords["z"],
                        "max_x": self.render_base_coords["x"] + total_width,
                        "max_z": self.render_base_coords["z"] + total_height
                    },
                    "sample_interval": sample_interval,
                    "selection": self.selected_game_coords
                }
                
                with open(json_output, 'w', encoding='utf-8') as f:
                    json.dump(coords_data, f, indent=2)
                
                #检查保存的图像是否存在
                if not os.path.exists(output_file):
                    progress_window.after(0, lambda: messagebox.showerror(
                        "错误", 
                        "保存图像失败"
                    ))
                    progress_window.after(0, progress_window.destroy)
                    return
                
                #更新GUI显示
                self.map_image = combined_image
                self.current_output_file = output_file
                self.current_json_file = json_output
                progress_window.after(0, self.update_map_display)
                progress_window.after(0, lambda: self.status_bar.config(text=f"已渲染选定区域，保存为: {output_file}"))
                
                #更新选择区域标签 - 使用实际方块坐标范围
                progress_window.after(0, lambda: self.region_label.config(
                    text=f"渲染区域: ({self.render_base_coords['x']}, {self.render_base_coords['z']}) 到 "
                         f"({self.render_base_coords['x'] + total_width}, {self.render_base_coords['z'] + total_height})"
                ))
                
                #更新文件信息标签
                progress_window.after(0, lambda: self.file_info_label.config(
                    text=f"图像文件:\n{output_file}\n\n坐标文件:\n{json_output}"
                ))
                
                #关闭进度窗口
                progress_window.after(500, progress_window.destroy)
                
            except Exception as e:
                progress_window.after(0, lambda: messagebox.showerror("错误", f"渲染区域时出错: {str(e)}"))
                progress_window.after(0, progress_window.destroy)
        
        #启动渲染线程
        threading.Thread(target=render_thread, daemon=True).start()
    
    def get_lod_sample_interval(self, file_count=None):
        """根据LOD设置和文件数量返回适当的采样间隔"""
        lod_setting = self.lod_var.get()
        
        #检查是否是手动设置的LOD
        if lod_setting.startswith("1 ("):
            return 1
        elif lod_setting.startswith("2 ("):
            return 2
        elif lod_setting.startswith("4 ("):
            return 4
        elif lod_setting.startswith("8 ("):
            return 8
        
        #自动模式，根据文件数量计算
        if file_count is None:
            file_count = 1
            
        #根据文件数量设置采样间隔
        if file_count >= 16:
            return 8  #每8个方块采样1次
        elif file_count >= 8:
            return 4  #每4个方块采样1次
        elif file_count >= 4:
            return 2  #每2个方块采样1次
        else:
            return 1  #默认精度，每个方块都采样

    def select_mca_from_save(self):
        """从当前选择的存档中选择MCA文件"""
        if not self.save_path:
            messagebox.showinfo("提示", "请先选择Minecraft存档")
            return
            
        #确定当前维度的region目录
        if self.current_dimension in self.dimension_paths:
            region_dir = os.path.join(self.save_path, self.dimension_paths[self.current_dimension])
            
            #获取维度显示名称
            dimension_display = next((d for d in self.available_dimensions if d.startswith(self.current_dimension)), self.current_dimension)
        else:
            region_dir = os.path.join(self.save_path, "region")
            dimension_display = "主世界"
            
        if not os.path.exists(region_dir):
            messagebox.showinfo("提示", f"存档中没有找到{dimension_display}的region目录: {region_dir}")
            return
            
        #获取所有MCA文件
        mca_files = [f for f in os.listdir(region_dir) if f.startswith("r.") and f.endswith(".mca")]
        
        if not mca_files:
            messagebox.showinfo("提示", f"在{dimension_display}的region目录中未找到MCA文件")
            return
            
        #创建MCA文件选择对话框
        mca_window = tk.Toplevel(self.root)
        mca_window.title(f"选择{dimension_display} MCA文件")
        mca_window.geometry("500x400")
        mca_window.transient(self.root)
        mca_window.grab_set()
        
        #添加说明标签
        ttk.Label(mca_window, text=f"从存档 {os.path.basename(self.save_path)} 的{dimension_display}中选择要渲染的MCA文件：\n"
                                   f"已找到 {len(mca_files)} 个MCA文件", justify=tk.LEFT).pack(padx=10, pady=10)
        
        #创建框架容纳列表和滚动条
        list_frame = ttk.Frame(mca_window)
        list_frame.pack(fill=tk.BOTH, expand=True, padx=10, pady=5)
        
        #创建滚动条
        scrollbar = ttk.Scrollbar(list_frame)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        
        #创建Listbox用于选择MCA文件，允许多选
        mca_listbox = tk.Listbox(list_frame, selectmode=tk.MULTIPLE, yscrollcommand=scrollbar.set)
        mca_listbox.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scrollbar.config(command=mca_listbox.yview)
        
        #添加搜索框
        search_frame = ttk.Frame(mca_window)
        search_frame.pack(fill=tk.X, padx=10, pady=5)
        
        ttk.Label(search_frame, text="搜索:").pack(side=tk.LEFT)
        search_var = tk.StringVar()
        search_entry = ttk.Entry(search_frame, textvariable=search_var)
        search_entry.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=5)
        
        #全选和全不选按钮
        select_frame = ttk.Frame(mca_window)
        select_frame.pack(fill=tk.X, padx=10, pady=5)
        
        def select_all():
            mca_listbox.select_set(0, tk.END)
            
        def deselect_all():
            mca_listbox.selection_clear(0, tk.END)
            
        ttk.Button(select_frame, text="全选", command=select_all).pack(side=tk.LEFT, padx=5)
        ttk.Button(select_frame, text="全不选", command=deselect_all).pack(side=tk.LEFT, padx=5)
        
        #添加按坐标范围选择按钮
        range_button = ttk.Button(select_frame, text="按坐标范围选择", command=lambda: select_by_range())
        range_button.pack(side=tk.LEFT, padx=(20, 5))
        
        def select_by_range():
            #创建按坐标范围选择的对话框
            range_window = tk.Toplevel(mca_window)
            range_window.title("按坐标范围选择")
            range_window.geometry("300x200")
            range_window.transient(mca_window)
            range_window.grab_set()
            
            #添加说明
            ttk.Label(range_window, text="请输入区域坐标范围：", justify=tk.LEFT).pack(padx=10, pady=10)
            
            #创建坐标输入框
            coords_frame = ttk.Frame(range_window)
            coords_frame.pack(fill=tk.X, padx=10, pady=5)
            
            #X坐标范围
            x_frame = ttk.Frame(coords_frame)
            x_frame.pack(fill=tk.X, pady=5)
            ttk.Label(x_frame, text="X 范围:").pack(side=tk.LEFT)
            x_min_var = tk.StringVar()
            x_min_entry = ttk.Entry(x_frame, width=6, textvariable=x_min_var)
            x_min_entry.pack(side=tk.LEFT, padx=5)
            ttk.Label(x_frame, text="至").pack(side=tk.LEFT)
            x_max_var = tk.StringVar()
            x_max_entry = ttk.Entry(x_frame, width=6, textvariable=x_max_var)
            x_max_entry.pack(side=tk.LEFT, padx=5)
            
            #Z坐标范围
            z_frame = ttk.Frame(coords_frame)
            z_frame.pack(fill=tk.X, pady=5)
            ttk.Label(z_frame, text="Z 范围:").pack(side=tk.LEFT)
            z_min_var = tk.StringVar()
            z_min_entry = ttk.Entry(z_frame, width=6, textvariable=z_min_var)
            z_min_entry.pack(side=tk.LEFT, padx=5)
            ttk.Label(z_frame, text="至").pack(side=tk.LEFT)
            z_max_var = tk.StringVar()
            z_max_entry = ttk.Entry(z_frame, width=6, textvariable=z_max_var)
            z_max_entry.pack(side=tk.LEFT, padx=5)
            
            #如果有玩家位置，填入默认值（以玩家为中心的3x3区域）
            if self.player_pos:
                player_rx = int(self.player_pos[0] // 512)
                player_rz = int(self.player_pos[2] // 512)
                x_min_var.set(str(player_rx - 1))
                x_max_var.set(str(player_rx + 1))
                z_min_var.set(str(player_rz - 1))
                z_max_var.set(str(player_rz + 1))
            
            #按钮框架
            btn_frame = ttk.Frame(range_window)
            btn_frame.pack(fill=tk.X, padx=10, pady=10)
            
            def apply_range():
                try:
                    #获取坐标范围
                    x_min = int(x_min_var.get())
                    x_max = int(x_max_var.get())
                    z_min = int(z_min_var.get())
                    z_max = int(z_max_var.get())
                    
                    #确保最小值小于最大值
                    if x_min > x_max:
                        x_min, x_max = x_max, x_min
                    if z_min > z_max:
                        z_min, z_max = z_max, z_min
                    
                    #清除当前选择
                    mca_listbox.selection_clear(0, tk.END)
                    
                    #选择范围内的文件
                    selected_count = 0
                    for i in range(mca_listbox.size()):
                        #从显示文本中提取文件名
                        display_text = mca_listbox.get(i)
                        filename = display_text.split(' - ')[0]
                        
                        #解析文件名中的坐标
                        try:
                            parts = filename.split('.')
                            if len(parts) == 4:
                                rx, rz = int(parts[1]), int(parts[2])
                                
                                #检查是否在范围内
                                if x_min <= rx <= x_max and z_min <= rz <= z_max:
                                    mca_listbox.selection_set(i)
                                    selected_count += 1
                        except:
                            pass
                    
                    #提示选择结果
                    messagebox.showinfo("选择结果", f"已选择 {selected_count} 个文件")
                    
                    #关闭对话框
                    range_window.destroy()
                    
                except ValueError:
                    messagebox.showerror("错误", "请输入有效的整数坐标")
            
            ttk.Button(btn_frame, text="应用", command=apply_range).pack(side=tk.RIGHT, padx=5)
            ttk.Button(btn_frame, text="取消", command=range_window.destroy).pack(side=tk.RIGHT, padx=5)
            
            #设置初始焦点
            if not self.player_pos:
                x_min_entry.focus_set()
        
        #排序选项
        sort_var = tk.StringVar(value="文件名")
        sort_options = ["文件名", "修改时间（最新）", "修改时间（最旧）", "坐标（距玩家近）"]
        ttk.Label(select_frame, text="排序:").pack(side=tk.LEFT, padx=(20, 5))
        sort_dropdown = ttk.Combobox(select_frame, textvariable=sort_var, values=sort_options, width=15, state="readonly")
        sort_dropdown.pack(side=tk.LEFT, padx=5)
        
        #处理MCA文件列表
        mca_file_info = []
        player_pos = self.player_pos if hasattr(self, 'player_pos') else None
        
        for filename in mca_files:
            filepath = os.path.join(region_dir, filename)
            mod_time = os.path.getmtime(filepath)
            
            #解析区域坐标
            rx, rz = 0, 0
            try:
                parts = filename.split('.')
                if len(parts) == 4:
                    rx, rz = int(parts[1]), int(parts[2])
            except:
                pass
                
            #计算与玩家的距离
            distance = float('inf')
            if player_pos:
                #玩家坐标除以512得到区域坐标
                player_rx = int(player_pos[0] // 512)
                player_rz = int(player_pos[2] // 512)
                distance = ((rx - player_rx) ** 2 + (rz - player_rz) ** 2) ** 0.5
                
            mca_file_info.append({
                'filename': filename,
                'filepath': filepath,
                'mod_time': mod_time,
                'rx': rx,
                'rz': rz,
                'distance': distance
            })
            
        #更新列表显示
        def update_list(*args):
            #获取搜索词
            search_text = search_var.get().lower()
            
            #获取排序方式
            sort_method = sort_var.get()
            
            #排序文件列表
            if sort_method == "文件名":
                sorted_files = sorted(mca_file_info, key=lambda x: x['filename'])
            elif sort_method == "修改时间（最新）":
                sorted_files = sorted(mca_file_info, key=lambda x: x['mod_time'], reverse=True)
            elif sort_method == "修改时间（最旧）":
                sorted_files = sorted(mca_file_info, key=lambda x: x['mod_time'])
            elif sort_method == "坐标（距玩家近）":
                sorted_files = sorted(mca_file_info, key=lambda x: x['distance'])
                
            #清空列表
            mca_listbox.delete(0, tk.END)
            
            #填充列表
            for file_info in sorted_files:
                filename = file_info['filename']
                
                #搜索过滤
                if search_text and search_text not in filename.lower():
                    continue
                    
                #创建显示文本
                if sort_method == "修改时间（最新）" or sort_method == "修改时间（最旧）":
                    mod_time_str = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(file_info['mod_time']))
                    display_text = f"{filename} - {mod_time_str}"
                elif sort_method == "坐标（距玩家近）" and player_pos:
                    display_text = f"{filename} - 距离: {file_info['distance']:.1f}"
                else:
                    display_text = filename
                    
                mca_listbox.insert(tk.END, display_text)
                
        #绑定搜索和排序事件
        search_var.trace("w", update_list)
        sort_var.trace("w", update_list)
        
        #初始化列表
        update_list()
        
        #按钮框架
        button_frame = ttk.Frame(mca_window)
        button_frame.pack(fill=tk.X, padx=10, pady=10)
        
        def confirm_selection():
            #获取选中的索引
            selected_indices = mca_listbox.curselection()
            if not selected_indices:
                messagebox.showinfo("提示", "请至少选择一个MCA文件")
                return
                
            #获取选中的文件路径和坐标
            selected_files = []
            region_coords = []
            
            for i in selected_indices:
                #从显示文本中提取文件名
                display_text = mca_listbox.get(i)
                filename = display_text.split(' - ')[0]
                
                #解析区域坐标
                try:
                    parts = filename.split('.')
                    if len(parts) == 4:
                        rx = int(parts[1])
                        rz = int(parts[2])
                        region_coords.append((rx, rz))
                except:
                    pass
                
                #构建完整路径
                filepath = os.path.join(region_dir, filename)
                selected_files.append(filepath)
                
            #计算选择区域的边界
            if region_coords:
                min_rx = min(rx for rx, _ in region_coords)
                max_rx = max(rx for rx, _ in region_coords)
                min_rz = min(rz for _, rz in region_coords)
                max_rz = max(rz for _, rz in region_coords)
                
                #计算方块坐标范围（每个区域是512x512方块）
                min_x = min_rx * 512
                max_x = (max_rx + 1) * 512 - 1
                min_z = min_rz * 512
                max_z = (max_rz + 1) * 512 - 1
                
                #计算选择区域大小
                width_regions = max_rx - min_rx + 1
                height_regions = max_rz - min_rz + 1
                total_blocks = (max_x - min_x + 1) * (max_z - min_z + 1)
            
            #保存选择的文件
            self.selected_mca_files = selected_files
            
            #添加区域信息到存档信息标签
            region_info = f"已选择 {len(self.selected_mca_files)} 个MCA文件 (从 {os.path.basename(self.save_path)})"
            if region_coords:
                region_info += f"\n区域范围: ({min_rx},{min_rz}) 至 ({max_rx},{max_rz}) ({width_regions}×{height_regions} 个区域)"
                region_info += f"\n方块范围: ({min_x},{min_z}) 至 ({max_x},{max_z}) (约 {total_blocks//1000000}M 方块)"
            
            #更新状态
            self.save_info.config(text=region_info)
            
            #保存区域范围信息以供渲染使用
            if region_coords:
                self.selected_region_bounds = {
                    "min_rx": min_rx,
                    "max_rx": max_rx,
                    "min_rz": min_rz,
                    "max_rz": max_rz,
                    "min_x": min_x,
                    "max_x": max_x,
                    "min_z": min_z,
                    "max_z": max_z
                }
            
            #如果选择了文件，启用渲染按钮
            self.render_btn.config(state="normal")
            
            #更新状态栏
            self.status_bar.config(text=f"已选择 {len(self.selected_mca_files)} 个MCA文件，可以开始渲染")
            
            #设置渲染按钮文本
            self.render_btn.config(text=f"渲染选择的 {len(self.selected_mca_files)} 个区域文件")
            
            #关闭对话框
            mca_window.destroy()
            
        ttk.Button(button_frame, text="确认选择", command=confirm_selection).pack(side=tk.RIGHT, padx=5)
        ttk.Button(button_frame, text="取消", command=mca_window.destroy).pack(side=tk.RIGHT, padx=5)
        
        #设置初始焦点
        search_entry.focus_set()

    #获取临时文件路径
    def get_temp_file_path(self, filename):
        """获取Windows临时文件夹中的文件路径"""
        temp_dir = os.environ.get('TEMP') or os.environ.get('TMP') or '.'
        #确保临时目录存在
        if not os.path.exists(temp_dir):
            print(f"临时目录不存在，尝试创建: {temp_dir}")
            try:
                os.makedirs(temp_dir, exist_ok=True)
            except Exception as e:
                print(f"创建临时目录失败: {str(e)}")
                #如果无法创建临时目录，使用当前目录
                temp_dir = '.'
        
        return os.path.join(temp_dir, filename)

    def auto_detect_jar_file(self, debug=False):
        """尝试自动检测Minecraft JAR文件位置"""
        #如果没有选择存档，使用默认路径检查
        if not self.save_path:
            if debug:
                print("未选择存档路径，将使用默认路径检查")
            minecraft_dir = os.path.join(os.environ.get('APPDATA', ''), ".minecraft")
            versions_dir = os.path.join(minecraft_dir, "versions")
            
            #可能的版本目录
            possible_versions = []
            if os.path.exists(versions_dir):
                #检查版本目录下的所有子目录
                for version in os.listdir(versions_dir):
                    version_dir = os.path.join(versions_dir, version)
                    jar_file = os.path.join(version_dir, f"{version}.jar")
                    if os.path.exists(jar_file) and os.path.isfile(jar_file):
                        possible_versions.append((jar_file, os.path.getmtime(jar_file)))
            
            #如果找到了一些版本JAR文件，按修改时间排序，选择最新的
            if possible_versions:
                possible_versions.sort(key=lambda x: x[1], reverse=True)  #按修改时间降序排序
                jar_path = possible_versions[0][0]  #选择最新的JAR文件
                
                #设置环境变量
                os.environ['MINECRAFT_JAR_PATH'] = jar_path
                print(f"自动检测到JAR文件: {jar_path}")
                
                #更新JAR路径标签
                if hasattr(self, 'jar_path_label'):
                    self.jar_path_label.config(text=f"JAR文件: {jar_path}")
                    
                return True
        else:
            #根据存档路径直接推导JAR文件位置
            save_path = os.path.normpath(self.save_path)  #规范化路径
            if debug:
                print(f"当前存档路径: {save_path}")
            
            #1. 直接检查选择的路径是否指向Minecraft游戏目录
            #例如: C:\Users\LowH\Desktop\Desktop\applk\.minecraft\versions\1.19.2录制与生存
            parts = save_path.split(os.sep)
            
            #检查路径中是否包含'versions'
            if 'versions' in parts:
                versions_index = parts.index('versions')
                if debug:
                    print(f"检测到路径中包含'versions'，索引为: {versions_index}")
                
                #如果'versions'后面有部分，那可能是版本名称
                if versions_index < len(parts) - 1:
                    version_name = parts[versions_index + 1]
                    if debug:
                        print(f"从路径中推断的版本名称: {version_name}")
                    
                    #构建JAR文件路径
                    version_dir = os.sep.join(parts[:versions_index+2])
                    jar_path = os.path.join(version_dir, f"{version_name}.jar")
                    
                    if debug:
                        print(f"尝试查找JAR文件: {jar_path}")
                    
                    if os.path.exists(jar_path) and os.path.isfile(jar_path):
                        #找到有效的JAR文件
                        os.environ['MINECRAFT_JAR_PATH'] = jar_path
                        print(f"直接从路径找到JAR文件: {jar_path}")
                        
                        #更新JAR路径标签
                        if hasattr(self, 'jar_path_label'):
                            self.jar_path_label.config(text=f"JAR文件: {jar_path}")
                        
                        return True
            
            #2. 查找'saves'的位置
            parts = save_path.split(os.sep)
            if 'saves' in parts:
                saves_index = parts.index('saves')
                if debug:
                    print(f"在路径中找到'saves'，索引为: {saves_index}")
                
                #'saves'的前一个部分可能是版本名或者父目录
                if saves_index > 0:
                    #向上查找versions目录
                    for i in range(saves_index, 0, -1):
                        if parts[i-1] == 'versions' and i < len(parts)-1:
                            #版本名是'versions'的下一个部分
                            version_name = parts[i]
                            #构建版本目录
                            version_dir = os.sep.join(parts[:i+1])
                            #构建JAR文件路径
                            jar_path = os.path.join(version_dir, f"{version_name}.jar")
                            
                            if debug:
                                print(f"尝试查找JAR文件: {jar_path}")
                            
                            if os.path.exists(jar_path) and os.path.isfile(jar_path):
                                #找到有效的JAR文件
                                os.environ['MINECRAFT_JAR_PATH'] = jar_path
                                print(f"根据存档路径找到JAR文件: {jar_path}")
                                
                                #更新JAR路径标签
                                if hasattr(self, 'jar_path_label'):
                                    self.jar_path_label.config(text=f"JAR文件: {jar_path}")
                                
                                return True
            
            #3. 备选方案：向上搜索
            #先找到saves目录
            save_dir = os.path.dirname(save_path)  #存档所在目录
            
            if debug:
                print(f"检查存档父目录: {save_dir}")
            
            #检查这个目录是否是saves
            if os.path.basename(save_dir) == 'saves':
                #找到saves目录，再往上一级是可能包含版本信息的目录
                parent_dir = os.path.dirname(save_dir)
                version_name = os.path.basename(parent_dir)
                
                if debug:
                    print(f"找到saves目录，上级目录为: {parent_dir}，可能的版本名: {version_name}")
                
                #如果父目录可能是版本目录，尝试查找对应的JAR文件
                jar_path = os.path.join(parent_dir, f"{version_name}.jar")
                
                if debug:
                    print(f"尝试查找JAR文件: {jar_path}")
                
                if os.path.exists(jar_path) and os.path.isfile(jar_path):
                    #找到有效的JAR文件
                    os.environ['MINECRAFT_JAR_PATH'] = jar_path
                    print(f"根据存档路径找到JAR文件: {jar_path}")
                    
                    #更新JAR路径标签
                    if hasattr(self, 'jar_path_label'):
                        self.jar_path_label.config(text=f"JAR文件: {jar_path}")
                    
                    return True
            
            #4. 如果以上方法都失败，尝试从.minecraft/versions中查找
            if debug:
                print("尝试在.minecraft/versions中查找可能的JAR文件")
            
            #尝试猜测.minecraft路径
            minecraft_dir = None
            for i in range(len(parts)):
                if parts[i].lower() == '.minecraft':
                    minecraft_dir = os.sep.join(parts[:i+1])
                    break
            
            if minecraft_dir:
                versions_dir = os.path.join(minecraft_dir, "versions")
                if os.path.exists(versions_dir):
                    if debug:
                        print(f"找到.minecraft目录: {minecraft_dir}, 版本目录: {versions_dir}")
                    
                    #检查所有版本目录
                    possible_versions = []
                    for version in os.listdir(versions_dir):
                        version_dir = os.path.join(versions_dir, version)
                        jar_file = os.path.join(version_dir, f"{version}.jar")
                        if os.path.exists(jar_file) and os.path.isfile(jar_file):
                            possible_versions.append((jar_file, os.path.getmtime(jar_file)))
                    
                    if possible_versions:
                        possible_versions.sort(key=lambda x: x[1], reverse=True)  #按修改时间降序排序
                        jar_path = possible_versions[0][0]  #选择最新的JAR文件
                        
                        #设置环境变量
                        os.environ['MINECRAFT_JAR_PATH'] = jar_path
                        print(f"从.minecraft目录找到JAR文件: {jar_path}")
                        
                        #更新JAR路径标签
                        if hasattr(self, 'jar_path_label'):
                            self.jar_path_label.config(text=f"JAR文件: {jar_path}")
                            
                        return True
        
        print("未能自动检测到JAR文件，将在需要时提示用户手动选择")
        return False

    #添加选择JAR文件的方法
    def select_jar_file(self):
        """手动选择Minecraft JAR文件"""
        #默认目录是.minecraft/versions
        default_path = os.path.join(os.environ.get('APPDATA', ''), ".minecraft", "versions")
        
        #询问用户是否要手动输入路径
        choice = messagebox.askyesno(
            "选择方式", 
            "您想手动输入JAR文件的完整路径吗？\n\n选择\"是\"可以直接输入路径\n选择\"否\"将打开文件选择对话框",
            icon='question'
        )
        
        if choice:
            #手动输入路径
            jar_file = simpledialog.askstring(
                "输入JAR路径", 
                "请输入Minecraft JAR文件的完整路径:",
                initialvalue=os.environ.get('MINECRAFT_JAR_PATH', "")
            )
            
            if not jar_file:
                return
        else:
            #打开文件对话框选择JAR文件
            jar_file = filedialog.askopenfilename(
                title="选择Minecraft JAR文件",
                initialdir=default_path,
                filetypes=[("JAR文件", "*.jar"), ("所有文件", "*.*")]
            )
            
            if not jar_file:
                return
        
        #检查文件是否存在
        if not os.path.exists(jar_file):
            messagebox.showerror("错误", f"指定的文件不存在: {jar_file}")
            return
            
        #设置环境变量
        os.environ['MINECRAFT_JAR_PATH'] = jar_file
        
        #更新UI显示
        self.jar_path_label.config(text=f"已设置JAR文件路径:\n{jar_file}")
        self.status_bar.config(text=f"已设置Minecraft JAR文件: {os.path.basename(jar_file)}")
        
        #提示用户已成功设置
        messagebox.showinfo("成功", f"已成功设置JAR文件路径:\n{jar_file}\n\n您可以重新渲染地图以使用新的方块颜色。")

    #添加选择mods文件夹的方法
    def select_mods_folder(self):
        """手动选择Minecraft mods文件夹"""
        #默认目录是.minecraft/mods
        default_path = os.path.join(os.environ.get('APPDATA', ''), ".minecraft", "mods")
        
        #询问用户是否要手动输入路径
        choice = messagebox.askyesno(
            "选择方式", 
            "您想手动输入Mods文件夹的完整路径吗？\n\n选择\"是\"可以直接输入路径\n选择\"否\"将打开文件夹选择对话框",
            icon='question'
        )
        
        if choice:
            #手动输入路径
            mods_dir = simpledialog.askstring(
                "输入Mods路径", 
                "请输入Minecraft mods文件夹的完整路径:",
                initialvalue=os.environ.get('MINECRAFT_MODS_PATH', "C:\\Users\\LowH\\AppData\\Roaming\\.minecraft\\versions\\Create  Flavored 510\\mods")
            )
            
            if not mods_dir:
                return
        else:
            #打开文件夹选择对话框
            mods_dir = filedialog.askdirectory(
                title="选择Minecraft mods文件夹",
                initialdir=default_path
            )
            
            if not mods_dir:
                return
        
        #检查文件夹是否存在
        if not os.path.isdir(mods_dir):
            messagebox.showerror("错误", f"指定的文件夹不存在: {mods_dir}")
            return
            
        #检查是否是有效的mods文件夹（至少包含一个.jar文件）
        jar_files = [f for f in os.listdir(mods_dir) if f.endswith('.jar')]
        if not jar_files:
            if not messagebox.askyesno("警告", f"所选文件夹中未找到任何JAR文件。这可能不是有效的mods文件夹。\n是否仍然使用此文件夹?"):
                return
        
        #设置环境变量
        os.environ['MINECRAFT_MODS_PATH'] = mods_dir
        
        #更新UI显示
        self.mods_path_label.config(text=f"已设置Mods文件夹路径:\n{mods_dir}")
        self.status_bar.config(text=f"已设置Minecraft mods文件夹: {mods_dir}")
        
        #提示用户已成功设置
        messagebox.showinfo("成功", f"已成功设置Mods文件夹路径:\n{mods_dir}\n\n找到 {len(jar_files)} 个模组文件\n您可以重新渲染地图以使用模组方块颜色。")

    def clear_color_cache(self):
        """清除颜色缓存"""
        #询问用户是否确认清除
        if not messagebox.askyesno("确认", "确定要清除所有颜色缓存文件吗？\n\n这将删除从模组中提取的所有方块颜色信息，下次渲染时需要重新提取。"):
            return
        
        #调用a.py中的清除缓存函数
        deleted_count = a.clear_color_cache()
        
        if deleted_count > 0:
            messagebox.showinfo("成功", f"已成功清除 {deleted_count} 个颜色缓存文件。")
        else:
            messagebox.showinfo("提示", "没有找到颜色缓存文件或清除过程中出现错误。")
        
        #更新状态栏
        self.status_bar.config(text=f"已清除 {deleted_count} 个颜色缓存文件")

    def on_dimension_change(self, event):
        """处理维度选择事件"""
        #提取维度值（仅保留前缀部分）
        dimension_str = self.dimension_var.get()
        #分离维度ID和描述
        dimension_id = dimension_str.split(" ")[0]
        self.current_dimension = dimension_id
        
        #更新状态栏
        self.status_bar.config(text=f"切换维度: {dimension_str}")
        
        #如果已经加载了存档，则加载该维度的区域文件
        if self.save_path:
            #在后台线程中加载维度数据
            threading.Thread(target=lambda: self.load_dimension_data(self.current_dimension), daemon=True).start()
        else:
            self.status_bar.config(text=f"已选择维度: {dimension_str}，请先加载存档")

    def scan_mod_dimensions(self):
        """扫描存档中的模组维度"""
        if not self.save_path:
            return []
        
        #检查dimensions目录是否存在
        dimensions_dir = os.path.join(self.save_path, "dimensions")
        if not os.path.exists(dimensions_dir):
            print(f"未找到dimensions目录: {dimensions_dir}")
            return []
        
        #扫描dimensions目录中的所有目录，每个都可能是一个模组维度
        mod_dimensions = []
        try:
            #递归搜索region目录的函数
            def find_region_dirs(current_path, relative_path=""):
                found_dirs = []
                try:
                    #检查当前目录是否包含region目录
                    region_dir = os.path.join(current_path, "region")
                    if os.path.exists(region_dir) and os.path.isdir(region_dir):
                        #检查region目录中是否有.mca文件
                        try:
                            if any(f.endswith(".mca") for f in os.listdir(region_dir)):
                                #构建相对路径并添加到列表
                                rel_path = os.path.join(relative_path, "region")
                                found_dirs.append((relative_path, rel_path))
                                print(f"找到模组维度region目录: {rel_path}")
                        except Exception as e:
                            print(f"读取region目录 {region_dir} 时出错: {e}")
                    
                    #递归搜索子目录
                    for item in os.listdir(current_path):
                        item_path = os.path.join(current_path, item)
                        if os.path.isdir(item_path) and item != "region":
                            #构建新的相对路径
                            new_rel_path = os.path.join(relative_path, item)
                            #递归搜索
                            found_dirs.extend(find_region_dirs(item_path, new_rel_path))
                except Exception as e:
                    print(f"扫描目录 {current_path} 时出错: {e}")
                return found_dirs
            
            #开始从dimensions目录递归搜索
            mod_dimension_paths = find_region_dirs(dimensions_dir, "dimensions")
            
            #处理找到的维度路径
            for dim_name, region_path in mod_dimension_paths:
                if dim_name == "dimensions":
                    #跳过根目录
                    continue
                
                #清理维度名称（去掉dimensions/前缀）
                if dim_name.startswith("dimensions/"):
                    dim_name = dim_name[len("dimensions/"):]
                
                #添加到模组维度列表
                mod_dimensions.append(dim_name)
                #记录路径映射
                self.dimension_paths[dim_name] = region_path
                print(f"注册模组维度: {dim_name}, 路径: {region_path}")
                
        except Exception as e:
            print(f"扫描dimensions目录时出错: {e}")
            return []
        
        #添加到可用维度列表
        for dim_name in mod_dimensions:
            display_name = f"{dim_name} (模组维度)"
            if display_name not in self.available_dimensions:
                self.available_dimensions.append(display_name)
        
        #更新维度下拉框
        self.update_dimension_dropdown()
        
        return mod_dimensions
    
    def update_dimension_dropdown(self):
        """更新维度下拉框的选项"""
        if hasattr(self, 'dimension_dropdown'):
            current_selection = self.dimension_var.get() if self.dimension_var.get() in self.available_dimensions else self.available_dimensions[0]
            self.dimension_dropdown['values'] = self.available_dimensions
            self.dimension_dropdown.set(current_selection)

if __name__ == "__main__":
    root = tk.Tk()
    app = MinecraftMapGUI(root)
    root.mainloop()     