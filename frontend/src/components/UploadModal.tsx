import { useState } from 'react';
import { App, Button, Form, Modal, Select, Upload } from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import { useApp } from '../lib/store';

/**
 * Загрузка документа в раздел. Раздел можно создать прямо здесь —
 * у нового клиента разделов может не быть.
 */
export default function UploadModal({
  open, onClose, defaultFolderId,
}: { open: boolean; onClose: () => void; defaultFolderId?: number }) {
  const { folders, uploadDocument, refreshFolders } = useApp();
  const { message } = App.useApp();
  const [form] = Form.useForm();
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async () => {
    if (!file) {
      message.warning('Выберите файл');
      return;
    }
    const values = await form.validateFields();
    setBusy(true);
    try {
      await uploadDocument(file, values.folderId);
      message.success('Документ загружен');
      form.resetFields();
      setFile(null);
      onClose();
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'Не удалось загрузить документ');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal open={open} onCancel={onClose} title="Загрузить документ" footer={null}
      width={520} destroyOnHidden>
      <Upload.Dragger
        beforeUpload={(f) => { setFile(f); return false; }}
        onRemove={() => setFile(null)}
        maxCount={1}
        style={{ borderRadius: 16, marginBottom: 16 }}
      >
        <p className="ant-upload-drag-icon"><InboxOutlined /></p>
        <p className="ant-upload-text">Перетащите файл или нажмите</p>
        <p className="ant-upload-hint">PDF, DOCX, XML, TXT · до 50 МБ</p>
      </Upload.Dragger>
      <Form form={form} layout="vertical"
        initialValues={{ folderId: defaultFolderId ?? folders[0]?.id }}>
        <Form.Item name="folderId" label="Раздел" rules={[{ required: true, message: 'Выберите раздел' }]}>
          <Select
            placeholder="Выберите раздел"
            options={folders.map((f) => ({ value: f.id, label: f.name }))}
            notFoundContent="Разделов пока нет"
          />
        </Form.Item>
        <Button type="primary" block size="large" onClick={submit} loading={busy}>
          Загрузить
        </Button>
      </Form>
      <Button
        type="link"
        style={{ marginTop: 8, paddingLeft: 0 }}
        onClick={async () => {
          try {
            await refreshFolders();
            message.info('Список разделов обновлён. Создать раздел можно в админке или через API.');
          } catch {
            message.error('Не удалось обновить разделы');
          }
        }}
      >
        Обновить список разделов
      </Button>
    </Modal>
  );
}
