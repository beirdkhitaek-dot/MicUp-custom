// Null Commander FX: a deep, muffled, distorted voice chain as a CLAP plugin.
//
//   input -> pitch shift -> bass shelf -> distortion + bit crush -> 2x low-pass -> stutter gate -> gain
//
// It is mono, allocation-free in process(), and has seven automatable parameters.
// Use this file as a template for your own plugins: copy the folder, rename the
// descriptor id/name, and change tick() and kParams.
//
// The settings are an estimate of a deep muffled "commander" voice. They are not the
// settings of any particular creator.

#include <clap/clap.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstring>

namespace {

// ─── Parameters ──────────────────────────────────────────────────────────────
enum ParamId : clap_id {
    kPitch = 0, kMuffle, kBass, kDrive, kCrush, kStutter, kGain, kParamCount
};

struct ParamDef { const char* name; double min, max, def; };

const ParamDef kParams[kParamCount] = {
    {"Pitch (semitones)",   -12.0,     6.0,   -5.0},
    {"Muffle cutoff (Hz)",  600.0, 12000.0, 2400.0},
    {"Bass boost (dB)",       0.0,    18.0,    8.0},
    {"Distortion",            0.0,     1.0,   0.35},
    {"Bit crush",             0.0,     1.0,    0.2},
    {"Stutter",               0.0,     1.0,    0.0},
    {"Output gain (dB)",    -12.0,    12.0,    0.0},
};

// ─── DSP building blocks ─────────────────────────────────────────────────────
struct Biquad {
    float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0, z1 = 0, z2 = 0;
    inline float process(float x) {
        const float y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
    void reset() { z1 = z2 = 0; }
    void set(double B0, double B1, double B2, double A0, double A1, double A2) {
        b0 = (float)(B0 / A0); b1 = (float)(B1 / A0); b2 = (float)(B2 / A0);
        a1 = (float)(A1 / A0); a2 = (float)(A2 / A0);
    }
    void lowpass(double fs, double f0, double q) {
        const double w = 2.0 * M_PI * std::min(f0, fs * 0.45) / fs, c = std::cos(w), al = std::sin(w) / (2.0 * q);
        set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al);
    }
    void lowshelf(double fs, double f0, double dB) {
        const double A = std::pow(10.0, dB / 40.0), w = 2.0 * M_PI * f0 / fs, c = std::cos(w);
        const double al = std::sin(w) / 2.0 * std::sqrt(2.0), sq = 2.0 * std::sqrt(A) * al;  // shelf slope 1
        set(A * ((A + 1) - (A - 1) * c + sq), 2 * A * ((A - 1) - (A + 1) * c), A * ((A + 1) - (A - 1) * c - sq),
            (A + 1) + (A - 1) * c + sq, -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - sq);
    }
};

// Two crossfaded delay taps whose delay sweeps: changes pitch without changing length.
struct PitchShifter {
    static constexpr int kMaxW = 4096;     // supports sample rates up to ~117 kHz at 35 ms
    float buf[kMaxW + 16];
    int   W = 1680, N = 1696, w = 0;
    float phase = 0, ratio = 1;

    void prepare(double fs) {
        W = std::min(kMaxW, (int)(fs * 0.035));
        N = W + 16;
        std::memset(buf, 0, sizeof(buf));
        w = 0; phase = 0;
    }
    inline float tap(float p) const {
        float pos = (float)w - (1.0f + p * (float)W);
        if (pos < 0) pos += (float)N;
        const int i0 = (int)pos; const float f = pos - (float)i0;
        const int i1 = (i0 + 1) % N;
        return buf[i0] * (1.0f - f) + buf[i1] * f;
    }
    inline float process(float x) {
        buf[w] = x;
        float out;
        if (std::fabs(ratio - 1.0f) < 0.001f) {
            int pos = w - W; if (pos < 0) pos += N;
            out = buf[pos];
        } else {
            const float p1 = phase;
            float p2 = p1 + 0.5f; if (p2 >= 1.0f) p2 -= 1.0f;
            const float g1 = 0.5f - 0.5f * std::cos(2.0f * (float)M_PI * p1);
            const float g2 = 0.5f - 0.5f * std::cos(2.0f * (float)M_PI * p2);
            out = tap(p1) * g1 + tap(p2) * g2;
            phase += (1.0f - ratio) / (float)W;
            if (phase >= 1.0f) phase -= 1.0f; else if (phase < 0.0f) phase += 1.0f;
        }
        if (++w >= N) w = 0;
        return out;
    }
};

// ─── Plugin instance ─────────────────────────────────────────────────────────
struct NullFx {
    clap_plugin_t        plugin{};
    const clap_host_t*   host = nullptr;

    std::atomic<float>   value[kParamCount];
    std::atomic<bool>    dirty{true};

    double       sr = 48000.0;
    PitchShifter ps;
    Biquad       shelf, lp1, lp2;
    float        drive = 0, driveNorm = 1, crushLevels = 0, stutter = 0, gainLin = 1;
    float        lfoPhase = 0, gate = 1;

    NullFx() { for (uint32_t i = 0; i < kParamCount; i++) value[i].store((float)kParams[i].def); }

    void setValue(clap_id id, double v) {
        if (id >= kParamCount) return;
        v = std::min(std::max(v, kParams[id].min), kParams[id].max);
        value[id].store((float)v, std::memory_order_relaxed);
        dirty.store(true, std::memory_order_release);
    }

    void updateCoefs() {
        ps.ratio = std::pow(2.0f, value[kPitch].load() / 12.0f);
        shelf.lowshelf(sr, 180.0, value[kBass].load());
        lp1.lowpass(sr, value[kMuffle].load(), 0.7);
        lp2.lowpass(sr, value[kMuffle].load(), 0.7);
        drive = value[kDrive].load();
        driveNorm = drive > 0.001f ? std::tanh(1.0f + drive * 24.0f) : 1.0f;
        const float crush = value[kCrush].load();
        crushLevels = crush > 0.001f ? std::pow(2.0f, 10.0f - 7.0f * crush) / 2.0f : 0.0f;
        stutter = value[kStutter].load();
        gainLin = std::pow(10.0f, value[kGain].load() / 20.0f);
    }

    inline float tick(float x) {
        float y = ps.process(x);
        y = shelf.process(y);
        if (drive > 0.001f) y = std::tanh((1.0f + drive * 24.0f) * y) / driveNorm;
        if (crushLevels > 0.0f) y = std::round(y * crushLevels) / crushLevels;
        y = lp2.process(lp1.process(y));
        if (stutter > 0.001f) {
            lfoPhase += 13.0f / (float)sr; if (lfoPhase >= 1.0f) lfoPhase -= 1.0f;
            const float target = lfoPhase < 0.5f ? 1.0f : 1.0f - stutter;
            gate += (target - gate) * 0.02f;  // ~1 ms smoothing avoids clicks
            y *= gate;
        } else {
            gate = 1.0f;
        }
        y *= gainLin;
        // Soft knee above 0.8 instead of a hard clip: a hard clip would add harmonics above the muffle cutoff.
        const float a = std::fabs(y);
        if (a > 0.8f) y = std::copysign(0.8f + 0.2f * std::tanh((a - 0.8f) / 0.2f), y);
        return y;
    }
};

NullFx* self(const clap_plugin_t* p) { return static_cast<NullFx*>(p->plugin_data); }

void handleEvents(NullFx* s, const clap_input_events_t* ev) {
    if (!ev) return;  // tolerate hosts that pass no event list
    const uint32_t n = ev->size(ev);
    for (uint32_t i = 0; i < n; i++) {
        const clap_event_header_t* h = ev->get(ev, i);
        if (h && h->space_id == CLAP_CORE_EVENT_SPACE_ID && h->type == CLAP_EVENT_PARAM_VALUE) {
            const auto* pv = reinterpret_cast<const clap_event_param_value_t*>(h);
            s->setValue(pv->param_id, pv->value);
        }
    }
}

// ─── Audio ports: one mono in, one mono out ──────────────────────────────────
uint32_t portsCount(const clap_plugin_t*, bool) { return 1; }
bool portsGet(const clap_plugin_t*, uint32_t index, bool, clap_audio_port_info_t* info) {
    if (index != 0) return false;
    info->id = 0;
    std::snprintf(info->name, sizeof(info->name), "%s", "main");
    info->flags = CLAP_AUDIO_PORT_IS_MAIN;
    info->channel_count = 1;
    info->port_type = CLAP_PORT_MONO;
    info->in_place_pair = CLAP_INVALID_ID;
    return true;
}
const clap_plugin_audio_ports_t kPorts = {portsCount, portsGet};

// ─── Parameters extension ────────────────────────────────────────────────────
uint32_t pCount(const clap_plugin_t*) { return kParamCount; }
bool pInfo(const clap_plugin_t*, uint32_t i, clap_param_info_t* info) {
    if (i >= kParamCount) return false;
    std::memset(info, 0, sizeof(*info));
    info->id = i;
    info->flags = CLAP_PARAM_IS_AUTOMATABLE;
    std::snprintf(info->name, sizeof(info->name), "%s", kParams[i].name);
    info->min_value = kParams[i].min;
    info->max_value = kParams[i].max;
    info->default_value = kParams[i].def;
    return true;
}
bool pGet(const clap_plugin_t* p, clap_id id, double* out) {
    if (id >= kParamCount) return false;
    *out = self(p)->value[id].load(std::memory_order_relaxed);
    return true;
}
bool pToText(const clap_plugin_t*, clap_id id, double v, char* buf, uint32_t size) {
    if (id >= kParamCount) return false;
    std::snprintf(buf, size, "%.2f", v);
    return true;
}
bool pFromText(const clap_plugin_t*, clap_id id, const char* text, double* out) {
    if (id >= kParamCount) return false;
    return std::sscanf(text, "%lf", out) == 1;
}
void pFlush(const clap_plugin_t* p, const clap_input_events_t* in, const clap_output_events_t*) {
    handleEvents(self(p), in);
}
const clap_plugin_params_t kParamsExt = {pCount, pInfo, pGet, pToText, pFromText, pFlush};

// ─── Plugin lifecycle ────────────────────────────────────────────────────────
bool init(const clap_plugin_t*) { return true; }
void destroy(const clap_plugin_t* p) { delete self(p); }
bool activate(const clap_plugin_t* p, double sampleRate, uint32_t, uint32_t) {
    NullFx* s = self(p);
    s->sr = sampleRate;
    s->ps.prepare(sampleRate);
    s->shelf.reset(); s->lp1.reset(); s->lp2.reset();
    s->gate = 1; s->lfoPhase = 0;
    s->updateCoefs();
    return true;
}
void deactivate(const clap_plugin_t*) {}
bool startProcessing(const clap_plugin_t*) { return true; }
void stopProcessing(const clap_plugin_t*) {}
void resetPlugin(const clap_plugin_t* p) {
    NullFx* s = self(p);
    s->ps.prepare(s->sr); s->shelf.reset(); s->lp1.reset(); s->lp2.reset();
}
clap_process_status process(const clap_plugin_t* p, const clap_process_t* pr) {
    NullFx* s = self(p);
    handleEvents(s, pr->in_events);
    if (s->dirty.exchange(false, std::memory_order_acquire)) s->updateCoefs();
    if (pr->audio_inputs_count == 0 || pr->audio_outputs_count == 0) return CLAP_PROCESS_CONTINUE;
    const float* in  = pr->audio_inputs[0].data32[0];
    float*       out = pr->audio_outputs[0].data32[0];
    for (uint32_t i = 0; i < pr->frames_count; i++) out[i] = s->tick(in[i]);
    return CLAP_PROCESS_CONTINUE;
}
const void* getExtension(const clap_plugin_t*, const char* id) {
    if (!std::strcmp(id, CLAP_EXT_AUDIO_PORTS)) return &kPorts;
    if (!std::strcmp(id, CLAP_EXT_PARAMS))      return &kParamsExt;
    return nullptr;
}
void onMainThread(const clap_plugin_t*) {}

const char* const kFeatures[] = {CLAP_PLUGIN_FEATURE_AUDIO_EFFECT, CLAP_PLUGIN_FEATURE_DISTORTION,
                                 CLAP_PLUGIN_FEATURE_PITCH_SHIFTER, CLAP_PLUGIN_FEATURE_MONO, nullptr};
const clap_plugin_descriptor_t kDescriptor = {
    CLAP_VERSION_INIT, "com.micplugin.null-commander", "Null Commander FX", "MicUp custom",
    "https://github.com/beirdkhitaek-dot/MicUp-custom", "", "", "1.0.0",
    "Deep, muffled, distorted voice chain", kFeatures};

// ─── Factory and entry ───────────────────────────────────────────────────────
uint32_t factCount(const clap_plugin_factory_t*) { return 1; }
const clap_plugin_descriptor_t* factDesc(const clap_plugin_factory_t*, uint32_t i) { return i == 0 ? &kDescriptor : nullptr; }
const clap_plugin_t* factCreate(const clap_plugin_factory_t*, const clap_host_t* host, const char* id) {
    if (!host || std::strcmp(id, kDescriptor.id)) return nullptr;
    NullFx* s = new NullFx();
    s->host = host;
    s->plugin.desc = &kDescriptor;
    s->plugin.plugin_data = s;
    s->plugin.init = init;
    s->plugin.destroy = destroy;
    s->plugin.activate = activate;
    s->plugin.deactivate = deactivate;
    s->plugin.start_processing = startProcessing;
    s->plugin.stop_processing = stopProcessing;
    s->plugin.reset = resetPlugin;
    s->plugin.process = process;
    s->plugin.get_extension = getExtension;
    s->plugin.on_main_thread = onMainThread;
    return &s->plugin;
}
const clap_plugin_factory_t kFactory = {factCount, factDesc, factCreate};

bool entryInit(const char*) { return true; }
void entryDeinit() {}
const void* entryGetFactory(const char* id) {
    return std::strcmp(id, CLAP_PLUGIN_FACTORY_ID) == 0 ? &kFactory : nullptr;
}

}  // namespace

extern "C" CLAP_EXPORT const clap_plugin_entry_t clap_entry = {
    CLAP_VERSION_INIT, entryInit, entryDeinit, entryGetFactory};
