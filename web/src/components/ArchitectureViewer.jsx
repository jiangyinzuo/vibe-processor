import React, { useState, useRef, useEffect } from 'react';
import * as d3 from 'd3';
import ModuleNode from './ModuleNode';
import Connection from './Connection';
import Breadcrumb from './Breadcrumb';
import DataFlowAnimation from './DataFlowAnimation';
import PerfOverlay from './PerfOverlay';

function ArchitectureViewer({ configData }) {
  const [currentPath, setCurrentPath] = useState([]);
  const [showDataFlow, setShowDataFlow] = useState(false);
  const [showPerf, setShowPerf] = useState(false);
  const [hoveredModule, setHoveredModule] = useState(null);
  const svgRef = useRef(null);
  const [transform, setTransform] = useState({ x: 0, y: 0, k: 1 });

  // 获取当前层级的模块
  const getCurrentModules = () => {
    let modules = configData.modules;
    for (const id of currentPath) {
      const parent = findModuleById(modules, id);
      modules = parent?.children || [];
    }
    return modules;
  };

  // 获取当前层级的连接
  const getCurrentConnections = () => {
    if (currentPath.length === 0) {
      return configData.connections || [];
    }
    // 子层级的连接需要从父模块中获取
    const parent = findModuleById(configData.modules, currentPath[currentPath.length - 1]);
    return parent?.connections || [];
  };

  // 递归查找模块
  const findModuleById = (modules, id) => {
    for (const module of modules) {
      if (module.id === id) return module;
      if (module.children) {
        const found = findModuleById(module.children, id);
        if (found) return found;
      }
    }
    return null;
  };

  // 处理模块点击
  const handleModuleClick = (moduleId) => {
    const modules = getCurrentModules();
    const module = modules.find(m => m.id === moduleId);
    if (module?.children && module.children.length > 0) {
      setCurrentPath([...currentPath, moduleId]);
    }
  };

  // 面包屑导航
  const handleBreadcrumbClick = (index) => {
    setCurrentPath(currentPath.slice(0, index));
  };

  // 获取面包屑路径
  const getBreadcrumbPath = () => {
    const path = [{ id: 'top', name: configData.name }];
    let modules = configData.modules;
    for (const id of currentPath) {
      const module = findModuleById(modules, id);
      if (module) {
        path.push({ id: module.id, name: module.name });
        modules = module.children || [];
      }
    }
    return path;
  };

  // 设置缩放和平移
  useEffect(() => {
    if (!svgRef.current) return;

    const svg = d3.select(svgRef.current);
    const zoom = d3.zoom()
      .scaleExtent([0.5, 3])
      .on('zoom', (event) => {
        setTransform({
          x: event.transform.x,
          y: event.transform.y,
          k: event.transform.k
        });
      });

    svg.call(zoom);

    return () => {
      svg.on('.zoom', null);
    };
  }, []);

  const modules = getCurrentModules();
  const connections = getCurrentConnections();
  const breadcrumbPath = getBreadcrumbPath();

  return (
    <div className="architecture-viewer">
      <div className="viewer-controls">
        <Breadcrumb path={breadcrumbPath} onNavigate={handleBreadcrumbClick} />
        <div className="control-buttons">
          <button
            className={showDataFlow ? 'active' : ''}
            onClick={() => setShowDataFlow(!showDataFlow)}
          >
            {showDataFlow ? '隐藏' : '显示'}数据流
          </button>
          <button
            className={showPerf ? 'active' : ''}
            onClick={() => setShowPerf(!showPerf)}
          >
            {showPerf ? '隐藏' : '显示'}性能数据
          </button>
        </div>
      </div>

      <svg
        ref={svgRef}
        className="architecture-svg"
        width={configData.viewport.width}
        height={configData.viewport.height}
      >
        <defs>
          <marker
            id="arrowhead"
            markerWidth="10"
            markerHeight="10"
            refX="9"
            refY="3"
            orient="auto"
          >
            <polygon points="0 0, 10 3, 0 6" fill="#37474F" />
          </marker>
        </defs>

        <g transform={`translate(${transform.x},${transform.y}) scale(${transform.k})`}>
          {/* 渲染连接线 */}
          {connections.map(conn => (
            <Connection
              key={conn.id}
              connection={conn}
              modules={modules}
            />
          ))}

          {/* 渲染模块节点 */}
          {modules.map(module => (
            <ModuleNode
              key={module.id}
              module={module}
              onClick={handleModuleClick}
              onHover={setHoveredModule}
              isHovered={hoveredModule === module.id}
            />
          ))}

          {/* 数据流动画 */}
          {showDataFlow && configData.dataFlows && (
            <DataFlowAnimation
              dataFlows={configData.dataFlows}
              modules={configData.modules}
              currentPath={currentPath}
            />
          )}

          {/* 性能数据叠加 */}
          {showPerf && configData.perfCounters && (
            <PerfOverlay
              perfData={configData.perfCounters}
              modules={modules}
            />
          )}
        </g>

        {/* Tooltip 层 - 始终在最顶层 */}
        <g transform={`translate(${transform.x},${transform.y}) scale(${transform.k})`}>
          {hoveredModule && modules.map(module => {
            if (module.id === hoveredModule && module.metadata) {
              const tooltipX = module.position.x + module.size.width + 10;
              const tooltipY = module.position.y;
              const entries = Object.entries(module.metadata);
              const maxTextLength = Math.max(
                ...entries.map(([key, value]) => `${key}: ${value}`.length)
              );
              const tooltipWidth = Math.min(Math.max(maxTextLength * 7, 200), 400);
              const lineHeight = 18;
              const padding = 10;
              const tooltipHeight = entries.length * lineHeight + padding * 2;

              return (
                <g key={`tooltip-${module.id}`} className="tooltip-layer">
                  <rect
                    x={tooltipX}
                    y={tooltipY}
                    width={tooltipWidth}
                    height={tooltipHeight}
                    fill="#FFFFFF"
                    stroke="#37474F"
                    strokeWidth={2}
                    rx={4}
                    style={{ filter: 'drop-shadow(2px 2px 4px rgba(0,0,0,0.3))' }}
                  />
                  {entries.map(([key, value], i) => (
                    <text
                      key={key}
                      x={tooltipX + padding}
                      y={tooltipY + padding + (i + 1) * lineHeight - 4}
                      fontSize={11}
                      fill="#263238"
                    >
                      <tspan fontWeight="bold">{key}:</tspan> {value}
                    </text>
                  ))}
                </g>
              );
            }
            return null;
          })}
        </g>
      </svg>
    </div>
  );
}

export default ArchitectureViewer;
