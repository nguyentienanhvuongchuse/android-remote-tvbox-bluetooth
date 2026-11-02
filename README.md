# ATV Remote (Kotlin) — điều khiển MyTV Box qua WiFi khi remote hỏng

App Android này điều khiển set-top-box Android/AOSP (như VNPT MyTV Box) qua
mạng LAN, dùng **Android TV Remote Protocol v2** — cùng giao thức mà app
"Google TV" chính hãng dùng. Không cần ADB, không cần Developer Options,
không cần thao tác gì trực tiếp trên box — chỉ cần box vẫn lên hình HDMI
để bạn đọc mã PIN hiển thị trên màn hình rồi gõ vào điện thoại.

## 1. Kiểm tra trước khi build app (quan trọng)

Không phải box Android nào cũng chạy dịch vụ này (nó là dịch vụ hệ thống
"Android TV Remote Service" của Google, không phải mọi bản AOSP tùy biến
đều có). Kiểm tra nhanh **trước khi mất công build**:

1. Lấy IP của box trong trang quản trị router (danh sách thiết bị/DHCP
   clients), ví dụ `192.168.1.23`.
2. Từ máy tính cùng mạng WiFi, chạy:
   ```
   nmap -p 6466,6467 192.168.1.23
   ```
   hoặc không có nmap thì:
   ```
   telnet 192.168.1.23 6467
   ```
   Nếu port 6466 và 6467 đang mở (`open`/kết nối được) → box hỗ trợ, app
   này sẽ hoạt động được. Nếu bị từ chối/đóng → box không chạy dịch vụ
   này, xem mục "Phương án dự phòng" bên dưới.

## 2. Build & chạy

- Mở thư mục này bằng Android Studio (Giraffe/Koala trở lên).
- Điện thoại và box phải **cùng mạng WiFi/LAN**.
- Chạy app, nhập IP của box → app tự sinh chứng chỉ TLS, kết nối cổng
  6467, và box sẽ hiện **mã 6 ký tự** trên màn hình TV.
- Gõ mã đó vào app → ghép đôi xong, app tự chuyển sang màn hình remote
  (D-pad, Home, Back, Volume, kênh...).

Sau khi ghép đôi lần đầu, chứng chỉ (client cert) được lưu trong
AndroidKeyStore nên lần sau không cần nhập lại PIN — có thể tự thêm màn
hình "kết nối nhanh" dùng lại `RemoteClient` trực tiếp (bỏ qua
`PairingClient`) miễn là bạn lưu lại cert của box (hiện code lưu trong bộ
nhớ, bạn nên serialize `serverCertificate` ra đĩa nếu muốn dùng lại).

## 3. Cấu trúc code

- `proto/ProtoWriter.kt`, `proto/ProtoReader.kt` — bộ mã hoá/giải mã
  protobuf tối giản, tự viết tay (không cần cài `protoc`), đúng theo
  format wire mà giao thức này dùng.
- `net/CertificateManager.kt` — sinh & lưu cặp khoá RSA + chứng chỉ tự ký
  trong AndroidKeyStore.
- `net/PairingClient.kt` — bắt tay ghép đôi qua cổng **6467** (TLS +
  PIN 6 ký tự).
- `net/RemoteClient.kt` — gửi phím điều khiển qua cổng **6466** sau khi
  đã ghép đôi.
- `net/KeyCode.kt` — mã phím (theo `android.view.KeyEvent`, xem thêm
  `RemoteKeyCode` enum đầy đủ trong `remotemessage.proto` của dự án
  [tronikos/androidtvremote2](https://github.com/tronikos/androidtvremote2)
  nếu muốn thêm phím khác).

Nguồn tham khảo giao thức (đã đối chiếu byte-by-byte khi viết code):
- https://github.com/Aymkdn/assistant-freebox-cloud/wiki/Google-TV-(aka-Android-TV)-Remote-Control-(v2)
- https://github.com/tronikos/androidtvremote2

## 4. Lưu ý / rủi ro

- VNPT SmartBox là bản AOSP tuỳ biến riêng, **không chắc 100%** có cài
  "Android TV Remote Service" giống Google TV chuẩn — bước kiểm tra port
  ở mục 1 sẽ cho biết ngay có đáng làm tiếp không.
- Handshake ở `RemoteClient.connect()` dựa trên tài liệu reverse-engineer
  cộng đồng (không phải spec chính thức của Google), một vài phiên bản
  firmware có thể lệch đôi chút — nếu kết nối được nhưng bấm phím không
  ăn, thử bắt gói tin (Wireshark) khi dùng app Google TV thật (nếu có
  điện thoại Android khác) để so sánh và chỉnh `buildClientConfigureMessage()`.
- Không dùng lại code này để truy cập thiết bị không phải của bạn.

## 5. Phương án dự phòng nếu box không hỗ trợ port 6466/6467

- **Chuột/bàn phím USB**: nếu box có cổng USB, cắm chuột USB vào là điều
  khiển được ngay (không cần app) → vào Settings bật
  "Network debugging"/ADB qua mạng, rồi có thể điều khiển qua `adb shell
  input keyevent ...` từ máy tính hoặc script.
- **HDMI-CEC**: nếu TV và box nối HDMI và cả hai hỗ trợ CEC, remote của
  chính cái TV (bấm nút "Home"/menu nguồn) đôi khi điều khiển được cả
  box — không cần app.
- **Remote hồng ngoại vạn năng**: mua remote học lệnh (universal remote)
  rẻ tiền, học lại mã IR — chỉ khả thi nếu remote gốc dùng IR (không phải
  Bluetooth) và bạn cần mượn 1 remote MyTV khác để "học" mã.
