import { useCallback, useEffect, useState } from 'react';
import {
  Alert, App, Button, Card, Empty, Space, Table, Tag,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  CloudDownloadOutlined, DatabaseOutlined, DeleteOutlined, PlusOutlined, ReloadOutlined,
  UndoOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import { api, download } from '../../lib/api';
import { errorText } from '../../lib/admin';
import { formatDateTime } from '../../lib/store';
import type { BackupDto } from '../../lib/types';

interface CreateBackupResponse { name: string; created: boolean; message: string }
interface RestoreBackupResponse { restored: boolean; safetyBackup: string; message: string }

export default function AdminBackupsPage() {
  const { message, modal } = App.useApp();

  const [rows, setRows] = useState<BackupDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [creating, setCreating] = useState(false);
  const [busyName, setBusyName] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      setRows(await api<BackupDto[]>('/admin/backups'));
    } catch (e) {
      setError(errorText(e, 'Не удалось загрузить список резервных копий'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const createBackup = async () => {
    setCreating(true);
    try {
      const res = await api<CreateBackupResponse>('/admin/backups', { method: 'POST' });
      message.success(res.message || `Резервная копия создана: ${res.name}`);
      await load();
    } catch (e) {
      message.error(errorText(e, 'Не удалось создать резервную копию'));
    } finally {
      setCreating(false);
    }
  };

  const downloadBackup = async (row: BackupDto) => {
    setBusyName(row.name);
    try {
      await download(`/admin/backups/${encodeURIComponent(row.name)}/download`, row.name);
    } catch (e) {
      message.error(errorText(e, 'Не удалось скачать резервную копию'));
    } finally {
      setBusyName(null);
    }
  };

  const restoreBackup = (row: BackupDto) => {
    modal.confirm({
      title: 'Восстановить БД из резервной копии?',
      content: `Текущее состояние базы данных будет заменено содержимым «${row.name}». `
        + 'Перед восстановлением автоматически создаётся safety-бэкап. '
        + 'После восстановления приложение нужно перезапустить.',
      okText: 'Восстановить',
      okButtonProps: { danger: true },
      onOk: async () => {
        setBusyName(row.name);
        try {
          const res = await api<RestoreBackupResponse>(
            `/admin/backups/${encodeURIComponent(row.name)}/restore`, { method: 'POST' },
          );
          message.success(res.message || 'База данных восстановлена');
          await load();
        } catch (e) {
          message.error(errorText(e, 'Не удалось восстановить базу данных'));
        } finally {
          setBusyName(null);
        }
      },
    });
  };

  const deleteBackup = (row: BackupDto) => {
    modal.confirm({
      title: 'Удалить резервную копию?',
      content: `Файл «${row.name}» будет удалён без возможности восстановления.`,
      okText: 'Удалить',
      okButtonProps: { danger: true },
      onOk: async () => {
        setBusyName(row.name);
        try {
          await api(`/admin/backups/${encodeURIComponent(row.name)}`, { method: 'DELETE' });
          message.success('Резервная копия удалена');
          await load();
        } catch (e) {
          message.error(errorText(e, 'Не удалось удалить резервную копию'));
        } finally {
          setBusyName(null);
        }
      },
    });
  };

  const columns: ColumnsType<BackupDto> = [
    {
      title: 'Файл',
      dataIndex: 'name',
      render: (name: string) => (
        <Space>
          <DatabaseOutlined style={{ color: '#4f46e5' }} />
          <code style={{ fontSize: 12 }}>{name}</code>
        </Space>
      ),
    },
    { title: 'Размер', dataIndex: 'sizePretty', width: 120 },
    {
      title: 'Создана',
      dataIndex: 'createdAt',
      width: 180,
      render: (createdAt: string) => formatDateTime(createdAt),
    },
    {
      title: 'Шифрование',
      dataIndex: 'encrypted',
      width: 140,
      responsive: ['lg'],
      render: (encrypted: boolean) => encrypted
        ? <Tag color="success" bordered={false}>Зашифрована</Tag>
        : <Tag bordered={false}>Без шифрования</Tag>,
    },
    {
      title: '',
      key: 'actions',
      width: 250,
      render: (_: unknown, row) => (
        <Space size={4} wrap>
          <Button
            size="small"
            icon={<CloudDownloadOutlined />}
            loading={busyName === row.name}
            onClick={() => void downloadBackup(row)}
          >
            Скачать
          </Button>
          <Button
            size="small"
            icon={<UndoOutlined />}
            loading={busyName === row.name}
            onClick={() => restoreBackup(row)}
          >
            Восстановить
          </Button>
          <Button
            size="small"
            type="text"
            danger
            icon={<DeleteOutlined />}
            aria-label={`Удалить ${row.name}`}
            onClick={() => deleteBackup(row)}
          />
        </Space>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Резервные копии"
        subtitle="Резервное копирование базы данных: создание, скачивание и восстановление"
        extra={(
          <Space wrap>
            <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>
              Обновить
            </Button>
            <Button type="primary" icon={<PlusOutlined />} loading={creating} onClick={() => void createBackup()}>
              Создать копию
            </Button>
          </Space>
        )}
      />

      {error && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="Не удалось загрузить резервные копии"
          description={error}
          action={<Button size="small" onClick={() => void load()}>Повторить</Button>}
        />
      )}

      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 16 }}
        message="Восстановление заменяет текущую БД"
        description={'Перед восстановлением создаётся safety-бэкап, а после — требуется перезапуск приложения. '
          + 'Выполняйте восстановление только при уверенности в выбранной копии.'}
      />

      <Card
        title={<Space><DatabaseOutlined /> Резервные копии БД</Space>}
        styles={{ body: { padding: rows.length === 0 ? 24 : 0 } }}
      >
        {rows.length === 0 && !loading ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description="Резервных копий пока нет — создайте первую"
          >
            <Button type="primary" icon={<PlusOutlined />} loading={creating} onClick={() => void createBackup()}>
              Создать копию
            </Button>
          </Empty>
        ) : (
          <Table<BackupDto>
            rowKey="name"
            dataSource={rows}
            columns={columns}
            loading={loading}
            scroll={{ x: 780 }}
            pagination={{ pageSize: 10, showSizeChanger: false }}
          />
        )}
      </Card>
    </>
  );
}
