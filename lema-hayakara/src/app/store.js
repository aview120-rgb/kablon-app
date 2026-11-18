// Saved projects (IndexedDB) – works offline and survives app updates.
const DB = 'lema-hayakara', STORE = 'projects';
function open() {
  return new Promise((res, rej) => {
    const r = indexedDB.open(DB, 1);
    r.onupgradeneeded = () => r.result.createObjectStore(STORE, { keyPath: 'id' });
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  });
}
async function tx(mode, fn) {
  const db = await open();
  return new Promise((res, rej) => {
    const t = db.transaction(STORE, mode);
    const st = t.objectStore(STORE);
    const out = fn(st);
    t.oncomplete = () => res(out && out.result !== undefined ? out.result : out);
    t.onerror = () => rej(t.error);
  });
}
export const saveProject = (p) => tx('readwrite', (s) => s.put(p));
export const getProject = (id) => tx('readonly', (s) => s.get(id));
export const deleteProject = (id) => tx('readwrite', (s) => s.delete(id));
export async function listProjects() {
  const all = await tx('readonly', (s) => s.getAll());
  return (all || []).map(({ id, name, updated, thumb }) => ({ id, name, updated, thumb })).sort((a, b) => b.updated - a.updated);
}
