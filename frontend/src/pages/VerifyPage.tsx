import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Card, Descriptions, Space, Spin, Tag, Typography } from 'antd';
import { CheckCircleFilled, CloseCircleFilled, SafetyCertificateOutlined } from '@ant-design/icons';
import { api } from '../lib/api';
import { formatDateTime } from '../lib/store';

interface SignatureInfo {
  subject?: string;
  serial?: string;
  signedAt?: string;
  applyPoa?: boolean;
  certNotAfter?: string;
  certValidNow?: boolean;
  cryptoOk?: boolean;
  cryptoMessage?: string;
  chainOk?: boolean;
  chainMessage?: string;
  poaFound?: boolean;
  poaStampNumber?: string;
  poaValidUntil?: string;
  poaEffective?: boolean;
}

interface PublicInfo {
  documentName: string;
  sizeBytes: number;
  uploadedAt: string;
  sha256?: string;
  signatures: SignatureInfo[];
  signatureCount: number;
  signed: boolean;
}

/** Публичная страница проверки подписи (открывается по QR-коду). */
export default function VerifyPage() {
  const { uuid } = useParams();
  const [info, setInfo] = useState<PublicInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();

  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (!uuid) {
        setError('Не указан идентификатор документа');
        setLoading(false);
        return;
      }
      try {
        const data = await api<PublicInfo>(`/public/info/${uuid}`);
        if (!cancelled) setInfo(data);
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : 'Документ не найден');
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [uuid]);

  const signature = info?.signatures?.[0];
  const allValid = Boolean(signature?.cryptoOk) && Boolean(signature?.chainOk);

  return (
    <div className="verify-wrap">
      <div className="verify-card">
        <div style={{ textAlign: 'center', marginBottom: 20 }}>
          <div className="brand-mark" style={{ margin: '0 auto 12px' }}>A</div>
          <Typography.Title level={3} style={{ margin: 0 }}>Проверка подписи документа</Typography.Title>
          <Typography.Text type="secondary">Публичная страница · Афина</Typography.Text>
        </div>

        <Card>
          {loading && (
            <div style={{ textAlign: 'center', padding: 40 }}><Spin size="large" /></div>
          )}

          {!loading && error && (
            <Space direction="vertical" align="center" style={{ width: '100%', padding: 30 }}>
              <CloseCircleFilled style={{ fontSize: 48, color: '#ef4444' }} />
              <Typography.Title level={4}>Документ не найден</Typography.Title>
              <Typography.Text type="secondary">{error}</Typography.Text>
            </Space>
          )}

          {!loading && info && (
            <>
              <div style={{ textAlign: 'center', padding: '8px 0 18px' }}>
                {allValid ? (
                  <>
                    <CheckCircleFilled style={{ fontSize: 52, color: '#10b981' }} />
                    <div style={{ fontWeight: 800, fontSize: 20, marginTop: 10 }}>
                      Подпись действительна
                    </div>
                    <div style={{ color: 'var(--muted)' }}>
                      Криптография, цепочка и CRL проверены
                    </div>
                  </>
                ) : (
                  <>
                    <CloseCircleFilled style={{ fontSize: 52, color: '#ef4444' }} />
                    <div style={{ fontWeight: 800, fontSize: 20, marginTop: 10 }}>
                      {info.signatureCount === 0 ? 'Документ не подписан' : 'Подпись требует проверки'}
                    </div>
                    <div style={{ color: 'var(--muted)' }}>
                      {info.signatureCount === 0
                        ? 'У документа нет действительных подписей'
                        : (signature?.chainMessage ?? signature?.cryptoMessage ?? 'Не удалось проверить')}
                    </div>
                  </>
                )}
              </div>

              <Descriptions column={1} items={[
                { key: 'doc', label: 'Документ', children: info.documentName },
                { key: 'uploaded', label: 'Загружен', children: formatDateTime(info.uploadedAt) },
                ...(signature?.subject ? [{
                  key: 'signer', label: 'Подписант', children: signature.subject,
                }] : []),
                ...(signature?.signedAt ? [{
                  key: 'signed', label: 'Подписан', children: formatDateTime(signature.signedAt),
                }] : []),
                ...(signature ? [{
                  key: 'crypto',
                  label: 'Криптопроверка',
                  children: signature.cryptoOk
                    ? <Tag color="success" icon={<CheckCircleFilled />}>УКЭП · ГОСТ 34.10-2012</Tag>
                    : <Tag color="error">Не подтверждена</Tag>,
                }, {
                  key: 'chain',
                  label: 'Цепочка и CRL',
                  children: signature.chainOk
                    ? <Tag color="success" icon={<SafetyCertificateOutlined />}>Проверены</Tag>
                    : <Tag color="warning">Не проверены</Tag>,
                }] : []),
                ...(signature?.certValidNow !== undefined ? [{
                  key: 'cert',
                  label: 'Срок действия сертификата',
                  children: signature.certValidNow
                    ? <Tag color="success">Действителен</Tag>
                    : <Tag color="error">Истёк</Tag>,
                }] : []),
                ...(signature?.poaFound ? [{
                  key: 'poa',
                  label: 'Доверенность',
                  children: (
                    <Tag color={signature.poaEffective ? 'purple' : 'error'}>
                      {signature.poaStampNumber ?? 'МЧД'}
                      {signature.poaValidUntil ? ` · до ${signature.poaValidUntil}` : ''}
                    </Tag>
                  ),
                }] : []),
                ...(info.sha256 ? [{
                  key: 'sha', label: 'SHA-256', children: (
                    <Typography.Text copyable={{ text: info.sha256 }} style={{ fontSize: 12 }}>
                      {info.sha256.slice(0, 32)}…
                    </Typography.Text>
                  ),
                }] : []),
              ]} />

              <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: 18 }}>
                Проверка выполняется по криптографическому содержимому подписи. Юридически
                значимая проверка — в аккредитованном удостоверяющем центре или на Госуслугах.
              </Typography.Paragraph>
            </>
          )}
        </Card>
      </div>
    </div>
  );
}
