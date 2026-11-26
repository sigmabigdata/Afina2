import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert, App, Button, Card, Empty, Form, Input, Modal, Space, Spin, Table, Tabs, Tag, Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  CloudDownloadOutlined, KeyOutlined, LockOutlined, MailOutlined, ReloadOutlined,
  SafetyCertificateOutlined, SettingOutlined, SyncOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import { api, fetchBlob, saveBlob } from '../../lib/api';
import { errorText, settingGroupTitle } from '../../lib/admin';
import { formatDateTime, useApp } from '../../lib/store';
import type { KeyInfoDto, KeysStatusDto, SettingDto, SslStatusDto } from '../../lib/types';

interface SmtpTestResponse { sent: boolean; to: string }
interface UpdateSettingsResponse { updated: number; skipped: number }

export default function SettingsPage() {
  const { message, modal } = App.useApp();
  const { user } = useApp();

  // ── настройки ──
  const [settings, setSettings] = useState<SettingDto[]>([]);
  const [values, setValues] = useState<Record<string, string>>({});
  const initial = useRef<Record<string, string>>({});
  const [settingsLoading, setSettingsLoading] = useState(true);
  const [settingsError, setSettingsError] = useState<string>();
  const [saving, setSaving] = useState(false);

  // ── IP-whitelist ──
  const [whitelist, setWhitelist] = useState('');
  const [whitelistInitial, setWhitelistInitial] = useState('');
  const [whitelistLoading, setWhitelistLoading] = useState(true);
  const [whitelistError, setWhitelistError] = useState<string>();
  const [whitelistSaving, setWhitelistSaving] = useState(false);

  // ── SMTP ──
  const [smtpTo, setSmtpTo] = useState('');
  const [smtpSending, setSmtpSending] = useState(false);

  // ── ключи ──
  const [keys, setKeys] = useState<KeysStatusDto | null>(null);
  const [keysLoading, setKeysLoading] = useState(true);
  const [keysError, setKeysError] = useState<string>();
  const [keysOpen, setKeysOpen] = useState(false);
  const [keysPassword, setKeysPassword] = useState('');
  const [keysConfirm, setKeysConfirm] = useState('');
  const [keysDownloading, setKeysDownloading] = useState(false);
  const [keysBusy, setKeysBusy] = useState(false);

  // ── TLS ──
  const [ssl, setSsl] = useState<SslStatusDto | null>(null);
  const [sslLoading, setSslLoading] = useState(true);
  const [sslError, setSslError] = useState<string>();
  const [sslCert, setSslCert] = useState('');
  const [sslKey, setSslKey] = useState('');
  const [sslSaving, setSslSaving] = useState(false);
  const [sslBusy, setSslBusy] = useState(false);

  const loadSettings = useCallback(async () => {
    setSettingsLoading(true);
    setSettingsError(undefined);
    try {
      const list = await api<SettingDto[]>('/admin/settings');
      setSettings(list);
      const next: Record<string, string> = {};
      list.forEach((item) => {
        if (!item.secret) next[item.key] = item.value;
      });
      initial.current = next;
      setValues(next);
    } catch (e) {
      setSettingsError(errorText(e, 'Не удалось загрузить настройки'));
    } finally {
      setSettingsLoading(false);
    }
  }, []);

  const loadWhitelist = useCallback(async () => {
    setWhitelistLoading(true);
    setWhitelistError(undefined);
    try {
      const res = await api<{ value: string }>('/admin/settings/ip-whitelist');
      setWhitelist(res.value);
      setWhitelistInitial(res.value);
    } catch (e) {
      setWhitelistError(errorText(e, 'Не удалось загрузить IP-whitelist'));
    } finally {
      setWhitelistLoading(false);
    }
  }, []);

  const loadKeys = useCallback(async () => {
    setKeysLoading(true);
    setKeysError(undefined);
    try {
      setKeys(await api<KeysStatusDto>('/admin/keys'));
    } catch (e) {
      setKeysError(errorText(e, 'Не удалось загрузить состояние ключей'));
    } finally {
      setKeysLoading(false);
    }
  }, []);

  const loadSsl = useCallback(async () => {
    setSslLoading(true);
    setSslError(undefined);
    try {
      setSsl(await api<SslStatusDto>('/admin/ssl'));
    } catch (e) {
      setSslError(errorText(e, 'Не удалось загрузить состояние TLS'));
    } finally {
      setSslLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadSettings();
    void loadWhitelist();
    void loadKeys();
    void loadSsl();
  }, [loadSettings, loadWhitelist, loadKeys, loadSsl]);

  useEffect(() => {
    if (!smtpTo && user?.email) setSmtpTo(user.email);
  }, [smtpTo, user?.email]);

  const dirty = useMemo(() => {
    const body: Record<string, string> = {};
    settings.forEach((item) => {
      if (item.key === 'admin.ip_whitelist') return;
      const current = values[item.key] ?? '';
      if (item.secret) {
        if (current && current !== '***') body[item.key] = current;
        return;
      }
      if (current !== (initial.current[item.key] ?? '')) body[item.key] = current;
    });
    return body;
  }, [settings, values]);

  const saveSettings = async () => {
    if (Object.keys(dirty).length === 0) {
      message.info('Изменений нет');
      return;
    }
    setSaving(true);
    try {
      const res = await api<UpdateSettingsResponse>('/admin/settings', {
        method: 'PUT',
        body: dirty,
      });
      message.success(
        `Сохранено настроек: ${res.updated}`
        + (res.skipped > 0 ? ` · секретов без изменений: ${res.skipped}` : ''),
      );
      await loadSettings();
    } catch (e) {
      message.error(errorText(e, 'Не удалось сохранить настройки'));
    } finally {
      setSaving(false);
    }
  };

  const saveWhitelist = async () => {
    setWhitelistSaving(true);
    try {
      const res = await api<{ value: string }>('/admin/settings/ip-whitelist', {
        method: 'PUT',
        body: { value: whitelist.trim() },
      });
      setWhitelistInitial(res.value);
      setWhitelist(res.value);
      message.success('IP-whitelist сохранён');
    } catch (e) {
      message.error(errorText(e, 'Не удалось сохранить IP-whitelist'));
    } finally {
      setWhitelistSaving(false);
    }
  };

  const sendTestEmail = async () => {
    if (!smtpTo.trim() || !smtpTo.includes('@')) {
      message.warning('Укажите корректный email');
      return;
    }
    setSmtpSending(true);
    try {
      const res = await api<SmtpTestResponse>('/admin/settings/smtp/test', {
        method: 'POST',
        body: { to: smtpTo.trim() },
      });
      message.success(`Тестовое письмо отправлено на ${res.to}`);
    } catch (e) {
      message.error(errorText(e, 'Не удалось отправить тестовое письмо'));
    } finally {
      setSmtpSending(false);
    }
  };

  const downloadKeys = async () => {
    const minLength = keys?.minPasswordLength ?? 12;
    if (keysPassword.length < minLength) {
      message.warning(`Пароль должен быть не короче ${minLength} символов`);
      return;
    }
    if (keysPassword !== keysConfirm) {
      message.warning('Пароли не совпадают');
      return;
    }
    setKeysDownloading(true);
    try {
      const res = await fetchBlob('/admin/keys/download', {
        method: 'POST',
        body: { password: keysPassword, confirmPassword: keysConfirm },
      });
      saveBlob(res.blob, res.fileName ?? 'afina2-keys.zip');
      message.success('Архив ключей скачан');
      setKeysOpen(false);
      setKeysPassword('');
      setKeysConfirm('');
      await loadKeys();
    } catch (e) {
      message.error(errorText(e, 'Не удалось скачать архив ключей'));
    } finally {
      setKeysDownloading(false);
    }
  };

  const rotateKey = (kind: 'pii' | 'file') => {
    modal.confirm({
      title: kind === 'pii' ? 'Ротировать ключ персональных данных?' : 'Ротировать ключ файлов?',
      content: 'Будет создана новая версия ключа. Старые данные останутся расшифровываемыми, '
        + 'но новые записи будут шифроваться новым ключом. Сделайте резервную копию архива ключей.',
      okText: 'Ротировать',
      okButtonProps: { danger: true },
      onOk: async () => {
        setKeysBusy(true);
        try {
          const res = await api<{ message: string }>(`/admin/keys/rotate/${kind}`, { method: 'POST' });
          message.success(res.message);
          await loadKeys();
        } catch (e) {
          message.error(errorText(e, 'Не удалось ротировать ключ'));
        } finally {
          setKeysBusy(false);
        }
      },
    });
  };

  const confirmSslUpload = () => {
    if (!sslCert.trim() || !sslKey.trim()) {
      message.warning('Вставьте сертификат и приватный ключ в PEM-формате');
      return;
    }
    modal.confirm({
      title: 'Применить новый TLS-сертификат?',
      content: 'Сертификат будет сохранён, Angie перезапустится. При ошибке выполнения '
        + 'будет выполнен автоматический откат на предыдущий сертификат.',
      okText: 'Загрузить и применить',
      okButtonProps: { danger: true },
      onOk: async () => {
        setSslSaving(true);
        try {
          const res = await api<{ message: string }>('/admin/ssl', {
            method: 'POST',
            body: { certPem: sslCert, keyPem: sslKey },
          });
          message.success(res.message);
          setSslCert('');
          setSslKey('');
          await loadSsl();
        } catch (e) {
          message.error(errorText(e, 'Не удалось применить сертификат'));
        } finally {
          setSslSaving(false);
        }
      },
    });
  };

  const sslAction = (action: 'restart' | 'rollback' | 'delete') => {
    const titles: Record<typeof action, string> = {
      restart: 'Перезапустить Angie?',
      rollback: 'Откатить сертификат?',
      delete: 'Удалить сертификат?',
    };
    const contents: Record<typeof action, string> = {
      restart: 'Кратковременный перерыв в обслуживании во время перезапуска веб-сервера.',
      rollback: 'Будет восстановлен предыдущий сертификат (или ACME), Angie перезапустится.',
      delete: 'Загруженный сертификат будет удалён, Angie перезапустится с self-signed/ACME.',
    };
    modal.confirm({
      title: titles[action],
      content: contents[action],
      okText: 'Продолжить',
      okButtonProps: { danger: action !== 'restart' },
      onOk: async () => {
        setSslBusy(true);
        try {
          const res = await api<{ message: string }>(
            action === 'delete' ? '/admin/ssl' : `/admin/ssl/${action}`,
            { method: action === 'delete' ? 'DELETE' : 'POST' },
          );
          message.success(res.message);
          await loadSsl();
        } catch (e) {
          message.error(errorText(e, 'Не удалось выполнить действие'));
        } finally {
          setSslBusy(false);
        }
      },
    });
  };

  const groups = useMemo(() => {
    const map = new Map<string, SettingDto[]>();
    settings.forEach((item) => {
      if (item.key === 'admin.ip_whitelist') return;
      const prefix = item.key.split('.')[0] || 'other';
      const list = map.get(prefix) ?? [];
      list.push(item);
      map.set(prefix, list);
    });
    return Array.from(map.entries()).sort(([a], [b]) => a.localeCompare(b));
  }, [settings]);

  const keyColumns: ColumnsType<KeyInfoDto> = [
    {
      title: 'Ключ',
      dataIndex: 'name',
      render: (name: string, row) => (
        <div>
          <div style={{ fontWeight: 600 }}>{name}</div>
          <div style={{ color: 'var(--muted)', fontSize: 12 }}>{row.description}</div>
        </div>
      ),
    },
    {
      title: 'Состояние',
      dataIndex: 'exists',
      width: 140,
      render: (exists: boolean, row) => exists
        ? <Tag color="success" bordered={false}>На месте</Tag>
        : <Tag color="error" bordered={false}>{row.problem || 'Отсутствует'}</Tag>,
    },
    { title: 'Права', dataIndex: 'permissions', width: 100, responsive: ['lg'] },
    {
      title: 'Изменён',
      dataIndex: 'modifiedAt',
      width: 160,
      responsive: ['xl'],
      render: (modifiedAt: string) => modifiedAt || '—',
    },
  ];

  const paramTab = (
    <Card
      title={<Space><SettingOutlined /> Параметры приложения</Space>}
      extra={<Button type="primary" loading={saving} onClick={() => void saveSettings()}>Сохранить изменения</Button>}
    >
      {settingsLoading ? (
        <div style={{ textAlign: 'center', padding: 48 }}><Spin size="large" /></div>
      ) : settingsError ? (
        <Alert
          type="error"
          showIcon
          message="Не удалось загрузить настройки"
          description={settingsError}
          action={<Button size="small" onClick={() => void loadSettings()}>Повторить</Button>}
        />
      ) : settings.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Настройки ещё не заданы" />
      ) : (
        <div className="grid-2" style={{ gridTemplateColumns: '1fr 1fr', alignItems: 'start' }}>
          {groups.map(([prefix, list]) => (
            <Card key={prefix} size="small" title={settingGroupTitle(prefix)}>
              <Space direction="vertical" size={14} style={{ width: '100%' }}>
                {list.map((item) => (
                  <div key={item.key}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
                      <label htmlFor={`setting-${item.key}`} style={{ fontWeight: 600, fontSize: 13 }}>
                        {item.description || item.key}
                      </label>
                      <code style={{ color: 'var(--muted)', fontSize: 11 }}>{item.key}</code>
                    </div>
                    {item.secret ? (
                      <Input.Password
                        id={`setting-${item.key}`}
                        value={values[item.key] ?? ''}
                        onChange={(e) => setValues((prev) => ({ ...prev, [item.key]: e.target.value }))}
                        placeholder="Секрет не отображается — введите новое значение"
                        autoComplete="new-password"
                      />
                    ) : (
                      <Input
                        id={`setting-${item.key}`}
                        value={values[item.key] ?? ''}
                        onChange={(e) => setValues((prev) => ({ ...prev, [item.key]: e.target.value }))}
                      />
                    )}
                    {item.updatedAt && (
                      <div style={{ color: 'var(--muted)', fontSize: 11, marginTop: 4 }}>
                        изменено {item.updatedBy || '—'} · {formatDateTime(item.updatedAt)}
                      </div>
                    )}
                  </div>
                ))}
              </Space>
            </Card>
          ))}
        </div>
      )}
    </Card>
  );

  const securityTab = (
    <Card
      title={<Space><LockOutlined /> Доступ в админку</Space>}
      extra={(
        <Button
          type="primary"
          loading={whitelistSaving}
          disabled={whitelist.trim() === whitelistInitial.trim()}
          onClick={() => void saveWhitelist()}
        >
          Сохранить
        </Button>
      )}
    >
      {whitelistLoading ? (
        <div style={{ textAlign: 'center', padding: 48 }}><Spin size="large" /></div>
      ) : whitelistError ? (
        <Alert
          type="error"
          showIcon
          message="Не удалось загрузить IP-whitelist"
          description={whitelistError}
          action={<Button size="small" onClick={() => void loadWhitelist()}>Повторить</Button>}
        />
      ) : (
        <Space direction="vertical" size={16} style={{ width: '100%', maxWidth: 720 }}>
          <div>
            <div style={{ fontWeight: 600, marginBottom: 8 }}>IP-адреса и подсети с доступом в админку</div>
            <Input.TextArea
              rows={4}
              value={whitelist}
              onChange={(e) => setWhitelist(e.target.value)}
              placeholder="203.0.113.5, 203.0.113.0/24, 2001:db8::/32"
            />
          </div>
          <Alert
            type="warning"
            showIcon
            message="Осторожно с изменениями"
            description={'Если убрать свой IP из списка, доступ в админку будет потерян. '
              + 'Значение «*» разрешает все адреса и не рекомендуется для промышленной эксплуатации. '
              + 'Пустое значение означает «без ограничений по IP».'}
          />
        </Space>
      )}
    </Card>
  );

  const mailTab = (
    <Card title={<Space><MailOutlined /> Проверка почты</Space>}>
      <Space direction="vertical" size={16} style={{ width: '100%', maxWidth: 560 }}>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
          Параметры SMTP задаются в разделе «Параметры» (группа smtp). Тестовое письмо
          отправляется с текущими настройками сервера.
        </Typography.Paragraph>
        <Form layout="vertical">
          <Form.Item label="Кому отправить тестовое письмо">
            <Input
              value={smtpTo}
              onChange={(e) => setSmtpTo(e.target.value)}
              placeholder="admin@example.ru"
              type="email"
            />
          </Form.Item>
        </Form>
        <Button
          type="primary"
          icon={<MailOutlined />}
          loading={smtpSending}
          onClick={() => void sendTestEmail()}
        >
          Отправить тестовое письмо
        </Button>
      </Space>
    </Card>
  );

  const keysTab = (
    <Card
      title={<Space><KeyOutlined /> Ключи шифрования</Space>}
      extra={(
        <Space wrap>
          <Button icon={<ReloadOutlined />} loading={keysLoading} onClick={() => void loadKeys()}>
            Обновить
          </Button>
          <Button
            type="primary"
            icon={<CloudDownloadOutlined />}
            onClick={() => setKeysOpen(true)}
            disabled={!keys}
          >
            Скачать архив
          </Button>
          <Button
            icon={<SyncOutlined />}
            loading={keysBusy}
            onClick={() => rotateKey('file')}
          >
            Ротация ключа файлов
          </Button>
          <Button
            icon={<SyncOutlined />}
            loading={keysBusy}
            onClick={() => rotateKey('pii')}
          >
            Ротация ключа ПДн
          </Button>
        </Space>
      )}
    >
      {keysLoading ? (
        <div style={{ textAlign: 'center', padding: 48 }}><Spin size="large" /></div>
      ) : keysError ? (
        <Alert
          type="error"
          showIcon
          message="Не удалось загрузить состояние ключей"
          description={keysError}
          action={<Button size="small" onClick={() => void loadKeys()}>Повторить</Button>}
        />
      ) : !keys ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Сведения о ключах недоступны" />
      ) : (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Alert
            type="warning"
            showIcon
            message="Без ключей данные не восстановить"
            description={'Архив скачивается только под паролем (AES-256). Пароль не хранится в приложении — '
              + 'при потере архив не открыть. Храните его отдельно от архива.'}
          />
          <Space wrap size={24}>
            <div>
              <div className="stat-label">Версия ключа ПДн</div>
              <div style={{ fontWeight: 700, fontSize: 20 }}>v{keys.piiVersion}</div>
            </div>
            <div>
              <div className="stat-label">Версия ключа файлов</div>
              <div style={{ fontWeight: 700, fontSize: 20 }}>v{keys.fileVersion}</div>
            </div>
            <div>
              <div className="stat-label">Последняя выгрузка</div>
              <div style={{ fontWeight: 700 }}>{formatDateTime(keys.lastBackupAt)}</div>
            </div>
            <div>
              <div className="stat-label">Выгрузок</div>
              <div style={{ fontWeight: 700, fontSize: 20 }}>{keys.downloadCount}</div>
            </div>
          </Space>
          <Table<KeyInfoDto>
            rowKey="name"
            dataSource={keys.keys}
            columns={keyColumns}
            pagination={false}
            size="small"
            locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Ключи не найдены" /> }}
          />
        </Space>
      )}
    </Card>
  );

  const sslTab = (
    <Card title={<Space><SafetyCertificateOutlined /> TLS-сертификат</Space>}>
      {sslLoading ? (
        <div style={{ textAlign: 'center', padding: 48 }}><Spin size="large" /></div>
      ) : sslError ? (
        <Alert
          type="error"
          showIcon
          message="Не удалось загрузить состояние TLS"
          description={sslError}
          action={<Button size="small" onClick={() => void loadSsl()}>Повторить</Button>}
        />
      ) : (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Space wrap size={20}>
            <Tag color={ssl?.hasCertificate ? 'success' : 'default'} bordered={false}>
              {ssl?.hasCertificate ? 'Свой сертификат загружен' : 'Self-signed сертификат'}
            </Tag>
            <Tag color={ssl?.acmeAvailable ? 'processing' : 'default'} bordered={false}>
              ACME {ssl?.acmeAvailable ? 'доступен' : 'недоступен'}
            </Tag>
            <Tag color={ssl?.acmeActive ? 'success' : 'default'} bordered={false}>
              ACME {ssl?.acmeActive ? 'активен' : 'не активен'}
            </Tag>
            <Button
              icon={<ReloadOutlined />}
              loading={sslBusy}
              onClick={() => sslAction('restart')}
            >
              Перезапустить Angie
            </Button>
            <Button
              loading={sslBusy}
              onClick={() => sslAction('rollback')}
            >
              Откатить сертификат
            </Button>
            <Button
              danger
              loading={sslBusy}
              disabled={!ssl?.hasCertificate}
              onClick={() => sslAction('delete')}
            >
              Удалить сертификат
            </Button>
          </Space>
          <div className="grid-2" style={{ gridTemplateColumns: '1fr 1fr', alignItems: 'start' }}>
            <div>
              <div style={{ fontWeight: 600, marginBottom: 8 }}>Сертификат (PEM, включая цепочку)</div>
              <Input.TextArea
                rows={8}
                value={sslCert}
                onChange={(e) => setSslCert(e.target.value)}
                placeholder={'-----BEGIN CERTIFICATE-----\n…'}
                style={{ fontFamily: 'ui-monospace, SFMono-Regular, monospace', fontSize: 12 }}
              />
            </div>
            <div>
              <div style={{ fontWeight: 600, marginBottom: 8 }}>Приватный ключ (PEM)</div>
              <Input.TextArea
                rows={8}
                value={sslKey}
                onChange={(e) => setSslKey(e.target.value)}
                placeholder={'-----BEGIN PRIVATE KEY-----\n…'}
                style={{ fontFamily: 'ui-monospace, SFMono-Regular, monospace', fontSize: 12 }}
              />
            </div>
          </div>
          <Space>
            <Button
              type="primary"
              loading={sslSaving}
              onClick={confirmSslUpload}
              disabled={!sslCert.trim() || !sslKey.trim()}
            >
              Загрузить и применить
            </Button>
            <span style={{ color: 'var(--muted)', fontSize: 12 }}>
              Сертификат и ключ проверяются на соответствие до применения.
            </span>
          </Space>
        </Space>
      )}
    </Card>
  );

  return (
    <>
      <PageHeader
        title="Настройки"
        subtitle="Параметры приложения, доступ в админку, почта, ключи и TLS"
      />
      <Tabs
        defaultActiveKey="params"
        items={[
          { key: 'params', label: <span><SettingOutlined /> Параметры</span>, children: paramTab },
          { key: 'security', label: <span><LockOutlined /> Доступ</span>, children: securityTab },
          { key: 'mail', label: <span><MailOutlined /> Почта</span>, children: mailTab },
          { key: 'keys', label: <span><KeyOutlined /> Ключи</span>, children: keysTab },
          { key: 'ssl', label: <span><SafetyCertificateOutlined /> TLS</span>, children: sslTab },
        ]}
      />

      <Modal
        open={keysOpen}
        title="Архив ключей под паролем"
        okText="Скачать ZIP"
        cancelText="Отмена"
        confirmLoading={keysDownloading}
        onCancel={() => setKeysOpen(false)}
        onOk={() => void downloadKeys()}
        destroyOnHidden
      >
        <Space direction="vertical" size={14} style={{ width: '100%' }}>
          <Alert
            type="warning"
            showIcon
            message="Пароль не сохраняется"
            description={`Минимум ${keys?.minPasswordLength ?? 12} символов. Если пароль потерян, архив не открыть.`}
          />
          <Input.Password
            placeholder="Пароль к архиву"
            value={keysPassword}
            onChange={(e) => setKeysPassword(e.target.value)}
            autoComplete="new-password"
          />
          <Input.Password
            placeholder="Повторите пароль"
            value={keysConfirm}
            onChange={(e) => setKeysConfirm(e.target.value)}
            autoComplete="new-password"
          />
        </Space>
      </Modal>
    </>
  );
}
