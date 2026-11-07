package com.gathin.kidsmode;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/**
 * The launcher asks this exported activity before opening its app library. With kids mode off, a
 * grace window still running or the no-control window active it returns RESULT_OK immediately;
 * otherwise it shows the parent PIN.
 */
public final class KidsLibraryAuthActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!KidsStore.isEnabled(this) || KidsStore.isLibraryGraceActive(this)
                || KidsStore.isUnmanagedNow(this, System.currentTimeMillis())) {
            setResult(RESULT_OK);
            finish();
            return;
        }
        setContentView(R.layout.activity_kids_auth);
        int grace = KidsStore.libraryGraceMin(this);
        TextView hint = findViewById(R.id.kidsAuthHint);
        hint.setText(grace > 0 ? getString(R.string.kids_auth_hint, grace)
                : getString(R.string.kids_auth_hint_always));
        KidsPinView pin = findViewById(R.id.kidsAuthPin);
        pin.setTitle(getString(R.string.kids_pin_title));
        pin.setOnComplete(value -> {
            int[] wait = new int[1];
            int result = KidsStore.checkPin(this, value, wait);
            if (result == KidsStore.PIN_OK) {
                KidsStore.grantLibraryGrace(this);
                setResult(RESULT_OK);
                finish();
            } else if (result == KidsStore.PIN_LOCKED) {
                pin.clear();
                pin.showError(getString(R.string.kids_pin_locked, wait[0]));
                pin.focusFirst();
            } else {
                pin.clear();
                pin.showError(getString(R.string.kids_error_wrong));
                pin.focusFirst();
            }
        });
    }

    @Override
    public void onBackPressed() {
        setResult(RESULT_CANCELED);
        super.onBackPressed();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }
}
