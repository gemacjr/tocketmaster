import { useEffect, useState } from 'react';

/** mm:ss countdown from an ISO timestamp; turns red under 60s, fires onExpire once. */
export default function CountdownTimer({ expiresAt, onExpire }) {
  const [remaining, setRemaining] = useState(() => msLeft(expiresAt));

  useEffect(() => {
    if (remaining <= 0) return;
    const t = setInterval(() => {
      const left = msLeft(expiresAt);
      setRemaining(left);
      if (left <= 0) {
        clearInterval(t);
        onExpire?.();
      }
    }, 1000);
    return () => clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [expiresAt]);

  if (remaining <= 0) return <span className="countdown expired">EXPIRED</span>;

  const mins = Math.floor(remaining / 60000);
  const secs = Math.floor((remaining % 60000) / 1000);
  return (
    <span className={`countdown ${remaining < 60000 ? 'warn' : ''}`}>
      {String(mins).padStart(2, '0')}:{String(secs).padStart(2, '0')}
    </span>
  );
}

function msLeft(iso) {
  return Math.max(0, new Date(iso).getTime() - Date.now());
}
