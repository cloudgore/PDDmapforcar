
import json
import re
from pathlib import Path
from collections import defaultdict

SOURCE = Path(r"D:\Проекты\PDDmapforcar\map-source\samara-cameras.html")
OUTPUT = Path(r"D:\Project\PDDmapNative\app\src\main\assets\cameras.geojson")

html = SOURCE.read_text(encoding="utf-8")

match = re.search(
    r'<script[^>]*id="camera-data"[^>]*>(.*?)</script>',
    html,
    re.DOTALL
)

if not match:
    raise RuntimeError("Не найден camera-data")

data = json.loads(match.group(1))

# Индекс сведений о нарушениях по координатам.
# Округляем до 6 знаков, чтобы избежать мелких расхождений.
reports = defaultdict(list)

for row in data["report"]:
    if len(row) < 6:
        continue

    number, address, lat, lon, schedule, violations = row[:6]

    key = (round(float(lat), 6), round(float(lon), 6))

    reports[key].append({
        "address": str(address),
        "schedule": str(schedule),
        "violations": str(violations)
    })


def detect_types(text):
    text = text.lower()
    types = []

    if "скорост" in text:
        types.append("speed")

    if "сигнал светофор" in text or "запрещающий сигнал" in text:
        types.append("traffic_light")

    if "стоп-лини" in text or "стоп лини" in text:
        types.append("stop_line")

    if "встречн" in text:
        types.append("oncoming")

    if "полос" in text and "маршрут" in text:
        types.append("bus_lane")

    if "ремн" in text:
        types.append("seatbelt")

    if "телефон" in text:
        types.append("phone")

    return types or ["unknown"]


features = []
matched = 0
type_counts = defaultdict(int)

for point in data["points"]:
    camera_id, name, lat, lon = point[:4]

    lat = float(lat)
    lon = float(lon)

    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        continue

    key = (round(lat, 6), round(lon, 6))
    found = reports.get(key, [])

    violations = " ".join(
        item["violations"] for item in found
    )

    camera_types = detect_types(violations)

    priority = [
    "traffic_light",
    "speed",
    "oncoming",
    "stop_line",
    "bus_lane",
    "seatbelt",
    "phone",
    "unknown"
]

    camera_types.sort(
    key=lambda t: priority.index(t) if t in priority else 999
    )

    if found:
        matched += 1

    for camera_type in camera_types:
        type_counts[camera_type] += 1

    features.append({
        "type": "Feature",
        "geometry": {
            "type": "Point",
            "coordinates": [lon, lat]
        },
        "properties": {
            "id": str(camera_id),
            "name": str(name),
            "camera_type": camera_types[0],
            "camera_types": ",".join(camera_types),
            "address": found[0]["address"] if found else "",
            "violations": violations,
            "speed_limit": None
        }
    })

OUTPUT.parent.mkdir(parents=True, exist_ok=True)

OUTPUT.write_text(
    json.dumps(
        {"type": "FeatureCollection", "features": features},
        ensure_ascii=False,
        separators=(",", ":")
    ),
    encoding="utf-8"
)

print("Всего камер:", len(features))
print("Сопоставлено с реестром:", matched)
print("Без совпадения:", len(features) - matched)
print("Типы нарушений:")

for camera_type, count in sorted(type_counts.items()):
    print(f"  {camera_type}: {count}")

print("Файл:", OUTPUT)
