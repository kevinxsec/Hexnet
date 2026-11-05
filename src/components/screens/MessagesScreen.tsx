import React, { useState } from 'react';
import { useMesh } from '../../context/MeshContext';

export const MessagesScreen: React.FC = () => {
  const { peers, channels, messages, openConversation, startNewChat } = useMesh();
  const [searchQuery, setSearchQuery] = useState('');
  const [activeTab, setActiveTab] = useState<'direct' | 'channels'>('direct');
  const [showComposeDialog, setShowComposeDialog] = useState(false);
  const [newHexInput, setNewHexInput] = useState('');
  const [newTitleInput, setNewTitleInput] = useState('');

  const filteredPeers = peers.filter(p =>
    p.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
    p.address.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const filteredChannels = channels.filter(c =>
    c.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
    c.description.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const handleStartChat = () => {
    if (newHexInput.trim()) {
      startNewChat(newHexInput.trim(), newTitleInput.trim() || `Node ${newHexInput.trim()}`);
      setShowComposeDialog(false);
      setNewHexInput('');
      setNewTitleInput('');
    }
  };

  return (
    <div className="flex-1 w-full max-w-[393px] mx-auto flex flex-col bg-black min-h-screen pb-24 select-none">
      {/* Header Area */}
      <div className="px-5 pt-2 pb-3">
        {/* Understated Mesh Connection Indicator */}
        <div className="flex items-center justify-between mb-2">
          <div className="flex items-center gap-2">
            <span className="w-2 h-2 rounded-full bg-[#91d9bd]"></span>
            <span className="text-[11px] font-mono text-[#a1a1aa]">
              {peers.length} peers connected
            </span>
          </div>
          <span className="text-[10px] font-mono font-bold text-[#8795ff] bg-[#8795ff]/15 px-2.5 py-0.5 rounded-full border border-[#8795ff]/25">
            OFFLINE P2P
          </span>
        </div>

        {/* Large Expressive "Messages" Heading */}
        <h1 className="text-[32px] font-mono font-bold text-[#f5f5f7] tracking-tight leading-none mb-3">
          Messages
        </h1>

        {/* Beautiful Floating Translucent Search Control */}
        <div className="relative w-full h-11 rounded-full bg-[#101014] border border-white/12 shadow-sm flex items-center px-4 gap-2.5 transition-colors focus-within:border-[#8795ff]/50">
          <span className="material-symbols-outlined text-[18px] text-[#6b7280]">search</span>
          <input
            type="text"
            value={searchQuery}
            onChange={e => setSearchQuery(e.target.value)}
            placeholder="Search conversations or channels..."
            className="w-full bg-transparent text-[13px] font-mono text-[#f5f5f7] placeholder:text-[#6b7280] focus:outline-none"
          />
          {searchQuery && (
            <button
              onClick={() => setSearchQuery('')}
              className="text-[#a1a1aa] hover:text-[#f5f5f7]"
            >
              <span className="material-symbols-outlined text-[16px]">close</span>
            </button>
          )}
        </div>

        {/* Minimal Direct / Channels Selector */}
        <div className="flex items-center gap-5 mt-4 pt-1">
          <button
            onClick={() => setActiveTab('direct')}
            className={`text-[13px] font-mono pb-1 transition-colors cursor-pointer ${
              activeTab === 'direct'
                ? 'font-bold text-[#8795ff] border-b-2 border-[#8795ff]'
                : 'text-[#6b7280] hover:text-[#a1a1aa]'
            }`}
          >
            Direct ({peers.length})
          </button>
          <button
            onClick={() => setActiveTab('channels')}
            className={`text-[13px] font-mono pb-1 transition-colors cursor-pointer ${
              activeTab === 'channels'
                ? 'font-bold text-[#8795ff] border-b-2 border-[#8795ff]'
                : 'text-[#6b7280] hover:text-[#a1a1aa]'
            }`}
          >
            Channels ({channels.length})
          </button>
        </div>
      </div>

      <div className="h-[0.5px] w-full bg-white/8"></div>

      {/* Spacious Conversation List (Dominating the available screen space) */}
      <div className="flex-1 flex flex-col">
        {activeTab === 'direct' ? (
          filteredPeers.length === 0 ? (
            <div className="flex-1 flex flex-col items-center justify-center p-8 text-center text-[#6b7280]">
              <span className="material-symbols-outlined text-[36px] text-[#8795ff]/40 mb-2">chat_bubble_outline</span>
              <p className="text-[13px] font-mono">No conversations found</p>
            </div>
          ) : (
            filteredPeers.map(peer => {
              const peerMsgs = messages[peer.id] || [];
              const lastMsg = peerMsgs[peerMsgs.length - 1];

              return (
                <div key={peer.id}>
                  <div
                    onClick={() => openConversation(peer.id)}
                    className="flex items-center px-5 py-3.5 hover:bg-white/5 active:bg-white/10 transition-colors cursor-pointer"
                  >
                    {/* Avatar */}
                    <div className="relative shrink-0 mr-3.5">
                      <div className="w-11 h-11 rounded-full bg-[#101014] border border-white/12 flex items-center justify-center text-[13px] font-mono font-bold text-[#f5f5f7]">
                        {peer.name.substring(0, 2).toUpperCase()}
                      </div>
                      {/* Subtle status dot */}
                      {peer.isDirect && (
                        <span className="absolute bottom-0 right-0 w-3 h-3 rounded-full bg-[#91d9bd] ring-2 ring-black"></span>
                      )}
                    </div>

                    {/* Content */}
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center justify-between mb-0.5">
                        <h3 className="text-[15px] font-mono font-semibold text-[#f5f5f7] truncate">
                          {peer.name}
                        </h3>
                        <span className="text-[11px] font-mono text-[#6b7280] shrink-0 ml-2">
                          {lastMsg ? lastMsg.timestamp : 'Active'}
                        </span>
                      </div>

                      <div className="flex items-center justify-between gap-2">
                        <p className="text-[13px] font-mono text-[#a1a1aa] truncate">
                          {lastMsg ? lastMsg.content : 'Route established'}
                        </p>
                        {lastMsg && lastMsg.isOutgoing && (
                          <span className="material-symbols-outlined text-[13px] text-[#8795ff] shrink-0">
                            done_all
                          </span>
                        )}
                      </div>
                    </div>
                  </div>
                  <div className="h-[0.5px] w-full bg-white/8 ml-[72px]"></div>
                </div>
              );
            })
          )
        ) : (
          filteredChannels.length === 0 ? (
            <div className="flex-1 flex flex-col items-center justify-center p-8 text-center text-[#6b7280]">
              <span className="material-symbols-outlined text-[36px] text-[#8795ff]/40 mb-2">tag</span>
              <p className="text-[13px] font-mono">No channels found</p>
            </div>
          ) : (
            filteredChannels.map(channel => (
              <div key={channel.id}>
                <div
                  onClick={() => openConversation(channel.id)}
                  className="flex items-center px-5 py-3.5 hover:bg-white/5 active:bg-white/10 transition-colors cursor-pointer"
                >
                  {/* Channel Glyph */}
                  <div className="w-11 h-11 rounded-full bg-[#101014] border border-white/12 flex items-center justify-center shrink-0 mr-3.5 text-[#cdbdff]">
                    <span className="material-symbols-outlined text-[20px]">tag</span>
                  </div>

                  {/* Content */}
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center justify-between mb-0.5">
                      <h3 className="text-[15px] font-mono font-semibold text-[#f5f5f7] truncate">
                        {channel.name}
                      </h3>
                      <span className="text-[11px] font-mono text-[#6b7280] shrink-0 ml-2">
                        {channel.lastTimestamp}
                      </span>
                    </div>

                    <div className="flex items-center justify-between gap-2">
                      <p className="text-[13px] font-mono text-[#a1a1aa] truncate">
                        {channel.lastMessage}
                      </p>
                      {channel.unreadCount > 0 && (
                        <span className="min-w-[18px] h-[18px] px-1.5 rounded-full bg-[#8795ff] text-black font-mono font-bold text-[10px] flex items-center justify-center shrink-0">
                          {channel.unreadCount}
                        </span>
                      )}
                    </div>
                  </div>
                </div>
                <div className="h-[0.5px] w-full bg-white/8 ml-[72px]"></div>
              </div>
            ))
          )
        )}
      </div>

      {/* Floating Action Button for New Chat */}
      <button
        onClick={() => setShowComposeDialog(true)}
        className="fixed bottom-24 right-6 w-14 h-14 rounded-full bg-[#8795ff] text-black shadow-[0_4px_16px_rgba(135,149,255,0.4)] flex items-center justify-center tactile-button cursor-pointer z-30"
        title="New Conversation"
      >
        <span className="material-symbols-outlined text-[24px]">edit</span>
      </button>

      {/* New Conversation Dialog */}
      {showComposeDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-sm animate-in fade-in duration-150">
          <div className="w-full max-w-sm rounded-3xl bg-[#101014] border border-white/15 p-5 shadow-2xl flex flex-col gap-4 font-mono">
            <h3 className="text-[18px] font-bold text-[#f5f5f7]">New Conversation</h3>
            <p className="text-[12px] text-[#a1a1aa]">
              Enter a peer's 4-character node hex or select from discovered nearby peers:
            </p>

            <div className="flex flex-col gap-2.5">
              <input
                type="text"
                placeholder="Peer Hex (e.g. 8B2F)"
                value={newHexInput}
                onChange={e => setNewHexInput(e.target.value.toUpperCase())}
                className="w-full px-3.5 py-2.5 rounded-xl bg-[#09090b] border border-white/12 text-[13px] text-[#f5f5f7] focus:outline-none focus:border-[#8795ff]"
              />
              <input
                type="text"
                placeholder="Display Name (optional)"
                value={newTitleInput}
                onChange={e => setNewTitleInput(e.target.value)}
                className="w-full px-3.5 py-2.5 rounded-xl bg-[#09090b] border border-white/12 text-[13px] text-[#f5f5f7] focus:outline-none focus:border-[#8795ff]"
              />
            </div>

            {peers.length > 0 && (
              <div>
                <span className="text-[11px] font-bold text-[#a1a1aa] block mb-2">
                  Discovered Nearby:
                </span>
                <div className="flex flex-col gap-1.5 max-h-36 overflow-y-auto">
                  {peers.map(p => (
                    <div
                      key={p.id}
                      onClick={() => {
                        openConversation(p.id);
                        setShowComposeDialog(false);
                      }}
                      className="px-3 py-2 rounded-lg bg-[#09090b] border border-white/8 hover:border-[#8795ff]/40 flex items-center justify-between cursor-pointer"
                    >
                      <span className="text-[13px] text-[#f5f5f7]">{p.name}</span>
                      <span className="text-[11px] text-[#8795ff]">{p.address}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}

            <div className="flex items-center justify-end gap-2 pt-2 border-t border-white/10">
              <button
                onClick={() => setShowComposeDialog(false)}
                className="px-4 py-2 rounded-full text-[12px] text-[#a1a1aa] hover:text-[#f5f5f7] tactile-button"
              >
                Cancel
              </button>
              <button
                onClick={handleStartChat}
                disabled={!newHexInput.trim()}
                className={`px-5 py-2 rounded-full text-[12px] font-bold tactile-button ${
                  newHexInput.trim()
                    ? 'bg-[#8795ff] text-black shadow-md'
                    : 'bg-[#1f1f1f] text-[#6b7280] opacity-50 cursor-not-allowed'
                }`}
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
