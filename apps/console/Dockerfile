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

FROM alpine:3.23.5
RUN apk upgrade --no-cache \
    && addgroup -S -g 10001 wyrmgate \
    && adduser -S -D -H -u 10001 -G wyrmgate wyrmgate
COPY --from=build --chown=10001:10001 /workspace/apps/console/dist /srv
USER 10001:10001
EXPOSE 8080
CMD ["busybox","httpd","-f","-p","8080","-h","/srv"]
