import { App, Button, Card, Form, Input, Select, Switch, Tabs } from 'antd';
import { LockOutlined, MailOutlined, SafetyCertificateOutlined, SettingOutlined } from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';

export default function SettingsPage() {
  const { message } = App.useApp();
  return (
    <>
      <PageHeader title="Настройки" subtitle="Параметры безопасности, почты и интеграций" />
      <Card>
        <Tabs
          items={[
            {
              key: 'security', label: <span><LockOutlined /> Безопасность</span>, children: (
                <Form layout="vertical" onFinish={() => message.success('Сохранено')} initialValues={{ mode: 'strict', requireCrl: true, csp: true }}>
                  <div className="grid-2">
                    <div>
                      <Form.Item name="mode" label="Режим проверки отзыва">
                        <Select options={[{ value: 'strict', label: 'Строгий (strict)' }, { value: 'soft', label: 'Мягкий (soft)' }]} />
                      </Form.Item>
                      <Form.Item name="requireCrl" label="Требовать проверку CRL" valuePropName="checked"><Switch /></Form.Item>
                      <Form.Item name="csp" label="Content-Security-Policy" valuePropName="checked"><Switch /></Form.Item>
                    </div>
                    <div>
                      <Form.Item label="Администраторы (IP whitelist)"><Input defaultValue="127.0.0.1, ::1" /></Form.Item>
                      <Form.Item label="TTL сессии, часов"><Input defaultValue="10" /></Form.Item>
                    </div>
                  </div>
                  <Button type="primary" htmlType="submit">Сохранить</Button>
                </Form>
              ),
            },
            {
              key: 'mail', label: <span><MailOutlined /> Почта</span>, children: (
                <Form layout="vertical" onFinish={() => message.success('Проверьте почту')}>
                  <div className="grid-2">
                    <Form.Item label="SMTP-хост"><Input defaultValue="mail.afinasystems.ru" /></Form.Item>
                    <Form.Item label="Порт"><Input defaultValue="25" /></Form.Item>
                    <Form.Item label="Отправитель"><Input defaultValue="noreply@afinasystems.ru" /></Form.Item>
                    <Form.Item label="Алерты"><Input defaultValue="security@nbdbank.ru" /></Form.Item>
                  </div>
                  <Button type="primary" htmlType="submit">Отправить тест</Button>
                </Form>
              ),
            },
            {
              key: 'gosklyuch', label: <span><SafetyCertificateOutlined /> Госключ</span>, children: (
                <Form layout="vertical" onFinish={() => message.success('Сохранено')} initialValues={{ enabled: false, provider: 'disabled' }}>
                  <div className="grid-2">
                    <Form.Item name="enabled" label="Интеграция включена" valuePropName="checked"><Switch /></Form.Item>
                    <Form.Item name="provider" label="Провайдер"><Select options={[{ value: 'disabled', label: 'disabled' }, { value: 'fake', label: 'fake (демо)' }, { value: 'epgu', label: 'epgu' }]} /></Form.Item>
                    <Form.Item label="Базовый URL API"><Input placeholder="https://…" /></Form.Item>
                    <Form.Item label="Код услуги"><Input placeholder="10000000374" /></Form.Item>
                  </div>
                  <Button type="primary" htmlType="submit">Сохранить</Button>
                </Form>
              ),
            },
            {
              key: 'system', label: <span><SettingOutlined /> Система</span>, children: (
                <div className="grid-2">
                  <Card size="small" title="Окружение"><div>prod · Astra Linux SE 1.8</div><div style={{ color: 'var(--muted)' }}>JCSP 5.0 · Axiom JDK 17</div></Card>
                  <Card size="small" title="Версия"><div>Afina2 0.1.0</div><div style={{ color: 'var(--muted)' }}>React 18 · Ant Design 5</div></Card>
                </div>
              ),
            },
          ]}
        />
      </Card>
    </>
  );
}
