// Persistent JSON store with atomic writes (temp file + fsync + rename).
// Survives crashes: the previous snapshot is kept as db.json.bak and used if db.json is corrupt.
import fs from 'node:fs';
import path from 'node:path';

const EMPTY = () => ({ version: 1, seq: 0, tasks: {}, devices: {}, uploads: {}, seenUploads: {} });

export class Store {
  /**
   * @param {string|null} file path of the JSON file, or null for an in-memory store (tests).
   * @param {{debounceMs?: number}} [opts]
   */
  constructor(file, opts = {}) {
    this.file = file;
    this.debounceMs = opts.debounceMs ?? 150;
    this.timer = null;
    this.dirty = false;
    this.data = this.#load();
  }

  #load() {
    if (!this.file) return EMPTY();
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    for (const candidate of [this.file, this.file + '.bak']) {
      try {
        if (!fs.existsSync(candidate)) continue;
        const parsed = JSON.parse(fs.readFileSync(candidate, 'utf8'));
        return { ...EMPTY(), ...parsed };
      } catch (err) {
        console.error(`[store] לא ניתן לקרוא את ${candidate}: ${err.message}`);
      }
    }
    return EMPTY();
  }

  nextId(prefix = 't') {
    this.data.seq += 1;
    return `${prefix}_${Date.now().toString(36)}_${this.data.seq.toString(36)}`;
  }

  /** Marks the data dirty and schedules a write. */
  save() {
    this.dirty = true;
    if (!this.file || this.timer) return;
    this.timer = setTimeout(() => {
      this.timer = null;
      this.flush();
    }, this.debounceMs);
    this.timer.unref?.();
  }

  flush() {
    if (!this.file || !this.dirty) return;
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    const tmp = this.file + '.tmp';
    const json = JSON.stringify(this.data);
    const fd = fs.openSync(tmp, 'w');
    try {
      fs.writeSync(fd, json);
      fs.fsyncSync(fd);
    } finally {
      fs.closeSync(fd);
    }
    if (fs.existsSync(this.file)) {
      try { fs.copyFileSync(this.file, this.file + '.bak'); } catch { /* best effort */ }
    }
    fs.renameSync(tmp, this.file);
    this.dirty = false;
  }
}
