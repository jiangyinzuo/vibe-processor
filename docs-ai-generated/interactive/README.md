# 交互式架构图

Vibe Processor 的交互式架构图查看器，使用 React + D3.js 构建。

## 功能特性

### 1. 模块层级导航
- 点击任何包含子模块的模块（如 AiCore、SM）可以展开查看内部结构
- 使用顶部面包屑导航返回上级或顶层视图
- 示例：Top → AiCore 0 → CubeUnit → SystolicArray

### 2. 精确布局控制
- 所有模块的位置、尺寸通过 JSON 配置文件精确定义
- 连接线使用直线风格，符合专业芯片架构图规范
- 支持不同形状：矩形（功能单元）、圆柱体（存储单元）

### 3. 交互功能
- **缩放**：鼠标滚轮缩放视图（0.5x - 3x）
- **平移**：鼠标拖拽移动画布
- **悬停提示**：鼠标悬停在模块上显示详细元数据（容量、延迟、源文件路径）
- **架构切换**：顶部按钮一键切换 NPU 和 GPU 架构

## 使用方法

### 在线查看
直接在浏览器中打开 `index.html` 文件：
```bash
# 方式 1：双击文件
open docs/interactive/index.html

# 方式 2：使用本地服务器
cd docs/interactive
python3 -m http.server 8000
# 访问 http://localhost:8000
```

## 架构配置

架构图的数据来源于 JSON 配置文件：
- `web/src/data/npu_architecture.json` - NPU 架构配置
- `web/src/data/gpu_architecture.json` - GPU 架构配置

### JSON 配置格式

```json
{
  "name": "ToyAscendTop",
  "viewport": { "width": 1400, "height": 900 },
  "modules": [
    {
      "id": "hbm",
      "name": "HBM Model",
      "type": "memory",
      "description": "片外 HBM Model\\n4096×128b\\nLatency=10",
      "position": { "x": 100, "y": 50 },
      "size": { "width": 180, "height": 120 },
      "style": {
        "fill": "#CFD8DC",
        "stroke": "#37474F",
        "strokeWidth": 3,
        "shape": "cylinder"
      },
      "metadata": {
        "capacity": "4096×128b",
        "latency": 10,
        "sourceFile": "src/main/scala/common/Hbm.scala"
      },
      "children": []
    }
  ],
  "connections": [
    {
      "id": "conn1",
      "source": "hbm",
      "target": "l2",
      "label": "外部预加载",
      "style": {
        "stroke": "#546E7A",
        "strokeWidth": 3,
        "animated": false
      }
    }
  ]
}
```

## 开发

### 环境要求
- Node.js 18+
- npm 或 yarn

### 本地开发
```bash
cd web
npm install
npm run dev
# 访问 http://localhost:5173
```

### 构建
```bash
npm run build
# 输出到 web/dist/index.html（单文件 HTML）
```

### 更新架构图
1. 修改 `web/src/data/*.json` 配置文件
2. 运行 `npm run build`
3. 复制 `web/dist/index.html` 到 `docs/interactive/index.html`

## 技术栈

- **React 18** - UI 组件框架
- **D3.js 7** - SVG 渲染和动画
- **Vite 5** - 构建工具
- **vite-plugin-singlefile** - 单文件 HTML 打包

## 文件结构

```
web/
├── src/
│   ├── App.jsx                      # 主应用
│   ├── components/
│   │   ├── ArchitectureViewer.jsx   # 架构图查看器
│   │   ├── ModuleNode.jsx           # 模块节点渲染
│   │   ├── Connection.jsx           # 连接线渲染
│   │   ├── Breadcrumb.jsx           # 面包屑导航
│   │   ├── DataFlowAnimation.jsx    # 数据流动画
│   │   └── PerfOverlay.jsx          # 性能数据叠加
│   ├── data/
│   │   ├── npu_architecture.json    # NPU 配置
│   │   └── gpu_architecture.json    # GPU 配置
│   └── styles/
│       └── architecture.css         # 样式文件
├── package.json
├── vite.config.js
└── index.html
```

## 许可证

与主项目相同。
