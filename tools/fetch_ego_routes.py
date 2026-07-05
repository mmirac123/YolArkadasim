# -*- coding: utf-8 -*-
"""
OpenStreetMap'ten (Overpass API) Ankara EGO otobüs hatlarını çekip
uygulamanın assets/routes.json formatına dönüştürür.

Kullanım:
    python tools/fetch_ego_routes.py                    # indir + birleştir + yaz
    python tools/fetch_ego_routes.py --dry-run          # sadece özet göster, dosya yazma
    python tools/fetch_ego_routes.py --input dump.json  # önceden indirilmiş Overpass
                                                        # çıktısını kullan (SSL sorunu
                                                        # olan ortamlar için)

Veri kaynağı: OpenStreetMap katkıcıları (ODbL lisansı — atıf zorunlu).
Uygulama zaten OSM tabanlı harita (osmdroid) kullandığı için atıf mevcut.

Kalite filtresi: OSM gönüllü katkıyla dolduğu için bazı hatlar eksik çizilmiş
olabilir; MIN_STOPS'tan az durağı olan veya duraklarının çoğu adsız olan
hatlar dosyaya alınmaz.
"""

import argparse
import json
import sys
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path

BBOX = "39.60,32.30,40.25,33.25"  # Ankara ve yakın çevresi (Gölbaşı dahil)
OVERPASS_SERVERS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]
QUERY = f"""
[out:json][timeout:300];
rel["route"="bus"]["operator"~"EGO",i]({BBOX})->.r;
.r out body;
node(r.r);
out body;
way(r.r);
out geom;
"""

MIN_STOPS = 10          # bundan az durağı çözülebilen hatlar elenir
MIN_NAMED_RATIO = 0.7   # durakların en az bu oranı adlandırılmış olmalı
SHAPE_TOLERANCE_M = 10.0  # Douglas-Peucker sadeleştirme toleransı (metre)

# Not: Ankara'daki OSM katkıcıları standart "stop" yerine yaygın olarak
# "bus_stop" rolünü kullanmış — ikisi de kabul edilir.
STOP_ROLES = {"stop", "stop_entry_only", "stop_exit_only", "bus_stop"}
PLATFORM_ROLES = {"platform", "platform_entry_only", "platform_exit_only"}
# Güzergâh geometrisini taşıyan way üyelerinin rolleri (Ankara'da çoğunlukla "line")
LINE_ROLES = {"", "line", "forward", "backward", "route"}

REPO_ROOT = Path(__file__).resolve().parent.parent
ROUTES_JSON = REPO_ROOT / "app" / "src" / "main" / "assets" / "routes.json"


def fetch_overpass():
    data = urllib.parse.urlencode({"data": QUERY}).encode("utf-8")
    last_err = None
    for server in OVERPASS_SERVERS:
        try:
            print(f"Overpass sorgusu: {server} ...", flush=True)
            req = urllib.request.Request(
                server, data=data,
                headers={"User-Agent": "YolArkadasim-route-importer/1.0"})
            with urllib.request.urlopen(req, timeout=240) as resp:
                return json.load(resp)
        except Exception as e:  # noqa: BLE001 - sıradaki sunucuyu dene
            print(f"  basarisiz: {e}", file=sys.stderr)
            last_err = e
    raise SystemExit(f"Tum Overpass sunuculari basarisiz: {last_err}")


def _dist2(a, b):
    """Kabaca karşılaştırma için kare mesafe (derece cinsinden, yeterli)."""
    return (a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2


def build_shape(rel, way_geoms, first_stop=None):
    """
    Relation'ın way parçalarını uç uca zincirler.

    Ankara verisinde way üyeleri çoğu hatta güzergâh sırasında DEĞİL; bu yüzden
    üye sırasına güvenmek yerine açgözlü yaklaşım kullanılır: ilk durağa en
    yakın uçlu parçayla başla, her adımda zincirin ucuna en yakın uca sahip
    kullanılmamış parçayı (gerekirse ters çevirerek) ekle.
    """
    segs = []
    for m in rel.get("members", []):
        if m["type"] == "way" and m.get("role", "") in LINE_ROLES:
            geom = way_geoms.get(m["ref"])
            if geom and len(geom) >= 2:
                segs.append(geom)
    if not segs:
        return []

    used = [False] * len(segs)
    if first_stop is not None:
        start = min(range(len(segs)), key=lambda i: min(
            _dist2(first_stop, segs[i][0]), _dist2(first_stop, segs[i][-1])))
    else:
        start = 0
    seg = segs[start]
    used[start] = True
    if first_stop is not None and _dist2(first_stop, seg[-1]) < _dist2(first_stop, seg[0]):
        seg = seg[::-1]
    shape = list(seg)

    # İki uçlu açgözlü: parça zincirin kuyruğuna da başına da eklenebilir.
    # (Başlangıç parçası güzergâhın ortasından seçilmiş olsa bile toparlar.)
    from collections import deque
    chain = deque(shape)
    for _ in range(len(segs) - 1):
        best = None  # (mesafe, i, flip, at_tail)
        head, tail = chain[0], chain[-1]
        for i, s in enumerate(segs):
            if used[i]:
                continue
            for flip in (False, True):
                a, b = (s[-1], s[0]) if flip else (s[0], s[-1])
                d_tail = _dist2(tail, a)   # kuyruğa ekle: ... -> a..b
                d_head = _dist2(head, b)   # başa ekle:    a..b -> ...
                if best is None or d_tail < best[0]:
                    best = (d_tail, i, flip, True)
                if d_head < best[0]:
                    best = (d_head, i, flip, False)
        if best is None:
            break
        _, i, flip, at_tail = best
        used[i] = True
        s = segs[i][::-1] if flip else segs[i]
        if at_tail:
            chain.extend(s[1:] if chain[-1] == s[0] else s)
        else:
            pre = s[:-1] if chain[0] == s[-1] else s
            chain.extendleft(reversed(pre))
    return list(chain)


def _meters(a, b):
    import math
    kx = 111320.0 * math.cos(math.radians((a[0] + b[0]) / 2))
    return math.hypot((a[1] - b[1]) * kx, (a[0] - b[0]) * 110540.0)


def shape_quality_ok(shape, stops):
    """
    Bozuk geometriyi veriye sokma: 1 km'den büyük kopukluk varsa veya
    duraklar çizgiden uzak düşüyorsa shape reddedilir (uygulama durak-durak
    çizgiye geri döner).
    """
    if len(shape) < 2:
        return False
    for i in range(len(shape) - 1):
        if _meters(shape[i], shape[i + 1]) > 1000.0:
            return False
    sampled_shape = shape[::5] or shape
    far = 0
    sampled_stops = stops[::2]
    for s in sampled_stops:
        p = (s["lat"], s["lon"])
        if min(_meters(p, q) for q in sampled_shape) > 200.0:
            far += 1
    return far <= max(1, len(sampled_stops) // 10)


def _perp_dist_m(pt, a, b):
    """Noktanın a-b doğru parçasına dik uzaklığı (metre, eşdikdörtgen yaklaşımı)."""
    import math
    lat0 = math.radians((a[0] + b[0]) / 2)
    kx = 111320.0 * math.cos(lat0)  # 1 derece boylam ~ metre
    ky = 110540.0                   # 1 derece enlem ~ metre
    ax, ay = a[1] * kx, a[0] * ky
    bx, by = b[1] * kx, b[0] * ky
    px, py = pt[1] * kx, pt[0] * ky
    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return ((px - ax) ** 2 + (py - ay) ** 2) ** 0.5
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    cx, cy = ax + t * dx, ay + t * dy
    return ((px - cx) ** 2 + (py - cy) ** 2) ** 0.5


def simplify(points, tolerance_m):
    """Douglas-Peucker (yinelemeli, stack tabanlı)."""
    if len(points) < 3:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        lo, hi = stack.pop()
        if hi - lo < 2:
            continue
        max_d, max_i = -1.0, -1
        for i in range(lo + 1, hi):
            d = _perp_dist_m(points[i], points[lo], points[hi])
            if d > max_d:
                max_d, max_i = d, i
        if max_d > tolerance_m:
            keep[max_i] = True
            stack.append((lo, max_i))
            stack.append((max_i, hi))
    return [p for p, k in zip(points, keep) if k]


def extract_routes(osm):
    # Duraklar OSM'de bazen nokta (highway=bus_stop), bazen way (platform alanı)
    # olarak çizilir; way'ler için Overpass'ın verdiği merkez koordinat kullanılır.
    elements = {}   # (type, id) -> {lat, lon, name, ref}  (durak adayları)
    way_geoms = {}  # way id -> [(lat, lon), ...]           (güzergâh geometrisi)
    relations = []
    for el in osm.get("elements", []):
        tags = el.get("tags", {})
        # Ankara OSM verisinde durakların "ref" etiketi EGO'nun gerçek durak
        # numarasıdır — uygulama ID olarak onu kullanır (OSM node id yerine).
        info = {
            "name": (tags.get("name") or "").strip(),
            "ref": (tags.get("ref") or "").strip(),
        }
        if el["type"] == "node":
            info["lat"], info["lon"] = el["lat"], el["lon"]
            elements[("node", el["id"])] = info
        elif el["type"] == "way":
            geom = [(p["lat"], p["lon"]) for p in el.get("geometry", []) if p]
            if geom:
                way_geoms[el["id"]] = geom
                # Way olarak çizilmiş platform durakları için merkez koordinat
                info["lat"] = sum(p[0] for p in geom) / len(geom)
                info["lon"] = sum(p[1] for p in geom) / len(geom)
                elements[("way", el["id"])] = info
            elif "center" in el:
                info["lat"], info["lon"] = el["center"]["lat"], el["center"]["lon"]
                elements[("way", el["id"])] = info
        elif el["type"] == "relation":
            relations.append(el)

    routes = []
    skipped = Counter()
    for rel in relations:
        tags = rel.get("tags", {})
        ref = tags.get("ref", "").strip()
        name = tags.get("name", "").strip()
        if not ref:
            skipped["ref'siz"] += 1
            continue

        # Üye sırası OSM'de güzergâh sırasıdır. 'stop' rolleri varsa onları,
        # yoksa 'platform' rollerini kullan (bazı hatlar sadece platform çizmiş).
        members = [m for m in rel.get("members", []) if m["type"] in ("node", "way")]
        stop_members = [m for m in members if m.get("role") in STOP_ROLES]
        if not stop_members:
            stop_members = [m for m in members if m.get("role") in PLATFORM_ROLES]

        stops, named = [], 0
        last_key = None
        for m in stop_members:
            key = (m["type"], m["ref"])
            info = elements.get(key)
            if info is None or key == last_key:
                continue
            last_key = key
            if info["name"]:
                named += 1
            ego_no = info["ref"] if info["ref"].isdigit() else str(m["ref"])
            stops.append({
                "id": ego_no,
                "name": info["name"] or "İsimsiz Durak",
                "lat": round(info["lat"], 6),
                "lon": round(info["lon"], 6),
            })

        if len(stops) < MIN_STOPS:
            skipped[f"<{MIN_STOPS} durak"] += 1
            continue
        if named / len(stops) < MIN_NAMED_RATIO:
            skipped["duraklar adsız"] += 1
            continue

        route_name = name if name else f"{ref}: {tags.get('from', '?')} - {tags.get('to', '?')}"
        route_entry = {
            "routeId": ref,
            "routeName": route_name,
            "stops": stops,
        }
        # Gerçek yol geometrisi: way parçalarını zincirle, kaliteyi doğrula,
        # ~10 m toleransla sadeleştir. Kalitesiz shape hiç yazılmaz.
        first_stop = (stops[0]["lat"], stops[0]["lon"])
        shape = build_shape(rel, way_geoms, first_stop)
        if len(shape) >= 2 and shape_quality_ok(shape, stops):
            shape = simplify(shape, SHAPE_TOLERANCE_M)
            route_entry["shape"] = [[round(p[0], 5), round(p[1], 5)] for p in shape]
        else:
            skipped["shape reddedildi"] += 1
        routes.append(route_entry)

    # Aynı ref'i taşıyan birden çok relation (yön/varyant) → routeId'yi eşsizleştir
    seen = Counter()
    for r in routes:
        seen[r["routeId"]] += 1
        if seen[r["routeId"]] > 1:
            r["routeId"] = f'{r["routeId"]}/{seen[r["routeId"]]}'

    routes.sort(key=lambda r: r["routeId"])
    return routes, skipped


def merge_with_existing(new_routes):
    existing = {"routes": []}
    if ROUTES_JSON.exists():
        existing = json.loads(ROUTES_JSON.read_text(encoding="utf-8"))
    existing_ids = {r["routeId"] for r in existing["routes"]}
    merged = existing["routes"] + [r for r in new_routes if r["routeId"] not in existing_ids]
    return {"routes": merged}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true", help="dosya yazma, özet göster")
    parser.add_argument("--input", help="önceden indirilmiş Overpass JSON dosyası")
    parser.add_argument("--print-query", action="store_true", help="Overpass sorgusunu yazdır ve çık")
    args = parser.parse_args()

    if args.print_query:
        print(QUERY.strip())
        return

    if args.input:
        osm = json.loads(Path(args.input).read_text(encoding="utf-8"))
    else:
        osm = fetch_overpass()
    routes, skipped = extract_routes(osm)

    print(f"\nOSM'den kullanılabilir hat: {len(routes)}")
    for reason, count in skipped.items():
        print(f"  elendi ({reason}): {count}")
    total_stops = sum(len(r["stops"]) for r in routes)
    print(f"Toplam durak kaydı: {total_stops}")
    shaped = [r for r in routes if "shape" in r]
    if shaped:
        pts = sum(len(r["shape"]) for r in shaped)
        print(f"Yol geometrisi olan hat: {len(shaped)} (sadeleştirilmiş {pts} nokta)")
    for r in routes[:10]:
        print(f"  {r['routeId']:>8}  {len(r['stops']):>3} durak  {r['routeName'][:60]}")
    if len(routes) > 10:
        print(f"  ... ve {len(routes) - 10} hat daha")

    if args.dry_run:
        return

    merged = merge_with_existing(routes)
    ROUTES_JSON.write_text(
        json.dumps(merged, ensure_ascii=False, indent=1),
        encoding="utf-8", newline="\n")
    size_kb = ROUTES_JSON.stat().st_size / 1024
    print(f"\nYazıldı: {ROUTES_JSON} ({len(merged['routes'])} hat, {size_kb:.0f} KB)")


if __name__ == "__main__":
    main()
