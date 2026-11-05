import React, { useState, useEffect } from 'react';

export const HeaderBar: React.FC = () => {
  const [timeStr, setTimeStr] = useState('09:41');

  useEffect(() => {
    const updateTime = () => {
      const d = new Date();
      setTimeStr(d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }));
    };
    updateTime();
    const interval = setInterval(updateTime, 10000);
    return () => clearInterval(interval);
  }, []);

  return (
    <div className="w-full bg-black select-none z-30">
      {/* Android System Status Bar (393px width standard) */}
      <div className="max-w-[393px] mx-auto px-5 pt-3 pb-1 flex items-center justify-between text-[12px] font-mono tracking-tight text-[#a1a1aa]">
        <span className="font-semibold text-[#f5f5f7]">{timeStr}</span>
        <div className="flex items-center gap-2">
          <span className="material-symbols-outlined text-[15px] text-[#f5f5f7]">signal_cellular_alt</span>
          <span className="text-[11px] font-semibold text-[#f5f5f7]">98%</span>
          <span className="material-symbols-outlined text-[15px] text-[#91d9bd]">battery_charging_full</span>
        </div>
      </div>
    </div>
  );
};
