import { useCallback, useEffect, useState } from 'react';
import {
  Alert, App, Avatar, Button, Card, Descriptions, Empty, Form, Input, Modal, Select, Space,
  Spin, Switch, Table, Tag, Tooltip, Typography, Upload,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  ArrowLeftOutlined, DeleteOutlined, DownloadOutlined, EditOutlined, FolderAddOutlined,
  LinkOutlined, SignatureOutlined, StopOutlined, UnlockOutlined, UploadOutlined,
} from '@ant-design/icons';
import { Link, useNavigate, useParams } from 'react-router-dom';
import PageHeader from '../../components/PageHeader';
import { DocStatus, OrderStatus } from '../../components/StatusTag';
import { api, download } from '../../lib/api';
import { ROLE_OPTIONS, errorText, roleLabel } from '../../lib/admin';
import {
  documentStatus, formatDate, formatDateTime, useApp,
} from '../../lib/store';
import type {
  ClientCardDto, DocumentDto, FolderDto, GosklyuchOrderDto, Role, UserDto,
} from '../../lib/types';

interface EditValues {
  email: string;
  fullName: string;
  phone?: string;
  snils?: string;
  role: Role;
  enabled: boolean;
}

interface ProfileValues {
  fullName?: string;
  sex?: string;
  inn?: string;
  birthDate?: string;
  birthPlace?: string;
  registrationAddress?: string;
  noPassport?: boolean;
  passportSeries?: string;
  passportNumber?: string;
  passportDeptCode?: string;
  passportIssuedBy?: string;
  passportIssueDate?: string;
}

export default function ClientCardPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { message, modal } = App.useApp();
  const { refreshClients } = useApp();

  const [card, setCard] = useState<ClientCardDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();

  const [editOpen, setEditOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [editForm] = Form.useForm<EditValues>();

  const [profileOpen, setProfileOpen] = useState(false);
  const [profileSaving, setProfileSaving] = useState(false);
  const [profileForm] = Form.useForm<ProfileValues>();

  const [folderOpen, setFolderOpen] = useState(false);
  const [folderSaving, setFolderSaving] = useState(false);
  const [folderForm] = Form.useForm<{ name: string }>();

  const [uploadOpen, setUploadOpen] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [uploadFile, setUploadFile] = useState<File | null>(null);
  const [uploadFolderId, setUploadFolderId] = useState<number | undefined>();

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    setError(undefined);
    try {
      setCard(await api<ClientCardDto>(`/admin/clients/${id}`));
    } catch (e) {
      setError(errorText(e, 'Не удалось загрузить карточку клиента'));
      setCard(null);
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { void load(); }, [load]);

  const refreshList = () => refreshClients().catch(() => undefined);

  const openEdit = () => {
    if (!card) return;
    editForm.setFieldsValue({
      email: card.client.email,
      fullName: card.client.fullName,
      phone: card.client.phone,
      snils: card.client.snils,
      role: card.client.role,
      enabled: card.client.enabled,
    });
    setEditOpen(true);
  };

  const submitEdit = async () => {
    if (!id) return;
    let values: EditValues;
    try {
      values = await editForm.validateFields();
    } catch {
      return;
    }
    setSaving(true);
    try {
      await api<UserDto>(`/admin/clients/${id}`, {
        method: 'PATCH',
        body: {
          email: values.email.trim(),
          fullName: values.fullName.trim(),
          phone: values.phone?.trim() || '',
          snils: values.snils?.trim() || '',
          role: values.role,
          enabled: values.enabled,
        },
      });
      message.success('Данные клиента обновлены');
      setEditOpen(false);
      await load();
      await refreshList();
    } catch (e) {
      message.error(errorText(e, 'Не удалось сохранить данные клиента'));
    } finally {
      setSaving(false);
    }
  };

  const submitProfile = async () => {
    if (!id) return;
    let values: ProfileValues;
    try {
      values = await profileForm.validateFields();
    } catch {
      return;
    }
    const body: Record<string, string> = {};
    (Object.keys(values) as (keyof ProfileValues)[]).forEach((key) => {
      const value = values[key];
      if (typeof value === 'boolean') {
        body[key] = String(value);
        return;
      }
      const text = (value ?? '').trim();
      if (text) body[key] = text;
    });
    if (Object.keys(body).length === 0) {
      message.warning('Заполните хотя бы одно поле профиля');
      return;
    }
    setProfileSaving(true);
    try {
      await api<UserDto>(`/admin/clients/${id}/profile`, { method: 'PATCH', body });
      message.success('Профиль клиента обновлён');
      setProfileOpen(false);
      profileForm.resetFields();
      await load();
      await refreshList();
    } catch (e) {
      message.error(errorText(e, 'Не удалось обновить профиль клиента'));
    } finally {
      setProfileSaving(false);
    }
  };

  const createFolder = async () => {
    if (!id) return;
    let values: { name: string };
    try {
      values = await folderForm.validateFields();
    } catch {
      return;
    }
    setFolderSaving(true);
    try {
      await api<FolderDto>(`/admin/clients/${id}/folders`, {
        method: 'POST',
        body: { name: values.name.trim() },
      });
      message.success('Раздел создан');
      setFolderOpen(false);
      folderForm.resetFields();
      await load();
    } catch (e) {
      message.error(errorText(e, 'Не удалось создать раздел'));
    } finally {
      setFolderSaving(false);
    }
  };

  const submitUpload = async () => {
    if (!id || !uploadFile) {
      message.warning('Выберите файл для загрузки');
      return;
    }
    setUploading(true);
    try {
      const form = new FormData();
      form.append('file', uploadFile);
      if (uploadFolderId) form.append('folderId', String(uploadFolderId));
      await api<DocumentDto>(`/admin/clients/${id}/documents`, { method: 'POST', form });
      message.success('Документ загружен клиенту');
      setUploadOpen(false);
      setUploadFile(null);
      setUploadFolderId(undefined);
      await load();
    } catch (e) {
      message.error(errorText(e, 'Не удалось загрузить документ'));
    } finally {
      setUploading(false);
    }
  };

  const sendLoginLink = async () => {
    if (!id) return;
    try {
      const res = await api<{ sent: boolean; email: string }>(
        `/admin/clients/${id}/login-link`, { method: 'POST' },
      );
      message.success(`Ссылка для входа отправлена на ${res.email}`);
    } catch (e) {
      message.error(errorText(e, 'Не удалось отправить ссылку для входа'));
    }
  };

  const exportClient = async () => {
    if (!id) return;
    try {
      await download(`/admin/clients/${id}/export`, `client_${id}.zip`);
      message.success('Архив карточки клиента скачан');
    } catch (e) {
      message.error(errorText(e, 'Не удалось выгрузить карточку клиента'));
    }
  };

  const toggleClient = () => {
    if (!id || !card) return;
    const doToggle = async () => {
      try {
        const updated = await api<UserDto>(`/admin/clients/${id}/toggle`, { method: 'POST' });
        message.success(updated.enabled ? 'Клиент разблокирован' : 'Клиент заблокирован');
        await load();
        await refreshList();
      } catch (e) {
        message.error(errorText(e, 'Не удалось изменить статус клиента'));
      }
    };
    if (!card.client.enabled) {
      void doToggle();
      return;
    }
    modal.confirm({
      title: 'Заблокировать клиента?',
      content: `${card.client.fullName || card.client.email} не сможет войти в кабинет и подписывать документы.`,
      okText: 'Заблокировать',
      okButtonProps: { danger: true },
      onOk: doToggle,
    });
  };

  const deleteClient = () => {
    if (!id || !card) return;
    modal.confirm({
      title: 'Удалить клиента?',
      content: `Учётная запись ${card.client.email} будет удалена без возможности восстановления. `
        + 'Если у клиента есть документы и разделы, удаление будет отклонено.',
      okText: 'Удалить',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await api(`/admin/clients/${id}`, { method: 'DELETE' });
          message.success('Клиент удалён');
          await refreshList();
          navigate('/admin/clients');
        } catch (e) {
          message.error(errorText(e, 'Не удалось удалить клиента'));
        }
      },
    });
  };

  const downloadDocument = async (doc: DocumentDto) => {
    try {
      await download(`/admin/documents/${doc.id}/download`, doc.name);
    } catch (e) {
      message.error(errorText(e, 'Не удалось скачать документ'));
    }
  };

  const downloadSignature = async (doc: DocumentDto) => {
    try {
      await download(`/admin/documents/${doc.id}/signature`, `${doc.name}.sig`);
    } catch (e) {
      message.error(errorText(e, 'Не удалось скачать подпись'));
    }
  };

  const deleteDocument = (doc: DocumentDto) => {
    modal.confirm({
      title: 'Удалить документ клиента?',
      content: doc.signed
        ? 'Документ подписан — удаление возможно только администратором и попадёт в аудит.'
        : 'Действие попадёт в журнал аудита.',
      okText: 'Удалить',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await api(`/admin/documents/${doc.id}`, { method: 'DELETE' });
          message.success('Документ удалён');
          await load();
        } catch (e) {
          message.error(errorText(e, 'Не удалось удалить документ'));
        }
      },
    });
  };

  if (loading) {
    return <Card><div style={{ textAlign: 'center', padding: 48 }}><Spin size="large" /></div></Card>;
  }

  if (error || !card) {
    return (
      <Card>
        <Typography.Title level={4}>Клиент не найден</Typography.Title>
        <Typography.Paragraph type="secondary">
          {error ?? 'Возможно, карточка была удалена.'}
        </Typography.Paragraph>
        <Space>
          <Link to="/admin/clients">← К списку клиентов</Link>
          <Button size="small" onClick={() => void load()}>Обновить</Button>
        </Space>
      </Card>
    );
  }

  const { client, folders, documents, gosklyuchOrders, consentConfigured } = card;

  const docColumns: ColumnsType<DocumentDto> = [
    {
      title: 'Документ',
      dataIndex: 'name',
      render: (name: string, doc) => (
        <div style={{ minWidth: 0 }}>
          <div style={{ fontWeight: 600 }}>{name}</div>
          <div style={{ color: 'var(--muted)', fontSize: 12 }}>
            {doc.folderName ?? 'Без раздела'} · {doc.sizePretty}
          </div>
        </div>
      ),
    },
    {
      title: 'Статус',
      dataIndex: 'signed',
      width: 150,
      render: (_: unknown, doc) => <DocStatus status={documentStatus(doc)} />,
    },
    {
      title: 'Загружен',
      dataIndex: 'uploadedAt',
      width: 150,
      responsive: ['lg'],
      render: (uploadedAt: string) => formatDateTime(uploadedAt),
    },
    {
      title: '',
      key: 'actions',
      width: 130,
      render: (_: unknown, doc) => (
        <Space size={4}>
          <Tooltip title="Скачать документ">
            <Button
              type="text"
              icon={<DownloadOutlined />}
              aria-label={`Скачать ${doc.name}`}
              onClick={() => void downloadDocument(doc)}
            />
          </Tooltip>
          <Tooltip title={doc.signed ? 'Скачать подпись' : 'Документ не подписан'}>
            <Button
              type="text"
              icon={<SignatureOutlined />}
              disabled={!doc.signed}
              aria-label={`Скачать подпись ${doc.name}`}
              onClick={() => void downloadSignature(doc)}
            />
          </Tooltip>
          <Tooltip title="Удалить документ">
            <Button
              type="text"
              danger
              icon={<DeleteOutlined />}
              aria-label={`Удалить ${doc.name}`}
              onClick={() => deleteDocument(doc)}
            />
          </Tooltip>
        </Space>
      ),
    },
  ];

  const orderColumns: ColumnsType<GosklyuchOrderDto> = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    {
      title: 'Документ',
      dataIndex: 'documentName',
      render: (name: string) => name || '—',
    },
    {
      title: 'Статус',
      dataIndex: 'status',
      width: 170,
      render: (status: string) => (
        <OrderStatus status={status === 'SIGNED' ? 'signed'
          : status === 'REJECTED' ? 'rejected'
            : status === 'EXPIRED' ? 'expired'
              : status === 'ERROR' ? 'error' : 'sent'} />
      ),
    },
    {
      title: 'Обновлена',
      dataIndex: 'updatedAt',
      width: 150,
      responsive: ['lg'],
      render: (updatedAt?: string) => formatDateTime(updatedAt),
    },
  ];

  return (
    <>
      <Space style={{ marginBottom: 16 }} wrap>
        <Link to="/admin/clients"><ArrowLeftOutlined /> Клиенты</Link>
        <span style={{ color: 'var(--muted)' }}>/</span>
        <span>{client.fullName || client.email}</span>
      </Space>

      <PageHeader
        title={client.fullName || client.email}
        subtitle={`${client.email} · клиент с ${formatDate(client.createdAt)}`}
        extra={(
          <Space wrap>
            <Button type="primary" icon={<EditOutlined />} onClick={openEdit}>Изменить</Button>
            <Button icon={<UploadOutlined />} onClick={() => setUploadOpen(true)}>Загрузить документ</Button>
            <Button icon={<FolderAddOutlined />} onClick={() => setFolderOpen(true)}>Создать раздел</Button>
            <Tooltip title="Отправить ссылку для входа">
              <Button icon={<LinkOutlined />} onClick={() => void sendLoginLink()} aria-label="Ссылка для входа" />
            </Tooltip>
            <Tooltip title="Выгрузить карточку в ZIP">
              <Button icon={<DownloadOutlined />} onClick={() => void exportClient()} aria-label="Выгрузить ZIP" />
            </Tooltip>
            <Tooltip title={client.enabled ? 'Заблокировать' : 'Разблокировать'}>
              <Button
                icon={client.enabled ? <StopOutlined /> : <UnlockOutlined />}
                onClick={toggleClient}
                aria-label={client.enabled ? 'Заблокировать клиента' : 'Разблокировать клиента'}
              />
            </Tooltip>
            <Button danger icon={<DeleteOutlined />} onClick={deleteClient}>Удалить</Button>
          </Space>
        )}
      />

      {!consentConfigured && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="Согласие на обработку ПДн не настроено"
          description="Клиент не сможет загружать документы, пока администратор не создаст активный шаблон согласия."
        />
      )}

      <div className="stat-grid" style={{ marginBottom: 16 }}>
        <Card>
          <div className="stat-label">Документов</div>
          <div className="stat-value">{client.documentsTotal}</div>
        </Card>
        <Card>
          <div className="stat-label">Подписано</div>
          <div className="stat-value" style={{ color: '#10b981' }}>{client.documentsSigned}</div>
        </Card>
        <Card>
          <div className="stat-label">Разделов</div>
          <div className="stat-value">{folders.length}</div>
        </Card>
        <Card>
          <div className="stat-label">Заявок Госключа</div>
          <div className="stat-value">{gosklyuchOrders.length}</div>
        </Card>
      </div>

      <div className="grid-2" style={{ alignItems: 'start' }}>
        <Card
          title={`Документы (${documents.length})`}
          extra={(
            <Button size="small" icon={<UploadOutlined />} onClick={() => setUploadOpen(true)}>
              Загрузить
            </Button>
          )}
          styles={{ body: { padding: documents.length === 0 ? 24 : 0 } }}
        >
          {documents.length === 0 ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description="У клиента пока нет документов"
            >
              <Button type="primary" icon={<UploadOutlined />} onClick={() => setUploadOpen(true)}>
                Загрузить первый документ
              </Button>
            </Empty>
          ) : (
            <Table<DocumentDto>
              rowKey="id"
              size="middle"
              dataSource={documents}
              columns={docColumns}
              pagination={documents.length > 10 ? { pageSize: 10, showSizeChanger: false } : false}
            />
          )}
        </Card>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <Card
            title="Персональные данные"
            extra={<Button size="small" type="link" onClick={() => setProfileOpen(true)}>Дополнить профиль</Button>}
          >
            <div style={{ display: 'flex', gap: 14, alignItems: 'center', marginBottom: 16 }}>
              <Avatar size={54} style={{ background: 'linear-gradient(135deg,#4f46e5,#8b5cf6)' }}>
                {(client.fullName || client.email).slice(0, 1).toUpperCase()}
              </Avatar>
              <div>
                <div style={{ fontWeight: 700 }}>{client.fullName || 'Без имени'}</div>
                <div style={{ color: 'var(--muted)', fontSize: 12 }}>{roleLabel(client.role)}</div>
              </div>
            </div>
            <Descriptions
              column={1}
              size="small"
              items={[
                { key: 'email', label: 'Email', children: client.email },
                { key: 'phone', label: 'Телефон', children: client.phone || '—' },
                { key: 'snils', label: 'СНИЛС', children: client.snils || '—' },
                {
                  key: 'status',
                  label: 'Статус',
                  children: (
                    <Tag color={client.enabled ? 'success' : 'default'} bordered={false} style={{ borderRadius: 999 }}>
                      {client.enabled ? 'Активен' : 'Заблокирован'}
                    </Tag>
                  ),
                },
                {
                  key: 'consent',
                  label: 'Согласие ПДн',
                  children: client.consentSigned
                    ? <Tag color="success" bordered={false}>Подписано {formatDate(client.consentSignedAt)}</Tag>
                    : <Tag color="warning" bordered={false}>Не подписано</Tag>,
                },
                {
                  key: 'profile',
                  label: 'Профиль',
                  children: client.profileComplete ? 'Заполнен' : 'Требует дополнения',
                },
                { key: 'lastLogin', label: 'Последний вход', children: formatDateTime(client.lastLoginAt) },
              ]}
            />
          </Card>

          <Card
            title={`Разделы (${folders.length})`}
            extra={<Button size="small" type="link" onClick={() => setFolderOpen(true)}>Добавить</Button>}
          >
            {folders.length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Разделов пока нет" />
            ) : (
              <Space direction="vertical" size={10} style={{ width: '100%' }}>
                {folders.map((folder) => (
                  <div
                    key={folder.id}
                    style={{
                      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                      padding: '8px 12px', borderRadius: 12, background: 'var(--surface-2)',
                      border: '1px solid var(--border)',
                    }}
                  >
                    <span style={{ fontWeight: 600 }}>{folder.name}</span>
                    <span style={{ color: 'var(--muted)', fontSize: 12 }}>
                      {folder.documentsCount} док. · {folder.signedCount} подп.
                    </span>
                  </div>
                ))}
              </Space>
            )}
          </Card>

          <Card title={`Заявки Госключа (${gosklyuchOrders.length})`} styles={{ body: { padding: gosklyuchOrders.length === 0 ? 24 : 0 } }}>
            {gosklyuchOrders.length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Заявок нет" />
            ) : (
              <Table<GosklyuchOrderDto>
                rowKey="id"
                size="small"
                dataSource={gosklyuchOrders}
                columns={orderColumns}
                pagination={false}
              />
            )}
          </Card>
        </div>
      </div>

      <Modal
        open={editOpen}
        title="Изменить данные клиента"
        okText="Сохранить"
        cancelText="Отмена"
        confirmLoading={saving}
        onCancel={() => setEditOpen(false)}
        onOk={() => void submitEdit()}
        destroyOnHidden
      >
        <Form form={editForm} layout="vertical">
          <Form.Item
            name="email"
            label="Email"
            rules={[
              { required: true, message: 'Укажите email' },
              { type: 'email', message: 'Некорректный email' },
            ]}
          >
            <Input autoComplete="off" />
          </Form.Item>
          <Form.Item name="fullName" label="ФИО" rules={[{ required: true, message: 'Укажите ФИО' }]}>
            <Input />
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
            Пустое поле телефона или СНИЛС очищает соответствующее значение.
          </div>
        </Form>
      </Modal>

      <Modal
        open={profileOpen}
        title="Дополнительные сведения профиля"
        okText="Сохранить"
        cancelText="Отмена"
        confirmLoading={profileSaving}
        onCancel={() => setProfileOpen(false)}
        onOk={() => void submitProfile()}
        width={640}
        destroyOnHidden
      >
        <Form form={profileForm} layout="vertical" initialValues={{ noPassport: false }}>
          <div className="grid-2" style={{ gridTemplateColumns: '1fr 1fr' }}>
            <Form.Item name="fullName" label="ФИО (для документов)">
              <Input />
            </Form.Item>
            <Form.Item name="sex" label="Пол">
              <Input placeholder="М / Ж" />
            </Form.Item>
            <Form.Item name="inn" label="ИНН">
              <Input placeholder="12 цифр" />
            </Form.Item>
            <Form.Item name="birthDate" label="Дата рождения">
              <Input type="date" />
            </Form.Item>
          </div>
          <Form.Item name="birthPlace" label="Место рождения">
            <Input />
          </Form.Item>
          <Form.Item name="registrationAddress" label="Адрес регистрации">
            <Input />
          </Form.Item>
          <Form.Item name="noPassport" label="Паспорт отсутствует" valuePropName="checked">
            <Switch />
          </Form.Item>
          <div className="grid-2" style={{ gridTemplateColumns: '1fr 1fr' }}>
            <Form.Item name="passportSeries" label="Серия паспорта">
              <Input />
            </Form.Item>
            <Form.Item name="passportNumber" label="Номер паспорта">
              <Input />
            </Form.Item>
            <Form.Item name="passportDeptCode" label="Код подразделения">
              <Input />
            </Form.Item>
            <Form.Item name="passportIssueDate" label="Дата выдачи">
              <Input type="date" />
            </Form.Item>
          </div>
          <Form.Item name="passportIssuedBy" label="Кем выдан">
            <Input />
          </Form.Item>
          <div style={{ color: 'var(--muted)', fontSize: 12 }}>
            Пустые поля не отправляются — ранее сохранённые значения останутся без изменений.
          </div>
        </Form>
      </Modal>

      <Modal
        open={folderOpen}
        title="Новый раздел клиента"
        okText="Создать"
        cancelText="Отмена"
        confirmLoading={folderSaving}
        onCancel={() => setFolderOpen(false)}
        onOk={() => void createFolder()}
        destroyOnHidden
      >
        <Form form={folderForm} layout="vertical">
          <Form.Item
            name="name"
            label="Название раздела"
            rules={[{ required: true, message: 'Укажите название раздела' }]}
          >
            <Input placeholder="Например, Договоры" maxLength={120} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        open={uploadOpen}
        title="Загрузить документ клиенту"
        okText="Загрузить"
        cancelText="Отмена"
        confirmLoading={uploading}
        onCancel={() => { setUploadOpen(false); setUploadFile(null); }}
        onOk={() => void submitUpload()}
        destroyOnHidden
      >
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <div>
            <div style={{ fontWeight: 600, marginBottom: 8 }}>Файл</div>
            <Upload
              maxCount={1}
              beforeUpload={(file) => { setUploadFile(file); return false; }}
              onRemove={() => setUploadFile(null)}
            >
              <Button icon={<UploadOutlined />}>Выбрать файл</Button>
            </Upload>
            {uploadFile && (
              <div style={{ color: 'var(--muted)', fontSize: 12, marginTop: 6 }}>
                {uploadFile.name}
              </div>
            )}
          </div>
          <div>
            <div style={{ fontWeight: 600, marginBottom: 8 }}>Раздел</div>
            <Select
              allowClear
              style={{ width: '100%' }}
              placeholder="Раздел по умолчанию"
              value={uploadFolderId}
              onChange={(value?: number) => setUploadFolderId(value)}
              options={folders.map((f) => ({ value: f.id, label: f.name }))}
            />
          </div>
          <div style={{ color: 'var(--muted)', fontSize: 12 }}>
            Документ будет загружен от имени клиента и попадёт в журнал аудита.
          </div>
        </Space>
      </Modal>
    </>
  );
}
