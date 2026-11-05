import React from 'react';
import { MeshProvider, useMesh } from './context/MeshContext';
import { HeaderBar } from './components/HeaderBar';
import { BottomNavBar } from './components/BottomNavBar';
import { MessagesScreen } from './components/screens/MessagesScreen';
import { ConversationScreen } from './components/screens/ConversationScreen';
import { NearbyScreen } from './components/screens/NearbyScreen';
import { MeshScreen } from './components/screens/MeshScreen';
import { SettingsScreen } from './components/screens/SettingsScreen';

const AppContent: React.FC = () => {
  const { activeTab, selectedConversationId } = useMesh();

  return (
    <div className="min-h-screen bg-black text-[#f5f5f7] flex flex-col items-center justify-start select-none font-mono antialiased overflow-x-hidden">
      {/* Mobile Frame Container (Targeting standard 393 × 852 dp Android viewport) */}
      <div className="w-full max-w-[393px] min-h-screen flex flex-col bg-black relative border-x border-white/5 shadow-2xl">
        {/* Android System Status Bar */}
        <HeaderBar />

        {/* Screen Content Viewport */}
        <main className="flex-1 flex flex-col w-full relative z-10">
          {selectedConversationId ? (
            <ConversationScreen />
          ) : (
            <>
              {activeTab === 'messages' && <MessagesScreen />}
              {activeTab === 'nearby' && <NearbyScreen />}
              {activeTab === 'mesh' && <MeshScreen />}
              {activeTab === 'settings' && <SettingsScreen />}
            </>
          )}
        </main>

        {/* Refined Floating Liquid Glass Bottom Navigation Bar */}
        <BottomNavBar />
      </div>
    </div>
  );
};

export const App: React.FC = () => {
  return (
    <MeshProvider>
      <AppContent />
    </MeshProvider>
  );
};

export default App;
