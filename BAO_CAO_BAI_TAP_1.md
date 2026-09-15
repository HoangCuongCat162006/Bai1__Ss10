# BÁO CÁO BÀI TẬP 1: SỬA LỖI HARDCODE URL TRONG RESTTEMPLATE CỦA ORDER-SERVICE
**Hệ thống:** VietMart Microservices  
**Dịch vụ:** Order Service  
**Cấp độ:** Vận dụng cơ bản  

---

## 1. TỔNG QUAN BỐI CẢNH & HIỆN TRẠNG HỆ THỐNG
Trong kiến trúc Microservices của nền tảng thương mại điện tử **VietMart**, `Order Service` cần gọi sang `Product Service` để lấy thông tin tên và giá sản phẩm phục vụ màn hình chi tiết đơn hàng. Hệ thống sử dụng **Netflix Eureka** làm Service Discovery để quản lý động các service instances.

Một thực tập sinh đã cài đặt lớp `ProductServiceClientRT` trong `order-service` nhưng mắc phải 3 lỗi cơ bản khiến code chỉ chạy trên máy dev cá nhân và lập tức gây lỗi khi deploy lên môi trường Staging/Production.

---

## 2. PHÂN TÍCH CHI TIẾT 3 LỖI KỸ THUẬT & HẬU QUẢ PRODUCTION

### LỖI 1: Khởi tạo trực tiếp RestTemplate không dùng `@LoadBalanced`
- **Đoạn code lỗi:**
  ```java
  private final RestTemplate restTemplate = new RestTemplate();
  ```
- **Nguyên nhân:**
  - Khởi tạo thủ công đối tượng bằng từ khóa `new`, vi phạm nguyên lý Inversion of Control (IoC) & Dependency Injection (DI) của Spring Framework.
  - Không gắn chú thích `@LoadBalanced`, khiến Spring Cloud không thể đính kèm bộ đánh chặn cân bằng tải `LoadBalancerInterceptor`.
- **Hậu quả trong môi trường Production có nhiều instance:**
  1. **Không phân giải được Service Name (Logical Service ID):** Khi gọi URL logic dạng `http://product-service/...`, RestTemplate mặc định sẽ xem `product-service` là DNS hostname internet/LAN thông thường và ném ra ngoại lệ `UnknownHostException`.
  2. **Vô hiệu hóa hoàn toàn Eureka Service Discovery:** Không thể tra cứu danh bạ IP/Port động của các instances đang online từ Eureka Server.
  3. **Không có Client-Side Load Balancing:** Khi Product Service scale ra nhiều node (ví dụ 3 đến 5 instances), Order Service không thể phân tải (Round Robin, Weighted...), dẫn đến mất cân bằng tải hoặc quá tải cục bộ.

---

### LỖI 2: Hardcode địa chỉ IP và Port vật lý (`http://192.168.1.45:8082`)
- **Đoạn code lỗi:**
  ```java
  String url = "http://192.168.1.45:8082/api/products/{id}";
  ```
- **Nguyên nhân:**
  - Gắn chặt (tight coupling) logic nghiệp vụ vào một địa chỉ IP cố định của môi trường local development.
- **Hậu quả trong môi trường Production có nhiều instance:**
  1. **Lỗi kết nối tức thì khi đổi môi trường:** Trong Staging/Production và môi trường Container/Kubernetes/Cloud, các Pods/VMs được cấp phát IP động và thay đổi thường xuyên sau mỗi lần restart/deploy. Gọi vào IP `192.168.1.45` sẽ sinh lỗi `ConnectException: Connection refused`.
  2. **Single Point of Failure (Điểm lỗi đơn lẻ):** Toàn bộ request từ Order Service dồn vào đúng 1 node duy nhất, nếu node này gặp sự cố thì tính năng xem đơn hàng sập toàn bộ.

---

### LỖI 3: Không cấu hình Timeout (`connectTimeout` & `readTimeout`)
- **Đoạn code lỗi:**
  ```java
  return restTemplate.getForObject(url, ProductInfo.class, productId);
  ```
- **Nguyên nhân:**
  - Mặc định trong Java và Spring `RestTemplate`, timeout là vô hạn (`-1`) hoặc phụ thuộc vào TCP stack của OS (kéo dài từ vài phút đến nửa tiếng).
- **Hậu quả trong môi trường Production có nhiều instance:**
  1. **Thread Starvation (Cạn kiệt Thread Pool):** Khi `product-service` bị nghẽn mạng, lock DB hoặc treo ứng dụng, các Tomcat worker threads của `order-service` sẽ bị block vô thời hạn để đợi response.
  2. **Cascading Failure (Sụp đổ dây chuyền):** Giới hạn mặc định của Tomcat là 200 threads. Khi hết threads, tất cả API khác của `order-service` (như tạo đơn, xem giỏ hàng) đều bị treo, kéo sập toàn bộ `order-service`.
  3. **Trải nghiệm người dùng tệ:** Khách hàng thấy màn hình loading quay vô tận và nhận mã lỗi 504 Gateway Timeout sau nhiều phút chờ đợi.

---

## 3. GIẢI PHÁP ĐÃ TRIỂN KHAI & MÃ NGUỒN HOÀN CHỈNH

### 1. Cấu hình Bean RestTemplate có `@LoadBalanced` và Timeout (`RestTemplateConfig.java`)
- Tạo bean `@LoadBalanced RestTemplate`.
- Cấu hình qua `SimpleClientHttpRequestFactory`:
  - `connectTimeout`: 2 giây (2000ms).
  - `readTimeout`: 3 giây (3000ms).

```java
package com.example.bai1.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    @Bean
    @LoadBalanced
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        return new RestTemplate(factory);
    }
}
```

### 2. Viết lại `ProductServiceClientRT.java` chuẩn Microservices
- Tiêm bean `RestTemplate` qua Constructor Injection.
- Sử dụng logical service URL: `http://product-service/api/products/{id}`.
- Phân biệt xử lý ngoại lệ:
  - `HttpClientErrorException.NotFound` (404) $\rightarrow$ Ném ngoại lệ nghiệp vụ `ProductNotFoundException`.
  - `ResourceAccessException` (timeout/mạng) $\rightarrow$ Bắt lỗi và trả về dữ liệu **Fallback** an toàn.

```java
package com.example.bai1.client;

import com.example.bai1.dto.ProductInfo;
import com.example.bai1.exception.ProductNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@Component
public class ProductServiceClientRT {

    private static final Logger log = LoggerFactory.getLogger(ProductServiceClientRT.class);
    public static final String PRODUCT_SERVICE_URL = "http://product-service/api/products/{id}";

    private final RestTemplate restTemplate;

    @Autowired
    public ProductServiceClientRT(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public ProductInfo getById(Long productId) {
        try {
            return restTemplate.getForObject(PRODUCT_SERVICE_URL, ProductInfo.class, productId);
        } catch (HttpClientErrorException.NotFound ex) {
            log.error("Product with id {} not found (HTTP 404): {}", productId, ex.getMessage());
            throw new ProductNotFoundException(productId);
        } catch (ResourceAccessException ex) {
            log.warn("Timeout or network error calling product-service for productId {}: {}. Returning fallback.",
                    productId, ex.getMessage());
            return getFallbackProduct(productId);
        }
    }

    protected ProductInfo getFallbackProduct(Long productId) {
        ProductInfo fallback = new ProductInfo();
        fallback.setId(productId);
        fallback.setName("Sản phẩm tạm thời không khả dụng");
        fallback.setPrice(0.0);
        fallback.setDescription("Dịch vụ sản phẩm đang gián đoạn hoặc timeout. Vui lòng thử lại sau.");
        return fallback;
    }
}
```

---

## 4. KẾT QUẢ KIỂM THỬ (UNIT TESTS)

Bộ kiểm thử được viết trong `ProductServiceClientRTTest.java` sử dụng **JUnit 5 + Mockito**:
1. **Happy Path:** Gọi API thành công, kiểm tra dữ liệu `ProductInfo` trả về chính xác.
2. **Timeout Fallback:** Mô phỏng `ResourceAccessException`, kiểm tra trả về dữ liệu fallback mà không làm sập ứng dụng.
3. **HTTP 404 Not Found:** Mô phỏng mã lỗi 404, kiểm tra ngoại lệ `ProductNotFoundException` được ném ra chính xác.

### Báo cáo kết quả chạy Test qua Gradle:
```
ProductServiceClientRTTest > 1. Test gọi thành công (happy path): trả về thông tin sản phẩm chính xác PASSED
ProductServiceClientRTTest > 3. Test xử lý lỗi 404 (Not Found): ném ra ProductNotFoundException PASSED
ProductServiceClientRTTest > 2. Test mô phỏng timeout: ResourceAccessException ném ra và trả về fallback PASSED
Bai1ApplicationTests > contextLoads() PASSED

BUILD SUCCESSFUL in 18s
4 actionable tasks: 4 executed
```
- **Tổng số tests:** 4 / 4 passed (100%)
- **Tỷ lệ thành công:** 100%

---

## 5. DANH MỤC CÁC FILE NỘP BÀI
- **Mã nguồn đã sửa:** `src/main/java/com/example/bai1/client/ProductServiceClientRT.java`
- **Cấu hình RestTemplate:** `src/main/java/com/example/bai1/config/RestTemplateConfig.java`
- **DTO Sản phẩm:** `src/main/java/com/example/bai1/dto/ProductInfo.java`
- **Custom Exception:** `src/main/java/com/example/bai1/exception/ProductNotFoundException.java`
- **Unit Test:** `src/test/java/com/example/bai1/client/ProductServiceClientRTTest.java`
- **File Báo Cáo PDF:** `BAO_CAO_BAI_TAP_1.pdf` (tạo tự động chuẩn in ấn A4)
- **File Báo Cáo HTML & Markdown:** `BAO_CAO_BAI_TAP_1.html`, `BAO_CAO_BAI_TAP_1.md`
