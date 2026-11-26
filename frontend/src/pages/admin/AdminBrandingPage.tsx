import { useCallback, useEffect, useState } from 'react';
import {
  Alert, App, Button, Card, Divider, Form, Input, Space, Spin, Typography, Upload,
} from 'antd';
import { DeleteOutlined, InboxOutlined, PictureOutlined, SaveOutlined } from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import Logo from '../../components/Logo';
import { api } from '../../lib/api';
import { errorText } from '../../lib/admin';
import { useApp } from '../../lib/store';

interface BrandingDto {
  name: string;
  tagline: string;
  hasLogo: boolean;
  logoUrl: string | null;
  defaultName?: string;
  defaultTagline?: string;
  maxLogoBytes?: number;
}

/**
 * Брендинг (белый лейбл): название, подпись и логотип заказчика.
 *
 * Загруженный логотип заменяет стандартный знак в шапке, на странице входа,
 * на публичной странице проверки подписи, в favicon и в футере PDF.
 */
export default function AdminBrandingPage() {
  const { message, modal } = App.useApp();
  const { refreshBranding, branding } = useApp();
  const [form] = Form.useForm();
  const [data, setData] = useState<BrandingDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string>();

  const load = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      const dto = await api<BrandingDto>('/admin/branding');
      setData(dto);
      form.setFieldsValue({ name: dto.name, tagline: dto.tagline });
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [form]);

  useEffect(() => { void load(); }, [load]);

  const saveTexts = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      const dto = await api<BrandingDto>('/admin/branding', {
        method: 'PUT',
        body: { name: values.name, tagline: values.tagline },
      });
      setData(dto);
      await refreshBranding();
      message.success('Название и подпись сохранены');
    } catch (e) {
      message.error(errorText(e));
    } finally {
      setSaving(false);
    }
  };

  const uploadLogo = async (file: File) => {
    const limit = data?.maxLogoBytes ?? 512 * 1024;
    if (file.size > limit) {
      message.error(`Логотип больше ${Math.round(limit / 1024)} КБ — уменьшите изображение`);
      return false;
    }
    const upload = new FormData();
    upload.append('file', file);
    setUploading(true);
    try {
      const dto = await api<BrandingDto>('/admin/branding/logo', { method: 'POST', form: upload });
      setData(dto);
      await refreshBranding();
      message.success('Логотип обновлён');
    } catch (e) {
      message.error(errorText(e));
    } finally {
      setUploading(false);
    }
    return false;
  };

  const removeLogo = () => {
    modal.confirm({
      title: 'Удалить логотип?',
      content: 'Вернётся стандартный знак «Афина».',
      okText: 'Удалить',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          const dto = await api<BrandingDto>('/admin/branding/logo', { method: 'DELETE' });
          setData(dto);
          await refreshBranding();
          message.success('Логотип удалён');
        } catch (e) {
          message.error(errorText(e));
        }
      },
    });
  };

  const resetAll = () => {
    modal.confirm({
      title: 'Сбросить брендинг?',
      content: 'Название, подпись и логотип вернутся к стандартным значениям.',
      okText: 'Сбросить',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          const dto = await api<BrandingDto>('/admin/branding', { method: 'DELETE' });
          setData(dto);
          form.setFieldsValue({ name: dto.name, tagline: dto.tagline });
          await refreshBranding();
          message.success('Брендинг сброшен');
        } catch (e) {
          message.error(errorText(e));
        }
      },
    });
  };

  if (loading) {
    return <Card><div style={{ textAlign: 'center', padding: 48 }}><Spin size="large" /></div></Card>;
  }

  return (
    <>
      <PageHeader
        title="Брендинг"
        subtitle="Логотип и название вашей организации — во всём интерфейсе и документах"
        extra={(
          <Space>
            <Button onClick={resetAll}>Сбросить</Button>
            <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={saveTexts}>
              Сохранить
            </Button>
          </Space>
        )}
      />

      {error && <Alert type="error" showIcon style={{ marginBottom: 16 }} message={error}
        action={<Button size="small" onClick={() => void load()}>Повторить</Button>} />}

      <div className="grid-2">
        <Card title="Логотип">
          <div style={{
            display: 'grid', placeItems: 'center', minHeight: 150,
            background: 'var(--surface-2)', borderRadius: 14, padding: 18,
          }}>
            {data?.logoUrl
              ? <img src={data.logoUrl} alt="Логотип организации"
                  style={{ maxHeight: 110, maxWidth: '100%' }} />
              : <Logo size={46} name={branding.name} tagline={branding.tagline} />}
          </div>

          <Divider plain style={{ margin: '18px 0 14px' }}>
            {data?.logoUrl ? 'Загруженный логотип' : 'Стандартный знак'}
          </Divider>

          <Upload.Dragger
            accept="image/png,image/jpeg,image/webp,image/svg+xml"
            showUploadList={false}
            disabled={uploading}
            beforeUpload={(file) => { void uploadLogo(file as File); return false; }}
            style={{ borderRadius: 14 }}
          >
            <p className="ant-upload-drag-icon">
              {uploading ? <Spin /> : <InboxOutlined />}
            </p>
            <p className="ant-upload-text">Перетащите логотип или нажмите</p>
            <p className="ant-upload-hint">
              PNG, JPEG, WebP или SVG · до {Math.round((data?.maxLogoBytes ?? 524288) / 1024)} КБ ·
              рекомендуется горизонтальный логотип с прозрачным фоном
            </p>
          </Upload.Dragger>

          <Space style={{ marginTop: 12 }} wrap>
            <Button danger icon={<DeleteOutlined />} disabled={!data?.logoUrl} onClick={removeLogo}>
              Удалить логотип
            </Button>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              <PictureOutlined /> Логотип применяется в шапке, на входе, в favicon и в PDF
            </Typography.Text>
          </Space>
        </Card>

        <Card title="Название и подпись">
          <Form form={form} layout="vertical">
            <Form.Item
              name="name"
              label="Название организации"
              rules={[{ required: true, message: 'Укажите название' }]}
              extra={`По умолчанию: ${data?.defaultName ?? 'Афина'}`}
            >
              <Input size="large" maxLength={60} placeholder="Например, НБД-Банк" />
            </Form.Item>
            <Form.Item
              name="tagline"
              label="Подпись под названием"
              extra={`По умолчанию: ${data?.defaultTagline ?? 'Документы · подпись · Госключ'}`}
            >
              <Input size="large" maxLength={80} placeholder="Документы · подпись · Госключ" />
            </Form.Item>
          </Form>

          <Alert
            type="info"
            showIcon
            message="Как это работает"
            description="Если загружен логотип, он показывается вместо стандартного знака и названия. Название и подпись используются, когда логотипа нет, а также в заголовке вкладки браузера."
          />
        </Card>
      </div>
    </>
  );
}
