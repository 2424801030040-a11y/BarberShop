package com.example.salonapp;

import android.content.Context;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButtonToggleGroup;

/** Shared, persisted list/grid selection for collection and history screens. */
public final class ViewModeToggle {
    private static final String PREFS = "display_preferences";
    private static final String KEY_GRID_MODE = "grid_mode";

    private ViewModeToggle() {}

    public static void bind(Context context, RecyclerView recyclerView, MaterialButtonToggleGroup toggle) {
        boolean savedGridMode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_GRID_MODE, false);
        toggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            boolean gridMode = checkedId == R.id.btnViewGrid;
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_GRID_MODE, gridMode).apply();
            apply(recyclerView, gridMode);
        });
        int savedButton = savedGridMode ? R.id.btnViewGrid : R.id.btnViewList;
        toggle.check(savedButton);
        apply(recyclerView, savedGridMode);
    }

    private static void apply(RecyclerView recyclerView, boolean gridMode) {
        if (gridMode) {
            recyclerView.setLayoutManager(new GridLayoutManager(recyclerView.getContext(), 2));
        } else {
            recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
        }
    }
}
