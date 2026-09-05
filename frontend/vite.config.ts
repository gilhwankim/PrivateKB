import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig(({ mode }) => {
  const desktopApiBaseUrl = mode === 'desktop' ? 'http://127.0.0.1:8080' : ''

  return {
    plugins: [react()],
    define: {
      __PRIVATEKB_API_BASE_URL__: JSON.stringify(desktopApiBaseUrl),
    },
    server: {
      port: 5173,
      strictPort: true,
      watch: {
        ignored: ['**/src-tauri/**'],
      },
      proxy: {
        '/api': {
          target: 'http://127.0.0.1:8080',
          changeOrigin: true,
          headers: {
            Origin: 'http://127.0.0.1:8080',
          },
        },
        '/actuator': {
          target: 'http://127.0.0.1:8080',
          changeOrigin: true,
          headers: {
            Origin: 'http://127.0.0.1:8080',
          },
        },
      },
    },
    test: {
      environment: 'jsdom',
      setupFiles: './src/test/setup.ts',
      css: true,
    },
  }
})
