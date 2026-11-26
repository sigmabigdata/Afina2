import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert, App, Button, Card, Descriptions, Empty, Modal, Space, Spin, Table, Tag, Upload,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  DeleteOutlined, ReloadOutlined, SafetyCertificateOutlined, UploadOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import { api } from '../../lib/api';
import { errorText } from '../../lib/admin';
import { formatDateTime, useApp } from '../../lib/store';
import type { CrlDto, CrlStatusDto, CrlStatusItemDto, CrlSummaryDto } from '../../lib/types';

function statusColor(status: string) {
  switch (status) {
    case 'ok': return 'success';
    case 'expiring': return 'warning';
    case 'expired': return 'error';
    default: return 'default';
  }
}

function statusLabel(status: string) {
  switch (status) {
    case 'ok': return 'OK';
    case 'expiring': return 'Истекает';
    case 'expired': return 'Истёк';
    default: return 'Не проверен';
  }
}

export default function CrlPage() {
  const { message, modal } = App.useApp();
  const { refreshCrls } = useApp();

  const [rows, setRows] = useState<CrlDto[]>([]);
  const [summary, setSummary] = useState<CrlSummaryDto | null>(null);
  const [report, setReport] = useState<CrlStatusDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [refreshing, setRefreshing] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [deletingAuto, setDeletingAuto] = useState(false);
  const [details, setDetails] = useState<CrlDto | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      const [list, sum, status] = await Promise.all([
        api<CrlDto[]>('/admin/crl'),
        api<CrlSummaryDto>('/admin/crl/summary'),
        api<CrlStatusDto>('/admin/crl/status'),
      ]);
      setRows(list);
      setSummary(sum);
      setReport(status);
    } catch (e) {
      setError(errorText(e, 'Не удалось загрузить список CRL'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const refreshAll = async () => {
    setRefreshing(true);
    try {
      const res = await api<{
        downloaded: number;
        failed: number;
        deleted: number;
        totalSignatures?: number;
      }>('/admin/crl/refresh', { method: 'POST' });
      message.success(
        `CRL обновлены: скачано ${res.downloaded ?? 0}, ошибок ${res.failed ?? 0}, `
        + `удалено осиротевших ${res.deleted ?? 0}`,
      );
      await load();
      await refreshCrls().catch(() => undefined);
    } catch (e) {
      message.error(errorText(e, 'Не удалось обновить CRL'));
    } finally {
      setRefreshing(false);
    }
  };

  const uploadCrl = async (file: File) => {
    setUploading(true);
    try {
      const form = new FormData();
      form.append('file', file);
      const res = await api<{ fileName: string; sizePretty: string }>('/admin/crl', {
        method: 'POST',
        form,
      });
      message.success(`CRL загружен: ${res.fileName} (${res.sizePretty})`);
      await load();
      await refreshCrls().catch(() => undefined);
    } catch (e) {
      message.error(errorText(e, 'Не удалось загрузить CRL'));
    } finally {
      setUploading(false);
    }
  };

  const deleteCrl = (row: CrlDto) => {
    modal.confirm({
      title: 'Удалить файл CRL?',
      content: `Файл ${row.fileName} будет удалён из хранилища. Проверка подписей по нему станет недоступна.`,
      okText: 'Удалить',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await api(`/admin/crl/${encodeURIComponent(row.fileName)}`, { method: 'DELETE' });
          message.success('Файл CRL удалён');
          await load();
          await refreshCrls().catch(() => undefined);
        } catch (e) {
          message.error(errorText(e, 'Не удалось удалить файл CRL'));
        }
      },
    });
  };

  const deleteAuto = () => {
    modal.confirm({
      title: 'Удалить автоматические CRL?',
      content: 'Будут удалены все файлы, скачанные автоматически (auto-*.crl). '
        + 'Ручные CRL останутся. При следующей проверке подписи CRL скачаются снова.',
      okText: 'Удалить',
      okButtonProps: { danger: true },
      onOk: async () => {
        setDeletingAuto(true);
        try {
          const res = await api<{ deleted: number }>('/admin/crl/auto', { method: 'DELETE' });
          message.success(`Удалено автоматических CRL: ${res.deleted}`);
          await load();
          await refreshCrls().catch(() => undefined);
        } catch (e) {
          message.error(errorText(e, 'Не удалось удалить автоматические CRL'));
        } finally {
          setDeletingAuto(false);
        }
      },
    });
  };

  const columns: ColumnsType<CrlDto> = [
    {
      title: 'Издатель',
      dataIndex: 'issuer',
      render: (issuer: string | undefined, row) => (
        <div>
          <div style={{ fontWeight: 600 }}>{issuer || row.fileName}</div>
          <div style={{ color: 'var(--muted)', fontSize: 12 }}>
            <code style={{ fontSize: 11 }}>{row.fileName}</code>
          </div>
        </div>
      ),
    },
    { title: 'Размер', dataIndex: 'sizePretty', width: 110, responsive: ['lg'] },
    {
      title: 'Действует до',
      dataIndex: 'nextUpdate',
      width: 170,
      render: (nextUpdate: string | undefined, row) => (
        <div>
          <div>{formatDateTime(nextUpdate)}</div>
          <div style={{ color: 'var(--muted)', fontSize: 12 }}>
            {row.daysToExpiry >= 0 ? `${row.daysToExpiry} дн.` : `просрочен на ${Math.abs(row.daysToExpiry)} дн.`}
          </div>
        </div>
      ),
    },
    {
      title: 'Тип',
      dataIndex: 'manual',
      width: 110,
      responsive: ['xl'],
      render: (manual: boolean) => (
        <Tag bordered={false}>{manual ? 'Вручную' : 'Авто'}</Tag>
      ),
    },
    {
      title: 'Статус',
      dataIndex: 'status',
      width: 150,
      render: (status: string) => (
        <Tag color={statusColor(status)} bordered={false} style={{ borderRadius: 999 }}>
          {statusLabel(status)}
        </Tag>
      ),
    },
    {
      title: '',
      key: 'actions',
      width: 150,
      render: (_: unknown, row) => (
        <Space size={4}>
          <Button size="small" type="link" onClick={() => setDetails(row)}>Детали</Button>
          <Button
            size="small"
            type="text"
            danger
            icon={<DeleteOutlined />}
            aria-label={`Удалить ${row.fileName}`}
            onClick={() => deleteCrl(row)}
          />
        </Space>
      ),
    },
  ];

  const detailItem: CrlStatusItemDto | undefined = useMemo(
    () => (details ? report?.items.find((i) => i.fileName === details.fileName) : undefined),
    [details, report],
  );

  return (
    <>
      <PageHeader
        title="CRL и доверие"
        subtitle="Списки отзыва сертификатов: обновление, ручная загрузка и проверка сроков"
        extra={(
          <Space wrap>
            <Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>
              Обновить
            </Button>
            <Upload
              accept=".crl,.CRL"
              maxCount={1}
              showUploadList={false}
              beforeUpload={(file) => { void uploadCrl(file); return false; }}
            >
              <Button icon={<UploadOutlined />} loading={uploading}>Загрузить CRL</Button>
            </Upload>
            <Button
              danger
              icon={<DeleteOutlined />}
              loading={deletingAuto}
              onClick={deleteAuto}
            >
              Удалить авто-CRL
            </Button>
            <Button type="primary" icon={<SafetyCertificateOutlined />} loading={refreshing} onClick={() => void refreshAll()}>
              Обновить CRL
            </Button>
          </Space>
        )}
      />

      {error && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="Не удалось загрузить CRL"
          description={error}
          action={<Button size="small" onClick={() => void load()}>Повторить</Button>}
        />
      )}

      <div className="stat-grid" style={{ marginBottom: 16 }}>
        <Card>
          <div className="stat-label">CRL в хранилище</div>
          <div className="stat-value">{summary?.count ?? rows.length}</div>
        </Card>
        <Card>
          <div className="stat-label">Объём</div>
          <div className="stat-value" style={{ fontSize: 22 }}>{summary?.totalSizePretty ?? '—'}</div>
        </Card>
        <Card>
          <div className="stat-label">Истекают</div>
          <div className="stat-value" style={{ color: '#f59e0b' }}>{summary?.expiring ?? 0}</div>
        </Card>
        <Card>
          <div className="stat-label">Просрочены</div>
          <div className="stat-value" style={{ color: '#ef4444' }}>{summary?.expired ?? 0}</div>
        </Card>
      </div>

      {report && report.summary.untrusted > 0 && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message={`CRL без подтверждённой подписи: ${report.summary.untrusted}`}
          description="Проверьте срок действия и подпись издателя в деталях CRL."
        />
      )}

      <Card title={<Space><SafetyCertificateOutlined /> Состояние CRL</Space>} styles={{ body: { padding: rows.length === 0 ? 24 : 0 } }}>
        {loading ? (
          <div style={{ textAlign: 'center', padding: 48 }}><Spin size="large" /></div>
        ) : rows.length === 0 ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description="CRL ещё не загружены. Обновите или загрузите файл вручную."
          >
            <Button type="primary" icon={<ReloadOutlined />} loading={refreshing} onClick={() => void refreshAll()}>
              Обновить CRL
            </Button>
          </Empty>
        ) : (
          <Table<CrlDto>
            rowKey="fileName"
            dataSource={rows}
            columns={columns}
            pagination={false}
            scroll={{ x: 760 }}
          />
        )}
      </Card>

      <Modal
        open={!!details}
        title={details ? `CRL: ${details.fileName}` : ''}
        footer={<Button onClick={() => setDetails(null)}>Закрыть</Button>}
        onCancel={() => setDetails(null)}
        width={640}
        destroyOnHidden
      >
        {details && (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Descriptions
              column={1}
              size="small"
              items={[
                { key: 'issuer', label: 'Издатель', children: details.issuer || detailItem?.issuerCn || '—' },
                { key: 'size', label: 'Размер', children: details.sizePretty },
                { key: 'last', label: 'Выпущен', children: formatDateTime(details.lastUpdate ?? detailItem?.thisUpdate) },
                { key: 'next', label: 'Действует до', children: formatDateTime(details.nextUpdate ?? detailItem?.nextUpdate) },
                { key: 'downloaded', label: 'Скачан', children: formatDateTime(details.downloadedAt) },
                {
                  key: 'sig',
                  label: 'Подпись CRL',
                  children: detailItem?.signatureVerified
                    ? <Tag color="success" bordered={false}>Проверена</Tag>
                    : <Tag color="warning" bordered={false}>Не проверена</Tag>,
                },
                {
                  key: 'status',
                  label: 'Статус',
                  children: (
                    <Tag color={statusColor(details.status)} bordered={false}>
                      {statusLabel(details.status)}
                    </Tag>
                  ),
                },
                { key: 'note', label: 'Примечание', children: detailItem?.note || '—' },
              ]}
            />
            {!detailItem && (
              <Alert
                type="info"
                showIcon
                message="Отчёт о доверии для этого файла недоступен"
                description="Файл есть в хранилище, но не разобран в отчёте — обновите CRL."
              />
            )}
          </Space>
        )}
      </Modal>
    </>
  );
}
