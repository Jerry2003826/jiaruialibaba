package com.example.agentdemo.common;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageRequestValidatorTest {

    private final PageRequestValidator validator = new PageRequestValidator();

    @Test
    void buildReturnsPagedRequestWithSort() {
        PageRequest pageRequest = validator.build(1, 20, 100, "PAGE_QUERY_INVALID",
                Sort.by(Sort.Direction.DESC, "createdAt"));

        assertThat(pageRequest.getPageNumber()).isEqualTo(1);
        assertThat(pageRequest.getPageSize()).isEqualTo(20);
        assertThat(pageRequest.getSort().getOrderFor("createdAt")).isNotNull();
    }

    @Test
    void buildRejectsNegativePage() {
        assertThatThrownBy(() -> validator.build(-1, 20, 100, "PAGE_QUERY_INVALID", Sort.unsorted()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PAGE_QUERY_INVALID"));
    }

    @Test
    void buildRejectsOversizedPageSize() {
        assertThatThrownBy(() -> validator.build(0, 101, 100, "PAGE_QUERY_INVALID", Sort.unsorted()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PAGE_QUERY_INVALID"))
                .hasMessage("size must be between 1 and 100");
    }

}
