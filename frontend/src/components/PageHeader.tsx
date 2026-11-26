import type { ReactNode } from 'react';

export default function PageHeader({
  title, subtitle, extra,
}: { title: string; subtitle?: string; extra?: ReactNode }) {
  return (
    <div className="page-head">
      <div>
        <h1 className="page-title">{title}</h1>
        {subtitle && <div className="page-sub">{subtitle}</div>}
      </div>
      {extra && <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>{extra}</div>}
    </div>
  );
}
