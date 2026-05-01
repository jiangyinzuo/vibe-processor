import React, { useState, useEffect } from 'react';

function DataFlowAnimation({ dataFlows, modules, currentPath }) {
  const [animationProgress, setAnimationProgress] = useState(0);
  const [isPlaying, setIsPlaying] = useState(false);

  useEffect(() => {
    if (!isPlaying) return;

    const interval = setInterval(() => {
      setAnimationProgress(prev => (prev + 0.01) % 1);
    }, 30);

    return () => clearInterval(interval);
  }, [isPlaying]);

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

  const getModuleCenter = (moduleId) => {
    const module = findModuleById(modules, moduleId);
    if (!module) return null;
    return {
      x: module.position.x + module.size.width / 2,
      y: module.position.y + module.size.height / 2,
    };
  };

  const renderDataFlow = (flow) => {
    const points = flow.path.map(id => getModuleCenter(id)).filter(p => p !== null);
    if (points.length < 2) return null;

    // 计算当前位置
    const totalSegments = points.length - 1;
    const currentSegment = Math.floor(animationProgress * totalSegments);
    const segmentProgress = (animationProgress * totalSegments) % 1;

    if (currentSegment >= totalSegments) return null;

    const start = points[currentSegment];
    const end = points[currentSegment + 1];
    const x = start.x + (end.x - start.x) * segmentProgress;
    const y = start.y + (end.y - start.y) * segmentProgress;

    return (
      <g key={flow.name}>
        {/* 绘制路径 */}
        {points.map((point, i) => {
          if (i === points.length - 1) return null;
          const next = points[i + 1];
          return (
            <line
              key={i}
              x1={point.x}
              y1={point.y}
              x2={next.x}
              y2={next.y}
              stroke={flow.color || '#FF6F00'}
              strokeWidth={2}
              strokeDasharray="5,5"
              opacity={0.3}
            />
          );
        })}
        {/* 移动的数据点 */}
        <circle
          cx={x}
          cy={y}
          r={8}
          fill={flow.color || '#FF6F00'}
          opacity={0.8}
        />
      </g>
    );
  };

  return (
    <g className="data-flow-animation">
      {isPlaying && dataFlows.map(flow => renderDataFlow(flow))}
    </g>
  );
}

export default DataFlowAnimation;
