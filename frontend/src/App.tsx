import { Route, Routes } from 'react-router-dom';
import AppLayout from './layouts/AppLayout';
import LoginPage from './pages/LoginPage';
import VerifyPage from './pages/VerifyPage';
import DashboardPage from './pages/DashboardPage';
import DocumentsPage from './pages/DocumentsPage';
import DocumentPage from './pages/DocumentPage';
import TemplatesPage from './pages/TemplatesPage';
import GosklyuchPage from './pages/GosklyuchPage';
import ProfilePage from './pages/ProfilePage';
import ClientsPage from './pages/admin/ClientsPage';
import ClientCardPage from './pages/admin/ClientCardPage';
import AuditPage from './pages/admin/AuditPage';
import CrlPage from './pages/admin/CrlPage';
import SettingsPage from './pages/admin/SettingsPage';
import MonitoringPage from './pages/admin/MonitoringPage';
import AdminGosklyuchPage from './pages/admin/AdminGosklyuchPage';
import NotFoundPage from './pages/NotFoundPage';

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/verify/:uuid" element={<VerifyPage />} />
      <Route path="/" element={<AppLayout />}>
        <Route index element={<DashboardPage />} />
        <Route path="dashboard" element={<DashboardPage />} />
        <Route path="documents" element={<DocumentsPage />} />
        <Route path="documents/:id" element={<DocumentPage />} />
        <Route path="templates" element={<TemplatesPage />} />
        <Route path="gosklyuch" element={<GosklyuchPage />} />
        <Route path="profile" element={<ProfilePage />} />
        <Route path="admin/clients" element={<ClientsPage />} />
        <Route path="admin/clients/:id" element={<ClientCardPage />} />
        <Route path="admin/audit" element={<AuditPage />} />
        <Route path="admin/crl" element={<CrlPage />} />
        <Route path="admin/settings" element={<SettingsPage />} />
        <Route path="admin/monitoring" element={<MonitoringPage />} />
        <Route path="admin/gosklyuch" element={<AdminGosklyuchPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
