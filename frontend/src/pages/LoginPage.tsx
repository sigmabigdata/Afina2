import { useState } from 'react';
import { App, Button, Card, Divider, Form, Input, Typography } from 'antd';
import { ArrowRightOutlined, LockOutlined, MailOutlined, SafetyCertificateOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';

export default function LoginPage() {
  const navigate = useNavigate();
  const { message } = App.useApp();
  const [sent, setSent] = useState(false);

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
              <div className="feature-item"><SafetyCertificateOutlined style={{ fontSize: 18 }} /> УКЭП через КриптоПро CAdES — ключ не покидает рабочее место</div>
              <div className="feature-item"><LockOutlined style={{ fontSize: 18 }} /> Проверка цепочки, CRL и подписи на сервере</div>
              <div className="feature-item"><ArrowRightOutlined style={{ fontSize: 18 }} /> Пакетная отправка в «Госключ» до 15 документов</div>
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
            Ссылка для входа придёт на вашу почту. Администраторы входят по сертификату.
          </Typography.Paragraph>

          <Form layout="vertical" onFinish={() => { setSent(true); message.success('Ссылка отправлена'); }}>
            <Form.Item name="email" label="Рабочая почта" rules={[{ required: true, type: 'email' }]}>
              <Input size="large" prefix={<MailOutlined />} placeholder="you@company.ru" autoComplete="email" />
            </Form.Item>
            <Button type="primary" htmlType="submit" size="large" block icon={<ArrowRightOutlined />}>
              Получить ссылку
            </Button>
          </Form>

          {sent && (
            <Card size="small" style={{ marginTop: 16, background: 'var(--surface-2)' }}>
              Если адрес зарегистрирован, письмо уже в пути. Проверьте «Спам».
            </Card>
          )}

          <Divider plain style={{ margin: '26px 0 18px' }}>или</Divider>

          <Button size="large" block icon={<SafetyCertificateOutlined />}
            onClick={() => { navigate('/dashboard'); }}>
            Войти по сертификату (админ)
          </Button>
          <Button type="text" block style={{ marginTop: 8 }}
            onClick={() => navigate('/dashboard')}>
            Демо-режим · посмотреть интерфейс
          </Button>
        </div>
      </div>
    </div>
  );
}
