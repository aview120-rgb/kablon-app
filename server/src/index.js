#!/usr/bin/env node
// Entry point: node src/index.js   (configuration via environment variables, see README.md)
import crypto from 'node:crypto';
import path from 'node:path';
import { Store } from './store.js';
import { TaskQueue } from './queue.js';
import { createServer } from './server.js';

const port = Number(process.env.PORT ?? 8787);
const host = process.env.HOST ?? '0.0.0.0';
const dataFile = path.resolve(process.env.DATA_FILE ?? './data/db.json');
let token = process.env.KABLON_TOKEN;
if (!token) {
  token = crypto.randomBytes(18).toString('base64url');
  console.log(`[שרת] לא הוגדר KABLON_TOKEN. אסימון זמני להפעלה זו: ${token}`);
}

const log = (m) => console.log(`[${new Date().toISOString()}] ${m}`);
const store = new Store(dataFile);
const queue = new TaskQueue(store, { timeZone: process.env.TZ_NAME ?? 'Asia/Jerusalem', log });
const server = createServer(queue, { token, log: process.env.QUIET ? () => {} : log });

queue.start(1000);
server.listen(port, host, () => log(`שרת תור המשימות פועל על http://${host}:${port} (נתונים: ${dataFile})`));

function shutdown(sig) {
  log(`מתקבל ${sig}, שומר נתונים ונסגר…`);
  queue.stop();
  store.flush();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 3000).unref();
}
process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('uncaughtException', (e) => {
  log(`שגיאה לא מטופלת: ${e.stack ?? e}`);
  store.flush();
});
