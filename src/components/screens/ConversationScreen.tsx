import React, { useState, useRef, useEffect } from 'react';
import { useMesh } from '../../context/MeshContext';

export const ConversationScreen: React.FC = () => {
  const {
    selectedConversationId,
    closeConversation,
    peers,
    channels,
    messages,
    sendMessage,
    retryMessage,
  } = useMesh();

  const [inputText, setInputText] = useState('');
  const [showInfoDialog, setShowInfoDialog] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement | null>(null);

  const isChannel = selectedConversationId?.startsWith('chan-');
  const peer = peers.find(p => p.id === selectedConversationId);
  const channel = channels.find(c => c.id === selectedConversationId);

  const title = (isChannel ? channel?.name : peer?.name) || 'Conversation';
  const currentMessages = selectedConversationId ? (messages[selectedConversationId] || []) : [];

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [currentMessages.length]);

  const handleSend = (e: React.FormEvent) => {
    e.preventDefault();
    if (!inputText.trim()) return;
    sendMessage(inputText.trim());
    setInputText('');
  };

  return (
    <div className="flex-1 w-full max-w-[393px] mx-auto flex flex-col bg-black min-h-screen select-none relative">
      {/* Minimal Contact Header */}
      <div className="sticky top-0 z-40 bg-black/90 backdrop-blur-xl border-b border-white/8 px-4 py-2 flex items-center justify-between">
        <div className="flex items-center gap-2 min-w-0">
          <button
            onClick={closeConversation}
            className="w-9 h-9 rounded-full flex items-center justify-center text-[#f5f5f7] hover:bg-white/10 active:scale-95 transition-all cursor-pointer"
          >
            <span className="material-symbols-outlined text-[20px]">arrow_back</span>
          </button>

          {/* Avatar */}
          <div className="w-9 h-9 rounded-full bg-[#101014] border border-white/12 flex items-center justify-center text-[12px] font-mono font-bold text-[#f5f5f7] shrink-0">
            {isChannel ? (
              <span className="material-symbols-outlined text-[16px] text-[#cdbdff]">tag</span>
            ) : (
              title.substring(0, 2).toUpperCase()
            )}
          </div>

          {/* Contact Name & Small Session Indicator */}
          <div className="flex flex-col min-w-0" onClick={() => setShowInfoDialog(true)}>
            <h2 className="text-[15px] font-mono font-bold text-[#f5f5f7] truncate cursor-pointer">
              {title}
            </h2>
            <div className="flex items-center gap-1 text-[11px] font-mono text-[#a1a1aa] cursor-pointer">
              <span className="material-symbols-outlined text-[12px] text-[#997bfa]">lock</span>
              <span className="truncate">
                {isChannel ? 'Mesh Broadcast' : (peer?.isDirect ? 'Direct P2P · Noise E2EE' : `${peer?.hops || 1}-Hop · Noise E2EE`)}
              </span>
            </div>
          </div>
        </div>

        <button
          onClick={() => setShowInfoDialog(true)}
          className="w-9 h-9 rounded-full flex items-center justify-center text-[#a1a1aa] hover:text-[#f5f5f7] hover:bg-white/10 transition-colors"
          title="Peer Details"
        >
          <span className="material-symbols-outlined text-[20px]">info</span>
        </button>
      </div>

      {/* Message Stream */}
      <div className="flex-1 flex flex-col px-4 py-4 overflow-y-auto space-y-3 pb-24">
        {/* Minimal 1-line encryption notice */}
        <div className="w-full flex items-center justify-center py-2">
          <div className="flex items-center gap-1.5 text-[11px] font-mono text-[#6b7280]">
            <span className="material-symbols-outlined text-[13px] text-[#997bfa]">lock</span>
            <span>Forward-secure Noise_XX encryption active</span>
          </div>
        </div>

        {currentMessages.length === 0 ? (
          <div className="flex-1 flex items-center justify-center text-center p-8 text-[#6b7280] font-mono text-xs">
            Start the conversation. Messages travel directly over offline Bluetooth mesh.
          </div>
        ) : (
          currentMessages.map(msg => {
            const isOutgoing = msg.isOutgoing;
            const isFailed = msg.status === 'FAILED';

            return (
              <div
                key={msg.id}
                className={`flex flex-col ${isOutgoing ? 'items-end' : 'items-start'} max-w-[85%] ${isOutgoing ? 'self-end' : 'self-start'}`}
              >
                <div
                  className={`px-4 py-2.5 shadow-sm text-[14px] font-mono leading-relaxed transition-all ${
                    isOutgoing
                      ? 'rounded-2xl rounded-br-xs bg-[#8795ff]/20 text-[#f5f5f7] border border-[#8795ff]/40'
                      : 'rounded-2xl rounded-bl-xs bg-[#101014] text-[#f5f5f7] border border-white/10'
                  }`}
                >
                  {!isOutgoing && msg.senderName && (
                    <span className="block text-[11px] font-semibold text-[#b9a7ff] mb-1">
                      {msg.senderName}
                    </span>
                  )}
                  <p className="whitespace-pre-wrap break-words">{msg.content}</p>

                  <div className="flex items-center justify-end gap-1 mt-1 text-[10px] text-[#6b7280]">
                    <span>{msg.timestamp}</span>
                    {isOutgoing && (
                      <span className="material-symbols-outlined text-[13px] text-[#8795ff]">
                        done_all
                      </span>
                    )}
                  </div>
                </div>

                {isFailed && (
                  <button
                    onClick={() => retryMessage(msg.id)}
                    className="text-[11px] font-mono text-[#f2a69d] mt-1 mr-1 hover:underline cursor-pointer"
                  >
                    Failed to deliver · Tap to retry
                  </button>
                )}
              </div>
            );
          })
        )}
        <div ref={messagesEndRef} />
      </div>

      {/* Elegant Floating Translucent Composer */}
      <div className="fixed bottom-0 left-0 right-0 z-30 px-4 pb-4 pt-2 bg-gradient-to-t from-black via-black/80 to-transparent pointer-events-none">
        <div className="max-w-[393px] mx-auto w-full pointer-events-auto">
          <form
            onSubmit={handleSend}
            className="flex items-center px-3.5 py-1.5 rounded-full bg-[#101014]/95 backdrop-blur-2xl border border-white/12 shadow-[0_8px_32px_rgba(0,0,0,0.8)] gap-2 focus-within:border-[#8795ff]/50"
          >
            <input
              type="text"
              value={inputText}
              onChange={e => setInputText(e.target.value)}
              placeholder={`Message ${title}...`}
              className="flex-1 bg-transparent text-[14px] font-mono text-[#f5f5f7] placeholder:text-[#6b7280] focus:outline-none py-2 px-1"
            />

            <button
              type="submit"
              disabled={!inputText.trim()}
              className={`w-9 h-9 rounded-full flex items-center justify-center tactile-button cursor-pointer transition-all ${
                inputText.trim()
                  ? 'bg-[#8795ff] text-black shadow-md'
                  : 'bg-[#17171d] text-[#6b7280] opacity-50 cursor-not-allowed'
              }`}
            >
              <span className="material-symbols-outlined text-[18px]">arrow_upward</span>
            </button>
          </form>
        </div>
      </div>

      {/* Technical Diagnostics Info Dialog */}
      {showInfoDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-sm animate-in fade-in duration-150">
          <div className="w-full max-w-sm rounded-3xl bg-[#101014] border border-white/15 p-5 shadow-2xl flex flex-col gap-4 font-mono">
            <h3 className="text-[17px] font-bold text-[#f5f5f7]">Peer Connection Info</h3>

            <div className="flex flex-col gap-2.5 text-[12px]">
              <div className="flex justify-between items-center py-1 border-b border-white/8">
                <span className="text-[#6b7280]">Recipient</span>
                <span className="text-[#f5f5f7] font-semibold">{title}</span>
              </div>
              <div className="flex justify-between items-center py-1 border-b border-white/8">
                <span className="text-[#6b7280]">Node Address</span>
                <span className="text-[#f5f5f7] font-semibold">{peer?.address || 'Broadcast'}</span>
              </div>
              <div className="flex justify-between items-center py-1 border-b border-white/8">
                <span className="text-[#6b7280]">Transport</span>
                <span className="text-[#f5f5f7] font-semibold">
                  {peer?.isDirect ? 'Direct 1-Hop Bluetooth LE' : 'Multi-Hop Mesh Relay'}
                </span>
              </div>
              <div className="flex justify-between items-center py-1 border-b border-white/8">
                <span className="text-[#6b7280]">Protocol</span>
                <span className="text-[#f5f5f7] font-semibold">Noise_XX_25519_ChaChaPoly</span>
              </div>
              <div className="flex justify-between items-center py-1">
                <span className="text-[#6b7280]">Verification</span>
                <span className={`font-semibold ${peer?.verifiedKey ? 'text-[#91d9bd]' : 'text-[#a1a1aa]'}`}>
                  {peer?.verifiedKey ? 'Ed25519 Verified' : 'Unverified'}
                </span>
              </div>
            </div>

            <div className="pt-2 border-t border-white/10 flex justify-end">
              <button
                onClick={() => setShowInfoDialog(false)}
                className="px-5 py-2 rounded-full bg-[#8795ff] text-black font-bold text-[12px] tactile-button"
              >
                Done
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
