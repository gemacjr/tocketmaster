import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev mode proxies each API path to its service (mirrors the nginx/ALB edge routing).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api/events': 'http://localhost:18081',
      '/api/admin/events': 'http://localhost:18081',
      '/api/search': 'http://localhost:18082',
      '/api/admin/search': 'http://localhost:18082',
      '/api/bookings': 'http://localhost:18083',
      '/api/payments': 'http://localhost:18083',
      '/api/users': 'http://localhost:18083',
      '/api/queue': 'http://localhost:18084',
      '/api/admin/queue': 'http://localhost:18084',
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    target: 'es2019',
  },
});
