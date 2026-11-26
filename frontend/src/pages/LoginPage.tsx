import { useState } from 'react';
import { Alert, App, Button, Divider, Form, Input, Typography } from 'antd';
import {
  ArrowRightOutlined, LockOutlined, MailOutlined, SafetyCertificateOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useSearchParams } from 'react-router-dom';
import CertLoginModal from '../components/CertLoginModal';
import { useApp } from '../lib/store';

export default function LoginPage() {
  const { login } = useApp();
  const { message } = App.useApp();
  const [params] = useSearchParams();
  const [sent, setSent] = useState(false);
  const [sending, setSending] = useState(false);
  const [certOpen, setCertOpen] = useState(false);

  const tokenError = params.get('error') === 'token';
  const demoUrl = import.meta.env.VITE_DEMO_URL as string | undefined;

  const onFinish = async ({ email }: { email: string }) => {
    setSending(true);
    try {
      await login(email);
      setSent(true);
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'Не удалось отправить ссылку');
    } finally {
      setSending(false);
    }
  };

  return (
    <div className="auth-wrap">
      <div className="auth-left">
        <div>
          <div className="brand" style={{ padding: 0 }}>
            <div className="brand-mark">A</div>
            <div>
              <div style={{ fontWeight: 800, fontSize: 20 }}>Афина</div>
              <div style={{ opacity: .7, fontSize: 12 }}>Afina2 · Secure Workspace</div>
            </div>
          </div>
          <div style={{ marginTop: 72, maxWidth: 560 }}>
            <div className="auth-badge"><ThunderboltOutlined /> Новая версия · React + Ant Design</div>
            <h1 style={{ fontSize: 46, lineHeight: 1.08, letterSpacing: '-0.04em', margin: '22px 0 14px' }}>
              Документы, подпись и&nbsp;Госключ в одном пространстве
            </h1>
            <p style={{ fontSize: 16, opacity: .84, lineHeight: 1.6 }}>
              Современный кабинет для банковских документов: КриптоПро, ГОСТ, CRL,
              прозрачный аудит и удобная работа с клиентами.
            </p>
            <div className="feature-list">
              <div className="feature-item">
                <SafetyCertificateOutlined style={{ fontSize: 18 }} />
                УКЭП через КриптоПро CAdES — ключ не покидает рабочее место
              </div>
              <div className="feature-item">
                <LockOutlined style={{ fontSize: 18 }} />
                Проверка цепочки, CRL и подписи на сервере
              </div>
              <div className="feature-item">
                <ArrowRightOutlined style={{ fontSize: 18 }} />
                Пакетная отправка в «Госключ» до 15 документов
              </div>
            </div>
          </div>
        </div>
        <div style={{ opacity: .55, fontSize: 12 }}>
          Astra Linux SE · Axiom JDK Pro · PostgreSQL · Angie · КриптоПро
        </div>
      </div>

      <div className="auth-right">
        <div className="auth-card">
          <Typography.Title level={3} style={{ marginBottom: 4 }}>Вход в сервис</Typography.Title>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 24 }}>
            Ссылка для входа придёт на вашу почту. Сотрудники входят по сертификату.
          </Typography.Paragraph>

          {tokenError && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message="Ссылка недействительна или истекла"
              description="Запросите новую ссылку для входа."
            />
          )}

          <Form layout="vertical" onFinish={onFinish}>
            <Form.Item
              name="email"
              label="Рабочая почта"
              rules={[{ required: true, type: 'email', message: 'Укажите корректный e-mail' }]}
            >
              <Input size="large" prefix={<MailOutlined />} placeholder="you@company.ru"
                autoComplete="email" />
            </Form.Item>
            <Button type="primary" htmlType="submit" size="large" block
              loading={sending} icon={<ArrowRightOutlined />}>
              Получить ссылку
            </Button>
          </Form>

          {sent && (
            <Alert
              type="success"
              showIcon
              style={{ marginTop: 16 }}
              message="Проверьте почту"
              description="Если адрес зарегистрирован, ссылка для входа уже отправлена. Проверьте папку «Спам»."
            />
          )}

          <Divider plain style={{ margin: '26px 0 18px' }}>или</Divider>

          <Button size="large" block icon={<SafetyCertificateOutlined />}
            onClick={() => setCertOpen(true)}>
            Войти по сертификату
          </Button>
          {demoUrl && (
            <Button type="text" block style={{ marginTop: 8 }}
              onClick={() => window.location.assign(demoUrl)}>
              Демо-режим · посмотреть интерфейс
            </Button>
          )}
        </div>
      </div>

      <CertLoginModal open={certOpen} onClose={() => setCertOpen(false)} />
    </div>
  );
}
