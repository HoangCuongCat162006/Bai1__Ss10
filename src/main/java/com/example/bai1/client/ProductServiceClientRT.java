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

    // URL sử dụng service-id đã đăng ký với Eureka thay vì hardcode IP và port
    public static final String PRODUCT_SERVICE_URL = "http://product-service/api/products/{id}";

    // RestTemplate bean được tiêm vào có @LoadBalanced và cấu hình timeout
    private final RestTemplate restTemplate;

    @Autowired
    public ProductServiceClientRT(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Lấy thông tin sản phẩm theo productId.
     *
     * @param productId ID sản phẩm cần truy vấn
     * @return ProductInfo thông tin sản phẩm hoặc fallback khi bị timeout
     * @throws ProductNotFoundException nếu Product Service trả về HTTP 404 Not Found
     */
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

    /**
     * Phương thức fallback trả về thông tin mặc định khi dịch vụ bị timeout
     *
     * @param productId ID sản phẩm
     * @return ProductInfo fallback
     */
    protected ProductInfo getFallbackProduct(Long productId) {
        ProductInfo fallback = new ProductInfo();
        fallback.setId(productId);
        fallback.setName("Sản phẩm tạm thời không khả dụng");
        fallback.setPrice(0.0);
        fallback.setDescription("Dịch vụ sản phẩm đang gián đoạn hoặc timeout. Vui lòng thử lại sau.");
        return fallback;
    }
}
