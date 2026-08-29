import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
export default defineConfig({plugins:[react()],server:{port:4173,proxy:{'/api':'http://localhost:8080'}},build:{sourcemap:false},test:{environment:'jsdom',setupFiles:['./src/test/setup.ts'],exclude:['e2e/**','node_modules/**','dist/**']}})
