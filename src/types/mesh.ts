export type NoiseSessionState = 'UNINITIALIZED' | 'HANDSHAKE_SENT' | 'HANDSHAKE_RECEIVED' | 'ESTABLISHED';

export interface Peer {
  id: string;
  name: string;
  callsign: string;
  address: string;
  rssi: number;
  hops: number;
  isDirect: boolean;
  verifiedKey: boolean;
  publicKey: string;
  noiseSessionState: NoiseSessionState;
  lastSeenSec: number;
  bearingDeg: number;
  distanceMeters: number;
  rttMs: number;
  batteryPercent: number;
  role: 'RELAY' | 'ENDPOINT' | 'BEACON';
  color: string;
}

export interface Channel {
  id: string;
  name: string;
  description: string;
  type: 'broadcast' | 'tactical' | 'emergency';
  activePeers: number;
  frequency: string;
  lastMessage: string;
  lastTimestamp: string;
  unreadCount: number;
}

export interface MeshMessage {
  id: string;
  conversationId: string;
  senderId: string;
  senderName: string;
  recipientId: string;
  content: string;
  timestamp: string;
  isOutgoing: boolean;
  status: 'PENDING' | 'SENDING' | 'SENT' | 'DELIVERED' | 'FAILED';
  hops: number;
  routePath: string[];
  rttMs: number;
}

export interface RoutingEntry {
  destination: string;
  destinationName: string;
  nextHop: string;
  metric: number;
  latencyMs: number;
  hops: number;
  phyLink: string;
  expiresInSec: number;
}
