# The web app, served by nginx, with /api passed on to NeoBank. Build context: the folder that holds the four repos.
FROM node:22-alpine AS build
WORKDIR /src/neobank-frontend
COPY neobank-frontend/package.json neobank-frontend/package-lock.json ./
RUN npm ci
COPY neobank-frontend/ ./
RUN npx ng build

FROM nginx:alpine
COPY neobank-demo/docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/neobank-frontend/dist/neobank-frontend/browser /usr/share/nginx/html
EXPOSE 80
