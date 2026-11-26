import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Метка сборки: видна в футере страницы входа, помогает понять,
// какая версия интерфейса загружена в браузере (кеш, прокси).
const buildStamp = new Date().toISOString().slice(0, 16).replace('T', ' ');

export default defineConfig({
  plugins: [react()],
  define: {
    __BUILD_STAMP__: JSON.stringify(buildStamp),
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
});
