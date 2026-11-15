from pathlib import Path
import re
import json

HERE = Path(__file__).resolve().parent
SOURCE = HERE / 'map-source' / 'samara-cameras.html'
OUTPUT = HERE / 'assets' / 'index.html'
STACKED = '(max-width:700px) and (orientation:portrait), (max-width:559px)'

STYLE = """
.quick-search{display:none}
.tiles img{opacity:1}
.gps{position:absolute;bottom:25px;right:12px;max-width:min(265px,65%);display:grid;justify-items:end;gap:4px}
.gps button,.gps output{background:#fff;border:1px solid #becfc7;color:#164f49;border-radius:9px;padding:6px 10px;box-shadow:0 2px 9px #1333}
.gps button{font-weight:750;min-height:38px}.gps output{font-size:.79rem;text-align:right}.gps output:empty{display:none}
.gps .active{background:#0c625a;color:white}
.mark.control{display:flex;gap:4px;justify-content:center;align-items:center;
  background:white;color:#223333;border:1px solid #769087;border-radius:9px;
  min-width:31px;height:28px;padding:0 5px;white-space:nowrap;font-size:.76rem}
.mark.control.speed{border:2px solid #c23530}
.mark.control.close-camera{z-index:2;box-shadow:0 0 0 2px #ffb748,0 1px 5px #14392f65}
.signal-icon{display:grid;gap:1px;padding:2px 3px;background:#253235;border-radius:3px}
.signal-icon i{display:block;width:4px;height:4px;border-radius:50%}
.signal-icon i:nth-child(1){background:#ff5d54}
.signal-icon i:nth-child(2){background:#ffca50}
.signal-icon i:nth-child(3){background:#68d293}
.position{position:absolute;width:20px;height:20px;border:3px solid white;border-radius:50%;background:#176bc4;box-shadow:0 0 0 4px #176bc450;transform:translate(-50%,-50%);pointer-events:none;z-index:3}
@media(max-width:700px){
  .workspace{grid-template-rows:minmax(350px,56vh) auto}
  .quick-search{display:block;position:absolute;bottom:25px;left:12px;background:#fff;
    border:1px solid #becfc7;color:#164f49;border-radius:9px;padding:8px 12px;font-weight:700;
    box-shadow:0 2px 9px #1333}
  .tile-status{bottom:68px}
}
@media (orientation:landscape) and (min-width:560px) and (max-height:600px){
  body{height:100vh;display:flex;flex-direction:column}
  header{padding:6px 14px;flex:none}
  header h1{font-size:1.18rem}
  header .eyebrow,header p:last-child{display:none}
  .stamp{padding:3px 8px}
  .workspace{height:auto;flex:1;min-height:0;
    grid-template-columns:clamp(205px,32vw,280px) minmax(0,1fr)}
  aside{overflow:auto}
  .map{min-height:0}
  .filters{padding:8px 10px}
  .filters h2{display:none}
  label.field{margin-top:5px}
  .check,.actions{margin-top:6px}
  .result-head{padding:5px 10px}
  .results{min-height:60px}
  .legend{font-size:.67rem}
  .card{max-height:75%}
}
"""

GESTURES = """
const pointers=new Map();
let dragged=false;
function pinchGeometry(){
  const [a,b]=pointers.values();
  return {x:(a.x+b.x)/2,y:(a.y+b.y)/2,span:Math.max(12,Math.hypot(a.x-b.x,a.y-b.y))};
}
function moveMap(dx,dy){
  followLocation=false;
  center=[clamp(center[0]-dx/scale(),0,1),clamp(center[1]-dy/scale(),0,1)];
  draw();
}
view.addEventListener('pointerdown',e=>{
  const marker=e.target.closest('button.mark');
  if(pointers.size>=2||(e.pointerType==='mouse'&&(e.button!==0||marker)))return;
  dragged=pointers.size>0;
  pointers.set(e.pointerId,{x:e.clientX,y:e.clientY,marker});
  view.setPointerCapture(e.pointerId);
  view.classList.add('drag');
});
view.addEventListener('pointermove',e=>{
  const pointer=pointers.get(e.pointerId);
  if(!pointer)return;
  const dx=e.clientX-pointer.x, dy=e.clientY-pointer.y;
  if(!dragged&&Math.hypot(dx,dy)<5)return;
  const before=pointers.size===2?pinchGeometry():null;
  pointer.x=e.clientX;pointer.y=e.clientY;
  dragged=true;
  if(!before){
    moveMap(dx,dy);
    return;
  }
  const after=pinchGeometry();
  moveMap(after.x-before.x,after.y-before.y);
  const rect=view.getBoundingClientRect();
  setZoom(zoom+Math.log2(after.span/before.span),after.x-rect.left,after.y-rect.top);
});
for(const type of ['pointerup','pointercancel','lostpointercapture']){
  view.addEventListener(type,e=>{
    const pointer=pointers.get(e.pointerId);
    if(!pointer)return;
    pointers.delete(e.pointerId);
    if(!pointers.size){
      view.classList.remove('drag');
      if(type==='pointerup'&&!dragged&&pointer.marker&&pointer.marker.isConnected){
        pointer.marker.click();
      }
    }
  });
}
"""

GPS = """
let locationWatch=null, locationTimer=null, currentLocation=null, followLocation=false;
const gpsButton=$('gps-start'), recenter=$('gps-center'), gpsStatus=$('gps-status');
const CAMERA_ALERT_DISTANCE=500;
let nearestPlace=null;
function setGpsMessage(message){
  gpsStatus.textContent=message;
}
function decorateCameraMark(mark,point){
  const speed=point.tags.has('Скорость');
  const signal=point.tags.has('Проезд на запрещающий сигнал');
  const stop=point.tags.has('Стоп-линия');
  if(!speed&&!signal&&!stop)return;
  mark.textContent='';
  mark.classList.add('control');
  if(signal){
    const icon=element('span','','signal-icon');
    icon.setAttribute('aria-hidden','true');
    icon.append(element('i'),element('i'),element('i'));
    mark.append(icon);
  }
  if(speed){
    mark.classList.add('speed');
    const limit=speedLimits[point.key];
    mark.append(element('span',limit?String(limit):'Скор.'));
  }
  if(stop)mark.append(element('span','Стоп'));
  mark.title=(speed&&speedLimits[point.key]?speedLimits[point.key]+' км/ч по OpenStreetMap · ':'')
    +[...point.tags].join(' · ')+' — '+point.title;
}
function distanceMeters(a,b){
  const rad=Math.PI/180;
  const h=Math.sin((b.lat-a.lat)*rad/2)**2+
    Math.cos(a.lat*rad)*Math.cos(b.lat*rad)*Math.sin((b.lon-a.lon)*rad/2)**2;
  return 12742000*Math.asin(Math.sqrt(Math.min(1,h)));
}
function showPosition(nodes){
  if(!currentLocation)return;
  const [x,y]=screen(currentLocation.xy);
  const dot=element('span','','position');
  dot.style.left=x+'px';dot.style.top=y+'px';
  dot.setAttribute('role','img');dot.setAttribute('aria-label','Ваше местоположение');
  nodes.append(dot);
}
function centerOnLocation(){
  if(!currentLocation)return;
  followLocation=true;
  center=[...currentLocation.xy];
  zoom=Math.max(zoom,15);
  draw();
}
function updateLocation({coords}){
  if(locationWatch===null)return;
  clearTimeout(locationTimer);
  locationTimer=setTimeout(()=>{
    currentLocation=null;nearestPlace=null;recenter.hidden=true;draw();
    setGpsMessage('Нет свежего сигнала GPS');
  },20000);
  if(!Number.isFinite(coords.accuracy)||coords.accuracy>100){
    currentLocation=null;nearestPlace=null;recenter.hidden=true;draw();
    setGpsMessage('Ждём точный сигнал GPS');
    return;
  }
  const first=!currentLocation;
  currentLocation={lat:coords.latitude,lon:coords.longitude,
    xy:project(coords.latitude,coords.longitude)};
  recenter.hidden=false;
  if(followLocation){
    center=[...currentLocation.xy];
    if(first)zoom=Math.max(zoom,15);
  }
  let count=0,nearestDistance=Infinity,nearestPoint=null;
  for(const point of current){
    const distance=distanceMeters(currentLocation,point);
    if(distance>1000)continue;
    count++;
    if(distance<nearestDistance){
      nearestDistance=distance;
      nearestPoint=point;
    }
  }
  nearestPlace=nearestDistance<=CAMERA_ALERT_DISTANCE?nearestPoint:null;
  setGpsMessage(count
    ? 'Ближайшая камера · '+Math.round(nearestDistance)+' м'
    : 'В радиусе 1 км нет камер из реестра');
  draw();
}
function stopLocation(){
  if(locationWatch!==null)navigator.geolocation.clearWatch(locationWatch);
  clearTimeout(locationTimer);
  locationWatch=null;currentLocation=null;nearestPlace=null;followLocation=false;
  recenter.hidden=true;setGpsMessage('');
  gpsButton.textContent='Включить GPS';gpsButton.classList.remove('active');
  gpsButton.setAttribute('aria-pressed','false');
  draw();
}
gpsButton.onclick=()=>{
  if(locationWatch!==null){stopLocation();return;}
  if(!navigator.geolocation){setGpsMessage('Геолокация недоступна');return;}
  followLocation=true;
  gpsButton.textContent='Остановить GPS';gpsButton.classList.add('active');
  gpsButton.setAttribute('aria-pressed','true');
  setGpsMessage('Получаем координаты GPS…');
  locationWatch=navigator.geolocation.watchPosition(updateLocation,error=>{
    if(locationWatch===null)return;
    if(error.code===1){
      stopLocation();
      setGpsMessage('Разрешите точное местоположение в настройках Android');
    }else{
      currentLocation=null;nearestPlace=null;recenter.hidden=true;draw();
      setGpsMessage('Проверьте, включена ли геолокация');
    }
  },{enableHighAccuracy:true,maximumAge:0,timeout:15000});
};
recenter.onclick=centerOnLocation;
window.addEventListener('pagehide',stopLocation);
"""

REGISTRY_UPDATE = r"""
const registryButton=$('registry-update'), registryStatus=$('registry-status');
const updateNotice=sessionStorage.getItem('registry-notice');
if(updateNotice){
  registryStatus.textContent=updateNotice;
  sessionStorage.removeItem('registry-notice');
}
async function registryPage(number){
  const response=await fetch('/registry/'+number,{cache:'no-store'});
  if(!response.ok)throw new Error('HTTP '+response.status);
  return response.text();
}
function readRegistryPoints(html){
  const points=[], ids=new Set();
  const pattern=/data\.points\.push\(\{([\s\S]*?)\}\);/g;
  let match;
  while((match=pattern.exec(html))!==null){
    const block=match[1];
    const id=block.match(/\/milestones\/(\d+)/);
    const model=block.match(/balloonContentHeader:\s*("(?:\\.|[^"\\])*")/);
    const coords=block.match(/coordinates:\s*\[\s*([\d.]+)\s*,\s*([\d.]+)\s*\]/);
    if(!id||!model||!coords||ids.has(id[1]))throw new Error('Invalid registry point');
    const lat=Number(coords[1]), lon=Number(coords[2]);
    if(!(51<lat&&lat<56&&46<lon&&lon<54))throw new Error('Invalid coordinates');
    ids.add(id[1]);
    points.push([id[1],JSON.parse(model[1]),lat,lon]);
  }
  if(points.length<0.8*data.points.length)throw new Error('Incomplete registry');
  return {points,ids};
}
function readRegistryPage(html,number,ids){
  const template=document.createElement('template');
  template.innerHTML=html;
  const doc=template.content;
  const active=doc.querySelector('.pages a.active');
  if(!active||active.getAttribute('href')!=='?page='+number)throw new Error('Wrong registry page');
  const rows=[];
  for(const a of doc.querySelectorAll('.sl-item-title a[href^="/milestones/"]')){
    const id=a.getAttribute('href').split('/').pop();
    if(!ids.has(id))throw new Error('Registry changed during download');
    const address=a.closest('.sl-item').querySelector('.sl-item-text').textContent.trim();
    rows.push([id,address,number]);
  }
  if(!rows.length)throw new Error('Empty registry page');
  const next=doc.querySelector('.pages a.active ~ a.next');
  return {rows,hasNext:!!next};
}
registryButton.onclick=async()=>{
  if(locationWatch!==null){
    registryStatus.textContent='Остановите GPS, чтобы обновить базу.';
    return;
  }
  registryButton.disabled=true;
  registryStatus.textContent='Подключаемся к Госавтоинспекции…';
  try{
    const first=await registryPage(1);
    const {points,ids}=readRegistryPoints(first);
    const date=first.match(/обновлено\s+(\d{2}\.\d{2}\.\d{4})/i);
    if(!date)throw new Error('Missing registry date');
    const rows=[];
    let pages=0,hasNext=true;
    while(hasNext&&pages<200){
      pages++;
      registryStatus.textContent='Загружаем страницу '+pages+'…';
      const page=readRegistryPage(pages===1?first:await registryPage(pages),pages,ids);
      rows.push(...page.rows);
      hasNext=page.hasNext;
    }
    if(hasNext||rows.length<0.8*points.length)throw new Error('Incomplete registry pages');
    localStorage.setItem('camera-registry',JSON.stringify({points,rows,pages,registryDate:date[1]}));
    sessionStorage.setItem('registry-notice','База обновлена · '+date[1]+' · '+fmt(points.length)+' записей');
    location.reload();
  }catch(error){
    registryStatus.textContent='Не удалось обновить базу. Сохранена предыдущая версия.';
  }finally{
    registryButton.disabled=false;
  }
};
"""

APP_CONTROLS = """
$('quick-search').onclick=()=>{
  $('search').focus({preventScroll:true});
  document.querySelector('.filters').scrollIntoView({block:'start'});
};
$('fit').onclick=()=>{
  followLocation=false;
  fit(visible);
  $('search').blur();
  view.closest('.map').scrollIntoView({block:'start'});
};
window.closeMapCard=()=>{
  if(card.hidden)return false;
  card.querySelector('.close').click();
  return true;
};
"""


def substitute(html, old, new):
    if html.count(old) != 1:
        raise ValueError(f'Expected one match for {old[:60]!r}')
    return html.replace(old, new)


html = SOURCE.read_text(encoding='utf-8')
# Older Android WebViews ignore the inset shorthand, leaving the map at zero size.
html = html.replace('position:absolute;inset:0;', 'position:absolute;top:0;right:0;bottom:0;left:0;')
speed_data = json.loads((HERE / 'speed-limits.json').read_text(encoding='utf-8'))
html = substitute(html, '<script id="camera-data"',
                  '<script id="speed-data" type="application/json">'
                  + json.dumps(speed_data, separators=(',', ':')) + '</script>\n<script id="camera-data"')
html = substitute(html, "const root='https://", """const speedData=JSON.parse(document.getElementById('speed-data').textContent);
const speedLimits=speedData.limits;
const root='https://""")
html = substitute(html, "card.append(element('h3','Что фиксируется на месте'));",
                  """if(p.tags.has('Скорость')&&speedLimits[p.key]){
    card.append(element('p','Лимит участка по OpenStreetMap: '+speedLimits[p.key]
      +' км/ч · данные от '+speedData.snapshot.slice(0,10),'muted'));
  }
  card.append(element('h3','Что фиксируется на месте'));""")
html = substitute(html, "const data=JSON.parse(document.getElementById('camera-data').textContent);",
                  """const data=JSON.parse(document.getElementById('camera-data').textContent);
try{
  const cached=JSON.parse(localStorage.getItem('camera-registry'));
  if(cached&&Array.isArray(cached.points)&&Array.isArray(cached.rows)){
    Object.assign(data,cached);
  }
}catch(error){}
""")
html = substitute(html, 'id="reset">Сбросить</button></div>',
                  'id="reset">Сбросить</button><button class="soft" id="registry-update">Обновить базу</button></div>'
                  '<div id="registry-status" role="status"></div>')
html = substitute(html, '</style>', STYLE + '</style>')
html = html.replace('@media(max-width:700px)', '@media ' + STACKED)
html = substitute(html, "matchMedia('(max-width:700px)')", f"matchMedia('{STACKED}')")
html = substitute(html, '<div class="legend">',
                  '<button class="quick-search" id="quick-search">Поиск и фильтры</button>\n'
                  '    <div class="legend">')
html = substitute(html, '<section class="card" id="card"',
                  '<div class="gps"><output id="gps-status"></output>'
                  '<button id="gps-center" hidden>К моему месту</button>'
                  '<button id="gps-start" aria-pressed="false">Включить GPS</button></div>\n'
                  '    <section class="card" id="card"')
html = substitute(html, 'marks.replaceChildren(nodes);',
                  'showPosition(nodes); marks.replaceChildren(nodes);')
html = substitute(html, 'zoom<17&&p!==selected?', 'zoom<17&&p!==selected&&p!==nearestPlace?')
html = substitute(html, "mark.setAttribute('aria-label',mark.title);", """if(!cluster){
        decorateCameraMark(mark,p);
        if(p===nearestPlace)mark.classList.add('close-camera');
      }
      mark.setAttribute('aria-label',mark.title);""")
html = substitute(html, 'function fit(points){', 'function fit(points){\n  followLocation=false;')
html = substitute(html, 'if(zoomTo){', 'if(zoomTo){\n    followLocation=false;')
html = html.replace(' target="_blank"', '').replace(" e.target='_blank';", '')
html = substitute(html, ', SRTM · © <a href="https://opentopomap.org/about" rel="noopener">OpenTopoMap</a> (<a href="https://creativecommons.org/licenses/by-sa/3.0/" rel="noopener">CC BY-SA</a>)',
                  ' contributors<span id="backup-credit" hidden> · © <a href="https://opentopomap.org/about" rel="noopener">OpenTopoMap</a> (CC BY-SA), SRTM</span>')
html = substitute(html, 'Подложка недоступна. Точки и поиск работают.',
                  'Дороги не загрузились. Проверьте интернет на магнитоле. Метки доступны.')
html = substitute(html, 'const z=Math.min(zoom,17),', 'const z=Math.min(Math.floor(zoom),17),')
html = substitute(html, 'const tileNodes=new Map();', """const tileNodes=new Map();
function showTileStatus(){
  const images=Array.from(tileNodes.values());
  const failed=images.some(image=>image.dataset.state==='failed');
  const loaded=images.some(image=>image.dataset.state==='loaded');
  $('tile-status').hidden=!failed||loaded;
  $('backup-credit').hidden=!images.some(image=>image.dataset.backup==='true'&&image.dataset.state==='loaded');
}
function loadTile(image,key){
  let backup=false;
  let timeout;
  function failed(){
    clearTimeout(timeout);
    if(!image.isConnected)return;
    if(!backup){
      backup=true;
      image.dataset.backup='true';
      image.src='https://tile.opentopomap.org/'+key+'.png';
      timeout=setTimeout(failed,7000);
      return;
    }
    image.dataset.state='failed';
    showTileStatus();
  }
  image.onerror=failed;
  image.onload=()=>{
    clearTimeout(timeout);
    image.dataset.state='loaded';
    showTileStatus();
  };
  image.src='https://tile.openstreetmap.org/'+key+'.png';
  timeout=setTimeout(failed,5000);
}
""")
html = substitute(html, """        image.addEventListener('error',()=>{$('tile-status').hidden=false});
        image.addEventListener('load',()=>{$('tile-status').hidden=true});
        image.src='https://tile.opentopomap.org/'+key+'.png';
        tiles.append(image);tileNodes.set(key,image);""",
                  """        tiles.append(image);tileNodes.set(key,image);
        loadTile(image,key);""")
html = substitute(html, 'if(!needed.has(key)){node.remove();tileNodes.delete(key)}',
                  'if(!needed.has(key)){node.remove();tileNodes.delete(key)}\n  showTileStatus();')
html = substitute(html, 'Math.floor(x*size-left)', '(x*size-left)')
html = substitute(html, 'Math.floor(y*size-top)', '(y*size-top)')
html = substitute(html, ".replaceAll('ё','е')", ".replace(/ё/g,'е')")
html = substitute(html, "p.cams.flatMap(c=>[c.id,c.model])", "[].concat(...p.cams.map(c=>[c.id,c.model]))")
html = substitute(html, "'use strict';", """'use strict';
function replaceContent(node,...children){
  node.textContent='';
  node.append(...children);
}
""")
html = re.sub(r'\b(marks|card|target)\.replaceChildren\(([^)]*)\)',
              lambda m: 'replaceContent(' + m[1] + (',' + m[2] if m[2] else '') + ')', html)
start = html.index('let drag=null;')
end = html.index("view.addEventListener('keydown'", start)
html = html[:start] + GESTURES + html[end:]
html = substitute(html, "new ResizeObserver(()=>{if(!ready&&view.clientWidth){ready=true;fit(current)}else draw()}).observe(view);",
                  GPS + """function resizeMap(){
  if(!ready&&view.clientWidth){ready=true;fit(current)}else draw();
}
if(typeof ResizeObserver==='function')new ResizeObserver(resizeMap).observe(view);
else window.addEventListener('resize',resizeMap);
resizeMap();
""" + APP_CONTROLS + REGISTRY_UPDATE)
OUTPUT.parent.mkdir(parents=True, exist_ok=True)
OUTPUT.write_text(html, encoding='utf-8')
