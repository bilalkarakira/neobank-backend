# The web app from a build you made yourself (cd neobank-frontend && npm ci && npx ng build).
ARG NGINX_IMAGE=nginx:alpine
FROM ${NGINX_IMAGE}
COPY neobank-demo/docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY neobank-frontend/dist/neobank-frontend/browser /usr/share/nginx/html
EXPOSE 80
