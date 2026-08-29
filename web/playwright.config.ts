import {defineConfig,devices} from '@playwright/test';
export default defineConfig({testDir:'./e2e',fullyParallel:true,retries:1,use:{baseURL:'http://127.0.0.1:4173',trace:'on-first-retry'},projects:[{name:'chromium',use:{...devices['Desktop Chrome']}}],webServer:{command:'npm run dev -- --host 127.0.0.1',url:'http://127.0.0.1:4173',reuseExistingServer:true}});
