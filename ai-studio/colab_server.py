# ============================================================================
#  סטודיו AI - שרת ליצירת תמונות וסרטונים (Google Colab, כרטיס T4 חינמי)
#  תמונה: SDXL-Turbo | סרטון מתמונה: LTX-Video | שרשור מקטעים עד 60 שניות
#  הדביקו את כל הקובץ לתא אחד ב-Colab (Runtime > Change runtime type > T4 GPU)
# ============================================================================

# ---------- 1. התקנת ספריות (גרסאות נעולות ותואמות ל-JS) ----------
import subprocess, sys
subprocess.run([sys.executable, "-m", "pip", "install", "-q", "--no-warn-conflicts",
    "diffusers==0.33.1", "transformers==4.51.3", "accelerate==1.6.0",
    "huggingface_hub==0.30.2", "gradio==5.29.0", "pydantic==2.10.6",
    "sentencepiece", "sacremoses", "protobuf",
    "imageio==2.37.0", "imageio-ffmpeg==0.6.0", "opencv-python"], check=True)

import os, gc, io, re, json, math, time, uuid, base64, shutil, threading, traceback
os.environ["PYTORCH_CUDA_ALLOC_CONF"] = "expandable_segments:True"
os.environ["HF_HUB_ENABLE_HF_TRANSFER"] = "0"
import numpy as np
import cv2
import torch
import imageio
import imageio_ffmpeg
from PIL import Image, ImageOps
import gradio as gr
from huggingface_hub import snapshot_download
from diffusers import AutoPipelineForText2Image, LTXImageToVideoPipeline
from transformers import T5EncoderModel, T5Tokenizer, MarianMTModel, MarianTokenizer

assert torch.cuda.is_available(), "❌ לא נמצא GPU. בחרו Runtime > Change runtime type > T4 GPU"
DEVICE = "cuda"
FFMPEG = imageio_ffmpeg.get_ffmpeg_exe()

IMAGE_MODEL = "stabilityai/sdxl-turbo"
VIDEO_MODEL = "Lightricks/LTX-Video"
TRANSLATE_MODEL = "Helsinki-NLP/opus-mt-tc-big-he-en"
T5_LOCAL = "/content/t5_bf16"          # עותק bf16 מקומי של מקודד הטקסט (טעינה מהירה)
OUT_DIR = "/content/ai_outputs"
os.makedirs(OUT_DIR, exist_ok=True)

FPS = 24
SEGMENT_SECONDS = 5
SEGMENT_FRAMES = 121                    # 5 שניות ב-24fps (חייב להיות 8k+1)
VIDEO_DTYPE = torch.bfloat16
NEGATIVE_VIDEO = "worst quality, inconsistent motion, blurry, jittery, distorted, deformed, watermark, text"
ASPECTS = {"1:1": (640, 640), "9:16": (512, 896), "16:9": (896, 512), "4:5": (576, 704), "3:4": (576, 768)}
QUALITY_STEPS = {"fast": 20, "quality": 30}

GPU_LOCK = threading.Lock()
JOBS = {}
JOBS_LOCK = threading.Lock()


def free_memory():
    gc.collect()
    torch.cuda.empty_cache()


def vram_text():
    free, total = torch.cuda.mem_get_info()
    return f"{(total - free) / 1024**3:.1f}/{total / 1024**3:.1f} GB"


# ---------- 2. הורדת מודלים מראש ----------
print("⬇️  מוריד מודלים (בפעם הראשונה ~35GB, כ-5-10 דקות)...")
snapshot_download(IMAGE_MODEL, allow_patterns=["model_index.json", "*/*.json", "*/*.txt", "*/*.fp16.safetensors"])
snapshot_download(VIDEO_MODEL, allow_patterns=["model_index.json", "scheduler/*", "tokenizer/*", "transformer/*", "vae/*", "text_encoder/*"])
snapshot_download(TRANSLATE_MODEL, allow_patterns=["*.json", "*.spm", "model.safetensors"])

# ---------- 3. מתרגם עברית->אנגלית (רץ על CPU) ----------
translator = {"tok": None, "model": None}
try:
    translator["tok"] = MarianTokenizer.from_pretrained(TRANSLATE_MODEL)
    translator["model"] = MarianMTModel.from_pretrained(TRANSLATE_MODEL).eval()
    print("✅ מתרגם עברית נטען")
except Exception as e:
    print("⚠️ המתרגם לא נטען, טקסט יישלח כפי שהוא:", e)

HEB_RE = re.compile(r"[֐-׿]")


def to_english(text):
    text = (text or "").strip()
    if not text or not HEB_RE.search(text) or translator["model"] is None:
        return text
    with torch.no_grad():
        batch = translator["tok"]([text], return_tensors="pt", truncation=True, max_length=256)
        out = translator["model"].generate(**batch, max_new_tokens=256, num_beams=4)
    return translator["tok"].decode(out[0], skip_special_tokens=True)


# ---------- 4. טעינת מודל הווידאו (LTX-Video) ל-GPU, ללא מקודד הטקסט ----------
print("🎬 טוען LTX-Video...")
video_pipe = LTXImageToVideoPipeline.from_pretrained(
    VIDEO_MODEL, text_encoder=None, tokenizer=None, torch_dtype=VIDEO_DTYPE)
video_pipe.to(DEVICE)
video_pipe.vae.enable_tiling()
video_pipe.vae.enable_slicing()
video_pipe.set_progress_bar_config(disable=True)
t5_tokenizer = T5Tokenizer.from_pretrained(VIDEO_MODEL, subfolder="tokenizer")
free_memory()

# מקודד הטקסט T5-XXL כבד (9.5GB). הוא נטען ל-GPU רק לרגע הקידוד ומשוחרר מיד - כך אין OOM.
if not os.path.exists(os.path.join(T5_LOCAL, "config.json")):
    print("🧠 מכין עותק bf16 של מקודד הטקסט (פעם אחת)...")
    _t5 = T5EncoderModel.from_pretrained(VIDEO_MODEL, subfolder="text_encoder",
                                         torch_dtype=VIDEO_DTYPE, device_map=DEVICE)
    _t5.save_pretrained(T5_LOCAL, max_shard_size="2GB")
    del _t5
    free_memory()


@torch.no_grad()
def encode_video_prompts(prompt, negative):
    encoder = T5EncoderModel.from_pretrained(T5_LOCAL, torch_dtype=VIDEO_DTYPE, device_map=DEVICE).eval()
    results = []
    for text in (prompt, negative):
        tok = t5_tokenizer([text], padding="max_length", max_length=128, truncation=True,
                           add_special_tokens=True, return_tensors="pt")
        emb = encoder(tok.input_ids.to(DEVICE))[0].to(dtype=VIDEO_DTYPE, device=DEVICE)
        mask = tok.attention_mask.bool().to(DEVICE)
        results.append((emb, mask))
    del encoder
    free_memory()
    return results


# ---------- 5. טעינת מודל התמונה (SDXL-Turbo) עם CPU offload ----------
print("🖼️  טוען SDXL-Turbo...")
image_pipe = AutoPipelineForText2Image.from_pretrained(IMAGE_MODEL, torch_dtype=torch.float16, variant="fp16")
image_pipe.enable_model_cpu_offload()
image_pipe.enable_vae_slicing()
image_pipe.set_progress_bar_config(disable=True)
free_memory()
print("✅ כל המודלים נטענו. זיכרון GPU:", vram_text())


# ---------- 6. כלי עזר לתמונות ----------
def decode_b64_image(data):
    data = (data or "").strip()
    if not data:
        raise ValueError("לא התקבלה תמונה")
    if "," in data and data.startswith("data:"):
        data = data.split(",", 1)[1]
    raw = base64.b64decode(data)
    if len(raw) > 25 * 1024 * 1024:
        raise ValueError("הקובץ גדול מדי (מעל 25MB)")
    img = Image.open(io.BytesIO(raw))
    img = ImageOps.exif_transpose(img).convert("RGB")
    return img


def encode_b64_image(img, fmt="JPEG"):
    buf = io.BytesIO()
    img.save(buf, format=fmt, quality=95)
    mime = "image/jpeg" if fmt == "JPEG" else "image/png"
    return f"data:{mime};base64," + base64.b64encode(buf.getvalue()).decode()


def video_size_for(img):
    ar = img.width / img.height
    if ar > 1.15:
        return 704, 480
    if ar < 0.87:
        return 480, 704
    return 576, 576


def sharpen(rgb, amount=0.35):
    blur = cv2.GaussianBlur(rgb, (0, 0), 1.0)
    return cv2.addWeighted(rgb, 1 + amount, blur, -amount, 0)


def color_match(rgb, ref, strength=0.5):
    """התאמת צבעים עדינה לתמונת המקור כדי למנוע סחיפת צבע בין המקטעים"""
    src = cv2.cvtColor(rgb, cv2.COLOR_RGB2LAB).astype(np.float32)
    tgt = cv2.cvtColor(ref, cv2.COLOR_RGB2LAB).astype(np.float32)
    s_mean, s_std = src.mean((0, 1)), src.std((0, 1)) + 1e-5
    t_mean, t_std = tgt.mean((0, 1)), tgt.std((0, 1)) + 1e-5
    matched = (src - s_mean) / s_std * t_std + t_mean
    out = src * (1 - strength) + matched * strength
    return cv2.cvtColor(np.clip(out, 0, 255).astype(np.uint8), cv2.COLOR_LAB2RGB)


def extract_last_frame(video_path):
    """חילוץ הפריים האחרון מקובץ MP4 באמצעות OpenCV"""
    cap = cv2.VideoCapture(video_path)
    last = None
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        last = frame
    cap.release()
    if last is None:
        raise RuntimeError("לא ניתן לקרוא את המקטע שנוצר")
    return cv2.cvtColor(last, cv2.COLOR_BGR2RGB)


def write_mp4(frames_uint8, path):
    writer = imageio.get_writer(path, fps=FPS, codec="libx264", macro_block_size=1,
                                ffmpeg_params=["-crf", "17", "-preset", "veryfast", "-pix_fmt", "yuv420p"])
    for f in frames_uint8:
        writer.append_data(f)
    writer.close()


def concat_mp4(paths, out_path):
    list_file = out_path + ".txt"
    with open(list_file, "w") as fh:
        for p in paths:
            fh.write(f"file '{p}'\n")
    cmd = [FFMPEG, "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", list_file,
           "-c", "copy", "-movflags", "+faststart", out_path]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        cmd = [FFMPEG, "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", list_file,
               "-c:v", "libx264", "-crf", "17", "-pix_fmt", "yuv420p", "-movflags", "+faststart", out_path]
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode != 0:
            raise RuntimeError("איחוד המקטעים נכשל: " + r.stderr[-300:])
    os.remove(list_file)


# ---------- 7. יצירת תמונה ----------
def generate_image(prompt, aspect="1:1", seed=-1):
    prompt = (prompt or "").strip()
    if not prompt:
        raise ValueError("נא לכתוב תיאור לתמונה")
    w, h = ASPECTS.get(aspect, ASPECTS["1:1"])
    seed = int(seed) if seed is not None and int(seed) >= 0 else int(time.time() * 1000) % 2**31
    if not GPU_LOCK.acquire(timeout=180):
        raise RuntimeError("השרת עסוק ביצירת סרטון. נסו שוב בסיום")
    try:
        prompt_en = to_english(prompt)
        g = torch.Generator("cpu").manual_seed(seed)
        img = image_pipe(prompt=prompt_en, width=w, height=h, num_inference_steps=4,
                         guidance_scale=0.0, generator=g).images[0]
    except torch.cuda.OutOfMemoryError:
        raise RuntimeError("אין מספיק זיכרון GPU. נסו יחס תמונה קטן יותר")
    finally:
        free_memory()
        GPU_LOCK.release()
    return img, {"seed": seed, "prompt_en": prompt_en, "width": w, "height": h}


# ---------- 8. יצירת סרטון ארוך בשרשור מקטעים (Frame Chaining) ----------
def new_job(total_segments, steps):
    job_id = uuid.uuid4().hex[:12]
    with JOBS_LOCK:
        JOBS[job_id] = {"job_id": job_id, "status": "queued", "segment": 0, "total_segments": total_segments,
                        "step": 0, "total_steps": steps, "progress": 0.0, "message": "ממתין להתחלה...",
                        "error": None, "video_path": None, "started": time.time(), "cancel": False}
    return job_id


def update_job(job_id, **kw):
    with JOBS_LOCK:
        job = JOBS[job_id]
        job.update(kw)
        segs, steps = job["total_segments"], max(1, job["total_steps"])
        if job["status"] == "running" and job["segment"] > 0:
            done = (job["segment"] - 1) + min(job["step"], steps) / steps
            job["progress"] = round(min(99.0, 100.0 * done / segs), 1)


def run_video_job(job_id, img, prompt, duration, seed, steps):
    seg_paths = []
    try:
        update_job(job_id, status="running", message="מתרגם ומקודד את תיאור התנועה...")
        prompt_en = to_english(prompt) or "natural smooth motion, cinematic"
        (pe, pm), (ne, nm) = encode_video_prompts(prompt_en, NEGATIVE_VIDEO)
        w, h = video_size_for(img)
        current = ImageOps.fit(img, (w, h), Image.LANCZOS)
        reference = np.array(current)
        total = JOBS[job_id]["total_segments"]
        work_dir = os.path.join(OUT_DIR, job_id)
        os.makedirs(work_dir, exist_ok=True)

        for i in range(total):
            if JOBS[job_id]["cancel"]:
                raise RuntimeError("היצירה בוטלה")
            update_job(job_id, segment=i + 1, step=0,
                       message=f"מעבד מקטע {i + 1} מתוך {total}")

            def on_step(pipe, step, timestep, kwargs, _i=i):
                update_job(job_id, step=step + 1,
                           message=f"מעבד מקטע {_i + 1} מתוך {total} (צעד {step + 1}/{steps})")
                if JOBS[job_id]["cancel"]:
                    pipe._interrupt = True
                return kwargs

            g = torch.Generator(DEVICE).manual_seed(seed + i)
            try:
                result = video_pipe(image=current, prompt_embeds=pe, prompt_attention_mask=pm,
                                    negative_prompt_embeds=ne, negative_prompt_attention_mask=nm,
                                    width=w, height=h, num_frames=SEGMENT_FRAMES,
                                    num_inference_steps=steps, guidance_scale=3.0,
                                    decode_timestep=0.03, decode_noise_scale=0.025,
                                    generator=g, output_type="np", callback_on_step_end=on_step)
            except torch.cuda.OutOfMemoryError:
                free_memory()
                raise RuntimeError("אין מספיק זיכרון GPU. נסו מצב 'מהיר' או סרטון קצר יותר")
            if JOBS[job_id]["cancel"]:
                raise RuntimeError("היצירה בוטלה")
            frames = (np.clip(result.frames[0], 0, 1) * 255).round().astype(np.uint8)
            del result
            free_memory()
            if i > 0:
                frames = frames[1:]       # הפריים הראשון זהה לסוף המקטע הקודם - מדלגים למניעת "קפיצה"
            seg_path = os.path.join(work_dir, f"seg_{i:02d}.mp4")
            write_mp4(frames, seg_path)
            seg_paths.append(seg_path)
            if i < total - 1:
                last = extract_last_frame(seg_path)
                last = color_match(sharpen(last), reference, 0.5)
                current = Image.fromarray(last)

        update_job(job_id, message="מאחד את כל המקטעים לסרטון אחד...", step=steps)
        final_path = os.path.join(OUT_DIR, f"video_{job_id}.mp4")
        concat_mp4(seg_paths, final_path)
        shutil.rmtree(work_dir, ignore_errors=True)
        update_job(job_id, status="done", progress=100.0, video_path=final_path,
                   message=f"הסרטון מוכן! ({duration} שניות)")
    except Exception as e:
        traceback.print_exc()
        update_job(job_id, status="error", error=str(e), message="שגיאה: " + str(e))
    finally:
        free_memory()
        GPU_LOCK.release()


def start_video(img, prompt, duration, seed=-1, quality="fast"):
    duration = int(max(5, min(60, int(float(duration)))))
    duration = int(math.ceil(duration / 5.0) * 5)
    segments = duration // SEGMENT_SECONDS
    steps = QUALITY_STEPS.get(quality, QUALITY_STEPS["fast"])
    seed = int(seed) if seed is not None and int(seed) >= 0 else int(time.time() * 1000) % 2**31
    if not GPU_LOCK.acquire(blocking=False):
        raise RuntimeError("השרת עסוק ביצירה אחרת. המתינו לסיומה")
    try:
        job_id = new_job(segments, steps)
        threading.Thread(target=run_video_job, args=(job_id, img, prompt, duration, seed, steps),
                         daemon=True).start()
    except Exception:
        GPU_LOCK.release()
        raise
    return job_id


def job_public(job_id):
    with JOBS_LOCK:
        job = JOBS.get(job_id)
        if not job:
            return None
        data = {k: v for k, v in job.items() if k not in ("video_path", "cancel")}
    data["elapsed"] = int(time.time() - job["started"])
    return data


# ---------- 9. פונקציות API (קלט/פלט טקסט JSON - פשוט ויציב לקריאה מ-JS) ----------
def ok(**kw):
    return json.dumps({"ok": True, **kw}, ensure_ascii=False)


def fail(msg):
    return json.dumps({"ok": False, "error": str(msg)}, ensure_ascii=False)


def api_health():
    busy = GPU_LOCK.locked()
    return ok(status="online", gpu=torch.cuda.get_device_name(0), vram=vram_text(), busy=busy,
              max_seconds=60, segment_seconds=SEGMENT_SECONDS, aspects=list(ASPECTS.keys()))


def api_generate_image(prompt, aspect, seed):
    try:
        img, info = generate_image(prompt, aspect, int(float(seed or -1)))
        return ok(image=encode_b64_image(img), **info)
    except Exception as e:
        return fail(e)


def api_start_video(image_b64, prompt, duration, seed, quality):
    try:
        img = decode_b64_image(image_b64)
        job_id = start_video(img, prompt, duration, int(float(seed or -1)), quality or "fast")
        return ok(job_id=job_id, **job_public(job_id))
    except Exception as e:
        return fail(e)


def api_video_status(job_id):
    job = job_public((job_id or "").strip())
    if job is None:
        return fail("משימה לא נמצאה (ייתכן שהשרת הופעל מחדש)"), None
    path = JOBS[job["job_id"]]["video_path"] if job["status"] == "done" else None
    return ok(**job), path


def api_cancel_video(job_id):
    with JOBS_LOCK:
        job = JOBS.get((job_id or "").strip())
        if not job:
            return fail("משימה לא נמצאה")
        job["cancel"] = True
    return ok(message="הבקשה לביטול נשלחה")


# ---------- 10. ממשק Gradio (עובד גם ישירות מהקישור) ----------
def ui_image(prompt, aspect, seed, progress=gr.Progress()):
    progress(0.1, desc="יוצר תמונה...")
    try:
        img, info = generate_image(prompt, aspect, int(seed))
    except Exception as e:
        raise gr.Error(str(e))
    return img, f"✅ seed={info['seed']} | {info['prompt_en']}"


def ui_video(img, prompt, duration, quality, seed, progress=gr.Progress()):
    if img is None:
        raise gr.Error("נא להעלות תמונה")
    try:
        job_id = start_video(Image.fromarray(img).convert("RGB"), prompt, duration, int(seed), quality)
    except Exception as e:
        raise gr.Error(str(e))
    while True:
        job = job_public(job_id)
        progress(job["progress"] / 100.0, desc=job["message"])
        if job["status"] == "done":
            yield JOBS[job_id]["video_path"], "✅ " + job["message"]
            return
        if job["status"] == "error":
            raise gr.Error(job["error"])
        yield None, f"⏳ {job['message']} | {job['progress']}%"
        time.sleep(2)


# פתיחת CORS לכל מקור (כדי שאפליקציית ה-HTML תוכל לפנות לשרת מכל דפדפן, כולל file://)
try:
    from gradio import route_utils
    route_utils.CustomCORSMiddleware.is_valid_origin = lambda self, headers: True
except Exception as e:
    print("⚠️ CORS patch:", e)

CSS = "body, .gradio-container {direction: rtl; text-align: right;}"
with gr.Blocks(title="סטודיו AI", css=CSS, theme=gr.themes.Soft()) as demo:
    gr.Markdown("# 🎬 סטודיו AI\nיצירת תמונות וסרטונים (עד 60 שניות). אפשר לעבוד מכאן או מאפליקציית ה-HTML.")
    with gr.Tab("יצירת תמונה"):
        i_prompt = gr.Textbox(label="תיאור התמונה (עברית או אנגלית)", lines=3)
        with gr.Row():
            i_aspect = gr.Dropdown(list(ASPECTS.keys()), value="1:1", label="יחס תמונה")
            i_seed = gr.Number(value=-1, label="Seed (-1 = אקראי)", precision=0)
        i_btn = gr.Button("✨ צור תמונה", variant="primary")
        i_out = gr.Image(label="תוצאה", type="pil", format="png")
        i_info = gr.Markdown()
    with gr.Tab("יצירת סרטון מתמונה"):
        v_img = gr.Image(label="תמונת פתיחה", type="numpy")
        v_prompt = gr.Textbox(label="תיאור התנועה", lines=2)
        with gr.Row():
            v_dur = gr.Slider(5, 60, value=5, step=5, label="משך (שניות)")
            v_quality = gr.Radio([("מהיר", "fast"), ("איכותי", "quality")], value="fast", label="איכות")
            v_seed = gr.Number(value=-1, label="Seed", precision=0)
        v_btn = gr.Button("🎥 צור סרטון", variant="primary")
        v_status = gr.Markdown()
        v_out = gr.Video(label="סרטון")
    i_btn.click(ui_image, [i_prompt, i_aspect, i_seed], [i_out, i_info], api_name=False)
    i_out.change(lambda x: x, i_out, v_img, api_name=False)
    v_btn.click(ui_video, [v_img, v_prompt, v_dur, v_quality, v_seed], [v_out, v_status], api_name=False)

    # נקודות קצה API נסתרות עבור אפליקציית ה-HTML
    with gr.Row(visible=False):
        a1, a2, a3, a4, a5 = [gr.Textbox() for _ in range(5)]
        a_out = gr.Textbox()
        a_file = gr.File()
        hb = [gr.Button() for _ in range(5)]
    hb[0].click(api_health, None, a_out, api_name="health", concurrency_limit=20)
    hb[1].click(api_generate_image, [a1, a2, a3], a_out, api_name="generate_image", concurrency_limit=2)
    hb[2].click(api_start_video, [a1, a2, a3, a4, a5], a_out, api_name="start_video", concurrency_limit=2)
    hb[3].click(api_video_status, [a1], [a_out, a_file], api_name="video_status", concurrency_limit=20)
    hb[4].click(api_cancel_video, [a1], a_out, api_name="cancel_video", concurrency_limit=5)

demo.queue(max_size=64)
demo.launch(share=True, prevent_thread_lock=True, allowed_paths=[OUT_DIR], show_error=True, quiet=True)
time.sleep(2)
print("\n" + "=" * 60)
print("✅ השרת פועל! העתיקו את הקישור הבא לאפליקציה בטלפון:")
print("   ", demo.share_url)
print("=" * 60)
print("השאירו את התא הזה רץ. הקישור תקף כ-72 שעות או עד לכיבוי ה-Colab.")
try:
    while True:
        time.sleep(60)
except KeyboardInterrupt:
    demo.close()
