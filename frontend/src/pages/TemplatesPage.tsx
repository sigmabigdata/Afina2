import { App, Button, Card, Tag, Typography } from 'antd';
import { FileAddOutlined, FileTextOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import PageHeader from '../components/PageHeader';
import { useApp } from '../lib/store';

export default function TemplatesPage() {
  const { templates } = useApp();
  const { message } = App.useApp();

  return (
    <>
      <PageHeader title="Шаблоны" subtitle="Готовые формы для документов и согласий" />
      <div className="grid-3">
        {templates.map((t) => (
          <Card key={t.id} hoverable>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
              <div style={{ width: 44, height: 44, borderRadius: 13, background: 'rgba(79,70,229,.1)', color: '#4f46e5', display: 'grid', placeItems: 'center', fontSize: 20 }}>
                {t.kind === 'consent' ? <SafetyCertificateOutlined /> : <FileTextOutlined />}
              </div>
              <Tag bordered={false} color={t.kind === 'consent' ? 'purple' : 'blue'} style={{ borderRadius: 999 }}>
                {t.kind === 'consent' ? 'Согласие' : 'Документ'}
              </Tag>
            </div>
            <Typography.Title level={4} style={{ marginTop: 14, marginBottom: 4 }}>{t.name}</Typography.Title>
            <Typography.Paragraph type="secondary" style={{ minHeight: 44 }}>{t.description}</Typography.Paragraph>
            <div style={{ color: 'var(--muted)', fontSize: 12, marginBottom: 14 }}>{t.fields} полей</div>
            <Button type="primary" block icon={<FileAddOutlined />}
              onClick={() => message.success(`Документ по шаблону «${t.name}» сформирован (демо)`)}>
              Сформировать
            </Button>
          </Card>
        ))}
      </div>
    </>
  );
}
