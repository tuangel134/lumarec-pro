package com.lumarec.pro;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Arrays;

/** Four identical tap zones, four steps, no visible trace of the secret sequence. */
final class KnockCodeView extends LinearLayout {
    interface Completed { void accept(char[] sequence); }
    private final char[] sequence=new char[4];
    private final Button[] buttons=new Button[4];
    private final TextView steps;
    private final Completed completed;
    private int position;
    private boolean accepting=true;
    private final int accent=Color.rgb(255,55,83), base=Color.rgb(42,29,35);
    KnockCodeView(Context context,Completed completed) {
        super(context); this.completed=completed; setOrientation(VERTICAL); setGravity(Gravity.CENTER);
        setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        steps=new TextView(context); steps.setTextSize(19); steps.setTextColor(accent); steps.setGravity(Gravity.CENTER);
        addView(steps,new LayoutParams(-1,dp(34))); updateSteps();
        for (int row=0;row<2;row++) {
            LinearLayout line=new LinearLayout(context);
            for (int column=0;column<2;column++) {
                final int index=row*2+column; Button zone=new Button(context); buttons[index]=zone;
                zone.setBackground(shape(base)); zone.setText(""); zone.setFilterTouchesWhenObscured(true);
                zone.setContentDescription(new String[]{"Cuadro superior izquierdo","Cuadro superior derecho","Cuadro inferior izquierdo","Cuadro inferior derecho"}[index]);
                LayoutParams size=new LayoutParams(0,dp(100),1); size.setMargins(dp(5),dp(5),dp(5),dp(5)); line.addView(zone,size);
                zone.setOnClickListener(v->tap(index));
            }
            addView(line,new LayoutParams(-1,-2));
        }
        Button reset=new Button(context); reset.setText("Borrar toques"); reset.setAllCaps(false); reset.setTextColor(Color.rgb(171,153,160));
        reset.setBackgroundColor(Color.TRANSPARENT); reset.setFilterTouchesWhenObscured(true);
        addView(reset,new LayoutParams(-1,dp(42))); reset.setOnClickListener(v->{ if (accepting) reset(); });
    }
    private void tap(int index) {
        if (!accepting || position>=4) return;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        sequence[position++]=(char)('0'+index); updateSteps();
        buttons[index].setBackground(shape(accent)); postDelayed(()->buttons[index].setBackground(shape(base)),110);
        if (position==4) { setAccepting(false); char[] result=sequence.clone(); Arrays.fill(sequence,'\0'); completed.accept(result); }
    }
    void reset() { Arrays.fill(sequence,'\0'); position=0; updateSteps(); setAccepting(true); }
    void setAccepting(boolean accepting) { this.accepting=accepting; for (Button button:buttons) if (button!=null) button.setEnabled(accepting); }
    private void updateSteps() {
        StringBuilder value=new StringBuilder(); for (int i=0;i<4;i++) value.append(i<position ? "●" : "○").append(i==3 ? "" : "   "); steps.setText(value);
    }
    private GradientDrawable shape(int color) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(16)); return d; }
    private int dp(int value) { return (int)(value*getResources().getDisplayMetrics().density+0.5f); }
}
