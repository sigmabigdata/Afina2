import { useState } from 'react';
import { App, Button, Card, Descriptions, Space, Tag, Timeline, Typography } from 'antd';
import {
  ArrowLeftOutlined, CheckCircleFilled, DeleteOutlined, DownloadOutlined,
  SafetyCertificateOutlined, ThunderboltOutlined,
} from '@ant-design/icons';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { DocStatus } from '../components/StatusTag';
import SignModal from '../components/SignModal';
import { useApp } from '../lib/store';

export default function DocumentPage() {
  const { id } = useParams();
  const { documents, deleteDocument, user } = useApp();
  const navigate = useNavigate();
  const { message, modal } = App.useApp();
  const [signOpen, setSignOpen] = useState(false);
  const doc = documents.find((d) => String(d.id) === id);

  if (!doc) {
    return (
      <Card>
        <Typography.Title level={4}>Документ не найден</Typography.Title>
        <Link to="/documents">← К документам</Link>
      </Card>
    );
  }

  return (
    <>
      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(-1)}>Назад</Button>
        <Link to="/documents">Документы</Link>
        <span style={{ color: 'var(--muted)' }}>/</span>
        <span>{doc.folder}</span>
      </Space>

      <div className="page-head">
        <div>
          <h1 className="page-title" style={{ fontSize: 26 }}>{doc.name}</h1>
          <Space style={{ marginTop: 8 }}>
            <DocStatus status={doc.status} />
            <Tag bordered={false}>{doc.size}</Tag>
            <Tag bordered={false}>{doc.uploadedAt}</Tag>
          </Space>
        </div>
        <Space wrap>
          {doc.status !== 'signed' && (
            <Button type="primary" icon={<SafetyCertificateOutlined />} onClick={() => setSignOpen(true)}>Подписать</Button>
          )}
          {doc.status !== 'signed' && (
            <Button icon={<ThunderboltOutlined />} onClick={() => {
              setSignOpen(false);
              message.info('Заявка в «Госключ» создана (демо)');
            }}>Подписать в Госключ</Button>
          )}
          <Button icon={<DownloadOutlined />}>Скачать .sig</Button>
          <Button danger icon={<DeleteOutlined />} onClick={() => modal.confirm({
            title: 'Удалить документ?',
            content: 'Действие попадёт в аудит.',
            okText: 'Удалить', cancelText: 'Отмена', okButtonProps: { danger: true },
            onOk: () => { deleteDocument(doc.id); message.success('Удалено'); navigate('/documents'); },
          })} />
        </Space>
      </div>

      <div className="grid-2">
        <Card title="Предпросмотр" styles={{ body: { padding: 20 } }}>
          <div className="pdf-preview">
            <div style={{ fontSize: 20, fontWeight: 800, marginBottom: 20 }}>{doc.name.replace('.pdf', '')}</div>
            <div className="pdf-line" /><div className="pdf-line" /><div className="pdf-line short" />
            <div style={{ height: 18 }} />
            <div className="pdf-line" /><div className="pdf-line short" />
            <div style={{ height: 18 }} />
            <div className="pdf-line" /><div className="pdf-line" /><div className="pdf-line short" />
            {doc.status === 'signed' && (
              <div className="pdf-stamp"><CheckCircleFilled /> ПОДПИСАНО · УКЭП</div>
            )}
          </div>
        </Card>
        <div style={{ display: 'grid', gap: 16, alignContent: 'start' }}>
          <Card title="Реквизиты">
            <Descriptions column={1} size="small" items={[
              { key: '1', label: 'Раздел', children: doc.folder },
              { key: '2', label: 'Тип', children: doc.type.toUpperCase() },
              { key: '3', label: 'Загружен', children: doc.uploadedAt },
              { key: '4', label: 'Владелец', children: user.name },
              { key: '5', label: 'Статус', children: <DocStatus status={doc.status} /> },
            ]} />
          </Card>
          <Card title="Подписи">
            {doc.signers.length === 0 ? <Typography.Text type="secondary">Подписей пока нет</Typography.Text> : (
              <Timeline items={doc.signers.map((s, i) => ({
                key: s + i,
                color: 'green',
                dot: <CheckCircleFilled style={{ color: '#10b981' }} />,
                children: <div><b>{s}</b><div className="timeline-meta">УКЭП · CAdES-BES · проверена</div></div>,
              }))} />
            )}
            {doc.gosklyuch && <Tag color="processing" style={{ marginTop: 10 }}>{doc.gosklyuch}</Tag>}
          </Card>
          <Card title="Безопасность">
            <Space direction="vertical">
              <span><CheckCircleFilled style={{ color: '#10b981' }} /> Подпись проверяется на сервере</span>
              <span><CheckCircleFilled style={{ color: '#10b981' }} /> Цепочка сертификатов и CRL</span>
              <span><CheckCircleFilled style={{ color: '#10b981' }} /> Действие записано в аудит</span>
            </Space>
          </Card>
        </div>
      </div>

      <SignModal doc={doc} open={signOpen} onClose={() => setSignOpen(false)} />
    </>
  );
}
