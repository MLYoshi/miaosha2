/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 可选：覆盖默认 baseURL（默认 '/' 走 Vite proxy） */
  readonly VITE_API_BASE_URL?: string;
  /** 可选：覆盖 Vite proxy 目标（默认 http://localhost:8080） */
  readonly VITE_PROXY_TARGET?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
