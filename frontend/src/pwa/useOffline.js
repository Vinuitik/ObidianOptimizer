import { useEffect, useState } from 'react';
import { isOnline, onConnectivityChange } from './connectivity';

// React binding for the connectivity signal. Returns true when online.
// On reconnect, callers can trigger outbox.flush() (see P3 offline layer).
export default function useOffline() {
  const [online, setOnline] = useState(isOnline);
  useEffect(() => onConnectivityChange(setOnline), []);
  return online;
}
