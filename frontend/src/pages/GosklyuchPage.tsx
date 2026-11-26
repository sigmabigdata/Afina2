import { Button, Card, Table, Tag } from 'antd';
import { ArrowRightOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import PageHeader from '../components/PageHeader';
import { OrderStatus } from '../components/StatusTag';
import { useApp } from '../lib/store';

export default function GosklyuchPage() {
  const { orders } = useApp();
  const columns = [
    { title: 'Документ', dataIndex: 'document' },
    { title: 'Клиент', dataIndex: 'client', responsive: ['md' as const] },
    { title: 'Заявка', dataIndex: 'externalId', responsive: ['lg' as const] },
    { title: 'Обновлена', dataIndex: 'updatedAt', responsive: ['xl' as const] },
    { title: 'Статус', dataIndex: 'status', render: (s: string) => <OrderStatus status={s} /> },
  ];

  return (
    <>
      <PageHeader title="Госключ" subtitle="Подписание документов в мобильном приложении «Госключ»" />
      <div className="grid-3">
        <Card><div style={{ color: 'var(--muted)' }}>В ожидании</div><div style={{ fontSize: 30, fontWeight: 800 }}>{orders.filter((o) => o.status === 'sent').length}</div></Card>
        <Card><div style={{ color: 'var(--muted)' }}>Подписано</div><div style={{ fontSize: 30, fontWeight: 800 }}>{orders.filter((o) => o.status === 'signed').length}</div></Card>
        <Card><div style={{ color: 'var(--muted)' }}>Отказы / истекли</div><div style={{ fontSize: 30, fontWeight: 800 }}>{orders.filter((o) => o.status === 'expired' || o.status === 'rejected').length}</div></Card>
      </div>
      <Card style={{ marginTop: 16 }} styles={{ body: { padding: 0 } }} title="Мои заявки">
        <Table rowKey="id" dataSource={orders} columns={columns} pagination={false} />
      </Card>
      <Card style={{ marginTop: 16 }} title="Как это работает">
        <div className="grid-3">
          <div><Tag color="processing">1</Tag><div style={{ fontWeight: 700, marginTop: 8 }}>Отправьте документ</div><div style={{ color: 'var(--muted)' }}>Один документ или пакет до 15 файлов</div></div>
          <div><Tag color="processing">2</Tag><div style={{ fontWeight: 700, marginTop: 8 }}>Клиент подписывает</div><div style={{ color: 'var(--muted)' }}>В приложении «Госключ» по СНИЛС</div></div>
          <div><Tag color="processing">3</Tag><div style={{ fontWeight: 700, marginTop: 8 }}>Подпись возвращается</div><div style={{ color: 'var(--muted)' }}>Сервер проверяет цепочку и CRL</div></div>
        </div>
        <Link to="/admin/gosklyuch"><Button type="link" icon={<ArrowRightOutlined />} style={{ paddingLeft: 0 }}>Все заявки в админке</Button></Link>
        <Button icon={<ThunderboltOutlined />} style={{ marginLeft: 12 }}>Отправить пакет</Button>
      </Card>
    </>
  );
}
