package com.lailo.application.features.product.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;

/**
 * Builds the URL segment of a product. A short id suffix is appended so two products may share a name
 * while the slug stays unique and stable: renaming never breaks an existing link.
 */
public final class Slug {

    private static final int MAX_NAME_PART = 80;
    private static final int SUFFIX_LENGTH = 8;

    /** Not instantiable: static helpers only. */
    private Slug() {
    }

    /**
     * Derives a slug from a product name and its id.
     *
     * @param name product name as typed
     * @param id the product id; its first 8 hex characters become the suffix
     * @return a lower-case slug such as {@code cotton-kurta-7f3ab912}
     */
    public static String from(String name, UUID id) {
        String base = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFKD)
                .replaceAll("[^\\p{ASCII}]", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.length() > MAX_NAME_PART) {
            base = base.substring(0, MAX_NAME_PART).replaceAll("-$", "");
        }
        String suffix = id.toString().replace("-", "").substring(0, SUFFIX_LENGTH);
        return base.isEmpty() ? suffix : base + "-" + suffix;
    }
}
