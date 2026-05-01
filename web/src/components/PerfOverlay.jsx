import React from 'react';

function PerfOverlay({ perfData, modules }) {
  const getUtilizationColor = (value, max) => {
    const ratio = value / max;
    if (ratio < 0.3) return '#4CAF50'; // 绿色
    if (ratio < 0.7) return '#FFC107'; // 黄色
    return '#F44336'; // 红色
  };

  const renderPerfData = (module) => {
    const data = perfData[module.id];
    if (!data) return null;

    const { position, size } = module;

    // 计算利用率（示例：基于 totalCycles）
    const maxCycles = 10000;
    const utilization = data.totalCycles || 0;
    const color = getUtilizationColor(utilization, maxCycles);

    return (
      <g key={module.id} className="perf-overlay">
        {/* 半透明覆盖层 */}
        <rect
          x={position.x}
          y={position.y}
          width={size.width}
          height={size.height}
          fill={color}
          opacity={0.3}
          pointerEvents="none"
        />
        {/* 性能数据文本 */}
        <text
          x={position.x + 5}
          y={position.y + 15}
          fontSize={10}
          fill="#000"
          fontWeight="bold"
          pointerEvents="none"
        >
          {Object.entries(data).map(([key, value], i) => (
            <tspan key={key} x={position.x + 5} dy={i === 0 ? 0 : 12}>
              {key}: {value}
            </tspan>
          ))}
        </text>
      </g>
    );
  };

  return (
    <g className="perf-overlay-group">
      {modules.map(module => renderPerfData(module))}
    </g>
  );
}

export default PerfOverlay;
