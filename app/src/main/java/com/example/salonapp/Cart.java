package com.example.salonapp;
import java.util.*;
public class Cart {
    public static final List<Item> items = new ArrayList<>();
    /** @return false when the stock limit is reached */
    public static boolean add(Item i) {
        for (Item x : items) if (x.id.equals(i.id)) { if (x.stock != null && x.qty >= x.stock) return false; x.qty++; return true; }
        if (i.stock != null && i.stock <= 0) return false;
        i.qty = 1; items.add(i); return true;
    }
    public static double subtotal() { double s = 0; for (Item i : items) s += i.price * i.qty; return s; }
    public static int itemCount() { int count = 0; for (Item item : items) count += Math.max(0, item.qty); return count; }
}
