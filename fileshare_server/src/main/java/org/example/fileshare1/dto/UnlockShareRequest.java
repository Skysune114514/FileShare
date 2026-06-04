package org.example.fileshare1.dto;

// PIN 解锁请求：访客提交的 pin 字符串。

import lombok.Data;

@Data
public class UnlockShareRequest {
    private String pin;
}
