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

FROM caddy:2.10.2-alpine
COPY --from=build /workspace/apps/console/dist /srv
EXPOSE 8080
CMD ["caddy","file-server","--root","/srv","--listen",":8080"]
