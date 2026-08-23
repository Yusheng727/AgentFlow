/** @type {import('tailwindcss').Config} */
// 色板对齐 prototype-final.html（A 风格：深色侧边栏 + 浅色内容区）
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        page: '#F8FAFC',
        surface: '#FFFFFF',
        hover: '#F1F5F9',
        line: '#E2E8F0',
        accent: { DEFAULT: '#3B82F6', dim: '#2563EB', light: '#EFF6FF' },
        success: { DEFAULT: '#16A34A', light: '#DCFCE7' },
        warning: { DEFAULT: '#D97706', light: '#FEF3C7' },
        danger: { DEFAULT: '#DC2626', light: '#FEE2E2' },
        ink: '#0F172A',
        muted: '#64748B',
        dim: '#94A3B8',
        sidebar: {
          DEFAULT: '#0F172A',
          hover: '#1E293B',
          active: 'rgba(59,130,246,0.15)',
          'active-border': 'rgba(59,130,246,0.3)',
        },
      },
      fontFamily: {
        sans: ['Inter', 'sans-serif'],
        mono: ['"JetBrains Mono"', 'monospace'],
      },
      boxShadow: {
        card: '0 1px 3px rgba(0,0,0,0.06), 0 1px 2px rgba(0,0,0,0.04)',
        'card-lg': '0 4px 12px rgba(0,0,0,0.08), 0 2px 4px rgba(0,0,0,0.04)',
      },
      animation: {
        'fade-in': 'fadeIn 0.25s ease-out',
      },
      keyframes: {
        fadeIn: {
          from: { opacity: '0', transform: 'translateY(6px)' },
          to: { opacity: '1', transform: 'translateY(0)' },
        },
      },
    },
  },
  plugins: [],
}
