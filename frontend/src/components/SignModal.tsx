import { useCallback, useEffect, useState } from 'react';
import {
  Alert, App, Button, Modal, Progress, Select, Space, Steps, Tag, Typography,
} from 'antd';
import {
  CheckCircleFilled, CloseCircleFilled, LockOutlined, SafetyCertificateOutlined, ScanOutlined,
} from '@ant-design/icons';
import { useApp } from '../lib/store';
import {
  CryptoProError, isCryptoProAvailable, listCertificates, signDetached,
  type CertificateInfo,
} from '../lib/cryptopro';
import type { DocumentItem } from '../lib/types';

const STEPS = [
  { title: 'Сертификат', description: 'Ключ КриптоПро', icon: <LockOutlined /> },
  { title: 'Подпись', description: 'CAdES-BES', icon: <SafetyCertificateOutlined /> },
  { title: 'Проверка', description: 'Цепочка и CRL', icon: <ScanOutlined /> },
];

type Phase = 'certs' | 'ready' | 'signing' | 'verifying' | 'done' | 'error';

/** Base64 содержимого документа (тот же файл, что проверяет сервер). */
async function fetchContentBase64(documentId: number): Promise<string> {
  const response = await fetch(`/api/v1/documents/${documentId}/content`, {
    credentials: 'include',
  });
  if (!response.ok) throw new Error('Не удалось получить содержимое документа');
  const buffer = await response.arrayBuffer();
  const bytes = new Uint8Array(buffer);
  let binary = '';
  const CHUNK = 0x8000;
  for (let i = 0; i < bytes.length; i += CHUNK) {
    binary += String.fromCharCode(...bytes.subarray(i, i + CHUNK));
  }
  return btoa(binary);
}

export default function SignModal({
  doc, open, onClose,
}: { doc: DocumentItem | null; open: boolean; onClose: () => void }) {
  const { signDocument } = useApp();
  const { message } = App.useApp();
  const [phase, setPhase] = useState<Phase>('certs');
  const [certs, setCerts] = useState<CertificateInfo[]>([]);
  const [selected, setSelected] = useState<string>();
  const [error, setError] = useState<string>();
  const [warning, setWarning] = useState<string>();
  const [progress, setProgress] = useState(0);

  const loadCerts = useCallback(async () => {
    setPhase('certs');
    setError(undefined);
    setProgress(10);
    try {
      const available = await isCryptoProAvailable();
      if (!available) {
        throw new CryptoProError(
          'Плагин КриптоПро ЭЦП не найден. Установите «КриптоПро ЭЦП Browser plug-in» '
          + 'и КриптоПро CSP, затем перезапустите браузер.',
        );
      }
      const list = (await listCertificates()).filter((c) => c.hasPrivateKey);
      setCerts(list);
      if (list.length === 0) {
        throw new CryptoProError(
          'В хранилище «Личные» нет сертификатов с закрытым ключом. '
          + 'Подключите Рутокен или установите контейнер.',
        );
      }
      setSelected(list[0].thumbprint);
      setPhase('ready');
      setProgress(0);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Ошибка инициализации плагина');
      setPhase('error');
      setProgress(0);
    }
  }, []);

  useEffect(() => {
    if (!open) {
      setPhase('certs');
      setCerts([]);
      setSelected(undefined);
      setError(undefined);
      setWarning(undefined);
      setProgress(0);
      return;
    }
    void loadCerts();
  }, [open, loadCerts]);

  const handleSign = async () => {
    if (!doc || !selected) return;
    const cert = certs.find((c) => c.thumbprint === selected);
    setPhase('signing');
    setError(undefined);
    setProgress(35);
    try {
      const content = await fetchContentBase64(doc.id);
      setProgress(55);
      const signature = await signDetached(content, selected);
      setProgress(75);
      setPhase('verifying');
      const result = await signDocument(doc.id, signature);
      setProgress(100);
      if (typeof result.warning === 'string') setWarning(result.warning);
      setPhase('done');
      message.success('Документ подписан');
      if (cert) message.info(`Подписант: ${cert.cn}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось подписать документ');
      setPhase('error');
      setProgress(0);
    }
  };

  const busy = phase === 'signing' || phase === 'verifying';

  return (
    <Modal
      open={open}
      onCancel={() => { if (!busy) onClose(); }}
      footer={null}
      width={600}
      maskClosable={!busy}
      closable={!busy}
      title={phase === 'done' ? 'Документ подписан' : 'Подписание документа'}
      destroyOnHidden
    >
      {doc && (
        <Typography.Paragraph type="secondary" style={{ marginTop: -4 }}>
          {doc.name}
        </Typography.Paragraph>
      )}

      {phase !== 'done' && phase !== 'error' && (
        <>
          <Steps
            current={phase === 'signing' ? 1 : phase === 'verifying' ? 2 : 0}
            size="small"
            items={STEPS}
            style={{ margin: '16px 0 20px' }}
          />
          {busy && (
            <Progress
              percent={progress}
              showInfo={false}
              strokeColor={{ '0%': '#4f46e5', '100%': '#10b981' }}
            />
          )}
          {phase === 'certs' && (
            <Alert type="info" showIcon message="Поиск сертификатов КриптоПро…"
              description="Плагин ищет сертификаты с закрытым ключом в хранилище «Личные»." />
          )}
          {phase === 'ready' && (
            <Space direction="vertical" size={14} style={{ width: '100%', marginTop: 8 }}>
              <div>
                <Typography.Text strong>Сертификат подписи</Typography.Text>
                <Select
                  style={{ width: '100%', marginTop: 8 }}
                  value={selected}
                  onChange={setSelected}
                  options={certs.map((c) => ({
                    value: c.thumbprint,
                    label: (
                      <Space direction="vertical" size={0}>
                        <span style={{ fontWeight: 600 }}>{c.cn}</span>
                        <span style={{ fontSize: 12, color: 'var(--muted)' }}>
                          {c.issuer.split(',').slice(0, 2).join(', ')}
                        </span>
                      </Space>
                    ),
                  }))}
                />
              </div>
              {(() => {
                const cert = certs.find((c) => c.thumbprint === selected);
                if (!cert) return null;
                const expired = cert.validTo && new Date(cert.validTo) < new Date();
                return (
                  <Space wrap>
                    <Tag color={expired ? 'error' : 'success'}>
                      {expired ? 'Срок действия истёк' : 'Действителен'}
                    </Tag>
                    {cert.snils && <Tag>СНИЛС ···{cert.snils.slice(-4)}</Tag>}
                    {cert.validTo && (
                      <Tag bordered={false}>
                        до {new Date(cert.validTo).toLocaleDateString('ru-RU')}
                      </Tag>
                    )}
                  </Space>
                );
              })()}
              <Alert
                type="info"
                showIcon
                message="Ключ не покидает рабочее место"
                description="Подпись CAdES-BES формируется плагином КриптоПро. Сервер проверит её по ГОСТ 34.10-2012, построит цепочку и проверит CRL."
              />
              <Button type="primary" size="large" block icon={<SafetyCertificateOutlined />}
                onClick={handleSign}>
                Подписать документ
              </Button>
            </Space>
          )}
          {phase === 'signing' && (
            <Alert type="info" showIcon message="Формирование подписи"
              description="Подтвердите операцию и введите PIN-код носителя в окне КриптоПро." />
          )}
          {phase === 'verifying' && (
            <Alert type="info" showIcon message="Проверка на сервере"
              description="Проверяем подпись, цепочку сертификатов и списки отзыва (CRL)." />
          )}
        </>
      )}

      {phase === 'error' && (
        <Space direction="vertical" size={14} style={{ width: '100%' }}>
          <div style={{ textAlign: 'center', padding: '8px 0' }}>
            <CloseCircleFilled style={{ fontSize: 48, color: '#ef4444' }} />
          </div>
          <Alert type="error" showIcon message="Подписать не удалось" description={error} />
          <Space>
            <Button onClick={loadCerts}>Повторить</Button>
            <Button type="primary" onClick={onClose}>Закрыть</Button>
          </Space>
        </Space>
      )}

      {phase === 'done' && (
        <div style={{ textAlign: 'center', padding: '12px 0 4px' }}>
          <CheckCircleFilled style={{ fontSize: 56, color: '#10b981' }} />
          <Typography.Title level={4} style={{ marginTop: 14 }}>
            Подпись проверена и добавлена
          </Typography.Title>
          <Typography.Paragraph type="secondary">
            ГОСТ 34.10-2012 · CAdES-BES · цепочка и CRL проверены
          </Typography.Paragraph>
          {warning && (
            <Alert type="warning" showIcon style={{ textAlign: 'left', marginBottom: 12 }}
              message="Обратите внимание" description={warning} />
          )}
          <Button type="primary" size="large" onClick={onClose}>Готово</Button>
        </div>
      )}
    </Modal>
  );
}
