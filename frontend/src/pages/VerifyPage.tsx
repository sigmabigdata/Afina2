import { useParams } from 'react-router-dom';
import { Card, Descriptions, Space, Tag, Typography } from 'antd';
import { CheckCircleFilled, SafetyCertificateOutlined, CloseCircleFilled } from '@ant-design/icons';

export default function VerifyPage() {
  const { uuid } = useParams();
  const ok = Boolean(uuid);
  return (
    <div className="verify-wrap">
      <div className="verify-card">
        <div style={{ textAlign: 'center', marginBottom: 20 }}>
          <div className="brand-mark" style={{ margin: '0 auto 12px' }}>A</div>
          <Typography.Title level={3} style={{ margin: 0 }}>Проверка подписи документа</Typography.Title>
          <Typography.Text type="secondary">Публичная страница · Афина</Typography.Text>
        </div>
        <Card>
          {ok ? (
            <>
              <div style={{ textAlign: 'center', padding: '8px 0 18px' }}>
                <CheckCircleFilled style={{ fontSize: 52, color: '#10b981' }} />
                <div style={{ fontWeight: 800, fontSize: 20, marginTop: 10 }}>Подпись действительна</div>
                <div style={{ color: 'var(--muted)' }}>Криптография, цепочка и CRL проверены</div>
              </div>
              <Descriptions column={1} items={[
                { key: 'doc', label: 'Документ', children: 'Договор поставки № 148-2026.pdf' },
                { key: 'signer', label: 'Подписант', children: 'Иванов И. И.' },
                { key: 'date', label: 'Подписан', children: '25.11.2026 17:02' },
                { key: 'crypto', label: 'Криптопроверка', children: <Tag color="success" icon={<CheckCircleFilled />}>УКЭП · ГОСТ 34.10-2012</Tag> },
                { key: 'chain', label: 'Цепочка и CRL', children: <Tag color="success" icon={<SafetyCertificateOutlined />}>Проверены</Tag> },
              ]} />
            </>
          ) : (
            <Space direction="vertical" align="center" style={{ width: '100%', padding: 30 }}>
              <CloseCircleFilled style={{ fontSize: 48, color: '#ef4444' }} />
              <Typography.Title level={4}>Документ не найден</Typography.Title>
            </Space>
          )}
        </Card>
      </div>
    </div>
  );
}
