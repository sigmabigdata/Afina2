import { useEffect, useMemo, useState } from 'react';
import { Avatar, Badge, Button, Dropdown, Menu, Tooltip } from 'antd';
import {
  AppstoreOutlined, BgColorsOutlined, AuditOutlined, BellOutlined, DashboardOutlined, DatabaseOutlined,
  FileDoneOutlined, FileTextOutlined, LockOutlined, LogoutOutlined, MenuFoldOutlined,
  MenuUnfoldOutlined, MonitorOutlined, MoonOutlined, SafetyCertificateOutlined,
  SearchOutlined, SettingOutlined, SolutionOutlined, SunOutlined,
  TeamOutlined, ThunderboltOutlined, UserOutlined,
} from '@ant-design/icons';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useApp } from '../lib/store';
import CommandPalette from '../components/CommandPalette';
import Logo from '../components/Logo';

const titles: Record<string, string> = {
  '/dashboard': 'Обзор',
  '/documents': 'Документы',
  '/templates': 'Шаблоны',
  '/gosklyuch': 'Госключ',
  '/profile': 'Профиль',
  '/admin/clients': 'Клиенты',
  '/admin/audit': 'Аудит',
  '/admin/crl': 'CRL и доверие',
  '/admin/settings': 'Настройки',
  '/admin/monitoring': 'Мониторинг',
  '/admin/gosklyuch': 'Заявки Госключа',
  '/admin/branding': 'Брендинг',
  '/admin/templates': 'Шаблоны админки',
  '/admin/poa': 'Доверенности',
  '/admin/backups': 'Резервные копии',
};

export default function AppLayout() {
  const { user: currentUser, dark, toggleTheme, logout, branding } = useApp();
  const [collapsed, setCollapsed] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [cmdkOpen, setCmdkOpen] = useState(false);
  const location = useLocation();
  const navigate = useNavigate();

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setCmdkOpen(true);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  useEffect(() => {
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  }, [dark]);

  const selected = useMemo(() => {
    const all = ['/dashboard', '/documents', '/templates', '/gosklyuch', '/profile',
      '/admin/clients', '/admin/audit', '/admin/crl', '/admin/settings',
      '/admin/monitoring', '/admin/gosklyuch', '/admin/templates', '/admin/poa',
      '/admin/backups'];
    const match = all.filter((p) => location.pathname.startsWith(p)).sort((a, b) => b.length - a.length)[0];
    return [match ?? '/dashboard'];
  }, [location.pathname]);

  // Layout рендерится под RequireAuth; запасной объект — только для типов
  const user = currentUser ?? {
    id: 0, name: '', email: '', role: 'client' as const,
    profileComplete: true, consentSigned: true, raw: {} as never,
  };

  const title = titles[selected[0]] ?? 'Афина';

  const menuItems = [
    {
      type: 'group' as const,
      label: <span className="nav-section">Рабочее пространство</span>,
      children: [
        { key: '/dashboard', icon: <DashboardOutlined />, label: <Link to="/dashboard">Обзор</Link> },
        { key: '/documents', icon: <FileTextOutlined />, label: <Link to="/documents">Документы</Link> },
        { key: '/templates', icon: <AppstoreOutlined />, label: <Link to="/templates">Шаблоны</Link> },
        { key: '/gosklyuch', icon: <SafetyCertificateOutlined />, label: <Link to="/gosklyuch">Госключ</Link> },
      ],
    },
    ...(user.role === 'admin' ? [{
      type: 'group' as const,
      label: <span className="nav-section">Администрирование</span>,
      children: [
        { key: '/admin/clients', icon: <TeamOutlined />, label: <Link to="/admin/clients">Клиенты</Link> },
        { key: '/admin/gosklyuch', icon: <ThunderboltOutlined />, label: <Link to="/admin/gosklyuch">Заявки Госключа</Link> },
        { key: '/admin/templates', icon: <FileDoneOutlined />, label: <Link to="/admin/templates">Шаблоны</Link> },
        { key: '/admin/poa', icon: <SolutionOutlined />, label: <Link to="/admin/poa">Доверенности</Link> },
        { key: '/admin/audit', icon: <AuditOutlined />, label: <Link to="/admin/audit">Аудит</Link> },
        { key: '/admin/crl', icon: <LockOutlined />, label: <Link to="/admin/crl">CRL и доверие</Link> },
        { key: '/admin/monitoring', icon: <MonitorOutlined />, label: <Link to="/admin/monitoring">Мониторинг</Link> },
        { key: '/admin/backups', icon: <DatabaseOutlined />, label: <Link to="/admin/backups">Резервные копии</Link> },
        { key: '/admin/branding', icon: <BgColorsOutlined />, label: <Link to="/admin/branding">Брендинг</Link> },
        { key: '/admin/settings', icon: <SettingOutlined />, label: <Link to="/admin/settings">Настройки</Link> },
      ],
    }] : []),
  ];

  return (
    <div className="app-shell">
      <aside className={`sidebar ${mobileOpen ? 'open' : ''}`}>
        <Link to="/dashboard" className="brand" onClick={() => setMobileOpen(false)}>
          <Logo
            size={collapsed ? 34 : 38}
            collapsed={collapsed}
            name={branding.name}
            tagline={branding.tagline}
            src={branding.logoUrl}
          />
        </Link>

        <div className="nav-scroll">
          <Menu
            mode="inline"
            theme={dark ? 'dark' : 'light'}
            selectedKeys={selected}
            items={menuItems}
            inlineCollapsed={collapsed}
            style={{ background: 'transparent', borderInlineEnd: 'none' }}
            onClick={() => setMobileOpen(false)}
          />
        </div>

        <div className="sidebar-footer">
          <div className="user-chip">
            <Avatar style={{ background: 'linear-gradient(135deg,#4f46e5,#8b5cf6)' }}>
              {user.name.split(' ').map((p) => p[0]).slice(0, 2).join('')}
            </Avatar>
            {!collapsed && (
              <div style={{ minWidth: 0 }}>
                <div style={{ fontWeight: 700, fontSize: 13, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{user.name}</div>
                <div style={{ color: 'var(--muted)', fontSize: 11 }}>{user.role === 'admin' ? 'Администратор' : 'Клиент'}</div>
              </div>
            )}
          </div>
        </div>
      </aside>

      <div className="main">
        <header className="topbar">
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0 }}>
            <Button
              type="text"
              icon={mobileOpen ? <MenuFoldOutlined /> : <MenuUnfoldOutlined />}
              onClick={() => (window.innerWidth <= 900 ? setMobileOpen((v) => !v) : setCollapsed((v) => !v))}
              aria-label="Меню"
            />
            <div className="topbar-title">{title}</div>
          </div>
          <div className="topbar-actions">
            <button type="button" className="search-trigger" onClick={() => setCmdkOpen(true)} aria-label="Поиск">
              <SearchOutlined />
              <span>Поиск по документам и клиентам</span>
              <kbd>⌘K</kbd>
            </button>
            <Tooltip title={dark ? 'Светлая тема' : 'Тёмная тема'}>
              <Button type="text" icon={dark ? <SunOutlined /> : <MoonOutlined />} onClick={toggleTheme} aria-label="Сменить тему" />
            </Tooltip>
            <Badge count={3} size="small">
              <Button type="text" icon={<BellOutlined />} aria-label="Уведомления" />
            </Badge>
            <Dropdown
              menu={{
                items: [
                  { key: 'profile', icon: <UserOutlined />, label: 'Профиль', onClick: () => navigate('/profile') },
                  { key: 'logout', icon: <LogoutOutlined />, label: 'Выйти', onClick: () => { void logout(); } },
                ],
              }}
              placement="bottomRight"
            >
              <Avatar style={{ cursor: 'pointer', background: 'linear-gradient(135deg,#4f46e5,#8b5cf6)' }}>
                {user.name.split(' ').map((p) => p[0]).slice(0, 2).join('')}
              </Avatar>
            </Dropdown>
          </div>
        </header>

        <main className="page">
          <Outlet />
        </main>
      </div>

      <CommandPalette open={cmdkOpen} onClose={() => setCmdkOpen(false)} />
    </div>
  );
}
