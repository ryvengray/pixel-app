// scrcpy H.264 over WebRTC; input uses the persistent RTC data channel.
const phoneScreen=(()=>{
 const video=$('phone-video'),toggle=$('touch-enabled'),message=$('screen-message');
 let enabled=false,paused=false,pc=null,channel=null,session=null,generation=0,starting=false;
 let format=null,gesture=null,animating=false,seq=0,lastMove=0,lastPong=0,rtt=null,fps=0,lastStats=null;
 function info(text,error=false){message.textContent=text;message.classList.toggle('error',error);}
 function available(){return enabled&&!paused&&!document.hidden&&pc?.connectionState==='connected'&&channel?.readyState==='open'&&format&&video.readyState>=2&&video.videoWidth===format.width&&video.videoHeight===format.height&&performance.now()-lastPong<5000;}
 function buttons(){const ready=available();$('swipe-up').disabled=!ready||!toggle.checked||!!gesture||animating;$('swipe-down').disabled=!ready||!toggle.checked||!!gesture||animating;video.dataset.control=ready&&toggle.checked?'true':'false';$('screen-retry').hidden=true;}
 function point(clientX,clientY){const rect=video.getBoundingClientRect();return {x:Math.max(0,Math.min(video.videoWidth-1,Math.floor((clientX-rect.left)/rect.width*video.videoWidth))),y:Math.max(0,Math.min(video.videoHeight-1,Math.floor((clientY-rect.top)/rect.height*video.videoHeight))),width:video.videoWidth,height:video.videoHeight,epoch:format.epoch};}
 function transmit(action,p){
  if(channel?.readyState!=='open')return false;
  if(channel.bufferedAmount>65536){info('网络积压，实时连接已暂停，请重新连接。',true);stop();return false;}
  if(action==='move'&&channel.bufferedAmount>8192)return false;
  channel.send(JSON.stringify({type:'touch',action,seq:++seq,x:p.x,y:p.y,width:p.width,height:p.height,epoch:p.epoch}));return true;
 }
 function release(){const held=gesture;gesture=null;if(held)transmit('cancel',held);animating=false;buttons();}
 function closeRemote(id){if(id)fetch(api+'/realtime/close/'+encodeURIComponent(id),{method:'POST',credentials:'same-origin',keepalive:true}).catch(()=>{});}
 function stop(){generation++;release();const old=session;session=null;channel=null;format=null;lastPong=0;lastStats=null;starting=false;const oldPC=pc;pc=null;if(oldPC)oldPC.close();video.srcObject=null;video.hidden=true;$('screen-empty').hidden=false;closeRemote(old);buttons();}
 function ping(){if(channel?.readyState==='open')channel.send(JSON.stringify({type:'ping',t:performance.now()}));}
 function receive(event){let data;try{data=JSON.parse(event.data);}catch{return;}
  if(data.type==='format'){
   if(format&&(format.width!==data.width||format.height!==data.height||format.epoch!==data.epoch))release();
   format={width:data.width,height:data.height,epoch:data.epoch};
  }else if(data.type==='pong'){lastPong=performance.now();if(typeof data.t==='number')rtt=Math.max(0,lastPong-data.t);}
  else if(data.type==='error'){release();info(data.message||'触控未完成',true);}
  buttons();
 }
 async function start(){
  if(!enabled||paused||document.hidden||starting||pc)return;
  if(!window.RTCPeerConnection){info('当前浏览器不支持 WebRTC，请使用新版 Chrome、Edge 或 Safari。',true);return;}
  starting=true;const attempt=++generation;info('正在建立实时视频连接…');$('adb-state').textContent='实时连接中';$('screen-pause').textContent='暂停预览';
  const connection=new RTCPeerConnection({iceServers:[]});pc=connection;
  const transceiver=connection.addTransceiver('video',{direction:'recvonly'});
  const codecs=window.RTCRtpReceiver?.getCapabilities('video')?.codecs?.filter(c=>c.mimeType.toLowerCase()==='video/h264');
  if(codecs?.length&&transceiver.setCodecPreferences)transceiver.setCodecPreferences(codecs);
  channel=connection.createDataChannel('control',{ordered:true});channel.onmessage=receive;channel.onopen=()=>{lastPong=performance.now();ping();buttons();};
  connection.ontrack=event=>{if(attempt!==generation)return;video.srcObject=new MediaStream([event.track]);video.hidden=false;video.muted=true;video.play().catch(error=>{if(attempt!==generation||error.name==='AbortError')return;if(error.name==='NotAllowedError'){$('screen-pause').textContent='继续播放';info('点击继续播放以显示实时视频。');}else info('视频播放未完成，请重新连接。',true);});};
  connection.onconnectionstatechange=()=>{
   if(attempt!==generation)return;
   if(connection.connectionState==='connected'){$('adb-state').textContent='WebRTC 已连接';info(toggle.checked?'实时触控已就绪，按住拖动即可滑动。':'视频已连接，勾选「允许点击与滑动」后操作手机。');}
   if(['failed','disconnected','closed'].includes(connection.connectionState)){stop();$('screen-pause').textContent='重新连接';$('adb-state').textContent='实时连接已断开';info('实时连接已结束，点击重新连接。',true);}
   buttons();
  };
  try{
   await connection.setLocalDescription(await connection.createOffer());
   if(connection.iceGatheringState!=='complete')await new Promise(resolve=>{const timer=setTimeout(resolve,3000);connection.addEventListener('icegatheringstatechange',()=>{if(connection.iceGatheringState==='complete'){clearTimeout(timer);resolve();}});});
   if(attempt!==generation)return;
   const answer=await request(api+'/realtime/offer',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({sdp:connection.localDescription.sdp,type:'offer'})});
   if(attempt!==generation){closeRemote(answer.id);return;}
   session=answer.id;await connection.setRemoteDescription({sdp:answer.sdp,type:answer.type});
  }catch(error){if(attempt===generation){stop();$('screen-pause').textContent='重新连接';$('adb-state').textContent='实时连接失败';info(error.message,true);}}
  finally{if(attempt===generation)starting=false;}
 }
 video.addEventListener('loadeddata',()=>{$('screen-empty').hidden=true;buttons();});
 video.addEventListener('resize',()=>{release();buttons();});
 video.addEventListener('pointerdown',event=>{if(!available()||!toggle.checked||gesture||animating||event.button!==0)return;event.preventDefault();gesture={...point(event.clientX,event.clientY),pointer:event.pointerId};video.setPointerCapture(event.pointerId);transmit('down',gesture);lastMove=0;buttons();});
 video.addEventListener('pointermove',event=>{if(!gesture||gesture.pointer!==event.pointerId)return;event.preventDefault();if(performance.now()-lastMove<30)return;lastMove=performance.now();const next=point(event.clientX,event.clientY);gesture={...gesture,...next};transmit('move',gesture);});
 video.addEventListener('pointerup',event=>{if(!gesture||gesture.pointer!==event.pointerId)return;event.preventDefault();const last={...gesture,...point(event.clientX,event.clientY)};transmit('up',last);gesture=null;video.releasePointerCapture(event.pointerId);buttons();});
 video.addEventListener('pointercancel',release);video.addEventListener('dragstart',e=>e.preventDefault());window.addEventListener('blur',release);
 toggle.addEventListener('change',()=>{release();if(available())info(toggle.checked?'实时触控已就绪，按住拖动即可滑动。':'实时视频已连接；触控已关闭。');buttons();});
 async function swipe(up){
  if(!available()||!toggle.checked||gesture||animating)return;animating=true;buttons();
  const p={x:Math.floor(video.videoWidth/2),y:Math.floor(video.videoHeight*(up ? .72 : .28)),width:video.videoWidth,height:video.videoHeight,epoch:format.epoch};
  const end=Math.floor(p.height*(up ? .28 : .72)),startY=p.y;gesture={...p,pointer:-1};transmit('down',p);
  try{for(let i=1;i<=12&&animating&&gesture;i++){await new Promise(resolve=>setTimeout(resolve,30));if(!gesture||!animating)break;p.y=Math.round(startY+(end-startY)*i/12);gesture={...gesture,...p};transmit('move',p);}}
  finally{if(gesture?.pointer===-1){transmit('up',p);gesture=null;}animating=false;buttons();}
 }
 $('swipe-up').addEventListener('click',()=>swipe(true));$('swipe-down').addEventListener('click',()=>swipe(false));
 $('screen-pause').addEventListener('click',()=>{if(pc?.connectionState==='connected'&&video.paused&&video.readyState>=2){video.play().then(()=>{$('screen-pause').textContent='暂停预览';info('实时视频已连接。');}).catch(()=>info('浏览器暂时无法播放，请重新连接。',true));return;}if(pc||starting){paused=true;stop();$('screen-pause').textContent='继续预览';info('实时预览已暂停。');}else{paused=false;start();}});
 document.addEventListener('visibilitychange',()=>{if(document.hidden)stop();else if(enabled&&!paused)start();});
 window.addEventListener('pagehide',stop);
 async function statistics(){
  ping();buttons();if(!pc||pc.connectionState!=='connected')return;
  try{const stats=await pc.getStats();for(const report of stats.values())if(report.type==='inbound-rtp'&&report.kind==='video'){
   if(lastStats&&report.timestamp>lastStats.time)fps=Math.max(0,(report.framesDecoded-lastStats.frames)*1000/(report.timestamp-lastStats.time));
   lastStats={frames:report.framesDecoded,time:report.timestamp};
  }}catch{}
  $('screen-size').textContent=video.videoWidth+' × '+video.videoHeight+' · '+Math.round(fps)+' FPS'+(rtt===null?'':' · 控制 RTT '+Math.round(rtt)+' ms');
 }
 setInterval(statistics,1000);
 return {auth(ok){enabled=ok;if(ok)start();else{stop();info('登录后显示手机实时画面。');}},update(){buttons();}};
})();
