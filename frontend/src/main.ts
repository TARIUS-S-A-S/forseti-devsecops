import { createApp } from 'vue'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ConfirmationService from 'primevue/confirmationservice'
import Aura from '@primevue/themes/aura'
import { definePreset } from '@primevue/themes'

// Self-hosted fonts (no Google Fonts — LOPDP compliance)
import '@fontsource/lora/400.css'
import '@fontsource/lora/500.css'
import '@fontsource/lora/600.css'
import '@fontsource/lora/700.css'
import '@fontsource/lora/400-italic.css'
import '@fontsource/inter/300.css'
import '@fontsource/inter/400.css'
import '@fontsource/inter/500.css'
import '@fontsource/inter/600.css'
import '@fontsource/inter/700.css'
import '@fontsource/jetbrains-mono/400.css'
import '@fontsource/jetbrains-mono/500.css'
import '@fontsource/jetbrains-mono/600.css'

import App from './App.vue'
import router from './router'
import './assets/styles/main.css'

// ─── Tema PrimeVue custom con identidad Forseti v3.0 ───
// Override del preset Aura: action UI = navy (#1E3A8A), no mandarina (que es color de marca).
const ForsetiPreset = definePreset(Aura, {
  semantic: {
    primary: {
      50:  '#EFF6FF',
      100: '#DBEAFE',
      200: '#BFDBFE',
      300: '#93C5FD',
      400: '#60A5FA',
      500: '#1E3A8A', // action navy
      600: '#1E40AF',
      700: '#1D4ED8',
      800: '#172554',
      900: '#172554',
      950: '#0F1F4D',
    },
    colorScheme: {
      light: {
        surface: {
          0:   '#FFFFFF',
          50:  '#F8FAFC',
          100: '#F1F5F9',
          200: '#E2E8F0',
          300: '#CBD5E1',
          400: '#94A3B8',
          500: '#64748B',
          600: '#475569',
          700: '#334155',
          800: '#1E293B',
          900: '#0F172A',
          950: '#020617',
        },
      },
    },
  },
  components: {
    button: {
      borderRadius: '8px',
      paddingX: '1.25rem',
      paddingY: '0.6rem',
    },
    inputtext: {
      borderRadius: '8px',
      paddingX: '0.875rem',
      paddingY: '0.625rem',
    },
  },
})

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(PrimeVue, {
  theme: {
    preset: ForsetiPreset,
    options: {
      darkModeSelector: '.dark-mode',
    },
  },
  ripple: true,
})
app.use(ConfirmationService)

app.mount('#app')
