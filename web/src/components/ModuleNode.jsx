import React, { useState } from 'react';

function ModuleNode({ module, onClick, onHover, isHovered }) {
  const hasChildren = module.children && module.children.length > 0;

  const handleClick = () => {
    if (hasChildren) {
      onClick(module.id);
    }
  };

  const handleMouseEnter = () => {
    if (onHover) {
      onHover(module.id);
    }
  };

  const handleMouseLeave = () => {
    if (onHover) {
      onHover(null);
    }
  };

  const renderShape = () => {
    const { position, size, style } = module;
    const commonProps = {
      fill: style.fill,
      stroke: isHovered ? '#1976D2' : style.stroke,
      strokeWidth: isHovered ? style.strokeWidth + 1 : style.strokeWidth,
      cursor: hasChildren ? 'pointer' : 'default',
      onMouseEnter: handleMouseEnter,
      onMouseLeave: handleMouseLeave,
      onClick: handleClick,
    };

    if (style.shape === 'cylinder') {
      // 圆柱体：顶部椭圆 + 矩形 + 底部椭圆
      const rx = size.width / 2;
      const ry = 15;
      return (
        <g>
          <ellipse
            cx={position.x + rx}
            cy={position.y + ry}
            rx={rx}
            ry={ry}
            {...commonProps}
          />
          <rect
            x={position.x}
            y={position.y + ry}
            width={size.width}
            height={size.height - ry}
            {...commonProps}
          />
          <ellipse
            cx={position.x + rx}
            cy={position.y + size.height}
            rx={rx}
            ry={ry}
            fill={style.fill}
            stroke={commonProps.stroke}
            strokeWidth={commonProps.strokeWidth}
          />
        </g>
      );
    } else {
      // 默认矩形
      return (
        <rect
          x={position.x}
          y={position.y}
          width={size.width}
          height={size.height}
          rx={0}
          ry={0}
          {...commonProps}
        />
      );
    }
  };

  const renderText = () => {
    const { position, size, name, description } = module;
    const centerX = position.x + size.width / 2;
    const centerY = position.y + size.height / 2;

    const lines = [name];
    if (description) {
      lines.push(...description.split('\n'));
    }

    const lineHeight = 16;
    const startY = centerY - ((lines.length - 1) * lineHeight) / 2;

    return (
      <g pointerEvents="none">
        {lines.map((line, i) => (
          <text
            key={i}
            x={centerX}
            y={startY + i * lineHeight}
            textAnchor="middle"
            dominantBaseline="middle"
            fontSize={i === 0 ? 14 : 12}
            fontWeight={i === 0 ? 'bold' : 'normal'}
            fill="#263238"
          >
            {line}
          </text>
        ))}
      </g>
    );
  };

  return (
    <g className="module-node">
      {renderShape()}
      {renderText()}
    </g>
  );
}

export default ModuleNode;
