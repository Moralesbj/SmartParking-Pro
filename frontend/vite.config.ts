import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// changeOrigin must stay false: the backend only accepts same-origin browser requests (CORS closed),
// so the proxy has to forward the browser's Host header unchanged, exactly like nginx in production.
const backend = { target: 'http://localhost:8080', changeOrigin: false };

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: { proxy: { '/api': backend, '/actuator': backend } },
});
