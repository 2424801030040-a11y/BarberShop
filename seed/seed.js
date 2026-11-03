// Nhập dữ liệu mẫu tiếng Việt vào Firestore (chạy lại nhiều lần không bị trùng vì dùng ID cố định).
// Cách chạy:  cd seed && npm install
//   Firebase Console > Project settings > Service accounts > Generate new private key -> lưu thành seed/serviceAccountKey.json
//   GOOGLE_APPLICATION_CREDENTIALS=./serviceAccountKey.json node seed.js      (Windows PowerShell: $env:GOOGLE_APPLICATION_CREDENTIALS=".\serviceAccountKey.json"; node seed.js)
const admin = require("firebase-admin");
admin.initializeApp({ credential: admin.credential.applicationDefault() });
const db = admin.firestore();

const data = {
  services: {
    "dv-cat-toc": { name: "Cắt tóc nam", description: "Tư vấn kiểu tóc, cắt và tạo kiểu · 30 phút", durationMinutes: 30, price: 80000 },
    "dv-cao-mat": { name: "Cạo mặt & tỉa râu", description: "Cạo mặt bằng dao, đắp khăn nóng · 20 phút", durationMinutes: 20, price: 50000 },
    "dv-goi-dau": { name: "Gội đầu massage", description: "Gội thảo dược, massage đầu và vai gáy · 25 phút", durationMinutes: 25, price: 60000 },
    "dv-uon-toc": { name: "Uốn tóc nam", description: "Uốn con sâu / xoăn nhẹ tự nhiên · 90 phút", durationMinutes: 90, price: 350000 },
    "dv-nhuom-toc": { name: "Nhuộm tóc", description: "Nhuộm màu thời trang, thuốc nhuộm cao cấp · 80 phút", durationMinutes: 80, price: 300000 },
    "dv-combo": { name: "Combo cắt + gội + cạo mặt", description: "Trọn gói thư giãn · 60 phút", durationMinutes: 60, price: 150000 },
  },
  branches: {
    "cn-quan1": { name: "Chí Phèo Quận 1", description: "12 Nguyễn Huệ, Phường Bến Nghé, Quận 1, TP. Hồ Chí Minh", phone: "0901 234 567" },
    "cn-quan3": { name: "Chí Phèo Quận 3", description: "45 Võ Văn Tần, Phường 6, Quận 3, TP. Hồ Chí Minh", phone: "0902 345 678" },
    "cn-thuduc": { name: "Chí Phèo Thủ Đức", description: "88 Võ Văn Ngân, Phường Linh Chiểu, TP. Thủ Đức, TP. Hồ Chí Minh", phone: "0903 456 789" },
  },
  stylists: {
    "tho-minh": { name: "Anh Minh", description: "Thợ chính · 8 năm kinh nghiệm", branchId: "cn-quan1" },
    "tho-hung": { name: "Anh Hùng", description: "Chuyên fade và undercut", branchId: "cn-quan1" },
    "tho-khoa": { name: "Anh Khoa", description: "Chuyên uốn và nhuộm", branchId: "cn-quan3" },
    "tho-tuan": { name: "Anh Tuấn", description: "Chuyên cạo mặt, tỉa râu", branchId: "cn-quan3" },
    "tho-long": { name: "Anh Long", description: "Thợ chính · phong cách cổ điển", branchId: "cn-thuduc" },
  },
  products: {
    "sp-sap-clay": { name: "Sáp vuốt tóc Clay Wax", description: "Giữ nếp mạnh, tạo độ phồng, không bóng", price: 180000, category: "Tạo kiểu", stock: 30 },
    "sp-pomade": { name: "Pomade gốc nước", description: "Bóng vừa, dễ gội sạch, hương gỗ nhẹ", price: 220000, category: "Tạo kiểu", stock: 25 },
    "sp-xit-gom": { name: "Gôm xịt giữ nếp", description: "Giữ nếp suốt 12 giờ, không bết tóc", price: 120000, category: "Tạo kiểu", stock: 40 },
    "sp-goi-nam": { name: "Dầu gội nam bạc hà", description: "Sạch gàu, mát lạnh, 350 ml", price: 130000, category: "Chăm sóc tóc", stock: 50 },
    "sp-xa": { name: "Dầu xả dưỡng tóc", description: "Mềm mượt, giảm khô xơ, 350 ml", price: 140000, category: "Chăm sóc tóc", stock: 35 },
    "sp-tinh-dau-rau": { name: "Tinh dầu dưỡng râu", description: "Làm mềm và giữ ẩm râu, 30 ml", price: 160000, category: "Chăm sóc râu", stock: 20 },
    "sp-luoc-go": { name: "Lược chải tóc bằng gỗ", description: "Gỗ tự nhiên, hạn chế tĩnh điện", price: 90000, category: "Phụ kiện", stock: 60 },
    "sp-khan": { name: "Khăn choàng cắt tóc", description: "Vải chống thấm, dễ giặt", price: 70000, category: "Phụ kiện", stock: 0 },
  },
  promotions: {
    "km-chao-ban": { name: "Chào bạn mới giảm 10%", description: "Giảm 10% cho đơn hàng đầu tiên. Nhập mã khi thanh toán.", code: "CHAOBAN10", percent: 10 },
    "km-combo": { name: "Ưu đãi cuối tuần", description: "Giảm 15% đơn hàng sản phẩm vào thứ Bảy và Chủ nhật.", code: "CUOITUAN15", percent: 15 },
    "km-ban-be": { name: "Giới thiệu bạn bè", description: "Giảm 20% khi bạn đi cùng bạn bè.", code: "BANBE20", percent: 20 },
  },
};

(async () => {
  for (const [col, docs] of Object.entries(data)) {
    const batch = db.batch();
    for (const [id, doc] of Object.entries(docs)) batch.set(db.collection(col).doc(id), doc);
    await batch.commit();
    console.log(`Đã nhập ${Object.keys(docs).length} tài liệu vào "${col}"`);
  }
})();
