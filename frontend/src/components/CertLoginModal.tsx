import { useCallback, useEffect, useState } from 'react';
import { Alert, App, Button, Input, Modal, Select, Space, Tag, Typography } from 'antd';
import { CloseCircleFilled, SafetyCertificateOutlined } from '@ant-design/icons';
import { api } from '../lib/api';
import {
  CryptoProError, isCryptoProAvailable, listCertificates, signDetached, toBase64,
  type CertificateInfo,
} from '../lib/cryptopro';
import { useApp } from '../lib/store';

type Phase = 'loading' | 'ready' | 'signing' | 'error';

/**
 * Вход сотрудника/администратора по сертификату УКЭП.
 *
 * Сервер выдаёт одноразовый челлендж; плагин КриптоПро подписывает его
 * отсоединённой CAdES-BES; сервер проверяет подпись и сверяет CN/СНИЛС
 * с карточкой сотрудника.
 */
export default function CertLoginModal({
  open, onClose,
}: { open: boolean; onClose: () => void }) {
  const { refreshMe } = useApp();
  const { message } = App.useApp();
  const [phase, setPhase] = useState<Phase>('loading');
  const [certs, setCerts] = useState<CertificateInfo[]>([]);
  const [selected, setSelected] = useState<string>();
  const [manualSnils, setManualSnils] = useState('');
  const [error, setError] = useState<string>();

  const init = useCallback(async () => {
    setPhase('loading');
    setError(undefined);
    try {
      const available = await isCryptoProAvailable();
      if (!available) {
        throw new CryptoProError(
          'Плагин КриптоПро ЭЦП не найден. Установите «КриптоПро ЭЦП Browser plug-in» '
          + 'и КриптоПро CSP.',
        );
      }
      const list = (await listCertificates()).filter((c) => c.hasPrivateKey);
      setCerts(list);
      if (list.length === 0) {
        throw new CryptoProError('Нет сертификатов с закрытым ключом в хранилище «Личные».');
      }
      setSelected(list[0].thumbprint);
      setPhase('ready');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Ошибка плагина КриптоПро');
      setPhase('error');
    }
  }, []);

  useEffect(() => {
    if (open) void init();
  }, [open, init]);

  const handleLogin = async () => {
    const cert = certs.find((c) => c.thumbprint === selected);
    if (!cert || !selected) return;
    // СНИЛС обычно есть в сертификате; если УЦ его не включил — сотрудник
    // вводит его сам, сервер сверит с admins.env
    const snils = cert.snils || manualSnils.replace(/\D/g, '');
    if (!snils || snils.length !== 11) {
      setError('В сертификате нет СНИЛС. Введите 11 цифр СНИЛС вручную.');
      setPhase('error');
      return;
    }
    setPhase('signing');
    setError(undefined);
    try {
      const { challenge } = await api<{ challenge: string }>('/auth/admin/challenge');
      const signature = await signDetached(toBase64(challenge), selected);
      await api('/auth/admin/cert-login', {
        method: 'POST',
        body: {
          cn: cert.cn,
          snils,
          challenge,
          signatureBase64: signature,
        },
      });
      await refreshMe();
      message.success(`Добро пожаловать, ${cert.cn}`);
      window.location.assign('/dashboard');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось войти по сертификату');
      setPhase('error');
    }
  };

  return (
    <Modal open={open} onCancel={onClose} footer={null} width={560}
      title="Вход по сертификату" destroyOnHidden>
      {phase === 'loading' && (
        <Alert type="info" showIcon message="Поиск сертификатов…"
          description="Обращаемся к плагину КриптоПро на рабочем месте." />
      )}

      {phase === 'ready' && (
        <Space direction="vertical" size={14} style={{ width: '100%' }}>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
            Выберите сертификат сотрудника. Плагин попросит PIN-код носителя.
          </Typography.Paragraph>
          <Select
            style={{ width: '100%' }}
            value={selected}
            onChange={setSelected}
            options={certs.map((c) => ({
              value: c.thumbprint,
              label: c.cn,
            }))}
          />
          {(() => {
            const cert = certs.find((c) => c.thumbprint === selected);
            if (!cert) return null;
            return (
              <Space wrap>
                {cert.snils
                  ? <Tag color="success">СНИЛС ···{cert.snils.slice(-4)}</Tag>
                  : <Tag color="error">Нет СНИЛС</Tag>}
                {cert.validTo && (
                  <Tag bordered={false}>
                    до {new Date(cert.validTo).toLocaleDateString('ru-RU')}
                  </Tag>
                )}
              </Space>
            );
          })()}
          {(() => {
            const cert = certs.find((c) => c.thumbprint === selected);
            if (!cert || cert.snils) return null;
            return (
              <div>
                <Typography.Text strong>СНИЛС для сверки с карточкой сотрудника</Typography.Text>
                <Input
                  style={{ marginTop: 6 }}
                  placeholder="11 цифр, например 18962570627"
                  value={manualSnils}
                  maxLength={14}
                  onChange={(e) => setManualSnils(e.target.value)}
                />
              </div>
            );
          })()}
          <Button type="primary" size="large" block icon={<SafetyCertificateOutlined />}
            onClick={handleLogin}>
            Подписать и войти
          </Button>
        </Space>
      )}

      {phase === 'signing' && (
        <Alert type="info" showIcon message="Ожидание подписи"
          description="Введите PIN-код в окне КриптоПро и подтвердите операцию." />
      )}

      {phase === 'error' && (
        <Space direction="vertical" size={14} style={{ width: '100%' }}>
          <div style={{ textAlign: 'center' }}>
            <CloseCircleFilled style={{ fontSize: 44, color: '#ef4444' }} />
          </div>
          <Alert type="error" showIcon message="Войти не удалось" description={error} />
          <Space>
            <Button onClick={init}>Повторить</Button>
            <Button type="primary" onClick={onClose}>Закрыть</Button>
          </Space>
        </Space>
      )}
    </Modal>
  );
}
