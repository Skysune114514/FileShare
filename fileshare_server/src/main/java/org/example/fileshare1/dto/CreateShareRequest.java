package org.example.fileshare1.dto;

// 创建分享请求：nodeId 必须是单文件；ttl 可选 3h/24h/72h；pin 可选。

import lombok.Data;

/**
 * 创建临时分享：仅单文件；ttl 为 {@code 3h}、{@code 24h}（默认）、{@code 72h}；pin 可选。
 */
@Data
public class CreateShareRequest {
    /** fs_node.id，须为当前用户下的 FILE */
    private Long nodeId;
    /** {@code 3h} | {@code 24h} | {@code 72h} */
    private String ttl;
    /** 可选，4～12 位，建议数字；空串视为不设置 */
    private String pin;
}
