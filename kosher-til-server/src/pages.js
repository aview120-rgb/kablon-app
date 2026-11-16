// Server-rendered HTML for the admin panel (Hebrew, RTL). Plain vanilla JS.

const BASE_CSS = `
  :root{color-scheme:dark;--bg:#0e1726;--surface:#172334;--line:#24344b;
    --txt:#e8eef7;--muted:#9ab0cc;--brand:#1f6feb;--ok:#1e9e5a;--bad:#d1333b}
  *{box-sizing:border-box}
  body{margin:0;font-family:system-ui,"Segoe UI",Arial,sans-serif;background:var(--bg);
    color:var(--txt);direction:rtl}
  header{padding:18px 20px;border-bottom:1px solid var(--line);display:flex;
    align-items:center;justify-content:space-between}
  h1{font-size:20px;margin:0}
  .wrap{max-width:900px;margin:0 auto;padding:20px}
  .card{background:var(--surface);border:1px solid var(--line);border-radius:12px;
    padding:16px;margin-bottom:14px}
  input,button,select{font:inherit}
  input{background:#0b1220;border:1px solid var(--line);color:var(--txt);
    border-radius:8px;padding:10px 12px;width:100%}
  button{background:var(--brand);color:#fff;border:0;border-radius:8px;
    padding:10px 14px;cursor:pointer}
  button.ghost{background:transparent;border:1px solid var(--line);color:var(--txt)}
  button.danger{background:var(--bad)}
  button.ok{background:var(--ok)}
  label{display:block;margin:10px 0 4px;color:var(--muted);font-size:13px}
  .row{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
  .muted{color:var(--muted);font-size:13px}
  .pill{padding:2px 10px;border-radius:999px;font-size:12px}
  .pill.on{background:rgba(30,158,90,.2);color:#7ee6a8}
  .pill.off{background:rgba(209,51,59,.2);color:#ff9aa0}
  table{width:100%;border-collapse:collapse}
  td,th{padding:10px;border-bottom:1px solid var(--line);text-align:right;vertical-align:top}
  .err{color:#ff9aa0;min-height:18px}
`;

function page(title, body) {
  return `<!doctype html><html lang="he" dir="rtl"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${title}</title><style>${BASE_CSS}</style></head><body>${body}</body></html>`;
}

export function loginPage() {
  return page('כושר-טיל — כניסת מנהל', `
<div class="wrap">
  <div class="card" style="max-width:380px;margin:60px auto">
    <h1>כושר-טיל · ניהול</h1>
    <label>שם משתמש</label><input id="u" autocomplete="username">
    <label>סיסמה</label><input id="p" type="password" autocomplete="current-password">
    <div class="err" id="err"></div>
    <button style="width:100%;margin-top:12px" onclick="login()">כניסה</button>
  </div>
</div>
<script>
async function login(){
  const r = await fetch('/api/admin/login',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({username:u.value,password:p.value})});
  if(r.ok){location.href='/panel';}else{err.textContent='שם משתמש או סיסמה שגויים';}
}
document.getElementById('p').addEventListener('keydown',e=>{if(e.key==='Enter')login()});
</script>`);
}

export function setupPage() {
  return page('כושר-טיל — הקמת מנהל', `
<div class="wrap">
  <div class="card" style="max-width:380px;margin:60px auto">
    <h1>הקמת מנהל ראשון</h1>
    <p class="muted">צור את חשבון המנהל הראשון. לאחר מכן עמוד זה יינעל.</p>
    <label>שם משתמש</label><input id="u" autocomplete="username">
    <label>סיסמה (לפחות 8 תווים)</label><input id="p" type="password" autocomplete="new-password">
    <div class="err" id="err"></div>
    <button style="width:100%;margin-top:12px" onclick="setup()">יצירה</button>
  </div>
</div>
<script>
async function setup(){
  if(p.value.length<8){err.textContent='סיסמה קצרה מדי';return;}
  const r = await fetch('/api/admin/setup',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({username:u.value,password:p.value})});
  if(r.ok){location.href='/panel';}
  else{const j=await r.json().catch(()=>({}));err.textContent=j.error||'שגיאה';}
}
</script>`);
}

export function panelPage() {
  return page('כושר-טיל — לוח ניהול', `
<header>
  <h1>כושר-טיל · לוח ניהול</h1>
  <button class="ghost" onclick="logout()">יציאה</button>
</header>
<div class="wrap">
  <div class="card">
    <div class="row" style="justify-content:space-between">
      <strong>בקשות שחרור ממתינות</strong>
      <button class="ghost" onclick="load()">רענון</button>
    </div>
    <div id="requests" class="muted" style="margin-top:10px">טוען…</div>
  </div>
  <div class="card">
    <strong>מכשירים</strong>
    <div id="devices" style="margin-top:10px">טוען…</div>
  </div>
</div>
<script>
async function api(path,opts){const r=await fetch(path,opts);if(r.status===401){location.href='/login';throw 0;}return r;}
function fmt(ms){if(!ms)return '—';const d=new Date(ms);return d.toLocaleString('he-IL');}

async function load(){
  const [dev,req] = await Promise.all([
    (await api('/api/admin/devices')).json(),
    (await api('/api/admin/requests')).json()
  ]);
  renderRequests(req.requests||[]);
  renderDevices(dev.devices||[]);
}

function renderRequests(list){
  const el=document.getElementById('requests');
  if(!list.length){el.innerHTML='<span class="muted">אין בקשות פתוחות</span>';return;}
  el.innerHTML='<table><tr><th>מכשיר</th><th>סיבה</th><th>מתי</th><th></th></tr>'+
    list.map(r=>'<tr><td>'+esc(r.device_id)+'</td><td>'+esc(r.reason||'')+'</td><td>'+
    fmt(r.ts)+'</td><td><button class="ghost" onclick="resolve('+r.id+')">סמן כטופל</button></td></tr>').join('')+'</table>';
}

function renderDevices(list){
  const el=document.getElementById('devices');
  if(!list.length){el.innerHTML='<span class="muted">עדיין אין מכשירים. מכשיר יופיע כאן אחרי שיפנה לשרת.</span>';return;}
  el.innerHTML = list.map(d=>{
    const released = d.unlock_until && d.unlock_until>Date.now();
    const on = d.enabled && !released;
    return '<div class="card" style="background:#0b1220">'+
      '<div class="row" style="justify-content:space-between">'+
        '<div><strong>'+esc(d.label||d.id)+'</strong><div class="muted">'+esc(d.id)+'</div></div>'+
        '<span class="pill '+(on?'on':'off')+'">'+(on?'סינון פעיל':(released?'משוחרר זמנית':'כבוי'))+'</span>'+
      '</div>'+
      '<div class="muted" style="margin-top:6px">נראה לאחרונה: '+fmt(d.last_seen)+
        (released?(' · שחרור עד '+fmt(d.unlock_until)):'')+'</div>'+
      '<div class="row" style="margin-top:10px">'+
        '<button class="'+(d.enabled?'danger':'ok')+'" onclick="toggle(\\''+d.id+'\\','+(d.enabled?0:1)+')">'+
          (d.enabled?'כבה סינון':'הפעל סינון')+'</button>'+
        '<select id="dur_'+d.id+'"><option value="15">15 דק\\'</option><option value="60">שעה</option>'+
          '<option value="240">4 שעות</option><option value="1440">יום</option></select>'+
        '<button class="ghost" onclick="release(\\''+d.id+'\\')">שחרר זמנית</button>'+
        '<button class="ghost" onclick="clearRelease(\\''+d.id+'\\')">בטל שחרור</button>'+
      '</div>'+
      '<div class="row" style="margin-top:8px">'+
        '<input id="label_'+d.id+'" placeholder="שם/תווית" value="'+esc(d.label||'')+'" style="max-width:200px">'+
        '<input id="contact_'+d.id+'" placeholder="טלפון מנהל" value="'+esc(d.manager_contact||'')+'" style="max-width:200px">'+
        '<button class="ghost" onclick="saveMeta(\\''+d.id+'\\')">שמור</button>'+
      '</div>'+
    '</div>';
  }).join('');
}

function esc(s){return String(s==null?'':s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}

async function post(path,body){return api(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body||{})});}
async function toggle(id,enabled){await post('/api/admin/device/'+id,{enabled});load();}
async function release(id){const m=parseInt(document.getElementById('dur_'+id).value,10)||15;
  await post('/api/admin/device/'+id,{unlockUntil:Date.now()+m*60000});load();}
async function clearRelease(id){await post('/api/admin/device/'+id,{unlockUntil:0});load();}
async function saveMeta(id){await post('/api/admin/device/'+id,
  {label:document.getElementById('label_'+id).value,managerContact:document.getElementById('contact_'+id).value});load();}
async function resolve(rid){await post('/api/admin/request/'+rid+'/resolve',{});load();}
async function logout(){await post('/api/admin/logout',{});location.href='/login';}
load();
</script>`);
}
