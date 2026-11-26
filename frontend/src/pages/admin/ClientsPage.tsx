import { useCallback, useEffect, useState } from 'react';
import {
  Alert, App, Button, Card, Dropdown, Empty, Form, Input, Modal, Select, Space, Switch, Table, Tag,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  DeleteOutlined, DownloadOutlined, LinkOutlined, MoreOutlined, PlusOutlined,
  ReloadOutlined, SearchOutlined, StopOutlined, UnlockOutlined,
} from '@ant-design/icons';
import { Link, useNavigate } from 'react-router-dom';
import PageHeader from '../../components/PageHeader';
import { api, download } from '../../lib/api';
import { ROLE_OPTIONS, errorText, roleLabel } from '../../lib/admin';
import { formatDate, useApp } from '../../lib/store';
import type { PageDto, Role, UserDto } from '../../lib/types';

const PAGE_SIZE = 20;

interface CreateClientValues {
  email: string;
  fullName: string;
  phone?: string;
  snils?: string;
  role: Role;
  enabled: boolean;
}

export default function ClientsPage() {
  const { message, modal } = App.useApp();
  const navigate = useNavigate();
  const { refreshClients } = useApp();

  const [rows, setRows] = useState<UserDto[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState<'all' | 'active' | 'blocked'>('all');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [busyId, setBusyId] = useState<number | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<CreateClientValues>();

  // Поиск с задержкой: не дёргаем API на каждый символ
  useEffect(() => {
    const timer = window.setTimeout(() => {
      setQuery(search.trim());
      setPage(1);
    }, 350);
    return () => window.clearTimeout(timer);
  }, [search]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      const params = new URLSearchParams({ page: String(page - 1), size: String(PAGE_SIZE) });
      if (query) params.set('q', query);
      if (status !== 'all') params.set('status', status);
      const data = await api<PageDto<UserDto>>(`/admin/clients?${params.toString()}`);
      setRows(data.items);
      setTotal(data.total);
    } catch (e) {
      setError(errorText(e, 'Не удалось загрузить список клиентов'));
      setRows([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [page, query, status]);

  useEffect(() => { void load(); }, [load]);

  const reloadAll = async () => {
    await load();
    await refreshClients(query || undefined).catch(() => undefined);
  };

  const submitCreate = async () => {
    let values: CreateClientValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    setSaving(true);
    try {
      await api<UserDto>('/admin/clients', {
        method: 'POST',
        body: {
          email: values.email.trim(),
          fullName: values.fullName.trim(),
          phone: values.phone?.trim() || null,
          snils: values.snils?.trim() || null,
          role: values.role,
          enabled: values.enabled,
        },
      });
      message.success('Клиент создан');
      setCreateOpen(false);
      form.resetFields();
      await reloadAll();
    } catch (e) {
      message.error(errorText(e, 'Не удалось создать клиента'));
    } finally {
      setSaving(false);
    }
  };

  const sendLoginLink = async (row: UserDto) => {
    setBusyId(row.id);
    try {
      const res = await api<{ sent: boolean; email: string }>(
        `/admin/clients/${row.id}/login-link`, { method: 'POST' },
      );
      message.success(`Ссылка для входа отправлена на ${res.email}`);
    } catch (e) {
      message.error(errorText(e, 'Не удалось отправить ссылку для входа'));
    } finally {
      setBusyId(null);
    }
  };

  const exportClient = async (row: UserDto) => {
    setBusyId(row.id);
    try {
      await download(`/admin/clients/${row.id}/export`, `client_${row.id}.zip`);
      message.success('Архив карточки клиента скачан');
    } catch (e) {
      message.error(errorText(e, 'Не удалось выгрузить карточку клиента'));
    } finally {
      setBusyId(null);
    }
  };

  const doToggle = async (row: UserDto) => {
    setBusyId(row.id);
    try {
      const updated = await api<UserDto>(`/admin/clients/${row.id}/toggle`, { method: 'POST' });
      message.success(updated.enabled ? 'Клиент разблокирован' : 'Клиент заблокирован');
      await reloadAll();
    } catch (e) {
      message.error(errorText(e, 'Не удалось изменить статус клиента'));
    } finally {
      setBusyId(null);
    }
  };

  const toggleClient = (row: UserDto) => {
    if (!row.enabled) {
      void doToggle(row);
      return;
    }
    modal.confirm({
      title: 'Заблокировать клиента?',
      content: `${row.fullName || row.email} не сможет войти в кабинет и подписывать документы. `
        + 'Доступ можно вернуть в любой момент.',
      okText: 'Заблокировать',
      okButtonProps: { danger: true },
      onOk: () => doToggle(row),
    });
  };

  const deleteClient = (row: UserDto) => {
    modal.confirm({
      title: 'Удалить клиента?',
      content: `Учётная запись ${row.email} будет удалена без возможности восстановления. `
        + 'Действие попадёт в журнал аудита.',
      okText: 'Удалить',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await api(`/admin/clients/${row.id}`, { method: 'DELETE' });
          message.success('Клиент удалён');
          await reloadAll();
        } catch (e) {
          message.error(errorText(e, 'Не удалось удалить клиента'));
        }
      },
    });
  };

  const onMenuClick = (key: string, row: UserDto) => {
    switch (key) {
      case 'open': navigate(`/admin/clients/${row.id}`); break;
      case 'link': void sendLoginLink(row); break;
      case 'export': void exportClient(row); break;
      case 'toggle': toggleClient(row); break;
      case 'delete': deleteClient(row); break;
      default: break;
    }
  };

  const columns: ColumnsType<UserDto> = [
    {
      title: 'Клиент',
      dataIndex: 'fullName',
      render: (name: string, row) => (
        <div style={{ minWidth: 0 }}>
          <Link to={`/admin/clients/${row.id}`} className="doc-link">{name || row.email}</Link>
          <div style={{ color: 'var(--muted)', fontSize: 12 }}>{row.email}</div>
        </div>
      ),
    },
    {
      title: 'Телефон',
      dataIndex: 'phone',
      responsive: ['lg'],
      render: (phone?: string) => phone || <span style={{ color: 'var(--muted)' }}>—</span>,
    },
    {
      title: 'Роль',
      dataIndex: 'role',
      responsive: ['xl'],
      render: (role: string) => <Tag bordered={false}>{roleLabel(role)}</Tag>,
    },
    {
      title: 'Документы',
      dataIndex: 'documentsTotal',
      render: (totalDocs: number, row) => (
        <span>
          {totalDocs}
          {' · '}
          <span style={{ color: '#10b981' }}>{row.documentsSigned} подп.</span>
        </span>
      ),
    },
    {
      title: 'Статус',
      dataIndex: 'enabled',
      render: (enabled: boolean) => (
        <Tag color={enabled ? 'success' : 'default'} bordered={false} style={{ borderRadius: 999 }}>
          {enabled ? 'Активен' : 'Заблокирован'}
        </Tag>
      ),
    },
    {
      title: 'Создан',
      dataIndex: 'createdAt',
      responsive: ['xl'],
      render: (createdAt: string) => formatDate(createdAt),
    },
    {
      title: '',
      key: 'actions',
      width: 64,
      render: (_: unknown, row) => (
        <Dropdown
          trigger={['click']}
          menu={{
            items: [
              { key: 'open', label: 'Открыть карточку' },
              { key: 'link', label: 'Отправить ссылку для входа', icon: <LinkOutlined /> },
              { key: 'export', label: 'Выгрузить ZIP', icon: <DownloadOutlined /> },
              { type: 'divider' },
              {
                key: 'toggle',
                label: row.enabled ? 'Заблокировать' : 'Разблокировать',
                icon: row.enabled ? <StopOutlined /> : <UnlockOutlined />,
              },
              { key: 'delete', label: 'Удалить', icon: <DeleteOutlined />, danger: true },
            ],
            onClick: ({ key }) => onMenuClick(key, row),
          }}
        >
          <Button
            type="text"
            icon={<MoreOutlined />}
            loading={busyId === row.id}
            aria-label={`Действия: ${row.fullName || row.email}`}
          />
        </Dropdown>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Клиенты"
        subtitle={`${total} ${total === 1 ? 'клиент' : 'клиентов'} · создание, блокировка и выгрузка карточек`}
        extra={(
          <Space wrap>
            <Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>
              Обновить
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
              Добавить клиента
            </Button>
          </Space>
        )}
      />

      {error && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="Не удалось загрузить клиентов"
          description={error}
          action={<Button size="small" onClick={() => void load()}>Повторить</Button>}
        />
      )}

      <Card styles={{ body: { paddingBottom: 8 } }}>
        <Space wrap style={{ width: '100%', justifyContent: 'space-between' }}>
          <Space wrap>
            <Input
              allowClear
              prefix={<SearchOutlined />}
              placeholder="ФИО, email или телефон"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              style={{ width: 300 }}
            />
            <Select
              value={status}
              onChange={(value) => { setStatus(value); setPage(1); }}
              style={{ width: 190 }}
              options={[
                { value: 'all', label: 'Все клиенты' },
                { value: 'active', label: 'Активные' },
                { value: 'blocked', label: 'Заблокированные' },
              ]}
            />
          </Space>
          <span style={{ color: 'var(--muted)', fontSize: 12 }}>
            {rows.length} на странице
          </span>
        </Space>
      </Card>

      <Card style={{ marginTop: 16 }} styles={{ body: { padding: 0 } }}>
        <Table<UserDto>
          rowKey="id"
          dataSource={rows}
          columns={columns}
          loading={loading}
          scroll={{ x: 720 }}
          locale={{
            emptyText: (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={query || status !== 'all'
                  ? 'Клиенты по заданным условиям не найдены'
                  : 'Клиентов пока нет — добавьте первого'}
              />
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
        open={createOpen}
        title="Новый клиент"
        okText="Создать"
        cancelText="Отмена"
        confirmLoading={saving}
        onCancel={() => setCreateOpen(false)}
        onOk={() => void submitCreate()}
        destroyOnHidden
      >
        <Form
          form={form}
          layout="vertical"
          initialValues={{ role: 'ROLE_USER', enabled: true }}
        >
          <Form.Item
            name="email"
            label="Email"
            rules={[
              { required: true, message: 'Укажите email' },
              { type: 'email', message: 'Некорректный email' },
            ]}
          >
            <Input placeholder="client@example.ru" autoComplete="off" />
          </Form.Item>
          <Form.Item
            name="fullName"
            label="ФИО"
            rules={[{ required: true, message: 'Укажите ФИО' }]}
          >
            <Input placeholder="Иванов Иван Иванович" />
          </Form.Item>
          <div className="grid-2" style={{ gridTemplateColumns: '1fr 1fr' }}>
            <Form.Item name="phone" label="Телефон">
              <Input placeholder="+7 (999) 123-45-67" />
            </Form.Item>
            <Form.Item name="snils" label="СНИЛС">
              <Input placeholder="11 цифр" />
            </Form.Item>
          </div>
          <div className="grid-2" style={{ gridTemplateColumns: '1fr 1fr' }}>
            <Form.Item name="role" label="Роль">
              <Select options={ROLE_OPTIONS} />
            </Form.Item>
            <Form.Item name="enabled" label="Доступ разрешён" valuePropName="checked">
              <Switch />
            </Form.Item>
          </div>
          <div style={{ color: 'var(--muted)', fontSize: 12 }}>
            Ссылка для входа отправляется из карточки клиента. Пароль не создаётся —
            вход по одноразовой ссылке или сертификату.
          </div>
        </Form>
      </Modal>
    </>
  );
}
