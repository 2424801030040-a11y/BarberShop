package com.example.salonapp;
import java.util.Locale;
/** Vietnamese display helpers. Firestore keeps English status codes (rules depend on them); only the display is translated. */
public class Vi {
    public static final Locale VN = new Locale("vi", "VN");
    public static String t(String k) {
        if (k == null) return "";
        switch (k) {
            case "pending": return "Chờ xác nhận";
            case "confirmed": return "Đã xác nhận";
            case "in_service": return "Đang cắt tóc";
            case "completed": return "Hoàn thành";
            case "canceled": return "Đã hủy";
            case "shipping": return "Đang giao hàng";
            case "delivered": return "Đã giao";
            case "unpaid": return "Chưa thanh toán";
            case "paid": return "Đã thanh toán";
            case "cash": case "COD": return "Tiền mặt";
            case "bank": return "Chuyển khoản";
            default: return k;
        }
    }
}
