import { useCallback, useEffect, useState } from 'react';
import {
  Alert, App, Button, Card, Empty, Form, Input, InputNumber, Modal, Select, Space, Switch, Table, Tag,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  DeleteOutlined, EditOutlined, EyeOutlined, FileDoneOutlined, PlusOutlined, ReloadOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import { api, fetchBlob } from '../../lib/api';
import { errorText } from '../../lib/admin';
import { formatDateTime, useApp } from '../../lib/store';
import type { TemplateDto } from '../../lib/types';

interface TemplateFormValues {
  name: string;
  description?: string;
  kind: 'DOCUMENT' | 'CONSENT';
  sortOrder: number;
  active: boolean;
  htmlContent?: string;
  fieldsJson?: string;
}

export default function AdminTemplatesPage() {
  const { message, modal } = App.useApp();
  const { refreshTemplates } = useApp();

  const [rows, setRows] = useState<TemplateDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<TemplateDto | null>(null);
  const [saving, setSaving] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [previewingId, setPreviewingId] = useState<number | null>(null);
  const [validating, setValidating] = useState(false);
  const [form] = Form.useForm<TemplateFormValues>();

  const load = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      setRows(await api<TemplateDto[]>('/admin/templates'));
    } catch (e) {
      setError(errorText(e, 'Не удалось загрузить шаблоны'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const syncStore = () => refreshTemplates().catch(() => undefined);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({
      kind: 'DOCUMENT', sortOrder: 0, active: true, htmlContent: '', fieldsJson: '',
    });
    setFormOpen(true);
  };

  const openEdit = (row: TemplateDto) => {
    setEditing(row);
    form.setFieldsValue({
      name: row.name,
      description: row.description ?? '',
      kind: row.kind,
      sortOrder: row.sortOrder,
      active: row.active,
      htmlContent: row.htmlContent ?? '',
      fieldsJson: row.fieldsJson ?? '',
    });
    setFormOpen(true);
  };

  const save = async () => {
    let values: TemplateFormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    const body = {
      name: values.name.trim(),
      description: values.description?.trim() ?? '',
      kind: values.kind,
      sortOrder: values.sortOrder ?? 0,
      active: values.active,
      htmlContent: values.htmlContent ?? '',
      fieldsJson: values.fieldsJson ?? '',
    };
    setSaving(true);
    try {
      if (editing) {
        await api<TemplateDto>(`/admin/templates/${editing.id}`, { method: 'PUT', body });
        message.success('Шаблон обновлён');
      } else {
        await api<TemplateDto>('/admin/templates', { method: 'POST', body });
        message.success('Шаблон создан');
      }
      setFormOpen(false);
      await load();
      await syncStore();
    } catch (e) {
      message.error(errorText(e, 'Не удалось сохранить шаблон'));
    } finally {
      setSaving(false);
    }
  };

  const toggle = async (row: TemplateDto) => {
    setBusyId(row.id);
    try {
      const updated = await api<TemplateDto>(`/admin/templates/${row.id}/toggle`, { method: 'POST' });
      setRows((prev) => prev.map((item) => (item.id === updated.id ? updated : item)));
      message.success(updated.active ? 'Шаблон включён' : 'Шаблон отключён');
      await syncStore();
    } catch (e) {
      message.error(errorText(e, 'Не удалось изменить доступность шаблона'));
    } finally {
      setBusyId(null);
    }
  };

  const preview = async (row: TemplateDto) => {
    setPreviewingId(row.id);
    const win = window.open('', '_blank');
    try {
      const { blob } = await fetchBlob(`/admin/templates/${row.id}/preview`, {
        method: 'POST',
        body: { values: {} },
      });
      const url = URL.createObjectURL(blob);
      if (win) win.location.href = url;
      else message.info('Разрешите всплывающие окна, чтобы открыть превью');
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) {
      win?.close();
      message.error(errorText(e, 'Не удалось построить превью шаблона'));
    } finally {
      setPreviewingId(null);
    }
  };

  const validateFields = async () => {
    setValidating(true);
    try {
      const fieldsJson = form.getFieldValue('fieldsJson') as string | undefined;
      const res = await api<{ valid: boolean; fields: unknown[] }>(
        '/admin/templates/validate-fields',
        { method: 'POST', body: { fieldsJson: fieldsJson ?? '' } },
      );
      message.success(`JSON полей корректен · полей: ${res.fields?.length ?? 0}`);
    } catch (e) {
      message.error(errorText(e, 'Некорректное описание полей'));
    } finally {
      setValidating(false);
    }
  };

  const remove = (row: TemplateDto) => {
    modal.confirm({
      title: 'Удалить шаблон?',
      content: `Шаблон «${row.name}» будет удалён вместе с HTML и описанием полей. `
        + 'Сформированные ранее документы останутся.',
      okText: 'Удалить',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await api(`/admin/templates/${row.id}`, { method: 'DELETE' });
          message.success('Шаблон удалён');
          await load();
          await syncStore();
        } catch (e) {
          message.error(errorText(e, 'Не удалось удалить шаблон'));
        }
      },
    });
  };

  const columns: ColumnsType<TemplateDto> = [
    {
      title: 'Шаблон',
      dataIndex: 'name',
      render: (name: string, row) => (
        <div style={{ minWidth: 0 }}>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <FileDoneOutlined style={{ color: '#4f46e5' }} />
            <span style={{ fontWeight: 600 }}>{name}</span>
          </div>
          <div style={{ color: 'var(--muted)', fontSize: 12 }}>
            {row.description || 'Без описания'}
          </div>
        </div>
      ),
    },
    {
      title: 'Назначение',
      dataIndex: 'kind',
      width: 140,
      render: (kind: TemplateDto['kind']) => (
        <Tag color={kind === 'CONSENT' ? 'purple' : 'blue'} bordered={false} style={{ borderRadius: 999 }}>
          {kind === 'CONSENT' ? 'Согласие' : 'Документ'}
        </Tag>
      ),
    },
    { title: 'Полей', dataIndex: 'fieldsCount', width: 90 },
    { title: 'Порядок', dataIndex: 'sortOrder', width: 100, responsive: ['lg'] },
    {
      title: 'Доступен',
      dataIndex: 'active',
      width: 120,
      render: (active: boolean, row) => (
        <Button
          size="small"
          type={active ? 'primary' : 'default'}
          ghost={active}
          loading={busyId === row.id}
          onClick={() => void toggle(row)}
        >
          {active ? 'Включён' : 'Отключён'}
        </Button>
      ),
    },
    {
      title: 'Обновлён',
      dataIndex: 'updatedAt',
      width: 160,
      responsive: ['xl'],
      render: (updatedAt: string | undefined, row) => formatDateTime(updatedAt ?? row.createdAt),
    },
    {
      title: '',
      key: 'actions',
      width: 190,
      render: (_: unknown, row) => (
        <Space size={4} wrap>
          <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(row)}>
            Изменить
          </Button>
          <Button
            size="small"
            icon={<EyeOutlined />}
            loading={previewingId === row.id}
            onClick={() => void preview(row)}
          >
            Превью
          </Button>
          <Button
            size="small"
            type="text"
            danger
            icon={<DeleteOutlined />}
            aria-label={`Удалить шаблон ${row.name}`}
            onClick={() => remove(row)}
          />
        </Space>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Шаблоны"
        subtitle="Администрирование шаблонов документов и согласий"
        extra={(
          <Space wrap>
            <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>
              Обновить
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              Новый шаблон
            </Button>
          </Space>
        )}
      />

      {error && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="Не удалось загрузить шаблоны"
          description={error}
          action={<Button size="small" onClick={() => void load()}>Повторить</Button>}
        />
      )}

      <Card styles={{ body: { padding: rows.length === 0 ? 24 : 0 } }}>
        {rows.length === 0 && !loading ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description="Шаблонов пока нет — создайте первый шаблон документа или согласия"
          >
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              Создать шаблон
            </Button>
          </Empty>
        ) : (
          <Table<TemplateDto>
            rowKey="id"
            dataSource={rows}
            columns={columns}
            loading={loading}
            scroll={{ x: 860 }}
            pagination={{ pageSize: 10, showSizeChanger: false }}
          />
        )}
      </Card>

      <Modal
        open={formOpen}
        title={editing ? `Шаблон: ${editing.name}` : 'Новый шаблон'}
        okText={editing ? 'Сохранить' : 'Создать'}
        cancelText="Отмена"
        confirmLoading={saving}
        onCancel={() => setFormOpen(false)}
        onOk={() => void save()}
        width={780}
        destroyOnHidden
      >
        <Form form={form} layout="vertical">
          <div className="grid-2" style={{ gridTemplateColumns: '1fr 1fr' }}>
            <Form.Item
              name="name"
              label="Название"
              rules={[{ required: true, message: 'Укажите название шаблона' }]}
            >
              <Input placeholder="Договор оказания услуг" />
            </Form.Item>
            <Form.Item name="kind" label="Назначение">
              <Select
                options={[
                  { value: 'DOCUMENT', label: 'Документ' },
                  { value: 'CONSENT', label: 'Согласие на обработку ПДн' },
                ]}
              />
            </Form.Item>
          </div>
          <Form.Item name="description" label="Описание для клиента">
            <Input placeholder="Что формируется по этому шаблону" />
          </Form.Item>
          <div className="grid-2" style={{ gridTemplateColumns: '1fr 1fr' }}>
            <Form.Item name="sortOrder" label="Порядок сортировки">
              <InputNumber min={0} max={9999} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="active" label="Доступен клиентам" valuePropName="checked">
              <Switch />
            </Form.Item>
          </div>
          <Form.Item
            name="htmlContent"
            label="HTML-шаблон"
            extra="Плейсхолдеры вида {{fio}} подставляются из полей ниже."
          >
            <Input.TextArea
              rows={8}
              style={{ fontFamily: 'ui-monospace, SFMono-Regular, monospace', fontSize: 12 }}
            />
          </Form.Item>
          <Form.Item label="Описание полей (JSON)">
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              <Form.Item name="fieldsJson" noStyle>
                <Input.TextArea
                  rows={5}
                  placeholder={'[{"key":"fio","label":"ФИО","type":"text","required":true}]'}
                  style={{ fontFamily: 'ui-monospace, SFMono-Regular, monospace', fontSize: 12 }}
                />
              </Form.Item>
              <Button size="small" loading={validating} onClick={() => void validateFields()}>
                Проверить JSON полей
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
