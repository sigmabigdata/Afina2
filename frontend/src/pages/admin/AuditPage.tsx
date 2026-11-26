import { useCallback, useEffect, useState } from 'react';
import {
  Alert, App, Button, Card, DatePicker, Empty, Input, InputNumber, Modal, Select, Space, Table, Tag,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { DeleteOutlined, DownloadOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import type { Dayjs } from 'dayjs';
import PageHeader from '../../components/PageHeader';
import { api, download } from '../../lib/api';
import { errorText } from '../../lib/admin';
import { formatDateTime } from '../../lib/store';
import type { AuditEventDto, AuditStatsDto, PageDto } from '../../lib/types';

const PAGE_SIZE = 20;

const RESULT_OPTIONS = [
  { value: 'SUCCESS', label: 'Успех' },
  { value: 'FAIL', label: 'Ошибка' },
  { value: 'WARN', label: 'Предупреждение' },
];

function resultTag(result: string) {
  if (result === 'SUCCESS') return <Tag color="success" bordered={false} style={{ borderRadius: 999 }}>Успех</Tag>;
  if (result === 'FAIL') return <Tag color="error" bordered={false} style={{ borderRadius: 999 }}>Ошибка</Tag>;
  return <Tag color="warning" bordered={false} style={{ borderRadius: 999 }}>{result === 'WARN' ? 'Внимание' : result}</Tag>;
}

export default function AuditPage() {
  const { message, modal } = App.useApp();

  const [rows, setRows] = useState<AuditEventDto[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [stats, setStats] = useState<AuditStatsDto | null>(null);

  const [type, setType] = useState<string | undefined>();
  const [result, setResult] = useState<string | undefined>();
  const [actorInput, setActorInput] = useState('');
  const [actor, setActor] = useState('');
  const [range, setRange] = useState<[Dayjs, Dayjs] | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [exporting, setExporting] = useState(false);
  const [cleanupOpen, setCleanupOpen] = useState(false);
  const [cleanupDays, setCleanupDays] = useState(365);
  const [cleaning, setCleaning] = useState(false);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setActor(actorInput.trim());
      setPage(1);
    }, 350);
    return () => window.clearTimeout(timer);
  }, [actorInput]);

  const filterQuery = useCallback(() => {
    const params = new URLSearchParams();
    if (type) params.set('type', type);
    if (result) params.set('result', result);
    if (actor) params.set('actor', actor);
    if (range) {
      params.set('from', range[0].toISOString());
      params.set('to', range[1].toISOString());
    }
    return params;
  }, [type, result, actor, range]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      const params = filterQuery();
      params.set('page', String(page - 1));
      params.set('size', String(PAGE_SIZE));
      const data = await api<PageDto<AuditEventDto>>(`/admin/audit?${params.toString()}`);
      setRows(data.items);
      setTotal(data.total);
    } catch (e) {
      setError(errorText(e, 'Не удалось загрузить журнал аудита'));
      setRows([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [filterQuery, page]);

  const loadStats = useCallback(async () => {
    try {
      setStats(await api<AuditStatsDto>('/admin/audit/stats'));
    } catch {
      setStats(null);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => { void loadStats(); }, [loadStats]);

  const resetFilters = () => {
    setType(undefined);
    setResult(undefined);
    setActorInput('');
    setActor('');
    setRange(null);
    setPage(1);
  };

  const exportCsv = async () => {
    setExporting(true);
    try {
      const params = filterQuery();
      const qs = params.toString();
      await download(`/admin/audit/export.csv${qs ? `?${qs}` : ''}`, 'audit.csv');
      message.success('Журнал выгружен в CSV');
    } catch (e) {
      message.error(errorText(e, 'Не удалось выгрузить журнал'));
    } finally {
      setExporting(false);
    }
  };

  const cleanup = async (days: number) => {
    setCleaning(true);
    try {
      const res = await api<{ deleted: number }>(
        `/admin/audit/cleanup?days=${days}`, { method: 'DELETE' },
      );
      message.success(`Удалено событий: ${res.deleted}`);
      setCleanupOpen(false);
      await load();
      await loadStats();
    } catch (e) {
      message.error(errorText(e, 'Не удалось очистить журнал'));
    } finally {
      setCleaning(false);
    }
  };

  const confirmCleanup = () => {
    const days = cleanupDays;
    setCleanupOpen(false);
    modal.confirm({
      title: 'Очистить журнал аудита?',
      content: `Будут безвозвратно удалены события старше ${days} дн. Действие попадёт в аудит.`,
      okText: 'Удалить',
      okButtonProps: { danger: true },
      onOk: () => cleanup(days),
    });
  };

  const typeOptions = Object.keys(stats?.byType ?? {}).map((key) => ({
    value: key,
    label: `${key} (${stats?.byType[key] ?? 0})`,
  }));

  const columns: ColumnsType<AuditEventDto> = [
    {
      title: 'Время',
      dataIndex: 'eventTime',
      width: 170,
      render: (eventTime: string) => formatDateTime(eventTime),
    },
    {
      title: 'Пользователь',
      dataIndex: 'actorEmail',
      render: (email: string | undefined, row) => (
        <div>
          <div>{email || 'система'}</div>
          {row.actorRole && (
            <div style={{ color: 'var(--muted)', fontSize: 12 }}>{row.actorRole}</div>
          )}
        </div>
      ),
    },
    {
      title: 'Действие',
      dataIndex: 'eventType',
      render: (eventType: string) => <code style={{ fontSize: 12 }}>{eventType}</code>,
    },
    {
      title: 'Объект',
      dataIndex: 'targetInfo',
      responsive: ['md'],
      render: (info: string | undefined, row) => info || row.targetType || '—',
    },
    {
      title: 'Результат',
      dataIndex: 'result',
      width: 150,
      render: (value: string) => resultTag(value),
    },
  ];

  return (
    <>
      <PageHeader
        title="Аудит"
        subtitle="Все действия пользователей и системы · фильтры, выгрузка и очистка"
        extra={(
          <Space wrap>
            <Button icon={<ReloadOutlined />} onClick={() => { void load(); void loadStats(); }} loading={loading}>
              Обновить
            </Button>
            <Button icon={<DownloadOutlined />} onClick={() => void exportCsv()} loading={exporting}>
              Экспорт CSV
            </Button>
            <Button danger icon={<DeleteOutlined />} onClick={() => setCleanupOpen(true)}>
              Очистить
            </Button>
          </Space>
        )}
      />

      {error && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="Не удалось загрузить журнал"
          description={error}
          action={<Button size="small" onClick={() => void load()}>Повторить</Button>}
        />
      )}

      <div className="stat-grid" style={{ marginBottom: 16 }}>
        <Card>
          <div className="stat-label">Всего событий</div>
          <div className="stat-value">{stats?.total ?? '—'}</div>
        </Card>
        <Card>
          <div className="stat-label">За сегодня</div>
          <div className="stat-value">{stats?.today ?? '—'}</div>
        </Card>
        <Card>
          <div className="stat-label">Ошибок</div>
          <div className="stat-value" style={{ color: stats?.failures ? '#ef4444' : undefined }}>
            {stats?.failures ?? '—'}
          </div>
        </Card>
        <Card>
          <div className="stat-label">Типов событий</div>
          <div className="stat-value">{stats ? Object.keys(stats.byType).length : '—'}</div>
        </Card>
      </div>

      <Card styles={{ body: { paddingBottom: 8 } }}>
        <Space wrap style={{ width: '100%' }}>
          <Select
            allowClear
            showSearch
            value={type}
            onChange={(value?: string) => { setType(value); setPage(1); }}
            placeholder="Все типы событий"
            style={{ width: 240 }}
            options={typeOptions}
          />
          <Select
            allowClear
            value={result}
            onChange={(value?: string) => { setResult(value); setPage(1); }}
            placeholder="Все результаты"
            style={{ width: 180 }}
            options={RESULT_OPTIONS}
          />
          <Input
            allowClear
            prefix={<SearchOutlined />}
            placeholder="Email пользователя"
            value={actorInput}
            onChange={(e) => setActorInput(e.target.value)}
            style={{ width: 240 }}
          />
          <DatePicker.RangePicker
            showTime
            value={range}
            onChange={(values) => {
              setRange(values && values[0] && values[1] ? [values[0], values[1]] : null);
              setPage(1);
            }}
            format="DD.MM.YYYY HH:mm"
            placeholder={['События с', 'по']}
          />
          <Button onClick={resetFilters}>Сбросить</Button>
        </Space>
      </Card>

      <Card style={{ marginTop: 16 }} styles={{ body: { padding: 0 } }}>
        <Table<AuditEventDto>
          rowKey="id"
          dataSource={rows}
          columns={columns}
          loading={loading}
          scroll={{ x: 760 }}
          locale={{
            emptyText: (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="Событий по заданным условиям нет"
              />
            ),
          }}
          expandable={{
            rowExpandable: (row) => Boolean(row.details || row.actorIp || row.targetId),
            expandedRowRender: (row) => (
              <Space direction="vertical" size={4} style={{ fontSize: 13 }}>
                <div><strong>IP:</strong> {row.actorIp || '—'}</div>
                <div><strong>Объект:</strong> {row.targetType || '—'} {row.targetId ? `#${row.targetId}` : ''}</div>
                <div><strong>Детали:</strong> {row.details || '—'}</div>
              </Space>
            ),
          }}
          pagination={{
            current: page,
            pageSize: PAGE_SIZE,
            total,
            showSizeChanger: false,
            showTotal: (count) => `Всего ${count}`,
            onChange: setPage,
          }}
        />
      </Card>

      <Modal
        open={cleanupOpen}
        title="Очистка журнала аудита"
        okText="Удалить"
        okButtonProps={{ danger: true }}
        cancelText="Отмена"
        confirmLoading={cleaning}
        onCancel={() => setCleanupOpen(false)}
        onOk={confirmCleanup}
      >
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Alert
            type="warning"
            showIcon
            message="Удаление необратимо"
            description="Будут удалены все события старше указанного срока. Свежие события останутся."
          />
          <div>
            <div style={{ fontWeight: 600, marginBottom: 8 }}>Хранить события, дней</div>
            <InputNumber
              min={1}
              max={3650}
              value={cleanupDays}
              onChange={(value) => setCleanupDays(value ?? 365)}
              style={{ width: 160 }}
            />
          </div>
        </Space>
      </Modal>
    </>
  );
}
