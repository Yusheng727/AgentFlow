import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// RTL 卸载后清理 DOM（vitest globals:true 时 RTL 会自动做，这里显式注册以防配置漂移）
afterEach(() => {
  cleanup()
})
