import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 构建产物直接落到 fileark-web 的静态资源目录，mvn package 时打进 jar
export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: '../fileark-web/src/main/resources/static',
    emptyOutDir: true
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080'
    }
  }
})
