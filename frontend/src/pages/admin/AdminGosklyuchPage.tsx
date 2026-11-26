import { useCallback, useEffect, useState } from 'react';
import {
  Alert, App, Button, Card, Collapse, Descriptions, Drawer, Empty, Select, Space, Spin, Table, Tag,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  CheckCircleFilled, CloseCircleFilled, DownloadOutlined, ExclamationCircleFilled,
  ReloadOutlined, SyncOutlined, ThunderboltOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import { OrderStatus } from '../../components/StatusTag';
import { api, download } from '../../lib/api';
import { errorText } from '../../lib/admin';
import { formatDateTime } from '../../lib/store';
import type {
  GosklyuchStatus, GosklyuchStatusDto, GosklyuchOrderDto,
} from '../../lib/types';

const STATUS_OPTIONS: { value: GosklyuchStatus; label: string }[] = [
  { value: 'NEW', label: 'Новая' },
  { value: 'SENT', label: 'Отправлена' },
  { value: 'SIGNED', label: 'Подписана' },
  { value: 'REJECTED', label: 'Отклонена' },
  { value: 'EXPIRED', label: 'Истёк срок' },
  { value: 'ERROR', label: 'Ошибка' },
];

function orderStatus(status: string): string {
  return status === 'SIGNED' ? 'signed'
    : status === 'REJECTED' ? 'rejected'
      : status === 'EXPIRED' ? 'expired'
        : status === 'ERROR' ? 'error' : 'sent';
}

const CHECK_TAG: Record<string, { color: string; label: string; icon: React.ReactNode }> = {
  OK: { color: 'success', label: 'OK', icon: <CheckCircleFilled /> },
  WARN: { color: 'warning', label: 'Предупреждение', icon: <ExclamationCircleFilled /> },
  ERROR: { color: 'error', label: 'Ошибка', icon: <CloseCircleFilled /> },
};

export default function AdminGosklyuchPage() {
  const { message, modal } = App.useApp();

  const [status, setStatus] = useState<GosklyuchStatusDto | null>(null);
  const [statusLoading, setStatusLoading] = useState(true);
  const [statusError, setStatusError] = useState<string>();

  const [orders, setOrders] = useState<GosklyuchOrderDto[]>([]);
  const [ordersLoading, setOrdersLoading] = useState(true);
  const [ordersError, setOrdersError] = useState<string>();
  const [filter, setFilter] = useState<'all' | GosklyuchStatus>('all');
  const [limit, setLimit] = useState(100);

  const [refreshAllBusy, setRefreshAllBusy] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [applyId, setApplyId] = useState<number | null>(null);

  const [detail, setDetail] = useState<GosklyuchOrderDto | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const loadStatus = useCallback(async () => {
    setStatusLoading(true);
    setStatusError(undefined);
    try {
      setStatus(await api<GosklyuchStatusDto>('/admin/gosklyuch/status'));
    } catch (e) {
      setStatusError(errorText(e, 'Не удалось получить состояние интеграции'));
    } finally {
      setStatusLoading(false);
    }
  }, []);

  const loadOrders = useCallback(async () => {
    setOrdersLoading(true);
    setOrdersError(undefined);
    try {
      const params = new URLSearchParams({ limit: String(limit) });
      if (filter !== 'all') params.set('status', filter);
      setOrders(await api<GosklyuchOrderDto[]>(`/admin/gosklyuch/orders?${params.toString()}`));
    } catch (e) {
      setOrdersError(errorText(e, 'Не удалось загрузить заявки'));
    } finally {
      setOrdersLoading(false);
    }
  }, [filter, limit]);

  useEffect(() => { void loadStatus(); }, [loadStatus]);
  useEffect(() => { void loadOrders(); }, [loadOrders]);

  const refreshAll = async () => {
    setRefreshAllBusy(true);
    try {
      const res = await api<{ updated: number }>('/admin/gosklyuch/refresh-all', { method: 'POST' });
      message.success(`Опрошено заявок: ${res.updated}`);
      await loadOrders();
      await loadStatus();
    } catch (e) {
      message.error(errorText(e, 'Не удалось обновить статусы заявок'));
    } finally {
      setRefreshAllBusy(false);
    }
  };

  const refreshOrder = async (order: GosklyuchOrderDto) => {
    setBusyId(order.id);
    try {
      const updated = await api<GosklyuchOrderDto>(
        `/admin/gosklyuch/orders/${order.id}/refresh`, { method: 'POST' },
      );
      setOrders((prev) => prev.map((item) => (item.id === updated.id ? updated : item)));
      message.success(`Статус заявки №${order.id} обновлён`);
    } catch (e) {
      message.error(errorText(e, 'Не удалось обновить заявку'));
    } finally {
      setBusyId(null);
    }
  };

  const applyOrder = (order: GosklyuchOrderDto) => {
    modal.confirm({
      title: `Применить подпись по заявке №${order.id}?`,
      content: 'Подпись будет проверена и добавлена к документу. '
        + 'При ошибке проверки заявка останется с ошибкой, а документ не изменится.',
      okText: 'Применить',
      onOk: async () => {
        setApplyId(order.id);
        try {
          await api<{ applied: boolean }>(`/admin/gosklyuch/orders/${order.id}/apply`, { method: 'POST' });
          message.success('Подпись проверена и применена');
          await loadOrders();
        } catch (e) {
          message.error(errorText(e, 'Подпись не применена'));
        } finally {
          setApplyId(null);
        }
      },
    });
  };

  const downloadSignature = async (order: GosklyuchOrderDto) => {
    try {
      await download(`/admin/gosklyuch/orders/${order.id}/signature`, `order_${order.id}.sig`);
    } catch (e) {
      message.error(errorText(e, 'Не удалось скачать подпись'));
    }
  };

  const openDetail = async (order: GosklyuchOrderDto) => {
    setDetail(order);
    setDetailLoading(true);
    try {
      setDetail(await api<GosklyuchOrderDto>(`/admin/gosklyuch/orders/${order.id}`));
    } catch (e) {
      message.error(errorText(e, 'Не удалось загрузить заявку'));
    } finally {
      setDetailLoading(false);
    }
  };

  const enabled = status?.enabled ?? false;

  const columns: ColumnsType<GosklyuchOrderDto> = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    {
      title: 'Клиент',
      dataIndex: 'ownerName',
      render: (ownerName: string | undefined, row) => (
        <div>
          <div>{ownerName || '—'}</div>
          <div style={{ color: 'var(--muted)', fontSize: 12 }}>владелец #{row.ownerId}</div>
        </div>
      ),
    },
    {
      title: 'Документ',
      dataIndex: 'documentName',
      render: (documentName: string | undefined, row) => (
        <div>
          <div>{documentName || '—'}</div>
          <div style={{ color: 'var(--muted)', fontSize: 12 }}>документ #{row.documentId}</div>
        </div>
      ),
    },
    {
      title: 'orderId',
      dataIndex: 'externalOrderId',
      width: 120,
      responsive: ['lg'],
      render: (externalOrderId?: number) => externalOrderId ?? '—',
    },
    {
      title: 'Обновлена',
      dataIndex: 'updatedAt',
      width: 170,
      responsive: ['xl'],
      render: (updatedAt: string | undefined, row) => formatDateTime(updatedAt ?? row.createdAt),
    },
    {
      title: 'Статус',
      dataIndex: 'status',
      width: 190,
      render: (value: string) => (
        <Space direction="vertical" size={2}>
          <OrderStatus status={orderStatus(value)} />
          <span style={{ color: 'var(--muted)', fontSize: 11 }}>{value}</span>
        </Space>
      ),
    },
    {
      title: 'Действия',
      key: 'actions',
      width: 260,
      render: (_: unknown, row) => (
        <Space wrap size={4}>
          <Button
            size="small"
            icon={<SyncOutlined />}
            loading={busyId === row.id}
            disabled={!enabled}
            onClick={() => void refreshOrder(row)}
          >
            Обновить
          </Button>
          <Button
            size="small"
            type="primary"
            ghost
            loading={applyId === row.id}
            disabled={!enabled || row.status !== 'SIGNED'}
            onClick={() => applyOrder(row)}
          >
            Применить
          </Button>
          <Button
            size="small"
            type="text"
            icon={<DownloadOutlined />}
            aria-label={`Скачать подпись заявки ${row.id}`}
            disabled={row.status !== 'SIGNED'}
            onClick={() => void downloadSignature(row)}
          />
          <Button size="small" type="link" onClick={() => void openDetail(row)}>
            Детали
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Заявки Госключа"
        subtitle="Состояние интеграции, заявки на подписание и применение подписей"
        extra={(
          <Space wrap>
            <Button icon={<ReloadOutlined />} loading={statusLoading || ordersLoading} onClick={() => { void loadStatus(); void loadOrders(); }}>
              Обновить
            </Button>
            <Button
              type="primary"
              icon={<ThunderboltOutlined />}
              loading={refreshAllBusy}
              disabled={!enabled}
              onClick={() => void refreshAll()}
            >
              Обновить статусы
            </Button>
          </Space>
        )}
      />

      {statusError && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="Не удалось получить состояние интеграции"
          description={statusError}
          action={<Button size="small" onClick={() => void loadStatus()}>Повторить</Button>}
        />
      )}

      {!statusLoading && status && !status.enabled && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="Интеграция с «Госключом» выключена"
          description={'Заявки не отправляются, действия недоступны. Проверьте настройки интеграции '
            + 'и диагностику ниже.'}
        />
      )}

      <div className="grid-2" style={{ alignItems: 'start' }}>
        <Card title="Состояние интеграции">
          {statusLoading ? (
            <div style={{ textAlign: 'center', padding: 32 }}><Spin size="large" /></div>
          ) : !status ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Состояние недоступно" />
          ) : (
            <Space direction="vertical" size={16} style={{ width: '100%' }}>
              <Descriptions
                column={1}
                size="small"
                items={[
                  {
                    key: 'enabled',
                    label: 'Интеграция',
                    children: status.enabled
                      ? <Tag color="success" bordered={false}>Включена</Tag>
                      : <Tag color="default" bordered={false}>Выключена</Tag>,
                  },
                  { key: 'provider', label: 'Провайдер', children: status.provider || '—' },
                  {
                    key: 'ready',
                    label: 'Готовность к отправке',
                    children: status.readyToSend
                      ? <Tag color="success" bordered={false}>Готова</Tag>
                      : <Tag color="warning" bordered={false}>Есть замечания</Tag>,
                  },
                  { key: 'max', label: 'Документов в пакете', children: status.maxDocuments },
                  { key: 'signer', label: 'Подписант пакета', children: status.signer || '—' },
                ]}
              />
              <div>
                <div style={{ fontWeight: 600, marginBottom: 8 }}>Самодиагностика конфигурации</div>
                <Space direction="vertical" size={8} style={{ width: '100%' }}>
                  {(status.diagnostics?.checks ?? []).map((check) => {
                    const cfg = CHECK_TAG[check.status] ?? CHECK_TAG.ERROR;
                    return (
                      <div
                        key={check.name}
                        style={{
                          display: 'flex', gap: 10, alignItems: 'flex-start', justifyContent: 'space-between',
                          padding: '8px 12px', borderRadius: 12, background: 'var(--surface-2)',
                          border: '1px solid var(--border)',
                        }}
                      >
                        <div style={{ minWidth: 0 }}>
                          <div style={{ fontWeight: 600 }}>{check.name}</div>
                          <div style={{ color: 'var(--muted)', fontSize: 12 }}>{check.message || '—'}</div>
                        </div>
                        <Tag color={cfg.color} icon={cfg.icon} bordered={false} style={{ borderRadius: 999 }}>
                          {cfg.label}
                        </Tag>
                      </div>
                    );
                  })}
                  {(status.diagnostics?.checks ?? []).length === 0 && (
                    <div style={{ color: 'var(--muted)' }}>Проверки не выполнялись</div>
                  )}
                </Space>
              </div>
              {status.diagnostics?.settings && (
                <Collapse
                  size="small"
                  items={[{
                    key: 'settings',
                    label: 'Параметры интеграции',
                    children: (
                      <Descriptions
                        column={1}
                        size="small"
                        items={Object.entries(status.diagnostics.settings).map(([key, value]) => ({
                          key,
                          label: key,
                          children: <code style={{ fontSize: 12 }}>{value || '—'}</code>,
                        }))}
                      />
                    ),
                  }]}
                />
              )}
            </Space>
          )}
        </Card>

        <Card
          title={`Заявки (${orders.length})`}
          styles={{ body: { padding: 0 } }}
        >
          {ordersError && (
            <Alert
              type="error"
              showIcon
              message={ordersError}
              style={{ margin: 16 }}
              action={<Button size="small" onClick={() => void loadOrders()}>Повторить</Button>}
            />
          )}
          <div style={{ padding: 16, display: 'flex', gap: 12, flexWrap: 'wrap' }}>
            <Select
              value={filter}
              onChange={(value) => setFilter(value)}
              style={{ width: 200 }}
              options={[{ value: 'all', label: 'Все статусы' }, ...STATUS_OPTIONS]}
            />
            <Select
              value={limit}
              onChange={setLimit}
              style={{ width: 140 }}
              options={[
                { value: 50, label: '50 заявок' },
                { value: 100, label: '100 заявок' },
                { value: 500, label: '500 заявок' },
              ]}
            />
          </div>
          <Table<GosklyuchOrderDto>
            rowKey="id"
            size="small"
            dataSource={orders}
            columns={columns}
            loading={ordersLoading}
            scroll={{ x: 900 }}
            locale={{
              emptyText: (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={enabled
                    ? 'Заявок нет — документы ещё не отправляли в «Госключ»'
                    : 'Интеграция выключена, заявки недоступны'}
                />
              ),
            }}
            pagination={{ pageSize: 10, showSizeChanger: false }}
          />
        </Card>
      </div>

      <Drawer
        open={!!detail}
        title={detail ? `Заявка №${detail.id}` : ''}
        width={560}
        onClose={() => setDetail(null)}
        destroyOnHidden
      >
        {detailLoading ? (
          <div style={{ textAlign: 'center', padding: 32 }}><Spin /></div>
        ) : detail ? (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            {detail.error && (
              <Alert type="error" showIcon message="Ошибка заявки" description={detail.error} />
            )}
            <Descriptions
              column={1}
              size="small"
              items={[
                { key: 'status', label: 'Статус', children: `${detail.status}${detail.statusName ? ` · ${detail.statusName}` : ''}` },
                { key: 'code', label: 'Код статуса', children: detail.statusCode ?? '—' },
                { key: 'ext', label: 'orderId ЕПГУ', children: detail.externalOrderId ?? '—' },
                { key: 'client', label: 'Клиент', children: detail.ownerName || `#${detail.ownerId}` },
                { key: 'doc', label: 'Документ', children: detail.documentName || `#${detail.documentId}` },
                { key: 'snils', label: 'СНИЛС подписанта', children: detail.signerSnils || '—' },
                { key: 'created', label: 'Создана', children: formatDateTime(detail.createdAt) },
                { key: 'updated', label: 'Обновлена', children: formatDateTime(detail.updatedAt) },
                { key: 'completed', label: 'Завершена', children: formatDateTime(detail.completedAt) },
              ]}
            />
            <div>
              <div style={{ fontWeight: 600, marginBottom: 8 }}>Позиции пакета</div>
              {detail.items.length === 0 ? (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Позиций нет" />
              ) : (
                <Table
                  rowKey="id"
                  size="small"
                  pagination={false}
                  dataSource={detail.items}
                  columns={[
                    { title: 'Документ', dataIndex: 'fileName' },
                    {
                      title: 'Подписан',
                      dataIndex: 'signed',
                      width: 110,
                      render: (signed: boolean) => signed
                        ? <Tag color="success" bordered={false}>Да</Tag>
                        : <Tag bordered={false}>Нет</Tag>,
                    },
                    {
                      title: 'Применён',
                      dataIndex: 'applied',
                      width: 110,
                      render: (applied: boolean) => applied
                        ? <Tag color="success" bordered={false}>Да</Tag>
                        : <Tag bordered={false}>Нет</Tag>,
                    },
                  ]}
                />
              )}
            </div>
          </Space>
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Заявка не найдена" />
        )}
      </Drawer>
    </>
  );
}
