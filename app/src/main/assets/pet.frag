precision highp float;
uniform vec2 resolution;
uniform float time, dark, reaction, motion;
mat2 rot(float a){float c=cos(a),s=sin(a);return mat2(c,-s,s,c);}
float triangle(vec2 p){
 float r=.64,k=1.7320508;
 p.x=abs(p.x)-r;p.y+=r/k;
 if(p.x+k*p.y>0.)p=vec2(p.x-k*p.y,-k*p.x-p.y)*.5;
 p.x-=clamp(p.x,-2.*r,0.);return -length(p)*sign(p.y);
}
vec3 local(vec3 p){
 float t=time*motion;
 p.y-=.06*sin(t*1.5)+reaction*.35;
 p.xy=rot(.055*sin(t*.8)+reaction*.13)*p.xy;
 p.xz=rot(.11*sin(t*.6)+.12+reaction*.16*p.y)*p.xz;
 float stretch=1.+.025*sin(t*1.5)+reaction*.06;p.y/=stretch;p.x*=stretch;return p;
}
float shape(vec3 p){
 p=local(p);p.z*=1.65;
 float r=max(length(p),.0001);
 // Smooth threefold spherical harmonic: rounded triangular volume without
 // the visible medial-axis creases of an extruded triangle distance field.
 float triangular=p.y*(p.y*p.y-3.*p.x*p.x)/(r*r*r);
 return (r-(.88+.12*triangular))*.65;
}
vec3 normal(vec3 p){vec2 e=vec2(.002,0.);return normalize(vec3(shape(p+e.xyy)-shape(p-e.xyy),shape(p+e.yxy)-shape(p-e.yxy),shape(p+e.yyx)-shape(p-e.yyx)));}
void main(){
 vec2 uv=(2.*gl_FragCoord.xy-resolution)/resolution.x;float t=time*motion;
 vec3 col=mix(vec3(.94,.96,.91),vec3(.034,.065,.057),dark);
 vec2 g=uv-vec2(.45*sin(t*.11),.5*cos(t*.09));
 col+=mix(vec3(-.04,.018,-.03),vec3(.035,.07,.045),dark)*exp(-1.4*dot(g,g));
 col+=mix(vec3(.014,.012,.006),vec3(.009,.014,.01),dark)*(.5+.5*sin(uv.x*2.+uv.y*1.4+t*.13));
 vec3 ro=vec3(uv*1.75,4.);ro.y-=.32;vec3 rd=vec3(0.,0.,-1.);
 float shadow=exp(-pow(ro.x/.78,2.)-pow((ro.y+.93)/.14,2.));
 col*=1.-shadow*mix(.15,.28,dark)*(1.-reaction*.35);
 if(abs(ro.x)<1.3&&ro.y>-1.2&&ro.y<1.65){
  float distance=0.,hit=0.;vec3 p=ro;
  for(int i=0;i<56;i++){p=ro+rd*distance;float d=shape(p);if(d<.0015){hit=1.;break;}distance+=d*.85;if(distance>6.)break;}
  if(hit>.5){
   vec3 n=normal(p),l=normalize(vec3(-.7,1.2,1.6)),q=local(p);
   float diffuse=max(dot(n,l),0.),rim=pow(1.-max(n.z,0.),2.),spec=pow(max(dot(reflect(-l,n),-rd),0.),45.);
   col=vec3(.57,.78,.56)*(.45+.53*diffuse)+vec3(.91,.98,.79)*spec*.36+vec3(.22,.38,.23)*rim;
   float blink=motion<.5?1.:1.-.93*pow(max(cos(t*1.27),0.),70.);
   vec2 eye=vec2(abs(q.x)-.22,(q.y-.10)/max(blink,.07));
   float eyes=1.-smoothstep(0.,.008,length(eye/vec2(.061,.083))-1.);
   float smile=abs(q.y-(-.19+1.4*q.x*q.x))-.014;
   float mouth=(1.-smoothstep(0.,.009,smile))*(1.-smoothstep(.13,.15,abs(q.x)));
   float opened=1.-smoothstep(.9,1.06,length(vec2(q.x/.08,(q.y+.18)/.067)));
   mouth=mix(mouth,opened,smoothstep(.25,.65,reaction));float front=smoothstep(.18,.4,q.z);
   col=mix(col,vec3(.055,.16,.08),max(eyes,mouth)*front);
   float shine=(1.-smoothstep(.011,.018,length(vec2(abs(q.x)-.206,q.y-.127))))*eyes*front;
   col=mix(col,vec3(.91,1.,.89),shine*.8);
  }
 }
 gl_FragColor=vec4(col,1.);
}
