import React, { useState } from 'react';
import ArchitectureViewer from './components/ArchitectureViewer';
import npuData from './data/npu_architecture.json';
import gpuData from './data/gpu_architecture.json';

function App() {
  const [selectedArch, setSelectedArch] = useState('npu');

  return (
    <div className="app">
      <header className="app-header">
        <h1>Vibe Processor - 交互式架构图</h1>
        <div className="arch-selector">
          <button
            className={selectedArch === 'npu' ? 'active' : ''}
            onClick={() => setSelectedArch('npu')}
          >
            昇腾 NPU
          </button>
          <button
            className={selectedArch === 'gpu' ? 'active' : ''}
            onClick={() => setSelectedArch('gpu')}
          >
            英伟达 GPU
          </button>
        </div>
      </header>
      <main className="app-main">
        <ArchitectureViewer
          key={selectedArch}
          configData={selectedArch === 'npu' ? npuData : gpuData}
        />
      </main>
    </div>
  );
}

export default App;
