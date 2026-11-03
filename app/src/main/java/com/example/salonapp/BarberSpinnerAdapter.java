package com.example.salonapp;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.bumptech.glide.Glide;
import java.util.List;

/** Shows each barber's Cloudinary portrait in the booking selector. */
public class BarberSpinnerAdapter extends ArrayAdapter<Item> {
    private final LayoutInflater inflater;

    public BarberSpinnerAdapter(@NonNull Context context, @NonNull List<Item> barbers) {
        super(context, R.layout.barber_spinner_item, barbers);
        inflater = LayoutInflater.from(context);
    }

    @NonNull @Override public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        return bind(position, convertView, parent);
    }

    @Override public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        return bind(position, convertView, parent);
    }

    private View bind(int position, View view, ViewGroup parent) {
        if (view == null) view = inflater.inflate(R.layout.barber_spinner_item, parent, false);
        Item barber = getItem(position);
        TextView name = view.findViewById(R.id.tvBarber);
        ImageView image = view.findViewById(R.id.ivBarber);
        name.setText(barber == null || barber.name == null ? "" : barber.name);
        if (barber != null && barber.imageUrl != null && barber.imageUrl.startsWith("https://res.cloudinary.com/")) {
            Glide.with(image).load(barber.imageUrl).centerCrop().into(image);
        } else {
            Glide.with(image).clear(image);
            image.setImageResource(android.R.drawable.ic_menu_myplaces);
        }
        return view;
    }
}
