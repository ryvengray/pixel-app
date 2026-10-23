package dev.pixel.sync;

import android.app.Dialog;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;

/** Keeps a bounded form viewport above the IME, including on edge-to-edge windows. */
final class FormKeyboard {
    private FormKeyboard() {}

    static void showInput(AlertDialog.Builder builder, EditText input) {
        input.setMaxLines(6);
        ScrollView scroll=new ScrollView(input.getContext());
        scroll.addView(input);
        AlertDialog dialog=builder.setView(scroll).create();
        dialog.show();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        keepFocusVisible(scroll);
    }

    static void showSheet(Dialog dialog, View form) {
        FrameLayout viewport=new FrameLayout(dialog.getContext());
        viewport.setOnClickListener(v->dialog.dismiss());
        ScrollView scroll=new ScrollView(dialog.getContext());
        scroll.setClickable(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(form);
        viewport.addView(scroll,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        dialog.setContentView(viewport);
        Window window=dialog.getWindow();
        if(window!=null){
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setDecorFitsSystemWindows(false);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(.3f);
            // Insets own the available space; do not also pan or resize the window.
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }
        applyInsets(viewport,scroll);
        dialog.show();
        if(window!=null)window.setLayout(-1,-1);
        viewport.requestApplyInsets();
    }

    static void applyInsets(View viewport, ScrollView scroll) {
        int left=viewport.getPaddingLeft(),top=viewport.getPaddingTop();
        int right=viewport.getPaddingRight(),bottom=viewport.getPaddingBottom();
        viewport.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            int ime=insets.getInsets(WindowInsets.Type.ime()).bottom;
            v.setPadding(left+safe.left,top+safe.top,right+safe.right,bottom+Math.max(safe.bottom,ime));
            scroll.post(()->revealCursor(scroll));
            return insets;
        });
        keepFocusVisible(scroll);
        viewport.requestApplyInsets();
    }

    static void keepFocusVisible(ScrollView scroll) {
        // Re-run after the reduced viewport has actually been measured.
        scroll.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            if(b-t!=ob-ot)scroll.post(()->revealCursor(scroll));
        });
    }

    private static void revealCursor(ScrollView scroll) {
        View focus=scroll.findFocus();
        if(!(focus instanceof EditText))return;
        EditText input=(EditText)focus;
        Rect rect=new Rect();
        input.getFocusedRect(rect);
        int margin=Math.round(12*input.getResources().getDisplayMetrics().density);
        rect.inset(0,-margin);
        input.requestRectangleOnScreen(rect,true);
    }
}
