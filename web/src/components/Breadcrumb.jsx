import React from 'react';

function Breadcrumb({ path, onNavigate }) {
  return (
    <div className="breadcrumb">
      {path.map((item, index) => (
        <span key={item.id} className="breadcrumb-item">
          {index > 0 && <span className="breadcrumb-separator"> &gt; </span>}
          <button
            className="breadcrumb-link"
            onClick={() => onNavigate(index)}
            disabled={index === path.length - 1}
          >
            {item.name}
          </button>
        </span>
      ))}
    </div>
  );
}

export default Breadcrumb;
