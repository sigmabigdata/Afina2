import { Button, Result } from 'antd';
import { useNavigate } from 'react-router-dom';

export default function NotFoundPage() {
  const navigate = useNavigate();
  return <Result status="404" title="Страница не найдена" subTitle="Возможно, она была перемещена"
    extra={<Button type="primary" onClick={() => navigate('/dashboard')}>На главную</Button>} />;
}
