import React from 'react';
import { useMesh, NavTab } from '../context/MeshContext';

export const BottomNavBar: React.FC = () => {
  const { activeTab, setActiveTab, selectedConversationId, channels } = useMesh();

  // Hide bottom bar when inside a specific conversation
  if (selectedConversationId) return null;

  const totalUnread = channels.reduce((acc, c) => acc + c.unreadCount, 0);

  const tabs: { id: NavTab; label: string; icon: string; badge?: number }[] = [
    { id: 'messages', label: 'Messages', icon: 'chat_bubble', badge: totalUnread },
    { id: 'nearby', label: 'Nearby', icon: 'sensors' },
    { id: 'mesh', label: 'Mesh', icon: 'hub' },
    { id: 'settings', label: 'Settings', icon: 'settings' },
  ];

  return (
    <nav className="fixed bottom-0 left-0 right-0 z-40 px-5 pb-4 pt-1 pointer-events-none select-none">
      <div className="max-w-[393px] mx-auto w-full pointer-events-auto">
        <div className="h-16 rounded-full bg-[#09090B]/95 backdrop-blur-2xl border border-white/12 shadow-[0_8px_32px_rgba(0,0,0,0.8)] px-2 flex items-center justify-around">
          {tabs.map(tab => {
            const isSelected = activeTab === tab.id;

            return (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id)}
                className={`flex-1 h-full flex flex-col items-center justify-center tactile-button cursor-pointer relative ${
                  isSelected ? 'text-[#8795ff]' : 'text-[#6b7280] hover:text-[#a1a1aa]'
                }`}
              >
                <div
                  className={`px-4 py-1 rounded-full flex items-center justify-center transition-colors ${
                    isSelected ? 'bg-[#8795ff]/15' : 'bg-transparent'
                  }`}
                >
                  <span className="material-symbols-outlined text-[20px]">
                    {tab.icon}
                  </span>
                  {tab.badge ? tab.badge > 0 && (
                    <span className="absolute top-2 right-4 min-w-[15px] h-[15px] px-1 bg-[#8795ff] text-black text-[9px] font-mono font-bold rounded-full flex items-center justify-center">
                      {tab.badge}
                    </span>
                  ) : null}
                </div>
                <span
                  className={`text-[10px] font-mono mt-0.5 tracking-tight ${
                    isSelected ? 'font-bold text-[#8795ff]' : 'font-normal text-[#6b7280]'
                  }`}
                >
                  {tab.label}
                </span>
              </button>
            );
          })}
        </div>
      </div>
    </nav>
  );
};
