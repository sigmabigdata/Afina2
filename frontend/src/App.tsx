import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { Spin } from 'antd';
import AppLayout from './layouts/AppLayout';
import LoginPage from './pages/LoginPage';
import VerifyPage from './pages/VerifyPage';
import DashboardPage from './pages/DashboardPage';
import DocumentsPage from './pages/DocumentsPage';
import DocumentPage from './pages/DocumentPage';
import TemplatesPage from './pages/TemplatesPage';
import GosklyuchPage from './pages/GosklyuchPage';
import ProfilePage from './pages/ProfilePage';
import OnboardingPage from './pages/OnboardingPage';
import AdminOnly from './components/AdminOnly';
import ClientsPage from './pages/admin/ClientsPage';
import ClientCardPage from './pages/admin/ClientCardPage';
import AuditPage from './pages/admin/AuditPage';
import CrlPage from './pages/admin/CrlPage';
import SettingsPage from './pages/admin/SettingsPage';
import MonitoringPage from './pages/admin/MonitoringPage';
import AdminGosklyuchPage from './pages/admin/AdminGosklyuchPage';
import NotFoundPage from './pages/NotFoundPage';
import { useApp } from './lib/store';

/** Экран загрузки сессии. */
function BootScreen() {
  return (
    <div style={{
      display: 'grid', placeItems: 'center', minHeight: '100vh',
      background: 'var(--bg)',
    }}>
      <Spin size="large" />
    </div>
  );
}

/** Защита кабинета: без сессии — на /login. */
function RequireAuth({ children }: { children: React.ReactNode }) {
  const { user, booting } = useApp();
  const location = useLocation();
  if (booting) return <BootScreen />;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return <>{children}</>;
}

export default function App() {
  const { user, booting } = useApp();

  return (
    <Routes>
      <Route path="/login" element={
        booting ? <BootScreen /> : user ? <Navigate to="/dashboard" replace /> : <LoginPage />
      } />
      <Route path="/verify/:uuid" element={<VerifyPage />} />
      <Route path="/onboarding" element={
        <RequireAuth><OnboardingPage /></RequireAuth>
      } />
      <Route path="/" element={<RequireAuth><AppLayout /></RequireAuth>}>
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route path="dashboard" element={<DashboardPage />} />
        <Route path="documents" element={<DocumentsPage />} />
        <Route path="documents/:id" element={<DocumentPage />} />
        <Route path="templates" element={<TemplatesPage />} />
        <Route path="gosklyuch" element={<GosklyuchPage />} />
        <Route path="profile" element={<ProfilePage />} />
        <Route path="admin/clients" element={<AdminOnly><ClientsPage /></AdminOnly>} />
        <Route path="admin/clients/:id" element={<AdminOnly><ClientCardPage /></AdminOnly>} />
        <Route path="admin/audit" element={<AdminOnly><AuditPage /></AdminOnly>} />
        <Route path="admin/crl" element={<AdminOnly><CrlPage /></AdminOnly>} />
        <Route path="admin/settings" element={<AdminOnly><SettingsPage /></AdminOnly>} />
        <Route path="admin/monitoring" element={<AdminOnly><MonitoringPage /></AdminOnly>} />
        <Route path="admin/gosklyuch" element={<AdminOnly><AdminGosklyuchPage /></AdminOnly>} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
