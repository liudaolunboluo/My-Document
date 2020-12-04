package com.thunisoft.zgfy.dwjk.utils;

import java.util.Set;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;

import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.HibernateValidatorConfiguration;

/**
 * 校验bean
 *
 * @author GaoZhilai
 * @date 2020/4/16 10:56
 */
public class ValidateUtils {

    private static Validator validator;

    static {
        HibernateValidatorConfiguration configure = Validation.byProvider(HibernateValidator.class).configure();
        ValidatorFactory validatorFactory = configure.failFast(false).buildValidatorFactory();
        // 根据validatorFactory拿到一个Validator
        validator = validatorFactory.getValidator();
    }

    /**
     * 校验指定的bean包含的参数是否合法, 返回第一个校验失败的字段信息
     * 
     * @param bean 要校验的对象
     * @param <T> 要校验的bean类型
     * @return 第一个校验失败的字段信息
     */
    public static <T> String validate(T bean) {
        Set<ConstraintViolation<T>> result = validator.validate(bean);
        StringBuilder sb = new StringBuilder();
        // 对结果进行遍历输出
        result.stream().forEach(
            v -> sb.append(v.getPropertyPath()).append(v.getMessage() + "; "));
        return sb.toString();
    }

    private ValidateUtils() {

    }
}
