import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],

  test: {
    include: ["src/**/*.{test,spec}.{ts,tsx}", "e2e/**/*.unit.ts"],
    environment: "jsdom",
    setupFiles: "./src/test/setup.ts",
  },
});
