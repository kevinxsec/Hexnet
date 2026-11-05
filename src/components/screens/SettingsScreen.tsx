import React, { useState } from 'react';
import { useMesh } from '../../context/MeshContext';

export const SettingsScreen: React.FC = () => {
  const { userProfile, meshServiceActive, setMeshServiceActive, resetIdentity } = useMesh();
  const [copied, setCopied] = useState(false);
  const [showConfirmReset, setShowConfirmReset] = useState(false);

  const handleCopy = () => {
    navigator.clipboard?.writeText(userProfile.keyFingerprint);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="flex-1 w-full max-w-[393px] mx-auto flex flex-col bg-black min-h-screen pb-24 select-none">
      {/* Header */}
      <div className="px-5 pt-2 pb-3">
        <p className="text-[11px] font-mono text-[#a1a1aa] mb-1">Preferences</p>
        <h1 className="text-[32px] font-mono font-bold text-[#f5f5f7] tracking-tight leading-none mb-3">
          Settings
        </h1>
      </div>

      <div className="h-[0.5px] w-full bg-white/8"></div>

      <div className="flex-1 flex flex-col px-5 py-4 space-y-5">
        {/* User Identity Card */}
        <div>
          <h2 className="text-[13px] font-mono font-bold text-[#a1a1aa] mb-2">
            My Node Identity
          </h2>
          <div className="p-4 rounded-2xl bg-[#101014] border border-white/10 flex flex-col gap-3 font-mono">
            <div className="flex items-center gap-3">
              <div className="w-12 h-12 rounded-full bg-[#17171d] border border-white/12 flex items-center justify-center text-[15px] font-bold text-[#f5f5f7]">
                SO
              </div>
              <div className="flex-1 min-w-0">
                <h3 className="text-[16px] font-bold text-[#f5f5f7] truncate">{userProfile.displayName}</h3>
                <p className="text-[12px] text-[#a1a1aa]">@{userProfile.username}</p>
              </div>
              <span className="px-2.5 py-1 rounded-full bg-[#8795ff]/15 border border-[#8795ff]/30 text-[#8795ff] text-[12px] font-bold">
                {userProfile.hexAddress}
              </span>
            </div>

            <div className="h-[0.5px] w-full bg-white/8"></div>

            <div
              onClick={handleCopy}
              className="flex items-center justify-between cursor-pointer hover:opacity-80 transition-opacity"
            >
              <div>
                <span className="text-[10px] text-[#6b7280] font-bold block">
                  ED25519 FINGERPRINT
                </span>
                <span className="text-[11px] text-[#a1a1aa] truncate block max-w-[240px]">
                  {userProfile.keyFingerprint}
                </span>
              </div>
              <span className="material-symbols-outlined text-[18px] text-[#8795ff]">
                {copied ? 'check' : 'content_copy'}
              </span>
            </div>
          </div>
        </div>

        {/* Mesh Radio Toggle */}
        <div>
          <h2 className="text-[13px] font-mono font-bold text-[#a1a1aa] mb-2">
            Mesh Radio
          </h2>
          <div className="p-4 rounded-2xl bg-[#101014] border border-white/10 flex items-center justify-between font-mono">
            <div>
              <h3 className="text-[14px] font-semibold text-[#f5f5f7]">Background Mesh Service</h3>
              <p className="text-[11px] text-[#a1a1aa] max-w-[220px] leading-relaxed">
                Maintain BLE advertising and packet relay when app is in background
              </p>
            </div>
            <button
              onClick={() => setMeshServiceActive(!meshServiceActive)}
              className={`w-12 h-7 rounded-full transition-colors relative cursor-pointer ${
                meshServiceActive ? 'bg-[#8795ff]' : 'bg-[#17171d]'
              }`}
            >
              <span
                className={`absolute top-1 left-1 w-5 h-5 rounded-full bg-black transition-transform ${
                  meshServiceActive ? 'translate-x-5' : 'translate-x-0'
                }`}
              ></span>
            </button>
          </div>
        </div>

        {/* Security Architecture */}
        <div>
          <h2 className="text-[13px] font-mono font-bold text-[#a1a1aa] mb-2">
            Security Architecture
          </h2>
          <div className="p-4 rounded-2xl bg-[#101014] border border-white/10 space-y-2.5 text-[12px] font-mono">
            <div className="flex justify-between items-center">
              <span className="text-[#6b7280]">Handshake</span>
              <span className="text-[#f5f5f7]">Noise_XX Pattern</span>
            </div>
            <div className="flex justify-between items-center">
              <span className="text-[#6b7280]">Diffie-Hellman</span>
              <span className="text-[#f5f5f7]">X25519 (RFC 7748)</span>
            </div>
            <div className="flex justify-between items-center">
              <span className="text-[#6b7280]">Cipher</span>
              <span className="text-[#f5f5f7]">ChaCha20-Poly1305</span>
            </div>
            <div className="flex justify-between items-center">
              <span className="text-[#6b7280]">Signing</span>
              <span className="text-[#f5f5f7]">Ed25519 (RFC 8032)</span>
            </div>
          </div>
        </div>

        {/* Clear Identity Button */}
        <div>
          <button
            onClick={() => setShowConfirmReset(true)}
            className="w-full py-3 rounded-2xl border border-[#f2a69d]/40 text-[#f2a69d] font-mono text-[13px] font-semibold hover:bg-[#f2a69d]/10 transition-colors cursor-pointer"
          >
            Reset Cryptographic Identity
          </button>
        </div>
      </div>

      {/* Confirmation Dialog */}
      {showConfirmReset && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-sm animate-in fade-in duration-150">
          <div className="w-full max-w-sm rounded-3xl bg-[#101014] border border-white/15 p-5 shadow-2xl flex flex-col gap-4 font-mono">
            <h3 className="text-[18px] font-bold text-[#f5f5f7]">Reset Identity?</h3>
            <p className="text-[12px] text-[#a1a1aa] leading-relaxed">
              This will erase your local Ed25519 and X25519 keypair and regenerate a new cryptographic identity. All existing sessions will be disconnected.
            </p>
            <div className="flex items-center justify-end gap-2 pt-2 border-t border-white/10">
              <button
                onClick={() => setShowConfirmReset(false)}
                className="px-4 py-2 rounded-full text-[12px] text-[#a1a1aa] hover:text-[#f5f5f7] tactile-button"
              >
                Cancel
              </button>
              <button
                onClick={() => {
                  setShowConfirmReset(false);
                  resetIdentity();
                }}
                className="px-5 py-2 rounded-full bg-[#f2a69d] text-black font-bold text-[12px] tactile-button"
              >
                Reset
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
