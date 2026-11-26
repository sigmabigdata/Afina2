import { useCallback, useEffect, useState } from 'react';
import { Alert, App, Button, Input, Modal, Select, Space, Tag, Typography } from 'antd';
import { CloseCircleFilled, SafetyCertificateOutlined } from '@ant-design/icons';
import { api } from '../lib/api';
import {
  CryptoProError, diagnosePlugin, isCryptoProAvailable, listCertificates, signDetached,
  toBase64, type CertificateInfo, type PluginDiagnostics,
} from '../lib/cryptopro';
import { useApp } from '../lib/store';

type Phase = 'loading' | 'ready' | 'signing' | 'error';

/**
 * Плагин КриптоПро работает только в настольных Chrome/Edge/Firefox/Yandex.
 * В Safari (и на мобильных) его API недоступен — предупреждаем сразу,
 * иначе пользователь видит непонятную ошибку.
 */
function unsupportedBrowser(): string | null {
  if (typeof navigator === 'undefined') return null;
  const ua = navigator.userAgent;
  const isIOS = /iPad|iPhone|iPod/.test(ua);
  const isSafari = /Safari/.test(ua) && !/Chrome|Chromium|Edg|OPR|YaBrowser/.test(ua);
  if (isIOS) return 'На мобильных устройствах плагин КриптоПро не поддерживается. '
    + 'Откройте систему на компьютере в Chrome, Edge или Firefox.';
  if (isSafari) return 'Safari не поддерживает плагин КриптоПро. '
    + 'Откройте страницу в Chrome, Edge, Firefox или Яндекс.Браузере — вход по сертификату заработает.';
  return null;
}

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
  const [diag, setDiag] = useState<PluginDiagnostics | null>(null);
  const [diagBusy, setDiagBusy] = useState(false);
  const [waited, setWaited] = useState(0);

  const init = useCallback(async () => {
    setPhase('loading');
    setError(undefined);
    const browserIssue = unsupportedBrowser();
    if (browserIssue) {
      setError(browserIssue);
      setPhase('error');
      return;
    }
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

  // Счётчик ожидания: пользователь видит, что поиск идёт, а не «зависло»
  useEffect(() => {
    if (!open || phase !== 'loading') {
      setWaited(0);
      return undefined;
    }
    const timer = setInterval(() => setWaited((v: number) => v + 1), 1000);
    return () => clearInterval(timer);
  }, [open, phase]);

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
        <Alert
          type="info"
          showIcon
          message={`Поиск сертификатов КриптоПро…${waited > 0 ? ` (${waited} с)` : ''}`}
          description={waited >= 4
            ? 'Плагин долго не отвечает. Проверьте, что установлено расширение '
              + '«КриптоПро ЭЦП Browser plug-in» и подключён носитель с ключом.'
            : 'Обращаемся к плагину КриптоПро и читаем хранилище «Личные».'}
        />
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
          {diag && (
            <Alert
              type={diag.responds ? 'success' : 'warning'}
              showIcon
              message={diag.responds
                ? `Плагин работает${diag.version ? `, версия ${diag.version}` : ''}`
                : 'Плагин недоступен'}
              description={(
                <div style={{ fontSize: 12, lineHeight: 1.6 }}>
                  <div>Скрипт API: {diag.scriptLoaded ? 'загружен' : 'не загружен'}</div>
                  <div>Объект API: {diag.apiPresent ? 'есть' : 'нет'}</div>
                  <div>Ответ плагина: {diag.responds ? 'да' : 'нет'}</div>
                  <div>Хранилище «Личные»: {diag.storeOpened
                    ? 'открыто'
                    : (diag.storeOpened === false ? 'не открылось' : '—')}</div>
                  <div>Сертификатов в хранилище: {diag.certificatesCount ?? '—'}</div>
                  {diag.error && <div>Ошибка: {diag.error}</div>}
                  <div style={{ marginTop: 6 }}>
                    <a href="https://www.cryptopro.ru/products/cades/plugin"
                      target="_blank" rel="noreferrer">
                      Установить «КриптоПро ЭЦП Browser plug-in»
                    </a>
                  </div>
                </div>
              )}
            />
          )}
          <Space wrap>
            <Button onClick={init}>Повторить</Button>
            <Button loading={diagBusy} onClick={async () => {
              setDiagBusy(true);
              try {
                setDiag(await diagnosePlugin());
              } finally {
                setDiagBusy(false);
              }
            }}>
              Диагностика плагина
            </Button>
            <Button type="primary" onClick={onClose}>Закрыть</Button>
          </Space>
        </Space>
      )}
    </Modal>
  );
}
