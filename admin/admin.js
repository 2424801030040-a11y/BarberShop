import { initializeApp } from "https://www.gstatic.com/firebasejs/12.19.0/firebase-app.js";
import { getAuth, onAuthStateChanged, signInWithEmailAndPassword, GoogleAuthProvider, signInWithPopup, signOut } from "https://www.gstatic.com/firebasejs/12.19.0/firebase-auth.js";
import { getFirestore, collection, getDocs, limit, query, where, addDoc, doc, updateDoc, deleteDoc, deleteField, serverTimestamp } from "https://www.gstatic.com/firebasejs/12.19.0/firebase-firestore.js";
import { firebaseConfig } from "./firebase-config.js";
import { cloudinaryConfig } from "./cloudinary-config.js";
import { supabaseConfig } from "./supabase-config.js";

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const titles = {overview:"Tổng quan",appointments:"Lịch hẹn",orders:"Đơn hàng",bankTransfers:"Giao dịch ngân hàng",contacts:"Tin nhắn",reviews:"Đánh giá",services:"Dịch vụ",products:"Sản phẩm",branches:"Chi nhánh",stylists:"Thợ cắt tóc",promotions:"Khuyến mãi",shopEvents:"Sự kiện cửa hàng",rewardVouchers:"Voucher tích điểm",customers:"Khách hàng",staff:"Nhân viên"};
const schemas = {
  services:[{key:"name",label:"Tên dịch vụ",required:true},{key:"description",label:"Mô tả",type:"textarea"},{key:"durationMinutes",label:"Thời lượng (phút)",type:"number",required:true,min:15,max:240},{key:"price",label:"Giá (₫)",type:"number",required:true,min:0},{key:"imageUrl",label:"Ảnh dịch vụ",type:"image"}],
  products:[{key:"name",label:"Tên sản phẩm",required:true},{key:"description",label:"Mô tả",type:"textarea"},{key:"category",label:"Danh mục",required:true},{key:"price",label:"Giá (₫)",type:"number",required:true,min:0},{key:"stock",label:"Tồn kho",type:"number",required:true,min:0},{key:"imageUrl",label:"Ảnh sản phẩm",type:"image"}],
  branches:[{key:"name",label:"Tên chi nhánh",required:true},{key:"description",label:"Địa chỉ",type:"textarea",required:true},{key:"phone",label:"Điện thoại"},{key:"imageUrl",label:"Ảnh chi nhánh",type:"image"},{key:"mapUrl",label:"Liên kết vị trí trên bản đồ",type:"url"}],
  stylists:[{key:"name",label:"Tên thợ",required:true},{key:"description",label:"Mô tả",type:"textarea"},{key:"branchId",label:"Chi nhánh",type:"branch",required:true},{key:"imageUrl",label:"Ảnh thợ cắt tóc",type:"image"}],
  promotions:[{key:"name",label:"Tên chương trình",required:true},{key:"description",label:"Mô tả",type:"textarea"},{key:"code",label:"Mã khuyến mãi",required:true},{key:"percent",label:"Mức giảm (%) — chọn một",type:"number",min:1,max:100},{key:"discountAmount",label:"Giảm trực tiếp (₫) — chọn một",type:"number",min:1},{key:"minSubtotal",label:"Đơn tối thiểu (₫)",type:"number",min:0},{key:"startsAt",label:"Bắt đầu",type:"datetime"},{key:"endsAt",label:"Kết thúc",type:"datetime"},{key:"active",label:"Đang hoạt động",type:"checkbox"}],
  rewardVouchers:[{key:"name",label:"Tên voucher",required:true},{key:"description",label:"Mô tả",type:"textarea"},{key:"pointsCost",label:"Điểm cần đổi",type:"number",required:true,min:1},{key:"percent",label:"Mức giảm (%) — chọn một",type:"number",min:1,max:100},{key:"discountAmount",label:"Giảm trực tiếp (₫) — chọn một",type:"number",min:1},{key:"minSubtotal",label:"Đơn tối thiểu (₫)",type:"number",min:0},{key:"startsAt",label:"Bắt đầu",type:"datetime"},{key:"endsAt",label:"Kết thúc",type:"datetime"},{key:"active",label:"Đang hoạt động",type:"checkbox"}],
  shopEvents:[{key:"title",label:"Tên sự kiện",required:true},{key:"body",label:"Nội dung thông báo",type:"textarea",required:true},{key:"startsAt",label:"Bắt đầu",type:"datetime",required:true},{key:"endsAt",label:"Kết thúc",type:"datetime",required:true},{key:"active",label:"Đang hiển thị",type:"checkbox"}],
};
const state = {data:{}, page:"overview", query:"", busy:false, staffUsers:[]};
let db, auth;

function escapeHtml(value) { return String(value ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c])); }
function fmtMoney(value) { return Number(value || 0).toLocaleString("vi-VN") + " ₫"; }
function fmtDate(value) { if (!value) return "—"; const d = new Date(value); return Number.isNaN(d.getTime()) ? "—" : new Intl.DateTimeFormat("vi-VN",{timeZone:"Asia/Ho_Chi_Minh",dateStyle:"medium",timeStyle:"short"}).format(d); }
function statusText(value) { return ({pending:"Chờ xử lý",confirmed:"Đã xác nhận lịch",in_service:"Đang cắt tóc",completed:"Hoàn thành",canceled:"Đã hủy",shipping:"Đang giao",delivered:"Đã giao",paid:"Đã thanh toán",unpaid:"Chưa thanh toán",open:"Chưa xử lý",resolved:"Đã xử lý",active:"Đang hoạt động",disabled:"Đã khóa",cash:"Tiền mặt",bank:"Chuyển khoản"})[value] || value || "—"; }
function badge(value,label=statusText(value)) { const cls = ["completed","delivered","paid","resolved"].includes(value) ? "good" : ["pending","confirmed","in_service","shipping","unpaid","open"].includes(value) ? "warn" : ["canceled"].includes(value) ? "bad" : ""; return `<span class="badge ${cls}">${escapeHtml(label)}</span>`; }
function notify(message, error=false) { const el=$("#notice"); el.textContent=message; el.classList.toggle("error",error); el.hidden=false; clearTimeout(notify.timer); notify.timer=setTimeout(()=>el.hidden=true,5000); }
function showView(which) { for (const id of ["loginView","deniedView","appView"]) $("#"+id).hidden=id!==which; }
const adminCollections=["services","products","branches","stylists","promotions","rewardVouchers","shopEvents","appointments","orders","bankTransferEvents","contacts","reviews","users"];
function plain(value) {
  if(value&&typeof value.toDate==="function")return value.toDate().toISOString();
  if(Array.isArray(value))return value.map(plain);
  if(value&&typeof value==="object")return Object.fromEntries(Object.entries(value).map(([key,item])=>[key,plain(item)]));
  return value;
}

const configReady = firebaseConfig.apiKey && firebaseConfig.appId && !firebaseConfig.apiKey.startsWith("PASTE_") && !firebaseConfig.appId.startsWith("PASTE_");
if (!configReady) {
  showView("loginView");
  $("#loginError").textContent="Cấu hình Firebase Web app còn thiếu. Kiểm tra apiKey và appId trong firebase-config.js.";
  $("#loginForm button[type=submit]").disabled=true;
} else {
  const app=initializeApp(firebaseConfig); auth=getAuth(app); db=getFirestore(app);
  onAuthStateChanged(auth,async user=>{
    if(!user){showView("loginView");return;}
    try {
      const token=await user.getIdTokenResult(true);
      if(token.claims.admin!==true){showView("deniedView");return;}
      $("#userEmail").textContent=user.email||user.uid;
      showView("appView");
      await refreshData();
    } catch(error) { notify(error.message||"Không thể xác minh quyền quản trị.",true); showView("deniedView"); }
  });
  $("#loginForm").addEventListener("submit",async event=>{
    event.preventDefault(); $("#loginError").textContent="";
    try { await signInWithEmailAndPassword(auth,$("#loginEmail").value.trim(),$("#loginPassword").value); }
    catch(error) { $("#loginError").textContent=error.message||"Đăng nhập không thành công."; }
  });
  $("#googleSignInButton").addEventListener("click",async()=>{
    $("#loginError").textContent="";
    try { await signInWithPopup(auth,new GoogleAuthProvider()); }
    catch(error) { $("#loginError").textContent=error.message||"Đăng nhập Google không thành công."; }
  });
  $("#signOutButton").addEventListener("click",()=>signOut(auth));
  $("#deniedSignOut").addEventListener("click",()=>signOut(auth));
  $("#refreshButton").addEventListener("click",refreshData);
  $("#nav").addEventListener("click",event=>{const button=event.target.closest("[data-page]");if(button)go(button.dataset.page);});
  $("#menuToggle").addEventListener("click",()=>$(".sidebar").classList.toggle("open"));
  $("#recordForm").addEventListener("submit",saveRecord);
  $("#recordForm").addEventListener("click",event=>{if(event.target.closest("[data-import-image]"))uploadCatalogImageFromUrl($("#imageUrlInput")?.value.trim());});
  $$('[data-close]').forEach(button=>button.addEventListener("click",()=>$("#recordDialog").close()));
  $("#content").addEventListener("click",handleContentClick);
  $("#content").addEventListener("input",event=>{if(event.target.id==="tableSearch"){state.query=event.target.value.toLocaleLowerCase();if(state.page==="staff"){const position=event.target.selectionStart;$("#content").innerHTML=renderStaffTable();const search=$("#tableSearch");search.focus();search.setSelectionRange(position,position);}else renderPage();}});
}

async function refreshData() {
  if(state.busy)return;
  state.busy=true; $("#content").innerHTML='<div class="loading">Đang tải dữ liệu cửa hàng…</div>';
  try {
    const snapshots=await Promise.all(adminCollections.map(name=>getDocs(query(collection(db,name),limit(name==="users"?500:300)))));
    state.data=Object.fromEntries(adminCollections.map((name,index)=>[name,snapshots[index].docs.map(item=>({id:item.id,...plain(item.data())}))]));
    renderPage();
  }
  catch(error) { $("#content").innerHTML=`<div class="panel empty-state">Không tải được dữ liệu quản trị.<br><br>${escapeHtml(error.message||"")}</div>`; }
  finally { state.busy=false; }
}

function go(page) { state.page=page;state.query="";$("#pageTitle").textContent=titles[page]||titles.overview;$("#nav .active")?.classList.remove("active");$(`#nav [data-page="${page}"]`)?.classList.add("active");$(".sidebar").classList.remove("open");renderPage(); }
function renderPage() { if(!$("#appView").hidden) { $("#pageTitle").textContent=titles[state.page]||titles.overview;if(state.page==="staff"){ $("#content").innerHTML='<div class="panel loading">Đang tải danh sách tài khoản…</div>';loadStaffUsers();return;} $("#content").innerHTML=state.page==="overview"?renderOverview():renderTablePage(state.page); } }

async function callManageStaff(payload){
  if(!auth?.currentUser)throw new Error("Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.");
  if(!supabaseConfig.url||!supabaseConfig.publishableKey)throw new Error("Chưa cấu hình URL và publishable key của Supabase cho trang quản trị.");
  const idToken=await auth.currentUser.getIdToken();
  const response=await fetch(`${supabaseConfig.url}/functions/v1/manage-staff`,{method:"POST",headers:{"content-type":"application/json","apikey":supabaseConfig.publishableKey,"authorization":`Bearer ${idToken}`},body:JSON.stringify(payload)});
  const result=await response.json().catch(()=>({}));
  if(!response.ok)throw new Error(result.error||`Quản lý tài khoản thất bại (HTTP ${response.status}).`);
  return result;
}

async function loadStaffUsers(){
  try{
    const result=await callManageStaff({action:"list"});
    if(state.page!=="staff")return;
    state.staffUsers=Array.isArray(result.users)?result.users:[];
    $("#content").innerHTML=renderStaffTable();
  }catch(error){
    if(state.page==="staff")$("#content").innerHTML=`<div class="panel empty-state">Không tải được danh sách tài khoản.<br><br>${escapeHtml(error.message||"")}<br><br><button class="button secondary" data-staff-reload>Tải lại</button></div>`;
  }
}

function renderStaffTable(){
  const ownUid=auth?.currentUser?.uid||"";
  const users=(state.staffUsers||[]).filter(user=>!state.query||JSON.stringify(user).toLocaleLowerCase().includes(state.query));
  const body=users.map(user=>{
    const roles=Array.isArray(user.roles)?user.roles:[];
    const roleText=roles.includes("admin")&&roles.includes("staff")?"Quản trị · Nhân viên":roles.includes("admin")?"Quản trị viên":roles.includes("staff")?"Nhân viên":"Người dùng";
    const status=user.disabled?badge("disabled"):badge("active");
    const staffLabel=roles.includes("staff")?"Thu hồi nhân viên":"Cấp nhân viên";
    const adminLabel=roles.includes("admin")?"Thu hồi quản trị":"Cấp quản trị";
    const staffDisabled=user.uid===ownUid&&roles.includes("admin")?"disabled":"";
    const adminDisabled=user.uid===ownUid&&roles.includes("admin")?"disabled":"";
    const lockDisabled=user.uid===ownUid?"disabled":"";
    return `<tr><td>${escapeHtml(user.displayName||"—")}</td><td>${escapeHtml(user.email||"—")}</td><td>${escapeHtml(user.uid)}</td><td>${escapeHtml(roleText)}</td><td>${status}</td><td>${escapeHtml(fmtDate(user.createdAt))}</td><td><div class="row-actions"><button class="button secondary" data-staff-action="role" data-role="staff" data-uid="${escapeHtml(user.uid)}" data-enabled="${!roles.includes("staff")}" ${staffDisabled}>${staffLabel}</button><button class="button secondary" data-staff-action="role" data-role="admin" data-uid="${escapeHtml(user.uid)}" data-enabled="${!roles.includes("admin")}" ${adminDisabled}>${adminLabel}</button><button class="button secondary" data-staff-action="disabled" data-uid="${escapeHtml(user.uid)}" data-disabled="${!user.disabled}" ${lockDisabled}>${user.disabled?"Mở khóa":"Khóa tài khoản"}</button></div></td></tr>`;
  }).join("");
  return `<div class="table-card"><div class="table-toolbar"><input id="tableSearch" type="search" placeholder="Tìm tên, email hoặc UID…" value="${escapeHtml(state.query)}"><button class="button secondary" data-staff-reload>Làm mới</button><p class="muted">${users.length} / ${(state.staffUsers||[]).length} tài khoản</p></div><div class="table-wrap">${users.length?`<table><thead><tr><th>Tên hiển thị</th><th>Email</th><th>UID</th><th>Vai trò</th><th>Tài khoản</th><th>Ngày tạo</th><th>Thao tác</th></tr></thead><tbody>${body}</tbody></table>`:`<div class="empty-state">Không có tài khoản phù hợp.</div>`}</div></div>`;
}

async function handleStaffAction(button){
  const uid=button.dataset.uid;
  const isRole=button.dataset.staffAction==="role";
  const role=button.dataset.role;
  const enabled=button.dataset.enabled==="true";
  const disabled=button.dataset.disabled==="true";
  const actionLabel=isRole?`${enabled?"Cấp":"Thu hồi"} quyền ${role==="admin"?"quản trị":"nhân viên"}`:`${disabled?"Khóa":"Mở khóa"} tài khoản`;
  if(!confirm(`${actionLabel} cho tài khoản ${uid}?`))return;
  button.disabled=true;
  try{
    if(isRole)await callManageStaff({action:"setRole",uid,role,enabled});
    else await callManageStaff({action:"toggleDisabled",uid,disabled});
    notify(`Đã cập nhật: ${actionLabel}. Người dùng cần đăng nhập lại để nhận quyền mới.`);
    await loadStaffUsers();
  }catch(error){notify(error.message||"Không thể cập nhật tài khoản.",true);button.disabled=false;}
}

function renderOverview() {
  const d=state.data, today=new Intl.DateTimeFormat("en-CA",{timeZone:"Asia/Ho_Chi_Minh"}).format(new Date());
  const todaysAppts=(d.appointments||[]).filter(x=>x.start?.slice(0,10)===today);
  const pendingOrders=(d.orders||[]).filter(x=>x.status==="pending");
  const openMessages=(d.contacts||[]).filter(x=>x.status!=="resolved");
  const pendingAppts=(d.appointments||[]).filter(x=>x.status==="pending");
  const revenue=monthlyRevenueData(d.orders||[],d.appointments||[],d.products||[],today);
  const paidThisMonth=revenue.productRevenue+revenue.serviceRevenue;
  const lowStock=(d.products||[]).filter(x=>Number(x.stock||0)<=5);
  return `<div class="stats-grid">
    ${stat("Lịch hẹn hôm nay",todaysAppts.length,"Theo giờ Việt Nam")}${stat("Lịch chờ xác nhận",pendingAppts.length,"Cần xử lý")}${stat("Đơn hàng chờ",pendingOrders.length,"Chưa gửi hàng")}${stat("Tin nhắn mới",openMessages.length,"Chưa đánh dấu xử lý")}${stat("Doanh thu tháng này",fmtMoney(paidThisMonth),"Sản phẩm đã thu + dịch vụ hoàn tất")}${stat("Sắp hết hàng",lowStock.length,"Sản phẩm còn tối đa 5")}
  </div><div class="dashboard-grid"><section class="panel"><div class="section-heading" style="margin:0 0 12px"><h2>Truy cập nhanh</h2></div><div class="quick-list">
    ${quick("Lịch hẹn","appointments","Cập nhật tiến độ dịch vụ")}${quick("Đơn hàng","orders","Xác nhận thanh toán và hoàn thành đơn")}${quick("Sản phẩm","products","Quản lý kho và giá bán")}${quick("Nhân viên","staff","Quản lý quyền cần công cụ tin cậy")}
  </div></section><section class="panel"><div class="section-heading" style="margin:0 0 8px"><h2>Cần chú ý</h2></div>
    ${statusRow("Lịch hẹn chờ",pendingAppts.length,"appointments")}${statusRow("Đơn hàng chờ",pendingOrders.length,"orders")}${statusRow("Tin nhắn chưa xử lý",openMessages.length,"contacts")}${statusRow("Đánh giá mới",(d.reviews||[]).length,"reviews")}
  </section></div><section class="panel revenue-panel"><div class="section-heading revenue-heading"><div><p class="eyebrow">DOANH THU</p><h2>Doanh thu theo ngày · ${escapeHtml(today.slice(0,7))}</h2></div><button class="button primary" data-export-monthly-revenue>Xuất Excel tháng này</button></div><p class="muted revenue-note">Sản phẩm: đơn đã thanh toán theo ngày nhận tiền. Dịch vụ: lịch hẹn đã hoàn tất theo ngày hoàn tất. Bảng dựa trên tối đa 300 đơn và 300 lịch gần nhất đã tải.</p><div class="table-wrap revenue-table-wrap"><table><thead><tr><th>Ngày</th><th>Đơn sản phẩm</th><th>Doanh thu sản phẩm</th><th>Dịch vụ hoàn tất</th><th>Doanh thu dịch vụ</th><th>Tổng doanh thu</th></tr></thead><tbody>${revenue.days.map(day=>`<tr><td>${escapeHtml(day.date)}</td><td>${day.orderCount}</td><td>${fmtMoney(day.productTotal)}</td><td>${day.serviceCount}</td><td>${fmtMoney(day.serviceTotal)}</td><td>${fmtMoney(day.total)}</td></tr>`).join("")}</tbody><tfoot><tr><th>Tổng tháng</th><th>${revenue.orders.length}</th><th>${fmtMoney(revenue.productRevenue)}</th><th>${revenue.appointments.length}</th><th>${fmtMoney(revenue.serviceRevenue)}</th><th>${fmtMoney(paidThisMonth)}</th></tr></tfoot></table></div></section><div class="section-heading"><div><p class="eyebrow">DỮ LIỆU TỔNG HỢP</p><h2>Danh mục đang quản lý</h2></div></div><div class="stats-grid">${stat("Dịch vụ",(d.services||[]).length,"Tối đa 300 bản ghi đã tải")}${stat("Sản phẩm",(d.products||[]).length,"Tối đa 300 bản ghi đã tải")}${stat("Chi nhánh",(d.branches||[]).length,"Tối đa 300 bản ghi đã tải")}${stat("Hồ sơ khách hàng",(d.users||[]).length,"Hồ sơ Firestore đã tải")}</div>`;
}
function revenueDate(order){return order.paidAt||order.completedAt||order.createdAt||null;}
function dateKey(value){if(!value)return "";const date=new Date(value);return Number.isNaN(date.getTime())?"":new Intl.DateTimeFormat("en-CA",{timeZone:"Asia/Ho_Chi_Minh"}).format(date);}
function serviceRevenueDate(appointment){return appointment.closedAt||appointment.start||appointment.createdAt||null;}
function monthlyRevenueData(orders,appointments,products,today){
  const month=today.slice(0,7),start=new Date(`${month}-01T00:00:00+07:00`),nextMonth=new Date(start);nextMonth.setMonth(nextMonth.getMonth()+1);
  const monthEnd=new Intl.DateTimeFormat("en-CA",{timeZone:"Asia/Ho_Chi_Minh"}).format(new Date(nextMonth.getTime()-1));
  const paid=orders.filter(order=>order.paymentStatus==="paid"&&dateKey(revenueDate(order)).startsWith(month));
  const completed=appointments.filter(appointment=>appointment.status==="completed"&&dateKey(serviceRevenueDate(appointment)).startsWith(month));
  const totals=new Map();const getDay=key=>{if(!totals.has(key))totals.set(key,{orderCount:0,productTotal:0,serviceCount:0,serviceTotal:0});return totals.get(key);};
  for(const order of paid){const day=getDay(dateKey(revenueDate(order)));day.orderCount++;day.productTotal+=Number(order.total)||0;}
  for(const appointment of completed){const day=getDay(dateKey(serviceRevenueDate(appointment)));day.serviceCount++;day.serviceTotal+=Number(appointment.total)||0;}
  const days=[];for(let date=new Date(start);dateKey(date)<=monthEnd;date.setDate(date.getDate()+1)){const key=dateKey(date),value=totals.get(key)||{orderCount:0,productTotal:0,serviceCount:0,serviceTotal:0};days.push({date:key,...value,total:value.productTotal+value.serviceTotal});}
  const productById=new Map(products.map(product=>[product.id,product]));
  return {orders:paid,appointments:completed,days,productRevenue:paid.reduce((sum,order)=>sum+(Number(order.total)||0),0),serviceRevenue:completed.reduce((sum,appointment)=>sum+(Number(appointment.total)||0),0),productById};
}
function xmlEscape(value){return String(value??"").replace(/[&<>"']/g,char=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&apos;"}[char]));}
function excelWorksheet(name,rows){return `<Worksheet ss:Name="${xmlEscape(name)}"><Table>${rows.map(row=>`<Row>${row.map(value=>{const numeric=typeof value==="number"&&Number.isFinite(value);return `<Cell><Data ss:Type="${numeric?"Number":"String"}">${xmlEscape(value)}</Data></Cell>`;}).join("")}</Row>`).join("")}</Table></Worksheet>`;}
function exportMonthlyRevenue(){
  const today=new Intl.DateTimeFormat("en-CA",{timeZone:"Asia/Ho_Chi_Minh"}).format(new Date()),data=monthlyRevenueData(state.data.orders||[],state.data.appointments||[],state.data.products||[],today);
  const daily=[["Ngày","Số đơn sản phẩm","Doanh thu sản phẩm (VND)","Số dịch vụ hoàn tất","Doanh thu dịch vụ (VND)","Tổng doanh thu (VND)"],...data.days.map(day=>[day.date,day.orderCount,day.productTotal,day.serviceCount,day.serviceTotal,day.total]),["TỔNG THÁNG",data.orders.length,data.productRevenue,data.appointments.length,data.serviceRevenue,data.productRevenue+data.serviceRevenue]];
  const products=[["Mã đơn","Ngày thanh toán","Mã sản phẩm","Tên sản phẩm","Loại / danh mục","Mô tả sản phẩm","Số lượng","Đơn giá (VND)","Thành tiền trước giảm (VND)","Tổng đơn sau giảm (VND)","Phương thức thanh toán","Khách hàng UID"],...data.orders.flatMap(order=>(Array.isArray(order.items)?order.items:[]).map(item=>{const product=data.productById.get(String(item.productId))||{};const qty=Number(item.quantity??item.qty)||0,price=Number(item.price??product.price)||0;return [order.orderCode||order.id,dateKey(revenueDate(order)),item.productId||"",item.name||product.name||"Sản phẩm",product.category||"Chưa phân loại",product.description||"",qty,price,qty*price,Number(order.total)||0,order.payment||"",order.userId||""];}))];
  const services=[["Mã lịch hẹn","Ngày hoàn tất","Mã dịch vụ","Tên dịch vụ","Thời lượng (phút)","Chi nhánh","Thợ cắt tóc","Giá dịch vụ (VND)","Khách hàng UID"],...data.appointments.map(appointment=>[appointment.id,dateKey(serviceRevenueDate(appointment)),appointment.serviceId||"",appointment.serviceName||"Dịch vụ",Number(appointment.durationMinutes)||0,appointment.branchName||appointment.branchId||"",appointment.stylistName||appointment.stylistId||"",Number(appointment.total)||0,appointment.userId||""])];
  const xml=`<?xml version="1.0"?><Workbook xmlns="urn:schemas-microsoft-com:office:spreadsheet" xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:x="urn:schemas-microsoft-com:office:excel" xmlns:ss="urn:schemas-microsoft-com:office:spreadsheet">${excelWorksheet("Doanh thu theo ngày",daily)}${excelWorksheet("Chi tiết sản phẩm",products)}${excelWorksheet("Chi tiết dịch vụ",services)}</Workbook>`;
  const blob=new Blob(["\ufeff",xml],{type:"application/vnd.ms-excel;charset=utf-8"}),url=URL.createObjectURL(blob),link=document.createElement("a");link.href=url;link.download=`doanh-thu-${today.slice(0,7)}.xls`;document.body.appendChild(link);link.click();link.remove();URL.revokeObjectURL(url);
  notify(`Đã xuất ${data.orders.length} đơn sản phẩm và ${data.appointments.length} dịch vụ hoàn tất trong tháng ${today.slice(0,7)}.`);
}
function stat(label,value,hint){return `<article class="stat-card"><div class="stat-label">${escapeHtml(label)}</div><div class="stat-value">${escapeHtml(value)}</div><div class="stat-hint">${escapeHtml(hint)}</div></article>`;}
function quick(label,page,desc){return `<button class="quick-link" data-go="${page}">${escapeHtml(label)}<span>${escapeHtml(desc)}</span></button>`;}
function statusRow(label,count,page){return `<div class="status-row"><span>${escapeHtml(label)}</span><button class="button secondary" data-go="${page}">${count} xem</button></div>`;}

const tableDefs={
  appointments:{collection:"appointments",columns:[["serviceName","Dịch vụ"],["branchName","Chi nhánh"],["stylistName","Thợ"],["start","Thời gian",fmtDate],["status","Trạng thái",v=>badge(v,v==="pending"?"Chờ xử lý / chờ cọc":undefined)]],actions:"appointment"},
  orders:{collection:"orders",columns:[["orderCode","Mã đơn"],["total","Tổng tiền",fmtMoney],["payment","Thanh toán",statusText],["paymentStatus","Tình trạng tiền",v=>badge(v)],["paymentReviewRequired","Đối soát",v=>v?badge("unpaid","Cần kiểm tra"):"—"],["status","Trạng thái",v=>badge(v,v==="pending"?"Chờ thanh toán":undefined)]],actions:"order"},
  bankTransfers:{collection:"bankTransferEvents",columns:[["providerTransactionId","Mã giao dịch SePay"],["orderCode","Mã đơn"],["transferAmount","Số tiền",fmtMoney],["referenceCode","Mã tham chiếu"],["result","Kết quả",v=>({matched:"Đã xác nhận",late_payment_review:"Đã nhận tiền · cần đối soát",reservation_expired:"Đơn hết hạn",unmatched:"Không khớp đơn",order_missing:"Không tìm thấy đơn",not_bank_order:"Không phải đơn chuyển khoản",already_paid:"Đơn đã thanh toán",order_canceled:"Đơn đã hủy",amount_mismatch:"Sai số tiền"}[v]||v)],["receivedAt","Nhận lúc",fmtDate]]},
  contacts:{collection:"contacts",columns:[["message","Nội dung"],["userId","UID khách hàng"],["createdAt","Ngày gửi",fmtDate],["status","Trạng thái",v=>badge(v||"open")]],actions:"contact"},
  reviews:{collection:"reviews",columns:[["targetName","Đối tượng"],["rating","Số sao",v=>`${escapeHtml(v||0)} ★`],["comment","Nhận xét"],["userId","UID khách hàng"],["createdAt","Ngày gửi",fmtDate]],actions:"review"},
  services:{collection:"services",columns:[["imageUrl","Ảnh",imageCell],["name","Dịch vụ"],["durationMinutes","Thời lượng",v=>`${escapeHtml(v)} phút`],["price","Giá",fmtMoney],["description","Mô tả"]],crud:true},
  products:{collection:"products",columns:[["imageUrl","Ảnh",imageCell],["name","Sản phẩm"],["category","Danh mục"],["price","Giá",fmtMoney],["stock","Tồn kho"],["description","Mô tả"]],crud:true},
  branches:{collection:"branches",columns:[["name","Chi nhánh"],["description","Địa chỉ",branchMapCell],["phone","Điện thoại"]],crud:true},
  stylists:{collection:"stylists",columns:[["imageUrl","Ảnh",imageCell],["name","Thợ"],["branchId","Mã chi nhánh"],["description","Mô tả"]],crud:true},
  promotions:{collection:"promotions",columns:[["name","Chương trình"],["code","Mã"],["discount","Mức giảm",(_,row)=>discountLabel(row)],["active","Trạng thái",v=>badge(v===false?"canceled":"resolved")],["startsAt","Bắt đầu",fmtDate],["endsAt","Kết thúc",fmtDate]],crud:true},
  rewardVouchers:{collection:"rewardVouchers",columns:[["name","Voucher"],["pointsCost","Điểm đổi",v=>`${escapeHtml(v)} điểm`],["discount","Mức giảm",(_,row)=>discountLabel(row)],["minSubtotal","Đơn tối thiểu",fmtMoney],["active","Trạng thái",v=>badge(v===false?"canceled":"resolved")],["endsAt","Hết hạn",fmtDate]],crud:true},
  shopEvents:{collection:"shopEvents",columns:[["title","Sự kiện"],["body","Thông báo"],["startsAt","Bắt đầu",fmtDate],["endsAt","Kết thúc",fmtDate],["active","Trạng thái",v=>badge(v===false?"canceled":"resolved")]],crud:true},
  customers:{collection:"users",columns:[["name","Tên",customerNameCell],["phone","Điện thoại"],["email","Email",customerEmailCell],["id","UID"]],users:true},
};
function discountLabel(row){return Number(row?.discountAmount)>0?`Giảm ${fmtMoney(row.discountAmount)}`:`Giảm ${escapeHtml(row?.percent||0)}%`;}

function renderTablePage(page) {
  const def=tableDefs[page];if(!def)return '<div class="panel empty-state">Màn hình chưa được cấu hình.</div>';
  let rows=state.data[def.collection]||[];
  if(def.users)rows=rows.filter(row=>!row.staff&&!row.admin);
  if(state.query)rows=rows.filter(row=>JSON.stringify(row).toLocaleLowerCase().includes(state.query));
  const isCatalog=Boolean(def.crud);
  const actionHeader=def.actions||def.crud;
  const th=def.columns.map(column=>`<th>${escapeHtml(column[1])}</th>`).join("");
  const body=rows.map(row=>`<tr>${def.columns.map(([key,,format])=>`<td title="${escapeHtml(rawCell(row[key]))}">${format?format(row[key],row):escapeHtml(rawCell(row[key]))}</td>`).join("")}${actionHeader?`<td>${rowActions(page,row,def)}</td>`:""}</tr>`).join("");
  const actionButtons=isCatalog?`<button class="button primary" data-action="create" data-collection="${page}">＋ Thêm mới</button>`:"";
  const note={appointments:"Dùng thao tác ở cuối dòng để chuyển lịch qua xác nhận, đang cắt tóc, hoàn thành hoặc hủy.",orders:"Đơn SePay tự hoàn thành khi thanh toán khớp; dùng thao tác cuối dòng để xác nhận thu tiền mặt hoặc hủy đơn chưa thanh toán.",customers:"Chỉ hiển thị hồ sơ Firestore đã tồn tại; quản lý tài khoản đăng nhập cần công cụ riêng."}[page];
  return `<div class="table-card"><div class="table-toolbar">${actionButtons}<input id="tableSearch" type="search" placeholder="Tìm trong danh sách…" value="${escapeHtml(state.query)}"><p class="muted">${rows.length} mục hiển thị · tải tối đa ${def.users?500:300}${note?`<br>${note}`:""}</p></div><div class="table-wrap">${rows.length?`<table><thead><tr>${th}${actionHeader?"<th>Thao tác</th>":""}</tr></thead><tbody>${body}</tbody></table>`:`<div class="empty-state">Chưa có dữ liệu phù hợp.</div>`}</div></div>`;
}
function rawCell(value){if(value===null||value===undefined||value==="")return "—";if(typeof value==="object")return JSON.stringify(value);if(typeof value==="boolean")return value?"Có":"Không";return value;}
function customerNameCell(value,row){return escapeHtml(value||row.displayName||"—");}
function customerEmailCell(value,row){
  const email=value||row.email||(state.data.contacts||[]).filter(message=>message.userId===row.id).sort((a,b)=>String(b.createdAt||"").localeCompare(String(a.createdAt||""))).map(message=>message.from).find(candidate=>typeof candidate==="string"&&candidate.includes("@"));
  return escapeHtml(email||"—");
}
function imageCell(value){return isCloudinaryImage(value)?`<img class="catalog-thumb" src="${escapeHtml(value)}" alt="" loading="lazy">`:"—";}
function branchMapCell(value,row){const address=String(value||"");const query=encodeURIComponent(`${row.name||""} ${address}`.trim());const mapUrl=typeof row.mapUrl==="string"&&/^https:\/\//i.test(row.mapUrl)?row.mapUrl:`https://www.google.com/maps/search/?api=1&query=${query}`;return `${address?`<span>${escapeHtml(address)}</span><br>`:""}<a class="map-link" href="${escapeHtml(mapUrl)}" target="_blank" rel="noopener noreferrer">Mở Google Maps ↗</a>`;}
function isCloudinaryImage(value){return typeof value==="string"&&value.startsWith(`https://res.cloudinary.com/${cloudinaryConfig.cloudName}/image/upload/`);}
function rowActions(page,row,def){const id=escapeHtml(row.id);
  if(page==="appointments"){
    const actions={pending:[["confirmed","Xác nhận lịch"],["canceled","Hủy lịch"]],confirmed:[["in_service","Bắt đầu cắt tóc"],["canceled","Hủy lịch"]],in_service:[["completed","Hoàn thành dịch vụ"]]}[row.status]||[];
    return actions.length?`<div class="row-actions">${actions.map(([status,label])=>`<button class="button secondary" data-action="work-status" data-kind="appointment" data-id="${id}" data-status="${status}">${label}</button>`).join("")}</div>`:"—";
  }
  if(page==="orders"){
    const status=row.status||"pending",paid=row.paymentStatus==="paid";
    const actions=status==="pending"?(paid?[["completed","Hoàn thành / giao khách","paid"]]:[["completed","Đã thu tiền mặt · hoàn thành","paid"],["canceled","Hủy đơn","unpaid"]]):status==="shipping"?[["delivered","Đã giao","paid"]]:status==="delivered"?[["completed","Xác nhận hoàn tất đơn","paid"]]:[];
    return actions.length?`<div class="row-actions">${actions.map(([next,label,payment])=>`<button class="button secondary" data-action="work-status" data-kind="order" data-id="${id}" data-status="${next}" data-payment="${payment}">${label}</button>`).join("")}</div>`:"—";
  }
  if(def.crud)return `<div class="row-actions"><button class="button secondary" data-action="edit" data-collection="${page}" data-id="${id}">Sửa</button><button class="button secondary" data-action="delete" data-collection="${page}" data-id="${id}">Xóa</button></div>`;
  if(page==="contacts")return `<div class="row-actions"><button class="button secondary" data-action="contact-reply" data-id="${id}" ${row.userId?"":"disabled"}>Trả lời </button><button class="button secondary" data-action="contact-toggle" data-id="${id}" data-resolved="${row.status!=="resolved"}">${row.status==="resolved"?"Mở lại":"Đánh dấu xử lý"}</button></div>`;
  if(page==="reviews")return `<button class="button secondary" data-action="delete" data-collection="reviews" data-id="${id}">Gỡ đánh giá</button>`;
  if(page==="customers")return "—";
  return "";
}

async function handleContentClick(event) {
  if(event.target.closest("[data-export-monthly-revenue]")){exportMonthlyRevenue();return;}
  const staffActionButton=event.target.closest("[data-staff-action]");if(staffActionButton){await handleStaffAction(staffActionButton);return;}
  if(event.target.closest("[data-staff-reload]")){await loadStaffUsers();return;}
  const goButton=event.target.closest("[data-go]");if(goButton){go(goButton.dataset.go);return;}
  const button=event.target.closest("[data-action]");if(!button)return;
  const {action,id,collection,value}=button.dataset;
  try {
    if(action==="create"||action==="edit"){openRecord(collection,action==="edit"?id:null);return;}
    if(action==="work-status"){
      const appointment=button.dataset.kind==="appointment";
      const payload=appointment?{kind:"appointment",appointmentId:id,status:button.dataset.status}:{kind:"order",orderId:id,status:button.dataset.status,...(button.dataset.payment==="paid"?{paymentStatus:"paid"}:{})};
      button.disabled=true;
      const idToken=await auth.currentUser.getIdToken();
      const response=await fetch(`${supabaseConfig.url}/functions/v1/update-work-status`,{method:"POST",headers:{"content-type":"application/json","apikey":supabaseConfig.publishableKey,"authorization":`Bearer ${idToken}`},body:JSON.stringify(payload)});
      const result=await response.json().catch(()=>({}));
      if(!response.ok)throw new Error(result.error||`Cập nhật thất bại (HTTP ${response.status}).`);
      notify(appointment?"Đã cập nhật lịch hẹn; khách hàng sẽ thấy trạng thái mới.":"Đã cập nhật đơn hàng; khách hàng sẽ thấy trạng thái mới.");
      await refreshData();return;
    }
    if(action==="delete"){
      if(!confirm(collection==="reviews"?"Gỡ đánh giá này khỏi cửa hàng?":"Xóa mục này? Hành động này không thể hoàn tác."))return;
      if(collection==="branches"){
        const attached=await getDocs(query(collectionRef("stylists"),where("branchId","==",id)));
        if(!attached.empty)throw new Error("Hãy chuyển hoặc xóa thợ đang gắn với chi nhánh trước.");
      }
      if(["services","stylists","branches"].includes(collection)){
        const field=collection==="services"?"serviceId":collection==="stylists"?"stylistId":"branchId";
        const referenced=await getDocs(query(collectionRef("appointments"),where(field,"==",id)));
        if(referenced.docs.some(item=>["pending","confirmed"].includes(item.data().status)))throw new Error("Không thể xóa mục đang được lịch hẹn mở sử dụng.");
      }
      if(collection==="products"){
        const pending=await getDocs(query(collectionRef("orders"),where("status","==","pending")));
        if(pending.docs.some(item=>(item.data().items||[]).some(product=>product.productId===id)))throw new Error("Không thể xóa sản phẩm đang có trong đơn chờ xử lý.");
      }
      await deleteDoc(doc(db,collection,id));notify("Đã xóa mục.");await refreshData();return;
    }
    if(action==="contact-toggle"){
      const resolve=button.dataset.resolved==="true";
      button.disabled=true;
      const idToken=await auth.currentUser.getIdToken();
      const response=await fetch(`${supabaseConfig.url}/functions/v1/update-work-status`,{method:"POST",headers:{"content-type":"application/json","apikey":supabaseConfig.publishableKey,"authorization":`Bearer ${idToken}`},body:JSON.stringify({kind:"contact",contactId:id,status:resolve?"resolved":"open"})});
      const result=await response.json().catch(()=>({}));
      if(!response.ok)throw new Error(result.error||`Cập nhật trạng thái thất bại (HTTP ${response.status}).`);
      notify(resolve?"Đã đánh dấu tin nhắn đã xử lý.":"Đã mở lại tin nhắn.");await refreshData();return;
    }
    if(action==="contact-reply"){
      const contact=(state.data.contacts||[]).find(item=>item.id===id);
      if(!contact?.userId)throw new Error("Tin nhắn này không có tài khoản khách hàng để nhận phản hồi.");
      const body=window.prompt(`Trả lời khách hàng ${contact.from||contact.userId}:`);
      if(body===null)return;
      const reply=body.trim();
      if(!reply){notify("Vui lòng nhập nội dung trả lời.",true);return;}
      if(reply.length>4000){notify("Nội dung trả lời tối đa 4.000 ký tự.",true);return;}
      button.disabled=true;
      const idToken=await auth.currentUser.getIdToken();
      const response=await fetch(`${supabaseConfig.url}/functions/v1/send-contact-reply`,{method:"POST",headers:{"content-type":"application/json","apikey":supabaseConfig.publishableKey,"authorization":`Bearer ${idToken}`},body:JSON.stringify({contactId:id,body:reply})});
      const result=await response.json().catch(()=>({}));
      if(!response.ok)throw new Error(result.error||`Gửi phản hồi thất bại (HTTP ${response.status}).`);
      notify(result.pushSent?"Đã gửi phản hồi và thông báo đến khách.":"Đã gửi phản hồi vào hộp thông báo trong ứng dụng của khách.");button.disabled=false;return;
    }
  } catch(error) { if(button)button.disabled=false; notify(error.message||"Thao tác chưa thành công.",true); }
}
function openRecord(collection,id) {
  const schema=schemas[collection],existing=id?(state.data[collection]||[]).find(row=>row.id===id):null;
  if(!schema)return;
  $("#dialogTitle").textContent=existing?"Chỉnh sửa":"Thêm mới";
  $("#formError").textContent="";
  $("#recordForm").dataset.collection=collection;$("#recordForm").dataset.id=id||"";
  $("#formFields").innerHTML=schema.map(field=>{
    const value=existing?.[field.key];const required=field.required?"required":"";const full=field.type==="textarea"?" full":"";
    if(field.type==="checkbox")return `<label class="check-field form-field full"><input name="${field.key}" type="checkbox" ${value===undefined||value===true?"checked":""}>${escapeHtml(field.label)}</label>`;
    if(field.type==="branch")return `<label class="form-field">${escapeHtml(field.label)}<select name="${field.key}" ${required}>${(state.data.branches||[]).map(branch=>`<option value="${escapeHtml(branch.id)}" ${value===branch.id?"selected":""}>${escapeHtml(branch.name)}</option>`).join("")}</select></label>`;
    if(field.type==="image")return `<div class="form-field full image-upload-field"><span>${escapeHtml(field.label)}</span><div class="image-url-import"><input id="imageUrlInput" type="url" placeholder="Dán liên kết ảnh công khai (https://…)" autocomplete="url"><button class="button secondary" type="button" data-import-image>Nhập liên kết</button></div><input name="imageUrl" type="hidden" value="${escapeHtml(value||"")}"><div class="image-upload-preview"><img id="imagePreview" src="${isCloudinaryImage(value)?escapeHtml(value):""}" alt="Ảnh xem trước" ${isCloudinaryImage(value)?"":"hidden"}><span id="imageUploadStatus">${isCloudinaryImage(value)?"Đã có ảnh. Dán liên kết mới để thay thế.":"Dán liên kết ảnh công khai để nhập vào Cloudinary."}</span></div></div>`;
    let type=field.type||"text";let fieldValue=value??"";
    if(type==="datetime"){type="datetime-local";if(fieldValue)fieldValue=toLocalInput(fieldValue);}
    return `<label class="form-field${full}">${escapeHtml(field.label)}${type==="textarea"?`<textarea name="${field.key}" ${required}>${escapeHtml(fieldValue)}</textarea>`:`<input name="${field.key}" type="${type}" value="${escapeHtml(fieldValue)}" ${required} ${field.min!==undefined?`min="${field.min}"`:""} ${field.max!==undefined?`max="${field.max}"`:""} ${type==="number"?"step=\"1\"":""}>`}</label>`;
  }).join("");
  $("#recordDialog").showModal();
}
function toLocalInput(value){const date=new Date(value);if(Number.isNaN(date.getTime()))return "";const shifted=new Date(date.getTime()-date.getTimezoneOffset()*60000);return shifted.toISOString().slice(0,16);}

async function saveRecord(event) {
  event.preventDefault();
  const form=event.currentTarget,collection=form.dataset.collection,id=form.dataset.id||undefined,data={};
  if(form.dataset.uploading==="true"){notify("Vui lòng đợi tải ảnh hoàn tất.",true);return;}
  for(const field of schemas[collection]){
    const input=form.elements[field.key];if(!input)continue;
    if(field.type==="checkbox")data[field.key]=input.checked;
    else if(field.type==="image")data[field.key]=input.value.trim()||null;
    else if(field.type==="number"){
      if(input.value===""&&(field.key==="percent"||field.key==="discountAmount"))continue;
      data[field.key]=input.value===""?0:Number(input.value);
    }
    else if(field.type==="datetime")data[field.key]=input.value?new Date(input.value).toISOString():null;
    else data[field.key]=input.value.trim();
  }
  if(collection==="promotions"||collection==="rewardVouchers"){
    const hasPercent=Number(data.percent)>0,hasAmount=Number(data.discountAmount)>0;
    if(hasPercent===hasAmount){$("#formError").textContent="Hãy nhập đúng một mức giảm: phần trăm hoặc số tiền cố định.";return;}
    if(hasPercent&&data.percent>100){$("#formError").textContent="Mức giảm phần trăm không được vượt quá 100%.";return;}
    if(!hasPercent)delete data.percent;
    if(!hasAmount)delete data.discountAmount;
  }
  if(collection==="promotions"&&!id&&data.active===undefined)data.active=true;
  try {
    const ref=id?doc(db,collection,id):null;
    let createdId=null;
    if(ref){
      const update={...data,updatedAt:serverTimestamp()};
      if(collection==="promotions"||collection==="rewardVouchers"){
        if(data.percent===undefined)update.percent=deleteField();
        if(data.discountAmount===undefined)update.discountAmount=deleteField();
      }
      await updateDoc(ref,update);
    }
    else {const created=await addDoc(collectionRef(collection),{...data,createdAt:serverTimestamp(),updatedAt:serverTimestamp()});createdId=created.id;}
    $("#recordDialog").close();await refreshData();
    if(collection==="shopEvents"&&!id){
      try {
        const result=await notifyShopEvent(createdId);
        const partial=result.failures>0?` Có ${result.failures} tài khoản chưa nhận đủ thông báo.`:"";
        notify(`Đã thêm sự kiện. Đã tạo ${result.inboxCreated} thông báo trong ứng dụng và gửi ${result.pushSent} thông báo đẩy.${partial}`,result.failures>0);
      } catch(error) {
        notify(`Sự kiện đã được lưu nhưng chưa gửi thông báo: ${error.message||"lỗi kết nối"}.`,true);
      }
    } else notify(id?"Đã lưu thay đổi.":"Đã thêm mục mới.");
  }
  catch(error) { $("#formError").textContent=error.message||"Không lưu được dữ liệu."; }
}
async function notifyShopEvent(eventId){
  if(!eventId)throw new Error("Không lấy được mã sự kiện.");
  const idToken=await auth.currentUser.getIdToken();
  const response=await fetch(`${supabaseConfig.url}/functions/v1/notify-shop-event`,{
    method:"POST",
    headers:{"content-type":"application/json","apikey":supabaseConfig.publishableKey,"authorization":`Bearer ${idToken}`},
    body:JSON.stringify({eventId}),
  });
  const result=await response.json().catch(()=>({}));
  if(!response.ok)throw new Error(result.error||`Không gửi được thông báo (HTTP ${response.status}).`);
  return result;
}
function collectionRef(name){return collection(db,name);}

async function uploadCatalogImageFromUrl(url) {
  if(!url){$("#imageUploadStatus").textContent="Hãy dán liên kết ảnh trước.";return;}
  try {
    const parsed=new URL(url);
    if(!["http:","https:"].includes(parsed.protocol))throw new Error("Liên kết ảnh phải bắt đầu bằng http:// hoặc https://.");
    if(parsed.username||parsed.password)throw new Error("Không dán liên kết có tên người dùng hoặc mật khẩu.");
  } catch(error) { notify(error.message||"Liên kết ảnh không hợp lệ.",true);return; }
  return sendCatalogImageToCloudinary(url);
}

async function sendCatalogImageToCloudinary(url) {
  const form=$("#recordForm"),input=form.elements.imageUrl,status=$("#imageUploadStatus"),preview=$("#imagePreview");
  if(form.dataset.uploading==="true"){status.textContent="Đang xử lý ảnh trước đó. Vui lòng đợi.";return;}
  if(!cloudinaryConfig.cloudName||!cloudinaryConfig.uploadPreset){status.textContent="Thiếu cấu hình Cloudinary.";return;}
  form.dataset.uploading="true";status.textContent="Đang tải ảnh lên Cloudinary…";
  try {
    const body=new FormData();body.append("file",url);body.append("upload_preset",cloudinaryConfig.uploadPreset);
    const response=await fetch(`https://api.cloudinary.com/v1_1/${cloudinaryConfig.cloudName}/image/upload`,{method:"POST",body});
    const result=await response.json();
    if(!response.ok||!result.secure_url)throw new Error(result.error?.message||"Cloudinary từ chối ảnh tải lên.");
    input.value=result.secure_url;preview.src=result.secure_url;preview.hidden=false;status.textContent="Đã lưu ảnh vào Cloudinary. Lưu biểu mẫu để hiển thị ảnh trong ứng dụng.";
  } catch(error) { status.textContent=error.message||"Tải ảnh thất bại."; }
  finally { form.dataset.uploading="false"; }
}
