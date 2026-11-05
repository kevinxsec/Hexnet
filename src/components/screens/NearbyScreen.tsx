import React, { useState } from 'react';
import { useMesh } from '../../context/MeshContext';
import { Peer } from '../../types/mesh';

export const NearbyScreen: React.FC = () => {
  const { peers, openConversation } = useMesh();
  const [filter, setFilter] = useState<'all' | 'direct' | 'contacts'>('all');
  const [inspectedPeer, setInspectedPeer] = useState<Peer | null>(null);

  const filteredPeers = peers.filter(p => {
    if (filter === 'direct') return p.isDirect;
    if (filter === 'contacts') return p.verifiedKey;
    return true;
  });

  return (
    <div className="flex-1 w-full max-w-[393px] mx-auto flex flex-col bg-black min-h-screen pb-24 select-none">
      {/* Header */}
      <div className="px-5 pt-2 pb-3">
        <div className="flex items-center justify-between mb-2">
          <div className="flex items-center gap-2">
            <span className="w-2 h-2 rounded-full bg-[#91d9bd]"></span>
            <span className="text-[11px] font-mono text-[#a1a1aa]">
              BLE discovery active
            </span>
          </div>
          <span className="text-[11px] font-mono font-bold text-[#8795ff]">
            {peers.length} in range
          </span>
        </div>

        <h1 className="text-[32px] font-mono font-bold text-[#f5f5f7] tracking-tight leading-none mb-3">
          Nearby
        </h1>

        {/* Filter Row */}
        <div className="flex items-center gap-5 mt-2 pt-1">
          <button
            onClick={() => setFilter('all')}
            className={`text-[13px] font-mono pb-1 transition-colors cursor-pointer ${
              filter === 'all'
                ? 'font-bold text-[#8795ff] border-b-2 border-[#8795ff]'
                : 'text-[#6b7280] hover:text-[#a1a1aa]'
            }`}
          >
            All ({peers.length})
          </button>
          <button
            onClick={() => setFilter('direct')}
            className={`text-[13px] font-mono pb-1 transition-colors cursor-pointer ${
              filter === 'direct'
                ? 'font-bold text-[#8795ff] border-b-2 border-[#8795ff]'
                : 'text-[#6b7280] hover:text-[#a1a1aa]'
            }`}
          >
            Direct ({peers.filter(p => p.isDirect).length})
          </button>
          <button
            onClick={() => setFilter('contacts')}
            className={`text-[13px] font-mono pb-1 transition-colors cursor-pointer ${
              filter === 'contacts'
                ? 'font-bold text-[#8795ff] border-b-2 border-[#8795ff]'
                : 'text-[#6b7280] hover:text-[#a1a1aa]'
            }`}
          >
            Verified ({peers.filter(p => p.verifiedKey).length})
          </button>
        </div>
      </div>

      <div className="h-[0.5px] w-full bg-white/8"></div>

      {/* Peer List */}
      <div className="flex-1 flex flex-col">
        {filteredPeers.map(peer => (
          <div key={peer.id}>
            <div
              onClick={() => setInspectedPeer(peer)}
              className="flex items-center px-5 py-3.5 hover:bg-white/5 active:bg-white/10 transition-colors cursor-pointer"
            >
              {/* Avatar */}
              <div className="w-11 h-11 rounded-full bg-[#101014] border border-white/12 flex items-center justify-center text-[13px] font-mono font-bold text-[#f5f5f7] shrink-0 mr-3.5">
                {peer.name.substring(0, 2).toUpperCase()}
              </div>

              {/* Info */}
              <div className="flex-1 min-w-0">
                <div className="flex items-center justify-between mb-0.5">
                  <h3 className="text-[15px] font-mono font-semibold text-[#f5f5f7] truncate">
                    {peer.name}
                  </h3>
                  <span className="text-[11px] font-mono text-[#91d9bd]">
                    {peer.isDirect ? '1-Hop Direct' : `${peer.hops}-Hop Relay`}
                  </span>
                </div>
                <p className="text-[12px] font-mono text-[#a1a1aa] truncate">
                  {peer.address} · {peer.verifiedKey ? 'Verified key' : 'Unverified'}
                </p>
              </div>

              {/* Chat action button */}
              <button
                onClick={e => {
                  e.stopPropagation();
                  openConversation(peer.id);
                }}
                className="ml-3 px-3.5 py-1.5 rounded-full bg-[#8795ff]/15 border border-[#8795ff]/30 text-[#8795ff] text-[12px] font-mono font-bold hover:bg-[#8795ff] hover:text-black transition-all tactile-button"
              >
                Chat
              </button>
            </div>
            <div className="h-[0.5px] w-full bg-white/8 ml-[72px]"></div>
          </div>
        ))}
      </div>

      {/* Peer Inspection Dialog */}
      {inspectedPeer && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-sm animate-in fade-in duration-150">
          <div className="w-full max-w-sm rounded-3xl bg-[#101014] border border-white/15 p-5 shadow-2xl flex flex-col gap-4 font-mono">
            <h3 className="text-[18px] font-bold text-[#f5f5f7]">{inspectedPeer.name}</h3>

            <div className="flex flex-col gap-2.5 text-[12px]">
              <div className="flex justify-between items-center py-1 border-b border-white/8">
                <span className="text-[#6b7280]">Node Address</span>
                <span className="text-[#f5f5f7] font-semibold">{inspectedPeer.address}</span>
              </div>
              <div className="flex justify-between items-center py-1 border-b border-white/8">
                <span className="text-[#6b7280]">Hop Distance</span>
                <span className="text-[#f5f5f7] font-semibold">
                  {inspectedPeer.isDirect ? '1-Hop (Direct BLE)' : `${inspectedPeer.hops}-Hops (Relayed)`}
                </span>
              </div>
              <div className="flex justify-between items-center py-1 border-b border-white/8">
                <span className="text-[#6b7280]">Key Status</span>
                <span className={`font-semibold ${inspectedPeer.verifiedKey ? 'text-[#91d9bd]' : 'text-[#a1a1aa]'}`}>
                  {inspectedPeer.verifiedKey ? 'Ed25519 Verified' : 'Unverified'}
                </span>
              </div>
              <div className="flex justify-between items-center py-1">
                <span className="text-[#6b7280]">Fingerprint</span>
                <span className="text-[#a1a1aa] truncate max-w-[180px]">{inspectedPeer.publicKey}</span>
              </div>
            </div>

            <div className="pt-2 border-t border-white/10 flex items-center justify-end gap-2">
              <button
                onClick={() => setInspectedPeer(null)}
                className="px-4 py-2 rounded-full text-[12px] text-[#a1a1aa] hover:text-[#f5f5f7] tactile-button"
              >
                Close
              </button>
              <button
                onClick={() => {
                  const id = inspectedPeer.id;
                  setInspectedPeer(null);
                  openConversation(id);
                }}
                className="px-5 py-2 rounded-full bg-[#8795ff] text-black font-bold text-[12px] tactile-button"
              >
                Open Chat
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
