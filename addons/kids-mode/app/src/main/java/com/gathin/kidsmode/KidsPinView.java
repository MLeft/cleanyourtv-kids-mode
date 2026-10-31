package com.gathin.kidsmode;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A remote-friendly 4-digit PIN pad, used by the settings gate and the full-screen lock. */
public final class KidsPinView extends LinearLayout {
    public interface OnComplete {
        void onComplete(String pin);
    }

    private final TextView dots;
    private final TextView error;
    private final StringBuilder pin = new StringBuilder();
    private OnComplete listener;

    public KidsPinView(Context context) {
        this(context, null);
    }

    public KidsPinView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setFocusable(true);
        setFocusableInTouchMode(true);
        LayoutInflater.from(context).inflate(R.layout.view_kids_pin, this, true);

        TextView title = findViewById(R.id.kidsPinTitle);
        title.setText(R.string.kids_pin_title);
        dots = findViewById(R.id.kidsPinDots);
        error = findViewById(R.id.kidsPinError);

        int[] ids = {R.id.kidsKey1, R.id.kidsKey2, R.id.kidsKey3, R.id.kidsKey4,
                R.id.kidsKey5, R.id.kidsKey6, R.id.kidsKey7, R.id.kidsKey8, R.id.kidsKey9};
        for (int i = 0; i < ids.length; i++) {
            final String digit = String.valueOf(i + 1);
            findViewById(ids[i]).setOnClickListener(v -> append(digit));
        }
        findViewById(R.id.kidsKey0).setOnClickListener(v -> append("0"));
        findViewById(R.id.kidsKeyDel).setOnClickListener(v -> backspace());
        findViewById(R.id.kidsKeyOk).setOnClickListener(v -> submit());
        updateDots();
    }

    public void setTitle(CharSequence text) {
        ((TextView) findViewById(R.id.kidsPinTitle)).setText(text);
    }

    public void setOnComplete(OnComplete callback) {
        listener = callback;
    }

    public void clear() {
        pin.setLength(0);
        error.setVisibility(View.GONE);
        updateDots();
    }

    public void showError(CharSequence text) {
        error.setText(text);
        error.setVisibility(View.VISIBLE);
    }

    public void focusFirst() {
        View first = findViewById(R.id.kidsKey1);
        if (first != null) first.requestFocus();
    }

    private void append(String digit) {
        if (pin.length() >= 4) return;
        pin.append(digit);
        error.setVisibility(View.GONE);
        updateDots();
        if (pin.length() == 4) postDelayed(this::submit, 120);
    }

    private void backspace() {
        if (pin.length() == 0) return;
        pin.setLength(pin.length() - 1);
        updateDots();
    }

    private void submit() {
        if (pin.length() == 4 && listener != null) listener.onComplete(pin.toString());
    }

    private void updateDots() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) text.append(' ');
            text.append(i < pin.length() ? '\u25CF' : '\u25CB');
        }
        dots.setText(text);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int key = event.getKeyCode();
            if (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9) {
                append(String.valueOf(key - KeyEvent.KEYCODE_0));
                return true;
            }
            if (key >= KeyEvent.KEYCODE_NUMPAD_0 && key <= KeyEvent.KEYCODE_NUMPAD_9) {
                append(String.valueOf(key - KeyEvent.KEYCODE_NUMPAD_0));
                return true;
            }
            if (key == KeyEvent.KEYCODE_DEL) {
                backspace();
                return true;
            }
            if (key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                submit();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        focusFirst();
    }
}
