# syntax=docker/dockerfile:1.7

FROM node:24-alpine AS build
WORKDIR /workspace/apps/console

COPY apps/console/package.json ./package.json
COPY apps/console/tsconfig.json ./tsconfig.json
COPY apps/console/tsconfig.app.json ./tsconfig.app.json
COPY apps/console/tsconfig.node.json ./tsconfig.node.json
COPY apps/console/vite.config.ts ./vite.config.ts
COPY apps/console/index.html ./index.html
COPY apps/console/src ./src

RUN npm install --no-audit --no-fund
RUN npm run build

FROM caddy:2.11.4-alpine
RUN apk upgrade --no-cache
COPY --from=build --chown=1000:1000 /workspace/apps/console/dist /srv
USER 1000:1000
EXPOSE 8080
CMD ["caddy","file-server","--root","/srv","--listen",":8080"]
