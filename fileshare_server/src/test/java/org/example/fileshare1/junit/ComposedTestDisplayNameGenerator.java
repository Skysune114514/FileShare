package org.example.fileshare1.junit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGenerator;

import java.lang.reflect.Method;
/**
 * 从 {@link UnitTest} / {@link IntegrationTest} 的 {@code value} 生成方法展示名，
 * 避免在元注解上使用空 {@link DisplayName} 触发 JUnit 配置警告。
 */
public class ComposedTestDisplayNameGenerator extends DisplayNameGenerator.Standard {

    @Override
    public String generateDisplayNameForMethod(Class<?> testClass, Method testMethod) {
        UnitTest unitTest = testMethod.getAnnotation(UnitTest.class);
        if (unitTest != null) {
            return unitTest.value();
        }
        IntegrationTest integrationTest = testMethod.getAnnotation(IntegrationTest.class);
        if (integrationTest != null) {
            return integrationTest.value();
        }
        return super.generateDisplayNameForMethod(testClass, testMethod);
    }
}
