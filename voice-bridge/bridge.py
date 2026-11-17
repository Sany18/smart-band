#!/usr/bin/env python3
"""Watch voice -> Claude bridge.

Runs on the Mac. The patched Mi Fitness on the phone POSTs the audio the band
recorded (16 kHz mono PCM s16le, or a WAV), the bridge transcribes it with
whisper.cpp, hands the text to Claude Code in headless mode (`claude -p`, which
uses the subscription the CLI is logged in with) and returns a short plain-text
reply sized for the band screen.

Endpoints (all need header `X-Token: $BRIDGE_TOKEN`):
  GET  /health                      -> {"ok": true, "stt": bool}
  POST /stt?rate=16000              body: raw PCM s16le mono, or audio/wav
                                    -> {"asr": "..."}
  POST /voice?rate=16000            same body -> {"asr": "...", "reply": "..."}
  POST /text                        body: {"text": "..."} -> {"reply": "..."}
  POST /reset                       start a new Claude conversation

Stdlib only. Config via environment variables, see README.md.
"""

from __future__ import annotations

import array
import datetime as dt
import hmac
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse
from zoneinfo import ZoneInfo

HOST = os.environ.get("BRIDGE_HOST", "0.0.0.0")
PORT = int(os.environ.get("BRIDGE_PORT", "8787"))
TOKEN = os.environ.get("BRIDGE_TOKEN", "")
TZ = ZoneInfo(os.environ.get("BRIDGE_TZ", "Europe/Kyiv"))

# STT backend:
#   whisper-cli    – whisper.cpp binary + ggml model (Mac / Metal)
#   faster-whisper – in-process CTranslate2 model, loaded once at start (Linux CPU, e.g. X1)
STT_BACKEND = os.environ.get("STT_BACKEND", "whisper-cli")
WHISPER_BIN = os.environ.get("WHISPER_BIN") or shutil.which("whisper-cli") or ""
WHISPER_MODEL = os.path.expanduser(
    os.environ.get("WHISPER_MODEL", "~/.cache/whisper/ggml-large-v3-turbo-q5_0.bin")
)
# Allowed spoken languages, first = fallback. Russian is never allowed: a single language is
# forced; with several, whisper's detection is used only if it lands in this list.
STT_LANGS = [l.strip() for l in os.environ.get(
    "STT_LANGS", os.environ.get("WHISPER_LANG", "uk")).split(",")
    if l.strip() and l.strip() not in ("auto", "ru")] or ["uk"]
# Letters that exist in Russian but not in Ukrainian, plus whisper's classic hallucinations
# on near-silent audio (it was trained on lots of subtitled Russian video).
RUSSIAN_ONLY = set("ыэъёЫЭЪЁ")
# Whisper's ru-probability can't tell Ukrainian from Russian on the band's mic (Ukrainian
# scored ru p up to 0.79, Russian 0.71), so rejection by detection is off by default (>1).
RU_REJECT_P = float(os.environ.get("RU_REJECT_P", "1.01"))
EN_MIN_P = float(os.environ.get("EN_MIN_P", "0.5"))   # decode as English only above this
# Common words that exist in Russian but not in Ukrainian. Russian speech forced into
# Ukrainian keeps some of them ("Та, за чей ти працюєш?"), so they mark a Russian phrase.
RUSSIAN_WORDS = set("""что чего чем чей чья чьё чьи зачем почему какой какая какое какие
каким как кто где когда сколько только тоже очень хорошо нет сейчас сегодня меня тебя себя
мне тебе мной привет спасибо пожалуйста можно нужно еще ещё вот и""".split())
LANG_REJECT_REPLY = os.environ.get("LANG_REJECT_REPLY", "Розпізнаю лише українську та англійську")
HALLUCINATIONS = ("продолжение следует", "субтитры", "редактор субтитров", "спасибо за просмотр",
                  "дякую за перегляд", "субтитри", "звучить музика")
FW_MODEL = os.environ.get("FW_MODEL", "large-v3-turbo")      # faster-whisper model name/path
FW_COMPUTE = os.environ.get("FW_COMPUTE", "int8")
FW_THREADS = int(os.environ.get("FW_THREADS", "0"))           # 0 = library default
FW_BEAM = int(os.environ.get("FW_BEAM", "1"))

CLAUDE_BIN = os.environ.get("CLAUDE_BIN") or shutil.which("claude") or "claude"
CLAUDE_MODEL = os.environ.get("CLAUDE_MODEL", "haiku")        # voice wants speed; "" = CLI default
CLAUDE_CWD = os.path.expanduser(os.environ.get("CLAUDE_CWD", "~"))
CLAUDE_EXTRA_ARGS = os.environ.get("CLAUDE_EXTRA_ARGS", "").split()
CLAUDE_TIMEOUT = int(os.environ.get("CLAUDE_TIMEOUT", "180"))
SESSION_IDLE = int(os.environ.get("SESSION_IDLE", "600"))  # new conversation after N s idle

# The band truncates speak/toast text at 300 chars; leave margin for UTF-8.
REPLY_MAX = int(os.environ.get("REPLY_MAX", "280"))
MAX_BODY = 8 * 1024 * 1024

SYSTEM_PROMPT = """\
Запити надходять голосом з фітнес-браслета користувача (Xiaomi Smart Band 9 Pro),
розпізнані автоматично — можливі помилки розпізнавання, вгадуй розумний зміст.
Користувач в Україні, часовий пояс {tz}. Зараз {now}. На початку кожного запиту
в дужках актуальний час — завжди бери час звідти, а не з попередніх відповідей.
Виконуй прохання як агент, якщо для цього є інструменти. Про повідомлення, чати,
листи (Telegram, Discord тощо) — дивись через інструменти inbox_* (inbox_digest або
inbox_chats). Архів оновлюється у фоні кожні 3 хвилини; inbox_sync викликай лише якщо
питають про щось за останні хвилини («щойно», «зараз написав»). Надсилати повідомлення ти не
можеш — не пропонуй відповісти за користувача. Погоду бери ОДНИМ запитом WebFetch до
https://wttr.in/<Місто>?format=j2&lang=uk (місто латиницею, за замовчуванням Kyiv), без
пошуку в інтернеті — відповідь потрібна за кілька секунд. Фінальна відповідь
показується на маленькому екрані: простий текст без markdown, без списків,
не довше {limit} символів, мовою запиту (українською або англійською).
Ніколи не відповідай російською, навіть якщо запит схожий на російську."""

_session_lock = threading.Lock()
# The newest voice request wins: a new /text kills the agent still working on the previous
# one (the band has usually closed that dialog already, and it would block the queue).
_running = {"proc": None, "gen": 0}
_running_lock = threading.Lock()


class Superseded(Exception):
    """A newer request replaced this one."""
_session = {"id": None, "last": 0.0}


def log(*a):
    print(dt.datetime.now(TZ).strftime("%H:%M:%S"), *a, file=sys.stderr, flush=True)


_fw_model = None
_fw_lock = threading.Lock()


def load_stt() -> None:
    """Load the in-process model up front so the first request isn't slow."""
    global _fw_model
    if STT_BACKEND != "faster-whisper":
        return
    from faster_whisper import WhisperModel  # imported lazily: optional dependency

    t = time.time()
    _fw_model = WhisperModel(FW_MODEL, device="cpu", compute_type=FW_COMPUTE,
                             cpu_threads=FW_THREADS)
    log(f"faster-whisper {FW_MODEL}/{FW_COMPUTE} loaded in {time.time() - t:.1f}s")


def stt_available() -> bool:
    if STT_BACKEND == "faster-whisper":
        return _fw_model is not None
    return bool(WHISPER_BIN) and os.path.isfile(WHISPER_MODEL)


class LanguageRejected(Exception):
    """Speech is (confidently) Russian: don't transcribe it at all."""


def transcribe(wav_path: str) -> str:
    """Speech -> text, never Russian (see STT_LANGS / RU_REJECT_P / reject_russian).
    Raises LanguageRejected for confidently-Russian speech."""
    if not stt_available():
        raise RuntimeError(f"STT backend {STT_BACKEND} not ready (see README)")
    t = time.time()
    lang = pick_language(wav_path)
    text = _transcribe(wav_path, lang)
    log(f"stt {time.time() - t:.1f}s lang={lang}")
    return reject_russian(text)


def reject_russian(text: str) -> str:
    low = text.lower()
    words = set(re.findall(r"[а-яёіїєґ']+", low))
    if (any(ch in RUSSIAN_ONLY for ch in text) or any(h in low for h in HALLUCINATIONS)
            or words & RUSSIAN_WORDS):
        log(f"rejected (russian/hallucination): {text!r}")
        return ""
    return text


def pick_language(wav_path: str) -> str:
    """Choose the decoding language: English only when whisper is fairly sure, otherwise
    the first allowed language (uk). Whisper's language ID is unreliable on the band's quiet
    mic (it calls Ukrainian ru/es/ja), so Russian is caught after decoding by reject_russian;
    RU_REJECT_P can additionally refuse very confident ru detections."""
    detected, prob = _detect_language(wav_path)
    if detected == "ru" and prob >= RU_REJECT_P:
        log(f"rejected: detected ru p={prob:.2f} >= {RU_REJECT_P}")
        raise LanguageRejected()
    if detected in STT_LANGS and (detected != "en" or prob >= EN_MIN_P):
        lang = detected
    else:
        lang = STT_LANGS[0]
    log(f"detected {detected!r} p={prob:.2f} -> {lang}")
    return lang


def _detect_language(wav_path: str) -> tuple[str, float]:
    if STT_BACKEND == "faster-whisper":
        with _fw_lock:
            _segs, info = _fw_model.transcribe(wav_path, beam_size=1)
        return info.language or "", float(info.language_probability or 0)
    out = subprocess.run([WHISPER_BIN, "-m", WHISPER_MODEL, "-l", "auto", "-dl",
                          "-f", wav_path], capture_output=True, text=True, timeout=60)
    m = re.search(r"auto-detected language:\s*([a-z]{2})\s*\(p\s*=\s*([0-9.]+)\)",
                  out.stdout + out.stderr)
    return (m.group(1), float(m.group(2))) if m else ("", 0.0)


def _transcribe(wav_path: str, lang: str) -> str:
    if STT_BACKEND == "faster-whisper":
        with _fw_lock:
            segments, _info = _fw_model.transcribe(
                wav_path, language=lang, beam_size=FW_BEAM, vad_filter=False,
                condition_on_previous_text=False)
            return " ".join(s.text.strip() for s in segments).strip()
    cmd = [WHISPER_BIN, "-m", WHISPER_MODEL, "-l", lang, "-nt", "-np", "-f", wav_path]
    out = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    if out.returncode != 0:
        raise RuntimeError(f"whisper failed: {out.stderr.strip()[-300:]}")
    return " ".join(out.stdout.split()).strip()


def normalize_pcm(pcm: bytes, target_peak: int = 23000, max_gain: float = 30.0) -> bytes:
    """Scale s16le PCM so its peak is ~-3 dBFS. The band's mic is very quiet (peaks around
    -34 dBFS), which noticeably hurts whisper accuracy."""
    samples = array.array("h")
    samples.frombytes(pcm[: len(pcm) // 2 * 2])
    if sys.byteorder != "little":
        samples.byteswap()
    peak = max((abs(s) for s in samples), default=0)
    if peak == 0:
        return pcm
    gain = min(max_gain, target_peak / peak)
    if gain <= 1.05:
        return pcm
    out = array.array("h", (max(-32768, min(32767, int(s * gain))) for s in samples))
    if sys.byteorder != "little":
        out.byteswap()
    log(f"normalize: peak {peak} -> x{gain:.1f}")
    return out.tobytes()


def pcm_to_wav(pcm: bytes, rate: int, path: str) -> None:
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm)


def shorten(text: str) -> str:
    text = " ".join(text.replace("**", "").replace("`", "").split())
    if len(text) <= REPLY_MAX:
        return text
    cut = text[: REPLY_MAX - 1]
    sp = cut.rfind(" ")
    return (cut[:sp] if sp > REPLY_MAX * 0.6 else cut) + "…"


def ask_claude(prompt: str) -> str:
    if os.environ.get("BRIDGE_ECHO"):  # debug: answer without Claude, optional fake delay
        time.sleep(float(os.environ.get("BRIDGE_ECHO_DELAY", "0")))
        return shorten(f"Ви сказали: {prompt}")
    with _running_lock:
        _running["gen"] += 1
        gen = _running["gen"]
        if _running["proc"] and _running["proc"].poll() is None:
            log("claude: newer request, stopping the previous one")
            _running["proc"].kill()
    with _session_lock:
        if gen != _running["gen"]:
            raise Superseded()
        now = time.time()
        if _session["id"] and now - _session["last"] > SESSION_IDLE:
            _session["id"] = None
        sys_prompt = SYSTEM_PROMPT.format(
            tz=TZ.key,
            now=dt.datetime.now(TZ).strftime("%Y-%m-%d %H:%M, %A"),
            limit=REPLY_MAX,
        )
        # The time goes into the message too: on --resume Claude tends to repeat the time
        # from earlier turns instead of the fresh system prompt (said 21:04 at 21:07).
        stamped = f"[{dt.datetime.now(TZ).strftime('%H:%M')}] {prompt}"
        cmd = [CLAUDE_BIN, "-p", stamped, "--output-format", "json",
               "--append-system-prompt", sys_prompt, *CLAUDE_EXTRA_ARGS]
        if CLAUDE_MODEL:
            cmd += ["--model", CLAUDE_MODEL]
        if _session["id"]:
            cmd += ["--resume", _session["id"]]
        log("claude <-", prompt[:120])
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                                cwd=CLAUDE_CWD, stdin=subprocess.DEVNULL)
        with _running_lock:
            _running["proc"] = proc
        try:
            stdout, stderr = proc.communicate(timeout=CLAUDE_TIMEOUT)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.communicate()
            raise
        if gen != _running["gen"]:
            log("claude: superseded")
            raise Superseded()
        try:
            res = json.loads(stdout)
        except json.JSONDecodeError:
            raise RuntimeError(f"claude failed: {(stderr or stdout).strip()[-300:]}")
        if res.get("session_id"):
            _session["id"] = res["session_id"]
        _session["last"] = time.time()
        reply = res.get("result") or ""
        if res.get("is_error"):
            reply = reply or "Помилка Claude"
        log("claude ->", reply[:120])
        return shorten(reply)


class Handler(BaseHTTPRequestHandler):
    server_version = "watch-voice-bridge/1"

    def log_message(self, fmt, *args):  # quieter default logging
        log(self.address_string(), fmt % args)

    def _send(self, code: int, obj: dict) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self) -> bool:
        got = self.headers.get("X-Token", "")
        if TOKEN and hmac.compare_digest(got, TOKEN):
            return True
        self._send(401, {"error": "bad token"})
        return False

    def _body(self) -> bytes:
        n = int(self.headers.get("Content-Length") or 0)
        if n > MAX_BODY:
            raise ValueError("body too large")
        return self.rfile.read(n)

    def _transcribe_body(self, rate: int) -> str:
        data = self._body()
        with tempfile.TemporaryDirectory() as td:
            wav = os.path.join(td, "in.wav")
            if data[:4] == b"RIFF":
                with open(wav, "wb") as f:
                    f.write(data)
            else:
                pcm_to_wav(normalize_pcm(data), rate, wav)
            if os.environ.get("BRIDGE_KEEP_AUDIO"):
                keep = os.path.expanduser(os.environ["BRIDGE_KEEP_AUDIO"])
                os.makedirs(keep, exist_ok=True)
                shutil.copy(wav, os.path.join(keep, dt.datetime.now(TZ).strftime("%Y%m%d-%H%M%S.wav")))
            asr = transcribe(wav)
        log("asr:", asr)
        return asr

    def do_GET(self):
        if not self._authorized():
            return
        if urlparse(self.path).path == "/health":
            self._send(200, {"ok": True, "stt": stt_available()})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        if not self._authorized():
            return
        url = urlparse(self.path)
        try:
            if url.path == "/text":
                text = (json.loads(self._body() or b"{}").get("text") or "").strip()
                if not text:
                    return self._send(400, {"error": "empty text"})
                return self._send(200, {"reply": ask_claude(text)})

            if url.path in ("/voice", "/stt"):
                rate = int(parse_qs(url.query).get("rate", ["16000"])[0])
                asr = self._transcribe_body(rate)
                if not asr:
                    return self._send(200, {"asr": "", "reply": "Не розчув, повторіть"})
                if url.path == "/stt":
                    return self._send(200, {"asr": asr})
                return self._send(200, {"asr": asr, "reply": ask_claude(asr)})

            if url.path == "/reset":
                with _session_lock:
                    _session["id"] = None
                return self._send(200, {"ok": True})

            self._send(404, {"error": "not found"})
        except Superseded:
            self._send(409, {"error": "superseded", "reply": ""})
        except LanguageRejected:
            self._send(200, {"asr": "", "reply": LANG_REJECT_REPLY})
        except subprocess.TimeoutExpired:
            self._send(504, {"error": "timeout", "reply": "Claude не встиг відповісти"})
        except Exception as e:  # report to the phone so the band can show it
            log("error:", repr(e))
            self._send(500, {"error": str(e), "reply": shorten(f"Помилка: {e}")})


def main():
    if not TOKEN:
        sys.exit("Set BRIDGE_TOKEN (shared secret the phone sends in X-Token).")
    load_stt()
    log(f"listening on {HOST}:{PORT}  stt={STT_BACKEND}:{'ok' if stt_available() else 'MISSING'}"
        f"  claude={CLAUDE_MODEL or 'default'}  cwd={CLAUDE_CWD}")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
