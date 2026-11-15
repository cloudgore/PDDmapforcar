"""Refresh the embedded public registry and optionally replace the regional report."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime
from html import unescape
import json
from pathlib import Path
import re
import subprocess
from tempfile import TemporaryDirectory
from urllib.parse import urljoin
from urllib.request import Request, build_opener, HTTPCookieProcessor
import xml.etree.ElementTree as ET
from zipfile import ZipFile

ROOT = 'https://xn--80aebkobnwfcnsfk1e0h.xn--p1ai'
REGISTRY = ROOT + '/r/63/milestones/?page=1'
TAG = re.compile(r'(<script id="camera-data" type="application/json">)(.*?)(</script>)', re.S)
STRING = r'"(?:\\.|[^"\\])*"'
NS = '{http://schemas.openxmlformats.org/spreadsheetml/2006/main}'


def clean_html(value):
    return ' '.join(unescape(re.sub(r'<[^>]*>', ' ', value)).split())


def read_map(html):
    points = []
    for block in re.findall(r'data\.points\.push\(\{(.*?)\}\);', html, re.S):
        link = re.search(r'/milestones/(\d+)', block)
        model = re.search(r'balloonContentHeader:\s*(' + STRING + ')', block)
        coords = re.search(r'coordinates:\s*\[\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*\]', block)
        if not all((link, model, coords)):
            raise ValueError('Изменилась структура точек реестра.')
        points.append([link[1], unescape(json.loads(model[1])), float(coords[1]), float(coords[2])])
    validate_points(points)
    return points


def validate_points(points):
    if not points or len(points) != len({row[0] for row in points}):
        raise ValueError('Реестр пуст или содержит повторяющиеся ID точек.')
    if any(not (51 < row[2] < 56 and 46 < row[3] < 54) for row in points):
        raise ValueError('В реестре обнаружены координаты за пределами ожидаемого диапазона.')


def page_rows(html, number):
    found = []
    for identifier, address in re.findall(
        r'<div\s+class="sl-item-title">\s*<a\s+href="/milestones/(\d+)"[^>]*>.*?</a>'
        r'\s*</div>\s*<div\s+class="sl-item-text">\s*(.*?)</div>', html, re.S
    ):
        found.append([identifier, clean_html(address), number])
    return found


def load_registry():
    opener = build_opener(HTTPCookieProcessor())

    def fetch(url):
        with opener.open(Request(url, headers={'User-Agent': 'SamaraCameraMap/1.0'}), timeout=30) as response:
            return response.read().decode(response.headers.get_content_charset() or 'utf-8')

    first = fetch(REGISTRY)
    points = read_map(first)
    published = re.search(r'обновлено\s+(\d{2}\.\d{2}\.\d{4})', first, re.I)
    first_rows = page_rows(first, 1)
    if not first_rows:
        raise ValueError('Не удалось прочитать адреса на первой странице.')
    pages = {1: first_rows}

    def page_links(html):
        return {int(n): urljoin(REGISTRY, f'?page={n}')
                for n in re.findall(r'<a[^>]+href="\?page=(\d+)"', html)}

    queue = {n: url for n, url in page_links(first).items() if n != 1}
    with ThreadPoolExecutor(max_workers=3) as pool:
        while queue:
            batch = dict(queue)
            queue.clear()
            for number, html in zip(batch, pool.map(fetch, batch.values())):
                rows = page_rows(html, number)
                if not rows:
                    raise ValueError(f'Не удалось прочитать адреса на странице {number}.')
                pages[number] = rows
                for next_page, url in page_links(html).items():
                    if next_page not in pages and next_page not in batch:
                        queue[next_page] = url
    rows = [row for number in sorted(pages) for row in pages[number]]
    return {'points': points, 'rows': rows, 'pages': len(pages),
            'registryDate': published[1] if published else '', 'retrieved': date.today().isoformat()}


def xlsx_rows(path):
    with ZipFile(path) as book:
        strings = []
        if 'xl/sharedStrings.xml' in book.namelist():
            strings = [''.join(t.text or '' for t in node.iter(NS + 't'))
                       for node in ET.fromstring(book.read('xl/sharedStrings.xml'))]
        sheet = ET.fromstring(book.read('xl/worksheets/sheet1.xml'))
    cells = {}
    for cell in sheet.iter(NS + 'c'):
        key = cell.attrib['r']
        value = cell.findtext(NS + 'v') or ''
        if cell.get('t') == 's':
            value = strings[int(value)]
        elif cell.get('t') == 'inlineStr':
            value = ''.join(node.text or '' for node in cell.iter(NS + 't'))
        if value.strip():
            cells[key] = value.strip()
    for item in sheet.iter(NS + 'mergeCell'):
        match = re.fullmatch(r'([ADE])(\d+):\1(\d+)', item.attrib['ref'])
        if match and int(match[2]) >= 4:
            for row in range(int(match[2]) + 1, int(match[3]) + 1):
                cells.setdefault(match[1] + str(row), cells.get(match[1] + match[2], ''))
    if (cells.get('A2'), cells.get('B3'), cells.get('C3')) != ('Адрес', 'Широта', 'Долгота'):
        raise ValueError('Ожидалась региональная таблица с адресом, широтой и долготой.')
    count = max(int(re.search(r'\d+$', key)[0]) for key in cells)
    rows = []
    for number in range(4, count + 1):
        address, latitude, longitude, mode, violation = [cells.get(f'{col}{number}', '') for col in 'ABCDE']
        if not any((address, latitude, longitude, mode, violation)):
            continue
        try:
            lat, lon = (float(value.replace(',', '.')) for value in (latitude, longitude))
        except ValueError as error:
            raise ValueError(f'Проверьте координаты в строке {number}.') from error
        if not (51 < lat < 56 and 46 < lon < 54):
            raise ValueError(f'Проверьте координаты в строке {number}.')
        rows.append([number, address, lat, lon, mode, violation])
    if not rows:
        raise ValueError('В региональной таблице нет записей.')
    return rows


def read_report(path, soffice):
    if path.suffix.lower() == '.xlsx':
        return xlsx_rows(path)
    if path.suffix.lower() != '.xls':
        raise ValueError('Поддерживаются региональные таблицы XLS и XLSX.')
    with TemporaryDirectory() as temp:
        profile = (Path(temp) / 'profile').as_uri()
        subprocess.run([soffice, f'-env:UserInstallation={profile}', '--headless', '--convert-to',
                        'xlsx', '--outdir', temp, str(path.resolve())], check=True, timeout=90)
        return xlsx_rows(Path(temp) / (path.stem + '.xlsx'))


def update():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--map', type=Path, default=Path(__file__).with_name('samara-cameras.html'))
    parser.add_argument('--report', type=Path)
    parser.add_argument('--report-date', help='ДД.ММ.ГГГГ')
    parser.add_argument('--report-url', default='')
    parser.add_argument('--soffice', default=r'C:\Program Files\LibreOffice\program\soffice.com')
    parser.add_argument('--report-only', action='store_true')
    parser.add_argument('--registry-json', type=Path, help='Локальный снимок реестра в JSON')
    args = parser.parse_args()
    if bool(args.report) != bool(args.report_date) or (args.report_only and not args.report):
        parser.error('Для замены перечня укажите --report и --report-date.')
    if args.report_date:
        datetime.strptime(args.report_date, '%d.%m.%Y')
    html = args.map.read_text(encoding='utf-8')
    found = TAG.search(html)
    if not found:
        raise ValueError('В HTML не найден блок данных.')
    data = json.loads(found[2])
    if not args.report_only:
        registry = (json.loads(args.registry_json.read_text(encoding='utf-8-sig'))
                    if args.registry_json else load_registry())
        validate_points(registry['points'])
        if data.get('points') and len(registry['points']) < 0.8 * len(data['points']):
            raise ValueError('В новом снимке более чем на 20% меньше точек. Проверьте источник.')
        data.update(registry)
    if args.report:
        data.update(report=read_report(args.report, args.soffice), reportDate=args.report_date,
                    reportUrl=args.report_url)
    encoded = json.dumps(data, ensure_ascii=False, separators=(',', ':')).replace('<', r'\u003c')
    result = html[:found.start(2)] + encoded + html[found.end(2):]
    temp = args.map.with_suffix('.tmp')
    temp.write_text(result, encoding='utf-8')
    temp.replace(args.map)
    print(f"Записей на карте реестра: {len(data['points'])}. Страниц: {data['pages']}. "
          f"Адресов по ID: {len({row[0] for row in data['rows']})}. Строк в перечне: {len(data['report'])}.")


if __name__ == '__main__':
    try:
        update()
    except (OSError, ValueError, subprocess.SubprocessError, KeyError) as error:
        raise SystemExit(f'Обновление остановлено. Карта не заменена. {error}') from error
