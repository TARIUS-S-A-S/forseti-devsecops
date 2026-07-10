# Forseti Frontend — multi-stage build
# Stage 1: build con Node 22
FROM node:22-alpine AS build
WORKDIR /build

COPY package.json ./
RUN npm install --no-audit --no-fund

COPY . ./
ENV NODE_ENV=production
RUN npm run build

# Stage 2: serve estático con Nginx unprivileged (corre como uid 101, sin root)
FROM nginxinc/nginx-unprivileged:alpine AS runtime

USER root
# Config Nginx para SPA (catch-all → index.html) — puerto 8080 (non-privileged)
RUN echo 'server { \
    listen 8080; \
    server_name _; \
    root /usr/share/nginx/html; \
    index index.html; \
    gzip on; \
    gzip_types text/plain text/css application/javascript application/json image/svg+xml; \
    location / { try_files $uri $uri/ /index.html; } \
    location /assets/ { expires 1y; add_header Cache-Control "public, immutable"; } \
}' > /etc/nginx/conf.d/default.conf

COPY --from=build /build/dist /usr/share/nginx/html
RUN chown -R nginx:nginx /usr/share/nginx/html
USER nginx

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -qO- http://localhost:8080/ > /dev/null || exit 1
