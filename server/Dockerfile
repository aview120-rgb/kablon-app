FROM node:22-alpine
WORKDIR /app
COPY package.json ./
COPY src ./src
COPY public ./public
ENV PORT=8787 DATA_FILE=/data/db.json
VOLUME /data
EXPOSE 8787
USER node
CMD ["node", "src/index.js"]
