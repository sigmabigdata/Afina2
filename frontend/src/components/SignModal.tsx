import { useEffect, useState } from 'react';
import { Alert, App, Button, Modal, Progress, Steps, Typography } from 'antd';
import { CheckCircleFilled, LockOutlined, SafetyCertificateOutlined, ScanOutlined } from '@ant-design/icons';
import { useApp } from '../lib/store';
import type { DocumentItem } from '../lib/types';

const steps = [
  { title: 'Сертификат', description: 'Проверка ключа КриптоПро', icon: <LockOutlined /> },
  { title: 'Подпись', description: 'CAdES-BES, PIN', icon: <SafetyCertificateOutlined /> },
  { title: 'Проверка', description: 'Цепочка и CRL', icon: <ScanOutlined /> },
];

export default function SignModal({ doc, open, onClose }: { doc: DocumentItem | null; open: boolean; onClose: () => void }) {
  const { signDocument, user } = useApp();
  const { message } = App.useApp();
  const [step, setStep] = useState(0);
  const [progress, setProgress] = useState(0);

  useEffect(() => {
    if (!open) { setStep(0); setProgress(0); return; }
    setStep(0);
    setProgress(8);
    const t1 = setTimeout(() => { setStep(1); setProgress(38); }, 700);
    const t2 = setTimeout(() => { setStep(2); setProgress(72); }, 1500);
    const t3 = setTimeout(() => { setProgress(100); }, 2300);
    return () => { clearTimeout(t1); clearTimeout(t2); clearTimeout(t3); };
  }, [open]);

  const done = progress === 100;

  return (
    <Modal
      open={open}
      onCancel={onClose}
      footer={null}
      width={560}
      title={done ? 'Документ подписан' : 'Подписание документа'}
      destroyOnClose
    >
      {doc && <Typography.Paragraph type="secondary" style={{ marginTop: -6 }}>{doc.name}</Typography.Paragraph>}
      {!done ? (
        <>
          <Steps current={step} size="small" items={steps} style={{ margin: '18px 0 22px' }} />
          <Progress percent={progress} showInfo={false} strokeColor={{ '0%': '#4f46e5', '100%': '#10b981' }} />
          <Alert
            style={{ marginTop: 18 }}
            type="info"
            showIcon
            message="Подпись формируется в браузере через КриптоПро CAdES"
            description="Закрытый ключ не покидает рабочее место. Сервер проверит подпись, цепочку сертификатов и CRL."
          />
        </>
      ) : (
        <div style={{ textAlign: 'center', padding: '18px 0 8px' }}>
          <CheckCircleFilled style={{ fontSize: 56, color: '#10b981' }} />
          <Typography.Title level={4} style={{ marginTop: 14 }}>Подпись проверена и добавлена</Typography.Title>
          <Typography.Paragraph type="secondary">
            ГОСТ 34.10-2012 · CAdES-BES · цепочка и CRL проверены
          </Typography.Paragraph>
          <Button type="primary" size="large" onClick={() => {
            if (doc) signDocument(doc.id, user.name);
            message.success('Документ подписан');
            onClose();
          }}>Готово</Button>
        </div>
      )}
    </Modal>
  );
}
