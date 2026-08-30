/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 商品图片资源基础地址（如 http://localhost:8080），用于拼接后端相对路径 /img/xx.png；未配置时视为同源 */
  readonly VITE_GOODS_IMG_BASE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
