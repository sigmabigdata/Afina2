import React from 'react';
import ReactDOM from 'react-dom/client';
import { App as AntApp, ConfigProvider, theme as antdTheme } from 'antd';
import ruRU from 'antd/locale/ru_RU';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { AppProvider, useApp } from './lib/store';
import { darkTheme, lightTheme } from './lib/theme';
import './styles/global.css';

function ThemedApp() {
  const { dark } = useApp();
  return (
    <ConfigProvider
      locale={ruRU}
      theme={{
        ...(dark ? darkTheme : lightTheme),
        algorithm: dark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
      }}
    >
      <AntApp>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </AntApp>
    </ConfigProvider>
  );
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <AppProvider>
      <ThemedApp />
    </AppProvider>
  </React.StrictMode>,
);
