package dev.pixel.sync;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.WeakHashMap;

/** Changes families without replacing each view's size, weight or italic style. */
final class AppTypography {
    private static final WeakHashMap<TextView,Face> faces=new WeakHashMap<>();
    private static final class Face {
        Typeface original,applied;
        int choice=-1;
        Face(Typeface original){this.original=original;}
    }
    static int choice(Context context){return context.getSharedPreferences("home",0).getInt("font",0)==1?1:0;}
    static void apply(View root){apply(root,choice(root.getContext()));}
    private static void apply(View view,int choice){
        if(view instanceof TextView&&!"font_preview".equals(view.getTag())){
            TextView text=(TextView)view;
            Face face=faces.get(text);
            if(face==null){face=new Face(text.getTypeface());faces.put(text,face);}
            else if(face.applied!=text.getTypeface()){face.original=text.getTypeface();face.choice=-1;}
            if(face.choice!=choice){
                Typeface original=face.original==null?Typeface.DEFAULT:face.original;
                Typeface selected=choice==0?original:Typeface.create(Typeface.create("serif",Typeface.NORMAL),original.getWeight(),original.isItalic());
                if(text.getTypeface()!=selected)text.setTypeface(selected);
                face.applied=text.getTypeface();face.choice=choice;
            }
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)apply(group.getChildAt(i),choice);}
    }
    private AppTypography(){}
}
