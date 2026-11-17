// Replaces the band's XiaoAI voice assistant with a self-hosted bridge to Claude.
//
// Injected into Mi Fitness 3.56.0 (com.mi.health) as an extra dex. A one-line
// smali hook at the top of
//   BluetoothSpeechRecognizerManager.receiveAudioCommand(String did, AivsCommand cmd)
// calls onCommand(); when it returns true the stock XiaoAI handling is skipped.
//
// Band -> phone (raw data channel type 103, already parsed by Mi Fitness):
//   1 START  data[0]=sessionId, data[1]=tts flag
//   2 AUDIO  N x 80-byte Opus frames (16 kHz mono, 20 ms each)
//   3 END
//   4 QUIT   (user cancelled)
// Phone -> band (n21 AivsInstruction list via Object):
//   c=2   ASR text   (e = v31{final, [w31{text}]})
//   c=3   stop capture
//   c=256 toast      (f = i41{text})
//   c=264 speak text (n = x31{text})
//   c=4   dialog finish
//
// Config: /storage/emulated/0/Android/data/com.mi.health/files/watchvoice.properties
// (missing file or enabled=false -> stock XiaoAI behaviour is untouched).

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.util.Log;

import com.banya.opus.OpusDecoder;
import com.xiaomi.fitness.aivs.bluetooth.AivsCommand;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class WatchVoiceHook {
    private static final String TAG = "WatchVoice";
    private static final String CONFIG =
            "/storage/emulated/0/Android/data/com.mi.health/files/watchvoice.properties";
    private static final int OPUS_FRAME = 80;
    private static final int PCM_FRAME = 640;      // 320 samples s16le = 20 ms
    private static final int SAMPLE_RATE = 16000;

    // One thread per dialog: a slow, already-abandoned dialog must not hold up the next one.
    private static final ExecutorService NET = Executors.newCachedThreadPool();
    private static final Object LOCK = new Object();

    // --- per-dialog state (guarded by LOCK) ---
    private static Object svc;               // BluetoothAivsService
    private static Method sendMethod;
    private static String did;
    private static int sessionId = -1;
    private static ByteArrayOutputStream pcm;
    private static OpusDecoder decoder;
    private static int decoderHandle = -1;
    private static boolean processing;
    private static long startedAt;
    /** Bumped on every START and QUIT; only stops the keepalive toasts of older dialogs.
     *  Replies are always sent: the band re-STARTs on its own while waiting for the agent,
     *  and dropping the reply then leaves it waiting until its error screen. */
    private static int generation;
    // simple energy VAD
    private static boolean speechSeen;
    private static int silentMs;
    private static double noiseFloor;
    private static double peakRms;
    private static int framesSeen;
    private static int speechMs;
    // per-second stats for tuning
    private static double statMax;
    private static double statSum;
    private static int statFrames;
    private static int decodeFailures;

    private WatchVoiceHook() {}

    /** @return true if the command was handled here and XiaoAI must be skipped. */
    public static boolean onCommand(Object service, String deviceId, AivsCommand cmd) {
        try {
            Config cfg = Config.load();
            if (cfg == null || !cfg.enabled) return false;
            byte type = cmd.getType();
            byte[] data = cmd.getData();
            switch (type) {
                case 1: onStart(cfg, service, deviceId, data); break;
                case 2: onAudio(cfg, data); break;
                case 3: finishCapture(cfg, "band END"); break;
                case 4: synchronized (LOCK) { generation++; } reset("band QUIT"); break;
                default: Log.w(TAG, "unknown command " + type);
            }
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "onCommand failed, falling back to XiaoAI", t);
            return false;
        }
    }

    private static void onStart(Config cfg, Object service, String deviceId, byte[] data) {
        synchronized (LOCK) {
            releaseDecoder();
            generation++;
            svc = service;
            did = deviceId;
            sessionId = (data != null && data.length > 0) ? (data[0] & 0xFF) : 0;
            pcm = new ByteArrayOutputStream(SAMPLE_RATE * 2 * 8);
            processing = false;
            startedAt = System.currentTimeMillis();
            speechSeen = false;
            silentMs = 0;
            noiseFloor = Double.MAX_VALUE;
            peakRms = 0;
            framesSeen = 0;
            speechMs = 0;
            statMax = 0;
            statSum = 0;
            statFrames = 0;
            decodeFailures = 0;
            decoder = new OpusDecoder(SAMPLE_RATE, 1, PCM_FRAME, OPUS_FRAME);
            decoderHandle = decoder.a();
            Log.i(TAG, "START did=" + deviceId + " session=" + sessionId + " decoder=" + decoderHandle);
        }
    }

    private static void onAudio(Config cfg, byte[] data) {
        boolean stop = false;
        synchronized (LOCK) {
            if (pcm == null || processing || data == null || data.length == 0) return;
            if (data.length % OPUS_FRAME != 0 || decoderHandle < 0) {
                Log.w(TAG, "skip audio chunk len=" + data.length);
                return;
            }
            byte[] out = decoder.b(decoderHandle, data, data.length);
            if (out == null) {
                if (decodeFailures++ % 10 == 0) {
                    Log.w(TAG, "opus decode failed len=" + data.length + " count=" + decodeFailures);
                }
                return;
            }
            pcm.write(out, 0, out.length);
            if (cfg.vad) stop = vad(cfg, out);
            if (System.currentTimeMillis() - startedAt > cfg.maxRecordMs) stop = true;
        }
        if (stop) finishCapture(cfg, "vad/timeout");
    }

    /**
     * Energy VAD over 20 ms frames; returns true once speech is followed by enough silence.
     *
     * The user usually starts talking right after pressing the button, so the noise floor is
     * the running MINIMUM (never learned from the first frames as a max), and "silence" is
     * judged relative to the loudest frame seen so far.
     */
    private static boolean vad(Config cfg, byte[] out) {
        boolean stop = false;
        for (int off = 0; off + PCM_FRAME <= out.length; off += PCM_FRAME) {
            double sum = 0;
            for (int i = off; i < off + PCM_FRAME; i += 2) {
                int s = (short) ((out[i] & 0xFF) | (out[i + 1] << 8));
                sum += (double) s * s;
            }
            double rms = Math.sqrt(sum / (PCM_FRAME / 2));
            framesSeen++;
            // The band emits a short loud click/beep when recording starts: keep it out of the
            // floor/peak estimates, otherwise quiet speech (rms ~15-60) looks like silence.
            boolean warmup = framesSeen * 20 <= cfg.vadSkipMs;
            if (!warmup) {
                noiseFloor = Math.min(noiseFloor, rms);
                peakRms = Math.max(peakRms, rms);
            }

            statMax = Math.max(statMax, rms);
            statSum += rms;
            if (++statFrames == 50) {                     // once per second of audio
                Log.i(TAG, String.format(java.util.Locale.US,
                        "audio t=%.1fs rms avg=%.0f max=%.0f floor=%.0f peak=%.0f speech=%s silent=%dms",
                        framesSeen * 0.02, statSum / statFrames, statMax, noiseFloor, peakRms,
                        speechSeen, silentMs));
                statMax = 0;
                statSum = 0;
                statFrames = 0;
            }

            if (warmup) continue;
            double speechThr = Math.max(cfg.vadMinRms, noiseFloor * 4);
            double silenceThr = Math.max(noiseFloor * 3, peakRms * cfg.vadSilenceRatio);
            if (rms >= speechThr) {
                speechMs += 20;
                if (speechMs >= 200) speechSeen = true;   // ignore clicks
            }
            if (rms > silenceThr) {
                silentMs = 0;
            } else if (speechSeen) {
                silentMs += 20;
                if (silentMs >= cfg.vadSilenceMs) stop = true;
            }
        }
        return stop;
    }

    private static void finishCapture(final Config cfg, String why) {
        final byte[] audio;
        final int sid;
        final Object s;
        final String d;
        final int gen;
        synchronized (LOCK) {
            if (pcm == null || processing) return;
            gen = generation;
            processing = true;
            audio = pcm.toByteArray();
            sid = sessionId;
            s = svc;
            d = did;
            releaseDecoder();
        }
        Log.i(TAG, "capture done (" + why + "): " + audio.length + " bytes PCM");
        send(s, d, sid, stopCapture());
        NET.execute(new Runnable() {
            @Override public void run() { handleDialog(cfg, s, d, sid, gen, audio); }
        });
    }

    private static boolean stale(int gen) {
        synchronized (LOCK) { return gen != generation; }
    }

    private static void handleDialog(Config cfg, Object s, String d, int sid, int gen, byte[] audio) {
        String reply;
        String question = "";
        try {
            if (audio.length < SAMPLE_RATE / 2) {        // < 250 ms of audio
                reply = "Не розчув, повторіть";
            } else {
                JSONObject stt = new JSONObject(post(cfg, "/stt?rate=" + SAMPLE_RATE,
                        "application/octet-stream", audio, cfg.sttTimeoutMs));
                String asr = stt.optString("asr", "").trim();
                Log.i(TAG, "asr: " + asr);
                question = asr;
                if (asr.isEmpty()) {
                    reply = stt.optString("reply", "Не розчув, повторіть");
                } else {
                    send(s, d, sid, asr(asr));
                    if (!cfg.interimText.isEmpty()) {
                        // Keep the band's dialog open while the agent thinks (it times out
                        // after a few seconds without any instruction).
                        send(s, d, sid, toast(cfg.interimText));
                    }
                    JSONObject body = new JSONObject().put("text", asr);
                    Thread keepalive = keepalive(cfg, s, d, sid, gen);
                    final Config c = cfg;
                    final byte[] req = body.toString().getBytes(StandardCharsets.UTF_8);
                    Future<String> agent = NET.submit(new Callable<String>() {
                        @Override public String call() throws Exception {
                            JSONObject ans = new JSONObject(post(c, "/text", "application/json",
                                    req, c.agentTimeoutMs));
                            return ans.optString("reply", ans.optString("error", "Порожня відповідь"));
                        }
                    });
                    try {
                        reply = cfg.notifyAfterMs > 0
                                ? agent.get(cfg.notifyAfterMs, TimeUnit.MILLISECONDS)
                                : agent.get();
                    } catch (TimeoutException slow) {
                        // The band gives up after ~15 s: close the dialog now and deliver
                        // the answer later as a phone notification, which it mirrors.
                        if (keepalive != null) keepalive.interrupt();
                        Log.i(TAG, "agent slow, answer deferred");
                        if (stale(gen)) {
                            // The user already started another question: closing "this" dialog
                            // now would cut that one off (session ids are always 0).
                            Log.i(TAG, "newer dialog active, not touching it");
                        } else {
                            send(s, d, sid, answer(cfg, cfg.deferredText), dialogFinish());
                            synchronized (LOCK) {
                                if (sessionId == sid) reset("dialog deferred");
                            }
                        }
                        String late;
                        try {
                            late = agent.get(cfg.agentTimeoutMs, TimeUnit.MILLISECONDS);
                        } catch (Throwable t) {
                            Log.e(TAG, "deferred agent call failed", t);
                            late = "Не вдалося отримати відповідь: " + t.getClass().getSimpleName();
                        }
                        if (late.isEmpty()) return;               // superseded by a newer request
                        Log.i(TAG, "deferred reply: " + late);
                        if (!stale(gen)) {
                            // The assistant screen stays up for 15-60 s after "finish": the late
                            // answer replaces "Шукаю…" there.
                            send(s, d, sid, answer(cfg, late), dialogFinish());
                            Log.i(TAG, "deferred reply shown in the assistant");
                        } else if (cfg.notifyFallback) {
                            waitForIdleDialog(30000);
                            notifyPhone(cfg, asr, late);
                        } else {
                            Log.i(TAG, "assistant already closed, deferred reply dropped");
                        }
                        return;
                    } finally {
                        if (keepalive != null) keepalive.interrupt();
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "bridge failed", t);
            reply = "Міст недоступний: " + t.getClass().getSimpleName();
        }
        Log.i(TAG, "reply: " + reply);
        send(s, d, sid, answer(cfg, reply), dialogFinish());
        synchronized (LOCK) {
            if (sessionId == sid) reset("dialog done");
        }
        if (cfg.notifyAlways && !question.isEmpty() && !reply.isEmpty()) {
            // Copy to the band's notification list too: the dialog toast disappears in seconds.
            waitForIdleDialog(30000);
            notifyPhone(cfg, question, reply);
        }
    }

    /** Re-sends the interim toast while the agent works, so the band keeps the dialog open.
     *  Stopped via interrupt(), which also sets a flag: a Binder call inside send() can swallow
     *  the interrupt itself, and a toast sent after the answer would replace it on the band. */
    private static Thread keepalive(final Config cfg, final Object s, final String d,
                                    final int sid, final int gen) {
        if (cfg.interimText.isEmpty() || cfg.keepaliveMs <= 0) return null;
        final long until = System.currentTimeMillis()
                + (cfg.notifyAfterMs > 0 ? cfg.notifyAfterMs : 60000);
        final java.util.concurrent.atomic.AtomicBoolean stopped =
                new java.util.concurrent.atomic.AtomicBoolean();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                String[] dots = {"", ".", ".."};
                try {
                    for (int i = 0; ; i++) {
                        Thread.sleep(cfg.keepaliveMs);
                        synchronized (stopped) {
                            if (stopped.get() || stale(gen) || System.currentTimeMillis() > until) return;
                            send(s, d, sid, toast(cfg.interimText + dots[i % dots.length]));
                        }
                    }
                } catch (InterruptedException ignored) {
                }
            }
        }, "WatchVoice-keepalive") {
            @Override public void interrupt() {
                synchronized (stopped) { stopped.set(true); }   // waits for an in-flight send
                super.interrupt();
            }
        };
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static int notifyId = 7100;

    /** Don't drop a late answer into the middle of the next voice dialog. */
    private static void waitForIdleDialog(long maxMs) {
        long end = System.currentTimeMillis() + maxMs;
        try {
            while (System.currentTimeMillis() < end) {
                synchronized (LOCK) {
                    if (pcm == null) return;
                }
                Thread.sleep(500);
            }
        } catch (InterruptedException ignored) {
        }
    }

    /** Posts the answer as a normal phone notification; Mi Fitness mirrors it to the band. */
    private static void notifyPhone(Config cfg, String question, String text) {
        try {
            Context ctx = (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            String ch = "watchvoice_answers";
            if (nm.getNotificationChannel(ch) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        ch, "Відповіді голосового помічника", NotificationManager.IMPORTANCE_HIGH));
            }
            Notification n = new Notification.Builder(ctx, ch)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(cfg.notifyTitle)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text).setSummaryText(question))
                    .setAutoCancel(true)
                    .build();
            int id;
            synchronized (LOCK) { id = notifyId++; }
            nm.notify("watchvoice", id, n);
            Log.i(TAG, "notification posted id=" + id);
        } catch (Throwable t) {
            Log.e(TAG, "notification failed", t);
        }
    }

    // --- instructions -------------------------------------------------------------

    private static n21 asr(String text) {
        w31 item = new w31();
        item.c = text;
        w31.a items = new w31.a();
        items.c = new w31[]{item};
        v31 res = new v31();
        res.c = true;
        res.d = items;
        n21 n = new n21();
        n.c = 2;
        n.e = res;
        return n;
    }

    private static n21 answer(Config cfg, String text) {
        n21 n = new n21();
        if (cfg.replyType == 256) {
            i41 toast = new i41();
            toast.c = text;
            n.c = 256;
            n.f = toast;
        } else {
            x31 speak = new x31();
            speak.c = text;
            n.c = 264;
            n.n = speak;
        }
        return n;
    }

    private static n21 toast(String text) {
        i41 t = new i41();
        t.c = text;
        n21 n = new n21();
        n.c = 256;
        n.f = t;
        return n;
    }

    private static n21 stopCapture() { n21 n = new n21(); n.c = 3; return n; }

    private static n21 dialogFinish() { n21 n = new n21(); n.c = 4; return n; }

    private static void send(Object s, String d, int sid, n21... list) {
        if (s == null || d == null) return;
        for (n21 n : list) n.d = sid;
        n21.a a = new n21.a();
        a.c = list;
        try {
            if (sendMethod == null || sendMethod.getDeclaringClass() != s.getClass()) {
                sendMethod = s.getClass().getMethod("sendAivsInstructions", String.class, n21.a.class);
            }
            sendMethod.invoke(s, d, a);
        } catch (Throwable t) {
            Log.e(TAG, "sendAivsInstructions failed", t);
        }
    }

    private static void reset(String why) {
        synchronized (LOCK) {
            Log.i(TAG, "reset: " + why);
            releaseDecoder();
            pcm = null;
            processing = false;
        }
    }

    private static void releaseDecoder() {
        if (decoder != null && decoderHandle >= 0) {
            try { decoder.c(decoderHandle); } catch (Throwable ignored) {}
        }
        decoder = null;
        decoderHandle = -1;
    }

    // --- tiny HTTP/1.x client over a raw socket --------------------------------------
    // Mi Fitness's network_security_config forbids cleartext to arbitrary hosts; that
    // policy is enforced by HttpURLConnection/OkHttp, not by plain sockets. Traffic goes
    // over Tailscale (WireGuard), and the bridge requires the X-Token shared secret.

    private static String post(Config cfg, String path, String type, byte[] body, int timeoutMs)
            throws Exception {
        Socket sock = new Socket();
        try {
            sock.connect(new InetSocketAddress(cfg.host, cfg.port), 5000);
            sock.setSoTimeout(timeoutMs);
            String head = "POST " + path + " HTTP/1.1\r\n"
                    + "Host: " + cfg.host + ":" + cfg.port + "\r\n"
                    + "X-Token: " + cfg.token + "\r\n"
                    + "Content-Type: " + type + "\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = sock.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
            InputStream in = sock.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            String resp = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            int split = resp.indexOf("\r\n\r\n");
            if (split < 0) throw new IllegalStateException("bad HTTP response");
            String status = resp.substring(0, resp.indexOf("\r\n"));
            String json = resp.substring(split + 4);
            if (!status.contains(" 200 ")) Log.w(TAG, path + " -> " + status);
            return json;
        } finally {
            try { sock.close(); } catch (Throwable ignored) {}
        }
    }

    // --- config ---------------------------------------------------------------------

    private static final class Config {
        boolean enabled;
        String host;
        int port;
        String token;
        int replyType;
        boolean vad;
        double vadMinRms;
        double vadSilenceRatio;
        int vadSkipMs;
        int vadSilenceMs;
        int maxRecordMs;
        int sttTimeoutMs;
        int agentTimeoutMs;
        String interimText;
        int keepaliveMs;
        int notifyAfterMs;
        boolean notifyAlways;
        boolean notifyFallback;
        String notifyTitle;
        String deferredText;

        private static Config cached;
        private static long cachedMtime = -1;

        static synchronized Config load() {
            File f = new File(CONFIG);
            if (!f.isFile()) {
                // Logging out / switching region wipes the app's external files dir.
                Log.w(TAG, "no config at " + CONFIG + " -> stock XiaoAI");
                cached = null;
                return null;
            }
            long mt = f.lastModified();
            if (cached != null && mt == cachedMtime) return cached;
            Properties p = new Properties();
            try (FileInputStream in = new FileInputStream(f)) {
                p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.e(TAG, "config read failed", e);
                return null;
            }
            Config c = new Config();
            c.enabled = Boolean.parseBoolean(p.getProperty("enabled", "true"));
            c.host = p.getProperty("host", "").trim();
            c.port = Integer.parseInt(p.getProperty("port", "8787").trim());
            c.token = p.getProperty("token", "").trim();
            c.replyType = Integer.parseInt(p.getProperty("reply_type", "264").trim());
            c.vad = Boolean.parseBoolean(p.getProperty("vad", "true"));
            c.vadMinRms = Double.parseDouble(p.getProperty("vad_min_rms", "12"));
            c.vadSilenceRatio = Double.parseDouble(p.getProperty("vad_silence_ratio", "0.12"));
            c.vadSkipMs = Integer.parseInt(p.getProperty("vad_skip_ms", "300").trim());
            c.vadSilenceMs = Integer.parseInt(p.getProperty("vad_silence_ms", "1200").trim());
            c.maxRecordMs = Integer.parseInt(p.getProperty("max_record_ms", "6000").trim());
            c.sttTimeoutMs = Integer.parseInt(p.getProperty("stt_timeout_ms", "30000").trim());
            c.agentTimeoutMs = Integer.parseInt(p.getProperty("agent_timeout_ms", "120000").trim());
            c.interimText = p.getProperty("interim_text", "Думаю…").trim();
            c.keepaliveMs = Integer.parseInt(p.getProperty("keepalive_ms", "3000").trim());
            c.notifyAfterMs = Integer.parseInt(p.getProperty("notify_after_ms", "5500").trim());
            c.notifyTitle = p.getProperty("notify_title", "Claude").trim();
            c.notifyAlways = Boolean.parseBoolean(p.getProperty("notify_always", "false").trim());
            c.notifyFallback = Boolean.parseBoolean(p.getProperty("notify_fallback", "false").trim());
            c.deferredText = p.getProperty("deferred_text",
                    "Шукаю…").trim();
            if (c.host.isEmpty() || c.token.isEmpty()) {
                Log.w(TAG, "config incomplete (host/token)");
                c.enabled = false;
            }
            cached = c;
            cachedMtime = mt;
            Log.i(TAG, "config loaded: host=" + c.host + ":" + c.port + " enabled=" + c.enabled
                    + " reply_type=" + c.replyType + " vad=" + c.vad);
            return c;
        }
    }
}
