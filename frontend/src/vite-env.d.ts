/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_DEMO_URL?: string;
  readonly VITE_API_BASE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

declare const __BUILD_STAMP__: string;
