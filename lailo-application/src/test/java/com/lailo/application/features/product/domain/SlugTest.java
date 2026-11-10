package com.lailo.application.features.product.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Slug generation: readable, URL-safe and unique by construction. */
class SlugTest {

    private static final UUID ID = UUID.fromString("7f3ab912-0000-4000-8000-000000000000");

    /**
     * A name becomes a readable slug ending in the product's id prefix.
     * <ul>
     *   <li><b>Given</b> a name with capitals, punctuation and double spaces</li>
     *   <li><b>When</b> a slug is built for a known id</li>
     *   <li><b>Then</b> it is lower-case, hyphen-separated and suffixed with the first 8 hex characters of the id</li>
     * </ul>
     */
    @Test
    void buildsReadableLowerCaseSlug() {
        assertThat(Slug.from("  Men's Cotton Kurta -- Blue!  ", ID)).isEqualTo("men-s-cotton-kurta-blue-7f3ab912");
    }

    /**
     * Two products with the same name get different slugs.
     * <ul>
     *   <li><b>Given</b> the same name and two different ids</li>
     *   <li><b>When</b> slugs are built</li>
     *   <li><b>Then</b> they differ, so the unique index never rejects a legitimate product</li>
     * </ul>
     */
    @Test
    void sameNameWithDifferentIdsGivesDifferentSlugs() {
        assertThat(Slug.from("Cotton Kurta", UUID.randomUUID()))
                .isNotEqualTo(Slug.from("Cotton Kurta", UUID.randomUUID()));
    }

    /**
     * Names that carry no ASCII letters still yield a usable slug.
     * <ul>
     *   <li><b>Given</b> a name written only in Devanagari</li>
     *   <li><b>When</b> a slug is built</li>
     *   <li><b>Then</b> the slug is the id suffix alone, never empty</li>
     * </ul>
     */
    @Test
    void fallsBackToTheIdSuffixWhenNothingIsTransliterable() {
        assertThat(Slug.from("कुर्ता", ID)).isEqualTo("7f3ab912");
    }

    /**
     * Very long names are truncated.
     * <ul>
     *   <li><b>Given</b> a 300-character name</li>
     *   <li><b>When</b> a slug is built</li>
     *   <li><b>Then</b> it fits the {@code slug} column (at most 89 characters)</li>
     * </ul>
     */
    @Test
    void truncatesLongNames() {
        assertThat(Slug.from("kurta ".repeat(50), ID)).hasSizeLessThanOrEqualTo(89).endsWith("-7f3ab912");
    }
}
