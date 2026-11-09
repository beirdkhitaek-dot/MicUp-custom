#include "ClapHost.h"
#include <cmath>
#include <cstdio>
#include <vector>
using namespace micplugin;
static const int SR = 48000;
static double f0ac(const std::vector<float>& y, size_t a, size_t b) {
    int lo = SR/400, hi = SR/50; double best=-1; int bk=lo;
    for (int k=lo;k<hi;k++){ double s=0; for(size_t i=a;i+k<b;i++) s+=y[i]*y[i+k]; if(s>best){best=s;bk=k;} }
    return (double)SR/bk;
}
static double bandEnergy(const std::vector<float>& y, size_t a, size_t b, double f1, double f2) {
    double e=0; size_t L=b-a; for(double f=f1; f<f2; f+=25){ double re=0,im=0; for(size_t i=0;i<L;i++){ double hw=0.5-0.5*cos(2*M_PI*i/(L-1)); double w=2*M_PI*f*(a+i)/SR; re+=hw*y[a+i]*cos(w); im+=hw*y[a+i]*sin(w);} e+=re*re+im*im; } return e;
}
static std::vector<float> run(std::shared_ptr<PluginInstance> p, double f0, int seconds, int block) {
    int n = SR*seconds; std::vector<float> in(n), out(n);
    for (int i=0;i<n;i++){ double v=0; for(int h=1;h<=10;h++) v+=sin(2*M_PI*f0*h*i/SR)/h; in[i]=(float)(0.3*v); }
    out = in;
    for (int i=0;i+block<=n;i+=block) { if(!p->process(out.data()+i, block, SR)) { printf("process FAILED at %d\n", i); break; } }
    return out;
}
int main(int argc, char** argv) {
    auto p = ClapHost::load(argv[1], SR);
    if (!p) { printf("LOAD FAILED\n"); return 1; }
    printf("loaded ok\nparams: %s\n", ClapHost::getParams(p).c_str());
    int fails = 0;
    auto check = [&](bool ok, const char* msg){ printf("%s %s\n", ok?"PASS":"FAIL", msg); if(!ok) fails++; };

    // 1) default params (pitch -5 st), 192-frame blocks like an audio callback
    auto y = run(p, 130.0, 2, 192);
    bool finite=true; float pk=0; for(float v: y){ if(!std::isfinite(v)) finite=false; pk=fmaxf(pk,fabsf(v)); }
    check(finite && pk<=1.0f && pk>0.05f, "default output finite, non-silent, within +-1");
    double f1 = f0ac(y, SR, SR+SR/4);
    printf("  default f0 = %.1f Hz (expect ~97.4 for -5 st)\n", f1);
    check(fabs(f1-97.4) < 5, "default pitch -5 st lands near 97 Hz");

    // 2) parameter delivery: set pitch to -8 via setParam (host event queue) and re-measure
    p->setParam(0, -8.0f);
    run(p, 130.0, 1, 192);                         // let the change take effect and settle
    auto y2 = run(p, 130.0, 2, 192);
    double f2 = f0ac(y2, SR, SR+SR/4);
    printf("  after setParam(pitch=-8) f0 = %.1f Hz (expect ~81.9)\n", f2);
    check(fabs(f2-81.9) < 5, "setParam reaches plugin: pitch moved to -8 st");

    // 3) muffle: lower the cutoff and confirm high-band energy drops
    double loB = bandEnergy(y2, SR, SR+SR/4, 50, 400);
    double hiBefore = bandEnergy(y2, SR, SR+SR/4, 3000, 8000);
    p->setParam(1, 700.0f);
    run(p, 130.0, 1, 192);
    auto y3 = run(p, 130.0, 2, 192);
    double loA = bandEnergy(y3, SR, SR+SR/4, 50, 400);
    double hiAfter = bandEnergy(y3, SR, SR+SR/4, 3000, 8000);
    printf("  3-8 kHz vs 50-400 Hz (dB): cutoff 2400 -> %.1f, cutoff 700 -> %.1f\n", 10*log10(hiBefore/loB), 10*log10(hiAfter/loA));
    check(10*log10(hiAfter/loA) < 10*log10(hiBefore/loB) - 15, "lowering muffle cutoff 2400->700 Hz drops highs by >15 dB relative to lows");

    // 4) odd block sizes up to the host maximum
    bool okBlocks = true; std::vector<float> buf(4096, 0.1f);
    for (int b : {1, 7, 64, 480, 1024, 4096}) okBlocks &= p->process(buf.data(), b, SR);
    check(okBlocks, "odd block sizes 1..4096 process without error");

    // 5) hammer the queue: more than 64 param changes between process calls must not crash
    for (int i=0;i<500;i++) p->setParam(i%7, 0.0f);
    check(p->process(buf.data(), 192, SR), "event queue overflow handled");

    // 6) clean shutdown
    p->cleanup();
    printf("cleanup ok\n");
    printf(fails ? "RESULT: %d FAILED\n" : "RESULT: all passed\n", fails);
    return fails ? 1 : 0;
}
