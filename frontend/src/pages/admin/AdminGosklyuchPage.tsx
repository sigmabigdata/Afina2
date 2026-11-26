import { App, Button, Card, Space, Table } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import { OrderStatus } from '../../components/StatusTag';
import { useApp } from '../../lib/store';
import type { OrderRow } from '../../lib/store';

export default function AdminGosklyuchPage() {
  const { orders } = useApp();
  const { message } = App.useApp();

  return (
    <>
      <PageHeader title="Заявки Госключа" subtitle="Управление отправками и применение подписей"
        extra={<Button icon={<ReloadOutlined />} onClick={() => message.success('Статусы обновлены (демо)')}>Обновить статусы</Button>} />
      <Card styles={{ body: { padding: 0 } }}>
        <Table rowKey="id" dataSource={orders} columns={[
          { title: 'ID', dataIndex: 'id', width: 70 },
          { title: 'Клиент', dataIndex: 'client' },
          { title: 'Документ', dataIndex: 'document' },
          { title: 'orderId', dataIndex: 'externalId', responsive: ['lg' as const] },
          { title: 'Обновлена', dataIndex: 'updatedAt', responsive: ['xl' as const] },
          { title: 'Статус', dataIndex: 'status', render: (s: string) => <OrderStatus status={s} /> },
          {
            title: 'Действия', key: 'actions', render: (_: unknown, row: OrderRow) => (
              <Space>
                <Button size="small" onClick={() => message.success(`Статус заявки ${row.id} обновлён`)}>Обновить</Button>
                <Button size="small" type="primary" ghost disabled={row.status !== 'signed'}
                  onClick={() => message.success('Подпись проверена и применена')}>Применить</Button>
              </Space>
            ),
          },
        ]} />
      </Card>
    </>
  );
}
