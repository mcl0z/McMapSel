import anvil
import os
import sys
import numpy as np
from PIL import Image
import json
import time
import concurrent.futures
import threading
import random
import zipfile
import io
import re
from pathlib import Path
import fnmatch

#用于更新共享数据的锁
data_lock = threading.Lock()
print_lock = threading.Lock()

#全局变量，用于存储进度信息
progress = {
    "total_chunks": 0,
    "processed_chunks": 0,
    "start_time": 0,
    "found_blocks": set()
}

#空气方块类型
AIR_BLOCKS = {"air", "cave_air", "void_air"}

#方块类型到颜色的映射 (RGB格式)
BLOCK_COLORS = {
    #石头类
    "stone": (127, 127, 127),
    "cobblestone": (110, 110, 110),
    "granite": (154, 123, 100),
    "diorite": (207, 207, 207),
    "andesite": (138, 138, 138),
    
    #矿石类
    "coal_ore": (46, 46, 46),
    "iron_ore": (197, 145, 106),
    "gold_ore": (252, 222, 112),
    "diamond_ore": (93, 236, 245),
    "emerald_ore": (23, 221, 98),
    "lapis_ore": (22, 64, 201),
    "redstone_ore": (255, 0, 0),
    
    #泥土和草方块 - 更新为固定的鲜绿色
    "dirt": (139, 111, 63),
    "grass_block": (67, 170, 55),        # 更鲜明的绿色
    "grass": (67, 170, 55),              # 与草方块相同的绿色
    "tall_grass": (67, 170, 55),         # 与草方块相同的绿色
    "fern": (79, 166, 37),               # 稍微暗一点的绿色
    "large_fern": (79, 166, 37),         # 稍微暗一点的绿色
    "podzol": (106, 67, 27),
    "mycelium": (126, 108, 140),
    
    # 添加更多草地相关方块
    "short_grass": (67, 170, 55),        # 与草方块相同
    "seagrass": (67, 170, 55),           # 与草方块相同
    "sea_pickle": (89, 176, 65),         # 海泡菜
    "lily_pad": (32, 178, 32),           # 睡莲
    "vine": (67, 170, 55),               # 藤蔓
    "moss_block": (89, 176, 65),         # 苔藓块
    "moss_carpet": (89, 176, 65),        # 苔藓地毯
    "azalea": (79, 166, 37),             # 杜鹃花丛
    "flowering_azalea": (79, 166, 37),   # 开花的杜鹃花丛
    "spore_blossom": (102, 187, 105),    # 孢子花
    
    #沙子和沙砾
    "sand": (219, 207, 142),
    "red_sand": (189, 106, 55),
    "gravel": (150, 141, 125),
    
    #木头类
    "oak_log": (188, 152, 98),
    "spruce_log": (109, 84, 59),
    "birch_log": (215, 203, 143),
    "jungle_log": (151, 114, 80),
    "acacia_log": (169, 88, 33),
    "dark_oak_log": (76, 51, 25),
    
    #树叶类 - 也更新为更固定的绿色
    "oak_leaves": (55, 154, 55),
    "spruce_leaves": (42, 141, 42),
    "birch_leaves": (64, 167, 55),
    "jungle_leaves": (55, 154, 55),
    "acacia_leaves": (64, 167, 55),
    "dark_oak_leaves": (45, 135, 45),
    
    #水和岩浆
    "water": (60, 68, 170),
    "lava": (234, 92, 15),
    
    #冰和雪
    "ice": (160, 233, 255),
    "snow": (255, 255, 255),
    "snow_block": (243, 244, 251),
    
    #植物
    "dandelion": (255, 236, 79),
    "poppy": (237, 48, 44),
    
    #其他常见方块
    "bedrock": (10, 10, 10),
    "obsidian": (21, 18, 30),
    "netherrack": (114, 58, 57),
    "soul_sand": (85, 67, 54),
    "glowstone": (254, 217, 63),
    "end_stone": (219, 222, 158),
    
    #特殊方块
    "air": (255, 255, 255, 0),  #透明
    "cave_air": (255, 255, 255, 0),  #透明
    "void_air": (255, 255, 255, 0),  #透明
    "none": (255, 0, 255, 255),  #粉色不透明，表示无效区块
}

#anvil-new库中可用的高度图类型
HEIGHTMAP_TYPES = [
    'MOTION_BLOCKING',          #最适合找顶层固体方块
    'MOTION_BLOCKING_NO_LEAVES', #不包括树叶的顶层固体方块
    'OCEAN_FLOOR',              #海底方块
    'WORLD_SURFACE'             #世界表面，包括树叶等
]

#预先定义一个全局颜色缓存
COLOR_CACHE = {}

def get_block_color(block_id):
    """根据方块ID获取对应的颜色"""
    #使用全局缓存
    if block_id in COLOR_CACHE:
        return COLOR_CACHE[block_id]
    
    #如果block_id为None或空字符串，返回紫色标记错误
    if not block_id:
        color = (255, 0, 255, 255)  #不透明紫色
        COLOR_CACHE[block_id] = color
        return color
    
    #从完整方块ID中提取基本名称
    if ":" in block_id:
        namespace, name = block_id.split(":", 1)
        #如果是minecraft命名空间，只使用名称部分
        if namespace == "minecraft":
            block_id = name
    
    #尝试匹配完整ID
    if block_id in BLOCK_COLORS:
        color = BLOCK_COLORS[block_id]
        COLOR_CACHE[block_id] = color
        return color
    
    #尝试匹配部分ID（例如，如果block_id是"spruce_planks"，可以匹配"spruce"）
    for key in BLOCK_COLORS:
        if key in block_id:
            color = BLOCK_COLORS[key]
            COLOR_CACHE[block_id] = color
            return color
    
    #生成随机颜色但保持一致性
    #使用方块ID的哈希值作为随机数种子
    random.seed(hash(str(block_id)))
    r = int(random.random() * 200 + 55)  #避免太暗的颜色
    g = int(random.random() * 200 + 55)
    b = int(random.random() * 200 + 55)
    color = (r, g, b, 255)
    COLOR_CACHE[block_id] = color
    return color

def log(message):
    """打印日志并立即刷新输出缓冲区"""
    with print_lock:
        print(message, flush=True)

def update_progress(chunks_processed=0, found_blocks=None):
    """更新进度信息"""
    with data_lock:
        progress["processed_chunks"] += chunks_processed
        if found_blocks:
            progress["found_blocks"].update(found_blocks)

def print_progress_bar():
    """打印统一的进度条"""
    with data_lock:
        processed = progress["processed_chunks"]
        total = progress["total_chunks"]
        if total == 0:
            return
        
        percent = min(100, processed * 100 // total)
        elapsed = time.time() - progress["start_time"]
        speed = processed / max(0.1, elapsed)
        remaining = (total - processed) / max(0.1, speed)
        
        #计算进度条长度 (50个字符)
        bar_length = 50
        filled_length = int(bar_length * percent / 100)
        bar = '█' * filled_length + '░' * (bar_length - filled_length)
        
    with print_lock:
        #使用\r回到行首，覆盖之前的进度条
        sys.stdout.write(f"\r处理进度: [{bar}] {percent}% ({processed}/{total}) "
                        f"速度: {speed:.1f}区块/秒 "
                        f"剩余: {remaining:.1f}秒 "
                        f"发现方块: {len(progress['found_blocks'])}种")
        sys.stdout.flush()

def turbo_process_chunk(chunk, local_found_blocks, sample_interval=1):
    """
    涡轮加速处理单个区块，直接使用高度图并尽量减少异常处理
    
    Args:
        chunk: anvil.Chunk对象
        local_found_blocks: 用于存储发现的方块类型的集合
        sample_interval: 采样间隔，每隔多少个方块采样一次，默认为1（全采样）
        
    Returns:
        该区块中16x16的顶部方块ID数组
    """
    #预分配区块方块数组
    chunk_blocks = [["air" for _ in range(16)] for _ in range(16)]
    
    #根据采样间隔创建要处理的坐标列表
    sample_coords = []
    for local_z in range(16):
        for local_x in range(16):
            if sample_interval == 1 or (local_x % sample_interval == 0 and local_z % sample_interval == 0):
                sample_coords.append((local_x, local_z))
    
    #创建一个状态跟踪数组，表示方块是否已经找到
    found_blocks = np.zeros((16, 16), dtype=bool)
    
    #检查区块是否有区段信息
    has_sections = hasattr(chunk, 'sections') and chunk.sections
    
    if has_sections:
        #尝试在Y坐标从高到低的顺序查找方块
        for local_z, local_x in sample_coords:
            #定义Y坐标的搜索范围
            low = -64  #最低高度
            high = 319  #最高高度
            result_block_id = "air"
            
            #二分查找第一个非空气方块
            while low <= high:
                mid = (low + high) // 2
                
                try:
                    block = chunk.get_block(local_x, mid, local_z)
                    if block and block.id not in AIR_BLOCKS:
                        #找到非空气方块，记录并向上查找
                        result_block_id = block.id
                        low = mid + 1
                    else:
                        #空气方块，向下查找
                        high = mid - 1
                except:
                    #如果出错，视为空气，向下查找
                    high = mid - 1
                
                #如果找到了方块，验证它是最上面的非空气方块
                if result_block_id != "air":
                    #从high向上扫描，找到确切的顶层方块
                    #由于二分查找的特性，high+1就是第一个空气方块
                    exact_y = high
                    
                    #再次确认这是顶层方块
                    try:
                        top_block = chunk.get_block(local_x, exact_y, local_z)
                        if top_block and top_block.id not in AIR_BLOCKS:
                            chunk_blocks[local_z][local_x] = top_block.id
                            local_found_blocks.add(top_block.id)
                            found_blocks[local_z, local_x] = True
                        else:
                            #如果最后检查结果是空气，则取上一个结果
                            chunk_blocks[local_z][local_x] = result_block_id
                            local_found_blocks.add(result_block_id)
                            found_blocks[local_z, local_x] = True
                    except:
                        #出错则使用之前找到的结果
                        chunk_blocks[local_z][local_x] = result_block_id
                        local_found_blocks.add(result_block_id)
                        found_blocks[local_z, local_x] = True
        
        # 如果使用了采样间隔 > 1，填充未采样的方块
        if sample_interval > 1:
            # 复制采样点的值到相邻未采样的点
            for local_z in range(16):
                for local_x in range(16):
                    if not found_blocks[local_z, local_x]:
                        # 找到最近的采样点
                        sample_x = (local_x // sample_interval) * sample_interval
                        sample_z = (local_z // sample_interval) * sample_interval
                        
                        # 确保采样点在范围内
                        sample_x = min(sample_x, 15)
                        sample_z = min(sample_z, 15)
                        
                        # 复制采样点的方块ID
                        if found_blocks[sample_z, sample_x]:
                            chunk_blocks[local_z][local_x] = chunk_blocks[sample_z][sample_x]
                            found_blocks[local_z, local_x] = True
                        else:
                            # 如果采样点也没有找到方块，使用"none"作为默认值
                            chunk_blocks[local_z][local_x] = "none"
                            local_found_blocks.add("none")
        else:
            #对于未找到方块的位置，标记为空气
            for local_z in range(16):
                for local_x in range(16):
                    if not found_blocks[local_z, local_x]:
                        chunk_blocks[local_z][local_x] = "air"
                        local_found_blocks.add("air")
    else:
        #如果没有区段信息，只处理采样点
        for local_z, local_x in sample_coords:
            #定义Y坐标的搜索范围
            low = -64  #最低高度
            high = 319  #最高高度
            result_block_id = "air"
            
            #二分查找第一个非空气方块
            while low <= high:
                mid = (low + high) // 2
                
                try:
                    block = chunk.get_block(local_x, mid, local_z)
                    if block and block.id not in AIR_BLOCKS:
                        #找到非空气方块，继续向上查找
                        result_block_id = block.id
                        low = mid + 1
                    else:
                        #是空气，向下查找
                        high = mid - 1
                except:
                    #出错视为空气
                    high = mid - 1
                
                #找到最后一个非空气方块
                if result_block_id != "air":
                    exact_y = high
                    try:
                        top_block = chunk.get_block(local_x, exact_y, local_z)
                        if top_block and top_block.id not in AIR_BLOCKS:
                            chunk_blocks[local_z][local_x] = top_block.id
                            local_found_blocks.add(top_block.id)
                            found_blocks[local_z, local_x] = True
                        else:
                            chunk_blocks[local_z][local_x] = result_block_id
                            local_found_blocks.add(result_block_id)
                            found_blocks[local_z, local_x] = True
                    except:
                        chunk_blocks[local_z][local_x] = result_block_id
                        local_found_blocks.add(result_block_id)
                        found_blocks[local_z, local_x] = True
                else:
                    chunk_blocks[local_z][local_x] = "air"
                    local_found_blocks.add("air")
                    found_blocks[local_z, local_x] = True
        
        # 如果使用了采样间隔 > 1，填充未采样的方块
        if sample_interval > 1:
            # 复制采样点的值到相邻未采样的点
            for local_z in range(16):
                for local_x in range(16):
                    if not found_blocks[local_z, local_x]:
                        # 找到最近的采样点
                        sample_x = (local_x // sample_interval) * sample_interval
                        sample_z = (local_z // sample_interval) * sample_interval
                        
                        # 确保采样点在范围内
                        sample_x = min(sample_x, 15)
                        sample_z = min(sample_z, 15)
                        
                        # 复制采样点的方块ID
                        if found_blocks[sample_z, sample_x]:
                            chunk_blocks[local_z][local_x] = chunk_blocks[sample_z][sample_x]
                            found_blocks[local_z, local_x] = True
                        else:
                            # 如果采样点也没有找到方块，使用"none"作为默认值
                            chunk_blocks[local_z][local_x] = "none"
                            local_found_blocks.add("none")
    
    return chunk_blocks

def process_chunk_batch(region, chunk_coords, thread_id, sample_interval=1):
    """
    处理一批区块并返回每个区块的顶层方块
    
    Args:
        region: anvil.Region对象
        chunk_coords: 要处理的区块坐标列表
        thread_id: 线程ID（用于日志）
        sample_interval: 采样间隔，每隔多少个方块采样一次
    
    Returns:
        包含每个区块顶层方块的字典，格式为 {(chunk_x, chunk_z): chunk_blocks}
    """
    result = {}
    chunks_processed = 0
    local_found_blocks = set()
    
    for chunk_x, chunk_z in chunk_coords:
        try:
            #获取区块
            chunk = region.get_chunk(chunk_x, chunk_z)
            
            #处理区块，获取顶层方块
            chunk_blocks = turbo_process_chunk(chunk, local_found_blocks, sample_interval)
            
            #保存结果
            result[(chunk_x, chunk_z)] = chunk_blocks
            chunks_processed += 1
        except Exception as e:
            #如果处理区块失败，使用"none"填充
            empty_chunk = [["none" for _ in range(16)] for _ in range(16)]
            result[(chunk_x, chunk_z)] = empty_chunk
            local_found_blocks.add("none")
            chunks_processed += 1
    
    #更新全局进度
    if chunks_processed > 0:
        update_progress(chunks_processed, local_found_blocks)
        print_progress_bar()
    
    return result

def get_top_blocks(mca_file_path, max_workers=8, region_size=32, progress_callback=None, sample_interval=1):
    """
    从指定的MCA文件中获取每个位置的顶层方块
    
    Args:
        mca_file_path: MCA文件的路径
        max_workers: 最大工作线程数
        region_size: 区域大小，通常为32（表示32x32个区块）
        progress_callback: 进度回调函数
        sample_interval: 采样间隔，用于控制精度和速度
    
    Returns:
        包含顶层方块信息的字典
    """
    # 检查是否禁用了颜色提取功能
    if not os.environ.get('DISABLE_COLOR_EXTRACTION'):
        # 尝试从对应的Minecraft客户端JAR文件更新方块颜色信息
        # 从存档路径推断.minecraft目录
        save_dir = os.path.dirname(os.path.dirname(mca_file_path))
        if os.path.basename(os.path.dirname(mca_file_path)) == "region":
            # 如果mca_file_path是region目录中的文件，则save_dir应该是它的上一级
            save_dir = os.path.dirname(os.path.dirname(mca_file_path))
        
        # 尝试更新方块颜色信息
        update_block_colors_from_jar(save_dir)
    
    # 记录开始时间
    start_time = time.time()

    #清空全局进度信息
    with data_lock:
        progress["processed_chunks"] = 0
        progress["found_blocks"] = set()
        progress["start_time"] = time.time()
    
    #检查区域大小参数
    if region_size <= 0 or region_size > 32:
        log(f"无效的区域大小: {region_size}，必须在1-32之间")
        region_size = min(32, max(1, region_size))
        log(f"已调整为: {region_size}")
    
    #检查采样间隔参数
    if sample_interval <= 0:
        log(f"无效的采样间隔: {sample_interval}，必须大于0")
        sample_interval = 1
    elif sample_interval > 1:
        log(f"使用优化采样间隔: 每{sample_interval}个方块采样1次")
    
    #检查文件是否存在
    if not os.path.exists(mca_file_path):
        log(f"文件不存在: {mca_file_path}")
        return None
    
    #从文件名解析区域坐标
    filename = os.path.basename(mca_file_path)
    if not filename.startswith('r.'):
        log(f"不是有效的区域文件: {filename}")
        return None
    
    try:
        #预先创建结果数组（避免后续频繁分配内存）
        array_size = region_size * 16
        top_blocks = np.full((array_size, array_size), "none", dtype=object)
        
        #打开区域文件
        log(f"正在打开区域文件: {mca_file_path}")
        start_time = time.time()
        region = anvil.Region.from_file(mca_file_path)
        log(f"区域文件加载完成，耗时: {time.time() - start_time:.2f}秒")
        
        #快速获取区域文件中存在的区块坐标
        populated_chunks = []
        try:
            #尝试使用更快的方法获取已填充的区块列表
            if hasattr(region, 'get_chunk_coordinates'):
                populated_chunks = list(region.get_chunk_coordinates())
                #过滤坐标范围
                populated_chunks = [(x, z) for x, z in populated_chunks if 0 <= x < region_size and 0 <= z < region_size]
            else:
                #手动检查每个区块是否存在
                for chunk_x in range(region_size):
                    for chunk_z in range(region_size):
                        if region.chunk_exists(chunk_x, chunk_z):
                            populated_chunks.append((chunk_x, chunk_z))
        except Exception:
            #如果无法获取已填充区块列表，处理所有区块
            log("无法获取已填充区块列表，将处理所有区块")
            populated_chunks = [(x, z) for x in range(region_size) for z in range(region_size)]
        
        #将任务分成批次，每个线程处理一批区块
        log(f"发现 {len(populated_chunks)} 个有效区块，开始处理，使用 {max_workers} 个工作线程")
        
        #设置总区块数
        with data_lock:
            progress["total_chunks"] = len(populated_chunks)
        
        if len(populated_chunks) == 0:
            log("没有找到有效区块，处理完成")
            return top_blocks
        
        #优化批次分配策略
        #计算每个工作线程的负载
        chunks_per_worker = max(1, len(populated_chunks) // max_workers)
        
        #如果区块数量少于工作线程数，减少工作线程数
        actual_workers = min(max_workers, len(populated_chunks))
        
        #分批处理任务
        batches = []
        for i in range(0, len(populated_chunks), chunks_per_worker):
            batches.append(populated_chunks[i:i + chunks_per_worker])
        
        #调整工作线程数为实际批次数
        actual_workers = min(actual_workers, len(batches))
        if actual_workers < max_workers:
            log(f"调整工作线程数为 {actual_workers} (实际批次数)")
        
        #使用线程池并行处理区块
        with concurrent.futures.ThreadPoolExecutor(max_workers=actual_workers) as executor:
            #提交所有批次任务，传递采样间隔参数
            future_to_batch = {
                executor.submit(process_chunk_batch, region, batch, i, sample_interval): i 
                for i, batch in enumerate(batches)
            }
            
            #处理结果
            valid_chunks = 0
            for future in concurrent.futures.as_completed(future_to_batch):
                try:
                    #获取这个线程处理的所有区块的结果
                    batch_results = future.result()
                    
                    #处理结果
                    for (chunk_x, chunk_z), chunk_blocks in batch_results.items():
                        if chunk_blocks is not None:
                            valid_chunks += 1
                            
                            #将区块数据复制到结果数组
                            start_x = chunk_x * 16
                            start_z = chunk_z * 16
                            for local_z in range(16):
                                for local_x in range(16):
                                    global_x = start_x + local_x
                                    global_z = start_z + local_z
                                    if 0 <= global_x < array_size and 0 <= global_z < array_size:
                                        top_blocks[global_z][global_x] = chunk_blocks[local_z][local_x]
                    
                    #如果有进度回调，调用它
                    if progress_callback:
                        with data_lock:
                            current = progress["processed_chunks"]
                            total = progress["total_chunks"]
                            progress_callback(current, total)
                except Exception as e:
                    log(f"处理结果时出错: {str(e)}")
        
        #输出处理完成的统计信息
        total_time = time.time() - progress["start_time"]
        print()  #添加换行，避免与进度条重叠
        log(f"\n处理完成!")
        log(f"总耗时: {total_time:.2f}秒")
        log(f"处理区块: {len(populated_chunks)}")
        log(f"有效区块: {valid_chunks}")
        log(f"平均速度: {len(populated_chunks) / total_time:.2f} 区块/秒")
        log(f"发现的方块类型数量: {len(progress['found_blocks'])}")
        
        #最后一次进度回调，确保显示100%
        if progress_callback:
            progress_callback(progress["total_chunks"], progress["total_chunks"])
        
        return top_blocks
    
    except KeyboardInterrupt:
        print()  #添加换行，避免与进度条重叠
        log("程序被用户中断！")
        return None
    except Exception as e:
        print()  #添加换行，避免与进度条重叠
        log(f"读取区域文件时出错: {e}")
        return None

def direct_render_to_png(top_blocks, output_file, progress_callback=None, sample_interval=1):
    """
    直接渲染顶部方块数据到PNG图片，不使用matplotlib
    
    Args:
        top_blocks: 包含顶部方块ID的二维数组
        output_file: 输出图像文件路径
        progress_callback: 进度回调函数，接收两个参数：当前进度和总进度
        sample_interval: 采样间隔，每隔多少个方块采样一次，默认为1（全采样）
    """
    if top_blocks is None:
        log("无法渲染：顶部方块数据为空")
        return False
    
    log("正在直接渲染PNG图像...")
    start_time = time.time()
    
    # 输出采样间隔信息
    if sample_interval > 1:
        log(f"注意：使用了优化采样（每{sample_interval}个方块采样1次），图像质量可能略有降低")
    
    # 检查是否需要使用不透明颜色
    use_opaque_colors = 'USE_OPAQUE_COLORS' in os.environ
    if use_opaque_colors:
        log("已启用不透明颜色模式，所有颜色将使用完全不透明的Alpha值")
    
    try:
        #获取数组大小
        height, width = top_blocks.shape
        
        if height <= 0 or width <= 0:
            log(f"无效的数组大小: {width}x{height}")
            return False
        
        log(f"图像大小: {width}x{height}像素")
    
        #创建一个RGB图像
        img = Image.new('RGBA', (width, height), (255, 255, 255, 0))
        
        #优化渲染过程 - 使用NumPy数组处理，然后一次性传输到图像
        log("正在优化像素处理...")
        
        #创建一个RGBA NumPy数组
        pixel_array = np.zeros((height, width, 4), dtype=np.uint8)
        
        #预处理所有块ID及其颜色
        log("正在预处理方块颜色...")
        unique_blocks = set()
        for row in top_blocks:
            for block_id in row:
                if block_id:  #确保不是None
                    unique_blocks.add(block_id)
        
        block_color_map = {block_id: get_block_color(block_id) for block_id in unique_blocks if block_id}
    
        #批量填充像素数组
        update_interval = max(1, height // 10)  #每10%更新一次进度
        
        log("正在填充像素数组...")
        error_count = 0
        for i in range(height):
            for j in range(width):
                try:
                    block_id = top_blocks[i, j]
                    if not block_id or block_id not in block_color_map:
                        #如果方块ID无效，使用粉色表示错误
                        pixel_array[i, j] = [255, 0, 255, 255]
                        error_count += 1
                        continue
                        
                    color = block_color_map[block_id]
                    
                    #确保颜色是RGBA格式
                    if len(color) == 3:
                        pixel_array[i, j, 0] = color[0]
                        pixel_array[i, j, 1] = color[1]
                        pixel_array[i, j, 2] = color[2]
                        pixel_array[i, j, 3] = 255
                    else:
                        pixel_array[i, j, 0] = color[0]
                        pixel_array[i, j, 1] = color[1]
                        pixel_array[i, j, 2] = color[2]
                        pixel_array[i, j, 3] = 255 if use_opaque_colors else color[3]
                except Exception as e:
                    #记录错误并使用紫色表示处理失败的像素
                    pixel_array[i, j] = [128, 0, 128, 255]
                    error_count += 1
            
            #更新进度
            if i % update_interval == 0:
                percent = i * 100 // height
                sys.stdout.write(f"\r图像渲染: {percent}% 完成")
                sys.stdout.flush()
                
                #如果有进度回调，调用它
                if progress_callback:
                    progress_callback(i, height)
        
        if error_count > 0:
            log(f"\n注意：处理过程中有 {error_count} 个像素出现错误 ({error_count/(width*height)*100:.2f}%)")
    
        #将NumPy数组转换为PIL图像
        print()  #添加换行
        log("正在转换为PIL图像...")
        img = Image.fromarray(pixel_array, 'RGBA')
        
        #保存原始尺寸图像，用于调试
        debug_file = os.path.splitext(output_file)[0] + "_original.png"
        img.save(debug_file)
        log(f"原始图像已保存到 {debug_file}")
    
        #创建缩略图版本 (最大2048px)
        max_size = 2048
        if width > max_size or height > max_size:
            #保持宽高比
            if width > height:
                new_width = max_size
                new_height = int(max_size * height / width)
            else:
                new_height = max_size
                new_width = int(max_size * width / height)
            
            log(f"调整图像大小至 {new_width}x{new_height}")
            img = img.resize((new_width, new_height), Image.Resampling.LANCZOS if hasattr(Image, 'Resampling') else Image.LANCZOS)
        
        #保存图像
        log("正在保存图像...")
        img.save(output_file)
        log(f"图像已保存到 {output_file}")
        log(f"渲染完成，耗时: {time.time() - start_time:.2f}秒")
        
        #最后一次进度回调，确保显示100%
        if progress_callback:
            progress_callback(height, height)
        
        return True
    except Exception as e:
        log(f"渲染图像时出错: {str(e)}")
        return False

def save_blocks_to_json(top_blocks, output_file):
    """
    将顶部方块数据保存为JSON文件
    
    Args:
        top_blocks: 包含顶部方块ID的二维数组
        output_file: 输出JSON文件路径
    """
    if top_blocks is None:
        return
    
    log(f"正在将数据保存为JSON: {output_file}")
    start_time = time.time()
    log("正在转换数据格式...")
    height, width = top_blocks.shape
    with open(output_file, 'w', encoding='utf-8') as f:
        f.write('[\n')  #开始数组
        for i in range(height):
            f.write('  [')  #开始行
            for j in range(width):
                #对字符串进行转义和引号包装
                block_id = top_blocks[i, j]
                json_str = json.dumps(block_id, ensure_ascii=False)
                f.write(json_str)
                if j < width - 1:
                    f.write(', ')
            f.write(']')  #结束行
            if i < height - 1:
                f.write(',\n')
            else:
                f.write('\n')
        f.write(']\n')  #结束数组
    
    log(f"JSON数据已保存，文件大小: {os.path.getsize(output_file) / 1024:.2f} KB，耗时: {time.time() - start_time:.2f}秒")

def find_minecraft_jar(saves_path):
    """
    根据存档路径推断Minecraft安装目录并找到最新的jar文件
    
    Args:
        saves_path: Minecraft存档的路径
    
    Returns:
        最新Minecraft客户端jar文件的路径，如果找不到返回None
    """
    try:
        # 尝试从存档路径推断.minecraft目录
        saves_path = Path(saves_path)
        
        # 尝试基于存档路径找到.minecraft目录
        possible_mc_dir = None
        
        # 检查Windows典型路径
        if "AppData" in str(saves_path):
            # 如果路径包含AppData和saves，尝试推断.minecraft目录
            if "saves" in str(saves_path):
                parts = str(saves_path).split("saves")
                possible_mc_dir = Path(parts[0])
        
        # 如果没找到，尝试从保存路径向上查找直到找到.minecraft目录
        if not possible_mc_dir:
            current = saves_path
            for _ in range(5):  # 向上最多检查5层目录
                if (current / "versions").exists():
                    possible_mc_dir = current
                    break
                if current.parent == current:  # 已经到达根目录
                    break
                current = current.parent
        
        # 如果还是没找到，尝试常见的默认位置
        if not possible_mc_dir:
            # Windows默认位置
            if os.name == 'nt':
                appdata = os.environ.get('APPDATA', '')
                possible_mc_dir = Path(appdata) / ".minecraft"
            # macOS默认位置
            elif sys.platform == 'darwin':
                possible_mc_dir = Path.home() / "Library/Application Support/minecraft"
            # Linux默认位置
            else:
                possible_mc_dir = Path.home() / ".minecraft"
        
        # 检查版本目录是否存在
        versions_dir = possible_mc_dir / "versions"
        if not versions_dir.exists():
            return None
        
        # 首先尝试查找当前存档可能对应的JAR文件
        # 如果存档路径包含版本名称，尝试直接查找该版本的JAR
        save_parent_dir = os.path.basename(os.path.dirname(saves_path))
        if save_parent_dir != "saves":
            # 存档可能位于特定版本目录下
            version_name = save_parent_dir
            # 检查是否存在这个版本目录
            version_dir = versions_dir / version_name
            if version_dir.exists():
                jar_path = version_dir / f"{version_name}.jar"
                if jar_path.exists():
                    log(f"找到与存档版本匹配的JAR文件: {jar_path}")
                    return str(jar_path)
            
            # 检查是否是自定义启动器格式的版本名称（如"Fabulously Optimized 1214"）
            custom_jar_path = versions_dir / version_name / f"{version_name}.jar"
            if custom_jar_path.exists():
                log(f"找到与存档版本匹配的自定义JAR文件: {custom_jar_path}")
                return str(custom_jar_path)
        
        # 如果没有找到存档对应的版本，尝试从版本目录名中提取版本号
        try:
            version_pattern = re.compile(r'(\d+\.\d+(\.\d+)?)')
            save_version_match = version_pattern.search(str(saves_path))
            save_version = save_version_match.group(1) if save_version_match else None
            
            if save_version:
                # 查找匹配此版本号的目录
                for version_dir in versions_dir.iterdir():
                    if version_dir.is_dir() and save_version in version_dir.name:
                        jar_path = version_dir / f"{version_dir.name}.jar"
                        if jar_path.exists():
                            log(f"找到与存档版本号匹配的JAR文件: {jar_path}")
                            return str(jar_path)
        except Exception as e:
            log(f"匹配版本号时出错: {e}")
        
        # 特殊处理Fabulously Optimized和其他自定义启动器
        for version_dir in versions_dir.iterdir():
            if version_dir.is_dir():
                if "Fabulously" in version_dir.name or "Fabric" in version_dir.name or "Forge" in version_dir.name:
                    jar_path = version_dir / f"{version_dir.name}.jar"
                    if jar_path.exists():
                        log(f"找到自定义启动器JAR文件: {jar_path}")
                        return str(jar_path)
        
        # 查找最新的版本目录
        version_dirs = [d for d in versions_dir.iterdir() if d.is_dir() and not d.name.startswith('.')]
        
        # 按照修改时间排序
        version_dirs.sort(key=lambda x: x.stat().st_mtime, reverse=True)
        
        # 查找每个版本目录中的jar文件
        for version_dir in version_dirs:
            jar_path = version_dir / f"{version_dir.name}.jar"
            if jar_path.exists():
                log(f"找到最新的JAR文件: {jar_path}")
                return str(jar_path)
        
        # 如果用户提供了明确的路径，直接检查
        if os.environ.get('MINECRAFT_JAR_PATH'):
            jar_path = Path(os.environ.get('MINECRAFT_JAR_PATH'))
            if jar_path.exists():
                log(f"使用环境变量指定的JAR文件: {jar_path}")
                return str(jar_path)
        
        log("未找到任何有效的Minecraft JAR文件")
        return None
    
    except Exception as e:
        log(f"查找Minecraft JAR文件时出错: {e}")
        return None

def extract_block_colors_from_jar(jar_path):
    """
    从Minecraft客户端jar文件中提取方块颜色信息
    
    Args:
        jar_path: jar文件的路径
    
    Returns:
        包含方块ID和颜色的字典，如果提取失败则返回None
    """
    if not jar_path or not os.path.exists(jar_path):
        log(f"JAR文件不存在: {jar_path}")
        return None
    
    try:
        extracted_colors = {}
        with zipfile.ZipFile(jar_path, 'r') as jar:
            # 搜索资源文件列表
            texture_files = [f for f in jar.namelist() if f.startswith('assets/minecraft/textures/block/') and f.endswith('.png')]
            
            # 处理每个纹理文件
            for texture_path in texture_files:
                try:
                    # 从路径提取方块ID
                    block_id = texture_path.split('/')[-1].replace('.png', '')
                    
                    # 读取图像数据
                    with jar.open(texture_path) as f:
                        img_data = f.read()
                        img = Image.open(io.BytesIO(img_data))
                        
                        # 将图像转换为RGBA模式
                        img = img.convert('RGBA')
                        
                        # 获取图像中所有像素
                        pixels = list(img.getdata())
                        
                        # 过滤掉透明像素
                        non_transparent = [p for p in pixels if p[3] > 128]
                        
                        # 如果所有像素都是透明的，跳过这个方块
                        if not non_transparent:
                            continue
                        
                        # 计算平均颜色
                        r_sum = sum(p[0] for p in non_transparent)
                        g_sum = sum(p[1] for p in non_transparent)
                        b_sum = sum(p[2] for p in non_transparent)
                        
                        count = len(non_transparent)
                        r_avg = r_sum // count
                        g_avg = g_sum // count
                        b_avg = b_sum // count
                        
                        # 存储颜色信息
                        extracted_colors[block_id] = (r_avg, g_avg, b_avg, 255)
                except Exception as e:
                    log(f"处理纹理文件时出错 {texture_path}: {e}")
                    continue
            
            # 还可以尝试从assets/minecraft/models/block/目录读取模型信息
            # 以及从assets/minecraft/blockstates/目录读取方块状态信息
            
            # 尝试从json文件中获取额外的方块信息
            try:
                block_states_files = [f for f in jar.namelist() if f.startswith('assets/minecraft/blockstates/') and f.endswith('.json')]
                for state_file in block_states_files:
                    block_id = state_file.split('/')[-1].replace('.json', '')
                    if block_id not in extracted_colors:
                        # 检查是否有关联的纹理
                        for existing_id, color in extracted_colors.items():
                            if block_id in existing_id or existing_id in block_id:
                                extracted_colors[block_id] = color
                                break
            except Exception as e:
                log(f"处理方块状态文件时出错: {e}")
        
        # 强制指定草地相关方块的颜色，不受JAR文件中纹理的影响
        grass_blocks = {
            # 草方块和相关变种
            "grass_block": (67, 170, 55),        # 鲜绿色
            "grass": (67, 170, 55),              # 与草方块相同
            "tall_grass": (67, 170, 55),         # 与草方块相同
            "short_grass": (67, 170, 55),        # 与草方块相同
            "fern": (79, 166, 37),               # 稍暗的绿色
            "large_fern": (79, 166, 37),         # 稍暗的绿色
            
            # 树叶
            "oak_leaves": (55, 154, 55),
            "spruce_leaves": (42, 141, 42),
            "birch_leaves": (64, 167, 55),
            "jungle_leaves": (55, 154, 55),
            "acacia_leaves": (64, 167, 55),
            "dark_oak_leaves": (45, 135, 45),
            
            # 其他绿色植物
            "seagrass": (67, 170, 55),
            "sea_pickle": (89, 176, 65),
            "lily_pad": (32, 178, 32),
            "vine": (67, 170, 55),
            "moss_block": (89, 176, 65),
            "moss_carpet": (89, 176, 65),
            "azalea": (79, 166, 37),
            "flowering_azalea": (79, 166, 37),
            "spore_blossom": (102, 187, 105),
        }
        
        # 将强制指定的颜色合并到提取的颜色中
        for block_id, color in grass_blocks.items():
            color_with_alpha = color if len(color) == 4 else (*color, 255)
            extracted_colors[block_id] = color_with_alpha
            
            # 同时处理带命名空间的ID
            extracted_colors[f"minecraft:{block_id}"] = color_with_alpha
            
        log(f"已强制设置草地相关方块为固定绿色，不受生物群系影响")
        
        return extracted_colors
    
    except Exception as e:
        log(f"提取JAR文件颜色时出错: {e}")
        return None

def extract_colors_from_mod_jar(mod_jar_path):
    """
    从模组JAR文件中提取方块颜色信息
    
    Args:
        mod_jar_path: 模组JAR文件的路径
    
    Returns:
        包含方块ID和颜色的字典
    """
    # 首先尝试从缓存加载
    if not os.environ.get('DISABLE_COLOR_CACHE'):
        cached_colors = load_mod_colors_from_cache(mod_jar_path)
        if cached_colors is not None:
            return cached_colors
    
    log(f"缓存未命中，从模组JAR文件提取颜色: {os.path.basename(mod_jar_path)}")
    extracted_colors = {}
    
    try:
        with zipfile.ZipFile(mod_jar_path, 'r') as jar:
            # 获取模组ID
            mod_id = None
            
            # 尝试从fabric.mod.json或mods.toml中获取模组ID
            if 'fabric.mod.json' in jar.namelist():
                try:
                    with jar.open('fabric.mod.json') as f:
                        mod_info = json.load(f)
                        mod_id = mod_info.get('id')
                        log(f"从Fabric模组获取ID: {mod_id}")
                except:
                    pass
            elif 'META-INF/mods.toml' in jar.namelist():
                try:
                    with jar.open('META-INF/mods.toml') as f:
                        content = f.read().decode('utf-8')
                        for line in content.split('\n'):
                            if line.strip().startswith('modId'):
                                mod_id = line.split('=')[1].strip().strip('"\'')
                                log(f"从Forge模组获取ID: {mod_id}")
                                break
                except:
                    pass
            
            # 如果无法获取模组ID，使用文件名作为ID
            if not mod_id:
                mod_id = os.path.basename(mod_jar_path).split('.')[0].lower()
                log(f"使用文件名作为模组ID: {mod_id}")
            
            # 搜索模组中的方块纹理
            # 检查多种可能的纹理路径格式
            texture_paths = [
                # Fabric/Forge 1.14+
                f'assets/{mod_id}/textures/block/',
                # 通用路径
                'assets/*/textures/block/',
                # 旧版Forge
                f'assets/{mod_id}/textures/blocks/',
                # 其他可能的路径
                'assets/*/textures/blocks/'
            ]
            
            # 收集所有方块纹理文件
            texture_files = []
            for path_pattern in texture_paths:
                for file_path in jar.namelist():
                    if fnmatch.fnmatch(file_path, path_pattern + '*.png'):
                        texture_files.append(file_path)
            
            log(f"在模组 {mod_id} 中找到 {len(texture_files)} 个方块纹理")
            
            # 处理每个纹理文件
            for texture_path in texture_files:
                try:
                    # 从路径提取方块ID
                    # 提取模组命名空间和方块名称
                    parts = texture_path.split('/')
                    if len(parts) >= 4:
                        namespace = parts[1]  # 模组命名空间
                        block_name = parts[-1].replace('.png', '')  # 方块名称
                        
                        # 完整的方块ID (namespace:block_name)
                        block_id = f"{namespace}:{block_name}"
                        
                        # 读取图像数据
                        with jar.open(texture_path) as f:
                            img_data = f.read()
                            img = Image.open(io.BytesIO(img_data))
                            
                            # 将图像转换为RGBA模式
                            img = img.convert('RGBA')
                            
                            # 获取图像中所有像素
                            pixels = list(img.getdata())
                            
                            # 过滤掉透明像素
                            non_transparent = [p for p in pixels if p[3] > 128]
                            
                            # 如果所有像素都是透明的，跳过这个方块
                            if not non_transparent:
                                continue
                            
                            # 计算平均颜色
                            r_sum = sum(p[0] for p in non_transparent)
                            g_sum = sum(p[1] for p in non_transparent)
                            b_sum = sum(p[2] for p in non_transparent)
                            
                            count = len(non_transparent)
                            r_avg = r_sum // count
                            g_avg = g_sum // count
                            b_avg = b_sum // count
                            
                            # 存储颜色信息
                            extracted_colors[block_id] = (r_avg, g_avg, b_avg, 255)
                            
                            # 同时存储不带命名空间的ID，以便匹配
                            extracted_colors[block_name] = (r_avg, g_avg, b_avg, 255)
                            
                except Exception as e:
                    log(f"处理模组纹理文件时出错 {texture_path}: {str(e)}")
                    continue
    
    except Exception as e:
        log(f"处理模组JAR文件时出错 {mod_jar_path}: {str(e)}")
    
    # 将提取的颜色保存到缓存
    if extracted_colors and not os.environ.get('DISABLE_COLOR_CACHE'):
        save_mod_colors_to_cache(mod_jar_path, extracted_colors)
    
    return extracted_colors

def find_mods_folder(minecraft_dir):
    """
    查找Minecraft的mods文件夹
    
    Args:
        minecraft_dir: Minecraft安装目录
    
    Returns:
        mods文件夹路径，如果找不到则返回None
    """
    log(f"尝试在以下位置查找mods文件夹: {minecraft_dir}")
    
    # 检查直接在.minecraft下的mods文件夹
    main_mods = os.path.join(minecraft_dir, "mods")
    if os.path.isdir(main_mods):
        log(f"找到主mods文件夹: {main_mods}")
        return main_mods
    
    # 检查各个版本目录下的mods文件夹
    versions_dir = os.path.join(minecraft_dir, "versions")
    if os.path.isdir(versions_dir):
        log(f"检查versions目录: {versions_dir}")
        for version in os.listdir(versions_dir):
            version_mods = os.path.join(versions_dir, version, "mods")
            if os.path.isdir(version_mods):
                log(f"找到版本 {version} 的mods文件夹: {version_mods}")
                return version_mods
    
    # 特殊处理：如果minecraft_dir本身是一个版本目录，检查其下的mods
    if "versions" in minecraft_dir:
        direct_mods = os.path.join(minecraft_dir, "mods")
        if os.path.isdir(direct_mods):
            log(f"找到直接位于版本目录下的mods文件夹: {direct_mods}")
            return direct_mods
        
        # 向上一级查找
        parent_dir = os.path.dirname(minecraft_dir)
        parent_mods = os.path.join(parent_dir, "mods")
        if os.path.isdir(parent_mods):
            log(f"找到父目录中的mods文件夹: {parent_mods}")
            return parent_mods
    
    # 从存档路径推断
    if "saves" in minecraft_dir:
        # 尝试从存档路径推断整合包目录
        save_parent = os.path.dirname(minecraft_dir)
        save_parent_mods = os.path.join(save_parent, "mods")
        if os.path.isdir(save_parent_mods):
            log(f"从存档路径推断的mods文件夹: {save_parent_mods}")
            return save_parent_mods
    
    # 处理特殊的整合包结构，如"Create Flavored 510"
    # 在路径中查找"versions"并检查该版本目录下的mods
    path_parts = minecraft_dir.split(os.sep)
    for i, part in enumerate(path_parts):
        if part == "versions" and i + 1 < len(path_parts):
            version_name = path_parts[i + 1]
            version_dir = os.path.join(*path_parts[:i+2])  # 构建到版本目录的路径
            version_mods = os.path.join(version_dir, "mods")
            if os.path.isdir(version_mods):
                log(f"找到整合包 {version_name} 的mods文件夹: {version_mods}")
                return version_mods
    
    # 尝试在AppData/Roaming/.minecraft中查找
    if os.name == 'nt':  # Windows
        appdata = os.environ.get('APPDATA', '')
        if appdata:
            default_mods = os.path.join(appdata, ".minecraft", "mods")
            if os.path.isdir(default_mods):
                log(f"找到默认的.minecraft/mods文件夹: {default_mods}")
                return default_mods
    
    # 最后一次尝试：手动指定的路径
    # 检查环境变量中是否有指定的mods路径
    mods_path = os.environ.get('MINECRAFT_MODS_PATH')
    if mods_path and os.path.isdir(mods_path):
        log(f"使用环境变量指定的mods路径: {mods_path}")
        return mods_path
    
    # 特殊处理"Create Flavored 510"这样的整合包
    if "versions" in minecraft_dir:
        # 尝试提取版本名称
        for part in minecraft_dir.split(os.sep):
            if "Create" in part or "Fabric" in part or "Forge" in part:
                # 构建可能的路径
                possible_path = os.path.join(os.environ.get('APPDATA', ''), ".minecraft", "versions", part, "mods")
                if os.path.isdir(possible_path):
                    log(f"找到整合包特定路径: {possible_path}")
                    return possible_path
    
    # 直接检查用户提供的路径
    create_flavored_path = os.path.join(os.environ.get('APPDATA', ''), ".minecraft", "versions", "Create  Flavored 510", "mods")
    if os.path.isdir(create_flavored_path):
        log(f"找到Create Flavored整合包的mods文件夹: {create_flavored_path}")
        return create_flavored_path
    
    log("未能找到任何mods文件夹")
    return None

def extract_colors_from_mods(minecraft_dir):
    """
    从所有模组中提取方块颜色
    
    Args:
        minecraft_dir: Minecraft安装目录
    
    Returns:
        包含所有模组方块颜色的字典
    """
    all_mod_colors = {}
    
    # 查找mods文件夹
    mods_dir = find_mods_folder(minecraft_dir)
    if not mods_dir:
        log("未找到mods文件夹")
        return all_mod_colors
    
    log(f"找到mods文件夹: {mods_dir}")
    
    # 获取所有JAR文件
    mod_files = [os.path.join(mods_dir, f) for f in os.listdir(mods_dir) if f.endswith('.jar')]
    
    if not mod_files:
        log("mods文件夹中未找到JAR文件")
        return all_mod_colors
    
    log(f"在mods文件夹中找到 {len(mod_files)} 个JAR文件")
    
    # 首先尝试加载整体缓存
    if not os.environ.get('DISABLE_COLOR_CACHE'):
        cache_dir = get_cache_directory()
        mods_cache_file = os.path.join(cache_dir, f"all_mods_colors_{hash(mods_dir)}.json")
        
        # 检查是否存在有效的整体缓存
        if os.path.exists(mods_cache_file):
            try:
                # 检查缓存文件的修改时间是否晚于所有mod文件的修改时间
                cache_mtime = os.path.getmtime(mods_cache_file)
                all_mods_newer = all(os.path.getmtime(mod_file) < cache_mtime for mod_file in mod_files)
                
                if all_mods_newer:
                    # 如果缓存是最新的，直接加载
                    with open(mods_cache_file, 'r', encoding='utf-8') as f:
                        json_colors = json.load(f)
                    
                    # 将十六进制字符串转换回颜色元组
                    colors = {}
                    for block_id, hex_color in json_colors.items():
                        hex_color = hex_color.lstrip('#')
                        if len(hex_color) == 6:
                            # RGB颜色
                            r = int(hex_color[0:2], 16)
                            g = int(hex_color[2:4], 16)
                            b = int(hex_color[4:6], 16)
                            colors[block_id] = (r, g, b)
                        elif len(hex_color) == 8:
                            # RGBA颜色
                            r = int(hex_color[0:2], 16)
                            g = int(hex_color[2:4], 16)
                            b = int(hex_color[4:6], 16)
                            a = int(hex_color[6:8], 16)
                            colors[block_id] = (r, g, b, a)
                    
                    log(f"从整体缓存加载所有模组颜色: {len(colors)} 个方块")
                    return colors
            except Exception as e:
                log(f"加载整体模组颜色缓存时出错: {e}")
    
    # 如果没有有效的整体缓存，处理每个模组JAR文件
    for mod_file in mod_files:
        try:
            log(f"正在处理模组: {os.path.basename(mod_file)}")
            mod_colors = extract_colors_from_mod_jar(mod_file)
            log(f"从模组 {os.path.basename(mod_file)} 中提取了 {len(mod_colors)} 个方块颜色")
            
            # 合并颜色
            all_mod_colors.update(mod_colors)
        except Exception as e:
            log(f"处理模组 {os.path.basename(mod_file)} 时出错: {str(e)}")
    
    # 保存整体缓存
    if not os.environ.get('DISABLE_COLOR_CACHE'):
        try:
            # 将颜色元组转换为十六进制字符串以便于存储
            json_colors = {}
            for block_id, color in all_mod_colors.items():
                if len(color) == 3:
                    # RGB颜色
                    json_colors[block_id] = f"#{color[0]:02x}{color[1]:02x}{color[2]:02x}"
                else:
                    # RGBA颜色
                    json_colors[block_id] = f"#{color[0]:02x}{color[1]:02x}{color[2]:02x}{color[3]:02x}"
            
            cache_dir = get_cache_directory()
            mods_cache_file = os.path.join(cache_dir, f"all_mods_colors_{hash(mods_dir)}.json")
            
            with open(mods_cache_file, 'w', encoding='utf-8') as f:
                json.dump(json_colors, f, indent=2, ensure_ascii=False)
            
            log(f"已将所有模组颜色保存到整体缓存: {mods_cache_file}")
        except Exception as e:
            log(f"保存整体模组颜色缓存时出错: {e}")
    
    log(f"总共从所有模组中提取了 {len(all_mod_colors)} 个方块颜色")
    return all_mod_colors

def update_block_colors_from_jar(saves_path):
    """
    从Minecraft jar文件更新方块颜色字典
    
    Args:
        saves_path: Minecraft存档的路径
    
    Returns:
        更新后的方块颜色字典
    """
    global BLOCK_COLORS
    
    # 尝试直接从环境变量获取JAR路径
    jar_path = os.environ.get('MINECRAFT_JAR_PATH')
    
    # 如果环境变量中有指定的JAR路径，直接使用
    if jar_path and os.path.exists(jar_path):
        log(f"使用环境变量指定的JAR文件: {jar_path}")
    else:
        # 输出存档路径以便调试
        log(f"当前存档路径: {saves_path}")
        
        # 添加手动硬编码路径尝试
        possible_jar_paths = [
            # Fabulously Optimized 路径
            os.path.join(os.environ.get('APPDATA', ''), ".minecraft", "versions", "Fabulously Optimized 1214", "Fabulously Optimized 1214.jar"),
            # 其他可能的路径
            os.path.join(os.environ.get('APPDATA', ''), ".minecraft", "versions", "1.21.1", "1.21.1.jar"),
            os.path.join(os.environ.get('APPDATA', ''), ".minecraft", "versions", "1.21", "1.21.jar"),
            os.path.join(os.environ.get('APPDATA', ''), ".minecraft", "versions", "1.20.4", "1.20.4.jar"),
            os.path.join(os.environ.get('APPDATA', ''), ".minecraft", "versions", "1.20.1", "1.20.1.jar"),
        ]
        
        # 尝试这些硬编码路径
        for path in possible_jar_paths:
            if os.path.exists(path):
                jar_path = path
                log(f"找到硬编码路径的JAR文件: {jar_path}")
                break
        
        # 如果没有找到，尝试查找函数
        if not jar_path or not os.path.exists(jar_path):
            jar_path = find_minecraft_jar(saves_path)
    
    if not jar_path or not os.path.exists(jar_path):
        log("未找到Minecraft JAR文件，将使用内置的方块颜色")
        return BLOCK_COLORS
    
    log(f"找到Minecraft JAR文件: {jar_path}")
    
    # 从jar文件提取颜色信息
    extracted_colors = extract_block_colors_from_jar(jar_path)
    if not extracted_colors:
        log("无法从JAR文件提取方块颜色，将使用内置的方块颜色")
        return BLOCK_COLORS
    
    log(f"从JAR文件成功提取了 {len(extracted_colors)} 种方块的颜色")
    
    # 检查是否禁用了模组颜色提取
    if not os.environ.get('DISABLE_MODS_EXTRACTION'):
        # 尝试从mods文件夹提取模组方块颜色
        try:
            # 推断Minecraft目录
            minecraft_dir = os.path.dirname(os.path.dirname(jar_path))
            if "versions" in minecraft_dir:
                minecraft_dir = os.path.dirname(os.path.dirname(minecraft_dir))
            
            log(f"推断的Minecraft目录: {minecraft_dir}")
            
            # 提取模组颜色
            mod_colors = extract_colors_from_mods(minecraft_dir)
            
            # 将模组颜色添加到提取的颜色中
            if mod_colors:
                log(f"将 {len(mod_colors)} 个模组方块颜色添加到颜色表中")
                extracted_colors.update(mod_colors)
        except Exception as e:
            log(f"处理模组时出错: {str(e)}")
    else:
        log("模组方块颜色提取已禁用")
    
    # 合并提取的颜色和内置颜色
    updated_colors = BLOCK_COLORS.copy()
    for block_id, color in extracted_colors.items():
        # 如果方块ID已存在，则只在内置颜色不存在时才更新
        if block_id not in updated_colors:
            updated_colors[block_id] = color
    
    # 如果提取的颜色数量超过内置颜色的2倍，就完全替换
    if len(extracted_colors) > len(BLOCK_COLORS) * 2:
        log("提取的颜色数量远大于内置颜色，将使用提取的颜色")
        # 保留内置的特殊方块颜色（如air, none等）
        for special_block in ["air", "cave_air", "void_air", "none"]:
            if special_block in BLOCK_COLORS and special_block not in extracted_colors:
                extracted_colors[special_block] = BLOCK_COLORS[special_block]
        updated_colors = extracted_colors
    
    # 更新全局变量
    BLOCK_COLORS = updated_colors
    
    # 清空颜色缓存，因为颜色表已更新
    COLOR_CACHE.clear()
    
    # 将更新后的颜色表保存为JSON文件
    try:
        # 获取临时目录路径
        temp_dir = os.environ.get('TEMP') or os.environ.get('TMP') or '.'
        json_path = os.path.join(temp_dir, f"block_colors_{int(time.time())}.json")
        
        # 将颜色元组转换为十六进制字符串以便于阅读
        json_colors = {}
        for block_id, color in updated_colors.items():
            if len(color) == 3:
                # RGB颜色
                json_colors[block_id] = f"#{color[0]:02x}{color[1]:02x}{color[2]:02x}"
            else:
                # RGBA颜色
                json_colors[block_id] = f"#{color[0]:02x}{color[1]:02x}{color[2]:02x}{color[3]:02x}"
        
        with open(json_path, 'w', encoding='utf-8') as f:
            json.dump(json_colors, f, indent=2, ensure_ascii=False, sort_keys=True)
        
        log(f"已将更新后的方块颜色保存到: {json_path}")
    except Exception as e:
        log(f"保存颜色JSON文件时出错: {e}")
    
    return updated_colors

def get_cache_directory():
    """获取缓存目录路径，如果不存在则创建"""
    temp_dir = os.environ.get('TEMP') or os.environ.get('TMP') or '.'
    cache_dir = os.path.join(temp_dir, "minecraft_map_cache")
    
    # 如果缓存目录不存在，创建它
    if not os.path.exists(cache_dir):
        try:
            os.makedirs(cache_dir)
        except Exception as e:
            log(f"创建缓存目录失败: {e}")
            return temp_dir  # 如果创建失败，使用临时目录
    
    return cache_dir

def get_mod_cache_file(mod_jar_path):
    """获取模组颜色缓存文件路径"""
    # 使用模组文件名和最后修改时间作为缓存文件名的一部分
    mod_name = os.path.basename(mod_jar_path)
    mod_mtime = os.path.getmtime(mod_jar_path)
    
    # 创建一个唯一的缓存文件名
    cache_filename = f"{mod_name}_{int(mod_mtime)}_colors.json"
    
    # 替换可能在文件名中不合法的字符
    cache_filename = cache_filename.replace(" ", "_").replace(":", "_")
    
    return os.path.join(get_cache_directory(), cache_filename)

def save_mod_colors_to_cache(mod_jar_path, colors):
    """将模组颜色保存到缓存文件"""
    cache_file = get_mod_cache_file(mod_jar_path)
    
    try:
        # 将颜色元组转换为十六进制字符串以便于存储
        json_colors = {}
        for block_id, color in colors.items():
            if len(color) == 3:
                # RGB颜色
                json_colors[block_id] = f"#{color[0]:02x}{color[1]:02x}{color[2]:02x}"
            else:
                # RGBA颜色
                json_colors[block_id] = f"#{color[0]:02x}{color[1]:02x}{color[2]:02x}{color[3]:02x}"
        
        with open(cache_file, 'w', encoding='utf-8') as f:
            json.dump(json_colors, f, indent=2, ensure_ascii=False)
        
        log(f"已将模组 {os.path.basename(mod_jar_path)} 的颜色缓存到: {cache_file}")
        return True
    except Exception as e:
        log(f"保存模组颜色缓存时出错: {e}")
        return False

def load_mod_colors_from_cache(mod_jar_path):
    """从缓存文件加载模组颜色"""
    cache_file = get_mod_cache_file(mod_jar_path)
    
    if not os.path.exists(cache_file):
        return None
    
    try:
        with open(cache_file, 'r', encoding='utf-8') as f:
            json_colors = json.load(f)
        
        # 将十六进制字符串转换回颜色元组
        colors = {}
        for block_id, hex_color in json_colors.items():
            hex_color = hex_color.lstrip('#')
            if len(hex_color) == 6:
                # RGB颜色
                r = int(hex_color[0:2], 16)
                g = int(hex_color[2:4], 16)
                b = int(hex_color[4:6], 16)
                colors[block_id] = (r, g, b)
            elif len(hex_color) == 8:
                # RGBA颜色
                r = int(hex_color[0:2], 16)
                g = int(hex_color[2:4], 16)
                b = int(hex_color[4:6], 16)
                a = int(hex_color[6:8], 16)
                colors[block_id] = (r, g, b, a)
        
        log(f"从缓存加载模组 {os.path.basename(mod_jar_path)} 的颜色: {len(colors)} 个方块")
        return colors
    except Exception as e:
        log(f"加载模组颜色缓存时出错: {e}")
        return None

def clear_color_cache():
    """清除所有颜色缓存文件"""
    cache_dir = get_cache_directory()
    
    try:
        # 获取缓存目录中的所有JSON文件
        cache_files = [f for f in os.listdir(cache_dir) if f.endswith('.json')]
        
        if not cache_files:
            log("没有找到颜色缓存文件")
            return 0
        
        # 删除每个缓存文件
        deleted_count = 0
        for cache_file in cache_files:
            try:
                file_path = os.path.join(cache_dir, cache_file)
                os.remove(file_path)
                deleted_count += 1
            except Exception as e:
                log(f"删除缓存文件时出错 {cache_file}: {e}")
        
        log(f"已清除 {deleted_count} 个颜色缓存文件")
        return deleted_count
    except Exception as e:
        log(f"清除缓存时出错: {e}")
        return 0

def main():
    #记录开始时间
    overall_start_time = time.time()
    
    if len(sys.argv) < 2:
        log("用法: python a.py <mca文件路径> [输出JSON文件路径] [输出图像文件路径] [线程数] [区域大小]")
        log("示例: python a.py C:/Users/LowH/Desktop/apps/.minecraft/versions/1.21.1-NeoForge_21.1.172/saves/新的世界/region/r.0.0.mca blocks.json map.png 8 32")
        log("参数说明:")
        log("  mca文件路径: 必需，.mca区域文件路径")
        log("  输出JSON文件路径: 可选，默认为blocks.json")
        log("  输出图像文件路径: 可选，默认为map.png")
        log("  线程数: 可选，默认为CPU核心数或8（取较小值）")
        log("  区域大小: 可选，以区块为单位，默认32（即32x32区块，共1024个区块）")
        return
    
    mca_file_path = sys.argv[1]
    json_output = sys.argv[2] if len(sys.argv) > 2 else "blocks.json"
    image_output = sys.argv[3] if len(sys.argv) > 3 else "map.png"
    max_workers = int(sys.argv[4]) if len(sys.argv) > 4 else min(os.cpu_count(), 8)  #默认使用CPU核心数或8（取较小值）
    region_size = int(sys.argv[5]) if len(sys.argv) > 5 else 32  #默认处理32x32区块
    
    log(f"正在处理区域文件: {mca_file_path}")
    log(f"JSON输出路径: {json_output}")
    log(f"图像输出路径: {image_output}")
    log(f"使用线程数: {max_workers}")
    log(f"处理区域大小: {region_size}x{region_size} 区块")
    
    #读取区域文件
    top_blocks = get_top_blocks(mca_file_path, max_workers, region_size)
    
    if top_blocks is not None:
        log("\n区域文件读取成功!")
        
        #保存为JSON
        save_blocks_to_json(top_blocks, json_output)
        
        #直接渲染PNG
        direct_render_to_png(top_blocks, image_output)
        
        #输出总体统计信息
        total_time = time.time() - overall_start_time
        log(f"\n所有处理完成!")
        log(f"总耗时: {total_time:.2f}秒")
        
        #释放内存
        del top_blocks

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n程序被用户中断!")
    except Exception as e:
        print(f"程序执行出错: {e}")
        import traceback
        traceback.print_exc()
