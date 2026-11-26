import type { ReactNode } from 'react';

export default function StatCard({
  icon, value, label, trend, glow,
}: { icon: ReactNode; value: ReactNode; label: string; trend?: string; glow?: string }) {
  return (
    <div className="stat-card" style={{ ['--stat-glow' as string]: glow ?? 'rgba(79,70,229,.12)' }}>
      <div className="stat-icon">{icon}</div>
      <div className="stat-value">{value}</div>
      <div className="stat-label">{label}</div>
      {trend && <div className="stat-trend" style={{ color: 'var(--mint)' }}>{trend}</div>}
    </div>
  );
}
