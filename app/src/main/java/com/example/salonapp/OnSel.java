package com.example.salonapp;
import android.view.View;
import android.widget.AdapterView;
public abstract class OnSel implements AdapterView.OnItemSelectedListener {
    public abstract void on(int pos);
    @Override public void onItemSelected(AdapterView<?> p, View v, int i, long id) { on(i); }
    @Override public void onNothingSelected(AdapterView<?> p) {}
}
