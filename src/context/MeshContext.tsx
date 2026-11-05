import React, { createContext, useContext, useState } from 'react';
import { Peer, Channel, MeshMessage, RoutingEntry } from '../types/mesh';
import { INITIAL_PEERS, INITIAL_CHANNELS, INITIAL_MESSAGES, INITIAL_ROUTES } from '../data/mockData';

export type NavTab = 'messages' | 'nearby' | 'mesh' | 'settings';

interface MeshContextType {
  activeTab: NavTab;
  setActiveTab: (tab: NavTab) => void;
  selectedConversationId: string | null;
  openConversation: (id: string) => void;
  closeConversation: () => void;
  peers: Peer[];
  channels: Channel[];
  messages: Record<string, MeshMessage[]>;
  routes: RoutingEntry[];
  meshServiceActive: boolean;
  setMeshServiceActive: (active: boolean) => void;
  sendMessage: (content: string) => void;
  retryMessage: (messageId: string) => void;
  deleteMessage: (messageId: string) => void;
  startNewChat: (targetHex: string, title: string) => void;
  verifyPeerIdentity: (peerId: string) => void;
  userProfile: {
    username: string;
    displayName: string;
    hexAddress: string;
    keyFingerprint: string;
  };
  resetIdentity: () => void;
}

const MeshContext = createContext<MeshContextType | null>(null);

export const MeshProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [activeTab, setActiveTab] = useState<NavTab>('messages');
  const [selectedConversationId, setSelectedConversationId] = useState<string | null>(null);
  const [peers, setPeers] = useState<Peer[]>(INITIAL_PEERS);
  const [channels, setChannels] = useState<Channel[]>(INITIAL_CHANNELS);
  const [messages, setMessages] = useState<Record<string, MeshMessage[]>>(INITIAL_MESSAGES);
  const [routes] = useState<RoutingEntry[]>(INITIAL_ROUTES);
  const [meshServiceActive, setMeshServiceActive] = useState<boolean>(true);

  const [userProfile, setUserProfile] = useState({
    username: 'solaris',
    displayName: 'Solaris',
    hexAddress: '0x49E2',
    keyFingerprint: 'ED25519:6a81...ef02_b994_5c2d',
  });

  const openConversation = (id: string) => {
    setSelectedConversationId(id);
  };

  const closeConversation = () => {
    setSelectedConversationId(null);
  };

  const sendMessage = (content: string) => {
    if (!selectedConversationId || !content.trim()) return;

    const timeStr = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    const isChannel = selectedConversationId.startsWith('chan-');
    const targetPeer = peers.find(p => p.id === selectedConversationId);

    const newMsg: MeshMessage = {
      id: `msg-${Date.now()}`,
      conversationId: selectedConversationId,
      senderId: 'local-me',
      senderName: userProfile.displayName,
      recipientId: selectedConversationId,
      content: content.trim(),
      timestamp: timeStr,
      isOutgoing: true,
      status: 'DELIVERED',
      hops: isChannel ? 3 : (targetPeer?.hops || 1),
      routePath: isChannel ? ['0x49E2', 'BROADCAST'] : ['0x49E2', targetPeer?.address || 'PEER'],
      rttMs: targetPeer?.rttMs || 42,
    };

    setMessages(prev => {
      const current = prev[selectedConversationId] || [];
      return {
        ...prev,
        [selectedConversationId]: [...current, newMsg],
      };
    });

    // Update conversation preview snippet
    if (isChannel) {
      setChannels(prev =>
        prev.map(c =>
          c.id === selectedConversationId
            ? { ...c, lastMessage: `${userProfile.displayName}: ${content.trim()}`, lastTimestamp: timeStr }
            : c
        )
      );
    }

    // Simulate natural reply for direct chat
    if (!isChannel && targetPeer) {
      setTimeout(() => {
        const replyPool = [
          'Received your packet. Noise handshake active.',
          'Acknowledged. Link quality is nominal.',
          'Message decrypted cleanly over direct BLE carrier.',
          'Got it! Forwarding packet across neighboring nodes.',
        ];
        const randomReply = replyPool[Math.floor(Math.random() * replyPool.length)];
        const replyTime = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

        const replyMsg: MeshMessage = {
          id: `msg-rep-${Date.now()}`,
          conversationId: selectedConversationId,
          senderId: targetPeer.id,
          senderName: targetPeer.name,
          recipientId: 'local-me',
          content: randomReply,
          timestamp: replyTime,
          isOutgoing: false,
          status: 'DELIVERED',
          hops: targetPeer.hops,
          routePath: [targetPeer.address, '0x49E2'],
          rttMs: targetPeer.rttMs,
        };

        setMessages(prev => {
          const cur = prev[selectedConversationId] || [];
          return {
            ...prev,
            [selectedConversationId]: [...cur, replyMsg],
          };
        });
      }, 1500);
    }
  };

  const retryMessage = (messageId: string) => {
    if (!selectedConversationId) return;
    setMessages(prev => {
      const cur = prev[selectedConversationId] || [];
      return {
        ...prev,
        [selectedConversationId]: cur.map(m => m.id === messageId ? { ...m, status: 'DELIVERED' } : m),
      };
    });
  };

  const deleteMessage = (messageId: string) => {
    if (!selectedConversationId) return;
    setMessages(prev => {
      const cur = prev[selectedConversationId] || [];
      return {
        ...prev,
        [selectedConversationId]: cur.filter(m => m.id !== messageId),
      };
    });
  };

  const startNewChat = (targetHex: string, title: string) => {
    const existing = peers.find(p => p.address.toLowerCase().includes(targetHex.toLowerCase()));
    if (existing) {
      openConversation(existing.id);
      return;
    }

    const newPeer: Peer = {
      id: `peer-${Date.now()}`,
      name: title,
      callsign: title.substring(0, 2).toUpperCase(),
      address: `0x${targetHex}`,
      rssi: -65,
      hops: 1,
      isDirect: true,
      verifiedKey: false,
      publicKey: 'ed25519:unknown',
      noiseSessionState: 'ESTABLISHED',
      lastSeenSec: 1,
      bearingDeg: 180,
      distanceMeters: 15,
      rttMs: 50,
      batteryPercent: 85,
      role: 'ENDPOINT',
      color: '#8795ff',
    };

    setPeers(prev => [newPeer, ...prev]);
    openConversation(newPeer.id);
  };

  const verifyPeerIdentity = (peerId: string) => {
    setPeers(prev =>
      prev.map(p =>
        p.id === peerId
          ? { ...p, verifiedKey: true, noiseSessionState: 'ESTABLISHED' }
          : p
      )
    );
  };

  const resetIdentity = () => {
    const randomHex = Math.random().toString(16).substring(2, 6).toUpperCase();
    setUserProfile({
      username: 'solaris',
      displayName: 'Solaris',
      hexAddress: `0x${randomHex}`,
      keyFingerprint: `ED25519:${Math.random().toString(16).substring(2, 10)}...`,
    });
    setMessages({});
  };

  return (
    <MeshContext.Provider
      value={{
        activeTab,
        setActiveTab,
        selectedConversationId,
        openConversation,
        closeConversation,
        peers,
        channels,
        messages,
        routes,
        meshServiceActive,
        setMeshServiceActive,
        sendMessage,
        retryMessage,
        deleteMessage,
        startNewChat,
        verifyPeerIdentity,
        userProfile,
        resetIdentity,
      }}
    >
      {children}
    </MeshContext.Provider>
  );
};

export const useMesh = () => {
  const context = useContext(MeshContext);
  if (!context) throw new Error('useMesh must be used within MeshProvider');
  return context;
};
