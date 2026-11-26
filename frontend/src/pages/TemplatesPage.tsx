import { useMemo, useState } from 'react';
import {
  App, Button, Card, Empty, Form, Input, InputNumber, Modal, Select, Tag, Typography,
} from 'antd';
import { FileAddOutlined, FileTextOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import PageHeader from '../components/PageHeader';
import { useApp, type TemplateRow } from '../lib/store';
import type { TemplateField } from '../lib/types';

function parseFields(row: TemplateRow): TemplateField[] {
  const raw = row.raw.fieldsJson;
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed
      .filter((f) => f && typeof f.key === 'string')
      .map((f) => ({
        key: String(f.key),
        label: String(f.label ?? f.key),
        type: String(f.type ?? 'text'),
        required: Boolean(f.required),
        defaultValue: f.defaultValue ?? f.default,
      }));
  } catch {
    return [];
  }
}

export default function TemplatesPage() {
  const { templates, folders, generateFromTemplate } = useApp();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [active, setActive] = useState<TemplateRow | null>(null);
  const [busy, setBusy] = useState(false);
  const [form] = Form.useForm();

  const fields = useMemo(() => (active ? parseFields(active) : []), [active]);

  const open = (row: TemplateRow) => {
    setActive(row);
    const initial: Record<string, string> = {};
    parseFields(row).forEach((f) => { initial[f.key] = f.defaultValue ?? ''; });
    form.setFieldsValue({ ...initial, folderId: folders[0]?.id });
  };

  const submit = async () => {
    if (!active) return;
    const values = await form.validateFields();
    const { folderId, ...rest } = values as Record<string, string> & { folderId?: number };
    setBusy(true);
    try {
      const doc = await generateFromTemplate(active.id, rest as Record<string, string>, folderId);
      message.success('Документ сформирован');
      setActive(null);
      navigate(`/documents/${doc.id}`);
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'Не удалось сформировать документ');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader title="Шаблоны" subtitle="Готовые формы: заполните поля — получите PDF" />
      {templates.length === 0 ? (
        <Card><Empty description="Шаблоны ещё не настроены администратором" /></Card>
      ) : (
        <div className="grid-3">
          {templates.map((t) => (
            <Card key={t.id} hoverable>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                <div style={{
                  width: 44, height: 44, borderRadius: 13, background: 'rgba(79,70,229,.1)',
                  color: '#4f46e5', display: 'grid', placeItems: 'center', fontSize: 20,
                }}>
                  {t.kind === 'consent' ? <SafetyCertificateOutlined /> : <FileTextOutlined />}
                </div>
                <Tag bordered={false} color={t.kind === 'consent' ? 'purple' : 'blue'}
                  style={{ borderRadius: 999 }}>
                  {t.kind === 'consent' ? 'Согласие' : 'Документ'}
                </Tag>
              </div>
              <Typography.Title level={4} style={{ marginTop: 14, marginBottom: 4 }}>
                {t.name}
              </Typography.Title>
              <Typography.Paragraph type="secondary" style={{ minHeight: 44 }}>
                {t.description}
              </Typography.Paragraph>
              <div style={{ color: 'var(--muted)', fontSize: 12, marginBottom: 14 }}>
                {t.fields} полей
              </div>
              <Button type="primary" block icon={<FileAddOutlined />} onClick={() => open(t)}>
                Сформировать
              </Button>
            </Card>
          ))}
        </div>
      )}

      <Modal
        open={!!active}
        title={active ? `Формирование: ${active.name}` : ''}
        onCancel={() => setActive(null)}
        onOk={submit}
        okText="Сформировать PDF"
        confirmLoading={busy}
        destroyOnHidden
        width={560}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="folderId" label="Раздел" rules={[{ required: true, message: 'Выберите раздел' }]}>
            <Select options={folders.map((f) => ({ value: f.id, label: f.name }))} />
          </Form.Item>
          {fields.map((f) => (
            <Form.Item
              key={f.key}
              name={f.key}
              label={f.label}
              rules={f.required ? [{ required: true, message: `Заполните «${f.label}»` }] : []}
            >
              {f.type === 'textarea' ? (
                <Input.TextArea rows={3} />
              ) : f.type === 'number' ? (
                <InputNumber style={{ width: '100%' }} />
              ) : f.type === 'date' ? (
                <Input placeholder="ДД.ММ.ГГГГ" />
              ) : (
                <Input />
              )}
            </Form.Item>
          ))}
          {fields.length === 0 && (
            <Typography.Paragraph type="secondary">
              В шаблоне не описаны поля — документ будет создан из статического текста.
            </Typography.Paragraph>
          )}
        </Form>
      </Modal>
    </>
  );
}
