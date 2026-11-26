import { Navigate } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useApp } from '../lib/store';

/** Пропускает только администраторов. */
export default function AdminOnly({ children }: { children: ReactNode }) {
  const { user } = useApp();
  if (user?.role !== 'admin') {
    return <Navigate to="/dashboard" replace />;
  }
  return <>{children}</>;
}
