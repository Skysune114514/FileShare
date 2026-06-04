package org.example.fileshare1.dto;

// 通用分页结构：content=本页数据，totalElements=总条数，page 从 1 开始。

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 简单分页包装（页码从 1 开始）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {
    private List<T> content;
    private long totalElements;
    private int totalPages;
    private int page;
    private int size;
}
