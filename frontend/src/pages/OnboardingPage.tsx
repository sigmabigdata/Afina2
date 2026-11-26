import { useEffect, useState } from 'react';
import { App, Button, Card, Checkbox, Form, Input, Result, Spin, Steps, Typography } from 'antd';
import { IdcardOutlined, PhoneOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { api } from '../lib/api';
import { useApp, toDocumentItem } from '../lib/store';
import SignModal from '../components/SignModal';
import type { DocumentDto, DocumentItem } from '../lib/types';

interface ConsentState {
  configured: boolean;
  signed?: boolean;
  documentId?: number;
}

/**
 * Первый вход клиента: заполнение профиля и подписание согласия на обработку ПДн.
 * Пока согласие не подписано, загрузка документов закрыта.
 */
export default function OnboardingPage() {
  const { user, refreshMe, refreshDocuments } = useApp();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [step, setStep] = useState(0);
  const [saving, setSaving] = useState(false);
  const [consent, setConsent] = useState<ConsentState | null>(null);
  const [consentDoc, setConsentDoc] = useState<DocumentItem | null>(null);
  const [signOpen, setSignOpen] = useState(false);

  useEffect(() => {
    if (user?.profileComplete) {
      setStep(1);
    }
  }, [user?.profileComplete]);

  const loadConsent = async () => {
    try {
      const state = await api<ConsentState>('/auth/consent');
      setConsent(state);
      if (state.signed) {
        navigate('/dashboard', { replace: true });
        return;
      }
      if (state.documentId) {
        const dto = await api<DocumentDto>(`/documents/${state.documentId}`);
        setConsentDoc(toDocumentItem(dto));
      }
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'Не удалось подготовить согласие');
    }
  };

  const handleProfile = async (values: { fullName: string; phone: string }) => {
    setSaving(true);
    try {
      await api('/auth/profile', { method: 'POST', body: values });
      await refreshMe();
      message.success('Профиль сохранён');
      setStep(1);
      await loadConsent();
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'Не удалось сохранить профиль');
    } finally {
      setSaving(false);
    }
  };

  if (!user) return <Spin />;

  return (
    <div style={{ maxWidth: 720, margin: '40px auto', padding: '0 16px' }}>
      <Card>
        <Typography.Title level={3} style={{ marginBottom: 4 }}>Добро пожаловать в Афину</Typography.Title>
        <Typography.Paragraph type="secondary">
          Осталось два шага: подтвердите свои данные и подпишите согласие на обработку
          персональных данных.
        </Typography.Paragraph>

        <Steps
          current={step}
          size="small"
          style={{ margin: '22px 0 26px' }}
          items={[
            { title: 'Профиль' },
            { title: 'Согласие на обработку ПДн' },
          ]}
        />

        {step === 0 && (
          <Form layout="vertical" onFinish={handleProfile}
            initialValues={{ fullName: user.name, phone: user.raw.phone }}>
            <Form.Item
              name="fullName"
              label="ФИО"
              rules={[{ required: true, message: 'Укажите ФИО полностью' }]}
              extra="Как в паспорте: фамилия, имя и отчество"
            >
              <Input size="large" prefix={<IdcardOutlined />} placeholder="Иванов Иван Иванович" />
            </Form.Item>
            <Form.Item
              name="phone"
              label="Мобильный телефон"
              rules={[{ required: true, message: 'Укажите телефон' }]}
              extra="Нужен для связи и подписания через «Госключ»"
            >
              <Input size="large" prefix={<PhoneOutlined />} placeholder="+7 900 000-00-00" />
            </Form.Item>
            <Button type="primary" size="large" htmlType="submit" loading={saving}>
              Сохранить и продолжить
            </Button>
          </Form>
        )}

        {step === 1 && (
          <div>
            {consent === null && (
              <div style={{ textAlign: 'center', padding: 24 }}><Spin /></div>
            )}
            {consent && !consent.configured && (
              <Result
                status="success"
                title="Профиль заполнен"
                subTitle="Согласие на обработку персональных данных не настроено администратором — можно продолжать."
                extra={<Button type="primary" onClick={() => navigate('/dashboard')}>В кабинет</Button>}
              />
            )}
            {consent && consent.configured && !consent.signed && (
              <>
                <Typography.Paragraph>
                  Документ согласия сформирован по шаблону банка. Подпишите его своей
                  УКЭП — без этого нельзя загружать документы.
                </Typography.Paragraph>
                <Checkbox defaultChecked disabled style={{ marginBottom: 16 }}>
                  Согласие на обработку персональных данных
                </Checkbox>
                <div>
                  <Button
                    type="primary"
                    size="large"
                    icon={<SafetyCertificateOutlined />}
                    disabled={!consentDoc}
                    onClick={() => setSignOpen(true)}
                  >
                    Подписать согласие
                  </Button>
                </div>
              </>
            )}
          </div>
        )}
      </Card>

      <SignModal
        doc={consentDoc}
        open={signOpen}
        onClose={async () => {
          setSignOpen(false);
          await refreshMe();
          await refreshDocuments();
          await loadConsent();
        }}
      />
    </div>
  );
}
