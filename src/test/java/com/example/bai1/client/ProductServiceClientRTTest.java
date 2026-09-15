package com.example.bai1.client;

import com.example.bai1.dto.ProductInfo;
import com.example.bai1.exception.ProductNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceClientRTTest {

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private ProductServiceClientRT productServiceClientRT;

    @Test
    @DisplayName("1. Test gọi thành công (happy path): trả về thông tin sản phẩm chính xác")
    void testGetById_Success() {
        // Arrange
        Long productId = 100L;
        ProductInfo expectedProduct = new ProductInfo(productId, "Laptop Dell XPS 15", 25000000.0, "Laptop cao cấp");

        when(restTemplate.getForObject(
                eq(ProductServiceClientRT.PRODUCT_SERVICE_URL),
                eq(ProductInfo.class),
                eq(productId)
        )).thenReturn(expectedProduct);

        // Act
        ProductInfo actualProduct = productServiceClientRT.getById(productId);

        // Assert
        assertNotNull(actualProduct, "Kết quả trả về không được null");
        assertEquals(productId, actualProduct.getId());
        assertEquals("Laptop Dell XPS 15", actualProduct.getName());
        assertEquals(25000000.0, actualProduct.getPrice());
        assertEquals("Laptop cao cấp", actualProduct.getDescription());

        verify(restTemplate, times(1)).getForObject(
                eq(ProductServiceClientRT.PRODUCT_SERVICE_URL),
                eq(ProductInfo.class),
                eq(productId)
        );
    }

    @Test
    @DisplayName("2. Test mô phỏng timeout: ResourceAccessException ném ra và trả về fallback")
    void testGetById_TimeoutFallback() {
        // Arrange
        Long productId = 200L;
        when(restTemplate.getForObject(
                eq(ProductServiceClientRT.PRODUCT_SERVICE_URL),
                eq(ProductInfo.class),
                eq(productId)
        )).thenThrow(new ResourceAccessException("I/O error on GET request: Read timed out"));

        // Act
        ProductInfo actualProduct = productServiceClientRT.getById(productId);

        // Assert
        assertNotNull(actualProduct, "Kết quả fallback không được null");
        assertEquals(productId, actualProduct.getId());
        assertEquals("Sản phẩm tạm thời không khả dụng", actualProduct.getName());
        assertEquals(0.0, actualProduct.getPrice());
        assertNotNull(actualProduct.getDescription());

        verify(restTemplate, times(1)).getForObject(
                eq(ProductServiceClientRT.PRODUCT_SERVICE_URL),
                eq(ProductInfo.class),
                eq(productId)
        );
    }

    @Test
    @DisplayName("3. Test xử lý lỗi 404 (Not Found): ném ra ProductNotFoundException")
    void testGetById_NotFoundThrowsException() {
        // Arrange
        Long productId = 404L;
        HttpClientErrorException notFoundException = HttpClientErrorException.create(
                HttpStatus.NOT_FOUND,
                "Not Found",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8
        );

        when(restTemplate.getForObject(
                eq(ProductServiceClientRT.PRODUCT_SERVICE_URL),
                eq(ProductInfo.class),
                eq(productId)
        )).thenThrow(notFoundException);

        // Act & Assert
        ProductNotFoundException thrown = assertThrows(ProductNotFoundException.class, () -> {
            productServiceClientRT.getById(productId);
        });

        assertTrue(thrown.getMessage().contains("404"));
        verify(restTemplate, times(1)).getForObject(
                eq(ProductServiceClientRT.PRODUCT_SERVICE_URL),
                eq(ProductInfo.class),
                eq(productId)
        );
    }
}
