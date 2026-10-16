#!/usr/bin/env python3
"""
SSMP 0.1.5.3 log analyzer: compares StarMP crash reports / F9 bug reports from several players.

Usage:  analyze_logs.py <file or folder> [...] [-o report.md]

Every line in the logs looks like:   Player : HH:MM:SS:mmmZ | <host-clock ms> | TAG | key=value ...
Column 2 is the HOST's clock (each guest corrects its own clock by the measured offset), so lines from different
PCs line up on it. The report covers:
  - files: who, role, version, hardware, time span
  - shared NPC fleets: per guest, how far each shown fleet is from where the host has it (error), flicker
    (shown -> gone -> shown), big correction jumps, local fleets that escaped hiding
  - other players' fleets: wire vs shown error
  - combat: mirror vs owner ship position / facing / hull, shots seen vs fired, input delivery delay
  - performance: fps, frame-time lows, hitches, mod ms per system, Java heap / GC, network, PC usage
"""
import sys, os, re, math, statistics, collections, argparse

LINE = re.compile(r'^(?P<player>.+?) : (?P<utc>\d\d:\d\d:\d\d:\d{3})Z \| (?P<host>-?\d*) \| (?P<tag>[A-Z0-9-]+) \| ?(?P<data>.*)$')
KV = re.compile(r'([\w>]+)=(\S+)')


def kv(s):
    return dict(KV.findall(s))


def num(d, k, default=None):
    try:
        return float(d[k])
    except (KeyError, ValueError):
        return default


class LogFile:
    def __init__(self, path):
        self.path = path
        self.header = {}
        self.hw = []
        self.player = None
        self.lines = []  # (host_ms, tag, data_str, kv)
        in_sys = False
        with open(path, encoding='utf-8', errors='replace') as f:
            for raw in f:
                raw = raw.rstrip('\n')
                m = LINE.match(raw)
                if m:
                    self.player = self.player or m.group('player')
                    h = m.group('host')
                    if not h:
                        continue  # SYS lines from the helper carry no host clock; matched by order below
                    self.lines.append((int(h), m.group('tag'), m.group('data'), kv(m.group('data'))))
                    continue
                if raw.startswith('---- system'):
                    in_sys = True; continue
                if raw.startswith('----') or raw.startswith('===='):
                    in_sys = raw.startswith('---- system') and in_sys
                    if raw.startswith('===='): in_sys = False
                    continue
                if ':' in raw and not raw.startswith(' '):
                    k, v = raw.split(':', 1)
                    if in_sys:
                        self.hw.append(raw.strip())
                    else:
                        self.header.setdefault(k.strip(), v.strip())
        # SYS lines (PC usage) have no host clock column: read them separately
        self.sys = []
        with open(path, encoding='utf-8', errors='replace') as f:
            for raw in f:
                if ' | | SYS | ' in raw:
                    self.sys.append(raw.split(' | | SYS | ', 1)[1].strip())
        self.lines.sort(key=lambda x: x[0])
        self.role = self.header.get('Role', '?')

    def tagged(self, *tags):
        return [l for l in self.lines if l[1] in tags]


def fmt(v, d=1):
    return '-' if v is None else f'{v:.{d}f}'


def pct(vals, p):
    if not vals: return None
    s = sorted(vals); return s[min(len(s) - 1, int(len(s) * p))]


# ---------------------------------------------------------------------------------------------------- sections
def files_section(logs, out):
    out.append('## Files\n')
    for lg in logs:
        span = (lg.lines[-1][0] - lg.lines[0][0]) / 1000 if lg.lines else 0
        out.append(f"- **{lg.player or lg.header.get('Player', '?')}** — {lg.role}, {lg.header.get('Version', '?')}, "
                   f"{len(lg.lines)} lines over {span:.0f} s ({os.path.basename(lg.path)})")
        for h in lg.hw:
            out.append(f'  - {h}')
        for k in ('Graphics', 'Display', 'Java', 'Host clock'):
            if k in lg.header: out.append(f'  - {k}: {lg.header[k]}')
    out.append('')


def npc_section(logs, out):
    out.append('## Shared NPC fleets\n')
    host_logs = [lg for lg in logs if lg.tagged('CAMP') and any(l[2].startswith('NPC-H') for l in lg.tagged('CAMP'))]
    # host truth: key -> list of (t, x, y)
    truth = collections.defaultdict(list)
    for lg in host_logs:
        for t, tag, d, k in lg.tagged('CAMP'):
            if d.startswith('NPC-H') and 'x' in k:
                truth[k['k']].append((t, float(k['x']), float(k['y'])))

    def host_at(key, t):
        s = truth.get(key)
        if not s: return None
        best = min(s, key=lambda e: abs(e[0] - t))
        return best if abs(best[0] - t) <= 600 else None

    any_guest = False
    for lg in logs:
        g = [(t, k) for t, tag, d, k in lg.tagged('CAMP') if d.startswith('NPC-G')]
        leaks = [(t, k) for t, tag, d, k in lg.tagged('CAMP') if d.startswith('NPC-LEAK')]
        if not g and not leaks: continue
        any_guest = True
        per = collections.defaultdict(list)
        for t, k in g: per[k['k']].append((t, k))
        sample_times = sorted({t for t, _ in g})
        rows = []
        for key, samples in per.items():
            errs = [num(k, 'err') for _, k in samples if num(k, 'err') is not None]
            true_errs = []
            for t, k in samples:
                h = host_at(key, t)
                if h: true_errs.append(math.hypot(float(k['x']) - h[1], float(k['y']) - h[2]))
            # flicker: present in a sample, absent in the next sample(s), present again
            present = {t for t, _ in samples}
            idx = [i for i, t in enumerate(sample_times) if t in present]
            gaps = sum(1 for a, b in zip(idx, idx[1:]) if b - a > 1)
            states = [k.get('state') for _, k in samples]
            missing = sum(1 for s in states if s != 'shown')
            jumps = 0
            for (t1, k1), (t2, k2) in zip(samples, samples[1:]):
                dt = max(0.001, (t2 - t1) / 1000)
                dist = math.hypot(float(k2['x']) - float(k1['x']), float(k2['y']) - float(k1['y']))
                sp = math.hypot(num(k1, 'vx', 0), num(k1, 'vy', 0))
                if dist > sp * dt * 2 + 300: jumps += 1
            name = samples[-1][1].get('name', '?')
            rows.append((key, name, len(samples), statistics.mean(errs) if errs else None, max(errs) if errs else None,
                         statistics.mean(true_errs) if true_errs else None, max(true_errs) if true_errs else None, gaps, missing, jumps))
        rows.sort(key=lambda r: -(r[4] or 0))
        out.append(f"### {lg.player} (guest view) — {len(per)} fleets, {len(sample_times)} samples\n")
        out.append('| key | fleet | samples | err vs latest packet avg/max | err vs host log avg/max | flicker gaps | not-shown samples | jumps |')
        out.append('|---|---|---|---|---|---|---|---|')
        for r in rows[:40]:
            out.append(f'| {r[0]} | {r[1]} | {r[2]} | {fmt(r[3],0)} / {fmt(r[4],0)} | {fmt(r[5],0)} / {fmt(r[6],0)} | {r[7]} | {r[8]} | {r[9]} |')
        if len(rows) > 40: out.append(f'| … | {len(rows)-40} more | | | | | | |')
        if leaks:
            names = collections.Counter(k.get('name') for _, k in leaks)
            out.append(f"\nLocal NPC fleets that escaped hiding: {len(names)} fleet(s), {len(leaks)} samples: " + ', '.join(f'{n} ({c})' for n, c in names.most_common(10)))
        stale = [k for t, tag, d, k in lg.tagged('CAMP') if d.startswith('NPC-STALE')]
        if stale: out.append(f"\nHost NPC updates older than 3 s in {len(stale)} samples (max {max(int(k['update_age_ms']) for k in stale)} ms).")
        out.append('')
    if not any_guest: out.append('_No guest NPC lines in these files._\n')
    if not host_logs: out.append('_No host log included: the "err vs host log" column needs the host\'s file too._\n')


def players_section(logs, out):
    rows = []
    for lg in logs:
        per = collections.defaultdict(list)
        for t, tag, d, k in lg.tagged('CAMP'):
            if d.startswith('PLAYER') and k.get('err', '-') != '-':
                per[k.get('name', k.get('peer'))].append((float(k['err']), int(k.get('age_ms', 0))))
        for name, v in per.items():
            e = [x[0] for x in v]; a = [x[1] for x in v]
            rows.append((lg.player, name, len(v), statistics.mean(e), max(e), statistics.mean(a), max(a)))
    if not rows: return
    out.append('## Other players\' fleets (wire position vs shown)\n')
    out.append('| observer | player | samples | err avg | err max | packet age avg ms | max ms |')
    out.append('|---|---|---|---|---|---|---|')
    for r in rows: out.append(f'| {r[0]} | {r[1]} | {r[2]} | {r[3]:.0f} | {r[4]:.0f} | {r[5]:.0f} | {r[6]} |')
    out.append('')


def combat_section(logs, out):
    owners = [lg for lg in logs if any(k.get('role') == 'owner' for _, _, _, k in lg.tagged('SHIP'))]
    mirrors = [lg for lg in logs if any(k.get('role') == 'mirror' for _, _, _, k in lg.tagged('SHIP'))]
    if not owners and not mirrors: return
    out.append('## Combat\n')
    if not owners or not mirrors:
        out.append('_Need both the battle owner\'s and a joiner\'s file for owner-vs-joiner comparisons._\n')
    truth = collections.defaultdict(list)  # id -> [(t, kv)]
    for lg in owners:
        for t, tag, d, k in lg.tagged('SHIP'):
            if k.get('role') == 'owner' and k.get('id', '-') != '-': truth[k['id']].append((t, k))
    for v in truth.values(): v.sort(key=lambda e: e[0])

    def at(i, t):
        s = truth.get(i)
        if not s: return None
        lo, hi = 0, len(s) - 1
        while lo < hi:
            mid = (lo + hi) // 2
            if s[mid][0] < t: lo = mid + 1
            else: hi = mid
        cand = [s[j] for j in (lo - 1, lo) if 0 <= j < len(s)]
        best = min(cand, key=lambda e: abs(e[0] - t))
        return best if abs(best[0] - t) <= 150 else None

    for lg in mirrors:
        pos, fac, hp, n = [], [], [], 0
        hulks = []
        per_ship = collections.defaultdict(list)
        for t, tag, d, k in lg.tagged('SHIP'):
            if k.get('role') != 'mirror' or k.get('id', '-') == '-': continue
            o = at(k['id'], t)
            if not o: continue
            ok = o[1]
            e = math.hypot(float(k['x']) - float(ok['x']), float(k['y']) - float(ok['y']))
            if ok.get('alive') == '0' or k.get('alive') == '0':
                hulks.append((k['id'], k.get('hull'), e)); continue  # wrecks are reported separately
            n += 1
            pos.append(e); per_ship[(k['id'], k.get('hull'), k.get('ctrl'))].append(e)
            fe = abs((float(k['f']) - float(ok['f']) + 540) % 360 - 180); fac.append(fe)
            hp.append(abs(float(k['hp']) - float(ok['hp'])))
        out.append(f"### {lg.player} (joiner) vs owner\n")
        if n:
            out.append(f"- matched samples: {n}")
            out.append(f"- position error: avg {statistics.mean(pos):.0f}, 95% {pct(pos,.95):.0f}, max {max(pos):.0f} (map units)")
            out.append(f"- facing error: avg {statistics.mean(fac):.1f}°, 95% {pct(fac,.95):.1f}°, max {max(fac):.1f}°")
            out.append(f"- hull difference: avg {statistics.mean(hp)*100:.1f}%, max {max(hp)*100:.1f}%")
            worst = sorted(per_ship.items(), key=lambda kv_: -statistics.mean(kv_[1]))[:8]
            out.append('- worst ships: ' + '; '.join(f"#{s[0]} {s[1]} ({s[2]}) avg {statistics.mean(v):.0f}" for s, v in worst))
        if hulks:
            hv = [h[2] for h in hulks]; ids = sorted({f"#{h[0]} {h[1]}" for h in hulks})
            out.append(f"- wrecks (destroyed ships): {len(ids)} ({', '.join(ids[:6])}), position error avg {statistics.mean(hv):.0f}, max {max(hv):.0f}")
        # shots
        own_shots = collections.defaultdict(list)
        for olg in owners:
            for t, tag, d, k in olg.tagged('SHOT'):
                if k.get('role') == 'owner': own_shots[(k.get('src'), k.get('wpn'))].append(t)
        seen = collections.defaultdict(list)
        for t, tag, d, k in lg.tagged('SHOT'):
            if k.get('role') == 'mirror': seen[(k.get('src'), k.get('wpn'))].append(t)
        if own_shots or seen:
            fired = sum(len(v) for v in own_shots.values()); shown = sum(len(v) for v in seen.values())
            matched, delays = 0, []
            for key, ts in seen.items():
                pool = sorted(own_shots.get(key, []))
                for t in ts:
                    c = [x for x in pool if -200 <= t - x <= 1500]
                    if c: matched += 1; delays.append(t - c[0]); pool.remove(c[0])
            out.append(f"- shots (non-PD): owner fired {fired}, joiner showed {shown}, matched {matched}"
                       + (f", shown {statistics.mean(delays):.0f} ms after firing on average (95% {pct(delays,.95):.0f} ms)" if delays else ''))
            out.append(f"- shots fired but never shown on this joiner (approx.): {max(0, fired - matched)}; shown with no owner shot: {max(0, shown - matched)}")
        # input delivery
        sent = [(t, int(k['seq']), int(k['bits'])) for t, tag, d, k in lg.tagged('INPUT') if 'seq' in k]
        rx = [(t, int(k['seq'])) for olg in owners for t, tag, d, k in olg.tagged('INPUT-RX') if 'seq' in k]
        if sent and rx:
            rx.sort()
            lags = []
            for t, s, _ in sent:
                c = next((rt for rt, rs in rx if rs >= s and rt >= t - 200), None)
                if c is not None: lags.append(c - t)
            if lags: out.append(f"- input delivery (client send → owner has it, 100 ms sampling): avg {statistics.mean(lags):.0f} ms, 95% {pct(lags,.95):.0f} ms")
        out.append('')
    for lg in owners + mirrors:
        cb = [k for t, tag, d, k in lg.tagged('CB')]
        if cb:
            out.append(f"- {lg.player}: up to {max(int(k.get('ships',0)) for k in cb)} ships, {max(int(k.get('projectiles',0)) for k in cb)} projectiles, {max(int(k.get('missiles',0)) for k in cb)} missiles at once")
    out.append('')


def perf_section(logs, out):
    out.append('## Performance\n')
    for lg in logs:
        p = [k for t, tag, d, k in lg.tagged('PERF')]
        raw = [d for t, tag, d, k in lg.tagged('PERF')]
        if not p: continue
        out.append(f"### {lg.player}\n")
        by_ctx = collections.defaultdict(list)
        for k, d in zip(p, raw): by_ctx[k.get('ctx', '?')].append((k, d))
        out.append('| screen | seconds | fps avg | fps min | 1% low (ms) avg / worst | worst frame ms | hitches | mod ms/frame avg / max | heap max MB | GC ms total | net in / out KB/s avg |')
        out.append('|---|---|---|---|---|---|---|---|---|---|---|')
        for ctx, rows in by_ctx.items():
            fps = [num(k, 'fps', 0) for k, _ in rows]
            p99 = [num(k, 'p99', 0) for k, _ in rows]
            worst = [num(k, 'worst', 0) for k, _ in rows]
            hitch = sum(int(k.get('hitches>100ms', 0)) for k, _ in rows)
            mod = [float(re.search(r'mod ms/frame=([\d.]+)', d).group(1)) for _, d in rows if re.search(r'mod ms/frame=([\d.]+)', d)]
            heap = [int(m.group(1)) for _, d in rows for m in [re.search(r'heap (\d+)/', d)] if m]
            gc = sum(int(m.group(1)) for _, d in rows for m in [re.search(r'gc \d+ \((\d+) ms\)', d)] if m)
            nin = [float(m.group(1)) for _, d in rows for m in [re.search(r'net in ([\d.]+) KB/s', d)] if m]
            nout = [float(m.group(1)) for _, d in rows for m in [re.search(r'out ([\d.]+) KB/s', d)] if m]
            out.append(f"| {ctx} | {len(rows)} | {statistics.mean(fps):.0f} | {min(fps):.0f} | {statistics.mean(p99):.1f} / {max(p99):.1f} | {max(worst):.0f} | {hitch} | "
                       f"{fmt(statistics.mean(mod) if mod else None,2)} / {fmt(max(mod) if mod else None,2)} | {max(heap) if heap else '-'} | {gc} | "
                       f"{fmt(statistics.mean(nin) if nin else None)} / {fmt(statistics.mean(nout) if nout else None)} |")
        # mod systems
        sysms = collections.defaultdict(list)
        for _, d in [x for v in by_ctx.values() for x in v]:
            m = re.search(r'\[(.*?)\] \|', d)
            if m:
                for part in m.group(1).split(', '):
                    bits = part.rsplit(' ', 1)
                    if len(bits) == 2:
                        try: sysms[bits[0]].append(float(bits[1]))
                        except ValueError: pass
        if sysms:
            out.append('\nMod time by system (ms per frame, avg / max): ' + '; '.join(f"{k} {statistics.mean(v):.2f} / {max(v):.2f}" for k, v in sorted(sysms.items(), key=lambda kv_: -statistics.mean(kv_[1]))))
        if lg.sys:
            gpu = [float(m.group(1)) for s in lg.sys for m in [re.search(r'gpu (\d+)%', s)] if m]
            cpu = [float(m.group(1)) for s in lg.sys for m in [re.search(r'^cpu (\d+)%', s)] if m]
            game = [float(m.group(1)) for s in lg.sys for m in [re.search(r'\(([\d.]+) threads\)', s)] if m]
            clk = [float(m.group(1)) for s in lg.sys for m in [re.search(r'clock ([\d.]+) GHz', s)] if m]
            out.append(f"\nPC: CPU total avg {fmt(statistics.mean(cpu) if cpu else None,0)}%, game threads avg {fmt(statistics.mean(game) if game else None)}"
                       f" (max {fmt(max(game) if game else None)}), clock {fmt(min(clk) if clk else None,2)}–{fmt(max(clk) if clk else None,2)} GHz"
                       + (f", GPU avg {statistics.mean(gpu):.0f}% (max {max(gpu):.0f}%)" if gpu else ', GPU n/a'))
        out.append('')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('paths', nargs='+')
    ap.add_argument('-o', '--out')
    a = ap.parse_args()
    files = []
    for p in a.paths:
        if os.path.isdir(p):
            files += [os.path.join(p, f) for f in sorted(os.listdir(p)) if f.startswith('StarMP') and f.endswith('.txt')]
        else:
            files.append(p)
    logs = [LogFile(f) for f in files]
    logs = [lg for lg in logs if lg.lines]
    out = ['# SSMP log comparison\n']
    if not logs:
        out.append('No StarMP log lines found.')
    else:
        files_section(logs, out)
        npc_section(logs, out)
        players_section(logs, out)
        combat_section(logs, out)
        perf_section(logs, out)
    text = '\n'.join(out)
    if a.out:
        open(a.out, 'w').write(text); print('wrote', a.out)
    else:
        print(text)


if __name__ == '__main__':
    main()
