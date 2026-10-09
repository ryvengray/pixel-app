package dev.pixel.sync;
import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import android.util.Log;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** A procedural 3D volume with animated features in body coordinates. */
public final class PetScene extends GLSurfaceView implements GLSurfaceView.Renderer {
 private final String fragment;
 private final FloatBuffer vertices=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
 private int program,position,resolution,timeUniform,darkUniform,reactionUniform,motionUniform,width,height;
 private final long start=SystemClock.uptimeMillis();
 private volatile long tapped=-10000;
 private volatile boolean dark,moving=true;
 private boolean active;
 private final Runnable frame=new Runnable(){public void run(){if(!active)return;requestRender();if(moving)postDelayed(this,33);}};
 public PetScene(Context context){
  super(context);
  try(java.io.InputStream in=context.getAssets().open("pet.frag")){
   java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[4096];int n;
   while((n=in.read(b))!=-1)out.write(b,0,n);fragment=new String(out.toByteArray(),StandardCharsets.UTF_8);
  }catch(java.io.IOException e){throw new IllegalStateException(e);}
  vertices.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);
  setEGLContextClientVersion(2);setEGLConfigChooser(8,8,8,0,0,0);setPreserveEGLContextOnPause(true);
  setRenderer(this);setRenderMode(RENDERMODE_WHEN_DIRTY);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
 }
 public void setTheme(boolean value){dark=value;requestRender();}
 public void setMoving(boolean value){moving=value;if(active){removeCallbacks(frame);post(frame);}}
 public boolean isMoving(){return moving;}
 public void react(){tapped=SystemClock.uptimeMillis();requestRender();}
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
  int v=compile(GLES20.GL_VERTEX_SHADER,"attribute vec2 position;void main(){gl_Position=vec4(position,0.,1.);}");
  int f=compile(GLES20.GL_FRAGMENT_SHADER,fragment);program=GLES20.glCreateProgram();
  GLES20.glAttachShader(program,v);GLES20.glAttachShader(program,f);GLES20.glLinkProgram(program);
  GLES20.glDeleteShader(v);GLES20.glDeleteShader(f);int[] ok=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0);
  if(ok[0]==0){Log.e("GrayScene",GLES20.glGetProgramInfoLog(program));program=0;return;}
  position=GLES20.glGetAttribLocation(program,"position");resolution=GLES20.glGetUniformLocation(program,"resolution");
  timeUniform=GLES20.glGetUniformLocation(program,"time");darkUniform=GLES20.glGetUniformLocation(program,"dark");
  reactionUniform=GLES20.glGetUniformLocation(program,"reaction");motionUniform=GLES20.glGetUniformLocation(program,"motion");
 }
 public void onSurfaceChanged(GL10 gl,int w,int h){width=w;height=h;GLES20.glViewport(0,0,w,h);}
 public void onDrawFrame(GL10 gl){
  if(program==0){GLES20.glClearColor(.1f,.2f,.14f,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);return;}
  long now=SystemClock.uptimeMillis();float elapsed=(now-tapped)/1000f;
  float bounce=elapsed<1.8f&&moving?(float)(Math.pow(Math.sin(elapsed*Math.PI/.6),2)*Math.exp(-elapsed*2)):0;
  GLES20.glUseProgram(program);GLES20.glUniform2f(resolution,width,height);GLES20.glUniform1f(timeUniform,(now-start)/1000f);
  GLES20.glUniform1f(darkUniform,dark?1:0);GLES20.glUniform1f(reactionUniform,bounce);GLES20.glUniform1f(motionUniform,moving?1:0);
  vertices.position(0);GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,vertices);
  GLES20.glEnableVertexAttribArray(position);GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
 }
}
