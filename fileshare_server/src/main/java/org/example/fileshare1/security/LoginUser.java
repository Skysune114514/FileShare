package org.example.fileshare1.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 自定义注解：标记 Controller 参数 = “当前登录用户 id”。
 *
 * 用法示例：public void upload(@LoginUser long userId, ...)
 * 它本身没有任何逻辑，只是一个“便签”。
 * Spring MVC 看到参数上有这个便签时，会调用 LoginUserArgumentResolver 去取真实值。
 */
// 注解只能用在“方法参数”上。
@Target(ElementType.PARAMETER)
// 运行时保留：Spring 在运行期通过反射检查注解，所以必须 RUNTIME 而不是 SOURCE。
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginUser {
}
