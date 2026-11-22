const ENDPOINT = "wss://api.derivws.com/trading/v1/options/ws/public";
const DEFAULTS = ["CRASH50", "frxXAUUSD"];
const $ = (id) => document.getElementById(id);
let socket = null, reconnectTimer = null, retry = 0, catalogue = [], selected = loadSelected();
let lastPrices = new Map(), subscriptions = new Map(), scale = 100;
function loadSelected(){try{return JSON.parse(localStorage.getItem("zichan.markets")) || DEFAULTS.slice()}catch{return DEFAULTS.slice()}}
function saveSelected(){localStorage.setItem("zichan.markets",JSON.stringify(selected))}
function setStatus(state,message){$("statusDot").className="dot "+state;$("statusText").textContent=message}
function connect(){
  clearTimeout(reconnectTimer); setStatus("amber","CONNECTING");
  try{socket=new WebSocket(ENDPOINT)}catch(e){scheduleReconnect();return}
  socket.onopen=()=>{retry=0;subscriptions.clear();setStatus("amber","SYNCING MARKETS");socket.send(JSON.stringify({active_symbols:"brief"}))};
  socket.onmessage=(event)=>{let data;try{data=JSON.parse(event.data)}catch{return}
    if(data.error){setStatus("red","API ERROR");return}
    if(data.msg_type==="active_symbols"){catalogue=data.active_symbols||[];subscribeSelected();renderSearch()}
    if(data.msg_type==="tick"){const t=data.tick;if(!t)return;const old=lastPrices.get(t.symbol);lastPrices.set(t.symbol,{quote:Number(t.quote),epoch:t.epoch,previous:old?.quote});renderPrices();if(t.subscription?.id)subscriptions.set(t.symbol,t.subscription.id)}
  };
  socket.onerror=()=>setStatus("red","CONNECTION ISSUE");
  socket.onclose=()=>{subscriptions.clear();setStatus("amber","RECONNECTING");scheduleReconnect()};
}
function scheduleReconnect(){clearTimeout(reconnectTimer);const wait=Math.min(30000,1500*Math.pow(2,Math.min(retry++,4)));reconnectTimer=setTimeout(connect,wait)}
function resolveSymbol(code){
  const exact=catalogue.find(x=>x.symbol===code);if(exact)return exact;
  const aliases={"CRASH50":/crash\s*50/i,"frxXAUUSD":/gold.*usd|xau.*usd/i};
  const re=aliases[code];return re?catalogue.find(x=>re.test(x.display_name||"")):null;
}
function subscribeSelected(){
  if(!socket||socket.readyState!==WebSocket.OPEN)return;
  const resolved=[];
  selected.forEach(code=>{const item=resolveSymbol(code)||catalogue.find(x=>x.symbol===code);if(item&&!resolved.some(x=>x.symbol===item.symbol))resolved.push(item)});
  const active=new Set(resolved.map(x=>x.symbol));
  for(const [symbol,id] of subscriptions){if(!active.has(symbol)){socket.send(JSON.stringify({forget:id}));subscriptions.delete(symbol)}}
  resolved.forEach(item=>{if(!subscriptions.has(item.symbol)){socket.send(JSON.stringify({ticks:item.symbol,subscribe:1}));subscriptions.set(item.symbol,"pending")}});
  renderPrices();setStatus("green","LIVE · "+resolved.length+"/"+selected.length);
}
function marketFor(code){return resolveSymbol(code)||catalogue.find(x=>x.symbol===code)}
function renderPrices(){
  const list=$("priceList");list.innerHTML="";
  selected.forEach(code=>{
    const item=marketFor(code),symbol=item?.symbol||code,name=item?.display_name||code.replace("frx","").replace("CRASH","Crash ");
    const p=lastPrices.get(symbol);
    const row=document.createElement("div");row.className="price-row";
    const label=document.createElement("div");label.className="market-name";label.textContent=shortName(name);label.title=name;
    const price=document.createElement("div");price.className="price "+(!p?"wait":p.previous===undefined?"":p.quote>p.previous?"up":p.quote<p.previous?"down":"");
    price.textContent=p?formatQuote(p.quote):"Waiting…";
    row.append(label,price);list.append(row);
  });
  $("marketCount").textContent=selected.length+" MARKETS";
}
function formatQuote(n){return n.toLocaleString("en-US",{minimumFractionDigits:2,maximumFractionDigits:8})}
function shortName(s){return s.replace(" Index","").replace("Derived ","").replace("Volatility ","Vol. ")}
function renderSearch(){
  const q=$("marketSearch").value.trim().toLowerCase(),box=$("marketResults");box.innerHTML="";
  const results=catalogue.filter(x=>!q||(x.display_name+" "+x.symbol).toLowerCase().includes(q)).slice(0,45);
  results.forEach(item=>{const b=document.createElement("button");b.className="market-option";const left=document.createElement("span");left.textContent=item.display_name;const right=document.createElement("small");right.textContent=selected.includes(item.symbol)?"ADDED":item.symbol;b.append(left,right);b.onclick=()=>{if(!selected.includes(item.symbol)){selected.push(item.symbol);saveSelected();subscribeSelected();renderPrices();renderSearch()}};box.append(b)});
}
$("settingsBtn").onclick=()=>{$("settings").classList.toggle("hidden");renderSearch()};
$("settingsClose").onclick=()=>$("settings").classList.add("hidden");
$("minBtn").onclick=()=>window.zichanWindow.minimize();
$("closeBtn").onclick=()=>window.zichanWindow.close();
$("marketSearch").addEventListener("input",renderSearch);
$("opacity").oninput=e=>{$("opacityValue").textContent=e.target.value+"%";window.zichanWindow.setOpacity(Number(e.target.value)/100)};
$("scale").oninput=e=>{scale=Number(e.target.value);$("scaleValue").textContent=scale+"%";document.documentElement.style.setProperty("--price-scale",scale/100);document.querySelectorAll(".price").forEach(x=>x.style.fontSize=(15*scale/100)+"px")};
$("alwaysTop").onchange=e=>window.zichanWindow.setAlwaysOnTop(e.target.checked);
$("resetBtn").onclick=()=>{selected=DEFAULTS.slice();saveSelected();subscribeSelected();renderPrices();renderSearch()};
$("clock").textContent="--:--:--";setInterval(()=>$("clock").textContent=new Date().toLocaleTimeString("en-GB"),1000);
renderPrices();connect();
