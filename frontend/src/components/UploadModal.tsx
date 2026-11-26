import { useState } from 'react';
import { App, Button, Form, Input, Modal, Select, Upload } from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import { useApp } from '../lib/store';

export default function UploadModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { addDocument, folders } = useApp();
  const { message } = App.useApp();
  const [form] = Form.useForm();
  const [file, setFile] = useState<File | null>(null);

  const submit = async () => {
    const values = await form.validateFields();
    addDocument({
      id: Date.now(),
      name: file?.name ?? 'Новый документ.pdf',
      folder: values.folder,
      size: file ? `${(file.size / 1024 / 1024).toFixed(1)} МБ` : '—',
      uploadedAt: new Date().toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }),
      status: 'unsigned',
      type: file?.name.endsWith('.docx') ? 'docx' : 'pdf',
      signers: [],
    });
    message.success('Документ загружен');
    form.resetFields();
    setFile(null);
    onClose();
  };

  return (
    <Modal open={open} onCancel={onClose} title="Загрузить документ" footer={null} width={520} destroyOnClose>
      <Upload.Dragger
        beforeUpload={(f) => { setFile(f); return false; }}
        maxCount={1}
        style={{ borderRadius: 16, marginBottom: 16 }}
      >
        <p className="ant-upload-drag-icon"><InboxOutlined /></p>
        <p className="ant-upload-text">Перетащите файл или нажмите</p>
        <p className="ant-upload-hint">PDF, DOCX, XML, TXT · до 50 МБ</p>
      </Upload.Dragger>
      <Form form={form} layout="vertical" initialValues={{ folder: folders[0]?.name }}>
        <Form.Item name="folder" label="Раздел" rules={[{ required: true }]}>
          <Select options={folders.map((f) => ({ value: f.name, label: f.name }))} />
        </Form.Item>
        <Form.Item name="name" label="Название (необязательно)">
          <Input placeholder="Например, договор поставки" />
        </Form.Item>
        <Button type="primary" block size="large" onClick={submit}>Загрузить</Button>
      </Form>
    </Modal>
  );
}
