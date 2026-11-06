import { cpSync, existsSync } from 'node:fs'
import path from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

/** The handbook (docs/handbuch, plain HTML with its pictures) goes along as /handbuch, so that the UI shows the one of its version */
function handbook() {
  const source = path.resolve(import.meta.dirname, '../../../docs/handbuch')
  return {
    name: 'handbook',
    closeBundle() {
      if (existsSync(source)) {
        cpSync(source, path.resolve(import.meta.dirname, 'dist/handbuch'), { recursive: true })
      }
    },
  }
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss(), handbook()],
  server: { port: 5174, strictPort: true },
  resolve: {
    alias: {
      '@': path.resolve(import.meta.dirname, './src'),
    },
  },
})
