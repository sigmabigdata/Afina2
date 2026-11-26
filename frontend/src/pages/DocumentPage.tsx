import { useCallback, useEffect, useState } from 'react';
import {
  Alert, App, Button, Card, Descriptions, Empty, Space, Spin, Tag, Timeline, Tooltip, Typography,
} from 'antd';
import {
  ArrowLeftOutlined, DeleteOutlined, DownloadOutlined, EyeOutlined, FileProtectOutlined,
  LinkOutlined, SafetyCertificateOutlined, ThunderboltOutlined,
} from '@ant-design/icons';
import { Link, useNavigate, useParams } from 'react-router-dom';
import PageHeader from '../components/PageHeader';
import SignModal from '../components/SignModal';
import { DocStatus, OrderStatus } from '../components/StatusTag';
import { api, download, resourceUrl } from '../lib/api';
import {
  documentStatus, formatDateTime, toDocumentItem, useApp,
} from '../lib/store';
import type { DocumentDto } from '../lib/types';

export default function DocumentPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { message, modal } = App.useApp();
  const {
    deleteDocument, sendToGosklyuch, downloadSignature, downloadDocument,
    refreshDocuments, isAdmin,
  } = useApp();
  const [doc, setDoc] = useState<DocumentDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [signOpen, setSignOpen] = useState(false);
  const [preview, setPreview] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    setError(undefined);
    try {
      setDoc(await api<DocumentDto>(`/documents/${id}`));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Документ не найден');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { void load(); }, [load]);

  if (loading) {
    return <Card><div style={{ textAlign: 'center', padding: 48 }}><Spin size="large" /></div></Card>;
  }

  if (error || !doc) {
    return (
      <Card>
        <Typography.Title level={4}>Документ не найден</Typography.Title>
        <Typography.Paragraph type="secondary">{error}</Typography.Paragraph>
        <Link to="/documents">← К документам</Link>
      </Card>
    );
  }

  const status = documentStatus(doc);
  const item = toDocumentItem(doc);
  const isPdf = doc.contentType === 'application/pdf' || doc.extension === 'pdf';

  const handleGosklyuch = async () => {
    setBusy(true);
    try {
      await sendToGosklyuch(doc.id);
      message.success('Документ отправлен в «Госключ»');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'Не удалось отправить в «Госключ»');
    } finally {
      setBusy(false);
    }
  };

  const handleDelete = () => {
    modal.confirm({
      title: 'Удалить документ?',
      content: doc.signed
        ? 'Документ подписан. Удаление доступно только администратору и попадёт в аудит.'
        : 'Действие попадёт в журнал аудита.',
      okText: 'Удалить',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await deleteDocument(doc.id);
          message.success('Документ удалён');
          navigate('/documents');
        } catch (e) {
          message.error(e instanceof Error ? e.message : 'Не удалось удалить документ');
        }
      },
    });
  };

  const copyPublicLink = async () => {
    try {
      const { url } = await api<{ url: string }>(`/documents/${doc.id}/public-link`);
      await navigator.clipboard.writeText(url);
      message.success('Ссылка для проверки подписи скопирована');
    } catch {
      message.error('Не удалось получить публичную ссылку');
    }
  };

  return (
    <>
      <Space style={{ marginBottom: 16 }} wrap>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(-1)}>Назад</Button>
        <Link to="/documents">Документы</Link>
        <span style={{ color: 'var(--muted)' }}>/</span>
        <span>{doc.folderName ?? 'Без раздела'}</span>
      </Space>

      <PageHeader
        title={doc.name}
        subtitle={`${doc.sizePretty} · загружен ${formatDateTime(doc.uploadedAt)}`}
        extra={(
          <Space wrap>
            {!doc.signed && (
              <Button type="primary" icon={<SafetyCertificateOutlined />} onClick={() => setSignOpen(true)}>
                Подписать
              </Button>
            )}
            {!doc.signed && (
              <Tooltip title="Отправить документ в мобильное приложение «Госключ»">
                <Button icon={<ThunderboltOutlined />} loading={busy} onClick={handleGosklyuch}>
                  Госключ
                </Button>
              </Tooltip>
            )}
            <Button icon={<DownloadOutlined />} onClick={() => downloadDocument(doc.id)}>
              Скачать
            </Button>
            <Button danger icon={<DeleteOutlined />} onClick={handleDelete}>Удалить</Button>
          </Space>
        )}
      />

      <Space wrap style={{ marginBottom: 16 }}>
        <DocStatus status={status} />
        <Tag bordered={false}>{doc.extension.toUpperCase() || 'FILE'}</Tag>
        {doc.signaturesCount > 0 && <Tag color="success">{doc.signaturesCount} подп.</Tag>}
        {doc.visualization && <Tag bordered={false}>штампы v{doc.visualization.version}</Tag>}
        {doc.gosklyuchOrder && <OrderStatus status={
          doc.gosklyuchOrder.status === 'SIGNED' ? 'signed'
            : doc.gosklyuchOrder.status === 'REJECTED' ? 'rejected'
              : doc.gosklyuchOrder.status === 'EXPIRED' ? 'expired'
                : doc.gosklyuchOrder.status === 'ERROR' ? 'error' : 'sent'
        } />}
      </Space>

      {doc.gosklyuchOrder && !doc.signed && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="Документ отправлен в «Госключ»"
          description={`Подписант получил уведомление. Статус заявки: ${doc.gosklyuchOrder.statusName ?? doc.gosklyuchOrder.status}.`}
        />
      )}

      <div className="grid-2">
        <Card
          title="Предпросмотр"
          extra={isPdf && (
            <Button size="small" icon={<EyeOutlined />} onClick={() => setPreview((v) => !v)}>
              {preview ? 'Скрыть' : 'Показать'}
            </Button>
          )}
        >
          {isPdf && preview ? (
            <iframe
              title="Документ"
              src={resourceUrl(`/documents/${doc.id}/content`)}
              style={{ width: '100%', height: 520, border: 'none', borderRadius: 12, background: '#fff' }}
            />
          ) : (
            <Empty
              description={isPdf
                ? 'Нажмите «Показать», чтобы открыть документ'
                : `Предпросмотр для формата ${doc.extension.toUpperCase()} недоступен`}
              image={Empty.PRESENTED_IMAGE_SIMPLE}
            >
              <Button icon={<DownloadOutlined />} onClick={() => downloadDocument(doc.id)}>
                Скачать файл
              </Button>
            </Empty>
          )}
        </Card>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <Card title="Сведения">
            <Descriptions column={1} size="small" items={[
              { key: 'folder', label: 'Раздел', children: doc.folderName ?? 'Без раздела' },
              { key: 'size', label: 'Размер', children: doc.sizePretty },
              { key: 'uploaded', label: 'Загружен', children: formatDateTime(doc.uploadedAt) },
              { key: 'status', label: 'Статус', children: <DocStatus status={status} /> },
              ...(doc.signerSubject ? [{
                key: 'signer', label: 'Подписант', children: doc.signerSubject,
              }] : []),
              ...(doc.signedAt ? [{
                key: 'signedAt', label: 'Подписан', children: formatDateTime(doc.signedAt),
              }] : []),
              {
                key: 'sha', label: 'SHA-256', children: (
                  <Typography.Text copyable={{ text: doc.fileSha256 }} style={{ fontSize: 12 }}>
                    {(doc.fileSha256 ?? '').slice(0, 24)}…
                  </Typography.Text>
                ),
              },
            ]} />
          </Card>

          <Card title={`Подписи (${doc.signatures.length})`}>
            {doc.signatures.length === 0 ? (
              <Typography.Text type="secondary">Подписей пока нет</Typography.Text>
            ) : (
              <Timeline
                items={doc.signatures.map((s) => ({
                  color: 'green',
                  children: (
                    <div>
                      <div style={{ fontWeight: 700 }}>{s.signerName || s.signerSubject}</div>
                      <div style={{ color: 'var(--muted)', fontSize: 12 }}>
                        {formatDateTime(s.signedAt)}
                        {s.signerSerial ? ` · серийный № ${s.signerSerial}` : ''}
                      </div>
                      <Space style={{ marginTop: 6 }} wrap>
                        {s.applyPoa && <Tag color="purple">по доверенности</Tag>}
                        <Button size="small" type="link" style={{ padding: 0 }}
                          onClick={() => download(`/documents/${doc.id}/signatures/${s.id}/download`,
                            `${doc.name}.sig`)}>
                          Скачать .sig
                        </Button>
                      </Space>
                    </div>
                  ),
                }))}
              />
            )}
            {doc.signed && (
              <Space wrap style={{ marginTop: 8 }}>
                <Button size="small" icon={<DownloadOutlined />}
                  onClick={() => downloadSignature(doc.id)}>
                  Основная подпись
                </Button>
                <Button size="small" icon={<FileProtectOutlined />}
                  onClick={() => download(`/documents/${doc.id}/download-signed`,
                    `${doc.name}_signed.zip`)}>
                  Документ + .sig
                </Button>
              </Space>
            )}
          </Card>

          <Card title="Проверка и подтверждение">
            <Space direction="vertical" size={10} style={{ width: '100%' }}>
              <Button block icon={<FileProtectOutlined />}
                onClick={() => download(`/documents/${doc.id}/signing-certificate`,
                  `СПРАВКА_${doc.name}.pdf`)}>
                Справка о подписании (PDF)
              </Button>
              {doc.signaturesCount > 0 && (
                <Button block icon={<FileProtectOutlined />}
                  onClick={() => download(`/documents/${doc.id}/visualization`,
                    `ВИЗУАЛИЗАЦИЯ_${doc.name}.pdf`)}>
                  Визуализация подписи (PDF)
                </Button>
              )}
              <Button block icon={<LinkOutlined />} onClick={copyPublicLink}>
                Публичная ссылка для проверки
              </Button>
              {isAdmin && doc.ownerId && (
                <Link to={`/admin/clients/${doc.ownerId}`}>
                  <Button block type="text">Карточка клиента</Button>
                </Link>
              )}
            </Space>
          </Card>
        </div>
      </div>

      <SignModal
        doc={item}
        open={signOpen}
        onClose={async () => {
          setSignOpen(false);
          await load();
          await refreshDocuments();
        }}
      />
    </>
  );
}
