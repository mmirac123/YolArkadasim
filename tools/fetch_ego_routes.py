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
[out:json][timeout:180];
rel["route"="bus"]["operator"~"EGO",i]({BBOX})->.r;
.r out body;
node(r.r);
out body;
way(r.r);
out center;
"""

MIN_STOPS = 10          # bundan az durağı çözülebilen hatlar elenir
MIN_NAMED_RATIO = 0.7   # durakların en az bu oranı adlandırılmış olmalı

# Not: Ankara'daki OSM katkıcıları standart "stop" yerine yaygın olarak
# "bus_stop" rolünü kullanmış — ikisi de kabul edilir.
STOP_ROLES = {"stop", "stop_entry_only", "stop_exit_only", "bus_stop"}
PLATFORM_ROLES = {"platform", "platform_entry_only", "platform_exit_only"}

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


def extract_routes(osm):
    # Duraklar OSM'de bazen nokta (highway=bus_stop), bazen way (platform alanı)
    # olarak çizilir; way'ler için Overpass'ın verdiği merkez koordinat kullanılır.
    elements = {}  # (type, id) -> {lat, lon, name, ref}
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
        elif el["type"] == "way" and "center" in el:
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
        routes.append({
            "routeId": ref,
            "routeName": route_name,
            "stops": stops,
        })

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
