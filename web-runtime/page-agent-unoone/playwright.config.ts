import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  timeout: 45_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['line'], ['html', { open: 'never', outputFolder: 'playwright-report' }]] : 'list',
  use: {
    ...devices['Pixel 7'],
    browserName: 'chromium',
    headless: true,
    trace: 'retain-on-failure'
  }
})
