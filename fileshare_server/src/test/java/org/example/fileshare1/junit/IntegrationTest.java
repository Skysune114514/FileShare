package org.example.fileshare1.junit;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 集成测试方法标记：合并 {@link Test} 与展示名（经 {@link ComposedTestDisplayNameGenerator}）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Test
public @interface IntegrationTest {

    String value();
}
