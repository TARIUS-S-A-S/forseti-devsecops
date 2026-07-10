# Forseti Frontend — multi-stage build
# Stage 1: build con Node 22
FROM node:22-alpine AS build
WORKDIR /build

COPY package.json ./
RUN npm install --no-audit --no-fund

COPY . ./
ENV NODE_ENV=production
RUN npm run build

# Stage 2: serve estático con Nginx mínimo
FROM nginx:alpine AS runtime

# Config Nginx para SPA (catch-all → index.html)
RUN echo 'server { \
    listen 80; \
    server_name _; \
    root /usr/share/nginx/html; \
    index index.html; \
    gzip on; \
    gzip_types text/plain text/css application/javascript application/json image/svg+xml; \
    location / { try_files $uri $uri/ /index.html; } \
    location /assets/ { expires 1y; add_header Cache-Control "public, immutable"; } \
}' > /etc/nginx/conf.d/default.conf

COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -qO- http://localhost/ > /dev/null || exit 1
