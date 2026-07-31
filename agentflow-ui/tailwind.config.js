/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        space: {
          950: '#0A0F1E',
          900: '#141B2E',
          800: '#1E2A45',
          700: '#2A3554',
          600: '#3A4870',
          500: '#5560A0',
        },
        cyan: {
          DEFAULT: '#00E5CC',
          dim: '#00C4B0',
        },
        text: {
          bright: '#F0F4F8',
          muted: '#8892B0',
          dim: '#5A6B8C',
        },
        success: '#22C55E',
        warning: '#FFB224',
        danger: '#EF4444',
      },
      fontFamily: {
        display: ['"Space Grotesk"', 'sans-serif'],
        body: ['"Inter"', 'sans-serif'],
        mono: ['"JetBrains Mono"', 'monospace'],
      },
      animation: {
        'pulse-slow': 'pulse 3s ease-in-out infinite',
        'fade-in': 'fadeIn 0.3s ease-out',
        'slide-up': 'slideUp 0.4s ease-out',
      },
      keyframes: {
        fadeIn: { from: { opacity: '0' }, to: { opacity: '1' } },
        slideUp: { from: { opacity: '0', transform: 'translateY(12px)' }, to: { opacity: '1', transform: 'translateY(0)' } },
      }
    }
  },
  plugins: []
}
