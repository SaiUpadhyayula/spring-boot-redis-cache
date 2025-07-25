package com.techie.springbootrediscache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techie.springbootrediscache.dto.ProductDto;
import com.techie.springbootrediscache.entity.Product;
import com.techie.springbootrediscache.repository.ProductRepository;
import com.techie.springbootrediscache.service.ProductService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@AutoConfigureMockMvc
class SpringBootRedisCacheApplicationTests {

    @Container
    @ServiceConnection
    static GenericContainer redis = new GenericContainer(DockerImageName.parse("redis:8.2-rc1-alpine3.22"))
            .withExposedPorts(6379);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private CacheManager cacheManager;
    @MockitoSpyBean
    private ProductRepository productRepositorySpy;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        productRepository.deleteAll(); // Ensure a clean database for each test
    }

    @Test
    void testCreateProductAndCacheIt() throws Exception {
        ProductDto productDto = new ProductDto(null, "Laptop", BigDecimal.valueOf(1200L));

        // Step 1: Create a Product
        MvcResult result = mockMvc.perform(post("/api/product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(productDto)))
                .andExpect(status().isCreated())
                .andReturn();

        ProductDto createdProduct = objectMapper.readValue(result.getResponse().getContentAsString(), ProductDto.class);
        Long productId = createdProduct.id();

        // Step 2: Check Product Exists in DB
        Assertions.assertTrue(productRepository.findById(productId).isPresent());

        // Step 3: Check Cache
        Cache cache = cacheManager.getCache(ProductService.PRODUCT_CACHE);
        assertNotNull(cache);
        assertNotNull(cache.get(productId, ProductDto.class));
    }

    @Test
    void testGetProductAndVerifyCache() throws Exception {
        // Step 1: Save product in DB
        Product product = new Product();
        product.setName("Phone");
        product.setPrice(BigDecimal.valueOf(800L));
        productRepository.save(product);

        // Step 2: Fetch product
        mockMvc.perform(MockMvcRequestBuilders.get("/api/product/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Phone"));

        Mockito.verify(productRepositorySpy, Mockito.times(1)).findById(product.getId());

        Mockito.clearInvocations(productRepositorySpy);

        mockMvc.perform(MockMvcRequestBuilders.get("/api/product/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Phone"));

        Mockito.verify(productRepositorySpy, Mockito.times(0)).findById(product.getId());
    }

    @Test
    void testUpdateProductAndVerifyCache() throws Exception {
        // Step 1: Create and Save Product
        Product product = new Product();
        product.setName("Tablet");
        product.setPrice(BigDecimal.valueOf(500L));
        product = productRepository.save(product);

        ProductDto updatedProductDto = new ProductDto(product.getId(), "Updated Tablet", BigDecimal.valueOf(550L));

        // Step 2: Update Product
        mockMvc.perform(MockMvcRequestBuilders.put("/api/product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatedProductDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Tablet"))
                .andExpect(jsonPath("$.price").value(550.0));

        // Step 3: Verify Cache is Updated
        Cache cache = cacheManager.getCache(ProductService.PRODUCT_CACHE);
        assertNotNull(cache);
        ProductDto cachedProduct = cache.get(product.getId(), ProductDto.class);
        assertNotNull(cachedProduct);
        Assertions.assertEquals("Updated Tablet", cachedProduct.name());
    }

    @Test
    void testDeleteProductAndEvictCache() throws Exception {
        // Step 1: Create and Save Product
        Product product = new Product();
        product.setName("Smartwatch");
        product.setPrice(BigDecimal.valueOf(250L));
        product = productRepository.save(product);

        // Step 2: Delete Product
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/product/" + product.getId()))
                .andExpect(status().isNoContent());

        // Step 3: Check that Product is Deleted from DB
        Assertions.assertFalse(productRepository.findById(product.getId()).isPresent());

        // Step 4: Check Cache Eviction
        Cache cache = cacheManager.getCache(ProductService.PRODUCT_CACHE);
        assertNotNull(cache);
        Assertions.assertNull(cache.get(product.getId()));
    }
}
