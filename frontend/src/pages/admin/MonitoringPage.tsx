import { useCallback, useEffect, useState } from 'react';
import {
  Alert, App, Button, Card, Collapse, Descriptions, Empty, Progress, Select, Space, Spin, Table,
  Tag,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  CheckCircleFilled, CloseCircleFilled, DatabaseOutlined, FolderOutlined, HddOutlined,
  KeyOutlined, MailOutlined, ReloadOutlined, SafetyCertificateOutlined, SendOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import { api } from '../../lib/api';
import { errorText } from '../../lib/admin';
import { formatDateTime } from '../../lib/store';
import type {
  MonitorCheckDto, MonitorHistoryDto, MonitorLogsDto, SystemInfoDto,
} from '../../lib/types';

function componentIcon(component: string) {
  const key = component.toLowerCase();
  if (key.includes('db') || key.includes('database') || key.includes('postgres')) return <DatabaseOutlined />;
  if (key.includes('mail') || key.includes('smtp')) return <MailOutlined />;
  if (key.includes('disk')) return <HddOutlined />;
  if (key.includes('storage') || key.includes('folder')) return <FolderOutlined />;
  if (key.includes('key')) return <KeyOutlined />;
  if (key.includes('mem') || key.includes('cpu')) return <ThunderboltOutlined />;
  return <SafetyCertificateOutlined />;
}

const STATUS_TAG: Record<string, { color: string; label: string }> = {
  OK: { color: 'success', label: 'OK' },
  FAIL: { color: 'error', label: 'Сбой' },
  WARN: { color: 'warning', label: 'Внимание' },
};

export default function MonitoringPage() {
  const { message } = App.useApp();

  const [checks, setChecks] = useState<MonitorCheckDto[]>([]);
  const [system, setSystem] = useState<SystemInfoDto | null>(null);
  const [history, setHistory] = useState<MonitorHistoryDto[]>([]);
  const [logs, setLogs] = useState<string[]>([]);
  const [logLines, setLogLines] = useState(200);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [historyLoading, setHistoryLoading] = useState(true);
  const [historyError, setHistoryError] = useState<string>();
  const [logsLoading, setLogsLoading] = useState(true);
  const [logsError, setLogsError] = useState<string>();
  const [alertSending, setAlertSending] = useState(false);

  const loadChecks = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      const [checkList, systemInfo] = await Promise.all([
        api<MonitorCheckDto[]>('/admin/monitor/checks'),
        api<SystemInfoDto>('/admin/system'),
      ]);
      setChecks(checkList);
      setSystem(systemInfo);
    } catch (e) {
      setError(errorText(e, 'Не удалось получить состояние компонентов'));
    } finally {
      setLoading(false);
    }
  }, []);

  const loadHistory = useCallback(async () => {
    setHistoryLoading(true);
    setHistoryError(undefined);
    try {
      setHistory(await api<MonitorHistoryDto[]>('/admin/monitor/history?limit=100'));
    } catch (e) {
      setHistoryError(errorText(e, 'Не удалось загрузить историю проверок'));
    } finally {
      setHistoryLoading(false);
    }
  }, []);

  const loadLogs = useCallback(async (lines: number) => {
    setLogsLoading(true);
    setLogsError(undefined);
    try {
      const res = await api<MonitorLogsDto>(`/admin/monitor/logs?lines=${lines}`);
      setLogs(res.lines ?? []);
    } catch (e) {
      setLogsError(errorText(e, 'Не удалось прочитать журнал'));
    } finally {
      setLogsLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadChecks();
    void loadHistory();
    void loadLogs(200);
  }, [loadChecks, loadHistory, loadLogs]);

  const reloadAll = () => {
    void loadChecks();
    void loadHistory();
    void loadLogs(logLines);
  };

  const sendTestAlert = async () => {
    setAlertSending(true);
    try {
      const res = await api<{ message: string }>('/admin/monitor/test-alert', { method: 'POST' });
      message.success(res.message || 'Тестовое оповещение отправлено');
    } catch (e) {
      message.error(errorText(e, 'Не удалось отправить тестовое оповещение'));
    } finally {
      setAlertSending(false);
    }
  };

  const healthy = checks.filter((c) => c.healthy).length;
  const attention = checks.length - healthy;

  const historyColumns: ColumnsType<MonitorHistoryDto> = [
    {
      title: 'Время',
      dataIndex: 'checkedAt',
      width: 170,
      render: (checkedAt: string) => formatDateTime(checkedAt),
    },
    { title: 'Компонент', dataIndex: 'component' },
    {
      title: 'Статус',
      dataIndex: 'status',
      width: 130,
      render: (status: string) => {
        const cfg = STATUS_TAG[status?.toUpperCase()] ?? { color: 'default', label: status };
        return <Tag color={cfg.color} bordered={false} style={{ borderRadius: 999 }}>{cfg.label}</Tag>;
      },
    },
    {
      title: 'Причина',
      dataIndex: 'reason',
      responsive: ['md'],
      render: (reason?: string) => reason || '—',
    },
    {
      title: 'Алерт',
      dataIndex: 'alertSent',
      width: 110,
      responsive: ['lg'],
      render: (sent: boolean) => sent
        ? <Tag color="success" bordered={false}>Отправлен</Tag>
        : <Tag bordered={false}>Нет</Tag>,
    },
  ];

  const memory = system?.memory;
  const disk = system?.disk;
  const cpu = system?.cpu;
  const database = system?.database;

  return (
    <>
      <PageHeader
        title="Мониторинг"
        subtitle="Состояние компонентов, ресурсы сервера, история проверок и журнал"
        extra={(
          <Space wrap>
            <Button icon={<ReloadOutlined />} loading={loading} onClick={reloadAll}>
              Проверить сейчас
            </Button>
            <Button
              type="primary"
              icon={<SendOutlined />}
              loading={alertSending}
              onClick={() => void sendTestAlert()}
            >
              Тестовое оповещение
            </Button>
          </Space>
        )}
      />

      {error && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="Не удалось получить состояние компонентов"
          description={error}
          action={<Button size="small" onClick={() => void loadChecks()}>Повторить</Button>}
        />
      )}

      <div className="stat-grid" style={{ marginBottom: 16 }}>
        <Card>
          <div className="stat-label">Компоненты</div>
          <div className="stat-value">{checks.length}</div>
        </Card>
        <Card>
          <div className="stat-label">В норме</div>
          <div className="stat-value" style={{ color: '#10b981' }}>{healthy}</div>
        </Card>
        <Card>
          <div className="stat-label">Требуют внимания</div>
          <div className="stat-value" style={{ color: attention > 0 ? '#f59e0b' : undefined }}>{attention}</div>
        </Card>
        <Card>
          <div className="stat-label">Аптайм</div>
          <div className="stat-value" style={{ fontSize: 20 }}>{system?.uptimePretty ?? '—'}</div>
        </Card>
      </div>

      <div className="grid-2" style={{ alignItems: 'start' }}>
        <Card title="Проверки компонентов" styles={{ body: { padding: loading || checks.length === 0 ? 24 : '8px 24px' } }}>
          {loading ? (
            <div style={{ textAlign: 'center', padding: 32 }}><Spin size="large" /></div>
          ) : checks.length === 0 ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Проверки не вернули результатов" />
          ) : (
            checks.map((check) => (
              <div
                key={check.component}
                style={{
                  display: 'flex', alignItems: 'center', gap: 12, padding: '12px 0',
                  borderBottom: '1px solid var(--border)',
                }}
              >
                <div style={{
                  width: 38, height: 38, borderRadius: 11, background: 'var(--surface-2)',
                  display: 'grid', placeItems: 'center',
                }}>
                  {componentIcon(check.component)}
                </div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontWeight: 700 }}>{check.title || check.component}</div>
                  <div style={{ color: 'var(--muted)', fontSize: 12 }}>
                    {check.message || 'Без замечаний'}
                    {typeof check.latencyMs === 'number' ? ` · ${check.latencyMs} мс` : ''}
                  </div>
                </div>
                {check.healthy ? (
                  <Tag icon={<CheckCircleFilled />} color="success" style={{ borderRadius: 999 }}>OK</Tag>
                ) : (
                  <Tag icon={<CloseCircleFilled />} color="error" style={{ borderRadius: 999 }}>Внимание</Tag>
                )}
              </div>
            ))
          )}
        </Card>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <Card title="Ресурсы сервера">
            {!system ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Метрики недоступны" />
            ) : (
              <Space direction="vertical" size={18} style={{ width: '100%' }}>
                <div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span>RAM</span>
                    <span>
                      {memory ? `${(memory.used / 1024).toFixed(1)} / ${(memory.total / 1024).toFixed(1)} ГБ` : '—'}
                    </span>
                  </div>
                  <Progress percent={memory?.percent ?? 0} showInfo={false} strokeColor="#4f46e5" />
                </div>
                <div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span>Диск</span>
                    <span>{disk ? `${disk.used} / ${disk.total} ГБ` : '—'}</span>
                  </div>
                  <Progress percent={disk?.percent ?? 0} showInfo={false} strokeColor="#10b981" />
                </div>
                <div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span>CPU</span>
                    <span>{cpu ? `${cpu.percent}% · ${cpu.cores} ядер` : '—'}</span>
                  </div>
                  <Progress percent={cpu?.percent ?? 0} showInfo={false} strokeColor="#8b5cf6" />
                </div>
                {cpu?.loadAvg && (
                  <div style={{ color: 'var(--muted)', fontSize: 12 }}>
                    Load average: {cpu.loadAvg.map((v) => v.toFixed(2)).join(' / ')}
                  </div>
                )}
              </Space>
            )}
          </Card>

          <Card title="База данных и хранилище">
            {!database ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Сведения недоступны" />
            ) : (
              <Space direction="vertical" size={12} style={{ width: '100%' }}>
                <Descriptions
                  column={1}
                  size="small"
                  items={[
                    { key: 'db', label: 'Размер БД', children: database.sizePretty ?? '—' },
                    { key: 'conn', label: 'Соединений', children: database.connections ?? '—' },
                    { key: 'users', label: 'Пользователей', children: database.usersCount ?? '—' },
                    { key: 'docs', label: 'Документов', children: database.docsCount ?? '—' },
                    { key: 'sigs', label: 'Подписей', children: database.sigsCount ?? '—' },
                    { key: 'storage', label: 'Хранилище', children: system?.storage ? `${system.storage.pretty} · ${system.storage.files} файлов` : '—' },
                    { key: 'started', label: 'Запущен', children: formatDateTime(system?.startedAt) },
                  ]}
                />
                {(system?.diagnostics?.length ?? 0) > 0 && (
                  <Collapse
                    size="small"
                    items={[{
                      key: 'diagnostics',
                      label: 'Диагностика БД',
                      children: (
                        <Space direction="vertical" size={6} style={{ width: '100%' }}>
                          {(system?.diagnostics ?? []).map((diag) => (
                            <div
                              key={diag.name}
                              style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}
                            >
                              <span style={{ fontSize: 13 }}>{diag.name}</span>
                              <Tag
                                color={diag.ok ? 'success' : 'error'}
                                bordered={false}
                                style={{ borderRadius: 999 }}
                              >
                                {diag.value}
                              </Tag>
                            </div>
                          ))}
                        </Space>
                      ),
                    }]}
                  />
                )}
              </Space>
            )}
          </Card>
        </div>
      </div>

      <Card
        title="История проверок"
        style={{ marginTop: 16 }}
        styles={{ body: { padding: history.length === 0 ? 24 : 0 } }}
        extra={<Button size="small" icon={<ReloadOutlined />} loading={historyLoading} onClick={() => void loadHistory()}>Обновить</Button>}
      >
        {historyError && (
          <Alert type="error" showIcon message={historyError} style={{ margin: 16 }} />
        )}
        {history.length === 0 && !historyLoading ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description="История пуста — проверки ещё не фиксировали переходы статусов"
          />
        ) : (
          <Table<MonitorHistoryDto>
            rowKey="id"
            size="small"
            dataSource={history}
            columns={historyColumns}
            loading={historyLoading}
            pagination={{ pageSize: 10, showSizeChanger: false }}
          />
        )}
      </Card>

      <Card
        title="Журнал приложения"
        style={{ marginTop: 16 }}
        extra={(
          <Space>
            <Select
              value={logLines}
              onChange={(value: number) => { setLogLines(value); void loadLogs(value); }}
              style={{ width: 140 }}
              options={[
                { value: 100, label: '100 строк' },
                { value: 200, label: '200 строк' },
                { value: 500, label: '500 строк' },
              ]}
            />
            <Button size="small" icon={<ReloadOutlined />} loading={logsLoading} onClick={() => void loadLogs(logLines)}>
              Обновить
            </Button>
          </Space>
        )}
      >
        {logsError ? (
          <Alert type="error" showIcon message={logsError} />
        ) : logs.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={logsLoading ? 'Загрузка…' : 'Журнал пуст'} />
        ) : (
          <pre style={{
            margin: 0, padding: 16, maxHeight: 360, overflow: 'auto', borderRadius: 12,
            background: 'var(--surface-2)', border: '1px solid var(--border)',
            fontSize: 12, lineHeight: 1.5, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
          }}>
            {logs.join('\n')}
          </pre>
        )}
      </Card>
    </>
  );
}
