package com.lailo.application.features.product.application.query;

import com.lailo.application.features.product.infrastructure.readmodel.ProductCatalogRepository;
import com.lailo.application.features.product.infrastructure.readmodel.ProductDocument;
import com.lailo.application.support.IntegrationTest;
import com.lailo.application.support.ProductFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Browse and search against the real MongoDB catalogue: filters, ordering and paging. */
class SearchProductsHandlerIT extends IntegrationTest {

    private static final Instant T = Instant.parse("2026-11-08T10:00:00Z");

    @Autowired SearchProductsHandler handler;
    @Autowired ProductCatalogRepository catalog;

    private String category;

    /** Seeds one isolated category: two active products, one sold out and one archived. */
    @BeforeEach
    void seedCatalogue() {
        category = ProductFixtures.uniqueCategory();
        save("Cotton Kurta", "Rangoli", "499.00", 5, "ACTIVE", T);
        save("Silk Saree", "Rangoli", "4999.00", 2, "ACTIVE", T.plusSeconds(60));
        save("Wool Shawl", "Himalaya", "1999.00", 0, "ACTIVE", T.plusSeconds(120));
        save("Linen Shirt", "Rangoli", "1299.00", 9, "ARCHIVED", T.plusSeconds(180));
    }

    /**
     * Inserts one catalogue document in the test's category.
     *
     * @param name display name
     * @param brand brand name
     * @param price price in rupees
     * @param stock units available
     * @param status {@code ACTIVE} or {@code ARCHIVED}
     * @param createdAt catalogue entry time, which drives the {@code NEWEST} order
     */
    private void save(String name, String brand, String price, int stock, String status, Instant createdAt) {
        String id = UUID.randomUUID().toString();
        catalog.save(ProductDocument.builder().id(id).slug(name.toLowerCase().replace(' ', '-') + "-" + id.substring(0, 8))
                .name(name).description(name + " in fine " + brand + " weave").brand(brand).category(category)
                .price(new BigDecimal(price)).currency("INR").stockQuantity(stock).status(status)
                .createdAt(createdAt).updatedAt(createdAt).build()).block();
    }

    /**
     * Browsing a category returns only its active products, newest first.
     * <ul>
     *   <li><b>Given</b> three active and one archived product in a category</li>
     *   <li><b>When</b> the category is browsed with the default ordering</li>
     *   <li><b>Then</b> three products come back newest first and the archived one is absent</li>
     * </ul>
     */
    @Test
    void browsesActiveProductsNewestFirst() {
        ProductPage page = handler.handle(SearchProductsQuery.builder().category(category).build()).block();

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.hasNext()).isFalse();
        assertThat(page.items()).extracting(ProductView::name)
                .containsExactly("Wool Shawl", "Silk Saree", "Cotton Kurta");
    }

    /**
     * Free text matches the brand as well as the name.
     * <ul>
     *   <li><b>Given</b> two active products of the brand "Rangoli"</li>
     *   <li><b>When</b> the text "rangoli" is searched inside the category</li>
     *   <li><b>Then</b> both are returned, matched case-insensitively</li>
     * </ul>
     */
    @Test
    void matchesFreeTextAgainstBrandCaseInsensitively() {
        ProductPage page = handler.handle(
                SearchProductsQuery.builder().text("rangoli").category(category).build()).block();

        assertThat(page.items()).extracting(ProductView::name).containsExactlyInAnyOrder("Cotton Kurta", "Silk Saree");
    }

    /**
     * Regex characters typed by a user are matched literally.
     * <ul>
     *   <li><b>Given</b> a catalogue with no product containing ".*"</li>
     *   <li><b>When</b> the text ".*" is searched</li>
     *   <li><b>Then</b> nothing matches, i.e. the input was quoted rather than executed as a pattern</li>
     * </ul>
     */
    @Test
    void quotesRegexMetacharactersInSearchText() {
        ProductPage page = handler.handle(
                SearchProductsQuery.builder().text(".*").category(category).build()).block();

        assertThat(page.total()).isZero();
        assertThat(page.items()).isEmpty();
    }

    /**
     * The price filter is numeric and combines with the stock filter.
     * <ul>
     *   <li><b>Given</b> products at 499, 1999 (sold out) and 4999 rupees</li>
     *   <li><b>When</b> products between 400 and 2000 that are in stock are searched</li>
     *   <li><b>Then</b> only the 499 product comes back; the sold-out 1999 one is filtered out</li>
     * </ul>
     */
    @Test
    void filtersByPriceRangeAndStock() {
        ProductPage page = handler.handle(SearchProductsQuery.builder()
                .category(category)
                .minPrice(new BigDecimal("400"))
                .maxPrice(new BigDecimal("2000"))
                .inStockOnly(true)
                .build()).block();

        assertThat(page.items()).extracting(ProductView::name, ProductView::stockQuantity)
                .containsExactly(tuple("Cotton Kurta", 5));
    }

    /**
     * Price ordering is numeric, not lexicographic.
     * <ul>
     *   <li><b>Given</b> prices 499, 1999 and 4999</li>
     *   <li><b>When</b> the category is sorted by ascending price</li>
     *   <li><b>Then</b> they come back 499, 1999, 4999</li>
     * </ul>
     */
    @Test
    void sortsByPriceAscending() {
        ProductPage page = handler.handle(SearchProductsQuery.builder()
                .category(category)
                .sort(ProductSort.PRICE_ASC)
                .build()).block();

        assertThat(page.items()).extracting(ProductView::price)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("499.00"), new BigDecimal("1999.00"), new BigDecimal("4999.00"));
    }

    /**
     * Paging reports the full total and whether more pages follow.
     * <ul>
     *   <li><b>Given</b> three active products</li>
     *   <li><b>When</b> page 0 and page 2 are read with size 1, sorted by price</li>
     *   <li><b>Then</b> each page holds one item, the total stays 3, and only the last page reports no next page</li>
     * </ul>
     */
    @Test
    void pagesThroughResults() {
        ProductPage first = handler.handle(SearchProductsQuery.builder()
                .category(category).sort(ProductSort.PRICE_ASC).page(0).size(1).build()).block();
        ProductPage last = handler.handle(SearchProductsQuery.builder()
                .category(category).sort(ProductSort.PRICE_ASC).page(2).size(1).build()).block();

        assertThat(first.total()).isEqualTo(3);
        assertThat(first.hasNext()).isTrue();
        assertThat(first.items()).extracting(ProductView::name).containsExactly("Cotton Kurta");
        assertThat(last.hasNext()).isFalse();
        assertThat(last.items()).extracting(ProductView::name).containsExactly("Silk Saree");
    }

    /**
     * An oversized page request is clamped instead of rejected.
     * <ul>
     *   <li><b>Given</b> a request for page -5 with size 5000</li>
     *   <li><b>When</b> the query is built and handled</li>
     *   <li><b>Then</b> page 0 and the maximum size are used</li>
     * </ul>
     */
    @Test
    void clampsPagingParameters() {
        SearchProductsQuery query = SearchProductsQuery.builder()
                .category(category).page(-5).size(5000).build();

        ProductPage page = handler.handle(query).block();

        assertThat(query.page()).isZero();
        assertThat(query.size()).isEqualTo(SearchProductsQuery.MAX_SIZE);
        assertThat(page.size()).isEqualTo(SearchProductsQuery.MAX_SIZE);
    }
}
