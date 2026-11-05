import React from 'react';
import { useMesh } from '../../context/MeshContext';

export const MeshScreen: React.FC = () => {
  const { peers, routes } = useMesh();

  return (
    <div className="flex-1 w-full max-w-[393px] mx-auto flex flex-col bg-black min-h-screen pb-24 select-none">
      {/* Header */}
      <div className="px-5 pt-2 pb-3">
        <div className="flex items-center justify-between mb-2">
          <div className="flex items-center gap-2">
            <span className="w-2 h-2 rounded-full bg-[#8795ff]"></span>
            <span className="text-[11px] font-mono text-[#a1a1aa]">
              AODV-Lite Protocol
            </span>
          </div>
          <span className="text-[11px] font-mono font-bold text-[#91d9bd]">
            Nominal
          </span>
        </div>

        <h1 className="text-[32px] font-mono font-bold text-[#f5f5f7] tracking-tight leading-none mb-3">
          Mesh
        </h1>
      </div>

      <div className="h-[0.5px] w-full bg-white/8"></div>

      <div className="flex-1 flex flex-col px-5 py-4 space-y-4">
        {/* Metric Cards */}
        <div className="grid grid-cols-3 gap-2.5">
          <div className="p-3.5 rounded-2xl bg-[#101014] border border-white/10 flex flex-col">
            <span className="text-[10px] font-mono font-bold text-[#6b7280]">PEERS</span>
            <span className="text-[20px] font-mono font-bold text-[#8795ff] mt-1">{peers.length}</span>
            <span className="text-[10px] font-mono text-[#a1a1aa]">
              {peers.filter(p => p.isDirect).length} direct
            </span>
          </div>
          <div className="p-3.5 rounded-2xl bg-[#101014] border border-white/10 flex flex-col">
            <span className="text-[10px] font-mono font-bold text-[#6b7280]">BUFFER</span>
            <span className="text-[20px] font-mono font-bold text-[#b9a7ff] mt-1">3</span>
            <span className="text-[10px] font-mono text-[#a1a1aa]">queued</span>
          </div>
          <div className="p-3.5 rounded-2xl bg-[#101014] border border-white/10 flex flex-col">
            <span className="text-[10px] font-mono font-bold text-[#6b7280]">LOSS</span>
            <span className="text-[20px] font-mono font-bold text-[#91d9bd] mt-1">0.2%</span>
            <span className="text-[10px] font-mono text-[#a1a1aa]">E2EE</span>
          </div>
        </div>

        {/* Radio & Transport Specs */}
        <div>
          <h2 className="text-[13px] font-mono font-bold text-[#a1a1aa] mb-2">
            Radio & Transport
          </h2>
          <div className="p-4 rounded-2xl bg-[#101014] border border-white/10 space-y-2.5 text-[12px] font-mono">
            <div className="flex justify-between items-center">
              <span className="text-[#6b7280]">Carrier Mode</span>
              <span className="text-[#f5f5f7]">Bluetooth LE 2M PHY</span>
            </div>
            <div className="flex justify-between items-center">
              <span className="text-[#6b7280]">Frequency Band</span>
              <span className="text-[#f5f5f7]">2.440 GHz ISM</span>
            </div>
            <div className="flex justify-between items-center">
              <span className="text-[#6b7280]">MTU Framing</span>
              <span className="text-[#f5f5f7]">251 Bytes Negotiated</span>
            </div>
            <div className="flex justify-between items-center">
              <span className="text-[#6b7280]">Routing Engine</span>
              <span className="text-[#f5f5f7]">Distance Vector (AODV)</span>
            </div>
            <div className="flex justify-between items-center">
              <span className="text-[#6b7280]">Crypto Suite</span>
              <span className="text-[#f5f5f7]">Noise_XX_25519</span>
            </div>
          </div>
        </div>

        {/* Converged Routing Table */}
        <div>
          <h2 className="text-[13px] font-mono font-bold text-[#a1a1aa] mb-2">
            Routing Table ({routes.length})
          </h2>
          <div className="space-y-2">
            {routes.map(r => (
              <div
                key={r.destination}
                className="p-3.5 rounded-2xl bg-[#101014] border border-white/10 flex items-center justify-between font-mono"
              >
                <div>
                  <h3 className="text-[13px] font-semibold text-[#f5f5f7]">
                    {r.destinationName} ({r.destination})
                  </h3>
                  <span className="text-[11px] text-[#a1a1aa]">Next: {r.nextHop}</span>
                </div>
                <div className="text-right">
                  <span className="text-[12px] font-bold text-[#8795ff] block">
                    {r.hops} HOP{r.hops > 1 ? 'S' : ''}
                  </span>
                  <span className="text-[10px] text-[#6b7280]">{r.latencyMs}ms</span>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
};
