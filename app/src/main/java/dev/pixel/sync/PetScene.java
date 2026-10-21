package dev.pixel.sync;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import android.util.Log;
import android.animation.ValueAnimator;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import android.opengl.GLUtils;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** A procedural 3D volume with animated features in body coordinates. */
public final class PetScene extends GLSurfaceView implements GLSurfaceView.Renderer {
 private static final String BACKDROP_FALLBACK="precision mediump float;uniform vec2 resolution,backdropSize;uniform sampler2D backdrop;void main(){vec2 uv=gl_FragCoord.xy/resolution;float sa=resolution.x/resolution.y,ia=backdropSize.x/backdropSize.y;if(sa>ia)uv.y=(uv.y-.5)*ia/sa+.5;else uv.x=(uv.x-.5)*sa/ia+.5;uv.y=1.-uv.y;gl_FragColor=texture2D(backdrop,uv);}";
 private final String fragment;
 private final Context context;
 private final FloatBuffer vertices=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
 private int program,position,resolution,timeUniform,darkUniform,reactionUniform,motionUniform,compactUniform,compactCenterUniform,thinkingUniform,backdropUniform,backdropSizeUniform,backdropTexture,width,height;
 private volatile int backdropResource=R.drawable.home_mist_mountains;
 private int loadedBackdropResource;
 private final long start=SystemClock.uptimeMillis();
 private volatile long tapped=-10000;
 private volatile boolean dark,moving=true,thinking;
 private float thinkingBlend;
 private long lastFrame;
 private volatile float compact;
 private volatile float compactCenterX=.90f,compactCenterY=.08f;
 private ValueAnimator compactAnimator;
 private boolean active;
 private final Runnable frame=new Runnable(){public void run(){if(!active)return;requestRender();if(moving)postDelayed(this,33);}};
 public PetScene(Context context){
  super(context);
  this.context=context;
  try(java.io.InputStream in=context.getAssets().open("pet.frag")){
   java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[4096];int n;
   while((n=in.read(b))!=-1)out.write(b,0,n);fragment=new String(out.toByteArray(),StandardCharsets.UTF_8);
  }catch(java.io.IOException e){throw new IllegalStateException(e);}
  vertices.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);
  setEGLContextClientVersion(2);setEGLConfigChooser(8,8,8,0,0,0);setPreserveEGLContextOnPause(true);
  setRenderer(this);setRenderMode(RENDERMODE_WHEN_DIRTY);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
 }
 public void setTheme(boolean value){dark=value;int resource=value?R.drawable.home_starfield:R.drawable.home_mist_mountains;if(backdropResource!=resource){backdropResource=resource;queueEvent(this::loadBackdrop);}requestRender();}
 public void setMoving(boolean value){moving=value;if(active){removeCallbacks(frame);post(frame);}}
 public boolean isMoving(){return moving;}
 public void setThinking(boolean value){thinking=value;requestRender();}
 public boolean isThinking(){return thinking;}
 public void react(){tapped=SystemClock.uptimeMillis();requestRender();}
 public void setCompactTarget(float xFraction,float yFraction){compactCenterX=xFraction;compactCenterY=yFraction;requestRender();}
 public void setCompact(boolean value,boolean animate){
  float target=value?1f:0f;
  if(compactAnimator!=null)compactAnimator.cancel();
  if(!animate){compact=target;requestRender();return;}
  compactAnimator=ValueAnimator.ofFloat(compact,target);compactAnimator.setDuration(380);
  compactAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
  compactAnimator.addUpdateListener(a->{compact=(float)a.getAnimatedValue();requestRender();});compactAnimator.start();
 }
 public void resumeScene(){onResume();active=true;removeCallbacks(frame);post(frame);}
 public void pauseScene(){active=false;removeCallbacks(frame);onPause();}
 @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){
  super.onSizeChanged(w,h,oldW,oldH);
  // Render the soft scene at a bounded resolution; native overlay text stays
  // full resolution on Pixel's high-density display.
  if(w>0&&h>0){int renderWidth=Math.min(w,720);getHolder().setFixedSize(renderWidth,Math.round((float)h*renderWidth/w));}
 }
 private int compile(int type,String source){
  int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,source);GLES20.glCompileShader(s);
  int[] ok=new int[1];GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);
  if(ok[0]==0){Log.e("GrayScene",GLES20.glGetShaderInfoLog(s));GLES20.glDeleteShader(s);return 0;}return s;
 }
 public void onSurfaceCreated(GL10 gl,EGLConfig config){
  backdropTexture=0;loadedBackdropResource=0;loadBackdrop();
  int v=compile(GLES20.GL_VERTEX_SHADER,"attribute vec2 position;void main(){gl_Position=vec4(position,0.,1.);}");
  int f=compile(GLES20.GL_FRAGMENT_SHADER,fragment);if(f==0){Log.e("GrayScene","Using background-only shader after scene shader compile failure");f=compile(GLES20.GL_FRAGMENT_SHADER,BACKDROP_FALLBACK);}if(f==0){program=0;return;}program=GLES20.glCreateProgram();
  GLES20.glAttachShader(program,v);GLES20.glAttachShader(program,f);GLES20.glLinkProgram(program);
  GLES20.glDeleteShader(v);GLES20.glDeleteShader(f);int[] ok=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0);
  if(ok[0]==0){Log.e("GrayScene",GLES20.glGetProgramInfoLog(program));program=0;return;}
  position=GLES20.glGetAttribLocation(program,"position");resolution=GLES20.glGetUniformLocation(program,"resolution");
  timeUniform=GLES20.glGetUniformLocation(program,"time");darkUniform=GLES20.glGetUniformLocation(program,"dark");
  reactionUniform=GLES20.glGetUniformLocation(program,"reaction");motionUniform=GLES20.glGetUniformLocation(program,"motion");
  compactUniform=GLES20.glGetUniformLocation(program,"compact");compactCenterUniform=GLES20.glGetUniformLocation(program,"compactCenter");thinkingUniform=GLES20.glGetUniformLocation(program,"thinking");
  backdropUniform=GLES20.glGetUniformLocation(program,"backdrop");backdropSizeUniform=GLES20.glGetUniformLocation(program,"backdropSize");
 }
 private int backdropWidth=1,backdropHeight=1;
 private void loadBackdrop(){
  int resource=backdropResource;if(backdropTexture!=0&&loadedBackdropResource==resource)return;Bitmap bitmap=BitmapFactory.decodeResource(context.getResources(),resource);if(bitmap==null){Log.e("GrayScene","Unable to decode background resource "+resource);return;}
  if(backdropTexture==0){int[] ids=new int[1];GLES20.glGenTextures(1,ids,0);backdropTexture=ids[0];}
  while(GLES20.glGetError()!=GLES20.GL_NO_ERROR){}GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,backdropTexture);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0);backdropWidth=bitmap.getWidth();backdropHeight=bitmap.getHeight();bitmap.recycle();GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,0);int error=GLES20.glGetError();if(error!=GLES20.GL_NO_ERROR){Log.e("GrayScene","Background texture upload failed: 0x"+Integer.toHexString(error));int[] ids={backdropTexture};GLES20.glDeleteTextures(1,ids,0);backdropTexture=0;}else loadedBackdropResource=resource;
 }
 public void onSurfaceChanged(GL10 gl,int w,int h){width=w;height=h;GLES20.glViewport(0,0,w,h);}
 public void onDrawFrame(GL10 gl){
  if(program==0||backdropTexture==0){if(dark)GLES20.glClearColor(.025f,.045f,.08f,1);else GLES20.glClearColor(.91f,.94f,.88f,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);return;}
  GLES20.glClearColor(.1f,.2f,.14f,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
  long now=SystemClock.uptimeMillis();float elapsed=(now-tapped)/1000f;
  float dt=lastFrame==0?1f:Math.min(1f,(now-lastFrame)/220f);lastFrame=now;
  thinkingBlend+=( (thinking?1f:0f)-thinkingBlend)*(moving?dt:1f);
  float bounce=elapsed<1.8f&&moving?(float)(Math.pow(Math.sin(elapsed*Math.PI/.6),2)*Math.exp(-elapsed*2)):0;
  GLES20.glUseProgram(program);GLES20.glUniform2f(resolution,width,height);GLES20.glUniform1f(timeUniform,(now-start)/1000f);
  GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,backdropTexture);GLES20.glUniform1i(backdropUniform,0);GLES20.glUniform2f(backdropSizeUniform,backdropWidth,backdropHeight);
  GLES20.glUniform1f(darkUniform,dark?1:0);GLES20.glUniform1f(reactionUniform,bounce);GLES20.glUniform1f(motionUniform,moving?1:0);
  GLES20.glUniform1f(thinkingUniform,thinkingBlend);
  GLES20.glUniform1f(compactUniform,compact);GLES20.glUniform2f(compactCenterUniform,compactCenterX,compactCenterY);
  vertices.position(0);GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,vertices);
  GLES20.glEnableVertexAttribArray(position);GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
 }
}
