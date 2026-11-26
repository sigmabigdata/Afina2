import { useCallback, useEffect, useState } from 'react';
import {
  Alert, App, Button, Card, Empty, Modal, Space, Table, Tag, Upload,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { UploadFile } from 'antd/es/upload/interface';
import {
  DeleteOutlined, DownloadOutlined, PlusOutlined, ReloadOutlined, SolutionOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import { api, download } from '../../lib/api';
import { errorText } from '../../lib/admin';
import { formatDate, formatDateTime } from '../../lib/store';
import type { PoaDto } from '../../lib/types';

export default function AdminPoaPage() {
  const { message, modal } = App.useApp();

  const [rows, setRows] = useState<PoaDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [uploadOpen, setUploadOpen] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      setRows(await api<PoaDto[]>('/admin/poa'));
    } catch (e) {
      setError(errorText(e, 'Не удалось загрузить доверенности'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const submitUpload = async () => {
    if (!file) {
      message.warning('Выберите XML-файл доверенности');
      return;
    }
    setUploading(true);
    try {
      const form = new FormData();
      form.append('file', file);
      const created = await api<PoaDto>('/admin/poa', { method: 'POST', form });
      message.success(`Доверенность загружена: ${created.fio || 'без ФИО'}`);
      setUploadOpen(false);
      setFile(null);
      await load();
    } catch (e) {
      message.error(errorText(e, 'Не удалось загрузить доверенность'));
    } finally {
      setUploading(false);
    }
  };

  const toggleActive = async (row: PoaDto) => {
    setBusyId(row.id);
    try {
      const updated = await api<PoaDto>(`/admin/poa/${row.id}/active`, {
        method: 'PATCH',
        body: { active: !row.active },
      });
      setRows((prev) => prev.map((item) => (item.id === updated.id ? updated : item)));
      message.success(updated.active ? 'Доверенность включена' : 'Доверенность отключена');
    } catch (e) {
      message.error(errorText(e, 'Не удалось изменить статус доверенности'));
    } finally {
      setBusyId(null);
    }
  };

  const downloadXml = async (row: PoaDto) => {
    try {
      await download(`/admin/poa/${row.id}/xml`, `poa_${row.id}.xml`);
    } catch (e) {
      message.error(errorText(e, 'Не удалось скачать XML доверенности'));
    }
  };

  const remove = (row: PoaDto) => {
    modal.confirm({
      title: 'Удалить доверенность?',
      content: `Доверенность ${row.docNumber ? `№${row.docNumber} ` : ''}на ${row.fio || 'представителя'} `
        + 'будет удалена вместе с исходным XML.',
      okText: 'Удалить',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await api(`/admin/poa/${row.id}`, { method: 'DELETE' });
          message.success('Доверенность удалена');
          await load();
        } catch (e) {
          message.error(errorText(e, 'Не удалось удалить доверенность'));
        }
      },
    });
  };

  const fileList: UploadFile[] = file
    ? [{ uid: 'poa-file', name: file.name, status: 'done' }]
    : [];

  const columns: ColumnsType<PoaDto> = [
    {
      title: 'Представитель',
      dataIndex: 'fio',
      render: (fio: string | undefined, row) => (
        <div>
          <div style={{ fontWeight: 600 }}>{fio || '—'}</div>
          <div style={{ color: 'var(--muted)', fontSize: 12 }}>
            {row.orgName || 'Организация не указана'}
            {row.snils ? ` · СНИЛС ${row.snils}` : ''}
          </div>
        </div>
      ),
    },
    {
      title: 'Доверенность',
      dataIndex: 'docNumber',
      render: (docNumber: string | undefined, row) => (
        <div>
          <div>{docNumber ? `№${docNumber}` : '—'}</div>
          <div style={{ color: 'var(--muted)', fontSize: 12 }}>
            {row.docDate ? `от ${formatDate(row.docDate)}` : 'дата не указана'}
          </div>
        </div>
      ),
    },
    {
      title: 'Срок действия',
      dataIndex: 'validUntil',
      width: 200,
      render: (_: unknown, row) => (
        <div>
          <div>{formatDate(row.validFrom)} — {formatDate(row.validUntil)}</div>
          <div style={{ color: 'var(--muted)', fontSize: 12 }}>{row.notaryName || 'Без нотариуса'}</div>
        </div>
      ),
    },
    {
      title: 'Статус',
      dataIndex: 'active',
      width: 190,
      render: (_: unknown, row) => (
        <Space direction="vertical" size={4}>
          <Tag color={row.active ? 'success' : 'default'} bordered={false} style={{ borderRadius: 999 }}>
            {row.active ? 'Включена' : 'Отключена'}
          </Tag>
          <Tag color={row.effective ? 'processing' : 'warning'} bordered={false} style={{ borderRadius: 999 }}>
            {row.effective ? 'Действует' : 'Не действует'}
          </Tag>
        </Space>
      ),
    },
    {
      title: 'Загружена',
      dataIndex: 'createdAt',
      width: 170,
      responsive: ['xl'],
      render: (createdAt: string, row) => (
        <div>
          <div>{formatDateTime(createdAt)}</div>
          <div style={{ color: 'var(--muted)', fontSize: 12 }}>{row.createdBy || '—'}</div>
        </div>
      ),
    },
    {
      title: '',
      key: 'actions',
      width: 200,
      render: (_: unknown, row) => (
        <Space size={4} wrap>
          <Button
            size="small"
            loading={busyId === row.id}
            onClick={() => void toggleActive(row)}
          >
            {row.active ? 'Отключить' : 'Включить'}
          </Button>
          <Button
            size="small"
            type="text"
            icon={<DownloadOutlined />}
            aria-label={`Скачать XML доверенности ${row.id}`}
            onClick={() => void downloadXml(row)}
          />
          <Button
            size="small"
            type="text"
            danger
            icon={<DeleteOutlined />}
            aria-label={`Удалить доверенность ${row.id}`}
            onClick={() => remove(row)}
          />
        </Space>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Доверенности"
        subtitle="Машиночитаемые доверенности (МЧД): загрузка XML, срок действия и включение"
        extra={(
          <Space wrap>
            <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>
              Обновить
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setUploadOpen(true)}>
              Загрузить доверенность
            </Button>
          </Space>
        )}
      />

      {error && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="Не удалось загрузить доверенности"
          description={error}
          action={<Button size="small" onClick={() => void load()}>Повторить</Button>}
        />
      )}

      <Card
        title={<Space><SolutionOutlined /> Доверенности организации</Space>}
        styles={{ body: { padding: rows.length === 0 ? 24 : 0 } }}
      >
        {rows.length === 0 && !loading ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description="Доверенности не загружены — добавьте XML МЧД"
          >
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setUploadOpen(true)}>
              Загрузить доверенность
            </Button>
          </Empty>
        ) : (
          <Table<PoaDto>
            rowKey="id"
            dataSource={rows}
            columns={columns}
            loading={loading}
            scroll={{ x: 900 }}
            pagination={{ pageSize: 10, showSizeChanger: false }}
          />
        )}
      </Card>

      <Modal
        open={uploadOpen}
        title="Загрузка доверенности"
        okText="Загрузить"
        cancelText="Отмена"
        confirmLoading={uploading}
        okButtonProps={{ disabled: !file }}
        onCancel={() => { setUploadOpen(false); setFile(null); }}
        onOk={() => void submitUpload()}
        destroyOnHidden
      >
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Alert
            type="info"
            showIcon
            message="Формат файла — XML МЧД"
            description={'Из файла извлекаются ФИО представителя, реквизиты и срок действия. '
              + 'Повторная загрузка того же файла будет отклонена.'}
          />
          <Upload.Dragger
            accept=".xml"
            maxCount={1}
            fileList={fileList}
            beforeUpload={(selected) => { setFile(selected); return false; }}
            onRemove={() => { setFile(null); return true; }}
          >
            <p className="ant-upload-drag-icon"><UploadOutlined /></p>
            <p className="ant-upload-text">Выберите XML-файл доверенности</p>
            <p className="ant-upload-hint">Файл будет проверен и разобран сервером</p>
          </Upload.Dragger>
        </Space>
      </Modal>
    </>
  );
}
