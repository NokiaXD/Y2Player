import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  base: './',
  server: { fs: { allow: ['..'] } },
  build: { target: 'es2020' },
  test: { environment: 'node', exclude: ['e2e/**', 'node_modules/**', 'dist/**'] }
})
