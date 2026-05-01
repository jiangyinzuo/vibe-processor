import React from 'react';

function Connection({ connection, modules }) {
  const sourceModule = modules.find(m => m.id === connection.source);
  const targetModule = modules.find(m => m.id === connection.target);

  if (!sourceModule || !targetModule) return null;

  // 计算连接点（模块中心）
  const sourceX = sourceModule.position.x + sourceModule.size.width / 2;
  const sourceY = sourceModule.position.y + sourceModule.size.height / 2;
  const targetX = targetModule.position.x + targetModule.size.width / 2;
  const targetY = targetModule.position.y + targetModule.size.height / 2;

  // 计算标签位置（路径中点）
  const labelX = (sourceX + targetX) / 2;
  const labelY = (sourceY + targetY) / 2;

  const style = connection.style || {};
  const animated = style.animated || false;

  return (
    <g className="connection">
      <line
        x1={sourceX}
        y1={sourceY}
        x2={targetX}
        y2={targetY}
        stroke={style.stroke || '#546E7A'}
        strokeWidth={style.strokeWidth || 2}
        markerEnd="url(#arrowhead)"
        strokeDasharray={animated ? '5,5' : 'none'}
      >
        {animated && (
          <animate
            attributeName="stroke-dashoffset"
            from="0"
            to="10"
            dur="1s"
            repeatCount="indefinite"
          />
        )}
      </line>
      {connection.label && (
        <text
          x={labelX}
          y={labelY - 5}
          textAnchor="middle"
          fontSize={11}
          fill="#263238"
          fontWeight="bold"
        >
          {connection.label.split('\n').map((line, i) => (
            <tspan key={i} x={labelX} dy={i === 0 ? 0 : 14}>
              {line}
            </tspan>
          ))}
        </text>
      )}
    </g>
  );
}

export default Connection;
