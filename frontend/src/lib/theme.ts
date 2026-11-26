import type { ThemeConfig } from 'antd';

export const brand = {
  primary: '#4f46e5',
  violet: '#8b5cf6',
  mint: '#10b981',
  amber: '#f59e0b',
  rose: '#ef4444',
  ink: '#0b1020',
};

export const sharedTokens: ThemeConfig['token'] = {
  colorPrimary: brand.primary,
  colorInfo: brand.primary,
  colorSuccess: brand.mint,
  colorWarning: brand.amber,
  colorError: brand.rose,
  borderRadius: 14,
  borderRadiusLG: 18,
  borderRadiusSM: 10,
  fontFamily: "'Manrope', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
  fontSize: 14,
  controlHeight: 40,
  controlHeightLG: 48,
  wireframe: false,
};

export const lightTheme: ThemeConfig = {
  token: {
    ...sharedTokens,
    colorBgBase: '#f6f7fb',
    colorBgContainer: '#ffffff',
    colorBgLayout: '#f6f7fb',
    colorBorder: '#e6e9f2',
    colorText: '#111827',
    colorTextSecondary: '#5b6478',
  },
  components: {
    Layout: { bodyBg: '#f6f7fb', siderBg: 'transparent' },
    Card: { borderRadiusLG: 20, boxShadowTertiary: '0 12px 40px rgba(17, 24, 39, 0.06)' },
    Menu: { itemBorderRadius: 12, itemHeight: 44, itemMarginInline: 8 },
    Table: { headerBg: 'transparent', headerColor: '#6b7280', rowHoverBg: '#f8f9ff' },
    Button: { borderRadius: 12, controlHeight: 40, fontWeight: 600 },
  },
};

export const darkTheme: ThemeConfig = {
  token: {
    ...sharedTokens,
    colorBgBase: '#080b16',
    colorBgContainer: '#101528',
    colorBgLayout: '#080b16',
    colorBorder: '#242b42',
    colorText: '#f1f4ff',
    colorTextSecondary: '#98a2bd',
  },
  components: {
    Layout: { bodyBg: '#080b16', siderBg: 'transparent' },
    Card: { borderRadiusLG: 20, boxShadowTertiary: '0 18px 50px rgba(0,0,0,.35)' },
    Menu: { itemBorderRadius: 12, itemHeight: 44, itemMarginInline: 8, darkItemBg: 'transparent' },
    Table: { headerBg: 'transparent', headerColor: '#8d97b3', rowHoverBg: '#151b31' },
    Button: { borderRadius: 12, controlHeight: 40, fontWeight: 600 },
  },
};
