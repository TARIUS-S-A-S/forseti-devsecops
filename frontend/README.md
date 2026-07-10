# Forseti Frontend

> SaaS de facturación electrónica SRI Ecuador + cumplimiento + gestión.
> Producto de **TARIUS S.A.S** — UI con identidad de marca v3.0 (mandarina + Lora + dos triángulos).

[![CI](https://github.com/TARIUS-S-A-S/forseti-frontend/actions/workflows/ci.yml/badge.svg)](https://github.com/TARIUS-S-A-S/forseti-frontend/actions)

## Stack

| Capa | Tecnología |
|---|---|
| Framework | Vue 3.5 + Composition API |
| Build | Vite 6 |
| Lenguaje | TypeScript 5 |
| UI Library | PrimeVue 4 (preset Aura customizado con tokens Forseti) |
| Estado | Pinia |
| Router | Vue Router 4 |
| HTTP | Axios (con interceptor 401 → /login) |
| Tests | Vitest (unit) + Playwright (E2E) + @vue/test-utils |
| Estilo | CSS variables nativas con tokens del manual de marca v3.0 |

## Identidad de marca aplicada

- **Color de marca:** mandarina `#FB923C` (hero, badges, punto del wordmark)
- **Color de acción UI:** navy `#1E3A8A` (botones primarios — separado de la marca, patrón Stripe/Linear)
- **Tipografía display:** `Lora` Bold (wordmark + titulares)
- **Tipografía UI:** `Inter`
- **Tipografía números:** `JetBrains Mono` (montos, claves SRI, secuenciales)
- **Símbolo:** dos triángulos en equilibrio (ink + mandarina) — `/public/logos/`
- **Endorsement:** "un producto de TARIUS." en footer, login y correos — componente `<EndorsementTarius>`

Tokens canónicos: [`_marca-tokens/forseti.tokens.json`](../../_marca-tokens/forseti.tokens.json)

## Quickstart local

```bash
npm install
npm run dev
```

Abrir http://localhost:5173. Proxy automático a backend en http://localhost:8080.

## Comandos

```bash
npm run dev         # dev server con HMR
npm run build       # build prod (vue-tsc + vite build)
npm run preview     # preview del build
npm run lint        # ESLint
npm run type-check  # vue-tsc strict
npm run test        # vitest run
npm run test:e2e    # playwright (headless)
```

## Estructura

```
src/
├── main.ts                  — entry + Vue + Pinia + Router + PrimeVue theme
├── App.vue                  — root
├── router/                  — Vue Router
├── stores/                  — Pinia stores (auth, empresa, ...)
├── views/                   — páginas (HomeView, LoginView, ...)
├── components/              — componentes reusables
│   ├── ForsetiLogo.vue      — logo SVG (ink/mandarina/blanco)
│   └── EndorsementTarius.vue — "un producto de TARIUS."
├── api/                     — cliente HTTP (axios)
└── assets/styles/
    ├── tokens.css           — CSS variables (sync con tokens.json)
    └── main.css             — base styles + utility classes
public/
├── logos/                   — SVGs Forseti
├── favicon.svg / ico        — del set v3.0
└── avatares/                — fallback avatares (12 variantes)
```

## Deploy

- **Staging:** push a `dev` → CI deploy a `staging.forseti.tarius.ec`
- **Producción:** PR de `dev` a `main` → CI deploy a `forseti.tarius.ec`

Build estático servido por Caddy (en el mismo VPS donde corre el backend).

## Documentación de referencia

- Plan completo: [`productos/forseti/documento-maestro.md`](../forseti/documento-maestro.md)
- Manual de marca: `OneDrive - TARIUS/07-marca/forseti/manual-de-marca/identidad-forseti.md`

---

**© TARIUS S.A.S — Quito, Ecuador.**
