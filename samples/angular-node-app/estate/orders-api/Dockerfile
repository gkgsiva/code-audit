FROM node:18-alpine
WORKDIR /app
COPY dist/ ./dist/
COPY package*.json ./
RUN npm ci --omit=dev
CMD ["node", "dist/server.js"]
