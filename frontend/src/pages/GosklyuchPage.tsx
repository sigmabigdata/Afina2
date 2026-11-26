import { useState } from 'react';
import { Alert, App, Button, Card, Empty, Modal, Select, Space, Table, Tag, Typography } from 'antd';
import { ArrowRightOutlined, ReloadOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import PageHeader from '../components/PageHeader';
import { OrderStatus } from '../components/StatusTag';
import { api } from '../lib/api';
import { useApp } from '../lib/store';

/**
 * Подписание через мобильное приложение «Госключ».
 *
 * Отправка доступна, если интеграция включена на сервере; иначе
 * показываем пояснение и кнопка неактивна.
 */
export default function GosklyuchPage() {
  const { orders, folders, refreshOrders, refreshDocuments } = useApp();
  const { message } = App.useApp();
  const [open, setOpen] = useState(false);
  const [folderId, setFolderId] = useState<number | undefined>(folders[0]?.id);
  const [busy, setBusy] = useState(false);

  const columns = [
    { title: 'Документ', dataIndex: 'document' },
    { title: 'Клиент', dataIndex: 'client', responsive: ['md' as const] },
    { title: 'Заявка', dataIndex: 'externalId', responsive: ['lg' as const] },
    { title: 'Обновлена', dataIndex: 'updatedAt', responsive: ['xl' as const] },
    { title: 'Статус', dataIndex: 'status', render: (s: string) => <OrderStatus status={s} /> },
  ];

  const sendPackage = async () => {
    if (!folderId) {
      message.warning('Выберите раздел');
      return;
    }
    setBusy(true);
    try {
      const result = await api<{ orderId: number; documents: number }>(
        `/documents/folders/${folderId}/gosklyuch`, { method: 'POST' });
      message.success(`В «Госключ» отправлено документов: ${result.documents}`);
      setOpen(false);
      await refreshOrders();
      await refreshDocuments();
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'Не удалось отправить пакет');
    } finally {
      setBusy(false);
    }
  };

  const refresh = async () => {
    try {
      await refreshOrders();
      await refreshDocuments();
      message.success('Статусы обновлены');
    } catch {
      message.error('Не удалось обновить статусы');
    }
  };

  return (
    <>
      <PageHeader
        title="Госключ"
        subtitle="Подписание документов в мобильном приложении «Госключ»"
        extra={(
          <Space>
            <Button icon={<ReloadOutlined />} onClick={refresh}>Обновить</Button>
            <Button type="primary" icon={<ThunderboltOutlined />} onClick={() => setOpen(true)}>
              Отправить пакет
            </Button>
          </Space>
        )}
      />

      <div className="grid-3">
        <Card>
          <div style={{ color: 'var(--muted)' }}>В ожидании</div>
          <div style={{ fontSize: 30, fontWeight: 800 }}>
            {orders.filter((o) => o.status === 'sent').length}
          </div>
        </Card>
        <Card>
          <div style={{ color: 'var(--muted)' }}>Подписано</div>
          <div style={{ fontSize: 30, fontWeight: 800 }}>
            {orders.filter((o) => o.status === 'signed').length}
          </div>
        </Card>
        <Card>
          <div style={{ color: 'var(--muted)' }}>Отказы / истекли</div>
          <div style={{ fontSize: 30, fontWeight: 800 }}>
            {orders.filter((o) => o.status === 'expired' || o.status === 'rejected').length}
          </div>
        </Card>
      </div>

      <Card style={{ marginTop: 16 }} styles={{ body: { padding: 0 } }} title="Мои заявки">
        {orders.length === 0 ? (
          <Empty style={{ padding: 40 }} description="Заявок пока нет" />
        ) : (
          <Table rowKey="id" dataSource={orders} columns={columns} pagination={false} />
        )}
      </Card>

      <Card style={{ marginTop: 16 }} title="Как это работает">
        <div className="grid-3">
          <div>
            <Tag color="processing">1</Tag>
            <div style={{ fontWeight: 700, marginTop: 8 }}>Отправьте документ</div>
            <div style={{ color: 'var(--muted)' }}>Один документ или пакет до 15 файлов</div>
          </div>
          <div>
            <Tag color="processing">2</Tag>
            <div style={{ fontWeight: 700, marginTop: 8 }}>Клиент подписывает</div>
            <div style={{ color: 'var(--muted)' }}>В приложении «Госключ» по СНИЛС</div>
          </div>
          <div>
            <Tag color="processing">3</Tag>
            <div style={{ fontWeight: 700, marginTop: 8 }}>Подпись возвращается</div>
            <div style={{ color: 'var(--muted)' }}>Сервер проверяет цепочку и CRL</div>
          </div>
        </div>
        <Link to="/admin/gosklyuch">
          <Button type="link" icon={<ArrowRightOutlined />} style={{ paddingLeft: 0, marginTop: 8 }}>
            Все заявки в админке
          </Button>
        </Link>
      </Card>

      <Modal
        open={open}
        title="Отправить пакет документов"
        onCancel={() => setOpen(false)}
        onOk={sendPackage}
        okText="Отправить"
        confirmLoading={busy}
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="В заявку попадут все неподписанные документы раздела"
          description="Не более 15 файлов за одну заявку — ограничение API ЕПГУ. Клиент подпишет их в приложении «Госключ»."
        />
        <Typography.Text strong>Раздел</Typography.Text>
        <Select
          style={{ width: '100%', marginTop: 8 }}
          value={folderId}
          onChange={setFolderId}
          placeholder="Выберите раздел"
          options={folders.map((f) => ({ value: f.id, label: f.name }))}
        />
      </Modal>
    </>
  );
}
